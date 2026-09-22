package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.ui.LogTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture

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
    /** ⭐ What Add node offers — [types] less anything this phone cannot run (VideoGate). */
    paletteTypes: Map<String, NodeType> = types,
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
    /**
     * ⭐ What the save dialog offers when this flow has no name yet — the recipe
     * it came from plus the first free index, `t2i_01`.
     *
     * ⚠ A suggestion in an editable box, never a silent filename.
     */
    suggestedName: String = "",
    /** ⭐ Installed checkpoints as `id to label`, for a sampler's own picker. */
    installedModels: List<CheckpointChoice> = emptyList(),
    onSetModel: (String, String) -> Unit = { _, _ -> },
    /** ⭐ Backend relaunches this graph will cost, said before Run. */
    plannedLoads: Int = 0,
    /** ⭐ A family swap waiting on a yes, or null. */
    pendingSwap: com.abrah.nightmare.HarnessViewModel.ModelSwap? = null,
    /** @param takeRecipe / takePrompt — the two boxes in [ModelSwapDialog]. */
    onConfirmSwap: (
        com.abrah.nightmare.HarnessViewModel.ModelSwap, Boolean, Boolean,
    ) -> Unit = { _, _, _ -> },
    onCancelSwap: () -> Unit = {},
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
     * ⭐⭐ Set ONE node's render size.
     *
     * ⚠ Not an `onEdit` transform like [onSetParam] is: the size is checked
     * against the node's model and every derived picture is re-derived — which
     * a pure `CanvasState` edit cannot do. `HarnessViewModel.setNodeResolution`.
     */
    onSetResolution: (node: String, com.abrah.nightmare.Res) -> Unit = { _, _ -> },
    /** ⭐ The same for a fixed-canvas family, where the choice is a ratio. */
    onSetAspect: (String) -> Unit = {},
    onEditMask: (node: String, (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) -> Unit =
        { _, _ -> },
    /** ⭐ The mask editor's Tap tool — `HarnessViewModel.tapMask`. */
    onTapMask: (node: String, x: Float, y: Float, done: (String?) -> Unit) -> Unit =
        { _, _, _, done -> done(null) },
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
    /** ⭐ Switch which node the inspector shows — the node strip. */
    onInspectNode: (String) -> Unit = {},
    /** ⭐ Write a rendered image to the gallery. */
    onSaveImage: (String) -> Unit = {},
    /** ⭐ Hand a node's picture to another app. */
    onShareImage: (String) -> Unit = {},
    /** ⭐ Send a node's picture into a flow ([com.abrah.nightmare.ui.SendToDialog]). */
    onSendImage: (String) -> Unit = {},
    /** ⭐ Keep a rendered image AND the flow that made it, in Results. */
    onStarImage: (String) -> Unit = {},
    /** ⚠ Whether the picture behind an id is FLAGGED — the star's tint. */
    isFavourite: (String) -> Boolean = { false },
    /** ⚠ Non-null when this flow's autosave keeps every Run — the disk is dimmed. */
    keepDisabledReason: String? = null,
    onDisabledAction: ((String) -> Unit)? = null,
    onKeepImage: (String) -> Unit = {},
    /** ⭐ Is this picture already kept? Drives the star's filled/outline state. */
    isKept: (String) -> Boolean = { false },
    /** ⭐ Drop a render from the node that made it. */
    onClearOutput: (String) -> Unit = {},
    /**
     * ⭐⭐⭐ Enlarge what an output node made — it puts an upscale node into
     * the flow behind that output and Runs
     * (`HarnessViewModel.offerUpscaleNode`).
     */
    onUpscaleNode: ((String) -> Unit)? = null,
    /** ⭐⭐ What made this node's picture, for the ⓘ dialog. */
    detailsOfNode: ((String) -> List<Pair<String, String>>)? = null,
    /** ⭐⭐ Download the segmenter from an inpaint node's Tap-select row. */
    onInstallSegmenter: (() -> Unit)? = null,
    /** ⭐⭐ …and delete it from there. */
    onDeleteSegmenter: (() -> Unit)? = null,
    /** ⚠ Whether the segmenter weights are on the phone. */
    segmenterInstalled: Boolean = true,
    /** ⭐⭐ The Models tab's own row for the segmenter — see the inspector's copy. */
    segmenterRow: com.abrah.nightmare.ui.ToolRow? = null,
    onCancelSegmenter: (() -> Unit)? = null,
    /** ⭐⭐ Import a `.safetensors` adapter from inside a node's LoRA picker. */
    onImportLora: (() -> Unit)? = null,
    /** ⚠⚠ `HarnessViewModel.loraEpoch` — what re-reads `_loras` after an Add. */
    loraEpoch: Int = 0,
    /**
     * ⭐⭐ The build, drawn top right.
     *
     * ⚠⚠ A PARAMETER, not `BuildConfig.VERSION_NAME` read inside the body,
     * and that is the whole point: read inside, it bakes the current version
     * into every canvas golden, so seven of them break on every release for a
     * reason that has nothing to do with what they pin. The goldens pass a
     * fixed string; the app passes its own.
     */
    version: String = com.abrah.nightmare.BuildConfig.VERSION_NAME,
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
    // ⚠ Which node's ⓘ is open in the fullscreen viewer, or null.
    var showingNodeInfo by remember { mutableStateOf<String?>(null) }
    // ⚠ The add-node offer in flight: the plan and where the node will go.
    var assisting by remember { mutableStateOf<Pair<AddPlan, Pt>?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .background(CanvasColors.background)
            .onSizeChanged { canvasSize = it },
    ) {
        // ⭐⭐ **The loop's clock.** One ticker for the whole screen, driving both
        // the thumbnails on the canvas and the one in the open sheet.
        //
        // ⚠⚠ It only runs while a clip is actually on the canvas. A permanent
        // 12 Hz invalidation would redraw every node, every wire and the grid
        // forever on a graph that has no video in it at all — on battery, for
        // nothing. `state.videos.isEmpty()` is the whole condition.
        //
        // ⚠ `withFrameMillis` rather than `delay`: it ties the tick to the
        // display's own frame callback, so the loop pauses with the window
        // instead of waking a backgrounded app twelve times a second.
        // ⚠ The TERMINAL clips only -- see [clipNodes]. A sampler feeding an
        // output node draws its poster still and lets the end of the chain play.
        // ⚠ Remembered: this walks every node's inputs, and it is read from a
        // draw scope that runs at 12 Hz while a clip is on the canvas.
        val playing = remember(state.workflow.graph, state.videos) {
            clipNodes(state.workflow.graph, state.videos)
        }
        val animate = playing.isNotEmpty()
        var clipTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(animate) {
            if (!animate) return@LaunchedEffect
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (now - last >= 1000L / com.abrah.nightmare.ClipStore.FPS) {
                        last = now
                        clipTick++
                    }
                }
            }
        }

        // ⚠ Reads `clipTick` INSIDE the lambda, which is what makes the canvas
        // redraw — a draw scope records its state reads. Hoisting the frame out
        // here would animate nothing.
        val clipFrameFor: (String) -> androidx.compose.ui.graphics.ImageBitmap? = { id ->
            state.videos[id]?.takeIf { id in playing }?.let { path ->
                com.abrah.nightmare.ClipStore.get(path)?.let { f ->
                    f[clipTick % f.size]
                }
            }
        }

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
            beforePreviews = state.beforePreviews,
            imageFor = imageFor,
            clipFrameFor = clipFrameFor,
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

        // ⚠ The old floating corner preview is GONE. A picture in the corner
        // belongs to no node, so with more than one image node on a canvas it
        // could not say which had produced it -- and it covered whatever was
        // underneath. Previews are drawn ON the node that made them.

        // ⭐⭐⭐ **The brand, the version and the icons are INSIDE the card**,
        // with the destinations under them — the user's call, 2026-09-22.
        //
        // ⚠⚠⚠ **The icons VANISHED for one build and this is why.** They
        // were a sibling of the card in a `Row`, with no weight on either and a
        // weighted `Spacer` between. A Compose `Row` measures its UNWEIGHTED
        // children first, against the full width — so the card, whose width is
        // a flow name and a load line we do not choose, took everything and the
        // icons were measured at zero. On the golden's shorter strings there
        // was room left over, so nothing caught it. ⇒ Anything that must not be
        // squeezed out goes in the SAME container as what would squeeze it.
        //
        // ⚠⚠ The version was briefly a sibling OUTSIDE the card for the same
        // bad reason, which cost the card its full width. It belongs beside the
        // name (the user, 2026-09-22), so the card is simply the container
        // again and nothing sits next to it.
        TopBar(
            backendUp = backendUp,
            flowName = flowName,
            flowDirty = flowDirty,
            loadLine = loadLine,
            onModels = onModels,
            onResults = onResults,
            onWorkflows = onWorkflows,
            version = version,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding(),
            icons = {
                // ⭐ Save the canvas. ⚠ FIRST, i.e. leftmost of the three,
                // because it is the only one that acts on the graph rather than
                // opening something about the app.
                IconButton(onClick = { saving = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        com.abrah.nightmare.ui.SaveIcon,
                        contentDescription = "save this flow",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⭐ What this phone's NPU actually is — it answers the question
                // a failed render raises, and the arch/VTCM pair answers most.
                IconButton(onClick = onDeviceInfo, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(R.string.cd_device_info),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⚠⚠ A GEAR, and it opens Settings — not the wrench that opened
                // the op harness. ⚠⚠⚠ The harness is NOT behind Settings; it is
                // reachable only through `OpService` over adb
                // (`notes/HANDOFF.md` §5), and a run that refuses carries the
                // backend's own reason in its chip instead.
                IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.cd_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )

        RunBar(
            plannedLoads = plannedLoads,
            onResults = onResults,
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
            // ⚠ No confirm: a clone is undone by deleting it, which is the
            // control right beside it. `ConfirmDelete` is for what cannot be
            // taken back (`docs/UI.md` §8.2).
            onCloneSelected = { onEdit { s -> s.cloneSelected() } },
            onClearSelection = { onEdit { s -> s.clearSelection() } },
            runError = runError,
            runLog = runLog,
            onCloseRunLog = onCloseRunLog,
            // ⚠⚠ ANY sampler ([com.abrah.nightmare.SAMPLER_TYPES]), not just
            // `sd.sample`: the text-to-video recipe rolls a seed like every
            // other recipe and had no lock at all, so a clip worth keeping
            // could not be asked for again. Reported from the phone, 2026-09-12.
            // ⭐⭐ EVERY sampler, in graph order — one lock each (2026-09-17). The
            // label only appears when there is more than one to tell apart.
            seeds = state.workflow.graph.nodes.filter {
                com.abrah.nightmare.isSampler(it.type)
            }.let { samplers ->
                samplers.map { n ->
                    SeedState(
                        value = n.params["seed"]?.trim()?.takeIf { it.isNotEmpty() && it != "0" },
                        lastRolled = seedFor(state.workflow.graph, n.id) { status[it]?.detail },
                        nodeId = n.id,
                        label = n.id.takeIf { samplers.size > 1 },
                    )
                }
            },
            onToggleSeed = { id ->
                state.workflow.graph.byId[id]?.let { n ->
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
    // ⭐ The SAME dialog Results and the inspector open.
    showingNodeInfo?.let { n ->
        detailsOfNode?.let { d ->
            com.abrah.nightmare.ui.ResultInfoDialog(d(n)) { showingNodeInfo = null }
        }
    }
    NodeInspector(
        state = state,
        types = types,
        onUpscale = onUpscaleNode,
        detailsOf = detailsOfNode,
        onReset = { id -> onEdit { st -> st.resetNode(id, types) } },
        onInstallSegmenter = onInstallSegmenter,
        onDeleteSegmenter = onDeleteSegmenter,
        segmenterInstalled = segmenterInstalled,
        segmenterRow = segmenterRow,
        onCancelSegmenter = onCancelSegmenter,
        busy = busy,
        onInspectNode = onInspectNode,
        onImportLora = onImportLora,
        loraEpoch = loraEpoch,
        status = status,
        onClearImage = onClearImage,
        onSaveImage = onSaveImage,
        onShareImage = onShareImage,
        onSendImage = onSendImage,
        onKeepImage = onKeepImage,
        isKept = isKept,
        installedModels = installedModels,
        onSetModel = onSetModel,
        // ⚠⚠⚠ **All four, or the two surfaces disagree.** The inspector and the
        // fullscreen viewer draw the SAME [PictureActions]; these were wired
        // into the viewer and not here, so in the node view the star did nothing
        // and the save tick stayed live while autosave dimmed it one tap away.
        // Reported 2026-09-15 — *"the ticks in fullscreen view and node view
        // aren't same behavior"*. ⚠ The rule this broke is the oldest one in
        // `docs/ARCHITECTURE.md` §5.6: a rule honoured in N−1 of N places is a
        // bug, and the missed place is the one the user opens first.
        onStarImage = onStarImage,
        isFavourite = isFavourite,
        keepDisabledReason = keepDisabledReason,
        onDisabledAction = onDisabledAction,
        onClearOutput = onClearOutput,
        imageFor = imageFor,
        // ⚠ `s.editing` IS the node whose inspector is open, so the viewer
        // opened from it knows its node too — same reason the canvas tap does.
        onViewFullscreen = { id ->
            onEdit { s -> s.copy(editing = null, viewing = id, viewingNode = s.editing) }
        },
        onSetParam = { node, name, value -> onEdit { s -> s.setParam(node, name, value) } },
        onSetParams = { node, values -> onEdit { s -> s.setParams(node, values) } },
        onEditMask = onEditMask,
        onTapMask = onTapMask,
        onSetResolution = onSetResolution,
        onSetAspect = onSetAspect,
        // ⚠ A pure state edit, like every other canvas change: renameNode
        // rewires the graph and moves every id-keyed map with it.
        onRename = { from, to -> onEdit { s -> s.renameNode(from, to) } },
        onDelete = { id -> onEdit { s -> s.removeNode(id) } },
        onDismiss = { onEdit { s -> s.closeInspector() } },
    )

    // ⭐⭐⭐ **The checkpoint swap's question**, over the inspector it came from.
    //
    // ⚠⚠⚠ It did not exist until 2026-09-15, while `pendingSwap` had been a
    // parameter of this function for a day — declared, passed in from
    // `MainActivity`, and read by nothing. So a cross-family pick set the state
    // and drew no dialog: on the phone the picker simply did nothing. ⚠ A
    // parameter no line of the body mentions is dead wiring, and the compiler
    // says nothing about it; the golden below is what will notice next time.
    pendingSwap?.let { swap ->
        ModelSwapDialog(swap, onConfirmSwap, onCancelSwap)
    }

    // ⭐ Fullscreen. A 190-unit node preview is a thumbnail; this is where a
    // person actually looks at what they made.
    //
    // ⚠⚠ WHICH NODE is showing this picture, found by reversing `previews`
    // rather than carried in `viewing`. The viewer needs it for two things a
    // picture alone cannot answer -- whether this is a photo the user chose (so
    // it can be swapped or dropped) and which sampler made it (so it can show
    // the seed) -- and the reverse lookup keeps `viewing` a plain image id, so
    // nothing has to keep the two halves in step.
    // ⚠⚠ The node the TAP named, and only as a fallback the reverse lookup —
    // which is ambiguous whenever two nodes hold the same picture (see
    // [CanvasState.viewingNode]). The fallback covers a viewer opened from
    // somewhere that has no node, and nothing else.
    val viewedNode = state.viewingNode
        ?: state.viewing?.let { id ->
            state.previews.entries.firstOrNull { it.value.first == id }?.key
        }
    val viewedType = viewedNode?.let { state.workflow.graph.byId[it] }?.type
    val viewedIsPhoto = viewedType == "core.image"
    /**
     * ⭐⭐ A SAMPLER's picture is an INPUT, not a result — it is the framed
     * photo going in ([applyFramedPreviews]) — so the viewer offers nothing.
     *
     * ⚠⚠ Every action in that row acts on a RESULT: keep it, download it, share
     * it, delete it. Offering them over a crop preview means "delete" clears a
     * render that was never made and "keep" files an input as if it were
     * output. The user's call, 2026-09-15: *"in sample fullscreen view, don't
     * need any btns."*
     */
    // ⭐⭐⭐ **A RECEIVED picture is NOT an input** — reversed 2026-09-22 at
    // the user's ask: *"why the fuck dont the icons show in fullscreen"*.
    //
    // ⚠⚠ The rule used to add `beforePreviews[viewedNode] == viewing` here,
    // on the argument that the picture belongs to the node upstream. It does not
    // on the node that actually has one: an auto-upscaling `core.output` MADE
    // both halves in one run, so its Received picture is its own result at an
    // earlier stage — savable, sharable, keepable, and deletable. Every
    // callback below already binds to `id`, the picture being VIEWED, so they
    // act on the right one.
    val viewedIsInput = viewedIsPhoto || viewedType in com.abrah.nightmare.IMAGE_SAMPLER_TYPES

    // ⭐⭐ **The pictures this node holds, in the order they were made** — what
    // a swipe moves between. Received first, Made second; one entry for every
    // other node, which turns the pager off.
    val viewedPages: List<String> = viewedNode?.let { n ->
        listOfNotNull(
            state.beforePreviews[n]?.first,
            state.previews[n]?.first ?: state.rendered[n],
        ).distinct()
    }.orEmpty()
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
        onEdit { s -> s.copy(viewing = null, viewingNode = null) }
    }
    state.viewing?.let { id ->
        imageFor(id)?.let { bmp ->
            FullscreenImage(
                bmp,
                onDismiss = { onEdit { s -> s.copy(viewing = null, viewingNode = null) } },
                // ⭐⭐ The clip, when the node being viewed is the one that OWNS
                // it. ⚠ Found through `viewedNode` rather than carried in
                // `viewing`, for the reason the block above gives: `viewing`
                // stays a plain image id and nothing has to keep two halves in
                // step.
                //
                // ⚠⚠⚠ **Filtered by [clipNodes], exactly as the canvas and the
                // sheet are.** Without the filter, opening the SAMPLER full
                // screen played the finished video — and once Save and Share
                // learned about clips, the sampler shared it too. Reported from
                // the phone, 2026-09-13: *"i still don't get why the sample in
                // fullscreen/share is showing the video output"*. The `VIDEO`
                // value flows sampler → output so every node on the chain holds
                // the same path; `clipNodes` picking the END is the ONE rule
                // that decides what a node shows, saves and sends, and a
                // surface that opts out of it is a surface that disagrees with
                // the three that do.
                // ⚠⚠ Resolved from the image id AGAIN rather than from
                // `viewedNode`, and not for tidiness: several nodes share one
                // poster (the clip flows sampler → output and both record it),
                // so `viewedNode` is whichever the map yielded first and may
                // not be the one that owns the clip. Asking for the OWNER is
                // the same question `HarnessViewModel.clipForImage` asks, and
                // the two must agree or the button and the picture disagree.
                videoPath = clipNodes(state.workflow.graph, state.videos).let { owners ->
                    state.previews.entries
                        .filter { it.value.first == id }
                        .map { it.key }
                        .firstOrNull { it in owners }
                        ?.let { state.videos[it] }
                },
                seed = viewedNode?.let { n ->
                    seedFor(state.workflow.graph, n) { status[it]?.detail }
                },
                hasSampler = viewedNode?.let { samplerFor(state.workflow.graph, it) } != null,
                // ⚠ The SAMPLER only, not every input. A photo's viewer keeps
                // its pick and bin — choosing the picture IS what that viewer is
                // for. The sampler's preview is a derived crop with nothing to
                // act on.
                chromeless = viewedType in com.abrah.nightmare.IMAGE_SAMPLER_TYPES,
                onPick = if (viewedIsPhoto) pickForViewed else null,
                onClear = if (viewedIsPhoto && viewedNode != null) {
                    {
                        onClearImage(viewedNode)
                        onEdit { s -> s.copy(viewing = null, viewingNode = null) }
                    }
                } else null,
                // ⚠ Only for a RENDER. A photo the user chose is already in
                // their gallery, and offering to save it back would make a
                // second copy of a file they already have.
                onSave = if (!viewedIsInput) {
                    { onSaveImage(id) }
                } else null,
                // ⭐ Share, wherever Save is offered. ⚠ Including an UPSCALE's
                // output: the whole point of a 4x is sending it somewhere.
                onShare = if (!viewedIsInput) {
                    { onShareImage(id) }
                } else null,
                // ⭐ Send into a flow — the viewer closes, the choice pops up.
                onSendTo = if (!viewedIsInput) {
                    {
                        onEdit { s -> s.copy(viewing = null, viewingNode = null) }
                        onSendImage(id)
                    }
                } else null,
                // ⭐⭐ Keep it, WITH the graph. ⚠ Distinct from the gallery
                // button beside it and the difference is the whole point: the
                // gallery gets a picture, this gets a picture you can reopen as
                // a flow.
                onKeep = if (!viewedIsInput) {
                    { onKeepImage(id) }
                } else null,
                // ⭐ The STAR keeps it too — the difference is the flag Results
                // filters on ([PictureActions]).
                onStar = if (!viewedIsInput) {
                    { onStarImage(id) }
                } else null,
                kept = isKept(id),
                favourite = isFavourite(id),
                keepDisabledReason = keepDisabledReason,
                onDisabledAction = onDisabledAction,
                onDeleteOutput = if (!viewedIsInput && viewedNode != null) {
                    {
                        onClearImage(viewedNode)
                        onEdit { s -> s.copy(viewing = null, viewingNode = null) }
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
                // ⭐⭐ The same two the inspector's row offers — one picture,
                // one set of actions, whichever surface it is on.
                // ⚠ Only on an OUTPUT this node made, never on a photo the user
                // picked: there is no wire behind an input to trace.
                // ⚠⚠ **Dimmed, not gone**, when the node already auto-upscales
                // — the same rule and the same words the inspector's row uses.
                onUpscale = viewedNode
                    ?.takeIf { !viewedIsInput && onUpscaleNode != null }
                    ?.let { n -> { onUpscaleNode?.invoke(n) } },
                upscaleDisabledReason = viewedNode
                    ?.let { state.workflow.graph.byId[it] }
                    ?.takeIf { com.abrah.nightmare.MediaOutputNode.autoUpscales(it) }
                    ?.let { "this output already enlarges every render — untick auto upscale to choose" },
                onInfo = viewedNode
                    ?.takeIf { !viewedIsInput && detailsOfNode != null }
                    ?.let { n -> { showingNodeInfo = n } },
                // ⭐⭐ Received ⇄ Made, to swipe between while zoomed out.
                // ⚠ Only when every page has a bitmap: a pager with a hole in
                // it swipes to a black screen.
                pages = viewedPages.mapNotNull(imageFor).takeIf {
                    it.size == viewedPages.size && viewedPages.size > 1
                }.orEmpty(),
                pageIndex = viewedPages.indexOf(id).coerceAtLeast(0),
                // ⚠⚠ A swipe moves `viewing`, and `viewingNode` STAYS put —
                // it is the node whose pictures these are, and recomputing it
                // from the image id would be the ambiguous reverse lookup the
                // comment above warns about.
                onPage = { i ->
                    viewedPages.getOrNull(i)?.let { next ->
                        if (next != id) onEdit { st -> st.copy(viewing = next) }
                    }
                },
            )
        }
    }

    // ⭐⭐ A confirm before deleting nodes, because delete is the one canvas
    // action with no undo -- the wires that pointed at those nodes go with them.
    if (confirmingDelete && state.selection.isNotEmpty()) {
        ConfirmDeleteNodes(
            ids = state.selection.sorted(),
            onConfirm = { onEdit { s -> s.removeSelected() } },
            onDismiss = { confirmingDelete = false },
        )
    }

    if (saving) {
        SaveWorkflowDialog(
            initial = savedAs.orEmpty(),
            suggested = suggestedName,
            onDismiss = { saving = false },
            onSave = { saving = false; onSave(it) },
            validate = validateWorkflowName,
        )
    }

    if (state.showPalette) {
        NodePalette(
            types = paletteTypes,
            onPick = { type ->
                // The middle of what is on screen, in world units.
                val centre = state.viewport.forDevice(density).toWorld(
                    Pt(canvasSize.width / 2f, canvasSize.height / 2f)
                )
                // ⚠ Offset by half a node so the node is centred rather than
                // starting at the centre, which puts most of it off to one side.
                val at = Pt(centre.x - Sizes.NODE_WIDTH / 2f, centre.y - Sizes.HEADER_HEIGHT)
                // ⭐⭐⭐ **Offer the helpers and the snap before adding.** Asked
                // for 2026-09-22 — adding an inpaint node meant adding a prompt
                // and a segmenter by hand and then drawing three wires.
                // ⚠ A plan with nothing in it (a prompt, a photo: no inputs)
                // adds straight away, exactly as before. The sheet appears only
                // when there is something to decide.
                val plan = planAdd(type, state.workflow.graph, types)
                if (plan.isEmpty) {
                    onEdit { s -> s.addNode(type, at) }
                } else {
                    // ⚠ The palette closes first, or two sheets stack.
                    onEdit { s -> s.closePalette() }
                    assisting = plan to at
                }
            },
            onDismiss = { onEdit { s -> s.closePalette() } },
        )
    }
    assisting?.let { (plan, at) ->
        AddAssistSheet(
            plan = plan,
            onCancel = { assisting = null },
            onAdd = { helperPorts, snaps, splice, feeds ->
                assisting = null
                onEdit { s -> s.applyAdd(plan, at, helperPorts, snaps, splice, feeds) }
            },
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
    /**
     * ⭐⭐ The save / device / settings icons, drawn at the RIGHT END of the
     * brand row INSIDE this card (the user's call, 2026-09-22).
     *
     * ⚠⚠ A slot rather than three more callbacks, because what matters is
     * that they share a container with the brand: outside it they were a
     * sibling with no weight and got measured to zero width.
     */
    icons: @Composable RowScope.() -> Unit = {},
    /** ⭐ The build, beside the name — see [CanvasScreen.version]. */
    version: String = "",
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
            // ⚠⚠ The status-bar inset moved OUT to the header Column that now
            // owns this row and the icons — an inset applied twice in one
            // stack pushes the second thing down by the notch again
            // (`docs/UI.md` §7.2).
            //
            // ⚠⚠⚠ **12 + 4 = 16, which is what `LibraryScreen` pads to**, and
            // that is the whole reason for these numbers. Reported 2026-09-22:
            // *"the name and icon are slightly shifting when i go to models
            // view"*. They were 12+10 across and 6+4 down against the library's
            // flat 16, so the logo jumped a few dp in both axes on every trip
            // between the two screens — small enough to look like a rendering
            // fault rather than a layout one.
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
            // ⚠ A background, because the canvas scrolls UNDER this bar: the
            // default graph puts a node within a few dp of the top, and without
            // a ground the model name is drawn over the node's title.
            .clip(RoundedCornerShape(14.dp))
            .background(CanvasColors.nodeBody.copy(alpha = 0.92f))
            .padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // ⭐⭐⭐ **The brand, LEFT, in the position Models / Flows / Results
        // put it** — asked for so it does not jump when you switch screens —
        // and the icons at the right end of the same row.
        // ⚠ [com.abrah.nightmare.ui.BrandMark] is the ONE function all four
        // surfaces draw; a second copy of the gradient is a second copy that
        // drifts. ⚠ No ✕ here: the canvas is what the others close back to.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⭐⭐⭐ The mark, the name AND the version, all from the ONE
            // function the library screens draw ([com.abrah.nightmare.ui.BrandMark]).
            // The version used to be a second `Text` here, which is how two
            // surfaces start disagreeing about a `v` prefix.
            com.abrah.nightmare.ui.BrandMark(version = version)
            Spacer(Modifier.weight(1f))
            icons()
        }
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
    /** ⚠ Used only when [initial] is blank — a re-save keeps its own name. */
    suggested: String = "",
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    /**
     * ⭐⭐ Why this name will not do, or null. ⚠ Checked HERE so the dialog can
     * refuse and stay open.
     */
    validate: (String) -> String? = { null },
) {
    // ⚠ The suggestion only when there is nothing to keep: re-saving an
    // existing flow must offer ITS name, or Save quietly becomes Save As.
    //
    // ⭐⭐ A [TextFieldValue] with the caret at the END, and the keyboard up on
    // open. The user's call, 2026-09-15: the box arrives pre-filled with
    // `t2i_01`, and the next thing anyone does is type — so a dialog that
    // needs a tap to focus and another to reach the end of the word is two taps
    // of nothing.
    //
    // ⚠ The caret at the END rather than a full selection, unlike the node
    // rename: a suggested name is usually kept and EXTENDED ("t2i_01" →
    // "t2i_01 harbour"), where a node's existing id is usually replaced.
    val focus = remember { FocusRequester() }
    var field by remember {
        val text = initial.ifBlank { suggested }
        mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    }
    val name = field.text
    LaunchedEffect(Unit) { focus.requestFocus() }
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
                    value = field,
                    onValueChange = { field = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    modifier = Modifier.focusRequester(focus),
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
    /** ⚠ The SAME callback the top bar uses, so the run log's "in Results" row
     *  and the Results button cannot land anywhere different. */
    onResults: () -> Unit = {},
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
    /** ⭐⭐ Duplicate the selection — [CanvasState.cloneSelected]. */
    onCloneSelected: () -> Unit = {},
    runError: String? = null,
    runLog: RunLogState = RunLogState(),
    onCloseRunLog: () -> Unit = {},
    /**
     * ⚠ Computed by the caller, which is the only scope with the run status and
     * an editor. This composable draws the bar; it does not know how a seed is
     * found or written.
     */
    seeds: List<SeedState> = emptyList(),
    onToggleSeed: (String) -> Unit = {},
    /** ⭐ Backend relaunches this graph will cost. 0 on a single-checkpoint one. */
    plannedLoads: Int = 0,
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
        // ⚠ Both through [ErrorNotice]: a refused wire and a failed Run are the
        // same kind of news and were drawn two ways (`docs/UI.md` §8.5).
        runError?.let { com.abrah.nightmare.ui.ErrorNotice(it) }
        state.message?.let { com.abrah.nightmare.ui.ErrorNotice(it) }
        // ⭐⭐ What is happening RIGHT NOW, directly above the button that
        // started it. ⚠ Below the errors and above the controls: a failure is
        // the more urgent thing to read, and Run must stay at the bottom edge
        // where the thumb already is.
        // ⭐⭐ The locked seed, wherever it is. ⚠ Read from the GRAPH rather
        // than remembered: the lock IS the sampler's `seed` param, so there is
        // one copy of the fact and no way for the badge and the render to
        // disagree.
        // ⚠ One per sampler, named when there are several — see the caller.
        RunLogPanel(
            runLog,
            onClose = onCloseRunLog,
            // ⚠ The same callback the top bar's Results button uses, so the
            // two cannot land anywhere different.
            onResults = onResults,
            // ⚠⚠ Present whenever there IS a sampler, locked or not: a user
            // cannot tell "a new picture every Run" from "the same one" by
            // looking at the canvas, and that is the most confusing thing about
            // Run. `null` here means the graph has no sampler at all.
            seeds = seeds,
            onToggleSeed = onToggleSeed,
            // ⚠ The seed axis is pulled OUT of the chip row: it has its own
            // row already, and showing it twice would be two controls for one
            // fact. ⚠ Per sampler, on that sampler's own seed chip.
            seedSweeps = armedSweeps.filter { it.param == "seed" },
            onReleaseSeedSweep = onReleaseSweep,
            armed = armedSweeps.filterNot { it.param == "seed" },
            onRelease = onReleaseSweep,
            batch = batchProgress,
            onCancelBatch = onCancelBatch,
        )

        // ⭐⭐⭐ **What the relaunches will cost, BEFORE the button is pressed.**
        //
        // `docs/ARCHITECTURE.md` §4: a graph using two checkpoints is scheduled
        // rather than refused now, and each switch is a kill + relaunch of the
        // backend costing 2.3–5 s — about a whole render. ⚠ The user's own rule
        // for this feature: *no refusal and no silent reorder*, which only works
        // if the cost is visible while they can still change the graph.
        //
        // ⚠ Hidden at zero, which is every single-checkpoint graph: a row that
        // always says "0 model loads" is a row people stop reading.
        if (plannedLoads > 0 && !busy) {
            Text(
                // ⚠ ~3.5 s, the middle of the measured 2.3–5 s. Said as "about"
                // because it is a range and a precise-looking number would be a
                // promise the backend does not make.
                "$plannedLoads model load${if (plannedLoads == 1) "" else "s"} this run " +
                    "— about ${plannedLoads * 7 / 2} s of loading",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
            )
        }

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
                onClone = onCloneSelected,
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
            // ⭐⭐⭐ **Add node LEFT, Run CENTRED, the zoom and the locks
            // RIGHT** — the user's call, 2026-09-22. Run was leftmost, which put
            // the one button pressed every single time in the corner where a
            // right thumb has to reach furthest across the phone.
            //
            // ⚠⚠ Centred TRULY, not "centred if the two sides happen to
            // match": the sides are a 78dp button and a zoom readout beside two
            // padlocks, so `SpaceBetween` would have parked Run left of middle
            // and moved it whenever the scale went from `1.0x` to `0.35x`. Each
            // side takes `weight(1f)`, so Run sits on the bar's midpoint
            // whatever is beside it.
            // ⚠ Add node is drawn FIRST, in the left slot.
            // Removed at the user's request, 2026-09-10: the Batch button. It
            // opened a second way to build a sweep, and there is only one now
            // — arm a knob from its own node.
            Row(
                // ⚠⚠⚠ **`weight(1f)` here and NO weight on the right slot**, and
                // that pairing is the whole fix. Run is 60% wider now and the
                // pixels come out of the spacing rather than out of a button
                // (the user, 2026-09-22: *"you can accomodate the extra pixels
                // to the left side spacing (theres no strict centering rule)"*).
                //
                // ⚠⚠ It was `weight(0.55f)` against a weighted right slot for
                // one recording, and the arithmetic does not survive: the right
                // slot holds a zoom readout and two padlocks, all fixed size, so
                // weighting it hands it space it cannot use — and the left slot
                // was squeezed to ~96dp, which made **"+ Node" wrap onto two
                // lines**. The `screen-top-bar` golden caught it before it
                // shipped. ⇒ A slot whose content is fixed WRAPS; only the slot
                // that must absorb slack gets the weight.
                Modifier.weight(1f),
                // ⚠ Still centred WITHIN its slot, so the gap either side of
                // Add node stays even.
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onAdd, shape = RoundedCornerShape(12.dp)) {
                    // ⚠ One line, never wrapped. A two-line "+ Node" is what a
                    // squeezed slot looks like, and it should be visible as a
                    // layout problem rather than absorbed by the label.
                    Text(
                        stringResource(R.string.add_node),
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
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
                    // ⚠ The SAME width as Run — it replaces it in place, and a
                    // button that resizes as it changes job makes the bar jump
                    // mid-render.
                    modifier = Modifier.defaultMinSize(minWidth = RUN_WIDTH),
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
                    // ⭐⭐ **About 60% wider than it wraps to** — the user's call,
                    // 2026-09-22. It is the button pressed on every single
                    // render and it was the smallest thing on the bar.
                    modifier = Modifier.defaultMinSize(minWidth = RUN_WIDTH),
                ) { Text(stringResource(if (busy) R.string.running else R.string.run), fontWeight = FontWeight.Medium) }
            }
            // ⚠ The right slot: the zoom readout, then the two locks. It WRAPS
            // its content — see the note on the left slot; every item in here is
            // a fixed size, so a weight would only steal room from the one slot
            // that needs it.
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Text(
                "${"%.1f".format(state.viewport.scale)}x",
                style = LogTextStyle,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
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
}

/**
 * ⭐ How wide Run (and Cancel, which replaces it) is drawn.
 *
 * ⚠ A `defaultMinSize`, not a fixed width: a longer word in another locale
 * still grows the button rather than being clipped by it.
 */
private val RUN_WIDTH = 124.dp

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
    onClone: () -> Unit,
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
        // ⭐⭐ Clone — asked for 2026-09-22. ⚠ BEFORE the bin, because the row
        // is ordered by consequence and the destructive one comes last here
        // (this bar is not [PictureActions]; its Done sits at the end and the
        // bin beside it is the thing a finger must not find by accident).
        IconButton(onClick = onClone, enabled = count > 0) {
            Icon(
                com.abrah.nightmare.ui.CloneIcon,
                contentDescription = "duplicate the selected nodes",
                tint = if (count > 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
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
    /**
     * ⭐⭐ The MP4 to PLAY here instead of drawing [image], when this node made
     * a clip.
     *
     * ⚠⚠ [image] is still required and is still the poster: the file can be
     * gone (it lives in `cacheDir`, which Android may clear) and a viewer that
     * then showed nothing would be worse than one showing the first frame.
     * [com.abrah.nightmare.ui.ClipPlayer] draws nothing for a missing file, so
     * the still behind it is what remains.
     *
     * ⚠ Zoom is OFF while a clip is playing — see the gesture block below.
     */
    videoPath: String? = null,
    /** The seed that made it, when a sampler upstream has one. */
    seed: String? = null,
    /** ⚠ Whether a sampler is upstream at all — the row shows either way. */
    hasSampler: Boolean = false,
    /**
     * ⭐⭐ Nothing over the picture at all.
     *
     * ⚠ True for a viewer opened on an INPUT — a photo being framed, or a
     * sampler's framed preview. Every control up there acts on a RESULT, and
     * so does every readout: a seed, a "tap to close" hint and a bin all belong
     * to something the graph produced.
     */
    chromeless: Boolean = false,
    /** Non-null only for a picture the user CHOSE, i.e. a `load_image` node. */
    onPick: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    /** ⭐ Write this picture to the gallery. Non-null for a RENDER. */
    onSave: (() -> Unit)? = null,
    /** ⭐ Hand this picture to another app. Non-null wherever [onSave] is. */
    onShare: (() -> Unit)? = null,
    /** ⭐ Send it into a flow. Non-null wherever [onShare] is. */
    onSendTo: (() -> Unit)? = null,
    /** ⭐ Keep it in Results, with the graph that made it. */
    onKeep: (() -> Unit)? = null,
    /** ⭐ The STAR: keep it AND flag it Favourite. [PictureActions] has the table. */
    onStar: (() -> Unit)? = null,
    favourite: Boolean = false,
    /** ⚠ Non-null when the flow's autosave already keeps every Run — the disk is dimmed. */
    keepDisabledReason: String? = null,
    onDisabledAction: ((String) -> Unit)? = null,
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
    /** ⭐⭐ Enlarge this picture — see [PictureActions.onUpscale]. */
    onUpscale: (() -> Unit)? = null,
    /** ⭐⭐ Why it is dimmed — see [PictureActions.upscaleDisabledReason]. */
    upscaleDisabledReason: String? = null,
    /** ⭐⭐ ⓘ — see [PictureActions.onInfo]. */
    onInfo: (() -> Unit)? = null,
    /**
     * ⭐⭐⭐ **The other pictures on this node, to swipe between** — Received
     * then Made on an auto-upscaling output. Asked for 2026-09-22.
     *
     * ⚠ [image] is still the one being shown, and [pageIndex] says where it
     * sits in this list. A swipe calls [onPage], which moves `state.viewing` —
     * so every action above is rebound to the picture that is now on screen
     * rather than to the one the viewer opened on.
     *
     * ⚠⚠ Empty or one long = no pager, and the viewer behaves exactly as it
     * did. The swipe is disabled while zoomed, or a drag meant to pan would
     * flick to the next picture and throw the zoom away.
     */
    pages: List<androidx.compose.ui.graphics.ImageBitmap> = emptyList(),
    pageIndex: Int = 0,
    onPage: (Int) -> Unit = {},
) {
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
    // ⚠⚠ Held as STATE objects rather than read out with `by`: they are
    // handed to [viewerZoom], whose gesture lambda outlives the composition
    // that built it.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(Offset.Zero) }
    val scale by scaleState
    val offset by offsetState
    var box by remember { mutableStateOf(IntSize.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            .onSizeChanged { box = it }
            // ⚠⚠ **No pinch-zoom on a clip.** The player is a `SurfaceView`;
            // scaling one through `graphicsLayer` moves the frame and leaves
            // the video surface where it was, which draws the clip in the wrong
            // place at the wrong size with nothing on screen explaining it. The
            // picture keeps every gesture it had.
            //
            // ⚠⚠⚠ [viewerZoom], not `detectTransformGestures`: the latter
            // consumes every pointer event it sees, so the pager below never got
            // a drag and swiping between this node's pictures did nothing at
            // all. The Results viewer learned this on 2026-09-10; the gesture is
            // one function now so the next fix lands on both.
            .then(
                if (videoPath != null) Modifier
                else Modifier.viewerZoom(
                    key = pageIndex,
                    scale = scaleState,
                    offset = offsetState,
                    bounds = { box },
                    onDismiss = onDismiss,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // ⭐⭐ A clip PLAYS here; a picture is drawn. ⚠ Same box, same padding
        // and the same action row underneath, because it is the same viewer —
        // Save, Share, Keep and the seed all mean what they meant, and a
        // second fullscreen built for video would be a second place for them
        // to drift.
        if (videoPath != null) {
            com.abrah.nightmare.ui.ClipPlayer(
                path = videoPath,
                modifier = Modifier.fillMaxSize().padding(12.dp),
            )
        } else if (pages.size > 1) {
            // ⭐⭐⭐ **Swipe between this node's pictures** — Received ⇄ Made,
            // the user's call 2026-09-22: *"in fullscreen i want to swipe to
            // upscaled img and backwards when zoomed out"*.
            //
            // ⚠⚠ `userScrollEnabled` is the load-bearing half, exactly as in
            // the Results viewer: a horizontal drag on a ZOOMED picture has to
            // pan it, not flick to the next one, or examining an edge throws
            // away the zoom that was just set up.
            val pager = androidx.compose.foundation.pager.rememberPagerState(
                initialPage = pageIndex.coerceIn(0, pages.size - 1),
                pageCount = { pages.size },
            )
            // ⚠⚠ The swipe MOVES `state.viewing`, so the action row above is
            // rebound to the picture now on screen. Without this, Keep and the
            // bin would go on acting on whichever picture the viewer opened on.
            LaunchedEffect(pager.currentPage) { onPage(pager.currentPage) }
            // ⚠ A new picture starts unzoomed — carrying 3x onto the next one
            // shows a corner of something the user has not seen whole.
            LaunchedEffect(pager.currentPage) {
                scaleState.value = 1f
                offsetState.value = Offset.Zero
            }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= 1.01f,
            ) { page ->
                Image(
                    bitmap = pages[page],
                    contentDescription = "the picture, full screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .viewerPicture(
                            // ⚠ Only the page being LOOKED at carries the zoom.
                            // A neighbour peeking in at 3x is a smear.
                            if (page == pager.currentPage) scale else 1f,
                            if (page == pager.currentPage) offset else Offset.Zero,
                            chromeless = chromeless,
                        ),
                )
            }
        } else {
        Image(
            bitmap = image,
            contentDescription = "the picture, full screen",
            // ⚠ Fit, never Crop: this is the one place the whole image must be
            // visible, and cropping here would hide the edges of what was made.
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                // ⚠ Room above for the actions and below for the seed, so
                // neither sits ON the picture (2026-09-17, "seed below image"),
                // and a clip so a ZOOM cannot spend it — [viewerPicture].
                .viewerPicture(scale, offset, chromeless = chromeless),
        )
        }
        // ⚠⚠ The actions at the TOP, like the Results viewer's — which moved
        // there because the gesture bar and the swipe live along the bottom
        // edge, and that is just as true here. `docs/UI.md` §8.3.
        // ⚠⚠ A COLUMN, not one Row. The seed pill used to sit at the END of the
        // action row, and with five actions in front of it the pill ran off the
        // right edge — its copy and lock buttons were drawn OFF-SCREEN. Seen on
        // the phone 2026-09-15: the pill wrapped to two lines and neither button
        // was there. ⇒ Actions on one line, the seed on its own. There is no
        // shortage of vertical space over a full-screen picture and there is
        // plainly a shortage of horizontal.
        // ⚠⚠⚠ **ONE gate for the whole overlay.** Gating the BUTTONS on "this is
        // an input" and leaving the seed row and the hint ungated is how a
        // sampler's fullscreen still showed `seed random` and `tap to close`
        // after the buttons were removed — reported 2026-09-15, and it was the
        // same half-fix twice. A viewer over an INPUT shows the picture and
        // nothing else.
        // ⭐⭐ CENTRED, and the seed BELOW the picture (the user's call,
        // 2026-09-17): the row leaned right, and the seed sat in the action
        // band where it read as one more button.
        if (!chromeless) Row(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                // ⚠ `docs/UI.md` §7.2 — an inset is not padding.
                .padding(top = 32.dp, end = 8.dp, bottom = 8.dp, start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ⚠ White rather than the theme's colours: this row sits on the
            // picture, not on a surface, and a primary-tinted icon over an
            // arbitrary photo is a coin toss for contrast.
            if (onPick != null && onClear != null) {
                ImageActions(onPick = onPick, onClear = onClear, tint = Color.White)
            }
            // ⭐ The SAME row the inspector draws, in the same order.
            PictureActions(
                tint = Color.White,
                deleteTint = Color.White,
                isClip = videoPath != null,
                onDelete = onDeleteOutput,
                // ⚠ The disk KEEPS and the arrow DOWNLOADS since 2026-09-15;
                // `onSave` was always the gallery write, so it moved rather
                // than changed. [PictureActions] has the table.
                onKeep = onKeep,
                onDownload = onSave,
                onShare = onShare,
                onSendTo = onSendTo,
                onStar = onStar,
                kept = kept,
                favourite = favourite,
                keepDisabledReason = keepDisabledReason,
                onDisabledAction = onDisabledAction,
                starKeptTint = com.abrah.nightmare.ui.StarKept,
                starIdleTint = com.abrah.nightmare.ui.StarIdle,
                onUpscale = onUpscale,
                upscaleDisabledReason = upscaleDisabledReason,
                onInfo = onInfo,
            )
        }
        if (!chromeless) Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp, start = 8.dp, end = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ⚠ The lock goes INSIDE the seed's own container, not in the
            // loose row: a lock icon beside save and delete has no visible subject.
            // ⚠⚠ Shown whenever a SAMPLER is upstream, not only when a seed is
            // already known. [seedFor] returns null while the sampler still says
            // `0` ("roll a new one each Run") and nothing has rolled yet — so
            // before the first Run the row vanished entirely, and with it the
            // copy button. Reported 2026-09-15 as "seed doesn't have a copy btn
            // in fullscreen": the button was never missing, the whole row was.
            // ⭐⭐ The SIZE first, in its own pill, then the seed in its own —
            // asked for 2026-09-22. Two facts about different things, so two
            // containers ([SizePill]'s note).
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SizePill(image.width, image.height, tint = Color.White)
                if (seed != null) {
                    SeedRow(seed, tint = Color.White, onLock = onLockSeed)
                } else if (hasSampler) {
                    SeedRow(null, tint = Color.White, onLock = null)
                }
            }
            if (onPick == null && seed == null && onSave == null) {
                Text(
                    if (scale > 1.01f) "double tap to fit" else "tap to close",
                    style = LogTextStyle,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * ⭐ The band a fullscreen viewer keeps clear above (actions) and below (seed)
 * its picture. ⚠ Shared with `ResultViewer`, so both viewers frame alike.
 */
internal val VIEWER_CHROME = 96.dp

/**
 * ⭐⭐⭐ **The picture inside a fullscreen viewer** — the chrome band, the
 * zoom, and the CLIP that keeps the second inside the first.
 *
 * ⚠⚠⚠ Asked for 2026-09-22: *"in zoom views dont allow zoom to leak
 * towards the top and bottom icons/text, pls set boundary for it."* Both viewers
 * already reserved [VIEWER_CHROME] with `padding`, but `graphicsLayer` scales
 * the drawing AFTER layout — at 3x the picture simply drew over the action row
 * and the seed pill, which are the two things a person is reading while they
 * zoom. `clipToBounds` before the layer is what confines it; the padding alone
 * never could.
 *
 * ⚠⚠ ONE function, both viewers (`FullscreenImage` here and
 * `ResultViewer`). They had two copies of the same four lines and the comment in
 * the second already claimed it matched the first — the horizontal inset did
 * not (8 vs 12), so they were unified here rather than left to drift again.
 */
/**
 * ⭐⭐⭐ **The zoom gesture a fullscreen viewer answers to** — one definition,
 * both viewers.
 *
 * Pinch to zoom about the point under the fingers, drag to pan once zoomed,
 * double tap in and out, single tap to close at 1x only.
 *
 * ⚠⚠⚠ **It consumes ONLY when the gesture is really ours** — two fingers
 * down, or already zoomed in. `detectTransformGestures` consumes every event it
 * sees, so a one-finger drag at 1x never reached the pager underneath and
 * swiping between pictures did nothing at all. Reported from the phone
 * 2026-09-10 against the Results viewer, and the canvas viewer had the same
 * `detectTransformGestures` until a pager was put under it too (2026-09-22).
 *
 * ⚠⚠ [scale] and [offset] are passed as STATE, not as values: the gesture
 * lambda outlives the composition that created it (`pointerInput` is keyed on
 * [key]), so a captured `Float` would be the zoom level from whenever the
 * pointer handler last restarted.
 *
 * @param bounds the viewer's size, for [clampOffset]. `IntSize.Zero` disables
 *   clamping — measure the box and pass it, or a fling puts the picture off
 *   screen with no way back but closing the viewer.
 */
internal fun Modifier.viewerZoom(
    key: Any?,
    scale: androidx.compose.runtime.MutableState<Float>,
    offset: androidx.compose.runtime.MutableState<Offset>,
    bounds: () -> IntSize,
    onDismiss: () -> Unit,
): Modifier {
    fun clampOffset(o: Offset, sc: Float): Offset {
        val b = bounds()
        if (b.width == 0 || b.height == 0) return o
        return Offset(
            o.x.coerceIn(-b.width * (sc - 1f) / 2f, b.width * (sc - 1f) / 2f),
            o.y.coerceIn(-b.height * (sc - 1f) / 2f, b.height * (sc - 1f) / 2f),
        )
    }
    return this
        .pointerInput(key) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    val fingers = event.changes.count { it.pressed }
                    if (fingers > 1 || scale.value > 1.01f) {
                        val next = (scale.value * event.calculateZoom()).coerceIn(1f, 8f)
                        val pan = event.calculatePan()
                        val centroid = event.calculateCentroid(useCurrent = false)
                        val b = bounds()
                        // ⚠⚠ The point under the fingers STAYS under the fingers.
                        // `graphicsLayer` scales about the centre, so zooming
                        // about a centroid means moving the layer by the
                        // difference — without it the picture zooms towards its
                        // middle and the detail being examined slides away.
                        val v = Offset(centroid.x - b.width / 2f, centroid.y - b.height / 2f)
                        val moved =
                            if (next != scale.value && b.width > 0) {
                                v - (v - offset.value) * (next / scale.value) + pan
                            } else {
                                offset.value + pan
                            }
                        scale.value = next
                        // ⚠ Snapping home at 1x: a pan that leaves the picture
                        // off-centre at rest is a viewer needing tidying before
                        // it can be read.
                        offset.value = if (next <= 1.01f) Offset.Zero else clampOffset(moved, next)
                        // ⚠ Only the changes that actually MOVED. Consuming a
                        // bare down would eat the tap that closes the viewer.
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
        .pointerInput(key) {
            detectTapGestures(
                // ⚠ Double tap is the fast way in and the fast way out. A pinch
                // on a phone held one-handed is awkward, and this is the gesture
                // every photo viewer already answers to.
                onDoubleTap = { at ->
                    if (scale.value > 1.01f) {
                        scale.value = 1f
                        offset.value = Offset.Zero
                    } else {
                        val b = bounds()
                        scale.value = 3f
                        val v = Offset(at.x - b.width / 2f, at.y - b.height / 2f)
                        offset.value = clampOffset(v - v * 3f, 3f)
                    }
                },
                // ⚠⚠ A single tap closes ONLY at 1x. While zoomed a tap is the
                // end of a pan that moved less than the slop, and closing on it
                // would throw away the zoom the user just set up — the way out
                // is back, or double tap out first.
                onTap = { if (scale.value <= 1.01f) onDismiss() },
            )
        }
}

internal fun Modifier.viewerPicture(
    scale: Float,
    offset: Offset,
    /** ⚠ A viewer over an INPUT draws no chrome, so it keeps no band for one. */
    chromeless: Boolean = false,
): Modifier = this
    .padding(
        horizontal = 12.dp,
        vertical = if (chromeless) 12.dp else VIEWER_CHROME,
    )
    .clipToBounds()
    .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
    }

/**
 * ⭐⭐ THE confirm for deleting nodes — from the run bar's selection and from the
 * inspector alike.
 *
 * ⚠⚠ They were two dialogs, titled `Delete this node?` and `Delete "id"?`, while
 * the inspector's comment claimed they matched. The design review, 2026-09-15.
 * ⚠ It NAMES them: the dialog covers the canvas, so "Delete 3 nodes?" is a
 * question the user cannot check.
 */
@Composable
internal fun ConfirmDeleteNodes(ids: List<String>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    com.abrah.nightmare.ui.ConfirmDelete(
        title = if (ids.size == 1) "Delete \"${ids[0]}\"?" else "Delete ${ids.size} nodes?",
        body = (if (ids.size == 1) "" else ids.joinToString(", ") + "\n\n") +
            "Every wire into or out of " + (if (ids.size == 1) "it" else "them") +
            " goes too, and this cannot be undone.",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/**
 * ⭐⭐⭐ **Point one sampler at another checkpoint, and say what else moves.**
 *
 * ⚠⚠ **Two radio groups, not two checkboxes**, and the difference is the
 * DEFAULT. A checkbox's unticked state is "no", so the safe default was "take
 * nothing" — which made the recommended answer the one that needed two extra
 * taps, and left the dialog saying nothing about which answer is usually right.
 * A radio pair states both answers and can recommend one. The user's call,
 * 2026-09-15: *"the options can be radio: Use model prompts (recommended)
 * (default) and use same prompt. and one more radio set for other params"*.
 *
 * ⚠⚠⚠ **Recommended means DEFAULT here, or the word is decoration.** Both
 * groups therefore default to the checkpoint's own values, which is also what
 * the top-bar picker already does (`modelRecipeRetarget`, `modelPromptRetarget`)
 * — asking for a checkpoint is asking for its published recipe. ⚠ This reverses
 * the "off by default" note of earlier the same day, and it is safe to reverse
 * precisely because the dialog now SHOWS the text it would write: the argument
 * for defaulting to "keep mine" was that a silent overwrite of a typed sentence
 * is the worst trade in the app, and nothing here is silent.
 *
 * ⚠ A family change is stated as a fact rather than a warning — every wire
 * survives it, because the two sampler types declare the same ports
 * (`docs/ARCHITECTURE.md` §5.7). What DOES change is the render size, and that
 * is the half worth saying.
 */
@Composable
private fun ModelSwapDialog(
    swap: com.abrah.nightmare.HarnessViewModel.ModelSwap,
    onConfirm: (com.abrah.nightmare.HarnessViewModel.ModelSwap, Boolean, Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    // ⭐ Seeded with the user's LAST answers ([HarnessViewModel.confirmSwap]).
    var takePrompt by remember(swap) { mutableStateOf(swap.takePromptDefault) }
    var takeRecipe by remember(swap) { mutableStateOf(swap.takeRecipeDefault) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Switch to ${swap.spec.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                swap.fromFamily?.let { from ->
                    Text(
                        "${swap.spec.label} is ${swap.spec.family.label}, not ${from.label}. " +
                            "The sampler changes family — every wire is kept — and it " +
                            "renders at ${swap.spec.native}.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                swap.promptNode?.let { id ->
                    SwapChoice(
                        heading = "Prompt on “$id”",
                        takeTheirs = takePrompt,
                        onChange = { takePrompt = it },
                        theirs = "Use ${swap.spec.label}’s prompts (recommended)",
                        // ⚠ Both fields, because the negative is half of a
                        // checkpoint's style and is the one nobody re-reads.
                        detail = swap.prompt + "\n— " + swap.negative.ifBlank { "no negative" },
                        mine = "Keep the prompt I have",
                    )
                }
                swap.recipe?.let { r ->
                    SwapChoice(
                        heading = "Sampling settings",
                        takeTheirs = takeRecipe,
                        onChange = { takeRecipe = it },
                        theirs = "Use ${swap.spec.label}’s settings (recommended)",
                        detail = r,
                        mine = "Keep my steps, CFG and scheduler",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(swap, takeRecipe, takePrompt) }) { Text("Switch") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * One either/or: take the checkpoint's value, or keep the one on the graph.
 *
 * ⚠ The whole row is the target, not just the button — a 20dp radio is below
 * the 48dp minimum and the label beside it is what a finger aims at.
 */
@Composable
private fun SwapChoice(
    heading: String,
    takeTheirs: Boolean,
    onChange: (Boolean) -> Unit,
    theirs: String,
    detail: String,
    mine: String,
) {
    Column {
        Text(
            heading,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwapRadio(selected = takeTheirs, onClick = { onChange(true) }, label = theirs, detail = detail)
        SwapRadio(selected = !takeTheirs, onClick = { onChange(false) }, label = mine, detail = null)
    }
}

@Composable
private fun SwapRadio(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    detail: String?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    it,
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
