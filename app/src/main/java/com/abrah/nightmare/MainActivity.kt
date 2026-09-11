package com.abrah.nightmare

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.abrah.nightmare.canvas.CanvasScreen
import com.abrah.nightmare.ui.LogTextStyle
import com.abrah.nightmare.ui.NightmareTheme
import com.abrah.nightmare.ui.ScreenHeader
import com.abrah.nightmare.ui.DeviceSheet
import com.abrah.nightmare.ui.LibraryScreen
import com.abrah.nightmare.ui.ModelsScreen
import com.abrah.nightmare.ui.WorkflowsScreen

/** What the header shows: the build that produced this APK. */
val HARNESS_VERSION: String =
    "harness " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"

class MainActivity : ComponentActivity() {

    /**
     * An op requested by intent, and a counter so the SAME op twice in a row
     * still fires twice.
     *
     * ⚠ This exists because driving the harness by tapping is genuinely unsafe:
     * the phone is in someone's hand, and a tap aimed at a screenshot taken
     * seconds ago lands wherever the screen has moved on to -- once, into the
     * user's Telegram. `mCurrentFocus` must be re-checked before every tap, and
     * the only way to make that rule cheap is to not need taps. See
     * notes/HANDOFF.md section 5.
     */
    private var pending by mutableStateOf<String?>(null)
    private var pendingNonce by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ⚠ Before anything composes or any op runs: NODE_TYPES reads
        // SelectedModel for its `model` default, and the backend launches
        // against it. Loading late would render the first graph against the
        // previous selection.
        SelectedModel.load(this)
        // ⚠ Before NODE_TYPES is touched: the upscale node's default and its
        // dropdown read this cache, and an empty one makes a new node default to
        // an upscaler that may not be installed.
        UpscalerCatalog.refresh(this)
        // ⚠ Before setContent: the theme decides the FIRST frame, and loading it
        // afterwards means a flash of the wrong palette on every cold start.
        Prefs.load(this)
        takeOp(intent)
        setContent {
            // ⚠⚠ Read from the view model, not from `Prefs` directly: the object
            // is a plain singleton with no Compose state, so a write to it would
            // change the value and recompose nothing.
            val vm: HarnessViewModel = viewModel()
            NightmareTheme(
                darkTheme = when (vm.theme) {
                    Prefs.Theme.DARK -> true
                    Prefs.Theme.LIGHT -> false
                    Prefs.Theme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                }
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HarnessScreen(op = pending, nonce = pendingNonce, vm = vm)
                }
            }
        }
    }

    /**
     * ⚠ Required, not belt-and-braces. `am start` on an activity that is already
     * running delivers here and NOT to onCreate, so without this the second
     * scripted op of a session is silently dropped -- which looks exactly like
     * an op that ran and did nothing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeOp(intent)
    }

    private fun takeOp(intent: Intent?) {
        val op = intent?.getStringExtra(EXTRA_OP) ?: return
        pending = op
        pendingNonce++
    }

    companion object {
        /**
         * `adb shell am start -n com.abrah.nightmare/.MainActivity --es op start`
         *
         * Ops: start, stop, health, encode_text, vae_decode, sample, graph.
         */
        const val EXTRA_OP = "op"
    }
}

@Composable
fun HarnessScreen(
    op: String? = null,
    nonce: Int = 0,
    vm: HarnessViewModel = viewModel(),
) {
    // One probe on open. A harness that makes you press a button to learn
    // whether anything is running wastes the first second of every session.
    // ⚠ Skipped when this composition was started BY an op. Both effects run on
    // first composition, checkBackend() takes the view model's `busy` latch
    // first, and runOp then loses it -- the op is dropped with "already
    // running" and the harness looks like it ignored the intent. Measured
    // 2026-09-08: this is exactly what "start backend ignored" was. An op
    // supersedes the opening probe rather than racing it.
    LaunchedEffect(Unit) { if (nonce == 0 || op == null) vm.checkBackend() }
    // Keyed on the nonce, not the op: the same op asked for twice is two
    // requests, and a LaunchedEffect keyed on the string alone would run once.
    LaunchedEffect(nonce) { if (nonce > 0 && op != null) vm.runOp(op) }

    // ⚠ Two screens behind one switch rather than two activities: the canvas
    // and the harness share a view model, and therefore one executor, one image
    // store and one backend. Two activities would mean two of each, and a graph
    // run from the canvas would not be cached for the harness or the reverse.
    // ⚠ Loaded when the canvas is first shown, not at app start: reading the
    // file needs the node types, and resolving those builds the QuickJS runtime.
    LaunchedEffect(vm.showCanvas) { if (vm.showCanvas) vm.restoreWorkflow() }

    // ⭐⭐ The load readout, polled while the canvas is up.
    //
    // ⚠⚠ POLLED rather than pushed, because the number worth seeing is the one
    // DURING a render — free RAM falls as the UNet stages in, and that is the
    // whole point of showing it. A value refreshed only on backend up/down
    // would sit still through the twenty-four seconds it is describing.
    //
    // ⚠ Every 2 s, and it is one `ActivityManager.getMemoryInfo` plus a
    // `File.length()`. ⚠ Stops when the canvas is not showing, so it costs
    // nothing behind the library or in the background.
    LaunchedEffect(vm.showCanvas) {
        while (vm.showCanvas) {
            vm.refreshLoad()
            kotlinx.coroutines.delay(2_000)
        }
    }

    // ⭐⭐ Opening a flow over an unsaved one ASKS. `HarnessViewModel.ActiveFlow`
    // has the reasoning; the short version is that this used to replace the
    // canvas silently and rely on a 150 ms autosave debounce having fired.
    // ⚠ Mid-render is refused in the view model rather than offered here — no
    // answer to a dialog makes swapping the graph under a running graph safe.
    vm.pendingOpen?.let { p ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = vm::dismissPendingOpen,
            title = { Text("Open \"" + p.label + "\"?") },
            text = {
                Text(
                    "The flow on the canvas has unsaved edits. Opening this one " +
                        "replaces it, and the edits are gone."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = vm::confirmPendingOpen) {
                    Text("Open anyway")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = vm::dismissPendingOpen) {
                    Text("Keep editing")
                }
            },
        )
    }

    // ⚠ Checked BEFORE the canvas: the models screen is reachable from both,
    // and a user who has no model at all needs it before either is any use.
    // ⭐⭐ ONE library screen, two tabs. Models and Flows were separate full
    // screens reached from separate buttons, so moving between them cost three
    // taps -- and they are the same question asked twice.
    if (vm.libraryOpen) {
        // ⚠⚠ Every screen the app puts OVER the canvas handles back itself, or
        // the gesture leaves the app entirely -- there is one activity and no
        // back stack, so the system's default is "finish". Found on the
        // fullscreen viewer, 2026-09-09; these two had it just as badly.
        BackHandler { vm.closeLibrary() }
        LibraryScreen(
            tab = vm.libraryTab,
            onTab = vm::switchLibraryTab,
            onClose = { vm.closeLibrary() },
            models = {
                // ⭐ The zip picker for an imported checkpoint.
                //
                // ⚠⚠ The NAME is captured before the picker opens and held
                // here, because `rememberLauncherForActivityResult` hands back
                // only a `Uri` — there is no way to pass a payload through the
                // round trip, and the activity can be recreated during it.
                // ⚠ `remember` and not a local `var`: this composable
                // recomposes while the picker is up.
                var importName by remember { mutableStateOf("") }
                // ⚠ `OpenDocument` rather than `GetContent`: it returns a
                // durable, re-readable Uri. `GetContent` can hand back one that
                // is already gone by the time a gigabyte has finished copying.
                val picker = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    // ⚠ Null is a CANCEL, not a failure. Saying nothing is right.
                    if (uri != null && importName.isNotEmpty()) {
                        vm.importModel(uri, importName)
                    }
                }
                ModelsScreen(
                    rows = vm.modelRows,
                    busy = vm.busy,
                    error = vm.modelError,
                    onInstall = vm::installModel,
                    onCancel = vm::cancelModelInstall,
                    onDelete = vm::deleteModel,
                    onSelect = vm::selectModel,
                    importing = vm.importing,
                    importProgress = vm.importProgress,
                    onImport = { name ->
                        importName = name
                        // ⚠ Two MIME types. A zip arrives as
                        // `application/octet-stream` from plenty of providers
                        // (Downloads especially), and filtering on
                        // `application/zip` alone greys out the file the user
                        // came to pick, with nothing on screen explaining why.
                        picker.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    upscalers = vm.upscalerRows,
                    onInstallUpscaler = vm::installUpscaler,
                    onDeleteUpscaler = vm::deleteUpscaler,
                )
            },
            results = {
                com.abrah.nightmare.ui.ResultsScreen(
                    groups = vm.keptGroups,
                    results = vm.kept,
                    thumbnailFor = vm::thumbnailFor,
                    onOpenFlow = { vm.openResultFlow(it.id) },
                    onView = { vm.viewResult(it) },
                    onDelete = { vm.deleteResult(it.id) },
                    onDiskBytes = vm.keptBytes,
                    selected = vm.selectedResults,
                    onToggleSelect = { vm.toggleResultSelected(it.id) },
                    onSelectAll = vm::toggleSelectAllResults,
                    onClearSelection = vm::clearResultSelection,
                    onDeleteSelected = vm::deleteSelectedResults,
                    onDeleteGroup = vm::deleteResultGroup,
                    onSaveSelected = vm::saveSelectedResults,
                    onSave = { vm.saveResultsToGallery(listOf(it.id)) },
                    onSaveGroup = { g -> vm.saveResultsToGallery(g.items.map { it.id }) },
                    onShareFlow = { vm.shareResultFlow(it.id) },
                )
            },
            flows = {
                // ⚠ Two pickers, because the two imports accept different
                // things and a single launcher would have to guess from the
                // extension — which is exactly how a .zip picked as a flow
                // becomes "could not import that file".
                val flowPicker = rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                ) { uri -> if (uri != null) vm.importWorkflow(uri) }
                WorkflowsScreen(
                    recipes = com.abrah.nightmare.canvas.RECIPES,
                    saved = vm.savedWorkflows,
                    error = vm.workflowError,
                    onOpenRecipe = { vm.openWorkflow(it.build()) },
                    onOpenSaved = vm::openSaved,
                    onDeleteSaved = vm::deleteSaved,
                    onRenameSaved = vm::renameWorkflow,
                    onShareSaved = vm::shareSavedWorkflow,
                    // ⚠ `application/octet-stream` alongside the real type, for
                    // the reason the model importer documents: plenty of
                    // providers hand a file over as octet-stream, and filtering
                    // on the exact type greys out the file the user came to pick
                    // with nothing on screen explaining why.
                    onImportFlow = {
                        flowPicker.launch(arrayOf("application/json", "application/octet-stream"))
                    },
                )
            },
        )
        // ⭐ A kept picture full screen, over the library. ⚠ Inside the
        // library branch, because that is where it is opened from and back
        // must return to the list rather than to the canvas.
        vm.viewingResult?.let { _ ->
            if (vm.viewingSet.isNotEmpty()) {
                BackHandler { vm.closeResult() }
                com.abrah.nightmare.ui.ResultViewer(
                    items = vm.viewingSet,
                    startIndex = vm.viewingIndex,
                    // ⚠ The FULL picture, not the list thumbnail: this is the
                    // surface where the detail is the point.
                    imageFor = { id -> vm.resultImage(id) },
                    detailsFor = vm::detailsOf,
                    onDismiss = { vm.closeResult() },
                    onOpenFlow = { r -> vm.closeResult(); vm.openResultFlow(r.id) },
                    onDelete = { r -> vm.deleteResult(r.id) },
                    onSave = { r -> vm.saveResultsToGallery(listOf(r.id)) },
                    onShare = { r -> vm.shareResultImage(r.id) },
                )
            }
        }
        return
    }

    if (vm.showCanvas) {
        // ⭐⭐ Every picture the graph can make without the NPU, kept current
        // as the user works -- the chosen photo on `load_image`, the framed one
        // on `crop`. ⚠ The trigger lives in `HarnessViewModel.updateCanvas`
        // rather than in a `LaunchedEffect` here, because a canvas edit can
        // arrive from the headless ops too, and a preview that was only correct
        // when a screen happened to be composed would be a second answer to
        // "what does this node hold".
        CanvasScreen(
            state = vm.canvas,
            types = vm.nodeTypes,
            status = vm.canvasStatus,
            busy = vm.busy,
            image = vm.image,
            onGesture = vm::updateCanvas,
            onRun = vm::runCanvasOrBatch,
            onBatch = vm::openBatch,
            batchProgress = vm.batchProgress,
            onCancelBatch = vm::cancelBatchRun,
            armedSweeps = vm.armedSweeps,
            onReleaseSweep = vm::releaseSweep,
            onBack = { vm.setCanvasVisible(false) },
            runError = vm.runError,
            runLog = vm.runLog,
            onCloseRunLog = vm::clearRunLog,
            modelLabel = vm.modelLabel,
            backendUp = vm.backend == BackendState.UP,
            // ⚠ A flow opened from Results has a name now, so the run bar
            // stops reading "unsaved flow" for a graph that plainly came from
            // somewhere. Saved name first, then the result's, then neither.
            flowName = vm.activeFlow.name ?: vm.openedResultName ?: "unsaved flow",
            flowDirty = vm.activeFlow.dirty,
            loadLine = vm.load?.let { l ->
                // ⚠⚠ Formatted HERE rather than in the view model: the STRING is
                // presentation, the numbers are not.
                //
                // ⚠ This shipped once as a literal "holding ${'$'}it · ${'$'}free/${'$'}total GB
                // free" on the phone — over-escaped in the edit that wrote it,
                // so Kotlin saw the dollar signs as text. A template that
                // renders its own placeholders is not a subtle bug and it still
                // reached a device, because nothing here is covered by a golden.
                val free = "%.1f".format(l.ramFreeBytes / 1e9)
                val total = "%.1f".format(l.ramTotalBytes / 1e9)
                // ⚠⚠ The model is named in BOTH states, and that matters: the
                // name used to have its own row and removing that row must not
                // cost the answer to "which checkpoint am I on". `resident` is
                // null when no process is up, and then the selected name is
                // still the honest thing to show — marked idle so it is not
                // read as "loaded".
                val name = l.resident ?: vm.modelLabel
                val holding = if (l.resident != null) "holding $name" else "$name (idle)"
                "$holding  ·  $free/$total GB free"
            },
            onModels = { vm.setModelsVisible(true) },
            onWorkflows = { vm.setWorkflowsVisible(true) },
            onResults = { vm.setResultsVisible(true) },
            onDeviceInfo = { vm.setDeviceInfoVisible(true) },
            imageFor = vm::imageFor,
            // ⚠⚠ Not `onGesture` for these: a whole state captured at composition
            // time and written back late REVERTS the graph. See
            // `HarnessViewModel.editCanvas` -- it cost the chosen photo and the
            // dragged crop rect, both on sheet dismissal.
            onEdit = vm::editCanvas,
            onEditMask = vm::editMask,
            onCancelRun = vm::cancelRun,
            onSetResolution = vm::selectResolution,
            onSetAspect = vm::selectAspect,
            validateWorkflowName = vm::workflowNameError,
            onClearImage = vm::clearImage,
            onSaveImage = vm::saveImage,
            onShareImage = vm::shareNodeImage,
            onKeepImage = vm::toggleKeepResult,
            isKept = vm::isKept,
            onClearOutput = vm::clearOutput,
            onSave = vm::saveWorkflowAs,
            savedAs = vm.currentWorkflowName,
        )
        // ⚠ A dialog, so it draws OVER the canvas rather than replacing it: the
        // question it answers ("why did that fail on my phone") is asked while
        // looking at the thing that failed.
        if (vm.showDeviceInfo) {
            DeviceSheet(vm.deviceCaps, onDismiss = { vm.setDeviceInfoVisible(false) })
        }
        // ⭐ The sweep builder. ⚠ A dialog over the canvas for the same reason
        // the device sheet is one: it is answering a question about the graph
        // you are looking at.
        if (vm.batch != null) {
            com.abrah.nightmare.canvas.BatchSheet(
                graph = vm.canvas.workflow.graph,
                types = vm.nodeTypes,
                onDismiss = vm::closeBatch,
                onRun = { spec -> vm.closeBatch(); vm.runBatch(spec) },
            )
        }
        return
    }

    // ⚠⚠ **The harness is now a TAB inside Settings**, not a screen of its own.
    // The canvas's gear opens this; the wrench that used to open the harness
    // directly is gone. `ui/SettingsScreen.kt` has the reasoning.
    //
    // ⚠ Back returns to the canvas rather than quitting -- the same hole the
    // fullscreen viewer had, and the reason every over-canvas screen handles it.
    BackHandler { vm.setCanvasVisible(true) }
    // ⚠ Declared HERE now, not on the Flows tab: a node pack is code and is
    // installed from Settings → Community, beside the page that explains what a
    // pack may and may not do. `ui/WorkflowsScreen.kt` has the reasoning.
    // ⚠ Two MIME types, as every other picker in this app does: plenty of
    // providers hand a zip over as `application/octet-stream`, and filtering on
    // the exact type greys out the file the user came for.
    val packPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importPlugin(uri) }
    com.abrah.nightmare.ui.SettingsScreen(
        tab = vm.settingsTab,
        onTab = vm::switchSettingsTab,
        onClose = { vm.setCanvasVisible(true) },
        theme = vm.theme,
        onTheme = vm::chooseTheme,
        diagnostics = { HarnessPane(vm) },
        onImportPack = {
            packPicker.launch(arrayOf("application/zip", "application/octet-stream"))
        },
    )
}

/**
 * The op harness, as the Diagnostics tab draws it.
 *
 * ⚠ Split out so `SettingsScreen` can take it as a slot and stay free of the
 * view model -- the same shape `LibraryScreen` uses for its tabs.
 */
@Composable
private fun HarnessPane(vm: HarnessViewModel) {
    HarnessContent(
        state = vm.backend,
        busy = vm.busy,
        log = vm.log,
        image = vm.image,
        onStart = vm::startBackend,
        onStop = vm::stopBackend,
        onHealth = vm::checkBackend,
        onEncodeText = vm::encodeText,
        onVaeDecode = vm::vaeDecode,
        onSample = vm::sample,
        onGraph = vm::runGraph,
        onOpenCanvas = { vm.setCanvasVisible(true) },
        onOpenModels = { vm.setModelsVisible(true) },
        progress = vm.progress,
        onNotWired = vm::notWired,
    )
}

/**
 * Stateless so it can be previewed. The agent loop in docs/UI.md section 2.1
 * looks at previews, and a composable that can only be rendered by running the
 * whole app is a composable nobody iterates on.
 */
@Composable
fun HarnessContent(
    state: BackendState,
    busy: Boolean,
    log: List<LogLine>,
    image: ImageBitmap?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHealth: () -> Unit,
    onEncodeText: () -> Unit,
    onVaeDecode: () -> Unit,
    onSample: () -> Unit,
    onGraph: () -> Unit,
    onOpenCanvas: () -> Unit = {},
    onOpenModels: () -> Unit = {},
    /** ⚠ Overridden by the goldens so a version bump is not a UI change. */
    version: String = HARNESS_VERSION,
    /** step to total while the sampler runs, null otherwise. */
    progress: Pair<Int, Int>? = null,
    onNotWired: (String, String) -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(version, onOpenCanvas)
            StatusRow(state)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            OpButtons(
                busy = busy,
                running = state == BackendState.UP,
                onStart = onStart,
                onStop = onStop,
                onHealth = onHealth,
                onEncodeText = onEncodeText,
                onVaeDecode = onVaeDecode,
                onSample = onSample,
                onGraph = onGraph,
                onOpenCanvas = onOpenCanvas,
                onOpenModels = onOpenModels,
                onNotWired = onNotWired,
            )
            // ⚠ Only while sampling. A bar that is always on screen at 0% is
            // indistinguishable from a render that has not started, which is
            // the one thing the canvas will need this to tell apart (section 4).
            if (progress != null) SampleProgress(progress)
            if (image != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                DecodedImage(image)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LogPane(log, Modifier.weight(1f))
        }
    }
}

/**
 * The sampler's live position.
 *
 * ⭐ This is the first thing in the app that could not exist before /sample
 * streamed. A monolithic /generate is an opaque wait; section 4 requires
 * per-node progress and a visible stop before the canvas can ship, and the bar
 * is the half of that which the user sees.
 *
 * ⚠ Shows the RAW step and total beside the bar. A fraction alone cannot say
 * whether a run that sat at 95% was nearly done or had a denominator that
 * moved, and sample() deliberately closes its own bar (for the VAE step it
 * never spends) -- so the numbers have to be legible, not just the fill.
 */
@Composable
private fun SampleProgress(progress: Pair<Int, Int>) {
    val (step, total) = progress
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "sampling  $step / $total",
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (total > 0) step.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * ⚠ Fixed height, and `ContentScale.Fit`. A 512² decode on a tall phone would
 * otherwise push the log off screen, and the log is what says whether the thing
 * on screen is the image you think it is.
 */
@Composable
private fun DecodedImage(image: ImageBitmap) {
    Image(
        bitmap = image,
        contentDescription = "the latest vae_decode output",
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * ⚠ The version is a PARAMETER with a real default rather than read from
 * BuildConfig here, so a golden can pin a fixed string. Reading it directly made
 * every `versionCode` bump fail `verifyRoborazzi` on three harness screenshots
 * — a false alarm on every single push, which is how a drift check stops being
 * believed.
 */
@Composable
private fun Header(version: String = HARNESS_VERSION, onBack: () -> Unit = {}) {
    // ⚠ The same header Models and Flows draw -- three panels over the canvas
    // had grown three ways of saying "put this away". `ui/ScreenHeader.kt` owns
    // why it is a ✕ rather than an arrow.
    //
    // ⚠ The version stays, in the `trailing` slot: a failure report that cannot
    // name its build is unattributable, which is why the push rule in CLAUDE.md
    // bumps versionCode every time.
    ScreenHeader(
        "Nightmare",
        onClose = onBack,
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Text(
            version,
            style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusRow(state: BackendState) {
    val dot = when (state) {
        BackendState.UP -> MaterialTheme.colorScheme.primary
        BackendState.DOWN -> MaterialTheme.colorScheme.error
        BackendState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (state) {
        BackendState.UP -> "running"
        BackendState.DOWN -> "not reachable"
        BackendState.UNKNOWN -> "checking"
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Text(
                "backend  " + label + "  :" + Backend.PORT, style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // ⚠ Read from the SELECTION rather than written out, so this panel
        // cannot disagree with what the backend was actually launched against.
        // It said "dreamshaper (dev fixture)" for as long as that was true and
        // would have kept saying it afterwards.
        Text(
            "model     " + SelectedModel.id, style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OpButtons(
    busy: Boolean,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHealth: () -> Unit,
    onEncodeText: () -> Unit,
    onVaeDecode: () -> Unit,
    onSample: () -> Unit,
    onGraph: () -> Unit,
    onOpenCanvas: () -> Unit,
    onOpenModels: () -> Unit,
    onNotWired: (String, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The backend is now a child of this app, so starting it is a button
        // rather than an adb command someone has to know about.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Op(if (running) "restart backend" else "start backend", busy,
                Modifier.weight(2f), onStart)
            Op("stop", busy, Modifier.weight(1f), onStop)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Op("health", busy, Modifier.weight(1f), onHealth)
            Op("encode_text", busy, Modifier.weight(1f), onEncodeText)
            Op("vae_decode", busy, Modifier.weight(1f), onVaeDecode)
        }
        // The first two-node graph, on one button: sample -> latent handle ->
        // vae_decode. It is a row of its own because it is the only op here
        // that is a GRAPH rather than a single call.
        Op("sample -> decode  (the graph)", busy, Modifier.fillMaxWidth(), onSample)
        // Four passes over a two-branch graph, with a verdict per pass. It is
        // its own button rather than a variant of the one above because what it
        // measures is what did NOT run (HarnessViewModel.runGraph).
        Op("executor: 4-pass cache check", busy, Modifier.fillMaxWidth(), onGraph)
        // ⭐ The canvas. Its own row because it is the only button here that
        // opens a SCREEN rather than running an op.
        Op("open the canvas", busy, Modifier.fillMaxWidth(), onOpenCanvas)
        // ⭐ Where a user gets a model at all. Full width and next to the
        // canvas because on a fresh install it is the FIRST thing needed:
        // without it the graph renders against a directory that is not there.
        Op("models", busy, Modifier.fillMaxWidth(), onOpenModels)
        // Still honest stubs. Each names the step that will wire it, so the
        // screen doubles as the plan.
        Stub("tier0: resize", "QuickJS", busy, onNotWired)
        Stub("tier1: clipseg", "ORT CPU", busy, onNotWired)
    }
}

@Composable
private fun Op(label: String, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
    ) { Text(label, fontSize = 13.sp) }
}

@Composable
private fun Stub(
    op: String,
    step: String,
    busy: Boolean,
    onNotWired: (String, String) -> Unit,
) {
    OutlinedButton(
        onClick = { onNotWired(op, step) },
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(op) }
}

@Composable
private fun LogPane(log: List<LogLine>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (log.isEmpty()) {
            item {
                Text(
                    "no output yet", style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(log) { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    line.stamp, style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    line.text,
                    style = LogTextStyle,
                    color = if (line.bad) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// Previews. The awkward states are the point (docs/UI.md section 2.1): a
// preview of the happy path only tells you the happy path fits.

@Preview(name = "backend up", showBackground = true, backgroundColor = 0xFF0B0B10)
@Composable
private fun PreviewUp() = NightmareTheme {
    HarnessContent(
        state = BackendState.UP, busy = false,
        log = listOf(
            LogLine("12:04:11", "/health 200 in 71 ms"),
            LogLine("12:04:09", "encode_text -- not wired yet (step 2.1)", bad = true),
        ),
        image = null,
        onStart = {}, onStop = {},
        onHealth = {}, onEncodeText = {}, onVaeDecode = {}, onSample = {},
        onGraph = {},
        onOpenCanvas = {},
        onOpenModels = {},
        onNotWired = { _, _ -> },
    )
}

@Preview(name = "backend down, long error", showBackground = true, backgroundColor = 0xFF0B0B10)
@Composable
private fun PreviewDown() = NightmareTheme {
    HarnessContent(
        state = BackendState.DOWN, busy = false,
        log = listOf(
            LogLine("12:04:11", "  no backend on :8085. Stage and launch one first.", bad = true),
            LogLine(
                "12:04:11",
                "/health unreachable after 6001 ms -- ConnectException: failed to " +
                    "connect to /127.0.0.1 (port 8085) after 6000ms",
                bad = true,
            ),
        ),
        image = null,
        onStart = {}, onStop = {},
        onHealth = {}, onEncodeText = {}, onVaeDecode = {}, onSample = {},
        onGraph = {},
        onOpenCanvas = {},
        onOpenModels = {},
        onNotWired = { _, _ -> },
    )
}

@Preview(name = "empty and busy", showBackground = true, backgroundColor = 0xFF0B0B10)
@Composable
private fun PreviewEmpty() = NightmareTheme {
    HarnessContent(
        state = BackendState.UNKNOWN, busy = true, log = emptyList(),
        image = null,
        onStart = {}, onStop = {},
        onHealth = {}, onEncodeText = {}, onVaeDecode = {}, onSample = {},
        onGraph = {},
        onOpenCanvas = {},
        onOpenModels = {},
        onNotWired = { _, _ -> },
    )
}

@Preview(name = "sampling", showBackground = true, backgroundColor = 0xFF0B0B10)
@Composable
private fun PreviewSampling() = NightmareTheme {
    HarnessContent(
        state = BackendState.UP, busy = true,
        log = listOf(
            LogLine("12:04:12", "sample: 20 steps, seed 42 -- streaming"),
        ),
        image = null,
        onStart = {}, onStop = {},
        onHealth = {}, onEncodeText = {}, onVaeDecode = {}, onSample = {},
        onGraph = {},
        onOpenCanvas = {},
        onOpenModels = {},
        progress = 9 to 22,
        onNotWired = { _, _ -> },
    )
}
