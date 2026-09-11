package com.abrah.nightmare

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
class HarnessViewModel(app: Application) : AndroidViewModel(app) {

    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    val log = mutableStateListOf<LogLine>()
    var backend by mutableStateOf(BackendState.UNKNOWN)
        private set
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

    /** ⚠ Which Settings tab, hoisted exactly as [libraryTab] is — the two
     *  screens are siblings and must behave the same way. */
    var settingsTab by mutableStateOf(com.abrah.nightmare.ui.SettingsTab.THEME)
        private set

    fun switchSettingsTab(t: com.abrah.nightmare.ui.SettingsTab) { settingsTab = t }

    // ---- app settings ----------------------------------------------------

    /**
     * ⭐ The app's own theme. ⚠ Mirrored into state so Compose recomposes;
     * `Prefs` owns the disk and this owns the frame.
     */
    var theme by mutableStateOf(Prefs.theme)
        private set

    /**
     * ⚠ `chooseTheme`, not `setTheme`: a `var theme` already generates a
     * `setTheme` on the JVM, and the two clash at the bytecode level with an
     * error that names a signature rather than the property.
     */
    fun chooseTheme(value: Prefs.Theme) {
        Prefs.setTheme(getApplication(), value)
        theme = value
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
            say("\"$label\" not opened -- this flow is still rendering", bad = true)
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
    )

    var load by mutableStateOf<CanvasLoad?>(null)
        private set

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
        val spec = if (backend == BackendState.UP) ModelCatalog.byId(SelectedModel.id) else null
        load = CanvasLoad(
            ramFreeBytes = mi.availMem,
            ramTotalBytes = mi.totalMem,
            resident = spec?.label,
            residentBytes = spec?.bytesOnDisk(ctx) ?: 0L,
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
            }
        } catch (e: Exception) {
            workflowError = "could not open \"$name\" -- ${e.message}"
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
                        }
                    },
                    onFailure = {
                        workflowError = "could not import that file -- ${it.message}"
                    },
                )
            }
        }
    }

    /**
     * ⭐⭐ Import a PLUGIN PACK — the `.zip` `PluginInstaller` already accepts.
     *
     * ⚠⚠⚠ **There is no validation gate yet** (`docs/ARCHITECTURE.md` §8c).
     * `PluginInstaller` bounds the ARCHIVE — entry count and total bytes, so a
     * zip bomb cannot finish — and the QuickJS sandbox plus default-deny
     * permissions stop a pack reaching the network or the disk. What nothing
     * checks is BEHAVIOUR: a pack can loop forever or allocate until the app
     * dies, and that reads to the user as "the app hung". ⇒ This is safe to
     * offer for a pack you wrote; it is not yet safe as a way to run a
     * stranger's code, and the Community tab says so.
     */
    fun importPlugin(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = runCatching {
                // ⚠ Copied to a real File first: PluginInstaller works on the
                // filesystem, and a content:// stream has no path.
                val tmp = java.io.File(ctx.cacheDir, "import-${System.currentTimeMillis()}.zip")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                } ?: throw java.io.IOException("could not read that file")
                try {
                    PluginInstaller.install(tmp, ops.pluginsDir(), ctx.cacheDir)
                } finally {
                    tmp.delete()
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                result.fold(
                    onSuccess = { dir ->
                        say("installed the pack in ${dir.name}")
                        // ⚠⚠ The node types are cached in a `by lazy`, so a pack
                        // installed now is invisible until the process restarts.
                        // Said out loud rather than left as "my node is missing".
                        say("  ⚠ restart the app for its nodes to appear", bad = true)
                    },
                    onFailure = { workflowError = "could not import that pack -- ${it.message}" },
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
    val importing: String? get() = installing?.takeIf { ModelCatalog.byId(it) == null }

    /** The phase/bytes of [importing], or null. */
    val importProgress: ModelInstaller.Progress? get() = if (importing != null) installProgress else null
    @Volatile private var cancelInstall = false
    private var installProgress by mutableStateOf<ModelInstaller.Progress?>(null)

    /**
     * ⚠ Reads the disk on every call. The alternative is a cache that has to be
     * invalidated by install, delete and cancel alike -- and the one that gets
     * missed shows a model as installed after it was removed.
     */
    fun refreshModels() {
        val ctx = getApplication<Application>()
        // ⚠ The upscalers ride along: this is the app's "re-read the disk"
        // entry point and a second one would be a second thing to forget.
        refreshUpscalers()
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
    fun importModel(uri: android.net.Uri, name: String) {
        if (installing != null) return
        val ctx = getApplication<Application>()
        installing = name
        cancelInstall = false
        modelError = null
        busy = true
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
                    onProgress = { p -> installProgress = p },
                    isCancelled = { cancelInstall },
                )
                val missing = spec.missing(ctx)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    busy = false
                    if (missing.isEmpty()) {
                        selectModel(spec)
                    } else {
                        modelError = "${spec.label} imported but is incomplete: " +
                            "missing ${missing.joinToString()}"
                    }
                    refreshModels()
                }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    installing = null
                    installProgress = null
                    busy = false
                    modelError = "import failed: ${e.message}"
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
        busy = true
        // ⚠ The build the DEVICE can take, and null means it can take none --
        // refused up front rather than after 3.5 GB.
        val build = spec.buildFor(DeviceProbe.caps())
        if (build == null) {
            modelError = "${spec.label} needs an HTP arch of " +
                "${spec.builds.minOf { it.minArch }} or newer; this device reports " +
                "${DeviceProbe.caps().arch}"
            installing = null
            busy = false
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
                        viewModelScope.launch { installProgress = p; refreshModels() }
                    },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch {
                    say("installed ${spec.label}")
                    // ⭐ First model in becomes the one in use. Otherwise a user
                    // downloads a model, presses Run, and renders against a
                    // model they do not have.
                    if (ModelCatalog.byId(SelectedModel.id)?.installed(ctx) != true) {
                        SelectedModel.set(ctx, spec.id)
                    }
                }
            } catch (e: ModelInstaller.Cancelled) {
                viewModelScope.launch { say("download cancelled", bad = true) }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    say("install failed -- $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    busy = false
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

    fun refreshUpscalers() {
        val ctx = getApplication<Application>()
        // ⚠ The node's dropdown reads a cache, not the disk -- refresh it here,
        // where the disk is being read anyway, or a just-downloaded upscaler
        // stays invisible to every upscale node until the app restarts.
        UpscalerCatalog.refresh(ctx)
        upscalerRows = UpscalerCatalog.ALL.map { spec ->
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
        busy = true
        installProgress = ModelInstaller.Progress("starting", 0, build.bytes)
        refreshUpscalers()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                UpscalerCatalog.install(
                    ctx, spec, build,
                    onProgress = { p ->
                        viewModelScope.launch { installProgress = p; refreshUpscalers() }
                    },
                    isCancelled = { cancelInstall },
                )
                viewModelScope.launch { say("installed ${spec.label}") }
            } catch (e: ModelInstaller.Cancelled) {
                viewModelScope.launch { say("download cancelled", bad = true) }
            } catch (e: Exception) {
                viewModelScope.launch {
                    modelError = e.message ?: e.javaClass.simpleName
                    say("install failed -- $modelError", bad = true)
                }
            } finally {
                viewModelScope.launch {
                    installing = null
                    installProgress = null
                    busy = false
                    refreshUpscalers()
                }
            }
        }
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
            say("could not delete -- ${e.message}", bad = true)
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
            say("  stopping the backend -- it was launched for the previous model")
            stopBackend()
        }
        refreshModels()
    }

    /**
     * ⭐⭐ Change the render size for the WHOLE graph.
     *
     * ⚠⚠ **The same act as choosing a model, and it must do the same things.**
     * `--patch` binds at backend launch beside `--type` and `--model_dir`
     * (`docs/ARCHITECTURE.md` §4), so a resolution is a third of the context
     * key, not a knob: it rewrites every backend node, it invalidates the
     * running process, and it costs a relaunch of 2.3-5 s on the next Run.
     * Doing less than [selectModel] does here would leave the graph and the
     * process disagreeing about the size, which decodes plausible garbage
     * rather than failing.
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
    fun selectResolution(res: Res) {
        val ctx = getApplication<Application>()
        val spec = SelectedModel.spec
        // ⚠⚠ **Not `if (res == SelectedModel.res) return`.** That guard was right
        // while the only way to choose a size was the model card, where the
        // selection WAS the state. The knob is on a node now, and the graph is
        // authoritative (`docs/MODELS.md` §4) -- so a workflow opened at 768x512
        // while the selection says 512x512 must still be retargetable back to
        // 512x512, and an early return keyed on the selection alone would
        // silently refuse exactly that. ⇒ Bail only when the selection AND every
        // node already agree, which is the real "nothing to do".
        val graphAgrees = runCatching {
            contextKeyRetarget(
                canvas.workflow.graph, typesFor(canvas.workflow.graph), spec, res,
            ).isEmpty()
        }.getOrDefault(false)
        if (res == SelectedModel.res && graphAgrees) return
        val ok = spec.availableResolutions(ctx)
        if (res !in ok) {
            say(
                "${spec.label} cannot render $res -- it serves ${ok.joinToString(", ")}",
                bad = true,
            )
            return
        }
        SelectedModel.setRes(ctx, res)
        say("resolution set to $res")
        // ⚠ The last run was at the OLD size, so its timings and its "done" no
        // longer describe this canvas -- exactly as after a model switch.
        clearRunLog()
        previewSigs.clear()
        retargetCanvas(spec, res)
        if (backend == BackendState.UP) {
            say("  stopping the backend -- it was launched at ${BackendProcess.launchedKey?.let { "${it.width}x${it.height}" } ?: "another size"}")
            stopBackend()
        }
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
        val spec = SelectedModel.spec
        if (!spec.fixedCanvas) {
            say("${spec.label} renders real resolutions -- pick one of those instead", bad = true)
            return
        }
        val graph = canvas.workflow.graph
        val changes = aspectRetarget(graph, typesFor(graph), aspect)
        if (changes.isEmpty()) return
        editCanvas { s -> changes.entries.fold(s) { acc, (id, p) -> acc.setParams(id, p) } }
        previewSigs.clear()
        val target = ModelCatalog.aspectTarget(aspect, spec.native)
        say("aspect $aspect -- ${target ?: spec.native} on ${changes.size} node" +
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
            res in (spec ?: SelectedModel.spec).availableResolutions(ctx)
        if (!modelMoved && !resMoved) return

        if (modelMoved) {
            SelectedModel.set(ctx, id!!)
            say("this workflow uses ${spec!!.label} -- selected it")
        }
        // ⚠ AFTER the model, because [SelectedModel.set] re-resolves the
        // resolution per model and would otherwise overwrite this.
        if (resMoved) {
            SelectedModel.setRes(ctx, res!!)
            say("this workflow renders at $res -- selected it")
        }
        clearRunLog()
        // ⚠ Derived pictures are the previous size, exactly as in [selectModel].
        previewSigs.clear()
        if (backend == BackendState.UP) {
            say("  stopping the backend -- it was launched for the previous context")
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
            editCanvas { s -> changes.entries.fold(s) { acc, (id, p) -> acc.setParams(id, p) } }
            say("  retargeted ${changes.size} node${if (changes.size == 1) "" else "s"} on the canvas")
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
                    " -- set on ${recipe.size} node" + (if (recipe.size == 1) "" else "s")
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
    fun editCanvas(change: (CanvasState) -> CanvasState) = updateCanvas(change(canvas))

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
        canvas = canvas.copy(previews = canvas.previews.filterKeys { !downstream(it) })
        previewSigs.keys.retainAll { !downstream(it) }
    }

    /**
     * ⭐ Drop a RENDER from the node that produced it.
     *
     * ⚠ Not [clearImage]: that clears a `load_image`'s `uri`, which a decode
     * node does not have. A render exists only as a preview and a cache entry,
     * so clearing it is dropping both — and dropping the cache is the
     * load-bearing half. Without it the next Run finds the same key, serves the
     * same latent, and the picture the user just cleared comes straight back.
     */
    fun clearOutput(nodeId: String) {
        val graph = canvas.workflow.graph
        fun downstream(id: String) = id == nodeId || graph.dependsOn(id, nodeId)
        canvas = canvas.copy(previews = canvas.previews.filterKeys { !downstream(it) })
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

    fun saveImage(imageId: String) {
        val ctx = getApplication<Application>()
        val png = ops.images.png(imageId)
        if (png == null) {
            // ⚠ Toasted too: every OUTCOME of a save is announced the same
            // way, or the one that fails is the one nobody hears about.
            say("that picture is no longer in memory -- Run again to remake it", bad = true)
            toast("That picture is no longer in memory — Run again")
            return
        }
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val name = "nightmare-" + System.currentTimeMillis()
            val where = runCatching { ImageSaver.savePng(ctx, png, name) }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                where.fold(
                    onSuccess = { say("saved to $it"); toast("Saved to the gallery") },
                    onFailure = {
                        say("could not save -- ${it.message}", bad = true)
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
        say("could not work out the crop sizes -- ${e.javaClass.simpleName}: ${e.message}", bad = true)
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
        canvas = next.copy(previews = canvas.previews)
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

    fun thumbnailFor(id: String): ImageBitmap? {
        thumbs[id]?.let { return it }
        val bmp = results.thumbnail(id)?.asImageBitmap() ?: return null
        thumbs[id] = bmp
        return bmp
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
    fun keepResult(
        imageId: String,
        flow: com.abrah.nightmare.canvas.Workflow? = null,
        batchId: String? = null,
        batchLabel: String = "",
    ) {
        val bmp = ops.images.get(imageId)
        if (bmp == null) {
            say("that picture is no longer in memory", bad = true)
            return
        }
        val kept = flow ?: canvas.workflow
        val graph = kept.graph
        val sampler = graph.nodes.firstOrNull { it.type == "sd.sample" }
        val seed = sampler?.id?.let { id ->
            com.abrah.nightmare.canvas.seedFor(graph, id) { canvasStatus[it]?.detail }
        }
        val prompt = graph.nodes.firstOrNull { it.type == "sd.clip_encode" }?.params?.get("prompt")
        val workflow = kept
        val types = nodeTypes
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val r = runCatching {
                results.keep(
                    bmp, workflow, types, seed, SelectedModel.spec.label, prompt,
                    batchId, batchLabel,
                )
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                r.fold(
                    onSuccess = { say("kept it, with the flow that made it") },
                    onFailure = { say("could not keep it -- ${it.message}", bad = true) },
                )
                refreshResults()
            }
        }
    }

    /** ⭐ Put a kept result's graph back on the canvas. */
    fun openResultFlow(id: String) {
        val loaded = results.flow(id)
        if (loaded == null) {
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
        val name = resultFlowName(id)
        guardedOpen(name) {
            openWorkflow(loaded.workflow, loaded.view)
            closeLibrary()
            openedResultName = name
            say("opened \"" + name + "\"")
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
     * ⚠⚠ Selecting a BATCH means selecting all of it. The card shows one
     * checkbox-worth of state (its cover), and this expands that to the items
     * so a delete or a save covers what the user actually pointed at.
     */
    fun expandSelection(ids: Set<String>): Set<String> {
        val out = ids.toMutableSet()
        for (g in keptGroups) if (g.isBatch && g.cover.id in ids) {
            out += g.items.map { it.id }
        }
        return out
    }

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
        val png = results.fullBytes(id)
        if (png == null) { toast("That picture is missing"); return }
        runCatching { Share.image(getApplication(), png, "nightmare-" + id) }
            .onFailure { toast("Could not share: " + it.message) }
    }

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
        val png = ops.images.png(imageId)
        if (png == null) {
            toast("That picture is no longer in memory — Run again")
            return
        }
        runCatching { Share.image(getApplication(), png, "nightmare-" + imageId.take(12)) }
            .onFailure { toast("Could not share: " + it.message) }
    }

    fun saveResultsToGallery(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var ok = 0
            var lastError: String? = null
            for (id in ids) {
                val png = results.fullBytes(id) ?: continue
                runCatching {
                    ImageSaver.savePng(ctx, png, "nightmare-" + id)
                }.fold(
                    onSuccess = { ok++ },
                    onFailure = { lastError = it.message },
                )
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (ok > 0) {
                    toast("Saved " + ok + " to the gallery")
                    say("saved " + ok + " to the gallery")
                } else {
                    toast("Could not save: " + (lastError ?: "nothing to save"))
                    say("could not save -- " + lastError, bad = true)
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

    fun resultImage(id: String): ImageBitmap? {
        fullImages[id]?.let { return it }
        val bmp = results.full(id)?.asImageBitmap() ?: return null
        // ⚠ Bounded: a batch of ten 1024² bitmaps is 40 MB, and a user can walk
        // through several batches without leaving the viewer.
        if (fullImages.size > FULL_IMAGE_CACHE) fullImages.clear()
        fullImages[id] = bmp
        return bmp
    }

    /** ⚠ Read per picture as it is swiped to, from that result's stored flow. */
    fun detailsOf(r: com.abrah.nightmare.canvas.Result): List<Pair<String, String>> =
        results.details(r.id)

    fun viewResult(r: com.abrah.nightmare.canvas.Result) {
        viewingResult = r
        // ⚠ Full size here, not the thumbnail: this is the one place the
        // picture is meant to be looked AT.
        viewingResultImage = results.full(r.id)?.asImageBitmap()
        viewingResultDetails = results.details(r.id)
        // ⚠ The batch, in its own order, or every kept picture.
        val set = if (r.batchId != null) {
            keptGroups.firstOrNull { it.batchId == r.batchId }?.items ?: listOf(r)
        } else {
            kept
        }
        viewingSet = set
        viewingIndex = set.indexOfFirst { it.id == r.id }.coerceAtLeast(0)
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
                say("preview failed -- ${e.javaClass.simpleName}: ${e.message}", bad = true)
            }
        }
    }

    /**
     * Run the free ancestry of [ids] once and attach whatever images come out.
     *
     * ⚠ A node whose signature has not moved is skipped, so this is cheap to
     * call often; a node that needs the backend is skipped by [freeAncestry].
     */
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
        if (wanted.isEmpty()) return

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
                (r.error?.let { " -- $it" } ?: ""),
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
            (r.outputs[id] as? Value.Image)?.let { img ->
                id to (img.id to img.w.toFloat() / img.h.coerceAtLeast(1))
            }
        }
        // ⚠⚠ Read `canvas` again rather than closing over the snapshot above:
        // the user has had PREVIEW_DEBOUNCE_MS plus a graph run to keep editing,
        // and writing back a stale state here would undo whatever they typed.
        if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)
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
            say("could not save the workflow -- ${e.message}", bad = true)
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
                say("saved workflow unreadable -- ${e.message}", bad = true)
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
                    say("could not check this workflow's plugins -- " +
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
        val combos = spec.expand()
        if (combos.isEmpty()) {
            runError = "nothing to sweep -- check the values"
            return@run
        }
        if (!ops.ensureBackend()) {
            runError = "the backend would not start -- see Settings > Diagnostics"
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
                "nothing to collect -- this graph makes no final picture"
            } else {
                "two endings (${terminals.joinToString(", ")}) -- a sweep needs one"
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
            val label = spec.labelFor(overrides)
            say("  run ${i + 1}/${combos.size}  $label")
            val r = ops.runWorkflow(
                canvas.workflow.copy(graph = graph),
                onStart = { id, _ -> runLog = runLog.copy(now = id, step = null) },
                onNode = { n ->
                    canvasStatus[n.id] = NodeStatus(outcome = n.outcome, detail = n.detail)
                    runLog = runLog.copy(
                        lines = runLog.lines +
                            com.abrah.nightmare.canvas.runLineOf(n.id, n.outcome, n.ms, n.detail),
                        now = null, step = null,
                    )
                },
                onProgress = { id, step, total ->
                    canvasStatus[id] = NodeStatus(progress = step to total)
                    runLog = runLog.copy(now = id, step = step to total)
                },
            )
            // ⚠ Previews follow the LAST run, so the canvas shows where the
            // sweep got to rather than freezing on the first picture.
            val shown = r.outputs.mapNotNull { (id, v) ->
                (v as? Value.Image)?.let { img ->
                    id to (img.id to (img.w.toFloat() / img.h.coerceAtLeast(1)))
                }
            }.toMap()
            if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)

            // ⭐⭐ Every run is KEPT, with what made it different in the label.
            // A sweep whose outputs were not collected would be eight renders
            // and one surviving picture, which is the opposite of the point.
            // ⚠ From the TERMINAL node by name, not "the last image in the
            // outputs map" — map order is not graph order, and a sweep that
            // collected the crop instead of the render would look like the
            // sampler doing nothing.
            (r.outputs[terminal] as? Value.Image)?.let { img ->
                keepResult(
                    img.id,
                    flow = canvas.workflow.copy(graph = graph),
                    batchId = batchId,
                    // ⚠ For a SEED sweep the override is "0" (roll me), so the
                    // useful label is which run it was — the real seed is
                    // recorded separately by `keepResult` from the run itself.
                    batchLabel = label.ifBlank { "run ${i + 1}" },
                )
            }

            if (r.error != null) {
                // ⚠ Stops the sweep. Eight runs that all fail the same way is
                // eight times the wait for one message.
                runError = r.error
                say("batch: stopped -- ${r.error}", bad = true)
                break
            }
            done++
        }
        batchProgress = null
        runLog = runLog.copy(
            startedAtMs = 0L, now = null, step = null,
            totalMs = android.os.SystemClock.elapsedRealtime() - started,
        )
        say("batch: $done of ${combos.size} done")
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

    fun runCanvas() = run("canvas") {
        canvasStatus.clear()
        runError = null
        // ⚠ `elapsedRealtime`, not `currentTimeMillis`: a clock correction
        // mid-render must not make the timer jump or go backwards.
        runLog = com.abrah.nightmare.canvas.RunLogState(
            startedAtMs = android.os.SystemClock.elapsedRealtime()
        )

        // ⭐ Start the backend if it is not up. A person pressing Run should not
        // have to know that a server process exists, let alone find the harness
        // screen to launch it -- which is what the app required until now.
        // ⚠ Shared with the headless op via HarnessOps, so the two front ends
        // cannot drift on the one path a user actually takes.
        if (!ops.ensureBackend()) {
            runError = if (ModelCatalog.byId(SelectedModel.id)?.installed(getApplication()) != true) {
                "no model installed -- open Models and download one"
            } else {
                "the backend would not start -- see the harness log"
            }
            return@run
        }

        val r = ops.runWorkflow(
            canvas.workflow,
            // ⭐ Named before it runs, so a 24 s sampler is a node with a name
            // rather than a frozen screen.
            onStart = { id, _ ->
                runLog = runLog.copy(now = id, step = null)
            },
            onNode = { n ->
                // ⚠ The progress entry is cleared as the node settles, or a
                // finished node keeps a half-full bar under its result.
                canvasStatus[n.id] = NodeStatus(outcome = n.outcome, detail = n.detail)
                runLog = runLog.copy(
                    lines = runLog.lines +
                        com.abrah.nightmare.canvas.runLineOf(n.id, n.outcome, n.ms, n.detail),
                    // ⚠ Cleared with the node that owned it, or the panel keeps
                    // counting the previous node's steps under the next one's
                    // name.
                    now = null,
                    step = null,
                )
            },
            onProgress = { id, step, total ->
                canvasStatus[id] = NodeStatus(progress = step to total)
                runLog = runLog.copy(now = id, step = step to total)
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
        val shown = r.outputs.mapNotNull { (id, v) ->
            (v as? Value.Image)?.let { img ->
                id to (img.id to (img.w.toFloat() / img.h.coerceAtLeast(1)))
            }
        }.toMap()
        if (shown.isNotEmpty()) canvas = canvas.copy(previews = canvas.previews + shown)

        r.error?.let {
            runError = it
            say("canvas: $it", bad = true)
        }
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
    fun notWired(op: String, step: String) = say("$op -- not wired yet ($step)", bad = true)

    private companion object {
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
        if (busy) { say("$label ignored -- already running", bad = true); return }
        busy = true
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
                busy = false
                runJob = null
            }
        }
    }
}
