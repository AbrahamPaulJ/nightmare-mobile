package com.abrah.nightmare.canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.CropGeometry
import com.abrah.nightmare.CropNode
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.MaskRaster
import com.abrah.nightmare.Widget
import com.abrah.nightmare.SizeDemand
import com.abrah.nightmare.requiredOutputSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.items
import com.abrah.nightmare.ui.LogTextStyle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card

/*
 * ⭐ THE INPAINT MASK WINDOW — the mask editor's toolbar, its layers and its
 * slider, moved out of `NodeInspector.kt` on 2026-09-26 when that file passed
 * 4,900 lines. Behaviour unchanged; `docs/ADD-OBJECTS.md` and `docs/UI.md` own
 * what it does.
 */

private enum class MaskParam(val label: String) { SIZE("Size"), GROW("Grow"), FEATHER("Feather") }

/**
 * ⭐⭐ DreamUI's shared mask slider: a dropdown naming the parameter, the track,
 * and the value in pixels at 512 (`radius * 2 * 512` for a brush, as DreamUI).
 *
 * ⚠ The list is keyed on the OPTIONS, not the tool — brush and eraser share
 * theirs, so switching between them keeps the selection.
 */
@Composable
private fun MaskParamSlider(
    tool: MaskTool,
    brush: Float,
    onBrush: (Float) -> Unit,
    grow: Float,
    growMin: Float,
    growMax: Float,
    onGrow: ((Float) -> Unit)?,
    feather: Float,
    featherMax: Float,
    onFeather: ((Float) -> Unit)?,
    /** ⭐ When the finger lifts off the Size track — for a value stored once per drag. */
    onBrushDone: (() -> Unit)? = null,
) {
    val options = when (tool) {
        MaskTool.BRUSH, MaskTool.ERASE -> listOfNotNull(MaskParam.SIZE, MaskParam.FEATHER.takeIf { onFeather != null })
        MaskTool.TAP, MaskTool.PICK -> listOfNotNull(MaskParam.GROW.takeIf { onGrow != null }, MaskParam.FEATHER.takeIf { onFeather != null })
    }
    if (options.isEmpty()) return
    var param by remember(options) { mutableStateOf(options.first()) }
    var menu by remember { mutableStateOf(false) }
    val (range, value) = when (param) {
        MaskParam.SIZE -> BRUSH_MIN..BRUSH_MAX to brush
        MaskParam.GROW -> growMin..growMax to grow
        MaskParam.FEATHER -> 0f..featherMax to feather
    }
    val shown = when (param) {
        MaskParam.SIZE -> "${(brush * 2 * 512).roundToInt()} px"
        MaskParam.GROW -> "${(grow * 512).roundToInt()} px"
        MaskParam.FEATHER -> "${(feather * 512).roundToInt()} px"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TextButton(
                onClick = { menu = true },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
            ) {
                Text(param.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Icon(
                    androidx.compose.material.icons.Icons.Filled.ArrowDropDown,
                    contentDescription = "choose what the slider sets",
                    modifier = Modifier.size(18.dp),
                )
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (o in options) {
                    DropdownMenuItem(text = { Text(o.label) }, onClick = { param = o; menu = false })
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                when (param) {
                    MaskParam.SIZE -> onBrush(v)
                    MaskParam.GROW -> onGrow?.invoke(v)
                    MaskParam.FEATHER -> onFeather?.invoke(v)
                }
            },
            valueRange = range,
            onValueChangeFinished = { if (param == MaskParam.SIZE) onBrushDone?.invoke() },
            modifier = Modifier.weight(1f),
        )
        Text(
            shown,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.width(56.dp),
        )
    }
}

/**
 * The mask editor plus the three controls it needs: brush or eraser, brush
 * size, and undo/clear.
 *
 * ⚠⚠ **Every write here is ONE param change on a finished gesture.** The
 * cropper's autosave crash came from a widget that wrote per pointer event
 * (`notes/HANDOFF.md` §5); [MaskEditor] commits on stroke end for exactly that
 * reason, and the brush-size slider writes a widget param like any other.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MaskToolbar(
    nodeId: String,
    node: com.abrah.nightmare.Node,
    source: ImageBitmap,
    /**
     * ⚠⚠ Where [source] sits on the picture the mask is STORED against — the
     * crop for an inpaint sampler, [CropRect.WHOLE] for a bare `image.mask`.
     * Every stroke crosses this boundary in both directions ([MaskFraming]).
     */
    frame: CropRect,
    /** ⚠ The WHOLE photo the mask is stored against — what a tap segments. */
    photo: ImageBitmap,
    /** ⭐ OUTPAINT: the photo's extent in [source]; the rest is locked padding. */
    padding: com.abrah.nightmare.Frame? = null,
    onTapMask: (node: String, x: Float, y: Float, done: (String?) -> Unit) -> Unit,
    /** ⭐ For the shared slider's grow and feather, which are the node's params. */
    type: NodeType? = null,
    onSetParam: (String, String) -> Unit = { _, _ -> },
    /**
     * ⚠⚠ A TRANSFORM, not a setter. See `HarnessViewModel.editMask`: writing an
     * absolute ops string computed from `node` is what made fast painting drop
     * strokes, because `node` is whatever the last recomposition captured.
     */
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit,
    /** ⚠ Whether the segmenter weights are on the phone — for the row below the slider. */
    segmenterInstalled: Boolean = true,
    /**
     * ⭐⭐⭐ **The SAME row the Models tab draws** — so the download beside
     * the checkbox has a size, a progress bar and a Cancel, like every other
     * download in the app (`docs/UI.md` §8.2).
     *
     * ⚠⚠ It was a bare `TextButton` reading "Download"/"Delete", with the
     * installed state read from a `remember(vm.working)` snapshot that neither
     * install nor delete invalidates — so the button did nothing visible, showed
     * no progress, and still said the same thing afterwards. Reported from the
     * phone 2026-09-22.
     */
    segmenterRow: com.abrah.nightmare.ui.ToolRow? = null,
    onCancelSegmenter: (() -> Unit)? = null,
    busy: Boolean = false,
    /** ⭐ Fetch them from here; null hides the button (a golden has no VM). */
    onInstallSegmenter: (() -> Unit)? = null,
    /** ⭐ …and remove them, which is the other half of saying they are here. */
    onDeleteSegmenter: (() -> Unit)? = null,
    /**
     * ⭐⭐ Pick a target BY NAME (`docs/SEGMENTER.md` §8). Everything below is
     * the parser's half of exactly what the five above are for the segmenter —
     * a switch, a tool, and the model the tool needs.
     */
    onPickMask: ((node: String, target: String, done: (String?) -> Unit) -> Unit)? = null,
    parserInstalled: Boolean = true,
    parserRow: com.abrah.nightmare.ui.ToolRow? = null,
    onInstallParser: (() -> Unit)? = null,
    onDeleteParser: (() -> Unit)? = null,
    onCancelParser: (() -> Unit)? = null,
) {
    var tool by remember { mutableStateOf(MaskTool.BRUSH) }
    // ⚠ Local, not a graph param: the brush size is how you are working right
    // now, not a property of the mask. Saving it into the workflow would make a
    // reopened graph carry someone else's finger.
    // ⚠⚠ **0.08, DreamUI's default, and the range and readout are its too.**
    // They were 0.06 and 0.01..0.25 shown as a percentage — close enough to look
    // deliberate and different enough that the same drag produced a different
    // stroke in the two apps. Asked for from the phone, 2026-09-10: use
    // DreamUI's brush properties. Its numbers are `brushRadiusFrac = 0.08f`,
    // range `0.02f..0.25f`, shown as `brush * 2 * 512` px.
    var radius by remember { mutableStateOf(BRUSH_DEFAULT) }

    // ⚠ The stored mask is in the PHOTO's coordinates; the editor works in
    // [source]'s. Converting here rather than inside [MaskEditor] keeps that
    // component honest — it paints on the picture it is given, in that
    // picture's terms, and knows nothing about crops.
    val stored = com.abrah.nightmare.MaskNode.stateOf(node)
    // ⭐⭐ Tapped regions first, in the PHOTO's space, then framed like strokes.
    // ⚠ From the cache synchronously — a tap made this session is there — and
    // off the main thread for the rest (a reopened flow: up to a second a photo).
    val seg = com.abrah.nightmare.segment.Segmenter
    val context = androidx.compose.ui.platform.LocalContext.current
    val photoBmp = photo.asAndroidBitmap()

    // ⭐⭐⭐ **Add Objects** ([com.abrah.nightmare.AddObjects]) — the objects
    // already placed, cut out in the background, then drawn onto the picture
    // being painted. ⚠ The SAME cut and ring functions the sampler renders with.
    val nodeParams = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
    val objectsOn = nodeParams[com.abrah.nightmare.AddObjects.ENABLE].equals("true", ignoreCase = true)
    val objects = com.abrah.nightmare.AddObjects.of(nodeParams)
    val objectCuts = rememberObjectCuts(node, type)
    var placing by remember { mutableStateOf<Placing?>(null) }
    var placeQueue by remember { mutableStateOf<List<Placing>>(emptyList()) }
    var choosingUri by remember { mutableStateOf<String?>(null) }
    var objectNote by remember { mutableStateOf<String?>(null) }
    val objectScope = rememberCoroutineScope()
    val pickObjectSource = rememberImagePick { choosingUri = it }
    // ⭐ The layer one Add object fills: every region cut in that go lands on it.
    var placingLayer by remember(nodeId) { mutableIntStateOf(com.abrah.nightmare.AddObjects.FIRST_LAYER) }

    // ⭐⭐⭐ **The LAYERS** — the user's design, 2026-09-26 (`docs/ADD-OBJECTS.md`).
    // Layer 1 is the IMAGE, and every stroke, tap, pick, invert and clear is
    // made there. Each Add object is a layer of its own (2, then 3) whose
    // objects are selected, moved, resized, turned, flipped and removed —
    // never painted; the ring round each is drawn for it.
    // ⚠ Hidden while placing: the ✓ has not put anything on a layer yet.
    val objectLayers = com.abrah.nightmare.AddObjects.layers(objects)
    val layersShown = objectsOn && objects.isNotEmpty() && placing == null
    var layer by remember(nodeId) { mutableIntStateOf(com.abrah.nightmare.AddObjects.IMAGE_LAYER) }
    val active = if (layersShown && layer in objectLayers) layer else com.abrah.nightmare.AddObjects.IMAGE_LAYER
    val onObjects = active != com.abrah.nightmare.AddObjects.IMAGE_LAYER
    var selected by remember(nodeId) { mutableStateOf<Int?>(null) }
    val selectedObj = selected?.let { i -> objects.getOrNull(i)?.takeIf { it.layer == active } }
    if (selected != null && selectedObj == null) selected = null
    var deletingLayer by remember { mutableStateOf(false) }
    // ⭐ The layer's ring width while its slider is held — written once, on release,
    // so a drag is one Undo step and not a hundred.
    var ringDraft by remember(nodeId, active) { mutableStateOf<Float?>(null) }
    val ring = ringDraft ?: objects.firstOrNull { it.layer == active }?.ring ?: com.abrah.nightmare.AddObjects.RING_DEFAULT
    val ringInFrame = ring / frame.w.coerceAtLeast(1e-3f)
    val shownObjects = ringDraft?.let { r -> objects.map { if (it.layer == active) it.copy(ring = r) else it } } ?: objects
    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
    fun write(params: Map<String, String>) = params.forEach { (k, v) -> onSetParam(k, v) }
    // ⭐⭐ A clean ring for [ids] at the top of the image layer — after a keep,
    // move, turn, flip, resize, ring width or Reset. Edits to the old ring stay
    // where they were painted (the user's call, 2026-09-26).
    fun freshRings(ids: Collection<Int>) =
        onEditMask(nodeId) { com.abrah.nightmare.AddObjects.freshRings(it, ids) }
    fun dropRings(ids: Collection<Int>) =
        onEditMask(nodeId) { com.abrah.nightmare.AddObjects.dropRings(it, ids) }

    fun framed(resolved: com.abrah.nightmare.MaskState) =
        com.abrah.nightmare.MaskFraming.toFrame(resolved, frame.x, frame.y, frame.w, frame.h)
    val par = com.abrah.nightmare.segment.Parser
    // ⭐⭐ The objects' rings are ops on THIS layer ([com.abrah.nightmare.MaskOp.ObjectRing]),
    // drawn round where each object sits now — the sampler's own rule
    // ([com.abrah.nightmare.AddObjects.resolveRings]). ⚠ A ring is cached per
    // cut, width and turn, so this stays cheap on the main thread after the
    // first draw.
    fun ringed(m: com.abrah.nightmare.MaskState) =
        com.abrah.nightmare.AddObjects.resolveRings(m, shownObjects, objectCuts, photo.width, photo.height)
    val quick = remember(stored, frame, photo, shownObjects, objectCuts) {
        framed(
            com.abrah.nightmare.MaskTaps.resolve(
                ringed(stored),
                pick = { t -> par.cachedPick(photoBmp, t) },
            ) { x, y -> seg.cached(photoBmp, x, y)?.candidates },
        )
    }
    var full by remember(stored, frame, photo, shownObjects, objectCuts) { mutableStateOf<com.abrah.nightmare.MaskState?>(null) }
    if ((com.abrah.nightmare.MaskTaps.hasTaps(stored) && seg.installed) ||
        (com.abrah.nightmare.MaskTaps.hasPicks(stored) && par.installed)
    ) {
        androidx.compose.runtime.LaunchedEffect(stored, frame, photo, shownObjects, objectCuts) {
            full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    framed(
                        com.abrah.nightmare.MaskTaps.resolve(
                            ringed(stored),
                            pick = { t -> par.pick(context, photoBmp, t) },
                        ) { x, y -> seg.segment(context, photoBmp, x, y)?.candidates },
                    )
                }.getOrNull()
            }
        }
    }
    val state = full ?: quick
    // ⭐ The Tap tool exists when the node's own checkbox is on — or, for a
    // flow saved before 2026-09-22, while a `mask.segment_model` is still WIRED
    // (`docs/SEGMENTER.md` §1). ⚠⚠ BOTH, not one: the node is retired rather
    // than deleted, so an old graph must keep working untouched.
    // ⚠ The port is gone (2026-09-22); the checkbox is the only switch now.
    val tapSelect = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)[
        com.abrah.nightmare.SdSampler.TAP_SELECT
    ].equals("true", ignoreCase = true)
    // ⚠⚠ The TOOL appears only when the model is here as well as ticked: a Tap
    // tool that answers every tap with "download it first" is a tool that does
    // not work, and the row under the slider is where that is said.
    val canTap = tapSelect && segmenterInstalled
    if (!canTap && tool == MaskTool.TAP) tool = MaskTool.BRUSH
    // ⚠ Its own switch and its own model, checked the same way: a Pick tool
    // whose parser is not on the phone is a tool that does not work.
    // ⭐⭐ The node's own tick — saved with the flow (the user's call,
    // 2026-09-23: *"remembered for that flow"*).
    val pickSelect = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)[
        com.abrah.nightmare.SdSampler.PICK_SELECT
    ].equals("true", ignoreCase = true)
    val canPick = pickSelect && parserInstalled && onPickMask != null
    if (!canPick && tool == MaskTool.PICK) tool = MaskTool.BRUSH
    var picking by remember { mutableStateOf(false) }
    var pickNote by remember { mutableStateOf<String?>(null) }
    var warmAtPick by remember { mutableStateOf(true) }
    // ⭐ The chips ARE the picks in the mask, so nothing else is stored.
    val picked = stored.ops.filterIsInstance<com.abrah.nightmare.MaskOp.Pick>()
        .map { it.target }.toSet()
    var tapping by remember { mutableStateOf(false) }
    var tapNote by remember { mutableStateOf<String?>(null) }
    // ⚠ Whether the segmenter was already warm when this tap started — it is
    // what decides between "Loading" and "Finding", and it must be sampled at
    // the tap rather than read live (see [onTap]).
    var warmAtTap by remember { mutableStateOf(true) }

    // ⭐⭐⭐ **Undo and redo over EVERY layer** — one history, so Undo reverses
    // whatever was done last: a stroke, a tap, Invert, Clear on the image; an
    // object kept, moved, turned, flipped, resized, removed; a ring width; a
    // Reset; a deleted layer ([EditHistory]; the user's ask, 2026-09-24).
    // ⚠ The auto-mask chips are left out — each chip is its own on/off — and
    // so are grow and feather.
    val strokes = com.abrah.nightmare.MaskState(stored.ops.filterNot { it is com.abrah.nightmare.MaskOp.Pick })
    val snapshot = MaskSnapshot(
        strokes.encode(),
        nodeParams[com.abrah.nightmare.AddObjects.PARAM].orEmpty(),
        nodeParams[com.abrah.nightmare.AddObjects.ORIGINAL].orEmpty(),
    )
    val history = rememberEditHistory(nodeId, snapshot)
    fun restore(to: MaskSnapshot) {
        val ops = com.abrah.nightmare.MaskState.decode(to.strokes).ops
        onEditMask(nodeId) { it.copy(ops = ops) }
        if (to.objects != snapshot.objects) onSetParam(com.abrah.nightmare.AddObjects.PARAM, to.objects)
        if (to.originals != snapshot.originals) onSetParam(com.abrah.nightmare.AddObjects.ORIGINAL, to.originals)
    }
    /** ⚠ Past what the window recorded, Undo drops the image's last op. */
    fun undoFallback(): MaskSnapshot? =
        if (strokes.isEmpty) null else snapshot.copy(strokes = strokes.dropLast().encode())
    fun nextPlacing() {
        placing = placeQueue.firstOrNull()
        placeQueue = placeQueue.drop(1)
    }
    // ⭐ The picture being worked on, framed, with the objects that are not
    // being edited baked in: SEE-THROUGH on the image layer, so the photo under
    // them can be painted (the user's call, 2026-09-24); solid under an object
    // layer, whose own objects [MaskEditor] draws so they can be dragged.
    // ⚠⚠ Off the main thread ([rememberOffMain]): a copy and a composite of
    // the whole frame, on every edit and every layer switch.
    val objectAlpha = if (layersShown && !onObjects) OBJECTS_SEE_THROUGH else 1f
    val editorSource = rememberOffMain(nodeId, "mask source", source, objectCuts, frame, objectAlpha, active) {
        val baked = objectCuts.filter { it.first.layer != active }
        if (baked.isEmpty()) return@rememberOffMain null
        val framedCuts = baked.map { (o, cut) ->
            o.copy(x = (o.x - frame.x) / frame.w, y = (o.y - frame.y) / frame.h, w = o.w / frame.w) to cut
        }
        com.abrah.nightmare.AddObjects.composite(source.asAndroidBitmap(), framedCuts, objectAlpha).asImageBitmap()
    }?.takeIf { objectCuts.any { it.first.layer != active } } ?: source
    // ⭐⭐ This layer's objects for [MaskEditor] to draw and move, in the
    // FRAME's terms — converted back to the photo's when a gesture ends.
    val cutImages = remember(objectCuts) { objectCuts.associate { (_, b) -> b to b.asImageBitmap() } }
    val layerEdit = if (!onObjects) null else ObjectLayerEdit(
        items = objects.withIndex().filter { it.value.layer == active }.mapNotNull { (i, o) ->
            val cut = com.abrah.nightmare.AddObjects.cutFor(o, objectCuts)?.let { cutImages[it] } ?: return@mapNotNull null
            ObjectItem(i, o.copy(x = (o.x - frame.x) / frame.w, y = (o.y - frame.y) / frame.h, w = o.w / frame.w), cut)
        },
        selected = selected,
        onSelect = { selected = it },
        onChange = { i, o ->
            write(
                com.abrah.nightmare.AddObjects.changed(
                    nodeParams, i, o.copy(x = frame.x + o.x * frame.w, y = frame.y + o.y * frame.h, w = o.w * frame.w),
                ),
            )
            freshRings(listOf(o.id))
        },
    )
    choosingUri?.let { uri ->
        ObjectChooser(
            uri = uri,
            tapEnabled = tapSelect,
            pickEnabled = pickSelect,
            onCancel = { choosingUri = null },
            onDone = { ops ->
                choosingUri = null
                objectNote = context.getString(R.string.objects_cutting)
                objectScope.launch {
                    val made = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        runCatching {
                            val src = com.abrah.nightmare.AddObjects.load(context, uri) ?: return@runCatching null
                            val m = com.abrah.nightmare.AddObjects.raster(
                                src,
                                com.abrah.nightmare.AddObjects.resolveOn(context, src, com.abrah.nightmare.MaskState.decode(ops)),
                            )
                            com.abrah.nightmare.AddObjects.regions(m).map { r ->
                                // ⚠ Starts centred on the CROP, at half its width:
                                // on the part of the photo being repainted.
                                val cutAspect = (r.width() * src.width) / (r.height() * src.height).coerceAtLeast(1e-3f)
                                val w = frame.w * 0.5f
                                val h = w * photo.width / cutAspect / photo.height
                                val o = com.abrah.nightmare.AddObjects.Placed(
                                    uri, ops, r.left, r.top, r.width(), r.height(),
                                    frame.x + (frame.w - w) / 2f, frame.y + (frame.h - h) / 2f, w,
                                    layer = placingLayer,
                                )
                                Placing(o, com.abrah.nightmare.AddObjects.cutout(src, m, o), null)
                            }
                        }.getOrNull()
                    }
                    objectNote = when {
                        made == null -> context.getString(R.string.objects_unreadable)
                        made.isEmpty() -> context.getString(R.string.objects_nothing_chosen)
                        else -> null
                    }
                    if (!made.isNullOrEmpty()) {
                        placeQueue = made
                        nextPlacing()
                    }
                }
            },
        )
    }
    if (deletingLayer) com.abrah.nightmare.ui.ConfirmDelete(
        title = stringResource(R.string.objects_delete_layer_title, active),
        body = stringResource(R.string.objects_delete_layer_body),
        onConfirm = {
            dropRings(
                (objects + com.abrah.nightmare.AddObjects.originalsOf(nodeParams))
                    .filter { it.layer == active }.map { it.id },
            )
            write(com.abrah.nightmare.AddObjects.layerDeleted(nodeParams, active))
            selected = null
            layer = com.abrah.nightmare.AddObjects.IMAGE_LAYER
        },
        onDismiss = { deletingLayer = false },
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ⭐⭐ Placing an object replaces the editor with the WHOLE photo,
        // zoomed out, and the tools with ✓ / ✕ (the user's design).
        placing?.let { pl ->
            val others = rememberOffMain(nodeId, "placer photo", photo, objectCuts) {
                if (objectCuts.isEmpty()) null
                else com.abrah.nightmare.AddObjects.composite(photo.asAndroidBitmap(), objectCuts).asImageBitmap()
            } ?: photo
            ObjectPlacer(photo = others, placing = pl, onChange = { o -> placing = Placing(o, pl.cut, pl.index) })
        } ?: Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          // ⭐⭐ **The layer chips, on top of the frame** — only once an object
          // is placed. ⚠ A FlowRow: three chips do not fit a narrow phone's row.
          if (layersShown) androidx.compose.foundation.layout.FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
              for (n in listOf(com.abrah.nightmare.AddObjects.IMAGE_LAYER) + objectLayers) {
                  FilterChip(
                      selected = active == n,
                      onClick = { layer = n; selected = null },
                      label = {
                          Text(
                              if (n == com.abrah.nightmare.AddObjects.IMAGE_LAYER) stringResource(R.string.mask_layer_image)
                              else stringResource(R.string.mask_layer_objects, n),
                              style = MaterialTheme.typography.labelLarge,
                          )
                      },
                      shape = RoundedCornerShape(10.dp),
                  )
              }
          }
          MaskEditor(
            source = editorSource,
            // ⭐ The image layer's mask, rings included, on EVERY layer: it is
            // the one mask there is, and on an object layer it shows where
            // that layer's rings fall. Nothing paints it there.
            state = state,
            viewKey = source,
            tool = tool,
            brushRadiusFrac = radius,
            padding = padding,
            objects = layerEdit,
            onTap = { u, v ->
                if (!tapping) {
                    // ⚠ Read BEFORE the work starts: by the time it finishes the
                    // segmenter is warm, and the label would have said "loading"
                    // for a tap that was not.
                    warmAtTap = com.abrah.nightmare.segment.Segmenter.isWarm(photo.asAndroidBitmap())
                    tapping = true
                    tapNote = null
                    // ⚠ Frame → photo, the same crossing a stroke makes.
                    onTapMask(nodeId, frame.x + u * frame.w, frame.y + v * frame.h) { note ->
                        tapping = false
                        tapNote = note
                    }
                }
            },
            onStroke = { painted ->
                // ⚠⚠ Back into the PHOTO's coordinates before it is stored, or
                // the sampler re-frames a stroke that was already framed.
                val stroke = com.abrah.nightmare.MaskFraming.toSource(
                    painted, frame.x, frame.y, frame.w, frame.h,
                )
                val op = if (tool == MaskTool.BRUSH) {
                    com.abrah.nightmare.MaskOp.Stroke(stroke)
                } else {
                    com.abrah.nightmare.MaskOp.Erase(stroke)
                }
                // ⚠ `it`, not the captured `state`: the op is appended to
                // whatever the node holds at the moment this runs.
                onEditMask(nodeId) { it.plus(op) }
            },
          )
        }
        // ⚠⚠⚠ **A FlowRow, so a second line is allowed.** Reported from the
        // phone 2026-09-23: with Tap AND Auto segment both shown, seven
        // controls were squeezed into one Row and the clear button shrank to a
        // sliver. The user: *"u can allow a 2nd row"*.
        if (placing != null) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.objects_place_hint) +
                    if (placeQueue.isNotEmpty()) " · " + stringResource(R.string.objects_more, placeQueue.size) else "",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // ⚠ ✓ keeps this placement, ✕ drops it.
            IconButton(onClick = {
                placing?.let { pl ->
                    val kept = com.abrah.nightmare.AddObjects.withNewId(nodeParams, pl.obj.copy(layer = placingLayer))
                    write(com.abrah.nightmare.AddObjects.added(nodeParams, kept))
                    // ⭐ Its ring goes on the IMAGE layer (the user's call, 2026-09-26).
                    freshRings(listOf(kept.id))
                    // ⭐ The user's ask, 2026-09-26: after adding, the NEW
                    // object's layer is the one in view, not the image.
                    layer = placingLayer
                    selected = null
                }
                nextPlacing()
            }) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.cd_keep_placement), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = { nextPlacing() }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_drop_object), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ⭐⭐ Add Objects: pick the photo to take objects from. ⚠⚠ DIMMED,
            // not hidden, once every layer is taken — and it says so when
            // tapped (`docs/UI.md` §8.18; the user's call, 2026-09-26).
            if (objectsOn) {
                val next = com.abrah.nightmare.AddObjects.nextLayer(objects)
                val full = stringResource(R.string.objects_max_layers, com.abrah.nightmare.AddObjects.MAX_LAYERS)
                LayerTool(
                    icon = com.abrah.nightmare.ui.AddObjectIcon,
                    label = stringResource(R.string.cd_add_objects),
                    deadReason = if (next == null) full else null,
                    onDead = ::toast,
                ) {
                    placingLayer = next ?: return@LayerTool
                    pickObjectSource()
                }
            }
            if (onObjects) {
                // ⭐⭐⭐ **An object layer's tools** — the user's list, 2026-09-26:
                // turn, flip, remove the selected object; undo, redo; reset the
                // layer; delete the layer. No brush: the ring is drawn for it.
                val pick = stringResource(R.string.objects_select_first)
                val dead = if (selectedObj == null) pick else null
                LayerTool(com.abrah.nightmare.ui.RotateRightIcon, stringResource(R.string.cd_rotate_object), dead, ::toast) {
                    selectedObj?.let { o ->
                        write(com.abrah.nightmare.AddObjects.changed(nodeParams, selected!!, o.copy(rot = com.abrah.nightmare.AddObjects.snapAngle(o.rot + 90f))))
                        freshRings(listOf(o.id))
                    }
                }
                LayerTool(com.abrah.nightmare.ui.FlipIcon, stringResource(R.string.cd_flip_object), dead, ::toast) {
                    selectedObj?.let { o ->
                        write(com.abrah.nightmare.AddObjects.changed(nodeParams, selected!!, o.copy(flip = !o.flip)))
                        freshRings(listOf(o.id))
                    }
                }
                LayerTool(com.abrah.nightmare.ui.RemoveObjectIcon, stringResource(R.string.cd_remove_object), dead, ::toast) {
                    selectedObj?.let { o -> dropRings(listOf(o.id)) }
                    selected?.let { i -> write(com.abrah.nightmare.AddObjects.removed(nodeParams, i)) }
                    selected = null
                }
            } else {
                // ⚠⚠ ICONS, and DreamUI's icons: `Icons.Default.Brush` and its
                // hand-built [EraserIcon]. The words "paint" and "erase" were ours
                // alone, and two apps that share a mask editor should not disagree
                // about what its two tools look like. ⚠ The label survives as the
                // content description, so a screen reader still reads it.
                FilterChip(
                    selected = tool == MaskTool.BRUSH,
                    onClick = { tool = MaskTool.BRUSH },
                    label = {
                        Icon(
                            com.abrah.nightmare.ui.BrushIcon,
                            contentDescription = stringResource(R.string.cd_paint),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                FilterChip(
                    selected = tool == MaskTool.ERASE,
                    onClick = { tool = MaskTool.ERASE },
                    label = {
                        Icon(
                            com.abrah.nightmare.ui.EraserIcon,
                            contentDescription = stringResource(R.string.cd_erase),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                )
                // ⭐ DreamUI's Tap tool, beside the brush — and only when wired.
                if (canTap) {
                    FilterChip(
                        selected = tool == MaskTool.TAP,
                        onClick = { tool = MaskTool.TAP },
                        label = {
                            Icon(
                                com.abrah.nightmare.ui.TapObjectIcon,
                                contentDescription = stringResource(R.string.cd_tap_object),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
                // ⭐⭐ …and Pick by name beside it, on the same terms: its own
                // checkbox ticked AND its own model on the phone.
                if (canPick) {
                    FilterChip(
                        selected = tool == MaskTool.PICK,
                        onClick = { tool = MaskTool.PICK },
                        label = {
                            Icon(
                                com.abrah.nightmare.ui.PickByNameIcon,
                                contentDescription = stringResource(R.string.cd_pick_by_name),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
            // ⚠⚠ ICONS, and DreamUI's icons — undo, invert, clear. These were
            // two TEXT buttons and no invert at all, though `MaskOp.Invert` has
            // been in the model and handled by the compositor the whole time.
            // ⚠ Small targets so the row stays one line beside the two tool
            // chips; the words survive as content descriptions.
            IconButton(
                onClick = {
                    // ⚠ Past what this window recorded, Undo drops the image's
                    // last op, as it always did — a reopened flow's mask is
                    // still undoable.
                    history.undo(snapshot, fallback = undoFallback()) { restore(it) }
                },
                enabled = history.canUndo || undoFallback() != null,
                // ⚠ Centred by hand: a FlowRow lines its items up at the TOP,
                // which sat these 34dp buttons above the 32dp chips.
                modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
            ) {
                Icon(
                    com.abrah.nightmare.ui.UndoIcon,
                    contentDescription = stringResource(R.string.cd_undo_stroke),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { history.redo(snapshot) { restore(it) } },
                enabled = history.canRedo,
                modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
            ) {
                Icon(
                    com.abrah.nightmare.ui.RedoIcon,
                    contentDescription = stringResource(R.string.cd_redo_stroke),
                    modifier = Modifier.size(18.dp),
                )
            }
            if (onObjects) {
                // ⭐⭐ Reset: the layer as ✓ left it — every object back where it
                // was placed, removed ones included. ⚠ One Undo step, like any edit.
                val placedAs = com.abrah.nightmare.AddObjects.originalsOf(nodeParams).filter { it.layer == active }
                LayerTool(
                    com.abrah.nightmare.ui.ResetIcon,
                    stringResource(R.string.cd_reset_layer),
                    if (placedAs == objects.filter { it.layer == active }) stringResource(R.string.objects_already_placed) else null,
                    ::toast,
                ) {
                    write(com.abrah.nightmare.AddObjects.reset(nodeParams, active))
                    freshRings(placedAs.map { it.id })
                    selected = null
                }
                // ⭐⭐ The bin deletes the whole LAYER, behind [ConfirmDelete].
                IconButton(
                    onClick = { deletingLayer = true },
                    modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_layer),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // ⭐ Invert flips what is masked SO FAR and later strokes add to the
                // flipped mask — which is what makes "invert, then tidy the edges"
                // work, and why it is an OP in the list rather than a final flag.
                // ⚠ Enabled on an empty mask too: inverting nothing is "mask
                // everything", which is a legitimate and useful starting point.
                IconButton(
                    onClick = { onEditMask(nodeId) { it.plus(com.abrah.nightmare.MaskOp.Invert) } },
                    // ⚠ Centred by hand: a FlowRow lines its items up at the TOP,
                    // which sat these 34dp buttons above the 32dp chips.
                    modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
                ) {
                    Icon(
                        com.abrah.nightmare.ui.InvertMaskIcon,
                        contentDescription = stringResource(R.string.cd_invert_mask),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    // ⚠⚠ The image's mask and nothing else — the objects and
                    // their rings stay (the user's call, 2026-09-24).
                    onClick = { onEditMask(nodeId) { it.cleared() } },
                    enabled = !state.isEmpty,
                    // ⚠ Centred by hand: a FlowRow lines its items up at the TOP,
                    // which sat these 34dp buttons above the 32dp chips.
                    modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
                ) {
                    Icon(
                        com.abrah.nightmare.ui.ClearLayersIcon,
                        contentDescription = stringResource(R.string.cd_clear_mask),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        // ⭐ What a finger does on an object layer — there is no tool to pick.
        if (onObjects && placing == null) Text(
            stringResource(R.string.objects_layer_hint),
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⚠ PIXELS at 512, exactly as DreamUI reads it out: `radius * 2 * 512`
        // is the stroke's diameter on the reference edge. A percentage of an
        // edge nobody can see is not a number anyone can carry between the two.
        // ⭐ What a tap is doing, or why it did nothing — a tap that silently
        // selects nothing is DreamUI's "the tap is ignored" report.
        // ⭐⭐⭐ **The targets, where the brush-size slider sits for Brush** —
        // the user's call, 2026-09-23. A selected chip IS a `MaskOp.Pick` in the
        // mask, so the row states the mask rather than a mode of its own, and
        // tapping one again lets it go.
        //
        // ⚠⚠ GROUPS, never single ATR labels. Under int8 the model renames
        // pixels between adjacent labels — `Dress` becomes `Upper-clothes` on
        // the same dress — so a group is stable where a garment's name is not
        // (`docs/SEGMENTER.md` §7). That is why there is no Dress chip.
        // ⚠⚠ Everything tied to a TOOL is hidden while an object is being
        // placed — the size slider, the chips, the tap and pick notes. None of
        // them does anything until ✓ or ✕ hands the picture back to the tools
        // (the user's ask, 2026-09-24).
        // ⚠ …and on an object layer, where no brush, tap or pick applies.
        val tools = placing == null && !onObjects
        if (tools && tool == MaskTool.PICK) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                com.abrah.nightmare.segment.Parser.TARGETS.forEach { t ->
                    FilterChip(
                        selected = t.id in picked,
                        // ⚠ One at a time: a second pick while the first is
                        // still parsing would queue a second pass over the same
                        // photo for a label map it is about to cache anyway.
                        enabled = !picking,
                        onClick = {
                            pickNote = null
                            // ⚠ Turning one OFF is instant and must not claim to
                            // be working — `pickMask` removes the op with no
                            // model call at all.
                            if (t.id in picked) {
                                onPickMask?.invoke(nodeId, t.id) { pickNote = it }
                            } else {
                                warmAtPick = com.abrah.nightmare.segment.Parser.isWarm(photoBmp)
                                picking = true
                                onPickMask?.invoke(nodeId, t.id) { note ->
                                    picking = false
                                    pickNote = note
                                }
                            }
                        },
                        label = { Text(t.label, style = MaterialTheme.typography.labelLarge) },
                        shape = RoundedCornerShape(10.dp),
                    )
                }
            }
        }
        if (tools && (tool == MaskTool.PICK || pickNote != null)) {
            Text(
                when {
                    picking && !warmAtPick -> stringResource(R.string.parser_loading)
                    picking -> stringResource(R.string.mask_pick_working)
                    pickNote != null -> pickNote!!
                    else -> stringResource(R.string.mask_pick_hint)
                },
                style = LogTextStyle,
                color = if (pickNote != null && !picking) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (tools && (tool == MaskTool.TAP || tapNote != null)) {
            Text(
                when {
                    // ⚠ The same distinction the toast makes: the first tap on a
                    // picture is loading, later ones are searching. One state,
                    // two honest sentences ([Segmenter.isWarm]).
                    tapping && !warmAtTap -> stringResource(R.string.segmenter_loading)
                    tapping -> stringResource(R.string.mask_tap_working)
                    tapNote != null -> tapNote!!
                    else -> stringResource(R.string.mask_tap_hint)
                },
                style = LogTextStyle,
                color = if (tapNote != null && !tapping) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⭐⭐ ONE slider with a dropdown of what it sets, and the list follows
        // the TOOL — DreamUI's `MaskParamSlider` (the user's call, 2026-09-17).
        // Brush and eraser share Size + Feather, so switching between them
        // mid-mask does not snap the slider off what was being adjusted; Tap
        // offers Grow + Feather.
        val live = com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)
        val growW = type?.widgets?.firstOrNull { it.name == "grow" }
        val featherW = type?.widgets?.firstOrNull { it.name == "feather" }
        // ⭐⭐ On an object layer the slider is the RING's width, Size only —
        // stored on each of the layer's objects ([com.abrah.nightmare.AddObjects.Placed.ring])
        // when the finger lifts ([ringDraft]).
        if (onObjects && placing == null) MaskParamSlider(
            tool = MaskTool.BRUSH,
            brush = ringInFrame,
            onBrush = { v -> ringDraft = v * frame.w },
            onBrushDone = {
                ringDraft?.let { r ->
                    write(com.abrah.nightmare.AddObjects.ringed(nodeParams, active, r))
                    freshRings(objects.filter { it.layer == active }.map { it.id })
                }
                ringDraft = null
            },
            grow = 0f, growMin = 0f, growMax = 1f, onGrow = null,
            feather = 0f, featherMax = 1f, onFeather = null,
        )
        if (tools) MaskParamSlider(
            tool = tool,
            brush = radius,
            onBrush = { v -> radius = v },
            grow = live["grow"]?.toFloatOrNull() ?: com.abrah.nightmare.MaskNode.GROW_DEFAULT,
            growMin = growW?.min?.toFloat() ?: com.abrah.nightmare.MaskNode.GROW_MIN,
            growMax = growW?.max?.toFloat() ?: com.abrah.nightmare.MaskNode.GROW_MAX,
            onGrow = if (growW == null) null else { v -> onSetParam("grow", fixed(v, 3)) },
            feather = live["feather"]?.toFloatOrNull() ?: 0f,
            featherMax = featherW?.max?.toFloat() ?: 0.2f,
            onFeather = if (featherW == null) null else { v -> onSetParam("feather", fixed(v, 3)) },
        )
        // ⭐⭐ **How much is covered, right under the slider that changes it**
        // — and ABOVE the tap-select container below.
        //
        // ⚠⚠ It sat UNDER the download card, so the one number that tells you
        // whether the mask is finished was the last thing on the screen, below a
        // button about a model. The user's call, 2026-09-22.
        Text(
            // ⚠⚠ Says the convention out loud. White-takes-repaint is the one
            // thing about masking that is easy to get backwards and impossible
            // to notice — the wrong way round replaces the region you meant to
            // keep, and the picture still looks plausible.
            state.let { shown ->
                if (shown.isEmpty) {
                    stringResource(R.string.mask_paint_hint)
                } else {
                    stringResource(
                        R.string.mask_covered,
                        (MaskRaster.coverageFraction(shown) * 100).roundToInt(),
                    )
                }
            },
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⭐⭐⭐ **Tap to select lives HERE, in the mask editor** — the user's
        // call, 2026-09-22. The tapping happens in this editor, so the switch
        // for it belongs here rather than in the node's knob list two screens of
        // sliders away.
        if (type?.widgets?.any { it.name == com.abrah.nightmare.SdSampler.TAP_SELECT } == true) {
            // ⭐⭐⭐ **ONE container for the switch and the thing it needs.**
            //
            // ⚠⚠ The checkbox and the download card were two loose items in the
            // editor's column, so the card read as an unrelated panel that had
            // appeared under an unrelated tick. The user's call, 2026-09-22:
            // *"put the checkbox and downloader in one container"*. They are one
            // control — a tool and the model it runs on — and the surface says
            // so.
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = tapSelect,
                            onCheckedChange = { v ->
                                onSetParam(com.abrah.nightmare.SdSampler.TAP_SELECT, v.toString())
                                com.abrah.nightmare.MaskDefaults.set(context, com.abrah.nightmare.SdSampler.TAP_SELECT, v)
                            },
                        )
                        // ⭐⭐ **"Show tap to select icon"** — the user's wording,
                        // 2026-09-22. ⚠ It says what the tick DOES: it puts the
                        // Tap tool in the toolbar above. It does not start
                        // selecting anything, and "Tap to select" read as though
                        // it might.
                        // ⚠ "Enable tap to select" — the user's wording,
                        // 2026-09-23, replacing "Show tap to select icon".
                        Text(
                            "Enable tap to select",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // ⭐⭐⭐ **The download is the SAME card the Models tab draws**
                    // — `ToolCard` over `DownloadCard`: the size, the progress bar,
                    // Cancel while it runs, Delete when it is there
                    // (`docs/UI.md` §8.1).
                    //
                    // ⚠⚠⚠ It was two hand-rolled `TextButton`s. They showed no
                    // size, no progress and no way to stop, and the state behind
                    // them never refreshed — so pressing either appeared to do
                    // nothing at all. Reported 2026-09-22.
                    //
                    // ⚠⚠ **Only while the box is TICKED**, or while a download
                    // this row started is still running. An 87 MB card under an
                    // unticked checkbox is an offer nobody made. Untick it
                    // mid-download and the card stays until the download ends,
                    // because Cancel has to remain reachable.
                    if (tapSelect || segmenterRow?.progress != null) {
                        segmenterRow?.let { row ->
                            Box(Modifier.padding(bottom = 4.dp)) {
                                com.abrah.nightmare.ui.ToolCard(
                                    row = row,
                                    busy = busy,
                                    onInstall = { onInstallSegmenter?.invoke() },
                                    onCancel = { onCancelSegmenter?.invoke() },
                                    onDelete = { onDeleteSegmenter?.invoke() },
                                )
                            }
                        }
                    }
                }
            }
        }
        // ⭐⭐⭐ **The same switch, for the other tool** — asked for 2026-09-23
        // as *"a show btn for the additional btns just like for the tap icon"*.
        //
        // ⚠⚠ Its OWN container and its own card, not a second line inside the
        // tap one: they are two models and two downloads, and a single card
        // under two checkboxes could not say which tick the 29 MB belonged to.
        // ⚠ Everything else is the tap row's rule, unchanged — the card is
        // `ToolCard` over the shared `DownloadCard` (`docs/UI.md` §8.1), and it
        // shows only while the box is ticked or a download it started is live.
        if (type?.widgets?.any { it.name == com.abrah.nightmare.SdSampler.PICK_SELECT } == true) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = pickSelect,
                            onCheckedChange = { v ->
                                // ⚠ The tick IS the switch: `MaskNode.opsOf` adds
                                // the node's chips to the mask while it is on and
                                // drops them when it is off. Nothing else to write.
                                onSetParam(com.abrah.nightmare.SdSampler.PICK_SELECT, v.toString())
                                com.abrah.nightmare.MaskDefaults.set(context, com.abrah.nightmare.SdSampler.PICK_SELECT, v)
                                if (v) tool = MaskTool.PICK
                            },
                        )
                        Text(
                            stringResource(R.string.mask_pick_show),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pickSelect || parserRow?.progress != null) {
                        parserRow?.let { row ->
                            Box(Modifier.padding(bottom = 4.dp)) {
                                com.abrah.nightmare.ui.ToolCard(
                                    row = row,
                                    busy = busy,
                                    onInstall = { onInstallParser?.invoke() },
                                    onCancel = { onCancelParser?.invoke() },
                                    onDelete = { onDeleteParser?.invoke() },
                                )
                            }
                        }
                    }
                }
            }
        }
        // ⭐⭐⭐ **Enable Add Objects** — the third switch, same card shape.
        // ⚠ Room under the tick and under the card (the user's ask,
        // 2026-09-24): the other two cards end on a 48dp checkbox row, this
        // one on a line of hint text or an object row, which sat flush against
        // the card's edge — and, as the last card, against the sheet's.
        if (type?.widgets?.any { it.name == com.abrah.nightmare.AddObjects.ENABLE } == true) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)) {
                    BoolKnobRow(
                        label = stringResource(R.string.objects_enable),
                        hint = stringResource(R.string.objects_enable_hint),
                        checked = objectsOn,
                        onChange = { v ->
                            onSetParam(com.abrah.nightmare.AddObjects.ENABLE, v.toString())
                            com.abrah.nightmare.MaskDefaults.set(context, com.abrah.nightmare.AddObjects.ENABLE, v)
                        },
                    )
                    objectNote?.let { Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (objectsOn) {
                        // ⚠ No per-object rows any more: an object is moved,
                        // turned and removed on its own LAYER, above.
                        // ⚠ The band starts from the pasted pixels only below 1.0;
                        // at 1.0 it is redrawn from noise and can drift in colour.
                        if (objects.isNotEmpty() && (nodeParams["denoise"]?.toFloatOrNull() ?: 1f) >= 0.99f) {
                            Text(
                                stringResource(R.string.objects_denoise_hint),
                                style = LogTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}


/** ⭐ One step of the mask window's history: the image's strokes, the objects and their reset point ([EditHistory]). */
internal data class MaskSnapshot(val strokes: String, val objects: String, val originals: String)

/**
 * ⭐ How see-through the objects are on the image layer — enough to paint the
 * photo under them, not so much that where they sit is lost (2026-09-24).
 */
private const val OBJECTS_SEE_THROUGH = 0.4f

/**
 * ⭐⭐ A mask-window tool button that is DIMMED, never hidden, when it does
 * not apply — and says why when tapped ([deadReason]), the rule
 * `docs/UI.md` §8.18 set for a picture's buttons (`PictureActions`).
 */
@Composable
private fun RowScope.LayerTool(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    deadReason: String?,
    onDead: (String) -> Unit,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = { if (deadReason != null) onDead(deadReason) else onClick() },
        // ⚠ Centred by hand: a FlowRow lines its items up at the TOP.
        modifier = Modifier.align(Alignment.CenterVertically).size(34.dp),
    ) {
        val tint = LocalContentColor.current
        Icon(
            icon,
            contentDescription = deadReason ?: label,
            tint = if (deadReason != null) tint.copy(alpha = 0.38f) else tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
