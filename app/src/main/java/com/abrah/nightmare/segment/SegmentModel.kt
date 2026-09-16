package com.abrah.nightmare.segment

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Tap an object, get its mask: SAM 2.1 (hiera-tiny, w8a8) on the **CPU**.
 *
 * ⭐ Ported from DreamUI's `SegmentModel.kt`, which owns the measurements
 * (`../LocalDream/docs/AUTOMASK.md`). Every constant and every threshold below
 * is its, and each was paid for there — copy, do not re-derive
 * (`docs/SEGMENTER.md` §2).
 *
 * ⚠ No NPU: ORT's QNN EP cannot create an HTP device on this SoC. It does not
 * matter, because the graph is split at the seam where a tap stops costing:
 *
 * | graph | when | cost on an S25 Ultra |
 * |---|---|---|
 * | trunk | once per photo | ~817 ms |
 * | prompt + decoder | per tap | ~28 ms |
 *
 * ⚠⚠ **The one deliberate difference from DreamUI: the photo is LETTERBOXED,
 * not stretched.** DreamUI only ever segmented a square crop, so its
 * `createScaledBitmap(image, 1024, 1024)` was a no-op in practice. Here the
 * whole photo is segmented — taps are stored in the PHOTO's coordinates, like
 * strokes (`MaskFraming`) — and a 4:3 photo squashed into a square is not what
 * SAM was trained on. SAM's own preprocessing scales the long edge to 1024 and
 * pads bottom/right, and that is what this does. ⚠ A side benefit: the
 * prompt encoder's coordinate ceiling (0.9592, below) falls in the padding for
 * any non-square photo.
 */
class SegmentModel private constructor(
    private val env: OrtEnvironment,
    private val trunk: OrtSession,
    private val prompt: OrtSession,
    private val decoder: OrtSession,
) : AutoCloseable {

    /**
     * ⭐ Every granularity SAM offered for one tap, area-ascending — subpart,
     * part, whole — each an ALPHA_8 bitmap covering the WHOLE photo (the
     * padding already cut away). [default] is where a tap starts.
     */
    class Segmentation(val candidates: List<Bitmap>, val default: Int)

    private class Embedding(
        val imageEmbeddings: OnnxTensor,
        val highRes1: OnnxTensor,
        val highRes2: OnnxTensor,
        /** The photo's extent inside the padded 1024² square, as fractions. */
        val fx: Float,
        val fy: Float,
    ) : AutoCloseable {
        override fun close() {
            imageEmbeddings.close(); highRes1.close(); highRes2.close()
        }
    }

    private var embedding: Embedding? = null
    private var embeddedFor: Long = 0

    /**
     * Runs the image trunk for [image] and keeps the result for later taps.
     * ⚠ The ~817 ms. Off the main thread.
     */
    @Synchronized
    fun encode(image: Bitmap, key: Long) {
        if (embeddedFor == key && embedding != null) return
        embedding?.close()
        embedding = null

        val scale = DIM.toFloat() / maxOf(image.width, image.height)
        val sw = (image.width * scale).roundToInt().coerceIn(1, DIM)
        val sh = (image.height * scale).roundToInt().coerceIn(1, DIM)
        val square = Bitmap.createBitmap(DIM, DIM, Bitmap.Config.ARGB_8888)
        // ⚠ Black padding: the trunk takes raw uint8, so zero is SAM's pad value.
        Canvas(square).drawBitmap(
            image, null, Rect(0, 0, sw, sh), Paint(Paint.FILTER_BITMAP_FLAG),
        )

        val n = DIM * DIM
        val px = IntArray(n)
        square.getPixels(px, 0, DIM, 0, 0, DIM, DIM)
        square.recycle()

        // NCHW uint8. The graph's quantization is scale 1/255, zero point 0, so
        // the raw byte IS the quantized value.
        val buf = ByteBuffer.allocateDirect(3 * n).order(ByteOrder.nativeOrder())
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until n) buf.put((px[i] shr shift and 0xFF).toByte())
        }
        buf.rewind()

        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, DIM.toLong(), DIM.toLong()), OnnxJavaType.UINT8)
            .use { tensor ->
                trunk.run(mapOf("image" to tensor)).use { out ->
                    val map = out.associate { it.key to it.value }
                    embedding = Embedding(
                        copyOf(map["image_embeddings"] as OnnxTensor, longArrayOf(1, 256, 64, 64)),
                        copyOf(map["high_res_features1"] as OnnxTensor, longArrayOf(1, 32, 256, 256)),
                        copyOf(map["high_res_features2"] as OnnxTensor, longArrayOf(1, 64, 128, 128)),
                        sw.toFloat() / DIM, sh.toFloat() / DIM,
                    )
                }
            }
        embeddedFor = key
    }

    @Synchronized
    fun isReady(key: Long): Boolean = embedding != null && embeddedFor == key

    /**
     * The candidates for a tap at ([x], [y]), normalised over the PHOTO. Null
     * when nothing contains the tap — a miss the caller reports, never a mask
     * of some other object.
     *
     * ⚠ Null too when [encode] has not run: callers prime first (DreamUI's
     * trap — a decoder with no trunk is indistinguishable from "no object").
     */
    @Synchronized
    fun segment(x: Float, y: Float): Segmentation? {
        val emb = embedding ?: return null
        // Into the padded square's coordinates.
        val px = x * emb.fx
        val py = y * emb.fy

        // Two point slots, one used; the second is marked unused by label -1.
        val coords = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).apply {
            put(quant(px, COORD_SCALE, 0)); put(quant(py, COORD_SCALE, 0))
            put(quant(0f, COORD_SCALE, 0)); put(quant(0f, COORD_SCALE, 0))
            rewind()
        }
        val labels = ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()).apply {
            put(quant(1f, LABEL_SCALE, LABEL_ZP)); put(quant(-1f, LABEL_SCALE, LABEL_ZP))
            rewind()
        }

        return OnnxTensor.createTensor(env, coords, longArrayOf(1, 2, 2), OnnxJavaType.UINT8).use { c ->
            OnnxTensor.createTensor(env, labels, longArrayOf(1, 2), OnnxJavaType.UINT8).use { l ->
                prompt.run(mapOf("unnorm_coords" to c, "labels" to l)).use { pr ->
                    val sparse = pr.get(0) as OnnxTensor
                    decoder.run(
                        mapOf(
                            "image_embeddings" to emb.imageEmbeddings,
                            "high_res_features1" to emb.highRes1,
                            "high_res_features2" to emb.highRes2,
                            "sparse_embedding" to sparse,
                        ),
                    ).use { dr ->
                        toSegmentation(
                            (dr.get(0) as OnnxTensor).byteBuffer,
                            (dr.get(1) as OnnxTensor).byteBuffer,
                            px, py, emb,
                        )
                    }
                }
            }
        }
    }

    /**
     * The four mask tokens -> the three real candidates.
     *
     * ⚠ Token 0 is excluded: the single-mask head blends competing readings of
     * an ambiguous point and bleeds (AUTOMASK §15).
     */
    private fun toSegmentation(
        masks: ByteBuffer,
        scores: ByteBuffer,
        tapX: Float,
        tapY: Float,
        emb: Embedding,
    ): Segmentation? {
        val n = MASK_DIM * MASK_DIM
        val built = (1 until TOKENS).map { token ->
            val alpha = decodeToken(masks, token * n, tapX, tapY)
            Candidate(
                alpha = alpha,
                area = alpha.count { (it.toInt() and 0xFF) >= 128 },
                hitsTap = hitsTap(alpha, tapX, tapY),
                score = (scores.get(token).toInt() and 0xFF) * SCORE_SCALE,
            )
        }.sortedBy { it.area }

        val usable = built.filter { it.area > 0 }
        if (usable.isEmpty()) return null

        // ⚠ Containment is HARD; confidence only breaks ties between masks that
        // contain the tap. DreamUI's fallback to the best score anywhere
        // selected a different object with no error shown.
        val containing = usable.indices.filter { usable[it].hitsTap }
        val default = containing.firstOrNull { usable[it].score >= SCORE_FLOOR }
            ?: containing.firstOrNull()
            ?: return null

        // ⭐ Cut the padding away, so a candidate covers exactly the photo.
        val cw = ceil(emb.fx * MASK_DIM).toInt().coerceIn(1, MASK_DIM)
        val ch = ceil(emb.fy * MASK_DIM).toInt().coerceIn(1, MASK_DIM)
        val bitmaps = usable.map { c ->
            val cut = ByteArray(cw * ch)
            for (row in 0 until ch) System.arraycopy(c.alpha, row * MASK_DIM, cut, row * cw, cw)
            alphaBitmap(cut, cw, ch)
        }
        return Segmentation(bitmaps, default)
    }

    private class Candidate(val alpha: ByteArray, val area: Int, val hitsTap: Boolean, val score: Float)

    private fun hitsTap(alpha: ByteArray, tapX: Float, tapY: Float): Boolean {
        val x = (tapX * MASK_DIM).toInt().coerceIn(0, MASK_DIM - 1)
        val y = (tapY * MASK_DIM).toInt().coerceIn(0, MASK_DIM - 1)
        return (alpha[y * MASK_DIM + x].toInt() and 0xFF) >= 128
    }

    /**
     * Quantized logits -> alpha. ⚠ The 3x3 blur is not polish: thresholding
     * the raw logits stipples every soft boundary (AUTOMASK §6).
     */
    private fun decodeToken(masks: ByteBuffer, base: Int, tapX: Float, tapY: Float): ByteArray {
        val n = MASK_DIM * MASK_DIM
        val logits = FloatArray(n) {
            ((masks.get(base + it).toInt() and 0xFF) - MASK_ZP) * MASK_SCALE
        }
        val alpha = ByteArray(n)
        for (y in 0 until MASK_DIM) {
            for (x in 0 until MASK_DIM) {
                var sum = 0f
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= MASK_DIM) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= MASK_DIM) continue
                        sum += logits[yy * MASK_DIM + xx]
                        count++
                    }
                }
                // A narrow ramp either side of the cut: the region is stretched
                // to the output later, and a soft edge survives that better.
                val t = ((sum / count) / EDGE_BAND + 0.5f).coerceIn(0f, 1f)
                alpha[y * MASK_DIM + x] = (t * t * (3f - 2f * t) * 255f).roundToInt().toByte()
            }
        }
        isolateTapComponent(alpha, tapX, tapY)
        return alpha
    }

    /**
     * Keeps only the blob under the tap, then fills its holes. ⚠ No-ops when
     * the tap lands outside the mask, or it would flood from a clear pixel and
     * erase the region. DreamUI's, unchanged.
     */
    private fun isolateTapComponent(alpha: ByteArray, tapX: Float, tapY: Float) {
        val d = MASK_DIM
        val tx = (tapX * d).toInt().coerceIn(0, d - 1)
        val ty = (tapY * d).toInt().coerceIn(0, d - 1)
        val start = ty * d + tx
        val inside = { i: Int -> (alpha[i].toInt() and 0xFF) >= 128 }
        if (!inside(start)) return

        // 8-connected, matching the chamfer's diagonal step.
        val keep = BooleanArray(alpha.size)
        val stack = IntArray(alpha.size)
        var top = 0
        stack[top++] = start
        keep[start] = true
        while (top > 0) {
            val i = stack[--top]
            val x = i % d
            val y = i / d
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= d) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= d) continue
                    val j = yy * d + xx
                    if (keep[j] || !inside(j)) continue
                    keep[j] = true
                    stack[top++] = j
                }
            }
        }

        // Holes: 4-connected on purpose — an 8-connected background leaks
        // through a checkerboard edge and fills nothing.
        val outside = BooleanArray(alpha.size)
        top = 0
        fun push(i: Int) {
            if (!keep[i] && !outside[i]) {
                outside[i] = true
                stack[top++] = i
            }
        }
        for (x in 0 until d) { push(x); push((d - 1) * d + x) }
        for (y in 0 until d) { push(y * d); push(y * d + d - 1) }
        while (top > 0) {
            val i = stack[--top]
            val x = i % d
            if (x > 0) push(i - 1)
            if (x < d - 1) push(i + 1)
            if (i >= d) push(i - d)
            if (i < alpha.size - d) push(i + d)
        }
        for (i in alpha.indices) {
            alpha[i] = when {
                keep[i] -> alpha[i]
                outside[i] -> 0
                else -> 255.toByte()
            }
        }
    }

    private fun copyOf(t: OnnxTensor, shape: LongArray): OnnxTensor {
        val src = t.byteBuffer
        val dst = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
        dst.put(src); dst.rewind()
        return OnnxTensor.createTensor(env, dst, shape, OnnxJavaType.UINT8)
    }

    private fun quant(real: Float, scale: Float, zp: Int): Byte =
        ((real / scale).roundToInt() + zp).coerceIn(0, 255).toByte()

    @Synchronized
    override fun close() {
        embedding?.close()
        embedding = null
        runCatching { trunk.close() }
        runCatching { prompt.close() }
        runCatching { decoder.close() }
    }

    companion object {
        private const val TAG = "SegmentModel"

        const val DIM = 1024
        private const val MASK_DIM = 256

        // From the bundle's metadata.json; every tensor is raw uint8.
        // ⚠ COORD_SCALE at zp 0 reaches only 0.9592 — the model's ceiling, not
        // ours (AUTOMASK §5).
        private const val COORD_SCALE = 0.003761707106605172f
        private const val LABEL_SCALE = 0.003921641502529383f
        private const val LABEL_ZP = 255
        private const val MASK_SCALE = 0.3612250089645386f
        private const val MASK_ZP = 165
        private const val SCORE_SCALE = 0.00390625f
        private const val TOKENS = 4

        /** 0.65, not 0.5: at 0.5 the default lands on a sliver (AUTOMASK §15). */
        private const val SCORE_FLOOR = 0.65f
        private const val EDGE_BAND = 3f

        /** ⚠ 4, measured: letting ORT pick oversubscribes big.LITTLE. */
        private const val THREADS = 4

        /** ⚠ ALPHA_8 rows are padded to a stride; write through a padded buffer. */
        fun alphaBitmap(bytes: ByteArray, w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            val stride = bmp.rowBytes
            val buf = ByteBuffer.allocate(stride * h)
            for (row in 0 until h) {
                buf.position(row * stride)
                buf.put(bytes, row * w, w)
            }
            buf.rewind()
            bmp.copyPixelsFromBuffer(buf)
            return bmp
        }

        /** Opens the three graphs from [dir]. ⚠ ~400 ms; off the main thread. */
        fun open(dir: File): SegmentModel? = try {
            val env = OrtEnvironment.getEnvironment(OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-seg")
            fun open(name: String) = env.createSession(
                File(dir, name).absolutePath,
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
            )
            SegmentModel(env, open("trunk.onnx"), open("prompt.onnx"), open("decoder.onnx"))
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open segmenter", t)
            null
        }
    }
}
