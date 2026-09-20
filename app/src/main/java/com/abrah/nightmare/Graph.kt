package com.abrah.nightmare

// ⚠ The canvas package owns the type-to-word rule and its overrides
// ([nodeLabel]); [nodeNameOf] below reuses it rather than keeping a second copy.
import com.abrah.nightmare.canvas.nodeLabel
import java.security.MessageDigest

/**
 * The graph the executor runs, and the cache key rule that decides what it may
 * skip.
 *
 * ⚠ This file owns the KEY RULE and nothing else owns any part of it. A second
 * copy of "what makes two runs the same" drifts, and a drifted key serves a
 * stale result that looks almost right -- a failure that never raises an error.
 * The backend has the identical hazard on its own side
 * (backend-patches/003-op-endpoints.patch), and the two are deliberately NOT
 * mirrors of each other: see [Value.Handle].
 */

/**
 * What an output port carries between nodes.
 *
 * ⭐ Handles, not buffers (docs/ARCHITECTURE.md §6). A conditioning is 473 KB
 * and a latent 64 KB; the graph moves ids and the host moves the bytes.
 */
/**
 * ⚠ Here rather than beside [cacheKey]'s digest because [Value.Prompt] is the
 * only value that has to address ITSELF -- every other one is addressed by a
 * store that hashed the bytes already.
 */
internal fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(32)

sealed interface Value {

    /**
     * A tensor living in the backend process.
     *
     * ⚠⚠ Valid only while RESIDENT. The id is the backend's own content
     * address -- the app never computes it, because recomputing it here would
     * be that second copy of the key rule and it would drift the first time the
     * backend's hash input changed. ⇒ The executor learns the id by running the
     * node once, then asks `GET /handles` whether it is still there.
     */
    data class Handle(val id: String, val kind: String) : Value {
        override fun address() = id
        override fun describe() = "$kind $id"
    }

    /**
     * Pixels, app-side. The bytes live in an [ImageStore]; this is the handle.
     *
     * ⚠ A handle here for the same reason as [Handle] there: a plugin node
     * receives this id as ~40 bytes of JSON instead of 780 KB of pixel array
     * (docs/ARCHITECTURE.md §6). ⚠⚠ And like a backend handle it can go stale —
     * the store is bounded — so the executor checks it is still held before
     * trusting a cached one.
     */
    data class Image(
        val id: String,
        val w: Int,
        val h: Int,
        /**
         * ⭐ Where this picture was CUT from, when it was — set by `image.crop`
         * and `image.mask_crop`, read by `image.paste` to put a patch back.
         * Null for everything else. [Region] says why it is rects, not ids.
         */
        val region: Region? = null,
    ) : Value {
        override fun address() = id
        override fun describe() = "image ${w}x$h $id"
    }

    /**
     * ⭐⭐ A video: an MP4 on disk, plus one frame to look at.
     *
     * ⚠⚠ **A file, not 49 bitmaps.** At 640×1024 ARGB a frame is 2.6 MB, so a
     * clip held in memory is ~128 MB — and [NodeCache] would pin it for the life
     * of the canvas. The generator encodes straight to H.264 in cacheDir and
     * this carries the path; 49 frames become ~1 MB that nothing has to keep on
     * the heap.
     *
     * ⚠ [posterId] is frame 0 in the [ImageStore], and it is what the canvas
     * draws on the node. Exactly ONE frame enters that store: it is bounded at
     * twelve entries, so a clip that put all its frames there would evict every
     * other node's picture.
     */
    data class Video(
        val path: String,
        val frames: Int,
        val w: Int,
        val h: Int,
        val posterId: String,
    ) : Value {
        // ⚠ The FILE NAME, which the producer makes a content address (a hash of
        // everything the clip was generated from). The path is not usable: it
        // carries a cache directory that differs between installs, so two
        // identical clips would look different to a downstream key.
        override fun address() = path.substringAfterLast('/')
        override fun describe() = "video ${frames}f ${w}x$h"

        /** ⚠ Valid only while the store still holds it, like any [Image]. */
        val poster get() = Image(posterId, w, h)
    }

    /**
     * ⭐⭐ What a person typed: positive and negative, as TEXT.
     *
     * ⚠⚠ **Not a conditioning, and that is the whole point**
     * (docs/ARCHITECTURE.md §5.7). A COND is encoded BY a checkpoint and belongs
     * to it, so one prompt node could never feed two samplers on two models —
     * which is exactly what chaining across checkpoints needs. The encode moved
     * INSIDE the sampler, where the model is known.
     *
     * ⚠ A value rather than a handle, unlike every other type here: the text is
     * ~100 bytes, there is no store for it to go stale in, and the thing it
     * would address is cheaper to carry than to look up. ⚠ The 129 ms encode is
     * not lost — the backend content-addresses conditionings, so the same text
     * encoded twice is 0–2 ms (§3).
     */
    data class Prompt(val positive: String, val negative: String) : Value {
        // ⚠ The two halves are separated by a byte neither can contain.
        // Without it a prompt "ab" with no negative and a prompt "a" with
        // negative "b" address identically, and the cache would serve one
        // node's picture for the other's.
        override fun address() = sha256(positive + '\u0000' + negative)
        // ⚠⚠ SHORT, and it says that it is short. It took 40 characters and
        // stopped, with no mark — so a run log line read as the whole prompt,
        // wrapped over two rows of the panel, and pushed the timings that the
        // log exists for off the side. Reported from the phone, 2026-09-15.
        // ⚠ An ellipsis only when something was actually cut: a prompt that
        // fits must not be made to look truncated.
        override fun describe() =
            positive.ifBlank { return@describe "(no prompt)" }
                .let { if (it.length <= PROMPT_BRIEF) it else it.take(PROMPT_BRIEF).trimEnd(' ', ',') + "…" }
    }

    /**
     * ⭐⭐ A bundle of float tensors held app-side —
     * [com.abrah.nightmare.npu.TensorStore].
     *
     * ⚠⚠ **Not a [Handle], and the difference is the process.** A `Handle`
     * names a tensor in the BACKEND, and the executor proves it is still alive
     * by asking `GET /handles`. These live in this process, produced and
     * consumed by `libnmqnn.so`, and there is no server to ask — so liveness
     * is a lookup in the store.
     *
     * @param kind `video_cond` or `video_latent`. ⚠ It is what the PORT type
     *   maps to, and the two must not be merged into one name: a conditioning
     *   wired into a latent port is a graph that would run and produce
     *   confident nonsense.
     */
    data class Tensors(val id: String, val kind: String) : Value {
        override fun address() = id
        override fun describe() = kind.replace('_', ' ')
    }

    /**
     * ⭐ A CAPABILITY, not data — what `mask.segment_model` puts on its wire.
     *
     * ⚠ Wiring it is the whole point (`docs/SEGMENTER.md` §1): it makes the Tap
     * tool appear in the inpaint editor. The regions themselves travel as taps
     * in the mask param, so the value carries nothing and addresses by [kind].
     */
    data class Capability(val kind: String) : Value {
        override fun address() = kind
        override fun describe() = kind
    }

    /**
     * The content address this value contributes to a DOWNSTREAM node's key.
     *
     * ⚠ Never the producing node's id: two nodes that computed the same thing
     * must be interchangeable to their consumers, or the cache cannot see that
     * a re-render produced an identical latent. That case is not theoretical --
     * it is exactly what happens after a backend restart.
     */
    fun address(): String

    fun describe(): String
}

/**
 * ⭐ The frame a UI should draw for this value, or null when there is nothing to
 * look at.
 *
 * ⚠ It exists so the canvas, the results store and the harness do not each
 * acquire their own `when` over [Value] — a video is a picture as far as every
 * one of them is concerned, and the day it stopped being one was the day four
 * call sites would have had to be found.
 */
fun Value.previewImage(): Value.Image? = when (this) {
    is Value.Image -> this
    is Value.Video -> poster
    else -> null
}

/**
 * ⭐ The v1 model, pinned in ONE place.
 *
 * `docs/ARCHITECTURE.md` §5.1: v1 builds against **AbsoluteReality alone** --
 * not preference, but because it is the checkpoint family every measured
 * inpaint and ControlNet number in `../LocalDream/docs/` was taken on, so that
 * knowledge transfers without re-measuring. ⚠ DreamShaper is DreamUI's own
 * addition and is explicitly NOT v1 material; it was this project's dev fixture
 * only because it happened to be the tree already staged on the phone.
 *
 * ⚠ A constant because it was 28 string literals across four files, which made
 * "change the model" an edit you could do four-fifths of. It is the `model`
 * half of [ContextKey], and §5.2 pins that for the whole graph.
 */
const val V1_MODEL = "absolutereality"

/**
 * Why `model` cannot be typed into.
 *
 * ⚠⚠ **It used to lock all three context-key knobs; now it locks only this
 * one.** `width`/`height` became editable on the node (they are still
 * [Widget.contextKey], and choosing one rewrites the whole graph), so a reason
 * that said "bound when the backend launches" on a knob the user can change
 * would be describing the wrong thing. The model is still not editable here:
 * changing it means a different catalogue entry, a different download, and a
 * different set of reachable sizes.
 *
 * ⚠ It names WHERE to change it. A lock with no way out is the state
 * `contextKeyRetarget` was written to rescue people from.
 */
const val CONTEXT_KEY_LOCK =
    "bound when the backend launches — open Models to change the checkpoint"

/**
 * `(type, model, resolution)` -- the three things bound at BACKEND LAUNCH
 * (docs/ARCHITECTURE.md §4). Changing any of them is a kill + relaunch costing
 * 2.3-5 s, about a whole render.
 *
 * v1 pins one key for the whole graph (§5.2), so the executor's job here is not
 * to schedule transitions but to REFUSE a graph that needs one -- loudly. A
 * silently ignored second key would run those nodes against a backend launched
 * for the first, and `/vae_decode` at the wrong size decodes plausible garbage
 * rather than failing.
 */
data class ContextKey(val type: String, val model: String, val width: Int, val height: Int) {
    override fun toString() = "$type/$model/${width}x$height"
}

/**
 * One end of a wire: a name and what may flow through it.
 *
 * ⚠ Shared vocabulary, declared here rather than in the plugin manifest, because
 * the CANVAS needs it for built-ins too. Before this existed a node type knew
 * only what the graph happened to connect, so a node could not be drawn until
 * it was already wired -- and an unconnected input had nowhere to appear.
 */
data class Port(val name: String, val type: String)

/**
 * A knob on a node.
 *
 * ⚠ [default] is a STRING like every other param, converted once where the
 * manifest is read. The cache key is a hash of the params, and a hash over
 * Double formatting changes with the locale — so "0.5" typed by a user and 0.5
 * read from JSON have to arrive here as the same characters.
 *
 * ⚠ Shared with the canvas, which needs the same list for a built-in as for a
 * plugin node. A built-in whose knobs were implicit could not be edited at all.
 */
data class Widget(
    val name: String,
    val type: String,
    val default: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    /**
     * Why this knob cannot be edited, or null when it can.
     *
     * ⚠⚠ A knob that looks editable and is not is worse than no knob. The
     * context-key params (`model`, `width`, `height`) are bound at BACKEND
     * LAUNCH: setting one to something else does not render at that size, it
     * makes the executor refuse the whole graph with "needs 2 backend
     * contexts". They were offered as ordinary text fields until a user asked
     * why they could pick a resolution that did not work.
     */
    val locked: String? = null,
    /**
     * One line saying what this knob is FOR, shown under the field.
     *
     * ⚠ Needed because two nodes can carry a knob of the same name doing
     * different jobs -- `sample` and `vae_encode` both have a `seed`, and only
     * one of them is the "give me a different picture" seed. A user cannot be
     * expected to infer that from the name.
     */
    val hint: String? = null,
    /**
     * The values this knob may take, when they are a short fixed set.
     *
     * ⭐ The inspector draws a row of chips instead of a text field. ⚠ It exists
     * because a knob whose only legal values are `black` and `mirror` rendered
     * as a free text box invites `Black`, `mirrored`, and a typo that fails at
     * Run — and because the first widget that needed it (`crop`'s padding) will
     * not be the last: a plugin manifest can declare one too.
     */
    val options: List<String>? = null,
    /**
     * ⭐⭐ This knob is one third of the [ContextKey] — it is bound at BACKEND
     * LAUNCH, not sent with a request.
     *
     * ⚠⚠ **Separate from [locked], and it has to be.** The two used to be the
     * same thing: `contextKeyRetarget` found the knobs to rewrite by filtering
     * on `locked == CONTEXT_KEY_LOCK`, which was exact only while every
     * context-key knob happened to be uneditable. `width`/`height` are editable
     * now and `model` is not, so a single flag can no longer mean both "the
     * user may not touch this" and "this must be rewritten graph-wide".
     *
     * ⚠ Declared by the WIDGET rather than matched by name, so a plugin node
     * that declares its own context-key knobs is retargeted too, and a node
     * that merely happens to carry a param called `width` is not.
     */
    val contextKey: Boolean = false,
    /**
     * ⭐⭐ This knob needs PRECISION at the bottom of its range, so the slider
     * weights its travel there and keeps two decimals there.
     *
     * ⚠⚠ Written for `cfg`, and the reason is distilled checkpoints: LCM,
     * Turbo and Lightning models live between 1.0 and 2.0, where the difference
     * between 1.02 and 1.2 is visible and the difference between 7 and 8 is
     * not. A plain linear 1..20 slider gives that whole band **5% of the
     * track** — about 18dp on a phone — so the values that matter most are the
     * ones a finger cannot land on. Asked for from the phone, 2026-09-11
     * ("add decimal cfg i.e. 1.002, 1.02").
     *
     * ⚠ Opt-in rather than inferred from the range: `denoise` is 0..1 and
     * wants its travel spread evenly, and guessing from the numbers would have
     * curved it too.
     */
    val fine: Boolean = false,
    /**
     * ⭐ An `int` knob that may only land on multiples of this, counted from
     * [min]. Written for the DiT families' width/height: the engine renders any
     * size 512–2048, but only in 256-px steps (upstream's `DitResolution`).
     */
    val step: Int? = null,
) {
    /** True for the kinds that want a numeric keyboard rather than a text one. */
    val numeric: Boolean get() = type == "int" || type == "float"
}

/**
 * The far end of a wire: a node, and WHICH of its outputs the wire left.
 *
 * ⚠⚠ [port] is nullable and null is not "unset" — it means **the node's sole
 * output**, resolved against the upstream type when the graph runs. Before this
 * type existed an input named only a node id, so a node with two outputs had no
 * way to say which one a wire came from; every node shipped so far has exactly
 * one output, which is the only reason that was survivable.
 *
 * ⚠ Serialised as `"node"` or `"node:port"` (see [toString]/[parse]) so a saved
 * workflow's `inputs` stays a flat string map — the bare form is what every
 * workflow written before this change contains, and it still loads. ⇒ **A node
 * id may not contain `:`**; [topoSort] refuses one by name rather than letting
 * it split into a port nobody wrote.
 */
data class Source(val node: String, val port: String? = null) {

    override fun toString() = if (port == null) node else "$node$SEP$port"

    companion object {
        const val SEP = ':'

        /** ⚠ First `:`, not last: a port name is the tail, and ids cannot contain one. */
        fun parse(s: String): Source {
            val i = s.indexOf(SEP)
            return if (i < 0) Source(s) else Source(s.substring(0, i), s.substring(i + 1))
        }
    }
}

/**
 * ⭐⭐ The node types that ROLL A SEED — the one list, so nothing can know about
 * half of them.
 *
 * ⚠⚠ It exists because `"sd.sample"` was written as a literal in six places
 * that each mean "the sampler": the pre-run roll (`HarnessOps.runRolled`), the
 * seed shown on a picture and the node a lock writes it to
 * (`canvas.seedFor`/`canvas.samplerFor`), the run bar's lock button, and the
 * seed filed with a kept result. `nd.video_sample` matched none of them, so a
 * video graph rolled nothing (`seed 0` hashed to `"0"` and the executor served
 * the CACHED clip — the exact failure `runRolled`'s own comment describes),
 * showed no seed, and offered no lock. Reported from the phone, 2026-09-12.
 *
 * ⚠ Matched by TYPE, not by "has a widget called seed": `sd.vae_encode` carries
 * one and rolling it would make every img2img graph full price every Run — the
 * distinction `runRolled` already drew and the reason this is a list rather
 * than a predicate over widgets.
 */
// ⚠⚠ `nd.first_frame` rolls too: it has its own seed and generates the
// picture the clip starts from, so a graph whose frame seed never rolled
// would animate the same still every Run.
/**
 * ⭐⭐ Every sampler that makes a PICTURE, as a SET.
 *
 * ⚠⚠ The fork of 2026-09-15 (capability × family, `docs/ARCHITECTURE.md` §5.7)
 * turned every `type == "sd.sample"` into a membership test. A rule that still
 * compares one string works on SD 1.5 and silently does nothing on SDXL — which
 * is the shape of bug that renders fine and is wrong.
 */
/**
 * ⭐⭐⭐ **The LAST-NODE rule** — a node is "last" when nothing of its own KIND
 * is downstream of it.
 *
 * The user's rule, 2026-09-15, and it settles two questions with one predicate:
 *
 * | applied to | means |
 * |---|---|
 * | a sampler | only this one may arm a batch sweep |
 * | an output | only this one carries save / star / download, and feeds Results |
 *
 * ⚠⚠ **Why the LAST sampler and not any.** Sweeping an earlier one re-runs
 * everything downstream of it, so ten seeds on a two-sampler chain is twenty
 * renders — a control that does not say so is a control that hides a
 * twenty-minute job behind one tap.
 *
 * ⭐ It handles BRANCHES without a special case, which is why it is stated as
 * "of its own kind" rather than "the last node in the graph". Two chains that
 * each end in an output have two last outputs, and each owns its own branch —
 * the user's call: *allow it; the sweep and Results use the branch you armed*.
 * A rule phrased as "the single furthest-downstream node" would have had no
 * answer there.
 *
 * ⚠ Cycles cannot reach here — `topoSort` refuses them by name — but the walk
 * is bounded anyway, because this runs while a sheet is OPENING and a graph
 * that will not run must still open.
 */
fun isLastOfKind(graph: Graph, nodeId: String, kind: (Node) -> Boolean): Boolean {
    val consumers = graph.nodes
        .flatMap { n -> n.inputs.values.map { it.node to n.id } }
        .groupBy({ it.first }, { it.second })
    val seen = mutableSetOf(nodeId)
    val queue = ArrayDeque(consumers[nodeId].orEmpty())
    var hops = 0
    while (queue.isNotEmpty() && hops++ < 256) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        val n = graph.byId[id] ?: continue
        if (kind(n)) return false
        queue.addAll(consumers[id].orEmpty())
    }
    return true
}

/** ⭐ May this sampler arm a sweep? Only the last one in its chain may. */
fun canSweep(graph: Graph, nodeId: String): Boolean {
    val node = graph.byId[nodeId] ?: return false
    if (!isSampler(node.type)) return false
    return isLastOfKind(graph, nodeId) { isSampler(it.type) }
}

/** ⭐ Does this output node own its branch's picture actions and Results entry? */
fun isLastOutput(graph: Graph, nodeId: String): Boolean {
    val node = graph.byId[nodeId] ?: return false
    if (node.type != "core.output") return false
    return isLastOfKind(graph, nodeId) { it.type == "core.output" }
}

/**
 * ⚠ How much of a prompt a one-line readout shows. A run-log row is one line
 * beside a node id and a duration; 28 characters is what fits beside them on a
 * 411dp phone, and the rest is on the node itself where it can be read.
 */
const val PROMPT_BRIEF = 28

// ⚠⚠⚠ **Read off [SdSampler.ALL], never written out.** Rule changed 2026-09-19,
// and the bug that changed it is the one this whole file warns about: the eight
// names were a LITERAL here, and when the two DiT families were registered in
// `Fused.kt` (2026-09-19) nobody added them. `flux2.sample` and `zimage.sample`
// were then in no set at all, so a Flux node silently lost about twenty rules a
// sampler has -- no checkpoint picker, no crop popup, no batch sweep, no
// framing reset, no seed on its result, and, worst, **no seed roll**: `isSampler`
// said no, seed 0 was never rolled, and the second Run served the CACHED
// picture. That is verbatim the `nd.video_sample` failure described above,
// re-made ten days later against a literal that was right when it was written.
// ⇒ A registration is now the only place a sampler is declared. Adding one to
// `SdSampler.ALL` is what puts it in these sets; there is nothing left to
// forget.
// ⚠ Renamed from `IMAGE_SAMPLER_TYPES` in the same edit: FLUX.2 and Z-Image are
// not SD, and a name that says otherwise is how the next family gets left out.
val IMAGE_SAMPLER_TYPES: Set<String> = SdSampler.ALL.map { it.name }.toSet()

/**
 * ⭐⭐⭐ **What a node is CALLED on screen — the one answer, for every
 * surface that draws a node's name.**
 *
 * ⭐⭐ **Only a SAMPLER's id is overridden, and the boundary is not
 * cosmetic.** A sampler is the one node whose TYPE changes under it — the
 * checkpoint dropdown retypes it across families — so an id that named a job
 * or a family is the one thing guaranteed to go stale. Every other node keeps
 * its id as its name: `photo`, `prompt`, `output` and `mask` are words the
 * recipes chose deliberately and they are BETTER than the type's label, which
 * a first version of this learned the hard way by renaming `photo` to `image`
 * on five goldens.
 *
 * ⚠⚠⚠ The canvas box and the inspector's tabs both name nodes, and they
 * gave different answers: the box drew [Node.id] large with the job title as a
 * subtitle it dropped when cramped, and the tabs drew the id alone. An id is
 * frozen at creation, so a node added as SD 1.5 and switched to FLUX.2 went on
 * calling itself `sd15_generate` on both — reported 2026-09-21 as "the node
 * doesn't change to edit node", when [SdSampler.titleFor] had been returning
 * "FLUX.2 Image edit" the whole time and almost nothing showed it.
 *
 * ⭐⭐ **An AUTO-GENERATED id is not a name a person chose.** `flux2_generate`
 * encodes the type, which is exactly what goes stale; a name the user typed does
 * not. ⇒ [primary] is the job title while the id is still automatic, and the
 * user's name the moment there is one. The 2026-09-15 rule that "a node's id is
 * what you are looking for" is kept where it was actually true.
 */
data class NodeName(
    /** The big line: what the user called it, else what it does. */
    val primary: String,
    /** The small line, or null when it would only repeat [primary]. */
    val secondary: String?,
)

/**
 * ⚠⚠ Whether [id] is one this app generated rather than one a person typed.
 *
 * ⚠⚠⚠ Tested against EVERY registered type's [NodeType.defaultId], not
 * just the node's current one — that is the whole point. A node swapped from
 * SD 1.5 to FLUX.2 keeps the id `sd15_generate` while its type now defaults to
 * `flux2_generate`, and comparing against the current type alone would call
 * that stale id a deliberate name and show it.
 *
 * ⚠ The `_2`, `_3`… suffix [Graph.freeId] adds is part of an auto id.
 */
fun isAutoNodeId(id: String, types: Map<String, NodeType> = NODE_TYPES): Boolean {
    val bases = types.values.mapNotNull { it.defaultId } + RECIPE_NODE_IDS
    return bases.any { id == it || Regex(Regex.escape(it) + """_\d+""").matches(id) }
}

/**
 * ⭐⭐⭐ **Every node id the BUILT-IN RECIPES write**, derived by building them
 * rather than listed by hand.
 *
 * ⚠⚠⚠ A recipe hand-writes short ids — `edit`, `generate`, `prompt`,
 * `photo`, `output` — and none of them is any type's [NodeType.defaultId]. That
 * made [isAutoNodeId] call them names a person had chosen, so opening the Image
 * edit flow and switching the checkpoint to SD 1.5 left the node still saying
 * **edit**. Reported 2026-09-21, and it is the same bug as the frozen
 * `defaultId` one directly above, entering by a second door.
 *
 * ⚠⚠ DERIVED, not a list: a new recipe's ids join this set by existing, and a
 * hand-kept copy is the thing that would go stale next. ⚠ `by lazy` — building
 * every recipe is cheap but pointless until a node is drawn.
 *
 * ⚠ A person who renames a node to exactly `edit` is then shown the job title
 * instead of their word. Accepted: it is a word this app already uses for that
 * node, and the title it falls back to says the same thing.
 */
private val RECIPE_NODE_IDS: Set<String> by lazy {
    runCatching {
        com.abrah.nightmare.canvas.RECIPES
            .flatMap { it.build().graph.nodes.map { n -> n.id } }
            .toSet()
    }.getOrDefault(emptySet())
}

/**
 * ⭐ [NodeName] for one node. Both surfaces call THIS — two hand-rolled
 * lookups are how they stopped agreeing in the first place.
 */
fun nodeNameOf(node: Node, types: Map<String, NodeType> = NODE_TYPES): NodeName {
    // ⚠⚠ The canvas's own [com.abrah.nightmare.canvas.nodeLabel] for the
    // fallback, never a local `substringAfterLast('.')`. It carries
    // LABEL_OVERRIDES, and re-deriving it here renamed the prompt node's
    // second line from `prompt` to `clip_encode` — caught by the canvas
    // goldens, 2026-09-21, which is the sibling rule failing yet again.
    val type = types[node.type]
    val title = type?.titleFor(node) ?: node.type.nodeLabel
    val sampler = type as? SdSampler
    return if (sampler != null && isAutoNodeId(node.id, types)) {
        // ⚠⚠ The JOB big and the FAMILY small, not the whole title big: the
        // full string does not fit a node's width and ellipsises away the job
        // ([SdSampler.jobFor]). The family is the half a person can also read
        // off the checkpoint field, so it is the half that gives way.
        NodeName(sampler.jobFor(node), sampler.family.label)
    } else {
        NodeName(node.id, title.takeIf { it != node.id })
    }
}

/** ⚠ The ones that carry a mask, its editor and the paste back. */
val INPAINT_TYPES: Set<String> = SdSampler.ALL.filter { it.inpaint }.map { it.name }.toSet()

val SAMPLER_TYPES = IMAGE_SAMPLER_TYPES + setOf("nd.sample")

/**
 * ⭐ Every node that FRAMES a picture it was given — every image sampler and
 * the video one.
 *
 * ⚠ The inspector draws a framing view for these and the canvas shows their
 * framed input; `image.crop` is framing too but is its own node, so it is added
 * where that matters rather than here.
 */
val FRAMING_TYPES = IMAGE_SAMPLER_TYPES + setOf("nd.sample")

/**
 * ⭐ Every node holding a FRAMING of a picture it was handed — the `x/y/w/h`
 * rect and its lock. ⚠ One home: the inspector draws a framing view for exactly
 * these, and [Graph.withNewPicture] resets exactly these.
 */
// ⚠ `image.crop` was here until it was deleted (2026-09-17); the samplers and the
// video node are every framing node now.
val FRAMES_PICTURE_TYPES = FRAMING_TYPES

/**
 * ⭐ The incoming picture SHAPE a sampler was last auto-fitted to
 * (`HarnessViewModel.fitChainedImageToImage`). ⚠ Forgotten with the framing, so a
 * different source is fitted afresh.
 */
const val FITTED_TO = "fitted_to"

/**
 * ⭐ Every node holding a PAINTING on a picture it was handed.
 * ⚠⚠ Only the INPAINT samplers — an image-to-image node has no mask editor.
 */
val PAINTS_PICTURE_TYPES = setOf("image.mask") + INPAINT_TYPES

/** ⚠ See [SAMPLER_TYPES] — never compare against one of those strings directly. */
fun isSampler(type: String): Boolean = type in SAMPLER_TYPES

/**
 * Builds a [Node.inputs] map from the compact wire spelling.
 *
 * ⚠ Each value goes through [Source.parse], so `"sample"` is the sole output and
 * `"sample:latent"` names one. Graph literals are written by hand all over the
 * harness and the tests, and `mapOf("latent" to Source("sample"))` at every one
 * of them buries the wiring under the ceremony.
 */
fun sources(vararg wires: Pair<String, String>): Map<String, Source> =
    wires.associate { (port, from) -> port to Source.parse(from) }

/**
 * One node. [params] are the widget values; [inputs] maps an input port to the
 * [Source] feeding it.
 *
 * ⚠ Params are strings deliberately. The key is a hash of them, and a hash over
 * Double formatting is a hash that changes when an unrelated locale does.
 */
data class Node(
    val id: String,
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val inputs: Map<String, Source> = emptyMap(),
)

data class Graph(val nodes: List<Node>) {
    val byId: Map<String, Node> = nodes.associateBy { it.id }

    /**
     * Wire [fromNode]'s output into [toNode]'s [port].
     *
     * ⚠ REPLACES whatever was on that input. An input takes one wire; letting
     * two land would make the graph's meaning depend on map ordering, and the
     * user's mental model is "I dropped a new wire on it" either way.
     */
    /**
     * ⚠ Params only. A widget edit must not disturb wiring, and it must not be
     * able to: the canvas calls this, and the canvas is where a stray edit
     * would silently rewire a graph the user is looking at.
     */
    fun withParam(nodeId: String, name: String, value: String) = copy(
        nodes = nodes.map { if (it.id == nodeId) it.copy(params = it.params + (name to value)) else it }
    )

    /**
     * Several params at once, as ONE change.
     *
     * ⚠⚠ Not a convenience. An interactive widget sets a whole tuple per
     * pointer event -- the cropper's four numbers ARE one rectangle -- and
     * applying them one at a time makes four graph revisions, four canvas
     * updates and four autosaves for a single movement of one finger. Those
     * concurrent saves crashed the app (`WorkflowStore`, `HarnessViewModel`).
     */
    fun withParams(nodeId: String, values: Map<String, String>) = copy(
        nodes = nodes.map { if (it.id == nodeId) it.copy(params = it.params + values) else it }
    )

    /**
     * ⭐⭐ A new picture on [nodeId] — and every framing and painting downstream
     * of it forgotten.
     *
     * ⚠⚠ Both are stored NORMALISED to the picture they were made on, so they
     * survive a new photo as numbers and mean nothing on it: a crop around a
     * face lands on a wall, a mask painted over a hand lands on the sky. Asked
     * for 2026-09-16, *"choosing a new image should reset the crop and the mask,
     * for all nodes"* — so DOWNSTREAM, not only the next node: a crop feeding an
     * inpaint sampler is framing the same new photo.
     *
     * ⚠ The LOCK goes too. It protected a framing that no longer exists, and a
     * lock left on the default rect would refuse the first drag on the new one.
     * `grow`/`feather`/padding stay — those are settings, not content.
     */
    fun withNewPicture(nodeId: String, uri: String): Graph =
        withParam(nodeId, "uri", uri).forgettingPictureWork { dependsOn(it, nodeId) }

    /**
     * ⭐⭐ The ONE place a framing and a painting are forgotten — for every node
     * [which] selects that holds one.
     *
     * ⚠⚠ Two doors reach it: a new photo ([withNewPicture]) and a picture input
     * WIRED to a different source ([connected]). Only the first existed, so an
     * inpaint rewired from a photo to a generate node kept the mask painted on the
     * photo — it looked inherited from the new source (reported 2026-09-17).
     */
    private fun forgettingPictureWork(which: (String) -> Boolean): Graph {
        val framing = setOf("x", "y", "w", "h", CropNode.LOCKED, FITTED_TO)
        return copy(nodes = nodes.map { n ->
            if (!which(n.id)) n else {
                var drop = emptySet<String>()
                if (n.type in FRAMES_PICTURE_TYPES) drop = drop + framing
                if (n.type in PAINTS_PICTURE_TYPES) drop = drop + MaskNode.OPS + MaskNode.PAINTED_ON
                if (drop.isEmpty()) n else n.copy(params = n.params - drop)
            }
        })
    }

    /**
     * Remove a node, and every wire that pointed at it.
     *
     * ⚠⚠ The second half is the part that matters. A dangling input makes
     * `topoSort` refuse the whole graph with "names unknown node" -- so deleting
     * one node would break a graph the user can still see, at a node they did
     * not touch, and only when they pressed Run.
     */
    fun without(nodeId: String): Graph {
        // ⚠ A node whose PICTURE came from [nodeId] loses the framing and
        // painting made on it — with the source gone they point at nothing, and
        // wiring a new source in afterwards would otherwise inherit them.
        val readers = nodes.filter { it.inputs["image"]?.node == nodeId }.map { it.id }.toSet()
        val base = if (readers.isEmpty()) this else forgettingPictureWork { id ->
            id in readers || readers.any { r -> dependsOn(id, r) }
        }
        return base.copy(
            nodes = base.nodes
                .filterNot { it.id == nodeId }
                .map { it.copy(inputs = it.inputs.filterValues { up -> up.node != nodeId }) }
        )
    }

    /**
     * A node id that is free, based on [base].
     *
     * ⚠ Ids must be unique: `topoSort` refuses duplicates outright, so a palette
     * that reused one would make the graph unrunnable the moment a second node
     * of the same type was added.
     */
    fun freeId(base: String): String {
        if (base !in byId) return base
        var n = 2
        while ("${base}_$n" in byId) n++
        return "${base}_$n"
    }

    /**
     * ⚠ [from] carries the OUTPUT PORT as well as the node. The canvas already
     * hit-tests each output dot separately, so dropping the port here is what
     * used to make two outputs indistinguishable once the wire existed.
     */
    fun connected(toNode: String, port: String, from: Source): Graph {
        val was = byId[toNode]?.inputs?.get(port)
        val wired = copy(
            nodes = nodes.map { if (it.id == toNode) it.copy(inputs = it.inputs + (port to from)) else it }
        )
        // ⭐⭐ A DIFFERENT picture coming in forgets the framing and painting on
        // it and everything downstream — the new-photo rule, for a rewire.
        // ⚠ Only a real change of source: the first wire into an empty port has
        // nothing to forget, and re-dropping the same wire must not wipe a mask.
        if (port != "image" || was == null || was == from) return wired
        return wired.forgettingPictureWork { it == toNode || wired.dependsOn(it, toNode) }
    }

    /** Convenience for the common single-output case and for tests. */
    fun connected(toNode: String, port: String, fromNode: String) =
        connected(toNode, port, Source(fromNode))

    /**
     * Remove ONE wire: the input [port] on [toNode] reads nothing again.
     *
     * ⚠⚠ Named by the INPUT end, always. The graph stores "this input reads that
     * source", so an input identifies exactly one wire while an output may feed
     * many — "delete the wire from X" would take out a fan-out the user never
     * touched. That is also why `WireRef` is keyed this way.
     */
    fun disconnected(toNode: String, port: String): Graph {
        val had = byId[toNode]?.inputs?.containsKey(port) == true
        // ⚠ Forgotten BEFORE the wire goes, while "downstream" still means it.
        val base = if (port == "image" && had) {
            forgettingPictureWork { it == toNode || dependsOn(it, toNode) }
        } else this
        return base.copy(
            nodes = base.nodes.map { if (it.id == toNode) it.copy(inputs = it.inputs - port) else it }
        )
    }

    /**
     * Does [node] read [maybeUpstream], directly or through any chain?
     *
     * ⚠ Iterative rather than recursive: this runs on a graph the user is
     * drawing, and a graph that already contains a cycle would recurse until
     * the stack ran out. The visited set makes it terminate on any input.
     */
    fun dependsOn(node: String, maybeUpstream: String): Boolean {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(byId[node]?.inputs?.values?.map { it.node }.orEmpty())
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (next == maybeUpstream) return true
            if (!seen.add(next)) continue
            byId[next]?.inputs?.values?.forEach { stack.addLast(it.node) }
        }
        return false
    }

    /**
     * ⚠⚠ Would wiring [fromNode] into [toNode] close a loop?
     *
     * A canvas lets people draw cycles by accident, and the executor only
     * discovers one at run time -- after the user has already pressed Run and
     * waited. Refusing at the moment of the drop is the difference between "you
     * cannot do that" and "that did not work".
     */
    fun wouldCycle(fromNode: String, toNode: String): Boolean =
        fromNode == toNode || dependsOn(fromNode, toNode)
}

/** The delimiter inside a key's pre-image: a character no param name can hold. */
private const val SEP = '\u001F'

/**
 * `(node type + version, widget values, addresses of input values)` --
 * docs/ARCHITECTURE.md §4, and the whole difference between a node graph and a
 * preset picker with wires.
 *
 * ⚠ The type VERSION is in the key on purpose. When a node's implementation
 * changes, every cached output of it must stop matching; without the version
 * the app keeps serving results computed by code that no longer exists.
 *
 * ⚠ It is a STRING, not a number, because a plugin node's version is its
 * plugin's — `com.example.pack@0.2.0`. A hot-reloaded plugin whose outputs kept
 * matching the old ones is exactly the bug this field exists to prevent, and it
 * is the bug a plugin ecosystem hits first.
 *
 * ⚠ Sorted, and delimited. Concatenating unsorted `k=v` pairs makes the key
 * depend on map iteration order, which is stable right up until it is not, and
 * an undelimited concatenation collides (`a=1,b=2` against `a=1b=2`).
 */
/**
 * ⚠⚠ One cache entry per OUTPUT PORT of a node.
 *
 * [cacheKey] already covers the type, version, params and inputs — everything
 * that decides what a node produces. The port name is what distinguishes the
 * two things it produced FROM each other, and without it a two-output node's
 * second value would overwrite its first.
 */
fun portKey(nodeKey: String, port: String): String = "$nodeKey#$port"

fun cacheKey(
    type: String,
    version: String,
    params: Map<String, String>,
    inputs: Map<String, Value>,
): String {
    val sb = StringBuilder()
    sb.append(type).append('@').append(version)
    // ⚠⚠ **Batch arming is EXCLUDED from the key.** A `batch:` param records
    // that a knob is armed for a sweep — it stores the RANGE, and changes
    // nothing about the picture this node renders right now. Left in the key,
    // arming or releasing a batch would invalidate the node and everything
    // downstream of it, so tapping the icon would spend a full render to change
    // a control's colour. ⚠ This is the one exception to `applyDefaults`'s rule
    // that undeclared params are kept and hashed; it is safe precisely because
    // the executor never reads them either (`BatchParams`).
    params.toSortedMap()
        .filterKeys { !it.startsWith(BatchParams.PREFIX) }
        .forEach { (k, v) -> sb.append(SEP).append(k).append('=').append(v) }
    inputs.toSortedMap().forEach { (k, v) -> sb.append(SEP).append(k).append('=').append(v.address()) }
    val d = MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray())
    return d.joinToString("") { "%02x".format(it) }.take(32)
}

/**
 * The result of ordering a graph.
 *
 * ⚠ A cycle is reported by NAMING the nodes left over, not by returning a
 * shorter list. A truncated topological order is a graph that silently runs
 * half of itself, and the canvas will let users draw cycles by accident.
 */
sealed interface Order {
    data class Ok(val nodes: List<Node>) : Order
    data class Broken(val why: String) : Order
}

/** Kahn's algorithm: every input is produced before it is read. */
fun topoSort(graph: Graph): Order {
    val dupes = graph.nodes.groupBy { it.id }.filterValues { it.size > 1 }.keys
    if (dupes.isNotEmpty()) return Order.Broken("duplicate node id: ${dupes.joinToString(", ")}")
    // ⚠ A `:` in an id would be read back as a port suffix by [Source.parse],
    // so a graph carrying one is refused HERE, by name. Left to the parser it
    // would instead load as a wire from a node that does not exist, and the
    // error would name the wrong thing entirely.
    graph.nodes.firstOrNull { Source.SEP in it.id }?.let {
        return Order.Broken("node id \"${it.id}\" contains '${Source.SEP}', which separates a node from its output port")
    }
    for (n in graph.nodes) {
        for ((port, upstream) in n.inputs) {
            if (upstream.node !in graph.byId) {
                return Order.Broken(
                    "node \"${n.id}\" input \"$port\" names unknown node \"${upstream.node}\""
                )
            }
        }
    }
    val indegree = graph.nodes.associate { it.id to it.inputs.size }.toMutableMap()
    val dependents = mutableMapOf<String, MutableList<String>>()
    for (n in graph.nodes) for (up in n.inputs.values) {
        dependents.getOrPut(up.node) { mutableListOf() }.add(n.id)
    }
    // Seeded in declaration order, so independent nodes run in the order they
    // were written -- a shuffled log is hard to read and harder to diff.
    val ready = ArrayDeque(graph.nodes.filter { indegree[it.id] == 0 }.map { it.id })
    val out = mutableListOf<Node>()
    while (ready.isNotEmpty()) {
        val id = ready.removeFirst()
        out.add(graph.byId.getValue(id))
        for (d in dependents[id].orEmpty()) {
            val left = indegree.getValue(d) - 1
            indegree[d] = left
            if (left == 0) ready.addLast(d)
        }
    }
    if (out.size != graph.nodes.size) {
        val stuck = graph.nodes.map { it.id } - out.map { it.id }.toSet()
        return Order.Broken("cycle among: ${stuck.joinToString(", ")}")
    }
    return Order.Ok(out)
}
