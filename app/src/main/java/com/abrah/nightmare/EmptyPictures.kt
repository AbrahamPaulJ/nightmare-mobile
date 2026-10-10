package com.abrah.nightmare

import com.abrah.nightmare.canvas.switchingOffFor

/** One empty picture node left out of a run, and the port of [consumer] it fed. */
data class SkippedPicture(val imageNode: String, val consumer: String, val port: String)

/**
 * ⭐⭐ Empty `core.image` nodes that feed ONLY optional pictures are left out of the
 * run with a warning, instead of failing it "no image chosen" (the user's call,
 * 2026-10-08): a sampler's `control` (ControlNet goes to none) and `reference`
 * (IP-Adapter goes to none — [switchingOffFor], the canvas's own rule for an
 * unwired port), and the `image` of a sampler that renders without one —
 * image-to-image then renders from text.
 *
 * ⚠ An inpaint's photo is not optional: there is nothing to repaint. An empty
 * picture that also feeds anything else still fails, by name.
 * ⚠ Pure, and the person's graph is not changed — the run gets the copy.
 */
fun skipEmptyPictures(graph: Graph): Pair<Graph, List<SkippedPicture>> {
    val empties = graph.nodes.filter { it.type == LoadImageNode.name && it.params["uri"].isNullOrBlank() }
    if (empties.isEmpty()) return graph to emptyList()
    var g = graph
    val skipped = mutableListOf<SkippedPicture>()
    for (empty in empties) {
        val reads = graph.nodes.flatMap { n ->
            n.inputs.filterValues { it.node == empty.id }.keys.map { n to it }
        }
        if (reads.isEmpty() || !reads.all { (n, port) -> optionalPicture(n.type, port) }) continue
        for ((n, port) in reads) {
            g = g.disconnected(n.id, port).switchingOffFor(n.id, port)
            skipped += SkippedPicture(empty.id, n.id, port)
        }
        g = g.copy(nodes = g.nodes.filterNot { it.id == empty.id })
    }
    return g to skipped
}

private fun optionalPicture(type: String, port: String): Boolean = when (port) {
    SdSampler.CONTROL, "reference" -> type in IMAGE_SAMPLER_TYPES
    "image" -> type in IMAGE_SAMPLER_TYPES && type !in INPAINT_TYPES
    else -> false
}
