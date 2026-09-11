package com.abrah.nightmare

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
    data class Image(val id: String, val w: Int, val h: Int) : Value {
        override fun address() = id
        override fun describe() = "image ${w}x$h $id"
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
    "bound when the backend launches -- open Models to change the checkpoint"

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
     * Remove a node, and every wire that pointed at it.
     *
     * ⚠⚠ The second half is the part that matters. A dangling input makes
     * `topoSort` refuse the whole graph with "names unknown node" -- so deleting
     * one node would break a graph the user can still see, at a node they did
     * not touch, and only when they pressed Run.
     */
    fun without(nodeId: String) = copy(
        nodes = nodes
            .filterNot { it.id == nodeId }
            .map { it.copy(inputs = it.inputs.filterValues { up -> up.node != nodeId }) }
    )

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
    fun connected(toNode: String, port: String, from: Source) = copy(
        nodes = nodes.map { if (it.id == toNode) it.copy(inputs = it.inputs + (port to from)) else it }
    )

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
    fun disconnected(toNode: String, port: String) = copy(
        nodes = nodes.map { if (it.id == toNode) it.copy(inputs = it.inputs - port) else it }
    )

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
