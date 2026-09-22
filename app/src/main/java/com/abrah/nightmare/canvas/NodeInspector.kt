package com.abrah.nightmare.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.aspectRatio
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

/**
 * ⭐⭐ **The strip's order: the order the graph RUNS in.**
 *
 * ⚠⚠ It was canvas position (left to right, then top to bottom) for a few
 * hours on 2026-09-20 and the user replaced it on the 21st: *"changing card
 * order on node based on canvas position is a bad and expensive idea. lets
 * just do execution order instead."* Both readings of "bad" hold. Position
 * makes the strip reshuffle every time a node is DRAGGED, so the card you
 * were about to tap moves for a reason that has nothing to do with the flow;
 * and it costs a sort with a map lookup per comparison, recomputed on every
 * recomposition of an open sheet.
 *
 * ⇒ [topoSort] instead — the executor's own order, so the strip reads the
 * way a Run reads: prompt, photo, sampler, output. It is the SAME function
 * the run uses, not a second opinion about what depends on what.
 *
 * ⚠ A graph that cannot be ordered — a cycle, or a wire to a node that is
 * half drawn — falls back to declaration order rather than emptying the
 * strip. An inspector is exactly where someone goes to FIX such a graph, so
 * it must still list its nodes.
 */
fun nodeStripOrder(workflow: Workflow): List<com.abrah.nightmare.Node> {
    val ordered = when (val o = com.abrah.nightmare.topoSort(workflow.graph)) {
        is com.abrah.nightmare.Order.Ok -> o.nodes
        is com.abrah.nightmare.Order.Broken -> workflow.graph.nodes
    }
    val loose = looseNodes(workflow.graph)
    if (loose.isEmpty()) return ordered
    // ⚠ `partition` keeps each side's own order, so both halves stay in
    // execution order — this only moves the boundary.
    val (main, rest) = ordered.partition { it.id !in loose }
    return main + rest
}

/**
 * ⭐⭐⭐ **The nodes that are NOT part of the main flow**, by id.
 *
 * ⚠⚠ The user's rule, 2026-09-22: *"if 2 nodes are connected and theres a
 * 3rd unconnected row, 2 is majority so those show"*. ⇒ the LARGEST weakly
 * connected group is the flow, and everything else is loose.
 *
 * ⚠ **Weakly** connected — direction is ignored. A prompt feeds a sampler and
 * nothing feeds the prompt; on a directed reading it is its own component, and
 * it is obviously part of the flow.
 *
 * ⚠⚠ Ties keep everything. With two groups of two there is no majority, and
 * guessing which pair is "the flow" would hide half a graph on the strength of
 * a coin toss.
 *
 * ⚠⚠⚠ Loose nodes are ORDERED LAST, never hidden — the user's call when
 * asked. A node you just dropped has no wires yet, and a strip that refused to
 * page to it would leave no way to configure it before wiring it up. Being
 * last says "not in the flow" and strands nobody.
 */
fun looseNodes(graph: com.abrah.nightmare.Graph): Set<String> {
    if (graph.nodes.size < 2) return emptySet()
    // Union-find over the wires, undirected.
    val parent = HashMap<String, String>()
    fun find(a: String): String {
        var r = a
        while (parent[r] != null && parent[r] != r) r = parent[r]!!
        return r
    }
    fun union(a: String, b: String) {
        parent.getOrPut(a) { a }; parent.getOrPut(b) { b }
        val ra = find(a); val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
    for (n in graph.nodes) parent.getOrPut(n.id) { n.id }
    for (n in graph.nodes) {
        for (src in n.inputs.values) {
            // ⚠ A wire to a node that is not there (a half-loaded flow) joins
            // nothing rather than throwing.
            if (graph.byId.containsKey(src.node)) union(n.id, src.node)
        }
    }
    val groups = graph.nodes.groupBy { find(it.id) }
    val biggest = groups.values.maxOfOrNull { it.size } ?: return emptySet()
    // ⚠ A tie means no majority — nothing is loose.
    if (groups.values.count { it.size == biggest } > 1) return emptySet()
    return groups.values.filter { it.size != biggest }.flatten().map { it.id }.toSet()
}
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
    /** ⭐ Rename a node — see [NodeInspectorBody.onRename]. */
    onRename: (from: String, to: String) -> Unit = { _, _ -> },
    /**
     * ⭐ A tap with the mask editor's Tap tool, in the PHOTO's coordinates —
     * `HarnessViewModel.tapMask`. The last argument reports back: null, or a
     * sentence to show.
     */
    onTapMask: (node: String, x: Float, y: Float, done: (String?) -> Unit) -> Unit =
        { _, _, _, done -> done(null) },
    onDismiss: () -> Unit,
    /** ⭐ Per node — see [NodeInspectorBody.onSetResolution]. */
    onSetResolution: (node: String, com.abrah.nightmare.Res) -> Unit = { _, _ -> },
    onSetAspect: (String) -> Unit = {},
    /** ⭐ Whether THIS node may arm a sweep — the last-node rule. */
    canSweep: Boolean = true,
    installedModels: List<CheckpointChoice> = emptyList(),
    onSetModel: (String, String) -> Unit = { _, _ -> },
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
    /** ⭐ Send a node's picture into a flow. */
    onSendImage: (String) -> Unit = {},
    onStarImage: (String) -> Unit = {},
    isFavourite: (String) -> Boolean = { false },
    keepDisabledReason: String? = null,
    onDisabledKeep: ((String) -> Unit)? = null,
    onKeepImage: (String) -> Unit = {},
    isKept: (String) -> Boolean = { false },
    onClearOutput: (String) -> Unit = {},
    /**
     * ⭐ Switch which node this sheet is showing — [NodeStrip].
     *
     * ⚠ It must also clear whatever was pending for the node being left: a
     * `cropRequest` or a focused field belongs to that node and would otherwise
     * fire on the new one (`HarnessViewModel.inspectNode`).
     */
    /**
     * ⭐⭐ Import a `.safetensors` adapter from inside the LoRA picker.
     * Null (a golden, a preview) hides the Add button.
     */
    onImportLora: (() -> Unit)? = null,
    /**
     * ⚠⚠ Bumped by `HarnessViewModel.refreshLoras`. The picker reads the
     * DIRECTORY, which cannot be stale but also cannot be observed, so this is
     * what makes an Add inside the open dialog show up in its own list.
     */
    loraEpoch: Int = 0,
    /** ⭐⭐ Enlarge the node's picture by editing the flow behind it. */
    onUpscale: ((String) -> Unit)? = null,
    /** ⭐⭐ What made this picture, for the ⓘ dialog. */
    detailsOf: ((String) -> List<Pair<String, String>>)? = null,
    onInspectNode: (String) -> Unit = {},
) {
    val nodeId = state.editing ?: return
    val node = state.workflow.graph.byId[nodeId] ?: return
    val type = types[node.type]
    // ⚠ A retry for the install that happened after launch: the first checkpoint
    // is what brings a vocabulary to a phone that had none. A no-op once loaded.
    val appCtx = LocalContext.current.applicationContext
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.abrah.nightmare.PromptTokens.ensureLoaded(appCtx)
        }
    }

    val ordered = nodeStripOrder(state.workflow)
    val here = ordered.indexOfFirst { it.id == nodeId }.coerceAtLeast(0)
    // ⭐⭐⭐ **A far longer pull is needed to close this sheet.**
    //
    // ⚠⚠⚠ Reported 2026-09-21: *"sometimes i try to swipe left right but
    // it closes inspector instead"*. It is Compose gesture arbitration, not a
    // bug: the pager claims a drag once HORIZONTAL touch slop is crossed and
    // the sheet once VERTICAL slop is, and a slightly diagonal swipe can cross
    // the sheet's first. Slop is a handful of pixels, so on a diagonal the two
    // are near a coin toss — and M3 also settles to Hidden on VELOCITY alone,
    // so a quick flick needs almost no distance.
    //
    // ⚠⚠ Material3 1.3.1 exposes neither a dismiss threshold nor
    // `sheetGesturesEnabled` (probed — it does not compile). It does expose
    // `confirmValueChange`, which a gesture-driven settle consults and a
    // programmatic `hide()` does not, so vetoing Hidden here lengthens the
    // PULL and leaves the scrim tap and the back gesture alone.
    //
    // ⇒ Losing the arbitration now costs a few pixels and a spring back
    // instead of the sheet. Dismissal still works; it has to mean it.
    // ⚠ A holder, because `confirmValueChange` is passed INTO the state it
    // needs to read — it cannot reference `sheet` before it exists.
    val dismissAfterPx = with(androidx.compose.ui.platform.LocalDensity.current) { 180.dp.toPx() }
    val held = remember { arrayOfNulls<androidx.compose.material3.SheetState>(1) }
    val sheet = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            // ⚠ Defaults to ALLOWING the change: an offset that is not yet
            // measurable must never leave a sheet that cannot be closed.
            target != androidx.compose.material3.SheetValue.Hidden ||
                held[0]?.let {
                    runCatching { it.requireOffset() > dismissAfterPx }.getOrDefault(true)
                } ?: true
        },
    )
    held[0] = sheet
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
    ) {
        // ⭐⭐⭐ **The nodes are PAGES, and the pager is [SwipeTabs]** — the
        // same one the Models sub-tabs use, which is where the asked-for feel
        // comes from: the next node peeks in as the finger moves instead of
        // appearing when it lifts.
        //
        // ⚠⚠⚠ This replaced a hand-rolled `detectHorizontalDragGestures`
        // that stepped on RELEASE. It worked and it felt wrong, because a
        // step-on-release has no in-between to animate — reported 2026-09-21,
        // *"i need it to have proper moving animation … how we can peek onto
        // the next tab as we slide, similar to Models subtabs"*. The sibling
        // already existed and I wrote a worse one beside it, which is the
        // failure `CLAUDE.md` opens with.
        //
        // ⚠⚠ `fillHeight` and a `fillMaxHeight` modifier together: a pager
        // sized by its page resizes the sheet on every swipe, so the pills
        // jump away from the finger — [SwipeTabs] carries that note from the
        // Add node sheet, and a node inspector has far more size variance
        // between pages than that one does (a prompt is three fields, a
        // sampler is a page of sliders). Also what makes stepping look
        // steady, asked for in the same message.
        //
        // ⚠ The pager is the source of truth while the sheet is open;
        // `state.editing` follows it through [onInspectNode] on settle.
        com.abrah.nightmare.ui.SwipeTabs(
            // ⭐⭐⭐ The SAME name the canvas box draws
            // ([com.abrah.nightmare.nodeNameOf]). It was `it.id`, and an id is
            // frozen at creation — a node added as SD 1.5 and switched to
            // FLUX.2 kept announcing itself as `sd15_generate` here while the
            // box beside it already said "FLUX.2 Image edit".
            labels = ordered.map { com.abrah.nightmare.nodeNameOf(it, types).primary },
            // ⭐⭐ Where the main flow ends and the loose nodes begin
            // ([looseNodes]). Null when every node is in the flow, which is the
            // ordinary case and draws no rule at all.
            dividerBefore = run {
                val loose = looseNodes(state.workflow.graph)
                if (loose.isEmpty()) null
                else ordered.indexOfFirst { it.id in loose }.takeIf { it > 0 }
            },
            fillHeight = true,
            initialPage = here,
            onPage = { i -> ordered.getOrNull(i)?.let { onInspectNode(it.id) } },
            modifier = Modifier.fillMaxHeight(),
        ) { page ->
        // ⚠⚠ Shadows the outer `node`/`nodeId`/`type` ON PURPOSE: every line
        // below was written against one node and now renders whichever page
        // it is on. Reading the node from `state.editing` here instead would
        // draw the SETTLED node into the page peeking in, so a swipe would
        // show the same node twice and then snap.
        val node = ordered.getOrNull(page) ?: return@SwipeTabs
        @Suppress("NAME_SHADOWING") val nodeId = node.id
        @Suppress("NAME_SHADOWING") val type = types[node.type]
        // ⭐⭐ Upscale is the one before/after exception to "a renderer's own
        // result belongs to `core.output` alone" ([NodeType.showsResult]) — it
        // shows what it MADE here too, not just what it received just above.
        // Reported 2026-09-18: a generate → upscale chain showed neither
        // picture anywhere until the node reached `core.output`.
        val shownId = if (node.type == com.abrah.nightmare.UpscaleNode.name) {
            state.rendered[nodeId]
        } else {
            state.previews[nodeId]?.first
        }
        val previewId = shownId
        // ⭐ The BEFORE half, same source the canvas box draws
        // ([CanvasState.beforePreviews]) — so the sheet and the graph agree.
        val beforeId = state.beforePreviews[nodeId]?.first
        // ⚠ A crop is framed against its INPUT, not its output. Showing the
        // node's own result would be showing the crop that has already happened.
        // ⚠ Every node that is not an output still acts on its own picture —
        // the rule is about which OUTPUT owns a branch, not about muting
        // everything that holds pixels.
        val actsOnItsPicture = node.type != "core.output" ||
            com.abrah.nightmare.isLastOutput(state.workflow.graph, nodeId)
        // ⚠⚠ [CanvasState.pictureInto] — a sampler upstream is read by what it
        // RENDERED, not by its preview (its framed input).
        val sourceId = state.pictureInto(nodeId, types)
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
            onRename = onRename,
            onTapMask = onTapMask,
            // ⚠ Read HERE, like `demand` above and for the same reason: the body
            // must stay a function of its arguments so the goldens can render
            // it, and this list is a cached disk scan.
            focusField = state.focusField,
            cropRequest = state.cropRequest,
            cropRequestTab = state.cropRequestTab,
            // ⚠⚠ THIS node's model, never the top bar's — they differ as soon as
            // a sampler's checkpoint is picked on the node. `remember`ed on the
            // id so the directory scan runs once per model, not per frame.
            // ⚠⚠⚠ **OFF the first frame.** This is a DIRECTORY SCAN, and it
            // ran inside `remember` during composition — on the main thread,
            // in the frame that opens the sheet. That is the choppy open
            // reported 2026-09-21: the sheet cannot draw until a `listFiles`
            // over a model directory comes back.
            // ⚠ Starts from the model's DECLARED sizes, which need no disk at
            // all, and the scanned list (patches found on disk) replaces it a
            // frame later. The control is correct either way; it only gains
            // entries.
            resolutions = run {
                val modelId = node.params["model"].orEmpty()
                val spec = com.abrah.nightmare.ModelCatalog.byId(modelId)
                androidx.compose.runtime.produceState(
                    initialValue = spec?.resolutions
                        ?: com.abrah.nightmare.SelectedModel.resolutions,
                    key1 = modelId,
                ) {
                    if (spec != null) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.abrah.nightmare.SelectedModel.resolutionsOf(appCtx, spec)
                        }
                    }
                }.value
            },
            onSetResolution = { onSetResolution(nodeId, it) },
            onSetAspect = onSetAspect,
            canSweep = com.abrah.nightmare.canSweep(state.workflow.graph, nodeId),
            // ⚠ Read HERE for the same reason: what a prompt is measured
            // against depends on what it is WIRED into.
            promptBudget = com.abrah.nightmare.PromptTokens.budgetFor(state.workflow.graph, nodeId),
            installedModels = installedModels,
            onSetModel = onSetModel,
            preview = shownId?.let(imageFor),
            onViewFullscreen = { shownId?.let(onViewFullscreen) },
            // ⭐ Upscale's BEFORE half — see [beforeId] above.
            beforeImage = beforeId?.let(imageFor),
            onViewBeforeFullscreen = { beforeId?.let(onViewFullscreen) },
            // ⭐⭐ The framing and the painting live on the SAMPLER now
            // (docs/ARCHITECTURE.md §5.7), so the two editors are drawn for it
            // as well as for the nodes they came from.
            //
            // ⚠⚠ Moving the params without moving the editors is what shipped
            // 1.4.54 with no way to paint a mask by hand — the storage and the
            // interface are one change, not two.
            cropSource = if (node.type in FRAMES) sourceId?.let(imageFor) else null,
            // ⭐⭐ FLUX.2's reference, resolved through the SAME `pictureInto`
            // the base uses — it takes the port as an argument precisely so a
            // second picture into one node does not need a second rule.
            refSource = state.pictureInto(nodeId, types, port = "reference")?.let(imageFor),
            // ⚠ Same upstream picture, different job: the cropper FRAMES it,
            // the mask editor is PAINTED on it. ⚠⚠ Both arrive as the PHOTO —
            // the mask is STORED in the photo's coordinates, so re-framing a
            // shot moves the picture and the strokes together. ⚠⚠⚠ The body
            // then paints on the FRAMED crop and converts at that boundary
            // ([MaskFraming]); it is not a second opinion about which picture
            // this is.
            maskSource = if (node.type in PAINTS) sourceId?.let(imageFor) else null,
            // ⚠ Only for a node that HAS a picture and did not make it from a
            // photo the user chose: a `load_image` already has swap/remove.
            onSaveImage = previewId?.takeIf { node.type != "core.image" }
                ?.let { id -> { onSaveImage(id) } },
            onShareImage = { previewId?.let(onShareImage) },
            onSendImage = { previewId?.let(onSendImage) },
            // ⭐⭐ The clip's frames, so the sheet loops what the node loops.
            //
            // ⚠⚠ This replaced a ▶ Play button that handed the MP4 to an
            // external player. The user's call, 2026-09-12: a node graph shows
            // you what a node made, and leaving the app to find out is not that.
            // ⭐ Tapping the still opens the FULLSCREEN player
            // ([com.abrah.nightmare.ui.ClipPlayer]) -- the loop is the preview,
            // the player is the watch. The MP4 that `video.output` writes to
            // Movies/Nightmare is where a clip goes to be KEPT, not to be seen.
            // ⚠ Same rule as the canvas ([clipNodes]): the END of the video
            // chain loops, everything upstream of it shows its poster. One rule
            // in both places, or the sheet and the node disagree about what a
            // node made.
            clip = state.videos[nodeId]
                ?.takeIf { nodeId in clipNodes(state.workflow.graph, state.videos) }
                ?.let(com.abrah.nightmare.ClipStore::get),
            hasClip = nodeId in clipNodes(state.workflow.graph, state.videos),
            // ⭐⭐⭐ **The LAST-node rule**, the other half: an output node owns
            // save / star / download only when no other output is downstream of
            // it (`isLastOfKind`). In `sample → output → … → output` the first
            // one displays and the second one acts, so there is one answer to
            // "which picture does starring keep".
            //
            // ⚠ A BRANCHING graph has two last outputs and both act — each owns
            // its own branch. The user's call: allow it, and let the branch the
            // sweep was armed on decide what Results collects.
            onKeepImage = previewId
                ?.takeIf { node.type != "core.image" && actsOnItsPicture }
                ?.let { id -> { onKeepImage(id) } },
            onStarImage = previewId
                ?.takeIf { node.type != "core.image" && actsOnItsPicture }
                ?.let { id -> { onStarImage(id) } },
            kept = previewId?.let(isKept) == true,
            favourite = previewId?.let(isFavourite) == true,
            keepDisabledReason = keepDisabledReason,
            onDisabledKeep = onDisabledKeep,
            onClearOutput = previewId?.takeIf { node.type != "core.image" }
                ?.let { { onClearOutput(nodeId) } },
            demand = demand,
            seed = seedFor(state.workflow.graph, nodeId) { status[it]?.detail },
            onClearImage = { onClearImage(nodeId) },
            onImportLora = onImportLora,
            loraEpoch = loraEpoch,
            // ⚠ Only on a node that HAS a picture and acts on it — the same
            // `actsOnItsPicture` rule the star and the disk follow.
            onUpscale = onUpscale
                ?.takeIf { previewId != null && actsOnItsPicture &&
                    nodeId !in clipNodes(state.workflow.graph, state.videos) }
                ?.let { up -> { up(nodeId) } },
            onInfo = detailsOf?.takeIf { previewId != null }?.let { d -> { d(nodeId) } },
        )
        }
    }
}

/**
 * ⭐ The node types whose inspector draws a FRAMING view, and the ones that
 * draw a PAINTING view.
 *
 * ⚠⚠ Sets rather than `==`, because the answer stopped being one type on
 * 2026-09-15: the fused sampler frames and paints as well (§5.7). FOUR places
 * ask this question — the two editors and the two "nothing wired yet" notes —
 * and they must all ask the same list.
 */
/**
 * ⭐⭐⭐ Knobs the fused sampler hides, because nothing they do could apply.
 *
 * `docs/ARCHITECTURE.md` §5.7: the rework moved ten nodes of complexity into one
 * node, and the bill for that lands in this sheet. Fifteen knobs in a column,
 * four of them meaningless until a photo is wired, is a worse interface than the
 * ten nodes were.
 *
 * ⚠⚠ Hidden, never disabled. A greyed control still costs a line and still
 * invites a tap; the honest version of "this cannot apply" is an absence, and
 * the knob returns the moment the wire that gives it meaning is drawn.
 *
 * ⚠⚠⚠ **The rectangle is hidden on EVERY node that draws a framing view**,
 * which is [FRAMES] and not just the SD samplers. `image.crop` was excused on
 * the reasoning that there "the rectangle IS the node's whole content" — which
 * was a reason to keep the numbers when the crop node had no editor, and stopped
 * being one the day it got the same [CropEditor] the samplers have. So `x`, `y`,
 * `Width`, `Height` and a **"Crop locked" checkbox** sat under the picture on
 * the crop node and on the video sampler, restating the control above them and
 * duplicating the padlock in the section title. Reported from the phone,
 * 2026-09-15: *"dont expose X, Y, Width, Height etc for all croppers. and why
 * tf is there a Crop locked checkbox?"*.
 *
 * ⚠⚠ It is the N−1-of-N rule again (`docs/ARCHITECTURE.md` §5.6): one
 * surface honoured the rule, the others did not, and the ones that did not are
 * where the finger went.
 */
private fun hiddenKnob(node: com.abrah.nightmare.Node, name: String): Boolean {
    // ⚠ The framing rectangle and the mask ops belong to their EDITOR, on every
    // node that draws one — asked first, because the checks below are about the
    // sampler's own conditional knobs.
    if (node.type in FRAMES &&
        name in setOf("x", "y", "w", "h", com.abrah.nightmare.CropNode.LOCKED)
    ) return true
    // ⭐ The reference region belongs to ITS editor too, for the same reason:
    // it is dragged on the picture, and four more sliders under the size
    // control is the duplicate the 2026-09-18 report named.
    // ⭐ …and the reference's own SIZE, drawn at the top of that editor
    // where the base's Shape + Resolution sit in the Crop one.
    if (name in setOf(
            com.abrah.nightmare.SdSampler.REF_X, com.abrah.nightmare.SdSampler.REF_Y,
            com.abrah.nightmare.SdSampler.REF_W, com.abrah.nightmare.SdSampler.REF_H,
            com.abrah.nightmare.SdSampler.REF_MAX,
        )
    ) return true
    if (node.type in PAINTS && name == com.abrah.nightmare.MaskNode.OPS) return true
    // ⭐ An inpaint node's grow and feather are drawn IN the Mask tab of its
    // popup, under the picture they change — never loose in the knob list
    // (the user's call, 2026-09-17).
    if (node.type in com.abrah.nightmare.INPAINT_TYPES && (name == "grow" || name == "feather")) return true
    // ⭐ No Pad on image-to-image (asked for 2026-09-17): it cannot pad
    // ([com.abrah.nightmare.PadRule.NEVER]), so the choice of fill cannot matter.
    if (name == com.abrah.nightmare.CropNode.PAD &&
        com.abrah.nightmare.padRuleFor(node.type) == com.abrah.nightmare.PadRule.NEVER
    ) return true

    if (node.type !in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) return false
    // ⭐ A DiT sampler's size belongs to the shape + size pair at the top of the
    // sheet, the same way every other family's does. Its `width`/`height` are
    // ordinary params rather than [Widget.contextKey] ones, so nothing above
    // hides them and they drew as two loose sliders under the size control that
    // already sets them — the duplicate the 2026-09-18 report named on the SD
    // nodes, re-made on this one.
    if ((name == "width" || name == "height") &&
        com.abrah.nightmare.ModelCatalog.byId(node.params["model"].orEmpty())?.isDit == true
    ) return true
    val hasImage = node.inputs["image"] != null
    val painted = !node.params[com.abrah.nightmare.MaskNode.OPS].isNullOrBlank()
    // ⚠ A `sample` type declares none of the mask widgets, so this branch never
    // fires for one — it is the inpaint types that need the conditional.
    val hasMask = painted
    return when (name) {
        // ⚠ Nothing to start FROM, so nothing to soften. `start_from`, `pad` and
        // `encode_seed` are not listed because the sampler no longer declares
        // them at all (2026-09-15) — hiding a widget that does not exist is a
        // rule nobody can check.
        "denoise" -> !hasImage
        // The mask half only exists once something is painted or wired.
        com.abrah.nightmare.MaskCropNode.ONLY_MASKED,
        com.abrah.nightmare.PasteNode.STITCH,
        "grow", "feather",
        -> !hasMask
        else -> false
    }
}

/**
 * ⭐⭐ One installed checkpoint, as the node's picker needs it.
 *
 * ⚠⚠ The FAMILY is carried, not looked up. It decides which chip the entry
 * sits under and what the field's label says, and a picker that re-derived it
 * from `ModelCatalog` would be a second place that knows what family an
 * IMPORTED model is — which is inferred from its files
 * (`docs/MODELS.md` §3b) and is exactly the kind of answer that must have one
 * home.
 */
data class CheckpointChoice(
    val id: String,
    val label: String,
    val family: com.abrah.nightmare.Family,
)

private val FRAMES = com.abrah.nightmare.FRAMES_PICTURE_TYPES

// ⚠⚠ Only the INPAINT types paint. That is the whole point of the fork: an
// image-to-image node was being handed a mask editor it has no use for.
private val PAINTS = com.abrah.nightmare.PAINTS_PICTURE_TYPES

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
     * ⭐ Rename a node. **Not a param write** -- the id is what every wire
     * points at, so this goes through `CanvasState.renameNode`, which moves
     * every wire, position, size and preview with it.
     */
    onRename: (from: String, to: String) -> Unit = { _, _ -> },
    /**
     * ⭐ A tap with the mask editor's Tap tool, in the PHOTO's coordinates —
     * `HarnessViewModel.tapMask`. The last argument reports back: null, or a
     * sentence to show.
     */
    onTapMask: (node: String, x: Float, y: Float, done: (String?) -> Unit) -> Unit =
        { _, _, _, done -> done(null) },
    /**
     * ⭐⭐ Open with this field focused and the keyboard up — set by a tap on
     * that prompt's box on the canvas.
     *
     * ⚠ Null for every other route in, so opening the sheet the ordinary way
     * does not throw a keyboard over it.
     */
    focusField: String? = null,
    /**
     * ⭐⭐ The sizes THIS NODE's model can actually render, for the size chips
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
     * ⭐ Choose THIS node's render size (`HarnessViewModel.setNodeResolution`).
     * **Not [onSetParam]**: a size is a third of the [ContextKey], so the pick
     * is checked against the node's model and every derived picture is
     * invalidated. ⚠⚠ Per node since the executor schedules keys instead of
     * refusing a graph with two (`docs/ARCHITECTURE.md` §4) — it used to
     * rewrite the whole graph against the TOP BAR's model, which refused every
     * size an SD 1.5 node offered while SDXL was selected.
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
    /** ⭐ Whether THIS node may arm a sweep — the last-node rule. */
    canSweep: Boolean = true,
    /** ⭐ What this node's prose is counted against — [com.abrah.nightmare.PromptTokens.budgetFor]. */
    promptBudget: com.abrah.nightmare.PromptTokens.Budget? = null,
    installedModels: List<CheckpointChoice> = emptyList(),
    onSetModel: (String, String) -> Unit = { _, _ -> },
    /** The picture this node is showing, if any. */
    preview: ImageBitmap? = null,
    onViewFullscreen: () -> Unit = {},
    /**
     * ⭐ What a before/after node RECEIVED (`image.upscale` only) — drawn
     * ABOVE [preview], which for that same node type is what it MADE. See
     * `CanvasState.beforePreviews`.
     */
    beforeImage: ImageBitmap? = null,
    onViewBeforeFullscreen: () -> Unit = {},
    /** The picture a `crop` node is framing — its upstream image. */
    cropSource: ImageBitmap? = null,
    /** ⭐ The picture wired into `reference`, if any. FLUX.2 samplers only. */
    refSource: ImageBitmap? = null,
    /** The picture an `image.mask` node is painted on — its upstream image. */
    maskSource: ImageBitmap? = null,
    /** ⭐ The same three actions the fullscreen viewer offers. Null hides them. */
    onSaveImage: (() -> Unit)? = null,
    /** ⭐ Hand this node's picture to another app. */
    onShareImage: () -> Unit = {},
    /** ⭐ Send this node's picture into a flow. */
    onSendImage: () -> Unit = {},
    /**
     * ⭐ The frames to loop instead of [preview], when this node made a clip.
     *
     * ⚠ Null for every other node, and null is not "no picture": the poster in
     * [preview] still draws. A clip whose frames have been evicted from
     * [com.abrah.nightmare.ClipStore] falls back to its still rather than to a
     * hole.
     */
    clip: List<androidx.compose.ui.graphics.ImageBitmap>? = null,
    /**
     * ⭐ Whether this node's Save and Share act on the CLIP.
     *
     * ⚠ The same [clipNodes] rule as [clip], and separate from it only
     * because [clip]'s frames can be evicted from `ClipStore` while the MP4 is
     * still there — a sheet falling back to the poster must still SAVE the
     * clip. ⚠⚠ Never a broader rule than [clip]'s: a node that shows a still
     * and shares a video is the bug this pair was reported for.
     */
    hasClip: Boolean = clip != null,
    /** ⭐ The STAR: keep it AND flag it. [PictureActions]. */
    onStarImage: (() -> Unit)? = null,
    favourite: Boolean = false,
    /** ⚠ Non-null when autosave already keeps every Run — the disk is dimmed. */
    keepDisabledReason: String? = null,
    onDisabledKeep: ((String) -> Unit)? = null,
    onKeepImage: (() -> Unit)? = null,
    /** ⚠ Filled star when true. The action toggles, so the icon must say which way. */
    kept: Boolean = false,
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
    /** ⭐⭐ Import a `.safetensors` from inside the LoRA picker; null hides Add. */
    onImportLora: (() -> Unit)? = null,
    /** ⚠⚠ See [NodeInspector]'s copy — it is what re-reads `_loras`. */
    loraEpoch: Int = 0,
    /** ⭐⭐ Enlarge this node's picture — see [PictureActions.onUpscale]. */
    onUpscale: (() -> Unit)? = null,
    /** ⭐⭐ What made this picture — the rows for [com.abrah.nightmare.ui.ResultInfoDialog]. */
    onInfo: (() -> List<Pair<String, String>>)? = null,
    /** ⚠ For a golden only: draw the inpaint popup's tab INLINE, since a Dialog is a window a screenshot cannot reach. */
    inlinePopupTab: Int? = null,
    /** ⭐ [CanvasState.cropRequest] — open the Crop popup when it names this node. */
    cropRequest: Pair<String, Int>? = null,
    cropRequestTab: Int = 0,
) {
    // ⚠ Local: an unanswered confirm is not something to persist, same as every
    // other one in the app.
    var confirmingDelete by remember { mutableStateOf(false) }
    // ⚠ Null when not renaming. Keyed on the node so opening another node's
    // sheet cannot leave a half-typed name from the last one behind.
    var renaming by remember(nodeId) { mutableStateOf<TextFieldValue?>(null) }

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
            // ⭐⭐ **Double-tap the name to rename the node.**
            //
            // ⚠ A node's id is its title on the canvas, and it was fixed at
            // creation: `freeId` hands out `upscale`, `upscale2`, so a graph of
            // six nodes read as a list of TYPES rather than of steps. Asked for
            // from the phone 2026-09-11.
            //
            // ⚠⚠ The whole name is SELECTED when the field opens, so the first
            // keystroke replaces it -- renaming `clip_encode` to `prompt` should
            // not begin with deleting eleven characters. That is what the
            // `TextRange(0, length)` does.
            //
            // ⚠ DOUBLE tap, not single: the title sits above a sheet people
            // scroll, and a rename opening on a stray touch would put a keyboard
            // over the knobs they were reaching for.
            val editingName = renaming
            if (editingName == null) {
                Text(
                    nodeId,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 20.sp,
                    modifier = Modifier.pointerInput(nodeId) {
                        detectTapGestures(
                            onDoubleTap = {
                                renaming = TextFieldValue(nodeId, TextRange(0, nodeId.length))
                            }
                        )
                    },
                )
                Text(
                    node.type,
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val focus = remember { FocusRequester() }
                // ⚠ Requested once the field EXISTS. Asking before it is
                // composed does nothing, and the user gets a selected field with
                // no keyboard.
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = editingName,
                    onValueChange = { renaming = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.rename_node)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    // ⚠⚠ Commit through [onRename], never as a param write: the
                    // id is what every wire points at, so renaming the node
                    // alone would disconnect the graph.
                    keyboardActions = KeyboardActions(onDone = {
                        onRename(nodeId, editingName.text)
                        renaming = null
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }

        // ⭐⭐ The crop node's real interface. Its four params are the rectangle;
        // the number fields below stay, because they are what a workflow stores
        // and because typing an exact value is sometimes the point.
        // ⭐⭐⭐ **The checkpoint, FIRST.** The user's call, 2026-09-15 — and it
        // is the right place: which model a sampler runs decides what every knob
        // below it means (a distilled checkpoint's cfg 1.5 against a stock 7.5),
        // what sizes are reachable, and whether the graph pays for a relaunch.
        // Buried under Steps and CFG it read as one more knob.
        // ⚠⚠ No `installedModels.isNotEmpty()` gate. A sampler ALWAYS has a
        // checkpoint field: with nothing installed it shows the model the node
        // names, marked "· not installed", which is the one state where a user
        // most needs to be told something rather than shown nothing. That gate
        // is also what made the field vanish entirely on a cold start
        // ([HarnessViewModel.init]) — an empty list and "no models" were
        // indistinguishable.
        // ⚠ Whether the LoRA picker is open. Local: an unanswered popup is
        // not something to persist. ⚠⚠ Declared HERE rather than beside the
        // other knob-popup state below, because [LoraRow] is now drawn under
        // the checkpoint picker and a Kotlin local has to exist before its use.
        var pickingLoras by remember(nodeId) { mutableStateOf(false) }
        // ⚠ Whether ⓘ is open. Local, keyed on the node, same as the picker.
        var showingInfo by remember(nodeId) { mutableStateOf(false) }
        if (node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) {
            CheckpointPicker(
                // ⭐⭐ Derived from the TYPES, not from a hardcoded family list:
                // a checkpoint is offered here when its family has an inpaint
                // sampler at all. One source of truth, so registering
                // `flux2.inpaint` would light this up with nothing to keep in
                // sync — and NOT registering it keeps FLUX.2 out, which is
                // where it stands (see `SdSampler.ALL`: the engine honours the
                // mask and then regenerates nothing inside it).
                // ⚠ The rule this replaced spelled the exclusion as
                // "never a DiT model", which was a fact about the engine
                // hardcoded in the UI.
                // ⚠⚠ [SdSampler.canInpaint], not a copy of it: the Use dialog
                // asks the same question and the two must give one answer.
                installed = if ((type as? com.abrah.nightmare.SdSampler)?.inpaint == true) {
                    installedModels.filter { com.abrah.nightmare.SdSampler.canInpaint(it.family) }
                } else installedModels,
                currentId = node.params["model"].orEmpty(),
                onPick = { onSetModel(nodeId, it) },
            )
            // ⭐⭐⭐ **Directly under the checkpoint, because that is what it
            // modifies.** The user's call, 2026-09-22. It was declared in
            // [com.abrah.nightmare.SdSampler.ditWidgets] and therefore drawn
            // wherever the knob loop reached it — under CFG and the size
            // sliders, pages away from the model whose weights it patches.
            //
            // ⚠ Same reasoning the checkpoint itself was moved to the top for
            // (§above): a control belongs beside the thing it changes the
            // meaning of, not in declaration order.
            // ⚠⚠ The [Widget] stays declared — it is what a saved flow
            // stores and what [com.abrah.nightmare.SdSampler.parseLoras] reads.
            // Only the DRAWING moved; the loop skips it.
            type?.widgets?.firstOrNull { it.name == com.abrah.nightmare.SdSampler.LORAS }?.let { w ->
                LoraRow(
                    widget = w,
                    value = node.params[w.name].orEmpty(),
                    why = w.locked,
                    onOpen = { pickingLoras = true },
                )
            }
        }

        val canSweepHere = canSweep
        // ⭐ An SD sampler edits its crop (and, on inpaint, its mask) in a popup,
        // and has no crop lock. ⚠ Image-to-image too since 2026-09-17, the user's
        // call: "i2i should have crop window similar to inpaint, without the lock".
        val popup = node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES
        val padRule = com.abrah.nightmare.padRuleFor(node.type)
        val cropLocked = !popup && com.abrah.nightmare.applyDefaults(type?.widgets.orEmpty(), node)[
            com.abrah.nightmare.CropNode.LOCKED
        ].equals("true", ignoreCase = true)
        // ⭐⭐ The framing and the painting as PANELS, drawn inline on every node
        // but an inpaint one — which shows them as two small previews that open
        // one popup ([InpaintEditors]). The user's call, 2026-09-17: two
        // picture-sized editors stacked in a scrolling sheet fought the sheet
        // for every drag, and a Dialog is its own window with no sheet under it.
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
        // ⭐⭐ ONE composable, drawn in the sheet AND at the top of the crop
        // popup (asked for 2026-09-17) — two copies of the size control would
        // stop agreeing the first time one of them learned something.
        // ⭐⭐⭐ **A DiT sampler's size, in the SAME slot and the same two
        // controls every other family gets.** Reported 2026-09-19: the Flux node
        // "doesn't maintain an ounce of consistency" with the SD ones, and this
        // was the loudest part of it — `sizeKnob` above asks for two
        // [Widget.contextKey] size widgets, which a DiT node deliberately does
        // not have (its size costs no relaunch), so it fell through every branch
        // here and its width and height turned up as two raw sliders loose in
        // the knob list at the bottom of the sheet.
        //
        // ⚠⚠ **A DiT size is not an ASPECT and the two must not be merged
        // however alike they read.** An aspect chip on SDXL crops a frozen
        // 1024² canvas and changes no dimension; these sliders CHOOSE the width
        // and height the engine renders. Same subject, two mechanisms — which
        // is why this calls [onSetResolution] (a size) and never [onSetAspect]
        // (a crop).
        //
        // ⭐⭐⭐ **Two free sliders, upstream's control**
        // (`local-dream/.../AdvancedSettingsDialog.kt`), the user's call
        // 2026-09-21. ⚠⚠ They replaced a Shape chooser over a table of
        // hand-picked pairs, and the table is what the report was about: at
        // `4:3` it could offer only 1024x768 or 2048x1536, so 1280x960 — legal
        // on this engine, legal upstream — was unreachable, and a person after
        // an ordinary 4:3 picture was pushed to 2048x1536. A table of pairs
        // cannot cover a 64-px grid; two sliders are the grid.
        //
        // ⚠ The table only existed because [ModelCatalog.DIT_STEP] was wrongly
        // believed to be 256, where `4:3` and `3:2` really do collide. At 64
        // they do not, and the reasoning the table rested on went with it.
        val ditSpec = com.abrah.nightmare.ModelCatalog.byId(node.params["model"].orEmpty())
            ?.takeIf { it.isDit && node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES }
        val ditPanel: @Composable () -> Unit = ditPanel@{
            val spec = ditSpec ?: return@ditPanel
            val widthW = type?.widgets?.firstOrNull { it.name == "width" } ?: return@ditPanel
            val heightW = type?.widgets?.firstOrNull { it.name == "height" } ?: return@ditPanel
            val cur = com.abrah.nightmare.Res(
                node.params["width"]?.toIntOrNull() ?: spec.native.width,
                node.params["height"]?.toIntOrNull() ?: spec.native.height,
            )
            // ⚠⚠ The value under the finger, held here until the finger lifts —
            // see [SliderRow]'s `onCommit`. ⚠ Keyed on `cur` as well as the node,
            // so it drops itself the moment the committed size lands (and when
            // anything else moves it, such as dropping a photo in).
            var draft by remember(nodeId, cur) { mutableStateOf<com.abrah.nightmare.Res?>(null) }
            val shown = draft ?: cur
            // ⚠ One commit for the pair, not one per slider: [onSetResolution]
            // takes a [com.abrah.nightmare.Res] and a half-applied size is not a
            // thing the graph should ever hold.
            val commit = { draft?.let(onSetResolution); draft = null }
            SliderRow(
                widthW, shown.width.toString(),
                onSet = { v -> v.toIntOrNull()?.let { draft = shown.copy(width = it) } },
                onCommit = commit,
            )
            SliderRow(
                heightW, shown.height.toString(),
                onSet = { v -> v.toIntOrNull()?.let { draft = shown.copy(height = it) } },
                onCommit = commit,
            )
        }
        val sizePanel: @Composable () -> Unit = {
            ditPanel()
            if (sizeKnob && resolutions.size > 1) {
                val current = com.abrah.nightmare.Res(
                    node.params["width"]?.toIntOrNull() ?: 0,
                    node.params["height"]?.toIntOrNull() ?: 0,
                ).toString()
                // ⚠ [Chooser] owns the chips-or-dropdown rule — and a list whose
                // length is the CHECKPOINT's is always a dropdown there.
                Chooser(
                    label = stringResource(R.string.resolution),
                    hint = stringResource(R.string.resolution_reloads),
                    options = resolutions.map { it.toString() },
                    current = current,
                    onPick = { l -> com.abrah.nightmare.Res.fromLabel(l)?.let(onSetResolution) },
                    variesInLength = true,
                )
            }
            // ⭐ The ASPECT, in the same place — it is the size control of a
            // fixed-canvas family, which has one resolution and so draws none.
            type?.widgets.orEmpty().firstOrNull { it.name == "aspect" }?.takeIf { sized == null }?.let { w ->
                Chooser(
                    label = w.name.knobLabel,
                    hint = w.hint,
                    options = w.options.orEmpty(),
                    current = node.params[w.name] ?: w.default.orEmpty(),
                    // ⚠⚠ Graph-wide on a SAMPLER only ([onSetAspect]); a crop
                    // node's `aspect` is its own frame shape, its own vocabulary.
                    onPick = {
                        if (node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) onSetAspect(it)
                        else onSetParam(nodeId, w.name, it)
                    },
                )
            }
        }
        // ⭐⭐⭐ The REFERENCE's region — a different job to the crop above,
        // and the comment is here because the two look alike on screen.
        //
        // The crop FRAMES the base into the output canvas: it is locked to the
        // render's shape, it can pad, and what falls outside is discarded.
        // This one only chooses WHICH PART of the reference to send, and the
        // region travels at its own aspect ratio — so no `aspect`, no padding,
        // and `PadRule.NEVER`. Fitting a reference to the canvas is exactly
        // what FLUX.2's reference-token scheme exists to avoid
        // (`docs/MODELS.md` §9).
        val refPanel: @Composable () -> Unit = refPanel@{
            val src = refSource ?: return@refPanel
            // ⭐⭐⭐ **The reference's OWN size, first** — the slot the Crop tab
            // uses for Shape + Resolution, filled by a control of this picture's
            // own ([SdSampler.REF_MAX]).
            //
            // ⚠⚠⚠ It must NOT be the output resolution, and for one build it
            // was: 1.5.518 drew [sizePanel] here on the reasoning that the canvas
            // is the only thing deciding a reference's cost (true) and therefore
            // the only honest size control for it (false). Both tabs then wrote
            // the same `width`/`height`, so moving one moved the other. Reported
            // the same day: *"dont share same resolution dropdown. have base
            // image res. and reference img res so i can test, for example, 1024
            // base img and 512 reference"*.
            //
            // ⚠⚠ What it controls is DETAIL, never memory — the hint says so,
            // and the warning below states the cost the canvas really does set.
            type?.widgets.orEmpty()
                .firstOrNull { it.name == com.abrah.nightmare.SdSampler.REF_MAX }
                ?.takeIf { popup }
                ?.let { w ->
                    Chooser(
                        label = "Reference size",
                        hint = w.hint,
                        options = w.options.orEmpty(),
                        current = node.params[w.name] ?: w.default.orEmpty(),
                        onPick = { onSetParam(nodeId, w.name, it) },
                        variesInLength = true,
                    )
                }
            if (!popup) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Reference",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "the part of this picture the model reads. It is not redrawn, " +
                    "and it keeps its own shape — it is never fitted to your output size.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ⭐⭐⭐ **The one combination that quietly does nothing.**
            //
            // ⚠⚠ With a reference wired AND denoise below 1, the base is
            // both the init latent and reference #1: it is told to stay where
            // it is while the noised init has little room to move, and the
            // render comes back as the input. Reported from the phone twice,
            // and it is why FLUX.2 nodes are now born at 1.0
            // ([SdSampler.defaultDenoise]).
            //
            // ⚠ Said, not enforced. Klein img2img at 0.65 with NO reference
            // works and looks good (measured), so the knob stays free and the
            // warning is scoped to the combination that misbehaves — it appears
            // only while both conditions hold and vanishes when either changes.
            // ⚠ The EFFECTIVE value, not the raw param: a node that has never
            // had denoise touched carries none, and reading null as "fine" would
            // hide the warning on exactly the nodes most likely to need it.
            val refDenoise = node.params["denoise"]?.toFloatOrNull()
                ?: type?.widgets.orEmpty()
                    .firstOrNull { it.name == "denoise" }?.default?.toFloatOrNull()
            if (refDenoise != null && refDenoise < 1f) {
                Text(
                    "⚠ Denoise is $refDenoise. With a reference wired, anything below " +
                        "1.0 uses your picture as the starting point AND as a reference " +
                        "telling the model to keep it — so the result usually comes back " +
                        "unchanged. Set Denoise to 1.0 to edit.",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            CropEditor(
                source = src,
                rect = refCropRectOf(node),
                // ⚠ ONE write per gesture, for the reason the crop above says:
                // four separate writes per pointer event is what crashed the
                // app mid-drag.
                onChange = { r -> onSetParams(nodeId, r.asRefParams().toMap()) },
                interactive = true,
                outW = src.width,
                // ⚠⚠ The REFERENCE's OWN aspect, so the region is a zoom into
                // it rather than a reshape of it, and `CropEditor` never has to
                // pad. Its `aspect` is not nullable and a free-form rect is not
                // a mode it has — this is the shape that needs no padding and
                // keeps the promise the panel's text makes.
                aspect = src.width.toFloat() / src.height.coerceAtLeast(1),
                padBlur = false,
                rule = com.abrah.nightmare.PadRule.NEVER,
            )
            // ⚠ The SAME footer the crop draws, saying the same two gestures
            // — two editors that look alike and read differently is the report
            // this panel is answering.
            Text(
                "drag to move · pinch to zoom",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // ⭐⭐ What the reference COSTS, measured, where the decision is
            // made. ⚠⚠ A 1024x1024 edit with a reference has never once
            // completed on a 12 GB phone — four attempts on 2026-09-20, every one
            // reaped by lmkd, and the same graph at 512x512 renders in 54 s. The
            // person choosing the resolution two lines above is the only one who
            // can avoid it, so the warning belongs here and not in a crash report.
            val refCost = framingOutSize(node, type)
            if (refCost.first.toLong() * refCost.second > 512L * 512L) {
                Text(
                    "⚠ a reference is encoded at your output size, not its own, so " +
                        "${refCost.first}x${refCost.second} costs about " +
                        "${(refCost.first.toLong() * refCost.second * 384 / (512L * 512L))} MB per " +
                        "picture. Above 512x512 this has not completed on a 12 GB phone; " +
                        "drop the resolution if the run is killed.",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val cropPanel: @Composable () -> Unit = {
            // ⭐ The size first, in the crop window too — the frame's shape is
            // decided by it, so it is changed where the frame is.
            // ⚠⚠ Only when the popup is actually the one drawing this: a
            // txt2img sampler has `popup = true` (every IMAGE_SAMPLER_TYPES node
            // does) but no `cropSource`, so it never opens the popup and falls
            // into the inline `cropPanel()` call below instead — which used to
            // draw this SAME size control a second time, stacked under the one
            // `sizePanel()` already drew unconditionally above. Reported
            // 2026-09-18: "generate node shows 2 aspect ratio / resolution".
            if (popup && cropSource != null) sizePanel()
            cropSource?.let { src ->
                val (outW, outH) = framingOutSize(node, type)
                // ⭐⭐ A TITLE over each editor. Reported 2026-09-15: with framing and
                // painting both on one node, two picture-sized controls sat one
                // above the other with nothing saying which was which.
                //
                // ⚠ The lock lives IN the title row rather than loose beside the
                // picture — the same reason [SeedRow] encloses its own lock: an icon
                // with no visible subject reads as "lock the app".
                // ⚠ Not in the inpaint popup: its tab already says Crop, and the
                // popup has no lock at all (the user's call, 2026-09-17).
                if (!popup) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Crop", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        onSetParam(nodeId, com.abrah.nightmare.CropNode.LOCKED, (!cropLocked).toString())
                    }) {
                        // ⚠⚠⚠ **A LOCK, not a tick.** One glyph, one meaning — and
                        // the tick was already taken: on the picture actions it says
                        // "this IS kept", a STATE. Using it here for "click to
                        // lock", an ACTION, made the same mark mean two different
                        // things two inches apart. Reported 2026-09-15, and it was
                        // the right call — the app already has a lock that means
                        // exactly this ([SeedRow] pins a seed with it), so the crop
                        // uses the same pair: open padlock to lock, closed to unlock.
                        Icon(
                            if (cropLocked) Icons.Filled.Lock else com.abrah.nightmare.ui.LockOpenIcon,
                            contentDescription =
                                if (cropLocked) "unlock the crop to change it" else "lock this crop",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                CropEditor(
                    source = src,
                    rect = cropRectOf(node),
                    // ⚠⚠ ONE write per gesture, not four. Setting the params
                    // individually ran the canvas-update-and-autosave path four
                    // times per pointer event, and the concurrent saves that came
                    // out of that are what crashed the app mid-drag.
                    // ⚠ A locked crop ignores the gesture entirely. Disabling the
                    // WRITE rather than the view keeps the picture visible — the
                    // framing is still the thing you look at while painting.
                    onChange = { r ->
                        if (!cropLocked) onSetParams(nodeId, r.asParams().toMap())
                    },
                    // ⚠ …and takes no gesture, so a drag over the frame scrolls the sheet.
                    interactive = !cropLocked,
                    outW = outW,
                    aspect = cropAspect(node, src.width, src.height, type),
                    padBlur = padRule != com.abrah.nightmare.PadRule.NEVER &&
                        node.params[CropNode.PAD] == CropNode.PAD_BLUR,
                    rule = padRule,
                )
                // ⭐⭐ The photo is too small for what is being asked of it, said
                // plainly. This is the ONE state where bars appear, and a user who
                // has not been told will read them as a bug in the cropper rather
                // than as the honest answer to "this picture has fewer pixels than
                // the pipeline needs".
                // ⚠ Only under that rule: an image-to-image node never pads, and on
                // an inpaint node bars are a choice (outpaint), not a shortfall.
                if (padRule == com.abrah.nightmare.PadRule.WHEN_TOO_SMALL &&
                    CropGeometry.needsPadding(src.width, src.height, outW, outH)
                ) {
                    Text(
                        "this picture is ${src.width}x${src.height}, smaller than the " +
                            "${outW}x$outH being asked for — the bars are padding, not a crop. " +
                            "Enlarging it instead would only make it soft.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (cropLocked) "locked · unlock to move or zoom" + (if (outW > 0) " · out ${outW}x$outH" else "")
                    else if (outW > 0) "drag to move · pinch to zoom · out ${outW}x$outH"
                    else "drag to move · pinch to zoom · the frame is the output, at its own size",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (popup && padRule != com.abrah.nightmare.PadRule.NEVER) {
                    type?.widgets?.firstOrNull { it.name == CropNode.PAD }?.let { w ->
                        ChoiceRow(
                            label = w.name.knobLabel,
                            hint = w.hint,
                            options = w.options.orEmpty(),
                            current = node.params[w.name] ?: w.default.orEmpty(),
                            onPick = { onSetParam(nodeId, w.name, it) },
                        )
                    }
                }
            }
        }
        val maskPanel: @Composable () -> Unit = {
            // ⭐⭐ The mask node's real interface.
            maskSource?.let { raw ->
                if (!popup) Text("Mask", style = MaterialTheme.typography.titleSmall)
                // ⭐⭐⭐ **Painted on the FRAMED picture, and it follows the crop.**
                //
                // Asked for 2026-09-15: *"mask should track the crop and update
                // instantly when crop changes."* The node rasterises a painting at
                // the SOURCE's size and puts it through the same framing the picture
                // gets ([SdSampler.run]), so the strokes follow the subject — and
                // the editor shows the view the model will see.
                //
                // ⚠⚠⚠ Which means the picture below and the coordinates the strokes
                // are stored in are DIFFERENT SPACES, and [MaskFraming] is the only
                // thing that reconciles them. Showing the framed picture without it
                // made the sampler apply the crop to the mask a SECOND time: the
                // repaint landed the frame's own offset further down the photo, and
                // it still rendered (2026-09-16).
                //
                // ⚠ ONE decision about which space this is — `frame` and the picture
                // are computed from it together, never tested for separately.
                //
                // ⚠ `remember` on the rect as well as the picture: re-framing must
                // re-cut this immediately, and re-cutting on every recomposition
                // would run a bitmap draw on every frame of an unrelated gesture.
                val rect = cropRectOf(node)
                val framed = node.type in com.abrah.nightmare.INPAINT_TYPES
                val frame = if (framed) rect else CropRect.WHOLE
                val src = if (!framed) raw else {
                    val outSize = framingOutSize(node, type)
                    remember(raw, rect, outSize, node.params[CropNode.PAD]) {
                        val (outW, outH) = outSize
                        CropNode.render(
                            raw.asAndroidBitmap(),
                            rect.x, rect.y, rect.w, rect.h,
                            outW, outH,
                            node.params[CropNode.PAD],
                        ).first.asImageBitmap()
                    }
                }
                MaskToolbar(
                    nodeId = nodeId,
                    node = node,
                    source = src,
                    photo = raw,
                    frame = frame,
                    // ⭐ OUTPAINT padding, locked and drawn blue — the same test
                    // the sampler masks it by ([com.abrah.nightmare.CropGeometry.photoInFrame]).
                    padding = if (padRule == com.abrah.nightmare.PadRule.OUTPAINT) {
                        com.abrah.nightmare.CropGeometry.photoInFrame(rect.x, rect.y, rect.w, rect.h)
                    } else null,
                    onEditMask = onEditMask,
                    onTapMask = onTapMask,
                    type = type,
                    onSetParam = { k, v -> onSetParam(nodeId, k, v) },
                )
            }
        }
        // ⭐⭐ ABOVE the crop and mask (asked for 2026-09-17): the size decides
        // the shape of both, so it is chosen before them, not scrolled past.
        sizePanel()

        // ⭐⭐⭐ **ONE editor mechanism for every picture on the node.**
        //
        // ⚠⚠ Reported from the phone 2026-09-20: *"why doesnt inpaint node
        // have consistency for base and reference img, both should have
        // consistent cropper windows with their respective names and knobs for
        // size"*. It was the N−1-of-N rule again (`docs/ARCHITECTURE.md`
        // §5.6) — the base's crop and mask went behind a titled thumbnail into
        // a tabbed popup, and the reference, which is the same gesture on the
        // same [CropEditor], was drawn loose in the sheet with no thumbnail, no
        // tab and no size control. On a FLUX.2 image-to-image node the two sat
        // one above the other with an empty half-row between them, because
        // `paints = false` left the Mask tile's slot spare — which is exactly
        // where the Reference tile belongs.
        if (popup && (cropSource != null || refSource != null)) {
            InpaintEditors(
                node = node,
                type = type,
                photo = cropSource,
                refPhoto = refSource,
                cropPanel = cropPanel,
                maskPanel = maskPanel,
                refPanel = refPanel,
                inlineTab = inlinePopupTab,
                paints = node.type in com.abrah.nightmare.INPAINT_TYPES,
                openCrop = cropRequest?.takeIf { it.first == nodeId }?.second,
                openTab = cropRequestTab,
            )
        } else {
            cropPanel()
            maskPanel()
            // ⚠ The inline fallback — a node whose sheet draws no popup at all.
            // Draws itself only when something is wired into `reference`.
            refPanel()
        }
        // ⚠ The sampler frames AND paints, so an empty one would print two
        // notes that say "wire a picture in" — the framing one below covers both.
        if (maskSource == null && node.type in PAINTS && node.type !in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) {
            Text(
                "no picture to paint on yet — wire an image into this node, and " +
                    "the picture appears here as soon as it can be worked out.",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cropSource == null && node.type in FRAMES) {
            // ⚠ One sentence for the sampler, naming BOTH things the picture
            // would unlock, because one node does both jobs now.
            // ⚠ Nothing for a sampler. An empty framing view on a node with no
            // picture wired is self-evident — the input port above it is visibly
            // unconnected — and the sentence explaining it was instructions for
            // a thing the user had not tried to do yet.
            if (node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) Unit else
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
        // ⚠ Null when not renaming. Keyed on the node so opening another
        // node's sheet cannot leave a half-typed name from the last one.

        val widgets = type?.widgets.orEmpty()
        // ⚠ Only when there is a frame to explain: on a `crop` with no input
        // picture the chooser is a knob for bars that cannot appear yet.
        val padWidget = if (cropSource != null) {
            widgets.firstOrNull { it.name == CropNode.PAD }
        } else null
        // ⚠ On an inpaint node the chooser is drawn IN the Crop tab of its popup
        // (the user's call, 2026-09-17), so not here as well — and never on an
        // image-to-image node, which cannot pad at all ([com.abrah.nightmare.PadRule.NEVER]).
        val padHere = padWidget?.takeIf { !popup && padRule != com.abrah.nightmare.PadRule.NEVER }
        // ⭐⭐ The pad chooser sits DIRECTLY under the framing view, because it
        // answers a question the framing view has just raised: the bars appear
        // as soon as the frame runs off the picture, and "black or mirrored" is
        // then the next thing you want. It used to be near the bottom, under the
        // numbers, which is nowhere near the thing it changes.
        padHere?.let { w ->
            ChoiceRow(
                label = w.name.knobLabel,
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
        // ⭐⭐ Upscale's BEFORE half — what it received, directly above what it
        // made below. The only before/after node today: a renderer's own
        // result belongs to `core.output` alone ([NodeType.showsResult]), so
        // nothing else showed the intermediate picture in a chain like
        // generate → upscale. Reported 2026-09-18. ⚠ Tap opens it fullscreen
        // too, the same as the picture below it.
        beforeImage?.let { bmp ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Received",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Image(
                    bitmap = bmp,
                    contentDescription = "the picture this node received",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = minOf(180.dp, LocalConfiguration.current.screenHeightDp.dp * 0.3f))
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onViewBeforeFullscreen() },
                )
            }
        }
        if (preview != null && cropSource == null && maskSource == null && onSaveImage != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // ⭐ The SAME row the fullscreen viewer draws, in the same order,
                // with the same confirm on the bin (`docs/UI.md` §8.3). ⚠ This
                // bin used to clear on the tap while the viewer's asked.
                PictureActions(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    deleteTint = MaterialTheme.colorScheme.error,
                    isClip = hasClip,
                    onDelete = onClearOutput,
                    onKeep = onKeepImage,
                    onDownload = { onSaveImage() },
                    onShare = { onShareImage() },
                    onSendTo = { onSendImage() },
                    onStar = onStarImage,
                    kept = kept,
                    favourite = favourite,
                    keepDisabledReason = keepDisabledReason,
                    onDisabledKeep = onDisabledKeep,
                    starKeptTint = com.abrah.nightmare.ui.StarKept,
                    starIdleTint = com.abrah.nightmare.ui.StarIdle,
                    onUpscale = onUpscale,
                    onInfo = onInfo?.let { { showingInfo = true } },
                )
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
        preview?.takeIf { cropSource == null && maskSource == null }?.let { still ->
            // ⭐ Only labelled when there is a "Received" picture above it to
            // read against — every other node with a preview still shows one
            // plain, unlabelled picture, exactly as before.
            if (beforeImage != null) {
                Text(
                    "Made",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⭐⭐ A clip loops here exactly as it does on the node.
            //
            // ⚠ Its OWN ticker rather than one shared with the canvas: the sheet
            // is a separate window over a canvas that may be scrolled away, and
            // a clock hoisted to the screen would keep the hidden one redrawing.
            // ⚠ Keyed on the clip, so opening a different node restarts it at
            // frame 0 instead of wherever the last one happened to be.
            var frame by remember(clip) { mutableIntStateOf(0) }
            LaunchedEffect(clip) {
                if (clip == null) return@LaunchedEffect
                var last = 0L
                while (true) {
                    withFrameMillis { now ->
                        if (now - last >= 1000L / com.abrah.nightmare.ClipStore.FPS) {
                            last = now
                            frame++
                        }
                    }
                }
            }
            val shown = clip?.let { f -> f[frame % f.size] } ?: still
            Image(
                bitmap = shown,
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
        // ⚠⚠ On the OUTPUT node only — the one place a result is shown
        // (§5.7). An inpaint node carried it too (the user, 2026-09-17).
        if (preview != null && seed != null && node.type == "core.output") SeedRow(seed)

        // ⭐ The popup for whichever knob's BAT icon was tapped.
        // ⭐ The LoRA picker, opened from the knob's own row below.
        //
        // ⚠⚠ The installed list is read HERE, not in [com.abrah.nightmare.SdSampler]'s
        // widget declaration — a `NodeType.widgets` getter is a plain property
        // with no Context, which is why this param shipped as free text. A
        // composable has one.
        // ⭐ The SAME dialog Results opens — one function, so the two cannot
        // drift (`docs/ARCHITECTURE.md` §5.6).
        if (showingInfo && onInfo != null) {
            com.abrah.nightmare.ui.ResultInfoDialog(onInfo()) { showingInfo = false }
        }
        if (pickingLoras) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            // ⚠ Re-read every time it opens: an import on the Settings tab
            // happens while a graph is on the canvas, and a list cached at
            // composition would not show the file that was just added.
            // ⚠⚠ Keyed on [loraEpoch] as well, so an Add made from INSIDE this
            // dialog lands in its own list. Without it the read is remembered
            // across the import and the file a person just picked is invisible
            // until they close and reopen — which is exactly the shape of bug
            // the Add button was added to remove.
            val installed = remember(pickingLoras, loraEpoch) {
                com.abrah.nightmare.BackendProcess.lorasDir(ctx).listFiles { f ->
                    f.isFile && f.extension.equals("safetensors", ignoreCase = true)
                }.orEmpty().map { it.name to it.length() }.sortedBy { it.first.lowercase() }
            }
            LoraPicker(
                installed = installed,
                spec = node.params[com.abrah.nightmare.SdSampler.LORAS].orEmpty(),
                onSet = { onSetParam(nodeId, com.abrah.nightmare.SdSampler.LORAS, it) },
                onDismiss = { pickingLoras = false },
                onImport = onImportLora,
            )
        }

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
        val picked = if (node.type == "core.image") "uri" else null
        if (picked != null) {
            ImagePicker(
                current = node.params[picked].orEmpty(),
                onPicked = { uri -> onSetParam(nodeId, picked, uri) },
                onClear = onClearImage,
            )
        }

        // ⭐⭐⭐ **The LAST-node rule** (`isLastOfKind`): only the last sampler
        // in a chain may arm a sweep. Sweeping an earlier one re-runs everything
        // downstream, so ten seeds on a two-sampler chain is twenty renders —
        // a control that does not say so hides a twenty-minute job behind a tap.
        //
        // ⚠ ONE function, asked by all four places that draw a sweep control.
        // Four copies of "is this batchable" would disagree the first time one
        // of them learned about chains, which is exactly what just happened.
        fun sweepable(param: String): Boolean =
            com.abrah.nightmare.BatchParams.isBatchable(node.type, param) && canSweepHere

        for (w in widgets) {
            if (w.name == picked) continue
            // ⭐⭐⭐ **A knob that cannot matter is not drawn** — §5.7, and the
            // price of fusing ten nodes into one.
            if (hiddenKnob(node, w.name)) continue
            // ⚠ Drawn above as one size control, or not at all. A node whose
            // model serves a single size has nothing to choose, and two locked
            // number fields saying 512 would be noise on every node in the graph.
            if (sizeKnob && (w.name == "width" || w.name == "height")) continue
            // ⚠ Already drawn, under the framing view it belongs to.
            if (w === padWidget) continue
            // ⚠ Drawn above, beside the resolution — or not at all when the graph fixed the size.
            if (w.name == "aspect") continue
            // ⭐⭐⭐ The CHECKPOINT is a picker, not a locked string. Every
            // installed model; picking one from another family swaps this node's
            // TYPE with it, which is safe because the four types declare the
            // same ports (docs/ARCHITECTURE.md §5.7).
            //
            // ⚠ Only on a sampler. `model` is a param on the legacy nodes too,
            // where it stays locked — they are deleted, not re-plumbed.
            // ⚠ Drawn at the TOP of the sheet, not here — see below.
            if (w.name == "model" && node.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES) continue
            // ⚠⚠ The mask's ops string is never worth typing. `crop` keeps its
            // four number fields beside the framing view because an exact
            // rectangle is sometimes the point; a list of stroke coordinates
            // never is, and showing it puts a wall of digits between the two
            // sliders that DO matter.
            if (w.name == com.abrah.nightmare.MaskNode.OPS && maskSource != null) continue
            // ⭐ A short fixed set of values is a row of chips, not a text box
            // that accepts "Black", "mirrored" and a typo that fails at Run.
            val options = w.options
            // ⚠ `aspect` never reaches here — it is graph-wide ([onSetAspect])
            // and drawn above; every chip row in this loop is per-node.
            val pick: (String) -> Unit = { v -> onSetParam(nodeId, w.name, v) }
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
                    sweepable(w.name)
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
                            Text(w.name.knobLabel, style = MaterialTheme.typography.bodyMedium)
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
                    // ⚠⚠ Only the samplers THIS node's family distinguishes —
                    // [ModelCatalog.schedulersFor]. Anima offers two, and no
                    // Karras: the widget's options say so, and the picker reads
                    // them rather than the catalogue-wide list.
                    val allowed = w.options ?: ModelCatalog.SCHEDULERS
                    val samplers = ModelCatalog.SAMPLERS.filter { (id, _) ->
                        allowed.any { ModelCatalog.splitScheduler(it).first == id }
                    }
                    val karrasOffered = allowed.any { it.endsWith("_karras") }
                    ChoiceDropdown(
                        label = w.name.knobLabel,
                        hint = w.hint,
                        options = samplers.map { it.second },
                        current = samplers.firstOrNull { it.first == base }?.second
                            ?: ModelCatalog.schedulerLabel(cur),
                        onPick = { label ->
                            val id = samplers.first { it.second == label }.first
                            pick(ModelCatalog.joinScheduler(id, karras && karrasOffered))
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
                        if (karrasOffered) Checkbox(
                            checked = karras && canKarras,
                            enabled = canKarras,
                            onCheckedChange = { on ->
                                pick(ModelCatalog.joinScheduler(base, on))
                            },
                        )
                        if (karrasOffered) Text(
                            if (canKarras) stringResource(R.string.karras_sigmas)
                            else stringResource(R.string.karras_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (canKarras) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        // ⚠ The REAL armed state, not a hardcoded null. It is
                        // always null on this path today -- an armed knob is
                        // drawn by the branch above and never reaches here --
                        // but writing the literal made that ordering
                        // load-bearing and invisible, and the icon would have
                        // lied the moment the branches moved.
                        if (sweepable(w.name)) {
                            BatchToggle(
                                armed = com.abrah.nightmare.BatchParams.armed(node, w.name),
                                onClick = { batching = w },
                            )
                        }
                    }
                    continue
                }
                // ⚠⚠ Chips do not scale. `pad` has two values and reads as a
                // pair of buttons; `scheduler` has NINE, which becomes a
                // horizontally-scrolling strip where the current value can be
                // off-screen — a control that hides its own state. [Chooser] is
                // where that is decided, for every choice in this sheet.
                Chooser(
                    label = w.name.knobLabel,
                    hint = w.hint,
                    options = options,
                    current = node.params[w.name] ?: w.default.orEmpty(),
                    onPick = { pick(it) },
                )
                // ⚠ `scheduler` is batchable and is NOT a range — its popup is
                // a multi-select over these same options.
                if (sweepable(w.name)) {
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
                    "two consumers disagree — ${conflict.reason()}"
                else -> w.locked
            }
            // ⚠⚠ Drawn ABOVE, directly under the checkpoint picker, because a
            // LoRA is a property OF that checkpoint — see [LoraRow]. It stays a
            // declared [Widget] so a workflow still carries the param.
            if (w.name == com.abrah.nightmare.SdSampler.LORAS) continue
            // ⭐⭐⭐ **A `bool` is a CHECKBOX.** It had no branch at all, so
            // every one in the app fell through to the text field at the bottom
            // of this loop — a number-less keyboard and the word `true` to type
            // by hand, where a typo (`True`, `ture`) reads as false and nothing
            // says so until the Run that quietly did not save. Reported from
            // the phone, 2026-09-12, about `video.output`'s `save` and
            // `nd.video_sample`'s `upscale`; `image.output`'s `save` had the
            // same field and the same trap.
            //
            // ⚠ The DEFAULT decides an unset param, not `false`: `video.output`
            // ships `save = true`, and a box drawn unchecked over a node that
            // will in fact save is a control lying about its own state.
            // ⚠ Written lowercase, the one spelling every `run` compares against.
            if (w.type == "bool") {
                val on = (node.params[w.name] ?: w.default).equals("true", ignoreCase = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = on,
                        enabled = why == null,
                        onCheckedChange = { v ->
                            if (why == null) onSetParam(nodeId, w.name, v.toString())
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (why != null) "${w.name.knobLabel}  (locked)" else w.name.knobLabel,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        (why ?: w.hint)?.let {
                            Text(
                                it,
                                style = LogTextStyle,
                                // ⚠ Not error red: a LOCKED knob is information, not a failure (`docs/UI.md` §8.5).
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                continue
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
            val batchable = sweepable(w.name)
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
                        Text(w.name.knobLabel, style = MaterialTheme.typography.bodyMedium)
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
            // ⚠ The two fields that hold sentences rather than a value.
            val isProse = w.name == "prompt" || w.name == "negative"
            // ⭐⭐ The field a canvas tap asked for. Focus is requested ONCE per
            // open (keyed on the node and name), so scrolling the sheet or
            // editing another knob does not drag the keyboard back up.
            val wanted = w.name == focusField
            val fieldFocus = remember(w.name) { FocusRequester() }
            val current = node.params[w.name] ?: w.default.orEmpty()
            // ⭐⭐ **The cursor starts at the END of the text, not the start.**
            //
            // ⚠⚠ A plain `String` value carries no selection, so focusing one
            // puts the caret at offset 0 — tap a prompt on the canvas and you
            // are typing in FRONT of what is already there, which is never what
            // anyone means. Reported from the phone, 2026-09-11.
            //
            // ⚠ A [TextFieldValue] is the only way to say where the caret goes,
            // and it is used ONLY for the field being focused: every other field
            // here stays a plain String, because a locally-held TextFieldValue
            // stops reflecting a param written from outside (the image picker
            // sets `uri`, a model switch rewrites `steps`).
            val seeded = remember(nodeId, w.name) {
                mutableStateOf(TextFieldValue(current, TextRange(current.length)))
            }
            if (wanted) {
                LaunchedEffect(nodeId, w.name) { fieldFocus.requestFocus() }
            }
            if (wanted) {
                OutlinedTextField(
                    value = seeded.value,
                    onValueChange = { v ->
                        seeded.value = v
                        if (why == null) {
                            onSetParam(
                                nodeId, w.name,
                                if (v.text.isBlank() && w.numeric) w.default.orEmpty() else v.text,
                            )
                        }
                    },
                    readOnly = why != null,
                    enabled = why == null,
                    label = { ProseLabel(w.name, why, if (isProse) com.abrah.nightmare.PromptTokens.count(if (wanted) seeded.value.text else current, promptBudget) else null) },
                    supportingText = {
                        Text(
                            why ?: w.hint.orEmpty(),
                            style = LogTextStyle,
                            // ⚠ Not error red: a LOCKED knob is information, not a failure (`docs/UI.md` §8.5).
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = !isProse,
                    minLines = if (isProse) 3 else 1,
                    maxLines = if (isProse) 8 else 1,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (w.numeric) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
                )
            } else {
            OutlinedTextField(
                // ⚠ The manifest default when the graph carries nothing, so
                // the field shows what the node will actually RUN with
                // rather than an empty box that means "0.5".
                value = current,
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
                label = { ProseLabel(w.name, why, if (isProse) com.abrah.nightmare.PromptTokens.count(if (wanted) seeded.value.text else current, promptBudget) else null) },
                supportingText = {
                    val range = if (w.min != null && w.max != null) {
                        "  ${w.min}..${w.max}"
                    } else ""
                    Text(
                        why ?: w.hint ?: (w.type + range),
                        style = LogTextStyle,
                        // ⚠ Not error red: a LOCKED knob is information, not a failure (`docs/UI.md` §8.5).
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                // ⚠ Only Text Encode has these now, and a prompt is the one
                // field people paste paragraphs into.
                singleLine = !isProse,
                // ⚠⚠ **minLines, not just `singleLine = false`.** Without a floor
                // the box OPENS one line tall and grows as you type, which reads
                // as a single-line field that happens to wrap — you cannot see
                // the prompt you already have without scrolling inside it. Three
                // lines up front makes it a text area; eight caps it so a long
                // negative cannot push the rest of the sheet off screen.
                // Asked for from the phone, 2026-09-11.
                minLines = if (isProse) 3 else 1,
                maxLines = if (isProse) 8 else 1,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (w.numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            }
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
            Text("Delete node", color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmingDelete) {
        // ⚠ Closed BEFORE the delete (ConfirmDelete dismisses first): `onDelete`
        // closes the sheet this dialog belongs to.
        ConfirmDeleteNodes(
            ids = listOf(nodeId),
            onConfirm = { onDelete(nodeId) },
            onDismiss = { confirmingDelete = false },
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
 * ⭐⭐⭐ **Which checkpoint this sampler runs** — one dropdown, its list
 * grouped under family headings.
 *
 * ⚠⚠⚠ **There is no filter and no browsing state, and that is the point.**
 * A family chip strip beside the field was tried twice and failed twice, in
 * opposite directions:
 *
 * | shipped | what broke |
 * |---|---|
 * | chip filters the list, field keeps showing the node's model | tap SDXL, field still reads "AbsoluteReality" over a list of SDXL models |
 * | chip picks that family's first checkpoint | tapping a chip fires the switch dialog, *"as if a dropdown item was chosen"* |
 * | chip browses, field follows, a line says "not applied" | still two controls, still two states, still wrong |
 *
 * ⇒ The user's call, 2026-09-16: *"lets not do chips just do dropdown that
 * shows by family"*. A grouped menu has **one** state — the node's `model`
 * param — so there is nothing to keep in step: the family is a HEADING over
 * rows, not a control with an opinion of its own. Every failure above came from
 * a second control that could disagree with the first.
 *
 * ⚠ The LABEL still names the family the node is set to, per the user's call on
 * the closed field. ⚠ No hint underneath: the cost of a second checkpoint is
 * said once, in the run bar, in the units that matter ("2 model loads this run
 * — about 7 s") and at the moment it applies.
 */
/**
 * ⭐⭐⭐ **The LoRA knob is a summary line that opens a picker**, not a
 * text field. A LoRA is chosen from what is on the phone; typing its filename
 * from memory is the one way to get it wrong, and a strength typed into a box
 * has no bounds at all.
 *
 * ⚠ It reads its own summary from [com.abrah.nightmare.LoraSpec] — the same
 * tokeniser the picker and Run use, so the line under the label cannot disagree
 * with what will be applied.
 *
 * ⚠⚠ Lifted out of the knob loop 2026-09-22 so it can be drawn directly
 * under the checkpoint picker. Its own drawing did not change.
 */
@Composable
private fun LoraRow(
    widget: com.abrah.nightmare.Widget,
    value: String,
    why: String?,
    onOpen: () -> Unit,
) {
    val entries = com.abrah.nightmare.LoraSpec.parse(value)
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = why == null) { onOpen() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (why != null) "${widget.name.knobLabel}  (locked)" else widget.name.knobLabel,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                // ⚠⚠⚠ The REASON beats everything when the knob is locked — a
                // row that still reads "adapters applied on top of this
                // checkpoint" over a dead button is the lie the lock exists to
                // stop.
                // ⚠⚠ Otherwise the HINT when nothing is picked, not the word
                // "None". The golden for this row showed it bare, and a bare row
                // is where the old text field's one useful sentence went.
                why ?: if (entries.isEmpty()) widget.hint ?: "None"
                else com.abrah.nightmare.LoraSpec.summary(entries),
                style = LogTextStyle,
                // ⚠ Not error red: a LOCKED knob is information, not a failure (`docs/UI.md` §8.5).
                color = if (why != null || entries.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
        }
        if (why == null) {
            TextButton(onClick = onOpen) {
                Text(if (entries.isEmpty()) "Choose" else "Change")
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CheckpointPicker(
    installed: List<CheckpointChoice>,
    currentId: String,
    onPick: (String) -> Unit,
) {
    val here = installed.firstOrNull { it.id == currentId }
    // ⚠⚠ Families in the CATALOGUE's order, not in whatever order the installs
    // happened to land — SD 1.5 before SDXL, every time this sheet opens.
    val families = com.abrah.nightmare.Family.entries
        .mapNotNull { f -> installed.filter { it.family == f }.takeIf { it.isNotEmpty() }?.let { f to it } }
    var open by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = here?.label ?: currentId,
            onValueChange = {},
            readOnly = true,
            label = { Text("Checkpoint" + (here?.family?.let { " · ${it.label}" } ?: "")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((family, models) in families) {
                // ⚠⚠ A HEADING, not a menu item — `enabled = false` so it cannot
                // be tapped and reads as a section rather than as a checkpoint
                // that happens to be unavailable.
                // ⚠ Only when there is more than one family: a single "SD 1.5"
                // heading over every row labels nothing.
                if (families.size > 1) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                family.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                }
                for (m in models) {
                    DropdownMenuItem(
                        text = { Text(m.label) },
                        onClick = { onPick(m.id); open = false },
                    )
                }
            }
            // ⚠⚠ The node's OWN checkpoint is listed even when it is not
            // installed. [installed] is installed-only, and a picker that cannot
            // show the value it is set to reads as blank on exactly the graph
            // that needs explaining — one saved against a model since deleted.
            // ⚠ Not clickable: picking it again would refuse with the reason,
            // which is a refusal the user did not ask for.
            if (here == null && currentId.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("$currentId · not installed") },
                    onClick = {},
                    enabled = false,
                )
            }
        }
    }
}

/**
 * ⭐⭐⭐ **One value out of a list — and the ONE place that decides how.**
 *
 * ⚠⚠⚠ The [CHIP_LIMIT] rule existed and was written out by hand at each
 * call site, which is the shape CLAUDE.md warns about: *two hand-rolled lookups
 * will stop agreeing*. They did. Two call sites branched on it and the THIRD —
 * the checkpoint picker — called [ChoiceRow] directly with **fifteen**
 * checkpoints in it, so the control that decides what the sampler runs was a
 * horizontally-scrolling strip of chips inside a vertically-scrolling sheet,
 * which could hide its own current value and fought the sheet for the gesture.
 * Reported from the phone, 2026-09-15, as *"the checkpoint picker is broken"*.
 *
 * ⚠ So the branch lives HERE and nothing outside this file chooses a control.
 */
@Composable
private fun Chooser(
    label: String,
    hint: String?,
    options: List<String>,
    current: String,
    onPick: (String) -> Unit,
    /**
     * ⚠⚠ True when the NUMBER of options depends on something else — the
     * resolutions a checkpoint ships. Such a list is always a dropdown: under
     * [CHIP_LIMIT] it flipped to chips for a model with two sizes and back for
     * one with seven, on the same node (reported 2026-09-19, AbsoluteReality
     * Inpaint). The checkpoint picker's rule, `docs/UI.md` §8.6.
     */
    variesInLength: Boolean = false,
) {
    if (variesInLength || options.size > CHIP_LIMIT) {
        ChoiceDropdown(label, hint, options, current, onPick)
    } else {
        ChoiceRow(label, hint, options, current, onPick)
    }
}

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
private fun SliderRow(
    widget: Widget,
    current: String,
    onSet: (String) -> Unit,
    /**
     * ⭐⭐ Called once when the finger LIFTS, for a knob whose [onSet] is too
     * expensive to run per frame.
     *
     * ⚠⚠ Null for every ordinary knob, and that is deliberate: writing a
     * param on each change is what makes the preview and the node title follow
     * the thumb. The DiT size sliders are the exception — a size write
     * invalidates every derived picture on the canvas and prints a line in the
     * run log ([HarnessViewModel.setNodeResolution]), so per-frame it would be
     * sixty invalidations and sixty log lines for one drag.
     */
    onCommit: (() -> Unit)? = null,
) {
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
            "${widget.name.knobLabel}   " +
                if (isInt) shown.roundToInt().toString()
                else fixed(shown, decimalsAt(shown)),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = toTrack(shown),
            onValueChange = { t ->
                val v = fromTrack(t)
                // ⭐ A [Widget.step] snaps to its grid from `min` — 512, 768, …
                val step = widget.step
                onSet(
                    if (isInt && step != null && step > 0) {
                        (min + ((v - min) / step).roundToInt() * step).roundToInt().coerceIn(min.roundToInt(), max.roundToInt()).toString()
                    } else if (isInt) v.roundToInt().toString() else fixed(v, decimalsAt(v))
                )
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
            onValueChangeFinished = onCommit ?: {},
            modifier = Modifier.fillMaxWidth(),
        )
        widget.hint?.let {
            Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * ⭐ One picture editor on a node: its name, the preview that opens it, and
 * the panel behind it.
 *
 * ⚠ A type rather than parallel lists, because the thumbnail row, the tab
 * row and the index the popup is open at are all read from one order.
 */
/**
 * ⚠ The longest edge the CROP and MASK tiles are rendered at. They are a
 * third of a phone wide; building them at the render resolution cost a
 * dropped frame every time the sheet opened.
 */
private const val THUMB_MAX_EDGE = 384

private data class EditorTile(
    val label: String,
    val thumb: ImageBitmap,
    val panel: @Composable () -> Unit,
)

/**
 * ⭐⭐ A node's PICTURE EDITORS: CROP, MASK and REFERENCE as small previews
 * under their names, each opening ONE popup with them as side-by-side tabs and
 * a close button. The user's call, 2026-09-17.
 *
 * ⚠⚠ A `Dialog`, not more of the sheet: the sheet scrolls and drags, and every
 * drag on a picture-sized editor inside it was a fight over who owns the finger.
 * A dialog is its own window with nothing under it to steal a gesture.
 *
 * ⚠ The popup is a FIXED height, so switching tabs does not resize it under the
 * finger — the same bug the Add node sheet had.
 *
 * ⚠ The previews are the same draws the editors make: the framed picture, that
 * picture with the mask over it ([MaskFraming], taps from the cache), and the
 * reference's chosen region.
 *
 * ⭐⭐⭐ **Which tiles appear is data, not three branches.** It was two fixed
 * ones until 2026-09-20, which is how the reference ended up outside the
 * mechanism entirely — a third picture on the same node, drawn a fourth way.
 * A tile is now `(label, thumbnail, panel)` and the list decides the tabs, the
 * order and the indices together, so a fourth picture is one entry rather than
 * another special case.
 */
@Composable
private fun InpaintEditors(
    node: com.abrah.nightmare.Node,
    type: NodeType?,
    /** ⚠ Null on a node with a reference and no base: the Crop tile is absent. */
    photo: ImageBitmap?,
    refPhoto: ImageBitmap?,
    cropPanel: @Composable () -> Unit,
    maskPanel: @Composable () -> Unit,
    refPanel: @Composable () -> Unit,
    inlineTab: Int? = null,
    /** ⚠ False on image-to-image: the Crop editor alone, no Mask tab. */
    paints: Boolean = true,
    /** ⭐ A request to open the popup ([CanvasState.cropRequest]'s count). */
    openCrop: Int? = null,
    /** ⭐ …on this tab — 1 (Mask) only where there is one. */
    openTab: Int = 0,
) {
    // ⚠⚠ Keyed on the NODE: the inspector can now switch node under this
    // composable ([NodeStrip]), and an unkeyed `remember` would leave the
    // popup open on the node you just left, showing the new node's picture.
    var open by remember(node.id) { mutableStateOf<Int?>(inlineTab) }
    // ⚠ Keyed on the COUNT: fires once per request, not on every recomposition.
    if (openCrop != null) LaunchedEffect(openCrop) { open = if (paints) openTab else 0 }
    val rect = cropRectOf(node)
    val ops = node.params[com.abrah.nightmare.MaskNode.OPS].orEmpty()
    val grow = node.params["grow"]
    val feather = node.params["feather"]
    val (outW0, outH0) = framingOutSize(node, type)
    val pad = node.params[CropNode.PAD]
    // ⚠⚠ Keyed on the render SIZE and the padding too — keyed on the photo and
    // rect alone, a resolution change left both previews at the old shape.
    val framed = remember(photo, rect, outW0, outH0, pad) {
        photo ?: return@remember null
        // ⚠⚠⚠ **Rendered at THUMBNAIL size, not at the output size.**
        //
        // These two tiles are about a third of a phone wide, and this used to
        // build them at the render resolution — a full 1024x1024 (or 2048x2048)
        // bitmap composited and then scaled down to ~180dp, on the main thread,
        // in the frame that opens the sheet. With the directory scan above it,
        // that is the choppy open reported 2026-09-21.
        //
        // ⚠⚠ The SHAPE is kept exactly — the tile is drawn at `outAspect`
        // and the framing maths is unchanged; only the pixel count drops. ⇒ Up
        // to 16x less work for a picture nobody can see the detail of.
        // ⚠ A small output is left alone rather than scaled UP.
        val scale = (THUMB_MAX_EDGE.toFloat() / maxOf(outW0, outH0, 1)).coerceAtMost(1f)
        val outW = (outW0 * scale).toInt().coerceAtLeast(1)
        val outH = (outH0 * scale).toInt().coerceAtLeast(1)
        CropNode.render(
            photo.asAndroidBitmap(), rect.x, rect.y, rect.w, rect.h, outW, outH, node.params[CropNode.PAD],
        ).first
    }
    // ⭐ OUTPAINT padding — the same test the sampler and the editor use.
    val padding = if (!paints) null
    else com.abrah.nightmare.CropGeometry.photoInFrame(rect.x, rect.y, rect.w, rect.h)
    val masked = remember(framed, ops, grow, feather) {
        val base = framed ?: return@remember null
        val stored = com.abrah.nightmare.MaskNode.stateOf(node)
        if (stored.isEmpty && padding == null) return@remember base.asImageBitmap()
        val out = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        if (!stored.isEmpty) {
            val photoBmp = photo?.asAndroidBitmap()
            val state = com.abrah.nightmare.MaskFraming.toFrame(
                com.abrah.nightmare.MaskTaps.resolve(stored) { x, y ->
                    photoBmp?.let { com.abrah.nightmare.segment.Segmenter.cached(it, x, y)?.candidates }
                },
                rect.x, rect.y, rect.w, rect.h,
            )
            val overlay = com.abrah.nightmare.MaskRaster.overlay(state, out.width, out.height, com.abrah.nightmare.MaskRaster.OVERLAY_RGB)
            canvas.drawBitmap(
                overlay, 0f, 0f,
                android.graphics.Paint().apply { alpha = com.abrah.nightmare.MaskRaster.OVERLAY_ALPHA },
            )
        }
        // ⚠ The padding as the editor draws it: blue bands over what is not photo.
        padding?.let { com.abrah.nightmare.MaskRaster.paintPadding(out, it, PADDING_ALPHA) }
        out.asImageBitmap()
    }
    // ⭐⭐ The REFERENCE tile, drawn by the same [CropNode.render] its editor
    // moves. ⚠ Target size **0, 0** — the region at its own pixels, so the
    // thumbnail keeps the shape the model is handed and cannot imply a crop to
    // the canvas ([SdSampler.runDit]).
    val refRect = refCropRectOf(node)
    val refFramed = remember(refPhoto, refRect) {
        val src = refPhoto ?: return@remember null
        CropNode.render(
            src.asAndroidBitmap(),
            refRect.x, refRect.y, refRect.w, refRect.h,
            0, 0, CropNode.PAD_BLACK,
        ).first.asImageBitmap()
    }
    // ⭐⭐⭐ The tiles, in the order they are drawn AND tabbed. ONE list, so
    // the thumbnail row, the tab row and the index [open] holds cannot disagree
    // — they were hardcoded pairs until 2026-09-20, which is how a third
    // picture on the node ended up outside this mechanism altogether.
    val outAspect =
        if (outW0 > 0 && outH0 > 0) outW0.toFloat() / outH0
        else framed?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: 1f
    val tiles = buildList {
        framed?.let { add(EditorTile("Crop", it.asImageBitmap(), cropPanel)) }
        if (paints) masked?.let { add(EditorTile("Mask", it, maskPanel)) }
        refFramed?.let { add(EditorTile("Reference", it, refPanel)) }
    }
    if (tiles.isEmpty()) return
    val labels = tiles.map { it.label }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.forEachIndexed { i, tile ->
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(tile.label, style = MaterialTheme.typography.titleSmall)
                androidx.compose.foundation.Image(
                    bitmap = tile.thumb,
                    contentDescription = "edit the ${tile.label.lowercase()}",
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        // ⭐ The RESOLUTION's shape, not a fixed strip (2026-09-17),
                        // and the SAME shape for every tile in the row.
                        //
                        // ⚠⚠ A reference keeps its own aspect on the wire, and
                        // giving its TILE that aspect was the first attempt here:
                        // a portrait reference beside a square canvas made one
                        // thumbnail twice the height of the other and the row
                        // read as broken — the opposite of the consistency this
                        // was changed for. The shape it is sent at is stated in
                        // its own editor, which is where that promise belongs;
                        // `ContentScale.Fit` letterboxes it here.
                        .aspectRatio(outAspect)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { open = i },
                )
            }
        }
        // ⚠⚠ The SAME half width when there is only one tile. Drawn full
        // width, a lone Crop preview looked exactly like the inline crop editor
        // it replaced, and read as "no popup" on the phone (2026-09-17).
        if (tiles.size == 1) Spacer(Modifier.weight(1f))
    }
    // ⚠ Clamped: the tile list shrinks the moment a wire is pulled, and an
    // index kept across that would open a tab that is no longer there.
    val tab = (open ?: return).coerceIn(0, tiles.lastIndex)
    val body: @Composable () -> Unit = {
        InpaintPopupBody(tab, labels, onTab = { open = it }, onClose = { open = null }, tiles[tab].panel)
    }
    if (inlineTab != null) {
        Box(Modifier.fillMaxWidth().height(760.dp)) { body() }
        return
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { open = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = 12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) { body() }
    }
}

/**
 * ⭐ The popup's content: the two tabs side by side, a close button, and the
 * tab's panel — its picture CENTRED in the width, the controls under it.
 */
@Composable
private fun InpaintPopupBody(
    tab: Int,
    labels: List<String>,
    onTab: (Int) -> Unit,
    onClose: () -> Unit,
    /** ⚠ The SELECTED tab's panel — the caller's tile list owns which. */
    panel: @Composable () -> Unit,
) {
    // ⭐⭐⭐ **Drag DOWN anywhere to close, and the window FOLLOWS the finger.**
    //
    // ⚠⚠⚠ **Why `nestedScroll` and not a `pointerInput` on the root.**
    // Hit-testing already does most of the work: the crop and mask editors
    // consume their own drags, so a parent detector never sees a finger that
    // landed on the picture — that part needs no arrangement at all. What
    // DOES get in the way is the scrolling column below: `verticalScroll`
    // consumes every vertical drag it can use, so a root detector would
    // almost never fire. ⇒ Take what the scroll could NOT use: once the
    // content is at the top a downward drag is spare, and spare downward drag
    // moves the window — which is how a bottom sheet behaves, for this reason.
    //
    // ⚠⚠ An earlier version of this claimed a whole-surface drag would
    // fight the editors. It would not; children consume first, and the user
    // said so on 2026-09-21. The fight the history records was a
    // `ModalBottomSheet`, whose dismiss works through nested scroll that raw
    // `pointerInput` children do not speak — a sheet-specific mechanism,
    // wrongly generalised to a `Dialog`.
    //
    // ⭐⭐ **Animated, which is a separate thing from closing.** Reported
    // 2026-09-21: only the node inspector animated shut. A window that
    // vanishes on release never showed the gesture working, so the offset is
    // applied live and released either back to rest or out of the bottom,
    // with `onClose` called when the slide ENDS.
    // ⚠ Damped to a third while dragging: the finger stays ahead of the
    // window, which is what makes it feel resisted rather than stuck to.
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val outY = with(androidx.compose.ui.platform.LocalDensity.current) { 900.dp.toPx() }
    val settle: () -> Unit = {
        scope.launch {
            if (offset.value > 160f) {
                offset.animateTo(outY, androidx.compose.animation.core.tween(180))
                onClose()
            } else {
                offset.animateTo(0f)
            }
        }
    }
    val pull = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f) {
                    scope.launch { offset.snapTo(offset.value + available.y / 3f) }
                }
                // ⚠ Consumes nothing: the content keeps every pixel it wanted,
                // this only watches what was left over.
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                settle()
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { translationY = offset.value }
            .nestedScroll(pull)
            // ⚠ …and the same gesture on the chrome, which does not scroll and
            // so sends the connection above nothing.
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                ) { _, d ->
                    scope.launch { offset.snapTo((offset.value + d / 3f).coerceAtLeast(0f)) }
                }
            }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ⚠ The pill is an AFFORDANCE, not the only target: it says the
        // window can be dragged, which nothing else about it does.
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 32.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ⭐ Two tabs SIDE BY SIDE, each half the row, in the pill style
            // of [com.abrah.nightmare.ui.SwipeTabs].
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    val on = tab == i
                    androidx.compose.material3.Surface(
                        onClick = { onTab(i) },
                        shape = RoundedCornerShape(20.dp),
                        color = if (on) MaterialTheme.colorScheme.secondaryContainer
                        else androidx.compose.ui.graphics.Color.Transparent,
                        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        )
                    }
                }
            }
            // ⚠ No ✕ here any more — the handle above closes it, and so does
            // the back gesture. Two ways to shut one window is the redundancy
            // this project keeps deleting.
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            // ⚠ The EDITORS centre themselves (window-fit, [CropEditor]); the
            // text under them stays left-aligned like every other sheet.
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            panel()
        }
    }
}

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
    growMax: Float,
    onGrow: ((Float) -> Unit)?,
    feather: Float,
    featherMax: Float,
    onFeather: ((Float) -> Unit)?,
) {
    val options = when (tool) {
        MaskTool.BRUSH, MaskTool.ERASE -> listOfNotNull(MaskParam.SIZE, MaskParam.FEATHER.takeIf { onFeather != null })
        MaskTool.TAP -> listOfNotNull(MaskParam.GROW.takeIf { onGrow != null }, MaskParam.FEATHER.takeIf { onFeather != null })
    }
    if (options.isEmpty()) return
    var param by remember(options) { mutableStateOf(options.first()) }
    var menu by remember { mutableStateOf(false) }
    val (range, value) = when (param) {
        MaskParam.SIZE -> BRUSH_MIN..BRUSH_MAX to brush
        MaskParam.GROW -> 0f..growMax to grow
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
@Composable
private fun MaskToolbar(
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
    fun framed(resolved: com.abrah.nightmare.MaskState) =
        com.abrah.nightmare.MaskFraming.toFrame(resolved, frame.x, frame.y, frame.w, frame.h)
    val quick = remember(stored, frame, photo) {
        framed(com.abrah.nightmare.MaskTaps.resolve(stored) { x, y -> seg.cached(photoBmp, x, y)?.candidates })
    }
    var full by remember(stored, frame, photo) { mutableStateOf<com.abrah.nightmare.MaskState?>(null) }
    if (com.abrah.nightmare.MaskTaps.hasTaps(stored) && seg.installed) {
        androidx.compose.runtime.LaunchedEffect(stored, frame, photo) {
            full = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    framed(com.abrah.nightmare.MaskTaps.resolve(stored) { x, y ->
                        seg.segment(context, photoBmp, x, y)?.candidates
                    })
                }.getOrNull()
            }
        }
    }
    val state = full ?: quick
    // ⭐ The Tap tool exists only while a segmenter is WIRED (`docs/SEGMENTER.md` §1).
    val canTap = node.inputs["segmenter"] != null
    if (!canTap && tool == MaskTool.TAP) tool = MaskTool.BRUSH
    var tapping by remember { mutableStateOf(false) }
    var tapNote by remember { mutableStateOf<String?>(null) }
    // ⚠ Whether the segmenter was already warm when this tap started — it is
    // what decides between "Loading" and "Finding", and it must be sampled at
    // the tap rather than read live (see [onTap]).
    var warmAtTap by remember { mutableStateOf(true) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MaskEditor(
            source = source,
            state = state,
            tool = tool,
            brushRadiusFrac = radius,
            padding = padding,
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
        // ⭐ What a tap is doing, or why it did nothing — a tap that silently
        // selects nothing is DreamUI's "the tap is ignored" report.
        if (tool == MaskTool.TAP || tapNote != null) {
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
        MaskParamSlider(
            tool = tool,
            brush = radius,
            onBrush = { radius = it },
            grow = live["grow"]?.toFloatOrNull() ?: 0f,
            growMax = growW?.max?.toFloat() ?: 0.2f,
            onGrow = if (growW == null) null else { v -> onSetParam("grow", fixed(v, 3)) },
            feather = live["feather"]?.toFloatOrNull() ?: 0f,
            featherMax = featherW?.max?.toFloat() ?: 0.2f,
            onFeather = if (featherW == null) null else { v -> onSetParam("feather", fixed(v, 3)) },
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
        title = { Text("Sweep " + widget.name.knobWord) },
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
        // ⚠ The same label style as a slider's (`bodyMedium`), not the hint's:
        // a knob's name reads alike whichever control draws it (§7.4).
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (o in options) {
                FilterChip(
                    selected = o == current,
                    onClick = { onPick(o) },
                    // ⚠ A chip is pressed, so it is Capitalised (§7.1). The VALUE
                    // written stays `black` — only what is drawn changes.
                    label = { Text(o.replaceFirstChar { it.uppercase() }, fontSize = 12.sp) },
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
    /** ⚠ Null = "rolls a new one each Run" — the row still shows, saying so. */
    seed: String?,
    tint: Color? = null,
    /**
     * ⭐ Pin this seed onto the sampler, so the next Run makes the same picture.
     * Null when there is nothing to pin or it is pinned already.
     */
    onLock: (() -> Unit)? = null,
    /**
     * ⭐⭐ Compact — the number and its copy button, no container, no caption.
     *
     * ⚠ For a row that is already crowded: the Results card carries five
     * controls beside this, and the full pill pushed the primary button off the
     * edge. ⚠ The caption is dropped rather than the copy button, because the
     * button is the reason the seed is on screen at all.
     */
    compact: Boolean = false,
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
        if (compact) Modifier else Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                (tint ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.12f)
            )
            .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            when {
                seed == null -> "seed random"
                compact -> seed
                else -> "seed $seed"
            },
            style = LogTextStyle,
            color = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // ⚠ No copy button when there is no number: a button that copies the
        // word "random" is worse than no button.
        seed?.let { value ->
            IconButton(onClick = { clipboard.setText(AnnotatedString(value)) }) {
                Icon(
                    painterResource(R.drawable.ic_copy),
                    contentDescription = "copy the seed",
                    tint = tint ?: MaterialTheme.colorScheme.primary,
                )
            }
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

/**
 * ⭐ A field's label, with the token count beside it when the field is prose —
 * `local-dream`'s `PromptCountLabel`, `Prompt 23/77`.
 *
 * ⚠⚠ ONE function for both text-field branches above (the focused one and the
 * plain one): a label added to one rendering branch and not the other is the
 * bug `docs/UI.md` §7.4 records three times.
 */
@Composable
private fun ProseLabel(name: String, why: String?, count: com.abrah.nightmare.PromptTokens.Count?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (why != null) "${name.knobLabel}  (locked)" else name.knobLabel)
        if (count != null) {
            Text(
                "  ${count.label}",
                color = if (count.over) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        }
    }
}
