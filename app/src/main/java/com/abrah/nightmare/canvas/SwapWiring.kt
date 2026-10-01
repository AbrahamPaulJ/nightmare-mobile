package com.abrah.nightmare.canvas

import com.abrah.nightmare.IpAdapter
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.SwapInputs

/**
 * ⭐⭐⭐ **An SD 1.5 Swap node's ControlNet and IP-Adapter pictures are NODES on
 * the canvas, wired in** — the user's call, 2026-10-01: *"auto wire image to
 * control dot of sampler if control img is enabled inside node inspector. same
 * for ipadapter img, show it as img input wired to the sampler. if user wants to
 * use different control image, they can change it from canvas view or within the
 * node inspector."*
 *
 * Until then a picture picked in the ControlNet or IP-Adapter tile was a hidden
 * PARAM ([SdSampler.CONTROL_IMAGE] / [SdSampler.IP_IMAGE]) — invisible on the
 * canvas, and the photo that silently became the control picture was never
 * drawn as one. ⇒ Every inspector edit passes through [swapWired], so the tile,
 * the canvas and anything added later share ONE set of rules:
 *
 * | edit | effect |
 * |---|---|
 * | ControlNet none → a type | the sampler's `image` source is wired into `control` too (img2img); with none, a NEW image node is |
 * | a type → none | the `control` wire goes, and its image node with it unless something else reads it (the img2img photo stays) |
 * | IP-Adapter none → an adapter | always a NEW image node into `reference` (a reference is almost never the source photo) |
 * | an adapter → none | as ControlNet → none, for `reference` — the user's call, 2026-10-01: the executor runs EVERY node, so a left-over empty image node fails the run with "no image chosen" |
 * | a picture picked in a tile | written INTO the wired image node when nothing else reads it; when it is SHARED — the img2img photo, or one node wired into both `control` and `reference` — a new node is wired in instead, so changing one never changes the other (2026-10-01) |
 * | a tile's Remove | the wired node's picture is emptied, node and wire kept, so the next pick goes back INTO it; a shared node is only unwired from this port |
 * | any rewire | an image node left reading into nothing is removed — the executor runs every node |
 *
 * ⚠ The picked-picture params are emptied once a node holds the picture: one
 * place per fact. They are still READ by the sampler, so a flow saved before
 * this renders as it did.
 */
internal fun CanvasState.swapWired(nodeId: String, name: String, value: String, before: CanvasState): CanvasState {
    val node = workflow.graph.byId[nodeId] ?: return this
    if (node.type != SdSampler.SD15_SWAP.name && node.type != SdSampler.SD15_SWAP_INPAINT.name) return this
    val was = before.workflow.graph.byId[nodeId]?.params
    return when (name) {
        SdSampler.CONTROLNET -> {
            val off = value.ifBlank { SwapInputs.NONE } == SwapInputs.NONE
            val wasOff = was?.get(SdSampler.CONTROLNET).orEmpty().ifBlank { SwapInputs.NONE } == SwapInputs.NONE
            when {
                off && !wasOff -> unwire(nodeId, SdSampler.CONTROL, dropNode = true)
                !off && wasOff && node.inputs[SdSampler.CONTROL] == null -> {
                    val photo = node.inputs["image"]?.node
                    if (photo != null) {
                        copy(workflow = workflow.copy(graph = workflow.graph.connected(nodeId, SdSampler.CONTROL, photo)))
                    } else {
                        withPictureNode(nodeId, SdSampler.CONTROL, "control", node.params[SdSampler.CONTROL_IMAGE].orEmpty())
                            .clearingParam(nodeId, SdSampler.CONTROL_IMAGE)
                    }
                }
                else -> this
            }
        }
        SdSampler.IP_ADAPTER -> {
            val off = value == IpAdapter.NONE
            val wasOff = was?.get(SdSampler.IP_ADAPTER) == IpAdapter.NONE
            when {
                off && !wasOff -> unwire(nodeId, "reference", dropNode = true)
                !off && wasOff && node.inputs["reference"] == null ->
                    withPictureNode(nodeId, "reference", "reference", node.params[SdSampler.IP_IMAGE].orEmpty())
                        .clearingParam(nodeId, SdSampler.IP_IMAGE)
                else -> this
            }
        }
        SdSampler.CONTROL_IMAGE -> picked(nodeId, SdSampler.CONTROL, "control", SdSampler.CONTROL_IMAGE, value)
        SdSampler.IP_IMAGE -> picked(nodeId, "reference", "reference", SdSampler.IP_IMAGE, value)
        else -> this
    }
}

/**
 * A picture picked (or removed) in a tile — see the table. ⚠ Never written into
 * a node anything else reads: the img2img photo, or the other tile's picture.
 */
private fun CanvasState.picked(nodeId: String, port: String, base: String, param: String, uri: String): CanvasState {
    val node = workflow.graph.byId[nodeId] ?: return this
    val wiredId = node.inputs[port]?.node
    val wired = wiredId?.let { workflow.graph.byId[it] }
    val own = wired != null && wired.type == "core.image" && !readElsewhere(wiredId!!, nodeId, port)
    if (uri.isBlank()) {
        return when {
            wiredId == null -> this
            own -> clearedPicture(wiredId!!)
            else -> unwire(nodeId, port)
        }.clearingParam(nodeId, param)
    }
    val placed = if (own) setParam(wiredId!!, "uri", uri)
    else withPictureNode(nodeId, port, base, uri).droppingOrphan(wiredId)
    return placed.clearingParam(nodeId, param)
}

/** Does anything but [nodeId]'s own [port] read [id]? */
private fun CanvasState.readElsewhere(id: String, nodeId: String, port: String): Boolean =
    workflow.graph.nodes.any { n ->
        n.inputs.any { (p, src) -> src.node == id && !(n.id == nodeId && p == port) }
    }

/** An image node nothing reads any more is removed (the executor runs every node). */
private fun CanvasState.droppingOrphan(id: String?): CanvasState {
    val n = id?.let { workflow.graph.byId[it] } ?: return this
    if (n.type != "core.image") return this
    val read = workflow.graph.nodes.any { m -> m.inputs.values.any { it.node == id } }
    return if (read) this else removeNode(id).copy(editing = editing)
}

/**
 * An image node's picture emptied — the uri AND what is drawn from it, as
 * `HarnessViewModel.clearImage` does: previews are only ever added, so a
 * cleared node would otherwise keep showing the old picture.
 */
private fun CanvasState.clearedPicture(id: String): CanvasState {
    val g = workflow.graph
    fun downstream(k: String) = k == id || g.dependsOn(k, id)
    return setParam(id, "uri", "").copy(
        previews = previews.filterKeys { !downstream(it) },
        rendered = rendered.filterKeys { !downstream(it) },
        beforePreviews = beforePreviews.filterKeys { !downstream(it) },
        beforeHidden = beforeHidden.filterKeys { !downstream(it) },
    )
}

/**
 * The wire into [port] goes. Its image node goes too when nothing else reads it
 * — always on a switch-off ([dropNode]), only when EMPTY on a tile's Remove (a
 * picture someone put there is kept). ⚠ Never the img2img photo: the sampler's
 * `image` wire still reads it. ⚠⚠ An empty one is never left behind: the
 * executor runs every node, wired or not, and it fails "no image chosen".
 */
private fun CanvasState.unwire(nodeId: String, port: String, dropNode: Boolean = false): CanvasState {
    val src = workflow.graph.byId[nodeId]?.inputs?.get(port)?.node ?: return this
    val cut = copy(workflow = workflow.copy(graph = workflow.graph.disconnected(nodeId, port)))
    val srcNode = cut.workflow.graph.byId[src] ?: return cut
    if (srcNode.type != "core.image") return cut
    val empty = srcNode.params["uri"].isNullOrBlank()
    val read = cut.workflow.graph.nodes.any { n -> n.inputs.values.any { it.node == src } }
    return if (!read && (empty || dropNode)) cut.removeNode(src).copy(editing = editing) else cut
}

private fun CanvasState.clearingParam(nodeId: String, param: String): CanvasState =
    if (workflow.graph.byId[nodeId]?.params?.get(param).isNullOrBlank()) this
    else copy(workflow = workflow.copy(graph = workflow.graph.withParam(nodeId, param, "")))

/**
 * A new `core.image` node holding [uri], wired into [port] of [nodeId], placed
 * in the feeder column below whatever already feeds the sampler. ⚠ The open
 * inspector stays on the sampler: the person is mid-edit there.
 */
private fun CanvasState.withPictureNode(nodeId: String, port: String, base: String, uri: String): CanvasState {
    val g = workflow.graph
    val id = g.freeId(base)
    val sampler = g.byId[nodeId] ?: return this
    val at = workflow.positions[nodeId] ?: Pt(0f, 0f)
    val feeders = sampler.inputs.values.mapNotNull { workflow.positions[it.node] }
    val x = feeders.minOfOrNull { it.x } ?: (at.x - PICTURE_STEP_X)
    var y = (feeders.maxOfOrNull { it.y } ?: at.y) + PICTURE_STEP_Y
    // ⚠ Below anything already standing there, so two auto-added nodes never stack.
    while (workflow.positions.values.any { kotlin.math.abs(it.x - x) < 100f && kotlin.math.abs(it.y - y) < 150f }) {
        y += PICTURE_STEP_Y
    }
    val added = Node(id, "core.image", params = mapOf("uri" to uri))
    return copy(
        workflow = workflow.copy(
            graph = g.copy(nodes = g.nodes + added).connected(nodeId, port, id),
            positions = workflow.positions + (id to Pt(x, y)),
        ),
        // ⚠ A reused id must not inherit a deleted node's picture ([CanvasState.addNode]).
        previews = previews - id,
        rendered = rendered - id,
        beforePreviews = beforePreviews - id,
        beforeHidden = beforeHidden - id,
        videos = videos - id,
    )
}

/**
 * ⭐⭐ A picture wired into a Swap node's `reference` or `control` ON THE CANVAS
 * switches that tool on when it is off — IP-Adapter to Plus, ControlNet to canny
 * (reported 2026-10-01: *"when i connect reference image from graph view, why
 * tf ipadapter selection is still none?"*). A wire is the person saying "use
 * this"; leaving the tool at none made it a wire that does nothing. ⚠ A tool
 * already on keeps its choice.
 */
internal fun com.abrah.nightmare.Graph.switchingOnFor(nodeId: String, port: String): com.abrah.nightmare.Graph {
    val n = byId[nodeId] ?: return this
    if (n.type != SdSampler.SD15_SWAP.name && n.type != SdSampler.SD15_SWAP_INPAINT.name) return this
    return when (port) {
        "reference" -> if (n.params[SdSampler.IP_ADAPTER] == IpAdapter.NONE) {
            withParam(nodeId, SdSampler.IP_ADAPTER, IpAdapter.PLUS)
        } else this
        SdSampler.CONTROL -> if (n.params[SdSampler.CONTROLNET].orEmpty().ifBlank { SwapInputs.NONE } == SwapInputs.NONE) {
            withParam(nodeId, SdSampler.CONTROLNET, SwapInputs.CANNY)
        } else this
        else -> this
    }
}

private const val PICTURE_STEP_X = 420f
private const val PICTURE_STEP_Y = 380f
