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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
 * drag its corner dot to resize it, tap to select (a second tap where boxes overlap picks the next one down). Under it,
 * one card per box: what it is (object, character, text), what it looks like, and for text the exact words.
 *
 * ⚠ The boxes are kept as fractions on the node ([MossLayout.PARAM]), so changing the picture's size keeps them where
 * they were drawn. A drag writes the node once, when the finger lifts; typing writes as it goes, as every field does.
 */
@Composable
internal fun MossLayoutPanel(param: String?, w: Int, h: Int, onSave: (String) -> Unit) {
    var layout by remember(param) { mutableStateOf(MossLayout.read(param)) }
    var selected by remember { mutableIntStateOf(-1) }
    var clearing by remember { mutableStateOf(false) }
    fun commit(l: MossLayout.Layout) { layout = l; onSave(MossLayout.write(l)) }
    val labels = listOf(
        stringResource(R.string.layout_object), stringResource(R.string.layout_character), stringResource(R.string.layout_text),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.layout_note), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LayoutCanvas(layout, selected, w, h, onSelect = { selected = it }, onLive = { layout = it }, onDone = { commit(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StudioOutlinedButton(onClick = {
                // ⭐ A new box in the middle, a third of the picture, offset so a second one is not hidden under the first.
                val k = (layout.boxes.size % 4) * 0.05
                commit(layout.copy(boxes = layout.boxes + MossLayout.Box(x1 = 0.33 + k, y1 = 0.33 + k, x2 = 0.66 + k, y2 = 0.66 + k)))
                selected = layout.boxes.lastIndex
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.layout_add_box), modifier = Modifier.padding(start = 4.dp))
            }
            if (layout.boxes.isNotEmpty()) {
                StudioTextButton(onClick = { clearing = true }, colors = dangerOutlinedColors()) {
                    Text(stringResource(R.string.layout_clear))
                }
            }
        }
        layout.boxes.forEachIndexed { i, b ->
            BoxCard(
                i, b, labels, selected == i,
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

@Composable
private fun LayoutCanvas(
    layout: MossLayout.Layout,
    selected: Int,
    w: Int,
    h: Int,
    onSelect: (Int) -> Unit,
    onLive: (MossLayout.Layout) -> Unit,
    onDone: (MossLayout.Layout) -> Unit,
) {
    val aspect = if (w > 0 && h > 0) w.toFloat() / h else 1f
    val measurer = rememberTextMeasurer()
    val tabStyle = MaterialTheme.typography.labelSmall
    val handle = with(LocalDensity.current) { 14.dp.toPx() }
    val latest by androidx.compose.runtime.rememberUpdatedState(layout)
    val latestSel by androidx.compose.runtime.rememberUpdatedState(selected)
    val border = MaterialTheme.colorScheme.outline
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
                        // ⭐ The selected box's corner dot first, then the boxes under the finger, top-most first; a box
                        // already selected keeps the grab, so a drag never switches its target by accident.
                        val onHandle = sel in boxes.indices && boxes[sel].let { b ->
                            kotlin.math.abs(down.position.x - (b.x2 * sz.width).toFloat()) < handle * 2 &&
                                kotlin.math.abs(down.position.y - (b.y2 * sz.height).toFloat()) < handle * 2
                        }
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
                            work = when (mode) {
                                Drag.MOVE -> {
                                    val b = boxes[target]
                                    val bw = b.x2 - b.x1
                                    val bh = b.y2 - b.y1
                                    val nx = (b.x1 + dx).coerceIn(0.0, 1.0 - bw)
                                    val ny = (b.y1 + dy).coerceIn(0.0, 1.0 - bh)
                                    latest.copy(boxes = boxes.toMutableList().also { it[target] = b.copy(x1 = nx, y1 = ny, x2 = nx + bw, y2 = ny + bh) })
                                }
                                Drag.RESIZE -> {
                                    val b = boxes[target]
                                    val nx2 = (b.x2 + dx).coerceIn(b.x1 + MIN_SIDE, 1.0)
                                    val ny2 = (b.y2 + dy).coerceIn(b.y1 + MIN_SIDE, 1.0)
                                    latest.copy(boxes = boxes.toMutableList().also { it[target] = b.copy(x2 = nx2, y2 = ny2) })
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
                            onLive(work)
                            onSelect(if (mode == Drag.DRAW) drawing else target)
                        } while (true)
                        if (moved) onDone(work)
                        else if (under.isNotEmpty()) {
                            // ⭐ A tap: the box under the finger; again where boxes overlap, the next one down.
                            val at = under.indexOf(sel)
                            onSelect(if (at >= 0) under[(at + 1) % under.size] else under.first())
                        } else onSelect(-1)
                    }
                },
        ) {
            layout.boxes.forEachIndexed { i, b ->
                val col = layoutColor(b.label)
                val tl = Offset((b.x1 * size.width).toFloat(), (b.y1 * size.height).toFloat())
                val bs = Size(((b.x2 - b.x1) * size.width).toFloat(), ((b.y2 - b.y1) * size.height).toFloat())
                drawRect(col.copy(alpha = if (i == selected) 0.30f else 0.16f), tl, bs)
                drawRect(col, tl, bs, style = Stroke(if (i == selected) 3.dp.toPx() else 1.5.dp.toPx()))
                // ⭐ The numbered tab: the box's number on its colour, what the cards below are numbered by.
                val t = measurer.measure("${i + 1}", tabStyle)
                drawRect(col, tl, Size(t.size.width + 8.dp.toPx(), t.size.height + 2.dp.toPx()))
                drawText(t, color = Color.Black, topLeft = tl + Offset(4.dp.toPx(), 1.dp.toPx()))
                if (i == selected) {
                    drawCircle(Color.White, handle * 0.8f, Offset(tl.x + bs.width, tl.y + bs.height))
                    drawCircle(col, handle * 0.55f, Offset(tl.x + bs.width, tl.y + bs.height))
                }
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
            Text(
                stringResource(R.string.layout_box_title, i + 1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.layout_delete_box, i + 1), tint = MaterialTheme.colorScheme.error)
            }
        }
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
