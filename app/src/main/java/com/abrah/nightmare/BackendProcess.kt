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

    /** ⭐ The DiT engine the backend dlopens for FLUX.2 / Z-Image (upstream local-dream 3.0). */
    const val DIT_ENGINE = "libdit_engine.so"
    /** ⭐ Where its Hexagon skels ride in the APK — copied into the runtime dir. */
    const val DIT_ASSETS = "ditlibs"
    private const val RUNTIME_DIR = "qnnruntime"

    @Volatile
    private var process: Process? = null

    /**
     * ⚠ Set from [start]'s `context`, read by [stop] and the monitor thread —
     * both need one to drive [BackendKeepAliveService] and neither is handed
     * one directly. `applicationContext`, so it outlives whichever Activity
     * happened to call [start].
     */
    @Volatile
    private var appContext: Context? = null

    /** Newest-first, same convention as the harness log. */
    val output = ArrayDeque<String>()

    /**
     * ⭐⭐⭐ **Why the backend gave up, in one line a person can act on.**
     *
     * ⚠⚠⚠ Reported 2026-09-21: importing a community FLUX checkpoint failed
     * with *"the backend would not start — see Settings > Diagnostics"*, and
     * **Settings has had no Diagnostics section since 2026-09-19**. Worse than a
     * stale pointer: [HarnessOps] already `say`s the whole backend log, but
     * `say` writes to the HARNESS log, which lived on exactly the screen that
     * was removed. So the answer existed, in full, and there was no way to read
     * it. The backend had said:
     * `parsing ComfyUI quantization metadata tensor failed:
     * 'model.diffusion_model.double_blocks.0.img_attn.proj.comfy_quant'`
     * — precise, actionable, and invisible.
     *
     * ⭐⭐ **The FIRST error, not the last.** [output] is newest-first, and the
     * last thing a failing launch prints is always the most generic: `engine
     * create failed: new_sd_ctx failed`, then `Pipeline initialization failed!`.
     * The root cause is the FIRST error the process emitted, which is the
     * OLDEST line here. Taking the newest would have reported "new_sd_ctx
     * failed" to a user whose actual problem was a quantisation format.
     *
     * ⚠ Null when nothing looks like an error — the caller then says only that
     * it would not start, rather than inventing a reason.
     */
    fun failureReason(): String? = synchronized(output) {
        val raw = output.lastOrNull {
            it.contains("[ ERROR ]") || it.trimStart().startsWith("ERROR")
        } ?: return null
        // The engine prefixes `   522.3ms [ ERROR ] [dit] model_loader.cpp:270  - `.
        // Everything before the ` - ` is timing and provenance, and none of it
        // means anything to the person reading the chip.
        val afterLevel = raw.substringAfter("[ ERROR ]", raw)
        val message = if (" - " in afterLevel) afterLevel.substringAfter(" - ") else afterLevel
        return message.trim().removePrefix("ERROR:").trim().takeIf { it.isNotBlank() }
    }

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
     * ⚠ Re-copies rather than skipping when the directory exists. A stale
     * runtime dir after a backend upgrade is a genuinely nasty failure: the
     * mismatch shows up as a QNN context that refuses to load, pointing at the
     * model rather than at the libs. 33 MB of copying is cheap next to that.
     *
     * ⚠⚠⚠ **But it copies through a temp file and a rename, and it does the
     * work ONCE PER PROCESS — because these same libraries are `dlopen`'d into
     * THIS process by `libnmqnn.so`, and rewriting a mapped `.so` in place is
     * fatal.** `FileOutputStream` opens with `O_TRUNC`, and truncating a file
     * makes the kernel zap every page of every mapping of it — COW'd pages
     * included. That throws away the linker's load-bias fixups in
     * `.got.plt`, so the next call into the library reads a slot holding
     * PLT0's *link-time* address and branches into an unmapped page:
     *
     *     signal 11 (SIGSEGV), SEGV_MAPERR, fault addr 0x3d37b0 (== .plt)
     *     x16 = base+0x3e8ff0 (inside .got.plt)   x17 = 0x3d37b0
     *     #01 libQnnSystem.so   #03 NativeQnn_load
     *
     * ⚠⚠ That was the "a context binary can only be loaded once per process"
     * blocker, and it was never about QNN, deserialisation or memory: it is
     * this function, called a second time by the second `QnnRunner`. Whichever
     * of the five libs is called into first after the rewrite is the one that
     * appears to crash, which is why the fault moved between `libQnnSystem`
     * and `libQnnHtp` and looked like two bugs. `docs/NEODRAGON.md` §5b.
     *
     * ⇒ **Never truncate a file this process may have mapped.** A rename swaps
     * the directory entry and leaves the old inode intact for whoever has it
     * open, which is exactly the semantics needed here.
     */
    @Volatile
    private var runtimeUnpacked: File? = null

    /**
     * ⭐ Where the QNN runtime, the DiT skels and the downloaded DiT engine all
     * live: INTERNAL storage, which is the only writable place a `.so` can be
     * mapped `PROT_EXEC` from. ⚠ Separate from [prepareRuntime] because
     * [DitEngine] writes here before any backend has launched, and unpacking
     * 33 MB of QNN libraries is not what an installer wants.
     */
    fun runtimeDir(context: Context): File = File(context.filesDir, RUNTIME_DIR)

    @Synchronized
    fun prepareRuntime(context: Context): File {
        runtimeUnpacked?.let { return it }
        val dir = runtimeDir(context).apply { mkdirs() }
        val all = context.assets.list("qnnlibs").orEmpty().toList()
        check(all.isNotEmpty()) {
            "no qnnlibs in assets — run tools/stage_backend.ps1 before building"
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
            val tmp = File(dir, "$n.tmp")
            context.assets.open("qnnlibs/$n").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            // ⚠ `File.renameTo` is `rename(2)` here -- same directory, same
            // filesystem -- so it is atomic and never truncates `dst`.
            check(tmp.renameTo(dst)) { "cannot replace ${dst.absolutePath}" }
        }
        // ⭐ The DiT engine's Hexagon skels (`libggml-htp-v79/v81.so`), beside
        // the QNN ones on the DSP search path — upstream's `ditlibs`. FastRPC
        // hands them to the DSP by bare name; nothing on the CPU loads them.
        // ⚠ Absent from assets is not an error: a build staged without the
        // engine simply cannot run a DiT model, and says so at launch.
        for (n in context.assets.list(DIT_ASSETS).orEmpty()) {
            val dst = File(dir, n)
            val tmp = File(dir, "$n.tmp")
            context.assets.open("$DIT_ASSETS/$n").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            check(tmp.renameTo(dst)) { "cannot replace ${dst.absolutePath}" }
        }
        Log.i(TAG, "runtime: ${names.size}/${all.size} libs (${DeviceProbe.caps()}) in ${dir.absolutePath}")
        runtimeUnpacked = dir
        return dir
    }

    /** Where a model must live for the app to reach it. */
    fun modelsDir(context: Context): File =
        File(ModelStorage.root(context), "models")

    /**
     * ⭐ The diagnostic override in `Download/nightmare-spillfill.txt` — a
     * plain integer (bytes), or null when the file is absent or unreadable
     * as one. See the note where it is read, in [start]'s `env`.
     */
    private fun readSpillFillOverride(context: Context): String? = runCatching {
        File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS,
            ),
            "nightmare-spillfill.txt",
        ).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.toLongOrNull() != null }
    }.getOrNull()

    /**
     * ⭐⭐ **DIAGNOSTIC: the DiT device spec**, from
     * `Download/nightmare-dit-backend.txt` — e.g.
     * `diffusion=CPU,te=CPU,vae=CPU`. Absent (the normal case) leaves the
     * backend's own `diffusion=HTP0,te=HTP0,vae=HTP0`.
     *
     * ⚠⚠ It exists for ONE question that nothing else can ask: a LoRA applied
     * at runtime logs `apply_loras completed` and 160/160 tensors bound, and the
     * picture comes back byte-identical to the one without it. Either the
     * adapter never reaches the HTP-resident weights or something else is wrong,
     * and the only way to tell them apart is to run the SAME build on the CPU
     * path (`docs/ROADMAP.md` §2f).
     *
     * ⚠ Same shape as [readSpillFillOverride] deliberately — a file in
     * Downloads, read at launch, no UI, nothing shipped enabled.
     */
    private fun readDitBackendOverride(context: Context): String? =
        readDiagnosticFile(context, "nightmare-dit-backend.txt")?.takeIf { it.isNotEmpty() }

    /**
     * ⚠⚠⚠ **In the app's OWN external files dir, not `Download/`.** Under
     * scoped storage this app can `stat` a file another app owns in Downloads
     * but cannot READ it — `isFile` says true and `readText` throws, and a
     * `runCatching` around both turns that into a silent null. Measured
     * 2026-09-21: `nightmare-dit-lora-mode.txt` sat in Downloads, the app found
     * it, and the backend never saw the variable. It is the same permission
     * that makes the engine say `cannot register LoRA source` for a LoRA left
     * there.
     *
     * ⚠ `Download/` is still read as a FALLBACK, because that is where the
     * older `nightmare-spillfill.txt` note tells people to put one — it works
     * when adb writes the file as the app's own uid, and costs nothing when it
     * does not.
     */
    private fun readDiagnosticFile(context: Context, name: String): String? {
        val places = listOf(
            File(context.getExternalFilesDir(null), name),
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                ),
                name,
            ),
        )
        for (f in places) {
            val v = runCatching {
                f.takeIf { it.isFile }?.readText()?.trim()
            }.getOrNull()
            if (!v.isNullOrEmpty()) return v
        }
        return null
    }

    /**
     * ⭐⭐ **DIAGNOSTIC: how a LoRA is applied**, from
     * `Download/nightmare-dit-lora-mode.txt` — `1` merges into the weights at
     * load, `2` keeps the branches and applies them per pass. Absent leaves the
     * engine's own choice, which is what ships.
     *
     * ⚠ Same shape as [readSpillFillOverride] and [readDitBackendOverride].
     */
    private fun readDitLoraModeOverride(context: Context): String? =
        readDiagnosticFile(context, "nightmare-dit-lora-mode.txt")
            ?.takeIf { it.toIntOrNull() != null }

    /**
     * ⭐ Where a textual-inversion embedding must live for the backend to
     * find it. `main.cpp` computes this itself as `parent_path().parent_path()`
     * of `--model_dir`, i.e. two directories above `modelsDir/<modelId>/` —
     * which lands here, a sibling of [modelsDir] rather than inside it. One
     * embeddings directory serves every model, loaded fresh at every launch.
     */
    fun embeddingsDir(context: Context): File =
        File(ModelStorage.root(context), "embeddings")

    /**
     * ⭐⭐ Where a LoRA adapter has to live for the engine to load it.
     *
     * ⚠⚠⚠ **Inside the app's own storage, and that is not a preference.**
     * The backend runs as this app's uid, and scoped storage does not let it
     * open a file another uid owns in `Download/` — the engine says so by name:
     * `cannot register LoRA source '/sdcard/Download/…'`. A LoRA a user picked
     * has to be COPIED here before it can be named in a request
     * (`docs/ROADMAP.md` §2f).
     *
     * ⚠ Beside the model directories rather than inside one: an adapter is not
     * a checkpoint, and the same file is usable by every checkpoint of its
     * architecture. ⚠ The leading underscore keeps it out of the way of
     * `CustomModels.scan`, exactly as `_dit_shared` does.
     */
    fun lorasDir(context: Context): File = File(modelsDir(context), "_loras")

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
                        "backend binary missing from $nativeDir — " +
                            "run tools/stage_backend.ps1 and rebuild"
                    )
                }

                val model = File(modelsDir(context), modelId)
                // ⚠ Only an ordinary launch needs a model directory. An
                // upscale-only server has none by definition.
                if (!upscalerOnly && !model.isDirectory) {
                    return@withContext Start.Failed(
                        "no model at ${model.absolutePath} — push one there first"
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
                val dit = !upscalerOnly && spec?.isDit == true
                // ⚠⚠ The engine is DOWNLOADED, not shipped (`DitEngine`), so
                // "missing" is an ordinary state and not a broken build: an
                // app update replaces the native dir, and before 1.5.502 this
                // file lived there. ⭐ The callers ask `DitEngine.isInstalled`
                // first and offer the download (`modelsPresentOrAsk`); this is
                // the backstop for a path that did not, and it names the fix.
                if (dit && !DitEngine.isInstalled(context)) {
                    return@withContext Start.Failed(
                        "the DiT engine ($DIT_ENGINE) is not installed — " +
                            "download it from the model this flow names"
                    )
                }

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
                        "$modelId cannot render $res — ${File(model, name).absolutePath} is missing. " +
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
                    // ⭐ Always the runtime dir. ⚠ It used to be the NATIVE
                    // dir for DiT types (upstream BackendService's shape),
                    // because that is where `libdit_engine.so` shipped; since
                    // 1.5.502 the engine is downloaded into the runtime dir
                    // instead, so one path serves both. ⭐ That also means
                    // `qnn_runtime::init` now finds `libQnnHtp.so` by path in a
                    // DiT process rather than falling back to the bare soname
                    // (`backend-patches/008`) — the fallback stays as a
                    // backstop, but nothing routine depends on it any more.
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
                val env = buildMap {
                    put(
                        "LD_LIBRARY_PATH",
                        listOf(
                            runtime.absolutePath,
                            "/system/lib64",
                            "/vendor/lib64",
                            "/vendor/lib64/egl",
                        ).joinToString(":"),
                    )
                    put("DSP_LIBRARY_PATH", runtime.absolutePath)
                    // ⭐⭐ ggml-hexagon asks FastRPC for its skel by bare name, so
                    // the runtime dir holding the skels AND the platform
                    // defaults must both be on the DSP search path — dropping
                    // the defaults leaves the skel unable to resolve what it
                    // links against. Upstream's exact list.
                    if (dit) {
                        // ⚠ Diagnostic only, and absent on every ordinary launch.
                        readDitBackendOverride(context)?.let { put("NM_DIT_BACKEND", it) }
                        readDitLoraModeOverride(context)?.let { put("NM_DIT_LORA_MODE", it) }
                        val dsp = listOf(
                            runtime.absolutePath, "/vendor/lib/rfsa/adsp", "/vendor/dsp/cdsp", "/dsp",
                        ).joinToString(";")
                        put("ADSP_LIBRARY_PATH", dsp)
                        put("DSP_LIBRARY_PATH", dsp)
                    }
                    // ⭐ A device-side diagnostic knob, reachable over adb with
                    // no rebuild: `PipelineAnima.hpp`'s lowram path
                    // (`loadUnetPartsIfNeeded`, the ONLY path this app ever
                    // takes for Anima) shares a spill-fill buffer between
                    // `unet_part1`/`unet_part2` sized from a HARDCODED constant
                    // tuned on this project's own v79 device — `spillFillGroupBytes()`
                    // already reads `LOCALDREAM_ANIMA_SPILL_FILL_BYTES` as an
                    // override, unconditionally, but nothing ever SET it.
                    // Reported 2026-09-18 (GitHub #2): `unet_part2` fails to
                    // init on a v75 (SM8650) device -- plausibly because that
                    // arch needs a different size. `adb shell "echo
                    // <bytes> > /sdcard/Download/nightmare-spillfill.txt"`
                    // lets someone try a different value with no APK change;
                    // absent, this is a no-op and nothing here changes.
                    readSpillFillOverride(context)?.let {
                        put("LOCALDREAM_ANIMA_SPILL_FILL_BYTES", it)
                    }
                }

                say("exec: ${cmd.joinToString(" ")}")
                env["LOCALDREAM_ANIMA_SPILL_FILL_BYTES"]?.let {
                    say("spill-fill override from Download/nightmare-spillfill.txt: $it bytes")
                }
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
                appContext = context.applicationContext
                // ⭐⭐ Hold the process priority up for as long as this backend
                // is resident — not just while a render is in flight. See
                // [BackendKeepAliveService].
                BackendKeepAliveService.start(context.applicationContext)
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
            // ⚠⚠⚠ **But only if THIS is still the process.** The monitor of a
            // process [stop] killed fires AFTER the relaunch that replaced it,
            // and clearing then wiped the NEW process's key — so every later
            // Run saw "a backend this app did not start", killed it and
            // started again, twice per Run. Measured on the phone 2026-09-16:
            // `[exited 143]` logged 60 ms after the new `exec:`. It only showed
            // when a NODE switched checkpoint, because that is the one path
            // where stop and start are milliseconds apart; the Models tab stops
            // the backend seconds before the next Run launches.
            synchronized(this@BackendProcess) {
                if (process === p) {
                    process = null
                    launchedKey = null
                    upscalerServer = false
                    // ⚠ Same guard as the rest of this block: only when THIS
                    // is still the live process. A relaunch already replaced
                    // it and already re-started the keep-alive service for
                    // the new one -- stopping it here would drop the priority
                    // out from under a process that is still running.
                    appContext?.let { BackendKeepAliveService.stop(it) }
                }
            }
            say("[exited $code]")
        }.apply { isDaemon = true; name = "backend-monitor" }.start()
    }

    fun stop() {
        val p = synchronized(this) {
            val was = process
            process = null
            launchedKey = null
            upscalerServer = false
            was
        }
        appContext?.let { BackendKeepAliveService.stop(it) }
        p?.let {
            say("[stopping]")
            it.destroy()
            // ⚠ Wait for it to be GONE before anyone relaunches: until then it
            // still holds the port, and a `/health` probe could be answered by
            // the process being killed. Bounded, and escalated, so a wedged
            // backend cannot hang the caller.
            if (!it.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                it.destroyForcibly()
                it.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        synchronized(output) {
            output.addFirst(line)
            while (output.size > 400) output.removeLast()
        }
    }
}
