package com.abrah.nightmare.npu

import android.graphics.Bitmap
import android.util.Log
import com.abrah.nightmare.ContextKey
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeCtx
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.str
import com.abrah.nightmare.Value
import com.abrah.nightmare.Widget
import java.io.File
import java.security.MessageDigest

/**
 * ⭐⭐ Text to video, as nodes.
 *
 * ⚠⚠ **These reach the NPU without the backend process.** Every other
 * NPU node in this app talks HTTP to a server holding a checkpoint bound at
 * launch; these load QNN context binaries **in-process** ([QnnRunner]) and join
 * no [ContextKey] at all. ⇒ A video node can sit in a graph beside an SD node
 * without forcing a 2.3–5 s backend relaunch, which is the same property
 * `image.upscale` has. `docs/NEODRAGON.md` §3.
 *
 * ⚠ `appSide = false` even so. That flag means "too expensive to run for a
 * preview", not "talks to the server" — and 25 s is the most expensive thing in
 * the app.
 */

/**
 * ⭐⭐ prompt → 49 frames.
 *
 * ⚠⚠ **FUSED, exactly as `sd.sample` is fused**, and for the same reason
 * (`docs/ARCHITECTURE.md` §3): the 18 MMDiT calls are driven by a pyramidal
 * schedule with per-(unit, stage) structure, and exposing one step as a node
 * would put that scheduler's state in JS. The decomposition that earns its
 * keep here is the one at the EDGES — the clip comes out as a value another
 * node consumes.
 *
 * ⭐⭐ **`image` is optional, and that is deliberate from the first version.**
 * Connected it means image-to-video; unconnected it means generate the first
 * frame too. The user's call, 2026-09-12: *"of course we will add i2v later so
 * sample might need to allow encoded image latent"* — so the port exists now,
 * before any saved workflow references this node, because
 * `docs/ARCHITECTURE.md` §7 says a node's shape cannot be extended once
 * workflows depend on it.
 *
 * ⚠ It is an **IMAGE**, not a LATENT, and the difference is honest rather than
 * a simplification: `Video.generate` takes a bitmap and does its own VAE encode
 * (`vaeenc`), because the reference pipeline's native entry point is
 * `generate(image=…)`. There is no separate video `vae_encode` op to wire a
 * LATENT out of, so a LATENT port would name a value nothing in this app can
 * produce. ⇒ When the encoder becomes its own node, that is when the port type
 * changes — with a version bump, not by widening this one.
 */
object VideoSampleNode : NodeType {

    /** ⚠ Reaches the NPU for ~25 s. Never run for a preview. */
    override val appSide = false

    /**
     * ⭐ The prompt is drawn IN the node's body, like `sd.clip_encode`'s
     * (`docs/UI.md`). It is the one field anyone opens this graph to change, and
     * a two-node graph where the interesting text is hidden behind a sheet is a
     * worse first screen than one extra inch of node.
     */
    override val name = "nd.sample"
    override val version = "2"

    /**
     * ⚠⚠ The prompt is a WIRE, like every other sampler's — `core.prompt` feeds
     * it, and the two conditionings this needs are made inside. ⚠ `image` is
     * optional and decides the flow: wired it is image-to-video, unwired the
     * node generates its own first frame. The user's call, 2026-09-15 — one
     * sampler for both, no first-frame node.
     */
    override val inputs = listOf(Port("prompt", "PROMPT"), Port("image", "IMAGE"))
    override val outputs = listOf(Port("video", "VIDEO"))

    /** ⚠ `generate`, with the SD samplers: it is the same job on other weights. */
    override val category = "generate"

    /** ⚠ Beside the SD card's `Image` in the Generate tab — [com.abrah.nightmare.SdSampler.paletteName]. */
    override val paletteName = "Video"

    /** ⭐ Named by what it is doing — the same rule as the SD nodes' [titleFor]. */
    override fun titleFor(node: com.abrah.nightmare.Node): String =
        if (node.inputs["image"] != null) "Image to video" else "Text to video"

    override val defaultId = "video"
    override val about = "a prompt, or a prompt and a photo, into a 2 second clip"

    /** ⚠⚠ The clip belongs to `core.output`, like every other result (§5.7). */
    override val showsResult = false

    override val widgets get() = listOf(
        // ⭐ 0 rolls a new clip every Run, like the sampler's seed and for the
        // same reason. ⚠ It is NOT bit-reproducible against the desktop
        // reference — java.util.Random is a different stream from torch's — so
        // the same seed gives the same clip on THIS device and no claim is made
        // beyond that.
        Widget(
            "seed", "int", "0",
            hint = "0 = a new clip every Run. Type the seed shown on the node to get it back.",
        ),
        Widget(
            "upscale", "bool", "true",
            // ⚠ 1024x640 / 512x320 — width first, which is the way round the
            // frames actually come out. Neodragon's docs use (h, w).
            hint = "2x to 1024x640 with QuickSRNet. Off renders 512x320 and is a little faster.",
        ),
        // ⭐⭐ The framing, exactly as the SD samplers carry it — `image.crop`'s
        // own param names, so `CropEditor` drives this node too with no second
        // spelling. ⚠ The crop NODE is gone from the i2v recipe: the sampler
        // fits whatever it is given, which is the rule the image path already
        // follows (docs/ARCHITECTURE.md §5.7).
        Widget("x", "float", "0.0", 0.0, 1.0, hint = "drag the frame on the picture"),
        Widget("y", "float", "0.0", 0.0, 1.0),
        Widget("w", "float", "1.0", 0.0, 1.0),
        Widget("h", "float", "1.0", 0.0, 1.0),
        Widget(com.abrah.nightmare.CropNode.LOCKED, "bool", "false"),
    )

    /**
     * ⚠⚠ **null — this node binds NOTHING at backend launch.** It is the whole
     * reason a video node does not drag a checkpoint into the graph's one
     * context key (`docs/ARCHITECTURE.md` §5.2): the binaries are loaded
     * in-process per graph and released, so there is no `--type` to agree on.
     */
    override fun contextKey(node: Node): ContextKey? = null

    /**
     * ⭐⭐ **What a picture wired into `image` has to be** — and therefore
     * what an `image.crop` in front of it makes, without anybody typing it.
     *
     * ⚠⚠ This is the whole mechanism `sd.vae_encode` uses, reused rather
     * than reinvented: declaring the demand makes [com.abrah.nightmare.Framing]
     * derive and LOCK the crop's `out_w`/`out_h`, and makes a direct
     * `image.load` wire refuse itself at the drop with "put a Crop between
     * them" — because a photo promises no size.
     *
     * ⚠ Before this, the pipeline centre-cropped whatever it was handed
     * ([Video.bitmapToChw] still does, as the backstop). That is not the same
     * thing as a crop node: the pipeline picks the middle, and the node lets
     * the user pick the shot.
     *
     * ⚠⚠ 512x320 is the ENCODE size, not the clip size. The clip comes out
     * 1024x640 because `upscale` doubles it afterwards, so this must not
     * follow that knob — wiring the crop to the output size would hand the VAE
     * an image four times too large.
     */
    /**
     * ⚠⚠ **Null — it demands nothing and FITS what it is given**, the same rule
     * the SD samplers adopted on 2026-09-15. It used to declare 512x320, which
     * made `Framing` lock a crop node in front of it and refuse a photo wired
     * straight in. That crop node is gone; the framing is four params on this
     * node and the same [com.abrah.nightmare.CropNode.render] does the work.
     */
    override fun requiredInputSize(node: Node, port: String): Pair<Int, Int>? = null

    /**
     * ⭐⭐ …but it does FRAME to an exact size, and this is the only place that
     * says so — 512x320 is a property of the compiled context, not of a param.
     * Without it the framing view in the inspector took the shape of whatever
     * photo was wired, and `Video.bitmapToChw`'s centre-crop then took a
     * different rectangle than the one the user dragged.
     */
    override fun framesTo(node: Node): Pair<Int, Int> = VideoStructure.frameSize

    /**
     * ⚠⚠⚠ **`Dispatchers.IO`, and it is NOT a tidy-up.**
     *
     * The canvas runs a graph from `viewModelScope.launch { }` with no
     * dispatcher, i.e. on **Main** — every other node stays off the UI thread
     * because [com.abrah.nightmare.Backend] switches to IO inside each HTTP
     * call, so the node bodies themselves never had to. This one does 25 s of
     * blocking JNI instead of HTTP, so without this it ran on the main thread:
     * the screen froze for the whole render and the process took a SIGSEGV
     * inside `NativeQnn.load` on `tid == pid`. Reported from the phone and read
     * off the tombstone, 2026-09-12.
     *
     * ⚠ It did not show up headlessly. `OpService` already runs its ops on a
     * worker, so the identical graph was clean there — which is exactly the
     * blind spot `docs/UI.md` §5 describes: a test that does not drive the
     * production path tests its own wiring.
     */
    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val android = ctx.android
            ?: throw IllegalStateException(
                "node \"${node.id}\": the video path needs a platform context and this " +
                    "executor has none"
            )

        // ⚠⚠ Checked BEFORE anything expensive, and by NAME. A missing binary
        // otherwise surfaces 20 s in as "Create From Binary failure", which
        // names neither the file nor the fact that it was never downloaded.
        val seed = node.params["seed"]?.toLongOrNull() ?: 0L
        val prompt = (inputs["prompt"] as? Value.Prompt)?.positive
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": nothing is wired into \"prompt\" — " +
                    "drag a Prompt node out of the palette and connect it"
            )
        val source = inputs["image"] as? Value.Image
        val needed = if (source != null) Video.requiredModels() - FIRST_FRAME_ONLY else Video.requiredModels()
        val missing = NpuFiles.missing(android, needed)
        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "node \"${node.id}\": ${missing.size} video model(s) not on this device " +
                    "(${missing.take(3).joinToString()}${if (missing.size > 3) ", …" else ""}) " +
                    "— they live in ${NpuFiles.ctxDir(android)}"
            )
        }
        val missingAssets = NpuFiles.missingAssets(android)
        if (missingAssets.isNotEmpty()) {
            throw IllegalStateException(
                "node \"${node.id}\": video weights missing: ${missingAssets.joinToString()}"
            )
        }

        // ⚠ The clip is named by a CONTENT ADDRESS of everything it was made
        // from, because that name becomes the value's address and therefore
        // part of every downstream cache key. A timestamp there would make two
        // identical clips look different to `video.output`.
        // ⭐⭐ Framed HERE, to the encoder's own size, by the same function the
        // framing view draws with. ⚠ `Video.bitmapToChw` still centre-crops as
        // a backstop; this is what lets the USER pick the shot instead.
        val bitmap: Bitmap? = source?.let { img ->
            val src = ctx.images.get(img.id)
                ?: throw IllegalStateException(
                    "node \"${node.id}\": image ${img.id} is no longer in the store"
                )
            val p = effectiveParams(node)
            fun f(k: String, d: Float) = p[k]?.toFloatOrNull() ?: d
            com.abrah.nightmare.CropNode.render(
                src, f("x", 0f), f("y", 0f), f("w", 1f), f("h", 1f),
                VideoStructure.frameSize.first, VideoStructure.frameSize.second,
                com.abrah.nightmare.CropNode.PAD_BLACK,
            ).first
        }
        // ⚠ Through `effectiveParams`, not `params[...]`: an unset knob must
        // read its DECLARED default, and comparing against "false" to mean
        // "true unless" encodes that default in a second place.
        val upscale = effectiveParams(node)["upscale"].equals("true", ignoreCase = true)
        val key = sha1(
            listOf(prompt, seed.toString(), upscale.toString(), source?.address().orEmpty())
                .joinToString("\u0000")
        )
        val out = File(File(android.cacheDir, "video").apply { mkdirs() }, "clip_$key.mp4")

        // ⚠⚠ ONE video run in this process at a time. The canvas has its own
        // `busy` guard, but the headless `OpService` does not share it — so a
        // harness op and a Run could both be mapping 1.5 GB contexts, and the
        // loser of that race is the whole process. ⚠ It refuses rather than
        // queues: waiting 25 s behind something the user cannot see is worse
        // than being told why.
        check(inFlight.compareAndSet(false, true)) {
            "node \"${node.id}\": another video render is already running"
        }
        val runner = QnnRunner(android)
        val frames: List<Bitmap>
        try {
            // ⚠⚠ A FALLBACK, not the roll. `HarnessOps.runRolled` rolls a
            // `seed = 0` on this node before the graph runs, exactly as it does
            // for `sd.sample` ([com.abrah.nightmare.SAMPLER_TYPES]) -- and it
            // has to happen there, because the cache key is computed from the
            // params: rolling only here meant the key still hashed "0" and the
            // second Run was served the FIRST clip. Reported from the phone,
            // 2026-09-12. This line survives for a caller that reaches the
            // node directly (a plain `Executor.run`, a test), where a
            // reproducible-by-accident clip would be worse than a random one.
            val used = if (seed == 0L) System.currentTimeMillis() else seed
            ctx.say("seed $used" + if (bitmap != null) ", animating your picture" else "")
            Log.i(TAG, "video: seed $used, upscale=$upscale, i2v=${bitmap != null}")

            // ⭐⭐ **Progress across the WHOLE render, not per phase.**
            //
            // ⚠⚠ The pipeline reports each phase on its own scale — the text
            // encoders as 0/1, the MMDiT as call/18 — so forwarding those
            // straight through made the bar sit at 0% for the first several
            // seconds (3 GB of context binaries being mapped, with nothing to
            // count) and then restart from zero twice. Reported from the phone
            // as the first few seconds showing nothing.
            //
            // ⇒ Each phase owns a SLICE of one 0..100 scale, and a phase's own
            // fraction moves within its slice. The weights are the measured
            // costs (docs/NEODRAGON.md): the MMDiT is ~2/3 of the render.
            var base = 0
            var span = PHASE_WEIGHT.getValue("text encoders")
            val r = Video(android, runner) { line ->
                // ⚠ The pipeline's own lines, live in the run panel. They are
                // indented and wordy by design — they were written to be read
                // while waiting.
                ctx.say(line.trim())
                Log.i(TAG, line)
            }.generate(prompt, used, bitmap) { stage, i, n ->
                val key = PHASE_WEIGHT.keys.firstOrNull { stage.startsWith(it) }
                if (key != null) {
                    base = PHASE_START.getValue(key)
                    span = PHASE_WEIGHT.getValue(key)
                }
                val within = if (n > 0) i * span / n else 0
                // ⚠ `atMs` is the backend's own timing field and there is no
                // backend here, so it is 0: a number invented on this side would
                // be a second clock disagreeing with the panel's.
                ctx.onProgress(
                    com.abrah.nightmare.Ops.Progress((base + within).coerceIn(0, 100), 100, 0L)
                )
                Log.i(TAG, "  $stage $i/$n")
            }
            frames = r.frames
            ctx.say("${frames.size} frames in ${"%.1f".format(r.seconds)} s — encoding")
            Log.i(TAG, "video: ${frames.size} frames in ${"%.1f".format(r.seconds)} s")
        } finally {
            // ⚠⚠ ALWAYS. Three MMDiT contexts are ~4.6 GB of mappings; leaving
            // them resident after a failed run is how the next node in the
            // graph dies of memory pressure rather than of its own bug.
            runner.releaseAll()
            inFlight.set(false)
        }

        check(frames.isNotEmpty()) { "node \"${node.id}\": the pipeline produced no frames" }
        VideoWriter.encode(android, frames, FPS, out) { line -> Log.i(TAG, line) }

        // ⭐⭐ The LOOP, built here because this is the only moment the full-size
        // frames exist — they are dropped as soon as this function returns.
        // [com.abrah.nightmare.ClipStore] thins and scales them; a node draws
        // the result as a GIF rather than as a still. The user's call,
        // 2026-09-12: *"just loop the frames as a gif, in both canvas view and
        // inside node view"*.
        com.abrah.nightmare.ClipStore.put(out.absolutePath, frames)

        // ⚠ ONE frame into the picture store — see [Value.Video]. It is still
        // needed: the poster is what Save, Share and Keep act on, what Results
        // files, and what a node draws before the loop is built or after the
        // process has been restarted.
        val posterId = ctx.images.put(frames.first())
        Value.Video(out.absolutePath, frames.size, frames[0].width, frames[0].height, posterId)
    }

    /**
     * ⚠ The three graphs only the FIRST-FRAME path needs (1.68 GB). With an
     * image wired in, SSD1B never runs and they are not required to be present
     * at all — refusing a run for a model the flow will not touch is the kind
     * of gate that makes a working device look broken.
     */
    private val FIRST_FRAME_ONLY = listOf("clipl", "ssd1bunet", "ssd1bvaedec")

    /**
     * ⭐ What each phase is worth on a single 0..100 scale, in the order the
     * pipeline runs them.
     *
     * ⚠⚠ The keys are PREFIXES of the labels `Video.generate` passes out
     * ("MMDiT unit 3 stage 1" starts with "MMDiT"), so a label gaining detail
     * does not silently fall out of the table. A label that matches nothing
     * leaves the bar where it was rather than jumping to zero.
     *
     * ⚠ Measured shares, not guesses: 23.4 s of which the MMDiT loop is ~13 s
     * and the first frame ~2.5 s (`docs/NEODRAGON.md`). The remainder is model
     * loading, which is charged to the phase that triggers it — which is why
     * "text encoders" is worth 15% despite being 12 ms of compute.
     */
    private val PHASE_WEIGHT = linkedMapOf(
        "text encoders" to 15,
        "first frame" to 15,
        "using supplied image" to 15,
        "MMDiT" to 60,
        "decode" to 10,
    )

    /** ⚠ Derived, so the two tables cannot disagree about where a phase starts. */
    private val PHASE_START: Map<String, Int> = buildMap {
        var at = 0
        for ((k, w) in PHASE_WEIGHT) {
            put(k, at)
            // ⚠ The two first-frame labels are ALTERNATIVES (i2v skips SSD1B),
            // so they share one slice instead of each advancing the bar.
            if (k != "first frame") at += w
        }
    }

    /** ⚠ Process-wide: the canvas and the harness are different callers. */
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val TAG = "VideoSample"

    /** ⚠ 24, matching the 49 frames the pipeline emits for a ~2 s clip. */
    const val FPS = 24

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
}

/**
 * ⭐ Where a clip ends — the gallery.
 *
 * ⚠ The same shape as `image.output` and for the same reasons: a `save` switch
 * rather than an automatic write (a graph is run repeatedly while it is being
 * built, and each Run would otherwise leave a file behind), and it returns its
 * input so the canvas can still draw what was produced.
 */
object VideoOutputNode : NodeType {
    override val name = "video.output"
    override val version = "1"
    override val inputs = listOf(Port("video", "VIDEO"))
    override val outputs = emptyList<Port>()
    override val category = "video"

    /** ⚠ Has a side effect, so it must never be served from the cache. */
    override val cacheable = false

    /**
     * ⭐⭐ **`save` starts TRUE here, where `image.output` starts false** — a
     * deliberate difference, and one the user reaffirmed after the app learned
     * to play clips: *"for video output node, save should be always true"*
     * (2026-09-12).
     *
     * ⚠⚠ The original reason was that nothing could play a clip, so a run
     * that left the MP4 in `cacheDir` produced something the user could not
     * watch. That reason is **gone** — fullscreen plays it now
     * ([com.abrah.nightmare.ui.ClipPlayer]) — and the default stays anyway,
     * because the file underneath it does not: a clip lives in `cacheDir`,
     * which Android clears without asking, where a picture the user is looking
     * at can be saved from the viewer afterwards.
     *
     * ⚠⚠ It is a **CHECKBOX**, not a text field. It rendered as one — typing
     * the word `true` on a phone keyboard, where `True` reads as false and
     * nothing says so until a Run that quietly wrote nothing — because the
     * inspector had no `bool` branch at all (`canvas/NodeInspector.kt`).
     *
     * ⚠ The switch stays and turning it off is still right while iterating on
     * a prompt; it is the DEFAULT that is true, not the value.
     */
    override val widgets = listOf(
        Widget(
            "save", "bool", "true",
            hint = "write an MP4 to Movies/Nightmare, which outlives the app's cache",
        ),
        Widget("name", "string", "nightmare"),
    )

    override fun contextKey(node: Node): ContextKey? = null

    /** ⚠ IO for the same reason the sampler is: this copies an MP4 through MediaStore. */
    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val video = inputs["video"] as? Value.Video
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": input \"video\" is not connected"
            )
        // ⚠⚠ `effectiveParams`, because `params["save"]` is ABSENT on a node
        // dropped from the palette -- so a `video.output` the user added by
        // hand declared `save = true`, drew a ticked box, and wrote nothing.
        // Only the shipped recipe, which spells the param out, ever saved.
        if (!effectiveParams(node)["save"].equals("true", ignoreCase = true)) {
            return@withContext video
        }

        val android = ctx.android
            ?: throw IllegalStateException(
                "node \"${node.id}\": saving needs a platform context and this executor has none"
            )
        val file = File(video.path)
        check(file.isFile) {
            "node \"${node.id}\": the clip is gone from ${file.parent} — run the sampler again"
        }
        val stem = node.params["name"].orEmpty().ifBlank { "nightmare" }
        VideoWriter.publish(android, file, "${stem}_${System.currentTimeMillis()}.mp4") {
            Log.i("VideoOutput", it)
        }
        video
    }
}
