package com.abrah.nightmare

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ⭐⭐ Painting an inpaint mask, ported from DreamUI's `MaskRaster.kt`.
 *
 * ⚠ **A trimmed port, not a copy.** DreamUI's version also carries tap-to-segment
 * regions (a SAM 2.1 decode — Tier 1, and there is no host op here that runs a
 * model), ControlNet pose, and the checkerboard padding of a zoom-out crop.
 * None of those exist in this app, and shipping the fields for them would be
 * three dead code paths. What is kept is the part that applies: brush, eraser,
 * invert, grow, feather.
 *
 * ⚠⚠ **White = repaint, black = keep**, matching the backend's own per-step
 * blend (`docs/ARCHITECTURE.md`) and `sd.latent_blend`'s `repaint` port. This
 * convention is the one thing about masking that is easy to get backwards and
 * impossible to notice: the wrong way round replaces the region you meant to
 * keep, and the picture still looks plausible.
 */

/**
 * One painted stroke, in **normalised** [0,1] image coordinates.
 *
 * ⚠ Normalised rather than pixels so the same stroke list rasterises correctly
 * at whatever size the graph asks for — 512 today, 1024 on SDXL — and survives
 * the editor being laid out at any on-screen size.
 */
data class MaskStrokeData(
    val points: List<Pair<Float, Float>>,
    /** Brush radius as a fraction of the image WIDTH. */
    val radiusFrac: Float,
)

/**
 * One editable unit of the mask.
 *
 * ⚠ Undo replays the list, so an erase and a paint are undone by the same
 * mechanism and in the order they were actually made.
 */
sealed interface MaskOp {
    data class Stroke(val stroke: MaskStrokeData) : MaskOp

    /**
     * A stroke that **removes** coverage.
     *
     * ⚠ Carries a [MaskStrokeData] rather than a type of its own: the eraser is
     * the brush with the sign flipped, so it shares the radius and the same
     * normalised geometry.
     */
    data class Erase(val stroke: MaskStrokeData) : MaskOp

    /** ⚠ Inverts what is masked SO FAR — see [MaskRaster.composite]. */
    data object Invert : MaskOp
}

/**
 * The whole mask, as the ops that made it.
 *
 * ⚠ Ops rather than a bitmap, because that is what a workflow can store: a
 * saved graph carries this as a string param and re-rasterises at whatever size
 * the consumer wants.
 */
data class MaskState(
    val ops: List<MaskOp> = emptyList(),
    /**
     * ⚠ Grows the mask outward before feathering. **Zero by default here**,
     * where DreamUI defaults to 10/512 — its default exists for tapped
     * segmenter regions, which trace an object's true edge and need slack. This
     * app has no segmenter, so every op is a brush stroke that is already
     * exactly the size the finger asked for, and growing it is a second
     * invisible brush-size control fighting the real one.
     */
    val growFrac: Float = 0f,
    /**
     * ⚠ Ramps the mask edge to zero over this fraction of the width. A soft
     * edge blends better once the backend downsamples to the 64×64 latent grid.
     */
    val featherFrac: Float = 0f,
) {
    val isEmpty: Boolean get() = ops.isEmpty()

    fun plus(op: MaskOp): MaskState = copy(ops = ops + op)
    fun dropLast(): MaskState = if (ops.isEmpty()) this else copy(ops = ops.dropLast(1))
    fun cleared(): MaskState = copy(ops = emptyList())

    /**
     * ⭐⭐ The whole state as ONE param string, because a graph param is a
     * string and a workflow has to round-trip.
     *
     * Format: ops joined by `;`, each `s<r>:x,y|x,y|…` (stroke), `e<r>:…`
     * (erase) or `i` (invert), then `~<grow>,<feather>`.
     *
     * ⚠ Three decimals: 1/1000 of a 512 px edge is half a pixel, which is finer
     * than the anti-aliased brush can express — and the string is written into
     * an autosaved JSON file on every stroke, so the digits are not free.
     */
    fun encode(): String {
        val body = ops.joinToString(";") { op ->
            when (op) {
                is MaskOp.Invert -> "i"
                is MaskOp.Stroke -> "s" + strokeText(op.stroke)
                is MaskOp.Erase -> "e" + strokeText(op.stroke)
            }
        }
        return "$body~${fmt(growFrac)},${fmt(featherFrac)}"
    }

    private fun strokeText(s: MaskStrokeData): String =
        fmt(s.radiusFrac) + ":" + s.points.joinToString("|") { "${fmt(it.first)},${fmt(it.second)}" }

    private fun fmt(f: Float): String {
        val r = (f * 1000f).roundToInt() / 1000f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
    }

    companion object {
        /**
         * ⚠ Never throws. A param edited by hand, truncated, or written by an
         * older version must not take the canvas down with it — a mask that
         * comes back empty is recoverable, a crash on graph load is not.
         */
        fun decode(text: String?): MaskState {
            if (text.isNullOrBlank()) return MaskState()
            return try {
                val (bodyText, tail) = text.split("~").let {
                    it[0] to it.getOrNull(1).orEmpty()
                }
                val grow = tail.split(",").getOrNull(0)?.toFloatOrNull() ?: 0f
                val feather = tail.split(",").getOrNull(1)?.toFloatOrNull() ?: 0f
                val ops = bodyText.split(";").mapNotNull { part ->
                    when {
                        part.isBlank() -> null
                        part == "i" -> MaskOp.Invert
                        part.startsWith("s") || part.startsWith("e") -> {
                            val stroke = parseStroke(part.substring(1)) ?: return@mapNotNull null
                            if (part[0] == 's') MaskOp.Stroke(stroke) else MaskOp.Erase(stroke)
                        }
                        else -> null
                    }
                }
                MaskState(ops, grow, feather)
            } catch (e: Exception) {
                MaskState()
            }
        }

        private fun parseStroke(text: String): MaskStrokeData? {
            val colon = text.indexOf(':')
            if (colon < 0) return null
            val r = text.substring(0, colon).toFloatOrNull() ?: return null
            val pts = text.substring(colon + 1).split("|").mapNotNull { p ->
                val c = p.split(",")
                val x = c.getOrNull(0)?.toFloatOrNull()
                val y = c.getOrNull(1)?.toFloatOrNull()
                if (x == null || y == null) null else x to y
            }
            return if (pts.isEmpty()) null else MaskStrokeData(pts, r)
        }
    }
}

object MaskRaster {

    /**
     * ⚠ 3-4 chamfer weights: an integer distance transform, straight vs
     * diagonal. Distances therefore come back in **thirds of a pixel**.
     */
    const val CH_STRAIGHT = 3
    const val CH_DIAGONAL = 4

    /**
     * Renders [state] at [w] x [h] as an opaque black/white bitmap.
     *
     * ⚠ Anti-aliased on purpose: the backend downsamples this to the latent
     * grid, where a soft edge blends better than a hard step.
     */
    fun rasterise(state: MaskState, w: Int, h: Int = w): Bitmap {
        val featherPx = state.featherFrac * w
        val combined = composite(state.ops, state.growFrac * w, w, h)
        return if (featherPx > 0f) dilate(combined, 0f, featherPx) else combined
    }

    /**
     * Additive ops unioned and [MaskOp.Erase] ops subtracted, **in the order
     * they were made**.
     *
     * ⚠⚠ Order matters because the eraser is not a filter over the finished
     * mask: rubbing something out and then painting it back has to leave it
     * painted, which a single subtractive pass at the end could not express. So
     * the ops are walked in sequence and split into runs at each erase.
     */
    private fun composite(ops: List<MaskOp>, growPx: Float, w: Int, h: Int): Bitmap {
        var acc: Bitmap? = null
        val run = mutableListOf<MaskOp>()

        fun flush() {
            if (run.isEmpty()) return
            val layer = coverage(run, w, h).let {
                if (growPx > 0f) dilate(it, growPx, 0f) else it
            }
            acc = acc?.let { union(it, layer) } ?: layer
            run.clear()
        }

        ops.forEach { op ->
            when (op) {
                is MaskOp.Erase -> {
                    flush()
                    // ⚠ An erase over empty space is a no-op; allocating a black
                    // buffer to subtract from would only cost a pass.
                    acc?.let { subtract(it, op.stroke, w, h) }
                }
                // ⚠ Inverts what is masked SO FAR, then keeps going. Later
                // strokes add to the flipped mask, which is what makes
                // "invert, then tidy the edges" work — and is why it cannot be
                // a flag applied at the end.
                is MaskOp.Invert -> {
                    flush()
                    acc = invert(acc, w, h)
                }
                else -> run += op
            }
        }
        flush()
        return acc ?: coverage(emptyList(), w, h)
    }

    /** Strokes composited into one black/white bitmap. */
    private fun coverage(ops: List<MaskOp>, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = android.graphics.Color.WHITE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isDither = true
        }
        ops.filterIsInstance<MaskOp.Stroke>().forEach { drawStroke(canvas, paint, it.stroke, w, h) }
        return bmp
    }

    /**
     * ⚠ Opaque BLACK rather than a porter-duff clear: this buffer is a
     * black/white coverage map, not an alpha channel, so black *is* "not
     * masked". Anti-aliased like every other stroke, so an erased edge is as
     * soft as a painted one.
     */
    private fun subtract(base: Bitmap, stroke: MaskStrokeData, w: Int, h: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            color = android.graphics.Color.BLACK
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isDither = true
        }
        drawStroke(Canvas(base), paint, stroke, w, h)
    }

    private fun drawStroke(canvas: Canvas, paint: Paint, s: MaskStrokeData, w: Int, h: Int) {
        // ⚠ radiusFrac is WIDTH-relative everywhere, so it scales by w on both
        // axes; that is what keeps a round dab round on a non-square image.
        val stroke = s.radiusFrac * 2f * w
        if (s.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(s.points[0].first * w, s.points[0].second * h, stroke / 2f, paint)
        } else {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            val path = Path().apply {
                moveTo(s.points[0].first * w, s.points[0].second * h)
                s.points.drop(1).forEach { lineTo(it.first * w, it.second * h) }
            }
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        }
    }

    private fun invert(src: Bitmap?, w: Int, h: Int): Bitmap {
        val px = IntArray(w * h)
        src?.getPixels(px, 0, w, 0, 0, w, h)
        src?.recycle()
        for (i in px.indices) {
            // ⚠ The buffer is opaque greyscale, so one channel is the coverage
            // and the other two follow it.
            val v = 255 - (px[i] and 0xFF)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /**
     * Pixel-wise max of two black-on-white masks. [top] is consumed.
     *
     * ⚠ LIGHTEN rather than plain over: both layers are opaque, so drawing one
     * on the other would replace the strokes with the other layer's black
     * background instead of unioning them.
     */
    private fun union(base: Bitmap, top: Bitmap): Bitmap {
        Canvas(base).drawBitmap(
            top, 0f, 0f,
            Paint(Paint.FILTER_BITMAP_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.LIGHTEN)
            },
        )
        top.recycle()
        return base
    }

    /**
     * Grows the mask outward by [growPx] and ramps it to zero over the next
     * [featherPx].
     *
     * ⚠ Both bands come from ONE distance transform, so the cost does not grow
     * with the radius — unlike iterated dilation, where a 32 px grow is 32
     * passes over the buffer.
     */
    private fun dilate(src: Bitmap, growPx: Float, featherPx: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()

        val dist = seed(px, w, h)
        chamfer(dist, w, h)

        val grow = growPx * CH_STRAIGHT
        val band = featherPx * CH_STRAIGHT
        for (i in px.indices) {
            val d = dist[i].toFloat()
            val a = when {
                d <= grow -> 1f
                band <= 0f -> 0f
                else -> 1f - smoothstep(min((d - grow) / band, 1f))
            }
            val v = (a * 255f).roundToInt().coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** Distance seed: 0 on painted pixels, "far" elsewhere. */
    private fun seed(pixels: IntArray, w: Int, h: Int): IntArray {
        val far = Int.MAX_VALUE / 4
        return IntArray(pixels.size) { if ((pixels[it] and 0xFF) >= 128) 0 else far }
    }

    /** Two-pass 3-4 chamfer distance transform, in place. */
    private fun chamfer(dist: IntArray, w: Int, h: Int) {
        fun at(x: Int, y: Int) = dist[y * w + x]
        for (y in 0 until h) {
            for (x in 0 until w) {
                var d = at(x, y)
                if (y > 0) {
                    d = min(d, at(x, y - 1) + CH_STRAIGHT)
                    if (x > 0) d = min(d, at(x - 1, y - 1) + CH_DIAGONAL)
                    if (x < w - 1) d = min(d, at(x + 1, y - 1) + CH_DIAGONAL)
                }
                if (x > 0) d = min(d, at(x - 1, y) + CH_STRAIGHT)
                dist[y * w + x] = d
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                var d = at(x, y)
                if (y < h - 1) {
                    d = min(d, at(x, y + 1) + CH_STRAIGHT)
                    if (x > 0) d = min(d, at(x - 1, y + 1) + CH_DIAGONAL)
                    if (x < w - 1) d = min(d, at(x + 1, y + 1) + CH_DIAGONAL)
                }
                if (x < w - 1) d = min(d, at(x + 1, y) + CH_STRAIGHT)
                dist[y * w + x] = d
            }
        }
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    /**
     * The mask as a translucent tint for drawing OVER the photo.
     *
     * ⚠⚠ Rasterised rather than re-stroked. Once grow or feather is non-zero
     * the geometry and the rasterised mask are not the same picture, and
     * showing the geometry would mean previewing something other than what gets
     * sent.
     */
    fun overlay(state: MaskState, w: Int, h: Int, color: Int): Bitmap {
        val mask = rasterise(state, w, h)
        val px = IntArray(w * h)
        mask.getPixels(px, 0, w, 0, 0, w, h)
        mask.recycle()
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        for (i in px.indices) {
            val a = px[i] and 0xFF
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** What fraction of the frame is masked. ⚠ Sampled, not exact — it is a readout. */
    fun coverageFraction(state: MaskState, sample: Int = 96): Float {
        if (state.isEmpty) return 0f
        val bmp = rasterise(state, sample, sample)
        val px = IntArray(sample * sample)
        bmp.getPixels(px, 0, sample, 0, 0, sample, sample)
        bmp.recycle()
        return px.count { (it and 0xFF) >= 128 }.toFloat() / px.size
    }
}
