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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.withTransform
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
private const val MASK_OVERLAY_RGB = MaskRaster.OVERLAY_RGB
private const val MASK_ALPHA = 0.55f

/**
 * ⭐ The OTHER layer's mask, drawn faint under the one being painted — the
 * user's ask, 2026-09-24: *"adjust opacity so other layers can be visible as
 * well"*. Faint enough that it never reads as the layer a stroke will change.
 */
private const val UNDER_ALPHA = 0.2f

/** ⚠ The overlay is rasterised at this edge, then scaled — not at screen size. */
private const val OVERLAY_DIM = 384

private const val MAX_ZOOM = 6f

/**
 * ⚠ Pinch gain. A raw spread ratio feels dead on a small canvas; the exponent
 * is applied ONCE to an absolute ratio rather than per event, so it cannot
 * drift.
 */
private const val PINCH_GAIN = 1.6f

/**
 * Whether a stroke adds coverage or takes it away — or, with a segmenter wired,
 * whether a touch SELECTS the object under it (`docs/SEGMENTER.md`).
 */
/**
 * ⚠ [PICK] draws NOTHING on the canvas — its whole interaction is the chip
 * row above, so a finger on the picture must not paint while it is selected
 * (`docs/SEGMENTER.md` §8).
 */
enum class MaskTool { BRUSH, ERASE, TAP, PICK }

/**
 * ⭐⭐⭐ An OBJECT LAYER in the editor (`docs/ADD-OBJECTS.md`, the user's
 * design 2026-09-26): nothing is painted on it. A finger on an object selects
 * it and drags it; two fingers resize and turn the selected one together,
 * snapping to right angles ([com.abrah.nightmare.AddObjects.snapAngle]). With
 * nothing selected, two fingers zoom the view as on the image layer.
 *
 * [items] are this layer's objects in the PICTURE's normalised terms (framed
 * by the caller), each with the index it has in the node's list.
 * ⚠ [onChange] fires ONCE, when the fingers lift — the same one-write-per-
 * gesture rule as a stroke, and it is what makes a drag one Undo step.
 */
class ObjectLayerEdit(
    val items: List<ObjectItem>,
    val selected: Int?,
    val onSelect: (Int?) -> Unit,
    val onChange: (Int, com.abrah.nightmare.AddObjects.Placed) -> Unit,
)

class ObjectItem(val index: Int, val obj: com.abrah.nightmare.AddObjects.Placed, val cut: ImageBitmap)

/** ⚠ How small or large an object may be pinched, as a share of the picture's width. */
private const val OBJECT_MIN_W = 0.03f
private const val OBJECT_MAX_W = 3f

/**
 * ⭐⭐ Paint an inpaint mask over [source] with a finger.
 *
 * Ported from DreamUI's `ui/MaskCanvas.kt`. Its zoom-out padding is drawn BLUE
 * rather than checkerboard ([padding]), because here it is locked mask. Its tap-to-segment mode is
 * [MaskTool.TAP], which reports the point and leaves segmenting to the caller. ⚠ Every comment
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
    /** ⭐ [MaskTool.TAP]: where the finger went down, normalised to [source]. */
    onTap: (Float, Float) -> Unit = { _, _ -> },
    /**
     * ⭐⭐ OUTPAINT: the photo's extent in [source], as fractions — everything
     * outside it is padding, drawn BLUE over the painting. ⚠ Display only: it is
     * `MaskRaster.forcePadding` on the sampler's mask that makes it masked and
     * un-erasable, so a stroke here can neither add to it nor take it away.
     */
    padding: com.abrah.nightmare.Frame? = null,
    /**
     * ⭐⭐ The other LAYER's mask, shown faintly under [state] and never
     * painted by a stroke here (`docs/ADD-OBJECTS.md`, layers).
     */
    under: MaskState? = null,
    /**
     * ⚠ What resets the zoom and pan — [source] unless told otherwise. The
     * mask window passes the PHOTO, so a new picture resets the view but the
     * objects moving or turning see-through (a layer switch) does not.
     */
    viewKey: Any? = source,
    /** ⭐⭐ Non-null on an OBJECT layer: select, move, resize, turn — no painting. */
    objects: ObjectLayerEdit? = null,
) {
    // ⚠⚠⚠ **The callbacks are read FRESH.** They are called from inside a
    // `pointerInput` that restarts only on the brush size and the tool, so it
    // held the lambdas of the composition it last started in — and a stroke
    // made after a LAYER switch went to the layer that was active before.
    val strokeTo by rememberUpdatedState(onStroke)
    val tapAt by rememberUpdatedState(onTap)
    val objectLayer by rememberUpdatedState(objects)
    // ⭐ The object under the fingers, as it is mid-gesture — drawn in place of
    // its stored self until the fingers lift and [ObjectLayerEdit.onChange] lands.
    var liveObject by remember { mutableStateOf<Pair<Int, com.abrah.nightmare.AddObjects.Placed>?>(null) }
    // ⚠ The in-progress stroke lives in LOCAL state so dragging stays smooth
    // without a round trip through the view model on every pointer sample.
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val aspect = source.width.toFloat() / source.height.toFloat()

    // ⚠ Reset whenever the picture changes — a pan held over from the previous
    // photo would put the mask somewhere arbitrary.
    var zoom by remember(viewKey) { mutableFloatStateOf(1f) }
    var pan by remember(viewKey) { mutableStateOf(Offset.Zero) }

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

    /**
     * ⭐⭐ One gesture on an object layer ([ObjectLayerEdit]). The down point
     * picks the TOPMOST object under it; with none there, the selected one
     * still takes a two-finger resize and turn, and a plain tap on nothing
     * lets the selection go.
     */
    suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.objectGesture(
        layer: ObjectLayerEdit,
        at: Offset,
        size: Size,
    ) {
        val p0 = toImage(at, size)
        val hit = layer.items.lastOrNull {
            com.abrah.nightmare.AddObjects.contains(it.obj, it.cut.width, it.cut.height, source.width, source.height, p0.x, p0.y)
        }
        if (hit != null && hit.index != layer.selected) layer.onSelect(hit.index)
        val target = hit ?: layer.items.firstOrNull { it.index == layer.selected }
        val start = target?.obj
        var cur = start
        var startSpread = 0f
        var startAngle = 0f
        var startW = 0f
        var startRot = 0f
        var startZoom = zoom
        var fingers = 1
        var most = 1
        var travelled = 0f
        while (true) {
            val event = awaitPointerEvent()
            val down = event.changes.filter { it.pressed }
            if (down.isEmpty()) break
            if (down.size != fingers) {
                // ⚠ Re-anchored whenever a finger lands or lifts, so the object
                // does not jump by what the spread was before.
                fingers = down.size
                most = maxOf(most, fingers)
                startSpread = 0f
            }
            val panPx = event.calculatePan()
            travelled += panPx.getDistance()
            if (target == null || cur == null) {
                // ⭐ Nothing selected: two fingers zoom the view, as on the image layer.
                if (down.size > 1) {
                    val spread = event.calculateCentroidSize(useCurrent = true)
                    if (startSpread == 0f && spread > 0f) { startSpread = spread; startZoom = zoom }
                    if (startSpread > 0f && spread > 0f) {
                        zoom = (startZoom * (spread / startSpread).pow(PINCH_GAIN)).coerceIn(1f, MAX_ZOOM)
                    }
                    pan = clampPan(pan + panPx, size, zoom)
                }
                event.changes.forEach { it.consume() }
                continue
            }
            var o: com.abrah.nightmare.AddObjects.Placed = cur
            o = o.copy(x = o.x + panPx.x / (size.width * zoom), y = o.y + panPx.y / (size.height * zoom))
            if (down.size > 1) {
                val a = down[0].position
                val b = down[1].position
                val spread = (a - b).getDistance()
                val angle = Math.toDegrees(kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())).toFloat()
                if (startSpread == 0f) {
                    if (spread > 0f) { startSpread = spread; startAngle = angle; startW = o.w; startRot = o.rot }
                } else {
                    val w = (startW * spread / startSpread).coerceIn(OBJECT_MIN_W, OBJECT_MAX_W)
                    // ⚠ Resized about its CENTRE, like the placer: its height
                    // in the picture's terms follows the cut's shape.
                    val tall = source.width.toFloat() / source.height.coerceAtLeast(1) *
                        target.cut.height / target.cut.width.coerceAtLeast(1)
                    o = o.copy(
                        x = o.x - (w - o.w) / 2f,
                        y = o.y - (w - o.w) * tall / 2f,
                        w = w,
                        rot = com.abrah.nightmare.AddObjects.snapAngle(startRot + angle - startAngle),
                    )
                }
            }
            cur = o
            liveObject = target.index to o
            event.changes.forEach { it.consume() }
        }
        val end = cur
        if (target != null && end != null && end != start) layer.onChange(target.index, end)
        else if (hit == null && most == 1 && travelled < 12f && layer.selected != null) layer.onSelect(null)
        liveObject = null
    }

    // ⚠ Recomputed only when the mask actually changes — not per frame, and not
    // during a drag, which is why the live stroke is drawn separately below.
    val overlay = remember(state, aspect) { overlayOf(state, aspect, "mask overlay") }
    DisposableEffect(overlay) { onDispose { overlay?.recycle() } }
    val underlay = remember(under, aspect) { under?.let { overlayOf(it, aspect, "other layer overlay") } }
    DisposableEffect(underlay) { onDispose { underlay?.recycle() } }

    // ⚠⚠ Fit the WINDOW and centre, by [CropEditor]'s own rule and constants —
    // it filled the width alone, so in the inpaint popup the Mask tab drew the
    // picture a different size and position from the Crop tab (2026-09-17).
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp
    val cap = (screenH * HEIGHT_SHARE).coerceAtLeast(MIN_FRAME)
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
    val frameW = minOf(maxWidth, cap * aspect)
    Box(
        Modifier
            .width(frameW)
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
                    // ⭐ Kept, like `NmPreview`: where every touch on the frame
                    // LANDS. Reported 2026-09-23 that drags near the frame's
                    // left and right sides register nothing; a missing line
                    // here for such a drag means the touch never reached it.
                    android.util.Log.i(
                        "NmMaskTouch",
                        "down x=${down.position.x.toInt()} y=${down.position.y.toInt()} " +
                            "frame=${size.width}x${size.height} tool=$tool zoom=$zoom",
                    )
                    objectLayer?.let { layer ->
                        down.consume()
                        objectGesture(layer, down.position, size.toSize())
                        return@awaitEachGesture
                    }
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

                    if (tool == MaskTool.PICK) {
                        // ⚠ Nothing: the chips do the picking. Falling through
                        // to the paint branch would paint a stroke with an
                        // invisible brush, which is the worst of both.
                    } else if (tool == MaskTool.TAP) {
                        // ⚠ The DOWN point, not where a wobbling finger lifted:
                        // it is what the user aimed at. A pinch is not a tap.
                        if (painting && live.isNotEmpty()) tapAt(live.first().x, live.first().y)
                    } else if (painting && live.isNotEmpty()) {
                        // ⚠⚠ ONE write, at the END of the gesture. A widget that
                        // emits per pointer event runs the canvas-update-and-
                        // autosave path dozens of times a second, and the
                        // concurrent writes that came out of that are what
                        // crashed the app mid-drag on the cropper
                        // (`notes/HANDOFF.md` §5). A tap is a legitimate
                        // one-dot stroke and the list already holds it.
                        strokeTo(MaskStrokeData(live.map { it.x to it.y }, brushRadiusFrac))
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
            // ⭐⭐ An object layer's own objects, drawn here rather than baked
            // into [source], so a drag moves them without a composite per frame.
            objects?.let { layer ->
                val ring = androidx.compose.material3.MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize()) {
                    for (item in layer.items) {
                        val o = liveObject?.takeIf { it.first == item.index }?.second ?: item.obj
                        val w = o.w * size.width
                        val h = w * item.cut.height / item.cut.width.coerceAtLeast(1)
                        // ⚠ The same three steps as `AddObjects.matrix`, which
                        // the render draws with: centre, turn, mirror.
                        withTransform({
                            translate(o.x * size.width + w / 2f, o.y * size.height + h / 2f)
                            rotate(o.rot, pivot = Offset.Zero)
                            scale(if (o.flip) -1f else 1f, 1f, pivot = Offset.Zero)
                        }) {
                            drawImage(
                                item.cut,
                                dstOffset = IntOffset((-w / 2f).toInt(), (-h / 2f).toInt()),
                                dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                            )
                            if (item.index == layer.selected) drawRect(
                                color = ring,
                                topLeft = Offset(-w / 2f, -h / 2f),
                                size = Size(w, h),
                                style = DrawStroke(width = 2.dp.toPx() / zoom),
                            )
                        }
                    }
                }
            }
            underlay?.let { u ->
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = UNDER_ALPHA
                            compositingStrategy = CompositingStrategy.Offscreen
                        },
                ) {
                    drawImage(
                        image = u.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            }

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
                // ⚠ Hidden while an object is dragged: its ring would stay
                // behind at the old place until the fingers lift.
                if (liveObject == null) overlay?.let {
                    drawImage(
                        image = it.asImageBitmap(),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }

                if (live.isNotEmpty() && tool != MaskTool.TAP && tool != MaskTool.PICK) {
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
            // ⭐ The locked padding, ABOVE the painting and inside the zoom layer
            // so it moves with the picture.
            padding?.let { p -> Canvas(Modifier.fillMaxSize()) { drawPadding(p) } }
        }
    }
    }
}

/**
 * The red overlay of [state] at [OVERLAY_DIM], or null when there is nothing.
 * ⚠ On the main thread, deliberately: it must land in the same frame the
 * finished stroke leaves, or the stroke blinks out. Timed to `NmPerf`, so a
 * slow one shows up in the log rather than as a stutter nobody can place.
 */
private fun overlayOf(state: MaskState, aspect: Float, label: String): android.graphics.Bitmap? {
    if (state.isEmpty) return null
    val t0 = System.nanoTime()
    val w = OVERLAY_DIM
    val h = (OVERLAY_DIM / aspect).toInt().coerceAtLeast(1)
    return MaskRaster.overlay(state, w, h, MASK_OVERLAY_RGB.toInt()).also {
        android.util.Log.i(
            com.abrah.nightmare.Perf.TAG,
            "$label ${w}x$h: ${(System.nanoTime() - t0) / 1_000_000} ms (main)",
        )
    }
}
