package com.abrah.nightmare.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * ⭐ Ported unchanged from DreamUI (`../LocalDream/dreamui`, 0.91), where the
 * renderer was proven bit-exact against `render_openpose.py`. Nightmare uses it
 * for SD 1.5 Swap's openpose ControlNet ([com.abrah.nightmare.SwapInputs]).
 *
 * A photo in, an OpenPose skeleton out: **MoveNet MultiPose Lightning** on the
 * **CPU** via ORT, then [OpenPoseRender].
 *
 * Same shape as [com.abrah.nightmare.segment.SegmentModel] -- ONNX, in-process, no NPU (ORT's QNN EP cannot
 * create an HTP device on this SoC, `../LocalDream/docs/AUTOMASK.md` §12) -- and the same
 * reason it is fine: the model is 19 MB and runs in ~30 ms on the PC's CPU.
 *
 * Why this exists: the pose ControlNet takes an OpenPose **skeleton** as its
 * hint. Feeding it a photo is not an error, it is a *meaningless* hint -- so
 * without this the model was only usable with a skeleton made on a PC.
 *
 * ⚠ This is the **photo** path only. A picture that already is a skeleton is
 * used as it is ([PoseDetector.looksLikeSkeleton] decides first): a detector
 * finds people, and a drawing of a skeleton contains none.
 *
 * ### The mapping is exact, not an approximation
 *
 * MoveNet emits COCO-17. OpenPose wants COCO-18: the **same** seventeen joints
 * plus a neck, which OpenPose itself defines as the midpoint of the shoulders.
 * DWPose does the same. Validated on the PC against `controlnet_aux`'s own
 * keypoints over 10 references and 147 joint observations: **median 0.0107**,
 * p90 0.0337, where poses only separate at the ~0.1 level -- an order of
 * magnitude below what the ControlNet resolves (`../LocalDream/docs/CONTROLNET.md`).
 *
 * ⚠⚠ **Ship the 19 MB float export, never the 5.3 MB int8 one.** The int8 model
 * loads and runs without error and returns **zero detections on every photo**,
 * while being *slower* than float (54 vs 30 ms). A silent all-empty result, not
 * a crash -- see [PoseDetector].
 */
class PoseModel private constructor(
    private val session: OrtSession,
    private val env: OrtEnvironment,
) : AutoCloseable {

    /**
     * Detects every figure in [image] and returns their COCO-18 keypoints,
     * normalised to `[0, 1]` over [image].
     *
     * Blocking (~50-100 ms on a phone CPU) -- call it off the main thread.
     */
    fun detect(
        image: Bitmap,
        kpThreshold: Float = KP_THRESHOLD,
        instThreshold: Float = INST_THRESHOLD,
    ): List<OpenPoseRender.Pose> {
        val (tensorBuf, box) = letterbox(image)
        val shape = longArrayOf(1, DIM.toLong(), DIM.toLong(), 3)

        val raw = OnnxTensor.createTensor(env, tensorBuf, shape).use { input ->
            session.run(mapOf(session.inputNames.first() to input)).use { result ->
                // [1, 6, 56]: six instance slots, each 17 keypoints of
                // (y, x, score) then a bounding box and its score.
                val out = result.get(0) as OnnxTensor
                FloatArray(SLOTS * STRIDE).also { out.floatBuffer.get(it) }
            }
        }

        val people = ArrayList<OpenPoseRender.Pose>(SLOTS)
        for (slot in 0 until SLOTS) {
            val base = slot * STRIDE
            if (raw[base + STRIDE - 1] < instThreshold) continue

            // COCO-17 in MoveNet's own order, before the OpenPose remap. NaN
            // marks "below threshold", which is not the same as (0, 0).
            val cx = FloatArray(COCO17) { Float.NaN }
            val cy = FloatArray(COCO17) { Float.NaN }
            for (i in 0 until COCO17) {
                if (raw[base + i * 3 + 2] < kpThreshold) continue
                val y = raw[base + i * 3]
                val x = raw[base + i * 3 + 1]
                // padded-frame normalised -> padded pixels -> original pixels
                cx[i] = ((x * DIM - box.dx) / box.scale) / image.width
                cy[i] = ((y * DIM - box.dy) / box.scale) / image.height
            }

            val pose = OpenPoseRender.Pose()
            for (op in 0 until OpenPoseRender.JOINTS) {
                val coco = OPENPOSE_FROM_COCO[op]
                if (coco < 0) continue
                if (cx[coco].isNaN()) continue
                pose.set(op, cx[coco], cy[coco])
            }
            // OpenPose's neck IS the shoulder midpoint; it has no detector of
            // its own, in MoveNet or anywhere else.
            if (!cx[L_SHOULDER].isNaN() && !cx[R_SHOULDER].isNaN()) {
                pose.set(
                    NECK,
                    (cx[L_SHOULDER] + cx[R_SHOULDER]) / 2f,
                    (cy[L_SHOULDER] + cy[R_SHOULDER]) / 2f,
                )
            }
            if (pose.count > 0) people.add(pose)
        }
        return people
    }

    /** Where a letterboxed image sits inside the square frame. */
    private class Box(val scale: Float, val dx: Float, val dy: Float)

    /**
     * Resizes preserving aspect into a [DIM] x [DIM] int32 frame, padded black.
     *
     * ⚠ Not a plain scale. MoveNet's coordinates are relative to the padded
     * frame, so stretching a non-square photo to the square input skews every
     * joint -- the pose still looks plausible, which is the failure mode that
     * takes longest to spot.
     */
    private fun letterbox(image: Bitmap): Pair<java.nio.IntBuffer, Box> {
        val scale = DIM.toFloat() / maxOf(image.width, image.height)
        val nw = (image.width * scale).roundToInt().coerceAtLeast(1)
        val nh = (image.height * scale).roundToInt().coerceAtLeast(1)
        val dx = (DIM - nw) / 2
        val dy = (DIM - nh) / 2

        val frame = Bitmap.createBitmap(DIM, DIM, Bitmap.Config.ARGB_8888)
        Canvas(frame).apply {
            drawColor(Color.BLACK)
            val scaled = Bitmap.createScaledBitmap(image, nw, nh, true)
            drawBitmap(scaled, dx.toFloat(), dy.toFloat(), null)
            if (scaled !== image) scaled.recycle()
        }

        val n = DIM * DIM
        val px = IntArray(n)
        frame.getPixels(px, 0, DIM, 0, 0, DIM, DIM)
        frame.recycle()

        // NHWC int32, one element per channel -- the export takes raw 0..255
        // values as int32, not a normalised float.
        val buf = ByteBuffer.allocateDirect(4 * n * 3)
            .order(ByteOrder.nativeOrder())
            .asIntBuffer()
        for (i in 0 until n) {
            val p = px[i]
            buf.put((p shr 16) and 0xFF)
            buf.put((p shr 8) and 0xFF)
            buf.put(p and 0xFF)
        }
        buf.rewind()
        return buf to Box(scale, dx.toFloat(), dy.toFloat())
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "PoseModel"

        /**
         * Input edge. Dynamic in the graph but **must be a multiple of 32**;
         * 256 is what the PC validation measured, so it is what ships.
         */
        const val DIM = 256

        /** Instance slots the graph always emits, detected or not. */
        private const val SLOTS = 6

        /** Floats per slot: 17 x (y, x, score) + bbox(4) + score. */
        private const val STRIDE = 56

        private const val COCO17 = 17

        private const val KP_THRESHOLD = 0.2f
        private const val INST_THRESHOLD = 0.2f

        // MoveNet's COCO-17 order.
        private const val R_SHOULDER = 6
        private const val L_SHOULDER = 5

        /** Index of the neck in COCO-18; synthesised, never detected. */
        private const val NECK = 1

        /**
         * COCO-18 joint (OpenPose order) -> MoveNet's COCO-17 index.
         *
         * -1 is the neck, which is the shoulder midpoint and is filled in
         * separately. Every other joint is the same joint under another name --
         * nothing here is an approximation.
         */
        private val OPENPOSE_FROM_COCO = intArrayOf(
            0,   // nose
            -1,  // neck (synthesised)
            6,   // r_shoulder
            8,   // r_elbow
            10,  // r_wrist
            5,   // l_shoulder
            7,   // l_elbow
            9,   // l_wrist
            12,  // r_hip
            14,  // r_knee
            16,  // r_ankle
            11,  // l_hip
            13,  // l_knee
            15,  // l_ankle
            2,   // r_eye
            1,   // l_eye
            4,   // r_ear
            3,   // l_ear
        )

        /**
         * Threads. Four, matching [com.abrah.nightmare.segment.SegmentModel]: letting ORT pick
         * oversubscribes a big.LITTLE phone and gets slower, not faster.
         */
        private const val THREADS = 4

        /** Opens the graph, or null if it isn't installed. Blocking. */
        fun open(context: Context): PoseModel? {
            if (!PoseDetector.isInstalled(context)) return null
            return try {
                val env = OrtEnvironment.getEnvironment(
                    OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-pose",
                )
                val session = env.createSession(
                    PoseDetector.file(context).absolutePath,
                    OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
                )
                PoseModel(session, env)
            } catch (t: Throwable) {
                Log.e(TAG, "failed to open pose detector", t)
                null
            }
        }

        /**
         * Photo in, keypoints out -- **not** a finished hint.
         *
         * The keypoints are what the editor keeps, because a skeleton the user
         * can move and resize has to be re-rendered from joints; a rendered
         * bitmap can only be stretched, which would thicken the limbs away from
         * the convention the ControlNet was trained on.
         *
         * ⚠ [square] must already be the square hint-sized crop -- the caller
         * makes it (the node's crop, `SwapInputs.frame`).
         */
        fun detectIn(model: PoseModel, square: Bitmap): List<OpenPoseRender.Pose> {
            val started = System.currentTimeMillis()
            val people = model.detect(square)
            // Logged because "no one found" and "the model returned nothing at
            // all" look identical from the UI, and the int8 export failed in
            // exactly that silent way -- the joint count is what tells them
            // apart in a bug report.
            Log.i(
                TAG,
                "pose: ${people.size} figure(s), joints=${people.map { it.count }} " +
                    "in ${System.currentTimeMillis() - started} ms",
            )
            return people
        }
    }
}
