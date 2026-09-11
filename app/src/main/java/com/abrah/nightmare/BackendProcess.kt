package com.abrah.nightmare

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Starts and stops the forked C++ backend as a child process of this app.
 *
 * ⚠ The shape of this is taken from DreamUI's `BackendService.kt`, deliberately
 * and almost verbatim, because every constraint below was learned the expensive
 * way there:
 *
 *  1. **The executable lives in `nativeLibraryDir`.** It is named `.so` but is
 *     an ELF *executable*, not a library. Android blocks exec from the writable
 *     app data dir; `nativeLibraryDir` is not writable, and is the one place
 *     execution is allowed. `useLegacyPackaging = true` in build.gradle.kts is
 *     what makes it a real file there rather than a zip entry.
 *  2. **The QNN libs ship as ASSETS, not jniLibs**, and are copied to a private
 *     dir at first run. `DSP_LIBRARY_PATH` must point at that dir or the Hexagon
 *     side cannot find `libQnnHtpV79Skel.so` and NPU init fails with
 *     `error: 1002` — a message that names neither the variable nor the file.
 *  3. **`LD_LIBRARY_PATH` alone is not enough.** Both variables are required.
 */
object BackendProcess {

    private const val TAG = "BackendProcess"
    /** ⚠ Internal, not private: [DeviceProbe] runs the same binary with `--device_info`. */
    const val EXECUTABLE = "libstable_diffusion_core.so"
    private const val RUNTIME_DIR = "qnnruntime"

    private var process: Process? = null

    /** Newest-first, same convention as the harness log. */
    val output = ArrayDeque<String>()

    val isRunning: Boolean get() = process?.isAlive == true

    /**
     * ⭐⭐ The [ContextKey] the RUNNING process was launched with, or null when
     * nothing is up.
     *
     * ⚠⚠ **Nothing recorded this before, and once resolution is a live knob
     * nothing could reconstruct it.** Reconciliation used to be model-id-only —
     * `selectModel` stopped the backend, and everything else assumed the
     * running process matched `SelectedModel`. That assumption is exactly what
     * breaks when `--patch` enters the launch line: a process started at 512²
     * answers `/health` identically to one started at 768², and `/vae_decode`
     * at the wrong size decodes plausible garbage rather than failing.
     *
     * ⇒ The launch key is the only thing that can tell them apart, so it is
     * stored at the moment it is used and cleared when the process dies.
     */
    @Volatile
    var launchedKey: ContextKey? = null
        private set

    /**
     * True when the running process is an upscale-only server — no checkpoint.
     *
     * ⚠ Distinct from `launchedKey == null`, which also means "nothing is
     * running": a caller has to tell "there is no backend" from "there is one,
     * and it deliberately holds no model".
     */
    @Volatile
    var upscalerServer: Boolean = false
        private set

    /**
     * Unpacks `assets/qnnlibs` into `filesDir/qnnruntime`.
     *
     * ⚠ Re-copies every time rather than skipping when the directory exists.
     * A stale runtime dir after a backend upgrade is a genuinely nasty failure:
     * the mismatch shows up as a QNN context that refuses to load, pointing at
     * the model rather than at the libs. 33 MB of copying is cheap next to that.
     */
    fun prepareRuntime(context: Context): File {
        val dir = File(context.filesDir, RUNTIME_DIR).apply { mkdirs() }
        val all = context.assets.list("qnnlibs").orEmpty().toList()
        check(all.isNotEmpty()) {
            "no qnnlibs in assets -- run tools/stage_backend.ps1 before building"
        }
        // ⚠⚠ ONLY this device's arch trio, plus the two shared libraries. The
        // APK carries all six arches (~150 MB) because `libQnnHtp.so` dispatches
        // on the arch of the DEVICE and the matching Skel must be present or NPU
        // init fails outright -- but unpacking all six on every start would copy
        // 150 MB to answer a question with one answer.
        // ⚠ An SoC we do not recognise gets every arch and lets QNN choose:
        // slower to unpack, always correct. A guessed arch is fast and fatal.
        val names = DeviceProbe.runtimeLibs(all)
        for (n in names) {
            val dst = File(dir, n)
            context.assets.open("qnnlibs/$n").use { input ->
                dst.outputStream().use { input.copyTo(it) }
            }
            dst.setReadable(true, false)
            dst.setExecutable(true, false)
        }
        Log.i(TAG, "runtime: ${names.size}/${all.size} libs (${DeviceProbe.caps()}) in ${dir.absolutePath}")
        return dir
    }

    /** Where a model must live for the app to reach it. */
    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models")

    sealed interface Start {
        data object Ok : Start
        data class Failed(val why: String) : Start
    }

    /**
     * ⚠ Returns when the process has been *launched*, not when it is serving.
     * A QNN backend takes 4-5 s to load its contexts, so the caller must still
     * poll `/health`. Reporting "started" as though it meant "ready" is how a
     * first request lands on a socket nobody is listening to yet.
     */
    suspend fun start(
        context: Context,
        modelId: String,
        port: Int,
        /**
         * ⚠ The resolution third of the [ContextKey], bound HERE via `--patch`
         * and nowhere else. Defaults to the selection so every existing caller
         * keeps its meaning; a caller that cares passes the graph's own.
         */
        res: Res = SelectedModel.res,
        /**
         * ⭐⭐ Launch with **no diffusion model at all** — upstream's
         * `--upscaler_mode`, "Upscale-only server, no diffusion model".
         *
         * ⚠⚠ For a graph that names no [ContextKey]: an upscale-only flow, or
         * any all-app-side graph that still needs an endpoint. `/upscale`
         * builds its own QNN context per request, so it needs a PROCESS but not
         * a checkpoint — and launching the ordinary way kept a ~1.2 GB SD
         * pipeline resident underneath it for nothing. That is both why the
         * load readout named a model an upscale flow was not using, and the
         * most likely reason an upscale on top of a resident pipeline died with
         * a closed socket.
         *
         * ⚠ No `--model_dir`, so [launchedKey] stays null and the readout
         * honestly reports nothing held.
         */
        upscalerOnly: Boolean = false,
    ): Start =
        withContext(Dispatchers.IO) {
            if (isRunning) return@withContext Start.Failed("already running")
            try {
                val nativeDir = context.applicationInfo.nativeLibraryDir
                val exe = File(nativeDir, EXECUTABLE)
                if (!exe.exists()) {
                    return@withContext Start.Failed(
                        "backend binary missing from $nativeDir -- " +
                            "run tools/stage_backend.ps1 and rebuild"
                    )
                }

                val model = File(modelsDir(context), modelId)
                // ⚠ Only an ordinary launch needs a model directory. An
                // upscale-only server has none by definition.
                if (!upscalerOnly && !model.isDirectory) {
                    return@withContext Start.Failed(
                        "no model at ${model.absolutePath} -- push one there first"
                    )
                }

                val runtime = prepareRuntime(context)
                // ⚠⚠ The `--type` comes from the CATALOGUE, through the same
                // function every node's `contextKey()` reads
                // (`backendContextKey`). It was a literal here and in three node
                // types; the day one family became two, a process launched for
                // one `--type` and nodes keyed for another would not fail --
                // `sdxl` forces 1024 inside the request parser whatever the
                // client sends, so the mismatch renders the wrong size and
                // reports success.
                val spec = ModelCatalog.byId(modelId)

                // ⚠⚠ **A missing patch is FATAL here, and that is a deliberate
                // departure from both upstreams.** `local-dream`'s
                // BackendService logs "Patch file not found … falling back to
                // 512×512" and launches anyway; DreamUI inherits the same shape
                // by passing null. In a node graph that silence is worse than
                // it is in a single-shot app: the nodes are keyed at the size
                // the user asked for, so `/vae_decode` would then be handed a
                // latent of one size against graphs built for another --
                // "decodes plausible garbage rather than failing", which is the
                // exact wording main.cpp's own /vae_decode guard uses.
                //
                // ⇒ Refuse, and name the file, because the cause is always a
                // model directory that shipped fewer patches than the
                // catalogue assumed.
                if (!upscalerOnly) spec?.missingPatch(context, res)?.let { name ->
                    return@withContext Start.Failed(
                        "$modelId cannot render $res -- ${File(model, name).absolutePath} is missing. " +
                            "Re-download the model, or pick a size it ships a patch for"
                    )
                }
                val patch = spec?.patchFor(context, res)

                val cmd = buildList {
                    add(exe.absolutePath)
                    if (upscalerOnly) {
                        // ⚠ QNN upscalers still need `--lib_dir`; everything
                        // else about a diffusion launch is skipped.
                        add("--upscaler_mode")
                    } else {
                        add("--type"); add(ModelCatalog.backendTypeOf(modelId))
                        add("--model_dir"); add(model.absolutePath)
                    }
                    add("--lib_dir"); add(runtime.absolutePath)
                    add("--port"); add(port.toString())
                    // ⚠⚠ Not a tuning knob. `--lowram` loads and releases each
                    // stage instead of holding the pipeline resident, and every
                    // SDXL checkpoint needs it: a ~3× UNet at 1024² does not fit
                    // beside its VAE and two text encoders. Omitting it does not
                    // run slower, it fails to allocate. ⚠ It is a property of
                    // the MODEL, so it is read off the catalogue entry beside
                    // the `--type` rather than decided here.
                    if (!upscalerOnly && spec?.lowram == true) add("--lowram")
                    // ⭐ The third launch-bound field of the context key. Absent
                    // for the 512 base (`unet.bin` already IS that graph) and
                    // for every family that ships no patches; a patch that was
                    // wanted and absent never reaches here -- it was refused
                    // above.
                    if (!upscalerOnly && patch != null) { add("--patch"); add(patch.absolutePath) }
                }
                val env = mapOf(
                    "LD_LIBRARY_PATH" to listOf(
                        runtime.absolutePath,
                        "/system/lib64",
                        "/vendor/lib64",
                        "/vendor/lib64/egl",
                    ).joinToString(":"),
                    "DSP_LIBRARY_PATH" to runtime.absolutePath,
                )

                say("exec: ${cmd.joinToString(" ")}")
                val p = ProcessBuilder(cmd).apply {
                    directory(File(nativeDir))
                    redirectErrorStream(true)
                    environment().putAll(env)
                }.start()
                process = p
                // ⚠ Recorded from the values actually placed on the command
                // line, not from `SelectedModel` -- the caller may have passed
                // a graph's own key, and a launch key read back off a global is
                // a launch key that can lie.
                // ⚠ Null for an upscale-only process: it holds no checkpoint, so
                // there is no context key and nothing for the readout to name.
                launchedKey = if (upscalerOnly) null else ContextKey(
                    ModelCatalog.backendTypeOf(modelId), modelId, res.width, res.height,
                )
                upscalerServer = upscalerOnly
                monitor(p)
                Start.Ok
            } catch (e: Exception) {
                Log.e(TAG, "start failed", e)
                Start.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }

    /**
     * Drains the child's stdout.
     *
     * ⚠ Not optional. A process whose output nobody reads will block on a full
     * pipe buffer and appear to hang, which reads as "the NPU is slow" rather
     * than "nobody is listening". It is also the only place the QNN errors go.
     */
    private fun monitor(p: Process) {
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { say(it) }
            } catch (_: Exception) {
                // Stream closes when the process dies; that is not an error.
            }
            // ⚠⚠ waitFor(), NOT exitValue(). `destroy()` closes the child's
            // stdout at once but the process is still dying, so exitValue()
            // throws IllegalThreadStateException("process hasn't exited") --
            // on a bare Thread with no handler, which kills the WHOLE APP.
            //
            // MEASURED 2026-09-08, by the first in-app restart this project
            // ever performed (the executor's `graph` pass D): the harness
            // died mid-op and Android relaunched it with the task's original
            // intent, so the log showed a completed op followed by "intent op:
            // start" from a new pid -- which reads as the restart having
            // worked, not as a crash. Nothing before pass D had ever called
            // stop() on a live backend, so the bug shipped in 0.5.
            val code = try { p.waitFor() } catch (e: Exception) { -1 }
            // ⚠ A crashed backend has no launch key. Leaving the last one set
            // would make [ensureBackend] believe the right process is up and
            // skip the relaunch that is the whole point of recording it.
            launchedKey = null
            upscalerServer = false
            say("[exited $code]")
        }.apply { isDaemon = true; name = "backend-monitor" }.start()
    }

    fun stop() {
        process?.let {
            say("[stopping]")
            it.destroy()
        }
        process = null
        launchedKey = null
        upscalerServer = false
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        synchronized(output) {
            output.addFirst(line)
            while (output.size > 400) output.removeLast()
        }
    }
}
