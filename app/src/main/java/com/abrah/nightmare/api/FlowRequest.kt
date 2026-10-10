package com.abrah.nightmare.api

import com.abrah.nightmare.Graph
import com.abrah.nightmare.IMAGE_SAMPLER_TYPES
import com.abrah.nightmare.LoadImageNode
import com.abrah.nightmare.ModelSpec
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.PromptNode
import com.abrah.nightmare.Widget
import com.abrah.nightmare.modelRecipeRetarget
import com.abrah.nightmare.retargetedGraph
import org.json.JSONObject

/** A refusal with its HTTP status — said to the caller as `{"error": message}`. */
class ApiError(val code: Int, message: String) : Exception(message)

/**
 * ⭐⭐ What `POST /run` asks for (`docs/AGENT-API.md` §3). Exactly one of [flow] (a recipe id,
 * `saved:<name>`, or `current` — the canvas autosave) and [graph] (a workflow JSON as the app
 * saves it).
 *
 * The overrides, applied in this order by [applyOverrides]:
 * 1. [model] — every sampler pointed at it, retyped across families, its steps / cfg /
 *    scheduler written (what picking it in Models does);
 * 2. [prompt] / [negative] — every prompt node;
 * 3. [knobs] — `seed`, `steps`, `cfg`, `denoise`, `scheduler`, `width`, `height`, `aspect`,
 *    `loras`: every SAMPLER that has the widget;
 * 4. [params] — per node id, any widget of that node's type;
 * 5. [images] — per `core.image` node id, a base64 picture.
 *
 * ⚠⚠ Every name is checked against the node schema before anything runs: an unknown node,
 * a widget the node does not have, a value off a chip list or out of range is a 400 that
 * names it — never a render that quietly ignored half the request (an agent cannot see that).
 */
data class RunRequest(
    val flow: String? = null,
    val graph: String? = null,
    val model: String? = null,
    val prompt: String? = null,
    val negative: String? = null,
    val knobs: Map<String, String> = emptyMap(),
    val params: Map<String, Map<String, String>> = emptyMap(),
    val images: Map<String, String> = emptyMap(),
    /** Server-sent events while it runs; otherwise one JSON answer at the end. */
    val stream: Boolean = false,
) {
    companion object {
        /** ⭐ The knobs [applyOverrides] sends to every sampler that has them. */
        val KNOBS = listOf("seed", "steps", "cfg", "denoise", "scheduler", "width", "height", "aspect", "loras")

        fun parse(text: String): RunRequest {
            val o = try {
                JSONObject(text.ifBlank { "{}" })
            } catch (e: Exception) {
                throw ApiError(400, "the body is not JSON: ${e.message}")
            }
            fun str(k: String): String? = if (o.has(k) && !o.isNull(k)) o.get(k).toString() else null
            val flow = str("flow")
            val graph = o.optJSONObject("graph")?.toString()
            if ((flow == null) == (graph == null)) throw ApiError(400, "give exactly one of \"flow\" and \"graph\"")
            val unknown = o.keys().asSequence().toSet() -
                (KNOBS + listOf("flow", "graph", "model", "prompt", "negative", "params", "images", "stream"))
            if (unknown.isNotEmpty()) throw ApiError(400, "unknown field(s): ${unknown.sorted().joinToString()}")
            fun obj(k: String): Map<String, String> {
                val v = o.optJSONObject(k) ?: return emptyMap()
                return v.keys().asSequence().associateWith { v.get(it).toString() }
            }
            val params = o.optJSONObject("params")?.let { p ->
                p.keys().asSequence().associateWith { id ->
                    val inner = p.optJSONObject(id) ?: throw ApiError(400, "params.$id must be an object")
                    inner.keys().asSequence().associateWith { inner.get(it).toString() }
                }
            }.orEmpty()
            return RunRequest(
                flow = flow,
                graph = graph,
                model = str("model"),
                prompt = str("prompt"),
                negative = str("negative"),
                knobs = KNOBS.mapNotNull { k -> str(k)?.let { k to it } }.toMap(),
                params = params,
                images = obj("images"),
                stream = o.optBoolean("stream", false),
            )
        }
    }
}

/**
 * ⭐⭐ [graph] with [req]'s overrides applied — see [RunRequest]. [spec] is [RunRequest.model]
 * resolved (null = unchanged). [picture] stores one decoded base64 picture for a `core.image`
 * node and returns the path its `uri` becomes. Pure apart from [picture]; throws [ApiError].
 */
fun applyOverrides(
    graph: Graph,
    types: Map<String, NodeType>,
    req: RunRequest,
    spec: ModelSpec?,
    picture: (nodeId: String, base64: String) -> String,
): Graph {
    var g = graph
    if (spec != null) {
        g = retargetedGraph(g, types, spec)
        g = modelRecipeRetarget(g, types, spec).entries.fold(g) { acc, (id, p) -> acc.withParams(id, p) }
    }
    val prompts = g.nodes.filter { it.type == PromptNode.name }
    if ((req.prompt != null || req.negative != null) && prompts.isEmpty()) {
        throw ApiError(400, "this flow has no prompt node")
    }
    for (n in prompts) {
        req.prompt?.let { g = g.withParam(n.id, "prompt", it) }
        req.negative?.let { g = g.withParam(n.id, "negative", it) }
    }
    for ((k, v) in req.knobs) {
        val takers = g.nodes.filter { n ->
            n.type in IMAGE_SAMPLER_TYPES && types[n.type]?.widgets?.any { it.name == k } == true
        }
        if (takers.isEmpty()) throw ApiError(400, "no sampler in this flow takes \"$k\"")
        for (n in takers) g = g.withParam(n.id, k, checked(types.getValue(n.type), n.id, k, v))
    }
    for ((id, p) in req.params) {
        val n = g.byId[id] ?: throw ApiError(400, "params: no node \"$id\" in this flow")
        val t = types[n.type] ?: throw ApiError(400, "params: node \"$id\" has an unknown type ${n.type}")
        g = g.withParams(id, p.mapValues { (k, v) -> checked(t, id, k, v) })
    }
    for ((id, b64) in req.images) {
        val n = g.byId[id] ?: throw ApiError(400, "images: no node \"$id\" in this flow")
        if (n.type != LoadImageNode.name) throw ApiError(400, "images: \"$id\" is a ${n.type}, not an image node")
        g = g.withParam(id, "uri", picture(id, b64))
    }
    return g
}

/** [v] for widget [k] of [t], or a 400 naming what is wrong. */
private fun checked(t: NodeType, id: String, k: String, v: String): String {
    val w: Widget = t.widgets.firstOrNull { it.name == k }
        ?: throw ApiError(400, "node \"$id\" (${t.name}) has no \"$k\" — it has ${t.widgets.joinToString { it.name }}")
    w.options?.let { opts ->
        // ⚠ `aspect` and `loras` carry free text the chips only suggest; the rest are closed lists.
        if (k != "aspect" && k != "loras" && opts.isNotEmpty() && v !in opts) {
            throw ApiError(400, "\"$k\" on \"$id\" must be one of ${opts.joinToString()}")
        }
    }
    if (w.type == "int" || w.type == "float") {
        val d = v.toDoubleOrNull() ?: throw ApiError(400, "\"$k\" on \"$id\" must be a number")
        if (w.type == "int" && d != Math.floor(d)) throw ApiError(400, "\"$k\" on \"$id\" must be a whole number")
        if ((w.min != null && d < w.min!!) || (w.max != null && d > w.max!!)) {
            throw ApiError(400, "\"$k\" on \"$id\" must be within ${w.min ?: "…"}..${w.max ?: "…"}")
        }
        return if (w.type == "int") d.toLong().toString() else v
    }
    return v
}
