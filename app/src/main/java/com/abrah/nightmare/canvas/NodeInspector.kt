package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.abrah.nightmare.ui.LogTextStyle

/**
 * The knobs of one node, as a bottom sheet.
 *
 * ⚠⚠ A SHEET, not fields drawn on the node. Text editing inside a custom canvas
 * means reimplementing selection, the IME, and scroll-into-view above a
 * keyboard — all of which M3 already does correctly. docs/UI.md §1 says M3
 * governs the chrome and the canvas stays custom-drawn; this is the line.
 *
 * ⚠ Values are written back on every keystroke rather than on a Done button.
 * A field the user typed into and then dismissed must not silently discard what
 * they typed — and because the executor is content-addressed, a half-typed
 * prompt costs nothing until Run is pressed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeInspector(
    state: CanvasState,
    types: Map<String, NodeType>,
    onSetParam: (node: String, name: String, value: String) -> Unit,
    onSetParams: (node: String, values: Map<String, String>) -> Unit,
    /** ⚠⚠ A transform — `HarnessViewModel.editMask`, and the reason it exists. */
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit =
        { _, _ -> },
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    /** ⭐ Graph-wide, not per-node — see [NodeInspectorBody.onSetResolution]. */
    onSetResolution: (com.abrah.nightmare.Res) -> Unit = {},
    onSetAspect: (String) -> Unit = {},
    imageFor: (String) -> ImageBitmap? = { null },
    onViewFullscreen: (String) -> Unit = {},
    /**
     * ⚠ Only for the SEED. The rolled seed of a `seed = 0` sampler exists
     * nowhere but the run's per-node detail line, so a sheet that wants to show
     * which seed made this picture has to be given the run.
     */
    status: Map<String, NodeStatus> = emptyMap(),
    /** Forget the picture on a `load_image` node. ⚠ The VM owns previews. */
    onClearImage: (String) -> Unit = {},
    /** ⭐ The same three the fullscreen viewer offers — see the body's note. */
    onSaveImage: (String) -> Unit = {},
    /** ⭐ Hand a node's picture to another app. */
    onShareImage: (String) -> Unit = {},
    onKeepImage: (String) -> Unit = {},
    onClearOutput: (String) -> Unit = {},
) {
    val nodeId = state.editing ?: return
    val node = state.workflow.graph.byId[nodeId] ?: return
    val type = types[node.type]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val shownId = state.previews[nodeId]?.first
        val previewId = shownId
        // ⚠ A crop is framed against its INPUT, not its output. Showing the
        // node's own result would be showing the crop that has already happened.
        val sourceId = node.inputs["image"]?.node?.let { state.previews[it]?.first }
        // ⭐⭐ What the graph demands of this node, read HERE rather than
        // computed in the body: the body is what the goldens render, and it must
        // stay a function of its arguments.
        val demand =
            if (type?.sizedByConsumer == true) {
                requiredOutputSize(state.workflow.graph, types, nodeId)
            } else {
                SizeDemand.None
            }
        NodeInspectorBody(
            nodeId, node, type, onSetParam, onSetParams, onEditMask, onDelete,
            // ⚠ Read HERE, like `demand` above and for the same reason: the body
            // must stay a function of its arguments so the goldens can render
            // it, and this list is a cached disk scan.
            resolutions = com.abrah.nightmare.SelectedModel.resolutions,
            onSetResolution = onSetResolution,
            onSetAspect = onSetAspect,
            preview = shownId?.let(imageFor),
            onViewFullscreen = { shownId?.let(onViewFullscreen) },
            cropSource = if (node.type == "image.crop") sourceId?.let(imageFor) else null,
            // ⚠ Same upstream picture, different job: the cropper FRAMES it,
            // the mask editor is PAINTED on it.
            maskSource = if (node.type == "image.mask") sourceId?.let(imageFor) else null,
            // ⚠ Only for a node that HAS a picture and did not make it from a
            // photo the user chose: a `load_image` already has swap/remove.
            onSaveImage = previewId?.takeIf { node.type != "image.load" }
                ?.let { id -> { onSaveImage(id) } },
            onShareImage = { previewId?.let(onShareImage) },
            onKeepImage = previewId?.takeIf { node.type != "image.load" }
                ?.let { id -> { onKeepImage(id) } },
            onClearOutput = previewId?.takeIf { node.type != "image.load" }
                ?.let { { onClearOutput(nodeId) } },
            demand = demand,
            seed = seedFor(state.workflow.graph, nodeId) { status[it]?.detail },
            onClearImage = { onClearImage(nodeId) },
        )
    }
}

/**
 * What the sheet contains — everything except the sheet.
 *
 * ⚠ Split out to be SEEN. A `ModalBottomSheet` renders into its own window, so
 * a Roborazzi capture of the screen behind it comes back without the sheet in
 * it — a golden that passes while showing none of the thing it is named for.
 * The body is ordinary layout and goldens normally.
 */
@Composable
internal fun NodeInspectorBody(
    nodeId: String,
    node: com.abrah.nightmare.Node,
    type: NodeType?,
    onSetParam: (node: String, name: String, value: String) -> Unit,
    /** ⚠ A tuple that means ONE thing, written as one change. [CropEditor]. */
    onSetParams: (node: String, values: Map<String, String>) -> Unit = { _, _ -> },
    /**
     * ⚠⚠ The mask's edits are TRANSFORMS, not writes — `HarnessViewModel.editMask`.
     * Defaulted to a no-op so a golden and a preview still render the sheet.
     */
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit =
        { _, _ -> },
    onDelete: (String) -> Unit,
    /**
     * ⭐⭐ The sizes the selected model can actually render, for the size chips
     * on a context-key node.
     *
     * ⚠⚠ **Passed in, never read from [SelectedModel] here.** This body is what
     * the goldens render and it must stay a function of its arguments; the list
     * is also a disk scan behind a cache, which has no business inside a
     * recomposition. ⚠ Fewer than two entries draws no control — a lone chip
     * that cannot be unselected is furniture.
     */
    resolutions: List<com.abrah.nightmare.Res> = emptyList(),
    /**
     * ⭐ Choose the render size. **Not [onSetParam]**: a size is a third of the
     * [ContextKey], so choosing one rewrites every backend node in the graph
     * rather than this node alone (`HarnessViewModel.selectResolution`).
     * Writing it per-node would leave a graph naming two keys, which the
     * executor refuses.
     */
    onSetResolution: (com.abrah.nightmare.Res) -> Unit = {},
    /**
     * ⭐ Choose the output shape on a fixed-canvas family. Graph-wide for the
     * same reason as [onSetResolution], though for a different one underneath:
     * the sampler uses the ratio to place the rectangle it paints and the
     * decoder uses it to cut that rectangle out, so the two disagreeing crops
     * the wrong region of a correctly rendered picture.
     */
    onSetAspect: (String) -> Unit = {},
    /** The picture this node is showing, if any. */
    preview: ImageBitmap? = null,
    onViewFullscreen: () -> Unit = {},
    /** The picture a `crop` node is framing — its upstream image. */
    cropSource: ImageBitmap? = null,
    /** The picture an `image.mask` node is painted on — its upstream image. */
    maskSource: ImageBitmap? = null,
    /** ⭐ The same three actions the fullscreen viewer offers. Null hides them. */
    onSaveImage: (() -> Unit)? = null,
    /** ⭐ Hand this node's picture to another app. */
    onShareImage: () -> Unit = {},
    onKeepImage: (() -> Unit)? = null,
    onClearOutput: (() -> Unit)? = null,
    /**
     * What the graph demands of this node's output, for a node whose size is
     * derived (`crop`).
     *
     * ⚠⚠ It decides three things at once, and they have to agree: the shape
     * of the framing view, whether `out_w`/`out_h` are shown LOCKED with a
     * reason, and whether the free `aspect` chips are offered at all. Offering a
     * shape the output cannot have is the bug the whole derivation exists to
     * remove.
     */
    demand: SizeDemand = SizeDemand.None,
    /**
     * The seed that made this node's picture, or null when there is none yet.
     * ⚠ It comes from the SAMPLER upstream, not from this node. [seedFor].
     */
    seed: String? = null,
    onClearImage: () -> Unit = {},
) {
    // ⚠ Local: an unanswered confirm is not something to persist, same as every
    // other one in the app.
    var confirmingDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(nodeId, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Text(
                node.type,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⭐⭐ The crop node's real interface. Its four params are the rectangle;
        // the number fields below stay, because they are what a workflow stores
        // and because typing an exact value is sometimes the point.
        cropSource?.let { src ->
            val outW = node.params["out_w"]?.toIntOrNull() ?: 0
            val outH = node.params["out_h"]?.toIntOrNull() ?: 0
            CropEditor(
                source = src,
                rect = cropRectOf(node),
                // ⚠⚠ ONE write per gesture, not four. Setting the params
                // individually ran the canvas-update-and-autosave path four
                // times per pointer event, and the concurrent saves that came
                // out of that are what crashed the app mid-drag.
                onChange = { r -> onSetParams(nodeId, r.asParams().toMap()) },
                outW = outW,
                aspect = cropAspect(node, src.width, src.height),
                padBlur = node.params[CropNode.PAD] == CropNode.PAD_BLUR,
            )
            // ⭐⭐ The photo is too small for what is being asked of it, said
            // plainly. This is the ONE state where bars appear, and a user who
            // has not been told will read them as a bug in the cropper rather
            // than as the honest answer to "this picture has fewer pixels than
            // the pipeline needs".
            if (CropGeometry.needsPadding(src.width, src.height, outW, outH)) {
                Text(
                    "this picture is ${src.width}×${src.height}, smaller than the " +
                        "${outW}×$outH being asked for — the bars are padding, not a crop. " +
                        "Enlarging it instead would only make it soft.",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (outW > 0) "drag to move · pinch to zoom · out ${outW}×$outH"
                else "drag to move · pinch to zoom · the frame is the output, at its own size",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⭐⭐ The mask node's real interface.
        maskSource?.let { src ->
            MaskToolbar(
                nodeId = nodeId,
                node = node,
                source = src,
                onEditMask = onEditMask,
            )
        }
        if (maskSource == null && node.type == "image.mask") {
            Text(
                "no picture to paint on yet — wire an image into this node, and " +
                    "the picture appears here as soon as it can be worked out.",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cropSource == null && node.type == "image.crop") {
            // ⚠ Says WHY there is no framing view. It no longer says "press Run
            // once": the input is resolved on demand when it can be
            // (`HarnessViewModel.resolveInputPreview`), so the only cases left
            // are genuinely missing input -- nothing wired, or no photo picked.
            Text(
                if (node.inputs.containsKey("image"))
                    "No picture yet — choose one on the Load Image node this is wired to."
                else
                    "Wire an image into this node to frame it.",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠ Which knob's batch popup is open, or null. Local: an unanswered
        // popup is not something to persist.
        var batching by remember(nodeId) { mutableStateOf<Widget?>(null) }

        val widgets = type?.widgets.orEmpty()
        // ⚠ Only when there is a frame to explain: on a `crop` with no input
        // picture the chooser is a knob for bars that cannot appear yet.
        val padWidget = if (cropSource != null) {
            widgets.firstOrNull { it.name == CropNode.PAD }
        } else null
        // ⭐⭐ The pad chooser sits DIRECTLY under the framing view, because it
        // answers a question the framing view has just raised: the bars appear
        // as soon as the frame runs off the picture, and "black or mirrored" is
        // then the next thing you want. It used to be near the bottom, under the
        // numbers, which is nowhere near the thing it changes.
        padWidget?.let { w ->
            ChoiceRow(
                label = w.name,
                hint = w.hint,
                options = w.options.orEmpty(),
                current = node.params[w.name] ?: w.default.orEmpty(),
                onPick = { onSetParam(nodeId, w.name, it) },
            )
        }

        // ⭐ The picture, where the person editing the node can actually see it.
        // ⚠ `Fit`, so a portrait photo is not cropped to a square in the one
        // place the node's own input is being inspected.
        //
        // ⚠⚠ NOT on a node that has a framing view. A `crop` node's own output
        // drawn under the cropper is a second copy of the same picture, smaller
        // and non-interactive, and it says nothing the frame above it has not
        // already said -- it only pushes every actual control off the screen.
        // Asked for from the phone, 2026-09-09.
        // ⭐⭐ The SAME actions the fullscreen viewer offers, on the node.
        //
        // ⚠⚠ A picture appears in three places — the node preview, this sheet,
        // and the viewer — and an action available in one and missing from
        // another reads as a bug (`docs/UI.md`). These were built on the viewer
        // first and the sheet was forgotten, which is the failure that section
        // exists to prevent.
        if (preview != null && cropSource == null && maskSource == null && onSaveImage != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // ⭐ Share sits beside Save here too — same set, same order,
                // on every surface that shows one picture (`docs/UI.md` §5).
                IconButton(onClick = { onShareImage() }) {
                    Icon(
                        com.abrah.nightmare.ui.ShareIcon,
                        contentDescription = "share this picture",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onSaveImage() }) {
                    Icon(
                        com.abrah.nightmare.ui.SaveIcon,
                        contentDescription = "save to the gallery",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                onKeepImage?.let { keep ->
                    IconButton(onClick = keep) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = "keep this and its flow",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                onClearOutput?.let { clear ->
                    IconButton(onClick = clear) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "clear this picture",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        // ⚠⚠ …and NOT on a `mask` node either, for the same reason plus a
        // sharper one: the mask's own output is a black-and-white raster, and
        // the editor above is already showing that same mask as a RED overlay
        // on the picture you are painting. Two renderings of one thing, one of
        // which is unreadable on its own, and the b/w copy pushed the brush
        // controls off the screen. It still appears on the node ON THE CANVAS,
        // where it is the only thing that shows what the node produces.
        // Asked for from the phone, 2026-09-10.
        preview?.takeIf { cropSource == null && maskSource == null }?.let {
            Image(
                bitmap = it,
                contentDescription = "this node's image",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠ Bounded by the WINDOW, not by a fixed 240dp. In
                    // landscape the whole window is ~360dp tall, so a flat cap
                    // is most of the screen -- and on a `crop` node this picture
                    // sits UNDER the framing view, which has already taken half.
                    .heightIn(max = minOf(240.dp, LocalConfiguration.current.screenHeightDp.dp * 0.4f))
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onViewFullscreen() },
            )
        }

        // ⭐⭐ WHICH seed made this. `seed = 0` means a new picture every Run, so
        // the number that produced the one on screen exists only in the run that
        // rolled it -- and a user who likes what they see has no other way to
        // ask for it again. ⚠ Beside a COPY, because the thing you do with a
        // ten-digit number is paste it into the sampler, and retyping it off a
        // screen by hand is how you get a different picture and no idea why.
        if (preview != null && seed != null) SeedRow(seed)

        // ⭐ The popup for whichever knob's BAT icon was tapped.
        batching?.let { w ->
            BatchDialog(
                node = node,
                widget = w,
                alreadyArmed = com.abrah.nightmare.BatchParams.axesOf(
                    com.abrah.nightmare.Graph(listOf(node))
                ).size,
                onDismiss = { batching = null },
                onSet = { spec ->
                    onSetParam(nodeId, com.abrah.nightmare.BatchParams.keyFor(w.name), spec)
                },
            )
        }

        if (widgets.isEmpty()) {
            // ⚠ Says so rather than showing an empty sheet. A node with no
            // knobs and a node whose type failed to load look identical
            // otherwise, and one of those is a bug.
            Text(
                if (type == null) "unknown node type — is its plugin loaded?"
                else "this node has no widgets",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⭐ A picker for the one widget nobody can type: a content:// URI.
        //
        // ⚠ It REPLACES that widget's text field rather than sitting above it.
        // The first golden of this sheet showed the chosen URI twice -- once
        // under the button and once in an editable box -- and the box is worse
        // than redundant: a hand-typed `content://media/.../258620` is a
        // valid-looking string that resolves to nothing, or to somebody else's
        // picture.
        val picked = if (node.type == "image.load") "uri" else null
        if (picked != null) {
            ImagePicker(
                current = node.params[picked].orEmpty(),
                onPicked = { uri -> onSetParam(nodeId, picked, uri) },
                onClear = onClearImage,
            )
        }

        // ⚠ A size the graph fixed is not a knob. It is still SHOWN -- a
        // locked field with a reason is how this app already explains the
        // context-key params -- but the shape chooser beside it is hidden
        // outright, because there is nothing left to choose.
        val sized = demand as? SizeDemand.Exactly
        val conflict = demand as? SizeDemand.Conflict

        // ⭐⭐ **The render size, as ONE control where the node carries two
        // params.** `width` and `height` stay separate params -- that is what a
        // saved workflow stores and what `backendContextKey` reads -- but they
        // are never two knobs to a person: nobody wants 768 wide and 512 tall
        // as independent choices, because only the PAIRS a patch file exists
        // for can be rendered at all.
        //
        // ⚠⚠ Chips of the reachable sizes, never number fields. A typed 640
        // has no `640.patch`, and `BackendProcess.start` would refuse the launch
        // -- correctly, but only after the user had already committed to it.
        val sizeKnob = type?.widgets.orEmpty()
            .count { it.contextKey && (it.name == "width" || it.name == "height") } == 2
        if (sizeKnob && resolutions.size > 1) {
            val current = com.abrah.nightmare.Res(
                node.params["width"]?.toIntOrNull() ?: 0,
                node.params["height"]?.toIntOrNull() ?: 0,
            ).toString()
            val labels = resolutions.map { it.toString() }
            // ⚠ Same threshold as every other choice in this sheet: past four,
            // chips become a scrolling strip that can hide the current value.
            // Seven resolutions is exactly the case that motivated the rule.
            if (labels.size > CHIP_LIMIT) {
                ChoiceDropdown(
                    label = stringResource(R.string.resolution),
                    hint = stringResource(R.string.resolution_reloads),
                    options = labels,
                    current = current,
                    onPick = { l -> com.abrah.nightmare.Res.fromLabel(l)?.let(onSetResolution) },
                )
            } else {
                ChoiceRow(
                    label = stringResource(R.string.resolution),
                    hint = stringResource(R.string.resolution_reloads),
                    options = labels,
                    current = current,
                    onPick = { l -> com.abrah.nightmare.Res.fromLabel(l)?.let(onSetResolution) },
                )
            }
        }

        for (w in widgets) {
            if (w.name == picked) continue
            // ⚠ Drawn above as one size control, or not at all. A node whose
            // model serves a single size has nothing to choose, and two locked
            // number fields saying 512 would be noise on every node in the graph.
            if (sizeKnob && (w.name == "width" || w.name == "height")) continue
            // ⚠ Already drawn, under the framing view it belongs to.
            if (w === padWidget) continue
            if (w.name == "aspect" && sized != null) continue
            // ⚠⚠ The mask's ops string is never worth typing. `crop` keeps its
            // four number fields beside the framing view because an exact
            // rectangle is sometimes the point; a list of stroke coordinates
            // never is, and showing it puts a wall of digits between the two
            // sliders that DO matter.
            if (w.name == com.abrah.nightmare.MaskNode.OPS && maskSource != null) continue
            // ⭐ A short fixed set of values is a row of chips, not a text box
            // that accepts "Black", "mirrored" and a typo that fails at Run.
            val options = w.options
            // ⚠⚠ `aspect` is graph-wide, every other chip row is per-node. The
            // sampler places the rectangle and the decoder cuts it out, so two
            // nodes holding different ratios crops the wrong region of a
            // correctly rendered picture -- with nothing to report it, because
            // both nodes did exactly what they were told.
            val pick: (String) -> Unit =
                if (w.name == "aspect") ({ v -> onSetAspect(v) })
                else ({ v -> onSetParam(nodeId, w.name, v) })
            if (options != null) {
                // ⭐⭐ **An armed CHOICE says what it will sweep, exactly as an
                // armed slider does.**
                //
                // ⚠⚠ A slider replaced itself with "Batching 4 values: …" when
                // armed, but a chips/dropdown knob kept rendering its single
                // parked value with only the toggle icon changing state — so
                // `scheduler`, the one batchable knob that is a CHOICE, showed
                // no armed status and never said which samplers were picked.
                // Reported from the phone 2026-09-11. A control parked on one
                // value under a batch that will run four is two answers to
                // "what will this run with", and the parked one is wrong.
                val armedChoice = if (
                    com.abrah.nightmare.BatchParams.isBatchable(node.type, w.name)
                ) com.abrah.nightmare.BatchParams.armed(node, w.name) else null
                if (armedChoice != null) {
                    val picked = com.abrah.nightmare.BatchParams.valuesOf(w.name, armedChoice)
                        // ⚠ Named, not raw: the sweep list must read the same
                        // way the picker above it does, or the user sees
                        // "dpm_sde_karras" listed for a sampler they chose as
                        // "DPM++ 2M SDE".
                        .map { if (w.name == "scheduler") ModelCatalog.schedulerLabel(it) else it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Batching " + picked.size + " values: " +
                                    picked.joinToString(", "),
                                style = LogTextStyle,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        BatchToggle(armed = armedChoice, onClick = { batching = w })
                    }
                    continue
                }
                // ⭐⭐ **The sampler is FIVE names and a Karras toggle**, not nine
                // raw ids — `local-dream`'s own presentation, and the upstream
                // this project follows (`CLAUDE.md`). The four `_karras` values
                // are the same four samplers with Karras sigmas, so listing them
                // separately asked the user to scan `dpm_sde_karras` out of a
                // dropdown to find a thing they know as "DPM++ 2M SDE".
                // ⚠ The stored param is untouched — still one of the nine wire
                // values, so a saved workflow and a bug report carry what the
                // backend actually receives.
                if (w.name == "scheduler") {
                    val cur = node.params[w.name] ?: w.default.orEmpty()
                    val (base, karras) = ModelCatalog.splitScheduler(cur)
                    ChoiceDropdown(
                        label = w.name,
                        hint = w.hint,
                        options = ModelCatalog.SAMPLERS.map { it.second },
                        current = ModelCatalog.SAMPLERS.firstOrNull { it.first == base }?.second
                            ?: base,
                        onPick = { label ->
                            val id = ModelCatalog.SAMPLERS.first { it.second == label }.first
                            pick(ModelCatalog.joinScheduler(id, karras))
                        },
                    )
                    // ⚠ Disabled rather than hidden for LCM: a checkbox that
                    // vanishes reads as a bug, where a greyed one with the
                    // sampler's name beside it says the variant does not exist.
                    // ⚠⚠ And it must not merely be greyed — the VALUE has to drop
                    // too, or switching to LCM with Karras armed would send
                    // `lcm_karras`, which the backend's comparison chain does
                    // not know and silently renders as `dpm`.
                    val canKarras = ModelCatalog.karrasSupported(base)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = karras && canKarras,
                            enabled = canKarras,
                            onCheckedChange = { on ->
                                pick(ModelCatalog.joinScheduler(base, on))
                            },
                        )
                        Text(
                            if (canKarras) stringResource(R.string.karras_sigmas)
                            else stringResource(R.string.karras_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (canKarras) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        if (com.abrah.nightmare.BatchParams.isBatchable(node.type, w.name)) {
                            BatchToggle(armed = null, onClick = { batching = w })
                        }
                    }
                    continue
                }
                // ⚠⚠ Chips do not scale. `pad` has two values and reads as a
                // pair of buttons; `scheduler` has NINE, which becomes a
                // horizontally-scrolling strip where the current value can be
                // off-screen — a control that hides its own state. Past four,
                // a dropdown shows the selection and nothing else.
                if (options.size > CHIP_LIMIT) {
                    ChoiceDropdown(
                        label = w.name,
                        hint = w.hint,
                        options = options,
                        current = node.params[w.name] ?: w.default.orEmpty(),
                        onPick = { pick(it) },
                    )
                } else {
                    ChoiceRow(
                        label = w.name,
                        hint = w.hint,
                        options = options,
                        current = node.params[w.name] ?: w.default.orEmpty(),
                        onPick = { pick(it) },
                    )
                }
                // ⚠ `scheduler` is batchable and is NOT a range — its popup is
                // a multi-select over these same options.
                if (com.abrah.nightmare.BatchParams.isBatchable(node.type, w.name)) {
                    BatchToggle(
                        armed = com.abrah.nightmare.BatchParams.armed(node, w.name),
                        onClick = { batching = w },
                    )
                }
                continue
            }
            // ⚠ The sampler used to need a case here: it carried a prompt that
            // the wired conditioning silently overrode, so the field had to
            // apologise for itself in its own supporting text. It no longer has
            // one (docs/ARCHITECTURE.md §3) — the fix for a knob that does nothing
            // was to delete the knob, not to label it.
            // ⚠ The graph's reason beats the type's: "vae_encode needs 512x512"
            // says WHICH node did this, which is the thing a user needs in order
            // to change it.
            val why = when {
                (w.name == "out_w" || w.name == "out_h") && sized != null -> sized.reason()
                (w.name == "out_w" || w.name == "out_h") && conflict != null ->
                    "two consumers disagree -- ${conflict.reason()}"
                else -> w.locked
            }
            // ⭐⭐ A knob with BOTH bounds is a slider, not a text field.
            //
            // ⚠ `steps`, `cfg` and `denoise` declare a range and were typed
            // into: a number pad over the canvas, no sense of where 7.5 sits
            // between 1 and 20, and nothing stopping 200. ⚠ `seed` declares no
            // range on purpose — it is an identifier, not a magnitude — so it
            // stays a text field, and so does anything locked.
            // ⭐⭐ **The batch affordance sits ON the knob it sweeps.** Asked
            // for from the phone, 2026-09-10, and it is the right shape for the
            // same reason the seed lock lives in the run bar: the control
            // belongs next to the thing it changes, not in a separate sheet
            // where you have to already know the feature exists.
            val batchable = com.abrah.nightmare.BatchParams.isBatchable(node.type, w.name)
            val armedSpec = if (batchable) {
                com.abrah.nightmare.BatchParams.armed(node, w.name)
            } else null
            // ⭐⭐ **An armed knob shows what it will SWEEP, not the one value
            // it is parked on.** A slider sitting at 7.5 under a bar that says
            // "Batching 4 cfg" is two answers to "what will this run with", and
            // the slider is the wrong one. Asked for from the phone,
            // 2026-09-10.
            if (armedSpec != null) {
                val vals = com.abrah.nightmare.BatchParams.valuesOf(w.name, armedSpec)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Batching " + vals.size + " values: " + vals.joinToString(", "),
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    BatchToggle(armed = armedSpec, onClick = { batching = w })
                }
                continue
            }
            if (why == null && w.numeric && w.min != null && w.max != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SliderRow(
                            widget = w,
                            current = node.params[w.name] ?: w.default.orEmpty(),
                            onSet = { onSetParam(nodeId, w.name, it) },
                        )
                    }
                    if (batchable) {
                        BatchToggle(
                            armed = com.abrah.nightmare.BatchParams.armed(node, w.name),
                            onClick = { batching = w },
                        )
                    }
                }
                continue
            }
            // ⚠⚠⚠ **The toggle belongs to the KNOB, not to the control drawn
            // for it.** It was added to the slider branch and the dropdown
            // branch and NOT to this one — so `seed`, which declares no min/max
            // and therefore falls through to a text field, had no batch icon at
            // all while every other sampler knob did. Reported from the phone,
            // 2026-09-10, and it is the third time a control has been attached
            // to one rendering path instead of to the thing it acts on.
            Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
            OutlinedTextField(
                // ⚠ The manifest default when the graph carries nothing, so
                // the field shows what the node will actually RUN with
                // rather than an empty box that means "0.5".
                value = node.params[w.name] ?: w.default.orEmpty(),
                onValueChange = {
                    // ⚠⚠ An emptied NUMERIC field writes its default rather
                    // than "", because "" is not a number: `seed` blank made
                    // `node.int("seed")` throw at Run with "param is not an
                    // int", which reads as a broken sampler rather than as an
                    // empty box. ⚠ The default for `seed` is "0", which already
                    // means "roll a new one".
                    if (why == null) {
                        onSetParam(
                            nodeId,
                            w.name,
                            if (it.isBlank() && w.numeric) w.default.orEmpty() else it,
                        )
                    }
                },
                readOnly = why != null,
                enabled = why == null,
                label = { Text(if (why != null) "${w.name}  (locked)" else w.name) },
                supportingText = {
                    val range = if (w.min != null && w.max != null) {
                        "  ${w.min}..${w.max}"
                    } else ""
                    Text(
                        why ?: w.hint ?: (w.type + range),
                        style = LogTextStyle,
                        color = if (why != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // ⚠ Only Text Encode has these now, and a prompt is the one
                // field people paste paragraphs into.
                singleLine = w.name != "prompt" && w.name != "negative",
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (w.numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            }
            if (batchable) {
                BatchToggle(
                    armed = com.abrah.nightmare.BatchParams.armed(node, w.name),
                    onClick = { batching = w },
                )
            }
            }        }

        // ⚠ The wiring is shown but not editable here. A wire is a gesture
        // on the canvas; offering a second way to change it in a sheet would
        // mean two mental models for one thing.
        if (node.inputs.isNotEmpty()) {
            Text(
                "inputs: " + node.inputs.entries.joinToString(", ") { "${it.key} ← ${it.value}" },
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ⚠ Delete lives here rather than on the canvas: a gesture that
        // removes a node is a gesture someone makes by accident, and this
        // is already the "I meant this node" surface.
        //
        // ⚠⚠ …which was the whole argument for it NOT asking, and it was
        // wrong. Being the deliberate surface makes the button reachable, not
        // the tap deliberate: this sits at the bottom of a scrolling sheet
        // full of knobs, so it is one mis-scrolled tap away from taking the
        // node and every wire on it with no undo. Every other destructive
        // action in the app already asks -- multi-select delete, a saved flow,
        // a model -- and this was the one that did not. `docs/UI.md` §5.
        TextButton(onClick = { confirmingDelete = true }) {
            Text("delete node", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            // ⚠ Names the node, exactly as the canvas's own confirm does: the
            // sheet is covering the node it is asking about.
            title = { Text("Delete \"$nodeId\"?") },
            text = {
                Text(
                    "Every wire into or out of it goes too, and this cannot be undone.",
                    style = LogTextStyle,
                )
            },
            confirmButton = {
                Button(
                    // ⚠ Closed BEFORE the delete. `onDelete` closes the sheet
                    // this dialog belongs to, and leaving the flag set would
                    // re-open it over the canvas asking about a node that is
                    // already gone.
                    onClick = { confirmingDelete = false; onDelete(nodeId) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * One knob whose values are a short fixed set, as a row of chips.
 *
 * ⚠⚠ It exists because a widget with two legal values rendered as a free
 * text field invites `Black`, `mirrored`, and a typo that reads as valid and
 * fails at Run -- and because `Widget.options` is declared in the manifest, so a
 * Tier 0 plugin gets this for free the day it wants an enum.
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * Past this many options a chip row becomes a scrolling strip, so the choice
 * moves into a dropdown. ⚠ Four fits a 411dp phone without scrolling.
 */
private const val CHIP_LIMIT = 4

/** Below this, a [Widget.fine] knob keeps two decimals. The distilled-model band. */
private const val FINE_EDGE = 2f

/**
 * One value out of a list too long for chips.
 *
 * ⚠ Read-only text field as the anchor rather than a bare button: it matches
 * every other row in this sheet, and the label stays visible while a value is
 * chosen — a plain button showing only the current value gives no clue what it
 * sets.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceDropdown(
    label: String,
    hint: String?,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            supportingText = hint?.let {
                {
                    Text(
                        it,
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (o in options) {
                DropdownMenuItem(
                    text = { Text(o) },
                    onClick = { onPick(o); open = false },
                )
            }
        }
    }
}

/**
 * A bounded number, as a slider with its value in the label.
 *
 * ⚠⚠ The VALUE IS IN THE LABEL, and that is not decoration: a slider with no
 * readout cannot tell you that cfg is 1.5 rather than 1.6, and on a distilled
 * checkpoint that difference is visible in the picture.
 *
 * ⚠ An `int` knob snaps to whole numbers via `steps`; a `float` keeps one
 * decimal. Writing back the raw float would put `7.500000119` into the graph
 * and into every cache key derived from it.
 */
@Composable
private fun SliderRow(widget: Widget, current: String, onSet: (String) -> Unit) {
    val min = widget.min!!.toFloat()
    val max = widget.max!!.toFloat()
    val isInt = widget.type == "int"
    // ⚠ Decimals follow the RANGE, not the type. One decimal is right for cfg
    // over 1..20 and useless for feather over 0..0.2, where every value a user
    // can pick rounds to "0.0" — which reads as a slider that does nothing.
    // ⚠ Decimals follow the VALUE for a [Widget.fine] knob: two below 2.0,
    // where a distilled model lives and 1.02 differs visibly from 1.2, and one
    // above it, where nobody is choosing between 7.4 and 7.45.
    fun decimalsAt(v: Float) =
        if (widget.fine) (if (v < FINE_EDGE) 2 else 1)
        else if (max - min <= 1f) 2 else 1
    // ⚠ Falls back to the DEFAULT, not to `min`: a param that failed to parse
    // is a bug, and pinning the slider to the low end of the range would
    // quietly change what the node renders with.
    val value = current.toFloatOrNull()
        ?: widget.default?.toFloatOrNull()
        ?: min
    val shown = value.coerceIn(min, max)
    // ⭐⭐ A [Widget.fine] slider is NOT linear in its value: the track position
    // is the square root of the normalised value, so the bottom of the range
    // gets far more travel. On cfg 1..20 that turns the 1.0-2.0 band from 5% of
    // the track into ~23% -- enough to land 1.02 on with a finger, which is the
    // whole point of the request.
    //
    // ⚠ Monotonic and exactly invertible, so the thumb sits where the value
    // says it does; a piecewise mapping would have a visible kink at the joint.
    // ⚠ The VALUE written to the graph is unchanged by any of this -- the
    // curve is presentation, and the param stays a plain number.
    fun toTrack(v: Float): Float =
        if (!widget.fine || max <= min) v
        else min + (max - min) * sqrt(((v - min) / (max - min)).coerceIn(0f, 1f))
    fun fromTrack(t: Float): Float {
        if (!widget.fine || max <= min) return t
        val n = ((t - min) / (max - min)).coerceIn(0f, 1f)
        return min + (max - min) * n * n
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "${widget.name}   " +
                if (isInt) shown.roundToInt().toString()
                else fixed(shown, decimalsAt(shown)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = toTrack(shown),
            onValueChange = { t ->
                val v = fromTrack(t)
                onSet(if (isInt) v.roundToInt().toString() else fixed(v, decimalsAt(v)))
            },
            valueRange = min..max,
            // ⚠⚠ CONTINUOUS, even for an int knob — the snapping is done in
            // onValueChange instead. Material3 draws a tick mark per position
            // whenever `steps > 0`, so `steps` over 1..50 came out as 48 dots
            // stippled across the track: unreadable, and it made a slider that
            // is really about "roughly how many" look like a precision
            // instrument. Rounding the written value gives the same snap with
            // none of that.
            steps = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        widget.hint?.let {
            Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
@Composable
private fun MaskToolbar(
    nodeId: String,
    node: com.abrah.nightmare.Node,
    source: ImageBitmap,
    /**
     * ⚠⚠ A TRANSFORM, not a setter. See `HarnessViewModel.editMask`: writing an
     * absolute ops string computed from `node` is what made fast painting drop
     * strokes, because `node` is whatever the last recomposition captured.
     */
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit,
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

    val state = com.abrah.nightmare.MaskNode.stateOf(node)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MaskEditor(
            source = source,
            state = state,
            tool = tool,
            brushRadiusFrac = radius,
            onStroke = { stroke ->
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            // ⚠⚠ ICONS, and DreamUI's icons — undo, invert, clear. These were
            // two TEXT buttons and no invert at all, though `MaskOp.Invert` has
            // been in the model and handled by the compositor the whole time.
            // ⚠ Small targets so the row stays one line beside the two tool
            // chips; the words survive as content descriptions.
            IconButton(
                onClick = { onEditMask(nodeId) { it.dropLast() } },
                enabled = !state.isEmpty,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    com.abrah.nightmare.ui.UndoIcon,
                    contentDescription = stringResource(R.string.cd_undo_stroke),
                    modifier = Modifier.size(18.dp),
                )
            }
            // ⭐ Invert flips what is masked SO FAR and later strokes add to the
            // flipped mask — which is what makes "invert, then tidy the edges"
            // work, and why it is an OP in the list rather than a final flag.
            // ⚠ Enabled on an empty mask too: inverting nothing is "mask
            // everything", which is a legitimate and useful starting point.
            IconButton(
                onClick = { onEditMask(nodeId) { it.plus(com.abrah.nightmare.MaskOp.Invert) } },
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    com.abrah.nightmare.ui.InvertMaskIcon,
                    contentDescription = stringResource(R.string.cd_invert_mask),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(
                onClick = { onEditMask(nodeId) { it.cleared() } },
                enabled = !state.isEmpty,
                modifier = Modifier.size(34.dp),
            ) {
                Icon(
                    com.abrah.nightmare.ui.ClearLayersIcon,
                    contentDescription = stringResource(R.string.cd_clear_mask),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // ⚠ PIXELS at 512, exactly as DreamUI reads it out: `radius * 2 * 512`
        // is the stroke's diameter on the reference edge. A percentage of an
        // edge nobody can see is not a number anyone can carry between the two.
        Text(stringResource(R.string.mask_brush_px, (radius * 2 * 512).roundToInt()), style = LogTextStyle)
        Slider(
            value = radius,
            onValueChange = { radius = it },
            valueRange = BRUSH_MIN..BRUSH_MAX,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            // ⚠⚠ Says the convention out loud. White-takes-repaint is the one
            // thing about masking that is easy to get backwards and impossible
            // to notice — the wrong way round replaces the region you meant to
            // keep, and the picture still looks plausible.
            if (state.isEmpty) {
                stringResource(R.string.mask_paint_hint)
            } else {
                stringResource(
                    R.string.mask_covered,
                    (MaskRaster.coverageFraction(state) * 100).roundToInt(),
                )
            },
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * ⭐ The little BAT toggle beside a sweepable knob.
 *
 * ⚠ It shows ARMED state by colour, not by a second label: the row is already
 * a name, a value and a slider, and a fourth element would wrap on a phone.
 */
@Composable
private fun BatchToggle(armed: String?, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(
            com.abrah.nightmare.ui.BatchIcon,
            contentDescription = if (armed == null) "sweep this parameter" else "sweeping $armed",
            tint = if (armed != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * ⭐⭐ Arm a knob for a sweep — **with sliders, never a text box.**
 *
 * ⚠⚠⚠ The first version asked people to type `1..20 by 2`. That is a grammar to
 * learn, on a phone keyboard, where a typo is silent until the count reads zero
 * — and the person typing it has a slider for every other number in this app.
 * Reported bluntly from the phone, 2026-09-10.
 *
 * ⇒ Three shapes, one per kind of knob:
 *  - a **range** (`steps`, `cfg`, `denoise`): from, to, and a step that can only
 *    grow, in multiples of the knob's own increment
 *  - a **set** (`scheduler`): multi-select over its declared options
 *  - a **count** (`seed`): how many, because a seed is an identifier rather than
 *    a magnitude and "seeds 1000..1009" is nobody's intent
 *
 * ⚠⚠ **The step only gets COARSER.** `cfg` moves in 0.1; allowing finer makes
 * 1..20 into 1900 renders, and the cap would then refuse after the work of
 * setting it up rather than before. ⚠ And `to` is SNAPPED to the last value the
 * step actually reaches — otherwise the slider shows a bound the sweep never
 * produces, which is a control lying about its own state.
 */
@Composable
private fun BatchDialog(
    node: com.abrah.nightmare.Node,
    widget: Widget,
    alreadyArmed: Int,
    onDismiss: () -> Unit,
    onSet: (String) -> Unit,
) {
    val stored = com.abrah.nightmare.BatchParams.armed(node, widget.name).orEmpty()
    val sweep = remember(widget) {
        com.abrah.nightmare.BatchParams.sweepFor(
            widget.name, widget.min, widget.max, widget.type == "int",
        )
    }
    val isRange = com.abrah.nightmare.BatchParams.isRange(widget.name)

    // ⚠ Seeded from what is already armed, so reopening an armed knob shows
    // where it is instead of resetting it.
    val parsed = remember(stored) { com.abrah.nightmare.BatchValues.parse(stored) }
    var from by remember {
        mutableStateOf(parsed.firstOrNull()?.toFloatOrNull() ?: sweep.min.toFloat())
    }
    var to by remember {
        mutableStateOf(parsed.lastOrNull()?.toFloatOrNull() ?: sweep.max.toFloat())
    }
    // How many of the knob's natural increments one sweep step is.
    var mult by remember {
        mutableStateOf(
            if (parsed.size > 1) {
                val d = (parsed[1].toFloatOrNull() ?: 0f) - (parsed[0].toFloatOrNull() ?: 0f)
                (d / sweep.step).roundToInt().coerceAtLeast(1)
            } else {
                1
            }
        )
    }
    var chosen by remember {
        mutableStateOf(stored.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }

    // ⚠⚠ Everything below is DERIVED from the sliders — no second source of
    // truth to drift from them.
    // ⚠⚠ **The sliders CORRECT THEMSELVES rather than letting you build an
    // invalid sweep and then refusing it.** The user's call, 2026-09-10: force
    // the controls to fit the validation.
    //
    // Two corrections, in this order:
    //  - too MANY values: the step is coarsened until the count fits the cap,
    //    because the range is what the person chose and the step is the thing
    //    they care least about
    //  - too FEW (one value): the step is fined back down, because a sweep of
    //    one is not a sweep and the cheapest fix is a smaller stride
    val maxMult = maxStepMultiple(sweep)
    val fitted = remember(from, to, mult, sweep) {
        var m = mult.coerceIn(1, maxMult)
        val span0 = (to - from).coerceAtLeast(0f)
        // Coarsen while the count is over the ceiling.
        while (m < maxMult &&
            (span0 / (sweep.step * m).toFloat()).toInt() + 1 >
            com.abrah.nightmare.BatchParams.MAX_PER_AXIS
        ) m++
        // …then fine down while the count is under the floor.
        while (m > 1 &&
            (span0 / (sweep.step * m).toFloat()).toInt() + 1 <
            com.abrah.nightmare.BatchParams.MIN_PER_AXIS
        ) m--
        m
    }
    val step = (sweep.step * fitted).toFloat()
    val span = (to - from).coerceAtLeast(0f)
    val n = if (step <= 0f) 0 else (span / step).toInt() + 1
    val snappedTo = from + step * (n - 1).coerceAtLeast(0)
    val dp = if (sweep.int) 0 else if (sweep.step < 0.1) 2 else 1
    val values = if (n <= 0) emptyList() else (0 until n).map { i ->
        val v = from + step * i
        if (sweep.int) v.roundToInt().toString() else fixed(v, dp)
    }

    val spec = if (!isRange) chosen.joinToString(", ") else values.joinToString(", ")
    val others = alreadyArmed -
        (if (com.abrah.nightmare.BatchParams.armed(node, widget.name) != null) 1 else 0)
    val why = com.abrah.nightmare.BatchParams.refusalFor(widget.name, spec, others)
    val armedValues = com.abrah.nightmare.BatchParams.valuesOf(widget.name, spec)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sweep " + widget.name) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    // ⭐ SCHEDULER: a set, with no order to make a range from.
                    !isRange -> {
                        Text(
                            "pick up to " + com.abrah.nightmare.BatchParams.MAX_PER_AXIS,
                            style = LogTextStyle,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            for (o in widget.options.orEmpty()) {
                                FilterChip(
                                    selected = o in chosen,
                                    onClick = {
                                        chosen = if (o in chosen) chosen - o else chosen + o
                                    },
                                    label = { Text(o, fontSize = 12.sp) },
                                    shape = RoundedCornerShape(10.dp),
                                )
                            }
                        }
                    }
                    // ⭐ RANGE: from, to, and a step that only gets coarser.
                    else -> {
                        Text("from  " + fixed(from, dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            value = from,
                            onValueChange = {
                                from = snapToStep(it, sweep)
                                if (to < from) to = from
                            },
                            valueRange = sweep.min.toFloat()..sweep.max.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "to  " + fixed(snappedTo, dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Slider(
                            value = to,
                            onValueChange = { to = snapToStep(it, sweep).coerceAtLeast(from) },
                            valueRange = sweep.min.toFloat()..sweep.max.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // ⚠⚠ In MULTIPLES of the knob's own increment, so it can
                        // never go finer than the knob itself moves.
                        Text("step  " + fixed(step, dp), style = MaterialTheme.typography.bodyMedium)
                        Slider(
                            // ⚠ Shows the FITTED value, so the handle sits where
                            // the sweep actually is rather than where the drag
                            // left it — a slider that disagrees with its own
                            // result is the thing this correction exists to stop.
                            value = fitted.toFloat(),
                            onValueChange = { mult = it.roundToInt().coerceAtLeast(1) },
                            valueRange = 1f..maxMult.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                // ⭐⭐ What it will actually run, always. This line is what
                // replaces having to understand a grammar.
                Text(
                    why ?: when {
                        armedValues.isEmpty() -> "not sweeping — this knob keeps its value"
                        else -> armedValues.size.toString() + " runs · " +
                            armedValues.joinToString(", ")
                    },
                    style = LogTextStyle,
                    color = if (why != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSet(spec); onDismiss() },
                enabled = why == null,
            ) { Text(if (armedValues.isEmpty()) "Release" else "Arm") }
        },
        dismissButton = {
            // ⚠ Release reachable WITHOUT dragging a slider to nothing — one
            // tap, the same as the run bar's ✕.
            if (com.abrah.nightmare.BatchParams.armed(node, widget.name) != null) {
                TextButton(onClick = { onSet(""); onDismiss() }) { Text("Release") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** ⚠ Snap to the knob's own increment, so a slider cannot land between values. */
private fun snapToStep(v: Float, sweep: com.abrah.nightmare.BatchParams.Sweep): Float {
    val s = sweep.step.toFloat()
    if (s <= 0f) return v
    return ((v / s).roundToInt() * s).coerceIn(sweep.min.toFloat(), sweep.max.toFloat())
}

/**
 * ⚠ How coarse the step may get: enough that the widest range still holds at
 * least two values, so the slider never reaches a setting that means nothing.
 */
private fun maxStepMultiple(sweep: com.abrah.nightmare.BatchParams.Sweep): Int {
    val steps = ((sweep.max - sweep.min) / sweep.step).toInt()
    return (steps / 2).coerceAtLeast(1)
}

/**
 * ⚠⚠ `Locale.ROOT`, and this is not pedantry. `"%.2f".format(1.5f)` uses the
 * DEFAULT locale, which across much of the world writes `1,50` — and
 * `"1,50".toFloatOrNull()` is null, so the param becomes unparseable and the
 * node throws `param "cfg" is not a number` at Run. A number a slider writes
 * has to be readable by the parser that reads it back.
 */
private fun fixed(v: Float, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f", v)

@Composable
private fun ChoiceRow(
    label: String,
    hint: String?,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (o in options) {
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    label = { Text(o, fontSize = 12.sp) },
                    shape = RoundedCornerShape(10.dp),
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        hint?.let {
            Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Choose an image, and keep the right to read it.
 *
 * ⚠⚠ `takePersistableUriPermission` is the load-bearing line. Without it the
 * grant dies with the process, so a workflow saved today fails tomorrow with a
 * SecurityException — which `LoadImageNode` reports as "pick the image again",
 * a message that should be rare rather than routine.
 *
 * ⚠ `OpenDocument`, not `GetContent`: only the former's URIs can be persisted.
 * `GetContent` hands back a one-shot grant that looks identical until it stops
 * working.
 */
/**
 ⭐ The system picker, as a plain `() -> Unit` anything can call.
 *
 * ⚠⚠ Hoisted out of [ImagePicker] because the FULLSCREEN viewer needs the same
 * launcher, and an activity-result launcher may not be registered inside an `if`
 * -- it is registered and torn down as the condition flips, which is precisely
 * when the result arrives: the picker runs while this activity is stopped.
 */
@Composable
internal fun rememberImagePick(onPicked: (String) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    // ⚠ Read through `rememberUpdatedState`: the launcher outlives the
    // composition that made it, so a captured callback would write the chosen
    // photo into a node the user has since navigated away from.
    val current = rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // ⚠ Not fatal: the URI still works for this session. Losing the
                // persist is a tomorrow problem, and refusing the pick outright
                // would be a today one.
            }
            current.value(uri.toString())
        }
    }
    return { launcher.launch(arrayOf("image/*")) }
}

@Composable
private fun ImagePicker(current: String, onPicked: (String) -> Unit, onClear: () -> Unit) {
    val pick = rememberImagePick(onPicked)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // ⭐⭐ A WORD while there is no picture, ICONS once there is one, and the
        // asymmetry is deliberate. An empty node has nothing on it to explain
        // itself, so the one control it carries says what it does; a node with a
        // photo already explains itself, and a full-width "choose another" then
        // spends the sheet's widest row restating it. Asked for from the phone.
        if (current.isBlank()) {
            Button(
                onClick = pick,
                shape = RoundedCornerShape(12.dp),
            ) { Text("Choose an image") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ImageActions(
                    onPick = pick,
                    onClear = onClear,
                )
                Text(
                    current,
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

}

/**
 * Pick a different picture, or forget this one.
 *
 * ⭐ Shared with the fullscreen viewer, which is the other place a person is
 * looking straight at the photo and wants to swap or drop it -- and having it in
 * one composable is what keeps the two from drifting into different gestures for
 * the same two jobs.
 */
@Composable
internal fun ImageActions(onPick: () -> Unit, onClear: () -> Unit, tint: Color? = null) {
    IconButton(onClick = onPick) {
        Icon(
            painterResource(R.drawable.ic_gallery),
            contentDescription = "choose a different picture",
            tint = tint ?: MaterialTheme.colorScheme.primary,
        )
    }
    // ⚠ Removes the PICTURE, not the node. Both are destructive and they sit two
    // rows apart in this sheet, which is why this one carries a label saying so
    // rather than being a second bare bin.
    IconButton(onClick = onClear) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = "remove this picture from the node",
            tint = tint ?: MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * `seed 1234567` and a copy button.
 *
 * ⚠ The clipboard write is the whole point, and there is no confirmation of our
 * own: Android 13+ shows one already, and a second toast on top of the system's
 * reads as the app not knowing what just happened.
 */
@Composable
internal fun SeedRow(
    seed: String,
    tint: Color? = null,
    /**
     * ⭐ Pin this seed onto the sampler, so the next Run makes the same picture.
     * Null when there is nothing to pin or it is pinned already.
     */
    onLock: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    // ⚠⚠ ONE bordered container around the number and both of its actions.
    //
    // The lock sat loose in a row of unrelated icons — save, delete, lock —
    // where nothing said WHAT it locked, and a lock icon with no subject reads
    // as "lock the app" or "lock the canvas". Enclosing it with the seed makes
    // the association structural rather than something the user has to infer
    // from adjacency. Reported from the phone 2026-09-10.
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                (tint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f)
            )
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "seed $seed",
            style = LogTextStyle,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IconButton(onClick = { clipboard.setText(AnnotatedString(seed)) }) {
            Icon(
                painterResource(R.drawable.ic_copy),
                contentDescription = "copy the seed",
                tint = tint ?: MaterialTheme.colorScheme.primary,
            )
        }
        onLock?.let {
            IconButton(onClick = it) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "keep this seed for the next Run",
                    tint = tint ?: MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * ⚠⚠ The brush, as DreamUI defines it — `../LocalDream/dreamui`'s
 * `GenerateViewModel.brushRadiusFrac` and `GenerateScreen`'s `MaskParam.BRUSH`
 * range. Kept as named constants so the next person can see they were COPIED
 * rather than chosen, and so a drift between the two apps is a diff rather than
 * an argument.
 */
private const val BRUSH_DEFAULT = 0.08f
private const val BRUSH_MIN = 0.02f
private const val BRUSH_MAX = 0.25f
