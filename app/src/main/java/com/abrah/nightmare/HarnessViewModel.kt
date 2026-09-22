package com.abrah.nightmare

import com.abrah.nightmare.canvas.asParams
import com.abrah.nightmare.canvas.knobLabel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.abrah.nightmare.canvas.CanvasState
import com.abrah.nightmare.canvas.SavedWorkflow
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.toJson
import com.abrah.nightmare.canvas.NodeStatus
import com.abrah.nightmare.canvas.WorkflowStore
import com.abrah.nightmare.canvas.defaultWorkflow
import com.abrah.nightmare.canvas.missingRequirements
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abrah.nightmare.canvas.runsOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the harness knows about the backend process. */
enum class BackendState { UNKNOWN, UP, DOWN }

data class LogLine(val stamp: String, val text: String, val bad: Boolean = false)

/**
 * The harness screen's state. The ops themselves are in [HarnessOps], which
 * knows nothing about Compose so that [OpService] can run the identical code
 * with nothing on screen.
 *
 * ⚠ This screen is scaffolding, and it says so on the device. The canvas comes
 * after the runtime works; this exists so the ops can be driven from the phone
 * and so the build/install/push loop is proven before the canvas depends on it.
 */
/**
 * ⚠ Not a node id — the key `detailsOfGraph` asks the WHOLE run for.
 *
 * ⚠⚠ Top level rather than in the companion, which already exists: a second
 * `companion object` in one class does not compile, and the error it gives
 * names every constant in the FIRST one as unresolved.
 */
private const val TOTAL_MS_KEY = "@@total"

/** ⭐⭐ What the Results filter reads — see `HarnessViewModel.resultTags`. */
data class ResultTags(val models: Set<String>, val text: String)

class HarnessViewModel(app: Application) : AndroidViewModel(app) {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val log = mutableStateListOf<LogLine>()
    var backend by mutableStateOf(BackendState.UNKNOWN)
        private set
    /**
     * ⭐⭐⭐ **A RUN is in flight** — a render, a sweep, or a harness op. Not a
     * download.
     *
     * ⚠⚠⚠ It used to mean both, because the harness screen gates the same
     * buttons on either, and that is what put a red **Cancel** on the CANVAS
     * during an 8.6 GB model download. Pressing it called [cancelRun], whose
     * `runJob` is null when nothing is rendering — so it aborted whatever HTTP
     * the backend had open, said "cancelling…", and the download carried on. A
     * red Cancel that does not cancel what it appears to be about. Reported
     * from the phone, 2026-09-15.
     *
     * ⚠⚠ A second symptom from the same latch: Run was refused mid-download
     * with *"canvas ignored — already running"*, which names nothing the user
     * can see. Refusing is right; that sentence was not.
     *
     * ⇒ An install sets [installing] and nothing else. [working] is the OR, for
     * the one screen that genuinely means either.
     */
    var busy by mutableStateOf(false)
        private set

    /** The last decoded image. The first pixels this project puts on screen. */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * Live sampler progress, or null when nothing is sampling. A pair rather
     * than a float: the raw step and total are what the log needs, and a
     * fraction computed here would hide a denominator that changed.
     */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /**
     * Which half of the app is on screen.
     *
     * ⭐ The CANVAS is the default. The harness was the launch screen for as
     * long as this project had no users, and it shows: a person opening the app
     * met "start backend" / "encode_text" / "vae_decode" buttons before anything
     * they wanted. The harness is still there behind the top bar, because the
     * op log is where a failure is actually diagnosed.
     */
    var showCanvas by mutableStateOf(true)
        private set

    fun setCanvasVisible(on: Boolean) { showCanvas = on }

    // ---- app settings ----------------------------------------------------

    /**
     * ⭐ The app's own theme. ⚠ Mirrored into state so Compose recomposes;
     * `Prefs` owns the disk and this owns the frame.
     */
    var theme by mutableStateOf(Prefs.theme)

    /**
     * ⭐⭐ Where model files are fetched from — **an observable copy of
     * [Prefs.downloadBase]**, for the same reason [theme] is one.
     *
     * ⚠⚠⚠ Reported from the phone 2026-09-21: the hf-mirror and Custom
     * radios "isnt working". They were, in the sense that the preference was
     * written and every download honoured it — `Prefs.apply()` reads the same
     * field. What did not work was the SCREEN: it read `Prefs.downloadBase`
     * directly, and that is a plain `var`, so nothing recomposed, the
     * selection never moved, and the custom field's `remember` key never
     * changed. Indistinguishable from broken, and worse than broken — it was
     * silently doing what the UI denied.
     *
     * ⇒ The pattern already existed one line up. `theme` is a plain `var` in
     * [Prefs] too and the screen never touches it: the view model holds the
     * observable copy and passes it in. I read the singleton instead of
     * looking at how its neighbour was done.
     */
    var downloadBase by mutableStateOf(Prefs.downloadBase)
        private set

    /**
     * ⚠ `chooseTheme`, not `setTheme`: a `var theme` already generates a
     * `setTheme` on the JVM, and the two clash at the bytecode level with an
     * error that names a signature rather than the property.
     */
    /**
     * ⭐⭐ Point the open inspector at another node — the node strip
     * (`canvas/NodeStrip`).
     *
     * ⚠⚠ Clears `focusField`, `cropRequest` and its tab, because all three
     * belong to the node being LEFT. A crop request that survived the switch
     * would open the crop popup on the node just arrived at, which is the same
     * class of bug as a `remember` that outlives its subject.
     *
     * ⚠ Does not touch the canvas SELECTION or mark the flow dirty: looking
     * at a node changes nothing about the graph.
     */
    fun inspectNode(id: String) {
        if (canvas.workflow.graph.byId[id] == null) return
        canvas = canvas.copy(
            editing = id,
            focusField = null,
            cropRequest = null,
            cropRequestTab = 0,
        )
    }

    fun chooseTheme(value: Prefs.Theme) {
        Prefs.setTheme(getApplication(), value)
        theme = value
    }

    /**
     * ⭐⭐ Where model files are downloaded from — [Prefs.downloadBase].
     *
     * ⚠ Nothing is re-read or refreshed afterwards: the catalogue holds whole
     * URLs and the substitution happens at the connection ([Prefs.apply]), so
     * a change takes effect on the next download with no rebuild of anything.
     * Upstream has to call `refreshAllModels()` here; we do not.
     */
    fun chooseDownloadBase(value: String) {
        Prefs.setDownloadBase(getApplication(), value)
        // ⚠ Read BACK from Prefs rather than echoing `value`: it normalises
        // (trailing slash, blank falling back to Hugging Face), and the screen
        // must show what was actually stored.
        downloadBase = Prefs.downloadBase
        say("downloads now come from $downloadBase")
    }

    /**
     * ⭐⭐ Delete the scratch [TempCleaner] found, and SAY what was freed.
     *
     * ⚠⚠ The number comes from the delete, not from the scan that offered
     * it: a target can vanish between the two (a download finishing, Android
     * clearing its own cache), and reporting the scan would claim bytes that
     * were never freed here.
     */
    fun cleanTempFiles() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                TempCleaner.clean(getApplication(), busy = working)
            }
            say("cleaned ${freed shr 20} MB of temp files")
        }
    }
    /**
     * Pixels for an image id, cached per id.
     *
     * ⚠ Cached because it is called from the draw pass: converting a Bitmap on
     * every frame would re-allocate a megabyte per node per frame.
     */
    private val bitmaps = mutableMapOf<String, ImageBitmap>()

    fun imageFor(id: String): ImageBitmap? = bitmaps.getOrPut(id) {
        ops.images.get(id)?.asImageBitmap() ?: return null
    }

    // ---- workflows -------------------------------------------------------

    var showWorkflows by mutableStateOf(false)
        private set

    var savedWorkflows by mutableStateOf<List<SavedWorkflow>>(emptyList())
        private set

    var workflowError by mutableStateOf<String?>(null)
        private set

    /** ⭐ Open the library straight onto Results, from the canvas. */
    fun setResultsVisible(on: Boolean) {
        showWorkflows = on
        workflowError = null
        if (on) {
            libraryTab = com.abrah.nightmare.ui.LibraryTab.RESULTS
            refreshResults()
        }
    }

    fun setWorkflowsVisible(on: Boolean) {
        showWorkflows = on
        workflowError = null
        if (on) {
            libraryTab = com.abrah.nightmare.ui.LibraryTab.FLOWS
            refreshWorkflows()
        }
    }

    // ---- device ----------------------------------------------------------

    var showDeviceInfo by mutableStateOf(false)
        private set

    /**
     * What the device sheet draws. ⚠ Read through [DeviceProbe.caps] every time
     * rather than snapshotted: it changes the first time a measurement lands,
     * and a copy taken at start-up would show the guess forever.
     */
    val deviceCaps: DeviceProbe.Caps get() = DeviceProbe.caps()

    /**
     * ⭐ Measured on demand, then cached for the process.
     *
     * ⚠ Not at app start: the probe needs the QNN libraries unpacked, which is
     * a ~25 MB copy, and doing it before anyone has asked would put that on the
     * cold-start path of a canvas that may never render anything.
     */
    fun setDeviceInfoVisible(on: Boolean) {
        showDeviceInfo = on
        if (on && DeviceProbe.measured == null) {
            viewModelScope.launch {
                DeviceProbe.measure(getApplication())
                // ⚠ The MODEL LIST depends on this: which tier each row offers,
                // and whether it offers one at all. A measurement that arrived
                // without refreshing would leave the picker showing the guess.
                refreshModels()
            }
        }
    }

    /**
     * ⚠ ONE library screen with two tabs, so both flags close together.
     * Leaving the other set would reopen the library the next time the canvas
     * asked for either — the state that used to be two independent screens is
     * now two views of one.
     */
    fun closeLibrary() {
        showModels = false
        showWorkflows = false
    }

    val libraryOpen: Boolean get() = showModels || showWorkflows

    fun refreshWorkflows() { savedWorkflows = store.saved() }

    /**
     * The saved workflow the canvas came from, or null for a recipe or a graph
     * built by hand.
     *
     * ⭐ It exists so Save can offer the name the user already chose instead of
     * an empty box. Saving over your own workflow should not require you to
     * remember and retype what you called it. ⚠ Cleared by anything that makes
     * the canvas no longer that workflow -- opening a recipe, or the autosave
     * restore, which is "whatever was on screen", not a named thing.
     */
    var currentWorkflowName by mutableStateOf<String?>(null)
        private set

    /**
     * Rename a saved workflow.
     *
     * ⚠ The canvas follows it when that workflow is the one open, or Save
     * would then offer the OLD name and quietly make a second copy under it.
     */
    fun renameWorkflow(from: String, to: String) {
        val why = store.rename(from, to)
        if (why != null) { workflowError = why; return }
        if (currentWorkflowName == from) currentWorkflowName = to.trim()
        workflowError = null
        say("renamed \"$from\" to \"${to.trim()}\"")
        refreshWorkflows()
    }

    /**
     * Save the canvas under [name].
     *
     * ⚠ Params, wiring, positions and node widths all go: `WorkflowIo` already
     * round-trips them, which is why this is a button and not a project.
     */
    /**
     * ⭐ Why [name] will not do, or null. ⚠ The STORE's rule, exposed rather
     * than restated: the save dialog and `saveWorkflowAs` must refuse exactly
     * the same set, or the dialog accepts a name the store then drops.
     */
    fun workflowNameError(name: String): String? = store.validName(name)

    fun saveWorkflowAs(name: String) {
        store.validName(name)?.let { workflowError = it; return }
        try {
            store.save(name.trim(), canvas.workflow, nodeTypes, canvas.savedView)
            currentWorkflowName = name.trim()
            // ⚠ Saving is the other thing that makes the canvas clean.
            savedBaseline = canvas.workflow
            say("saved \"${name.trim()}\"")
            workflowError = null
            refreshWorkflows()
        } catch (e: Exception) {
            workflowError = e.message ?: e.javaClass.simpleName
        }
    }

    /**
     * ⚠⚠ Replaces the canvas, and CLEARS the previews with it. A picture left
     * attached to a node id that the new graph also happens to use would show
     * the old workflow's output on a node that never produced it.
     */
    /**
     * ⭐⭐ **The ACTIVE FLOW.** What is on the canvas, whether it has a name,
     * and whether it has moved since that name last meant something.
     *
     * ⚠⚠ It exists because opening a flow REPLACES the canvas, and until now it
     * did so silently. Open one from Results mid-edit and the graph you were
     * working on was gone — not corrupted, just replaced, with the autosave
     * holding whatever the 150 ms debounce had caught. Reported as the question
     * "if mid flow I go to results and open a new one, how is the old one
     * handled?", and the honest answer was "badly".
     */
    data class ActiveFlow(val name: String?, val dirty: Boolean)

    /**
     * ⚠⚠ The workflow as it was when it was last SAVED or OPENED — the
     * baseline "dirty" is measured against. Null means the canvas came from
     * nowhere nameable (a recipe, the autosave restore), which is still a
     * baseline: edits after it are real edits.
     */
    private var savedBaseline: Workflow? = null

    val activeFlow: ActiveFlow
        get() = ActiveFlow(
            currentWorkflowName,
            // ⚠ Structural equality on the graph, not identity. Every gesture
            // rebuilds the state object, so `!==` would report a canvas as
            // dirty for having been panned.
            savedBaseline?.graph != canvas.workflow.graph,
        )

    /**
     * A flow the user asked to open while another was active and unsaved.
     *
     * ⚠ Held rather than opened, so the DIALOG decides. The alternative — open
     * and offer an undo — means the replaced graph has to be kept somewhere
     * anyway, and an undo nobody notices is not a safety net.
     */
    var pendingOpen by mutableStateOf<PendingOpen?>(null)
        private set

    data class PendingOpen(val label: String, val open: () -> Unit)

    /**
     * ⭐⭐ Open [what], unless the canvas has unsaved work or is mid-render.
     *
     * ⚠⚠ **Mid-render is a REFUSAL, not a prompt.** A run holds node results
     * keyed to the graph that started it; swapping the graph underneath leaves
     * a render writing previews onto node ids that now mean something else, and
     * the picture lands on the wrong node. There is no answer to a dialog that
     * makes that safe, so it is not offered as a choice.
     */
    private fun guardedOpen(label: String, open: () -> Unit) {
        if (runLog.running) {
            say("\"$label\" not opened — this flow is still rendering", bad = true)
            workflowError = "Still rendering. Wait for it to finish, or close the run."
            return
        }
        if (activeFlow.dirty) {
            pendingOpen = PendingOpen(label, open)
            return
        }
        open()
    }

    fun confirmPendingOpen() {
        val p = pendingOpen ?: return
        pendingOpen = null
        p.open()
    }

    fun dismissPendingOpen() { pendingOpen = null }

    /**
     * ⭐ What opening a saved flow CHANGED in it — a deleted node type rebuilt
     * away ([com.abrah.nightmare.canvas.LoadedWorkflow.notes]) — logged and
     * toasted, never silent.
     */
    private fun sayNotes(loaded: com.abrah.nightmare.canvas.LoadedWorkflow) {
        loaded.notes.forEach { say(it); toast(it) }
    }

    // ---- send a picture into a flow -------------------------------------

    /**
     * ⭐⭐ Where a picture can be SENT (asked for 2026-09-17): an image input of
     * the flow on the canvas, a built-in flow that takes a picture, or a saved
     * flow that does. ⚠ The picture is already COPIED to [file] — a result can
     * be deleted and a canvas render evicted, and the flow it lands in must keep
     * reading it.
     */
    data class SendChoices(
        val file: java.io.File,
        /** Every `core.image` node of the canvas flow, by id — the user picks one. */
        val current: List<String>,
        val recipes: List<com.abrah.nightmare.canvas.Recipe>,
        val saved: List<String>,
    )

    var pendingSend by mutableStateOf<SendChoices?>(null)
        private set

    fun cancelSend() { pendingSend = null }

    /** ⭐ From the canvas: a picture in the image store. */
    fun offerSendImage(imageId: String) =
        offerSend { f -> ops.images.get(imageId)?.let { bmp ->
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        } != null }

    /** ⭐ From Results: the kept PNG. */
    fun offerSendResult(resultId: String) =
        offerSend { f -> results.pngFile(resultId)?.let { it.copyTo(f, overwrite = true); true } ?: false }

    private fun offerSend(write: (java.io.File) -> Boolean) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = java.io.File(getApplication<Application>().filesDir, "sent").apply { mkdirs() }
            val file = java.io.File(dir, "sent_${System.currentTimeMillis()}.png")
            if (!runCatching { write(file) }.getOrDefault(false)) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { toast("That picture could not be read") }
                return@launch
            }
            fun takesPicture(g: Graph) = g.nodes.any { it.type == "core.image" }
            val recipes = com.abrah.nightmare.canvas.RECIPES
                .filter { !com.abrah.nightmare.npu.VideoGate.hidden || it.id !in com.abrah.nightmare.npu.VideoGate.VIDEO_RECIPES }
                .filter { r -> runCatching { takesPicture(r.build().graph) }.getOrDefault(false) }
            val saved = store.saved().map { it.name }
                .filter { n -> runCatching { store.load(n)?.workflow?.graph?.let(::takesPicture) }.getOrNull() == true }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                pendingSend = SendChoices(
                    file = file,
                    current = canvas.workflow.graph.nodes.filter { it.type == "core.image" }.map { it.id },
                    recipes = recipes,
                    saved = saved,
                )
            }
        }
    }

    /**
     * ⭐ Put the sent picture on [nodeId] of the canvas flow. ⚠ Through
     * `setParam("uri")`, the same door the gallery picker uses — so the framing
     * resets and a sampler it feeds fits itself and opens its crop
     * ([markNewPictures]), exactly as if the photo had been picked.
     */
    fun sendToCurrent(nodeId: String) {
        val s = pendingSend ?: return
        pendingSend = null
        closeResult()
        closeLibrary()
        editCanvas { it.setParam(nodeId, "uri", s.file.absolutePath) }
        say("sent the picture to $nodeId")
    }

    /** ⭐ Open a built-in flow with the picture on its first image input. */
    fun sendToRecipe(r: com.abrah.nightmare.canvas.Recipe) {
        val s = pendingSend ?: return
        pendingSend = null
        guardedOpen(r.label) {
            closeResult()
            openRecipeNow(r)
            closeLibrary()
            placeSent(s.file)
        }
    }

    /** ⭐ Open a saved flow with the picture on its first image input. */
    fun sendToSaved(name: String) {
        val s = pendingSend ?: return
        pendingSend = null
        val loaded = store.load(name) ?: run { workflowError = "\"$name\" is gone"; return }
        guardedOpen(name) {
            closeResult()
            openWorkflow(loaded.workflow, loaded.view)
            currentWorkflowName = name
            sayNotes(loaded)
            closeLibrary()
            placeSent(s.file)
        }
    }

    private fun placeSent(file: java.io.File) {
        val target = canvas.workflow.graph.nodes.firstOrNull { it.type == "core.image" } ?: return
        editCanvas { it.setParam(target.id, "uri", file.absolutePath) }
        say("sent the picture to ${target.id}")
    }

    // ---- what the device is carrying -------------------------------------

    /**
     * ⭐⭐ What the canvas shows about LOAD, and it is two measured facts
     * rather than a score.
     *
     * ⚠⚠ **No synthesised gauge.** A single "load" bar needs a denominator,
     * and there is no honest one: the budget depends on the checkpoint, the
     * resolution, whether `--lowram` is staging the UNet, and what else the
     * phone is doing. A bar built on a guessed maximum would be exactly the
     * kind of authoritative-looking wrong number this project has already lost
     * days to. ⇒ Free RAM, measured; and what the backend is holding, named.
     */
    data class CanvasLoad(
        val ramFreeBytes: Long,
        val ramTotalBytes: Long,
        /** The checkpoint the RUNNING backend was launched with, or null. */
        val resident: String?,
        val residentBytes: Long,
        /**
         * ⭐⭐ How many context binaries the IN-PROCESS runner is holding.
         *
         * ⚠ A second, independent route to the NPU — see
         * [com.abrah.nightmare.npu.QnnRunner.Resident] for why [resident] alone
         * could never describe a video render.
         */
        val npuGraphs: Int = 0,
        /**
         * ⭐⭐ Whether the graph ON THE CANVAS names a checkpoint at all.
         *
         * ⚠⚠ False for a video flow, and for an upscale-only one. Without
         * this the bar named whichever SD checkpoint happened to be selected
         * and marked it `(idle)` — which is true of that checkpoint and
         * describes nothing the open flow will do. Reported from the phone,
         * 2026-09-13: *"in t2v flow top bar says qteamix (idle)"*.
         *
         * ⚠ The same test the RUN path already makes before launching a
         * backend with no model ([contextKeyModels]); it was simply never
         * asked by the readout.
         */
        val graphNeedsCheckpoint: Boolean = true,
        /** ⚠ A checkpoint-free graph that reaches the NPU in-process. */
        val graphIsVideo: Boolean = false,
        /**
         * ⭐⭐⭐ **The checkpoint(s) the OPEN GRAPH names**, in node order.
         *
         * ⚠⚠ The readout used [SelectedModel] when nothing was resident — the
         * global picker, which is not what the flow will load once a node can
         * carry its own checkpoint. Change a sampler's model and the top bar
         * kept naming the old one. Reported from the phone, 2026-09-15:
         * *"the 'holding' at top bar of canvas isnt updated for model
         * changes"*. ⚠ The rule was already written two branches away — *name
         * what the OPEN FLOW would use, not what the picker happens to be set
         * to* — and applied only to the video case.
         *
         * ⚠ A LIST because a graph may name two since 2026-09-15; the readout
         * says "+1" rather than pretending there is one.
         */
        val graphModels: List<String> = emptyList(),
        /**
         * ⭐⭐ Whether a run is in flight.
         *
         * ⚠⚠ Without it the line said **"(idle)" during a render**: the poll
         * is every 2 s and a backend launch takes 2.3-5 s, so the whole launch
         * window — and any gap before `launchedKey` is set — was described as
         * idle while the user watched the run bar count. Reported the same day
         * as *"sometimes even says idle on runs"*. "Idle" is a claim about the
         * machine, and this is the one fact that contradicts it.
         */
        val running: Boolean = false,
    )

    /**
     * ⚠⚠ Memoised on the set of node TYPES, because [refreshLoad] runs every
     * two seconds and resolving node types can build the QuickJS runtime for a
     * graph that names a pack. The answer cannot change without a type
     * appearing or disappearing.
     */
    private var keyNeedCacheFor: Set<String>? = null
    private var keyNeedCached: Pair<Boolean, Boolean> = true to false

    var load by mutableStateOf<CanvasLoad?>(null)
        private set

    /** @return (needs a checkpoint, is a video graph). ⚠ Memoised — see above. */
    private fun graphCheckpointNeed(): Pair<Boolean, Boolean> {
        val graph = canvas.workflow.graph
        val types = graph.nodes.mapTo(mutableSetOf()) { it.type }
        if (types == keyNeedCacheFor) return keyNeedCached
        val answer = runCatching {
            val needs = contextKeyModels(graph, typesFor(graph)).isNotEmpty()
            // ⚠ By DOMAIN, not by a list of type names: `<domain>.<name>` is
            // the contract a plugin depends on (`ARCHITECTURE.md` §5.3), so a
            // contributor's own `nd.*` node is covered without an entry here.
            val video = graph.nodes.any { it.type.startsWith("nd.") }
            needs to video
        }.getOrDefault(true to false)
        keyNeedCacheFor = types
        keyNeedCached = answer
        return answer
    }

    /**
     * ⚠ Cheap: one `ActivityManager` call and a `File.length()`. It is polled
     * a few times a minute while the canvas is up, because the number worth
     * seeing is the one DURING a render.
     */
    fun refreshLoad() {
        val ctx = getApplication<Application>()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        // ⚠ Only when the backend is actually UP. A checkpoint sitting on disk
        // is not "resident", and saying so would make the readout describe the
        // catalogue rather than the machine.
        //
        // ⚠⚠ **Read off the LAUNCH KEY, not off [SelectedModel].** The selection
        // is what the next graph would use; the launch key is what this process
        // actually has in memory, and they differ constantly — an upscale-only
        // graph holds no checkpoint at all, yet the readout named the t2i model
        // that merely happened to be selected. Reported from the phone
        // 2026-09-11. A process launched in `--upscaler_mode` has no model, so
        // `launchedKey` is null and the line correctly says nothing is held.
        val spec = BackendProcess.launchedKey?.model?.let { ModelCatalog.byId(it) }
        val needs = graphCheckpointNeed()
        // ⚠ Distinct labels in NODE order, so a two-checkpoint graph names the
        // one that runs first. ⚠ Falls back to the selected model for a graph
        // with no sampler at all, which is what the readout said before.
        val named = runCatching {
            contextKeyModels(canvas.workflow.graph, typesFor(canvas.workflow.graph))
                .mapNotNull { ModelCatalog.byId(it)?.label ?: it.takeIf { s -> s.isNotBlank() } }
                .distinct()
        }.getOrDefault(emptyList())
        load = CanvasLoad(
            ramFreeBytes = mi.availMem,
            ramTotalBytes = mi.totalMem,
            resident = spec?.label,
            // ⚠ The SAME function the residency gate uses, or the bar would
            // state a size the release decision disagrees with.
            residentBytes = spec?.loadedBytes(ctx) ?: 0L,
            // ⭐⭐ The OTHER route to the NPU. ⚠ Cheap in the same way the rest
            // of this is: a map size behind a lock, no file and no IPC.
            npuGraphs = com.abrah.nightmare.npu.QnnRunner.Resident.count(),
            graphNeedsCheckpoint = needs.first,
            graphIsVideo = needs.second,
            graphModels = named.ifEmpty { listOf(SelectedModel.spec.label) },
            running = busy,
        )
    }

    fun openWorkflow(w: Workflow, view: com.abrah.nightmare.canvas.SavedView? = null) {
        // ⚠ Derived on the way in: a workflow written before the sizes existed,
        // or by hand, must arrive correct rather than at the first edit.
        // ⚠⚠ The view the file recorded, or a FRESH one -- never the view the
        // user happened to be at. A recipe's nodes are laid out near the origin,
        // so the default viewport frames them; carrying the old offset over is
        // what opened a graph onto blank space.
        canvas = deriveSizes(CanvasState(w).withView(view))
        // ⚠ With the previews. A signature remembered against the OLD graph's
        // node of the same id would make [refreshPreviews] decide the new one is
        // already up to date.
        previewSigs.clear()
        canvasStatus.clear()
        runError = null
        clearRunLog()
        showWorkflows = false
        // ⚠ A recipe or a hand-built graph is not a saved workflow, so Save
        // must not offer a name that belongs to a different file. [openSaved]
        // sets it again immediately after calling this.
        currentWorkflowName = null
        // ⚠ The canvas is now what the user chose, and this has just written it
        // to the autosave. A restore afterwards could only put back what was
        // there before they chose.
        restored = true
        adoptGraphModel(w)
        saveWorkflow()
        refreshPreviews()
        // ⚠ The freshly opened graph IS the baseline. Without this every open
        // would leave the canvas instantly "dirty" against the previous flow.
        savedBaseline = w
        // ⚠ Cleared here and set again by [openResultFlow] AFTER this runs, so
        // opening anything else drops the result name rather than leaving the
        // run bar naming a picture the canvas no longer shows.
        openedResultName = null
    }

    fun openSaved(name: String) {
        // ⚠ A saved flow has a name of its own — suggesting "t2i_02" for one
        // called "harbour at dusk" would offer to fork it under a name that
        // says nothing about it.
        openedRecipeId = null
        try {
            val loaded = store.load(name)
            if (loaded == null) { workflowError = "\"$name\" is gone"; refreshWorkflows(); return }
            // ⚠ Named rather than silently dropped: a workflow using a pack that
            // is not installed opens with those nodes present and failing by
            // name, which is what tells the user which pack to fetch.
            val missing = loaded.requires.filter { nodeTypes.none { t -> t.key.startsWith(it.pluginId) } }
            if (missing.isNotEmpty()) {
                say("\"$name\" wants ${missing.joinToString { it.pluginId }}", bad = true)
            }
            guardedOpen(name) {
                openWorkflow(loaded.workflow, loaded.view)
                currentWorkflowName = name
                say("opened \"$name\"")
                sayNotes(loaded)
            }
        } catch (e: Exception) {
            workflowError = "could not open \"$name\" — ${e.message}"
        }
    }

    /**
     * ⭐⭐ Import a FLOW someone sent you — a `.json` written by Save.
     *
     * ⚠⚠ Imported under a name derived from the file, then OPENED, because the
     * two halves answer different questions: a file that lands in the library
     * and is not shown leaves the user hunting for what just happened.
     *
     * ⚠ The name is validated by the same rule Save uses, so an import cannot
     * create a file the library then refuses to show — and a clash gets a
     * suffix rather than silently overwriting someone's work.
     */
    fun importWorkflow(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                } ?: throw java.io.IOException("could not read that file")
                // ⚠ Parsed BEFORE anything is written. A malformed file must
                // fail as "this is not a workflow", not as a broken entry in
                // the library that fails again every time it is opened.
                val loaded = com.abrah.nightmare.canvas.workflowFromJson(text)
                val base = (uriDisplayName(uri) ?: "imported")
                    .substringBeforeLast('.')
                    .replace(Regex("[^A-Za-z0-9 _-]"), " ")
                    .trim()
                    .ifEmpty { "imported" }
                    .take(40)
                var name = base
                var n = 2
                while (store.saved().any { it.name == name }) {
                    name = "$base $n".take(40); n++
                }
                store.save(name, loaded.workflow, nodeTypes, loaded.view)
                name to loaded
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.fold(
                    onSuccess = { (name, loaded) ->
                        refreshWorkflows()
                        // ⚠ Through the guard, so importing over unsaved work
                        // asks first exactly as opening a saved flow does.
                        guardedOpen(name) {
                            openWorkflow(loaded.workflow, loaded.view)
                            currentWorkflowName = name
                            closeLibrary()
                            say("imported \"$name\"")
                            sayNotes(loaded)
                        }
                    },
                    onFailure = {
                        workflowError = "could not import that file — ${it.message}"
                    },
                )
            }
        }
    }

    /** ⚠ The provider's display name, or null. Used only to suggest a name. */
    private fun uriDisplayName(uri: android.net.Uri): String? =
        runCatching {
            getApplication<Application>().contentResolver
                .query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i) else null
                }
        }.getOrNull()

    fun deleteSaved(name: String) {
        store.delete(name)
        // ⚠ Save must stop offering the name of a file that no longer exists.
        if (currentWorkflowName == name) currentWorkflowName = null
        refreshWorkflows()
    }

    /** What the canvas shows for the model in use, installed or not. */
    val modelLabel: String
        get() {
            val spec = ModelCatalog.byId(SelectedModel.id) ?: return SelectedModel.id
            val here = spec.installed(getApplication())
            return if (here) spec.label else "${spec.label} (not installed)"
        }

    // ---- models ----------------------------------------------------------

    var showModels by mutableStateOf(false)
        private set

    fun setModelsVisible(on: Boolean) {
        showModels = on
        if (on) {
            libraryTab = com.abrah.nightmare.ui.LibraryTab.MODELS
            refreshModels()
            // ⭐⭐⭐ An UNKNOWN chip is measured here, not left as a guess.
            //
            // ⚠⚠ This screen is where the guess does its damage: every SDXL,
            // Anima and FLUX row reads "this device cannot run it", which is a
            // statement about the user's hardware that we do not actually
            // know. Reported by a Snapdragon 8 Gen 5 owner on 2026-09-20,
            // whose chip is not in `SOC_TO_ARCH` and so took the v68 / 2 MB
            // floor.
            //
            // ⚠ The measurement used to happen ONLY when the Device sheet was
            // opened — the one action nobody would think to take when the
            // models say they are unsupported. The cost note on
            // [setDeviceInfoVisible] still holds (it unpacks the QNN
            // libraries), which is why this is not at app start and not for a
            // chip the tables already know.
            if (DeviceProbe.needsMeasuring()) {
                viewModelScope.launch {
                    DeviceProbe.measure(getApplication())
                    // ⚠ The rows are computed from the caps, so a measurement
                    // that arrived without this would leave the picker showing
                    // the guess it was taken to replace.
                    refreshModels()
                }
            }
        }
    }

    /**
     * Which half of the library is showing.
     *
     * ⚠ Held here rather than in the composable so the tab SURVIVES a
     * configuration change and a trip to the canvas and back — a user comparing
     * a recipe against their checkpoints should not be returned to Models every
     * time they look away.
     */
    var libraryTab by mutableStateOf(com.abrah.nightmare.ui.LibraryTab.MODELS)
        private set

    fun switchLibraryTab(t: com.abrah.nightmare.ui.LibraryTab) {
        libraryTab = t
        // ⚠ Each tab refreshes what it draws from DISK on the way in, for the
        // same reason [refreshModels] does: a cache here would need invalidating
        // from install, delete, save and rename alike, and the one that got
        // missed would show a model as present after it was removed.
        when (t) {
            com.abrah.nightmare.ui.LibraryTab.MODELS -> refreshModels()
            com.abrah.nightmare.ui.LibraryTab.FLOWS -> refreshWorkflows()
            com.abrah.nightmare.ui.LibraryTab.RESULTS -> refreshResults()
        }
    }

    /** The rows the picker draws. Recomputed from disk rather than remembered. */
    var modelRows by mutableStateOf<List<com.abrah.nightmare.ui.ModelRow>>(emptyList())
        private set

    var modelError by mutableStateOf<String?>(null)
        private set

    /**
     * ⭐⭐ **How the app died last time, if it did** — null on every ordinary
     * launch, which is almost all of them ([CrashReport]).
     *
     * ⚠⚠ Read ONCE, here, rather than by the composable: `CrashReport.pending`
     * consumes the breadcrumb and records that the exit has been reported, so
     * calling it from a recomposition would show the report and then lose it.
     */
    var crashReport by mutableStateOf<CrashReport.Report?>(null)
        private set

    /** ⚠ The user has read it. [CrashReport] has already recorded that it was shown. */
    fun dismissCrashReport() { crashReport = null }

    /** Non-null while an install is running; the id being fetched. */
    private var installing by mutableStateOf<String?>(null)

    /**
     * ⭐⭐ The import in flight, for the banner that says one is happening.
     *
     * ⚠⚠ **Exposed separately because the model ROW cannot show it.** A row's
     * progress is matched as `spec.id == installing`, and during an import
     * there IS no spec: the directory is still being written and
     * [CustomModels.scan] has not seen it, so the row the progress belongs to
     * does not exist until the work is already finished. The bar was plumbed
     * end to end and rendered nowhere — reported from the phone 2026-09-11 as
     * "no idea if importing is in progress".
     *
     * ⚠ A DOWNLOAD is unaffected: its row is a catalogue entry that exists
     * before, during and after, which is exactly why this was never noticed.
     */
    val importing: String? get() = installing
        // ⚠⚠⚠ **"Not in the catalogue" is not the same as "an import".**
        // This derived an import from the ABSENCE of a catalogue entry, which
        // was true of exactly one thing when it was written. The video models
        // then started sharing [installing] as the one-download-at-a-time latch
        // under a sentinel id that is deliberately in no catalogue — so an
        // 8.6 GB DOWNLOAD lit the "Importing …" banner. Reported from the
        // phone, 2026-09-13: *"why does it say importing when i download"*.
        // ⇒ Name the thing that is not an import, rather than inferring it.
        ?.takeIf { it != VIDEO_INSTALL_ID && ModelCatalog.byId(it) == null }

    /** The phase/bytes of [importing], or null. */
    val importProgress: ModelInstaller.Progress? get() = if (importing != null) installProgress else null
    @Volatile private var cancelInstall = false
    private var installProgress by mutableStateOf<ModelInstaller.Progress?>(null)

    /**
     * ⭐⭐ The ONE place a download's progress reaches the UI: the field, plus
     * the single row that is downloading.
     *
     * ⚠⚠ **Never a `refresh*()` from here.** Those re-read the DISK — 24 specs'
     * required files, a recursive `walkTopDown` per installed model, the video
     * directory listed once per graph, `CustomModels.scan` — roughly 500 stats,
     * on the main thread. `installModel` called `refreshModels()` per progress
     * tick, so a 4.3 GB Anima download ran that ~4,300 times and the whole app
     * (and the system's input dispatch) stuttered for the length of the
     * download. Reported from the phone, 2026-09-17.
     *
     * ⚠ [com.abrah.nightmare.npu.VideoInstaller]'s caller already had this fix
     * and the other three installers did not — the rule was honoured in one
     * place of four. ⇒ All four call this now.
     */
    private fun tickProgress(p: ModelInstaller.Progress) {
        installProgress = p
        val id = installing ?: return
        when {
            id == VIDEO_INSTALL_ID -> videoRow = videoRow?.copy(progress = p)
            id == SEGMENTER_INSTALL_ID -> segmenterRow = segmenterRow?.copy(progress = p)
            modelRows.any { it.spec.id == id } ->
                modelRows = modelRows.map { if (it.spec.id == id) it.copy(progress = p) else it }
            upscalerRows.any { it.spec.id == id } ->
                upscalerRows = upscalerRows.map { if (it.spec.id == id) it.copy(progress = p) else it }
        }
        // ⭐⭐ …and the shade. Asked for 2026-09-15 and never wired: only the
        // FAILURE ever reached a notification (PROGRESS.md). ⚠ Its own ~1 s
        // throttle: each update is a Binder call to system_server, and this
        // tick fires every 150 ms.
        // ⭐ …and the breadcrumb, for the OTHER thing that gets reclaimed: a
        // multi-gigabyte download or import ([CrashReport]). Idempotent, so the
        // 150 ms tick costs one file write per job rather than one per tick.
        CrashReport.mark(getApplication(), "downloading ${downloadLabel(id)}")
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastNoticeAt >= NOTICE_TICK_MS) {
            lastNoticeAt = now
            DownloadNotice.progress(
                getApplication(), downloadLabel(id), p.phase,
                p.fraction.takeIf { p.total > 0 },
            )
        }
    }

    /**
     * ⭐⭐⭐ **What this run is about to load, for the crash report to quote.**
     *
     * ⚠⚠⚠ Asked for 2026-09-21, after a Z-Image render at 2048² was killed
     * and the dialog could only say *"it happened while rendering"*. For a
     * memory kill that is the ONLY useful thing there is to say: Android writes
     * no tombstone for an lmkd kill (`trace=null`, `description=null`, measured
     * on this phone), so there is no log to show and never will be. What there
     * is, is the checkpoint and the size — and the size is the knob
     * (`docs/MODELS.md` §9).
     *
     * ⚠⚠ The node's OWN `width`/`height`, never [contextKeyResolutions]. A DiT
     * sampler's size is not part of its context key — the key pins the model's
     * native 1024² — so asking the key would have reported 1024x1024 for the
     * 2048² render that actually died, which is worse than saying nothing.
     *
     * ⚠ Every step is wrapped: a breadcrumb is a diagnostic and must never be
     * the reason a run fails to start.
     */
    private fun renderingBreadcrumb(): String = runCatching {
        val samplers = canvas.workflow.graph.nodes.filter { it.type in SAMPLER_TYPES }
        val what = samplers.mapNotNull { n ->
            val model = n.params["model"]?.takeIf { it.isNotBlank() }
                ?.let { ModelCatalog.byId(it)?.label ?: it }
            val w = n.params["width"]?.toIntOrNull()
            val h = n.params["height"]?.toIntOrNull()
            when {
                model != null && w != null && h != null -> "$model at ${w}x$h"
                model != null -> model
                else -> null
            }
        }.distinct()
        when {
            what.isEmpty() -> "rendering"
            // ⚠ A multi-checkpoint graph names them all: the executor schedules
            // both, and which one was resident when it died is the question.
            else -> "rendering ${what.joinToString(" and ")}"
        }
    }.getOrDefault("rendering")

    private var lastNoticeAt = 0L

    /** ⚠ What the shade and the toast call the thing downloading — one lookup for all four installers. */
    private fun downloadLabel(id: String): String = when (id) {
        VIDEO_INSTALL_ID -> "Video models"
        SEGMENTER_INSTALL_ID -> com.abrah.nightmare.segment.Segmenter.LABEL
        else -> ModelCatalog.byId(id)?.label ?: UpscalerCatalog.byId(id)?.label ?: id
    }

    /**
     * ⭐⭐ The three ways a download ends, said the SAME way by all four
     * installers (checkpoint, video, upscaler, segmenter). ⚠ Before this only
     * the checkpoint path reported anything, and only a failure — the rule was
     * honoured in one place of four, and in half of that one.
     */
    private fun downloadSucceeded(label: String) {
        DownloadNotice.done(getApplication(), label, ok = true)
        toast("$label downloaded")
    }

    private fun downloadFailed(label: String, why: String) {
        DownloadNotice.done(getApplication(), label, ok = false, detail = why)
        toast("$label failed — $why")
    }

    /** ⚠ Stopped by the user: no outcome to report, so the row simply goes. */
    private fun downloadCancelled() = DownloadNotice.clear(getApplication())

    /**
     * ⚠ Reads the disk on every call. The alternative is a cache that has to be
     * invalidated by install, delete and cancel alike -- and the one that gets
     * missed shows a model as installed after it was removed.
     */
    fun refreshModels() {
        val ctx = getApplication<Application>()
        // ⭐⭐⭐ **The shade's backstop.** An ONGOING row with nothing
        // installing is a row nobody will ever end — and the user cannot swipe
        // it away, because `setOngoing(true)` forbids it.
        //
        // ⚠⚠ Here because every path that installs or imports anything already
        // calls this on its way out, success or failure, so it is the one place
        // that covers all of them AND the next one somebody adds. The bug it
        // ends: both import paths posted progress and reported no outcome, so a
        // custom Z-Image import pinned "importing" in the shade for good
        // ([DownloadNotice.live]). Reported from the phone 2026-09-21.
        //
        // ⚠ Not a substitute for reporting the outcome — `downloadSucceeded`
        // and `downloadFailed` say what HAPPENED, and this only guarantees that
        // nothing is left spinning when they are forgotten.
        if (installing == null && DownloadNotice.live) DownloadNotice.clear(ctx)
        // ⚠ …and the breadcrumb, for the same reason and in the same place: an
        // install that ended left one behind on every path that did not go
        // through `run`'s finally ([CrashReport]).
        if (installing == null && !busy) CrashReport.clear(ctx)
        // ⚠ The upscalers ride along: this is the app's "re-read the disk"
        // entry point and a second one would be a second thing to forget.
        refreshUpscalers()
        // ⚠ …and the recipes' view of what is installed, for the same reason.
        ModelCatalog.refreshInstalled(ctx)
        refreshSegmenter()
        refreshEmbeddings()
        // ⚠⚠ …and the LoRAs. It was missed when they landed, which is exactly
        // what the comment above warns about: the list stayed empty until an
        // import happened to refresh it, so a file already in `_loras` was
        // invisible to both the Models tab and the node's picker.
        refreshLoras()
        // ⚠ …and the video models, for the same reason. ⚠⚠ `probeVideoSupport`
        // is NOT called here: it starts the QNN backend, which is seconds, and
        // this runs every time the library opens. The tab asks for it itself.
        refreshVideoModels()
        // ⚠ …and so does the reachable-size cache, for exactly that reason. A
        // download that has just landed brings six patch files with it, and the
        // size chips read a cache rather than the disk ([SelectedModel.refresh]).
        SelectedModel.refresh(ctx)
        // ⚠⚠ Rescan first. A custom model directory is normally copied onto the
        // phone WHILE the app is running (adb, a file manager, a share), so the
        // catalogue read on the next line is stale by construction unless this
        // runs -- and this function is already the app's "re-read the disk"
        // entry point, called on every open of the Models tab.
        CustomModels.scan(ctx)
        modelRows = ModelCatalog.all.map { spec ->
            val here = spec.installed(ctx)
            com.abrah.nightmare.ui.ModelRow(
                spec = spec,
                // ⚠ Recomputed per refresh rather than cached: the answer
                // changes the first time [DeviceProbe.measure] returns, which is
                // after the first backend start.
                build = spec.buildFor(DeviceProbe.caps()),
                installed = here,
                selected = spec.id == SelectedModel.id,
                progress = if (spec.id == installing) installProgress else null,
                onDisk = if (here) spec.bytesOnDisk(ctx) else 0,
                // ⚠ Only read for a custom row, but computed for every one: a
                // conditional here would be a second place that knows what
                // `isCustom` means, and it is one cheap `exists()` per file.
                missing = if (here) emptyList() else spec.missing(ctx),
            )
        }
    }


    /**
     * Imports a picked zip as a custom checkpoint, then selects it.
     *
     * ⭐ Selecting it afterwards is the point: an import that leaves the app on
     * the previous checkpoint reads as having failed. ⚠ Only when it is
     * COMPLETE -- selecting a half-imported model would launch the backend
     * against a directory missing a file and fail with a path.
     *
     * ⚠ Reuses [installing]/[installProgress] so the card shows one progress
     * bar and `busy` gates the same buttons. An import is an install that
     * happens to have no download phase.
     */
    /**
     * ⭐ The name for an import the user did not name — the picked file's display
     * name, made safe and never colliding ([CustomModels.nameFromFile]).
     */
    fun importNameFor(uri: android.net.Uri): String {
        val ctx = getApplication<Application>()
        val display = runCatching {
            ctx.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        val taken = ModelCatalog.builtIn.map { it.id }.toSet() +
            ModelCatalog.root(ctx).listFiles().orEmpty().map { it.name }
        return CustomModels.nameFromFile(display, taken)
    }

    fun importModel(uri: android.net.Uri, name: String) {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = name
        cancelInstall = false
        modelError = null
        // ⚠ Indeterminate: a zip's uncompressed size is not known until it is
        // read, and a bar sitting at 100% through a minute of unpacking reads
        // as a hang. Same choice ModelInstaller's extract phase makes.
        installProgress = ModelInstaller.Progress("importing", 0, 0)
        refreshModels()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val spec = CustomModels.import(
                    ctx, name,
                    open = {
                        ctx.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("cannot read the picked file")
                    },
                    onProgress = { p -> viewModelScope.launch { tickProgress(p) } },
                    isCancelled = { cancelInstall },
                )
                val missing = spec.missing(ctx)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    if (missing.isEmpty()) {
                        // ⚠⚠ The shade, not just the screen — see [DownloadNotice.live].
                        downloadSucceeded(spec.label)
                        selectModel(spec)
                    } else {
                        modelError = "${spec.label} imported but is incomplete: " +
                            "missing ${missing.joinToString()}"
                        downloadFailed(spec.label, modelError!!)
                    }
                    refreshModels()
                }
            } catch (e: ModelInstaller.Cancelled) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    downloadCancelled()
                    refreshModels()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    modelError = "import failed: ${e.message}"
                    downloadFailed(downloadLabel(name), modelError!!)
                    refreshModels()
                }
            }
        }
    }

    /**
     * ⭐⭐ Import a plain `.safetensors` as a model of [family].
     *
     * ⚠ The same shape as [importModel] on purpose — one install at a time,
     * progress through [tickProgress], the error left on screen rather than
     * toasted away. The only difference is which [CustomModels] entry point
     * it calls and that the family is told rather than inferred.
     */
    fun importDitModel(uri: android.net.Uri, name: String, family: Family) {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = name
        cancelInstall = false
        modelError = null
        installProgress = ModelInstaller.Progress("importing", 0, 0)
        refreshModels()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val spec = CustomModels.importDit(
                    ctx, name, family,
                    open = {
                        ctx.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException("cannot read the picked file")
                    },
                    onProgress = { p -> viewModelScope.launch { tickProgress(p) } },
                    isCancelled = { cancelInstall },
                )
                val missing = spec.missing(ctx)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    if (missing.isEmpty()) {
                        // ⚠⚠ The shade, not just the screen — see [DownloadNotice.live].
                        downloadSucceeded(spec.label)
                        selectModel(spec)
                    } else {
                        modelError = "${spec.label} imported but is incomplete: " +
                            "missing ${missing.joinToString()}"
                        downloadFailed(spec.label, modelError!!)
                    }
                    refreshModels()
                }
            } catch (e: ModelInstaller.Cancelled) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    downloadCancelled()
                    refreshModels()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    modelError = "import failed: ${e.message}"
                    downloadFailed(downloadLabel(name), modelError!!)
                    refreshModels()
                }
            }
        }
    }

    fun installModel(spec: ModelSpec) {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = spec.id
        cancelInstall = false
        modelError = null
        // ⚠ The build the DEVICE can take, and null means it can take none --
        // refused up front rather than after 3.5 GB.
        val build = spec.buildFor(DeviceProbe.caps())
        if (build == null) {
            modelError = "${spec.label} needs an HTP arch of " +
                "${spec.builds.minOf { it.minArch }} or newer; this device reports " +
                "${DeviceProbe.caps().arch}"
            installing = null
            return
        }
        installProgress = ModelInstaller.Progress("starting", 0, build.bytes)
        refreshModels()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                ModelInstaller.install(
                    ctx, spec, build,
                    onProgress = { p ->
                        // ⚠ Hopped to the main thread: this fires from the
                        // download loop, and Compose state written off it is a
                        // race that shows up as a frozen bar rather than a crash.
                        viewModelScope.launch { tickProgress(p) }
                    },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed ${spec.label}")
                    downloadSucceeded(spec.label)
                    // ⭐ First model in becomes the one in use. Otherwise a user
                    // downloads a model, presses Run, and renders against a
                    // model they do not have.
                    if (ModelCatalog.byId(SelectedModel.id)?.installed(ctx) != true) {
                        SelectedModel.set(ctx, spec.id)
                    }
                }
            } catch (e: ModelInstaller.Cancelled) {
                // ⚠ No outcome to report, so the row goes rather than sitting
                // there saying "failed" about something the user stopped.
                viewModelScope.launch {
                    downloadCancelled()
                    say("download cancelled", bad = true)
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    downloadFailed(spec.label, modelError!!)
                    say("install failed — $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    refreshModels()
                }
            }
        }
    }

    fun cancelModelInstall() { cancelInstall = true }

    // ---- upscalers -------------------------------------------------------

    /**
     * ⭐⭐ The SECOND kind of model, listed separately because it is a
     * different thing (`Upscalers.kt`): one loose weight file, no launch
     * contract, no context key, loaded per request and freed after.
     *
     * ⚠ It shares [installing]/[installProgress] with the checkpoint installer
     * on purpose — one download at a time, one progress bar, and no way to
     * start a 3.5 GB checkpoint and a 24 MB upscaler into the same latch.
     */
    var upscalerRows by mutableStateOf<List<com.abrah.nightmare.ui.UpscalerRow>>(emptyList())
        private set

    /**
     * ⚠ A sentinel for [installing], which is a MODEL id everywhere else. The
     * video models share the one-download-at-a-time latch with the checkpoints
     * deliberately: one progress bar, and no way to start 8.5 GB and 3.5 GB
     * into the same field.
     */
    private val VIDEO_INSTALL_ID = "\u0000video"

    fun refreshUpscalers() {
        val ctx = getApplication<Application>()
        // ⚠ The node's dropdown reads a cache, not the disk -- refresh it here,
        // where the disk is being read anyway, or a just-downloaded upscaler
        // stays invisible to every upscale node until the app restarts.
        UpscalerCatalog.refresh(ctx)
        // ⚠ + the imported ones (`UpscalerCatalog.scanned`), so a custom
        // upscaler gets a row exactly like a catalogue one.
        upscalerRows = UpscalerCatalog.allSpecs().map { spec ->
            val here = spec.installed(ctx)
            com.abrah.nightmare.ui.UpscalerRow(
                spec = spec,
                build = spec.buildFor(DeviceProbe.caps()),
                installed = here,
                progress = if (spec.id == installing) installProgress else null,
                onDisk = if (here) spec.bytesOnDisk(ctx) else 0,
            )
        }
    }

    /**
     * ⭐⭐ The video models as one row. Null until [refreshVideoModels] runs.
     *
     * ⚠ Null also hides the tab, which is what a device that has never run
     * the canary should show — rather than an 8.5 GB Download button beside a
     * question nobody has asked yet.
     */
    var videoRow by mutableStateOf<com.abrah.nightmare.ui.VideoRow?>(null)
        private set

    /**
     * ⚠ The canary's verdict, remembered for the session. It brings the whole
     * QNN backend up, so it is seconds on a cold app and must not run on every
     * tab change.
     */
    private var videoSupported: Boolean? = null

    fun refreshVideoModels() {
        val ctx = getApplication<Application>()
        val vi = com.abrah.nightmare.npu.VideoInstaller
        videoRow = com.abrah.nightmare.ui.VideoRow(
            installedBytes = vi.installedBytes(ctx),
            totalBytes = vi.totalBytes,
            missing = vi.missing(ctx),
            // ⚠ The INSTALLER's check, which is size-aware — `NpuFiles`'s only
            // asks whether the file exists, which is the right question for a
            // render and the wrong one for a download that may have truncated.
            weightsMissing = vi.missingAssets(ctx),
            supported = videoSupported,
            progress = if (installing == VIDEO_INSTALL_ID) installProgress else null,
        )
    }

    /**
     * ⭐⭐ Ask the chip before offering the download — by RUNNING a context
     * binary, not by reading a chip name (`docs/DEVICES.md` §2).
     *
     * ⚠ Off the main thread and once per session; it starts the QNN backend.
     */
    fun probeVideoSupport() {
        if (videoSupported != null) return
        val ctx = getApplication<Application>()
        // ⭐ The gate's cached answer first ([com.abrah.nightmare.npu.VideoGate]),
        // so the canary runs once per app version, not once per session.
        com.abrah.nightmare.npu.VideoGate.supported?.let {
            videoSupported = it
            refreshVideoModels()
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.abrah.nightmare.npu.VideoGate.probeIfNeeded(ctx, BuildConfig.VERSION_CODE) {
                runCatching {
                    com.abrah.nightmare.npu.NpuCanary.run(ctx, com.abrah.nightmare.npu.QnnRunner(ctx))
                }.getOrNull()
            }
            viewModelScope.launch {
                // ⚠⚠ Permissive, matching `NpuCanary.canDownload`: only a REAL
                // refusal blocks a phone. A check that could not run must not
                // lock out a device that would have worked.
                videoSupported = com.abrah.nightmare.npu.VideoGate.supported ?: true
                refreshVideoModels()
            }
        }
    }

    fun installVideoModels() {
        if (installing != null) return
        val ctx = getApplication<Application>()
        val vi = com.abrah.nightmare.npu.VideoInstaller
        installing = VIDEO_INSTALL_ID
        cancelInstall = false
        modelError = null
        installProgress = ModelInstaller.Progress("starting", 0, vi.totalBytes)
        refreshVideoModels()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                vi.install(
                    ctx,
                    onProgress = { p -> viewModelScope.launch { tickProgress(p) } },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed the video models")
                    downloadSucceeded(downloadLabel(VIDEO_INSTALL_ID))
                }
            } catch (e: ModelInstaller.Cancelled) {
                // ⚠ Not an error: every completed file is kept and the next
                // attempt resumes from it.
                viewModelScope.launch {
                    downloadCancelled()
                    say("download cancelled — what arrived is kept", bad = true)
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    downloadFailed(downloadLabel(VIDEO_INSTALL_ID), modelError!!)
                    say("video install — $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    refreshVideoModels()
                }
            }
        }
    }

    /**
     * ⚠⚠ Deletes the GRAPHS only, never the host-side weights. Those cannot
     * be re-downloaded (`docs/NEODRAGON.md` §6), so removing them would take
     * something the user may have no way to get back.
     */
    fun deleteVideoModels() {
        val dir = com.abrah.nightmare.npu.NpuFiles.ctxDir(getApplication())
        val n = dir.listFiles()?.count { it.isFile && it.delete() } ?: 0
        say("deleted $n video graph(s)")
        refreshVideoModels()
    }

    fun installUpscaler(spec: UpscalerSpec) {
        if (installing != null) return
        val ctx = getApplication<Application>()
        val build = spec.buildFor(DeviceProbe.caps())
        if (build == null) {
            modelError = "${spec.label} needs an HTP arch of " +
                "${spec.builds.minOf { it.minArch }} or newer; this device reports " +
                "${DeviceProbe.caps().arch}"
            return
        }
        installing = spec.id
        cancelInstall = false
        modelError = null
        installProgress = ModelInstaller.Progress("starting", 0, build.bytes)
        refreshUpscalers()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                UpscalerCatalog.install(
                    ctx, spec, build,
                    onProgress = { p ->
                        viewModelScope.launch { tickProgress(p) }
                    },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed ${spec.label}")
                    downloadSucceeded(spec.label)
                }
            } catch (e: ModelInstaller.Cancelled) {
                viewModelScope.launch {
                    downloadCancelled()
                    say("download cancelled", bad = true)
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    downloadFailed(spec.label, modelError!!)
                    say("install failed — $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    refreshUpscalers()
                }
            }
        }
    }

    // ---- History: upscale a kept picture -----------------------------------

    /** ⭐ The upscaler's label while a History upscale runs, else null. */
    var upscalingResult by mutableStateOf<String?>(null)
        private set

    /**
     * ⭐⭐ Enlarge a kept picture and keep the result as a NEW item — DreamUI's
     * History upscale (the user's call, 2026-09-17).
     *
     * ⚠ The same graph the `save_upscaled` op runs (photo → upscale), through the
     * same [run] latch as a canvas Run: it needs the backend, and two jobs
     * racing for it is the thing that latch exists to stop. ⚠ An upscaler binds
     * no context key, so it runs in whichever backend is up.
     */
    fun upscaleResult(id: String, upscalerId: String) {
        val spec = UpscalerCatalog.byId(upscalerId) ?: return
        val file = results.imageFile(id)
        if (!file.isFile) {
            say("that picture's file is gone", bad = true)
            return
        }
        val g = com.abrah.nightmare.Graph(
            listOf(
                com.abrah.nightmare.Node(
                    "photo", "core.image",
                    // ⚠⚠ The PATH, not a `file://` URI: `core.image` reads any
                    // non-`content://` string as a path, so a `file://` one looked
                    // for a file literally named that and every upscale failed.
                    params = mapOf("uri" to file.absolutePath),
                ),
                com.abrah.nightmare.Node(
                    "upscale", "image.upscale",
                    params = mapOf(UpscaleNode.UPSCALER to upscalerId),
                    inputs = com.abrah.nightmare.sources("image" to "photo"),
                ),
            )
        )
        val wf = com.abrah.nightmare.canvas.Workflow(g, emptyMap())
        run("upscale") {
            upscalingResult = spec.label
            toast("Upscaling with ${spec.label}…")
            try {
                if (!ops.ensureBackendFor(g, nodeTypes)) {
                    say("upscale: no backend", bad = true)
                    toast("could not start the backend to upscale")
                    return@run
                }
                val r = ops.runWorkflow(wf)
                val img = r.outputs["upscale"]?.previewImage()
                if (r.error != null || img == null) {
                    val why = r.error ?: r.runs.lastOrNull { it.outcome == Outcome.FAILED }?.detail ?: "no picture"
                    // ⚠ Logged AND toasted: a toast alone left nothing to read
                    // when this failed on the phone (2026-09-17).
                    say("upscale failed — $why", bad = true)
                    toast("upscale failed — $why")
                    return@run
                }
                keepResult(img.id, flow = wf)
                toast("Upscaled — kept as a new result")
            } finally {
                upscalingResult = null
            }
        }
    }

    /**
     * ⭐⭐⭐ **Which output node is waiting for an upscaler to be picked**, or
     * null. The canvas draws [com.abrah.nightmare.ui.UpscalePicker] for it — the
     * SAME chooser Results opens.
     */
    var upscaleNodePick by mutableStateOf<String?>(null)
        private set

    fun offerUpscaleNode(nodeId: String) { upscaleNodePick = nodeId }
    fun cancelUpscaleNode() { upscaleNodePick = null }

    /**
     * ⭐⭐⭐ **Upscale what an output node is showing, by editing the flow.**
     *
     * Asked for 2026-09-22: trace the wire behind the output, put an upscale
     * node in between, pin the seed so the picture is not regenerated, Run.
     *
     * ⚠⚠ **The seed pinning is what makes it cheap rather than a second full
     * render.** Every rolling sampler upstream is pinned to the seed its last
     * Run rolled — the same walk [lockSeedsFor]-style edits already do — so the
     * executor serves those nodes from cache and only the new node runs.
     * ⚠ A graph that has never run pins nothing and simply renders; there is no
     * previous picture to preserve.
     *
     * ⚠ The inserted node STAYS (the user's call): it is an ordinary edit,
     * deletable, and saved with the flow.
     */
    fun upscaleFromNode(nodeId: String, upscalerId: String) {
        upscaleNodePick = null
        val node = canvas.workflow.graph.byId[nodeId] ?: return
        if (node.type != MediaOutputNode.name) {
            toast("Upscaling is a checkbox on the output node")
            return
        }
        // ⭐⭐⭐ **It TICKS the checkbox; it does not add a node.**
        //
        // ⚠⚠⚠ It used to insert an `image.upscale` between this output and
        // whatever fed it. That node is deleted now (2026-09-22), and the user
        // had asked for it to be — a button that answered "upscale this" by
        // building the very node they wanted gone was the complaint. ⇒ The
        // output does the enlargement itself, and this sets the two params that
        // say so.
        //
        // ⚠ No seed pinning any more, and none is needed: nothing upstream
        // changes, so every node before this one comes back from the cache and
        // only the enlargement runs. The pinning existed because INSERTING a
        // node re-keyed the graph.
        updateCanvas(
            canvas.setParams(
                nodeId,
                mapOf(
                    MediaOutputNode.UPSCALE to "true",
                    MediaOutputNode.UPSCALER to upscalerId,
                ),
            )
        )
        runCanvasOrBatch()
    }

    /**
     * ⭐⭐ A node's picture, described the way a kept result is — the SAME
     * dialog ([com.abrah.nightmare.ui.ResultInfoDialog]) over the LIVE graph.
     *
     * ⚠ Asked for 2026-09-22: the output viewer had no ⓘ where Results has one.
     */
    fun detailsOfNode(nodeId: String): List<Pair<String, String>> {
        val g = canvas.workflow.graph
        // ⚠ The picture this node is SHOWING, for the Output line. `previews`
        // holds the id and its aspect, and the store knows the pixels.
        val shown = canvas.rendered[nodeId] ?: canvas.previews[nodeId]?.first
        val bmp = shown?.let { imageFor(it) }
        return detailsOfGraph(
            g, nodeId,
            width = bmp?.width ?: 0,
            height = bmp?.height ?: 0,
            times = { id -> if (id == TOTAL_MS_KEY) lastRunMs else nodeMs[id] },
        ) { id ->
            com.abrah.nightmare.canvas.seedFor(g, id) { n -> canvasStatus[n]?.detail }
        }
    }


    // ---- a flow naming a model that is not here -----------------------------

    /**
     * ⭐⭐ The download a Run is waiting on — asked in a popup ON THE CANVAS
     * instead of failing with "open Models and download it" (the user's call,
     * 2026-09-17).
     *
     * ⚠⚠ EVERY kind of model a flow can need, not only checkpoints: a deleted
     * segmenter ran straight past this and failed later by name, which is the
     * failure the popup exists to replace (reported 2026-09-17).
     */
    sealed interface MissingModel {
        val label: String
        /** The id [installing] holds while this one downloads. */
        val installId: String

        /**
         * @param wanted the checkpoint id the flow names.
         * @param substitute true when [offer] is not [wanted] — an imported
         *   checkpoint has no download, so accepting also points the flow at [offer].
         */
        data class Checkpoint(val wanted: String, val offer: ModelSpec, val substitute: Boolean) : MissingModel {
            override val label get() = offer.label
            override val installId get() = offer.id
        }

        data object Segment : MissingModel {
            override val label get() = com.abrah.nightmare.segment.Segmenter.LABEL
            override val installId get() = "\u0000segmenter"
        }

        data class Upscale(val spec: UpscalerSpec) : MissingModel {
            override val label get() = spec.label
            override val installId get() = spec.id
        }

        /**
         * ⭐⭐ The FLUX.2 / Z-Image engine, missing while its WEIGHTS are here.
         *
         * ⚠ Only reachable for someone who installed a DiT model before
         * 1.5.502, when the engine shipped inside the APK: an app update
         * replaces the native library dir, so their 6.7 GB of weights outlived
         * the 22 MB that runs them. A fresh install never sees this — the
         * engine comes down with the model (`ModelInstaller.installOnce`).
         */
        data object Engine : MissingModel {
            override val label get() = com.abrah.nightmare.DitEngine.LABEL
            override val installId get() = "\u0000ditengine"
        }
    }

    var missingModel by mutableStateOf<MissingModel?>(null)
        private set

    /** ⭐ Bytes the download will cost, for the popup's sentence. */
    fun missingBytes(m: MissingModel): Long = when (m) {
        is MissingModel.Checkpoint -> m.offer.buildFor(DeviceProbe.caps())?.bytes ?: m.offer.best?.bytes ?: 0L
        MissingModel.Segment -> com.abrah.nightmare.segment.Segmenter.BYTES
        is MissingModel.Upscale -> m.spec.buildFor(DeviceProbe.caps())?.bytes ?: 0L
        MissingModel.Engine -> com.abrah.nightmare.DitEngine.BYTES
    }

    /** ⭐ The download's progress, for the popup. Null when not fetching it. */
    val missingProgress: ModelInstaller.Progress?
        get() = missingModel?.let { m -> if (installing == m.installId) installProgress else null }

    /** ⭐ Whether it is on the phone now — the popup turns into Run. */
    val missingInstalled: Boolean
        get() = when (val m = missingModel) {
            null -> false
            is MissingModel.Checkpoint -> modelRows.any { it.spec.id == m.offer.id && it.installed }
            MissingModel.Segment -> segmenterRow?.installed == true
            is MissingModel.Upscale -> upscalerRows.any { it.spec.id == m.spec.id && it.installed }
            MissingModel.Engine -> com.abrah.nightmare.DitEngine.installed
        }

    /**
     * True when every model the canvas needs is here. Otherwise sets
     * [missingModel] for the FIRST that is not, and returns false.
     *
     * ⚠ Asked before the backend is touched: a launch against a missing
     * directory fails seconds later naming a path.
     */
    private fun modelsPresentOrAsk(): Boolean {
        val ctx = getApplication<Application>()
        val graph = canvas.workflow.graph
        val types = typesFor(graph)
        val caps = DeviceProbe.caps()
        for (n in graph.nodes) {
            val t = types[n.type] as? SdSampler ?: continue
            val id = com.abrah.nightmare.applyDefaults(t.widgets, n)["model"].orEmpty()
            val spec = ModelCatalog.byId(id)
            if (spec != null && spec.installed(ctx)) {
                // ⭐⭐ Weights present is not "ready" for a DiT family: the
                // engine that runs them is a download too, and an app update
                // takes it away (`MissingModel.Engine`).
                if (spec.isDit && !com.abrah.nightmare.DitEngine.isInstalled(ctx)) {
                    missingModel = MissingModel.Engine
                    return false
                }
                continue
            }
            val offer = spec?.takeIf { !it.isCustom && it.buildFor(caps) != null }
                ?: ModelCatalog.all.firstOrNull { it.family == t.family && !it.isCustom && it.buildFor(caps) != null }
                ?: continue
            missingModel = MissingModel.Checkpoint(id, offer, substitute = offer.id != id)
            return false
        }
        // ⭐⭐⭐ **The segmenter is demanded ONLY by a mask that was actually
        // TAPPED**, never by the feature being switched on.
        //
        // ⚠⚠⚠ Reported 2026-09-22: *"in inpaint flow even if i dont use tap
        // to segment and click run, i got window saying to download segment
        // model"*. The clause was `n.type == SelectObjectNode.name`, and the
        // Inpaint recipe wired one of those in by default — so every inpaint
        // Run demanded an 80 MB download for a tool the person had not touched.
        // The node is deleted now and the checkbox that replaced it must NOT
        // inherit the demand: ticking a tool is not using it.
        // ⚠ A mask holding taps is different — those taps are re-resolved at
        // render, so the model is genuinely needed and the popup is right.
        val needsSegmenter = graph.nodes.any { n ->
            n.type in com.abrah.nightmare.INPAINT_TYPES &&
                com.abrah.nightmare.MaskTaps.hasTaps(com.abrah.nightmare.MaskNode.stateOf(n))
        }
        if (needsSegmenter && !com.abrah.nightmare.segment.Segmenter.isInstalled(ctx)) {
            missingModel = MissingModel.Segment
            return false
        }
        // ⭐ Upscalers an OUTPUT node names — `image.upscale` is deleted, and the
        // checkbox that replaced it needs the same weights.
        for (n in graph.nodes.filter { MediaOutputNode.autoUpscales(it) }) {
            val id = MediaOutputNode.effectiveParams(n)[MediaOutputNode.UPSCALER].orEmpty()
            val spec = UpscalerCatalog.byId(id) ?: continue
            if (spec.installed(ctx) || spec.buildFor(caps) == null) continue
            missingModel = MissingModel.Upscale(spec)
            return false
        }
        return true
    }

    /** ⭐ Accept the popup: point the flow at a substitute if needed, and fetch. */
    fun downloadMissingModel() {
        when (val m = missingModel ?: return) {
            is MissingModel.Checkpoint -> {
                if (m.substitute) {
                    editCanvas { s ->
                        s.workflow.graph.nodes
                            .filter { it.params["model"] == m.wanted || (m.wanted.isBlank() && nodeTypes[it.type] is SdSampler) }
                            .filter { (nodeTypes[it.type] as? SdSampler)?.family == m.offer.family }
                            .fold(s) { acc, n -> acc.setParam(n.id, "model", m.offer.id) }
                    }
                }
                installModel(m.offer)
            }
            MissingModel.Segment -> installSegmenter()
            is MissingModel.Upscale -> installUpscaler(m.spec)
            MissingModel.Engine -> installDitEngine()
        }
    }

    fun dismissMissingModel() {
        missingModel = null
    }

    // ---- the segmenter (docs/SEGMENTER.md) ---------------------------------

    /** ⭐ The Tools tab's one row. Shares the one-download latch, like the upscalers. */
    var segmenterRow by mutableStateOf<com.abrah.nightmare.ui.ToolRow?>(null)
        private set

    private val SEGMENTER_INSTALL_ID = MissingModel.Segment.installId

    fun refreshSegmenter() {
        val ctx = getApplication<Application>()
        val seg = com.abrah.nightmare.segment.Segmenter
        seg.refresh(ctx)
        segmenterRow = com.abrah.nightmare.ui.ToolRow(
            label = seg.LABEL,
            bytes = seg.BYTES,
            installed = seg.installed,
            onDisk = if (seg.installed) seg.bytesOnDisk(ctx) else 0L,
            progress = if (installing == SEGMENTER_INSTALL_ID) installProgress else null,
        )
    }

    fun installSegmenter() {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = SEGMENTER_INSTALL_ID
        cancelInstall = false
        modelError = null
        installProgress = ModelInstaller.Progress("starting", 0, com.abrah.nightmare.segment.Segmenter.BYTES)
        refreshSegmenter()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.abrah.nightmare.segment.Segmenter.install(
                    ctx,
                    onProgress = { p -> viewModelScope.launch { tickProgress(p) } },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed ${com.abrah.nightmare.segment.Segmenter.LABEL}")
                    downloadSucceeded(com.abrah.nightmare.segment.Segmenter.LABEL)
                }
            } catch (e: ModelInstaller.Cancelled) {
                viewModelScope.launch {
                    downloadCancelled()
                    say("download cancelled", bad = true)
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    downloadFailed(com.abrah.nightmare.segment.Segmenter.LABEL, modelError!!)
                    say("install failed — $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    refreshSegmenter()
                }
            }
        }
    }

    // ---- the DiT engine (notes/HANDOFF.md §7) ------------------------------

    /**
     * ⭐ Fetch `libdit_engine.so` on its own, for someone whose DiT weights
     * predate 1.5.502. ⚠ No Tools-tab row goes with it, deliberately: it is a
     * DEPENDENCY of a model rather than a thing to choose, it installs with the
     * weights, and a Delete button beside it would invite stranding 6.7 GB.
     */
    fun installDitEngine() {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = MissingModel.Engine.installId
        cancelInstall = false
        modelError = null
        installProgress = ModelInstaller.Progress("starting", 0, com.abrah.nightmare.DitEngine.BYTES)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                com.abrah.nightmare.DitEngine.install(
                    ctx,
                    onProgress = { p -> viewModelScope.launch { tickProgress(p) } },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed ${com.abrah.nightmare.DitEngine.LABEL}")
                    downloadSucceeded(com.abrah.nightmare.DitEngine.LABEL)
                }
            } catch (e: ModelInstaller.Cancelled) {
                viewModelScope.launch {
                    downloadCancelled()
                    say("download cancelled", bad = true)
                }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    downloadFailed(com.abrah.nightmare.DitEngine.LABEL, modelError!!)
                    say("install failed — $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                }
            }
        }
    }

    fun deleteSegmenter() {
        com.abrah.nightmare.segment.Segmenter.delete(getApplication())
        say("deleted ${com.abrah.nightmare.segment.Segmenter.LABEL}")
        refreshSegmenter()
    }

    // ---- embeddings (textual inversion) -------------------------------------
    //
    // ⭐ App-side growback of upstream's Embedding Manager — the BACKEND never
    // lost this (`docs/ARCHITECTURE.md`): `loadTextualInversions()` reads
    // every `.safetensors` in [BackendProcess.embeddingsDir] at launch and
    // matches a prompt token against a file's name, unmodified since the fork.

    /** ⭐ The Tools tab's installed-embeddings list. */
    var embeddingRows by mutableStateOf<List<com.abrah.nightmare.ui.EmbeddingRow>>(emptyList())
        private set

    fun refreshEmbeddings() {
        val dir = BackendProcess.embeddingsDir(getApplication())
        embeddingRows = dir.listFiles { f ->
            f.isFile && f.extension.equals("safetensors", ignoreCase = true)
        }.orEmpty()
            .map { com.abrah.nightmare.ui.EmbeddingRow(it.name, it.length()) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * ⭐⭐ The LoRA adapters on this device. DiT families only — a QNN
     * pipeline has nowhere to put one (`docs/ROADMAP.md` §2f).
     */
    var loraRows by mutableStateOf<List<com.abrah.nightmare.ui.EmbeddingRow>>(emptyList())
        private set

    /**
     * ⭐⭐ Bumped whenever `_loras` changes — an import, a delete.
     *
     * ⚠⚠ The node's LoRA picker reads the DIRECTORY, not [loraRows], because
     * the directory cannot be stale and the list depends on someone having
     * called [refreshLoras] first. But a directory read is not observable, so
     * an Add inside the picker would copy a file the open dialog never saw.
     * This integer is the dependency that makes the read re-run — the source of
     * truth stays the disk.
     */
    var loraEpoch by mutableStateOf(0)
        private set

    fun refreshLoras() {
        loraRows = BackendProcess.lorasDir(getApplication()).listFiles { f ->
            f.isFile && f.extension.equals("safetensors", ignoreCase = true)
        }.orEmpty()
            .map { com.abrah.nightmare.ui.EmbeddingRow(it.name, it.length()) }
            .sortedBy { it.name.lowercase() }
        loraEpoch++
    }

    /**
     * ⭐⭐ Copy a picked adapter into [BackendProcess.lorasDir].
     *
     * ⚠⚠ The COPY is the point, not tidiness: the engine cannot open a file
     * left in `Download/` at all ("cannot register LoRA source"), because the
     * backend runs as this app's uid and scoped storage does not reach another
     * app's file there.
     *
     * ⚠ Same validation as [importEmbedding], for the same reason — refuse a
     * file whose name does not end `.safetensors` rather than renaming it, and
     * take `File(displayName).name` so a provider's untrusted DISPLAY_NAME
     * cannot carry `../` out of the directory.
     */
    fun importLora(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                val displayName = uriDisplayName(uri).orEmpty()
                require(displayName.endsWith(".safetensors", ignoreCase = true)) {
                    "only .safetensors files are supported"
                }
                val dir = BackendProcess.lorasDir(ctx).apply { mkdirs() }
                val safeName = java.io.File(displayName).name
                    .ifBlank { "lora_${System.currentTimeMillis()}.safetensors" }
                val target = java.io.File(dir, safeName)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("could not read that file")
                safeName
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        say("imported LoRA $name")
                        refreshLoras()
                        // ⚠⚠ A LoRA is cached by NAME — see [HarnessOps.dropNodeCache].
                        ops.dropNodeCache()
                    },
                    onFailure = { e ->
                        modelError = "LoRA import failed: ${e.message}"
                        say("LoRA import failed — ${e.message}", bad = true)
                    },
                )
            }
        }
    }

    fun importEmbedding(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                // ⚠⚠ **Validated by extension, matching upstream exactly** —
                // `local-dream`'s own `importEmbedding` refuses a picked file
                // whose name does not end `.safetensors` rather than silently
                // renaming it. Asked for 2026-09-19: *"i hope u atleast used
                // the same validation of their files as local dream does"* —
                // it did not; this used to APPEND the extension onto whatever
                // was picked, which would have happily copied in a `.jpg` and
                // called it a `.safetensors`. ⚠ By the ORIGINAL display name,
                // before it is sanitised to a bare filename below — sanitising
                // first would let a name like "photo.jpg.safetensors" (an
                // unlikely but possible provider quirk) pass a check it
                // should not.
                val displayName = uriDisplayName(uri).orEmpty()
                require(displayName.endsWith(".safetensors", ignoreCase = true)) {
                    "only .safetensors files are supported"
                }
                val dir = BackendProcess.embeddingsDir(ctx).apply { mkdirs() }
                // ⚠⚠ `.name`, never the raw display name: a content provider's
                // DISPLAY_NAME is untrusted text, and the same zip-slip shape
                // `PluginInstaller` guards against (a name carrying `../`)
                // would write outside [dir] if used as-is.
                val safeName = java.io.File(displayName).name
                    .ifBlank { "embedding_${System.currentTimeMillis()}.safetensors" }
                val target = java.io.File(dir, safeName)
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("could not read that file")
                safeName
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.fold(
                    onSuccess = { name ->
                        say("imported embedding $name")
                        refreshEmbeddings()
                    },
                    onFailure = {
                        modelError = "could not import that embedding — ${it.message}"
                    },
                )
            }
        }
    }

    /**
     * ⚠ Stripped to its last segment before it reaches [java.io.File], the
     * same reason [deleteEmbedding] does it — a name that came back from a UI
     * row is still a name this function did not produce.
     *
     * ⚠⚠ No "is a graph using it" guard, and deliberately: the `loras` param
     * holds NAMES, and a node naming a file that is gone refuses at Run by that
     * name ([SdSampler.parseLoras]). That is a better failure than a delete
     * refused on behalf of a workflow the user may not have open.
     */
    fun deleteLora(name: String) {
        val f = java.io.File(BackendProcess.lorasDir(getApplication()), java.io.File(name).name)
        if (f.delete()) say("deleted LoRA $name")
        refreshLoras()
        // ⚠ …and on the way out too: a node still naming it must now REFUSE,
        // not serve the picture it made while the file was there.
        ops.dropNodeCache()
    }

    fun deleteEmbedding(name: String) {
        // ⚠ Same reason as the import path: never trust a name handed back to
        // this function into a raw `File(dir, name)` without stripping it to
        // its last segment first.
        val f = java.io.File(BackendProcess.embeddingsDir(getApplication()), java.io.File(name).name)
        if (f.delete()) say("deleted embedding $name")
        refreshEmbeddings()
    }

    /**
     * ⚠ No "is it selected" guard, unlike [deleteModel]: nothing global points
     * at an upscaler. A NODE may name one, and that node fails with a sentence
     * telling the user to install it — which is the right failure, and a far
     * better one than refusing the delete on behalf of a graph they may not
     * even have open.
     */
    fun deleteUpscaler(spec: UpscalerSpec) {
        UpscalerCatalog.delete(getApplication(), spec)
        say("deleted ${spec.label}")
        refreshUpscalers()
    }

    /**
     * ⭐ Bring your own upscaler — a bare `.bin`, the same way a checkpoint
     * is imported as a zip ([importModel]). Reported 2026-09-18.
     *
     * ⚠ Named from the provider's own file name, like [importNameFor] does
     * for a checkpoint — a picked file rarely has a name a user would choose
     * to type twice.
     */
    fun importUpscaler(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                // ⚠ Validated by extension, same reasoning and the same fix
                // as [importEmbedding] — there is no upstream reference for a
                // custom upscaler (local-dream has none), so this applies the
                // SAME rule for the same reason: a picked file with the wrong
                // extension should be refused, not silently accepted under a
                // name that implies it is something it is not.
                val displayName = uriDisplayName(uri).orEmpty()
                require(displayName.endsWith(".bin", ignoreCase = true)) {
                    "only .bin files are supported"
                }
                val proposed = displayName.dropLast(4)
                val name = java.io.File(proposed).name.let {
                    if (UpscalerCatalog.isValidName(it)) it else "upscaler_${System.currentTimeMillis()}"
                }
                UpscalerCatalog.importCustom(ctx, name) {
                    ctx.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException("could not read that file")
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.fold(
                    onSuccess = { spec ->
                        say("imported upscaler ${spec.label}")
                        refreshUpscalers()
                    },
                    onFailure = {
                        modelError = "could not import that upscaler — ${it.message}"
                    },
                )
            }
        }
    }

    fun deleteModel(spec: ModelSpec) {
        val ctx = getApplication<Application>()
        try {
            ModelInstaller.delete(ctx, spec)
            say("deleted ${spec.label}")
            // ⚠⚠ The SELECTION has to move with it. `SelectedModel` names a
            // catalogue entry, which still resolves after the files are gone --
            // so deleting the selected model left it selected, every new node
            // baked that id into its locked `model` param, and the graph then
            // held a mixture the user could not edit and could not run. The
            // executor reported that as "needs 2 backend contexts", which
            // describes our roadmap rather than their graph.
            if (SelectedModel.id == spec.id) {
                val next = ModelCatalog.installed(ctx).firstOrNull { it.id != spec.id }
                    ?: ModelCatalog.byId(V1_MODEL)
                if (next != null) selectModel(next)
            }
        } catch (e: Exception) {
            modelError = e.message
            say("could not delete — ${e.message}", bad = true)
        }
        refreshModels()
    }

    /**
     * ⚠⚠ Changing the model invalidates nothing automatically, and it must:
     * a running backend was launched with `--model_dir` for the OLD model, so
     * leaving it up would render every subsequent graph against the model the
     * user just switched away from -- silently, since the node's `model` param
     * only feeds the context key.
     */
    /**
     * ⭐⭐ A Use waiting on a flow choice — the model, and whether the open
     * canvas would be lost.
     *
     * ⚠⚠ Pressing Use has always retargeted whatever is on the canvas and
     * nothing else. A user who came to the Models tab to *start* something with
     * a checkpoint had to press Use, go to Flows, then pick a recipe — and if
     * the canvas held unsaved work, Use silently rewrote it on the way past.
     * Asked for 2026-09-15: offer the flows, and say what will be lost.
     */
    /**
     * ⭐⭐⭐ A Use waiting on a flow choice — for ANY kind of model.
     *
     * ⚠⚠ [spec] is null for an upscaler and for the video models, and that
     * nullability is the feature: they are not checkpoints, nothing selects
     * them globally, and there is nothing to retarget an open canvas at. What
     * they DO have is flows that use them, which is what a person pressing Use
     * is after — asked for 2026-09-20, *"video and upscaler models should also
     * have use btns"*.
     *
     * ⇒ Use means **open a flow that uses this thing**, and for a checkpoint
     * it additionally selects it. One dialog, three callers.
     *
     * ⚠ [offer] is computed by the caller, never filtered in the dialog: a
     * checkpoint offers only the flows it can actually run ([Recipe.runsOn]).
     */
    data class PendingUse(
        val spec: ModelSpec?,
        val title: String,
        val offer: List<com.abrah.nightmare.canvas.Recipe>,
        val unsavedFlow: Boolean,
    )

    var pendingUse by mutableStateOf<PendingUse?>(null)
        private set

    fun cancelUse() { pendingUse = null }

    /**
     * ⚠ "Unsaved" is the honest test: a canvas that came from a saved flow can
     * be reopened, one that did not cannot. [currentWorkflowName] is null for
     * exactly that second case.
     */
    fun askUse(spec: ModelSpec) {
        pendingUse = PendingUse(
            spec,
            title = "Use ${spec.label}",
            // ⚠⚠ Only the flows this CHECKPOINT can run. `usesCheckpoint`
            // alone offered Inpaint on Z-Image ([Recipe.runsOn]).
            offer = com.abrah.nightmare.canvas.RECIPES.filter { it.runsOn(spec) },
            unsavedFlow = unsavedFlow(),
        )
    }

    /** ⭐ Use on an UPSCALER — it has one flow and no global selection. */
    fun askUseUpscaler(label: String) {
        pendingUse = PendingUse(
            spec = null,
            title = "Use $label",
            offer = com.abrah.nightmare.canvas.recipesWithId("upscale"),
            unsavedFlow = unsavedFlow(),
        )
    }

    /** ⭐ Use on the VIDEO models — two flows, both theirs. */
    fun askUseVideo() {
        pendingUse = PendingUse(
            spec = null,
            title = "Use the video models",
            offer = com.abrah.nightmare.canvas.recipesWithId("t2v", "i2v"),
            unsavedFlow = unsavedFlow(),
        )
    }

    private fun unsavedFlow(): Boolean =
        currentWorkflowName == null && canvas.workflow.graph.nodes.isNotEmpty()

    /**
     * @param recipe the flow to open with it, or null to keep the open canvas
     *   and only retarget it — which is what Use did before it asked.
     */
    fun confirmUse(spec: ModelSpec?, recipe: com.abrah.nightmare.canvas.Recipe?) {
        pendingUse = null
        // ⚠ Null for an upscaler or the video models: there is no global
        // selection to make, only a flow to open.
        spec?.let { selectModel(it) }
        // ⚠ AFTER the model is set: a recipe reads [SelectedModel] as it builds,
        // so building first would lay out a flow pointed at the old checkpoint
        // and then retarget it — two writes where one will do, and the first one
        // visible.
        recipe?.let {
            // ⚠ The UNGUARDED one: this dialog already said "unsaved flows will
            // be replaced" before listing the flows. See [openRecipeNow].
            openRecipeNow(it)
            // ⭐ …and get out of the way. Choosing a flow is asking to work on
            // it; leaving the library open means the user's next act is always
            // to close a screen they are done with. The user's call, 2026-09-15.
            // ⚠ Only when a flow was chosen — "keep the current flow" is a
            // change to the CANVAS's model, and closing the tab under someone
            // who came to browse checkpoints would take the list away mid-scroll.
            closeLibrary()
        }
    }

    fun selectModel(spec: ModelSpec) {
        val ctx = getApplication<Application>()
        if (spec.id == SelectedModel.id) return
        SelectedModel.set(ctx, spec.id)
        say("model set to ${spec.label}")
        // ⚠ The last run was against the OLD checkpoint, so its timings and its
        // "done" no longer describe this canvas.
        clearRunLog()
        // ⚠⚠ Every derived PICTURE is stale too, not just the params. A model
        // switch changes the render size, so a `crop` that already has a 512
        // picture must remake it at 1024 — and a signature is only rechecked
        // when the node's params changed, which is a race against the
        // derivation that changes them. [openWorkflow] clears these for the
        // same reason when a graph is replaced wholesale; a model switch
        // rewrites every backend node's size, which is the same kind of event.
        previewSigs.clear()
        retargetCanvas(spec, SelectedModel.res)
        if (backend == BackendState.UP) {
            say("  stopping the backend — it was launched for the previous model")
            stopBackend()
        }
        refreshModels()
    }

    /**
     * ⭐⭐ Change ONE node's render size.
     *
     * ⚠⚠ **Per node, checked against THAT node's model.** This used to rewrite
     * the whole graph against the top bar's model — right while a graph named
     * one key, wrong since the model became a per-node choice and the executor
     * schedules keys (`docs/ARCHITECTURE.md` §4, "resolution — per-node too").
     * With SDXL in the top bar it refused every size an SD 1.5 node offered.
     *
     * ⚠ No backend stop: the executor relaunches for a key it does not hold
     * ([scheduleByKey]), the same way it moves between two checkpoints.
     *
     * ⚠⚠ `previewSigs.clear()` is NOT tidiness. DreamUI hit this on its first
     * non-square inpaint: cropping at one resolution and generating at another
     * left the painter holding a bitmap of the OLD size, which then went to a
     * backend that sizes its buffers from the request and **died with SIGSEGV
     * writing past them** (`GenerateViewModel.setResolution`). Every derived
     * picture on this canvas is the wrong shape the instant this returns, and a
     * signature is only rechecked when a node's params changed — which is a
     * race against the derivation that changes them.
     */
    fun setNodeResolution(nodeId: String, res: Res) {
        val ctx = getApplication<Application>()
        val node = canvas.workflow.graph.byId[nodeId] ?: return
        val spec = ModelCatalog.byId(node.params["model"].orEmpty()) ?: SelectedModel.spec
        val w = res.width.toString()
        val h = res.height.toString()
        if (node.params["width"] == w && node.params["height"] == h) return
        // ⚠⚠ [ModelSpec.serves], not `res in resolutionsOf(...)`. A DiT
        // family answers with a GRID rather than a list, and the list idiom
        // refused every legal size but its native one.
        if (!spec.serves(ctx, res)) {
            say(
                "${spec.label} cannot render $res — it serves ${spec.sizesServedText(ctx)}",
                bad = true,
            )
            return
        }
        editCanvas { s -> s.setParams(nodeId, mapOf("width" to w, "height" to h)) }
        say("$nodeId: resolution set to $res")
        previewSigs.clear()
        // ⚠ The chip that was tapped reads its selected state off this list.
        refreshModels()
    }

    /**
     * ⭐ Change the output shape on a fixed-canvas family.
     *
     * ⚠⚠ Deliberately **not** [selectResolution], though the user experiences
     * both as "what shape is my picture". `aspect_ratio` is a request field: it
     * paints a centered rectangle into the 1024² canvas the process is already
     * running (`ModelCatalog.aspectTarget`), so it costs **no relaunch**, does
     * not touch the context key, and must not stop the backend. Conflating the
     * two would spend 2.3-5 s to change a crop.
     *
     * ⚠ Previews still go: every derived picture downstream is a different
     * shape now, which is the same staleness a resolution change causes.
     */
    fun selectAspect(aspect: String) {
        // ⚠⚠ No check against the TOP BAR's model. The chip is only declared by
        // a fixed-canvas family's type ([aspectWidget]) and [aspectRetarget]
        // writes only nodes that declare it, so a selection of SD 1.5 must not
        // refuse the chip on an SDXL node (2026-09-17).
        val graph = canvas.workflow.graph
        val changes = aspectRetarget(graph, typesFor(graph), aspect)
        if (changes.isEmpty()) return
        editCanvas { s -> changes.entries.fold(s) { acc, (id, p) -> acc.setParams(id, p) } }
        previewSigs.clear()
        val spec = changes.keys.firstNotNullOfOrNull { id ->
            ModelCatalog.byId(graph.byId[id]?.params?.get("model").orEmpty())
        } ?: SelectedModel.spec
        val target = ModelCatalog.aspectTarget(aspect, spec.native)
        say("aspect $aspect — ${target ?: spec.native} on ${changes.size} node" +
            (if (changes.size == 1) "" else "s"))
        // ⚠ Same reason as [selectResolution]: the chip's selected state is
        // read back off `modelRows`, which reads it off the graph.
        refreshModels()
    }

    /**
     * ⭐⭐ Follow the model the OPENED graph names, rather than rewriting the
     * graph to match the picker.
     *
     * ⚠⚠ **The graph is authoritative** (`docs/MODELS.md` §4). A saved workflow
     * stores `model` on every backend node, so opening one while a different
     * model is selected launched the backend with `--model_dir` for the PICKER's
     * checkpoint and rendered the user's graph against it -- silently, because a
     * node's `model` param only feeds the context key. A picture that looks
     * almost right, from a mismatch nothing reported.
     *
     * ⚠ Three cases are deliberately left alone: a graph naming **two** models
     * (there is no single answer, and the executor refuses it by name with
     * advice that now works -- [retargetCanvas]), a graph naming an id the
     * catalogue does not hold ([SelectedModel.set] would throw), and a graph
     * naming what is already selected.
     *
     * ⚠ A hand-pushed model directory used to be the main reason for the
     * second case; it no longer is, because [CustomModels] gives one a real
     * catalogue entry. What remains is a workflow naming a model that was
     * deleted, or one saved on another phone.
     *
     * ⚠ Deliberately NOT [selectModel]: that retargets the canvas, which is the
     * exact opposite of what this is for.
     */
    private fun adoptGraphModel(w: Workflow) {
        // ⚠ Guarded for the same reason [deriveSizes] is: a graph naming a pack
        // resolves `nodeTypes`, which builds the QuickJS runtime and can throw
        // for reasons that have nothing to do with the model. Losing the user's
        // restored graph to a question about which checkpoint it wants would be
        // far worse than leaving the selection where it was.
        val types = try {
            typesFor(w.graph)
        } catch (e: Throwable) {
            return
        }
        val named = contextKeyModels(w.graph, types)
        // ⚠⚠ **The resolution is adopted too, and on its own.** A saved graph
        // stores `width`/`height` on every backend node, so opening one at 768²
        // while 512² is selected would launch the backend with the wrong
        // `--patch` and render the user's graph against it -- the same silent
        // wrong-size failure adopting the MODEL exists to prevent, and it does
        // not need the model to differ to happen.
        val id = named.singleOrNull()
        val res = contextKeyResolutions(w.graph, types).singleOrNull()
        val ctx = getApplication<Application>()
        val spec = id?.let { ModelCatalog.byId(it) }
        val modelMoved = spec != null && id != SelectedModel.id
        // ⚠ Read against the graph's model, not the selected one -- on a model
        // switch the sizes that are legal change with it.
        val resMoved = res != null && res != SelectedModel.res &&
            (spec ?: SelectedModel.spec).serves(ctx, res)
        if (!modelMoved && !resMoved) return

        if (modelMoved) {
            SelectedModel.set(ctx, id!!)
            say("this workflow uses ${spec!!.label} — selected it")
        }
        // ⚠ AFTER the model, because [SelectedModel.set] re-resolves the
        // resolution per model and would otherwise overwrite this.
        if (resMoved) {
            SelectedModel.setRes(ctx, res!!)
            say("this workflow renders at $res — selected it")
        }
        clearRunLog()
        // ⚠ Derived pictures are the previous size, exactly as in [selectModel].
        previewSigs.clear()
        if (backend == BackendState.UP) {
            say("  stopping the backend — it was launched for the previous context")
            stopBackend()
        }
        refreshModels()
    }

    /**
     * ⭐⭐ Point every backend node on the OPEN canvas at [spec].
     *
     * ⚠⚠ This is the half of the executor's own advice that did not exist. A
     * graph whose nodes name a model the user has switched away from (or
     * deleted -- [deleteModel] moves the selection) refuses with "needs 2
     * backend contexts", and the fix it prints is *"open Models and select one,
     * which rewrites every node"*. Selecting only moved a global, so a user who
     * followed that instruction exactly stayed stuck: the three knobs it refers
     * to are locked, so there was no other way out of the state.
     *
     * ⚠ The OPEN canvas only, never a saved file. `docs/MODELS.md` §4 is
     * explicit that a user's saved graph is their work and is authoritative over
     * the picker -- so opening a workflow must not be re-targeted, and this runs
     * on the deliberate act of choosing a model instead.
     *
     * ⚠ Through [editCanvas], so it lands on the canvas as it is NOW and gets
     * the autosave, the derived crop sizes and the preview refresh for free. A
     * captured snapshot here would revert whatever the user did since.
     */
    private fun retargetCanvas(spec: ModelSpec, res: Res = spec.native) {
        val graph = canvas.workflow.graph
        val types = typesFor(graph)
        val changes = contextKeyRetarget(graph, types, spec, res)
        if (changes.isNotEmpty()) {
            // ⚠⚠⚠ **The FAMILY moves WITH the model, here too.** A sampler's
            // type is registered per family (`SdSampler.SD15`/`.SDXL`/`.ANIMA`
            // — `Fused.kt`) and its title follows that fixed type, never the
            // checkpoint actually named in `params["model"]`. `setNodeModel`'s
            // per-node swap already migrates the type when the family changes
            // (`SdSampler.typeFor`); this bulk path — "Use" on the Models tab,
            // keeping the open canvas — rewrote every node's `model` param the
            // SAME way but never touched `type`, so a node stayed titled
            // "SD 1.5" with an SDXL checkpoint underneath it. Reported
            // 2026-09-18 (screenshot: checkpoint "…_sdxl_lora", node title
            // "SD 1.5", subtitle "sd15.sample").
            val retitled = changes.keys.count { id ->
                (types[graph.byId[id]?.type] as? SdSampler)?.family?.let { it != spec.family } == true
            }
            editCanvas { s ->
                s.copy(
                    workflow = s.workflow.copy(
                        graph = Graph(
                            s.workflow.graph.nodes.map { n ->
                                val p = changes[n.id] ?: return@map n
                                val sampler = types[n.type] as? SdSampler
                                val newType = if (sampler != null && sampler.family != spec.family) {
                                    SdSampler.typeFor(spec.family, sampler.inpaint)
                                } else {
                                    n.type
                                }
                                n.copy(type = newType, params = n.params + p)
                            }
                        )
                    )
                )
            }
            say(
                "  retargeted ${changes.size} node${if (changes.size == 1) "" else "s"} on the canvas" +
                    if (retitled > 0) " ($retitled retyped to ${spec.family.label})" else ""
            )
        }
        // ⭐⭐ …and the checkpoint's own sampling recipe, WRITTEN DOWN.
        //
        // ⚠⚠ Before this, `steps`/`cfg`/`scheduler` were resolved from the
        // selected model at RUN time whenever a node carried none of them. That
        // is not reproducible: the same graph file rendered differently after a
        // model switch, and the inspector showed the new model's numbers while
        // what ran was whatever the defaults happened to resolve to. Writing
        // them makes displayed == stored == rendered.
        val recipe = modelRecipeRetarget(canvas.workflow.graph, types, spec)
        if (recipe.isNotEmpty()) {
            editCanvas { s -> recipe.entries.fold(s) { acc, (id, p) -> acc.setParams(id, p) } }
            // ⚠ Announced rather than silent: it can overwrite a value the user
            // set by hand, and a number changing under someone with no
            // explanation is what makes an app feel unreliable.
            say(
                "  ${spec.label}: ${spec.steps} steps, cfg ${spec.cfg}, ${spec.scheduler}" +
                    " — set on ${recipe.size} node" + (if (recipe.size == 1) "" else "s")
            )
        }
        // ⭐⭐ …and its starter PROMPT, on any text node still holding ours.
        //
        // ⚠⚠ Unlike the recipe above, this one never overwrites a sentence the
        // user typed -- [modelPromptRetarget] rewrites only a blank field or one
        // still carrying a catalogue model's own text. A model switch that ate
        // someone's prompt would be the worst trade in the app.
        val prompts = modelPromptRetarget(canvas.workflow.graph, types, spec)
        if (prompts.isNotEmpty()) {
            editCanvas { s -> prompts.entries.fold(s) { acc, (id, p) -> acc.setParams(id, p) } }
            // ⚠ Named, like the numbers above: the text on a node changing with
            // no explanation is exactly what makes an app feel unreliable.
            say(
                "  its starter prompt written to ${prompts.size} node" +
                    (if (prompts.size == 1) "" else "s") +
                    " — anything you typed is left alone"
            )
        }
        // ⚠⚠ Report the DERIVED sizes, because this is where they go wrong and
        // the failure is otherwise silent until a render comes out smeared.
        //
        // A model switch changes `width`/`height` on the backend nodes, and the
        // consumer-derived ones (`crop`, `mask`) must follow. When they do not,
        // the crop emits at its source's own size and everything downstream is
        // the wrong shape — reported from the phone as "the frame is not
        // adjusting for the new model; I pan it a little and it is fixed",
        // panning being an edit that forces the derivation this line watches.
        val derived = canvas.workflow.graph.nodes.filter {
            typesFor(canvas.workflow.graph)[it.type]?.sizedByConsumer == true
        }
        if (derived.isNotEmpty()) {
            say(
                "  sizes: " + derived.joinToString("  ") {
                    "${it.id}=${it.params["out_w"] ?: "?"}x${it.params["out_h"] ?: "?"}"
                }
            )
        }
        // ⚠ And whatever is still inconsistent, by name. [sizeMismatches] is the
        // same check Run makes; saying it HERE puts it next to the action that
        // caused it rather than one render later.
        for (why in sizeMismatches(canvas.workflow.graph, typesFor(canvas.workflow.graph))) {
            say("  ⚠ size: $why", bad = true)
        }
    }

    /**
     * The canvas's whole interactive state.
     *
     * ⚠ One value, replaced wholesale by the gesture layer. Spreading viewport,
     * selection and drag across separate fields would let two of them disagree
     * mid-gesture, which is precisely the class of bug the state machine exists
     * to make impossible.
     */
    var canvas by mutableStateOf(CanvasState(defaultWorkflow()))
        private set

    /**
     * ⚠⚠ Saves when the workflow changed AND no gesture is in flight. Saving on
     * every change would write a file per frame of a node drag; saving only on
     * Run would lose everything a user did before backing out. "The gesture
     * finished and something is different" is the moment that means *edit*.
     */
    /**
     * ⭐⭐ Apply [change] to the canvas **as it is now**.
     *
     * ⚠⚠ This exists because [updateCanvas] takes a whole `CanvasState`, and
     * everything outside the gesture loop builds that from a value it captured
     * at some earlier composition. Any callback that fires LATE therefore hands
     * back a snapshot of the entire graph as it was when the callback was made
     * -- and writing it undoes every edit since.
     *
     * Measured on the phone, 2026-09-09, with the preview log:
     *
     * ```
     * 11:09:49.388  shown=[(load_image, img_1889…)]   the photo just chosen
     * 11:09:50.373  canvas changed=true opened=true editing=null   the sheet closed
     * 11:09:50.626  shown=[(load_image, img_ed7d…)]   the OLD photo, back again
     * ```
     *
     * `changed=true` on a *dismissal* is the whole story: closing the sheet
     * rewrote the workflow from the state the sheet was composed with, which
     * still held the previous `uri`. The picture then followed the graph
     * correctly -- the previews were never wrong, the GRAPH was being reverted
     * under them. The image picker is the worst case because the activity is
     * stopped while the user chooses, but a crop rect reverted the same way.
     *
     * ⇒ Every non-gesture edit is a **transform**, applied here to the live
     * value. The gesture loop keeps handing back whole states, and that is
     * correct: it threads its own state for the duration of one gesture and
     * publishes each step immediately (`CanvasGestures.kt`).
     */
    fun editCanvas(change: (CanvasState) -> CanvasState) {
        val before = canvas
        val after = paintedOnGenerated(before, refitFramings(before, change(before)))
        markNewPictures(before, after)
        // ⚠⚠⚠ **A deleted node's id is free to be reused — [Graph.freeId] hands
        // the lowest unused one straight back — so every VIEW-MODEL map keyed
        // by node id has to lose that entry HERE, once, for every deletion
        // path at once, or a "new" node opens wearing the last thing that id
        // did. `CanvasState.removeNode`/`addNode` already do this for their
        // OWN fields (`previews`, `rendered`, `beforePreviews`); these three
        // live in the view model instead and were still missed, which is
        // exactly the 2026-09-11 bug again on 2026-09-18 (a freshly dropped
        // node showing a red FAILED border, or a picture belonging to a mask
        // that node never painted) — reported furiously, and rightly, after
        // the first fix shipped and did not cover them.
        val gone = before.workflow.graph.nodes.map { it.id }.toSet() -
            after.workflow.graph.nodes.map { it.id }.toSet()
        for (id in gone) {
            canvasStatus.remove(id)
            pendingAutoFrame.remove(id)
            previewSigs.remove(id)
        }
        updateCanvas(after)
    }

    /**
     * ⭐⭐⭐ **Painting on a GENERATED picture pins it** (`docs/ARCHITECTURE.md`,
     * "Chains through a node a person must act on").
     *
     * ⚠⚠ A mask is coordinates on ONE picture. With a sampler upstream rolling
     * a new seed each Run, the next Run made a different picture and repainted
     * the old coordinates on it — a plausible render of the wrong area. ⇒ When
     * the painting changes: every random seed upstream is LOCKED to the one that
     * made what is on screen, and the picture is recorded
     * ([MaskNode.PAINTED_ON]) so a change made some other way — a prompt edit —
     * stops the node by name instead.
     * ⚠ Only for a generated picture: a photo is fixed already.
     */
    private fun paintedOnGenerated(before: CanvasState, after: CanvasState): CanvasState {
        var out = after
        val graph = after.workflow.graph
        for (n in graph.nodes) {
            if (n.type !in com.abrah.nightmare.INPAINT_TYPES) continue
            val ops = n.params[com.abrah.nightmare.MaskNode.OPS].orEmpty()
            if (before.workflow.graph.byId[n.id]?.params?.get(com.abrah.nightmare.MaskNode.OPS).orEmpty() == ops) continue
            val up = n.inputs["image"]?.node ?: continue
            if (com.abrah.nightmare.canvas.samplerFor(graph, up) == null) continue
            if (ops.isBlank()) {
                out = out.setParams(n.id, mapOf(com.abrah.nightmare.MaskNode.PAINTED_ON to ""))
                continue
            }
            after.pictureInto(n.id, nodeTypes)?.let { pic ->
                out = out.setParams(n.id, mapOf(com.abrah.nightmare.MaskNode.PAINTED_ON to pic))
            }
            // Every sampler upstream, not only the nearest: a chain of two
            // rolls two seeds, and either one changes the picture.
            val seen = mutableSetOf<String>()
            val queue = ArrayDeque(listOf(up))
            while (queue.isNotEmpty()) {
                val id = queue.removeFirst()
                if (!seen.add(id)) continue
                val s = graph.byId[id] ?: continue
                if (com.abrah.nightmare.isSampler(s.type) &&
                    s.params["seed"]?.trim().orEmpty().let { it.isEmpty() || it == "0" }
                ) {
                    com.abrah.nightmare.canvas.seedFor(graph, id) { canvasStatus[it]?.detail }?.let { rolled ->
                        out = out.setParams(id, mapOf("seed" to rolled))
                        say("$id: seed locked at $rolled — ${n.id} is painted on its picture")
                    }
                }
                s.inputs.values.forEach { queue.addLast(it.node) }
            }
        }
        return out
    }

    /**
     * ⭐⭐ Samplers whose photo was just REPLACED, waiting for the new picture to
     * arrive: sampler id -> the preview image id it showed before, so the OLD
     * picture still in the preview map is never mistaken for the new one.
     */
    private val pendingAutoFrame = mutableMapOf<String, String?>()

    /**
     * ⭐ A new photo on a `core.image` marks every SD sampler it feeds — the
     * user's call, 2026-09-17: a new image opens the crop window with the
     * size and framing already fitted to it. ⚠ Only a DIFFERENT, non-blank uri.
     */
    private fun markNewPictures(before: CanvasState, after: CanvasState) {
        for (n in after.workflow.graph.nodes) {
            if (n.type != "core.image") continue
            val uri = n.params["uri"].orEmpty()
            if (uri.isBlank() || before.workflow.graph.byId[n.id]?.params?.get("uri") == uri) continue
            for (s in after.workflow.graph.nodes) {
                if (s.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES && s.inputs["image"]?.node == n.id) {
                    pendingAutoFrame[s.id] = before.previews[n.id]?.first
                }
            }
        }
    }

    /**
     * ⭐⭐ The size and framing a sampler gets for [photo] — asked for 2026-09-17:
     * *"default the res/aspect ratio smartly depending on input image ratio
     * (closest match)"*, then the whole photo framed ([wholePhotoFraming]).
     *
     * ⚠ Closest by LOG ratio (DreamUI's `pickResolutionFor`), so 2:3 and 3:2
     * are equally far from 1:1. ⚠ SD 1.5 picks among the sizes ITS model serves;
     * a fixed-canvas family picks an aspect, written on THIS node only. ⚠ A tie
     * keeps the size the node already has.
     *
     * ⭐⭐ **A DiT family does not pick at all — it COMPUTES**
     * ([ModelCatalog.ditFit]). Its size is a request field on a 64-px grid, so
     * "closest match" is within half a step of exact and the log-ratio search
     * above has nothing to search.
     */
    private fun autoFraming(node: Node, photo: android.graphics.Bitmap): Map<String, String> =
        autoFraming(node, photo.width, photo.height)

    private fun autoFraming(node: Node, photoW: Int, photoH: Int): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val spec = ModelCatalog.byId(node.params["model"].orEmpty())
        val photoAspect = photoW.toFloat() / photoH.coerceAtLeast(1)
        fun far(a: Float) = kotlin.math.abs(kotlin.math.ln(a / photoAspect))
        if (spec != null && spec.fixedCanvas) {
            val best = ModelCatalog.ASPECTS.minWith(compareBy(
                { r -> r.split(':').let { far(it[0].toFloat() / it[1].toFloat()) } },
                { r -> if (r == node.params["aspect"]) 0 else 1 },
            ))
            out["aspect"] = best
        } else if (spec != null && spec.isDit) {
            // ⭐⭐⭐ **Computed, not picked from a list** — a DiT model renders
            // any pair on its 64-px grid, so the photo's own ratio is reachable
            // to within a grid step and there is no "closest match" to settle
            // for. Reported 2026-09-21: a 4:3 photo landed on 1024x768 or
            // 2048x1536 because those were the only 4:3 entries a table held,
            // and 1280x960 — legal on this engine and on upstream — could not be
            // reached at all.
            //
            // ⚠ Area held at what the node already renders, so dropping a photo
            // changes the SHAPE and not the render time.
            val cur = Res(
                node.params["width"]?.toIntOrNull() ?: spec.native.width,
                node.params["height"]?.toIntOrNull() ?: spec.native.height,
            )
            val best = ModelCatalog.ditFit(photoAspect, cur)
            out["width"] = best.width.toString()
            out["height"] = best.height.toString()
        } else if (spec != null) {
            val cur = Res(node.params["width"]?.toIntOrNull() ?: 0, node.params["height"]?.toIntOrNull() ?: 0)
            SelectedModel.resolutionsOf(getApplication(), spec).minWithOrNull(compareBy(
                { r -> far(r.width.toFloat() / r.height) },
                { r -> if (r == cur) 0 else 1 },
            ))?.let { best ->
                out["width"] = best.width.toString()
                out["height"] = best.height.toString()
            }
        }
        val sized = node.copy(params = node.params + out)
        val (fw, fh) = com.abrah.nightmare.canvas.framingOutSize(sized, nodeTypes[node.type])
        if (fw > 0 && fh > 0) {
            out.putAll(
                com.abrah.nightmare.canvas.wholePhotoFraming(
                    photoW, photoH, fw.toFloat() / fh, com.abrah.nightmare.padRuleFor(node.type),
                ).asParams().toMap()
            )
        }
        return out
    }

    /**
     * ⭐ Run the pending auto-frames whose NEW photo has arrived: fit the size
     * and framing, open that sampler's sheet and its crop popup.
     */
    /**
     * ⭐⭐ A run stopped at a node waiting on a PERSON ([NeedsInput]): say why,
     * FIT it to the picture that was just made, and open its CROP tab on that
     * picture (the user's call, 2026-09-17: frame first, then paint).
     * ⚠ Not [runError]: nothing failed. ⚠ The same [autoFraming] a new photo
     * gets. Only a node with nothing painted waits for this reason, so there is
     * no painting for the refit to move. ⚠ ONE function for the single run and
     * the sweep, which is the only way the two stay alike.
     */
    private fun stopForPerson(r: GraphRun) {
        val (id, why) = r.waiting ?: return
        say("$id: $why")
        toast(why)
        val node = canvas.workflow.graph.byId[id]
        val made = canvas.pictureInto(id, nodeTypes)?.let { ops.images.get(it) }
        var next = canvas
        // ⚠⚠ **No auto-fill.** Tried 2026-09-18 (a full mask written into
        // [MaskNode.OPS] the moment this opened) and reverted the same day —
        // *"lets not do the full masking thing for inpaint... if user doesnt
        // mask just show error"*. `Fused.kt` now refuses ANY inpaint with
        // nothing painted, always, with no fallback; this only fits the
        // FRAME to the fresh picture so the editor opens on the right crop.
        if (node != null && made != null && node.params[com.abrah.nightmare.MaskNode.OPS].isNullOrBlank()) {
            next = next.setParams(id, autoFraming(node, made))
        }
        canvas = next.copy(
            editing = id,
            cropRequest = id to ((canvas.cropRequest?.second ?: 0) + 1),
            cropRequestTab = 0,
        )
        saveWorkflow()
    }

    /**
     * ⭐⭐ Image-to-image fed a GENERATED picture, fitted to it BEFORE the run
     * (the user's call, 2026-09-17: "fit automatically").
     *
     * ⚠⚠ Before, not during: a resolution is part of the backend's launch key,
     * which is decided before the first node runs — a sampler cannot change its
     * size mid-graph. The picture's SHAPE is knowable in advance from the node
     * that makes it ([predictedAspect]), and the shape is all the fit needs.
     * ⚠ Only when that shape CHANGED since the last fit ([com.abrah.nightmare.FITTED_TO]), so a
     * framing adjusted by hand afterwards survives every later Run.
     * ⚠ Inpaint is not fitted here — it waits for a person and is fitted then
     * ([stopForPerson]), on the real picture.
     */
    private fun fitChainedImageToImage() {
        val graph = canvas.workflow.graph
        var next = canvas
        for (n in graph.nodes) {
            if (n.type !in com.abrah.nightmare.IMAGE_SAMPLER_TYPES || n.type in com.abrah.nightmare.INPAINT_TYPES) continue
            val up = n.inputs["image"]?.node ?: continue
            if (nodeTypes[graph.byId[up]?.type]?.showsResult != false) continue
            val aspect = predictedAspect(graph, up) ?: continue
            val tag = "%.3f".format(java.util.Locale.ROOT, aspect)
            if (n.params[com.abrah.nightmare.FITTED_TO] == tag) continue
            val fit = autoFraming(n, (aspect * 1000).toInt(), 1000)
            next = next.setParams(n.id, fit + (com.abrah.nightmare.FITTED_TO to tag))
            say("${n.id}: fitted to the picture ${up} will make (${tag})")
        }
        if (next !== canvas) { canvas = next; saveWorkflow() }
    }

    /**
     * ⭐ The width/height ratio of the picture [id] will produce, from its
     * settings alone — or null when that is not knowable before it runs.
     */
    private fun predictedAspect(graph: Graph, id: String, depth: Int = 0): Float? {
        if (depth > 16) return null
        val n = graph.byId[id] ?: return null
        val t = nodeTypes[n.type] ?: return null
        fun input() = n.inputs["image"]?.node?.let { predictedAspect(graph, it, depth + 1) }
        return when {
            n.type in com.abrah.nightmare.INPAINT_TYPES &&
                n.params[com.abrah.nightmare.PasteNode.STITCH].equals("true", true) -> input()
            n.type in com.abrah.nightmare.IMAGE_SAMPLER_TYPES ->
                t.framesTo(n)?.takeIf { it.first > 0 && it.second > 0 }?.let { it.first.toFloat() / it.second }
            n.type == "image.upscale" -> input()
            n.type == "core.image" ->
                previewsAspect(n.id)
            else -> com.abrah.nightmare.canvas.framingOutSize(n, t)
                .takeIf { it.first > 0 && it.second > 0 }?.let { it.first.toFloat() / it.second }
        }
    }

    private fun previewsAspect(id: String): Float? = canvas.previews[id]?.second

    /** ⭐ Node id -> the picture it produced in [r], for [CanvasState.rendered]. */
    private fun renderedBy(r: GraphRun): Map<String, String> =
        r.outputs.mapNotNull { (id, v) -> v.previewImage()?.let { id to it.id } }.toMap()

    private fun applyPendingAutoFrames() {
        if (pendingAutoFrame.isEmpty()) return
        for ((sid, oldImage) in pendingAutoFrame.toMap()) {
            val s = canvas.workflow.graph.byId[sid]
            if (s == null) { pendingAutoFrame.remove(sid); continue }
            val srcId = canvas.pictureInto(sid, nodeTypes) ?: continue
            if (srcId == oldImage) continue
            val photo = ops.images.get(srcId) ?: continue
            pendingAutoFrame.remove(sid)
            val params = autoFraming(s, photo)
            canvas = canvas.setParams(sid, params).copy(
                editing = sid,
                cropRequest = sid to ((canvas.cropRequest?.second ?: 0) + 1),
                cropRequestTab = 0,
            )
            say("$sid: fitted to the new picture — ${params.filterKeys { it in setOf("width", "height", "aspect") }.values.joinToString("x")}")
            saveWorkflow()
        }
    }

    /**
     * ⭐⭐ Every framing whose OUTPUT SHAPE an edit changed, refitted to the new
     * shape — same centre, inside the old frame (the user's call, 2026-09-17).
     *
     * ⚠⚠ HERE, the one door every canvas edit passes, rather than in each thing
     * that can change a shape — a resolution, an aspect chip, a model switch
     * into another family. One of those would have been missed.
     *
     * ⚠ Skipped when the same edit moved the rect itself (that IS the user
     * framing), and when the source picture is not in memory to measure. The
     * mask needs nothing: it is stored against the photo, not the frame.
     */
    private fun refitFramings(before: CanvasState, after: CanvasState): CanvasState {
        val types = typesFor(after.workflow.graph)
        var out = after
        for (n in after.workflow.graph.nodes) {
            if (n.type !in com.abrah.nightmare.FRAMING_TYPES) continue
            val old = before.workflow.graph.byId[n.id] ?: continue
            val type = types[n.type]
            val oldSize = com.abrah.nightmare.canvas.framingOutSize(old, type)
            val newSize = com.abrah.nightmare.canvas.framingOutSize(n, type)
            if (oldSize == newSize || newSize.first <= 0 || newSize.second <= 0) continue
            val rect = com.abrah.nightmare.canvas.cropRectOf(n)
            if (rect != com.abrah.nightmare.canvas.cropRectOf(old)) continue
            val src = after.pictureInto(n.id, nodeTypes)?.let { ops.images.get(it) } ?: continue
            val fitted = com.abrah.nightmare.canvas.refitToAspect(
                rect, src.width, src.height, newSize.first.toFloat() / newSize.second,
            )
            out = out.setParams(n.id, fitted.asParams().toMap())
        }
        return out
    }

    /**
     * ⭐⭐ Change a `image.mask` node's ops **by transforming what is there
     * NOW**, never by writing a value computed from a snapshot.
     *
     * ⚠⚠⚠ **This is the fix for "painting undoes the previous stroke".** The
     * mask editor used to do `onSetParam(id, OPS, stateOf(node).plus(op).encode())`
     * where `node` was captured when the sheet last recomposed — so a stroke
     * that landed before the next recomposition rebuilt the whole ops string
     * from a STALE list and overwrote the newer one. Paint faster than Compose
     * and strokes vanish; the eraser lost the same way, which is why it looked
     * like it did nothing. Reported from the phone, 2026-09-10.
     *
     * ⚠⚠ **Note precisely where the staleness was, because it is subtle:** the
     * WRITE was already live. `onSetParam` goes through [editCanvas], so it
     * applies to the current canvas. What was stale is the VALUE — the whole
     * ops string, rebuilt from a snapshot before being handed over as an
     * absolute. A live transform carrying a stale absolute is still a lost
     * edit, and it looks identical to a broken widget.
     *
     * ⇒ The rule [editCanvas] states has a second half: the transform must
     * COMPUTE from the live value too, not merely be applied to it. Anywhere a
     * caller reads a param, derives a new one and writes it back, that pair has
     * to happen inside the transform.
     *
     * ⚠ The RASTER was never the problem. `MaskRaster.composite` is a faithful
     * port of DreamUI's — same run splitting at each erase, same
     * invert-what-is-masked-so-far — and it is left untouched.
     */
    fun editMask(nodeId: String, change: (com.abrah.nightmare.MaskState) -> com.abrah.nightmare.MaskState) {
        editCanvas { s ->
            val node = s.workflow.graph.byId[nodeId] ?: return@editCanvas s
            s.setParam(
                nodeId,
                com.abrah.nightmare.MaskNode.OPS,
                change(com.abrah.nightmare.MaskNode.stateOf(node)).encode(),
            )
        }
    }

    /**
     * ⭐⭐ A tap in the inpaint editor's Tap tool, at ([x], [y]) in the PHOTO's
     * normalised coordinates (`docs/SEGMENTER.md`). [done] gets null, or a
     * sentence for the editor to show.
     *
     * ⚠ DreamUI's `tapSegment`, over stored taps instead of held bitmaps: a
     * tap on an already-selected object steps to the next granularity (part →
     * whole) and, after the last, removes it; anywhere else selects anew.
     *
     * ⚠⚠ The write is a TRANSFORM on the live mask ([editMask]'s rule) — the
     * segmentation takes up to a second, and a stroke painted meanwhile must
     * not be lost to a mask computed before it.
     */
    fun tapMask(nodeId: String, x: Float, y: Float, done: (String?) -> Unit) {
        val ctx = getApplication<Application>()
        val node = canvas.workflow.graph.byId[nodeId] ?: return done(null)
        val photo = canvas.pictureInto(nodeId, nodeTypes)?.let { ops.images.get(it) }
            ?: return done("no picture to select in yet — choose one on the image node")
        if (!com.abrah.nightmare.segment.Segmenter.isInstalled(ctx)) {
            return done("download ${com.abrah.nightmare.segment.Segmenter.LABEL} in Models, Tools, to tap objects")
        }
        val stored = com.abrah.nightmare.MaskNode.stateOf(node)
        // ⭐⭐⭐ **Say the first tap is loading, before it starts.**
        //
        // Asked for 2026-09-20: the first tap on a picture takes a couple of
        // seconds and looked like nothing happening. It is two costs, both
        // one-off — opening the model (~400 ms per process) and encoding this
        // photo's trunk (up to ~1.2 s per picture)
        // ([Segmenter.isWarm]).
        //
        // ⚠⚠ A toast rather than only the inline line: the mask editor is a
        // fullscreen dialog and "Finding the object…" sits under the picture,
        // which is not where a finger that just tapped is looking. ⚠ Only when
        // COLD — a toast on every tap would be noise, and the later taps are
        // fast enough to need nothing.
        if (!com.abrah.nightmare.segment.Segmenter.isWarm(photo)) {
            toast(ctx.getString(R.string.segmenter_loading))
        }
        viewModelScope.launch {
            val seg = com.abrah.nightmare.segment.Segmenter
            val outcome = withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    // ⭐ An existing selection under the finger, newest first.
                    val hit = stored.ops.filterIsInstance<com.abrah.nightmare.MaskOp.Tap>().lastOrNull { t ->
                        seg.segment(ctx, photo, t.x, t.y)?.candidates
                            ?.getOrNull(t.k)
                            ?.let { com.abrah.nightmare.MaskTaps.covers(it, x, y) } == true
                    }
                    if (hit != null) hit to seg.segment(ctx, photo, hit.x, hit.y)
                    else null to seg.segment(ctx, photo, x, y)
                }
            }
            val (hit, found) = outcome.getOrElse {
                say("segment failed — ${it.message}", bad = true)
                return@launch done("could not segment: ${it.message ?: it.javaClass.simpleName}")
            }
            if (found == null) return@launch done("nothing to select there — try the middle of the object")
            editCanvas { s ->
                val live = s.workflow.graph.byId[nodeId] ?: return@editCanvas s
                val mask = com.abrah.nightmare.MaskNode.stateOf(live)
                val ops = if (hit != null) {
                    val i = mask.ops.lastIndexOf(hit)
                    if (i < 0) return@editCanvas s
                    // ⚠ The cycle ends when it would come back round to where a
                    // tap starts — which is computable, so no visit count is stored.
                    val next = (hit.k + 1) % found.candidates.size
                    if (next == found.default) mask.ops.filterIndexed { j, _ -> j != i }
                    else mask.ops.mapIndexed { j, op -> if (j == i) hit.copy(k = next) else op }
                } else {
                    mask.ops + com.abrah.nightmare.MaskOp.Tap(x, y, found.default)
                }
                val values = mutableMapOf(com.abrah.nightmare.MaskNode.OPS to mask.copy(ops = ops).encode())
                // ⭐ The first tap gives the mask DreamUI's slack: a region traces
                // the object's true edge, and repainting exactly to it leaves a
                // halo. `MaskState.growFrac` defaults to zero only for brushes.
                if (hit == null && (live.params["grow"]?.toFloatOrNull() ?: 0f) == 0f) {
                    values["grow"] = TAP_GROW.toString()
                }
                s.setParams(nodeId, values)
            }
            done(null)
        }
    }

    /**
     ⭐ Forget the picture on a `load_image` node -- the uri AND the pixels.
     *
     * ⚠⚠ Clearing the param is only half of it. Previews are added and never
     * removed ([resolvePreviews]), so the node would keep drawing the photo the
     * user just deleted, and so would every `crop` downstream of it -- a failed
     * re-resolve leaves the last good picture in place, which is right for a
     * transient error and wrong for a deliberate delete.
     *
     * ⚠ Their SIGNATURES go too, or the next refresh finds the node unchanged
     * and never asks again.
     */
    fun clearImage(nodeId: String) {
        editCanvas { s -> s.setParam(nodeId, "uri", "") }
        val graph = canvas.workflow.graph
        fun downstream(id: String) = id == nodeId || graph.dependsOn(id, nodeId)
        canvas = canvas.copy(
            previews = canvas.previews.filterKeys { !downstream(it) },
            rendered = canvas.rendered.filterKeys { !downstream(it) },
        )
        previewSigs.keys.retainAll { !downstream(it) }
    }

    /**
     * ⭐ Drop a RENDER from the node that produced it.
     *
     * ⚠ Not [clearImage]: that clears a `load_image`'s `uri`, which a decode
     * node does not have. A render exists only as a preview and a cache entry,
     * and only the PREVIEW is dropped — see the note on the cache below.
     */
    fun clearOutput(nodeId: String) {
        val graph = canvas.workflow.graph
        fun downstream(id: String) = id == nodeId || graph.dependsOn(id, nodeId)
        // ⭐⭐ …and the AUTOSAVED copy in History with it (the user's call,
        // 2026-09-17). ⚠ Only autosaved and not starred: a copy kept or starred
        // by hand stays — see [com.abrah.nightmare.canvas.Result.auto].
        val shownIds = canvas.previews.filterKeys { downstream(it) }.values.map { it.first }.toSet()
        val dropped = kept.filter { it.auto && !it.favourite && it.imageId in shownIds }
        if (dropped.isNotEmpty()) {
            dropped.forEach { results.delete(it.id) }
            refreshResults()
        }
        canvas = canvas.copy(
            previews = canvas.previews.filterKeys { !downstream(it) },
            rendered = canvas.rendered.filterKeys { !downstream(it) },
        )
        previewSigs.keys.retainAll { !downstream(it) }
        // ⚠ The executor's cache is deliberately NOT dropped. With `seed = 0`
        // the next Run rolls a new seed, so the key differs and a fresh picture
        // is made anyway; with the seed LOCKED, getting the same picture back
        // is what locking a seed means. Clearing the cache would break the
        // second case to no benefit in the first.
        say("cleared the picture on $nodeId")
    }

    /**
     * ⭐ Write a rendered picture into the phone's gallery.
     *
     * ⚠ On IO: this encodes a PNG and hands it to MediaStore, which is a file
     * write, and a 1024² render is megabytes.
     */
    /**
     * ⭐⭐ **The one place a gallery save is announced.**
     *
     * ⚠⚠ A `say()` line goes to the harness log, which nobody has open — so
     * saving a picture from the canvas, from a result, or from a selection all
     * appeared to do NOTHING. A toast is the right weight for it: the action
     * succeeded, it is not worth a dialog, and the user is looking at the
     * picture rather than at a log. Asked for from the phone, 2026-09-10 —
     * "consistently for any image saves, even in the node viewer".
     *
     * ⚠ On the main thread: `Toast.makeText` from a worker throws, and the
     * saves here all finish on IO.
     */
    fun toast(text: String) {
        android.widget.Toast.makeText(
            getApplication(), text, android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * ⭐⭐ The CLIP a node's picture is the poster of, or null.
     *
     * ⚠⚠ Reversed out of [CanvasState.previews] rather than asked of the
     * caller. Every save/share surface is keyed by an **image** id — the
     * fullscreen viewer keeps `viewing` a plain image id on purpose, and the
     * inspector passes the same one — so making them clip-aware from the UI
     * side would have meant threading a node id through three composables and
     * the goldens that render them. The map that answers it is already here.
     *
     * ⚠⚠⚠ **[clipNodes] decides, exactly as it does for what animates.**
     * The `VIDEO` value flows sampler → output, so every node on the chain
     * holds the same path — and an earlier cut of this let ANY of them share
     * it, on the reasoning that they all name the same file. That is not what
     * the user sees: they see a node showing a still that sends a video.
     * Reported from the phone, 2026-09-13: *"i still don't get why the sample
     * in fullscreen/share is showing the video output"*. ⇒ The END of the
     * chain owns the clip for every purpose — looping, fullscreen, Save and
     * Share — and an upstream node saves and shares its own still.
     *
     * ⚠⚠ `isFile`, not merely non-null. The graph's MP4 is in
     * `cacheDir/video/` and Android clears that whenever it likes, so the path
     * outliving the file is the ordinary case rather than the odd one.
     */
    private fun clipForImage(imageId: String): java.io.File? {
        // ⚠⚠ The join is [com.abrah.nightmare.canvas.clipOwner]'s, not this
        // function's — it has been hand-rolled wrongly twice and the reasoning
        // lives with it. Save, Share and every keep come through here.
        val node = com.abrah.nightmare.canvas.clipOwner(
            canvas.workflow.graph, canvas.previews, canvas.videos, imageId,
        ) ?: return null
        return canvas.videos[node]?.let { java.io.File(it) }?.takeIf { it.isFile }
    }

    fun saveImage(imageId: String) {
        val ctx = getApplication<Application>()
        // ⭐⭐ A clip saves as the CLIP. Writing the poster PNG while the user
        // is watching it move is the "poster frame is not a video" complaint in
        // a second place — `docs/NEODRAGON.md` §7c.
        val clip = clipForImage(imageId)
        // ⚠⚠ The BITMAP, not `images.png(...)`. The bytes form builds two
        // full copies in the heap and then caches a third in the store; at
        // SDXL-upscale size that is what stopped this working while Results —
        // which streams — kept working. [ImageSaver.saveBitmap].
        val bmp = ops.images.get(imageId)
        if (clip == null && bmp == null) {
            // ⚠ Toasted too: every OUTCOME of a save is announced the same
            // way, or the one that fails is the one nobody hears about.
            say("that picture is no longer in memory — Run again to remake it", bad = true)
            toast("That picture is no longer in memory — Run again")
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = "nightmare-" + System.currentTimeMillis()
            val where = runCatching {
                if (clip != null) {
                    com.abrah.nightmare.npu.VideoWriter
                        .publish(ctx, clip, name + ".mp4").toString()
                } else {
                    ImageSaver.saveBitmap(ctx, bmp!!, name)
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                val what = if (clip != null) "clip" else "picture"
                where.fold(
                    onSuccess = {
                        say("saved " + what + " to " + it)
                        toast(
                            if (clip != null) "Clip saved to the gallery"
                            else "Saved to the gallery"
                        )
                    },
                    onFailure = {
                        say("could not save the " + what + " — ${it.message}", bad = true)
                        toast("Could not save: " + it.message)
                    },
                )
            }
        }
    }

    /**
     * ⭐⭐ Write every consumer-derived output size into the node that must
     * produce it.
     *
     * ⚠⚠ Here rather than in the inspector, though the inspector is where the
     * user sees it. A preview runs the REAL node from its REAL params, so if the
     * size lived only in the sheet the node's own thumbnail would disagree with
     * the framing view above it -- and a workflow saved without the sheet ever
     * being opened would render something else again. ⇒ The graph is the single
     * copy, the canvas keeps it true, and the widgets are shown locked with the
     * reason (`Framing.kt`).
     *
     * ⚠ Returns the SAME state when nothing moved, so this cannot loop: the
     * write it makes is exactly the value the next derivation computes.
     */
    /**
     * The node types to reason about [graph] with.
     *
     * ⚠⚠ **Built-ins unless the graph actually names a pack.** Resolving
     * [nodeTypes] builds the QuickJS runtime, and `restoreWorkflow` is explicit
     * that a built-ins-only workflow must not pay for the engine merely to be
     * reopened. Calling `nodeTypes` unconditionally broke four
     * `WorkflowRestoreTest` cases with `UnsatisfiedLinkError` -- on the JVM
     * there is no native library at all -- and on a phone it would have been an
     * invisible cost on the app's own start-up path.
     *
     * ⚠ A pack-qualified type name is the test, which is the same `pack:Node`
     * spelling the manifest and the workflow file use.
     */
    private fun typesFor(graph: Graph): Map<String, NodeType> =
        if (graph.nodes.any { ':' in it.type }) nodeTypes else NODE_TYPES

    private fun deriveSizes(state: CanvasState): CanvasState = try {
        derivedSizes(state)
    } catch (e: Throwable) {
        // ⚠ A canvas must still open. `nodeTypes` can throw for reasons that
        // have nothing to do with sizes -- a missing native library, a plugin
        // whose manifest will not parse -- and losing the user's graph to that
        // would be far worse than a `crop` whose numbers are a moment stale.
        say("could not work out the crop sizes — ${e.javaClass.simpleName}: ${e.message}", bad = true)
        state
    }

    private fun derivedSizes(state: CanvasState): CanvasState {
        val graph = state.workflow.graph
        // ⚠ The loop lives in [deriveSizes] so a test can reach it. A single
        // pass cannot settle a chain of consumer-derived nodes, which the
        // inpaint recipe has (`crop -> mask -> latent_blend`).
        val next = deriveSizes(graph, typesFor(graph))
        return if (next === graph) state
        else state.copy(workflow = state.workflow.copy(graph = next))
    }

    fun updateCanvas(incoming: CanvasState) {
        // ⚠ Only when the graph actually moved: `updateCanvas` fires on every
        // pointer event of a pan, and re-deriving there is work for nothing.
        val next =
            if (incoming.workflow !== canvas.workflow) deriveSizes(incoming) else incoming
        val changed = next.workflow !== canvas.workflow
        val opened = next.editing != canvas.editing
        // ⚠⚠ **Previews are OURS, and [next]'s copy of them is thrown away.**
        //
        // The canvas hands back a whole `CanvasState` derived from the one it
        // was composed with, and during a drag several pointer events are
        // handled between two recompositions -- so every one of them carries the
        // preview map as it was at the last frame. A picture resolved in between
        // was therefore applied and then immediately overwritten by the next
        // pointer event, which is precisely "it doesn't update the image in the
        // canvas view" while the sheet above it was already correct. Reported
        // from the phone, 2026-09-09, on the build that added free previews.
        //
        // ⇒ Nothing in the UI ever ADDS or removes a preview -- only
        // [resolvePreviews] and the Run do -- so the invariant is simply that
        // this map does not travel through the composition at all.
        canvas = next.copy(
            previews = canvas.previews,
            // ⚠ The VM's, for the same reason as previews.
            rendered = canvas.rendered,
            // ⚠ The crop request is the VM's too, for the same reason: it is set
            // when a picture ARRIVES, between two frames, and a pointer event
            // carrying the last frame's copy would erase it. ⚠ Closing the sheet
            // still clears it.
            cropRequest = if (next.editing == null) null else canvas.cropRequest ?: next.cropRequest,
        )
        // ⚠⚠ …or when only the VIEW moved. A pan changes no node, so the graph
        // was saved and the viewport was not -- and a cold start then reopened
        // the user's own workflow onto empty space, which is the bug the saved
        // view exists to fix. ⚠ Gated on Idle, so this is one debounced write
        // per gesture rather than one per pointer event.
        val viewMoved = next.viewport != canvas.viewport ||
            next.zoomLocked != canvas.zoomLocked || next.panLocked != canvas.panLocked
        if ((changed || viewMoved) &&
            next.gesture is com.abrah.nightmare.canvas.Gesture.Idle
        ) saveWorkflow()
        // ⭐ A picture that can be made without the NPU is made now, not at
        // Run. ⚠ Guarded on an actual edit: `updateCanvas` also fires on every
        // pointer event of a pan, and relaunching the job for those would be
        // work for nothing. [refreshPreviews] is debounced and signature-checked
        // on top of that, so a node DRAG -- which changes the workflow but not a
        // single param -- costs one cancelled coroutine and no graph run.
        if (changed || opened) refreshPreviews()
    }

    private val store by lazy {
        WorkflowStore(java.io.File(getApplication<Application>().filesDir, "workflows"))
    }

    // ---- results ---------------------------------------------------------

    private val results by lazy {
        com.abrah.nightmare.canvas.ResultsStore(
            java.io.File(getApplication<Application>().filesDir, "results")
        )
    }

    var kept by mutableStateOf<List<com.abrah.nightmare.canvas.Result>>(emptyList())
        private set

    /** ⭐ The same results, with each sweep gathered into one entry. */
    var keptGroups by mutableStateOf<List<com.abrah.nightmare.canvas.ResultGroup>>(emptyList())
        private set

    var keptBytes by mutableStateOf(0L)
        private set

    /**
     * ⚠ Thumbnails decoded once and cached, like [bitmaps]. A list of 1024²
     * PNGs decoded per frame is a megabyte per card per frame.
     */
    private val thumbs = mutableStateMapOf<String, ImageBitmap>()

    /**
     * ⚠⚠ Ids with a decode already in flight, so a LazyVerticalGrid
     * recomposing the same item several times per scroll frame launches ONE
     * coroutine per id, not one per composition.
     */
    private val thumbLoading = mutableSetOf<String>()

    /**
     * ⭐⭐ Async, off the main thread. Returns the cached bitmap immediately
     * when there is one and null otherwise, kicking off a decode that fills
     * `thumbs` (a `mutableStateMapOf`, so the write itself triggers the
     * recomposition that shows the picture once it lands).
     *
     * ⚠⚠ This used to decode SYNCHRONOUSLY, inline, on whatever thread calls
     * it — which for every caller is composition. Reported 2026-09-18 as
     * still-visible lag after the pager's own decode was already fixed: the
     * GRID was the other half, `BitmapFactory.decodeFile` twice per thumbnail
     * (a bounds pass, then the real one) on the UI thread, once per new item
     * scrolled into view. Small per call, but disk I/O on the thread that owns
     * the frame budget is exactly the "something stupidly inefficient" this
     * was asked to find.
     */
    fun thumbnailFor(id: String): ImageBitmap? {
        thumbs[id]?.let { return it }
        if (thumbLoading.add(id)) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val bmp = results.thumbnail(id)?.asImageBitmap()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    thumbLoading.remove(id)
                    if (bmp != null) thumbs[id] = bmp else markUnreadable(id, "thumbnail")
                }
            }
        }
        return null
    }

    /**
     * ⭐⭐ Kept results whose PNG will not decode — drawn as a "can't read this
     * picture" card instead of a blank one.
     *
     * ⚠⚠ A failed decode used to write NOTHING, so no recomposition followed
     * and the card stayed an empty grey square forever, indistinguishable from
     * one still loading. Reported from a phone 2026-09-19; the cause was two
     * autosaves interleaving one PNG (`ResultsStore.keep`), and the files that
     * race already wrote cannot be repaired — this is how their owner finds and
     * deletes them. ⚠ A state map, so marking one redraws the card.
     */
    private val unreadable = mutableStateMapOf<String, Unit>()

    fun resultUnreadable(id: String): Boolean = id in unreadable

    private fun markUnreadable(id: String, what: String) {
        unreadable[id] = Unit
        android.util.Log.w("Harness", "result $id: $what decode failed (${results.imageFile(id).length()} bytes)")
    }

    fun refreshResults() {
        kept = results.all()
        keptGroups = results.grouped()
        keptBytes = results.bytes()
    }

    /**
     * ⭐⭐ Keep a picture AND the graph that made it.
     *
     * ⚠ The graph is captured NOW, not when the picture was made — which is the
     * same thing only because the canvas cannot have changed between a render
     * finishing and the user tapping the star on it. ⚠ If that ever stops being
     * true (a background run, say) this has to capture at render time instead.
     */
    /**
     * @param flow the graph that ACTUALLY produced this picture, when it is not
     *   the one on the canvas.
     *
     * ⚠⚠ A batch run needs this. Each iteration runs a COPY with overrides
     * applied, so storing `canvas.workflow` would file every result under the
     * un-swept graph — and "Open flow" on the seed-3 picture would restore a
     * flow that makes the seed-1 one. The stored flow is the whole reason
     * Results is more than a gallery.
     */
    /**
     * ⭐⭐ Is this picture already kept? Drives the star's filled/outline state.
     *
     * ⚠ Matched on [Result.imageId], a content address, so the SAME pixels
     * re-rendered from a cache still read as kept.
     */
    fun isKept(imageId: String): Boolean = kept.any { it.imageId == imageId }

    /**
     * ⭐⭐ Star, or UN-star. A second tap removes what the first kept.
     *
     * ⚠⚠ The star was one-way: tapping it again filed a SECOND copy of the
     * same picture, so a mis-tap could only be undone by going to Results and
     * deleting it. Asked for from the phone, 2026-09-11.
     *
     * ⚠ Removes every result naming this image, not just the first — a
     * duplicate kept before this existed should not survive the un-star and
     * leave the state looking unchanged.
     */
    fun toggleKeepResult(
        imageId: String,
        flow: com.abrah.nightmare.canvas.Workflow? = null,
        batchId: String? = null,
        batchLabel: String = "",
        /**
         * ⭐⭐ True when the STAR did this rather than the disk (2026-09-15).
         *
         * ⚠⚠ The two buttons differ only in this flag, and the TOGGLE differs
         * with them: the disk un-keeps (the picture leaves Results), while the
         * star on something already kept only clears the FLAG — un-starring
         * must not throw away a picture the user kept on purpose.
         */
        favourite: Boolean = false,
    ) {
        val existing = kept.filter { it.imageId == imageId }
        if (existing.isEmpty()) {
            keepResult(imageId, flow, batchId, batchLabel, favourite)
            return
        }
        // ⭐ Already kept, and the STAR was tapped: flip the flag, keep the picture.
        if (favourite) {
            val on = !existing.any { it.favourite }
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                existing.forEach { runCatching { results.setFavourite(it.id, on) } }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    say(if (on) "favourited" else "un-favourited")
                    refreshResults()
                }
            }
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            existing.forEach { runCatching { results.delete(it.id) } }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                say("removed from Results")
                refreshResults()
            }
        }
    }

    /**
     * ⭐ Whether the Results tab is showing favourites only.
     *
     * ⚠ On the ViewModel rather than inside the screen so it survives opening a
     * result and coming back — a filter that resets every time you look at a
     * picture is a filter nobody uses twice.
     */
    var favouritesOnly by mutableStateOf(false)
        private set

    fun showFavouritesOnly(on: Boolean) { favouritesOnly = on }

    // ---- swapping a sampler's checkpoint, in the node ---------------------

    /**
     * ⭐⭐⭐ A swap waiting on the user's yes — and everything the dialog needs
     * to say what it will change.
     *
     * ⚠⚠ It used to carry three fields and the dialog **was never built**:
     * `pendingSwap` reached `CanvasScreen` as a parameter that nothing in its
     * body read, so picking a checkpoint from another family set this and then
     * silently did nothing at all. Half of *"the checkpoint picker is broken"*,
     * 2026-09-15, was exactly that.
     *
     * ⚠ Every field here is a thing to OFFER, and each is null when there is
     * nothing to offer — [HarnessViewModel.setNodeModel] applies straight away
     * when they all are, so the dialog appears when it has something to ask and
     * not on every tap of a list.
     */
    data class ModelSwap(
        val nodeId: String,
        val spec: ModelSpec,
        val newType: String,
        /** The family being left, or null when the family does not change. */
        val fromFamily: Family? = null,
        /** The prompt node feeding this sampler, when its text would change. */
        val promptNode: String? = null,
        /** What that node would say — shown, because it replaces what is typed. */
        val prompt: String = "",
        val negative: String = "",
        /** `20 steps, cfg 7.5, dpmpp` when the checkpoint publishes different ones. */
        val recipe: String? = null,
        /**
         * ⭐ The radios' starting answers: what the user chose LAST time
         * (asked for 2026-09-17), "take theirs" until they have chosen once.
         */
        val takePromptDefault: Boolean = true,
        val takeRecipeDefault: Boolean = true,
    )

    var pendingSwap by mutableStateOf<ModelSwap?>(null)
        private set

    private val swapPrefs get() =
        getApplication<Application>().getSharedPreferences("nightmare", android.content.Context.MODE_PRIVATE)

    /**
     * ⭐ The dialog's confirm: remember both answers, then apply. ⚠ Only a
     * choice the dialog SHOWED is remembered — a row it hid had no answer.
     */
    fun confirmSwap(swap: ModelSwap, takeRecipe: Boolean, takePrompt: Boolean) {
        swapPrefs.edit().apply {
            if (swap.recipe != null) putBoolean(SWAP_TAKE_RECIPE, takeRecipe)
            if (swap.promptNode != null) putBoolean(SWAP_TAKE_PROMPT, takePrompt)
        }.apply()
        applyNodeModel(swap.nodeId, swap.spec, swap.newType, takeRecipe, takePrompt)
    }

    fun cancelSwap() { pendingSwap = null }

    /**
     * ⚠ [busy] OR an install — for the HARNESS screen, whose buttons are
     * gated on "the app is doing something long", and which is the reason the
     * two were ever one flag. ⚠⚠ The canvas must NOT read this: its Run button
     * becomes a Cancel, and a download is not a run to cancel.
     */
    val working: Boolean get() = busy || installing != null

    /**
     * ⭐⭐⭐ Point one sampler at a different checkpoint.
     *
     * ⚠⚠ When the FAMILY changes the node's TYPE changes with it — `sd15.sample`
     * becomes `sdxl.sample` — and that is safe precisely because the two declare
     * the same ports (`docs/ARCHITECTURE.md` §5.7): every wire survives, which
     * was the user's own argument for allowing it. ⚠ Capability never changes
     * here: a `sample` cannot become an `inpaint`, because those do NOT have the
     * same ports and a mask wire would be dropped.
     *
     * ⚠ It asks first when the family moves, and only then.
     */
    /**
     * ⭐⭐ The node whose text feeds this sampler, if any.
     *
     * ⚠ By WIDGET, not by type — the same test [modelPromptRetarget] uses, so a
     * plugin prompt node is offered on the same terms a built-in one is.
     */
    private fun promptFeeding(nodeId: String): Node? {
        val g = canvas.workflow.graph
        val src = g.byId[nodeId]?.inputs?.get("prompt")?.node ?: return null
        val n = g.byId[src] ?: return null
        val has = nodeTypes[n.type]?.widgets?.map { it.name }?.toSet().orEmpty()
        return n.takeIf { "prompt" in has && "negative" in has }
    }

    fun setNodeModel(nodeId: String, modelId: String) {
        val spec = ModelCatalog.byId(modelId) ?: return
        // ⚠⚠ Refused with the REASON rather than silently accepted. A node
        // pointed at a checkpoint that is not on the phone fails at Run, nine
        // seconds and one backend launch later, with an error about a directory.
        // ⚠ "Partial" is its own answer: an import that lost a file looks
        // installed from the outside (the folder is there, it is gigabytes) and
        // this is the only place that says otherwise.
        val ctx = getApplication<Application>()
        if (!spec.installed(ctx)) {
            val missing = spec.missing(ctx)
            toast(
                if (missing.isEmpty()) "${spec.label} is not installed"
                else "${spec.label} is incomplete — missing ${missing.joinToString()}"
            )
            return
        }
        val node = canvas.workflow.graph.byId[nodeId] ?: return
        val type = nodeTypes[node.type] as? SdSampler ?: return
        val newType = SdSampler.typeFor(spec.family, type.inpaint)

        // ⭐⭐⭐ **…and the PROMPT, which the user asked to be offered here.**
        //
        // ⚠⚠ Offered rather than applied. The top-bar picker rewrites a prompt
        // silently through [modelPromptRetarget], and may, because that function
        // only ever overwrites text the app itself wrote. Here the answer is a
        // dialog instead: a checkpoint swap inside a node is a deliberate act on
        // one sampler, and the person doing it is the one who can say whether
        // the sentence they typed should follow. The user's ask, 2026-09-15 —
        // *"when changing checkpoint also allow to change the prompt"*.
        val feeder = promptFeeding(nodeId)
        val promptNode = feeder?.takeIf {
            it.params["prompt"] != spec.starterPrompt ||
                it.params["negative"] != spec.starterNegative
        }
        // ⚠ Only when the checkpoint's published numbers differ from what this
        // node already carries — a row saying "and its settings" that changes
        // nothing is a row that teaches people to ignore the dialog.
        val recipe = listOf(
            "steps" to spec.steps.toString(),
            "cfg" to spec.cfg.toString(),
            "scheduler" to spec.scheduler,
        ).takeIf { r -> r.any { node.params[it.first] != it.second } }
            ?.let { "${spec.steps} steps, cfg ${spec.cfg}, ${spec.scheduler}" }

        val swap = ModelSwap(
            nodeId, spec, newType,
            fromFamily = type.family.takeIf { it != spec.family },
            promptNode = promptNode?.id,
            prompt = spec.starterPrompt,
            negative = spec.starterNegative,
            recipe = recipe,
            takePromptDefault = swapPrefs.getBoolean(SWAP_TAKE_PROMPT, true),
            takeRecipeDefault = swapPrefs.getBoolean(SWAP_TAKE_RECIPE, true),
        )
        // ⚠ Nothing to ask → nothing is asked. Picking a second SD 1.5
        // checkpoint whose recipe matches rewrites one param, and a dialog for
        // that would be a dialog on every tap of a list.
        if (swap.fromFamily == null && swap.promptNode == null && swap.recipe == null) {
            applyNodeModel(nodeId, spec, newType, takeRecipe = false)
        } else {
            pendingSwap = swap
        }
    }

    /**
     * @param takeRecipe overwrite `steps`/`cfg`/`scheduler` with the
     *   checkpoint's published ones. ⚠⚠ **Off by default** — the user's call,
     *   2026-09-15. `modelRecipeRetarget` does overwrite them when the PICKER
     *   changes model, on the reasoning that asking for a checkpoint is asking
     *   for its settings; inside a node the user has usually just tuned those
     *   numbers, and losing them to a family swap is the surprise.
     */
    fun applyNodeModel(
        nodeId: String,
        spec: ModelSpec,
        newType: String,
        takeRecipe: Boolean,
        /**
         * ⭐ Also write [ModelSpec.starterPrompt] onto the prompt node feeding
         * this sampler.
         *
         * ⚠⚠ Unlike [modelPromptRetarget] this DOES overwrite a sentence the
         * user typed. `ModelSwapDialog` defaults its radio to yes and calls that
         * the recommended answer, which is only defensible because the dialog
         * SHOWS the replacement text first. ⚠ The parameter's own default stays
         * **false**, so any caller that does not ask a person changes nothing.
         */
        takePrompt: Boolean = false,
    ) {
        pendingSwap = null
        val g = canvas.workflow.graph
        val node = g.byId[nodeId] ?: return
        // ⚠⚠ The SIZE has to move: SDXL renders 1024 whatever the request says
        // (`docs/MODELS.md` §3), so a node left at 512 would render 1024 and
        // decode it as 512 — a plausible picture, silently wrong.
        val params = node.params.toMutableMap()
        params["model"] = spec.id
        params["width"] = spec.native.width.toString()
        params["height"] = spec.native.height.toString()
        if (takeRecipe) {
            params["steps"] = spec.steps.toString()
            params["cfg"] = spec.cfg.toString()
            params["scheduler"] = spec.scheduler
        }
        // ⚠ A sampler the new family does not have is replaced even when the
        // recipe was declined: `dpm` on Anima is not refused, it silently runs
        // the ancestral sampler under the wrong name.
        if (params["scheduler"] !in ModelCatalog.schedulersFor(spec.family)) {
            params["scheduler"] = spec.scheduler
        }
        // ⭐⭐ The whole photo in view for the NEW shape (the user's call,
        // 2026-09-17) — fitted and padded on inpaint, filling the frame on
        // image-to-image ([com.abrah.nightmare.canvas.wholePhotoFraming]).
        // ⚠ Only when the picture is in memory to measure; the crop popup then
        // opens on it, below.
        val photo = canvas.pictureInto(nodeId, nodeTypes)?.let { ops.images.get(it) }
        if (photo != null) {
            params.putAll(autoFraming(node.copy(type = newType, params = params), photo))
        }
        val promptId = if (takePrompt) promptFeeding(nodeId)?.id else null
        val next = Graph(
            g.nodes.map {
                when (it.id) {
                    nodeId -> it.copy(type = newType, params = params)
                    promptId -> it.copy(
                        params = it.params +
                            mapOf("prompt" to spec.starterPrompt, "negative" to spec.starterNegative)
                    )
                    else -> it
                }
            }
        )
        canvas = canvas.copy(
            workflow = canvas.workflow.copy(graph = next),
            // ⭐ Open the crop on the new framing — only when there is a picture.
            cropRequest = if (photo != null) nodeId to ((canvas.cropRequest?.second ?: 0) + 1)
            else canvas.cropRequest,
            cropRequestTab = 0,
        )
        // ⚠⚠ At once, not at the next 2 s tick. The top bar names what the
        // graph will load, and a readout that lags a deliberate change by two
        // seconds is read as not having taken it.
        refreshLoad()
        say(
            if (newType == node.type) "${node.id}: ${spec.label}"
            else "${node.id}: ${spec.label} (${spec.family.label})",
        )
        // ⚠ Named, never silent: it replaced a sentence somebody wrote.
        promptId?.let { say("  ${spec.label}'s prompt written to $it") }
        saveWorkflow()
    }

    /**
     * ⭐⭐ What the open graph will cost in backend relaunches — the run bar says
     * it BEFORE the button is pressed.
     *
     * ⚠ Off the SCHEDULED order, never the graph: the whole point of
     * [scheduleByKey] is that the count depends on the order it chooses
     * (`docs/ARCHITECTURE.md` §4).
     */
    val plannedLoads: Int
        get() = runCatching {
            val g = canvas.workflow.graph
            val types = typesFor(g)
            val order = (topoSort(g) as? Order.Ok)?.nodes ?: return@runCatching 0
            keyTransitions(
                scheduleByKey(order, { types.getValue(it.type).contextKey(it) }, BackendProcess.launchedKey),
            ) { types.getValue(it.type).contextKey(it) }
        }.getOrDefault(0)


    /**
     * ⭐ The recipe the open canvas came from, for the save dialog to name it.
     *
     * ⚠ Cleared when a SAVED flow is opened: that graph has a name of its own,
     * and suggesting "t2i_02" for a flow called "harbour at dusk" would be
     * offering to fork it under a name that says nothing.
     */
    var openedRecipeId by mutableStateOf<String?>(null)
        private set

    /**
     * ⭐⭐⭐ Open a recommended flow — **through [guardedOpen], like every
     * other way of replacing the canvas.**
     *
     * ⚠⚠⚠ It did not, and that was the whole bug: tapping a recipe in the
     * Flows tab called [openWorkflow] directly and threw away unsaved work with
     * nothing said, while the SAVED flow one row below it and the open-flow
     * button in Results both asked first. Reported from the phone, 2026-09-15:
     * *"in Flows tab when i click flow it needs same confirm popup as open flow
     * icon from results tab"*.
     *
     * ⚠⚠ The N−1-of-N rule (`docs/ARCHITECTURE.md` §5.6): three routes
     * replace the canvas, two honoured the guard, and the one that did not is a
     * row the user taps more often than either of the others.
     */
    fun openRecipe(r: com.abrah.nightmare.canvas.Recipe) =
        guardedOpen(r.label) { openRecipeNow(r) }

    /**
     * ⚠⚠ The unguarded half, for the ONE caller that has already asked:
     * [confirmUse] warns about unsaved work inside its own dialog before
     * offering the flows, so routing it back through [guardedOpen] would ask
     * the same question twice in a row.
     */
    private fun openRecipeNow(r: com.abrah.nightmare.canvas.Recipe) {
        openedRecipeId = r.id
        openWorkflow(r.build())
    }

    /**
     * ⭐⭐ The name the save dialog starts with — `t2i_01`, `inpaint_02`.
     *
     * ⚠⚠ The INDEX skips what is already saved, so pressing save twice does not
     * offer the same name twice and quietly replace the first. ⚠ It is a
     * SUGGESTION in an editable box, never a silent filename: a flow worth
     * keeping usually deserves a real name, and this is the floor for the times
     * it does not.
     */
    fun suggestedFlowName(): String {
        val base = openedRecipeId ?: "flow"
        val taken = savedWorkflows.map { it.name.lowercase() }.toSet()
        var i = 1
        while ("%s_%02d".format(base, i).lowercase() in taken) i++
        return "%s_%02d".format(base, i)
    }

    /** ⭐ Star or un-star one kept result, from the Results tab. */
    fun toggleResultFavourite(r: com.abrah.nightmare.canvas.Result) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { results.setFavourite(r.id, !r.favourite) }
            withContext(kotlinx.coroutines.Dispatchers.Main) { refreshResults() }
        }
    }

    /** ⚠ Whether the picture behind [imageId] is flagged — the star's tint. */
    fun isFavourite(imageId: String?): Boolean =
        imageId != null && kept.any { it.imageId == imageId && it.favourite }

    /**
     * ⭐⭐ Why the keep button is dimmed, or null when it is live.
     *
     * ⚠ A sentence, not a boolean: it is what the toast says when the disabled
     * button is tapped, and "autosave is on" with no way to find the switch is
     * the kind of dead end §8 exists to stop.
     */
    val keepDisabledReason: String?
        get() = if (MediaOutputNode.autosaves(canvas.workflow.graph)) {
            "autosave is on — every Run is kept. Turn it off on the Output node to keep by hand."
        } else null

    fun keepResult(
        imageId: String,
        flow: com.abrah.nightmare.canvas.Workflow? = null,
        batchId: String? = null,
        batchLabel: String = "",
        favourite: Boolean = false,
        auto: Boolean = false,
        /**
         * ⭐ The node whose picture this is — autosave passes its output node.
         * Null means "whichever node on the canvas shows [imageId]".
         */
        from: String? = null,
    ) {
        val bmp = ops.images.get(imageId)
        if (bmp == null) {
            say("that picture is no longer in memory", bad = true)
            return
        }
        val kept = flow ?: canvas.workflow
        val graph = kept.graph
        // ⭐⭐ The sampler UPSTREAM OF THIS PICTURE, through [samplerFor] — the
        // same nearest-first walk the canvas viewer's seed uses, so the two
        // cannot disagree about which render a picture came from.
        //
        // ⚠⚠ It was "the LAST sampler in graph order" for the whole graph, which
        // is right only while a graph has one branch. A flow with two outputs fed
        // by two samplers filed BOTH pictures under the second sampler's seed
        // and the globally selected model (reported with the two-output save
        // race, 2026-09-19). ⚠ That rule survives as the fallback for a flow
        // this canvas is not showing (an upscale's own little graph).
        val origin = from ?: canvas.previews.entries
            .firstOrNull { it.value.first == imageId && it.key in graph.byId }?.key
        // ⚠ Any sampler -- see [com.abrah.nightmare.SAMPLER_TYPES]. A kept clip
        // filed no seed at all while `sd.sample` was the only type matched here.
        val sampler = origin?.let { com.abrah.nightmare.canvas.samplerFor(graph, it) }?.let { graph.byId[it] }
            ?: ((topoSort(graph) as? Order.Ok)?.nodes ?: graph.nodes)
                .lastOrNull { com.abrah.nightmare.isSampler(it.type) }
        val seed = sampler?.id?.let { id ->
            com.abrah.nightmare.canvas.seedFor(graph, id) { canvasStatus[it]?.detail }
        }
        // ⚠ The sampler's OWN checkpoint — a model is a per-node choice
        // (`docs/ARCHITECTURE.md` §5.7), so the selected one names only one branch.
        val modelLabel = (ModelCatalog.byId(sampler?.params?.get("model").orEmpty()) ?: SelectedModel.spec).label
        // ⚠ The video sampler carries its prompt itself; every picture recipe
        // puts it on a `clip_encode`. Neither graph has both.
        val prompt = graph.nodes.firstOrNull { it.type == "sd.clip_encode" }?.params?.get("prompt")
            ?: graph.nodes.firstOrNull { it.type == "nd.clip_encode" }?.params?.get("prompt")
        val workflow = kept
        val types = nodeTypes
        // ⭐⭐ The CLIP behind this poster, when there is one — through
        // [clipForImage], which is the SAME lookup Save and Share use.
        //
        // ⚠⚠⚠ Two bugs lived in the hand-rolled copy this replaces, and both
        // are the rules in `docs/ARCHITECTURE.md` §5.6.
        //
        // **One: `if (flow == null) … else null`.** The reasoning was "a `flow`
        // passed in is a graph, and a graph has no rendered output to keep" —
        // true of a SAVED flow, and false of the two callers that actually pass
        // one: **autosave** hands over `canvas.workflow`, and a **batch** hands
        // over the live graph with one param overridden. Both are the live
        // canvas. So every auto-kept and every swept video result filed with
        // `videoPath = null` and Results showed a still, while the node viewer
        // — which reads `canvas.videos` directly — kept playing. Reported from
        // the phone, 2026-09-15: *"results tab regressed on the video output,
        // it just shows static img. node viewer is fine"*.
        //
        // **Two: `firstOrNull`.** Several nodes share one poster id
        // (`Value.Video.previewImage()`), so this picked whichever node the map
        // happened to hold first — usually the sampler, which [clipNodes]
        // correctly rejects as not owning the clip. That is the identical bug
        // [clipForImage] was written to fix on 2026-09-13, reintroduced by
        // copying the lookup instead of calling it. ⚠⚠ Two surfaces that must
        // agree call the SAME function.
        val clip = clipForImage(imageId)
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching {
                results.keep(
                    bmp, imageId, workflow, types, seed, modelLabel, prompt,
                    batchId, batchLabel, video = clip, favourite = favourite, auto = auto,
                )
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                r.fold(
                    onSuccess = {
                        say(if (favourite) "favourited, with the flow that made it"
                            else "kept it, with the flow that made it")
                    },
                    onFailure = { say("could not keep it — ${it.message}", bad = true) },
                )
                refreshResults()
            }
        }
    }

    /** ⭐ Put a kept result's graph back on the canvas. */
    fun openResultFlow(id: String) {
        val loaded0 = results.flow(id)
        if (loaded0 == null) {
            say("that result's flow could not be read", bad = true)
            return
        }
        // ⭐⭐ A NAME, not "that picture's flow".
        //
        // ⚠⚠ The old label was the same six words for every result, so the
        // confirm that asks before replacing unsaved work could not say WHICH
        // flow was about to land — and after opening, the run bar read "unsaved
        // flow" whatever you had opened. Reported from the phone, 2026-09-10.
        //
        // ⚠ Derived from the flow's own name when it had one, and from the
        // result's id otherwise, so two results never present as the same
        // thing. It is NOT set as `currentWorkflowName` — this graph came from
        // a picture, not from a saved file, and Save must not offer to
        // overwrite a workflow that was never opened.
        val loaded = loaded0 ?: return
        val name = resultFlowName(id)
        guardedOpen(name) {
            // ⚠⚠ The NODES in view, not the viewport the result was kept at.
            // That view came from wherever the canvas had been panned when the
            // picture was made, so the flow opened onto blank grid and had to be
            // found by hand (the user, 2026-09-17). ⇒ Move the layout to where a
            // recipe starts and fit it the way a recipe is fitted.
            openWorkflow(
                loaded.workflow.copy(
                    positions = com.abrah.nightmare.canvas.layoutAtRecipeOrigin(loaded.workflow.positions),
                ),
                null,
            )
            closeLibrary()
            openedResultName = name
            say("opened \"" + name + "\"")
            sayNotes(loaded)
        }
    }

    /**
     * ⭐ The name of the result flow on the canvas, or null.
     *
     * ⚠ Shown in the run bar in place of "unsaved flow" so a graph opened from
     * Results says where it came from. ⚠ Cleared by anything that replaces the
     * canvas with something else.
     */
    var openedResultName by mutableStateOf<String?>(null)
        private set

    /**
     * ⚠ Built from the workflow name recorded WITH the result when there is
     * one, and its short id otherwise. `r1789…` is not a name, but it is
     * unique, and a unique ugly name beats six identical pretty ones.
     */
    private fun resultFlowName(id: String): String {
        val r = kept.firstOrNull { it.id == id }
        val stem = r?.prompt?.take(24)?.trim()?.ifBlank { null }
        val suffix = id.removePrefix("r").takeLast(4)
        return if (stem != null) "$stem · $suffix" else "result $suffix"
    }

    /**
     * ⭐⭐ Results the user has long-pressed into a selection, by id.
     *
     * ⚠ A SET rather than a flag on each row: the rows come from disk on every
     * refresh, so a flag would be lost the moment anything rescanned.
     */
    var selectedResults by mutableStateOf<Set<String>>(emptySet())
        private set

    /** ⚠ Long press ENTERS selection; tapping in it toggles. */
    fun toggleResultSelected(id: String) {
        selectedResults =
            if (id in selectedResults) selectedResults - id else selectedResults + id
    }

    /**
     * ⚠⚠ A TOGGLE, not "select all". With no other control there was no way to
     * leave selection mode at all — the bar hides when the set is empty, and
     * nothing could empty it. Reported from the phone, 2026-09-10.
     */
    fun toggleSelectAllResults() {
        val everything = kept.map { it.id }.toSet()
        selectedResults = if (selectedResults.containsAll(everything)) emptySet() else everything
    }

    fun clearResultSelection() { selectedResults = emptySet() }

    /**
     * ⚠⚠ Deletes every selected result. The CONFIRM lives in the UI, not here —
     * this is the irreversible half and it must not be reachable without one.
     */
    /**
     * ⚠⚠ A whole sweep, as one action. Its items were never individually
     * selectable, so this is the only way they go — and the confirm that
     * reaches it names the count.
     */
    fun deleteResultGroup(g: com.abrah.nightmare.canvas.ResultGroup) {
        g.items.forEach { results.delete(it.id) }
        // ⚠ The selection may have held the group's cover; drop it or the bar
        // would keep counting a picture that no longer exists.
        selectedResults = selectedResults - g.items.map { it.id }.toSet()
        say("forgot a batch of " + g.size)
        refreshResults()
    }

    /**
     * ⚠⚠⚠ **It expands NOTHING, and that is a bug fix.**
     *
     * It used to grow a selection containing a batch's COVER into the whole
     * sweep, because Results drew a batch as ONE card with one checkbox and
     * acting on less than the card would have been a lie.
     *
     * ⚠⚠ That layout is gone. Results is a big preview over a flat grid
     * (`docs/UI.md` §8.11) and `BatchCard` is no longer called by anything — so
     * nothing on screen says which pictures share a sweep, and deleting one
     * quietly took the other seven. Reported by a user 2026-09-22, and the
     * reason it was hard to credit is that the justification still existed in
     * the code that no longer ran.
     *
     * ⇒ A selection means exactly the pictures in it. ⚠ Kept as a function
     * rather than inlined away: if grouping ever returns to the list, this is
     * the one place that decides, and the note above is the argument it has to
     * beat.
     */
    fun expandSelection(ids: Set<String>): Set<String> = ids

    fun saveSelectedResults() = saveResultsToGallery(expandSelection(selectedResults))

    fun deleteSelectedResults() {
        val ids = expandSelection(selectedResults)
        ids.forEach { results.delete(it) }
        selectedResults = emptySet()
        say("forgot " + ids.size + " picture" + (if (ids.size == 1) "" else "s"))
        refreshResults()
    }

    /**
     * ⭐⭐ Write kept pictures into the phone's gallery.
     *
     * ⚠ Reads the stored PNG rather than the thumbnail: this is the copy
     * somebody keeps, and a 64dp version of it would be a quiet disappointment
     * discovered later.
     *
     * ⚠ ONE toast for the whole set, naming the count — five toasts in a row
     * for five pictures is a notification queue, not feedback.
     */
    /**
     * ⭐ Share a kept picture to another app.
     *
     * ⚠ The stored PNG's bytes, like the gallery save — this is the copy the
     * user is looking at, and re-encoding it would hand out something subtly
     * different from what they chose to send.
     */
    fun shareResultImage(id: String) {
        // ⭐⭐ A kept CLIP shares as the MP4. ⚠ Unlike the graph's copy this
        // one lives in `files/results/` and does not evaporate — keeping it
        // there is the whole reason `ResultsStore` copies it ([Result.videoPath]).
        keptClip(id)?.let { clip ->
            runCatching { Share.video(getApplication(), clip, "nightmare-" + id) }
                .onFailure { toast("Could not share: " + it.message) }
            return
        }
        val png = results.fullBytes(id)
        if (png == null) { toast("That picture is missing"); return }
        runCatching { Share.image(getApplication(), png, "nightmare-" + id) }
            .onFailure { toast("Could not share: " + it.message) }
    }

    /** ⚠ See [clipForImage] for why the FILE decides, not a stored flag. */
    private fun keptClip(id: String): java.io.File? = results.clipFile(id)

    /**
     * ⭐⭐ Share the FLOW that made a result, as the same JSON a saved workflow
     * uses.
     *
     * ⚠ One format for saving, importing and sharing — `WorkflowIo` — so a flow
     * someone sends is one the Flows tab can already import. A second
     * "share format" would be a second thing to keep in step.
     */
    fun shareResultFlow(id: String) {
        val loaded = results.flow(id)
        if (loaded == null) { toast("That flow could not be read"); return }
        runCatching {
            Share.workflow(
                getApplication(),
                loaded.workflow.toJson(nodeTypes, loaded.view),
                resultFlowName(id),
            )
        }.onFailure { toast("Could not share: " + it.message) }
    }

    /** ⭐ Share a SAVED workflow from the Flows tab. */
    fun shareSavedWorkflow(name: String) {
        val loaded = runCatching { store.load(name) }.getOrNull()
        if (loaded == null) { toast("\"" + name + "\" could not be read"); return }
        runCatching {
            Share.workflow(
                getApplication(),
                loaded.workflow.toJson(nodeTypes, loaded.view),
                name,
            )
        }.onFailure { toast("Could not share: " + it.message) }
    }

    /**
     * ⭐ Share a picture a NODE produced — the decode, an upscale, any output.
     *
     * ⚠ From the live image store rather than from Results: this picture may
     * never have been kept, and "share what is on the node" must not require
     * keeping it first.
     */
    fun shareNodeImage(imageId: String) {
        val name = "nightmare-" + imageId.take(12)
        // ⭐⭐ The clip, when this node made one — see [clipForImage].
        clipForImage(imageId)?.let { clip ->
            runCatching { Share.video(getApplication(), clip, name) }
                .onFailure { toast("Could not share: " + it.message) }
            return
        }
        // ⚠⚠ The bitmap, for the reason [saveImage] gives.
        val bmp = ops.images.get(imageId)
        if (bmp == null) {
            toast("That picture is no longer in memory — Run again")
            return
        }
        runCatching { Share.image(getApplication(), bmp, name) }
            .onFailure { toast("Could not share: " + it.message) }
    }

    fun saveResultsToGallery(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var ok = 0
            // ⭐ Counted apart so the toast can say what actually landed. A
            // selection can hold both, and "Saved 5" when three of them were
            // clips does not tell the user which folder to look in.
            var clips = 0
            var lastError: String? = null
            for (id in ids) {
                // ⭐⭐ Each item saves in its own kind — see [saveImage].
                val clip = keptClip(id)
                // ⚠⚠ The FILE, streamed — not `fullBytes`, which pulls ~40 MB
                // into the heap for a 4096² upscale to copy bytes it never
                // looks at. Same defect as `ImageStore.png()`, same fix.
                val file = if (clip == null) results.pngFile(id) ?: continue else null
                runCatching {
                    if (clip != null) {
                        com.abrah.nightmare.npu.VideoWriter
                            .publish(ctx, clip, "nightmare-" + id + ".mp4")
                    } else {
                        ImageSaver.savePngFile(ctx, file!!, "nightmare-" + id)
                    }
                }.fold(
                    onSuccess = { ok++; if (clip != null) clips++ },
                    onFailure = { lastError = it.message },
                )
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (ok > 0) {
                    val what = when {
                        clips == 0 -> "" + ok
                        clips == ok -> "" + ok + (if (ok == 1) " clip" else " clips")
                        else -> "" + (ok - clips) + " + " + clips +
                            (if (clips == 1) " clip" else " clips")
                    }
                    toast("Saved " + what + " to the gallery")
                    say("saved " + what + " to the gallery")
                } else {
                    toast("Could not save: " + (lastError ?: "nothing to save"))
                    say("could not save — " + lastError, bad = true)
                }
            }
        }
    }

    /** ⭐ The kept picture being looked at full screen, or null. */
    var viewingResult by mutableStateOf<com.abrah.nightmare.canvas.Result?>(null)
        private set

    var viewingResultImage by mutableStateOf<ImageBitmap?>(null)
        private set

    var viewingResultDetails by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    /**
     * ⭐⭐ The SET the viewer swipes through, and where in it to start.
     *
     * ⚠ The batch when the picture belongs to one, the whole tab otherwise —
     * so swiping compares what a user would think of as "these", not an
     * arbitrary neighbourhood.
     */
    var viewingSet by mutableStateOf<List<com.abrah.nightmare.canvas.Result>>(emptyList())
        private set

    var viewingIndex by mutableStateOf(0)
        private set

    /**
     * ⭐ The full-size picture for a result, decoded on demand and cached.
     *
     * ⚠⚠ Cached like [thumbnailFor], and for a sharper reason: the viewer now
     * SWIPES, so it asks for a neighbour's bitmap on every drag. Decoding a
     * 1024² PNG per frame of a swipe is the kind of thing that reads as "the
     * app is slow" rather than as a missing cache.
     */
    private val fullImages = mutableStateMapOf<String, ImageBitmap>()
    /** ⚠ Recency order for [fullImages] — oldest first. See [resultImage]. */
    private val fullImageOrder = ArrayDeque<String>()
    /** ⚠ Same purpose as [thumbLoading] — one decode in flight per id. */
    private val fullImageLoading = mutableSetOf<String>()

    /**
     * ⚠⚠ Async, off the main thread — same reason and same shape as
     * [thumbnailFor]. This is called inline from the pager's composition on
     * every swipe, and a 2048px decode (`ResultsStore.full`) is still real
     * work; doing it on the thread that owns the frame budget is what made
     * paging through kept results feel slow even after the cache stopped
     * thrashing. Reported 2026-09-18.
     */
    fun resultImage(id: String): ImageBitmap? {
        fullImages[id]?.let {
            // ⚠ Touch: this id is the most recently used now, not whenever it
            // first decoded — otherwise swiping back and forth across exactly
            // [FULL_IMAGE_CACHE] pictures evicts the one still on screen.
            fullImageOrder.remove(id)
            fullImageOrder.addLast(id)
            return it
        }
        if (fullImageLoading.add(id)) {
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val bmp = results.full(id)?.asImageBitmap()
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    fullImageLoading.remove(id)
                    if (bmp != null) {
                        // ⚠⚠ Evict the SINGLE oldest entry, not the whole map.
                        // `clear()` here used to wipe every cached bitmap the
                        // moment a 13th distinct result was viewed, so paging
                        // through more than a dozen kept pictures (or a batch)
                        // re-decoded on almost every swipe.
                        fullImageOrder.addLast(id)
                        while (fullImages.size >= FULL_IMAGE_CACHE) {
                            val oldest = fullImageOrder.removeFirstOrNull() ?: break
                            fullImages.remove(oldest)
                        }
                        fullImages[id] = bmp
                    } else markUnreadable(id, "full-size")
                }
            }
        }
        return null
    }

    /** ⚠ Read per picture as it is swiped to, from that result's stored flow. */
    /**
     * ⭐⭐ What made a kept picture: EVERY param of the LAST sampler in the flow
     * — the one whose render this is, in a chain — plus the prompt wired into
     * it (the user's call, 2026-09-17).
     *
     * ⚠⚠ It read `sd.clip_encode` / `nd.clip_encode` for the prompt, types gone
     * since §5.7, so a current flow showed no prompt at all and a rolled seed
     * as nothing. ⚠ Labels are [knobLabel], the inspector's own words.
     */
    /**
     * ⭐⭐⭐ **What a kept result can be FILTERED by** — the models any
     * sampler in its flow used, and the text worth searching.
     *
     * Asked for 2026-09-22: a filter beside Favourites listing every model, and
     * a search box.
     *
     * ⚠⚠ **Cached per result id.** It opens the stored flow from disk, and
     * the Results list asks for every visible row on every recomposition — the
     * same shape as the `refresh*()` reads that froze the app during a download
     * (`docs/UI.md` §8.4). A flow is immutable once kept, so the cache never
     * needs invalidating.
     *
     * ⚠ The model's LABEL, not its id: it is what the checkbox will say, and
     * two ids can share a label only if the catalogue does.
     */
    fun resultTags(r: com.abrah.nightmare.canvas.Result): ResultTags =
        resultTagCache.getOrPut(r.id) {
            val g = results.flow(r.id)?.workflow?.graph
                ?: return@getOrPut ResultTags(emptySet(), r.batchLabel)
            val models = g.nodes.mapNotNull { n ->
                n.params["model"]?.takeIf { it.isNotBlank() && isSampler(n.type) }
            }.map { ModelCatalog.byId(it)?.label ?: it }.toSet()
            // ⚠ Prompt AND negative AND the batch label: a person searching
            // "cat" means the picture, and they may have typed it into either.
            val text = buildString {
                for (n in g.nodes) {
                    n.params["prompt"]?.let { append(it).append(' ') }
                    n.params["negative"]?.let { append(it).append(' ') }
                }
                append(r.batchLabel)
            }
            ResultTags(models, text)
        }

    private val resultTagCache = mutableMapOf<String, ResultTags>()

    fun detailsOf(r: com.abrah.nightmare.canvas.Result): List<Pair<String, String>> {
        val g = results.flow(r.id)?.workflow?.graph ?: return emptyList()
        return detailsOfGraph(g, null, r.width, r.height) { r.seed }
    }

    /**
     * ⭐⭐⭐ **The body of [detailsOf], over ANY graph** — a stored flow or the
     * live canvas.
     *
     * ⚠⚠ Extracted 2026-09-22 when the output viewer gained the same ⓘ.
     * Two surfaces that must agree call the SAME function (`CLAUDE.md`); a
     * second copy of "which sampler, which prompt, which rolled seed" is a
     * second copy that stops agreeing — and this one already got that wrong
     * once, reading node types that had been gone since §5.7.
     *
     * @param upTo when non-null, describe the chain feeding THIS node rather
     *   than the whole graph — a canvas may hold two branches.
     */
    private fun detailsOfGraph(
        g: Graph,
        upTo: String?,
        width: Int = 0,
        height: Int = 0,
        /**
         * ⚠ Milliseconds for a node id, or for [TOTAL_MS_KEY] — null when this
         * graph has no timings at all (a stored result: the run that made it is
         * over and its clock was never kept with it).
         */
        times: ((String) -> Long?)? = null,
        rolled: (String) -> String?,
    ): List<Pair<String, String>> {
        val types = typesFor(g)
        val order = (topoSort(g) as? Order.Ok)?.nodes ?: g.nodes
        // ⚠ Only what actually feeds [upTo] — the last sampler on the canvas
        // may belong to a branch this output has nothing to do with.
        val reach: Set<String>? = if (upTo == null) null else {
            val acc = mutableSetOf<String>()
            val pending = ArrayDeque(listOf(upTo))
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (!acc.add(id)) continue
                g.byId[id]?.inputs?.values?.forEach { pending.addLast(it.node) }
            }
            acc
        }
        val samplers = order.filter { isSampler(it.type) && (reach == null || it.id in reach) }
        val out = mutableListOf<Pair<String, String>>()
        val s = samplers.lastOrNull()
        if (s == null) {
            if (width > 0) out += "Size" to "${width}x$height"
            out += "Nodes" to g.nodes.size.toString()
            return out
        }
        val type = types[s.type]
        val p = applyDefaults(type?.widgets.orEmpty(), s)
        out += "Node" to (type?.titleFor(s) ?: s.type)
        s.inputs["prompt"]?.node?.let { g.byId[it] }?.params?.let { pp ->
            pp["prompt"]?.takeIf { it.isNotBlank() }?.let { out += "Prompt" to it }
            pp["negative"]?.takeIf { it.isNotBlank() }?.let { out += "Negative" to it }
        }
        val skip = setOf("x", "y", "w", "h", CropNode.LOCKED)
        for (w in type?.widgets.orEmpty()) {
            if (w.name in skip) continue
            val v = p[w.name] ?: continue
            when (w.name) {
                "model" -> out += "Model" to (ModelCatalog.byId(v)?.label ?: v)
                // ⚠ The ROLLED seed: the param is 0 ("new every Run") on most flows.
                "seed" -> out += "Seed" to (if (v == "0") rolled(s.id) ?: v else v)
                MaskNode.OPS -> {
                    val m = MaskState.decode(v)
                    if (!m.isEmpty) {
                        val taps = m.ops.count { it is MaskOp.Tap }
                        out += "Mask" to "${m.ops.size - taps} strokes, $taps tapped"
                    }
                }
                else -> if (v.isNotBlank()) out += w.name.knobLabel to v
            }
        }
        if (width > 0) out += "Output" to "${width}x$height"
        // ⭐⭐ What it COST — asked for 2026-09-22. ⚠ Per SAMPLER as well as
        // the whole flow, because on a chain the total says nothing about which
        // step was expensive. ⚠⚠ A sampler that came back from the cache has
        // no entry rather than a 0 — see [nodeMs]; it is named as cached, which
        // is the useful fact.
        if (times != null) {
            for (sampler in samplers) {
                val ms = times(sampler.id)
                out += ("Time · " + sampler.id) to
                    (ms?.let { com.abrah.nightmare.canvas.formatMs(it) } ?: "cached")
            }
            times(TOTAL_MS_KEY)?.let {
                out += "Time · whole flow" to com.abrah.nightmare.canvas.formatMs(it)
            }
        }
        if (samplers.size > 1) out += "Samplers in the chain" to samplers.size.toString()
        return out
    }

    /** ⭐ Star the selection, or un-star it when every one already is. */
    fun starSelectedResults() {
        val ids = expandSelection(selectedResults)
        val sel = kept.filter { it.id in ids }
        if (sel.isEmpty()) return
        val on = !sel.all { it.favourite }
        sel.filter { it.favourite != on }.forEach { toggleResultFavourite(it) }
    }

    /**
     * ⭐ Share a History selection (or one) as its pictures/clips, or as the
     * flows that made them — the popup's two choices (2026-09-17).
     */
    fun shareResults(ids: Collection<String>, asFlow: Boolean) {
        val ctx = getApplication<Application>()
        val list = expandSelection(ids.toSet()).toList()
        runCatching {
            if (asFlow) {
                com.abrah.nightmare.Share.many(ctx, list.mapNotNull { id ->
                    val loaded = results.flow(id) ?: return@mapNotNull null
                    val json = loaded.workflow.toJson(nodeTypes, loaded.view)
                    (resultFlowName(id) + ".json") to { f: java.io.File -> f.writeText(json) }
                }, "application/json", "Share flows")
            } else {
                val entries = list.mapNotNull { id ->
                    keptClip(id)?.let { clip ->
                        return@mapNotNull ("nightmare-$id.mp4") to { f: java.io.File -> clip.copyTo(f, overwrite = true); Unit }
                    }
                    val png = results.imageFile(id).takeIf { it.isFile } ?: return@mapNotNull null
                    ("nightmare-$id.png") to { f: java.io.File -> png.copyTo(f, overwrite = true); Unit }
                }
                val mime = when {
                    entries.all { it.first.endsWith(".png") } -> "image/png"
                    entries.all { it.first.endsWith(".mp4") } -> "video/mp4"
                    else -> "*/*"
                }
                com.abrah.nightmare.Share.many(ctx, entries, mime, "Share")
            }
        }.onFailure { toast("Could not share: " + it.message) }
    }

    fun viewResult(r: com.abrah.nightmare.canvas.Result) {
        viewingResult = r
        viewingResultImage = null
        viewingResultDetails = emptyList()
        // ⚠ The batch, in its own order, or every kept picture.
        val set = if (r.batchId != null) {
            keptGroups.firstOrNull { it.batchId == r.batchId }?.items ?: listOf(r)
        } else {
            kept
        }
        viewingSet = set
        viewingIndex = set.indexOfFirst { it.id == r.id }.coerceAtLeast(0)
        // ⚠⚠ Off the main thread: `full()` decodes a PNG and `details()` reads
        // and parses the stored workflow JSON, both file I/O, both used to run
        // synchronously on the tap that opens this viewer.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bmp = results.full(r.id)?.asImageBitmap()
            val details = results.details(r.id)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                // ⚠ Only if still looking at the same result — a fast tap
                // through several pictures must not let a slow earlier decode
                // land on top of the one the user is now looking at.
                if (viewingResult?.id == r.id) {
                    viewingResultImage = bmp
                    viewingResultDetails = details
                }
            }
        }
    }

    fun closeResult() {
        viewingResult = null
        viewingResultImage = null
        viewingResultDetails = emptyList()
    }

    fun deleteResult(id: String) {
        if (viewingResult?.id == id) closeResult()
        results.delete(id)
        thumbs.remove(id)
        refreshResults()
        say("forgot that one")
    }

    /**
     * node id -> the signature of the free sub-graph that produced its preview.
     *
     * ⚠⚠ What makes [refreshPreviews] idempotent. Without it the only test
     * available is "does this node already have a picture", and that answer is
     * wrong in both directions: a `load_image` pointed at a new photo keeps the
     * old one forever, and a node with no picture is re-attempted on every
     * keystroke. ⚠ Recorded BEFORE the run, not after, so a graph that fails --
     * a blank `uri`, a plugin that throws -- is not retried until something
     * about it changes. The alternative is a failing sub-graph re-run every time
     * the user touches anything, each one logging.
     */
    private val previewSigs = mutableMapOf<String, String>()

    /** The pending preview resolve. ⚠ At most one: see [refreshPreviews]. */
    private var previewJob: kotlinx.coroutines.Job? = null

    /**
     * ⭐⭐ Every picture the graph can make WITHOUT the NPU, made now.
     *
     * ⚠⚠ This is what "the canvas shows you what you are doing" costs, and it
     * replaces a narrower thing that resolved only an interactive node's input
     * when its sheet opened. Two reports from the phone, 2026-09-09, were the
     * same missing half: a photo chosen on `load_image` showed nothing until
     * Run, and the cropper's own node kept its pre-crop picture while the
     * framing view above it was already correct. Both nodes are app-side and
     * cost milliseconds; there was never a reason to wait for a render.
     *
     * ⚠ DEBOUNCED, and the debounce is load-bearing rather than polite. An
     * interactive widget writes params per pointer event, so an undebounced
     * resolve would decode the source photo on every frame of a crop drag --
     * `load_image` is not cacheable, by design, because the file behind a URI
     * can change. Cancelling and relaunching means the work starts only once the
     * finger stops.
     *
     * ⚠ ONE run for the whole set, not one per node. The cropper and the
     * `load_image` feeding it share an ancestry, and resolving them separately
     * decodes the same photo twice.
     *
     * ⚠ Nothing is ever CLEARED here. A preview may be there from an earlier
     * Run, and taking it away because a re-resolve failed would remove a picture
     * the user is working against.
     */
    fun refreshPreviews() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            kotlinx.coroutines.delay(PREVIEW_DEBOUNCE_MS)
            try {
                resolvePreviews(previewTargets(canvas.workflow.graph, typesFor(canvas.workflow.graph)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                // ⚠⚠ Swallowed to the LOG, never to the process. Nothing here
                // was asked for out loud -- the user picked a photo, they did
                // not press Run -- so a plugin that throws, or a `nodeTypes`
                // that cannot build its JS engine, must not take the app down
                // on a background thread with the canvas still on screen. This
                // project has already lost the process twice that way
                // (`say`'s log trim, the autosave race), both reached through
                // the cropper. ⚠ Throwable, not Exception: UnsatisfiedLinkError
                // from a missing native library is an Error.
                say("preview failed — ${e.javaClass.simpleName}: ${e.message}", bad = true)
            }
        }
    }

    /**
     * Run the free ancestry of [ids] once and attach whatever images come out.
     *
     * ⚠ A node whose signature has not moved is skipped, so this is cheap to
     * call often; a node that needs the backend is skipped by [freeAncestry].
     */
    /**
     * ⭐⭐⭐ What a SAMPLER shows on the canvas: **the framed picture going IN**.
     *
     * Asked for 2026-09-15 — *"in i2i, show the cropped area as preview for the
     * sample node."* The sampler stopped drawing its render earlier the same
     * day ([NodeType.showsResult]), which left it blank; but a sampler with a
     * photo wired is holding a framing decision, and that IS worth seeing
     * without opening the node.
     *
     * ⚠⚠ The INPUT, never the result — the render still belongs to
     * `core.output` alone, so nothing here changes what a Run displays.
     *
     * ⚠ Free: [CropNode.render] is the same app-side draw the framing view
     * does, on a picture already in the store because the node upstream
     * previewed it.
     */
    /**
     * ⭐⭐⭐ What a SAMPLER shows on the canvas: **the framed picture going IN**,
     * with the mask over it on an inpaint node.
     *
     * Asked for 2026-09-15 — *"show the cropped area as preview for the sample
     * node… it should show instantly the moment I add an image"*, and for
     * inpaint *"node should show mask overlaid onto cropped img, same as the
     * editor view"*. The sampler stopped drawing its RENDER
     * ([NodeType.showsResult]), which left it blank; but a sampler holding a
     * framing and a painting is holding two decisions worth seeing without
     * opening it.
     *
     * ⚠⚠ The INPUT, never the result — a render still appears on `core.output`
     * alone, so nothing here changes what a Run displays.
     *
     * ⚠ Free: the same app-side draws the two editors do, on a picture already
     * in the store because the node upstream previewed it.
     */
    private fun applyFramedPreviews() {
        // ⭐ First: a sampler waiting on a new photo is fitted to it, so the
        // preview below is drawn from the fitted framing, not the old one.
        applyPendingAutoFrames()
        val graph = canvas.workflow.graph
        val shown = graph.nodes
            .filter { it.type in com.abrah.nightmare.FRAMING_TYPES }
            .mapNotNull { n ->
                val srcId = canvas.pictureInto(n.id, nodeTypes) ?: return@mapNotNull null
                val src = ops.images.get(srcId) ?: return@mapNotNull null
                val p = com.abrah.nightmare.applyDefaults(nodeTypes[n.type]?.widgets.orEmpty(), n)
                fun f(k: String, d: Float) = p[k]?.toFloatOrNull() ?: d
                runCatching {
                    // ⚠⚠ `framingOutSize` — the SAME function the crop editor,
                    // the mask editor and the sampler's own cut ask. This read
                    // width/height directly, so on SDXL with a 2:3 aspect the
                    // node (and its fullscreen view) showed a 1:1 framing while
                    // every editor showed 2:3 (reported 2026-09-17). The video
                    // sampler's encoder size comes through `framesTo` as well.
                    val (outW, outH) = com.abrah.nightmare.canvas.framingOutSize(n, nodeTypes[n.type])
                    var bmp = com.abrah.nightmare.CropNode.render(
                        src, f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f),
                        outW, outH, p[com.abrah.nightmare.CropNode.PAD] ?: com.abrah.nightmare.CropNode.PAD_BLACK,
                    ).first
                    // ⭐ …and the painting on top, so the node shows what the
                    // Mask editor shows. ⚠ Translucent: the picture underneath
                    // is what makes the mask legible as a REGION of it.
                    val ops0 = p[com.abrah.nightmare.MaskNode.OPS].orEmpty()
                    if (n.type in com.abrah.nightmare.INPAINT_TYPES && ops0.isNotBlank()) {
                        // ⚠ Taps from the CACHE only: this runs on the main
                        // thread, and a tap made in this session is already there.
                        val state = com.abrah.nightmare.MaskTaps.resolve(
                            com.abrah.nightmare.MaskState.decode(ops0).copy(
                                growFrac = f("grow", 0f), featherFrac = f("feather", 0.02f),
                            ),
                        ) { x, y -> com.abrah.nightmare.segment.Segmenter.cached(src, x, y)?.candidates }
                        // ⚠⚠ `bmp` is the FRAMED picture and the mask is stored
                        // against the PHOTO, so it is converted here exactly as
                        // the editor converts it ([MaskFraming]). Rasterising
                        // the stored ops straight into a framed bitmap draws
                        // the painting the crop's own offset away from where it
                        // will be repainted — the 2026-09-16 bug, as a preview.
                        // ⚠⚠ The editor's RED overlay, not the raw white mask —
                        // the node must look like the editor (the user, 2026-09-17).
                        // One function draws both ([MaskRaster.overlay]).
                        val mask = com.abrah.nightmare.MaskRaster.overlay(
                            com.abrah.nightmare.MaskFraming.toFrame(
                                state, f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f),
                            ),
                            bmp.width, bmp.height, com.abrah.nightmare.MaskRaster.OVERLAY_RGB,
                        )
                        val out = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                        android.graphics.Canvas(out).drawBitmap(
                            mask, 0f, 0f,
                            android.graphics.Paint().apply { alpha = com.abrah.nightmare.MaskRaster.OVERLAY_ALPHA },
                        )
                        bmp = out
                    }
                    // ⭐ …and the OUTPAINT padding in blue, as the editors show it.
                    if (com.abrah.nightmare.padRuleFor(n.type) == com.abrah.nightmare.PadRule.OUTPAINT) {
                        com.abrah.nightmare.CropGeometry.photoInFrame(f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f))
                            ?.let { pad ->
                                if (!bmp.isMutable) bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                                com.abrah.nightmare.MaskRaster.paintPadding(bmp, pad)
                            }
                    }
                    n.id to (ops.images.put(bmp) to bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                }.getOrNull()
            }
        // ⚠⚠ …and a framing node with NOTHING coming in shows nothing. This only
        // ever added, so an inpaint rewired to an un-run generate node kept the
        // photo it had framed before (reported 2026-09-17).
        val empty = graph.nodes
            .filter { it.type in com.abrah.nightmare.FRAMING_TYPES && canvas.pictureInto(it.id, nodeTypes) == null }
            .map { it.id }
            .toSet()
        if (shown.isNotEmpty() || empty.any { it in canvas.previews }) {
            canvas = canvas.copy(previews = canvas.previews.filterKeys { it !in empty } + shown)
        }
        applyBeforeAfterPreviews(graph)
    }

    /**
     * ⭐⭐ [CanvasState.beforePreviews] — what a before/after node (today only
     * `image.upscale`) RECEIVED, so the canvas can show it alongside what the
     * node MADE. Reported 2026-09-18: a generate → upscale chain showed
     * nothing of the intermediate picture at all, because a renderer never
     * draws its own result ([NodeType.showsResult]) and nothing else showed
     * what fed the next node either.
     *
     * ⚠ Same shape as the `shown`/`empty` pair just above, and called from the
     * same places for the same reason: [CanvasState.pictureInto] already
     * resolves the right picture (`rendered[up]` for an upstream renderer),
     * this only needs to run whenever [applyFramedPreviews] does.
     */
    private fun applyBeforeAfterPreviews(graph: Graph) {
        // ⭐⭐ …and `core.output` when its auto-upscale is on, since 2026-09-22:
        // it then MAKES a different picture from the one it was handed, which
        // is exactly the condition a before/after exists for.
        // ⚠ The retired `image.upscale` keeps its own, for flows that have one.
        val nodes = graph.nodes.filter {
            it.type == UpscaleNode.name || MediaOutputNode.autoUpscales(it)
        }
        // ⚠⚠⚠ **The node's OWN input port, not the string "image".**
        //
        // [CanvasState.pictureInto] defaults to `image` and `core.output`'s port
        // is `media` — the union type that takes a picture or a clip — so this
        // asked for a wire that does not exist and every auto-upscaling output
        // recorded NO before picture at all. Reported 2026-09-22, after being
        // claimed as working: the before/after simply never appeared.
        //
        // ⚠⚠ **Second time the same two port names have done this.**
        // `insertUpscale` read `inputs["image"]` off an output node for a whole
        // release. ⇒ Ask the TYPE what its port is called; a port name written
        // out at a call site is a port name that will be wrong somewhere.
        fun portOf(n: com.abrah.nightmare.Node) =
            nodeTypes[n.type]?.inputs?.firstOrNull()?.name ?: "image"
        val shown = nodes.mapNotNull { n ->
            val id = canvas.pictureInto(n.id, nodeTypes, portOf(n)) ?: return@mapNotNull null
            val bmp = ops.images.get(id) ?: return@mapNotNull null
            n.id to (id to bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
        }.toMap()
        // ⚠⚠⚠ **Every node that should NOT have one is cleared, not just the
        // before/after nodes whose wire is empty.**
        //
        // This read `nodes.filter { … == null }`, so a node that STOPPED being a
        // before/after node was in neither set and kept its old entry forever.
        // Reported from the phone 2026-09-22: untick `auto upscale`, press Run,
        // and the Received frame still showed the picture from two runs ago
        // while Made updated underneath it. ⇒ The set to clear is "everything
        // that is not currently a before/after node with a picture", which is
        // the whole graph minus [shown].
        val drop = staleBeforePreviews(
            held = canvas.beforePreviews.keys,
            beforeAfter = nodes.map { it.id }.toSet(),
            empty = nodes.filter { canvas.pictureInto(it.id, nodeTypes, portOf(it)) == null }
                .map { it.id }.toSet(),
        )
        if (shown.isNotEmpty() || drop.isNotEmpty()) {
            canvas = canvas.copy(
                beforePreviews = canvas.beforePreviews.filterKeys { it !in drop } + shown,
            )
        }
    }

    private suspend fun resolvePreviews(ids: List<String>) {
        val workflow = canvas.workflow
        val graph = workflow.graph
        // ⚠ A LinkedHashMap keyed by id: two targets share ancestors, and the
        // same node twice in one graph is a duplicate-id graph the executor
        // refuses by name.
        val need = LinkedHashMap<String, Node>()
        val wanted = LinkedHashMap<String, String>()
        for (id in ids) {
            val sub = freeAncestry(graph, id, typesFor(graph)) ?: continue
            val sig = previewSig(sub)
            // ⚠⚠ The signature alone, NOT "and it already has a picture". A
            // node that FAILED has no picture by definition, so the second half
            // made every failing node -- a `load_image` with no photo chosen,
            // which is what a freshly added one is -- re-run its whole ancestry
            // on every keystroke, forever. Seen in the preview log doing exactly
            // that. The signature covers everything the result depends on, so a
            // re-run would fail identically; a Run still reads the file afresh.
            if (previewSigs[id] == sig) continue
            wanted[id] = sig
            sub.forEach { need[it.id] = it }
        }
        // ⚠⚠ The sampler's framed preview is computed from previews that
        // ALREADY exist, so it must be refreshed even when this pass has
        // nothing of its own to resolve. Returning first is why "show the
        // cropped image on the sample node" did nothing: the pass that resolved
        // the photo returned before reaching it, and every later pass had an
        // empty `wanted` and returned here.
        if (wanted.isEmpty()) {
            applyFramedPreviews()
            return
        }

        val r = ops.runWorkflow(workflow.copy(graph = Graph(need.values.toList())))
        // ⭐⭐ ONE line per resolve, and it has earned its place permanently: it
        // is what found a bug four builds deep that three rounds of reading the
        // code had each diagnosed wrongly. Nothing here is asked for out loud,
        // so a preview that quietly does the wrong thing has no other witness.
        // ⚠ Debounced upstream, so a crop drag costs a handful of these, not one
        // per pointer event.
        android.util.Log.i(
            PREVIEW_TAG,
            "resolve ${wanted.keys} -> " + r.runs.joinToString { "${it.id}:${it.outcome}" } +
                (r.error?.let { " — $it" } ?: ""),
        )
        // ⚠⚠ The signature is recorded HERE, after the run RETURNED -- never
        // before it. Marking first meant a job cancelled mid-run (which every
        // edit does, by design) left the signature of a picture that was never
        // applied, and every later refresh then agreed the node was up to date.
        // The node kept the previous image until something else changed. ⚠ A
        // node that FAILED inside the run is still marked, so a blank `uri` or a
        // throwing plugin is not re-attempted on every keystroke; only
        // cancellation skips this line, because only cancellation means the
        // question was never answered.
        previewSigs.putAll(wanted)
        val shown = wanted.keys.mapNotNull { id ->
            (r.outputs[id]?.previewImage())?.let { img ->
                id to (img.id to img.w.toFloat() / img.h.coerceAtLeast(1))
            }
        }
        // ⚠⚠ Read `canvas` again rather than closing over the snapshot above:
        // the user has had PREVIEW_DEBOUNCE_MS plus a graph run to keep editing,
        // and writing back a stale state here would undo whatever they typed.
        if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)
        // ⚠⚠ AFTER the write, never from the snapshot above: on the very pass
        // that resolves a newly chosen photo, that photo is not in
        // `canvas.previews` until the line above runs — so reading it earlier
        // gave the sampler nothing exactly when the user had just added an image.
        applyFramedPreviews()
    }

    /** The pending autosave. ⚠ At most one: see [saveWorkflow]. */
    private var saveJob: kotlinx.coroutines.Job? = null

    /**
     * ⚠ Off the main thread: small, but a file write is a file write.
     *
     * ⚠⚠ **Conflated, and that is a bug fix.** An interactive widget writes a
     * param per pointer event, so a single crop drag queued dozens of concurrent
     * writes of the whole workflow -- all to the same `current.json.tmp`, which
     * they then deleted and renamed out from under each other. The failures
     * arrived together and took down the process through [say].
     *
     * ⇒ One pending save, replaced by the next edit. The delay is what makes a
     * drag cost one write instead of sixty. ⚠ It also means the last ~150 ms of
     * editing is not on disk yet; that is why [onCleared] flushes.
     */
    private fun saveWorkflow() {
        val snapshot = canvas.workflow
        val view = canvas.savedView
        saveJob?.cancel()
        saveJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(150)
            writeWorkflow(snapshot, view)
        }
    }

    private fun writeWorkflow(w: Workflow, view: com.abrah.nightmare.canvas.SavedView? = null) {
        try {
            store.save(CURRENT, w, nodeTypes, view)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            say("could not save the workflow — ${e.message}", bad = true)
        }
    }

    /**
     * ⚠ The conflated save has a window where the newest edit is only in memory.
     * Closing the app inside it would lose the last thing the user did, so the
     * pending write is forced through here rather than cancelled with the scope.
     */
    override fun onCleared() {
        val pending = saveJob
        saveJob = null
        if (pending != null && pending.isActive) {
            pending.cancel()
            writeWorkflow(canvas.workflow)
        }
        super.onCleared()
    }

/**
     * Has the autosave been put on the canvas yet? Set the moment the attempt is
     * made, not when it succeeds -- a file that will not read will not read on
     * the second try either, and retrying would only reprint the error.
     */
    private var restored = false

    /**
     * Restore the last graph, ONCE, when the canvas is first shown.
     *
     * ⚠⚠ **Not routed through [run], and that is the fix.** It used to be, and
     * `run` refuses whenever the `busy` latch is held -- which on a cold start it
     * always is: `HarnessScreen` fires `checkBackend()` from a neighbouring
     * `LaunchedEffect` on the same first composition, and that takes the latch
     * synchronously before suspending on HTTP. So the restore lost every time,
     * the canvas kept `defaultWorkflow()`, and the next gesture AUTOSAVED that
     * over the user's file. The visible symptom is "my workflow is gone", one
     * step removed from the cause. ⚠ Restoring a file onto the canvas is not an
     * op: it touches no backend, so it has no business queueing behind a probe.
     *
     * ⚠ This is the SECOND time these effects raced over that latch. The first
     * dropped a scripted op in silence (`notes/HANDOFF.md` §5) and was fixed for
     * `runOp` alone; this one was sitting beside it the whole time.
     *
     * ⚠ Once, not on every return to the canvas. The effect is keyed on
     * `showCanvas`, so it re-fires coming back from Models or Workflows -- and
     * re-reading the file there can only revert edits the asynchronous autosave
     * has not caught up with yet. The canvas is authoritative by then.
     *
     * ⚠ A file that will not parse is REPORTED and then left alone -- not
     * deleted, and not silently replaced by the default. The user's graph may be
     * recoverable by hand, and a save that overwrote it would remove that chance.
     */
    fun restoreWorkflow() {
        if (restored) return
        restored = true
        viewModelScope.launch {
            val loaded = try {
                withContext(kotlinx.coroutines.Dispatchers.IO) { store.load(CURRENT) }
            } catch (e: Exception) {
                say("saved workflow unreadable — ${e.message}", bad = true)
                return@launch
            } ?: return@launch

            // ⚠⚠ The canvas FIRST, the plugin warning after. It was the other
            // way round, and `nodeTypes` builds the QuickJS runtime -- so
            // anything thrown while working out which packs are MISSING took the
            // user's whole graph with it, on the one path that restores it. A
            // warning must not be able to eat the thing it is warning about.
            canvas = deriveSizes(
                canvas.copy(workflow = loaded.workflow, selection = emptySet(), editing = null)
                    .withView(loaded.view)
            )
            sayNotes(loaded)
            // ⭐ The photo a restored `load_image` points at is on the node
            // before the user touches anything. A cold start used to open on a
            // graph of empty boxes until something was run.
            previewSigs.clear()
            adoptGraphModel(loaded.workflow)
            refreshPreviews()
            say("loaded ${loaded.workflow.graph.nodes.size} nodes" +
                if (loaded.requires.isEmpty()) "" else ", needs ${loaded.requires.size} plugin(s)")

            // ⚠ Only when the graph actually names a pack -- a built-ins-only
            // workflow, which is most of them, never resolves `nodeTypes` and so
            // never pays for the JS engine just to be reopened.
            if (loaded.requires.isNotEmpty()) {
                try {
                    // ⚠ Loaded anyway. The nodes are kept so the graph is still
                    // there to look at and the plugin can be installed; the
                    // executor refuses by name if it is run first.
                    missingRequirements(loaded.requires, nodeTypes)
                        .forEach { say("workflow needs $it", bad = true) }
                } catch (e: Throwable) {
                    say("could not check this workflow's plugins — " +
                        "${e.javaClass.simpleName}: ${e.message}", bad = true)
                }
            }
        }
    }

    /** Per-node outcome and progress from the last run, for the canvas to draw. */
    val canvasStatus = mutableStateMapOf<String, NodeStatus>()

    /**
     * ⚠ Resolved lazily and cached: it builds the plugin host, which creates a
     * QuickJS runtime. The canvas is opened deliberately, so paying for the
     * engine then is fine; paying for it at app start is not.
     */
    val nodeTypes: Map<String, NodeType> by lazy { ops.nodeTypes() }

    /**
     * Run the canvas's workflow.
     *
     * ⭐ Through `HarnessOps.runWorkflow`, the same call the headless
     * `canvas_run` op makes — so what the button does can be checked without
     * taking the screen.
     */
    /**
     * Why the last Run failed, in one line, for the canvas to show.
     *
     * ⚠⚠ A user on the canvas cannot see the harness log, so before this a
     * refused graph -- "backend down", "missing param" -- was indistinguishable
     * from a button that did nothing. Reported from a real phone as exactly
     * that: "run button did nothing".
     */
    var runError by mutableStateOf<String?>(null)
        private set

    /**
     * ⭐⭐ What the canvas shows WHILE it renders — `canvas/RunLog.kt`.
     *
     * ⚠⚠ A Run was silent for its whole duration. The only live signal was a
     * bar a few dp wide inside the node, and on SDXL one `sample` is 24 s: long
     * enough for a person with no feedback to conclude the button did nothing.
     */
    /**
     * ⭐⭐ Per-node milliseconds from the last Run, and [lastRunMs] for the
     * whole graph — what the ⓘ dialog reports.
     *
     * ⚠ Only nodes that RAN are in here, so a cached one is absent rather than
     * present with a zero.
     */
    val nodeMs = mutableStateMapOf<String, Long>()

    // ⚠⚠ The whole-run total is NOT declared here — `lastRunMs` already
    // exists, set from `GraphRun.totalMs` so a sweep can estimate from a
    // measured render. One number, two readers.

    var runLog by mutableStateOf(com.abrah.nightmare.canvas.RunLogState())
        private set

    /**
     * Forget the last run.
     *
     * ⚠⚠ Called wherever the run becomes STALE, not only from the close button.
     * The panel deliberately outlives its run so the total stays readable — but
     * once the graph is pointed at a different checkpoint, or a different
     * workflow is opened, "done 26.5s" describes something that is no longer on
     * screen. Reported from the phone: switching model left the previous run's
     * "done" sitting there as though it belonged to the new one.
     */
    /**
     * ⭐⭐⭐ **One node finished — the ONE place that records it.**
     *
     * ⚠⚠⚠ There are TWO run paths, [runCanvas] and [runBatch], and each
     * had its own copy of this. When per-node timings were added for the ⓘ
     * dialog on 2026-09-22 only the BATCH copy got them, so an ordinary Run —
     * which is every Run without a sweep armed — recorded none and the dialog
     * showed no time at all. Reported the same day: *"i dont see the time taken
     * for run in info, why?"*.
     *
     * ⚠⚠ The mirror image was true of the total: [runCanvas] set it and
     * [runBatch] did not. ⇒ Two paths through one event is one path too many;
     * both call this.
     *
     * ⚠ `now`/`step` are cleared with the node that owned them, or the panel
     * keeps counting the previous node's steps under the next one's name.
     * ⚠ Only a node that actually RAN gets a time: a cached one reports 0 ms,
     * and "0 ms" beside a sampler reads as a broken clock rather than a cache
     * hit.
     */
    private fun noteNodeRun(n: NodeRun) {
        canvasStatus[n.id] = NodeStatus(outcome = n.outcome, detail = n.detail)
        if (n.outcome == Outcome.RAN) nodeMs[n.id] = n.ms
        runLog = runLog.copy(
            lines = runLog.lines +
                com.abrah.nightmare.canvas.runLineOf(n.id, n.outcome, n.ms, n.detail),
            now = null,
            step = null,
        )
    }

    fun clearRunLog() { runLog = com.abrah.nightmare.canvas.RunLogState() }

    // ---- batching ---------------------------------------------------------

    /**
     * The sweep the Batch sheet is building, or null when it is closed.
     *
     * ⚠ Held in the view model rather than in the sheet so a half-built sweep
     * survives a recomposition — a phone rotating mid-typing must not discard
     * the values someone just entered.
     */
    var batch by mutableStateOf<BatchSpec?>(null)
        private set

    /**
     * ⭐⭐ The knobs armed on the CANVAS's graph, for the run bar.
     *
     * ⚠ Read from the graph every time rather than cached: the arming IS a
     * param, so the graph is the one copy of the fact and a cache here would be
     * a second answer to "is cfg being swept".
     */
    val armedSweeps: List<com.abrah.nightmare.canvas.ArmedSweep>
        get() = BatchParams.axesOf(canvas.workflow.graph).map {
            com.abrah.nightmare.canvas.ArmedSweep(
                it.nodeId, it.param, it.values.size, it.values,
            )
        }

    /** ⚠ Releasing is clearing the param — the same one place the arming lives. */
    fun releaseSweep(a: com.abrah.nightmare.canvas.ArmedSweep) {
        editCanvas { s -> s.setParam(a.nodeId, BatchParams.keyFor(a.param), "") }
    }

    fun openBatch() { batch = batch ?: BatchSpec() }
    fun closeBatch() { batch = null }
    /** ⚠ `updateBatch`, not `setBatch`: `var batch` already generates the
     *  latter on the JVM and the two clash at the bytecode level. */
    fun updateBatch(spec: BatchSpec) { batch = spec }

    /** ⚠ Which run of how many, while a sweep is going. Null when it is not. */
    var batchProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    @Volatile private var cancelBatch = false

    /**
     * ⚠⚠ **Stops BETWEEN iterations, not during one.** A render in flight is
     * on the NPU and cannot be interrupted without killing the backend; what a
     * sweep must never be is unstoppable, and eight renders is where that stops
     * being tolerable. `ARCHITECTURE` §8b names this as a requirement rather
     * than a nicety.
     */
    fun cancelBatchRun() { cancelBatch = true }

    /**
     * ⭐⭐ Run the canvas graph once per combination in [spec].
     *
     * ⚠⚠ The user's graph is NEVER modified — each iteration runs a COPY with
     * the overrides applied, exactly as `runRolled` copies for the rolled seed.
     * That is also what lets the executor's cache carry the sweep: only the
     * nodes whose params actually moved are re-run, so a 4-seed batch pays for
     * four samplers and one of everything upstream.
     */
    fun runBatch(spec: BatchSpec) = run("batch") {
        fitChainedImageToImage()
        // ⭐⭐ **Seeds are rolled HERE, once per sweep**, and written into the
        // axis before anything expands. ⚠ Once, and at the start: expanding a
        // spec whose values were random per call would give a different seed to
        // the count preview than to the run, and a different one again to each
        // iteration's label. ⚠ The count is unchanged, so the number the user
        // was shown before pressing Run is still the number of renders.
        // [BatchSpec.rollSeeds] has the two user reports this answers.
        val rolled = spec.rollSeeds()
        val combos = rolled.expand()
        if (combos.isEmpty()) {
            runError = "nothing to sweep — check the values"
            return@run
        }
        if (!modelsPresentOrAsk()) return@run
        if (!ops.ensureBackendFor(canvas.workflow.graph, typesFor(canvas.workflow.graph))) {
            runError = backendRefusal(namesNoKey = false)
            return@run
        }
        // ⚠⚠ **Refused, not guessed.** A graph with two unconsumed image nodes
        // has no single answer to "which pictures is this sweep collecting",
        // and picking one silently is twenty minutes of collecting the wrong
        // node. `terminalImageNodes` returns the list precisely so this can say
        // which ones.
        val terminals = terminalImageNodes(canvas.workflow.graph, typesFor(canvas.workflow.graph))
        if (terminals.size != 1) {
            runError = if (terminals.isEmpty()) {
                "nothing to collect — this graph makes no final picture"
            } else {
                "two endings (${terminals.joinToString(", ")}) — a sweep needs one"
            }
            say("batch: $runError", bad = true)
            return@run
        }
        val terminal = terminals.single()
        cancelBatch = false
        // ⭐ ONE id for the whole sweep, so Results can gather its items into a
        // single entry instead of listing a hundred cards.
        val batchId = "b" + System.currentTimeMillis()
        canvasStatus.clear()
        runError = null
        say("batch: ${combos.size} runs")
        val started = android.os.SystemClock.elapsedRealtime()
        var done = 0
        for ((i, overrides) in combos.withIndex()) {
            if (cancelBatch) {
                say("batch: stopped after $done of ${combos.size}", bad = true)
                break
            }
            batchProgress = (i + 1) to combos.size
            runLog = com.abrah.nightmare.canvas.RunLogState(
                startedAtMs = android.os.SystemClock.elapsedRealtime()
            )
            val graph = overrides.entries.fold(canvas.workflow.graph) { g, (id, params) ->
                g.withParams(id, params)
            }
            val label = rolled.labelFor(overrides)
            say("  run ${i + 1}/${combos.size}  $label")
            val r = ops.runWorkflow(
                canvas.workflow.copy(graph = graph),
                onStart = { id, _ -> runLog = runLog.copy(now = id, step = null) },
                onNode = ::noteNodeRun,
                onProgress = { id, step, total ->
                    canvasStatus[id] = NodeStatus(progress = step to total)
                    runLog = runLog.copy(now = id, step = step to total)
                },
            )
            // ⚠⚠ PER RUN, not per sweep — the same meaning [runCanvas] gives
            // it ("the LAST measured render"), which is what the batch estimate
            // and the ⓘ dialog both read. A whole sweep's elapsed time here
            // would tell the estimator that one render takes eight renders.
            lastRunMs = r.totalMs
            // ⚠ Previews follow the LAST run, so the canvas shows where the
            // sweep got to rather than freezing on the first picture.
            val shown = r.outputs.mapNotNull { (id, v) ->
                (v.previewImage())?.let { img ->
                    id to (img.id to (img.w.toFloat() / img.h.coerceAtLeast(1)))
                }
            }.toMap()
            if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)
            canvas = canvas.copy(rendered = canvas.rendered + renderedBy(r))
            // ⚠ Same reason as the ordinary Run path: a FRAMING node fed by a
            // sampler in this sweep needs its canvas-box preview re-drawn now
            // that `rendered` moved, or it stays stale until an unrelated edit.
            applyFramedPreviews()

            // ⭐⭐ Every run is KEPT, with what made it different in the label.
            // A sweep whose outputs were not collected would be eight renders
            // and one surviving picture, which is the opposite of the point.
            // ⚠ From the TERMINAL node by name, not "the last image in the
            // outputs map" — map order is not graph order, and a sweep that
            // collected the crop instead of the render would look like the
            // sampler doing nothing.
            (r.outputs[terminal]?.previewImage())?.let { img ->
                keepResult(
                    img.id,
                    flow = canvas.workflow.copy(graph = graph),
                    batchId = batchId,
                    // ⚠ For a SEED sweep the override is "0" (roll me), so the
                    // useful label is which run it was — the real seed is
                    // recorded separately by `keepResult` from the run itself.
                    batchLabel = label.ifBlank { "run ${i + 1}" },
                    from = terminal,
                )
            }

            // ⚠⚠ A node waiting on a PERSON stops the sweep too: every further
            // run would stop at the same node, having re-made its upstream.
            if (r.waiting != null) {
                stopForPerson(r)
                say("batch: stopped — ${r.waiting.first} is waiting for you")
                break
            }
            if (r.error != null) {
                // ⚠ Stops the sweep. Eight runs that all fail the same way is
                // eight times the wait for one message.
                runError = r.error
                say("batch: stopped — ${r.error}", bad = true)
                break
            }
            done++
        }
        batchProgress = null
        runLog = runLog.copy(
            startedAtMs = 0L, now = null, step = null,
            totalMs = android.os.SystemClock.elapsedRealtime() - started,
            // ⭐⭐ Every run was kept, and the canvas shows only the LAST one —
            // so say where the other seven went and offer to open it.
            keptCount = done,
        )
        say("batch: $done of ${combos.size} done — all $done are in Results")
        refreshResults()
    }

    /**
     * ⭐⭐ **Run means "sweep" when knobs are armed.**
     *
     * ⚠⚠ One button, not two. The arming is visible in the run bar with its
     * own release, so "why did Run take twenty minutes" is answerable by
     * looking at the screen — which is the whole reason the chip is there. A
     * separate Batch button beside an armed sweep would leave plain Run quietly
     * ignoring the thing the bar says is on.
     */
    fun runCanvasOrBatch() {
        val axes = BatchParams.axesOf(canvas.workflow.graph)
        if (axes.isEmpty()) { runCanvas(); return }
        runBatch(BatchSpec(axes))
    }

    /**
     * What the armed sweep is about to cost — for the confirm, before it runs.
     *
     * ⚠ `lastSampleMs` is the LAST measured render, so the estimate is this
     * device and this model rather than a constant that is wrong everywhere but
     * here. Null until something has been rendered.
     */
    var lastRunMs by mutableStateOf<Long?>(null)
        private set

    fun batchEstimate(): BatchEstimate =
        estimateBatch(BatchParams.runCount(canvas.workflow.graph), lastRunMs, lastResultBytes)

    private var lastResultBytes: Long? = null

    /**
     * ⭐ Why Run could not even start — ONE sentence for Run and for a sweep.
     *
     * ⚠ They had two: a sweep said "see Settings > Diagnostics" and never said
     * "no model installed", while Run said "see the harness log" (a screen the
     * user reaches only through Settings). The design review, 2026-09-15.
     *
     * ⚠⚠⚠ **And "see Settings > Diagnostics" pointed at a screen that was
     * DELETED on 2026-09-19**, three days before a user followed it. Reported
     * 2026-09-21 while importing a community FLUX checkpoint. It is the doc
     * rule applied to UI copy — *a superseded claim is worse than no claim* —
     * and the fix is not a better pointer: the backend had already said exactly
     * what was wrong, and nothing showed it.
     *
     * ⇒ **Say the reason here** ([BackendProcess.failureReason]). The chip is
     * the only surface a person sees when a run refuses, so the one line that
     * explains it belongs in the chip, not behind a navigation path.
     */
    private fun backendRefusal(namesNoKey: Boolean): String {
        if (!namesNoKey &&
            ModelCatalog.byId(SelectedModel.id)?.installed(getApplication()) != true
        ) {
            return "no model installed — open Models and download one"
        }
        // ⚠ The backend's own words, trimmed to the root cause. A checkpoint the
        // engine cannot load says so by name ("parsing ComfyUI quantization
        // metadata tensor failed"), which is the difference between "try another
        // file" and "this app is broken".
        val why = BackendProcess.failureReason()
        return if (why != null) "the backend would not start — $why"
        else "the backend would not start"
    }

    /**
     * ⭐⭐ A run that ENDS without reporting a total must not keep saying
     * "starting…".
     *
     * ⚠⚠ Reported from the phone 2026-09-15: Run with no model installed set a
     * running log, refused at `ensureBackend`, and returned — so the panel sat
     * on "starting…" with its clock ticking, forever, under the error that said
     * why. Any early return, throw or cancel did the same. ⇒ Settled in
     * [run]'s `finally`, so no future exit path can forget it: a run that never
     * reached a node goes back to idle (the error chip says why); one that did
     * stops its clock and keeps its lines.
     */
    private fun settleRunLog() {
        val log = runLog
        if (!log.running) return
        runLog = if (log.lines.isEmpty()) {
            com.abrah.nightmare.canvas.RunLogState()
        } else {
            log.copy(
                startedAtMs = 0L, now = null, step = null,
                totalMs = android.os.SystemClock.elapsedRealtime() - log.startedAtMs,
            )
        }
    }

    fun runCanvas() = run("canvas") {
        fitChainedImageToImage()
        canvasStatus.clear()
        runError = null
        // ⚠ `elapsedRealtime`, not `currentTimeMillis`: a clock correction
        // mid-render must not make the timer jump or go backwards.
        runLog = com.abrah.nightmare.canvas.RunLogState(
            startedAtMs = android.os.SystemClock.elapsedRealtime()
        )

        // ⭐⭐⭐ Refused BEFORE the backend is even started: a render with nowhere
        // to go would cost 24 seconds and show the user nothing, because a
        // renderer no longer draws its own result ([NodeType.showsResult]).
        // ⚠ Named, with the fix in the sentence — the run log's red line is the
        // only thing a user on the canvas can see.
        val orphans = runCatching {
            rendersNowhere(canvas.workflow.graph, typesFor(canvas.workflow.graph))
        }.getOrDefault(emptyList())
        if (orphans.isNotEmpty()) {
            runError = orphans.joinToString(", ") + " has nowhere to send its picture — " +
                "connect an Output node"
            runLog = runLog.copy(
                lines = runLog.lines + com.abrah.nightmare.canvas.RunLine(
                    orphans.first(), runError.orEmpty(), bad = true,
                ),
            )
            return@run
        }

        // ⭐ Start the backend if it is not up. A person pressing Run should not
        // have to know that a server process exists, let alone find the harness
        // screen to launch it -- which is what the app required until now.
        // ⚠ Shared with the headless op via HarnessOps, so the two front ends
        // cannot drift on the one path a user actually takes.
        // ⚠ A graph naming no context key (upscale-only, or all app-side) needs
        // a server but NOT a checkpoint. Launching one the ordinary way held
        // ~1.2 GB of SD pipeline for nothing -- and made the load readout name a
        // model the flow was not using.
        val namesNoKey = runCatching {
            contextKeyModels(canvas.workflow.graph, typesFor(canvas.workflow.graph)).isEmpty()
        }.getOrDefault(false)
        if (!modelsPresentOrAsk()) return@run
        if (!ops.ensureBackendFor(canvas.workflow.graph, typesFor(canvas.workflow.graph))) {
            runError = backendRefusal(namesNoKey)
            return@run
        }

        val r = ops.runWorkflow(
            canvas.workflow,
            // ⭐ Named before it runs, so a 24 s sampler is a node with a name
            // rather than a frozen screen.
            onStart = { id, _ ->
                runLog = runLog.copy(now = id, step = null)
            },
            onNode = ::noteNodeRun,
            onProgress = { id, step, total ->
                canvasStatus[id] = NodeStatus(progress = step to total)
                runLog = runLog.copy(now = id, step = step to total)
            },
            // ⭐⭐ A long node's own narration, live in the panel.
            //
            // ⚠⚠ Reported from the phone 2026-09-12: the video node ran for 25 s
            // with the panel showing one name and 0%, because the pipeline's
            // stage lines only ever reached logcat. A percentage that sits at
            // zero while three gigabytes are mapped is not wrong, it is just not
            // an answer to "is this doing anything".
            //
            // ⚠ Appended, not trimmed here: the panel already renders only its
            // last MAX_LINES and scrolls, so a second cap in the state would
            // throw away history the panel is capable of showing.
            onLog = { id, text ->
                runLog = runLog.copy(
                    lines = runLog.lines + com.abrah.nightmare.canvas.RunLine(id, text),
                    now = id,
                )
            },
        )
        // ⚠ `startedAtMs = 0` stops the panel's ticking clock; `totalMs` is what
        // it shows instead, so the cost of the run survives the run.
        runLog = runLog.copy(
            startedAtMs = 0L,
            now = null,
            step = null,
            totalMs = r.totalMs,
        )
        // ⚠ Remembered so a sweep can say "about 21 minutes" from a MEASURED
        // render rather than from a constant — `estimateBatch`.
        lastRunMs = r.totalMs
        // ⭐ Every image the graph produced, attached to the node that made it.
        // ⚠ Aspect ratio included: the canvas lays a node out around the
        // picture's own shape so the preview is never stretched.
        // ⚠⚠ A RENDERER does not draw its own result — `core.output` does
        // ([NodeType.showsResult]). Without this filter the picture appears
        // twice, on the node that made it and on the node that says where it
        // goes, and the first one reads as the end of the flow.
        val shown = r.outputs.mapNotNull { (id, v) ->
            val t = canvas.workflow.graph.byId[id]?.type
            if (nodeTypes[t]?.showsResult == false) return@mapNotNull null
            (v.previewImage())?.let { img ->
                id to (img.id to (img.w.toFloat() / img.h.coerceAtLeast(1)))
            }
        }.toMap()
        if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)
        // ⭐⭐ …and what EVERY node rendered, shown or not ([CanvasState.rendered]).
        canvas = canvas.copy(rendered = canvas.rendered + renderedBy(r))
        // ⚠⚠ **Re-frame downstream, now that `rendered` just moved.** A
        // FRAMING node (i2i, inpaint) fed by a sampler upstream reads its
        // picture through `pictureInto`, which resolves to `rendered[up]` for
        // a renderer — but its CANVAS BOX preview is a cached bitmap in
        // `canvas.previews`, written only by [applyFramedPreviews], and this
        // Run just updated `rendered` through a DIFFERENT assignment than the
        // one `updateCanvas` watches for that trigger. Without this, a chain
        // like generate → inpaint left the downstream node's box showing
        // whatever it had before this Run — stale or blank — until the next
        // edit happened to call [refreshPreviews] on its own. Reported
        // 2026-09-18. ⚠ Direct, not the debounced [refreshPreviews]: a Run
        // already took real time, so there is nothing left to coalesce.
        applyFramedPreviews()
        // ⭐ …and the clips, so the node that made one can offer to play it.
        // ⚠ Kept beside the previews rather than inside them: a poster is a
        // picture like any other, and the clip is the thing it is a still OF.
        val clips = r.outputs.mapNotNull { (id, v) ->
            (v as? Value.Video)?.let { id to it.path }
        }.toMap()
        if (clips.isNotEmpty()) canvas = canvas.copy(videos = canvas.videos + clips)

        // ⭐⭐⭐ **Autosave**: the output node says every Run is worth keeping,
        // so it is kept here — with the flow, the seed and the prompt, exactly
        // as the disk button would.
        //
        // ⚠⚠ HERE rather than inside the node: keeping needs the store, the
        // workflow and the batch label, none of which a `NodeType.run` can
        // reach ([MediaOutputNode]). ⚠ Keyed on the picture the OUTPUT node
        // holds, so a graph with two branches keeps the one it was told to.
        //
        // ⭐⭐ A Run with SEVERAL outputs is kept as one group, the way a sweep
        // is: a shared `batchId` puts its pictures side by side in Results and
        // the viewer walks them together, each labelled with the output node it
        // came from. The user's call, 2026-09-19. ⚠ A single output keeps no
        // batch, so an ordinary Run reads exactly as it always did.
        if (MediaOutputNode.autosaves(canvas.workflow.graph)) {
            val outs = canvas.workflow.graph.nodes
                .filter { it.type == MediaOutputNode.name }
                .mapNotNull { n -> r.outputs[n.id]?.previewImage()?.id?.let { n.id to it } }
                // ⚠ Two outputs wired to ONE picture keep it once. `kept` below
                // cannot catch that: it is not refreshed until these saves land.
                .distinctBy { it.second }
                // ⚠ Not already there: a Run that changed nothing is served from
                // the cache and would otherwise keep a second copy every press.
                .filter { (_, id) -> kept.none { it.imageId == id } }
            val batchId = if (outs.size > 1) "b" + System.currentTimeMillis() else null
            outs.forEach { (node, id) ->
                keepResult(
                    id, canvas.workflow,
                    batchId = batchId,
                    batchLabel = if (batchId != null) node else "",
                    auto = true,
                    from = node,
                )
            }
        }

        r.error?.let {
            runError = it
            say("canvas: $it", bad = true)
        }
        // ⭐⭐ A node WAITING on a person ([NeedsInput]): say why, FIT it to the
        // picture that was just made, and open its CROP tab on that picture
        // (the user's call, 2026-09-17: frame first, then paint). ⚠ Not
        // [runError]: nothing failed.
        // ⚠ The same [autoFraming] a new photo gets — the resolution or aspect
        // closest to the GENERATED picture's shape, then the whole of it framed.
        // ⚠ Only a node with nothing painted waits for this reason, so there is
        // no painting for the refit to move.
        stopForPerson(r)
        // ⚠ A graph can complete with a FAILED node and no graph-level error;
        // saying nothing then is the same silence in a different disguise.
        if (r.error == null) {
            val bad = r.runs.firstOrNull { it.outcome == Outcome.FAILED }
            if (bad != null) runError = "${bad.id}: ${bad.detail}"
        }
    }



    private val ops = HarnessOps(app, object : HarnessOps.Sink {
        override fun say(text: String, bad: Boolean) = this@HarnessViewModel.say(text, bad)
        override fun image(bitmap: Bitmap) { this@HarnessViewModel.image = bitmap.asImageBitmap() }
        override fun progress(stepOfTotal: Pair<Int, Int>?) {
            this@HarnessViewModel.progress = stepOfTotal
        }
        override fun backend(state: BackendState) { this@HarnessViewModel.backend = state }
    })

    /**
     * ⚠⚠ `@Synchronized`, and it is not decoration: this CRASHED the app.
     *
     * Ops run off the main thread and several can log at once. The trim below
     * is a check-then-act -- read `log.size`, then `removeRange(200, size)` --
     * and two threads that both pass the check race: the second removes with an
     * index the first has already invalidated.
     * `IndexOutOfBoundsException: toIndex = 201`, thrown on a Dispatchers.IO
     * worker, which takes the whole process with it.
     *
     * ⚠ Measured 2026-09-08 while dragging in the CROPPER, which is the part
     * that made it look like a cropper bug: the crop editor wrote four params
     * per pointer event, each one autosaving, and the failing saves all logged
     * at once. `SnapshotStateList` is safe for single mutations; a compound
     * read-then-mutate is not, whatever the list is.
     */
    @Synchronized
    fun say(text: String, bad: Boolean = false) {
        // ⚠ Mirrored to logcat, not just the on-screen list. When a scripted op
        // fails, the in-app log is on a screen nobody may be looking at, and
        // `logcat -s BackendProcess:V` shows NOTHING for a failure that returns
        // before the child is spawned. That blindness cost a diagnostic round
        // on 2026-09-08.
        android.util.Log.i("Harness", text)
        log.add(0, LogLine(clock.format(Date()), text, bad))
        // The newest 200 lines. An unbounded log on a device that runs for hours
        // is a slow leak nobody notices until a screenshot takes a second.
        if (log.size > 200) log.removeRange(200, log.size)
    }

    fun checkBackend() = run("health") { ops.health() }
    fun encodeText() = run("encode_text") { ops.encodeText() }
    fun vaeDecode() = run("vae_decode") { ops.vaeDecode() }
    fun sample() = run("sample") { ops.sample() }
    fun runGraph() = run("graph") { ops.runGraph() }
    fun startBackend() = run("start backend") { ops.launchBackend() }
    fun stopBackend() = run("stop backend") { ops.stopBackend() }

    /** Dispatch for an op named by intent (MainActivity.EXTRA_OP). */
    fun runOp(op: String) = run(op) { ops.run(op) }

    /**
     * ⚠ These are honest stubs, not silent no-ops. A harness button that logs
     * nothing looks identical to one whose op failed, and this project has
     * already lost time to checks that reported the opposite of the truth.
     */
    fun notWired(op: String, step: String) = say("$op — not wired yet ($step)", bad = true)

    private companion object {
        /** ⚠ The shade's own tick — see [tickProgress]. */
        const val NOTICE_TICK_MS = 1000L
        /** ⭐ The model-swap dialog's last answers ([confirmSwap]). */
        const val SWAP_TAKE_PROMPT = "swap_take_prompt"

        const val SWAP_TAKE_RECIPE = "swap_take_recipe"

        /** ⚠ DreamUI's 10/512 — the grow a first tap gives an ungrown mask. */
        const val TAP_GROW = 10f / 512f

        /** The one workflow the app keeps. Named because there will be more. */
        const val CURRENT = "current"

        /**
         * How long the canvas must be still before a free preview is resolved.
         *
         * ⚠ Long enough that a crop drag costs ONE resolve rather than one per
         * pointer event -- `load_image` is deliberately not cacheable, so each
         * one decodes the photo again -- and short enough that letting go of the
         * frame updates the node before the eye moves back to it.
         */
        const val PREVIEW_DEBOUNCE_MS = 220L

        /** ⚠ Ten 1024² bitmaps is ~40 MB — past this the map is dropped whole. */
        const val FULL_IMAGE_CACHE = 12

        /** ⚠ Its own tag so `logcat -s NmPreview` is the whole story, with no Run noise. */
        const val PREVIEW_TAG = "NmPreview"
    }

    /**
     * ⭐⭐ The job a Cancel button stops. Null when nothing is running.
     *
     * ⚠ Held rather than derived from `busy`, because cancelling needs the Job
     * itself and `busy` is only a flag.
     */
    private var runJob: kotlinx.coroutines.Job? = null

    /** True while a run is in flight and can be stopped. */
    val canCancel: Boolean get() = busy

    /**
     * ⭐⭐ Stop the run in progress. **Completed nodes keep their outputs**, so
     * pressing Run again resumes from the cache rather than redoing them — the
     * executor's cache is untouched by this, exactly as it is by a node that
     * fails.
     *
     * ⚠⚠ **The disconnect is what actually stops the NPU**, not the job
     * cancellation. The sampler's SSE loop is a blocking socket read that
     * coroutine cancellation cannot interrupt, so without
     * [Backend.abortInFlight] this would stop the UI and leave the render
     * running to completion. The backend notices the dead socket on its next
     * progress write and aborts (`main.cpp`, "Client disconnected").
     *
     * ⚠ Order matters: disconnect FIRST so the read is already unblocked when
     * the job is cancelled, then cancel so nothing downstream of it runs.
     */
    fun cancelRun() {
        if (!busy) return
        say("cancelling…")
        Backend.abortInFlight()
        runJob?.cancel()
    }

    private fun run(label: String, block: suspend () -> Unit) {
        // ⚠⚠ The DOWNLOAD is named, because it is the one a user cannot see
        // from the canvas. "already running" was true and useless: nothing was
        // running that they had started or could find.
        if (installing != null) {
            val what = if (installing == VIDEO_INSTALL_ID) "the video models"
                else ModelCatalog.byId(installing.orEmpty())?.label ?: "a model"
            runError = "$what is downloading — Run once it has finished"
            say("$label ignored — $what is downloading", bad = true)
            return
        }
        if (busy) { say("$label ignored — already running", bad = true); return }
        busy = true
        // ⚠ Both ends, for the same reason [applyNodeModel] does it: the
        // readout must stop saying "idle" the moment Run is pressed, not up to
        // two seconds later.
        refreshLoad()
        // ⭐⭐ The breadcrumb: if the process dies between here and the
        // `finally` below, the next launch says so ([CrashReport]). A render is
        // the likeliest moment for that — it is when gigabytes of checkpoint
        // are resident.
        CrashReport.mark(getApplication(), renderingBreadcrumb())
        runJob = viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // ⚠⚠ Caught SEPARATELY and rethrown. The broad `Throwable` below
                // would otherwise swallow a cancellation and report it as a
                // crash -- and a coroutine that eats its own CancellationException
                // leaves the scope believing the job is still alive.
                runError = "cancelled"
                say("$label cancelled")
                throw e
            } catch (e: Throwable) {
                // ⚠⚠ Throwable, not Exception. `UnsatisfiedLinkError` from a
                // missing native library is an ERROR, so it slipped past the
                // narrower catch and the op vanished with no line in the log at
                // all -- indistinguishable from one that never started. This
                // project has already lost a session to an op that failed in
                // silence (`notes/HANDOFF.md` §5).
                say("$label threw ${e.javaClass.simpleName}: ${e.message}", bad = true)
            } finally {
                // ⚠ FIRST in the finally: every other line here can throw, and a
                // breadcrumb left behind turns the next routine reclaim into a
                // false crash report — the one thing this was asked not to do.
                CrashReport.clear(getApplication())
                settleRunLog()
                busy = false
                runJob = null
                refreshLoad()
            }
        }
    }

    /**
     * ⭐⭐⭐ **Read the model list ONCE at construction**, so nothing that needs
     * it has to hope a screen was visited first.
     *
     * ⚠⚠⚠ [modelRows] was filled only by opening the **Models tab** (and by
     * install/delete/probe). The sampler's checkpoint picker is drawn from it,
     * and was hidden when it was empty — so on a COLD START, before anyone had
     * been to Models, a sampler's inspector had no Checkpoint field at all.
     * Reported the minute a fresh APK was installed, 2026-09-16: *"THERES NO
     * FUCKING DROPDOWN NOW"*. It had nothing to do with the picker — every
     * version of it carried the same gate, and it only showed once the process
     * restarted without visiting Models.
     *
     * ⚠⚠ The general shape, worth more than the fix: **a sheet must not depend
     * on a screen having been opened.** `modelRows` reads as view state for the
     * Models screen and is in fact app state; anything filled by navigation is
     * empty on the path that skips that navigation, and that is the path a new
     * user takes.
     *
     * ⚠⚠⚠ **LAST in the class, and it must stay last.** Kotlin runs property
     * initialisers and `init` blocks in DECLARATION order, so an `init` placed
     * where this text first went — beside [refreshModels] — called it before
     * `upscalerRows` existed and every Robolectric test died on
     * `MutableState.setValue` against null. A constructor that touches state
     * must come after all of it.
     */
    init {
        // ⭐⭐ Asked at app open, exactly once per process, and null on an
        // ordinary launch.
        //
        // ⚠⚠⚠ **FIRST, before [refreshModels].** That function's backstop
        // clears the breadcrumb whenever nothing is installing or running —
        // which at startup is always true — so calling it first would delete the
        // evidence of the crash being reported and every kill would read as
        // idle. The two are correct individually and wrong in the other order.
        //
        // ⚠ `runCatching`: a diagnostic must never be the thing that stops the
        // app opening ([DeviceProbe] makes the same promise).
        crashReport = runCatching { CrashReport.pending(app) }.getOrNull()
        refreshModels()
    }
}

/**
 * ⭐⭐⭐ **Which before/after entries are stale** — the rule that decides what
 * `CanvasState.beforePreviews` stops holding.
 *
 * ⚠⚠⚠ It had only the second half. `empty` was computed over the before/after
 * nodes THEMSELVES, so a node that stopped being one — untick `auto upscale` —
 * was in neither set and kept its entry forever. Reported from the phone
 * 2026-09-22: *"turning off autoupscale and rerunning updates only the upscale
 * preview instead of removing it, and the received frame goes stale with some
 * old shit."* ⇒ The universe to sweep is everything [held], not everything live.
 *
 * ⚠⚠ A node that IS still a before/after node and whose bitmap has not resolved
 * yet is in neither set on purpose: dropping it there would blink the Received
 * frame out and back on every preview pass.
 *
 * @param held the node ids that currently have an entry.
 * @param beforeAfter the node ids that are before/after nodes right now.
 * @param empty those of [beforeAfter] with nothing wired into them.
 */
internal fun staleBeforePreviews(
    held: Set<String>,
    beforeAfter: Set<String>,
    empty: Set<String>,
): Set<String> = held.filterTo(mutableSetOf()) { it !in beforeAfter } + empty
