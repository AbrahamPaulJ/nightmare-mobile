package com.abrah.nightmare.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.abrah.nightmare.MaskRaster
import com.abrah.nightmare.MaskState
import com.abrah.nightmare.MaskStrokeData
import kotlin.math.pow

/** Translucent red, so the photo stays visible under what you are painting. */
private const val MASK_OVERLAY_RGB = 0xFF3B30
private const val MASK_ALPHA = 0.55f

/** ⚠ The overlay is rasterised at this edge, then scaled — not at screen size. */
private const val OVERLAY_DIM = 384

private const val MAX_ZOOM = 6f

/**
 * ⚠ Pinch gain. A raw spread ratio feels dead on a small canvas; the exponent
 * is applied ONCE to an absolute ratio rather than per event, so it cannot
 * drift.
 */
private const val PINCH_GAIN = 1.6f

/** Whether a stroke adds coverage or takes it away. */
enum class MaskTool { BRUSH, ERASE }

/**
 * ⭐⭐ Paint an inpaint mask over [source] with a finger.
 *
 * Ported from DreamUI's `ui/MaskCanvas.kt`, minus the tap-to-segment mode (it
 * decodes a SAM 2.1 output, which is Tier 1 and does not exist here) and the
 * zoom-out crop's checkerboard padding (no outset crop here). ⚠ Every comment
 * below marked with a bug is one DreamUI already paid for; none of it is
 * defensive programming.
 *
 * ⚠⚠ **White = repaint.** The overlay is drawn translucent red rather than
 * white so the photo underneath stays readable, but what leaves here is a
 * black/white mask where white is where `sd.latent_blend`'s `repaint` latent
 * shows through.
 */
@Composable
fun MaskEditor(
    source: ImageBitmap,
    state: MaskState,
    tool: MaskTool,
    brushRadiusFrac: Float,
    /** ⚠ Called ONCE per finished stroke, never per pointer event. See below. */
    onStroke: (MaskStrokeData) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠ The in-progress stroke lives in LOCAL state so dragging stays smooth
    // without a round trip through the view model on every pointer sample.
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val aspect = source.width.toFloat() / source.height.toFloat()

    // ⚠ Reset whenever the picture changes — a pan held over from the previous
    // photo would put the mask somewhere arbitrary.
    var zoom by remember(source) { mutableFloatStateOf(1f) }
    var pan by remember(source) { mutableStateOf(Offset.Zero) }

    /**
     * Screen point -> normalised image coordinate, undoing the view transform.
     *
     * ⚠⚠ This is the whole correctness of painting while zoomed. `graphicsLayer`
     * scales about the CENTRE, so rendering maps an image point q to
     * `c + (q - c) * zoom + pan`; painting has to invert exactly that, or every
     * stroke lands offset by the pan and scaled by the zoom — painted in the
     * right place on screen and stored in the wrong place in the mask.
     */
    fun toImage(p: Offset, size: Size): Offset {
        val cx = size.width / 2f
        val cy = size.height / 2f
        return Offset(
            (cx + (p.x - pan.x - cx) / zoom) / size.width,
            (cy + (p.y - pan.y - cy) / zoom) / size.height,
        )
    }

    fun clampPan(next: Offset, size: Size, at: Float): Offset {
        if (at <= 1f) return Offset.Zero
        val maxX = size.width * (at - 1f) / 2f
        val maxY = size.height * (at - 1f) / 2f
        return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
    }

    // ⚠ Recomputed only when the mask actually changes — not per frame, and not
    // during a drag, which is why the live stroke is drawn separately below.
    val overlay = remember(state, aspect) {
        if (state.isEmpty) {
            null
        } else {
            val w = OVERLAY_DIM
            val h = (OVERLAY_DIM / aspect).toInt().coerceAtLeast(1)
            MaskRaster.overlay(state, w, h, MASK_OVERLAY_RGB.toInt())
        }
    }
    DisposableEffect(overlay) { onDispose { overlay?.recycle() } }

    Box(
        modifier
            .fillMaxWidth()
            // ⚠⚠ The painter must be the shape of the IMAGE, not a square.
            // A hardcoded 1f lets ContentScale.Fit letterbox a 768x512 photo
            // inside a square box while the Canvas still spans the whole square
            // — so the empty bands accept strokes, and every coordinate is
            // normalised against the square. A touch on the photo's top edge
            // stores y = 0.167 rather than 0, and the mask sent to the model
            // does not match what was painted.
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(8.dp))
            // ⚠⚠ ONE handler for every touch, and the FINGER COUNT decides:
            //   1 finger  -> paint, and the scrolling sheet never sees it
            //   2 fingers -> pinch-zoom and pan
            //
            // ⚠ Hand-rolled rather than detectDragGestures/detectTransformGestures,
            // and consumed from the FIRST event. Those detectors apply touch slop
            // before claiming anything, and the vertically scrolling column this
            // sits in wins during that window — which is a brush drag scrolling
            // the sheet instead of painting.
            //
            // ⚠⚠ NOT keyed on zoom/pan, and that is load-bearing. This block
            // WRITES zoom; Compose cancels and restarts a pointerInput when a key
            // changes, so keying it on zoom makes it tear itself down on its own
            // first output — the restarted block waits at awaitFirstDown() for a
            // NEW finger-down while the fingers are still on the glass, so a pinch
            // produces exactly one small step and then dies. zoom/pan are state
            // delegates, so capturing them once does NOT freeze the transform.
            .pointerInput(brushRadiusFrac, tool) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    var painting = false
                    // ⚠ Pinch is tracked ABSOLUTELY, from the spread at the
                    // moment the gesture became a pinch, rather than by
                    // multiplying per-event deltas: it cannot drift or lose
                    // scale if events are coalesced.
                    var startSpread = 0f
                    var startZoom = zoom

                    live = listOf(toImage(down.position, size.toSize()))
                    painting = true
                    // Claim it here, before any slop can elapse.
                    down.consume()

                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break

                        if (!transforming && event.changes.count { it.pressed } > 1) {
                            transforming = true
                            // ⭐ The stray-stroke fix. A two-finger gesture
                            // necessarily BEGINS as one finger, so a stroke has
                            // already started before the second lands. Nothing
                            // is committed until the gesture ends, so discarding
                            // it costs nothing.
                            if (painting) {
                                live = emptyList()
                                painting = false
                            }
                        }

                        if (transforming) {
                            val spread = event.calculateCentroidSize(useCurrent = true)
                            if (startSpread == 0f && spread > 0f) {
                                startSpread = spread
                                startZoom = zoom
                            }
                            if (startSpread > 0f && spread > 0f) {
                                val ratio = (spread / startSpread).pow(PINCH_GAIN)
                                zoom = (startZoom * ratio).coerceIn(1f, MAX_ZOOM)
                            }
                            pan = clampPan(pan + event.calculatePan(), size.toSize(), zoom)
                            event.changes.forEach { it.consume() }
                        } else if (painting) {
                            val change = event.changes.firstOrNull { it.pressed }
                            if (change != null) {
                                live = live + toImage(change.position, size.toSize())
                                change.consume()
                            }
                        }
                    }

                    if (painting && live.isNotEmpty()) {
                        // ⚠⚠ ONE write, at the END of the gesture. A widget that
                        // emits per pointer event runs the canvas-update-and-
                        // autosave path dozens of times a second, and the
                        // concurrent writes that came out of that are what
                        // crashed the app mid-drag on the cropper
                        // (`notes/HANDOFF.md` §5). A tap is a legitimate
                        // one-dot stroke and the list already holds it.
                        onStroke(MaskStrokeData(live.map { it.x to it.y }, brushRadiusFrac))
                    }
                    live = emptyList()
                }
            },
    ) {
        // ⚠⚠ ONE layer carries the zoom, wrapping BOTH visual layers — not the
        // same Modifier applied to each separately. They must move together to
        // the pixel: the overlay is the only thing saying what is masked, and a
        // mask half a zoom step out of step with the photo is worse than no zoom
        // at all. Nested, the mask is rasterised ONCE at its natural size and
        // the finished composite is scaled as a texture, which is the cheap path
        // — applied per-child, the offscreen layer is re-rasterised every frame
        // and trails the photo as a translucent ghost.
        //
        // ⚠ The gesture handler stays on the OUTER Box, outside this layer, so
        // pointer coordinates arrive untransformed and toImage() keeps inverting
        // the transform by hand. Moving it inside would make Compose undo the
        // scale first and toImage() would undo it twice.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            Image(
                bitmap = source,
                contentDescription = "the picture being masked",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            Canvas(
                Modifier
                    .fillMaxSize()
                    // ⚠⚠ Flatten the mask into ONE translucent layer. Drawing
                    // each stroke at 55% composites 0.55 over 0.55 -> 0.80
                    // wherever two dabs meet, so the overlay reads as a pile of
                    // blobs and you cannot tell what is actually masked.
                    //
                    // ⚠ Offscreen is requested EXPLICITLY rather than left to
                    // Modifier.alpha: alpha alone may take the cheaper
                    // ModulateAlpha path, which multiplies each draw call
                    // instead of allocating a layer.
                    .graphicsLayer {
                        alpha = MASK_ALPHA
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                overlay?.let {
                    drawImage(
                        image = it.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }

                if (live.isNotEmpty()) {
                    // ⚠ WIDTH, not min(width, height): radiusFrac is
                    // width-relative everywhere else, so measuring the preview
                    // against the short edge draws a landscape brush a third
                    // smaller than the dab actually written into the mask.
                    val stroke = brushRadiusFrac * 2f * size.width
                    // ⚠ The eraser previews as a HOLE in the overlay, drawn in
                    // the ground colour rather than with BlendMode.Clear —
                    // clearing inside this layer would punch through to the
                    // photo and read as a black hole while the finger is down.
                    val color = if (tool == MaskTool.ERASE) Color.Black else Color.Red
                    if (live.size == 1) {
                        drawCircle(
                            color = color,
                            radius = stroke / 2f,
                            center = Offset(live[0].x * size.width, live[0].y * size.height),
                        )
                    } else {
                        val path = Path().apply {
                            moveTo(live[0].x * size.width, live[0].y * size.height)
                            live.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = DrawStroke(
                                width = stroke,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
            }
        }
    }
}
