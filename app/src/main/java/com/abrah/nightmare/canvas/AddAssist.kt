package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Source

/**
 * ⭐⭐⭐ **What a node needs the moment it is dropped, worked out before it is.**
 *
 * The user's ask, 2026-09-22: *"add node is kind of tedious. for eg to add an
 * inpaint node a user has to manually add the prompt and segmenter. and also
 * wire the inpaint node to the previous sensible node … so when we add a sample
 * node like inpaint we offer to include helper nodes (checkboxed list by
 * default) and also offer to smart snap it to the node that makes sense."*
 *
 * ⚠⚠⚠ **Derived from the node's own declared PORTS, never from a table of
 * "inpaint needs a prompt".** A hand-written table is a second description of
 * the node set, and this app has already paid for one of those: every rule
 * written out per call site in the inspector drifted until `Chooser` existed
 * (`docs/UI.md` §8.6). A plugin's node gets the same assistance as a built-in
 * one for free, which a table could never do.
 *
 * ⚠⚠ It only ever proposes. Nothing here edits a graph — [applyAdd] does, from
 * the boxes the person left ticked.
 */

/** One existing node that could feed one of the new node's inputs. */
data class SnapCandidate(
    /** The input port ON THE NEW NODE. */
    val port: String,
    /** The existing node that would feed it, and the output port it feeds from. */
    val fromNode: String,
    val fromPort: String,
    /**
     * ⭐ Ticked when the sheet opens.
     *
     * ⚠ At most one per port: two sources on one input is not a graph this app
     * can store ([Graph.connected] replaces rather than adds), so a second
     * recommendation would be a box that silently unticks the first.
     */
    val recommended: Boolean,
)

/**
 * One node to create alongside and wire into [port].
 *
 * ⚠⚠ Offered even when something on the canvas ALREADY feeds that port — the
 * user's call, 2026-09-22: *"you should offer to connect same prompt node or
 * use new one"*. Sharing one prompt between two samplers and giving the second
 * its own are both ordinary things to want, and only the person knows which.
 * [AddPlan.snaps] carries the sharing option; this carries the fresh one, and
 * they are mutually exclusive per port.
 */
data class HelperNode(
    /** The input port ON THE NEW NODE this helper will feed. */
    val port: String,
    val type: NodeType,
)

/**
 * ⭐⭐⭐ **Splicing the new node in FRONT of something that already reads the
 * source** — the case the first version missed.
 *
 * The user, 2026-09-22: *"you didnt account for when the new sample node goes
 * between an existing sample node and an output, that would involve breaking off
 * the wire to output node from sample 1 and instead connecting it from output
 * img wire of sample 2"*.
 *
 * ⇒ When the node being added can feed [consumer]'s [consumerPort] as well as
 * read from [SnapCandidate.fromNode], the wire between them is the one a person
 * almost always means to redirect: otherwise the new node hangs off the side
 * producing a picture nothing looks at.
 */
data class SpliceCandidate(
    /** The existing node whose input is taken over. */
    val consumer: String,
    /** The port on it that currently reads [from]. */
    val consumerPort: String,
    /** What it currently reads — and what the new node will read instead. */
    val from: String,
    /** The new node's output port that will feed [consumer]. */
    val outPort: String,
    /** The new node's input port that takes [from]. */
    val inPort: String,
)

/**
 * ⭐⭐⭐ **An existing node the NEW one would feed** — the other direction.
 *
 * ⚠⚠ The user's ask, 2026-09-22: *"add node assist did well but pls add
 * support for non-sample nodes as well"*. A prompt, a photo and a crop have
 * nothing to take, so the first version offered them nothing at all and the
 * sheet never opened — yet "what does this feed" is the only question worth
 * asking about a node you just dropped at the start of a flow.
 *
 * ⚠ Ticked when [free] — i.e. that port has no wire yet. Filling a hole is
 * safe; replacing a wire someone drew is a decision, so it is offered unticked.
 */
data class FeedCandidate(
    /** The existing node that would read the new one. */
    val toNode: String,
    /** Its input port. */
    val toPort: String,
    /** The new node's output port that feeds it. */
    val outPort: String,
    /** True when [toPort] currently has no wire. */
    val free: Boolean,
)

/**
 * ⭐⭐ The offer: helpers to create, and existing nodes to snap to.
 *
 * ⚠ [isEmpty] is what decides whether the sheet is worth showing at all. A node
 * with no inputs — a prompt, a photo — proposes nothing, and a dialog that
 * offers nothing is the thing `docs/UI.md` §8.10 warns teaches people to tap
 * through dialogs without reading them.
 */
data class AddPlan(
    val type: NodeType,
    val helpers: List<HelperNode>,
    val snaps: List<SnapCandidate>,
    val splices: List<SpliceCandidate> = emptyList(),
    val feeds: List<FeedCandidate> = emptyList(),
) {
    val isEmpty: Boolean
        get() = helpers.isEmpty() && snaps.isEmpty() && splices.isEmpty() && feeds.isEmpty()
}

/**
 * ⭐⭐⭐ Work out what to offer for [type] against the graph as it stands.
 *
 * ⚠⚠ **The RECOMMENDED source is the LAST one in execution order**, not the
 * nearest on screen and not the first found. A person adding an inpaint node
 * after a Run means "inpaint what I just made"; execution order is the only
 * reading of "what I just made" that does not depend on where boxes were
 * dragged. Same reasoning the inspector's strip settled on (`nodeStripOrder`).
 *
 * ⚠ A port that some node can already feed gets NO helper. Offering to create a
 * prompt when there is a prompt sitting there is how a canvas ends up with
 * three of them.
 */
fun planAdd(
    type: NodeType,
    graph: Graph,
    types: Map<String, NodeType>,
): AddPlan {
    val order = when (val o = com.abrah.nightmare.topoSort(graph)) {
        is com.abrah.nightmare.Order.Ok -> o.nodes
        is com.abrah.nightmare.Order.Broken -> graph.nodes
    }
    val snaps = mutableListOf<SnapCandidate>()
    val helpers = mutableListOf<HelperNode>()

    for (input in type.inputs) {
        // Every existing node with an output that fits this input, in execution
        // order — so `last()` is the most recent thing the graph produces.
        val feeders = order.mapNotNull { n ->
            val out = types[n.type]?.outputs?.firstOrNull { fits(it, input) } ?: return@mapNotNull null
            n.id to out.name
        }
        val best = feeders.lastOrNull()?.first
        for ((nodeId, port) in feeders) {
            snaps += SnapCandidate(input.name, nodeId, port, recommended = nodeId == best)
        }
        // ⚠⚠ A fresh node is offered EVEN WHEN something can feed the port —
        // see [HelperNode]. It is ticked only when nothing else can, so the
        // default is still "use what is there".
        helperFor(input, types)?.let { helpers += HelperNode(input.name, it) }
    }

    // ⭐⭐⭐ Where the new node would go BETWEEN two that are already wired.
    //
    // ⚠ Only for a source the new node can actually read, and only where the
    // new node can also feed what reads it — both halves, or it is not a splice.
    val splices = mutableListOf<SpliceCandidate>()
    for (consumer in order) {
        for ((port, src) in consumer.inputs) {
            val consumerPort = types[consumer.type]?.inputs?.firstOrNull { it.name == port } ?: continue
            val out = type.outputs.firstOrNull { fits(it, consumerPort) } ?: continue
            val srcOut = types[graph.byId[src.node]?.type]?.outputs ?: continue
            val inPort = type.inputs.firstOrNull { inp -> srcOut.any { fits(it, inp) } } ?: continue
            // ⚠ A node cannot splice in front of itself, and a splice that
            // closed a loop would be refused at Run instead of here.
            if (consumer.id == src.node) continue
            splices += SpliceCandidate(
                consumer = consumer.id,
                consumerPort = port,
                from = src.node,
                outPort = out.name,
                inPort = inPort.name,
            )
        }
    }
    // ⭐⭐⭐ …and the other direction: what this node would FEED.
    //
    // ⚠ Offered for every node, not only the input-less ones — a crop dropped
    // after a photo wants wiring onward just as much. ⚠⚠ A free port is
    // ticked; an occupied one is offered unticked, because replacing a wire
    // someone drew is their decision (see [FeedCandidate.free]).
    val feeds = mutableListOf<FeedCandidate>()
    for (consumer in order) {
        val consumerType = types[consumer.type] ?: continue
        for (inPort in consumerType.inputs) {
            val out = type.outputs.firstOrNull { fits(it, inPort) } ?: continue
            feeds += FeedCandidate(
                toNode = consumer.id,
                toPort = inPort.name,
                outPort = out.name,
                free = consumer.inputs[inPort.name] == null,
            )
        }
    }
    return AddPlan(type, helpers, snaps, splices, feeds)
}

/**
 * ⚠ The same one-directional rule the canvas refuses a dragged wire with
 * ([connectionError]'s `typeFits`) — expressed once there, asked here. A second
 * opinion about what fits what is how the palette would start offering wires
 * the canvas then refuses.
 */
private fun fits(output: Port, input: Port): Boolean =
    output.type == input.type ||
        (input.type == "MEDIA" && (output.type == "IMAGE" || output.type == "VIDEO"))

/**
 * The node type to CREATE for an unfed port, or null when nothing obvious makes
 * one.
 *
 * ⚠⚠ The cheapest type that produces it, and never a sampler: offering to add
 * a second generate node to feed the first one's image input is a graph nobody
 * asked for and a checkpoint load nobody wanted. ⇒ Only nodes with no inputs of
 * their own are proposed, which is exactly the set of "things you start a flow
 * with" — a prompt, a photo.
 */
private fun helperFor(input: Port, types: Map<String, NodeType>): NodeType? =
    types.values
        // ⚠⚠ **Never a RETIRED type.** `mask.segment_model` and
        // `image.upscale` are hidden from the palette but still registered so
        // old flows load — and this read the registry, so the assist happily
        // offered to create the very nodes the palette had stopped offering.
        // Reported 2026-09-22. ⇒ The same `hidden` flag the palette filters on,
        // asked in both places.
        .filterNot { it.hidden }
        .filter { it.inputs.isEmpty() && it.outputs.any { o -> fits(o, input) } }
        // ⚠ Deterministic, so the sheet does not reorder between openings: map
        // iteration order is not a promise.
        .minByOrNull { it.name }

/**
 * ⭐⭐⭐ Apply what the person left ticked: create the node, create the helpers
 * they kept, wire the snaps they kept, and open the new node.
 *
 * ⚠⚠ **The new node's inspector opens, exactly as [CanvasState.addNode] does.**
 * This replaces that call, so it has to keep its promises — including clearing
 * the picture maps for a reused id, which is the 2026-09-11 and 2026-09-18 bug
 * that comment names.
 *
 * ⚠ Helpers are placed to the LEFT of the new node and stacked, because they
 * feed it: the canvas reads left to right everywhere else.
 */
fun CanvasState.applyAdd(
    plan: AddPlan,
    at: Pt,
    /** Ports whose helper the person kept. */
    helperPorts: Set<String>,
    /** Which existing node feeds which of the new node's ports. */
    snaps: Map<String, SnapCandidate>,
    /**
     * ⭐⭐ The splice they kept, or null. It supplies the new node's input AND
     * redirects the consumer's wire, so it OVERRIDES any snap on the same port.
     */
    splice: SpliceCandidate? = null,
    /** ⭐⭐ Downstream connections they kept — see [FeedCandidate]. */
    feeds: List<FeedCandidate> = emptyList(),
): CanvasState {
    var st = addNode(plan.type, at)
    val newId = st.editing ?: return st

    var row = 0
    for (h in plan.helpers) {
        if (h.port !in helperPorts) continue
        // ⚠ `addNode` moves `editing` onto each helper; the new node is put back
        // at the end, so the sheet that opens is the one the person asked for.
        st = st.addNode(h.type, Pt(at.x - 260f, at.y + row * 120f))
        val helperId = st.editing ?: continue
        val outPort = h.type.outputs.firstOrNull()?.name
        st = st.copy(
            workflow = st.workflow.copy(
                graph = st.workflow.graph.connected(newId, h.port, Source(helperId, outPort)),
            ),
        )
        row++
    }
    for ((port, snap) in snaps) {
        // ⚠ A splice owns its own input port — see [splice].
        if (splice != null && port == splice.inPort) continue
        // ⚠ Checked again here, not just in the sheet: a candidate computed
        // before the person ticked boxes can be stale by the time they finish.
        if (st.workflow.graph.byId[snap.fromNode] == null) continue
        if (st.workflow.graph.wouldCycle(snap.fromNode, newId)) continue
        st = st.copy(
            workflow = st.workflow.copy(
                graph = st.workflow.graph.connected(newId, port, Source(snap.fromNode, snap.fromPort)),
            ),
        )
    }
    // ⭐⭐⭐ The splice: take the source, then TAKE OVER the wire that read it.
    //
    // ⚠⚠ Both halves or neither. Wiring only the input leaves the new node
    // producing a picture nothing looks at, which is what the first version did
    // and what the user reported.
    if (splice != null) {
        val g = st.workflow.graph
        if (g.byId[splice.from] != null && g.byId[splice.consumer] != null &&
            !g.wouldCycle(splice.from, newId)
        ) {
            st = st.copy(
                workflow = st.workflow.copy(
                    graph = g
                        .connected(newId, splice.inPort, Source(splice.from))
                        .connected(splice.consumer, splice.consumerPort, Source(newId, splice.outPort)),
                ),
            )
        }
    }
    // ⚠ Downstream, after the splice — a splice already rewires its consumer,
    // and a feed naming the same port would fight it.
    for (f in feeds) {
        if (splice != null && f.toNode == splice.consumer && f.toPort == splice.consumerPort) continue
        val g = st.workflow.graph
        if (g.byId[f.toNode] == null || g.wouldCycle(newId, f.toNode)) continue
        st = st.copy(
            workflow = st.workflow.copy(
                graph = g.connected(f.toNode, f.toPort, Source(newId, f.outPort)),
            ),
        )
    }
    return st.copy(editing = newId, showPalette = false, message = null)
}

