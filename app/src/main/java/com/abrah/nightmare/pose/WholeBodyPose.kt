package com.abrah.nightmare.pose

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerCatalog
import com.abrah.nightmare.segment.IdleRelease
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer

/**
 * ⭐⭐ **Whole-body pose: DWPose** (RTMPose-L wholebody, 384x288, yzd-v/DWPose, Apache-2.0): 133 points a person,
 * body, feet, both hands and 68 face points, the skeleton Wan 2.2 Fun Control was trained on (VideoX-Fun draws
 * DWPose). Motion transfer's Pose mode reads it ([com.abrah.nightmare.npu.MotionTrack]); MoveNet
 * ([PoseDetector]) still finds the person's box, which DWPose then reads (the user's call, 2026-10-27: "B").
 *
 * ⚠ The FOURTH Tools download, shaped like [PoseDetector]: one `.onnx` on the CPU through ORT, a revision stamp,
 * released when idle. Linked, not re-hosted. 51 ms a frame on the PC's CPU (4 threads).
 */
object WholeBodyPose {

    private const val TAG = "WholeBodyPose"

    const val ID = "dwpose"
    const val LABEL = "Whole-body pose (DWPose)"

    /** ⚠ Bumped whenever the FILE changes ([PoseDetector.REVISION]'s rule). */
    const val REVISION = 1

    const val FILE = "dw-ll_ucoco_384.onnx"
    const val URL = "https://huggingface.co/yzd-v/DWPose/resolve/main/dw-ll_ucoco_384.onnx"

    /** ⚠ Exact: an upstream change fails loudly. */
    const val BYTES = 134_399_116L

    fun dir(context: Context): File = File(BackendProcess.modelsDir(context), ID)

    internal fun file(context: Context) = File(dir(context), FILE)

    private fun stamp(context: Context) = File(dir(context), ".rev")

    private fun installedRevision(context: Context): Int =
        runCatching { stamp(context).readText().trim().toInt() }.getOrDefault(0)

    /** Present AND current. */
    fun isInstalled(context: Context): Boolean =
        file(context).length() == BYTES && installedRevision(context) >= REVISION

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** ⭐ The cheap answer for a preview. Refreshed by [install], [delete] and app start. */
    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    /** Fetch it. ⚠ Blocking: off the main thread. */
    fun install(
        context: Context,
        onProgress: (ModelInstaller.Progress) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val dir = dir(context).apply { mkdirs() }
        val part = File(dir, "$FILE.part")
        UpscalerCatalog.download(URL, part, BYTES, onProgress, isCancelled)
        if (part.length() != BYTES) {
            val got = part.length()
            part.delete()
            throw IOException("$LABEL: downloaded $got bytes, expected $BYTES from $URL")
        }
        val target = file(context)
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        stamp(context).writeText(REVISION.toString())
        refresh(context)
        Log.i(TAG, "installed $LABEL revision $REVISION (${bytesOnDisk(context)} bytes)")
    }

    fun delete(context: Context) {
        close()
        dir(context).deleteRecursively()
        refresh(context)
    }

    // ---- the open model ---------------------------------------------------

    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession? {
        session?.let { return it }
        if (!isInstalled(context)) return null
        return try {
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-dwpose")
            env.createSession(file(context).absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) })
                .also { session = it }
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open $LABEL", t)
            null
        }
    }

    @Synchronized
    private fun releaseModel() {
        if (session == null) return
        runCatching { session?.close() }
        session = null
        Log.i(TAG, "model released (idle or low memory)")
    }

    private val idle = IdleRelease(IdleRelease.IDLE_MS) { releaseModel() }

    /** ⭐ Let the model go now if nothing is using it: low memory. */
    fun trim() = idle.releaseNow()

    @Synchronized
    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /**
     * ⭐⭐ The 133 points of the person in [box] (x0, y0, x1, y1 px of [frame]), as (x, y, score) in [frame]'s
     * pixels, or null when the model is not installed. The score is DWPose's raw SimCC maximum: a point under
     * [OpenPoseRender.WHOLE_BODY_THRESHOLD] is not drawn, as `controlnet_aux` does. ⚠ Blocking.
     */
    fun points(context: Context, frame: Bitmap, box: FloatArray): FloatArray? {
        idle.begin()
        try {
            val s = session(context) ?: return null
            val crop = Crop.of(box, frame.width, frame.height)
            val input = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), tensorOf(frame, crop), longArrayOf(1, 3, IN_H.toLong(), IN_W.toLong()),
            )
            return input.use {
                s.run(mapOf(s.inputNames.first() to it)).use { r ->
                    val sx = (r.get(0) as OnnxTensor).floatBuffer
                    val sy = (r.get(1) as OnnxTensor).floatBuffer
                    decode(sx, sy, crop)
                }
            }
        } finally {
            idle.end()
        }
    }

    /** ⭐ DWPose's input: 288 wide, 384 tall. */
    const val IN_W = 288
    const val IN_H = 384

    /** ⚠ SimCC bins per pixel (`simcc_split_ratio` 2): 576 x bins, 768 y bins. */
    private const val SPLIT = 2
    const val POINTS = 133

    private const val THREADS = 4

    /** ⭐ ImageNet mean and std on 0..255 RGB, as DWPose's `onnxpose.py`. */
    private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
    private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)

    /**
     * ⭐ The crop DWPose reads: the box's centre, its size x1.25 (`bbox_xyxy2cs` padding), widened to 288:384
     * (`_fix_aspect_ratio`). A box under 10 px, or none, reads the whole frame (`tools/animate_prep.py`).
     */
    internal data class Crop(val cx: Float, val cy: Float, val w: Float, val h: Float) {
        companion object {
            fun of(box: FloatArray, frameW: Int, frameH: Int): Crop {
                var (x0, y0, x1, y1) = box.toList()
                if (x1 - x0 < 10f || y1 - y0 < 10f) { x0 = 0f; y0 = 0f; x1 = frameW.toFloat(); y1 = frameH.toFloat() }
                var w = (x1 - x0) * 1.25f
                var h = (y1 - y0) * 1.25f
                val aspect = IN_W.toFloat() / IN_H
                if (w > h * aspect) h = w / aspect else w = h * aspect
                return Crop((x0 + x1) / 2f, (y0 + y1) / 2f, w, h)
            }
        }
    }

    /** ⚠ The crop warped into 288x384 (bilinear, black past the frame), normalised, CHW. */
    private fun tensorOf(frame: Bitmap, c: Crop): FloatBuffer {
        val s = IN_W / c.w
        val dst = Bitmap.createBitmap(IN_W, IN_H, Bitmap.Config.ARGB_8888)
        Canvas(dst).apply {
            drawColor(Color.BLACK)
            drawBitmap(frame, Matrix().apply {
                setScale(s, s)
                postTranslate(IN_W / 2f - s * c.cx, IN_H / 2f - s * c.cy)
            }, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val n = IN_W * IN_H
        val px = IntArray(n).also { dst.getPixels(it, 0, IN_W, 0, 0, IN_W, IN_H) }
        dst.recycle()
        val buf = FloatBuffer.allocate(3 * n)
        for (ch in 0 until 3) {
            val shift = 16 - 8 * ch
            for (i in 0 until n) buf.put((((px[i] shr shift) and 0xFF) - MEAN[ch]) / STD[ch])
        }
        buf.rewind()
        return buf
    }

    /** ⚠ SimCC argmax per point, back to frame pixels; score = min of the two maxima (`get_simcc_maximum`). */
    private fun decode(sx: FloatBuffer, sy: FloatBuffer, c: Crop): FloatArray {
        val nx = IN_W * SPLIT
        val ny = IN_H * SPLIT
        val out = FloatArray(POINTS * 3)
        for (k in 0 until POINTS) {
            var bx = 0; var vx = Float.NEGATIVE_INFINITY
            for (i in 0 until nx) { val v = sx.get(k * nx + i); if (v > vx) { vx = v; bx = i } }
            var by = 0; var vy = Float.NEGATIVE_INFINITY
            for (i in 0 until ny) { val v = sy.get(k * ny + i); if (v > vy) { vy = v; by = i } }
            out[k * 3] = bx.toFloat() / SPLIT / IN_W * c.w + c.cx - c.w / 2f
            out[k * 3 + 1] = by.toFloat() / SPLIT / IN_H * c.h + c.cy - c.h / 2f
            out[k * 3 + 2] = minOf(vx, vy)
        }
        return out
    }
}
