package com.abrah.nightmare.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.abrah.nightmare.CropNode
import com.abrah.nightmare.blurSource
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.CropGeometry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The crop rectangle, in fractions of the source image.
 *
 * ⚠ Normalised rather than pixels, exactly as [com.abrah.nightmare.CropNode]
 * stores it: a saved workflow re-pointed at a photo of a different size must
 * still mean the same framing.
 *
 * ⚠⚠ It may reach OUTSIDE the picture — `x` below zero, `x + w` above one — and
 * that is deliberate. A photo too small to fill the size its consumer demands is
 * padded rather than enlarged (`CropGeometry.needsPadding`), and the frame that
 * expresses that is one that hangs over the edge. Clamping it back inside would
 * silently re-frame the crop instead.
 */
data class CropRect(val x: Float, val y: Float, val w: Float, val h: Float) {

    /**
     * ⚠ Clamped so the rect always has area and always still OVERLAPS the
     * image. It is no longer clamped *inside* it — see the class comment — so
     * this is a guard against a malformed param rather than the policy it was.
     * The policy lives in `CropGeometry.minScale` and [CropView.clampOffset],
     * which is where the viewport can enforce it against real dimensions.
     */
    fun clamped(): CropRect {
        val cw = w.coerceIn(MIN, MAX_SPAN)
        val ch = h.coerceIn(MIN, MAX_SPAN)
        return CropRect(
            x.coerceIn(-cw + MIN, 1f - MIN),
            y.coerceIn(-ch + MIN, 1f - MIN),
            cw,
            ch,
        )
    }

    /** Within a rounding step of [o]? ⚠ The params are stored to 3 decimals. */
    fun near(o: CropRect) =
        abs(x - o.x) < 2e-3f && abs(y - o.y) < 2e-3f &&
            abs(w - o.w) < 2e-3f && abs(h - o.h) < 2e-3f

    companion object {
        /** ⚠ 5%: below this a pinch can zoom past anything useful. */
        const val MIN = 0.05f

        /**
         * ⚠ A ceiling on how far outside the picture a frame may reach. 64x is
         * a 64-pixel thumbnail asked for 4096 — far past anything sane, which
         * is what a guard is for.
         */
        const val MAX_SPAN = 64f
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/**
 * ⚠⚠ The pan/zoom arithmetic, as PURE FUNCTIONS with no Compose in them, for
 * the reason `CanvasModel` gives at length: this is the part that fails
 * silently. A framing that is off by a factor renders the wrong region of the
 * photo and never throws, and no screenshot of the editor shows it -- the
 * editor would look right and the output would be wrong.
 *
 * `scale` maps picture pixels to viewport pixels; `offset` is where the
 * picture's top-left corner sits inside the viewport.
 *
 * ⚠ The zoom FLOOR lives in `com.abrah.nightmare.CropGeometry.minScale`, beside
 * the node that has to honour it. One fact, one home: the editor and `CropNode`
 * disagreeing about when a picture may be padded is precisely the silent
 * failure this file exists to prevent.
 */
object CropView {

    /** What the viewport currently frames, in fractions of the picture. */
    fun rectOf(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float,
        scale: Float, offsetX: Float, offsetY: Float,
    ) = CropRect(
        x = -offsetX / (imgW * scale),
        y = -offsetY / (imgH * scale),
        w = viewW / (imgW * scale),
        h = viewH / (imgH * scale),
    ).clamped()

    /** …and the scale that puts [rect] back in a viewport of this width. */
    fun scaleFor(viewW: Float, imgW: Float, rect: CropRect) =
        viewW / (rect.w.coerceAtLeast(CropRect.MIN) * imgW)

    /**
     * ⚠ Bounds written both ways round, and both are load-bearing now.
     *
     * Zoomed in, `dx` is negative and the range is `[dx, 0]`: the picture is
     * bigger than the frame and may not be panned off its own edge. **Padded,
     * `dx` is positive and the range is `[0, dx]`**: the picture is SMALLER than
     * the frame, and this is what keeps it inside — it slides within the bars
     * rather than escaping them.
     */
    fun clampOffset(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float,
        scale: Float, offsetX: Float, offsetY: Float,
    ): Offset {
        val dx = viewW - imgW * scale
        val dy = viewH - imgH * scale
        return Offset(
            offsetX.coerceIn(min(0f, dx), max(0f, dx)),
            offsetY.coerceIn(min(0f, dy), max(0f, dy)),
        )
    }
}

/**
 * ⭐⭐ Framing, the way a camera does it: **a fixed viewport, and the picture
 * moves behind it.** Drag to pan, pinch to zoom.
 *
 * ⚠⚠ The viewport IS the output, so it cannot be the wrong shape — the aspect
 * lock is geometry rather than a feature. What decides that shape is
 * [aspect], and it comes from whatever consumes this node: a `crop` feeding a
 * `vae_encode` frames a square because that is what will be encoded, and the
 * user is never offered a framing that cannot be produced.
 *
 * ⚠⚠ **The picture may be smaller than the frame**, and the bars are then real
 * output — black, or the picture's own edges mirrored. That happens only when
 * the photo cannot fill the demanded size without being enlarged; a photo big
 * enough is still held to covering the frame exactly as before. See
 * `CropGeometry.minScale`.
 *
 * ⚠ The pan/zoom lives in the picture's own pixel space and the [CropRect] is
 * derived from it, not the other way round. The rect stays the single stored
 * truth -- it is still what the node's params hold, what the cache keys on and
 * what a workflow file round-trips -- but a gesture cannot be expressed in it
 * without the viewport, so the viewport is what the fingers move.
 *
 * ⚠ This is the pattern every interactive node after it should copy; see
 * `docs/UI.md`.
 */
@Composable
fun CropEditor(
    source: ImageBitmap,
    rect: CropRect,
    onChange: (CropRect) -> Unit,
    /** Output width/height, or 0 when the framed pixels are the output. */
    outW: Int = 0,
    /** The viewport's shape: width / height. */
    aspect: Float = 1f,
    /**
     * Fill the bars with the picture's own edges, mirrored and blurred, rather
     * than with black. ⚠ [CropNode.PAD_BLUR] is the same fill; this draws it.
     */
    padBlur: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val onChangeNow by rememberUpdatedState(onChange)
    val pw = source.width.toFloat()
    val ph = source.height.coerceAtLeast(1).toFloat()

    // Scale maps picture px -> viewport px; offset is the picture's top-left
    // corner within the viewport (so it is zero or negative when zoomed in, and
    // positive when the picture is smaller than the frame).
    var scale by remember(source) { mutableFloatStateOf(0f) }
    var offset by remember(source) { mutableStateOf(Offset.Zero) }
    var view by remember(source) { mutableStateOf(Size.Zero) }
    // ⚠⚠ What this editor last handed upstream. Without it the emitted rect
    // comes straight back as the `rect` argument and re-frames the view mid
    // gesture -- the picture would jump on every pointer event. Compared with a
    // tolerance because the params are rounded to three decimals on the way out.
    var emitted by remember(source) { mutableStateOf<CropRect?>(null) }

    // ⚠⚠ The small copy the bars are tiled from, made ONCE per picture. It is
    // the same [blurSource] the node uses, so the preview blurs by exactly the
    // amount the render will -- and computing it per frame would scale a bitmap
    // on every pointer event of a drag.
    val blurred = remember(source, padBlur) {
        if (padBlur) blurSource(source.asAndroidBitmap()).asImageBitmap() else source
    }

    fun clamp() {
        if (view.width <= 0f || view.height <= 0f) return
        scale = max(scale, CropGeometry.minScale(view.width, view.height, pw, ph, outW))
        offset = CropView.clampOffset(
            view.width, view.height, pw, ph, scale, offset.x, offset.y,
        )
    }

    /** Put the stored rect on screen. */
    fun frameFrom(r: CropRect) {
        if (view.width <= 0f) return
        scale = CropView.scaleFor(view.width, pw, r)
        offset = Offset(-r.x * pw * scale, -r.y * ph * scale)
        clamp()
    }

    /** …and read it back off the screen. */
    fun current(): CropRect =
        CropView.rectOf(view.width, view.height, pw, ph, scale, offset.x, offset.y)

    // ⚠⚠ **The frame must fit the SCREEN, not just the column it sits in.**
    //
    // `fillMaxWidth().aspectRatio(a)` derives the height from the width alone,
    // so a portrait frame (a < 1) is taller than it is wide and runs off the
    // bottom of the sheet -- and in landscape, where the whole window is ~360dp
    // tall, even a square one does. Reported from the phone, 2026-09-09.
    //
    // ⚠⚠ And the height cannot come from the layout: this composable lives
    // inside the inspector's `verticalScroll`, which offers an INFINITE height
    // constraint by definition. There is nothing to fit against, so the bound
    // has to come from the window itself.
    //
    // ⇒ Fit inside `min(available width, cap x aspect)`, where the cap is a
    // fraction of the window height. Then the whole frame is visible with the
    // knobs under it, in either orientation, without scrolling to find its edge.
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val cap = (screenH * HEIGHT_SHARE).coerceAtLeast(MIN_FRAME)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // ⚠ Whichever axis binds first. `aspect` is width/height, so a frame
        // capped by height is `cap * aspect` wide.
        val frameW = minOf(maxWidth, cap * aspect)
        val frameH = frameW / aspect.coerceAtLeast(0.01f)
        Box(
            Modifier
                .size(frameW, frameH)
                .clip(RoundedCornerShape(10.dp))
        ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(source, outW, aspect) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        if (scale <= 0f) return@detectTransformGestures
                        // Zoom about the pinch centroid, so whatever is between
                        // the fingers stays between them instead of sliding away.
                        val next = scale * zoom
                        offset = Offset(
                            centroid.x - (centroid.x - offset.x) * (next / scale) + pan.x,
                            centroid.y - (centroid.y - offset.y) * (next / scale) + pan.y,
                        )
                        scale = next
                        clamp()
                        val r = current()
                        emitted = r
                        onChangeNow(r)
                    }
                }
        ) {
            // ⚠ Re-framed when the SHAPE changes too, not only the picture: a
            // rewire that changes the demanded size changes the viewport, and a
            // scale computed for the old one frames the wrong region.
            if (view != size) {
                view = size
                frameFrom(rect)
                emitted = current()
            } else if (emitted?.near(rect) == false) {
                // Changed from outside -- someone typed in the number fields.
                frameFrom(rect)
                emitted = current()
            }
            if (scale <= 0f) return@Canvas

            val iw = (pw * scale).roundToInt()
            val ih = (ph * scale).roundToInt()

            // ⭐ The bars are OUTPUT, not chrome, so they are drawn as the node
            // will render them. Black underneath in both modes: a mirror that
            // does not quite reach the corner must not show the sheet's
            // background, which is not a colour the picture will ever have.
            drawRect(Color.Black)
            if (padBlur) drawMirroredEdges(blurred, offset, iw, ih, size)
            drawImage(
                image = source,
                dstOffset = IntOffset(offset.x.roundToInt(), offset.y.roundToInt()),
                dstSize = IntSize(iw, ih),
            )

            // Thirds, the way every camera draws them. ⚠ Over the whole
            // viewport, because the whole viewport is the crop now -- there is
            // no "outside the rectangle" left to dim.
            for (i in 1..2) {
                val gx = size.width * i / 3f
                val gy = size.height * i / 3f
                drawLine(Color.White.copy(alpha = 0.25f), Offset(gx, 0f), Offset(gx, size.height))
                drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, gy), Offset(size.width, gy))
            }
            drawRect(
                color = CanvasColors.selectedStroke,
                size = size,
                style = Stroke(width = 3f),
            )
        }
        }
    }
}

/**
 * ⚠ How much of the window the framing view may take.
 *
 * Half leaves room for the padding chips, the number fields and the delete
 * button beneath it — in landscape, where the window is ~360dp tall, that is the
 * difference between seeing the frame and scrolling to find its bottom edge.
 */
private const val HEIGHT_SHARE = 0.5f

/** ⚠ …but never so small that a finger cannot frame anything in it. */
private val MIN_FRAME = 140.dp

/**
 * The picture's own edges, reflected outwards to fill the frame -- BLURRED.
 *
 * ⚠⚠ A reflected TILING, not four stretched edge strips. Reflection is what
 * `Shader.TileMode.MIRROR` does in `CropNode`, and the editor has to agree with
 * the node exactly or the preview is a lie about the thing it is previewing —
 * tile `(i, j)` is flipped on an axis whenever that index is odd.
 *
 * ⚠⚠ …and it is handed the SAME downscaled copy the node tiles ([blurSource]),
 * which is what makes both blurred by the same amount. Drawing a tiny bitmap
 * over a large area IS the blur here: the bilinear filter does the work, and it
 * is the only form of it the node and this preview can both perform identically
 * -- a `RenderEffect` needs a hardware layer, which the node's software bitmap
 * is not.
 *
 * ⚠ Bounded. A 32-pixel thumbnail in a 4096 frame would ask for thousands of
 * tiles, and past a handful nobody can tell what they are looking at anyway.
 */
private fun DrawScope.drawMirroredEdges(
    image: ImageBitmap,
    offset: Offset,
    iw: Int,
    ih: Int,
    view: Size,
) {
    if (iw <= 0 || ih <= 0) return
    val iMin = floor(-offset.x / iw).toInt()
    val iMax = ceil((view.width - offset.x) / iw).toInt()
    val jMin = floor(-offset.y / ih).toInt()
    val jMax = ceil((view.height - offset.y) / ih).toInt()
    if ((iMax - iMin + 1).toLong() * (jMax - jMin + 1) > MAX_TILES) return

    for (i in iMin..iMax) {
        for (j in jMin..jMax) {
            if (i == 0 && j == 0) continue   // the picture itself is drawn after
            val tx = offset.x + i * iw
            val ty = offset.y + j * ih
            withTransform({
                // ⚠ Odd index -> flipped, about the tile's own centre. That is
                // what makes neighbouring tiles meet edge-to-edge with no seam.
                scale(
                    if (i % 2 == 0) 1f else -1f,
                    if (j % 2 == 0) 1f else -1f,
                    pivot = Offset(tx + iw / 2f, ty + ih / 2f),
                )
            }) {
                drawImage(
                    image = image,
                    dstOffset = IntOffset(tx.roundToInt(), ty.roundToInt()),
                    dstSize = IntSize(iw, ih),
                )
            }
        }
    }
}

private const val MAX_TILES = 81L

/** ⚠ Ignores a malformed param rather than throwing: the sheet must still open. */
fun cropRectOf(node: com.abrah.nightmare.Node): CropRect = CropRect(
    node.params["x"]?.toFloatOrNull() ?: 0f,
    node.params["y"]?.toFloatOrNull() ?: 0f,
    node.params["w"]?.toFloatOrNull() ?: 1f,
    node.params["h"]?.toFloatOrNull() ?: 1f,
).clamped()

/** Rounded, so the params a workflow stores stay short and stable. */
fun CropRect.asParams(): List<Pair<String, String>> = listOf(
    "x" to round3(x), "y" to round3(y), "w" to round3(w), "h" to round3(h),
)

private fun round3(v: Float) = (Math.round(v * 1000f) / 1000f).toString()

/**
 * The viewport's shape for a crop node: what its consumer demands, or the shape
 * the user chose when nothing does.
 *
 * ⚠⚠ `"source"` is the default and it means *the photo's own shape*, so an
 * unwired crop opens showing the whole picture rather than a square guess at
 * what might be wanted. The alternative — a free-form rectangle the user drags
 * the corners of — is the model this editor deliberately replaced, and having
 * both would mean two gesture languages in one view (`docs/UI.md` §5).
 */
fun cropAspect(node: com.abrah.nightmare.Node, srcW: Int, srcH: Int): Float {
    val ow = node.params["out_w"]?.toIntOrNull() ?: 0
    val oh = node.params["out_h"]?.toIntOrNull() ?: 0
    if (ow > 0 && oh > 0) return ow.toFloat() / oh
    val chosen = node.params["aspect"].orEmpty()
    val parts = chosen.split(':')
    if (parts.size == 2) {
        val a = parts[0].toFloatOrNull()
        val b = parts[1].toFloatOrNull()
        if (a != null && b != null && a > 0f && b > 0f) return a / b
    }
    return if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH else 1f
}
