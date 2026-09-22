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

/** One node to create alongside, because nothing on the canvas can feed a port. */
data class HelperNode(
    /** The input port ON THE NEW NODE this helper will feed. */
    val port: String,
    val type: NodeType,
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
) {
    val isEmpty: Boolean get() = helpers.isEmpty() && snaps.isEmpty()
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
        if (feeders.isEmpty()) {
            helperFor(input, types)?.let { helpers += HelperNode(input.name, it) }
        } else {
            val best = feeders.last().first
            for ((nodeId, port) in feeders) {
                snaps += SnapCandidate(input.name, nodeId, port, recommended = nodeId == best)
            }
        }
    }
    return AddPlan(type, helpers, snaps)
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
    return st.copy(editing = newId, showPalette = false, message = null)
}

