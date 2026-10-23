package com.abrah.nightmare.canvas

import com.abrah.nightmare.ui.StudioButton
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.pow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐⭐⭐ **Doodle on a picture** (the user's list, 2026-10-09 — Local Dream's Draw screen): colour,
 * size, opacity, softness and an eraser, painted onto a LAYER over the image node's photo. The
 * layer is a transparent PNG stored beside the photo as the node's `drawing` param and composited
 * when the node runs ([com.abrah.nightmare.LoadImageNode]) — the photo itself is never changed, so
 * a drawing can be reopened, erased or cleared.
 *
 * ⚠ Strokes are kept as a LIST and replayed over [existing] on every commit, so undo and redo cost
 * a replay, not a 16 MB bitmap per step. The layer is the photo's shape, at most [MAX_EDGE] long.
 */
internal data class DrawStroke(
    /** In LAYER pixels. */
    val points: List<Offset>,
    val color: Int,
    /** Brush diameter in layer pixels. */
    val width: Float,
    val alpha: Float,
    /** Edge softness (blur radius) in layer pixels; 0 = hard. */
    val blur: Float,
    val erase: Boolean,
)

internal object DrawLayer {
    /** ⚠ 2048² ARGB is 16 MB — the most a layer may cost. */
    const val MAX_EDGE = 2048

    /** The layer's size for a picture of [w]×[h]: its shape, capped at [MAX_EDGE]. */
    fun sizeFor(w: Int, h: Int): IntSize {
        val s = minOf(1f, MAX_EDGE.toFloat() / maxOf(w, h, 1))
        return IntSize(maxOf(1, (w * s).toInt()), maxOf(1, (h * s).toInt()))
    }

    /** ⭐ The paint one stroke is drawn with — on the layer AND live on screen, so they match. */
    fun paintFor(s: DrawStroke, scale: Float = 1f): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = s.width * scale
        if (s.erase) {
            // ⚠ DST_OUT, not CLEAR: CLEAR ignores alpha, so a half-strength eraser would not exist.
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            color = android.graphics.Color.BLACK
        } else {
            color = s.color
        }
        alpha = (s.alpha * 255).toInt().coerceIn(1, 255)
        if (s.blur > 0.5f) maskFilter = BlurMaskFilter(s.blur * scale, BlurMaskFilter.Blur.NORMAL)
    }

    fun pathOf(points: List<Offset>, scale: Float = 1f): android.graphics.Path = android.graphics.Path().apply {
        if (points.isEmpty()) return@apply
        moveTo(points[0].x * scale, points[0].y * scale)
        // ⚠ A tap is a dot: a zero-length line with a round cap.
        if (points.size == 1) lineTo(points[0].x * scale + 0.01f, points[0].y * scale)
        for (k in 1 until points.size) {
            // ⭐ Midpoint quads — smooth without lagging behind the finger.
            val a = points[k - 1]; val b = points[k]
            quadTo(a.x * scale, a.y * scale, (a.x + b.x) / 2 * scale, (a.y + b.y) / 2 * scale)
        }
        points.last().let { lineTo(it.x * scale, it.y * scale) }
    }

    /** ⭐ [base] (or nothing) with [strokes] on top, at [size]. */
    fun render(base: Bitmap?, strokes: List<DrawStroke>, size: IntSize): Bitmap {
        val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        base?.let { c.drawBitmap(it, null, android.graphics.Rect(0, 0, size.width, size.height), Paint(Paint.FILTER_BITMAP_FLAG)) }
        for (s in strokes) c.drawPath(pathOf(s.points), paintFor(s))
        return out
    }

    /**
     * ⭐ The eyedropper: the colour SEEN at [at] (layer pixels) — the drawing over the photo, averaged
     * over a small disc as Local Dream's picker does, so one noisy pixel does not decide it.
     */
    fun sampleAt(photo: ImageBitmap, layer: Bitmap, at: Offset): Int {
        // ⚠ A HARDWARE bitmap cannot be read pixel by pixel; one copy per pick is cheap.
        val src = photo.asAndroidBitmap().let {
            if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
        val r = maxOf(1, maxOf(layer.width, layer.height) / 256)
        var rs = 0f; var gs = 0f; var bs = 0f; var n = 0
        for (dy in -r..r) for (dx in -r..r) {
            val lx = (at.x.toInt() + dx).coerceIn(0, layer.width - 1)
            val ly = (at.y.toInt() + dy).coerceIn(0, layer.height - 1)
            val px = (lx * src.width / layer.width).coerceIn(0, src.width - 1)
            val py = (ly * src.height / layer.height).coerceIn(0, src.height - 1)
            val under = src.getPixel(px, py)
            val over = layer.getPixel(lx, ly)
            val a = (over ushr 24) / 255f
            rs += ((over shr 16) and 0xFF) * a + ((under shr 16) and 0xFF) * (1 - a)
            gs += ((over shr 8) and 0xFF) * a + ((under shr 8) and 0xFF) * (1 - a)
            bs += (over and 0xFF) * a + (under and 0xFF) * (1 - a)
            n++
        }
        return android.graphics.Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    /**
     * ⚠ True when nothing is left to keep — the node then has no drawing at all. ⚠ Alpha ≤ 8 counts
     * as nothing: erasing over a stroke leaves an antialiased fringe that no eye sees.
     */
    fun isEmpty(b: Bitmap): Boolean {
        val step = maxOf(1, maxOf(b.width, b.height) / 256)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { if (b.getPixel(x, y) ushr 24 > 8) return false; x += step }
            y += step
        }
        return true
    }
}

/** ⭐ The swatches: the colours a doodle mostly wants, then a hue slider for the rest. */
private val SWATCHES = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFF9800, 0xFFFFEB3B, 0xFF43A047,
    0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFEC407A, 0xFF795548, 0xFF9E9E9E,
).map { it.toInt() }

/**
 * ⭐⭐ The window, laid out as the MASK window is (the user, 2026-10-23: "make draw sheet consistent to mask sheet"):
 * the tools as ICON chips with undo, redo and clear beside them, the picture, ONE [DropdownSlider] for size, opacity,
 * softness and hue, then the colours. [photo] is the node's picture WITHOUT the drawing; [existing] the drawing so far.
 *
 * ⭐⭐ AUTOSAVED, like the mask: no Done or Cancel. [onSave] gets the layer [SAVE_DELAY_MS] after the last change and
 * once more when the window goes, or null when it ends up empty (the drawing is then removed). Undo is the way back.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun DrawWindow(
    photo: ImageBitmap,
    existing: Bitmap?,
    onSave: (Bitmap?) -> Unit,
    /** ⭐ A heading, for the sheet the image node opens; the inpaint window's tab already names it. */
    title: String? = null,
    /** ⚠ For a golden: strokes already made, so the picture shows them. */
    initialStrokes: List<DrawStroke> = emptyList(),
) {
    val size = remember(photo) { DrawLayer.sizeFor(photo.width, photo.height) }
    var base by remember { mutableStateOf(existing) }
    var strokes by remember { mutableStateOf(initialStrokes) }
    var redo by remember { mutableStateOf(emptyList<DrawStroke>()) }
    var layer by remember { mutableStateOf(DrawLayer.render(existing, initialStrokes, size)) }
    fun commit() { layer = DrawLayer.render(base, strokes, size) }
    // ⚠⚠ A NEW stroke is drawn onto the layer in place; only undo, redo and clear replay ([commit]). Replaying
    // every stroke (each blur a software mask filter) into a fresh 2048² bitmap made each stroke slower than the
    // last and allocated 16 MB per stroke on the main thread. [layerRev] is what tells the draw to repaint.
    var layerRev by remember { mutableIntStateOf(0) }
    fun append(s: DrawStroke) {
        android.graphics.Canvas(layer).drawPath(DrawLayer.pathOf(s.points), DrawLayer.paintFor(s))
        layerRev++
    }
    // ⭐ Autosave: [dirty] counts changes, [saved] the last one written. ⚠ A COPY is handed over, because the
    // layer keeps being drawn into in place while the save encodes it off the main thread.
    var dirty by remember { mutableIntStateOf(0) }
    var saved by remember { mutableIntStateOf(0) }
    val latestSave by androidx.compose.runtime.rememberUpdatedState(onSave)
    fun save() {
        if (saved == dirty) return
        saved = dirty
        val l = layer
        latestSave(if (DrawLayer.isEmpty(l)) null else l.copy(Bitmap.Config.ARGB_8888, false))
    }
    androidx.compose.runtime.LaunchedEffect(dirty) {
        if (dirty > 0) { kotlinx.coroutines.delay(SAVE_DELAY_MS); save() }
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { save() } }

    var erase by remember { mutableStateOf(false) }
    // ⭐ The eyedropper (Local Dream's picker mode): the next touch takes its colour from the picture.
    var picking by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    var color by remember { mutableIntStateOf(SWATCHES[2]) }
    var hue by remember { mutableFloatStateOf(0f) }
    // ⭐ Two fingers zoom and pan, as in the mask window ([MaskEditor]); reset with the picture.
    var zoom by remember(photo) { mutableFloatStateOf(1f) }
    var pan by remember(photo) { mutableStateOf(Offset.Zero) }
    // ⚠ Fractions of the picture's long edge, so a brush is the same on a 512 or a 2048 picture.
    var sizeFrac by remember { mutableFloatStateOf(0.03f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var softness by remember { mutableFloatStateOf(0f) }
    var live by remember { mutableStateOf<List<Offset>?>(null) }
    val longEdge = maxOf(size.width, size.height).toFloat()
    fun current(points: List<Offset>) = DrawStroke(
        points, color, sizeFrac * longEdge, opacity, softness * sizeFrac * longEdge, erase,
    )

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
        // ⭐ The mask window's row: the tools as icon chips, then undo, redo and clear as small icon buttons. The
        // words survive as content descriptions.
        androidx.compose.foundation.layout.FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrawTool(com.abrah.nightmare.ui.BrushIcon, stringResource(R.string.draw_brush), !erase && !picking) { erase = false; picking = false }
            DrawTool(com.abrah.nightmare.ui.EraserIcon, stringResource(R.string.draw_eraser), erase && !picking) { erase = true; picking = false }
            DrawTool(com.abrah.nightmare.ui.EyedropperIcon, stringResource(R.string.draw_pick), picking) { picking = !picking }
            DrawAction(com.abrah.nightmare.ui.UndoIcon, stringResource(R.string.draw_undo), strokes.isNotEmpty()) {
                strokes.lastOrNull()?.let { redo = redo + it; strokes = strokes.dropLast(1); commit(); dirty++ }
            }
            DrawAction(com.abrah.nightmare.ui.RedoIcon, stringResource(R.string.draw_redo), redo.isNotEmpty()) {
                redo.lastOrNull()?.let { strokes = strokes + it; redo = redo.dropLast(1); commit(); dirty++ }
            }
            DrawAction(Icons.Filled.Delete, stringResource(R.string.draw_clear), base != null || strokes.isNotEmpty()) {
                base = null; strokes = emptyList(); redo = emptyList(); commit(); dirty++
            }
        }
        // ⭐ The picture, at its own shape, as wide as the sheet allows and no taller than half the screen.
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val maxH = with(density) { (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.5f).dp }
            val aspect = size.width.toFloat() / size.height
            val w = minOf(maxWidth, maxH * aspect)
            val h = w / aspect
            // ⚠⚠ The gesture handler is on the OUTER box, outside the zoomed layer, so touches arrive
            // untransformed and [toLayer] inverts the view by hand — [MaskEditor]'s `toImage`, the
            // same arithmetic: `graphicsLayer` scales about the centre, then translates by [pan].
            Box(
                Modifier.size(w, h).clip(RoundedCornerShape(8.dp))
                    // ⚠ NOT keyed on zoom/pan: this block writes them, and a key change restarts it
                    // mid-pinch (the mask window's lesson).
                    .pointerInput(size) {
                        val viewW = this.size.width.toFloat()
                        val viewH = this.size.height.toFloat()
                        val toLayerScale = size.width / viewW
                        fun toLayer(p: Offset): Offset {
                            val cx = viewW / 2f
                            val cy = viewH / 2f
                            return Offset(
                                (cx + (p.x - pan.x - cx) / zoom) * toLayerScale,
                                (cy + (p.y - pan.y - cy) / zoom) * toLayerScale,
                            )
                        }
                        fun clampPan(next: Offset, at: Float): Offset {
                            if (at <= 1f) return Offset.Zero
                            val maxX = viewW * (at - 1f) / 2f
                            val maxY = viewH * (at - 1f) / 2f
                            return Offset(next.x.coerceIn(-maxX, maxX), next.y.coerceIn(-maxY, maxY))
                        }
                        awaitEachGesture {
                            // ⚠ Claimed from the FIRST event, before touch slop lets the sheet scroll.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var pts = listOf(toLayer(down.position))
                            var painting = true
                            var transforming = false
                            var startSpread = 0f
                            var startZoom = zoom
                            if (!picking) live = pts
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.none { it.pressed }) break
                                if (!transforming && ev.changes.count { it.pressed } > 1) {
                                    // ⭐ A pinch starts as one finger: drop the stroke it began.
                                    transforming = true
                                    painting = false
                                    live = null
                                }
                                if (transforming) {
                                    val spread = ev.calculateCentroidSize(useCurrent = true)
                                    if (startSpread == 0f && spread > 0f) { startSpread = spread; startZoom = zoom }
                                    if (startSpread > 0f && spread > 0f) {
                                        zoom = (startZoom * (spread / startSpread).pow(PINCH_GAIN)).coerceIn(1f, MAX_ZOOM)
                                    }
                                    pan = clampPan(pan + ev.calculatePan(), zoom)
                                    ev.changes.forEach { it.consume() }
                                } else if (painting) {
                                    val ch = ev.changes.firstOrNull { it.pressed } ?: continue
                                    pts = pts + toLayer(ch.position)
                                    if (!picking) live = pts
                                    ch.consume()
                                }
                            }
                            if (painting && picking) {
                                // ⚠ Where the finger LIFTED: the eyedropper is aimed by dragging.
                                color = DrawLayer.sampleAt(photo, layer, pts.last())
                                picking = false
                                erase = false
                            } else if (painting) {
                                val made = current(pts)
                                strokes = strokes + made
                                redo = emptyList()
                                append(made)
                                dirty++
                            }
                            live = null
                        }
                    },
            ) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                },
            ) {
                val dst = androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt())
                drawImage(photo, dstSize = dst, filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium)
                val scale = this.size.width / size.width
                if (layerRev < 0) return@Canvas // ⚠ READ here, so a stroke drawn in place repaints
                drawIntoCanvas { c ->
                    val n = c.nativeCanvas
                    // ⚠ Its own layer, so a live ERASER cuts the drawing and not the photo under it.
                    val save = n.saveLayer(0f, 0f, this.size.width, this.size.height, null)
                    n.drawBitmap(layer, null, android.graphics.RectF(0f, 0f, this.size.width, this.size.height), Paint(Paint.FILTER_BITMAP_FLAG))
                    live?.let { pts -> n.drawPath(DrawLayer.pathOf(pts, scale), DrawLayer.paintFor(current(pts), scale)) }
                    n.restoreToCount(save)
                }
            }
            }
        }
        // ⭐⭐ ONE slider for the four (the mask window's [DropdownSlider]); hue only while painting.
        DropdownSlider(
            listOfNotNull(
                SliderChoice(stringResource(R.string.draw_size), sizeFrac, 0.005f..0.15f, "${(sizeFrac * 100).toInt().coerceAtLeast(1)}%", { sizeFrac = it }),
                SliderChoice(stringResource(R.string.draw_opacity), opacity, 0.05f..1f, "${(opacity * 100).toInt()}%", { opacity = it }),
                SliderChoice(stringResource(R.string.draw_softness), softness, 0f..1f, "${(softness * 100).toInt()}%", { softness = it }),
                if (erase) null else SliderChoice(stringResource(R.string.draw_hue), hue, 0f..360f, "${hue.toInt()}°", {
                    hue = it
                    color = android.graphics.Color.HSVToColor(floatArrayOf(it, 0.85f, 0.95f))
                }),
            ),
            stringResource(R.string.cd_choose_mask_slider),
        )
        if (!erase) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ⭐ The colour in use first, so a picked or chosen one is visible.
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color(color))
                        .border(3.dp, MaterialTheme.colorScheme.primary, CircleShape),
                )
                // ⭐ Any colour, beside the colour in use so it is never scrolled off: Local Dream's
                // picker (saturation × brightness square, hue bar).
                Box(
                    Modifier.size(32.dp).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Brush.sweepGradient(HUES))
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { choosing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add, contentDescription = stringResource(R.string.draw_more_colors),
                        tint = Color.White, modifier = Modifier.size(18.dp),
                    )
                }
                for (c in SWATCHES) {
                    Box(
                        Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { color = c },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (choosing) {
        ColorPickerDialog(color, onPick = { color = it; erase = false; picking = false }, onDismiss = { choosing = false })
    }
}

/** ⚠ How long after the last change the drawing is saved: one file per pause, not one per stroke. */
private const val SAVE_DELAY_MS = 600L

/** ⭐ A tool as the mask window draws one: an icon chip, selected or not ([MaskToolbar]'s brush and eraser). */
@Composable
private fun DrawTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp)) },
        shape = RoundedCornerShape(10.dp),
    )
}

/** ⭐ Undo, redo and clear as the mask window's small icon buttons, centred on the chips' line. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.DrawAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.align(Alignment.CenterVertically).size(34.dp)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
    }
}

private val HUES = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/**
 * ⭐ Any colour — PORTED from Local Dream's `SimpleColorPickerDialog` (`DrawScreen.kt`, xororz,
 * CC BY-NC 4.0): a square of saturation (across) × brightness (down) for the hue chosen on the bar
 * under it, and the result beside the confirm.
 */
@Composable
private fun ColorPickerDialog(initial: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val chosen = Color.hsv(hue, sat, value)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.draw_choose_color)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val square = 200.dp
                Box(
                    Modifier.size(square).clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                fun select(p: Offset) {
                                    sat = (p.x / this.size.width).coerceIn(0f, 1f)
                                    value = 1f - (p.y / this.size.height).coerceIn(0f, 1f)
                                }
                                val down = awaitFirstDown()
                                select(down.position)
                                down.consume()
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val ch = ev.changes.firstOrNull { it.pressed } ?: break
                                    select(ch.position)
                                    ch.consume()
                                }
                            }
                        },
                ) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
                    Box(
                        Modifier.offset(x = square * sat - 8.dp, y = square * (1f - value) - 8.dp).size(16.dp)
                            .border(2.dp, if (value < 0.5f) Color.White else Color.Black, CircleShape),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))
                        .background(androidx.compose.ui.graphics.Brush.horizontalGradient(HUES)),
                )
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f, modifier = Modifier.fillMaxWidth())
                Box(
                    Modifier.size(60.dp, 30.dp).clip(RoundedCornerShape(6.dp)).background(chosen)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)),
                )
            }
        },
        confirmButton = {
            StudioButton(onClick = { onPick(chosen.toArgb()); onDismiss() }) { Text(stringResource(R.string.draw_use_color)) }
        },
        dismissButton = { com.abrah.nightmare.ui.StudioOutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
