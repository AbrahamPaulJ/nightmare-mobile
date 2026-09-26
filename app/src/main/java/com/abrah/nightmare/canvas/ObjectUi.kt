package com.abrah.nightmare.canvas

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.abrah.nightmare.AddObjects
import com.abrah.nightmare.ui.LogTextStyle
import com.abrah.nightmare.MaskOp
import com.abrah.nightmare.MaskState
import com.abrah.nightmare.MaskTaps
import com.abrah.nightmare.segment.Parser
import com.abrah.nightmare.segment.Segmenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ One object waiting to be placed: its record, and the pixels cut out.
 * [index] is where it sits in the node's list when it is being MOVED, null when
 * it is new.
 */
internal class Placing(val obj: AddObjects.Placed, val cut: Bitmap, val index: Int?)

/**
 * ⭐⭐⭐ **Place an object on the photo** — drag to move, pinch to resize (the
 * user's calls, 2026-09-23). The WHOLE photo, zoomed out, so where it goes is
 * judged against the whole picture rather than the crop.
 *
 * ⚠ Sized by the mask editor's own rule ([HEIGHT_SHARE], [MIN_FRAME]) so the
 * window does not jump between painting and placing.
 * ⚠ The gesture is consumed from the first event, like [MaskEditor]'s, so the
 * scrolling sheet never takes a drag meant for the object.
 */
@Composable
internal fun ObjectPlacer(
    photo: ImageBitmap,
    placing: Placing,
    onChange: (AddObjects.Placed) -> Unit,
    modifier: Modifier = Modifier,
) {
    val aspect = photo.width.toFloat() / photo.height.coerceAtLeast(1)
    val cutAspect = placing.cut.width.toFloat() / placing.cut.height.coerceAtLeast(1)
    val current by rememberUpdatedState(placing.obj)
    val change by rememberUpdatedState(onChange)
    val cutImage = remember(placing.cut) { placing.cut.asImageBitmap() }
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val cap = (screenH * HEIGHT_SHARE).coerceAtLeast(MIN_FRAME)
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val frameW = minOf(maxWidth, cap * aspect)
        Box(
            Modifier
                .width(frameW)
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(8.dp))
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val first = awaitPointerEvent(PointerEventPass.Main)
                            if (first.changes.none { it.pressed }) continue
                            first.changes.forEach { it.consume() }
                            var startSpread = 0f
                            var startW = current.w
                            while (true) {
                                val e = awaitPointerEvent(PointerEventPass.Main)
                                val down = e.changes.filter { it.pressed }
                                if (down.isEmpty()) break
                                val o = current
                                val pan = down.fold(Offset.Zero) { acc, c -> acc + (c.position - c.previousPosition) } / down.size.toFloat()
                                var w = o.w
                                if (down.size > 1) {
                                    val c = down.fold(Offset.Zero) { acc, p -> acc + p.position } / down.size.toFloat()
                                    val spread = down.map { (it.position - c).getDistance() }.average().toFloat()
                                    if (startSpread == 0f) { startSpread = spread; startW = o.w }
                                    if (startSpread > 0f) w = (startW * spread / startSpread).coerceIn(0.03f, 1.5f)
                                } else startSpread = 0f
                                // ⚠ Resized about its CENTRE, so a pinch does not
                                // walk the object off towards the top-left.
                                val hOld = o.w * size.width / cutAspect / size.height
                                val hNew = w * size.width / cutAspect / size.height
                                change(
                                    o.copy(
                                        x = o.x + pan.x / size.width - (w - o.w) / 2f,
                                        y = o.y + pan.y / size.height - (hNew - hOld) / 2f,
                                        w = w,
                                    )
                                )
                                e.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Canvas(Modifier.fillMaxSize()) {
                val o = placing.obj
                val w = o.w * size.width
                val h = w / cutAspect
                val left = o.x * size.width
                val top = o.y * size.height
                drawImage(
                    cutImage,
                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                    dstSize = IntSize(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1)),
                )
            }
        }
    }
}

/**
 * ⭐⭐⭐ **Choose your object(s)** — the source photo, with the mask window's own
 * tools: brush, erase, undo, redo, invert, clear, and Tap / Auto mask when this node
 * has them enabled (the user's design, 2026-09-23). ⚠ The SAME [MaskEditor]
 * draws and paints; only the state is local, because nothing is stored until
 * Done.
 *
 * [onDone] gets the chosen mask as ops, in the source photo's coordinates.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ObjectChooser(
    uri: String,
    tapEnabled: Boolean,
    pickEnabled: Boolean,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val b = withContext(Dispatchers.IO) { AddObjects.load(context, uri) }
        if (b == null) failed = true else source = b
    }
    var mask by remember { mutableStateOf(MaskState()) }
    // ⭐ The mask window's own undo and redo ([EditHistory]).
    val history = rememberEditHistory(uri, mask)
    var tool by remember { mutableStateOf(MaskTool.BRUSH) }
    var radius by remember { mutableStateOf(0.06f) }
    // ⚠ Bumped when a tap or a pick lands in its model's cache, so the view
    // re-resolves (the resolve below reads the caches only).
    var warmed by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val canTap = tapEnabled && Segmenter.installed
    val canPick = pickEnabled && Parser.installed

    val height = rememberDialogHeight()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().height(height), color = MaterialTheme.colorScheme.background) {
            // ⚠⚠⚠ **Cancel and Done are PINNED under the content**, which scrolls
            // above them. They were the last item in one scrolling column, and
            // with Auto mask on its chips pushed them off the bottom — where a
            // drag could not reach them, because the picture takes every drag for
            // the brush. Reported 2026-09-24.
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Choose your object(s)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Mask what to take. Separate areas become separate objects, each placed on its own.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val src = source
                    when {
                        failed -> com.abrah.nightmare.ui.ErrorNotice("this photo cannot be read — pick another")
                        src == null -> Text("opening the photo…", style = LogTextStyle)
                        else -> {
                            val image = remember(src) { src.asImageBitmap() }
                            val shown = remember(mask, warmed, src) {
                                MaskTaps.resolve(mask, pick = { t -> Parser.cachedPick(src, t) }) { x, y ->
                                    Segmenter.cached(src, x, y)?.candidates
                                }
                            }
                            MaskEditor(
                                source = image,
                                state = shown,
                                tool = tool,
                                brushRadiusFrac = radius,
                                onStroke = { s ->
                                    mask = mask.plus(if (tool == MaskTool.ERASE) MaskOp.Erase(s) else MaskOp.Stroke(s))
                                },
                                onTap = { x, y ->
                                    if (!busy) {
                                        busy = true
                                        scope.launch {
                                            val found = withContext(Dispatchers.Default) {
                                                runCatching { Segmenter.segment(context, src, x, y) }.getOrNull()
                                            }
                                            if (found != null) mask = mask.plus(MaskOp.Tap(x, y, 0))
                                            busy = false
                                            warmed++
                                        }
                                    }
                                },
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                FilterChip(tool == MaskTool.BRUSH, { tool = MaskTool.BRUSH }, label = {
                                    Icon(com.abrah.nightmare.ui.BrushIcon, "Paint", Modifier.size(18.dp))
                                })
                                FilterChip(tool == MaskTool.ERASE, { tool = MaskTool.ERASE }, label = {
                                    Icon(com.abrah.nightmare.ui.EraserIcon, "Erase", Modifier.size(18.dp))
                                })
                                if (canTap) FilterChip(tool == MaskTool.TAP, { tool = MaskTool.TAP }, label = {
                                    Icon(com.abrah.nightmare.ui.TapObjectIcon, "Select an object", Modifier.size(18.dp))
                                })
                                IconButton(onClick = { history.undo(mask) { mask = it } }, enabled = history.canUndo) {
                                    Icon(com.abrah.nightmare.ui.UndoIcon, "Undo the last stroke", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { history.redo(mask) { mask = it } }, enabled = history.canRedo) {
                                    Icon(com.abrah.nightmare.ui.RedoIcon, "Redo", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { mask = mask.plus(MaskOp.Invert) }) {
                                    Icon(com.abrah.nightmare.ui.InvertMaskIcon, "Invert the mask", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { mask = mask.cleared() }, enabled = !mask.isEmpty) {
                                    Icon(com.abrah.nightmare.ui.ClearLayersIcon, "Clear the mask", Modifier.size(18.dp))
                                }
                            }
                            if (tool == MaskTool.BRUSH || tool == MaskTool.ERASE) {
                                Slider(value = radius, onValueChange = { radius = it }, valueRange = 0.02f..0.25f)
                            }
                            if (canPick) {
                                val picked = mask.ops.filterIsInstance<MaskOp.Pick>().map { it.target }.toSet()
                                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Parser.TARGETS.forEach { t ->
                                        FilterChip(
                                            selected = t.id in picked,
                                            enabled = !busy,
                                            onClick = {
                                                if (t.id in picked) {
                                                    mask = mask.copy(ops = mask.ops.filterNot { it is MaskOp.Pick && it.target == t.id })
                                                } else {
                                                    busy = true
                                                    scope.launch {
                                                        withContext(Dispatchers.Default) {
                                                            runCatching { Parser.pick(context, src, t.id) }
                                                        }
                                                        mask = mask.plus(MaskOp.Pick(t.id))
                                                        busy = false
                                                        warmed++
                                                    }
                                                }
                                            },
                                            label = { Text(t.label) },
                                        )
                                    }
                                }
                            }
                            if (busy) Text("reading the photo…", style = LogTextStyle)
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = { onDone(mask.encode()) }, enabled = source != null && !mask.isEmpty && !busy) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

/**
 * ⭐⭐⭐ **A node's placed objects, cut out** — THE one way a surface gets them.
 * The mask window, the node view's Crop and Mask tiles and (through the view
 * model) the canvas box all show objects; they used to decide separately, and
 * the tiles never showed them at all (reported 2026-09-23).
 *
 * ⚠ Off the main thread: a cut decodes the source photo and may segment it.
 */
@Composable
internal fun rememberObjectCuts(
    node: com.abrah.nightmare.Node,
    type: com.abrah.nightmare.NodeType?,
): List<Pair<AddObjects.Placed, Bitmap>> {
    val context = LocalContext.current
    val params = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val objects = AddObjects.of(params)
    val key = params[AddObjects.ENABLE] + "|" + params[AddObjects.PARAM].orEmpty()
    val cuts by androidx.compose.runtime.produceState<List<Pair<AddObjects.Placed, Bitmap>>>(emptyList(), key) {
        value = if (objects.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            AddObjects.cuts(
                objects,
                load = { AddObjects.load(context, it) },
                resolve = { src, m -> AddObjects.resolveOn(context, src, m) },
            ).first
        }
    }
    return cuts
}

/**
 * ⭐⭐⭐ **How tall a full-height `Dialog` may be** — the frame the system
 * actually gives a window, not the display.
 *
 * ⚠⚠⚠ A Compose `Dialog` measures its content against the DISPLAY (2340 px on
 * the S25 Ultra) and asks for a window that size, but the window manager
 * confines it between the status and navigation bars (96..2205, 2109 px) —
 * so whatever sat in the bottom 231 px was drawn off screen. That is where
 * Choose your object(s)' Cancel and Done were, and the inpaint popup lost its
 * bottom 44 px the same way. Read off `dumpsys window` (requested 1080x2340,
 * frame [0,96][1080,2205]) on 2026-09-24, after a `decorFitsSystemWindows`
 * and `systemBarsPadding()` fix changed nothing.
 *
 * ⚠ Every full-height dialog sizes itself with this, so none of them depends
 * on the display and the window agreeing.
 */
@Composable
internal fun rememberDialogHeight(): androidx.compose.ui.unit.Dp {
    val view = androidx.compose.ui.platform.LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val config = LocalConfiguration.current
    return remember(view, density, config) {
        val r = android.graphics.Rect()
        view.getWindowVisibleDisplayFrame(r)
        with(density) {
            (if (r.height() > 0) r.height() else view.resources.displayMetrics.heightPixels).toDp()
        }
    }
}
