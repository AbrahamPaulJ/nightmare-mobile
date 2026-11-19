package com.abrah.nightmare.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.MossLayout
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle
import com.abrah.nightmare.ui.StudioOutlinedButton
import com.abrah.nightmare.ui.StudioTextButton
import com.abrah.nightmare.ui.dangerOutlinedColors

/**
 * ⭐⭐ A MOSS sampler's **Layout** tab (GitHub #77; the plugin's editor, LinXce/Comfyui-Moss-LayoutPrompt, made for a
 * finger): the picture's shape with one box per element. Drag on an empty part to draw a box, drag a box to move it,
 * drag any corner dot to resize it, tap to select (a second tap where boxes overlap picks the next one down); edges snap
 * to the picture's edges and middle and to the other boxes ([LayoutSnap]). Undo and redo beside Add box. Under it, one
 * card per box: what it is (object, character, text), its place and size in pixels, what it looks like, and for text
 * the exact words. GitHub #132 asked for the undo, the snapping, the typed size and the easier resize.
 *
 * ⚠ The boxes are kept as fractions on the node ([MossLayout.PARAM]), so changing the picture's size keeps them where
 * they were drawn. A drag writes the node once, when the finger lifts; typing writes as it goes, as every field does.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MossLayoutPanel(param: String?, w: Int, h: Int, onSave: (String) -> Unit) {
    var layout by remember(param) { mutableStateOf(MossLayout.read(param)) }
    var selected by remember { mutableIntStateOf(-1) }
    var clearing by remember { mutableStateOf(false) }
    fun commit(l: MossLayout.Layout) { layout = l; onSave(MossLayout.write(l)) }
    // ⭐ GitHub #132: undo and redo, the mask window's own history ([EditHistory]), watching what the node holds.
    val history = rememberEditHistory(Unit, param.orEmpty())
    val labels = listOf(
        stringResource(R.string.layout_object), stringResource(R.string.layout_character), stringResource(R.string.layout_text),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.layout_note), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LayoutCanvas(layout, selected, w, h, onSelect = { selected = it }, onDone = { commit(it) })
        // ⚠ A FlowRow: on a narrow phone a button moves to a second line whole instead of its label wrapping (the user,
        // 2026-11-19: Clear came out as "Cle / ar"). Destructive before the add, as every row orders them.
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { history.undo(param.orEmpty()) { layout = MossLayout.read(it); onSave(it) } }, enabled = history.canUndo) {
                Icon(com.abrah.nightmare.ui.UndoIcon, contentDescription = stringResource(R.string.draw_undo))
            }
            IconButton(onClick = { history.redo(param.orEmpty()) { layout = MossLayout.read(it); onSave(it) } }, enabled = history.canRedo) {
                Icon(com.abrah.nightmare.ui.RedoIcon, contentDescription = stringResource(R.string.draw_redo))
            }
            if (layout.boxes.isNotEmpty()) {
                StudioTextButton(onClick = { clearing = true }, colors = dangerOutlinedColors()) {
                    Text(stringResource(R.string.layout_clear), maxLines = 1, softWrap = false)
                }
            }
            StudioOutlinedButton(onClick = {
                // ⭐ A new box in the middle, a third of the picture, offset so a second one is not hidden under the first.
                val k = (layout.boxes.size % 4) * 0.05
                commit(layout.copy(boxes = layout.boxes + MossLayout.Box(x1 = 0.33 + k, y1 = 0.33 + k, x2 = 0.66 + k, y2 = 0.66 + k)))
                selected = layout.boxes.lastIndex
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.layout_add_box), modifier = Modifier.padding(start = 4.dp), maxLines = 1, softWrap = false)
            }
        }
        layout.boxes.forEachIndexed { i, b ->
            BoxCard(
                i, b, labels, selected == i, w, h,
                onSelect = { selected = i },
                onChange = { nb -> commit(layout.copy(boxes = layout.boxes.toMutableList().also { it[i] = nb })) },
                onDelete = {
                    commit(layout.copy(boxes = layout.boxes.filterIndexed { j, _ -> j != i }))
                    selected = -1
                },
            )
        }
    }
    if (clearing) {
        com.abrah.nightmare.ui.ConfirmDelete(
            title = stringResource(R.string.layout_clear_title),
            body = stringResource(R.string.layout_clear_body, layout.boxes.size),
            onConfirm = { clearing = false; selected = -1; commit(MossLayout.Layout()) },
            onDismiss = { clearing = false },
        )
    }
}

/** ⭐ A box's colour by what it is, the plugin's three (blue object, orange character, green text): a semantic hue. */
internal fun layoutColor(label: String): Color = when (label) {
    "character" -> Color(0xFFFF9147)
    "text" -> Color(0xFF59D97F)
    else -> Color(0xFF4A9EFF)
}

/** ⚠ The smallest box a drag makes or a resize leaves, as a fraction of the picture's side. */
private const val MIN_SIDE = 0.04

private enum class Drag { NONE, MOVE, RESIZE, DRAW }

/**
 * ⭐ GitHub #132: snap-to-align. A dragged box's edges and centre snap to the picture's edges and centre and to the
 * other boxes' edges and centres, within [TOLERANCE_DP]; the lines it snapped to are drawn while the finger is down.
 */
internal object LayoutSnap {
    const val TOLERANCE_DP = 8

    /** ⚠ Pure: the lines on one axis ([x] or y) a box other than [skip] offers, with the picture's three. */
    fun lines(boxes: List<MossLayout.Box>, skip: Int, x: Boolean): List<Double> =
        listOf(0.0, 0.5, 1.0) + boxes.filterIndexed { i, _ -> i != skip }.flatMap { b ->
            if (x) listOf(b.x1, (b.x1 + b.x2) / 2, b.x2) else listOf(b.y1, (b.y1 + b.y2) / 2, b.y2)
        }

    /** ⚠ Pure: the shift that puts the nearest of [edges] on one of [lines], within [tol], and that line; (0, null) for none. */
    fun snap(edges: List<Double>, lines: List<Double>, tol: Double): Pair<Double, Double?> {
        var best = 0.0
        var at: Double? = null
        for (e in edges) for (l in lines) {
            val d = l - e
            if (kotlin.math.abs(d) <= tol && (at == null || kotlin.math.abs(d) < kotlin.math.abs(best))) { best = d; at = l }
        }
        return best to at
    }
}

@Composable
private fun LayoutCanvas(
    layout: MossLayout.Layout,
    selected: Int,
    w: Int,
    h: Int,
    onSelect: (Int) -> Unit,
    onDone: (MossLayout.Layout) -> Unit,
) {
    val aspect = if (w > 0 && h > 0) w.toFloat() / h else 1f
    val measurer = rememberTextMeasurer()
    val tabStyle = MaterialTheme.typography.labelSmall
    val handle = with(LocalDensity.current) { 14.dp.toPx() }
    val latest by androidx.compose.runtime.rememberUpdatedState(layout)
    val latestSel by androidx.compose.runtime.rememberUpdatedState(selected)
    val border = MaterialTheme.colorScheme.outline
    val tolPx = with(LocalDensity.current) { LayoutSnap.TOLERANCE_DP.dp.toPx() }
    // The lines a drag has snapped to, (true = vertical line at x, false = horizontal at y); drawn while the finger is down.
    var guides by remember { mutableStateOf(emptyList<Pair<Boolean, Double>>()) }
    // ⭐ The layout while a finger drags, held HERE: read only in the draw, so a drag redraws this canvas and nothing else.
    // It was lifted to the panel on every move, which recomposed every box card and its text fields each frame (the user
    // asked for a smooth drag, 2026-11-19). The cards follow when the finger lifts.
    var live by remember { mutableStateOf<MossLayout.Layout?>(null) }
    val guide = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // ⚠ A tall picture is held to 360dp high, so the cards stay in reach under it.
        Canvas(
            Modifier.widthIn(max = maxWidth).heightIn(max = 360.dp).aspectRatio(aspect)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, border)
                .pointerInput(w, h) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val sz = Size(size.width.toFloat(), size.height.toFloat())
                        val fx0 = (down.position.x / sz.width).toDouble().coerceIn(0.0, 1.0)
                        val fy0 = (down.position.y / sz.height).toDouble().coerceIn(0.0, 1.0)
                        val boxes = latest.boxes
                        val sel = latestSel
                        // ⭐ The selected box's corners first (any of the four, GitHub #132: the one dot was hard to reach
                        // with a finger or the S Pen), then the boxes under the finger, top-most first; a box already
                        // selected keeps the grab, so a drag never switches its target by accident.
                        val corner = if (sel !in boxes.indices) null else boxes[sel].let { b ->
                            listOf(false to false, true to false, false to true, true to true).map { (r, d) ->
                                Triple(r, d, kotlin.math.hypot(
                                    down.position.x - ((if (r) b.x2 else b.x1) * sz.width).toFloat(),
                                    down.position.y - ((if (d) b.y2 else b.y1) * sz.height).toFloat(),
                                ))
                            }.filter { it.third < handle * 2.5f }.minByOrNull { it.third }
                        }
                        val onHandle = corner != null
                        val tolX = tolPx / sz.width.toDouble()
                        val tolY = tolPx / sz.height.toDouble()
                        val under = boxes.indices.reversed().filter { i -> boxes[i].let { fx0 in it.x1..it.x2 && fy0 in it.y1..it.y2 } }
                        val target = when {
                            onHandle -> sel
                            sel in under -> sel
                            else -> under.firstOrNull() ?: -1
                        }
                        val mode = when { onHandle -> Drag.RESIZE; target >= 0 -> Drag.MOVE; else -> Drag.DRAW }
                        var drawing = -1
                        var moved = false
                        var work = latest
                        do {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull() ?: break
                            if (!c.pressed) break
                            val dx = ((c.position.x - down.position.x) / sz.width).toDouble()
                            val dy = ((c.position.y - down.position.y) / sz.height).toDouble()
                            if (!moved && kotlin.math.hypot(c.position.x - down.position.x, c.position.y - down.position.y) < viewConfiguration.touchSlop) continue
                            moved = true
                            c.consume()
                            var snapped = emptyList<Pair<Boolean, Double>>()
                            work = when (mode) {
                                Drag.MOVE -> {
                                    val b = boxes[target]
                                    val bw = b.x2 - b.x1
                                    val bh = b.y2 - b.y1
                                    var nx = b.x1 + dx
                                    var ny = b.y1 + dy
                                    val (sx, lx) = LayoutSnap.snap(listOf(nx, nx + bw / 2, nx + bw), LayoutSnap.lines(boxes, target, true), tolX)
                                    val (sy, ly) = LayoutSnap.snap(listOf(ny, ny + bh / 2, ny + bh), LayoutSnap.lines(boxes, target, false), tolY)
                                    nx = (nx + sx).coerceIn(0.0, 1.0 - bw)
                                    ny = (ny + sy).coerceIn(0.0, 1.0 - bh)
                                    snapped = listOfNotNull(lx?.let { true to it }, ly?.let { false to it })
                                    latest.copy(boxes = boxes.toMutableList().also { it[target] = b.copy(x1 = nx, y1 = ny, x2 = nx + bw, y2 = ny + bh) })
                                }
                                Drag.RESIZE -> {
                                    val b = boxes[target]
                                    val (right, lower) = corner!!.first to corner.second
                                    val ex = (if (right) b.x2 else b.x1) + dx
                                    val ey = (if (lower) b.y2 else b.y1) + dy
                                    val (sx, lx) = LayoutSnap.snap(listOf(ex), LayoutSnap.lines(boxes, target, true), tolX)
                                    val (sy, ly) = LayoutSnap.snap(listOf(ey), LayoutSnap.lines(boxes, target, false), tolY)
                                    snapped = listOfNotNull(lx?.let { true to it }, ly?.let { false to it })
                                    val nb = b.copy(
                                        x1 = if (right) b.x1 else (ex + sx).coerceIn(0.0, b.x2 - MIN_SIDE),
                                        x2 = if (right) (ex + sx).coerceIn(b.x1 + MIN_SIDE, 1.0) else b.x2,
                                        y1 = if (lower) b.y1 else (ey + sy).coerceIn(0.0, b.y2 - MIN_SIDE),
                                        y2 = if (lower) (ey + sy).coerceIn(b.y1 + MIN_SIDE, 1.0) else b.y2,
                                    )
                                    latest.copy(boxes = boxes.toMutableList().also { it[target] = nb })
                                }
                                Drag.DRAW -> {
                                    val fx = (fx0 + dx).coerceIn(0.0, 1.0)
                                    val fy = (fy0 + dy).coerceIn(0.0, 1.0)
                                    val nb = MossLayout.Box(
                                        x1 = minOf(fx0, fx), y1 = minOf(fy0, fy),
                                        x2 = maxOf(maxOf(fx0, fx), minOf(fx0, fx) + MIN_SIDE).coerceAtMost(1.0),
                                        y2 = maxOf(maxOf(fy0, fy), minOf(fy0, fy) + MIN_SIDE).coerceAtMost(1.0),
                                    )
                                    if (drawing < 0) drawing = boxes.size
                                    latest.copy(boxes = boxes + nb)
                                }
                                Drag.NONE -> latest
                            }
                            guides = snapped
                            live = work
                            onSelect(if (mode == Drag.DRAW) drawing else target)
                        } while (true)
                        guides = emptyList()
                        live = null
                        if (moved) onDone(work)
                        else if (under.isNotEmpty()) {
                            // ⭐ A tap: the box under the finger; again where boxes overlap, the next one down.
                            val at = under.indexOf(sel)
                            onSelect(if (at >= 0) under[(at + 1) % under.size] else under.first())
                        } else onSelect(-1)
                    }
                },
        ) {
            (live ?: layout).boxes.forEachIndexed { i, b ->
                val col = layoutColor(b.label)
                val tl = Offset((b.x1 * size.width).toFloat(), (b.y1 * size.height).toFloat())
                val bs = Size(((b.x2 - b.x1) * size.width).toFloat(), ((b.y2 - b.y1) * size.height).toFloat())
                drawRect(col.copy(alpha = if (i == selected) 0.30f else 0.16f), tl, bs)
                drawRect(col, tl, bs, style = Stroke(if (i == selected) 3.dp.toPx() else 1.5.dp.toPx()))
                // ⭐ The numbered tab: the box's number on its colour, what the cards below are numbered by. Set in from
                // the corner so the selected box's corner dot does not cover it.
                val t = measurer.measure("${i + 1}", tabStyle)
                val tab = tl + Offset(handle, 0f)
                drawRect(col, tab, Size(t.size.width + 8.dp.toPx(), t.size.height + 2.dp.toPx()))
                drawText(t, color = Color.Black, topLeft = tab + Offset(4.dp.toPx(), 1.dp.toPx()))
                if (i == selected) {
                    // ⭐ A dot on every corner: each one resizes (GitHub #132).
                    for (cx in listOf(tl.x, tl.x + bs.width)) for (cy in listOf(tl.y, tl.y + bs.height)) {
                        drawCircle(Color.White, handle * 0.8f, Offset(cx, cy))
                        drawCircle(col, handle * 0.55f, Offset(cx, cy))
                    }
                }
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            for ((vertical, at) in guides) {
                if (vertical) drawLine(guide, Offset((at * size.width).toFloat(), 0f), Offset((at * size.width).toFloat(), size.height), 1.5.dp.toPx(), pathEffect = dash)
                else drawLine(guide, Offset(0f, (at * size.height).toFloat()), Offset(size.width, (at * size.height).toFloat()), 1.5.dp.toPx(), pathEffect = dash)
            }
        }
    }
}

@Composable
private fun BoxCard(
    i: Int,
    b: MossLayout.Box,
    labels: List<String>,
    selected: Boolean,
    w: Int,
    h: Int,
    onSelect: () -> Unit,
    onChange: (MossLayout.Box) -> Unit,
    onDelete: () -> Unit,
) {
    val col = layoutColor(b.label)
    Column(
        Modifier.fillMaxWidth()
            .border(if (selected) 2.dp else 1.dp, if (selected) col else MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ⭐ The box's colour beside its number, so the card reads as THAT box on the picture (the user asked what
            // "Box 1" was, 2026-11-19).
            androidx.compose.foundation.layout.Box(Modifier.padding(end = 8.dp).size(14.dp).background(col, MaterialTheme.shapes.extraSmall))
            Text(
                stringResource(R.string.layout_box_title, i + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.layout_delete_box, i + 1), tint = MaterialTheme.colorScheme.error)
            }
        }
        // ⚠ Once, under the first card: what a card is for, not repeated on every one.
        if (i == 0) Text(stringResource(R.string.layout_box_hint, i + 1), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Chooser(
            stringResource(R.string.layout_kind), null, labels,
            labels[MossLayout.LABELS.indexOf(b.label).coerceAtLeast(0)],
            { picked -> onSelect(); onChange(b.copy(label = MossLayout.LABELS[labels.indexOf(picked).coerceAtLeast(0)])) },
        )
        OutlinedTextField(
            value = b.caption,
            onValueChange = { onChange(b.copy(caption = it)) },
            label = { Text(stringResource(R.string.layout_caption)) },
            modifier = Modifier.fillMaxWidth(),
        )
        // ⭐ GitHub #132: the box's place and size typed, in the picture's own pixels. Written when the field is left or
        // Done is pressed, so a half-typed number never squashes the box.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val pw = w.coerceAtLeast(1)
            val ph = h.coerceAtLeast(1)
            PxField("X", (b.x1 * pw).toInt(), Modifier.weight(1f)) { v ->
                val bw = b.x2 - b.x1
                val nx = (v.toDouble() / pw).coerceIn(0.0, 1.0 - bw)
                onChange(b.copy(x1 = nx, x2 = nx + bw))
            }
            PxField("Y", (b.y1 * ph).toInt(), Modifier.weight(1f)) { v ->
                val bh = b.y2 - b.y1
                val ny = (v.toDouble() / ph).coerceIn(0.0, 1.0 - bh)
                onChange(b.copy(y1 = ny, y2 = ny + bh))
            }
            PxField("W", ((b.x2 - b.x1) * pw).toInt(), Modifier.weight(1f)) { v ->
                onChange(b.copy(x2 = (b.x1 + v.toDouble() / pw).coerceIn(b.x1 + MIN_SIDE, 1.0)))
            }
            PxField("H", ((b.y2 - b.y1) * ph).toInt(), Modifier.weight(1f)) { v ->
                onChange(b.copy(y2 = (b.y1 + v.toDouble() / ph).coerceIn(b.y1 + MIN_SIDE, 1.0)))
            }
        }
        if (b.label == "text") {
            OutlinedTextField(
                value = b.text,
                onValueChange = { onChange(b.copy(text = it)) },
                label = { Text(stringResource(R.string.layout_words)) },
                isError = b.needsText,
                supportingText = if (b.needsText) ({ Text(stringResource(R.string.layout_words_needed)) }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One whole-pixel field of a box card; [onSet] runs when it is left or Done is pressed, with a number in it. */
@Composable
private fun PxField(label: String, value: Int, modifier: Modifier, onSet: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    fun set() { text.trim().toIntOrNull()?.takeIf { it != value && it >= 0 }?.let(onSet) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.filter(Char::isDigit).take(5) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { set() }),
        modifier = modifier.onFocusChanged { if (!it.isFocused) set() },
    )
}

/**
 * ⭐ The tile: the boxes drawn small in their colours, or a + with none. ⚠ Drawn here on a small bitmap, not by a
 * composable, because a tile's thumbnail is an [ImageBitmap] ([EditorTile]).
 */
@Composable
internal fun mossLayoutTile(param: String?, w: Int, h: Int, panel: @Composable () -> Unit): EditorTile {
    val layout = remember(param) { MossLayout.read(param) }
    val bg = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val thumb = remember(param, w, h, bg) { if (layout.boxes.isEmpty()) null else layoutThumb(layout, w, h, bg) }
    return EditorTile(stringResource(R.string.layout_tab), thumb, empty = layout.boxes.isEmpty(), panel = panel)
}

internal fun layoutThumb(layout: MossLayout.Layout, w: Int, h: Int, background: Int): ImageBitmap {
    val long = 192f
    val s = long / maxOf(w, h, 1)
    val bw = (w * s).toInt().coerceAtLeast(16)
    val bh = (h * s).toInt().coerceAtLeast(16)
    val bmp = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    c.drawColor(background)
    val fill = android.graphics.Paint()
    val edge = android.graphics.Paint().apply { style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f }
    for (b in layout.boxes) {
        val col = layoutColor(b.label)
        val r = android.graphics.RectF((b.x1 * bw).toFloat(), (b.y1 * bh).toFloat(), (b.x2 * bw).toFloat(), (b.y2 * bh).toFloat())
        fill.color = col.copy(alpha = 0.3f).let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) }
        edge.color = android.graphics.Color.rgb((col.red * 255).toInt(), (col.green * 255).toInt(), (col.blue * 255).toInt())
        c.drawRect(r, fill)
        c.drawRect(r, edge)
    }
    return bmp.asImageBitmap()
}
