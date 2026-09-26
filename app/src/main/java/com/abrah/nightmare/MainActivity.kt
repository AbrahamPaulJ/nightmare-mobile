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

    /**
     * ⭐ Memory pressure, or the app leaving the screen: the segment models go
     * now rather than at their idle timeout (`segment/IdleRelease`). ⚠ Their
     * cached results stay, so nothing already drawn changes.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            com.abrah.nightmare.segment.Segmenter.trim()
            com.abrah.nightmare.segment.Parser.trim()
        }
    }

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
        // ⭐ Which checkpoints are on the phone, for the RECIPES: they are
        // built with no `Context` and must still prefer an installed model
        // over a 1 GB download (`Workflows.ctxKeyParams`).
        ModelCatalog.refreshInstalled(this)
        com.abrah.nightmare.segment.Segmenter.refresh(this)
        // ⚠⚠ The parser's twin of the line above, which was MISSING: without
        // it `Parser.installed` stayed false on every phone, so the mask window
        // never resolved a pick in the background and nothing auto-picked.
        com.abrah.nightmare.segment.Parser.refresh(this)
        MaskDefaults.load(this)
        // ⭐ The DiT engine is a download too, since 1.5.502 — and an app
        // update wipes the older, APK-shipped copy out of the native dir.
        DitEngine.refresh(this)
        // ⭐ The video gate's remembered answer, before anything composes, so a
        // phone that cannot run video never draws a Video card for one frame.
        com.abrah.nightmare.npu.VideoGate.load(this, BuildConfig.VERSION_CODE)
        // ⚠ Before setContent: the theme decides the FIRST frame, and loading it
        // afterwards means a flash of the wrong palette on every cold start.
        Prefs.load(this)
        // ⚠ Off the main thread: the first load parses a ~3.6 MB tokenizer.json.
        // Nothing waits on it — the counts appear when it lands.
        val appCtx = applicationContext
        Thread { PromptTokens.ensureLoaded(appCtx) }.start()
        takeOp(intent)
        setContent {
            // ⚠⚠ Read from the view model, not from `Prefs` directly: the object
            // is a plain singleton with no Compose state, so a write to it would
            // change the value and recompose nothing.
            val vm: HarnessViewModel = viewModel()
            // ⭐ Ask the chip once, at first launch (the user's call, 2026-09-17).
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.probeVideoSupport() }
            val dark = when (vm.theme) {
                Prefs.Theme.DARK -> true
                Prefs.Theme.LIGHT -> false
                Prefs.Theme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            // ⚠⚠ The bar ICONS follow the app's theme, not the system's:
            // `enableEdgeToEdge` picks them from the system setting, so Light in
            // Settings on a dark-mode phone drew white icons on a white bar.
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            NightmareTheme(darkTheme = dark) {
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

    // ⭐ Send a picture into a flow — from the canvas viewer or Results, so drawn
    // here, above both. ⚠ Before the unsaved-flow confirm in the view model's
    // order of events: choosing a flow here may raise that one next.
    vm.pendingSend?.let { s ->
        com.abrah.nightmare.ui.SendToDialog(
            choices = s,
            onCurrent = vm::sendToCurrent,
            onRecipe = vm::sendToRecipe,
            onSaved = vm::sendToSaved,
            onDismiss = vm::cancelSend,
        )
    }

    // ⚠ Checked BEFORE the canvas: the models screen is reachable from both,
    // and a user who has no model at all needs it before either is any use.
    // ⭐⭐ ONE library screen, two tabs. Models and Flows were separate full
    // screens reached from separate buttons, so moving between them cost three
    // taps -- and they are the same question asked twice.
    // ⭐⭐⭐ **The canvas is ALWAYS drawn** — the library and Settings are
    // pull-down SHEETS over it since 2026-09-26 (the user's call: no ✕,
    // "pull down to close" like the node sheet — [com.abrah.nightmare.ui.PullDownSheet]).
    // ⚠ They used to REPLACE the canvas, so it was torn down and rebuilt on
    // every trip to Models; now it stays composed underneath.
        // ⭐⭐ The LoRA import, launched from a node's picker rather than from
        // the Settings tab — `canvas/LoraPicker.kt`'s Add button. ⚠ Same wide
        // filter and the same validator as the Settings one: `.safetensors` has
        // no registered MIME type, so the EXTENSION is what validates
        // ([HarnessViewModel.importLora]).
        val canvasLoraPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) vm.importLora(uri) }
        // ⭐⭐ How the app died last time, if it did — null on an ordinary
        // launch, which is nearly every launch (`CrashReport`). Drawn on the
        // FIRST screen, because the person who needs it is the one who just
        // watched the app vanish.
        vm.crashReport?.let { r ->
            com.abrah.nightmare.ui.CrashNotice(r, onDismiss = vm::dismissCrashReport)
        }
        vm.missingModel?.let { m -> MissingModelDialog(m, vm) }
        // ⭐⭐ Which upscaler, asked on the canvas — the SAME chooser Results
        // opens ([com.abrah.nightmare.ui.UpscalePicker]), so a person meets one
        // dialog wherever they start an upscale from.
        vm.upscaleNodePick?.let { node ->
            com.abrah.nightmare.ui.UpscalePicker(
                upscalers = vm.upscalerRows,
                onPick = { id, scale -> vm.upscaleFromNode(node, id, scale) },
                // ⚠ No `askToNotify` here: that helper is scoped to the library
                // branch, and this dialog is on the canvas. An upscale started
                // here shows its progress on the canvas, not in a notification.
                onInstall = vm::installUpscaler,
                onDismiss = vm::cancelUpscaleNode,
            )
        }
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
            // ⭐⭐ The SAME import the Settings tab runs, reachable from the
            // node that wants it — `canvas/LoraPicker.kt`. ⚠ A second launcher
            // rather than a shared one because `rememberLauncherForActivityResult`
            // is composition-scoped and these two screens are never composed
            // together; both call the one `HarnessViewModel.importLora`.
            onImportLora = { canvasLoraPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            loraEpoch = vm.loraEpoch,
            // ⭐⭐ Enlarge what an output made — it opens the SAME upscaler
            // chooser Results uses, then edits the flow and Runs.
            onUpscaleNode = vm::offerUpscaleNode,
            detailsOfNode = vm::detailsOfNode,
            onDropEnlargement = vm::dropEnlargement,
            onDropReceived = vm::dropReceived,
            // ⭐⭐ The segmenter, offered where the checkbox that needs it is.
            // ⚠ Re-read on `vm.working`, which is what changes while an install
            // runs — without a dependency the row would still say "not
            // installed" after the download finished.
            onInstallSegmenter = vm::installSegmenter,
            onDeleteSegmenter = vm::deleteSegmenter,
            onCancelSegmenter = vm::cancelModelInstall,
            // ⭐⭐⭐ **Read off `segmenterRow`, which is STATE.**
            //
            // ⚠⚠⚠ It was `remember(vm.working) { Segmenter.isInstalled(…) }`,
            // and neither `installSegmenter` nor `deleteSegmenter` touches
            // `working` — so the key never changed, the snapshot never
            // recomputed, and the row went on saying "not downloaded" after a
            // download and "Delete" after a delete. Reported from the phone
            // 2026-09-22 as *"not even the delete btn works"*: it worked, and
            // nothing on screen said so. ⇒ `segmenterRow` is a
            // `mutableStateOf` that `refreshSegmenter()` rewrites, and both
            // paths call it.
            segmenterRow = vm.segmenterRow,
            segmenterInstalled = vm.segmenterRow?.installed == true,
            // ⭐⭐ The parser, on exactly the same terms — STATE, not a
            // `remember` snapshot. The note above is why.
            onInstallParser = vm::installParser,
            onDeleteParser = vm::deleteParser,
            onCancelParser = vm::cancelModelInstall,
            parserRow = vm.parserRow,
            parserInstalled = vm.parserRow?.installed == true,
            // ⭐ Video hidden from Add node where the chip refused it (VideoGate).
            paletteTypes = if (com.abrah.nightmare.npu.VideoGate.hidden) {
                vm.nodeTypes.filterKeys { it != com.abrah.nightmare.npu.VideoGate.VIDEO_TYPE }
            } else vm.nodeTypes,
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
                // ⭐⭐⭐ The IN-PROCESS NPU wins the line while it is holding
                // something, because it is the thing actually running.
                //
                // ⚠⚠ Without this the bar described a video render as
                // "AbsoluteReality (idle)" — naming an SD checkpoint that was
                // not involved, and calling the machine idle while 13 context
                // binaries were mapped on the NPU. Reported from the phone,
                // 2026-09-13: *"why is the video model not shown in the top bar,
                // it just shows sd1.5 model as idle"*. The two routes to the NPU
                // are independent (`docs/NEODRAGON.md` §3), so the readout has
                // to ask both rather than assume the server is the only one.
                val holding = when {
                    // Something is mapped on the NPU right now.
                    // ⚠ A COLON after "holding" — the user's call, 2026-09-22.
                    // ⚠⚠ The literal is here, not `R.string.canvas_holding`:
                    // this line has never used that resource, and changing the
                    // string alone did nothing on the phone.
                    l.npuGraphs > 0 ->
                        "holding: ${com.abrah.nightmare.npu.NpuFiles.LABEL}" +
                            "  ·  ${l.npuGraphs} graph" + (if (l.npuGraphs == 1) "" else "s")
                    // A backend process is up with a checkpoint in it.
                    l.resident != null -> "holding: ${l.resident}"
                    // ⭐⭐ Nothing is loaded — so name what the OPEN FLOW would
                    // use, not what the picker happens to be set to. A video
                    // flow does not touch a checkpoint, and saying
                    // "QteaMix (idle)" over a t2v graph described a model that
                    // will never be loaded by anything on the canvas.
                    !l.graphNeedsCheckpoint ->
                        if (l.graphIsVideo) {
                            // ⚠ Not "(idle)" while a clip is rendering: the
                            // contexts are mapped a few seconds in, and the
                            // window before that was described as idle.
                            com.abrah.nightmare.npu.NpuFiles.LABEL +
                                if (l.running) " (loading…)" else " (idle)"
                        } else "no checkpoint needed"
                    // ⭐⭐⭐ Nothing resident — so name what THIS GRAPH will load.
                    //
                    // ⚠⚠ It said `vm.modelLabel`, the global picker. Since a node
                    // can carry its own checkpoint (2026-09-15) that is simply a
                    // different question, and changing a sampler's model left the
                    // bar naming the old one. `graphModels` is the graph's own
                    // answer; the rule is the one the branch above already
                    // follows.
                    else -> {
                        val names = l.graphModels
                        val head = names.firstOrNull() ?: vm.modelLabel
                        // ⚠ A graph may name TWO checkpoints now, and the bar has
                        // one line. Say the first and how many more, rather than
                        // picking one and implying it is the only one.
                        val more = if (names.size > 1) " +${names.size - 1}" else ""
                        // ⚠ "loading…" rather than "(idle)" while a run is in
                        // flight — a backend launch is 2.3-5 s and the poll is
                        // every 2 s, so the launch window read as idle.
                        head + more + if (l.running) " (loading…)" else " (idle)"
                    }
                }
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
            onTapMask = vm::tapMask,
            onPickMask = vm::pickMask,
            onTranslate = vm::translatePrompt,
            onCancelRun = vm::cancelRun,
            onSetResolution = vm::setNodeResolution,
            onSetAspect = vm::selectAspect,
            validateWorkflowName = vm::workflowNameError,
            onClearImage = vm::clearImage,
            onInspectNode = vm::inspectNode,
            onSaveImage = vm::saveImage,
            onShareImage = vm::shareNodeImage,
            onSendImage = vm::offerSendImage,
            onKeepImage = vm::toggleKeepResult,
            // ⭐ Same action, one flag different — [PictureActions] has the table.
            onStarImage = { id -> vm.toggleKeepResult(id, favourite = true) },
            isFavourite = { id -> vm.isFavourite(id) },
            keepDisabledReason = vm.keepDisabledReason,
            onDisabledAction = { why -> vm.say(why) },
            isKept = vm::isKept,
            onClearOutput = vm::clearOutput,
            onSave = vm::saveWorkflowAs,
            savedAs = vm.currentWorkflowName,
            suggestedName = vm.suggestedFlowName(),
            // ⚠⚠⚠ **INSTALLED only** — downloaded or imported, nothing else.
            //
            // ⚠⚠ This is a REVERSAL, and both halves were the user's call on the
            // same day. It first listed every catalogue entry with the absent
            // ones labelled `· not installed`, so that someone looking for SDXL
            // could tell a missing FEATURE from a missing MODEL. With fifteen
            // checkpoints and two installed, that made a picker where thirteen
            // of fifteen entries were refusals — asked for as *"only show
            // downloaded/imported models thank you very much"*, 2026-09-15.
            //
            // ⚠ What that reasoning was protecting is now carried by the Models
            // tab, which lists the whole catalogue and is one tap away; a node's
            // picker is for choosing between what the phone can actually run.
            installedModels = vm.modelRows
                .filter { it.installed }
                .map {
                    com.abrah.nightmare.canvas.CheckpointChoice(
                        it.spec.id, it.spec.label, it.spec.family,
                    )
                },
            onSetModel = vm::setNodeModel,
            plannedLoads = vm.plannedLoads,
            pendingSwap = vm.pendingSwap,
            onConfirmSwap = vm::confirmSwap,
            onCancelSwap = vm::cancelSwap,
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

    if (vm.libraryOpen) {
        com.abrah.nightmare.ui.PullDownSheet(onDismiss = { vm.closeLibrary() }) {
            // ⚠⚠ Every screen the app puts OVER the canvas handles back itself, or
            // the gesture leaves the app entirely -- there is one activity and no
            // back stack, so the system's default is "finish". Found on the
            // fullscreen viewer, 2026-09-09; these two had it just as badly.
            BackHandler { vm.closeLibrary() }
            // ⭐⭐ Asked AT the first Download, not at launch: that is the
            // moment the answer means something. Android 13+ drops every
            // notification silently without it, and the shade is where a
            // 4 GB download is watched once the screen is off.
            val notifyAsk = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { }
            val notifyCtx = androidx.compose.ui.platform.LocalContext.current
            val askToNotify: () -> Unit = {
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    notifyCtx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) notifyAsk.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            LibraryScreen(
                version = com.abrah.nightmare.BuildConfig.VERSION_NAME,
                tab = vm.libraryTab,
                onTab = vm::switchLibraryTab,
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
                        if (uri != null) {
                            vm.importModel(uri, importName.ifEmpty { vm.importNameFor(uri) })
                        }
                    }
                    // ⭐⭐ The DiT import: one `.safetensors`, family from the tab.
                    // ⚠ Its own name and family are captured before the picker opens,
                    // for the reason the zip picker captures `importName`: the
                    // callback cannot see which tab was pressed by the time it runs.
                    var ditName by remember { mutableStateOf("") }
                    var ditFamily by remember { mutableStateOf(Family.ZIMAGE) }
                    val ditPicker = rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            vm.importDitModel(
                                uri,
                                ditName.ifEmpty { vm.importNameFor(uri) },
                                ditFamily,
                            )
                        }
                    }
                    // ⚠ `.safetensors` has no registered MIME type, so providers
                    // hand it back as `application/octet-stream` at best — same
                    // reasoning as the zip picker above.
                    val embeddingPicker = rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri -> if (uri != null) vm.importEmbedding(uri) }
                    // ⚠ Same reasoning: `.bin` has no registered MIME type either.
                    val upscalerPicker = rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    ) { uri -> if (uri != null) vm.importUpscaler(uri) }
                    ModelsScreen(
                        rows = vm.modelRows,
                        // ⚠ The OR: this screen's buttons mean "the app is doing
                        // something long", which is what [working] is for.
                        busy = vm.working,
                        error = vm.modelError,
                        onInstall = { askToNotify(); vm.installModel(it) },
                        onCancel = vm::cancelModelInstall,
                        onDelete = vm::deleteModel,
                        onSelect = vm::askUse,
                        pendingUse = vm.pendingUse,
                        recipes = com.abrah.nightmare.canvas.RECIPES,
                        onConfirmUse = vm::confirmUse,
                        onCancelUse = vm::cancelUse,
                        // ⭐ Use on the other two KINDS of model. Neither selects
                        // anything globally; both open a flow that uses them.
                        onUseUpscaler = { vm.askUseUpscaler(it.label) },
                        onUseVideo = vm::askUseVideo,
                        importing = vm.importing,
                        importProgress = vm.importProgress,
                        onImportDit = { name, family ->
                            ditName = name
                            ditFamily = family
                            ditPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
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
                        onInstallUpscaler = { askToNotify(); vm.installUpscaler(it) },
                        onDeleteUpscaler = vm::deleteUpscaler,
                        onImportUpscaler = {
                            upscalerPicker.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        segmenter = vm.segmenterRow,
                        onInstallSegmenter = { askToNotify(); vm.installSegmenter() },
                        onDeleteSegmenter = vm::deleteSegmenter,
                        parser = vm.parserRow,
                        onInstallParser = { askToNotify(); vm.installParser() },
                        onDeleteParser = vm::deleteParser,
                        video = vm.videoRow.takeIf { !com.abrah.nightmare.npu.VideoGate.hidden },
                        onInstallVideo = { askToNotify(); vm.installVideoModels() },
                        onDeleteVideo = vm::deleteVideoModels,
                        onProbeVideo = vm::probeVideoSupport,
                    )
                },
                results = {
                    com.abrah.nightmare.ui.ResultsScreen(
                        // ⭐⭐ What the filter beside Favourites reads.
                        tagsOf = vm::resultTags,
                        groups = vm.keptGroups,
                        results = vm.kept,
                        favouritesOnly = vm.favouritesOnly,
                        onFavouritesOnly = vm::showFavouritesOnly,
                        onToggleFavourite = vm::toggleResultFavourite,
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
                        imageFor = vm::resultImage,
                        unreadable = vm::resultUnreadable,
                        detailsFor = vm::detailsOf,
                        onUpscale = { r, u, s -> vm.upscaleResult(r.id, u, s) },
                        upscalers = vm.upscalerRows,
                        onInstallUpscaler = { askToNotify(); vm.installUpscaler(it) },
                        upscaling = vm.upscalingResult,
                        onShareResults = { ids, asFlow -> vm.shareResults(ids, asFlow) },
                        onToast = vm::toast,
                        onStarSelected = vm::starSelectedResults,
                        onSendTo = { vm.offerSendResult(it.id) },
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
                        recipes = com.abrah.nightmare.canvas.RECIPES.filter {
                            !com.abrah.nightmare.npu.VideoGate.hidden ||
                                it.id !in com.abrah.nightmare.npu.VideoGate.VIDEO_RECIPES
                        },
                        saved = vm.savedWorkflows,
                        error = vm.workflowError,
                        onOpenRecipe = vm::openRecipe,
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
                        onToggleFavourite = vm::toggleResultFavourite,
                        items = vm.viewingSet,
                        startIndex = vm.viewingIndex,
                        // ⚠ The FULL picture, not the list thumbnail: this is the
                        // surface where the detail is the point.
                        imageFor = { id -> vm.resultImage(id) },
                        // ⭐ Shown while the full decode is still in flight —
                        // both are async now, so this fills the gap rather than
                        // leaving a blank page for however long that takes.
                        thumbnailFor = { id -> vm.thumbnailFor(id) },
                        unreadable = vm::resultUnreadable,
                        detailsFor = vm::detailsOf,
                        onDismiss = { vm.closeResult() },
                        onOpenFlow = { r -> vm.closeResult(); vm.openResultFlow(r.id) },
                        onDelete = { r -> vm.deleteResult(r.id) },
                        onSave = { r -> vm.saveResultsToGallery(listOf(r.id)) },
                        onShare = { r -> vm.shareResultImage(r.id) },
                        onSendTo = { r -> vm.offerSendResult(r.id) },
                        // ⭐⭐ The same upscale the Results row offers, from the
                        // viewer — one picker, one refusal, one in-flight name.
                        upscalers = vm.upscalerRows,
                        upscaling = vm.upscalingResult,
                        onUpscale = { r, u, s -> vm.upscaleResult(r.id, u, s) },
                        onInstallUpscaler = { askToNotify(); vm.installUpscaler(it) },
                        onToast = vm::toast,
                    )
                }
            }
        }
        return
    }

    if (vm.showCanvas) return

    com.abrah.nightmare.ui.PullDownSheet(onDismiss = { vm.setCanvasVisible(true) }) {

        // ⚠⚠ **Settings is one page now** — Community and Diagnostics (the op
        // harness) were removed 2026-09-19, at the user's ask: a pack still loads
        // from `<externalFiles>/plugins/` with no UI, and the harness is a
        // developer surface (`OpService` over adb) that never needed one either.
        // `ui/SettingsScreen.kt` has the reasoning.
        //
        // ⚠ Back returns to the canvas rather than quitting -- the same hole the
        // fullscreen viewer had, and the reason every over-canvas screen handles it.
        BackHandler { vm.setCanvasVisible(true) }
        // ⭐ Settings' own copy of the embeddings import picker — see the Models
        // tab's `embeddingPicker` for the same launcher and why it is declared
        // per-screen rather than shared (each screen owns its own launcher, the
        // existing pattern every picker in this file already follows).
        val settingsEmbeddingPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) vm.importEmbedding(uri) }
        // ⚠ Same reasoning again: `.safetensors` has no registered MIME type, so
        // the filter has to be wide and the EXTENSION is what validates
        // ([HarnessViewModel.importLora]).
        val settingsLoraPicker = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
        ) { uri -> if (uri != null) vm.importLora(uri) }
        // ⭐ Whether this app is exempt from Doze/App Standby battery
        // optimisation — the belt-and-suspenders half of the background-kill fix
        // ([BackendKeepAliveService]'s foreground service is the main one). Not a
        // ViewModel field: it is OS state this app does not own, so it is read
        // fresh from PowerManager rather than cached and drifting.
        // ⚠ Re-read on RESUME, not just once: the only way it changes is the user
        // granting it from the system dialog this screen launches, and that
        // dialog closes back into this same Activity.
        val appCtx = androidx.compose.ui.platform.LocalContext.current
        var batteryUnrestricted by androidx.compose.runtime.remember {
            mutableStateOf(
                (appCtx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                    .isIgnoringBatteryOptimizations(appCtx.packageName)
            )
        }
        val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    vm.storageAccessReturned()
                    batteryUnrestricted =
                        (appCtx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                            .isIgnoringBatteryOptimizations(appCtx.packageName)
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        com.abrah.nightmare.ui.SettingsScreen(
            theme = vm.theme,
            onTheme = vm::chooseTheme,
            batteryUnrestricted = batteryUnrestricted,
            onRequestBatteryUnrestricted = {
                // ⚠⚠ The DIRECT request, not just a link to the settings list —
                // sideload distribution means the Play policy gating this intent
                // does not apply (CLAUDE.md), and the whole point is to save the
                // user from hunting through Battery settings for this app by name.
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${appCtx.packageName}"),
                )
                appCtx.startActivity(intent)
            },
            loras = vm.loraRows,
            onImportLora = {
                settingsLoraPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onDeleteLora = vm::deleteLora,
            embeddings = vm.embeddingRows,
            onImportEmbedding = {
                settingsEmbeddingPicker.launch(arrayOf("application/octet-stream", "*/*"))
            },
            onDeleteEmbedding = vm::deleteEmbedding,
            downloadBase = vm.downloadBase,
            onDownloadBase = vm::chooseDownloadBase,
            onCleanTemp = vm::cleanTempFiles,
            // ⚠ So the cleaner leaves a live download's scratch alone.
            installing = vm.working,
            translateRows = vm.translateRows,
            onInstallTranslation = vm::installTranslation,
            onDeleteTranslation = vm::deleteTranslation,
            onCancelInstall = vm::cancelModelInstall,
            modelsPlace = vm.modelsPlace,
            storageAccess = vm.storageAccess,
            strandedModels = vm.strandedModels,
            moveProgress = vm.moveProgress,
            movePlan = vm.movePlan,
            onModelsPlace = vm::chooseModelsPlace,
            onConfirmMove = vm::confirmMove,
            onDismissMove = vm::dismissMove,
        )
        // ⭐ The system's All files access page, opened when a person picks
        // `Download/Nightmare` without it ([HarnessViewModel.chooseModelsPlace]).
        // ⚠ The answer is read on RESUME, below — the page returns no result.
        if (vm.wantStorageAccess) {
            androidx.compose.runtime.LaunchedEffect(Unit) {
                vm.storageAccessPageOpened()
                runCatching {
                    appCtx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${appCtx.packageName}"),
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }
}

/**
 * ⭐⭐ A Run stopped because a flow names a checkpoint that is not here — asked
 * ON the canvas, with the download in the popup (the user's call, 2026-09-17).
 *
 * ⚠ Stays open through the download and becomes a Run button when the model
 * lands; Hide lets the download continue with the popup closed.
 */
@Composable
private fun MissingModelDialog(m: HarnessViewModel.MissingModel, vm: HarnessViewModel) {
    val bytes = vm.missingBytes(m)
    val size = if (bytes >= 1L shl 30) String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    else "${bytes shr 20} MB"
    val progress = vm.missingProgress
    val done = vm.missingInstalled
    // ⭐ Files deleted from a built-in: say which, and call it a repair.
    val repair = remember(m, done) { vm.repairOf(m) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.dismissMissingModel() },
        title = {
            Text(
                when {
                    done -> "${m.label} is ready"
                    // ⚠ Not "this flow": a translate tap is not a Run.
                    m is HarnessViewModel.MissingModel.Translate ->
                        androidx.compose.ui.res.stringResource(R.string.translate_needs_title)
                    else -> "This flow needs a model"
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        done && m is HarnessViewModel.MissingModel.Translate -> "Downloaded."
                        done -> "Downloaded. Run the flow now?"
                        m is HarnessViewModel.MissingModel.Checkpoint && m.substitute && m.wanted.isNotBlank() ->
                            "\"${m.wanted}\" is not on this phone and has no download — it was " +
                                "imported somewhere else. Download ${m.label} ($size) and use it " +
                                "for this flow instead?"
                        // ⭐ The language pair by its NAME, not the enum's code,
                        // and a promise that the tap needs no repeating.
                        m is HarnessViewModel.MissingModel.Translate ->
                            androidx.compose.ui.res.stringResource(
                                R.string.translate_needs_body,
                                vm.translateRows[m.source]?.label ?: m.label, size,
                            )
                        repair.isNotEmpty() -> androidx.compose.ui.res.stringResource(
                            R.string.repair_body, m.label, repair.joinToString(), size,
                        )
                        m is HarnessViewModel.MissingModel.Segment ->
                            "${m.label} is not installed — this flow's Segment model node needs it. " +
                                "Download it ($size)?"
                        else -> "${m.label} is not installed. Download it ($size)?"
                    }
                )
                if (progress != null) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${progress.done shr 20} of ${progress.total shr 20} MB · ${progress.phase}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (!done) {
                    Text("Use Wi-Fi.", style = MaterialTheme.typography.bodySmall)
                }
                vm.modelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                // ⚠ A translation model has nothing to Run — the prompt it was
                // fetched for is translated the moment it lands.
                done && m is HarnessViewModel.MissingModel.Translate ->
                    androidx.compose.material3.TextButton(onClick = { vm.dismissMissingModel() }) { Text("OK") }
                done -> androidx.compose.material3.Button(onClick = {
                    vm.dismissMissingModel()
                    vm.runCanvasOrBatch()
                }) { Text("Run") }
                progress != null -> androidx.compose.material3.TextButton(onClick = { vm.dismissMissingModel() }) {
                    Text("Hide")
                }
                else -> androidx.compose.material3.Button(onClick = { vm.downloadMissingModel() }) {
                    Text(androidx.compose.ui.res.stringResource(if (repair.isNotEmpty()) R.string.repair else R.string.download))
                }
            }
        },
        dismissButton = {
            if (progress != null) {
                androidx.compose.material3.TextButton(onClick = { vm.cancelModelInstall(); vm.dismissMissingModel() }) {
                    Text("Cancel download")
                }
            } else if (!done) {
                androidx.compose.material3.TextButton(onClick = { vm.dismissMissingModel() }) { Text("Not now") }
            }
        },
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
            LogLine("12:04:09", "encode_text — not wired yet (step 2.1)", bad = true),
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
                "/health unreachable after 6001 ms — ConnectException: failed to " +
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
            LogLine("12:04:12", "sample: 20 steps, seed 42 — streaming"),
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
