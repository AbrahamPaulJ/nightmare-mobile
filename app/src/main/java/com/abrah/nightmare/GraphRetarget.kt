package com.abrah.nightmare

/**
 * ⭐⭐ [graph] pointed at [spec] — every context-key knob rewritten ([contextKeyRetarget]) and
 * every sampler of ANOTHER family retyped to [spec]'s own ([SdSampler.typeFor]). The ONE
 * implementation: the canvas's model switch (`HarnessViewModel.retargetCanvas`) and the API's
 * `model` override both call it.
 */
fun retargetedGraph(graph: Graph, types: Map<String, NodeType>, spec: ModelSpec, res: Res = spec.native): Graph {
    val changes = contextKeyRetarget(graph, types, spec, res)
    if (changes.isEmpty()) return graph
    return Graph(
        graph.nodes.map { n ->
            val p = changes[n.id] ?: return@map n
            val sampler = types[n.type] as? SdSampler
            val newType = if (sampler != null && sampler.family != spec.family) {
                SdSampler.typeFor(spec.family, sampler.inpaint)
            } else {
                n.type
            }
            n.copy(type = newType, params = n.params + p)
        },
    )
}
