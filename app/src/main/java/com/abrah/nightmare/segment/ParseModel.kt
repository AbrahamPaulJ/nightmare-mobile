package com.abrah.nightmare.segment

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
import java.nio.FloatBuffer

/**
 * ⭐⭐ Pick a target by NAME: an ATR human parser (SegFormer-B2 fine-tuned on
 * ATR, int8) on the **CPU**. One pass over the photo labels every pixel as one
 * of 18 things — hat, hair, face, the garments, each limb — and a target is a
 * GROUP of those labels.
 *
 * ⭐⭐⭐ **One pass fills every target at once**, which is the whole reason this
 * beats a text prompt here: switching from Clothes to Hair is a re-read of a
 * label map already in hand, not another inference. ⇒ [Parser] caches the map
 * per photo and never per target.
 *
 * ⚠ Measured on the S25 Ultra, 2026-09-23 (`docs/SEGMENTER.md` §7): ~535 ms per
 * photo warm, ~1 s including the session open, and **0 ms per target after
 * that**. The photo's own size does not matter — the input is a fixed 512².
 *
 * ⚠⚠ **The photo is SQUASHED to 512², not letterboxed** — the opposite of
 * [SegmentModel]'s choice and deliberate: this model's `preprocessor_config.json`
 * says `size {512, 512}` with bilinear resample and no padding, so that is what
 * the weights were shown. SAM letterboxes because SAM was trained that way.
 *
 * ⚠⚠ **int8 renames, it does not empty.** Quantization moves pixels between
 * ADJACENT labels — `Dress` ↔ `Upper-clothes`, a limb ↔ `Bag` — so a GROUP is
 * stable (union IoU 0.97 against fp32) and a single garment's name is not. That
 * is why [Parser.TARGETS] are groups and why there is no `Dress` target.
 */
class ParseModel private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    /**
     * ⭐ The softmax probability of every class per cell of the model's own
     * output grid, class-major (`probs[c * w * h + i]`).
     *
     * ⚠⚠ Probabilities, not the argmax: every rule in [ParseMask] needs to
     * know how SURE the model was, and an argmax throws that away. ~1.2 MB a
     * photo at 18 × 128², which is why [Parser]'s cache holds four.
     */
    class Labels(val probs: FloatArray, val w: Int, val h: Int, val classes: Int)

    /**
     * Label every pixel of [image]. ⚠ Blocking, ~535 ms. Never from the main
     * thread. Null when the graph failed, which the caller reports as a miss.
     */
    fun parse(image: Bitmap): Labels? = try {
        val square = Bitmap.createBitmap(DIM, DIM, Bitmap.Config.ARGB_8888)
        Canvas(square).drawBitmap(
            image, null, Rect(0, 0, DIM, DIM), Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val n = DIM * DIM
        val px = IntArray(n)
        square.getPixels(px, 0, DIM, 0, 0, DIM, DIM)
        square.recycle()
        // NCHW float32: rescale 1/255, then ImageNet mean/std. The numbers are
        // the model's own preprocessor config, not a convention.
        val buf = FloatBuffer.allocate(3 * n)
        for (c in 0 until 3) {
            val shift = 16 - 8 * c
            for (i in 0 until n) buf.put(((px[i] shr shift and 0xFF) / 255f - MEAN[c]) / STD[c])
        }
        buf.rewind()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, DIM.toLong(), DIM.toLong())).use { t ->
            session.run(mapOf(session.inputNames.first() to t)).use { out ->
                val logits = out.associate { it.key to it.value }[session.outputNames.first()]
                    as OnnxTensor
                val shape = logits.info.shape
                val classes = shape[1].toInt()
                val h = shape[2].toInt()
                val w = shape[3].toInt()
                val f = logits.floatBuffer
                val plane = w * h
                val probs = FloatArray(classes * plane) { f.get(it) }
                for (i in 0 until plane) {
                    var top = Float.NEGATIVE_INFINITY
                    for (c in 0 until classes) top = maxOf(top, probs[c * plane + i])
                    var sum = 0f
                    for (c in 0 until classes) {
                        val e = kotlin.math.exp(probs[c * plane + i] - top)
                        probs[c * plane + i] = e
                        sum += e
                    }
                    for (c in 0 until classes) probs[c * plane + i] /= sum
                }
                Labels(probs, w, h, classes)
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "parse failed", t)
        null
    }

    override fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val TAG = "ParseModel"

        /** ⚠ The model's own input size. Squashed, see the class note. */
        const val DIM = 512

        /** ⚠ 4, as [SegmentModel]: letting ORT pick oversubscribes big.LITTLE. */
        private const val THREADS = 4

        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * ⚠ The ATR label order, which is the MODEL's, not ours. Index is the
         * argmax value; do not reorder.
         */
        val ATR = listOf(
            "Background", "Hat", "Hair", "Sunglasses", "Upper-clothes", "Skirt",
            "Pants", "Dress", "Belt", "Left-shoe", "Right-shoe", "Face",
            "Left-leg", "Right-leg", "Left-arm", "Right-arm", "Bag", "Scarf",
        )

        /**
         * ⚠ The ALPHA_8 mask for [target] — [ParseMask] decides what is in it.
         * Null when the target is not in the picture, which the caller must say
         * out loud rather than draw as an empty mask.
         */
        fun maskOf(
            map: Labels,
            groups: List<IntArray>,
            target: IntArray,
            topmostOnly: Boolean,
        ): Bitmap? {
            val bytes = ParseMask.mask(
                map.probs, map.classes, map.w, map.h, groups, target, topmostOnly,
            ) ?: return null
            return SegmentModel.alphaBitmap(bytes, ParseMask.OUT, ParseMask.OUT)
        }

        /** Opens the graph. ⚠ ~380 ms; off the main thread. */
        fun open(file: File): ParseModel? = try {
            val env = OrtEnvironment.getEnvironment(
                OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR, "nightmare-parse",
            )
            ParseModel(
                env,
                env.createSession(
                    file.absolutePath,
                    OrtSession.SessionOptions().apply { setIntraOpNumThreads(THREADS) },
                ),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "failed to open parser", t)
            null
        }
    }
}
