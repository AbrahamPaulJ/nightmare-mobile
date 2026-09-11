package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.ui.LogTextStyle

/**
 * The canvas, with the run bar over it.
 *
 * ⚠ Stateless, like `HarnessContent`, so the goldens can put it in states the
 * app is hard to hold still in — mid-render, failed, mid-wire. A composable that
 * can only be reached by driving the real app is a composable nobody pins.
 */
@Composable
fun CanvasScreen(
    state: CanvasState,
    types: Map<String, NodeType>,
    status: Map<String, NodeStatus>,
    busy: Boolean,
    image: ImageBitmap?,
    /**
     * A whole new state, from the GESTURE LOOP only.
     *
     * ⚠⚠ Named for the one caller it may have. A gesture threads its own state
     * for its duration and publishes each step immediately, so its value is
     * never stale (`CanvasGestures.kt`). Everything else must use [onEdit]: a
     * state captured at composition time and written back late reverts the
     * whole graph, which is what took the chosen photo and the dragged crop
     * rect away on sheet dismissal. `HarnessViewModel.editCanvas` has the log.
     */
    onGesture: (CanvasState) -> Unit,
    onRun: () -> Unit,
    /** ⭐ Stop the run in flight. Null hides the affordance (previews, goldens). */
    onCancelRun: (() -> Unit)? = null,
    /** ⭐ Open the Batch sheet. See `BatchSheet.kt`. */
    onBatch: () -> Unit = {},
    /** ⭐ Which run of how many, while a sweep is going. Null when it is not. */
    batchProgress: Pair<Int, Int>? = null,
    onCancelBatch: () -> Unit = {},
    /** ⭐ Knobs armed for a sweep, read from the graph. See `BatchParams`. */
    armedSweeps: List<ArmedSweep> = emptyList(),
    onReleaseSweep: (ArmedSweep) -> Unit = {},
    onBack: () -> Unit,
    /**
     * ⚠ Why the LAST RUN failed, shown here rather than only in the harness log.
     * A user on the canvas cannot see that log, so a refused graph looked
     * exactly like a button that did nothing.
     */
    runError: String? = null,
    /** ⭐ What is running now, and what the last run cost — `RunLog.kt`. */
    runLog: RunLogState = RunLogState(),
    /** Dismiss the run log. ⚠ Hides it; it does not cancel the render. */
    onCloseRunLog: () -> Unit = {},
    /** The model in use, so the user can see WHAT will render before pressing Run. */
    modelLabel: String = "",
    backendUp: Boolean = false,
    /** ⭐ The active flow's name, and whether it has unsaved edits. */
    flowName: String? = null,
    flowDirty: Boolean = false,
    /** ⭐ One line of measured load: free RAM, and what the backend holds. */
    loadLine: String? = null,
    onModels: () -> Unit = {},
    onResults: () -> Unit = {},
    onWorkflows: () -> Unit = {},
    /** ⭐ Open the device sheet — HTP arch and VTCM. */
    onDeviceInfo: () -> Unit = {},
    /**
     * Save the canvas under this name.
     *
     * ⚠ [savedAs] is the workflow it came from, and the dialog offers it
     * rather than an empty box: saving over your own graph should not require
     * remembering what you called it.
     */
    onSave: (String) -> Unit = {},
    savedAs: String? = null,
    /** Resolves an image id to pixels, for node previews and the viewer. */
    imageFor: (String) -> ImageBitmap? = { null },
    /**
     * ⭐⭐ Apply a change to the canvas **as it is when the change happens**,
     * rather than to the one this composition was given.
     *
     * ⚠⚠ Everything that is not a gesture goes through here, and that is a
     * bug fix rather than a style. `onGesture(state.something())` captures the
     * WHOLE graph at composition time, so a callback that fires late -- a sheet
     * dismissal, a result from the image picker (which runs while this activity
     * is stopped), the end of a dismiss animation -- writes back a snapshot
     * that undoes every edit made since. On the phone that reverted the `uri`
     * the user had just chosen, and the crop rect they had just dragged, the
     * instant the sheet closed. `HarnessViewModel.editCanvas` has the log.
     *
     * ⚠ The default keeps the old behaviour for the goldens, which fire no
     * callbacks at all.
     */
    onEdit: ((CanvasState) -> CanvasState) -> Unit = { change -> onGesture(change(state)) },
    /**
     * ⭐⭐ A mask edit, as a transform of the node's OWN ops.
     *
     * ⚠⚠ Separate from [onEdit] because the staleness that bit here was in the
     * VALUE, not the write: rebuilding the whole ops string from a captured
     * node and handing it over as an absolute loses any stroke made since.
     * `HarnessViewModel.editMask` has the measurement.
     */
    /**
     * ⭐⭐ Set the render size for the WHOLE graph, from the node the user
     * happened to open.
     *
     * ⚠ Not an `onEdit` transform like [onSetParam] is: a size change also
     * stops a backend launched at the old one, re-derives every framed crop and
     * moves the remembered default for new nodes — none of which a pure
     * `CanvasState` edit can do. `HarnessViewModel.selectResolution` owns it.
     */
    onSetResolution: (com.abrah.nightmare.Res) -> Unit = {},
    /** ⭐ The same for a fixed-canvas family, where the choice is a ratio. */
    onSetAspect: (String) -> Unit = {},
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit =
        { _, _ -> },
    /**
     * ⭐ Why a workflow name will not do, or null — `WorkflowStore.validName`.
     * ⚠ Passed IN rather than duplicated here: two copies of a naming rule
     * disagreeing is how a dialog accepts a name the store then refuses.
     */
    validateWorkflowName: (String) -> String? = { null },
    /**
     * Forget the picture on a `load_image` node.
     *
     * ⚠⚠ Not `onEdit { it.setParam(id, "uri", "") }`, though that is half of it.
     * Clearing the uri leaves the node's PREVIEW -- and every preview derived
     * from it downstream -- still hanging on the canvas, because previews are
     * only ever added (`HarnessViewModel.resolvePreviews`). Dropping them is the
     * view model's job, and it is the only thing that may touch that map.
     */
    onClearImage: (String) -> Unit = {},
    /** ⭐ Write a rendered image to the gallery. */
    onSaveImage: (String) -> Unit = {},
    /** ⭐ Hand a node's picture to another app. */
    onShareImage: (String) -> Unit = {},
    /** ⭐ Keep a rendered image AND the flow that made it, in Results. */
    onKeepImage: (String) -> Unit = {},
    /** ⭐ Is this picture already kept? Drives the star's filled/outline state. */
    isKept: (String) -> Boolean = { false },
    /** ⭐ Drop a render from the node that made it. */
    onClearOutput: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    // ⚠ The gesture modifier outlives every recomposition (see below), so it
    // must read these through a stable holder rather than capture them.
    val liveState = rememberUpdatedState(state)
    val liveTypes = rememberUpdatedState(types)
    val liveOnState = rememberUpdatedState(onGesture)
    // ⚠ Needed so a node from the palette lands where the user is LOOKING.
    // Placing at the world origin would drop it off screen as soon as the
    // canvas had been panned once.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // ⚠ Local to the screen: a half-typed name and an unanswered "are you
    // sure" are not graph state, and putting them in `CanvasState` would autosave
    // them and restore them on a cold start.
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(CanvasColors.background)
            .onSizeChanged { canvasSize = it },
    ) {
        GraphCanvas(
            workflow = state.workflow,
            types = types,
            viewport = state.viewport,
            selected = state.selection,
            wire = state.wire,
            wireConfirming = state.wireConfirming,
            status = status,
            pending = state.pending,
            previews = state.previews,
            imageFor = imageFor,
            modifier = Modifier
                .fillMaxSize()
                .canvasGestures(
                    // ⚠⚠ Read through `rememberUpdatedState`, NOT `{ state }`.
                    //
                    // `state` is a function parameter, so `{ state }` captures
                    // its VALUE at the composition that created the lambda —
                    // and `pointerInput(Unit)` keeps the block it was first
                    // given. The gesture loop therefore read the canvas as it
                    // was when the screen opened, forever: press/drag/release
                    // all computed against a stale Idle state, so nothing moved,
                    // nothing zoomed and a tap opened nothing.
                    //
                    // ⚠ It looked correct — the old comment here even argued for
                    // lambdas — and it survived a touch test, because that test
                    // passed a delegated local `var` whose closure reads the
                    // MutableState live. Only `CanvasTouchTest` driving THIS
                    // composable catches it. Measured on device 2026-09-08: a
                    // pan swipe moved the canvas by exactly zero pixels.
                    state = { liveState.value },
                    types = { liveTypes.value },
                    density = density,
                    onGesture = { liveOnState.value(it) },
                ),
        )

        // ⭐⭐ **Editing a prompt ON the canvas**, without the inspector.
        //
        // ⚠⚠ This is the one real composable floated over the drawn canvas.
        // `docs/ROADMAP.md` weighed that approach for the batch strip and set
        // it aside, and the reason it is affordable here is that it is exactly
        // ONE field at a time: it is positioned once, from a rect the layout
        // already computes, and it exists only while the keyboard is up. N
        // floating widgets that must stay hit-testable through every pan and
        // zoom is the thing that was too expensive, and this is not that.
        //
        // ⚠ Positioned from the node's own box, so it lands on the prompt it is
        // editing. It is NOT clipped to the canvas transform -- it is a real
        // field at a screen position, which is what lets the keyboard, the
        // cursor and text selection all behave normally.
        state.editingProse?.let { (nodeId, field) ->
            val node = state.workflow.graph.byId[nodeId]
            val nodeBox = layout(state.workflow, types, state.previews)
                .firstOrNull { it.id == nodeId }
            val rect = nodeBox?.proseRects()?.firstOrNull { it.first == field }
            if (node != null && nodeBox != null && rect != null) {
                val topLeft = state.viewport.toScreen(Pt(nodeBox.topLeft.x, rect.second))
                val widthDp = (nodeBox.width * state.viewport.scale / density).dp
                var text by remember(nodeId, field) {
                    mutableStateOf(node.params[field].orEmpty())
                }
                val focus = remember(nodeId, field) { FocusRequester() }
                LaunchedEffect(nodeId, field) { focus.requestFocus() }
                val commit = {
                    onEdit { st -> st.setParam(nodeId, field, text).copy(editingProse = null) }
                }
                // ⚠⚠ The scrim is FIRST, so it sits UNDER the field. Drawn
                // after, it would cover the thing it exists to dismiss and the
                // field could never be typed into.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(nodeId, field) { detectTapGestures { commit() } }
                )
                Box(Modifier.offset { IntOffset(topLeft.x.toInt(), topLeft.y.toInt()) }) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text(field) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        // ⚠ Prose wraps rather than scrolling sideways, same as
                        // the inspector's field.
                        minLines = 2,
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        // ⚠⚠ Committed on Done AND on a tap outside. A prompt
                        // typed and then lost to a stray tap is the worst
                        // outcome an in-place editor can have.
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .width(widthDp.coerceAtLeast(180.dp))
                            .focusRequester(focus),
                    )
                }
            }
        }

        // ⚠ The old floating corner preview is GONE. A picture in the corner
        // belongs to no node, so with more than one image node on a canvas it
        // could not say which had produced it -- and it covered whatever was
        // underneath. Previews are drawn ON the node that made them.

        TopBar(
            backendUp = backendUp,
            flowName = flowName,
            flowDirty = flowDirty,
            loadLine = loadLine,
            onModels = onModels,
            onResults = onResults,
            onWorkflows = onWorkflows,
            modifier = Modifier.align(Alignment.TopStart),
        )

        // ⭐⭐ The two diagnostics, top RIGHT. Neither is part of making a
        // picture -- the harness had a full-width word in the run bar,
        // competing for the row with Run itself. ⚠ They stay ONE TAP away
        // rather than being buried: the harness is where the backend log lives,
        // and a canvas with no exit strands the user the first time a render
        // fails.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⭐ Save the canvas. ⚠ FIRST in this row, i.e. leftmost, because
            // it is the only one of the three that acts on the graph rather
            // than opening something about the app — and it is the one reached
            // most often, so it sits furthest from the screen edge where a
            // thumb is steadiest.
            IconButton(onClick = { saving = true }) {
                Icon(
                    com.abrah.nightmare.ui.SaveIcon,
                    contentDescription = "save this workflow",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⭐ What this phone's NPU actually is. ⚠ Beside the wrench because
            // it answers the question the wrench's log raises -- "why did that
            // fail on MY phone" -- and the arch/VTCM pair is the answer to most
            // of them.
            // ⚠ See `docs/UI.md` §7.2: an inset is not padding. This row sits
            // at the top-RIGHT, so it needs clearance from that edge too — the
            // gear was landing hard against it.
            IconButton(onClick = onDeviceInfo) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.cd_device_info),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⚠⚠ A GEAR, and it opens Settings — not the wrench that opened
            // the op harness. A developer tool with a developer's icon was one
            // of three unlabelled glyphs on the app's first screen, and the
            // least likely of the three to be what anyone wanted. The harness
            // is still there, behind Settings > Diagnostics.
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        RunBar(
            state = state,
            busy = busy,
            onRun = onRun,
            onCancelRun = onCancelRun,
            onBatch = onBatch,
            batchProgress = batchProgress,
            onCancelBatch = onCancelBatch,
            armedSweeps = armedSweeps,
            onReleaseSweep = onReleaseSweep,
            onAdd = { onEdit { s -> s.openPalette() } },
            onBack = onBack,
            onToggleZoomLock = { onEdit { s -> s.toggleZoomLock() } },
            onTogglePanLock = { onEdit { s -> s.togglePanLock() } },
            onSelectAll = { onEdit { s -> s.selectAll() } },
            onDeleteSelected = { confirmingDelete = true },
            onClearSelection = { onEdit { s -> s.clearSelection() } },
            runError = runError,
            runLog = runLog,
            onCloseRunLog = onCloseRunLog,
            // ⚠ The FIRST sampler. A graph with two has two seeds and no single
            // answer; the row names the one Run reaches first and leaves the
            // rest to the inspector rather than lying about either.
            seed = state.workflow.graph.nodes.firstOrNull { it.type == "sd.sample" }?.let { n ->
                SeedState(
                    value = n.params["seed"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
                    lastRolled = seedFor(state.workflow.graph, n.id) { status[it]?.detail },
                )
            },
            onToggleSeed = {
                state.workflow.graph.nodes.firstOrNull { it.type == "sd.sample" }?.let { n ->
                    val pinned = n.params["seed"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
                    if (pinned != null) {
                        onEdit { st -> st.setParam(n.id, "seed", "0") }
                    } else {
                        // ⚠ Only when there IS a rolled seed to pin. Locking
                        // before anything has run would pin nothing.
                        seedFor(state.workflow.graph, n.id) { status[it]?.detail }
                            ?.let { rolled -> onEdit { st -> st.setParam(n.id, "seed", rolled) } }
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // ⚠⚠ The app is edge-to-edge (`enableEdgeToEdge`), so without
                // this the whole bar sits UNDER the navigation bar: on a gesture
                // -nav phone the buttons are half-covered and the bottom strip
                // belongs to the system, which is why "Run did nothing" and
                // "+ node" could not be pressed at all. Reported from a real
                // phone, 2026-09-08 -- no golden caught it, because the goldens
                // render GraphCanvas without the bar.
                .navigationBarsPadding()
                // Gesture-nav phones report a small navigation inset but still
                // reserve a taller strip for the back gesture; the extra keeps
                // the row clear of it.
                .padding(bottom = 8.dp),
        )
    }

    // ⚠ Outside the Box, so the sheet's scrim covers the run bar too. Inside it
    // the bar would sit on top of the scrim and still be tappable, which is how
    // a graph gets run while its own inspector is open.
    NodeInspector(
        state = state,
        types = types,
        status = status,
        onClearImage = onClearImage,
        onSaveImage = onSaveImage,
        onShareImage = onShareImage,
        onKeepImage = onKeepImage,
        isKept = isKept,
        onClearOutput = onClearOutput,
        imageFor = imageFor,
        onViewFullscreen = { id -> onEdit { s -> s.copy(editing = null, viewing = id) } },
        onSetParam = { node, name, value -> onEdit { s -> s.setParam(node, name, value) } },
        onSetParams = { node, values -> onEdit { s -> s.setParams(node, values) } },
        onEditMask = onEditMask,
        onSetResolution = onSetResolution,
        onSetAspect = onSetAspect,
        // ⚠ A pure state edit, like every other canvas change: renameNode
        // rewires the graph and moves every id-keyed map with it.
        onRename = { from, to -> onEdit { s -> s.renameNode(from, to) } },
        onDelete = { id -> onEdit { s -> s.removeNode(id) } },
        onDismiss = { onEdit { s -> s.closeInspector() } },
    )

    // ⭐ Fullscreen. A 190-unit node preview is a thumbnail; this is where a
    // person actually looks at what they made.
    //
    // ⚠⚠ WHICH NODE is showing this picture, found by reversing `previews`
    // rather than carried in `viewing`. The viewer needs it for two things a
    // picture alone cannot answer -- whether this is a photo the user chose (so
    // it can be swapped or dropped) and which sampler made it (so it can show
    // the seed) -- and the reverse lookup keeps `viewing` a plain image id, so
    // nothing has to keep the two halves in step.
    val viewedNode = state.viewing?.let { id ->
        state.previews.entries.firstOrNull { it.value.first == id }?.key
    }
    val viewedIsPhoto = viewedNode
        ?.let { state.workflow.graph.byId[it] }?.type == "image.load"
    // ⚠⚠ Hoisted OUT of the `let` below: `rememberImagePick` registers an
    // activity-result launcher, and a launcher registered inside a conditional
    // is registered and torn down as the condition flips -- which is exactly
    // when the result comes back, since the picker runs while this activity is
    // stopped.
    val pickForViewed = rememberImagePick { uri ->
        viewedNode?.let { n -> onEdit { s -> s.setParam(n, "uri", uri) } }
        // ⚠ Back to the canvas, because the picture on screen is now the OLD
        // one: the new photo resolves into a new image id a moment later, and a
        // viewer still holding the previous id would sit there showing the
        // thing the user just replaced.
        onEdit { s -> s.copy(viewing = null) }
    }
    state.viewing?.let { id ->
        imageFor(id)?.let { bmp ->
            FullscreenImage(
                bmp,
                onDismiss = { onEdit { s -> s.copy(viewing = null) } },
                seed = viewedNode?.let { n ->
                    seedFor(state.workflow.graph, n) { status[it]?.detail }
                },
                onPick = if (viewedIsPhoto) pickForViewed else null,
                onClear = if (viewedIsPhoto && viewedNode != null) {
                    {
                        onClearImage(viewedNode)
                        onEdit { s -> s.copy(viewing = null) }
                    }
                } else null,
                // ⚠ Only for a RENDER. A photo the user chose is already in
                // their gallery, and offering to save it back would make a
                // second copy of a file they already have.
                onSave = if (!viewedIsPhoto) {
                    { onSaveImage(id) }
                } else null,
                // ⭐ Share, wherever Save is offered. ⚠ Including an UPSCALE's
                // output: the whole point of a 4x is sending it somewhere.
                onShare = if (!viewedIsPhoto) {
                    { onShareImage(id) }
                } else null,
                // ⭐⭐ Keep it, WITH the graph. ⚠ Distinct from the gallery
                // button beside it and the difference is the whole point: the
                // gallery gets a picture, this gets a picture you can reopen as
                // a flow.
                onKeep = if (!viewedIsPhoto) {
                    { onKeepImage(id) }
                } else null,
                kept = isKept(id),
                onDeleteOutput = if (!viewedIsPhoto && viewedNode != null) {
                    {
                        onClearImage(viewedNode)
                        onEdit { s -> s.copy(viewing = null) }
                    }
                } else null,
                // ⚠ Offered only when there IS a rolled seed and it is not
                // already pinned — a lock button that is already locked says
                // nothing, and the release lives in the run bar where it stays
                // visible after this closes.
                onLockSeed = viewedNode?.let { n ->
                    val sampler = samplerFor(state.workflow.graph, n)
                    val rolled = seedFor(state.workflow.graph, n) { status[it]?.detail }
                    if (sampler != null && rolled != null &&
                        state.workflow.graph.byId[sampler]?.params?.get("seed")?.trim()
                            .orEmpty().let { it.isEmpty() || it == "0" }
                    ) {
                        { onEdit { s -> s.setParam(sampler, "seed", rolled) } }
                    } else {
                        null
                    }
                },
            )
        }
    }

    // ⭐⭐ A confirm before deleting nodes, because delete is the one canvas
    // action with no undo -- the wires that pointed at those nodes go with them.
    if (confirmingDelete && state.selection.isNotEmpty()) {
        val n = state.selection.size
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(if (n == 1) "Delete this node?" else "Delete $n nodes?") },
            text = {
                Text(
                    // ⚠ Names them. "Delete 3 nodes?" over a canvas the dialog
                    // is covering is a question the user cannot check.
                    state.selection.sorted().joinToString(", ") +
                        "\n\nEvery wire into or out of them goes too, and this cannot be undone.",
                    style = LogTextStyle,
                )
            },
            confirmButton = {
                Button(
                    onClick = { confirmingDelete = false; onEdit { s -> s.removeSelected() } },
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

    if (saving) {
        SaveWorkflowDialog(
            initial = savedAs.orEmpty(),
            onDismiss = { saving = false },
            onSave = { saving = false; onSave(it) },
            validate = validateWorkflowName,
        )
    }

    if (state.showPalette) {
        NodePalette(
            types = types,
            onPick = { type ->
                // The middle of what is on screen, in world units.
                val centre = state.viewport.forDevice(density).toWorld(
                    Pt(canvasSize.width / 2f, canvasSize.height / 2f)
                )
                // ⚠ Offset by half a node so the node is centred rather than
                // starting at the centre, which puts most of it off to one side.
                onEdit { s ->
                    s.addNode(
                        type,
                        Pt(centre.x - Sizes.NODE_WIDTH / 2f, centre.y - Sizes.HEADER_HEIGHT),
                    )
                }
            },
            onDismiss = { onEdit { s -> s.closePalette() } },
        )
    }
}

/**
 * ⭐ Model, backend state, and the way to the model picker.
 *
 * ⚠ It exists because the canvas is now the app's FIRST screen: everything the
 * harness used to explain -- which model, whether a server is running, where to
 * get one -- has to be visible to someone who never sees the harness.
 */
@Composable
private fun TopBar(
    backendUp: Boolean,
    /**
     * ⭐ The flow on the canvas, and whether it has unsaved edits. Null hides
     * the row — a preview and a golden have no view model.
     */
    flowName: String? = null,
    flowDirty: Boolean = false,
    /** ⭐ What the device is carrying. Null while it has not been measured. */
    loadLine: String? = null,
    onModels: () -> Unit,
    onWorkflows: () -> Unit,
    onResults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠⚠ TWO rows, and the model name is the SECOND one. It used to sit inline
    // ahead of the buttons, which made the row's layout depend on the length of
    // a name we do not choose: "AnythingV5" fits and "Pony Diffusion v6 XL"
    // pushes Save off the edge of a 384dp phone. SDXL is what made that
    // concrete -- its labels are two and three words -- but the bar was always
    // one long checkpoint name away from breaking.
    //
    // ⇒ The buttons come first and their row is fixed; the name gets a line to
    // itself underneath, where growing costs nothing.
    Column(
        modifier
            // ⚠ Edge-to-edge again: without this the row is under the clock.
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            // ⚠ A background, because the canvas scrolls UNDER this bar: the
            // default graph puts a node within a few dp of the top, and without
            // a ground the model name is drawn over the node's title.
            .clip(RoundedCornerShape(14.dp))
            .background(CanvasColors.nodeBody.copy(alpha = 0.92f))
            .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onModels,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text(stringResource(R.string.nav_models), fontSize = 12.sp) }
            OutlinedButton(
                onClick = onWorkflows,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text(stringResource(R.string.nav_flows), fontSize = 12.sp) }
            // ⭐ Results, reachable from the canvas. ⚠ It was a TAB with no
            // door: you could only get to it by opening Models or Flows first
            // and then noticing a third tab, which nobody did.
            OutlinedButton(
                onClick = onResults,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text(stringResource(R.string.nav_results), fontSize = 12.sp) }
            // ⚠ Save is NOT here any more. It moved to the icon row at the top
            // RIGHT (beside ⓘ and the wrench), because this row is destinations
            // — "go to Models", "go to Flows" — and Save is an action on the
            // canvas you are already looking at. Mixing the two made a row of
            // three words where only two of them navigated.
        }
        // ⭐⭐ Under the buttons: the dot, the flow, and the load.
        //
        // ⚠⚠ **The model name is NOT on a row of its own, and that is the fix.**
        // It had one, and the load line underneath said "holding <the same
        // name>" — the checkpoint printed twice, two lines apart. It appears
        // once now, inside the load line, where it is doing work: "holding X"
        // says the model AND that a process is up for it.
        //
        // ⚠⚠⚠ **WRAPPED, never ellipsised.** Both lines used to be `maxLines =
        // 1` with `TextOverflow.Ellipsis` and a `weight`, so a long checkpoint
        // name — which is most of the SDXL catalogue — was cut mid-word and the
        // load figures after it vanished entirely. Asked for from the phone,
        // 2026-09-10: *do not "..." the held model, wrap it, and let the
        // container cover it.* The bar is a `Column`, so it grows to whatever
        // these need; nothing here has a fixed height to break.
        if (flowName != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                // ⚠ Top, not Center: the flow name may now be two lines, and a
                // centred dot beside a two-line block floats in the middle of it.
                verticalAlignment = Alignment.Top,
            ) {
                // ⚠ The backend light. ⚠ Nudged down by 4dp so it sits on the
                // FIRST line's optical centre rather than the row's.
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (backendUp) CanvasColors.ran
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                )
                Text(
                    flowName + if (flowDirty) " •" else "",
                    style = LogTextStyle,
                    fontSize = 11.sp,
                    color = if (flowDirty) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (loadLine != null) {
            Text(
                loadLine,
                style = LogTextStyle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // ⚠ No maxLines, no overflow: this is the line that carries the
                // model name, and losing its tail is losing the free-RAM figure
                // the row exists for.
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Name it, with the name it already has.
 *
 * ⚠ Pre-populated from the workflow the canvas was opened from, and the field
 * starts SELECTED so overwriting is one keystroke and keeping it is none.
 */
@Composable
private fun SaveWorkflowDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /**
     * ⭐⭐ Why this name will not do, or null. ⚠ Checked HERE so the dialog can
     * refuse and stay open.
     */
    validate: (String) -> String? = { null },
) {
    var name by remember { mutableStateOf(initial) }
    // ⚠⚠ **The reason this exists.** Save used to close the dialog and call the
    // view model, which set `workflowError` — a field rendered ONLY on the
    // Workflows screen. So an invalid name closed the dialog, saved nothing,
    // and said nothing anywhere the user was looking. Reported from the phone
    // as "flows with invalid names don't save at all, nothing happens",
    // 2026-09-10.
    val why = validate(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.isBlank()) stringResource(R.string.save_workflow)
                else stringResource(R.string.save_workflow_named, initial)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                )
                // ⚠ The rule is stated BEFORE it is broken, not only after:
                // "letters, digits, spaces, - and _ only" is not guessable, and
                // a user who has just been refused wants to know what to type,
                // not what they typed.
                if (why != null && name.isNotBlank()) {
                    Text(
                        why,
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (initial.isNotBlank() && name.trim() == initial) {
                    Text(
                        stringResource(R.string.save_replaces),
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            // ⚠ Disabled while the name is refused, so "save" never does
            // nothing. A button that is enabled and silently no-ops is worse
            // than one that is visibly unavailable with the reason above it.
            Button(
                onClick = { onSave(name) },
                enabled = name.isNotBlank() && why == null,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RunBar(
    state: CanvasState,
    busy: Boolean,
    onRun: () -> Unit,
    onCancelRun: (() -> Unit)? = null,
    onBatch: () -> Unit,
    /** ⭐ Which run of how many, while a sweep is going. Null when it is not. */
    batchProgress: Pair<Int, Int>? = null,
    onCancelBatch: () -> Unit = {},
    armedSweeps: List<ArmedSweep> = emptyList(),
    onReleaseSweep: (ArmedSweep) -> Unit = {},
    onAdd: () -> Unit,
    onBack: () -> Unit,
    onToggleZoomLock: () -> Unit,
    onTogglePanLock: () -> Unit,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit,
    runError: String? = null,
    runLog: RunLogState = RunLogState(),
    onCloseRunLog: () -> Unit = {},
    /**
     * ⚠ Computed by the caller, which is the only scope with the run status and
     * an editor. This composable draws the bar; it does not know how a seed is
     * found or written.
     */
    seed: SeedState? = null,
    onToggleSeed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ⚠ The refusal from the last gesture, shown here rather than as a
        // transient toast. "That would make a loop" is a rule the user is
        // learning; a message that vanishes teaches nothing.
        // ⚠ Run failures first: they are the ones a user is waiting on.
        runError?.let {
            Text(
                it,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        state.message?.let {
            Text(
                it,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        // ⭐⭐ What is happening RIGHT NOW, directly above the button that
        // started it. ⚠ Below the errors and above the controls: a failure is
        // the more urgent thing to read, and Run must stay at the bottom edge
        // where the thumb already is.
        // ⭐⭐ The locked seed, wherever it is. ⚠ Read from the GRAPH rather
        // than remembered: the lock IS the sampler's `seed` param, so there is
        // one copy of the fact and no way for the badge and the render to
        // disagree.
        // ⚠ The FIRST sampler. A graph with two has two seeds and no single
        // answer; the row would then be lying about one of them, so it names
        // the one Run reaches first and leaves the rest to the inspector.
        RunLogPanel(
            runLog,
            onClose = onCloseRunLog,
            // ⚠⚠ Present whenever there IS a sampler, locked or not: a user
            // cannot tell "a new picture every Run" from "the same one" by
            // looking at the canvas, and that is the most confusing thing about
            // Run. `null` here means the graph has no sampler at all.
            seed = seed,
            onToggleSeed = onToggleSeed,
            // ⚠ The seed axis is pulled OUT of the chip row: it has its own
            // row already, and showing it twice would be two controls for one
            // fact.
            seedSweep = armedSweeps.firstOrNull { it.param == "seed" }?.count,
            onReleaseSeedSweep = {
                armedSweeps.firstOrNull { it.param == "seed" }?.let(onReleaseSweep)
            },
            armed = armedSweeps.filterNot { it.param == "seed" },
            onRelease = onReleaseSweep,
            batch = batchProgress,
            onCancelBatch = onCancelBatch,
        )

        // ⭐⭐ A CONTEXTUAL row while multi-select is on, replacing the normal
        // one rather than crowding beside it. Four controls already fill this
        // row on a phone; a fifth and a sixth would ellipsise the lot, and the
        // golden that caught exactly that is `screen-zoom-locked`.
        //
        // ⚠⚠ Keyed on the MODE, not on `selection.isNotEmpty()`. Two reasons,
        // and both were bugs: an ordinary tap used to select, so the run bar
        // vanished on every tap and the user was offered Delete when all they
        // did was open a node; and un-toggling the last selected node used to
        // put Run back while taps were still toggling — an invisible mode with
        // no way out, since the way out is the Done in this very row.
        if (state.multiSelect) {
            SelectionBar(
                count = state.selection.size,
                total = state.workflow.graph.nodes.size,
                onSelectAll = onSelectAll,
                onDelete = onDeleteSelected,
                onDone = onClearSelection,
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⭐⭐ Run BECOMES Cancel while a render is in flight, rather than
            // sitting there greyed out beside a new button.
            //
            // ⚠ A disabled Run was the only thing the bar said during a 24 s
            // SDXL sample, which reads as "the app is stuck" — and there was no
            // way to stop a render you had already decided was wrong.
            // ⚠⚠ Completed nodes keep their outputs, so this is cheap to press:
            // Run again resumes from the cache.
            if (busy && onCancelRun != null) {
                Button(
                    onClick = onCancelRun,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) { Text(stringResource(R.string.cancel_run), fontWeight = FontWeight.Medium) }
            } else {
                Button(
                    onClick = onRun,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(if (busy) R.string.running else R.string.run), fontWeight = FontWeight.Medium) }
            }
            // ⚠⚠ **No Batch button.** It opened a second way to build a sweep,
            // and there is only one now: arm a knob from its own node. The run
            // bar already says what is armed and Run already sweeps when it is
            // — a button that duplicated that was a third place to look.
            // Removed at the user's request, 2026-09-10.
            OutlinedButton(onClick = onAdd, shape = RoundedCornerShape(12.dp)) {
                Text(stringResource(R.string.add_node), fontSize = 12.sp)
            }
            Text(
                "${"%.1f".format(state.viewport.scale)}x",
                style = LogTextStyle,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // ⭐⭐ Two locks, because they solve different annoyances: one
            // keeps the graph the same SIZE while you work at it, the other
            // keeps it in the same PLACE. ⚠ Both are drawn rather than taken
            // from `material-icons-core`, which has `Lock` and no way to say
            // WHICH lock -- and no open-padlock at all, so an unlocked state
            // would have had to be a tint on an identical glyph.
            LockButton(
                mark = LockMark.ZOOM,
                locked = state.zoomLocked,
                onClick = onToggleZoomLock,
                what = "zoom",
            )
            LockButton(
                mark = LockMark.PAN,
                locked = state.panLocked,
                onClick = onTogglePanLock,
                what = "canvas movement",
            )
        }
    }
}

/** Which lock a padlock glyph stands for. */
private enum class LockMark { ZOOM, PAN }

/**
 * A padlock with a mark saying WHICH lock it is.
 *
 * ⚠⚠ Drawn rather than taken from `material-icons-core`. That set has exactly
 * one `Lock` and no `LockOpen` (the open one lives in `material-icons-extended`,
 * which costs ~55 MB of dex — `app/build.gradle.kts`), so two locks side by side
 * would have been the same glyph twice, and the unlocked state a tint on it. A
 * control whose meaning is carried entirely by colour is a control nobody reads.
 *
 * ⇒ The shackle OPENS when unlocked, which is the shape everyone already knows,
 * and the mark beside it says which one this is: a magnifier for zoom, a
 * four-way arrow for moving the canvas.
 */
@Composable
private fun LockButton(
    mark: LockMark,
    locked: Boolean,
    onClick: () -> Unit,
    /** For the screen reader, and for the state to be sayable at all. */
    what: String,
) {
    val tint =
        if (locked) CanvasColors.selectedStroke
        else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Canvas(
            Modifier
                .size(30.dp, 22.dp)
                .semantics {
                    contentDescription = if (locked) "$what is locked" else "lock $what"
                }
        ) {
            val h = size.height
            val stroke = Stroke(width = h * 0.11f)
            // --- the mark, on the left -------------------------------------
            val m = h * 0.36f
            val cx = h * 0.42f
            val cy = h * 0.52f
            when (mark) {
                LockMark.ZOOM -> {
                    drawCircle(tint, radius = m * 0.62f, center = Offset(cx, cy - m * 0.15f),
                        style = stroke)
                    drawLine(
                        tint,
                        Offset(cx + m * 0.42f, cy + m * 0.28f),
                        Offset(cx + m * 0.95f, cy + m * 0.85f),
                        strokeWidth = h * 0.11f,
                    )
                }
                LockMark.PAN -> {
                    // A four-way arrow: the universal "this moves".
                    drawLine(tint, Offset(cx - m, cy), Offset(cx + m, cy), strokeWidth = h * 0.1f)
                    drawLine(tint, Offset(cx, cy - m), Offset(cx, cy + m), strokeWidth = h * 0.1f)
                    val a = m * 0.42f
                    for (d in listOf(-1f, 1f)) {
                        drawLine(tint, Offset(cx + d * m, cy),
                            Offset(cx + d * (m - a), cy - a), strokeWidth = h * 0.1f)
                        drawLine(tint, Offset(cx + d * m, cy),
                            Offset(cx + d * (m - a), cy + a), strokeWidth = h * 0.1f)
                        drawLine(tint, Offset(cx, cy + d * m),
                            Offset(cx - a, cy + d * (m - a)), strokeWidth = h * 0.1f)
                        drawLine(tint, Offset(cx, cy + d * m),
                            Offset(cx + a, cy + d * (m - a)), strokeWidth = h * 0.1f)
                    }
                }
            }
            // --- the padlock, on the right ----------------------------------
            val bodyW = h * 0.52f
            val bodyH = h * 0.42f
            val left = size.width - bodyW
            val top = h - bodyH
            drawRoundRect(
                color = tint,
                topLeft = Offset(left, top),
                size = Size(bodyW, bodyH),
                cornerRadius = CornerRadius(h * 0.08f, h * 0.08f),
            )
            // ⚠ The shackle: closed sits centred over the body, open is shifted
            // and shortened on one leg -- the difference has to be a SHAPE, not
            // a colour.
            val r = bodyW * 0.34f
            val shackleX = left + bodyW / 2f + if (locked) 0f else r * 0.9f
            drawArc(
                color = tint,
                startAngle = 180f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(shackleX - r, top - r * 1.35f),
                size = Size(r * 2f, r * 2f),
                style = stroke,
            )
            drawLine(
                tint,
                Offset(shackleX - r, top - r * 0.35f),
                Offset(shackleX - r, top),
                strokeWidth = h * 0.11f,
            )
            if (locked) {
                drawLine(
                    tint,
                    Offset(shackleX + r, top - r * 0.35f),
                    Offset(shackleX + r, top),
                    strokeWidth = h * 0.11f,
                )
            }
        }
    }
}

/**
 * What the run bar becomes while nodes are selected.
 *
 * ⭐ Icons here and words elsewhere, deliberately: this is a contextual action
 * bar, where a glyph is the convention and the row is tightest. ⚠ Every one is
 * from `material-icons-core`, which material3 already brings in --
 * `material-icons-extended` is thousands of generated classes and cost 55 MB of
 * dex when it was last on the classpath (`app/build.gradle.kts`).
 */
@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$count of $total selected",
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // ⚠ Hidden once everything IS selected, rather than shown doing
        // nothing. A control that cannot change anything is noise.
        if (count < total) {
            OutlinedButton(
                onClick = onSelectAll,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
            ) { Text("All", fontSize = 12.sp) }
        }
        // ⚠ Disabled, not hidden, at zero: the row must not change shape as the
        // count crosses one, or the Done button moves under the finger reaching
        // for it.
        IconButton(onClick = onDelete, enabled = count > 0) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "delete the selected nodes",
                tint = if (count > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
        IconButton(onClick = onDone) {
            Icon(Icons.Filled.Close, contentDescription = "clear the selection")
        }
    }
}

/**
 * A picture, full screen, dismissed by tapping it.
 *
 * ⚠ Its own Box over everything rather than a Dialog: a Dialog gets its own
 * window and its own insets, and on an edge-to-edge screen that means a picture
 * inset by the status bar with black bars the user did not ask for.
 */
@Composable
private fun FullscreenImage(
    image: ImageBitmap,
    onDismiss: () -> Unit,
    /** The seed that made it, when a sampler upstream has one. */
    seed: String? = null,
    /** Non-null only for a picture the user CHOSE, i.e. a `load_image` node. */
    onPick: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    /** ⭐ Write this picture to the gallery. Non-null for a RENDER. */
    onSave: (() -> Unit)? = null,
    /** ⭐ Hand this picture to another app. Non-null wherever [onSave] is. */
    onShare: (() -> Unit)? = null,
    /** ⭐ Keep it in Results, with the graph that made it. */
    onKeep: (() -> Unit)? = null,
    /** ⭐ Whether this picture is already in Results — the star's amber/grey state. */
    kept: Boolean = false,
    /**
     * ⭐ Drop this render from the node that made it.
     *
     * ⚠ Confirmed, because there is no other way to clear an output and no undo
     * once it is gone.
     */
    onDeleteOutput: (() -> Unit)? = null,
    /**
     * ⭐⭐ Pin the seed that made this picture onto the sampler.
     *
     * ⚠ Null when there is nothing to pin, or when it is pinned already — the
     * action and its undo live in the run bar, not here, so that the LOCKED
     * state is visible without opening a picture.
     */
    onLockSeed: (() -> Unit)? = null,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    // ⚠ Passed in now — see the star below. A local latch could not express
    // un-starring, and could not know the picture was already kept.
    
    // ⚠⚠ BACK CLOSES THE VIEWER. Without this the system back went to the
    // activity, which has no back stack -- so the one gesture every Android user
    // makes to leave a fullscreen picture QUIT THE APP, losing the canvas
    // underneath it. Reported from the phone, 2026-09-09. ⚠ The viewer is a Box
    // in the same window, not a Dialog, so nothing handles it for us.
    BackHandler(onBack = onDismiss)

    // ⚠⚠ Zoom lives HERE, not in the canvas viewport: this is a picture being
    // looked at, and a 512-square render on a 1080-wide screen is drawn at 2x
    // already -- the detail a person wants to check is under the interpolation
    // until they can push in past it.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    // ⚠ Never further than the picture can travel while still covering the
    // frame. Un-clamped, a pinch flings the image off screen and the only way
    // back is to close the viewer -- which also throws away the zoom.
    fun clamp(o: Offset, sc: Float) = Offset(
        o.x.coerceIn(-box.width * (sc - 1f) / 2f, box.width * (sc - 1f) / 2f),
        o.y.coerceIn(-box.height * (sc - 1f) / 2f, box.height * (sc - 1f) / 2f),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 8f)
                    // ⚠⚠ The point under the fingers STAYS under the fingers.
                    // `graphicsLayer` scales about the centre, so zooming about
                    // a centroid means moving the layer by the difference --
                    // without this the picture zooms towards its middle and the
                    // detail being examined slides away from the pinch.
                    val v = Offset(centroid.x - box.width / 2f, centroid.y - box.height / 2f)
                    val moved = v - (v - offset) * (next / scale) + pan
                    scale = next
                    offset = clamp(moved, next)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // ⚠ Double tap is the fast way in and the fast way out. A
                    // pinch on a phone held one-handed is awkward, and this is
                    // the gesture every photo viewer already answers to.
                    onDoubleTap = { at ->
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero
                        } else {
                            scale = 3f
                            val v = Offset(at.x - box.width / 2f, at.y - box.height / 2f)
                            offset = clamp(v - v * 3f, 3f)
                        }
                    },
                    // ⚠⚠ …and a single tap closes ONLY at 1x. While zoomed, a
                    // tap is the end of a pan that moved less than the slop, and
                    // closing the viewer on it would throw away the zoom the
                    // user just set up -- the way to leave is back, or double
                    // tap out first.
                    onTap = { if (scale <= 1.01f) onDismiss() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = image,
            contentDescription = "the picture, full screen",
            // ⚠ Fit, never Crop: this is the one place the whole image must be
            // visible, and cropping here would hide the edges of what was made.
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ⚠ White rather than the theme's colours: this row sits on the
            // picture, not on a surface, and a primary-tinted icon over an
            // arbitrary photo is a coin toss for contrast.
            if (onPick != null && onClear != null) {
                ImageActions(onPick = onPick, onClear = onClear, tint = Color.White)
            }
            // ⭐ Keep it. ⚠ Confirms by CHANGING, not with a dialog: writing a
            // file is reversible from the gallery, so a modal would be in the
            // way of the one action people repeat.
            onSave?.let { save ->
                IconButton(onClick = { save(); saved = true }) {
                    Icon(
                        if (saved) Icons.Filled.Check else com.abrah.nightmare.ui.SaveIcon,
                        contentDescription = if (saved) "saved to the gallery" else "save to the gallery",
                        tint = Color.White,
                    )
                }
            }
            // ⭐ Share, next to Save — the two "send this picture somewhere"
            // actions belong together, and the order is the same everywhere:
            // destructive, save, share, keep, then the primary.
            onShare?.let { share ->
                IconButton(onClick = share) {
                    Icon(
                        com.abrah.nightmare.ui.ShareIcon,
                        contentDescription = "share this picture",
                        tint = Color.White,
                    )
                }
            }
            onKeep?.let { keep ->
                IconButton(onClick = keep) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription =
                            if (kept) "stop keeping this" else "keep this and its flow",
                        // ⚠⚠ The REAL kept state, passed in, not a local latch.
                        // This was `var kept` flipped to true on click: it never
                        // went back, so un-starring left a filled star, and
                        // reopening the viewer on an already-kept picture showed
                        // an empty one. Reported from the phone 2026-09-11.
                        tint = if (kept) com.abrah.nightmare.ui.StarKept
                        else com.abrah.nightmare.ui.StarIdle,
                    )
                }
            }
            onDeleteOutput?.let {
                IconButton(onClick = { confirmingDelete = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "clear this output",
                        tint = Color.White,
                    )
                }
            }
            // ⚠ The lock goes INSIDE the seed's own container, not in the
            // loose row above: a lock icon beside save and delete has no
            // visible subject.
            seed?.let { SeedRow(it, tint = Color.White, onLock = onLockSeed) }
            if (onPick == null && seed == null && onSave == null) {
                Text(
                    if (scale > 1.01f) "double tap to fit" else "tap to close",
                    style = LogTextStyle,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }

        // ⚠⚠ A confirm, because clearing an output cannot be undone and the
        // picture is often the only copy — the render is not written anywhere
        // until someone saves it.
        if (confirmingDelete && onDeleteOutput != null) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Clear this picture?") },
                text = {
                    Text(
                        "The node goes back to empty. " +
                            if (saved) {
                                "You saved it to the gallery, so that copy stays."
                            } else {
                                "It has NOT been saved to the gallery, and Run will " +
                                    "make a different one unless the seed is locked."
                            },
                        style = LogTextStyle,
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { confirmingDelete = false; onDeleteOutput() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Clear") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                },
            )
        }
    }
}
