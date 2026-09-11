package com.abrah.nightmare

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Every harness op, with no UI attached.
 *
 * ⚠⚠ The op bodies live HERE, not in the view model, because they must be
 * runnable with nothing on screen. `am start --es op …` works but brings the app
 * to the FOREGROUND, over whatever the person holding the phone was doing --
 * and this phone is someone's actual phone. [OpService] runs the identical code
 * from a background service, so a test costs the user nothing.
 *
 * ⇒ One copy, two front ends. A service that reimplemented these would drift
 * from the buttons and the two would stop being comparable, which is exactly
 * what makes a device report unattributable.
 */
class HarnessOps(private val ctx: Context, private val sink: Sink) {

    /**
     * Where an op's output goes. Only [say] must be implemented -- a headless
     * caller has no image to show and no progress bar to move, and forcing it
     * to write empty overrides would be noise.
     */
    interface Sink {
        fun say(text: String, bad: Boolean = false)
        fun image(bitmap: Bitmap) {}
        fun progress(stepOfTotal: Pair<Int, Int>?) {}
        fun backend(state: BackendState) {}
    }

    private fun say(text: String, bad: Boolean = false) = sink.say(text, bad)

    /** Bumped per decode so successive runs differ, and visibly so. */
    private var seed = 42

    /**
     * ⚠ FIXED, unlike [seed]. sample() is content-addressed on the seed, so a
     * bumping one would return a new handle every run and the latent cache
     * could never be seen to work. tools/sample_stream.sh pins 42 too, so the
     * app and the shell client are comparable.
     */
    private val sampleSeed = 42

    /** ⚠ One prompt for the preview measurement, so the two runs differ only in previews. */
    private val PREVIEW_PROMPT = "a cat on grass"
    private val PREVIEW_NEG = "blurry, lowres"

    /**
     * Where app-side pixels live, shared by the executor and by the plugin
     * host. ⚠ ONE store: a plugin returns an image id and the executor has to
     * be able to resolve it, so two stores would fail with "unknown handle" on
     * the first Tier 0 node.
     */
    /** ⚠ Exposed so the canvas can draw node previews from the same store the
     * executor fills; a second store would show stale pictures. */
    val images = ImageStore()

    /**
     * ⚠ Lazy, because constructing it loads libnmjs.so and creates a QuickJS
     * runtime. Doing that when the harness object is built would mean every app
     * start paid for a plugin engine it may never use -- and would turn a
     * missing native library into a crash at launch rather than a legible
     * failure in the op that needed it.
     */
    private val plugins by lazy { PluginHost(images, BackendLatents) }

    /**
     * The bridge from a synchronous host op to a suspending HTTP call.
     *
     * ⚠⚠ `runBlocking`, deliberately and with its eyes open. `host.call` cannot
     * be async (see [LatentOps]), so SOMETHING has to block; doing it here keeps
     * the blocking on the node's own thread, which the executor already owns
     * and runs off the main thread. ⚠ It would be wrong the moment a host op
     * were called from the main thread — which is why nothing may call
     * [HarnessOps] from there.
     */
    private object BackendLatents : LatentOps {
        override fun blend(a: String, b: String, maskPng: ByteArray): String =
            kotlinx.coroutines.runBlocking {
                when (val r = Ops.latentBlend(a, b, maskPng)) {
                    is Ops.Result.Ok -> r.value.handle
                    // ⚠ The backend's own words, not a tidy summary: its error
                    // bodies name the actual problem, and the plugin author is
                    // the one who has to act on it.
                    is Ops.Result.Err -> throw IllegalStateException(
                        "latent.blend failed http ${r.code}: ${r.body.take(160)}"
                    )
                }
            }
    }

    /**
     * ⚠ One instance, held for this object's life, because the CACHE IS ITS
     * STATE. A per-run executor would be correct and would never skip
     * anything, which is the same as not having built it.
     *
     * ⚠ Also lazy, and it must stay that way: it takes [PluginHost.types] by
     * reference so a plugin loaded later shows up without rebuilding the
     * executor -- and building it eagerly would construct the JS runtime at app
     * start through that reference.
     */
    private val executor by lazy {
        // ⚠ The Context is for OutputNode, which writes to MediaStore. Nothing
        // else in the executor touches the platform, and the parameter is
        // nullable so the JVM tests can build one without Android.
        Executor(images = images, types = plugins.types, android = ctx)
    }

    /**
     * Dispatch for an op named by intent, so the whole harness is drivable over
     * adb.
     *
     * ⚠ An unknown name LOGS rather than being ignored. A scripted op that
     * silently does nothing is indistinguishable from one that ran and failed,
     * and this project has already lost a session to a check that reported the
     * opposite of the truth.
     */
    suspend fun run(op: String, arg: String? = null) {
        say("intent op: $op" + (arg?.let { " $it" } ?: ""))
        when (op) {
            "start" -> launchBackend()
            "stop" -> stopBackend()
            "health" -> health()
            "encode_text" -> encodeText()
            "vae_decode" -> vaeDecode()
            "sample" -> sample()
            "graph" -> runGraph()
            "js" -> jsSmoke()
            "plugin" -> pluginGraph()
            "plugin_dir" -> pluginsFromDisk()
            "plugin_zip" -> installZips()
            "preview" -> previewCost()
            "canvas_run" -> canvasRun()
            "workflow_io" -> workflowRoundTrip()
            "save_image" -> saveImage()
            "vae_roundtrip" -> vaeRoundTrip()
            "img2img" -> img2img()
            "graph_img2img" -> graphImg2Img()
            "cond_wire" -> condWire()
            "load_image" -> loadImageGraph()
            // ⭐ Drivable headlessly, because a download that fails silently on
            // a tab is a download nobody can debug. `--es arg upscaler_anime`.
            "upscalers" -> listUpscalers()
            "upscaler_install" -> installUpscaler(arg)
            "models" -> listModels()
            "model_install" -> installModel(arg)
            "model_delete" -> deleteModel(arg)
            "model_use" -> useModel(arg)
            "resolutions" -> listResolutions()
            "res_use" -> useResolution(arg)
            "aspect" -> aspectProbe(arg)
            "model_scan" -> scanModels()
            "model_import" -> importModels()
            "latent_blend" -> latentBlend()
            "plugin_latent" -> pluginLatentGraph()
            else -> say("unknown intent op \"$op\"", bad = true)
        }
    }

    // ---- models ----------------------------------------------------------

    /**
     * The context-key params every backend node in a harness FIXTURE carries.
     *
     * ⚠⚠ These were `"width" to "512", "height" to "512"` written out eleven
     * times, which was correct while every model was SD 1.5 and became a trap
     * the moment SDXL landed: `--type sdxl` forces 1024 inside the backend's
     * request parser whatever the client sends, so a fixture asking for 512
     * samples at 1024 and then asks `/vae_decode` for a 512 picture out of a
     * 1024 latent. The op does not report "the fixture is wrong"; it reports a
     * size error, or decodes plausible garbage, and both read as a bug in
     * whatever the op was actually testing.
     *
     * ⇒ The size comes from the model, exactly as it does for a real node.
     */
    private fun ctxKey(extra: Map<String, String> = emptyMap()): Map<String, String> {
        // ⚠ The SELECTED size, not the model's native one. Once resolution is a
        // live knob those differ, and a fixture pinned to native would launch
        // the backend at the user's size and then ask it for the model's -- the
        // exact mismatch this function was written to stop.
        val res = SelectedModel.res
        return mapOf(
            "model" to SelectedModel.id,
            "width" to res.width.toString(),
            "height" to res.height.toString(),
        ) + extra
    }

    /**
     * ⚠ Deliberately FEWER steps than a real render (the node default is 20).
     * These ops answer "did the pipeline run, cache and return the right
     * shapes", never "is the picture good" -- and a diagnostic that takes three
     * times as long to prove the same thing is a worse diagnostic.
     *
     * ⚠⚠ So do NOT read a harness render as a quality sample. At 8 steps SDXL
     * produces rainbow speckle, which is undercooking rather than breakage:
     * measured on device 2026-09-09, 8/20/30 steps at one seed.
     */
    private val FIXTURE_STEPS = "8"

    /**
     * ⭐ Re-reads the models directory for imported checkpoints.
     *
     * ⚠ The headless counterpart of opening the Models tab. A directory pushed
     * over adb while the app was already running is invisible until something
     * scans, and `--es op models` alone would report it absent — which reads as
     * a broken push rather than a stale catalogue.
     */
    fun scanModels() {
        val found = CustomModels.scan(ctx)
        if (found.isEmpty()) {
            say("no imported models in ${ModelCatalog.root(ctx)}")
            return
        }
        say("imported models: ${found.size}")
        for (spec in found) {
            val missing = spec.missing(ctx)
            // ⚠ Says the family it was INFERRED as. That is the one thing worth
            // checking by eye after an import: everything downstream -- the
            // backend `--type`, the render size, the context key -- follows from
            // it, and a wrong answer here is the silent-wrong-size failure.
            // ⚠⚠ The SCHEDULER is on this line because it is invisible
            // everywhere else and it decides what the pictures look like. A
            // distilled checkpoint rendered under the backend's `dpm` default
            // is coherent and *bad*, which reads as a poor conversion rather
            // than as a setting -- measured 2026-09-10. `config.json` is the
            // only thing that can change it and this is the only place that
            // says whether it was read.
            say(
                "  ${spec.id.padEnd(20)} ${spec.family.label} ${spec.native} " +
                    "sched=${spec.scheduler}  " +
                    if (missing.isEmpty()) {
                        "ok  ${spec.bytesOnDisk(ctx) shr 20} MB"
                    } else {
                        "INCOMPLETE -- missing ${missing.joinToString()}"
                    },
            )
            if (spec.label != spec.id) say("       label \"${spec.label}\" (from config.json)")
        }
    }

    /**
     * ⭐ Installs every zip in `files/models-inbox/`.
     *
     * ⚠ Blocking and gigabytes, exactly like [installModel] -- the service
     * holds the op for its whole duration because the harness has no other way
     * to keep the process alive.
     *
     * ⚠⚠ Deliberately the inbox pattern rather than a path argument: it is the
     * same shape as `plugins-inbox` (`notes/HANDOFF.md` §5), it needs no SAF
     * picker and no screen, and the app can read it without the storage
     * permissions an arbitrary path would need.
     */
    suspend fun importModels() {
        val inbox = CustomModels.inbox(ctx)
        say("importing from $inbox")
        val lines = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            CustomModels.importInbox(ctx)
        }
        if (lines.isEmpty()) {
            // ⚠ Names the directory and the extension. The likely mistakes are
            // pushing to the models dir instead, and pushing an unzipped tree.
            say("nothing to import -- put a .zip in $inbox", bad = true)
            return
        }
        for (line in lines) say("  $line", bad = line.startsWith("FAIL"))
        listModels()
    }

    /** What the catalog holds, and what is actually on disk. */
    fun listModels() {
        say("selected: ${SelectedModel.id}")
        for (spec in ModelCatalog.all) {
            val missing = spec.missing(ctx)
            val state = when {
                missing.isEmpty() -> "installed  ${spec.bytesOnDisk(ctx) shr 20} MB"
                // ⚠ The SPEC's list: SD 1.5 wants 7 files, SDXL 11.
                missing.size == spec.requiredFiles.size -> "absent"
                // ⚠ A DISTINCT state, and the one worth naming: a directory
                // holding some of the files reads as "installed" to anything
                // that only checks the directory exists, and then fails at
                // launch with a path.
                else -> "PARTIAL -- missing ${missing.joinToString()}"
            }
            // ⚠ Wide enough for the longest id in the catalogue --
            // `sdxl_cyberrealistic` is 19 characters, and a column that only
            // fits SD 1.5's names makes every SDXL row ragged. ⚠ An imported id
            // is the user's directory name and can be any length; it ruins the
            // column rather than the meaning, so it is not worth padding for.
            //
            // ⚠⚠ Imported rows are TAGGED. Their family and resolution were
            // inferred from the files rather than published by us, so a reader
            // comparing this list against the catalogue needs to know which
            // rows are claims and which are facts.
            val tag = if (spec.isCustom) "  [imported ${spec.family.label}]" else ""
            say("  ${spec.id.padEnd(20)} ${state}$tag")
        }
    }

    /**
     * ⚠ Blocking, and it is a ~1 GB download. The service holds the op for its
     * whole duration by design -- the harness has no other way to keep the
     * process alive, and a detached install would be killed with the app.
     */
    suspend fun installModel(id: String?) {
        val spec = ModelCatalog.byId(id ?: "")
        if (spec == null) {
            say("model_install needs --es arg <id>; have ${ModelCatalog.all.joinToString { it.id }}", bad = true)
            return
        }
        if (spec.installed(ctx)) {
            say("${spec.id} is already installed")
            return
        }
        val build = spec.buildFor(DeviceProbe.caps())
        if (build == null) {
            say("${spec.id} has no build this device can load " +
                "(arch ${DeviceProbe.caps().arch}, needs ${spec.builds.minOf { it.minArch }})", bad = true)
            return
        }
        say("installing ${spec.label} ${build.tier} (${build.bytes shr 20} MB)")
        val t0 = System.nanoTime()
        var lastPct = -1
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ModelInstaller.install(ctx, spec, build, onProgress = { p ->
                    // ⚠ Logged per 10%, not per megabyte: logcat drops lines
                    // under flood, and the dropped ones are the interesting end.
                    val pct = (p.fraction * 100).toInt() / 10 * 10
                    if (p.total > 0 && pct != lastPct) {
                        lastPct = pct
                        say("  ${p.phase}: $pct%")
                    }
                })
            }
            val secs = (System.nanoTime() - t0) / 1_000_000_000
            say("  ok   ${spec.id} installed in ${secs}s, ${spec.bytesOnDisk(ctx) shr 20} MB on disk")
        } catch (e: Exception) {
            say("  install FAILED -- ${e.message}", bad = true)
        }
    }

    fun deleteModel(id: String?) {
        val spec = ModelCatalog.byId(id ?: "")
        if (spec == null) { say("model_delete needs --es arg <id>", bad = true); return }
        try {
            ModelInstaller.delete(ctx, spec)
            say("deleted ${spec.id}")
        } catch (e: Exception) {
            say("delete refused -- ${e.message}", bad = true)
        }
    }

    suspend fun useModel(id: String?) {
        val spec = ModelCatalog.byId(id ?: "")
        if (spec == null) { say("model_use needs --es arg <id>", bad = true); return }
        if (!spec.installed(ctx)) {
            say("${spec.id} is not installed -- missing ${spec.missing(ctx).joinToString()}", bad = true)
            return
        }
        val was = SelectedModel.id
        SelectedModel.set(ctx, spec.id)
        say("selected ${spec.id}")
        // ⚠⚠ A running backend was launched with `--model_dir` for the OLD
        // model and does not reload. Leaving it up renders every later graph
        // against the model just switched AWAY from -- silently, because a
        // node's `model` param only feeds the context key and is never compared
        // with what the server is actually holding.
        // ⚠ The UI path does this too (`HarnessViewModel.selectModel`); the two
        // front ends share this file precisely so they cannot drift, and this
        // op having omitted it was that drift.
        if (was != spec.id && Backend.get("/health").code == 200) {
            say("  stopping the backend -- it was launched for $was")
            stopBackend()
        }
        // ⚠ It does NOT retarget the canvas, where `HarnessViewModel.selectModel`
        // does. Not drift: this op has no canvas -- it runs in a service with no
        // window, and the open graph is a view-model field. The divergence it
        // leaves is self-healing, because the next restore adopts the model the
        // GRAPH names (`adoptGraphModel`) rather than the one this set.
    }

    /**
     * ⭐ What sizes the selected model can serve, and which one is chosen.
     *
     * ⚠ Discovered from the patch files on disk
     * ([ModelSpec.availableResolutions]), so this op is also how you find out
     * that an archive shipped fewer patches than expected -- the row simply is
     * not there, and nothing else would say so.
     */
    fun listResolutions() {
        val spec = SelectedModel.spec
        if (spec.fixedCanvas) {
            say("${spec.label} renders a fixed ${spec.native} and crops to shape:")
            for (a in ModelCatalog.ASPECTS) {
                val t = ModelCatalog.aspectTarget(a, spec.native) ?: spec.native
                say("  ${a.padEnd(5)} $t")
            }
            return
        }
        val all = spec.availableResolutions(ctx)
        say("${spec.label}: ${all.size} resolution${if (all.size == 1) "" else "s"}")
        for (r in all) {
            val patch = r.patchName?.let { n ->
                if (java.io.File(spec.dir(ctx), n).exists()) n else "$n MISSING"
            } ?: "base unet.bin"
            say("  ${if (r == SelectedModel.res) "*" else " "} ${r.toString().padEnd(9)} $patch")
        }
    }

    /**
     * ⚠ Takes `WxH` — `--es arg 768x512`. It sets the selection and stops a
     * backend launched for another size, exactly as [useModel] does for a
     * model; the size is the same kind of launch-bound field.
     */
    suspend fun useResolution(arg: String?) {
        val want = arg?.let { Res.fromLabel(it) }
        if (want == null) { say("res_use needs --es arg <WxH>, e.g. 768x512", bad = true); return }
        val spec = SelectedModel.spec
        val ok = spec.availableResolutions(ctx)
        if (want !in ok) {
            say("${spec.label} cannot render $want -- it serves ${ok.joinToString(", ")}", bad = true)
            return
        }
        val was = SelectedModel.res
        SelectedModel.setRes(ctx, want)
        say("resolution $want")
        // ⚠ Same reasoning as [useModel]: `--patch` binds at launch, so a
        // process started at the old size will not reload into the new one.
        if (was != want && Backend.get("/health").code == 200) {
            say("  stopping the backend -- it was launched at $was")
            stopBackend()
        }
    }

    suspend fun health() {
        val r = Backend.get("/health")
        when {
            r.code == 200 -> {
                sink.backend(BackendState.UP)
                say("/health 200 in ${r.millis} ms")
            }
            r.code == -1 -> {
                sink.backend(BackendState.DOWN)
                say("/health unreachable after ${r.millis} ms -- ${r.body}", bad = true)
                say("  no backend on :${Backend.PORT}. Stage and launch one first.", bad = true)
            }
            else -> {
                // ⚠ A reachable server answering non-200 is a DIFFERENT finding
                // from an absent one, and collapsing them hides which it was.
                sink.backend(BackendState.UP)
                say("/health ${r.code} in ${r.millis} ms -- reachable but unhappy", bad = true)
            }
        }
    }

    suspend fun encodeText() {
        when (val r = Ops.encodeText("a cat on grass", "blurry, lowres")) {
            is Ops.Result.Ok -> {
                val c = r.value
                sink.backend(BackendState.UP)
                say("encode_text ${c.serverMs} ms (wire ${c.wireMs} ms)")
                say("  ${c.handle}  ${c.seqLen}x${c.hiddenDim}")
                // ⚠ Both hashes, because they answer different questions: neg is
                // constant across these calls, pos is not. Showing one would
                // make an unchanged encoder indistinguishable from a working
                // one -- which is exactly how this check was wrong twice on the
                // backend side (backend-patches/README.md).
                say("  neg ${c.negHash}  pos ${c.posHash}")
            }
            is Ops.Result.Err -> {
                say("encode_text FAILED http ${r.code}", bad = true)
                say("  ${r.body.take(180)}", bad = true)
            }
        }
    }

    suspend fun vaeDecode() {
        val s = seed++
        when (val r = Ops.vaeDecode(seed = s)) {
            is Ops.Result.Ok -> {
                val d = r.value
                sink.backend(BackendState.UP)
                val bmp = BitmapFactory.decodeByteArray(d.png, 0, d.png.size)
                if (bmp == null) {
                    // ⚠ A PNG that will not decode is a real outcome, not a
                    // reason to show nothing. Silence here would look identical
                    // to a backend that never answered.
                    say("vae_decode returned ${d.png.size} B that BitmapFactory " +
                        "refused to decode", bad = true)
                } else {
                    sink.image(bmp)
                    say("vae_decode seed $s -- ${d.serverMs} ms (wire ${d.wireMs} ms)")
                    say("  ${bmp.width}x${bmp.height}  ${d.png.size} B  sha ${d.rgbSha}")
                }
            }
            is Ops.Result.Err -> {
                say("vae_decode FAILED http ${r.code}", bad = true)
                say("  ${r.body.take(180)}", bad = true)
            }
        }
    }

    /**
     * sample() -> latent handle -> vae_decode -> pixels, with the sampler's
     * progress arriving live.
     *
     * ⭐ The verdict below is the point of the op. Every frame of a buffered
     * response is byte-identical to a streamed one, so "we got 22 progress
     * events" proves nothing about streaming. What separates them is WHEN the
     * first one landed, and the control is the server's own render time: a
     * first frame at ~5% of it streamed, a first frame at ~100% of it did not.
     */
    suspend fun sample() {
        val steps = 20
        say("sample: $steps steps, seed $sampleSeed -- streaming")
        val s = when (val r = Ops.sample(
            prompt = "a cat on grass",
            negative = "blurry, lowres",
            steps = steps,
            seed = sampleSeed,
            onProgress = { p ->
                // ⚠ Called from the IO dispatcher. Compose snapshot state is
                // safe to write off the main thread; the recomposition it
                // schedules is not this coroutine's problem.
                sink.progress(p.step to p.total)
            },
        )) {
            is Ops.Result.Err -> {
                sink.progress(null)
                say("sample FAILED http ${r.code}", bad = true)
                say("  ${r.body.take(180)}", bad = true)
                return
            }
            is Ops.Result.Ok -> r.value
        }
        sink.progress(null)
        sink.backend(BackendState.UP)
        say("sample ${s.serverMs} ms (wire ${s.wireMs} ms) -- ${s.handle}")
        say("  latent_sha ${s.latentSha}  ${s.progressEvents} progress frames")
        say("  first frame +${s.firstProgressMs} ms, last +${s.lastProgressMs} ms")
        if (s.firstProgressMs in 0 until s.serverMs / 2) {
            say("  STREAMED: progress led the result by ${s.serverMs - s.firstProgressMs} ms")
        } else {
            // Not thrown, because the render itself succeeded. A buffered
            // stream is a transport finding, and calling it a sample failure
            // would send the next session to look in the wrong place.
            say("  BUFFERED? first frame at +${s.firstProgressMs} ms of a " +
                "${s.serverMs} ms render -- the client is not streaming", bad = true)
        }

        // The other half of the graph. A handle that cannot be decoded is a
        // handle that proves nothing, so the op does not stop at the latent.
        when (val d = Ops.vaeDecode(latentHandle = s.handle)) {
            is Ops.Result.Ok -> {
                val bmp = BitmapFactory.decodeByteArray(d.value.png, 0, d.value.png.size)
                if (bmp == null) {
                    say("decode returned ${d.value.png.size} B that BitmapFactory " +
                        "refused", bad = true)
                } else {
                    sink.image(bmp)
                    say("vae_decode ${d.value.serverMs} ms -- ${bmp.width}x${bmp.height} " +
                        "sha ${d.value.rgbSha}")
                    // ⭐ And LOOK at it. graph_smoke.sh exists because a random
                    // latent decodes just as deterministically as a sampled one
                    // (backend-patches/README.md); only the picture tells them
                    // apart, and this puts it on the screen.
                    say("  ^ that image is the check -- a cat, not beige blobs")
                }
            }
            is Ops.Result.Err -> {
                say("vae_decode(handle) FAILED http ${d.code}", bad = true)
                say("  ${d.body.take(180)}", bad = true)
            }
        }
    }

    /**
     * Every node type the canvas can draw, plugins included.
     *
     * ⚠ Touching this builds the plugin host, which creates a QuickJS runtime.
     * That is why the canvas screen is opened deliberately rather than being
     * the app's first screen.
     */
    fun nodeTypes(): Map<String, NodeType> = plugins.types

    /**
     * Run a workflow, reporting each node as it settles.
     *
     * ⭐ The ONE path a graph runs by. The canvas's Run button and the headless
     * `canvas_run` op both call this, so a device report from either is
     * comparable — a screen with its own private run path would drift from the
     * check meant to verify it.
     */
    suspend fun runWorkflow(
        workflow: com.abrah.nightmare.canvas.Workflow,
        onNode: (NodeRun) -> Unit = {},
        onProgress: (String, Int, Int) -> Unit = { _, _, _ -> },
        /** ⭐ Which node is being reached — what the canvas's run log names. */
        onStart: (String, String) -> Unit = { _, _ -> },
    ): GraphRun = runRolled(workflow, onNode, onProgress, onStart)

    /**
     * ⭐ Rolls every `seed = 0` before running, then executes.
     *
     * ⚠⚠ It must happen HERE, before the executor, because the cache key is
     * computed from the node's params. A random seed chosen inside the node's
     * `run` would be invisible to the key, so the executor would serve the
     * CACHED latent and the roll would change nothing -- the exact complaint
     * ("Run gives the same picture") in a subtler form.
     *
     * ⚠ Only `sample`. `vae_encode`'s seed is what makes the same image encode
     * to the same latent, which is what lets everything downstream of it cache;
     * rolling that one would make every img2img graph full price every Run.
     *
     * ⚠ The user's graph is NOT modified -- a rolled copy is run, so the node
     * still reads 0 and still rolls next time.
     */
    private suspend fun runRolled(
        workflow: com.abrah.nightmare.canvas.Workflow,
        onNode: (NodeRun) -> Unit,
        onProgress: (String, Int, Int) -> Unit,
        onStart: (String, String) -> Unit = { _, _ -> },
    ): GraphRun {
        // ⚠⚠ Checked BEFORE the sampling, because a size mismatch renders
        // successfully and looks like a quality problem — see [sizeMismatches].
        for (why in sizeMismatches(workflow.graph, nodeTypes())) {
            say("⚠ size: $why", bad = true)
        }
        val rolled = mutableMapOf<String, Int>()
        val nodes = workflow.graph.nodes.map { n ->
            val wantsRoll = n.type == "sd.sample" &&
                (n.params["seed"] ?: SampleNode.widgets.first { it.name == "seed" }.default).orEmpty()
                    .trim().toIntOrNull() == 0
            if (!wantsRoll) return@map n
            // ⚠ Never 0, or the next run would read it back as "roll again" if
            // this value were ever written into a workflow.
            val seed = java.util.Random().nextInt(Int.MAX_VALUE - 1) + 1
            rolled[n.id] = seed
            n.copy(params = n.params + ("seed" to seed.toString()))
        }
        if (rolled.isNotEmpty()) {
            say("rolled seed: " + rolled.entries.joinToString { "${it.key}=${it.value}" })
        }
        return executor.run(
            Graph(nodes),
            onStart = onStart,
            onProgress = { id, step, total ->
                sink.progress(step to total)
                onProgress(id, step, total)
            },
            // ⚠ The rolled seed is shown on the node, or a user watching a
            // picture change every Run has no way to learn WHICH seed made the
            // one they liked.
            // ⚠⚠ A sampler's line names the SETTINGS as well as the seed.
            // A seed alone does not reproduce a picture — steps, cfg and the
            // scheduler are equally part of it, and all three can come from the
            // MODEL rather than from anything written in the graph. This line
            // is the only place the four ever appear together.
            onNode = { n ->
                val recipe = if (n.type == "sd.sample") {
                    workflow.graph.byId[n.id]
                        ?.let { runCatching { SampleNode.effectiveParams(it) }.getOrNull() }
                        ?.let { p ->
                            // ⚠⚠ The EFFECTIVE step count when a latent is
                            // wired, because `denoise` eats steps
                            // (`start_step = steps * (1 - denoise)`) and the
                            // written number is then not what ran. An inpaint
                            // reading "10st" while executing 7 is how a
                            // structureless render looked like a model problem.
                            val written = p["steps"]?.toIntOrNull() ?: 0
                            val d = p["denoise"]?.toDoubleOrNull() ?: 1.0
                            val onLatent = workflow.graph.byId[n.id]
                                ?.inputs?.containsKey("latent") == true
                            val ran = if (onLatent && d > 0.0 && d < 1.0) {
                                written - (written * (1.0 - d)).toInt()
                            } else {
                                written
                            }
                            val steps = if (ran != written) "${ran}st of $written" else "${written}st"
                            "  $steps cfg ${p["cfg"]} ${p["scheduler"]}"
                        }
                        .orEmpty()
                } else {
                    ""
                }
                val seeded = rolled[n.id]?.let { "seed $it  " }.orEmpty()
                onNode(
                    if (seeded.isEmpty() && recipe.isEmpty()) n
                    else n.copy(detail = "$seeded${n.detail}$recipe")
                )
            },
        ).also { r ->
            sink.progress(null)
            // ⚠⚠ Publish the picture the graph produced.
            //
            // Only `canvasRun()` used to do this, and the canvas's Run button goes
            // through here instead -- so pressing Run rendered correctly, marked
            // every node CACHED, and showed the user nothing at all. Reported from a
            // real phone as "run gives no image"; the render was never the problem.
            //
            // ⚠ The LAST image in execution order, not a node called "decode": a
            // user's graph need not contain one, and naming it here would make the
            // preview work only for the shipped default.
            val out = r.runs.asReversed()
                .firstNotNullOfOrNull { r.outputs[it.id] as? Value.Image }
            out?.let { img -> images.get(img.id)?.let { sink.image(it) } }
        }
    }

    /**
     * ⭐ The canvas's Run, headless.
     *
     * ⚠ It exists so the screen can be verified without taking the screen. It
     * proves the PLUMBING -- default workflow, executor, node statuses, the
     * decoded image -- which is everything about the Run button except the
     * pixels the goldens already pin.
     */
    /**
     * ⚠ Auto-starts the backend, exactly as pressing Run on the canvas does.
     * Keeping that only in the view model made this op test a path no user
     * takes -- the same front-end drift `model_use` had.
     */
    /**
     * ⚠⚠ **`/health` is not the question any more.** It answers 200 from a
     * process launched at ANY resolution, so once `--patch` is on the launch
     * line a healthy backend can still be the wrong one -- and the failure is
     * silent, because `/vae_decode` against graphs of another size decodes
     * plausible garbage rather than erroring.
     *
     * ⇒ Serving AND launched for the key we want. Anything else is a kill and
     * a relaunch, which costs 2.3-5 s (`docs/ARCHITECTURE.md` §4) and is
     * announced rather than silent: it is about a whole render, and a user who
     * is not told will read it as the app having hung.
     */
    suspend fun ensureBackend(
        want: ContextKey? = null,
        /**
         * ⭐⭐ This graph names NO context key, so it needs a server but no
         * checkpoint — an upscale-only flow, or any all-app-side graph that
         * still calls an endpoint.
         *
         * ⚠⚠ Launching the ordinary way for one of those kept a ~1.2 GB SD
         * pipeline resident for nothing, which is what made the load readout
         * name a model the flow was not using. `--upscale` allocates its own
         * QNN context per request on TOP of whatever is resident, so this is
         * also the memory that an upscale-after-t2i had to find.
         */
        noModel: Boolean = false,
    ): Boolean {
        if (noModel) return ensureUpscaleServer()
        val target = want ?: ContextKey(
            ModelCatalog.backendTypeOf(SelectedModel.id),
            SelectedModel.id,
            SelectedModel.res.width,
            SelectedModel.res.height,
        )
        if (Backend.get("/health").code == 200) {
            val have = BackendProcess.launchedKey
            // ⚠ A null launch key with a live /health is a backend this app did
            // not start -- a leftover from a previous process, or one launched
            // by hand during development. It cannot be verified, so it is
            // replaced rather than trusted.
            if (have == target) return true
            say(
                if (have == null) "the running backend was not started by this app -- relaunching"
                else "the backend is serving $have but this graph needs $target -- relaunching"
            )
            stopBackend()
        }
        val spec = ModelCatalog.byId(target.model)
        if (spec == null || !spec.installed(ctx)) {
            say("no model installed -- open Models and download one", bad = true)
            return false
        }
        say("starting the backend for ${target.model} at ${target.width}x${target.height}…")
        return launchBackend(target)
    }

    suspend fun canvasRun() {
        // ⭐⭐ The user's OWN canvas, not a fixture.
        //
        // ⚠⚠ This ran `defaultWorkflow()` — a hardcoded three-node txt2img —
        // so the headless op tested a graph nobody had, and every "verified on
        // device" claim made through it was about the fixture rather than about
        // what was on screen. A bug reported from the canvas was therefore
        // *unreproducible here by construction*, which is exactly backwards for
        // the one tool meant to reproduce it. Measured 2026-09-10: the autosave
        // held an eight-node inpaint graph while this op reported three.
        //
        // ⚠ Falls back to the default when nothing is saved, so a fresh install
        // still has something to run.
        val wf = runCatching {
            com.abrah.nightmare.canvas.WorkflowStore(java.io.File(ctx.filesDir, "workflows"))
                .load("current")?.workflow
        }.getOrNull() ?: com.abrah.nightmare.canvas.defaultWorkflow()
        say("canvas: ${wf.graph.nodes.size} nodes, " +
            "${nodeTypes().size} types available")
        // ⚠⚠ Launch for the key the GRAPH names, not for the selection. This op
        // reads the autosave straight off disk, so it bypasses the adoption
        // [HarnessViewModel.adoptGraphModel] does when a workflow is opened --
        // a saved graph at 768² would otherwise be run against a backend
        // launched at whatever the picker last said.
        var namesNoKey = false
        val want = try {
            val types = nodeTypes()
            val models = contextKeyModels(wf.graph, types)
            // ⚠⚠ EMPTY is a different answer from "could not resolve one".
            // A graph naming two models also yields a null `want`, and launching
            // a model-free server for THAT would replace a clear refusal
            // ("needs 2 backend contexts") with a confusing one.
            namesNoKey = models.isEmpty()
            val m = models.singleOrNull()
            val res = contextKeyResolutions(wf.graph, types).singleOrNull()
            if (m != null && res != null) {
                ContextKey(ModelCatalog.backendTypeOf(m), m, res.width, res.height)
            } else null
        } catch (e: Throwable) {
            null
        }
        // ⚠ No context key means no checkpoint is needed — an upscale-only or
        // all-app-side graph gets a server with no model loaded.
        if (!ensureBackend(want, noModel = namesNoKey)) return
        val r = runWorkflow(wf, onNode = { n ->
            say("  ${n.id.padEnd(8)} ${n.outcome.name.lowercase().padEnd(7)} " +
                "${n.ms} ms  ${n.detail}", bad = n.outcome == Outcome.FAILED)
        })
        if (r.error != null) {
            say("canvas: refused -- ${r.error}", bad = true)
            return
        }
        say("canvas: ran ${r.ran}, cached ${r.cached}, failed ${r.failed} in ${r.totalMs} ms")

        // ⚠ The terminal node's image is the check. A graph that "completed"
        // while producing nothing is exactly what a status count cannot show.
        val out = r.outputs["decode"] as? Value.Image
        if (out == null) {
            say("canvas: the graph produced no image", bad = true)
            return
        }
        images.get(out.id)?.let { sink.image(it) }
        say("canvas: ${out.w}x${out.h} ${out.id}")
    }

    /**
     * ⭐ The prompt, as a node -- every graph in this file starts with one.
     *
     * ⚠⚠ The sampler has no prompt of its own any more
     * (docs/ARCHITECTURE.md §3), so a text encode is not decoration here: a graph
     * without one is refused by name and every pass below it measures nothing.
     *
     * ⚠ It adds a node to every prediction in this file, and that is worth
     * having. `encode_text` is 129 ms cold and 0-1 ms once the backend has seen
     * the prompt (docs/ARCHITECTURE.md §3), so a pass that did NOT see it cached
     * would be hiding a re-encode nobody asked for.
     */
    private fun textNode(
        id: String = "text",
        prompt: String = "a cat on grass",
        negative: String = "blurry, lowres",
    ) = Node(id, "sd.clip_encode", params = mapOf("prompt" to prompt, "negative" to negative))

    /**
     * ⭐ The last v1 node: a picture from the device, through the graph.
     *
     * ```
     * load_image ──> vae_encode ──> sample(latent) ──> decode
     * ```
     *
     * ⚠ It uses an image THIS APP wrote to the gallery, found through
     * MediaStore — so the op needs no picker and no permission, and still
     * exercises the real `content://` path a picked image takes.
     */
    suspend fun loadImageGraph() {
        val uri = newestSavedImage()
        if (uri == null) {
            say("load_image: nothing in Pictures/${ImageSaver.FOLDER} yet -- run " +
                "`save_image` first", bad = true)
            return
        }
        say("load_image: $uri")

        val g = Graph(
            listOf(
                Node(
                    "src", "image.load",
                    params = mapOf("uri" to uri) + ctxKey().filterKeys { it != "model" },
                ),
                textNode(prompt = "a dog on snow"),
                Node(
                    "enc", "sd.vae_encode",
                    params = ctxKey(mapOf("seed" to "42")),
                    inputs = sources("image" to "src"),
                ),
                Node(
                    "redo", "sd.sample",
                    params = mapOf(
                        "steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to "7",
                        "denoise" to "0.6",
                    ),
                    inputs = sources("cond" to "text", "latent" to "enc"),
                ),
                Node(
                    "out", "sd.vae_decode",
                    params = ctxKey(),
                    inputs = sources("latent" to "redo"),
                ),
            )
        )

        executor.cache.clear()
        val r = pass("A from a real file", g, expectRan = 5, expectCached = 0)
        if (r.failed > 0 || r.error != null) return

        val src = r.outputs["src"] as? Value.Image
        if (src == null || src.w != 512 || src.h != 512) {
            say("  FAIL load_image gave ${src?.w}x${src?.h}, expected 512x512", bad = true)
            return
        }
        say("  ok   loaded and fitted to ${src.w}x${src.h}")

        // ⚠⚠ The second pass is the check with teeth: load_image is NOT
        // cacheable, because the file behind a URI can change while the id does
        // not. It must re-run while the 1.1 s sampler above it does not.
        val again = pass("B not cached", g, expectRan = 1, expectCached = 4)
        val srcRun = again.runs.firstOrNull { it.id == "src" }
        if (srcRun?.outcome == Outcome.RAN) {
            say("  ok   the loader re-read the file while everything downstream stayed cached")
        } else {
            say("  FAIL load_image was ${srcRun?.outcome} -- a file can change behind its " +
                "URI, so it must not be cached", bad = true)
        }

        (r.outputs["out"] as? Value.Image)?.let { img ->
            images.get(img.id)?.let { sink.image(it) }
        }
    }

    /** The most recent image this app put in the gallery, as a content:// URI. */
    private fun newestSavedImage(): String? {
        val uriCol = android.provider.MediaStore.Images.Media._ID
        val where = android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        return ctx.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(uriCol), where, arrayOf("%${ImageSaver.FOLDER}%"),
            android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC",
        )?.use { c ->
            if (!c.moveToFirst()) null
            else android.content.ContentUris.withAppendedId(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                c.getLong(0),
            ).toString()
        }
    }

    /**
     * ⭐⭐ Is a supplied conditioning actually USED?
     *
     * The check is byte-exact rather than visual, and that is the point. Encode
     * "a dog on snow", then ask the sampler for **"a cat on grass"** while
     * handing it the dog's conditioning. If the conditioning is really what
     * reaches the UNet, the result must be **identical to a plain dog render** —
     * same seed, same steps, same everything else — so the two `latent_sha`
     * values must match.
     *
     * ⚠ And the control is in the same pass: it must ALSO differ from a plain
     * cat render. Without that, an op that ignored the conditioning and
     * secretly rendered the dog anyway would be indistinguishable from one that
     * ignored the *prompt* — and only the second is what we want.
     */
    suspend fun condWire() {
        val neg = "blurry, lowres"
        suspend fun render(prompt: String, cond: String? = null) = Ops.sample(
            prompt = prompt, negative = neg, steps = 8, seed = 42, condHandle = cond,
        )

        val dog = when (val r = render("a dog on snow")) {
            is Ops.Result.Err -> { say("cond_wire: dog FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        val cat = when (val r = render("a cat on grass")) {
            is Ops.Result.Err -> { say("cond_wire: cat FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        say("cond_wire: dog ${dog.latentSha}, cat ${cat.latentSha}")
        if (dog.latentSha == cat.latentSha) {
            say("  FAIL the two prompts produced the same latent -- the fixture is " +
                "not distinguishing anything", bad = true)
            return
        }

        val condDog = when (val r = Ops.encodeText("a dog on snow", neg)) {
            is Ops.Result.Err -> { say("cond_wire: encode FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        say("  encoded ${condDog.handle} in ${condDog.serverMs} ms")

        val mixed = when (val r = render("a cat on grass", cond = condDog.handle)) {
            is Ops.Result.Err -> {
                say("cond_wire: mixed FAILED http ${r.code} -- ${r.body.take(160)}", bad = true)
                return
            }
            is Ops.Result.Ok -> r.value
        }
        say("  cat prompt + dog conditioning -> ${mixed.latentSha}")

        when {
            mixed.latentSha == dog.latentSha ->
                say("  ok   identical to the dog render: the conditioning is what reached " +
                    "the UNet, not the prompt")
            mixed.latentSha == cat.latentSha ->
                say("  FAIL identical to the CAT render -- the conditioning handle was " +
                    "ignored", bad = true)
            else ->
                say("  FAIL matched neither render -- something else differs too", bad = true)
        }

        // ⚠ And the handle must still differ from the dog's, because the prompt
        // string is part of the key. Same picture, different request.
        if (mixed.handle != dog.handle) {
            say("  ok   different handle, same latent -- the key covers the prompt as well")
        } else {
            say("  FAIL the two requests collided on one handle", bad = true)
        }
    }

    /**
     * ⭐⭐ The two img2img routes, as NODES rather than as raw ops.
     *
     * | route | graph |
     * |---|---|
     * | latent → latent | `sample → sample(latent)` — no VAE at all |
     * | image → latent | `sample → decode → vae_encode → sample(latent)` |
     *
     * ⚠ The second is what a **Load Image** node will do: pixels have to be
     * encoded. The first is what one sampler feeding another does, and it skips
     * the VAE entirely — which is only possible because both ops agree about
     * what a latent handle means.
     *
     * ⚠⚠ The cheap route must be measurably cheaper. If it is not, the two ops
     * are not actually sharing a latent space and something is silently
     * decoding and re-encoding on the way.
     */
    suspend fun graphImg2Img() {
        // ⚠ Two text nodes, because the two samplers ask for different
        // pictures. They are what carries the prompt now, and route B must
        // find both of them CACHED -- a conditioning that re-encoded between
        // the routes would put 129 ms into the comparison this op exists to
        // make.
        val cat = textNode("text_cat", "a cat on grass")
        val dog = textNode("text_dog", "a dog on snow")
        fun sampler(id: String, seed: Int, cond: String, from: String? = null) = Node(
            id, "sd.sample",
            params = mapOf(
                "steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to seed.toString(),
                "denoise" to "0.6",
            ),
            inputs = sources("cond" to cond) +
                if (from == null) emptyMap() else sources("latent" to from),
        )
        fun decoder(id: String, from: String) = Node(
            id, "sd.vae_decode",
            params = ctxKey(),
            inputs = sources("latent" to from),
        )

        // Route A: latent straight into the next sampler.
        val a = Graph(
            listOf(
                cat, dog,
                sampler("base", 42, "text_cat"),
                sampler("redo", 7, "text_dog", from = "base"),
                decoder("out", "redo"),
            )
        )
        executor.cache.clear()
        val ra = pass("A latent -> latent", a, expectRan = 5, expectCached = 0)
        if (ra.failed > 0 || ra.error != null) return

        // Route B: through pixels, the way a loaded image must go.
        val b = Graph(
            listOf(
                cat, dog,
                sampler("base", 42, "text_cat"),
                decoder("mid", "base"),
                Node(
                    "enc", "sd.vae_encode",
                    params = ctxKey(mapOf("seed" to "42")),
                    inputs = sources("image" to "mid"),
                ),
                sampler("redo", 7, "text_dog", from = "enc"),
                decoder("out", "redo"),
            )
        )
        // ⚠ `base` and both conditionings are already cached from route A,
        // which is the point: the two routes share their expensive half, so
        // what is being compared is the VAE work and nothing else.
        val rb = pass("B image -> latent", b, expectRan = 4, expectCached = 3)
        if (rb.failed > 0 || rb.error != null) return

        val encMs = rb.runs.firstOrNull { it.id == "enc" }?.ms ?: -1
        val midMs = rb.runs.firstOrNull { it.id == "mid" }?.ms ?: -1
        say("  route B paid ${midMs + encMs} ms extra for the VAE " +
            "(decode $midMs + encode $encMs) that route A never touched")
        if (encMs > 0 && midMs > 0) {
            say("  ok   the latent route skips a real cost, not a nominal one")
        } else {
            say("  FAIL the VAE nodes reported no time -- were they cached?", bad = true)
        }

        (rb.outputs["out"] as? Value.Image)?.let { img ->
            images.get(img.id)?.let { sink.image(it) }
            say("  final ${img.w}x${img.h} ${img.id}")
        }
    }

    /**
     * ⭐⭐ img2img, entirely inside the graph: a render fed back into the
     * sampler with a different prompt.
     *
     * ```
     * sample("a cat on grass") ──> latent ──> sample("a dog on snow", denoise) ──> decode
     * ```
     *
     * ⚠ No decode and no encode between them. Both ops speak the same latent
     * space, so the round trip that would cost ~190 ms and 1.4% of the picture
     * is simply not taken — which is the whole reason the two ops were made to
     * agree about what a latent handle means.
     *
     * ⚠⚠ Three denoise strengths in one pass, because a single one proves
     * nothing: the result has to move AWAY from the source as the strength
     * rises. One number on its own is consistent with the input being ignored.
     */
    suspend fun img2img() {
        val base = when (val r = Ops.sample(prompt = "a cat on grass",
            negative = "blurry, lowres", steps = 8, seed = 42)) {
            is Ops.Result.Err -> { say("img2img: base sample FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        val baseImage = decodeToBitmap(base.handle) ?: return
        say("img2img: base ${base.handle}")

        var previous: Double? = null
        for (denoise in listOf(0.3, 0.6, 0.9)) {
            val out = when (val r = Ops.sample(
                prompt = "a dog on snow", negative = "blurry, lowres",
                steps = 8, seed = 7, latentHandle = base.handle, denoise = denoise,
            )) {
                is Ops.Result.Err -> {
                    say("  denoise $denoise FAILED http ${r.code} -- ${r.body.take(160)}",
                        bad = true)
                    return
                }
                is Ops.Result.Ok -> r.value
            }
            val bmp = decodeToBitmap(out.handle) ?: return
            val diff = meanDiff(baseImage, bmp, 0, 0, bmp.width, bmp.height)
            say("  denoise $denoise -> ${out.serverMs} ms, |diff| from base " +
                "${"%.1f".format(diff)}")
            sink.image(bmp)

            // ⚠ Rising, not merely non-zero. A backend that ignored the input
            // latent would give three unrelated renders whose diffs from the
            // base wander; one that ignored `denoise` would give three
            // identical numbers.
            previous?.let {
                if (diff <= it) {
                    say("  FAIL denoise $denoise moved LESS far than the previous " +
                        "strength -- the strength is not being applied", bad = true)
                    return
                }
            }
            previous = diff
        }
        say("  ok   the picture moves further from its source as denoise rises")

        // ⚠ And fewer steps are actually run: denoise 0.3 of 8 steps starts at
        // step 5, so it must be quicker than a full render. A backend that
        // silently ran all 8 would still pass the diff check above.
        say("  (a low denoise should also be quicker -- compare the ms above)")
    }

    /**
     * ⭐ How much does a VAE round trip cost the picture?
     *
     * sample → decode → **encode** → decode. The two decodes are of the same
     * image passed through the VAE one extra time, so the difference between
     * them is the round-trip loss — a number the canvas will need before
     * img2img can be honest about what it does to an image.
     *
     * ⚠ The control is in the same pass: the same two latents decoded twice
     * without an encode between them would differ by zero, and the img2img path
     * is what adds the loss. Quoting a diff with nothing to compare it against
     * would say nothing about the VAE.
     */
    suspend fun vaeRoundTrip() {
        val sampled = when (val r = Ops.sample(prompt = "a cat on grass",
            negative = "blurry, lowres", steps = 8, seed = 42)) {
            is Ops.Result.Err -> { say("vae_roundtrip: sample FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        val first = when (val d = Ops.vaeDecode(latentHandle = sampled.handle)) {
            is Ops.Result.Err -> { say("vae_roundtrip: decode FAILED ${d.code}", bad = true); return }
            is Ops.Result.Ok -> d.value
        }
        say("vae_roundtrip: decoded ${first.png.size} B, sha ${first.rgbSha}")

        val encoded = when (val e = Ops.vaeEncode(first.png, seed = 42)) {
            is Ops.Result.Err -> {
                say("vae_roundtrip: encode FAILED http ${e.code} -- ${e.body.take(160)}", bad = true)
                return
            }
            is Ops.Result.Ok -> e.value
        }
        say("  encoded in ${encoded.serverMs} ms -> ${encoded.handle} sha ${encoded.latentSha}")

        // ⚠⚠ Determinism first. The handle is content-addressed on the image and
        // the seed, so a second encode of the same bytes must return the SAME
        // handle -- otherwise the executor would re-run every downstream node
        // forever and the cache would look broken rather than the op.
        val again = Ops.vaeEncode(first.png, seed = 42)
        if (again is Ops.Result.Ok && again.value.latentSha == encoded.latentSha) {
            say("  ok   encoding the same image twice gives the same latent")
        } else {
            say("  FAIL a second encode differed -- the op is not deterministic", bad = true)
        }

        val second = when (val d = Ops.vaeDecode(latentHandle = encoded.handle)) {
            is Ops.Result.Err -> { say("vae_roundtrip: re-decode FAILED ${d.code}", bad = true); return }
            is Ops.Result.Ok -> d.value
        }

        val a = images.decode(first.png)
        val b = images.decode(second.png)
        if (a == null || b == null) {
            say("vae_roundtrip: a decode produced no bitmap", bad = true)
            return
        }
        sink.image(b)
        val diff = meanDiff(a, b, 0, 0, a.width, a.height)
        say("  round-trip loss: mean |diff| ${"%.2f".format(diff)} of 255 " +
            "(${"%.2f".format(diff / 2.55)}%)")
        // ⚠ A threshold with a REASON: DreamUI measured VAE round-trip noise at
        // ~1/255 outside an inpaint mask (../LocalDream/docs/INPAINT.md), and an
        // encode+decode is one full trip. Well past that means the normalisation
        // or the latent space is wrong, not that the VAE is lossy.
        if (diff < 12.0) say("  ok   within what a VAE round trip costs")
        else say("  FAIL ${"%.1f".format(diff)} is far more than a round trip should cost -- " +
            "suspect the pixel range or the latent space", bad = true)
    }

    /**
     * ⭐ A graph that ends in an Output node, saved to the gallery.
     *
     * ⚠⚠ Runs the graph TWICE. The second run is the check that matters: an
     * Output node is not cacheable, so it must write a second file — a cached
     * one would silently save nothing and the user would press Run twice for one
     * picture. The count of files before and after is the evidence.
     */
    suspend fun saveImage() {
        val wf = com.abrah.nightmare.canvas.defaultWorkflow()
        val withOutput = com.abrah.nightmare.canvas.Workflow(
            graph = Graph(
                wf.graph.nodes + Node(
                    "out", "image.output",
                    params = mapOf("save" to "true", "name" to "smoke"),
                    inputs = sources("image" to "decode"),
                )
            ),
            positions = wf.positions + ("out" to com.abrah.nightmare.canvas.Pt(24f, 560f)),
        )

        val before = countSaved()
        say("save_image: ${before} file(s) in Pictures/${ImageSaver.FOLDER} before")

        val first = runWorkflow(withOutput, onNode = { n ->
            say("  ${n.id.padEnd(7)} ${n.outcome.name.lowercase().padEnd(7)} ${n.ms} ms",
                bad = n.outcome == Outcome.FAILED)
        })
        if (first.error != null || first.failed > 0) {
            say("save_image: first run failed -- ${first.error}", bad = true)
            return
        }

        val second = runWorkflow(withOutput)
        val outRun = second.runs.firstOrNull { it.id == "out" }
        say("  second run: ran ${second.ran}, cached ${second.cached}; " +
            "out was ${outRun?.outcome?.name?.lowercase()}")

        val after = countSaved()
        say("save_image: ${after} file(s) after two runs")
        when {
            outRun?.outcome != Outcome.RAN ->
                say("  FAIL the Output node was ${outRun?.outcome} on the second run -- a " +
                    "node with a side effect must not be cached", bad = true)
            after == before + 2 ->
                say("  ok   two runs wrote two files, and the sampler stayed cached")
            else ->
                say("  FAIL expected ${before + 2} files, found $after", bad = true)
        }
    }

    /** ⭐ What the upscaler catalogue holds and what is on disk. */
    fun listUpscalers() {
        val caps = DeviceProbe.caps()
        say("upscalers (device arch ${caps.arch}, vtcm ${caps.vtcmMb} MB):")
        for (spec in UpscalerCatalog.ALL) {
            val b = spec.buildFor(caps)
            say(
                "  ${spec.id.padEnd(20)} " +
                    (if (spec.installed(ctx)) "installed ${spec.bytesOnDisk(ctx)} B" else "absent") +
                    "  build=" + (b?.tier ?: "none") + " " + (b?.bytes ?: 0) + " B"
            )
            // ⚠ The leftovers too. A 0-byte `.part` is exactly what a broken
            // download leaves, and it is invisible from the Models tab —
            // `installed()` says "absent" and the card offers Download again.
            spec.dir(ctx).listFiles()?.forEach { f ->
                say("      ${f.name}  ${f.length()} B")
            }
        }
    }

    /**
     * ⭐ Install an upscaler by id, reporting the REASON on failure.
     *
     * ⚠ Synchronous and chatty on purpose: this exists because the UI path
     * reported nothing usable when the download failed.
     */
    suspend fun installUpscaler(id: String?) {
        val spec = UpscalerCatalog.byId(id.orEmpty())
        if (spec == null) {
            say("upscaler_install: unknown id \"$id\" -- " +
                UpscalerCatalog.ALL.joinToString { it.id }, bad = true)
            return
        }
        val build = spec.buildFor(DeviceProbe.caps())
        if (build == null) {
            say("upscaler_install: no build for this device", bad = true)
            return
        }
        say("upscaler_install: ${spec.label} tier ${build.tier}, ${build.bytes} B")
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                UpscalerCatalog.install(ctx, spec, build, onProgress = { p ->
                    if (p.done == p.total) say("  ${p.phase} ${p.done}/${p.total}")
                })
            }
            say("  ok   installed ${spec.file(ctx).length()} B at ${spec.file(ctx).absolutePath}")
        } catch (e: Throwable) {
            // ⚠⚠ The CLASS as well as the message. The first failure of this
            // downloader left a 0-byte file and no clue; an IOException and an
            // SSLException want opposite fixes.
            say("  FAIL ${e.javaClass.simpleName}: ${e.message}", bad = true)
        }
    }

    /** How many images this app has in its gallery folder. */
    private fun countSaved(): Int {
        val projection = arrayOf(android.provider.MediaStore.Images.Media._ID)
        val where = android.provider.MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        return ctx.contentResolver.query(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, where, arrayOf("%${ImageSaver.FOLDER}%"), null,
        )?.use { it.count } ?: -1
    }

    /**
     * ⭐ Save a workflow, read it back, and check it is the same graph — on the
     * device's own filesystem rather than a temp folder.
     *
     * ⚠ The JVM tests cover the format; this covers the things only a device
     * has: real app storage, real permissions, and a real `filesDir` that
     * survives the app but not an uninstall.
     */
    suspend fun workflowRoundTrip() {
        val dir = java.io.File(ctx.filesDir, "workflows")
        val store = com.abrah.nightmare.canvas.WorkflowStore(dir)
        val wf = com.abrah.nightmare.canvas.defaultWorkflow()
        try {
            store.save("smoke", wf, nodeTypes())
        } catch (e: Exception) {
            say("workflow_io: save FAILED -- ${e.javaClass.simpleName}: ${e.message}", bad = true)
            return
        }
        val file = store.file("smoke")
        say("workflow_io: wrote ${file.length()} B to ${file.absolutePath}")

        val back = try {
            store.load("smoke")
        } catch (e: Exception) {
            say("workflow_io: load FAILED -- ${e.message}", bad = true)
            return
        }
        if (back == null) {
            say("workflow_io: nothing loaded back", bad = true)
            return
        }
        // ⚠ The GRAPH is compared, not the file text: what matters is that the
        // executor would run the same thing, and two files can differ in
        // whitespace while meaning the same graph.
        if (back.workflow.graph == wf.graph && back.workflow.positions == wf.positions) {
            say("  ok   ${back.workflow.graph.nodes.size} nodes round-tripped, " +
                "positions intact")
        } else {
            say("  FAIL the graph changed across a save/load", bad = true)
            return
        }
        val missing = com.abrah.nightmare.canvas.missingRequirements(back.requires, nodeTypes())
        say("  requires ${back.requires.size} plugin(s), ${missing.size} missing")
    }

    /**
     * ⭐ What does a live preview cost?
     *
     * The canvas will want the partially-denoised image inside the node, and
     * the backend has forwarded preview frames all along — nothing ever asked
     * for them. Before the canvas depends on it, the number has to exist: a
     * preview is a whole **VAE decode** (~190 ms measured) against a ~130 ms
     * sampler step, so a stride of 1 would more than double a render.
     *
     * ⚠ Measured against a CONTROL in the same pass — the identical render with
     * previews off — because a per-step cost quoted from one run is a cost
     * quoted against nothing.
     *
     * ⚠ A warm-up render first. The first sample after a backend start pays for
     * graph warm-up, and attributing that to previews would overstate them by
     * more than they cost.
     */
    suspend fun previewCost() {
        val steps = 20
        val stride = 4

        say("preview: warming up (the first render after a start is not representative)")
        when (val w = Ops.sample(prompt = PREVIEW_PROMPT, negative = PREVIEW_NEG,
            steps = 8, seed = 4242)) {
            is Ops.Result.Err -> {
                say("preview: warm-up FAILED http ${w.code} -- ${w.body.take(160)}", bad = true)
                return
            }
            is Ops.Result.Ok -> say("  warm-up ${w.value.serverMs} ms")
        }

        // ⚠ Different seeds for the two runs, so neither can be served from a
        // cache the other filled. Same everything else.
        val off = Ops.sample(prompt = PREVIEW_PROMPT, negative = PREVIEW_NEG,
            steps = steps, seed = 101)
        val plain = when (off) {
            is Ops.Result.Err -> {
                say("preview: control FAILED http ${off.code}", bad = true); return
            }
            is Ops.Result.Ok -> off.value
        }
        say("  control  ${plain.serverMs} ms for $steps steps " +
            "(${plain.serverMs / steps} ms/step), ${plain.previewFrames} previews")

        var lastPreview: ByteArray? = null
        var previewBytes = 0L
        val on = Ops.sample(
            prompt = PREVIEW_PROMPT, negative = PREVIEW_NEG, steps = steps, seed = 102,
            previewStride = stride,
            onProgress = { p ->
                sink.progress(p.step to p.total)
                p.preview?.let { lastPreview = it; previewBytes += it.size }
            },
        )
        sink.progress(null)
        val withPreviews = when (on) {
            is Ops.Result.Err -> {
                say("preview: preview run FAILED http ${on.code} -- ${on.body.take(160)}",
                    bad = true)
                return
            }
            is Ops.Result.Ok -> on.value
        }

        val n = withPreviews.previewFrames
        say("  previews ${withPreviews.serverMs} ms for $steps steps, " +
            "$n frames at stride $stride, ${previewBytes / 1024} KB total")

        if (n == 0) {
            // ⚠ A real outcome, not a measurement. `previewSupported()` is a
            // pipeline capability, so a backend that cannot preview must say so
            // rather than be reported as "previews are free".
            say("  FAIL asked for previews and got none -- the pipeline may not " +
                "support them, or the request fields are wrong", bad = true)
            return
        }

        val extra = withPreviews.serverMs - plain.serverMs
        say("  ⇒ ${extra} ms extra, ${extra / n} ms per preview " +
            "against ${plain.serverMs / steps} ms per step")

        // The picture itself: a partially-denoised latent decodes to something
        // recognisable but soft, and looking at it is the only way to tell a
        // real preview from a decode of noise.
        lastPreview?.let { bytes ->
            val bmp = images.decode(bytes)
            if (bmp == null) {
                say("  FAIL the last preview (${bytes.size} B) would not decode", bad = true)
            } else {
                sink.image(bmp)
                say("  last preview ${bmp.width}x${bmp.height}, ${bytes.size} B jpeg")
            }
        }
    }

    /**
     * ⭐ The `latent.blend` op, which is what makes **inpainting a Tier 0 node**
     * — no 9-channel model, works on any 4-channel checkpoint
     * (`../LocalDream/docs/INPAINT.md` §1).
     *
     * ⚠⚠ The two extreme masks are the real test, and they are exact rather
     * than visual: an ALL-BLACK mask must reproduce A's latent **byte for
     * byte**, and an ALL-WHITE mask must reproduce B's. That pins the
     * convention — mask = 1 takes B — which is the one thing here that fails as
     * a plausible picture with the wrong region replaced rather than as an
     * error. A half mask is then checked to differ from both, because two
     * identity cases could also be satisfied by an op that ignored the mask
     * entirely and returned whichever latent it felt like.
     */
    suspend fun latentBlend() {
        val a = when (val r = Ops.sample(prompt = "a cat on grass",
            negative = "blurry, lowres", steps = 8, seed = 42)) {
            is Ops.Result.Err -> { say("latent_blend: sample A FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        val b = when (val r = Ops.sample(prompt = "a cat on grass",
            negative = "blurry, lowres", steps = 8, seed = 7)) {
            is Ops.Result.Err -> { say("latent_blend: sample B FAILED ${r.code}", bad = true); return }
            is Ops.Result.Ok -> r.value
        }
        say("latent_blend: A ${a.handle} sha ${a.latentSha}")
        say("             B ${b.handle} sha ${b.latentSha}")

        suspend fun blend(label: String, mask: ByteArray, expect: String?): Ops.Blended? =
            when (val r = Ops.latentBlend(a.handle, b.handle, mask)) {
                is Ops.Result.Err -> {
                    say("  $label FAILED http ${r.code} -- ${r.body.take(160)}", bad = true)
                    null
                }
                is Ops.Result.Ok -> {
                    val got = r.value.latentSha
                    when {
                        expect == null -> say("  $label -> sha $got")
                        got == expect -> say("  ok   $label reproduced the expected latent ($got)")
                        else -> say("  FAIL $label gave $got, expected $expect", bad = true)
                    }
                    r.value
                }
            }

        blend("black mask == A", solidMaskPng(0x000000), a.latentSha)
        blend("white mask == B", solidMaskPng(0xFFFFFF), b.latentSha)

        val half = blend("half mask", halfMaskPng(), null) ?: return
        if (half.latentSha == a.latentSha || half.latentSha == b.latentSha) {
            // ⚠ Without this, an op that ignored the mask and returned one input
            // would pass both identity checks above.
            say("  FAIL a half mask reproduced one of the inputs -- the mask is " +
                "not being applied", bad = true)
            return
        }
        say("  ok   half mask differs from both inputs")

        // ⚠⚠ "It decoded" is not proof. A blend that ignored the mask's
        // GEOMETRY -- say, averaging everywhere -- would still produce a valid
        // image with a new sha. So decode all three and check WHERE each half
        // came from, with the cross-comparisons as the control: if left-vs-B is
        // not much smaller than left-vs-A, the mask's shape is not being
        // applied even though its sha changed the handle.
        val pa = decodeToBitmap(a.handle) ?: return
        val pb = decodeToBitmap(b.handle) ?: return
        val pm = decodeToBitmap(half.handle) ?: return
        sink.image(pm)

        // ⚠ Quarters, not halves: the VAE is convolutional, so pixels either
        // side of the seam legitimately bleed into each other. Sampling away
        // from the boundary measures the blend rather than the blur.
        val w = pm.width
        val h = pm.height
        val leftVsB = meanDiff(pm, pb, 0, 0, w / 4, h)
        val leftVsA = meanDiff(pm, pa, 0, 0, w / 4, h)
        val rightVsA = meanDiff(pm, pa, w - w / 4, 0, w / 4, h)
        val rightVsB = meanDiff(pm, pb, w - w / 4, 0, w / 4, h)

        say("  left quarter:  vs B ${"%.1f".format(leftVsB)}  vs A ${"%.1f".format(leftVsA)}")
        say("  right quarter: vs A ${"%.1f".format(rightVsA)}  vs B ${"%.1f".format(rightVsB)}")

        // The mask was WHITE on the left, and white takes B.
        val geometryHolds = leftVsB < leftVsA / 2 && rightVsA < rightVsB / 2
        if (geometryHolds) {
            say("  ok   the white half took B and the black half took A -- the mask's " +
                "geometry is applied, not just its hash")
        } else {
            say("  FAIL the halves do not match the mask: white was supposed to take B", bad = true)
        }
    }

    /** A latent handle, decoded, or null with the failure already reported. */
    private suspend fun decodeToBitmap(handle: String): android.graphics.Bitmap? =
        when (val d = Ops.vaeDecode(latentHandle = handle)) {
            is Ops.Result.Err -> {
                say("  decode of $handle FAILED http ${d.code}", bad = true)
                null
            }
            is Ops.Result.Ok -> images.decode(d.value.png)
                ?: null.also { say("  decode of $handle produced no bitmap", bad = true) }
        }

    /**
     * Mean absolute RGB difference over a rectangle, 0..255.
     *
     * ⚠ Sampled every 4th pixel. A full 512² comparison of three pairs is a
     * million getPixel calls, and the number this feeds is a ratio -- it does
     * not get more true by being slower.
     */
    private fun meanDiff(
        x: android.graphics.Bitmap, y: android.graphics.Bitmap,
        left: Int, top: Int, width: Int, height: Int,
    ): Double {
        var sum = 0L
        var n = 0
        var py = top
        while (py < top + height) {
            var px = left
            while (px < left + width) {
                val a = x.getPixel(px, py)
                val b = y.getPixel(px, py)
                sum += Math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)).toLong()
                sum += Math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)).toLong()
                sum += Math.abs((a and 0xFF) - (b and 0xFF)).toLong()
                n += 3
                px += 4
            }
            py += 4
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /**
     * ⭐⭐ A CONTRIBUTOR'S node blending latents — the whole tier ladder in one
     * graph.
     *
     * ```
     * sample(42) ─┐
     * sample(7)  ─┼─> LatentMix (plugin, LATENT ports) ──> vae_decode ──> pixels
     * HalfMask   ─┘   (plugin, IMAGE out)
     * ```
     *
     * Two of those five nodes come from a pack that is not in the APK, one of
     * them moves a LATENT through JavaScript, and the executor caches all of
     * them the same way. ⚠ The plugin never sees a pixel or a byte of base64:
     * it passes handles, and the host turns the mask image into what the
     * backend wants.
     */
    suspend fun pluginLatentGraph() {
        val dir = java.io.File(pluginsDir(), "latent-mix")
        val plugin = try {
            Plugin.fromDir(dir)
        } catch (e: Exception) {
            say("plugin_latent: no pack at ${dir.absolutePath} -- push examples/latent-mix " +
                "there first (${e.message})", bad = true)
            return
        }
        val added = try {
            plugins.load(plugin)
        } catch (e: Throwable) {
            say("plugin_latent: load failed -- ${e.javaClass.simpleName}: ${e.message}",
                bad = true)
            return
        }
        say("plugin_latent: ${plugin.id} v${plugin.version} -> ${added.joinToString(", ")}")

        // ⚠ ONE text node feeding both samplers, which is the cheap and
        // correct shape: the two renders differ by seed alone, so encoding the
        // same prompt twice would be pure waste -- and a COND handle fanning
        // out to two consumers is a case the executor should be seen doing.
        fun sampler(id: String, seed: Int) = Node(
            id, "sd.sample",
            params = mapOf(
                "steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to seed.toString(),
            ),
            inputs = sources("cond" to "text"),
        )
        val graph = Graph(
            listOf(
                textNode(),
                sampler("a", 42),
                sampler("b", 7),
                Node("mask", "com.example.latent-mix:HalfMask"),
                Node(
                    "mix", "com.example.latent-mix:LatentMix",
                    inputs = sources("a" to "a", "b" to "b", "mask" to "mask"),
                ),
                Node(
                    "decode", "sd.vae_decode",
                    params = ctxKey(),
                    inputs = sources("latent" to "mix"),
                ),
            )
        )

        executor.cache.clear()
        val r = pass("A latent plugin", graph, expectRan = 6, expectCached = 0)
        if (r.failed > 0 || r.error != null) return
        pass("B cached", graph, expectRan = 0, expectCached = 6)

        (r.outputs["decode"] as? Value.Image)?.let { img ->
            images.get(img.id)?.let { bmp ->
                sink.image(bmp)
                say("  ${bmp.width}x${bmp.height} from a latent a PLUGIN blended")
            }
        }

        // ⚠⚠ The wiring check, run last because it deliberately fails: a LATENT
        // into an IMAGE port must be refused by NAME, not discovered later as a
        // missing image handle one step away from the mistake.
        val wrong = Graph(
            listOf(
                textNode(),
                sampler("a", 42),
                Node("mask", "com.example.latent-mix:HalfMask"),
                Node(
                    "mix", "com.example.latent-mix:LatentMix",
                    // mask <- a latent, which is not an IMAGE.
                    inputs = sources("a" to "a", "b" to "a", "mask" to "a"),
                ),
            )
        )
        val bad = executor.run(wrong)
        val mixRun = bad.runs.firstOrNull { it.id == "mix" }
        if (mixRun?.outcome == Outcome.FAILED && mixRun.detail.contains("wants IMAGE")) {
            say("  ok   a latent wired into an image port is refused: ${mixRun.detail.take(120)}")
        } else {
            say("  FAIL a latent into an IMAGE port was not refused -- outcome " +
                "${mixRun?.outcome}, ${mixRun?.detail?.take(120)}", bad = true)
        }
    }

    /** A solid mask. ⚠ At latent resolution already, so nothing is resampled. */
    private fun solidMaskPng(rgb: Int): ByteArray = maskPng { canvas, w, h ->
        canvas.drawColor(0xFF000000.toInt() or rgb)
    }

    /** White on the left half: the blend should take B there and A on the right. */
    private fun halfMaskPng(): ByteArray = maskPng { canvas, w, h ->
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawRect(
            0f, 0f, w / 2f, h.toFloat(),
            android.graphics.Paint().apply { color = android.graphics.Color.WHITE },
        )
    }

    private inline fun maskPng(draw: (android.graphics.Canvas, Int, Int) -> Unit): ByteArray {
        // 64x64 is the latent grid for a 512² render. Sending it at that size
        // means the backend's downsample is a no-op, so a wrong result cannot
        // be blamed on resampling.
        val w = 64
        val h = 64
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        draw(android.graphics.Canvas(bmp), w, h)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // The plugin runtime (docs/ARCHITECTURE.md §6)
    // ------------------------------------------------------------------

    /**
     * ⭐ Proves QuickJS is real on this device, and that the two directions of
     * the bridge work: JS values coming out, and a host op going in.
     *
     * ⚠ It also proves the two things that make the runtime SAFE to hand a
     * downloaded script, and those are the checks with teeth:
     *
     *  - a runaway loop ends the NODE, not the app (the interrupt budget);
     *  - a thrown host op arrives in JS as a catchable error rather than as a
     *    pending JNI exception, which would make every later JNI call in the
     *    thread undefined behaviour.
     *
     * ⚠ Each check states its expected answer, because "it printed something"
     * is not a result -- this project has lost a session to a check that
     * reported the opposite of the truth.
     */
    suspend fun jsSmoke() {
        var hostCalls = 0
        val rt = try {
            JsRuntime { op, args ->
                hostCalls++
                when (op) {
                    "echo" -> args
                    "boom" -> throw IllegalStateException("host op refused")
                    else -> throw IllegalArgumentException("unknown host op \"$op\"")
                }
            }
        } catch (e: Throwable) {
            // ⚠ Throwable, not Exception: a missing libnmjs.so arrives as
            // UnsatisfiedLinkError, which an Exception catch would let through
            // as an app crash rather than a legible finding.
            say("js: could not create a runtime -- ${e.javaClass.simpleName}: ${e.message}",
                bad = true)
            return
        }

        rt.use {
            check("arithmetic", "3") { rt.eval("1 + 2") }
            check("string ops", "NIGHTMARE") { rt.eval("'nightmare'.toUpperCase()") }
            check("JSON round trip", """{"a":1}""") {
                rt.eval("JSON.stringify(JSON.parse('{\"a\":1}'))")
            }

            // The bridge, outbound. The prelude stringifies, the host echoes,
            // the prelude parses -- so a wrong answer here localises to one of
            // three places rather than to "JS".
            check("host op round trip", "42") {
                rt.eval("String(__nm.host('echo', {v: 42}).v)")
            }

            // A node, registered and invoked the way a plugin's will be.
            rt.eval(
                """
                __nm.register('demo.double', {
                  run: function (ctx, inputs, widgets) {
                    return { n: inputs.n * widgets.factor };
                  }
                });
                """.trimIndent(),
                "<smoke plugin>",
            )
            check("node invoke", """{"n":14}""") {
                rt.invoke("demo.double", """{"inputs":{"n":7},"widgets":{"factor":2}}""")
            }

            // ⚠⚠ The runaway loop. Budget deliberately small: the point is that
            // the call RETURNS, and the app survives to print the next line.
            val t0 = System.currentTimeMillis()
            val stopped = try {
                rt.eval("var i = 0; while (true) { i++; }", "<runaway>", budgetMs = 800)
                false
            } catch (e: JsException) {
                true
            }
            val loopMs = System.currentTimeMillis() - t0
            if (stopped && loopMs < 5_000) {
                say("  ok   runaway loop interrupted after $loopMs ms")
            } else {
                say("  FAIL runaway loop: stopped=$stopped after $loopMs ms -- the " +
                    "interrupt budget is not a real stop", bad = true)
            }

            // A host op that throws must reach JS as a catchable error. If this
            // instead crashed the process, nothing after it would print.
            //
            // ⚠⚠ And the MESSAGE must survive. The bridge used to flatten every
            // host failure to "host op threw", which cost a wrong verdict on
            // 2026-09-08: a permission denial arrived unreadable and the check
            // for it reported the opposite of the truth. So the assertion is on
            // the host's own words, not on the fact that something was thrown.
            check("host error carries its reason", "yes: host op refused") {
                rt.eval("(function(){ try { __nm.host('boom', {}); return 'not thrown'; } " +
                    "catch (e) { return (e.message.indexOf('host op refused') >= 0 " +
                    "? 'yes: host op refused' : 'lost: ' + e.message); } })()")
            }

            // And an unknown op is a *plugin* error, not a host crash.
            check("unknown host op is catchable", "caught") {
                rt.eval("(function(){ try { __nm.host('nope', {}); return 'not thrown'; } " +
                    "catch (e) { return 'caught'; } })()")
            }

            say("js: $hostCalls host calls, runtime closed cleanly")
        }
    }

    /**
     * ⚠ Compares against an EXPECTED value rather than printing what it got. A
     * smoke test that only prints is a smoke test whose failure looks like a
     * pass on a quick read of the log.
     */
    private inline fun check(what: String, expect: String, body: () -> String) {
        val got = try {
            body()
        } catch (e: Exception) {
            say("  FAIL $what threw ${e.javaClass.simpleName}: ${e.message}", bad = true)
            return
        }
        if (got == expect) say("  ok   $what = $got")
        else say("  FAIL $what = $got (expected $expect)", bad = true)
    }

    /**
     * ⭐⭐ The first plugin node, end to end: two text files in the APK's
     * assets become a node the executor runs, caches and invalidates exactly
     * like a built-in.
     *
     * The graph is `sample -> vae_decode -> Resize`, and the three passes ask
     * the three questions that matter for a CONTRIBUTOR'S node rather than for
     * ours:
     *
     *  A  it runs at all, and the pixels really changed size;
     *  B  it is cached like anything else;
     *  C  changing only ITS widget re-runs only IT -- the 2.6 s of NPU work
     *     upstream stays cached, which is the entire reason a graph beats a
     *     pipeline.
     *
     * ⚠ Needs a backend for the first two nodes. The Resize node itself
     * declares no context key (Tier 0 runs app-side), which is why a graph of
     * only Tier 0 nodes needs no backend at all.
     */
    suspend fun pluginGraph() {
        val plugin = try {
            Plugin.fromAssets(ctx, "plugins/resize-pack")
        } catch (e: Exception) {
            say("plugin: could not read the manifest -- ${e.javaClass.simpleName}: ${e.message}",
                bad = true)
            return
        }
        say("plugin ${plugin.id} v${plugin.version} api ${plugin.api}, " +
            "${plugin.nodes.size} node(s), permissions ${plugin.permissions}")

        val added = try {
            plugins.load(plugin)
        } catch (e: Throwable) {
            say("plugin: load failed -- ${e.javaClass.simpleName}: ${e.message}", bad = true)
            return
        }
        say("  registered: ${added.joinToString(", ")}")

        fun graph(scale: String) = Graph(
            listOf(
                textNode(),
                Node(
                    "sample", "sd.sample",
                    params = mapOf(
                        "steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to "42",
                    ),
                    inputs = sources("cond" to "text"),
                ),
                Node(
                    "decode", "sd.vae_decode",
                    params = ctxKey(),
                    inputs = sources("latent" to "sample"),
                ),
                Node(
                    "small", "com.nightmare.resize-pack:Resize",
                    params = if (scale.isEmpty()) emptyMap() else mapOf("scale" to scale),
                    inputs = sources("image" to "decode"),
                ),
            )
        )

        executor.cache.clear()
        val a = pass("A plugin cold", graph("0.5"), expectRan = 4, expectCached = 0)
        val small = a.outputs["small"] as? Value.Image
        if (small == null) {
            say("plugin: the node produced no image -- nothing after this means anything",
                bad = true)
            return
        }
        // ⚠ The dimensions ARE the check. "the node ran" is satisfied by a node
        // that returns its input unchanged, and that is exactly what a broken
        // handle round trip would look like.
        if (small.w == 256 && small.h == 256) {
            say("  ok   512x512 -> ${small.w}x${small.h} via ${plugin.id}")
        } else {
            say("  FAIL resize produced ${small.w}x${small.h}, expected 256x256", bad = true)
        }

        // ⭐ The default. The manifest says scale defaults to 0.5, so a node
        // with NO params must key identically to the explicit 0.5 above -- if
        // it re-runs, `effectiveParams` is not feeding the cache key.
        pass("B default == explicit", graph(""), expectRan = 0, expectCached = 4)

        val c = pass("C rescale only", graph("0.25"), expectRan = 1, expectCached = 3)
        (c.outputs["small"] as? Value.Image)?.let {
            if (it.w == 128) say("  ok   rescaled to ${it.w}x${it.h} with the NPU work cached")
            else say("  FAIL rescale produced ${it.w}x${it.h}, expected 128x128", bad = true)
        }

        // ⭐⭐ The permission check, on a real node in a real graph. A pack that
        // declares nothing and calls image.info must be REFUSED before the op
        // runs, and the executor must report that node FAILED rather than
        // quietly yielding nothing.
        val nosy = try {
            Plugin.fromAssets(ctx, "plugins/nosy-pack").also { plugins.load(it) }
        } catch (e: Throwable) {
            say("  FAIL could not load the nosy pack -- ${e.message}", bad = true)
            null
        }
        if (nosy != null) {
            val g = Graph(graph("0.25").nodes + Node(
                "nosy", nosy.nodes.single().qualified(nosy.id),
                inputs = sources("image" to "decode"),
            ))
            val d = executor.run(g)
            val run = d.runs.firstOrNull { it.id == "nosy" }
            val denied = run?.outcome == Outcome.FAILED &&
                run.detail.contains("permission")
            if (denied) {
                say("  ok   denied: ${run!!.detail.take(140)}")
            } else {
                // ⚠⚠ The loudest line in this file. A permission check that
                // does not deny is worse than none: it makes the manifest read
                // like a guarantee it is not providing.
                say("  FAIL the nosy node was NOT denied -- outcome ${run?.outcome}, " +
                    "detail ${run?.detail?.take(120)}", bad = true)
            }
        }

        say("  host ops called: ${plugins.surface.calls}, images resident: ${images.size}")
        (c.outputs["small"] as? Value.Image)?.let { img ->
            images.get(img.id)?.let { sink.image(it) }
        }
    }

    /** Where a pushed or downloaded plugin lives. */
    fun pluginsDir(): java.io.File =
        java.io.File(ctx.getExternalFilesDir(null), "plugins")

    /**
     * Install every zip sitting in the inbox, then run whatever that leaves.
     *
     * ⭐ A zip is what a *downloaded* plugin is, so this is the last step of the
     * delivery path that does not require answering "where may a plugin come
     * from" (`notes/HANDOFF.md` §7) — the archive is already on the device and
     * nothing here fetches anything.
     *
     * ```
     * adb push pack.zip /sdcard/Android/data/com.abrah.nightmare/files/plugins-inbox/
     * ```
     *
     * ⚠ A refused archive is reported and SKIPPED, not fatal. One bad download
     * must not stop the packs that are fine from loading, and the reason it was
     * refused is the whole point of printing it.
     */
    suspend fun installZips() {
        val inbox = java.io.File(ctx.getExternalFilesDir(null), "plugins-inbox")
        val zips = inbox.listFiles { f: java.io.File ->
            f.isFile && f.name.endsWith(".zip", ignoreCase = true)
        }?.sortedBy { it.name }
        if (zips.isNullOrEmpty()) {
            say("no zips in ${inbox.absolutePath}", bad = true)
            return
        }
        for (z in zips) {
            try {
                // ⚠ Staged in cacheDir, not in the plugins directory: an
                // archive that fails validation must never exist where the
                // loader walks.
                val dir = PluginInstaller.install(z, pluginsDir(), ctx.cacheDir)
                say("  installed ${z.name} (${z.length()} B) -> ${dir.name}")
            } catch (e: Exception) {
                say("  ${z.name}: REFUSED -- ${e.message}", bad = true)
            }
        }
        pluginsFromDisk()
    }

    /**
     * ⭐⭐ THE Tier 0 claim, actually tested: nodes the APK has never seen.
     *
     * Everything before this loaded a pack out of `assets/`, which proves the
     * runtime but not the promise — an asset ships in the app release the tier
     * table says a contributor should not need. This walks
     * `<externalFiles>/plugins/`, loads whatever is there, and chains every
     * image→image node it finds onto the end of a real render.
     *
     * ```
     * adb push examples/center-square /sdcard/Android/data/com.abrah.nightmare/files/plugins/
     * ```
     *
     * ⚠ Generic on purpose. An op that knew the example pack's node name would
     * be testing the example, not the loading — a second pack must work with no
     * code change here, because that is what a contributor is going to do.
     */
    suspend fun pluginsFromDisk() {
        val dir = pluginsDir()
        say("plugin dir: ${dir.absolutePath}")
        val packs = dir.listFiles { f: java.io.File -> f.isDirectory }?.sortedBy { it.name }
        if (packs.isNullOrEmpty()) {
            // ⚠ A finding, not an error. An empty directory means the push has
            // not happened, and saying so with the path is the difference
            // between a five-second fix and a debugging session.
            say("no plugins on disk -- push one to ${dir.absolutePath} first", bad = true)
            return
        }

        val chain = mutableListOf<Pair<String, Plugin.Spec>>()
        for (p in packs) {
            val plugin = try {
                Plugin.fromDir(p)
            } catch (e: Exception) {
                say("  ${p.name}: REFUSED -- ${e.message}", bad = true)
                continue
            }
            val added = try {
                plugins.load(plugin)
            } catch (e: Throwable) {
                say("  ${plugin.id}: load failed -- ${e.javaClass.simpleName}: ${e.message}",
                    bad = true)
                continue
            }
            say("  loaded ${plugin.id} v${plugin.version} from ${p.name}: " +
                added.joinToString(", "))
            for (spec in plugin.nodes) {
                val takesImage = spec.inputs.any { it.type == "IMAGE" }
                val makesImage = spec.outputs.any { it.type == "IMAGE" }
                if (takesImage && makesImage) chain += spec.qualified(plugin.id) to spec
            }
        }
        if (chain.isEmpty()) {
            say("no image->image nodes among the loaded packs -- nothing to chain", bad = true)
            return
        }

        val nodes = mutableListOf(
            textNode(),
            Node(
                "sample", "sd.sample",
                params = mapOf(
                    "steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to "42",
                ),
                inputs = sources("cond" to "text"),
            ),
            Node(
                "decode", "sd.vae_decode",
                params = ctxKey(),
                inputs = sources("latent" to "sample"),
            ),
        )
        var upstream = "decode"
        chain.forEachIndexed { i, (type, spec) ->
            val id = "p$i"
            val inPort = spec.inputs.first { it.type == "IMAGE" }.name
            nodes += Node(id, type, inputs = sources(inPort to upstream))
            upstream = id
        }

        val expect = nodes.size
        val a = pass("A disk plugins", Graph(nodes), expectRan = expect, expectCached = 0)
        (a.outputs[upstream] as? Value.Image)?.let {
            say("  final image ${it.w}x${it.h} from ${chain.last().first}")
            images.get(it.id)?.let { bmp -> sink.image(bmp) }
        }
        // ⚠ The second pass is the check that a disk-loaded node is a FIRST
        // CLASS node: if it keyed differently from a built-in it would re-run
        // here, and "it worked" would have hidden that.
        pass("B disk plugins cached", Graph(nodes), expectRan = 0, expectCached = expect)
    }

    // ------------------------------------------------------------------
    // The executor (docs/ARCHITECTURE.md §4)
    // ------------------------------------------------------------------

    /**
     * Two independent branches over one model: `sample -> vae_decode`, twice.
     *
     * ⚠ Two branches rather than one, because a single chain cannot show the
     * thing that matters. Changing a seed on a one-branch graph re-runs all of
     * it, and "everything re-ran" is indistinguishable from a cache that does
     * nothing. With two, changing branch B's seed must leave branch A entirely
     * untouched -- and B's DECODE must re-run even though not one of its own
     * params changed, because its input handle did. That propagation is the
     * mechanism; the rest is bookkeeping.
     *
     * ⚠ [FIXTURE_STEPS], not the node default of 20. This op renders four
     * times over its four passes and the point is the skipping, not the
     * picture; 8 steps is ~1 s each at 512 (docs/ARCHITECTURE.md §3) and keeps
     * the whole experiment inside ~15 s. ⚠⚠ On an SDXL model that is ~12 s a
     * pass AND an ugly picture -- undercooked, not broken. Read the PASS/FAIL
     * lines, never the image.
     */
    private fun demoGraph(seedA: Int, seedB: Int): Graph {
        fun sampler(id: String, seed: Int) = Node(
            id = id, type = "sd.sample",
            params = ctxKey(
                mapOf(
                    "steps" to FIXTURE_STEPS,
                    "cfg" to "7.5",
                    "seed" to seed.toString(),
                )
            ),
            inputs = sources("cond" to "text"),
        )
        fun decoder(id: String, from: String) = Node(
            id = id, type = "sd.vae_decode",
            params = ctxKey(),
            inputs = sources("latent" to from),
        )
        return Graph(
            listOf(
                // ⭐ One conditioning, two branches. It is also what makes
                // pass D's prediction sharper than it was: a restarted backend
                // has forgotten the COND handle too, so the text node re-runs
                // and produces the SAME id, and the samplers below it must be
                // seen re-running for the latent they lost rather than for a
                // key that changed.
                textNode(),
                sampler("sample_a", seedA), decoder("decode_a", "sample_a"),
                sampler("sample_b", seedB), decoder("decode_b", "sample_b"),
            )
        )
    }

    /**
     * ⭐ The op that proves the executor consults the handle table, in four
     * passes over one backend.
     *
     * | pass | graph | predicted |
     * |---|---|---|
     * | A | seeds 42 / 7, cold cache | 5 ran |
     * | B | identical | 5 cached |
     * | C | branch B reseeded | 3 cached (text + A), 2 ran (B) |
     * | D | C's graph after a backend RESTART | 3 ran (text + samplers), 2 cached (decodes) |
     *
     * ⚠⚠ Pass D is the whole reason this op exists rather than a unit test.
     * A pure in-app memo cache passes A, B and C identically -- it is D that
     * separates "remembers what it computed" from "checks the backend still has
     * it". And D's prediction is a SPLIT, not a wipe: the conditioning and the
     * latents are gone, so the text node and both samplers re-run, but every one
     * of those ops is content-addressed and returns the same handle id, so the
     * decodes' keys still match and they must NOT re-run. Predicting 3/2 exactly
     * is what makes the pass falsifiable; 5 ran means the prune is too broad,
     * 5 cached means residency is not consulted at all.
     */
    /**
     * ⭐⭐ Prove the aspect crop on a fixed-canvas family, end to end.
     *
     * ⚠⚠ **The one part of the shape feature a fixture could not otherwise
     * reach.** `aspect_ratio` changes nothing the other ops look at: the
     * context key is the same, the launch line is the same, and `/sample`
     * returns a full-canvas latent either way. What differs is only the SIZE of
     * the picture [VaeDecodeNode] cuts out of it — so the check is the decoded
     * dimensions, compared against [ModelCatalog.aspectTarget], which is itself
     * the app's copy of arithmetic that lives in C++.
     *
     * ⇒ A disagreement here is the drift that copy exists to make visible, and
     * it is invisible to every other op and to every JVM test (which can only
     * check the app's copy against itself).
     *
     * `--es arg 16:9`
     */
    suspend fun aspectProbe(arg: String?) {
        val spec = SelectedModel.spec
        if (!spec.fixedCanvas) {
            say("aspect needs a fixed-canvas model (SDXL/Anima); ${spec.label} is ${spec.family.label}",
                bad = true)
            return
        }
        val ratio = arg ?: ModelCatalog.DEFAULT_ASPECT
        val want = ModelCatalog.aspectTarget(ratio, spec.native)
        if (want == null && ratio != ModelCatalog.DEFAULT_ASPECT) {
            say("\"$ratio\" is not a w:h ratio this app would crop for", bad = true)
            return
        }
        if (!ensureBackend()) return
        val expect = want ?: spec.native
        say("aspect $ratio on ${spec.label}: expecting $expect out of ${spec.native}")
        val g = Graph(
            listOf(
                textNode(),
                Node(
                    "sample", "sd.sample",
                    params = ctxKey(
                        mapOf("steps" to FIXTURE_STEPS, "cfg" to "7.5", "seed" to "42",
                              "aspect" to ratio)
                    ),
                    inputs = sources("cond" to "text"),
                ),
                Node(
                    "decode", "sd.vae_decode",
                    // ⚠ The SAME ratio on the decoder. That is the pairing
                    // `aspectRetarget` enforces on a real graph, and writing it
                    // by hand here is what makes this fixture a test of the crop
                    // rather than of the retarget.
                    params = ctxKey(mapOf("aspect" to ratio)),
                    inputs = sources("latent" to "sample"),
                ),
            )
        )
        // ⚠ `executor.run`, not `runWorkflow`: the latter rolls a `seed = 0`
        // into a fresh one, and this fixture pins 42 so two runs of it are
        // comparable.
        val r = executor.run(
            g,
            onProgress = { _, step, total -> sink.progress(step to total) },
            onNode = { n ->
                say("  ${n.id.padEnd(8)} ${n.outcome.name.lowercase().padEnd(7)} " +
                    "${n.ms} ms  ${n.detail}", bad = n.outcome == Outcome.FAILED)
            },
        )
        sink.progress(null)
        if (r.error != null) { say("aspect: refused -- ${r.error}", bad = true); return }
        val out = r.outputs["decode"] as? Value.Image
        if (out == null) { say("aspect: no image came out", bad = true); return }
        images.get(out.id)?.let { sink.image(it) }
        if (out.w == expect.width && out.h == expect.height) {
            say("aspect $ratio -> ${out.w}x${out.h} ✓ matches ${expect}")
        } else {
            say("aspect $ratio -> ${out.w}x${out.h} but expected $expect -- " +
                "the app's aspectTarget and the backend disagree", bad = true)
        }
    }

    suspend fun runGraph() {
        executor.cache.clear()
        val a = pass("A cold", demoGraph(42, 7), expectRan = 5, expectCached = 0)
        if (a.error != null || a.failed > 0) {
            say("graph: pass A did not complete -- the later passes would be " +
                "measuring nothing. Stopping.", bad = true)
            return
        }
        pass("B identical", demoGraph(42, 7), expectRan = 0, expectCached = 5)
        pass("C reseed B", demoGraph(42, 8), expectRan = 2, expectCached = 3)

        say("pass D: restarting the backend under the cache…")
        if (!restartBackend()) {
            say("graph: no backend after the restart -- D not run", bad = true)
            return
        }
        val d = pass("D after restart", demoGraph(42, 8), expectRan = 3, expectCached = 2)
        // ⚠ The prune count is the direct evidence. Without it, a D that
        // happened to re-run the right two nodes for the wrong reason reads as
        // a pass.
        if (d.prunedHandles > 0) {
            say("  dropped ${d.prunedHandles} handle(s) the restarted backend no " +
                "longer held -- residency WAS consulted")
        } else {
            say("  pruned 0 handles after a restart -- residency was NOT consulted, " +
                "so D proves nothing", bad = true)
        }

        // The last decode, on screen where there is one. A verdict table nobody
        // can look at is the mistake graph_smoke.sh exists to prevent.
        (d.outputs["decode_b"] as? Value.Image)?.let { img ->
            images.get(img.id)?.let { bmp ->
                sink.image(bmp)
                say("  ^ decode_b, from the cache -- a cat, not beige blobs")
            }
        }
    }

    /** One pass, its node table, and its verdict against the prediction. */
    private suspend fun pass(
        label: String,
        graph: Graph,
        expectRan: Int,
        expectCached: Int,
    ): GraphRun {
        say("pass $label:")
        val r = executor.run(
            graph,
            onProgress = { _, step, total -> sink.progress(step to total) },
        )
        sink.progress(null)
        if (r.error != null) {
            say("  refused: ${r.error}", bad = true)
            return r
        }
        r.runs.forEach { n ->
            val mark = when (n.outcome) {
                Outcome.RAN -> "ran    ${n.ms} ms"
                Outcome.CACHED -> "cached"
                Outcome.FAILED -> "FAILED"
                Outcome.BLOCKED -> "blocked"
            }
            say("  ${n.id.padEnd(9)} $mark  ${n.detail}", bad = n.outcome == Outcome.FAILED)
        }
        val ok = r.ran == expectRan && r.cached == expectCached
        say(
            "  ${if (ok) "PASS" else "FAIL"} $label: ran ${r.ran}, cached ${r.cached} " +
                "(expected $expectRan / $expectCached) in ${r.totalMs} ms",
            bad = !ok,
        )
        return r
    }

    // ------------------------------------------------------------------
    // The backend process
    // ------------------------------------------------------------------

    /**
     * Kill the backend and bring a new one up.
     *
     * ⚠⚠ Waits for /health to go DOWN before starting the replacement, and that
     * wait is the point. `stop()` returns immediately and the dying process
     * keeps :8189 for a moment; a replacement that then fails to bind leaves
     * the OLD server answering /health 200 with all its handles still resident
     * -- so pass D would "restart" nothing and report a cache that works. The
     * check has to prove the old process is gone, not that a process is there.
     */
    suspend fun restartBackend(): Boolean {
        BackendProcess.stop()
        var down = false
        repeat(10) {
            if (!down) {
                if (Backend.get("/health").code != 200) down = true
                else kotlinx.coroutines.delay(500)
            }
        }
        if (!down) {
            say("  backend still answering /health after stop() -- not restarted", bad = true)
            return false
        }
        sink.backend(BackendState.DOWN)
        return launchBackend()
    }

    /**
     * Start the backend as a child of this app, then wait for it to serve.
     *
     * ⚠ start() returns when the process is LAUNCHED, not when it is ready --
     * QNN takes 4-5 s to load its contexts. Polling /health is the difference
     * between "started" and "serving", and reporting the former as the latter
     * is how a first request lands on a socket nobody is listening to.
     */
    /**
     * A server with no diffusion model, for a graph that names no context key.
     *
     * ⚠ A process holding a CHECKPOINT also serves `/upscale` perfectly well,
     * so one that is already up is left alone rather than torn down: the user
     * would pay a relaunch to free memory they may be about to need again. Only
     * an absent backend is started this way.
     */
    private suspend fun ensureUpscaleServer(): Boolean {
        if (Backend.get("/health").code == 200) {
            if (!BackendProcess.upscalerServer) {
                say("this graph needs no checkpoint; the backend already up is holding one")
            }
            return true
        }
        say("starting an upscale-only backend -- this graph needs no checkpoint…")
        return launchBackend(upscalerOnly = true)
    }

    suspend fun launchBackend(
        want: ContextKey? = null,
        upscalerOnly: Boolean = false,
    ): Boolean {
        val models = BackendProcess.modelsDir(ctx)
        say("models dir: ${models.absolutePath}")
        val modelId = want?.model ?: SelectedModel.id
        val res = want?.let { Res(it.width, it.height) } ?: SelectedModel.res
        when (val r = BackendProcess.start(
            ctx, modelId = modelId, port = Backend.PORT, res = res,
            upscalerOnly = upscalerOnly,
        )) {
            is BackendProcess.Start.Failed -> {
                say("start failed -- ${r.why}", bad = true)
                drainBackendLog()
                return false
            }
            BackendProcess.Start.Ok -> {
                say("launched, waiting for /health…")
                var up = false
                repeat(45) {
                    if (!up) {
                        if (Backend.get("/health").code == 200) {
                            up = true
                            sink.backend(BackendState.UP)
                            say("serving after ~${it + 1}s")
                        } else {
                            kotlinx.coroutines.delay(1000)
                        }
                    }
                }
                if (!up) {
                    sink.backend(BackendState.DOWN)
                    say("no /health after 45s -- backend output follows", bad = true)
                    drainBackendLog()
                }
                return up
            }
        }
    }

    fun stopBackend() {
        BackendProcess.stop()
        sink.backend(BackendState.DOWN)
        say("backend stopped")
    }

    /**
     * ⚠ The child's own output is the only place QNN errors appear. Without
     * this a failed start says "no /health" and nothing about why.
     */
    private fun drainBackendLog() {
        synchronized(BackendProcess.output) {
            BackendProcess.output.take(12).forEach { say("  | $it", bad = true) }
        }
    }
}
