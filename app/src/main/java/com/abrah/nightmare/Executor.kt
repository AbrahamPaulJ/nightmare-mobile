package com.abrah.nightmare

/**
 * The executor: a topologically ordered walk of a [Graph] that runs only the
 * nodes whose result it does not already have.
 *
 * ⭐ This is the difference between a node graph and a preset picker with wires
 * (docs/ARCHITECTURE.md §4). `/sample` was already content-addressed and `/handles`
 * already reported what was resident; nothing consulted either, so re-running
 * an unchanged node re-rendered it.
 *
 * ⚠⚠ The cache is TWO facts, not one, and both are required:
 *
 *  1. the app's `key -> value` memory, which survives as long as this object;
 *  2. `GET /handles`, which says whether the backend still holds the tensor
 *     that value names.
 *
 * Trusting (1) alone serves a handle the backend no longer has -- after a
 * restart, an idle release, or an eviction -- and `/vae_decode` answers that
 * with an error rather than with a wrong picture, so the failure is at least
 * loud. Trusting (2) alone cannot work at all: the executor never computes a
 * backend handle id, it only remembers one. ⇒ [prune] is what joins them, and
 * `graph` pass D on the device is the check that it is really consulted -- a
 * pure in-app cache passes every other pass identically.
 */

/** What happened to one node in one run. */
enum class Outcome { RAN, CACHED, FAILED, BLOCKED }

/**
 * ⭐⭐ A node that cannot run until a PERSON does something — thrown, not
 * returned, and reported as [Outcome.BLOCKED] with no graph error.
 *
 * ⚠⚠ The chaining rule (`docs/ARCHITECTURE.md`, "Chains through a node a person
 * must act on"): an inpaint fed a GENERATED picture has nothing to repaint
 * until someone paints on that picture, so everything upstream runs and is
 * cached, this node stops with the reason, and the next Run carries on.
 * ⚠ Not a failure: nothing went wrong, and a red run bar would say it had.
 */
class NeedsInput(message: String) : Exception(message)

data class NodeRun(
    val id: String,
    val type: String,
    val outcome: Outcome,
    val ms: Long,
    val detail: String,
)

data class GraphRun(
    val runs: List<NodeRun>,
    val outputs: Map<String, Value>,
    val totalMs: Long,
    /** Non-null when the graph could not be run at all (bad shape, no backend). */
    val error: String? = null,
    /**
     * Cached values dropped this run because the backend no longer held them.
     * ⚠ Reported rather than logged internally: it is the only visible sign
     * that residency was consulted at all, and a run that pruned nothing looks
     * identical to one that never asked.
     */
    val prunedHandles: Int = 0,
    /** ⭐ The first node that stopped for a person ([NeedsInput]) — its id and why. */
    val waiting: Pair<String, String>? = null,
) {
    val ran get() = runs.count { it.outcome == Outcome.RAN }
    val cached get() = runs.count { it.outcome == Outcome.CACHED }
    val failed get() = runs.count { it.outcome == Outcome.FAILED }
}

/**
 * The backend, as the executor sees it.
 *
 * ⚠ An interface only so the executor can be tested on the JVM without a phone,
 * an NPU or a 1.2 GB checkpoint. It is NOT an abstraction layer over transports:
 * [BackendHost] is the one real implementation and the ops it wraps mirror
 * `backend-patches/003-op-endpoints.patch` one-for-one.
 */
interface OpHost {
    /** Resident handle ids, or null when the backend could not be asked. */
    suspend fun residentHandles(): Set<String>?

    /**
     * ⚠⚠ **No prompt.** The text lives in `encode_text` and reaches the
     * sampler as [condHandle] -- ComfyUI's shape, where a KSampler takes
     * CONDITIONING and has no text field at all. `/sample` is still the FUSED
     * op and its JSON still carries a prompt string (docs/ARCHITECTURE.md §3),
     * so the empty one goes on at the wire boundary in [BackendHost] rather
     * than here: this interface is what a NODE may ask for, and a node may no
     * longer ask for text.
     */
    suspend fun sample(
        steps: Int,
        cfg: Double,
        seed: Int,
        width: Int,
        height: Int,
        /** Non-null for img2img: start from this latent instead of from noise. */
        latentHandle: String? = null,
        denoise: Double = 0.6,
        /**
         * ⚠ Which sampler. Defaults to the backend's own so a caller that does
         * not care cannot accidentally change anyone's output.
         */
        scheduler: String = ModelCatalog.DEFAULT_SCHEDULER,
        /** The conditioning the graph encoded. Required -- see [SampleNode]. */
        condHandle: String,
        /**
         * ⭐ `w:h` for a fixed-canvas family, or null for the plain square.
         *
         * ⚠ NOT a size. It asks the backend to paint a centered rectangle of
         * that shape into the canvas it was launched with; the canvas, and so
         * the context key, is unchanged (`ModelCatalog.aspectTarget`).
         */
        aspect: String? = null,
        /** ⭐ An inpaint node's picture + mask, PNG — see `Ops.sample`. */
        inpaintImage: ByteArray? = null,
        inpaintMask: ByteArray? = null,
        onProgress: (Ops.Progress) -> Unit,
    ): Ops.Result<Ops.Sampled>

    suspend fun vaeDecode(latentHandle: String, width: Int, height: Int): Ops.Result<Ops.Decoded>

    /**
     * ⭐ The whole render in one call — see `Ops.generate`. Only the DiT
     * families take this path. ⚠ Defaulted to a refusal so a host that does not
     * serve it (every test fake written before it) says so by name.
     */
    suspend fun generate(
        prompt: String, negative: String, steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, imagePng: ByteArray?, denoise: Double,
        /** ⚠ Klein's masked redraw only; the backend refuses a mask with no image. */
        maskPng: ByteArray? = null,
        /** ⚠ Klein's edit references, at their own aspect ratios. */
        referencePngs: List<ByteArray> = emptyList(),
        /** ⭐ See `Backend.generate` — DiT only. */
        loras: List<Pair<String, Double>> = emptyList(),
        onProgress: (Ops.Progress) -> Unit,
    ): Ops.Result<Ops.Decoded> = Ops.Result.Err(501, "this host cannot run a whole-render model")

    /**
     * ⚠ **mask = 1 takes [b]**, matching `/generate`'s own per-step blend where
     * the mask means "repaint". Blending the other way round produces a
     * plausible picture with the wrong region replaced and raises nothing.
     */
    suspend fun latentBlend(a: String, b: String, maskPng: ByteArray): Ops.Result<Ops.Blended>

    suspend fun vaeEncode(png: ByteArray, seed: Int, width: Int, height: Int): Ops.Result<Ops.Sampled>

    suspend fun encodeText(prompt: String, negative: String): Ops.Result<Ops.Cond>

    /**
     * ⭐⭐ 4x a picture, through the upscaler weight file at [upscalerPath].
     *
     * ⚠⚠ **Takes a PATH, not bytes, and it is the only op here that does.** The
     * backend opens the file itself, so a 24 MB weight never crosses the wire —
     * and, more importantly, the upscaler is loaded per request and freed after,
     * so this op joins no context key and forces no relaunch. `Upscalers.kt`
     * has the whole table of how an upscaler differs from a checkpoint.
     */
    suspend fun upscale(
        rgb: ByteArray,
        width: Int,
        height: Int,
        upscalerPath: String,
    ): Ops.Result<Ops.Upscaled>
}

/** The real one. */
object BackendHost : OpHost {
    override suspend fun residentHandles() = Ops.handles()

    override suspend fun upscale(rgb: ByteArray, width: Int, height: Int, upscalerPath: String) =
        Ops.upscale(rgb, width, height, upscalerPath)

    override suspend fun sample(
        steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, latentHandle: String?, denoise: Double,
        scheduler: String, condHandle: String, aspect: String?,
        inpaintImage: ByteArray?, inpaintMask: ByteArray?,
        onProgress: (Ops.Progress) -> Unit,
        // ⚠ Named, not positional. Ops.sample grew preview arguments BEFORE
        // onProgress, and a positional forward silently bound the callback to
        // the wrong parameter -- caught here only because the types happened to
        // disagree. Named arguments make the next insertion a non-event.
    ) = Ops.sample(
        // ⚠⚠ The empty prompt is not a placeholder for one nobody typed. The
        // backend requires the FIELD to be present (`Missing 'prompt'`), and
        // ignores its value entirely once `cond_handle` is set -- the supplied
        // conditioning replaces the CLIP encode outright
        // (backend-patches/002-pipeline-op-surface.patch). It still counts
        // towards the backend's own content-addressed key, where it is constant
        // and the cond TENSOR hash is what separates two renders.
        prompt = "", negative = "", steps = steps, cfg = cfg, seed = seed,
        width = width, height = height, latentHandle = latentHandle, denoise = denoise,
        scheduler = scheduler, condHandle = condHandle, aspect = aspect,
        inpaintImage = inpaintImage, inpaintMask = inpaintMask,
        onProgress = onProgress,
    )

    override suspend fun vaeDecode(latentHandle: String, width: Int, height: Int) =
        Ops.vaeDecode(latentHandle = latentHandle, width = width, height = height)

    override suspend fun generate(
        prompt: String, negative: String, steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, imagePng: ByteArray?, denoise: Double,
        maskPng: ByteArray?,
        referencePngs: List<ByteArray>,
        loras: List<Pair<String, Double>>,
        onProgress: (Ops.Progress) -> Unit,
    ) = Ops.generate(
        prompt = prompt, negative = negative, steps = steps, cfg = cfg, seed = seed,
        width = width, height = height, imagePng = imagePng, denoise = denoise,
        maskPng = maskPng, referencePngs = referencePngs, loras = loras,
        onProgress = onProgress,
    )

    override suspend fun latentBlend(a: String, b: String, maskPng: ByteArray) =
        Ops.latentBlend(a = a, b = b, maskPng = maskPng)

    override suspend fun vaeEncode(png: ByteArray, seed: Int, width: Int, height: Int) =
        Ops.vaeEncode(png = png, seed = seed, width = width, height = height)

    override suspend fun encodeText(prompt: String, negative: String) =
        Ops.encodeText(prompt, negative)
}

/**
 * What a node gets to work with while it runs.
 *
 * ⚠ [android] is nullable so the executor can be tested with no Android at all.
 * A node that needs it says so by failing with a sentence, which is better than
 * a test suite that cannot construct the executor.
 */
class NodeCtx(
    val host: OpHost,
    val images: ImageStore,
    val android: android.content.Context? = null,
    val onProgress: (Ops.Progress) -> Unit,
    /**
     * ⭐⭐ A line of narration, WHILE the node runs.
     *
     * ⚠⚠ [onProgress] cannot carry this and should not be made to: a fraction
     * says how far along something is, and what a person waiting 25 s wants is
     * *what is happening*. The video node spends its first seconds mapping 3 GB
     * of context binaries — a phase with no steps to count, where a percentage
     * is honestly 0 and a name is honestly "loading the text encoders".
     *
     * ⚠ Defaulted to a no-op, so every node with nothing to narrate and every
     * existing construction of this class is unchanged.
     */
    val say: (String) -> Unit = {},
    /**
     * ⭐⭐ A line the PERSON must see, not only the run log — the app toasts it.
     * For a node that did not do what it was asked and still succeeded (the
     * output's upscale refusing a picture already too big), which otherwise
     * looks exactly like a button that did nothing. ⚠ It is also logged, so
     * the run log keeps its record; defaulted to [say] for every construction
     * that has no toast to give.
     */
    val warn: (String) -> Unit = say,
    /**
     * ⭐⭐ Which of THIS node's output ports something downstream actually
     * reads.
     *
     * ⚠⚠ It exists for one real cost: `nd.clip_encode` produces two
     * conditionings, and the one the first frame needs requires `clipl`
     * (234 MB) to be mapped and executed. In an image-to-video graph nothing
     * consumes it, and paying for it anyway would undo the saving that makes
     * i2v the cheaper path (`docs/NEODRAGON.md` §8).
     *
     * ⚠ Empty means "nothing downstream", which is the honest answer for a
     * terminal node — a node must still produce its primary output, because
     * that is what the canvas draws.
     */
    val wanted: Set<String> = emptySet(),
    /**
     * ⭐ The types of every node upstream of this one. ⚠ For the chaining rule
     * ([NeedsInput]): an inpaint must know whether its picture was GENERATED,
     * which is a fact about the graph, not about the pixels.
     */
    val ancestorTypes: Set<String> = emptySet(),
)

/**
 * A node type: what it costs to run, what backend it needs, and how to run it.
 *
 * ⚠ [version] is part of every cache key derived from this type. Bump it when
 * the implementation changes what the node produces, or the app keeps serving
 * outputs computed by code that no longer exists. A plugin node's version is
 * its plugin's, so a hot reload invalidates exactly its own outputs.
 */
interface NodeType {
    val name: String
    val version: String

    /**
     * What the canvas draws and what a wire may attach to.
     *
     * ⚠ Declared, not inferred from the graph. An input with nothing connected
     * still has to be visible -- that is the whole state a user is in before
     * they connect it.
     */
    val inputs: List<Port>
    val outputs: List<Port>

    /** Groups nodes in the palette, and is the only thing that earns a hue (docs/UI.md §1). */
    val category: String

    /**
     * ⭐⭐ What the PALETTE calls this, when the stripped type name is not enough.
     *
     * ⚠⚠ The sampler fork of 2026-09-15 put FOUR entries in `generate` whose
     * stripped names are `sample`, `sample`, `inpaint`, `inpaint` — a list with
     * two identical rows and nothing to choose between them. Reported the same
     * day: *"why are there two sample in generate without any other desc?"*
     *
     * ⚠ Defaults to the stripped type name, so every other node and every plugin
     * is unchanged.
     */
    val paletteName: String get() = name.substringAfterLast('.').substringAfterLast(':')

    /** ⭐ One line under the palette row saying what it is FOR. Empty for none. */
    val about: String get() = ""

    /**
     * ⭐⭐ Which palette CARD this type belongs on, and the chip that picks it.
     *
     * ⚠ Types sharing a [paletteGroup] draw as ONE card whose chips are their
     * [paletteVariant]s — three families of the same job are one thing a person
     * wants, not three rows (the user's call, 2026-09-16). Defaults to the type
     * name with no variant, so every other node and every plugin is its own card.
     */
    val paletteGroup: String get() = name
    val paletteVariant: String? get() = null

    /**
     * ⭐⭐ What a node of this type is called ON THE CANVAS, given how it is wired —
     * `SDXL Inpaint`, `SD 1.5 Image to image`. Null for the stripped type name.
     *
     * ⚠⚠ The user's call, 2026-09-16: *"remove the word sample all around"* and
     * name a node by its family and what it is doing. The TYPE id (`sdxl.sample`)
     * is what a saved flow and a manifest depend on and does not change.
     */
    fun titleFor(node: Node): String? = null

    /** ⭐ The id a NEW node of this type is given, before [Graph.freeId] numbers it. Null for the stripped name. */
    val defaultId: String? get() = null

    /**
     * ⭐⭐ How wide a NEW node of this type is, in world units, or null for the
     * ordinary 190.
     *
     * ⚠ A node the user has resized keeps their size — this is only the birth
     * width. ⚠⚠ It is here rather than in the layout because the reason is the
     * TYPE's: a prompt holds prose, an output holds the picture you came to
     * look at, and both were columns 26 characters wide. The user's call,
     * 2026-09-15.
     */
    val defaultWidth: Float? get() = null

    /**
     * ⭐⭐⭐ False for a node that RENDERS: its result belongs to `core.output`.
     *
     * ⚠⚠ The user's call, 2026-09-15 — *"sample shouldn't show output"*. A
     * render appearing on the node that made it reads as the end of the flow,
     * so a graph could finish with its picture nowhere in particular and still
     * look complete. ⇒ **One place shows a result, and a graph has to say where
     * it goes.** [rendersNowhere] refuses a run that does not.
     *
     * ⚠ It is NOT `appSide == false`: `image.crop` shows its picture and always
     * should — that picture is an INPUT being framed, not a result. The
     * distinction is "did this node make it", not "was it expensive".
     *
     * ⚠⚠ This reverses the reasoning of 2026-09-13 that deleted the output node
     * because "every terminal node draws its own result". That rule was ours and
     * the user changed it (`CLAUDE.md`, *The rules here are ours to change*).
     */
    val showsResult: Boolean get() = true

    /**
     * ⭐ True for a type that still RUNS but is no longer offered in the palette.
     *
     * ⚠⚠ The rework of docs/ARCHITECTURE.md §5.7 replaces ten types with one
     * fused sampler, and the two sets have to coexist for exactly one build —
     * long enough to render the old inpaint graph and the new one at the same
     * seed and compare them. Unregistering the old types instead would make that
     * comparison impossible, and shipping them in the palette would offer a user
     * two ways to do the same thing.
     *
     * ⚠ They are DELETED in the next push, not hidden forever. A permanently
     * hidden node type is a node type nobody maintains.
     */
    val hidden: Boolean get() = false

    /**
     * The knobs the inspector shows.
     *
     * ⚠ Declared even for built-ins. Before this, a built-in's params existed
     * only as whatever the graph happened to carry, so the canvas had no way to
     * offer them — the prompt was editable nowhere.
     */
    val widgets: List<Widget> get() = emptyList()

    /**
     * The backend process this node needs, or null if it runs app-side and
     * needs none — every Tier 0 node, and the reason a graph of them can run
     * with no backend at all.
     */
    fun contextKey(node: Node): ContextKey?

    /**
     * ⚠⚠ False for a node with a SIDE EFFECT.
     *
     * The cache says "this node already produced this result", which is a lie
     * for a node whose result is a file on disk: the second Run would skip it
     * and silently save nothing, and the user would have pressed the button
     * twice and received one picture. A node that touches the world outside the
     * graph must run every time it is asked.
     */
    val cacheable: Boolean get() = true

    /**
     * ⭐⭐ True when this node runs **entirely in the app**, with no backend.
     *
     * ⚠⚠ **This is what makes a node safe to run for a PREVIEW**, and it is a
     * flag rather than `contextKey == null` because those two stopped meaning
     * the same thing the moment `image.upscale` existed. `Previews.kt` said so
     * in advance: `encode_text` has no context key — encoding a prompt does not
     * care about the resolution — and calls the backend anyway, and it was safe
     * only because it emits a COND rather than an IMAGE, so the IMAGE filter in
     * `previewTargets` never reached it.
     *
     * `image.upscale` breaks that: it has no context key (the upscaler is
     * loaded per request from a path, so it never joins the launch contract),
     * it emits an IMAGE, and it calls `/upscale`. Under the old test, opening a
     * canvas containing one would have STARTED THE BACKEND and run a 4x
     * upscale, because a sheet was opened. ⇒ Declared, once, here.
     *
     * ⚠ Default TRUE, which is right for every Tier 0 plugin: a contributor's
     * node calls host ops that are app-side by construction (`ARCHITECTURE` §2),
     * and the four built-ins that reach the server override it.
     */
    val appSide: Boolean get() = true

    /**
     * ⭐ True when this node's INSPECTOR is where its picture is worked on.
     *
     * ⚠⚠ The canvas needs it for one decision it cannot otherwise make: a tap
     * on a node's preview. For an ordinary node that means "let me look at
     * this", so it opens the viewer; for an interactive one the picture IS the
     * control, so it must open the node's own editor instead. Tapping the
     * cropper's picture and getting a fullscreen copy of it is the gesture
     * landing on the wrong thing entirely.
     */
    val interactive: Boolean get() = false

    /**
     * ⭐⭐ The exact pixel size this node NEEDS on [port], or null when any
     * size will do.
     *
     * ⚠⚠ Declared rather than discovered, and that is the whole point: before
     * this, `vae_encode`'s "3 * w * h or nothing" was knowable only by running
     * it, so the canvas could draw a graph it knew would fail and say nothing
     * until the user pressed Run and waited. A demand a consumer can be ASKED
     * for is what lets `crop` size itself and what lets a doomed wire be refused
     * while the finger is still down.
     *
     * ⚠ It takes the node, not just the port name, because the demand usually
     * comes from that node's own params -- here the context-key resolution.
     */
    fun requiredInputSize(node: Node, port: String): Pair<Int, Int>? = null

    /**
     * ⭐⭐ **The exact size this node's FRAMING view produces**, or null when it
     * does not frame or its size is already a param.
     *
     * ⚠⚠ It exists for the one node whose framing size is in neither place:
     * the video sampler renders 512x320 because that is what its compiled QNN
     * context was built for, and no param says so. `framingOutSize` guessed from
     * `out_w`/`out_h` then `width`/`height`, found neither, and the cropper came
     * out the shape of the user's photo — which the encoder then centre-cropped
     * without saying. Reported 2026-09-15.
     *
     * ⚠ Distinct from [requiredInputSize], which is a demand made of whatever
     * is WIRED IN and is null for every node that fits what it is given. This is
     * what the node emits after fitting it.
     */
    fun framesTo(node: Node): Pair<Int, Int>? = null

    /**
     * ⭐⭐ The exact pixel size this node PROMISES, or null when it cannot.
     *
     * ⚠⚠ Null is not "unknown to us", it is **"this node refuses to promise"**,
     * and `load_image` is the case that matters: its output is whatever the
     * photo happens to be. That is exactly why a `load_image` may not be wired
     * straight into a `vae_encode` -- not because the size is wrong, but because
     * nothing can say it is right.
     */
    fun outputSize(node: Node): Pair<Int, Int>? = null

    /**
     * True when this node's output size is DERIVED from whoever consumes it.
     *
     * ⚠ `crop` alone today. The canvas writes the demand into its params and
     * locks them, so the node stays a pure function of what it holds and a
     * headless run produces exactly what the editor showed.
     */
    val sizedByConsumer: Boolean get() = false

    /**
     * ⭐⭐ Param names whose VALUES this node shows in its body on the canvas.
     *
     * ⚠ For a node whose whole content is text. A prompt node's ports carry a
     * conditioning, so without this the canvas can only draw a box with a name
     * and the only way to see what it says is to open the inspector.
     *
     * ⚠ Declared by the TYPE rather than matched by name, so a plugin node that
     * holds prose gets the same treatment and one that merely has a `prompt`
     * param does not.
     */
    val prose: List<String> get() = emptyList()

    /**
     * The params as the node will actually be run with, defaults filled in.
     *
     * ⚠⚠ The CACHE KEY is computed from this, not from what the user typed. A
     * widget left at its default must key the same as one set explicitly to
     * that value, or the two are different nodes to the cache and the user sees
     * a re-render for touching nothing.
     */
    fun effectiveParams(node: Node): Map<String, String> = applyDefaults(widgets, node)

    suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value

    /**
     * ⭐⭐ Every output port this node produces — the general form of [run].
     *
     * ⚠⚠ Defaulted to "[run]'s value, under the first port", so every
     * single-output node in the app is unchanged and none of them had to be
     * touched. A type that genuinely makes two things overrides THIS and lets
     * [run] delegate to it.
     *
     * ⚠ The executor caches per PORT, so a two-output node whose consumers
     * were added one at a time does not recompute the half it already had.
     */
    suspend fun runPorts(
        ctx: NodeCtx,
        node: Node,
        inputs: Map<String, Value>,
        // ⚠⚠ `firstOrNull`, not `first`: a SINK declares no outputs at all
        // (`save_image`), and its value is still recorded -- under a name no
        // wire can spell -- so the sink keeps appearing in `GraphRun.outputs`
        // while staying unwireable. `first()` here crashed every sink in the
        // app the moment multi-output landed.
    ): Map<String, Value> =
        mapOf(outputs.firstOrNull()?.name.orEmpty() to run(ctx, node, inputs))
}

/**
 * ⚠ Throws rather than defaulting. A missing widget is a malformed graph, and a
 * default here would render something plausible that the user did not ask for.
 */
internal fun Node.str(name: String): String =
    params[name] ?: throw IllegalArgumentException("node \"$id\": missing param \"$name\"")

internal fun Node.int(name: String): Int =
    str(name).toIntOrNull()
        ?: throw IllegalArgumentException("node \"$id\": param \"$name\" is not an int: ${params[name]}")

private fun Node.dbl(name: String): Double =
    str(name).toDoubleOrNull()
        ?: throw IllegalArgumentException("node \"$id\": param \"$name\" is not a number: ${params[name]}")

/**
 * ⭐⭐ The [ContextKey] of a node that names a checkpoint -- the ONE place the
 * three backend node types derive one.
 *
 * ⚠⚠ `--type` used to be the literal `"sd15npu"` written out four times: here
 * three times and once in [BackendProcess]. Four literals that must agree are a
 * bug waiting for the second family, and the failure is silent rather than
 * loud -- `sdxl` forces 1024 inside the backend's request parser whatever the
 * client sends, so a node keyed at 512 against an SDXL process renders 1024 and
 * reports success. ⇒ Both sides now read [ModelCatalog.backendTypeOf], and a
 * family is data.
 *
 * ⚠ Keyed on the node's OWN `model` param, not on [SelectedModel]: the graph is
 * authoritative (`docs/MODELS.md` §4), and it is what the executor refuses two
 * of.
 */
/**
 * ⭐⭐ The aspect chip, declared **only by the families that can honour it**.
 *
 * ⚠⚠ Not a context-key knob and deliberately not locked: `aspect_ratio` is a
 * REQUEST field that costs no relaunch (`ModelCatalog.aspectTarget`). Locking
 * it would say the opposite of what is true, and putting it in the key would
 * make a change of shape cost 2.3-5 s for nothing.
 *
 * ⚠ Absent on SD 1.5 rather than present-and-ignored: the backend only reads
 * `aspect_ratio` for `sdxl`/`anima`, so on SD 1.5 this would be a knob that
 * silently does nothing — and SD 1.5 has real resolutions instead.
 *
 * ⚠ A spread, so a family switch simply removes it. An `aspect` param already
 * written stays in `node.params` and keeps being hashed into the cache key
 * (that is `applyDefaults`' rule), which is correct: it is the value the node
 * would use again if the user switched back.
 */
/** ⚠ `internal`: the fused sampler declares the same knob, and a second
 * copy of "which families crop to shape" is the drift `aspectAgrees` stops. */
/*
 * ⚠⚠ [spec] is the model of the TYPE declaring it — `SdSampler` passes its own
 * family's. Reading the top-bar selection here gave an SD 1.5 node an aspect
 * chip whenever SDXL was selected, which read as "the model switch did nothing"
 * (2026-09-17). The legacy types still default to the selection.
 */
internal fun aspectWidget(spec: ModelSpec = SelectedModel.spec): Array<Widget> =
    if (!spec.fixedCanvas) emptyArray()
    else arrayOf(
        Widget(
            "aspect", "string", ModelCatalog.DEFAULT_ASPECT,
            options = ModelCatalog.ASPECTS,
            hint = "this family renders a fixed ${spec.native} and crops to shape — " +
                "it costs no reload, but a wider picture is not a bigger one",
        )
    )

/**
 * ⭐ Keep `aspect` identical on every node in the graph that declares it.
 *
 * ⚠⚠ **The sampler and the decoder must agree or the picture is wrong, and
 * nothing would report it.** `/sample` uses the ratio to place the inpaint
 * rectangle; the decode node uses it to cut that rectangle back out. Two
 * different values crop the wrong region of a correctly rendered canvas — a
 * plausible picture, off centre, with no error anywhere.
 *
 * ⇒ Written graph-wide from one choice, by WIDGET rather than by node type, for
 * the same reason [modelRecipeRetarget] is: a plugin node declaring `aspect`
 * gets the same treatment and one that does not is left alone.
 */
fun aspectRetarget(
    graph: Graph,
    types: Map<String, NodeType>,
    aspect: String,
): Map<String, Map<String, String>> {
    val out = LinkedHashMap<String, Map<String, String>>()
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        // ⚠⚠ Only the FIXED-CANVAS aspect ([aspectWidget]'s vocabulary):
        // `image.crop` declares an `aspect` of its own, and a ratio written there
        // from a sampler's chip would re-frame a crop nobody touched.
        val w = t.widgets.firstOrNull { it.name == "aspect" } ?: continue
        if (w.options != ModelCatalog.ASPECTS) continue
        if (n.params["aspect"] == aspect) continue
        out[n.id] = mapOf("aspect" to aspect)
    }
    return out
}

/**
 * ⭐⭐ The `aspect` this node should actually act on, or **null**.
 *
 * ⚠⚠ **A leftover `aspect` param is normal and must be ignored, not obeyed.**
 * The chip is only DECLARED by a fixed-canvas family ([aspectWidget]), but a
 * param already written stays in `node.params` when the graph is retargeted to
 * SD 1.5 — that is `applyDefaults`' rule, and it is right, because switching
 * back should restore the user's choice. What was wrong was ACTING on it:
 * `RequestParser.hpp` only reads `aspect_ratio` for `sdxl`/`anima`, so on SD 1.5
 * the backend rendered a full frame while the app cropped it anyway. Reported
 * from the phone 2026-09-11 as a decode of **432x768** — 768 with a stale 9:16
 * applied to it.
 *
 * ⚠ Keyed on the node's OWN `model`, never [SelectedModel]: the graph is
 * authoritative (`docs/MODELS.md` §4), and this must agree with what the
 * SAMPLER sent for the same node.
 */
fun nodeAspect(node: Node): String? {
    val spec = ModelCatalog.byId(node.params["model"].orEmpty()) ?: return null
    if (!spec.fixedCanvas) return null
    return node.params["aspect"]
}

fun backendContextKey(node: Node): ContextKey {
    // ⭐⭐ A DiT model's size is a REQUEST field, not a launch one: no patch,
    // no graph compiled per shape. Keying on the node's width/height would
    // relaunch the backend for every slider move, so it keys on the model's
    // native size — one process serves every size.
    val dit = ModelCatalog.byId(node.str("model"))?.takeIf { it.isDit }
    return ContextKey(
        ModelCatalog.backendTypeOf(node.str("model")),
        node.str("model"),
        dit?.native?.width ?: node.int("width"),
        dit?.native?.height ?: node.int("height"),
    )
}

/**
 * ⭐ The checkpoints this graph names, in order, without duplicates.
 *
 * ⚠ Read off the WIDGET declaration rather than off `contextKey()`, so it
 * cannot throw on a malformed param -- it is asked on the path that OPENS a
 * workflow, and a graph that will not run must still open.
 *
 * ⚠ `.size > 1` here is the same condition the executor refuses a run for; this
 * is just the version the UI can ask before there is anything to refuse.
 */
fun contextKeyModels(graph: Graph, types: Map<String, NodeType>): List<String> =
    graph.nodes.mapNotNull { n ->
        val t = types[n.type] ?: return@mapNotNull null
        if (t.widgets.none { it.name == "model" && it.contextKey }) null
        else n.params["model"]
    }.distinct()

/**
 * ⭐ The resolutions this graph's backend nodes name, without duplicates.
 *
 * ⚠ The sibling of [contextKeyModels] and for the same reason: read off the
 * WIDGET declaration rather than off `contextKey()`, so it cannot throw on a
 * malformed param. It is asked on the path that OPENS a workflow, and a graph
 * that will not run must still open.
 *
 * ⚠ A node missing either half contributes nothing rather than a half-formed
 * size — `applyDefaults` will fill it in, and guessing here would make the
 * adopted resolution depend on which node happened to be incomplete.
 */
fun contextKeyResolutions(graph: Graph, types: Map<String, NodeType>): List<Res> =
    graph.nodes.mapNotNull { n ->
        val t = types[n.type] ?: return@mapNotNull null
        val keyed = t.widgets.filter { it.contextKey }.map { it.name }.toSet()
        if ("width" !in keyed || "height" !in keyed) return@mapNotNull null
        val w = n.params["width"]?.toIntOrNull() ?: return@mapNotNull null
        val h = n.params["height"]?.toIntOrNull() ?: return@mapNotNull null
        Res(w, h)
    }.distinct()

/**
 * ⭐⭐ What to write into each node so the whole graph names [spec] -- i.e. the
 * "which rewrites every node" half of the executor's own advice.
 *
 * ⚠⚠ It was advice about something that did not exist. A graph naming a model
 * the user had deleted (or switched away from mid-edit) reports "needs 2 backend
 * contexts", and the fix it prints is "open Models and select one, which
 * rewrites every node" -- but selecting a model only moved a global, so a user
 * who followed the instruction exactly stayed stuck, with three locked knobs
 * they could not edit and no other way out.
 *
 * ⚠ Driven by the WIDGET declaration rather than by a list of param names, so a
 * plugin node that declares its own context-key knobs is retargeted too, and a
 * node that merely happens to carry a param called `width` is not.
 *
 * ⚠ Returns only nodes that actually change, so a caller can tell "nothing to
 * do" from "done" without diffing the graph.
 */
fun contextKeyRetarget(
    graph: Graph,
    types: Map<String, NodeType>,
    spec: ModelSpec,
    /**
     * ⚠ The size to write, defaulting to the model's native one. Passed
     * explicitly when the user picks a resolution: that is the SAME act as
     * picking a model -- both rewrite the whole graph's context key, which is
     * what keeps §5.2's one-key-per-graph pin true while the size is editable.
     */
    res: Res = spec.native,
): Map<String, Map<String, String>> {
    val wanted = mapOf(
        "model" to spec.id,
        "width" to res.width.toString(),
        "height" to res.height.toString(),
    )
    val out = LinkedHashMap<String, Map<String, String>>()
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        val keyed = t.widgets.filter { it.contextKey }.map { it.name }.toSet()
        val change = wanted.filterKeys { it in keyed && n.params[it] != wanted[it] }
        if (change.isNotEmpty()) out[n.id] = change
    }
    return out
}

/**
 * ⭐⭐ The new model's own recipe, written ONTO the graph.
 *
 * ⚠⚠ **This exists because a default resolved at RUN time is not
 * reproducible.** `steps`/`cfg`/`scheduler` default from the selected model
 * ([ModelSpec]), so a node carrying none of them renders differently after a
 * model switch while the graph on disk is byte-identical. The numbers the
 * inspector shows are the new model's; what actually ran is whatever the
 * defaults resolved to at that moment, and nothing records which.
 *
 * ⇒ Switching models WRITES the new model's numbers into every sampler, so
 * displayed == stored == rendered. ⚠ It overwrites a value set by hand, and
 * that is the intended trade: asking for a checkpoint is asking for its
 * published recipe. The change is announced in the run log, never silent.
 *
 * ⚠ Deliberately NOT `seed` — that one is the user's, and switching models is
 * no reason to lose a seed they locked.
 */
fun modelRecipeRetarget(
    graph: Graph,
    types: Map<String, NodeType>,
    spec: ModelSpec,
): Map<String, Map<String, String>> {
    val out = LinkedHashMap<String, Map<String, String>>()
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        // ⚠ By WIDGET, not by node type: a plugin sampler declaring the same
        // knobs gets the same treatment, and one that does not is left alone.
        val has = t.widgets.map { it.name }.toSet()
        val wanted = mapOf(
            "steps" to spec.steps.toString(),
            "cfg" to spec.cfg.toString(),
            "scheduler" to spec.scheduler,
        )
        val change = wanted.filterKeys { it in has && n.params[it] != wanted[it] }
        if (change.isNotEmpty()) out[n.id] = change
    }
    return out
}

/**
 * ⭐⭐ The new model's STARTER PROMPT, written onto the graph -- but only over
 * text the app itself put there.
 *
 * ⚠⚠ **This is the one retarget that must not overwrite the user.**
 * [modelRecipeRetarget] overwrites `steps`/`cfg` by design, because asking for
 * a checkpoint is asking for its published recipe. A prompt is the opposite:
 * it is the sentence the user came to write, and a model switch that erased it
 * would be the worst kind of data loss -- silent, and on the one field nothing
 * else in the app can reconstruct.
 *
 * ⚠ So "untouched" is decided by VALUE, not by tracking edits: a field is the
 * app's to rewrite when it is blank or still carries some catalogue model's own
 * prompt, which is exactly the set of strings this function and the recipes
 * ever write. Anything else was typed by a person and is left alone. That is
 * `local-dream`'s `!prefs.hasSaved` rule without the per-model store -- a graph
 * is not a screen, and the text lives in the file rather than in preferences.
 *
 * ⚠ Both fields or neither, and keyed on the node declaring BOTH widgets --
 * the CLIP-encode shape. A plugin node with a lone `prompt` string widget means
 * something else by it.
 */
/**
 * ⚠⚠ The literals the RECIPES carried before they read the model — app-written
 * text that predates [ModelSpec.prompt] being consumed at all.
 *
 * Without them the feature looks broken on the one graph everybody has: the
 * canvas autosave was built from a recipe that hardcoded these, so every
 * existing canvas would read as "the user typed this" and never pick up a
 * checkpoint's own prompt. ⚠ The risk is the mirror image and it is small: a
 * user who deliberately kept `a cat on grass` loses it on a model switch, and
 * can type it back. ⚠ Frozen — never extend this with a value the app still
 * writes, or the untouched test stops meaning anything.
 */
private val LEGACY_RECIPE_TEXT = setOf(
    "a cat on grass",
    "blurry, lowres",
    "masterpiece, best quality, highly detailed,",
)

fun modelPromptRetarget(
    graph: Graph,
    types: Map<String, NodeType>,
    spec: ModelSpec,
): Map<String, Map<String, String>> {
    val out = LinkedHashMap<String, Map<String, String>>()
    // ⚠ Every model's, not just the previously selected one: A -> B -> C must
    // still recognise A's prompt as ours. ⚠ `all` rather than `builtIn`, so an
    // imported model's `config.json` prompt counts too.
    // ⚠⚠ The FAMILY defaults count as ours too, and leaving them out was the
    // bug waiting to happen: since 2026-09-15 a new graph opens on
    // [ModelSpec.starterPrompt], which is the family text whenever a checkpoint
    // has none — so text this app wrote a minute ago would have read as
    // "the user typed this" and frozen forever.
    val ours = ModelCatalog.all.flatMap { listOf(it.prompt, it.negative) }.toSet() +
        Family.entries.flatMap { listOf(it.prompt, it.negative) } +
        "" + LEGACY_RECIPE_TEXT
    val wanted = mapOf("prompt" to spec.starterPrompt, "negative" to spec.starterNegative)
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        val has = t.widgets.map { it.name }.toSet()
        if (!has.containsAll(wanted.keys)) continue
        val change = wanted.filterKeys { k ->
            n.params[k].orEmpty() in ours && n.params[k] != wanted[k]
        }
        if (change.isNotEmpty()) out[n.id] = change
    }
    return out
}


/**
 * conditioning + seed -> latent handle. The fused sampler of ARCHITECTURE §3.
 *
 * ⭐⭐ **It has no prompt.** The text lives in exactly one place --
 * `encode_text` -- and arrives here as a wire, which is ComfyUI's shape: a
 * KSampler takes CONDITIONING and carries no text field at all.
 *
 * ⚠ It carried both for a while, because `/sample` is the FUSED op and takes
 * prompt strings; the cond wire came later. That left two sources of truth for
 * one thing, and the inspector had to explain in words that the box the user
 * could type in was ignored. A knob that must apologise for itself is the wrong
 * knob (docs/ARCHITECTURE.md §3).
 *
 * ⚠ ONE cond port, where ComfyUI has two (positive and negative). Not a
 * simplification: `/encode_text` encodes prompt AND negative into a single
 * tensor whose halves the UNet reads as the CFG batch, so a graph that wired
 * them separately would be lying about what the backend can do.
 */
/**
 * ⭐⭐⭐ **The process scheduler** — a topological order that switches backend
 * context as few times as it can.
 *
 * `docs/ARCHITECTURE.md` §4. `--type`, `--model_dir` and `--patch` bind at
 * BACKEND LAUNCH, so changing checkpoint or resolution is a kill + relaunch
 * costing **2.3–5 s** — about a whole render. A graph using two checkpoints was
 * REFUSED until 2026-09-15; now it is ordered.
 *
 * ⭐ The rule, in one sentence a user can predict: **finish everything runnable
 * under the checkpoint that is already loaded before switching.** When nothing
 * is runnable under it, switch to whichever key has the most ready work.
 *
 * ⚠⚠ Not optimal in general — minimising transitions over a DAG is a hard
 * problem — and it does not need to be: a canvas holds a handful of samplers,
 * and a rule a person can state beats one they cannot. ⚠ Where two keys tie,
 * the user's own node ORDER breaks it, which is §4's rule for batch axes and
 * the same instinct here.
 *
 * ⚠ A node with a NULL key (every app-side node, `image.upscale`, the video
 * path) runs under whatever is loaded and never forces a switch — that property
 * is what makes a mixed graph tractable at all.
 *
 * @param order a valid topological order; the result is a permutation of it.
 */
fun scheduleByKey(
    order: List<Node>,
    keyOf: (Node) -> ContextKey?,
    /**
     * ⭐ The key the backend ALREADY holds, when one does. Work under it runs
     * first, so a graph naming the loaded checkpoint never starts by leaving it.
     */
    start: ContextKey? = null,
): List<Node> {
    if (order.size < 2) return order
    val keys = order.associate { it.id to keyOf(it) }
    if (keys.values.filterNotNull().distinct().size < 2) return order

    // Dependencies as ids, so "ready" is a set test rather than a graph walk.
    val deps = order.associate { n -> n.id to n.inputs.values.map { it.node }.toSet() }
    val done = mutableSetOf<String>()
    val left = order.toMutableList()
    val out = ArrayList<Node>(order.size)
    // ⚠ Starts at what is loaded, or null — then the FIRST key is chosen by the
    // same rule as every other switch rather than by whichever node is first.
    var current: ContextKey? = start

    while (left.isNotEmpty()) {
        val ready = left.filter { done.containsAll(deps.getValue(it.id)) }
        // ⚠ A graph whose remaining nodes are all blocked is a cycle, which
        // `topoSort` refuses before this runs. Emitting the rest unchanged is
        // the safe answer rather than looping forever.
        if (ready.isEmpty()) { out += left; break }

        // Everything that needs no key, and everything already under it.
        val free = ready.filter { keys[it.id] == null || keys[it.id] == current }
        if (free.isNotEmpty()) {
            for (n in free) { out += n; done += n.id }
            left.removeAll(free.toSet())
            continue
        }
        // Nothing runnable here: switch to the key with the most ready work.
        // ⚠ `maxByOrNull` keeps the FIRST maximum, and `ready` is in the user's
        // node order — so a tie is broken the way they laid the graph out.
        current = ready.mapNotNull { keys[it.id] }
            .groupingBy { it }.eachCount()
            .entries.maxByOrNull { it.value }?.key
    }
    return out
}

/**
 * ⭐⭐ The context key a Run must LAUNCH with: the first one [scheduleByKey]
 * will reach. Null when the graph names none, or cannot be ordered.
 *
 * ⚠⚠ Launching for anything else is a wasted start. Run used to launch for the
 * GLOBAL selection, so a sampler naming another checkpoint paid a start for the
 * selected model and then a relaunch for its own — on every Run, because the
 * next Run launched the selection again. Reported from the phone, 2026-09-16:
 * *"its always starting the model on every run"*.
 *
 * ⚠ [loaded] is passed through for the same reason the executor passes it: a
 * two-checkpoint graph should begin with whichever one is already up.
 */
fun launchKeyFor(graph: Graph, types: Map<String, NodeType>, loaded: ContextKey?): ContextKey? {
    val order = (topoSort(graph) as? Order.Ok)?.nodes ?: return null
    fun keyOf(n: Node) = try {
        types[n.type]?.contextKey(n)
    } catch (e: IllegalArgumentException) {
        null
    }
    return scheduleByKey(order, ::keyOf, loaded).firstNotNullOfOrNull(::keyOf)
}

/**
 * ⭐ How many backend relaunches [order] will cost, for the run bar to say so
 * BEFORE the button is pressed.
 *
 * ⚠ Counted off the SCHEDULED order, never the graph: the whole point of
 * [scheduleByKey] is that the count depends on the order chosen.
 */
fun keyTransitions(order: List<Node>, keyOf: (Node) -> ContextKey?): Int {
    var current: ContextKey? = null
    var n = 0
    for (node in order) {
        val k = keyOf(node) ?: continue
        if (k != current) { if (current != null) n++; current = k }
    }
    return n
}

object SampleNode : NodeType {
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val name = "sd.sample_legacy"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    /**
     * ⚠ 2: the prompt left the node, so nothing it cached under 1 is comparable.
     * ⚠ 3: `scheduler` is sent. Everything cached under 2 was rendered with the
     * backend's `dpm` default, and a cached latent from then is not what this
     * node would produce now -- on a model whose default is not `dpm` it is a
     * visibly different picture.
     */
    override val version = "3"
    /**
     * ⚠ `latent` is OPTIONAL: connected it means img2img, unconnected it means
     * start from noise. The executor only requires the inputs a graph actually
     * wires, so one node type covers both — and a separate "img2img sampler"
     * node would be the same op with a different name and a second cache key.
     *
     * ⚠⚠ `cond` is NOT optional, and it is the only input that is not. An
     * unwired sampler cannot render anything at all now, so it refuses by name
     * in [run] rather than sending an empty prompt to CLIP and returning the
     * soup that a null conditioning produces.
     */
    override val inputs = listOf(Port("cond", "COND"), Port("latent", "LATENT"))
    override val outputs = listOf(Port("latent", "LATENT"))
    override val category = "sampling"
    override val widgets get() = listOf(
        // ⚠⚠ Defaults from the MODEL, not from a literal. A distilled
        // checkpoint publishes something like 10 steps at cfg 1.5, and this
        // app's 20/7.5 renders it burnt and oversaturated rather than failing
        // -- measured on device 2026-09-10. A checkpoint that says nothing gets
        // ModelCatalog.DEFAULT_STEPS/DEFAULT_CFG, which are these same numbers.
        Widget("steps", "int", SelectedModel.spec.steps.toString(), 1.0, 50.0),
        // ⚠ `fine`: the slider weights its travel toward 1.0 and keeps two
        // decimals there, because a distilled checkpoint lives between 1.0 and
        // 2.0 and a linear 1..20 track gives that band 5% of its length.
        Widget("cfg", "float", SelectedModel.spec.cfg.toString(), 1.0, 20.0, fine = true),
        // ⭐ 0 rolls a new seed on every Run. A fixed seed is what makes a
        // render reproducible; 0 is what makes Run mean "give me another one"
        // instead of returning the cached picture unchanged.
        Widget(
            "seed", "int", "0",
            hint = "0 = a new picture every Run. Type the seed shown on the node to get that one back.",
        ),
        // ⚠ Only read when `latent` is connected. 1.0 renoises completely,
        // which is txt2img with extra steps -- the backend defaults to 0.6 for
        // the same reason.
        Widget("denoise", "float", "0.65", 0.0, 1.0),
        // ⭐⭐ The sampler. ⚠⚠ This node did not send one until 2026-09-10, so
        // every render this app ever made used the backend's `dpm` default --
        // including checkpoints whose author published a different one. On a
        // distilled model at its own settings (10 steps, cfg 1.5) that is the
        // difference between a crunchy over-sharpened picture and a clean one.
        //
        // ⚠ Defaults from the MODEL, like `model`/`width`/`height` above: the
        // checkpoint knows which sampler it was tuned for and the user should
        // not have to. ⚠ Chips, not text -- an unknown value falls through to
        // `dpm` inside the backend in silence (`ModelCatalog.SCHEDULERS`).
        Widget(
            "scheduler", "string", SelectedModel.spec.scheduler,
            options = ModelCatalog.SCHEDULERS,
            hint = "the sampler; a distilled model usually needs the one its author published",
        ),
        // ⚠ model / width / height are the CONTEXT KEY (§4), not ordinary
        // knobs: changing either costs a backend relaunch, and v1 pins one key
        // for the whole graph. They are listed so the inspector can show them,
        // and the executor still refuses a graph that needs two.
        Widget("model", "string", SelectedModel.id, locked = CONTEXT_KEY_LOCK, contextKey = true),
        *aspectWidget(),
        // ⚠⚠ contextKey but NOT locked: a size is chosen ON a node now, and
        // choosing it rewrites every other backend node in the graph
        // ([contextKeyRetarget]) rather than leaving two keys behind. The
        // inspector replaces this pair with ONE chip row of the sizes the
        // model's patches actually make reachable -- a free number field would
        // let a user type 640, which has no patch and fails at launch.
        Widget("width", "int", SelectedModel.res.width.toString(), contextKey = true),
        Widget("height", "int", SelectedModel.res.height.toString(), contextKey = true),
    )

    override fun contextKey(node: Node) = backendContextKey(node)

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        // ⚠⚠ Required, and refused HERE with the fix in the sentence. A user who
        // drags a Sampler out of the palette has a node that cannot run until
        // they wire text into it, and "missing param prompt" would have pointed
        // at a knob that no longer exists.
        val cond = inputs["cond"]
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": nothing is wired into \"cond\" — " +
                    "the prompt lives in a Text Encode node now; connect one"
            )
        if (!(cond is Value.Handle && cond.kind == "cond")) {
            throw IllegalArgumentException(
                "node \"${node.id}\": input \"cond\" carries ${cond.describe()}, " +
                    "not a conditioning"
            )
        }
        // ⚠ Absent is normal, not an error: an unconnected `latent` is txt2img.
        val from = inputs["latent"]
        if (from != null && !(from is Value.Handle && from.kind == "latent")) {
            throw IllegalArgumentException(
                "node \"${node.id}\": input \"latent\" carries ${from.describe()}, not a latent"
            )
        }
        val r = ctx.host.sample(
            steps = node.int("steps"),
            cfg = node.dbl("cfg"),
            seed = node.int("seed"),
            width = node.int("width"),
            height = node.int("height"),
            latentHandle = (from as? Value.Handle)?.id,
            denoise = node.dbl("denoise"),
            scheduler = node.str("scheduler"),
            condHandle = cond.id,
            // ⚠ Only a ratio that actually crops is sent. `1:1` (and anything
            // malformed) resolves to null, which is exactly what the backend
            // does with equal terms -- so the two agree on "no aspect" without
            // the app having to know that.
            aspect = nodeAspect(node)
                ?.takeIf { ModelCatalog.aspectTarget(it, Res(node.int("width"), node.int("height"))) != null },
            // ⚠ No previews from the executor yet. They cost a VAE decode per
            // stride (measured by the `preview` op), so turning them on is a
            // per-node choice the canvas makes, not a default.
            onProgress = ctx.onProgress,
        )
        return when (r) {
            is Ops.Result.Ok -> Value.Handle(r.value.handle, "latent")
            is Ops.Result.Err -> throw OpFailure("sample", r.code, r.body)
        }
    }
}

/** latent handle -> pixels. */
object VaeDecodeNode : NodeType {
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val name = "sd.vae_decode"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = listOf(Port("latent", "LATENT"))
    override val outputs = listOf(Port("image", "IMAGE"))
    override val category = "latent"
    override val widgets get() = listOf(
        Widget("model", "string", SelectedModel.id, locked = CONTEXT_KEY_LOCK, contextKey = true),
        // ⚠⚠ contextKey but NOT locked: a size is chosen ON a node now, and
        // choosing it rewrites every other backend node in the graph
        // ([contextKeyRetarget]) rather than leaving two keys behind. The
        // inspector replaces this pair with ONE chip row of the sizes the
        // model's patches actually make reachable -- a free number field would
        // let a user type 640, which has no patch and fails at launch.
        Widget("width", "int", SelectedModel.res.width.toString(), contextKey = true),
        Widget("height", "int", SelectedModel.res.height.toString(), contextKey = true),
        // ⚠ Declared here as well as on the sampler because THIS node is where
        // the crop happens -- see [run]. [aspectRetarget] keeps the two equal.
        *aspectWidget(),
    )

    override fun contextKey(node: Node) = backendContextKey(node)

    /**
     * ⚠⚠ **The aspect crop that `/generate` does and the op path does not.**
     *
     * `opSample` sets `return_latent = true`, and `generate()` returns on that
     * flag (`Pipeline.hpp`) **before** `decodeToPixels` and therefore before its
     * own `needsAspectCrop` / `cropCenter` at the tail. So on the monolithic
     * path a 16:9 request yields a 1024x576 picture, and on the decomposed node
     * path the identical request yields the target rectangle **letterboxed
     * inside a full 1024² frame** — sampled correctly, never cut out.
     *
     * ⇒ Reattached here, in Kotlin, on the decoded bitmap. App-side by choice:
     * no rebuild, no patch to regenerate, and it cannot break the shared C++
     * that `/generate` still depends on.
     *
     * ⚠ Centred, because the backend centres the rectangle it paints
     * (`req.width - paint_w) / 2`). Off-by-one here is an off-centre picture
     * with nothing to report it, which is why [ModelCatalog.aspectTarget]
     * mirrors the C++ sum exactly rather than approximating the ratio.
     */
    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val latent = inputs["latent"]
            ?: throw IllegalArgumentException("node \"${node.id}\": input \"latent\" is not connected")
        if (latent !is Value.Handle || latent.kind != "latent") {
            throw IllegalArgumentException(
                "node \"${node.id}\": input \"latent\" carries ${latent.describe()}, not a latent"
            )
        }
        return decode(ctx, latent.id, node.int("width"), node.int("height"), nodeAspect(node))
    }

    /**
     * ⭐⭐ latent -> pixels, aspect crop included — and the ONLY implementation.
     *
     * ⚠⚠ The fused sampler ends with this call (docs/ARCHITECTURE.md §5.7). The
     * aspect crop below is the half that must not be re-typed anywhere: it
     * exists because `/sample` returns before the C++ does its own crop, so a
     * second copy that forgot it would letterbox every non-square picture.
     */
    suspend fun decode(
        ctx: NodeCtx,
        latentId: String,
        w: Int,
        h: Int,
        aspect: String?,
    ): Value.Image {
        return when (val r = ctx.host.vaeDecode(latentId, w, h)) {
            is Ops.Result.Ok -> {
                val full = ctx.images.decode(r.value.png)
                    ?: throw OpFailure("vae_decode", 200,
                        "returned ${r.value.png.size} B that would not decode as an image")
                val target = aspect?.let { ModelCatalog.aspectTarget(it, Res(w, h)) }
                if (target == null || (target.width == full.width && target.height == full.height)) {
                    // ⭐ The backend already hashed these pixels, so its rgb_sha
                    // IS the content address -- no reason to hash a megabyte
                    // again, and using the same id everywhere means a decode and
                    // a re-decode are interchangeable to everything downstream.
                    val id = ctx.images.put(full, png = r.value.png, id = "img_" + r.value.rgbSha)
                    return Value.Image(id, full.width, full.height)
                }
                // ⚠ Refuse rather than clamp: a target bigger than the canvas
                // means [ModelCatalog.aspectTarget] and the backend disagree,
                // and silently shrinking the crop would hide exactly the drift
                // that comment warns about.
                if (target.width > full.width || target.height > full.height) {
                    throw OpFailure("vae_decode", 200,
                        "aspect $aspect wants ${target.width}x${target.height} " +
                            "out of a ${full.width}x${full.height} canvas")
                }
                val cropped = cropCenter(full, target.width, target.height)
                // ⚠⚠ A DIFFERENT id, derived from the backend's hash plus the
                // crop. Reusing `rgb_sha` would address the uncropped frame,
                // so a graph that changed only its aspect would serve the
                // previous shape's pixels out of the image store.
                val id = ctx.images.put(
                    cropped, id = "img_" + r.value.rgbSha + "_${target.width}x${target.height}",
                )
                Value.Image(id, cropped.width, cropped.height)
            }
            is Ops.Result.Err -> throw OpFailure("vae_decode", r.code, r.body)
        }
    }
}

/** ⚠ Carries the backend's own words. Its error bodies name the real problem. */
class OpFailure(op: String, val code: Int, val body: String) :
    RuntimeException("$op failed http $code — ${explainOpFailure(code, body).take(200)}")

/**
 * ⭐⭐⭐ **A backend that DIED, said as such** — rather than the exception the
 * socket happened to throw on the way down.
 *
 * ⚠⚠⚠ Reported 2026-09-22: a FLUX.2 Klein render with one LoRA failed as
 * `generate failed http -1 — EOFException: no message`, which is the truth and
 * tells a person nothing. `-1` is [Backend]'s code for "the call threw", and
 * when the process is also gone the call did not fail — **the backend did**,
 * and its own last `[ ERROR ]` line is very often precise
 * ([BackendProcess.failureReason], whose whole reason for existing was the same
 * kind of answer being invisible).
 *
 * ⚠⚠ **The socket's words are KEPT, not replaced.** `EOFException` (the
 * process vanished mid-response) and `SocketTimeoutException` (it is alive and
 * slow — `Backend.STAGE_TIMEOUT_MS`) are different failures, and a message that
 * threw the distinction away would cost the next diagnosis what this one cost.
 *
 * ⚠ Memory is *a* likely cause on a phone, never a certain one, so it is
 * offered as the first thing to try and not stated as the reason. When the
 * engine left a real error, that error leads.
 */
internal fun explainOpFailure(code: Int, body: String): String {
    if (code != -1 || BackendProcess.isRunning) return body
    val why = BackendProcess.failureReason()
    return "the backend stopped" + (why?.let { ": $it" } ?: "") +
        " — if it keeps happening, close other apps or try a smaller canvas [$body]"
}

/**
 * ⭐ A picture from the device, as the start of a graph — **whole, and at its
 * own size.**
 *
 * ⚠⚠ It used to scale-and-centre-crop to a declared square, and that was a
 * framing decision made silently by the node that can least justify one: a
 * portrait photo lost its head before anything downstream could say otherwise,
 * and a `crop` node placed after it was choosing a framing inside a framing
 * somebody else had already imposed. ⇒ The node is deliberately DUMB now. It
 * decodes the file and hands it on; `crop` is where framing is decided, and it
 * is the node that knows what shape is wanted (`docs/UI.md` §5).
 *
 * ⚠⚠ Consequently it PROMISES NO SIZE — [outputSize] is null, because the
 * answer is "whatever the user photographed". That is what refuses a wire
 * straight into `vae_encode`, which needs an exact 512²: not because the size is
 * wrong but because nothing can say it is right.
 *
 * ⚠ NOT cacheable. The file behind a URI can change while its id does not, and
 * a graph that quietly kept rendering last week's photo would be very hard to
 * disbelieve.
 */
object LoadImageNode : NodeType {
    override val name = "core.image"
    /**
     * ⚠⚠ **`image`, not `photo`** — the user's call, 2026-09-22: *"dont call
     * it a photo node, image is the correct term"*. The node takes anything the
     * picker hands it, and a render fed into a flow from Results is not a photo.
     * ⚠ `defaultId` names NEW nodes only; a saved flow keeps the ids it has.
     */
    override val defaultId = "image"
    // ⚠ 3: pictures are decoded UPRIGHT now (EXIF orientation, `ImageStore.decode`).
    override val version = "3"
    override val inputs = emptyList<Port>()
    override val outputs = listOf(Port("image", "IMAGE"))
    /** ⚠ `source`, with the prompt: it is where a flow STARTS, not a pixel op. */
    override val category = "source"
    override val cacheable = false
    override val widgets = listOf(
        // A content:// URI from the picker, or an absolute path.
        Widget("uri", "string", ""),
    )

    override fun contextKey(node: Node): ContextKey? = null

    /**
     * ⚠⚠ The long edge a decode is allowed to reach, and it is a memory
     * limit rather than a quality one.
     *
     * A 12 MP phone photo is ~48 MB as ARGB_8888; `ImageStore` holds a dozen
     * images and `hashPixels` copies the whole buffer to content-address it, so
     * a handful of untouched originals is an OOM rather than a slowdown. 4096 is
     * far above anything a 512² pipeline can use and far above what a finger can
     * frame, so nothing visible is lost. ⚠ Done with `inSampleSize`, so the
     * bytes are never decoded at full size in the first place.
     */
    const val MAX_EDGE = 4096

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val uri = node.str("uri")
        require(uri.isNotBlank()) { "node \"${node.id}\": no image chosen" }
        // ⚠ NOT named `android`: a local of that name shadows the `android.*`
        // package, and `android.net.Uri` then resolves against the Context.
        val platform = ctx.android
            ?: throw IllegalStateException(
                "node \"${node.id}\": loading an image needs a platform context"
            )

        val bytes = try {
            if (uri.startsWith("content://")) {
                platform.contentResolver.openInputStream(android.net.Uri.parse(uri))
                    ?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("nothing to read at $uri")
            } else {
                java.io.File(uri).readBytes()
            }
        } catch (e: SecurityException) {
            // ⚠ Named, because this is what a URI outliving its permission grant
            // looks like -- a saved workflow reopened after a reboot.
            throw IllegalStateException(
                "node \"${node.id}\": no longer permitted to read $uri — pick the image again"
            )
        }

        val decoded = ctx.images.decode(bytes, MAX_EDGE)
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": $uri is not an image this device can decode"
            )
        return Value.Image(ctx.images.put(decoded), decoded.width, decoded.height)
    }
}

/**
 * ⭐ prompt -> conditioning. CLIP as a node.
 *
 * ⚠ It imposes **no context key**. Text encoding runs on the same backend as
 * everything else, but it does not care about the resolution — and claiming a
 * key it does not need would make a graph refuse to mix a text encode with a
 * sampler at another size for no reason.
 *
 * ⚠ Cheap and heavily cached: 129 ms cold, **0-1 ms** once the backend has seen
 * the prompt (docs/ARCHITECTURE.md §3). The value of this node is that the
 * canvas can SHOW conditioning as a wire, not that it saves time.
 */
object TextEncodeNode : NodeType {
    // ⚠ Both prompts, in the order they are read. The negative is second
    // because it is the one people check rather than compose.
    override val prose = listOf("prompt", "negative")

    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val name = "sd.clip_encode"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = emptyList<Port>()
    override val outputs = listOf(Port("cond", "COND"))
    override val category = "conditioning"
    /**
     * ⭐ The ONLY place a prompt is written. [SampleNode] used to carry a copy
     * and ignore it whenever this node was wired; it no longer has one.
     *
     * ⚠ A default rather than none. A node added from the palette must arrive
     * with every param present, or it fails with "missing param" -- an error
     * about the app rather than about the empty prompt the user can plainly
     * see.
     *
     * ⭐⭐ **And the default is the CHECKPOINT's, not a literal** -- the same
     * rule [SampleNode] applies to `steps`/`cfg`/`scheduler`, extended to the
     * two fields upstream carries beside them ([ModelSpec.prompt]). A prompt
     * style is the thing a checkpoint's author knows and we cannot guess: an
     * anime model and a photographic one want opposite negatives, and the app
     * shipped the catalogue's per-model text while showing every new node an
     * empty box. ⭐ `local-dream`'s `Model.codeDefaults` + `config.json`, which
     * DreamUI dropped for one hardcoded prompt. The user's ask, 2026-09-12.
     *
     * ⚠ An IMPORTED model's is empty unless its own `config.json` says
     * otherwise ([CustomModels.Config]) -- deliberately. We know nothing about
     * a checkpoint someone brought, and handing it a built-in's prompt would
     * bias it toward a model it is not.
     */
    override val widgets get() = listOf(
        Widget(
            "prompt", "string", SelectedModel.spec.starterPrompt,
            hint = "what to draw — the sampler reads it through the cond wire",
        ),
        Widget(
            "negative", "string", SelectedModel.spec.starterNegative,
            hint = "what to keep out of the picture",
        ),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        when (val r = ctx.host.encodeText(node.str("prompt"), node.str("negative"))) {
            is Ops.Result.Ok -> Value.Handle(r.value.handle, "cond")
            is Ops.Result.Err -> throw OpFailure("encode_text", r.code, r.body)
        }
}

/**
 * pixels -> latent. The way a picture gets back INTO a graph.
 *
 * ⚠ It imposes the SAME context key as the sampler and the decoder: the VAE
 * encoder graph is sized by the backend's launch-time dimensions like
 * everything else, so an encode at a resolution the backend was not launched
 * with cannot work.
 */
object VaeEncodeNode : NodeType {
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val name = "sd.vae_encode"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    override val outputs = listOf(Port("latent", "LATENT"))
    override val category = "latent"
    override val widgets get() = listOf(
        Widget("model", "string", SelectedModel.id, locked = CONTEXT_KEY_LOCK, contextKey = true),
        // ⚠⚠ contextKey but NOT locked: a size is chosen ON a node now, and
        // choosing it rewrites every other backend node in the graph
        // ([contextKeyRetarget]) rather than leaving two keys behind. The
        // inspector replaces this pair with ONE chip row of the sizes the
        // model's patches actually make reachable -- a free number field would
        // let a user type 640, which has no patch and fails at launch.
        Widget("width", "int", SelectedModel.res.width.toString(), contextKey = true),
        Widget("height", "int", SelectedModel.res.height.toString(), contextKey = true),
        // ⚠ A VAE latent is mean + std * noise, so the seed is what makes the
        // same image encode to the same latent -- and therefore what lets the
        // executor cache anything downstream of it.
        // ⚠⚠ NOT the generation seed, and it deliberately does NOT roll. If both
        // seeds were random the picture could never be reproduced, and rolling
        // this one would re-encode the source image every Run for no benefit.
        Widget(
            "seed", "int", "42",
            hint = "encoding noise, not the picture seed — it changes the result only slightly, so leave it fixed. The sample node's seed is the one that matters.",
        ),
    )

    override fun contextKey(node: Node) = backendContextKey(node)

    // ⭐ The demand the canvas reads: it is what sizes a `crop` upstream and
    // what refuses a `load_image` wired straight in. ⚠ From the node's own
    // params, which the context key has already pinned.
    override fun requiredInputSize(node: Node, port: String) =
        if (port == "image") node.int("width") to node.int("height") else null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val image = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": input \"image\" is not connected"
            )
        val png = ctx.images.png(image.id)
            ?: throw IllegalStateException(
                "node \"${node.id}\": image ${image.id} is no longer in the store"
            )
        val r = ctx.host.vaeEncode(png, node.int("seed"), node.int("width"), node.int("height"))
        return when (r) {
            is Ops.Result.Ok -> Value.Handle(r.value.handle, "latent")
            is Ops.Result.Err -> throw OpFailure("vae_encode", r.code, r.body)
        }
    }
}

/**
 * ⭐ The end of a graph: show the picture, and optionally keep it.
 *
 * ARCHITECTURE §5.3 node 8 merges Comfy's `PreviewImage` and `SaveImage` — on a
 * phone canvas, two nodes that differ by one boolean is one node too many.
 *
 * ⚠⚠ NOT cacheable, because saving is a side effect: a cached Output would skip
 * the write on the second Run and the user would press the button twice for one
 * file.
 */
object OutputNode : NodeType {
    override val name = "image.output"
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    // ⚠ No outputs: this is where a graph ends. It still returns its input so
    // the harness and the canvas can show what was produced.
    override val outputs = emptyList<Port>()
    override val category = "image"
    override val cacheable = false
    override val widgets = listOf(
        Widget("save", "bool", "false"),
        Widget("name", "string", "nightmare"),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val image = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": input \"image\" is not connected"
            )
        // ⚠ `effectiveParams`, so an unset knob reads its declared default
        // rather than being false by accident. See [VideoOutputNode].
        if (!effectiveParams(node)["save"].equals("true", ignoreCase = true)) return image

        val png = ctx.images.png(image.id)
            ?: throw IllegalStateException(
                "node \"${node.id}\": image ${image.id} is no longer in the store"
            )
        val android = ctx.android
            ?: throw IllegalStateException(
                "node \"${node.id}\": saving needs a platform context and this executor has none"
            )
        ImageSaver.savePng(android, png, node.params["name"].orEmpty())
        return image
    }
}

/**
 * ⭐⭐ Framing, as pixels — the helpers every framing node uses.
 *
 * ⚠⚠ **No longer a node.** `image.crop` was deleted 2026-09-17 at the user's call:
 * no flow used it and the samplers frame their own input (§5.7). What survived is
 * what the SAMPLERS are made of — [render], the padding choices and the lock — so
 * the name stays on the one implementation of a crop rather than moving to a new
 * one. `WorkflowIo.migrateCropNodes` rebuilds a saved flow that still names one.
 *
 * ⚠ The rect is NORMALISED (0..1 of the source). Pixel coordinates would be
 * wrong the moment the same graph was pointed at a photo of a different size,
 * which is exactly what a saved workflow does.
 */
object CropNode {

    /** How the bars are filled when the picture cannot fill the frame. */
    const val PAD_BLACK = "black"

    /**
     * ⭐⭐ The picture's own edges, mirrored outwards and BLURRED.
     *
     * ⚠⚠ It replaced a hard mirror, which was the obvious thing and the wrong
     * one: a reflection meets the photo at a seam the eye goes straight to, and
     * the bars end up busier than the picture they surround. Blurred, the same
     * fill reads as depth -- it is what a phone gallery and every video player
     * put behind a letterboxed frame, for the same reason.
     *
     * ⚠ The blur is a MIRROR TILING OF A DOWNSCALED COPY, not a convolution.
     * One 64-px bitmap drawn back up through a bilinear filter is a soft, wide
     * blur for the price of a scale -- and it is a blur the CANVAS EDITOR can
     * reproduce exactly, which a RenderEffect on a hardware layer could not.
     * [CropEditor] tiles the same small bitmap, so the preview is not a lie.
     */
    const val PAD_BLUR = "blur"

    /**
     * ⭐⭐ A GREEN SCREEN — pure `#00FF00`, for an outpaint LoRA trained to
     * paint over green padding (asked for by a user, 2026-09-27). Inpaint and
     * image edit only ([SdSampler.padOptions]).
     */
    const val PAD_GREEN = "green"

    /** ⚠ Pure chroma green, the one every green-screen workflow keys on. */
    const val GREEN_RGB = 0xFF00FF00.toInt()

    /** ⚠ How wide the downscaled copy is, in pixels. Smaller is blurrier. */
    const val BLUR_SOURCE_WIDTH = 48
    const val PAD = "pad"

    /**
     * ⭐⭐ The crop is FINISHED — a tick locks it, a pencil unlocks it.
     *
     * ⚠⚠ Asked for 2026-09-15, and the reason is the drag: framing and painting
     * now share one node, so a finger meant for the mask that lands on the
     * cropper re-frames the shot and silently moves every stroke with it. A
     * lock makes "I am done framing" a thing the user can say.
     *
     * ⚠ A PARAM, not editor state: it is part of what a saved flow means, and a
     * crop that unlocked itself on reload would be a lock that protects nothing.
     */
    const val LOCKED = "crop_locked"

    /** ⚠ A ceiling on a derived-from-nothing output, so a silly rect cannot OOM. */
    const val MAX_OUT = 8192

    /**
     * ⭐⭐ The framing, as pixels — and the ONLY implementation of it.
     *
     * ⚠⚠ The fused sampler fits a photo to the model's size by calling THIS
     * (docs/ARCHITECTURE.md §5.7). Two surfaces that must agree call the same
     * function; two hand-rolled croppers stop agreeing the first time one of
     * them learns about padding (§5.6 step 4).
     *
     * @return the pixels, and the frame in SOURCE coordinates that produced them
     *   — the caller needs the second to say where the picture was cut from.
     */
    fun render(
        src: android.graphics.Bitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        outW: Int,
        outH: Int,
        pad: String?,
    ): Pair<android.graphics.Bitmap, Frame> {
        // The frame in SOURCE pixels. ⚠ Floats, and they may fall outside the
        // bitmap: that is the padded case, and clamping here would silently
        // re-frame the crop rather than pad it.
        val rect = CropGeometry.frameOf(x, y, w, h, src.width, src.height)
        val (ow, oh) = CropGeometry.outputSize(outW, outH, rect, MAX_OUT)

        val bmp = android.graphics.Bitmap.createBitmap(
            ow, oh, android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bmp)
        // Source pixels -> output pixels.
        val m = android.graphics.Matrix().apply {
            setScale(ow / rect.width(), oh / rect.height())
            preTranslate(-rect.left, -rect.top)
        }
        val paint = android.graphics.Paint().apply { isFilterBitmap = true; isAntiAlias = true }

        if (pad == PAD_BLUR) {
            // ⭐ The picture's own edges, reflected outwards and softened.
            //
            // ⚠ A MIRROR shader rather than eight hand-placed copies: the tiling
            // is exact at every scale and there is no seam to get wrong. ⚠⚠ Over
            // a DOWNSCALED copy, which is what makes it blurred -- and the local
            // matrix therefore carries the extra scale, or the small bitmap's
            // tiles would land at a fraction of the size the source's would.
            val small = blurSource(src)
            val k = src.width.toFloat() / small.width
            paint.shader = android.graphics.BitmapShader(
                small,
                android.graphics.Shader.TileMode.MIRROR,
                android.graphics.Shader.TileMode.MIRROR,
            ).apply { setLocalMatrix(android.graphics.Matrix(m).apply { preScale(k, k) }) }
            // ⚠ Black underneath: a shader that does not quite reach a corner
            // must not leave the bitmap's own transparency there.
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), paint)
            // ⚠⚠ …and the picture SHARP on top of it. The blur is padding, not
            // a filter: blurring the part the user framed would be destroying
            // the thing they framed.
            canvas.drawBitmap(src, m, android.graphics.Paint(paint).apply { shader = null })
        } else {
            canvas.drawColor(if (pad == PAD_GREEN) GREEN_RGB else android.graphics.Color.BLACK)
            canvas.drawBitmap(src, m, paint)
        }
        return bmp to rect
    }
}

/**
 * ⭐ Cut a [w]x[h] rectangle from the middle of [src].
 *
 * ⚠⚠ **The same rectangle `Pipeline.hpp`'s `cropCenter` cuts**, and the offset
 * arithmetic is deliberately the C++'s: integer `(full - target) / 2`, which
 * truncates. Rounding the other way would shift the crop a pixel on every odd
 * difference — invisible in a test that checks only the SIZE, and visible as a
 * one-pixel drift against `/generate`'s output for the same request.
 *
 * ⚠ No scaling: this is a cut, not a resize. `createBitmap` with an origin is
 * the cheap path — it shares the source's pixel buffer where it can.
 */
fun cropCenter(
    src: android.graphics.Bitmap,
    w: Int,
    h: Int,
): android.graphics.Bitmap {
    if (w == src.width && h == src.height) return src
    val x = (src.width - w) / 2
    val y = (src.height - h) / 2
    return android.graphics.Bitmap.createBitmap(src, x, y, w, h)
}

/**
 * ⭐⭐ [cropCenter]'s inverse: [src] centred on a black [w]x[h] canvas.
 *
 * ⚠⚠ How a fixed-canvas family is handed a non-square picture. The backend
 * renders the whole 1024² canvas and keeps only the centred aspect rectangle
 * (`RequestParser.hpp`, `aspect_pad_inpaint`), so the picture and the mask must
 * sit in exactly that rectangle — upstream's `padBitmapToCanvas` does the same.
 * ⚠ The SAME truncating offset as [cropCenter], or the cut would come back a
 * pixel off the place it was put.
 */
fun padToCanvas(
    src: android.graphics.Bitmap,
    w: Int,
    h: Int,
): android.graphics.Bitmap {
    if (w == src.width && h == src.height) return src
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.drawBitmap(src, ((w - src.width) / 2).toFloat(), ((h - src.height) / 2).toFloat(), null)
    return out
}

/**
 * A small copy of [src], for the blurred padding.
 *
 * ⚠ Shared with [CropEditor] through the same constant so the node and its own
 * preview blur by the same amount. ⚠ Never smaller than 1 px in either axis: a
 * very wide, very short picture rounds an axis to zero and
 * `createScaledBitmap` throws.
 */
fun blurSource(src: android.graphics.Bitmap): android.graphics.Bitmap {
    val w = CropNode.BLUR_SOURCE_WIDTH
    if (src.width <= w) return src
    val h = (src.height.toFloat() * w / src.width).toInt().coerceAtLeast(1)
    return android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
}

/**
 * ⭐⭐ Paint a mask over a picture. The other half of inpainting.
 *
 * ⚠⚠ **Interactive, like `crop`** — the node's picture is not something to look
 * at, it is the surface you paint on, so a tap on it opens the editor rather
 * than the fullscreen viewer.
 *
 * ⚠ **`sizedByConsumer`**, so the render size flows backwards: the demand from
 * `sd.latent_blend`'s `mask` port reaches here, and this node passes the same
 * demand to its own input — which is what makes a `crop` upstream frame itself
 * to the right size with nothing to configure.
 *
 * ⚠ The output is a black/white PNG at `out_w` x `out_h`, NOT the photo with
 * paint on it: the red overlay is a display convention so the picture stays
 * readable while you work (`MaskEditor`). White is where `repaint` shows
 * through.
 */
object MaskNode : NodeType {
    override val name = "image.mask"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    override val outputs = listOf(Port("image", "IMAGE"))
    override val category = "image"
    override val interactive = true
    override val sizedByConsumer = true

    /** The whole mask, as one string. ⚠ See [MaskState.encode]. */
    const val OPS = "ops"

    /**
     * ⭐⭐ Grow, in px at 512 as the slider shows it: −20…+41, default 10 — the
     * user's numbers, 2026-09-24. It acts on tapped and picked regions only
     * ([MaskState.growFrac]); negative shrinks them.
     */
    const val GROW_DEFAULT = 10f / 512f
    const val GROW_MIN = -20f / 512f
    const val GROW_MAX = 41f / 512f

    /**
     * ⭐⭐ The image id of the GENERATED picture the mask was last painted on,
     * written with every stroke (`HarnessViewModel.editCanvas`). ⚠ An inpaint
     * whose incoming picture no longer matches stops by name rather than
     * repainting the same coordinates on a different picture ([NeedsInput]).
     * Blank for a photo: a new photo already clears the painting.
     */
    const val PAINTED_ON = "painted_on"

    /** ⚠ The longest edge rasterised when nothing downstream demands a size. */
    const val NO_DEMAND_MAX_EDGE = 2048

    override val widgets = listOf(
        // ⚠ Hidden from typing in practice but still a real param, because it is
        // what a saved workflow stores -- the same reason `crop` keeps its four
        // number fields beside the framing view.
        Widget(OPS, "string", "", hint = "paint on the picture above"),
        // ⚠ Grow defaults to ZERO here where DreamUI defaults to 10/512: its
        // default exists for segmenter regions that trace an object's true edge
        // and need slack. Every op here is a brush stroke that is already the
        // size the finger asked for, so growing it is a second invisible
        // brush-size control fighting the real one.
        Widget("grow", "float", GROW_DEFAULT.toString(), GROW_MIN.toDouble(), GROW_MAX.toDouble(), hint = "grow or shrink a tapped or picked area"),
        Widget("feather", "float", "0.0", 0.0, 0.2, hint = "soften the mask edge"),
        // ⚠ Not locked HERE: the inspector locks `out_w`/`out_h` with the
        // consumer'''s own reason the moment this node feeds something that
        // demands a size, exactly as it does for `crop`.
        Widget("out_w", "int", "0", 0.0, 8192.0),
        Widget("out_h", "int", "0", 0.0, 8192.0),
    )

    /** ⚠ App-side: rasterising strokes needs no backend, so no context key. */
    override fun contextKey(node: Node): ContextKey? = null

    /** ⚠ The demand passes straight through: a mask is the size of its picture. */
    override fun requiredInputSize(node: Node, port: String): Pair<Int, Int>? {
        // ⚠ Read directly rather than through `node.int`, which THROWS on a
        // missing param. A node with nothing downstream yet has no `out_w` at
        // all, and "no demand" is a normal answer here -- not an error for the
        // caller's runCatching to swallow.
        val w = node.params["out_w"]?.toIntOrNull() ?: 0
        val h = node.params["out_h"]?.toIntOrNull() ?: 0
        return if (port == "image" && w > 0 && h > 0) w to h else null
    }

    /**
     * ⭐⭐⭐ **The ONE reader of a node's mask string.** On an inpaint node with
     * Enable auto mask ticked, the node's chips ([SdSampler.PICK_TARGETS]) are
     * ALWAYS part of the mask, followed by whatever was painted on top.
     *
     * ⚠⚠⚠ **The chips live in `pick_targets` and nowhere else.** Reported
     * 2026-09-23, 1.6.019: *"im getting nothing masked when i run"*. The rule
     * then was "picks apply only when there is no [OPS] key at all", so ANY
     * empty mask string — a Clear, an Undo, the last chip toggled off —
     * silently switched the auto mask off while its box stayed ticked. The
     * tick is the authority now: unticking it, or deselecting every chip, is
     * the only way the chips leave the mask. Pick ops stored in [OPS] by
     * earlier builds are ignored for the same reason.
     */
    fun opsOf(type: String, params: Map<String, String>): String? {
        val raw = params[OPS]
        if (type !in INPAINT_TYPES) return raw
        val auto = (params[SdSampler.PICK_SELECT] ?: MaskDefaults.of(SdSampler.PICK_SELECT))
            .equals("true", ignoreCase = true)
        val picks = if (auto) pickTargets(params).map { MaskOp.Pick(it) } else emptyList()
        if (raw == null && picks.isEmpty()) return null
        val stored = MaskState.decode(raw)
        return stored.copy(ops = picks + stored.ops.filterNot { it is MaskOp.Pick }).encode()
    }

    /** The node's auto mask chips; Clothes until someone picks otherwise. */
    fun pickTargets(params: Map<String, String>): List<String> =
        params[SdSampler.PICK_TARGETS]?.split(',')?.filter { it.isNotBlank() }
            ?: listOf("clothes")

    fun stateOf(node: Node): MaskState = MaskState.decode(opsOf(node.type, node.params)).copy(
        growFrac = node.params["grow"]?.toFloatOrNull() ?: GROW_DEFAULT,
        featherFrac = node.params["feather"]?.toFloatOrNull() ?: 0f,
    )

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val src = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"image\" is not connected — wire the picture " +
                    "you want to paint on"
            )
        // ⚠ The size comes from the demand when there is one and from the
        // picture otherwise, so a mask node with nothing downstream still
        // renders something rather than refusing.
        // ⚠ With no demand the picture's own size — capped, because an
        // `image.mask_crop` consumer demands nothing and the frame upstream may
        // be a 4096 px photo. A mask is read in NORMALISED terms downstream, so
        // a smaller raster of it loses nothing but memory (DreamUI caps its
        // source at 2048 for the same reason).
        val cap = (NO_DEMAND_MAX_EDGE.toFloat() / maxOf(src.w, src.h)).coerceAtMost(1f)
        val w = node.params["out_w"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: (src.w * cap).toInt().coerceAtLeast(1)
        val h = node.params["out_h"]?.toIntOrNull()?.takeIf { it > 0 }
            ?: (src.h * cap).toInt().coerceAtLeast(1)
        val state = stateOf(node)
        if (state.isEmpty) {
            throw IllegalArgumentException(
                "node \"${node.id}\": nothing is painted — tap the node and paint the " +
                    "area to repaint"
            )
        }
        val bmp = MaskRaster.rasterise(state, w, h)
        return Value.Image(ctx.images.put(bmp), w, h)
    }
}

/**
 * ⭐⭐⭐ **"Only masked"** — cut the part of the picture around the mask, at the
 * render size, and the same part of the mask with it.
 *
 * ⚠⚠ DreamUI's toggle of the same name, with its logic ([InpaintCrop]); ON by
 * default there and here (the user's call, 2026-09-15). Off, or when the mask
 * is already most of the frame, it hands on the whole frame at the render size
 * — which is what the inpaint flow did before this node existed.
 *
 * ⚠ TWO outputs because they must be cut by the SAME rect: an image cropped one
 * way and a mask cropped another is a repaint in the wrong place, and it still
 * renders. Both carry [Value.Image.region] so `image.paste` can put the result
 * back.
 *
 * ⚠ `sizedByConsumer` on its OUTPUTS only: it demands nothing of its inputs —
 * the whole point is that the frame upstream keeps its own pixels.
 */
object MaskCropNode : NodeType {
    override val name = "image.mask_crop"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"), Port("mask", "IMAGE"))
    override val outputs = listOf(Port("image", "IMAGE"), Port("mask", "IMAGE"))
    override val category = "mask"
    override val sizedByConsumer = true

    const val ONLY_MASKED = "only_masked"

    override val widgets = listOf(
        Widget(
            ONLY_MASKED, "bool", "true",
            hint = "Generate over a crop around the mask: more detail where you painted, " +
                "and the rest of the picture keeps its full resolution. Ignored when the " +
                "mask covers most of the picture.",
        ),
        Widget("out_w", "int", "0", 0.0, 8192.0),
        Widget("out_h", "int", "0", 0.0, 8192.0),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override fun outputSize(node: Node): Pair<Int, Int>? {
        val w = node.params["out_w"]?.toIntOrNull() ?: 0
        val h = node.params["out_h"]?.toIntOrNull() ?: 0
        return if (w > 0 && h > 0) w to h else null
    }

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value =
        runPorts(ctx, node, inputs).getValue("image")

    override suspend fun runPorts(
        ctx: NodeCtx,
        node: Node,
        inputs: Map<String, Value>,
    ): Map<String, Value> {
        val image = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"image\" is not connected — wire the picture to repaint"
            )
        val mask = inputs["mask"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"mask\" is not connected — wire the Mask node"
            )
        val src = ctx.images.get(image.id)
            ?: throw IllegalStateException("node \"${node.id}\": image ${image.id} is no longer in the store")
        val maskBmp = ctx.images.get(mask.id)
            ?: throw IllegalStateException("node \"${node.id}\": mask ${mask.id} is no longer in the store")

        // ⚠ Unwired, the render size is unknown; a square the size of the model's
        // is the least surprising stand-in.
        val outW = node.params["out_w"]?.toIntOrNull()?.takeIf { it > 0 } ?: SelectedModel.res.width
        val outH = node.params["out_h"]?.toIntOrNull()?.takeIf { it > 0 } ?: SelectedModel.res.height

        val only = effectiveParams(node)[ONLY_MASKED].equals("true", ignoreCase = true)
        val c = cut(src, maskBmp, outW, outH, only)
        val region = Region(
            c.rect[0].toFloat(), c.rect[1].toFloat(),
            (c.rect[0] + c.rect[2]).toFloat(), (c.rect[1] + c.rect[3]).toFloat(),
            src.width, src.height, image.region,
        )
        return linkedMapOf(
            "image" to Value.Image(ctx.images.put(c.image), outW, outH, region),
            "mask" to Value.Image(ctx.images.put(c.mask), outW, outH, region),
        )
    }

    /** The picture and its mask, cut by the SAME rect, plus that rect. */
    class Cut(
        val image: android.graphics.Bitmap,
        val mask: android.graphics.Bitmap,
        /** x, y, w, h in [src] pixels. */
        val rect: IntArray,
    )

    /**
     * ⭐⭐ "Only masked", as pixels — and the ONLY implementation of it.
     *
     * ⚠⚠ The fused sampler cuts its render window with THIS
     * (docs/ARCHITECTURE.md §5.7). The half that must never be re-typed is the
     * mask mapping: the mask is addressed in the image's NORMALISED terms, so a
     * mask rasterised at a different size cuts the same part. An image cropped
     * one way and a mask cropped another is a repaint in the wrong place, and
     * it still renders.
     */
    fun cut(
        src: android.graphics.Bitmap,
        maskBmp: android.graphics.Bitmap,
        outW: Int,
        outH: Int,
        only: Boolean,
    ): Cut {
        val rect = (if (only) InpaintCrop.compute(InpaintPixels.bounds01(maskBmp), src.width, src.height, outW, outH) else null)
            ?: InpaintCrop.whole(src.width, src.height, outW, outH)

        val mx = maskBmp.width.toFloat() / src.width
        val my = maskBmp.height.toFloat() / src.height
        val maskRect = intArrayOf(
            (rect[0] * mx).toInt().coerceIn(0, maskBmp.width - 1),
            (rect[1] * my).toInt().coerceIn(0, maskBmp.height - 1),
            0, 0,
        ).also {
            it[2] = (rect[2] * mx).toInt().coerceIn(1, maskBmp.width - it[0])
            it[3] = (rect[3] * my).toInt().coerceIn(1, maskBmp.height - it[1])
        }
        return Cut(
            InpaintPixels.cut(src, rect, outW, outH),
            InpaintPixels.cut(maskBmp, maskRect, outW, outH),
            rect,
        )
    }
}

/**
 * ⭐⭐⭐ Put a repainted patch back where it was cut from — and, with **"Stitch to
 * original"**, all the way back into the photo the frame came from.
 *
 * ⚠⚠ DreamUI's `composite` and its "Stitch to original image" toggle
 * ([InpaintPixels.composite]); OFF by default, as there. Blended along the mask,
 * never pasted as a rectangle — the seam is the whole reason the inpaint output
 * "did not join up with the original", reported 2026-09-15.
 *
 * ⚠ The pixels it composites INTO come from wires (`frame`, `original`), and only
 * the rects come from [Value.Image.region] — see [Region].
 */
object PasteNode : NodeType {
    override val name = "image.paste"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    override val inputs = listOf(
        Port("patch", "IMAGE"),
        Port("cut", "IMAGE"),
        Port("mask", "IMAGE"),
        Port("frame", "IMAGE"),
        Port("original", "IMAGE"),
    )
    override val outputs = listOf(Port("image", "IMAGE"))
    override val category = "image"

    const val STITCH = "stitch"

    override val widgets = listOf(
        Widget(
            STITCH, "bool", "false",
            hint = "Off: the result is the frame you chose. On: it is pasted back into the " +
                "whole original photo, at the photo's own size.",
        ),
    )

    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        fun need(port: String, why: String): Value.Image =
            inputs[port] as? Value.Image
                ?: throw IllegalArgumentException("node \"${node.id}\": \"$port\" is not connected — $why")
        val patch = need("patch", "wire the decoded picture")
        val cut = need("cut", "wire the Mask Crop's image")
        val mask = need("mask", "wire the Mask Crop's mask")
        val frame = need("frame", "wire the picture the crop was cut from")
        val inner = cut.region
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"cut\" does not say where it was cut from — wire a Mask Crop into it"
            )
        fun bmp(v: Value.Image) = ctx.images.get(v.id)
            ?: throw IllegalStateException("node \"${node.id}\": image ${v.id} is no longer in the store — Run again")

        val stitch = effectiveParams(node)[STITCH].equals("true", ignoreCase = true)
        val original = inputs["original"] as? Value.Image
        val outer = frame.region
        val out = if (stitch && original != null && outer != null) {
            // ⭐ Into the photo: the patch's rect in the frame, carried through the
            // frame's own rect in the photo.
            val r = InpaintCrop.toParent(inner, outer)
            InpaintPixels.composite(bmp(original), bmp(patch), android.graphics.RectF(r[0], r[1], r[2], r[3]), bmp(mask))
        } else {
            InpaintPixels.composite(
                bmp(frame), bmp(patch),
                android.graphics.RectF(inner.left, inner.top, inner.right, inner.bottom),
                bmp(mask),
            )
        }
        return Value.Image(ctx.images.put(out), out.width, out.height)
    }
}

/**
 * ⭐⭐ Two latents and a mask -> one latent. **Inpainting, without a 9-channel
 * UNet.**
 *
 * This is the tier ladder's own argument as a built-in: compositing two renders
 * in latent space needs no new NPU weights and no conversion pipeline
 * (`../LocalDream/docs/INPAINT.md` §1). It shipped first as the Tier 0 plugin
 * `examples/latent-mix`, which stays as the worked example of doing it from a
 * manifest and some JS; this is the same op with no pack to install.
 *
 * ⚠⚠ **`mask` is an IMAGE, not a new port type.** The backend takes a whole
 * PNG and downsamples it to latent resolution itself, so a mask IS a picture
 * here — which means `image.load` already produces one and nothing new had to
 * be invented. ⚠ A dedicated MASK type would buy validation and cost every
 * existing image node the ability to feed this one.
 *
 * ⚠⚠ **White takes B.** `mask = 1` means "repaint", matching `/generate`'s own
 * per-step blend. Getting it backwards does not fail — it replaces the region
 * you meant to keep.
 *
 * ⚠ The mask is NOT resized to the latent by us: doing it here would be a
 * second copy of a rule that already lives in `RequestParser`, and the two
 * disagreeing would make a mask drawn for `/generate` mean something else in a
 * graph.
 */
object LatentBlendNode : NodeType {
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false
    override val name = "sd.latent_blend"
    /** ⚠ Replaced by the fused sampler — docs/ARCHITECTURE.md §5.7. Registered
     * for one build so the old graph and the new one can be rendered side by
     * side at the same seed, then deleted. [NodeType.hidden]. */
    override val hidden = true
    override val version = "1"
    /**
     * ⚠⚠ **`base` and `repaint`, not `a` and `b`.** The backend's field names
     * are a/b and so is the `latent.blend` host op, but on a canvas those are
     * two identical-looking sockets and the convention that separates them —
     * white takes B — is written down nowhere the user can see. Naming the
     * ports for what they DO puts the rule in the picture: the mask's white
     * area is where `repaint` shows through.
     *
     * ⚠ Getting them the wrong way round does not fail. It replaces the region
     * you meant to keep, which is a plausible image and a silent mistake.
     */
    override val inputs = listOf(
        Port("base", "LATENT"), Port("repaint", "LATENT"), Port("mask", "IMAGE"),
    )
    override val outputs = listOf(Port("latent", "LATENT"))
    override val category = "latent"
    override val widgets get() = listOf(
        // ⚠ The context key, exactly as on the sampler: a blend is done by the
        // backend against the model it was launched with, so it cannot be the
        // one node in a graph that names a different one.
        Widget("model", "string", SelectedModel.id, locked = CONTEXT_KEY_LOCK, contextKey = true),
        // ⚠⚠ contextKey but NOT locked: a size is chosen ON a node now, and
        // choosing it rewrites every other backend node in the graph
        // ([contextKeyRetarget]) rather than leaving two keys behind. The
        // inspector replaces this pair with ONE chip row of the sizes the
        // model's patches actually make reachable -- a free number field would
        // let a user type 640, which has no patch and fails at launch.
        Widget("width", "int", SelectedModel.res.width.toString(), contextKey = true),
        Widget("height", "int", SelectedModel.res.height.toString(), contextKey = true),
    )

    override fun contextKey(node: Node) = backendContextKey(node)

    /**
     * ⚠ The mask must arrive at the render size. `vae_encode` already imposes
     * this on its own input and `crop` reads it, so a photo wired through a
     * cropper into here is framed automatically rather than refused.
     */
    override fun requiredInputSize(node: Node, port: String) =
        if (port == "mask") node.int("width") to node.int("height") else null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        // ⚠ Named individually rather than in a loop: "which latent" is the
        // thing a user gets wrong here, and "input a is not connected" is a
        // better sentence than "an input is missing".
        val a = inputs["base"] as? Value.Handle
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"base\" is not connected — it takes the latent " +
                    "to keep, from a Sampler or a VAE Encode"
            )
        val b = inputs["repaint"] as? Value.Handle
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"repaint\" is not connected — it takes the latent " +
                    "that shows through the mask's white area"
            )
        val mask = inputs["mask"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"mask\" is not connected — it takes an " +
                    "IMAGE, and its WHITE area is where \"repaint\" shows through"
            )
        val png = ctx.images.png(mask.id)
            ?: throw IllegalStateException(
                "node \"${node.id}\": mask ${mask.id} is no longer in the store"
            )
        return when (val r = ctx.host.latentBlend(a.id, b.id, png)) {
            is Ops.Result.Ok -> Value.Handle(r.value.handle, "latent")
            is Ops.Result.Err -> throw OpFailure("latent_blend", r.code, r.body)
        }
    }
}


/**
 * ⭐⭐ **4x on the NPU — and the first node whose model is not the graph's.**
 *
 * ⚠⚠ It calls the backend but has NO context key, which no node did before it.
 * `/upscale` loads the weight file per request from a path and frees it after,
 * so this runs inside whichever process is already up: an upscaler in the same
 * graph as a sampler costs no relaunch (`Upscalers.kt` has the table). That is
 * exactly why [appSide] had to become its own flag — `contextKey == null` used
 * to mean "safe to run for a preview", and for this node it does not.
 *
 * ⚠⚠ The output is 4x the input in EACH axis, so a 1024² frame becomes 4096²
 * — 64 MB as ARGB, into a store that holds a dozen images. Put it last.
 *
 * ⚠ The scale factor is read from the RESPONSE rather than assumed: it is a
 * property of the weight file, and a 2x upscaler would otherwise be reported as
 * 4x by a node that never looked.
 */
object UpscaleNode : NodeType {
    /** ⚠⚠ It RENDERS, so its result belongs to `core.output` too — the rule is
     * everywhere or it is two rules ([NodeType.showsResult]). */
    override val showsResult = false

    /**
     * ⭐⭐⭐ **DELETED, 2026-09-22** — the user's call, twice: hiding it from
     * the palette was not enough while the Inpaint recipe still built one and
     * the upscale button still inserted one.
     *
     * ⚠⚠ It is out of `NODE_TYPES` entirely. A saved flow naming one is
     * REWRITTEN on load — `WorkflowIo.migrateUpscaleNodes` drops the node and
     * turns [MediaOutputNode.UPSCALE] on at the output it fed — so nothing
     * breaks and nothing is left behind.
     *
     * ⚠ The object survives because [upscaleTo] is still the ONE
     * implementation of the call, which `core.output` makes, and the migration
     * reads this node's param names off it.
     */
    override val hidden = true
    override val name = "image.upscale"
    override val version = "1"
    override val inputs = listOf(Port("image", "IMAGE"))
    override val outputs = listOf(Port("image", "IMAGE"))
    /**
     * ⚠ `edit`, beside crop — it was `generate` for having its own model, like a
     * sampler. The user's call, 2026-09-17: it is one picture in and one out
     * whatever made the picture, so it belongs with the light, family-agnostic
     * nodes in the palette's Common tab ([com.abrah.nightmare.canvas.paletteTabs]).
     */
    override val category = "edit"
    /** ⚠ Reaches the backend, so never run for a preview. [NodeType.appSide]. */
    override val appSide = false

    const val UPSCALER = "upscaler"

    /**
     * ⭐⭐ How much larger — `2x`, `3x` or `4x`, local-dream's own choice
     * (the user's ask, 2026-09-26). ⚠ The upscalers are all 4x
     * ([NATIVE_SCALE]); a smaller choice is the 4x result scaled down, as
     * upstream does it (`performUpscale`) — it caps the SIZE, not the time.
     */
    const val SCALE = "scale"
    const val NATIVE_SCALE = 4
    val SCALES = listOf("2x", "3x", "4x")

    /**
     * ⭐⭐⭐ **The longest edge an upscale may PRODUCE** — the user's call,
     * 2026-09-26, after the output node's button and auto upscale had no cap
     * at all (a 2048 px render became 8192 px). 4096 is the GPU texture limit
     * and [LoadImageNode.MAX_EDGE]; at 4x it admits a 1024 px source, which is
     * local-dream's own upscale limit.
     */
    const val MAX_OUT_EDGE = 4096

    /** The scale a `2x`/`3x`/`4x` param asks for; 4 when unreadable. */
    fun scaleOf(raw: String?): Int =
        raw?.trim()?.removeSuffix("x")?.toIntOrNull()?.coerceIn(2, NATIVE_SCALE) ?: NATIVE_SCALE

    /**
     * ⭐⭐ The largest scale up to [wanted] that keeps a [width]×[height]
     * picture within [MAX_OUT_EDGE] — or null when not even 2x fits.
     */
    fun fittingScale(width: Int, height: Int, wanted: Int): Int? =
        (wanted.coerceIn(2, NATIVE_SCALE) downTo 2).firstOrNull { maxOf(width, height) * it <= MAX_OUT_EDGE }

    override val widgets get() = listOf(
        // ⚠⚠ The OPTIONS are the INSTALLED set, so the dropdown cannot offer a
        // file that is not on the device -- and the DEFAULT is the first of
        // them.
        //
        // ⚠⚠ This comment used to say exactly that while the code read
        // `ALL.first()` and `ALL.map`, which are not the same thing: on a phone
        // with only the realistic upscaler fetched, a freshly dropped node was
        // pre-set to `upscaler_anime` and failed the instant it ran. The list is
        // a cached disk scan ([UpscalerCatalog.installedIds]) because a widgets
        // getter has no Context.
        //
        // ⚠ Falls back to [UpscalerCatalog.ALL] when nothing is installed --
        // that is a fresh install, and a control with no options at all says
        // less than one whose [run] names what to download.
        Widget(
            UPSCALER, "string",
            (UpscalerCatalog.installedIds.firstOrNull() ?: UpscalerCatalog.ALL.first().id),
            options = UpscalerCatalog.installedIds.ifEmpty { UpscalerCatalog.ALL.map { it.id } },
            hint = "which upscaler weights to use — install them under Models",
        ),
    )

    /**
     * ⚠⚠ **Null, and that is the whole point of this node.** An upscaler binds
     * nothing at launch, so it does not pin the graph to a process. A key here
     * would make every graph containing an upscaler refuse to run alongside its
     * own sampler ("needs 2 backend contexts") for no reason at all.
     */
    override fun contextKey(node: Node): ContextKey? = null

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val image = inputs["image"] as? Value.Image
            ?: throw IllegalArgumentException(
                "node \"${node.id}\": \"image\" is not connected"
            )
        val wanted = scaleOf(node.params[SCALE])
        val scale = fittingScale(image.w, image.h, wanted)
            ?: throw NeedsInput(
                "too big to upscale: ${image.w}x${image.h} — pictures up to " +
                    "${MAX_OUT_EDGE / 2} px on the long edge only ($MAX_OUT_EDGE px after 2x)"
            )
        return upscaleTo(ctx, node.id, image, node.params[UPSCALER].orEmpty(), scale)
    }

    /**
     * ⭐⭐⭐ **Enlarge one picture — the ONE implementation.**
     *
     * ⚠⚠ Called by this node's [run] and by `core.output`'s auto-upscale
     * ([MediaOutputNode.UPSCALE]). Two copies of "find the weights, pack RGB,
     * post it, decode the reply" would be two copies that drift, and the
     * failure modes here — a missing upscaler, bytes that will not decode —
     * are exactly the ones a user needs named the same way wherever they start.
     */
    suspend fun upscaleTo(
        ctx: NodeCtx,
        nodeId: String,
        image: Value.Image,
        upscalerId: String,
        /** ⭐ 2, 3 or 4 — the upscaler's 4x result is scaled down to it. Already capped ([fittingScale]). */
        scale: Int = NATIVE_SCALE,
    ): Value.Image {
        val src = ctx.images.get(image.id)
            ?: throw IllegalStateException(
                "node \"$nodeId\": image ${image.id} is no longer in the store"
            )
        val android = ctx.android
            ?: throw IllegalStateException(
                "node \"$nodeId\": upscaling needs a platform context to find the weights"
            )
        val id = upscalerId
        val path = UpscalerCatalog.pathFor(android, id)
            ?: throw IllegalArgumentException(
                "node \"$nodeId\": upscaler \"" +
                    (UpscalerCatalog.byId(id)?.label ?: id) +
                    "\" is not installed — open Models and download it"
            )
        // ⚠ RAW RGB, not a PNG: /upscale takes 3*w*h uncompressed bytes.
        val rgb = rgbBytes(src)
        return when (val r = ctx.host.upscale(rgb, src.width, src.height, path)) {
            is Ops.Result.Ok -> {
                val four = android_graphics_decode(r.value.jpeg)
                    ?: throw IllegalStateException(
                        "node \"$nodeId\": the upscaler returned bytes that will not decode"
                    )
                val bmp = if (scale >= NATIVE_SCALE) four
                else scaledTo(four, src.width * scale, src.height * scale)
                Value.Image(ctx.images.put(bmp), bmp.width, bmp.height)
            }
            is Ops.Result.Err -> throw OpFailure("upscale", r.code, r.body)
        }
    }

    /** ⚠ A function of its own: inside [upscaleTo] a local `android` shadows the package. */
    private fun scaledTo(b: android.graphics.Bitmap, w: Int, h: Int): android.graphics.Bitmap =
        android.graphics.Bitmap.createScaledBitmap(b, w, h, true)

    /**
     * A bitmap as tightly packed RGB, the layout `/upscale` reads.
     *
     * ⚠⚠ Three bytes per pixel, NOT four. `copyPixelsToBuffer` would give
     * RGBA/ARGB and the server reads `3 * w * h` — a mismatch it catches with a
     * 400, but only after 4 MB has crossed the socket.
     */
    private fun rgbBytes(src: android.graphics.Bitmap): ByteArray {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(3 * w * h)
        var o = 0
        for (p in px) {
            out[o++] = ((p shr 16) and 0xFF).toByte()
            out[o++] = ((p shr 8) and 0xFF).toByte()
            out[o++] = (p and 0xFF).toByte()
        }
        return out
    }

    private fun android_graphics_decode(bytes: ByteArray): android.graphics.Bitmap? =
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

val NODE_TYPES: Map<String, NodeType> = (
    listOf(
        // ⭐⭐⭐ The set of docs/ARCHITECTURE.md §5.7 — what a person wires.
        // ⚠⚠⚠ **`UpscaleNode` is NOT here since 2026-09-22** — the type is
        // DELETED, not hidden. `core.output` carries the checkbox and
        // `WorkflowIo.migrateUpscaleNodes` rewrites a saved flow that names one,
        // turning the enlargement on at the output it fed. ⚠ The object itself
        // survives for `UpscaleNode.upscaleTo`, which is still the one
        // implementation of the call, and for the migration to read its param
        // names off.
        PromptNode, LoadImageNode, MediaOutputNode,
        // ⭐ Six SD samplers (three families × sample/inpaint): one class, two
        // arguments of difference.
        // docs/ARCHITECTURE.md §5.7 has why the fork is capability AND family.
    ) + SdSampler.ALL + listOf(
        // ⚠⚠ The ten it replaces, `hidden` and running, for ONE build: long
        // enough to render the old inpaint graph and the new one at the same
        // seed and compare them. Deleted in the next push.
        MaskNode, MaskCropNode, PasteNode, TextEncodeNode, SampleNode,
        VaeDecodeNode, VaeEncodeNode, LatentBlendNode,
        // ⭐ The video path (docs/NEODRAGON.md). ⚠ Listed here like any other
        // built-in ON PURPOSE: it reaches the NPU in-process rather than
        // through the backend server, and a registry that made that visible
        // would be leaking a transport detail into the palette.
        //
        // ⚠⚠⚠ **`nd.video_sample`, `video.output` and `image.output` are GONE**,
        // 2026-09-13. The first was one node doing five jobs; the other two
        // existed only to hold a `save` switch, and every terminal node draws
        // its own result and carries save/share/star. `WorkflowIo` rebuilds a
        // saved graph that names any of them, so nothing a user kept fails to
        // open. `docs/NEODRAGON.md` §8.
        // ⭐⭐ ONE video sampler, 2026-09-15 — the user's call. The five-node
        // split of 2026-09-13 is reversed: `nd.clip_encode` became `core.prompt`
        // (text, shared with the SD path), and the first frame, the encode, the
        // sample and the decode are all inside this node. ⚠ The first frame is
        // no longer a node you can see before the clip; that is the cost, and it
        // was accepted to make t2v and i2v the same three nodes as t2i and i2i.
        com.abrah.nightmare.npu.VideoSampleNode,
        // ⭐ Tap to select — wired into an inpaint node (docs/SEGMENTER.md).
        SelectObjectNode,
    )).associateBy { it.name }

/**
 * `cacheKey -> value`, bounded, least-recently-used first out.
 *
 * ⚠ Bounded because a canvas will have many more nodes than this harness, and
 * every entry pins something real -- a backend tensor or a bitmap. An unbounded
 * cache on a device that runs for hours is a leak nobody notices until the app
 * is killed.
 */
class NodeCache(private val limit: Int = 16) {
    private val entries = LinkedHashMap<String, Value>(16, 0.75f, true)

    val size get() = entries.size

    fun get(key: String): Value? = entries[key]

    fun put(key: String, value: Value) {
        entries[key] = value
        while (entries.size > limit) {
            val oldest = entries.keys.iterator()
            oldest.next()
            oldest.remove()
        }
    }

    /**
     * Drop every remembered value whose bytes are gone. Returns how many went.
     *
     * ⚠⚠ [alive] covers BOTH stores, and it has to. A backend handle dies when
     * that process restarts; an image handle dies when the app-side store
     * evicts it. They are different lifetimes with the same failure — a cache
     * hit naming something nobody holds — and a prune that knew about only one
     * of them would hand a plugin an id that resolves to nothing.
     *
     * ⭐ A surviving image entry is what makes the post-restart split work: the
     * re-render is content-addressed, so it returns the same latent id, so the
     * decode's key still matches and the decode is skipped. The device check
     * predicts exactly that.
     */
    fun prune(alive: (Value) -> Boolean): Int {
        val gone = entries.filterValues { !alive(it) }.keys.toList()
        gone.forEach { entries.remove(it) }
        return gone.size
    }

    fun clear() = entries.clear()
}

/**
 * Runs graphs, remembering what it has already computed.
 *
 * ⚠ Stateful, and deliberately owned by the caller rather than global: the
 * cache's lifetime IS this object's lifetime, so a caller that wants a cold run
 * makes a new one (or calls `cache.clear()`) instead of hunting for an
 * invalidation flag.
 */
class Executor(
    private val host: OpHost = BackendHost,
    val cache: NodeCache = NodeCache(),
    /**
     * Where app-side pixels live. ⚠ Shared with whatever created it (the
     * harness, and the plugin host that hands ids to JS) — an executor with a
     * private store could not resolve a handle a plugin returned.
     */
    val images: ImageStore = ImageStore(),
    /**
     * ⚠ Node types are a REGISTRY, not a `when`. Plugins add entries here; a
     * hardcoded dispatch would mean a contributor's node could only exist by
     * editing the executor, which is the whole thing the tier design is trying
     * to avoid.
     */
    private val types: Map<String, NodeType> = NODE_TYPES,
    /**
     * ⚠ Only for nodes that must touch the platform (saving to the gallery).
     * Null in tests, and a node that needs it fails with a sentence naming
     * itself rather than a null dereference.
     */
    private val android: android.content.Context? = null,
    /**
     * ⭐⭐⭐ Load a different backend context mid-graph, returning whether it
     * came up — the relaunch that makes two checkpoints in one graph possible.
     *
     * ⚠⚠ A CALLBACK, not something the executor does itself: launching the
     * server is a process, a notification and a health poll, and every one of
     * those belongs to the layer that already owns them
     * (`HarnessOps.ensureBackend`). The executor decides WHEN; the host decides
     * HOW. ⚠ Null in tests and in any runner that cannot switch — a graph
     * needing two keys is then refused by name rather than run against the
     * wrong one.
     */
    private val switchKey: (suspend (ContextKey) -> Boolean)? = null,
    /**
     * ⚠ What the backend holds as a run begins, for [scheduleByKey] to start
     * from. Must be the SAME answer [launchKeyFor] was given, or the pre-run
     * launch and the schedule disagree and pay a relaunch between them.
     */
    private val loadedKey: () -> ContextKey? = { null },
) {

    suspend fun run(
        graph: Graph,
        onProgress: (nodeId: String, step: Int, total: Int) -> Unit = { _, _, _ -> },
        onNode: (NodeRun) -> Unit = {},
        /**
         * ⭐ Fired as each node is REACHED, before it is run or served from the
         * cache.
         *
         * ⚠⚠ [onNode] fires when a node FINISHES, which is useless for telling
         * a user what is happening now: on SDXL a single `sample` is 24 seconds
         * during which the only callback that fires is [onProgress], and only
         * the sampler emits those. Without this the canvas could not name the
         * node it was waiting on -- and a 24 s silence reads as a button that
         * did nothing (`docs/UI.md` §5).
         */
        onStart: (nodeId: String, type: String) -> Unit = { _, _ -> },
        /**
         * ⭐ A node's own narration, forwarded live — see [NodeCtx.say].
         *
         * ⚠ Fired from whatever thread the node runs on, which for a long node
         * is an IO worker. Compose state takes writes off the main thread and
         * the rest of this app already relies on that.
         */
        onLog: (nodeId: String, text: String) -> Unit = { _, _ -> },
        /** ⭐ A node telling the person something — see [NodeCtx.warn]. Already logged through [onLog]. */
        onWarn: (nodeId: String, text: String) -> Unit = { _, _ -> },
    ): GraphRun {
        val t0 = System.nanoTime()
        fun sinceMs() = (System.nanoTime() - t0) / 1_000_000

        val order = when (val o = topoSort(graph)) {
            is Order.Broken -> return GraphRun(emptyList(), emptyMap(), sinceMs(), o.why)
            is Order.Ok -> o.nodes
        }

        val nodeTypes = mutableMapOf<String, NodeType>()
        for (n in order) {
            nodeTypes[n.id] = types[n.type]
                ?: return GraphRun(emptyList(), emptyMap(), sinceMs(),
                    "node \"${n.id}\": unknown type \"${n.type}\"")
        }

        // ⚠ A wire naming an output port the upstream type does not declare is
        // a BROKEN GRAPH, and it is refused here rather than at the node that
        // reads it: by then the message would blame the consumer for a mistake
        // made on the producer's side. Checked statically because it can be --
        // nothing about it depends on a value.
        for (n in order) {
            for ((port, src) in n.inputs) {
                val from = nodeTypes.getValue(src.node)
                val have = from.outputs.joinToString(", ") { it.name }.ifEmpty { "none" }
                if (src.port == null) {
                    // ⚠ A bare source means "the sole output", so it is only
                    // meaningful while there IS one. Taking the first of several
                    // would render a plausible wrong picture from a wire the user
                    // never disambiguated -- and every workflow written before
                    // [Source] existed is bare, so this is the case that matters.
                    if (from.outputs.size != 1) {
                        return GraphRun(emptyList(), emptyMap(), sinceMs(),
                            "node \"${n.id}\" input \"$port\" does not say which output of " +
                                "\"${src.node}\" it comes from; it has $have")
                    }
                } else if (from.outputs.none { it.name == src.port }) {
                    return GraphRun(emptyList(), emptyMap(), sinceMs(),
                        "node \"${n.id}\" input \"$port\" names output \"${src.port}\" on " +
                            "\"${src.node}\", which has $have")
                }
                // ✅ A NON-FIRST port is legal now. It used to be refused here
                // because `NodeType.run` returned one value, so only the first
                // port was ever filled -- the wire format could address a second
                // output and the runtime could not produce one. [NodeType.runPorts]
                // closed that gap on 2026-09-13, which is what `nd.clip_encode`'s
                // two conditionings needed (`docs/NEODRAGON.md` §8). The branch
                // above still catches a port the type does not declare, which is
                // the only thing left that can be wrong here.
            }
        }

        // ⚠ v1 pins ONE context key for the whole graph (ARCHITECTURE §5.2), so
        // a second one is refused here rather than scheduled. The scheduler is
        // v1.1 work and it is to be designed against a measurement of real
        // workflows; a placeholder that silently ran the nodes anyway would
        // decode at a resolution the backend was not launched with.
        val keys = try {
            order.mapNotNull { nodeTypes.getValue(it.id).contextKey(it) }.toSet()
        } catch (e: IllegalArgumentException) {
            return GraphRun(emptyList(), emptyMap(), sinceMs(), e.message ?: "bad params")
        }
        // ⭐⭐⭐ **Two checkpoints in one graph are SCHEDULED, not refused** —
        // 2026-09-15, the user's call. The refusal that stood here named the
        // models and told the user to pick one; what replaces it is
        // [scheduleByKey], which orders the graph so each checkpoint is loaded
        // once, and a relaunch between groups. `docs/ARCHITECTURE.md` §4.
        //
        // ⚠ The ORDER is computed even for one key: it is a no-op there
        // (`scheduleByKey` returns its input), so there is one path rather than
        // a fast one and a scheduled one that could diverge.
        val scheduled = try {
            scheduleByKey(order, { nodeTypes.getValue(it.id).contextKey(it) }, loadedKey())
        } catch (e: IllegalArgumentException) {
            return GraphRun(emptyList(), emptyMap(), sinceMs(), e.message ?: "bad params")
        }
        // ⚠⚠ A graph that needs a switch and has no way to make one is refused
        // HERE rather than failing at the first node of the second group — at
        // which point half the renders have already been paid for.
        if (keys.size > 1 && switchKey == null) {
            return GraphRun(
                emptyList(), emptyMap(), sinceMs(),
                "cannot run: this graph needs ${keys.size} checkpoints and this runner " +
                    "cannot switch between them",
            )
        }

        // The residency snapshot, taken once. ⚠ An unreachable /handles is a
        // DOWN BACKEND, not an empty cache: treating it as "nothing resident"
        // would send the executor off to re-run every node against a server
        // that is not there, and report the backend's absence as four node
        // failures instead of one.
        // ⭐ `keys.isEmpty()` is a graph of Tier 0 nodes ONLY, and it runs with
        // no backend at all -- asking a server that need not exist whether it
        // holds tensors nobody named would make an all-app-side graph fail for
        // a reason that has nothing to do with it.
        val resident = if (keys.isEmpty()) emptySet() else host.residentHandles()
            ?: return GraphRun(emptyList(), emptyMap(), sinceMs(),
                "GET /handles unreachable — backend down, nothing can run")
        val pruned = cache.prune { v ->
            when (v) {
                is Value.Handle -> v.id in resident
                is Value.Image -> v.id in images
                // ⚠⚠ A clip is kept while its FILE is there, not while its
                // poster frame is. The poster lives in a store bounded at
                // twelve, so keying on it would drop a perfectly good 2 s
                // render as soon as a dozen other nodes had drawn something --
                // and re-rendering it costs 25 s.
                is Value.Video -> java.io.File(v.path).isFile
                // ⚠⚠ A store lookup, not a backend question: these never
                // leave this process ([Value.Tensors]). ⚠ The store is bounded
                // at four because a video latent is tens of MB, so an eviction
                // here is ordinary and means "run that node again".
                is Value.Tensors -> v.id in com.abrah.nightmare.npu.TensorStore
                // ⚠ Never stale: it is the text itself, not a handle to
                // something a store or a server might have dropped.
                is Value.Prompt -> true
                is Value.Capability -> true
            }
        }

        val runs = mutableListOf<NodeRun>()
        // ⚠ Per node, per OUTPUT PORT. A flat `nodeId -> Value` cannot represent
        // a node that produced two things, and the map is what a consumer reads
        // -- so this is the half of multi-output that the graph model alone
        // could not fix.
        val values = mutableMapOf<String, MutableMap<String, Value>>()
        // The port a bare [Source] means. Validated above to exist and be sole.
        // ⚠ `orEmpty()` covers a SINK (`save_image` declares no outputs): its
        // value is still recorded, under a name no wire can spell, so the sink
        // keeps appearing in [GraphRun.outputs] while staying unwireable. The
        // static pass above already refuses a wire that tries.
        fun outName(nodeId: String) = nodeTypes.getValue(nodeId).outputs.firstOrNull()?.name.orEmpty()
        fun resolve(src: Source): Value? = values[src.node]?.get(src.port ?: outName(src.node))
        var stopped: String? = null
        var waiting: Pair<String, String>? = null

        // ⚠ Tracks what the BACKEND holds, so a group boundary is a change of
        // key rather than "this node names one".
        var loaded: ContextKey? = null
        for (node in scheduled) {
            // ⭐⭐ The relaunch. ⚠ Before the node runs and after everything the
            // previous key needed is done — which is what [scheduleByKey]
            // guarantees, and why the switch can be this simple.
            val want = try {
                nodeTypes.getValue(node.id).contextKey(node)
            } catch (e: IllegalArgumentException) {
                null
            }
            if (want != null && want != loaded) {
                // ⚠⚠ A failed switch stops the graph. Carrying on would run the
                // rest against whatever is still loaded and produce plausible
                // pictures from the wrong checkpoint — the silent failure this
                // whole key mechanism exists to prevent.
                if (loaded != null || switchKey != null) {
                    val ok = switchKey?.invoke(want) ?: false
                    if (!ok) {
                        return GraphRun(
                            runs, values.mapValues { (id, byPort) -> byPort.getValue(outName(id)) },
                            sinceMs(), "could not load $want",
                        )
                    }
                }
                loaded = want
            }
            val type = nodeTypes.getValue(node.id)
            onStart(node.id, node.type)

            // A node whose upstream failed cannot run and must not be reported
            // as cached. BLOCKED is its own outcome so a log reader can see
            // where the damage started rather than counting failures.
            val missing = node.inputs.filterValues { resolve(it) == null }
            if (missing.isNotEmpty()) {
                val r = NodeRun(node.id, node.type, Outcome.BLOCKED, 0,
                    "waiting on " + missing.values.joinToString(", "))
                runs += r; onNode(r)
                continue
            }

            val inputs = node.inputs.mapValues { (_, up) -> resolve(up)!! }
            // ⚠ effectiveParams, not node.params: a widget left at its default
            // must key identically to one set explicitly to that value.
            val params = try {
                type.effectiveParams(node)
            } catch (e: Exception) {
                val r = NodeRun(node.id, node.type, Outcome.FAILED, 0,
                    e.message ?: e.javaClass.simpleName)
                stopped = stopped ?: "${node.id}: ${e.message}"
                runs += r; onNode(r)
                continue
            }
            val key = cacheKey(node.type, type.version, params, inputs)

            // ⚠ A node with a side effect is never served from the cache, and
            // never put into it either -- storing it would only invite a later
            // change to start trusting it.
            // ⭐⭐ Which ports anything downstream reads — see [NodeCtx.wanted].
            // Computed from the graph, which is the only thing that knows.
            val wanted = order.flatMap { c -> c.inputs.values.filter { it.node == node.id } }
                .mapTo(mutableSetOf()) { it.port ?: outName(node.id) }
            val ports = type.outputs.map { it.name }
            // ⚠⚠ Cached per OUTPUT PORT. A two-output node whose consumers were
            // wired one at a time must not recompute the half it already has,
            // and a flat per-node entry cannot express "I have `cond` but not
            // `frame_cond`".
            val hits = if (type.cacheable) {
                ports.mapNotNull { portName ->
                    cache.get(portKey(key, portName))?.let { portName to it }
                }.toMap()
            } else {
                emptyMap()
            }
            // ⚠ Everything downstream wants, plus the PRIMARY port, must be
            // present. The primary is the one the canvas draws, so a hit that
            // lacked it would leave the node blank.
            val need = (wanted + ports.take(1)).filter { it in ports }
            if (hits.isNotEmpty() && need.isNotEmpty() && need.all { it in hits }) {
                values[node.id] = hits.toMutableMap()
                val shown = hits[outName(node.id)] ?: hits.values.first()
                val r = NodeRun(node.id, node.type, Outcome.CACHED, 0, shown.describe())
                runs += r; onNode(r)
                continue
            }

            val n0 = System.nanoTime()
            val r = try {
                val ctx = NodeCtx(
                    host, images, android,
                    onProgress = { p -> onProgress(node.id, p.step, p.total) },
                    say = { line -> onLog(node.id, line) },
                    warn = { line -> onLog(node.id, line); onWarn(node.id, line) },
                    wanted = wanted,
                    ancestorTypes = ancestorTypes(node.id, order),
                )
                // ⚠⚠ The node is run with the SAME params the key was computed
                // from. Keying on the effective values but running on the
                // written ones is the classic way a cache starts serving a
                // result the node never produced -- and it would only show up
                // once a widget had a default, which is to say once plugins
                // existed.
                val produced = type.runPorts(ctx, node.copy(params = params), inputs)
                if (type.cacheable) {
                    produced.forEach { (portName, pv) -> cache.put(portKey(key, portName), pv) }
                }
                values[node.id] = produced.toMutableMap()
                // ⚠ The PRIMARY port is what the run line shows. A node that made
                // two things describes the one the canvas draws, not an
                // arbitrary map entry.
                val v = produced[outName(node.id)] ?: produced.values.first()
                NodeRun(node.id, node.type, Outcome.RAN,
                    (System.nanoTime() - n0) / 1_000_000, v.describe())
            } catch (e: NeedsInput) {
                // ⭐ Waiting on a person — BLOCKED, and no graph error.
                waiting = waiting ?: (node.id to (e.message ?: "needs you"))
                NodeRun(node.id, node.type, Outcome.BLOCKED, 0, e.message ?: "needs you")
            } catch (e: Exception) {
                stopped = stopped ?: "${node.id}: ${e.message}"
                NodeRun(node.id, node.type, Outcome.FAILED,
                    (System.nanoTime() - n0) / 1_000_000,
                    e.message ?: e.javaClass.simpleName)
            }
            runs += r; onNode(r)
        }

        // ⚠⚠⚠ **The video runner is released HERE, once, when the graph is
        // done** — not per node. Residency across node boundaries is the whole
        // reason the split is affordable: `clipg` costs 1897 ms to map and 40 ms
        // to run, and two phases need it. ⚠ Holding it past the run is what put
        // ~7.5 GB of context in one process and got the app killed
        // (`docs/NEODRAGON.md` §8), so it is released however the run ended —
        // including a node that threw.
        com.abrah.nightmare.npu.VideoRunner.releaseAll()

        return GraphRun(
            runs = runs,
            // ⚠ Flattened to the node's FIRST output. Every caller asks "what
            // did this node produce"; when a type grows a second output, the
            // caller that needs to tell them apart wants a new accessor rather
            // than a silently reinterpreted map.
            outputs = values.mapValues { (id, byPort) -> byPort.getValue(outName(id)) },
            totalMs = sinceMs(),
            error = stopped,
            prunedHandles = pruned,
            waiting = waiting,
        )
    }

    /**
     * ⭐ The TYPES of every node upstream of [id] — what [NodeCtx.ancestorTypes]
     * carries, so a node can ask "was my picture generated" without the graph.
     */
    private fun ancestorTypes(id: String, order: List<Node>): Set<String> {
        val byId = order.associateBy { it.id }
        val out = mutableSetOf<String>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(byId[id]?.inputs?.values?.map { it.node }.orEmpty())
        while (queue.isNotEmpty()) {
            val n = byId[queue.removeFirst()] ?: continue
            if (!seen.add(n.id)) continue
            out += n.type
            n.inputs.values.forEach { queue.addLast(it.node) }
        }
        return out
    }
}
