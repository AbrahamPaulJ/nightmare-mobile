package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.CropNode
import com.abrah.nightmare.MaskCropNode
import com.abrah.nightmare.MaskNode
import com.abrah.nightmare.PasteNode
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reading and writing a workflow.
 *
 * ⚠⚠ A saved graph records **which plugins it needs, and at what version**
 * (`docs/ARCHITECTURE.md` §6). Without that, opening a workflow on a device
 * missing a pack fails with "unknown type com.example.pack:Thing" — which tells
 * the user the name of something they have never heard of, rather than "install
 * com.example.pack 0.1.0". The node type string alone cannot say that, because
 * it carries no version.
 *
 * ⚠ Positions live here too, and they are the one thing that must NOT reach the
 * executor: a workflow file is layout plus graph, and the graph half is what
 * runs.
 */

/** A plugin a saved workflow depends on. */
data class Requirement(val pluginId: String, val version: String)

/**
 * Where the canvas was, and how it was held, when the workflow was saved.
 *
 * ⭐⭐ Saved because opening a graph onto empty space is opening it onto
 * nothing. The nodes were wherever the user left them -- a long way from the
 * origin, after an afternoon of panning -- so a load that kept the CURRENT view
 * showed a blank grid and left them to hunt for their own workflow. Asked for
 * from the phone, 2026-09-09.
 *
 * ⚠ The locks travel with it for the same reason: they are how the user had the
 * canvas set up to work on this graph, which is exactly what reopening it should
 * restore.
 *
 * ⚠⚠ NOT part of [Workflow]. `updateCanvas` triggers the autosave and the
 * preview resolve on `workflow` changing identity, so a viewport inside it would
 * fire both on every pointer event of a pan.
 */
data class SavedView(
    val offset: Pt,
    val scale: Float,
    val zoomLocked: Boolean = false,
    val panLocked: Boolean = false,
)

/** What came out of a file, and what it needs to run. */
data class LoadedWorkflow(
    val workflow: Workflow,
    val requires: List<Requirement>,
    /** ⚠ Null for a file written before views were saved. The caller frames it. */
    val view: SavedView? = null,
)

class WorkflowFormatError(message: String) : Exception(message)

/** Bumped only when the shape changes incompatibly. */
const val WORKFLOW_FORMAT = 1

/**
 * ⚠ [types] is needed for the `requires` list: a node's type string names its
 * plugin but not the version, and the version is the whole point of recording
 * the dependency.
 */
fun Workflow.toJson(types: Map<String, NodeType>, view: SavedView? = null): String {
    val nodes = JSONArray()
    for (n in graph.nodes) {
        val pos = positions[n.id] ?: Pt(0f, 0f)
        nodes.put(
            JSONObject()
                .put("id", n.id)
                .put("type", n.type)
                .put("params", JSONObject(n.params.toMap()))
                // ⚠ `"node"` or `"node:port"` — a flat string map, so a workflow
                // written before outputs were addressable still reads back.
                .put("inputs", JSONObject(n.inputs.mapValues { it.value.toString() }))
                .put("x", pos.x.toDouble())
                .put("y", pos.y.toDouble())
                // ⚠ Only when the user actually resized it, so a workflow does
                // not pin a default that a later release may want to change.
                .apply {
                    sizes[n.id]?.let { put("w", it.toDouble()) }
                    // ⚠ Beside "w" and for the same reason: a node the user
                    // sized is sized until they say otherwise, and a reopened
                    // flow that forgot it would silently shrink their prompts.
                    proseLines[n.id]?.let { put("lines", it) }
                }
        )
    }

    // ⚠ Derived from the nodes actually present, deduped. Recording every
    // loaded plugin would make a workflow demand packs it never used.
    val requires = JSONArray()
    graph.nodes
        .mapNotNull { types[it.type] }
        .mapNotNull { type ->
            // A plugin node's version is "<pluginId>@<version>" (Plugin.kt).
            type.version.substringBefore('@').takeIf { it != type.version }
                ?.let { it to type.version.substringAfter('@') }
        }
        .distinct()
        .sortedBy { it.first }
        .forEach { (id, version) ->
            requires.put(JSONObject().put("id", id).put("version", version))
        }

    return JSONObject()
        .put("format", WORKFLOW_FORMAT)
        .put("nodes", nodes)
        .put("requires", requires)
        // ⚠⚠ An OPTIONAL field, and deliberately not a format bump. The format
        // number is refused when it does not match, so raising it would make
        // every workflow anyone has already saved fail to open -- to add a field
        // that an older file simply does not have.
        .apply {
            view?.let {
                put(
                    "view",
                    JSONObject()
                        .put("x", it.offset.x.toDouble())
                        .put("y", it.offset.y.toDouble())
                        .put("scale", it.scale.toDouble())
                        .put("zoomLock", it.zoomLocked)
                        .put("panLock", it.panLocked),
                )
            }
        }
        .toString(2)
}

fun workflowFromJson(json: String): LoadedWorkflow {
    val root = try {
        JSONObject(json)
    } catch (e: Exception) {
        throw WorkflowFormatError("not a workflow file: ${e.message}")
    }
    val format = root.optInt("format", -1)
    // ⚠ Refused by number rather than adapted to. A file from a newer app may
    // mean something different by the same field names, and guessing produces a
    // graph that looks right and runs wrong.
    if (format != WORKFLOW_FORMAT) {
        throw WorkflowFormatError(
            "workflow format $format; this app reads $WORKFLOW_FORMAT"
        )
    }

    val nodesJson = root.optJSONArray("nodes")
        ?: throw WorkflowFormatError("workflow has no \"nodes\"")
    val nodes = mutableListOf<Node>()
    val sizes = mutableMapOf<String, Float>()
    val proseLines = mutableMapOf<String, Int>()
    val positions = mutableMapOf<String, Pt>()
    for (i in 0 until nodesJson.length()) {
        val o = nodesJson.getJSONObject(i)
        val id = o.optString("id").ifEmpty { throw WorkflowFormatError("node $i has no id") }
        val type = o.optString("type").ifEmpty {
            throw WorkflowFormatError("node \"$id\" has no type")
        }
        // ⚠⚠ A node whose type this app does not know is KEPT, not dropped.
        // Dropping it would silently delete the user's work and leave a graph
        // that looks complete; keeping it means the executor refuses by name and
        // the missing plugin can be installed.
        nodes += Node(
            id = id,
            type = type,
            params = o.optJSONObject("params").toStringMap(),
            inputs = o.optJSONObject("inputs").toStringMap().mapValues { Source.parse(it.value) },
        )
        positions[id] = Pt(
            o.optDouble("x", 0.0).toFloat(),
            o.optDouble("y", 0.0).toFloat(),
        )
        if (o.has("w")) sizes[id] = o.getDouble("w").toFloat()
        // ⚠ Absent in every flow written before this existed, which is correct:
        // `proseLinesOf` falls back to the default.
        if (o.has("lines")) proseLines[id] = o.getInt("lines")
    }

    // ⚠⚠ RENAMES FIRST, and everything after this line may assume current
    // names. `migrateSamplerPrompts` matches on the sampler's type, so running
    // it against a file still saying `sample` would either need both spellings
    // or silently skip the repair.
    val migrated = collapseSamplers(
        reviveOutputNodes(
        collapseVideoChain(migrateSamplerPrompts(nodes.map(::migrateType), positions))
    ))

    val requiresJson = root.optJSONArray("requires")
    val requires = (0 until (requiresJson?.length() ?: 0)).map {
        val o = requiresJson!!.getJSONObject(it)
        Requirement(o.getString("id"), o.optString("version"))
    }

    // ⚠ A scale of 0 would make the canvas un-navigable and un-recoverable, and
    // it is what a hand-edited or truncated file produces. Clamped to the same
    // range a pinch can reach.
    val view = root.optJSONObject("view")?.let { v ->
        SavedView(
            offset = Pt(v.optDouble("x", 0.0).toFloat(), v.optDouble("y", 0.0).toFloat()),
            scale = v.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 3f),
            zoomLocked = v.optBoolean("zoomLock", false),
            panLocked = v.optBoolean("panLock", false),
        )
    }
    return LoadedWorkflow(
        Workflow(Graph(migrated.first), migrated.second, sizes, proseLines), requires, view,
    )
}

/**
 * ⭐⭐ Built-in node types gained a NAMESPACE, and old files still say the bare
 * name.
 *
 * `sample` became `sd.sample`, `encode_text` became `sd.clip_encode`,
 * `load_image` became `image.load`. The rename happened while the app had one
 * user and no published plugins, because the type string is the stable
 * identifier a contributor writes in a manifest -- renaming it after someone
 * else depends on it is a breaking change to their work, and a flat namespace
 * with `sd`, `llm` and `vision` nodes in it has nowhere to put a second family.
 * `docs/MODELS.md`.
 *
 * ⚠⚠ NOT a format bump, exactly as with [migrateSamplerPrompts]: the SHAPE of
 * the file did not change, only a string inside it, and bumping would make every
 * saved workflow fail to open rather than be repaired.
 *
 * ⚠ Plugin types (`com.example.pack:Thing`) are untouched -- they were always
 * namespaced, which is the pattern this brings the built-ins in line with.
 */
private fun migrateType(node: Node): Node {
    val renamed0 = RENAMED[node.type]?.let { node.copy(type = it) } ?: node
    // ⚠⚠ The inpaint types lost their `mask` PORT (2026-09-17, the user's call):
    // the painting is the mask. A saved wire into it is dropped rather than left
    // pointing at a port nothing declares.
    val renamed = if (renamed0.type in com.abrah.nightmare.SD_INPAINT_TYPES && "mask" in renamed0.inputs) {
        renamed0.copy(inputs = renamed0.inputs - "mask")
    } else renamed0
    // ⚠⚠ …and `crop`'s mirrored padding became a BLURRED mirror, under a new
    // value. Left as `mirror` it would name a fill the node no longer produces,
    // and the chip in the inspector would offer a word for something else.
    return if (renamed.type == "image.crop" && renamed.params[CropNode.PAD] == "mirror") {
        renamed.copy(params = renamed.params + (CropNode.PAD to CropNode.PAD_BLUR))
    } else renamed
}

private val RENAMED = mapOf(
    // ⚠ `sample` -> a FAMILY-specific type; [samplerTypeFor] does it, not this map.
    "vae_encode" to "sd.vae_encode",
    "vae_decode" to "sd.vae_decode",
    "crop" to "image.crop",
    // ⚠ The first fused video node, back under the name it now has.
    "nd.video_sample" to "nd.sample",
    // ⚠ The segmenter node's first name, for one day (docs/SEGMENTER.md).
    "mask.select_object" to "mask.segment_model",
    // ⭐⭐ The three generalised nodes of docs/ARCHITECTURE.md §5.7. They belong
    // to no family -- one prompt node serves SD and video, one output node takes
    // a picture or a clip -- so `core.` is the domain that says so.
    // ⚠ `sd.clip_encode` is renamed here and REWIRED in [collapseSamplers]: its
    // consumer's port changes from `cond` to `prompt`, which a rename cannot do.
    "encode_text" to "core.prompt",
    "sd.clip_encode" to "core.prompt",
    "load_image" to "core.image",
    "image.load" to "core.image",
    "output" to "core.output",
    "image.output" to "core.output",
    // ⚠ The clip's own output node, back — `core.output` takes either, so the
    // two that were deleted on 2026-09-13 land on the one that replaced them.
    "video.output" to "core.output",
)

/**
 * ⚠⚠ A graph saved before the sampler lost its prompt, made runnable again.
 *
 * `sample` carried `prompt`/`negative` until the text moved into `encode_text`
 * for good (docs/ARCHITECTURE.md §3). Every graph anyone has saved — including the
 * canvas autosave the app opens on — is from before that, and left alone each
 * one would now open onto a sampler that refuses to run with "nothing is wired
 * into cond" and a prompt that has vanished from the inspector with no trace of
 * where it went. ⇒ The text is MOVED into a new node rather than dropped, and
 * the sampler's copy is stripped: an undeclared param is still hashed into the
 * cache key ([applyDefaults]), so a leftover `prompt` would sit in the key of a
 * node whose inspector no longer shows it.
 *
 * ⚠ NOT a format bump. [WORKFLOW_FORMAT] is refused by number when it does not
 * match, so raising it would make every existing file fail to open instead of
 * being repaired — the file's SHAPE did not change, only which node holds the
 * text.
 *
 * ⚠ Keyed on the params being present, not on `cond` being unwired. A sampler
 * with no `prompt` param is already current, and synthesising an empty Text
 * Encode for it would add a node the user never had.
 */
/**
 * ⭐⭐⭐ **The fused video sampler, decomposed** — so every graph saved before
 * 2026-09-13 still opens and still runs.
 *
 * `nd.video_sample` was one node carrying a prompt, a seed and an `upscale`
 * checkbox, optionally fed an `image`, and usually wired to a `video.output`
 * that only held a `save` switch. It is now five nodes with the prompt on a
 * WIRE, exactly as `sd.sample` is (`docs/NEODRAGON.md` §8).
 *
 * ```
 *   nd.video_sample(prompt, seed, upscale) [← image] → video.output(save)
 * becomes
 *   nd.clip_encode(prompt) ─cond─┐
 *     └frame_cond→ nd.first_frame(seed) → nd.vae_encode ─latent─┐
 *                                                            └→ nd.sample(seed)
 *                                                                  ↓
 *                                                            nd.vae_decode(upscale)
 * ```
 *
 * ⚠⚠ **The wired `image` wins.** When one is connected the first-frame half
 * is not created at all — that is what the graph SAID, and inventing an SSD1B
 * branch beside it would make a saved image-to-video flow quietly generate its
 * own first frame and ignore the picture.
 *
 * ⚠⚠ **`video.output` disappears and its `save` is not preserved**, because
 * there is nothing left for it to switch: `nd.vae_decode` is terminal and draws
 * its own clip, and Save/Share on it write the MP4 (§7). A node whose only
 * widget had become a no-op is worse than no node.
 *
 * ⚠ NOT a format bump, for the same reason as [migrateSamplerPrompts]: the
 * file's SHAPE is unchanged, and bumping would make every saved workflow fail
 * to open rather than be repaired.
 *
 * ⚠⚠ A migrated graph gives a DIFFERENT clip for the same seed. The fused
 * node threaded one `Random` through every phase; these each start their own.
 * Nothing here was ever bit-reproducible, but it is the reason a user's saved
 * seed will not reproduce their old clip.
 */
/**
 * ⭐⭐ **The output node came BACK**, and an old one is repaired rather than
 * dropped.
 *
 * It was deleted on 2026-09-13 as a node whose only widget had become a no-op,
 * and un-deleted on 2026-09-15 (`docs/ARCHITECTURE.md` §5.7) because chaining
 * gave it back a job: in `sampler → sampler → output` it marks the deliverable.
 * ⇒ The pass that used to DELETE these now fixes them up.
 *
 * ⚠ Two things change under it: the port is `media` rather than `image`, since
 * it takes a clip too; and `save` defaults to true now, so a file that never
 * carried the param is pinned to **false** on the way in. An old graph that was
 * not writing files must not start writing them because a default moved.
 */
private fun reviveOutputNodes(
    input: Pair<List<Node>, Map<String, Pt>>,
): Pair<List<Node>, Map<String, Pt>> {
    val (nodes, positions) = input
    if (nodes.none { it.type == "core.output" }) return input
    return nodes.map { n ->
        if (n.type != "core.output") n
        else n.copy(
            params = if ("save" in n.params) n.params else n.params + ("save" to "false"),
            // ⚠ BOTH old ports: `image.output` took `image` and `video.output`
            // took `video`, and the one node that replaced them takes `media`.
            inputs = n.inputs.mapKeys { (port, _) ->
                if (port == "image" || port == "video") "media" else port
            },
        )
    } to positions
}

/**
 * ⭐⭐⭐ **The five video nodes collapse back into one** — 2026-09-15.
 *
 * `nd.clip_encode` → `nd.first_frame` → `nd.vae_encode` → `nd.sample` →
 * `nd.vae_decode` was the shape from 2026-09-13. It is now one `nd.sample`
 * taking a `core.prompt` and an optional photo, so every graph saved in between
 * has to be folded up.
 *
 * ⚠⚠ The function this replaces did the OPPOSITE — it expanded a saved fused
 * node into five — and leaving it would have rewritten every old graph into
 * nodes that no longer exist. A migration that emits a shape the app cannot run
 * is worse than none: the file opens and then fails at Run.
 *
 * ⚠ The PHOTO survives and the first frame does not: a graph whose encoder was
 * fed by a crop or a photo was image-to-video, and that picture is the one thing
 * the user chose. A `nd.first_frame` upstream means it was text-to-video, and
 * the new node makes its own.
 */
private fun collapseVideoChain(
    input: Pair<List<Node>, Map<String, Pt>>,
): Pair<List<Node>, Map<String, Pt>> {
    val (nodes, positions) = input
    val old = setOf(
        "nd.clip_encode", "nd.first_frame", "nd.vae_encode", "nd.vae_decode",
    )
    val split = nodes.filter { it.type in old }
    // ⭐⭐ The FIRST fused node carried its prompt as a PARAM. Renamed to
    // `nd.sample` by [RENAMED], it then had text nothing reads and an unwired
    // prompt port — so the text is lifted into a `core.prompt` beside it, the
    // same act [migrateSamplerPrompts] performs for the SD sampler.
    val strays = nodes.filter {
        it.type == "nd.sample" && "prompt" in it.params && "prompt" !in it.inputs
    }
    if (split.isEmpty() && strays.isEmpty()) return input
    if (split.isEmpty()) {
        val taken = nodes.map { it.id }.toMutableSet()
        val out = mutableListOf<Node>()
        val pos = positions.toMutableMap()
        for (n in nodes) {
            if (n !in strays) { out += n; continue }
            var id = "${'$'}{n.id}_text"
            var i = 2
            while (id in taken) id = "${'$'}{n.id}_text${'$'}{i++}"
            taken += id
            out += Node(id, "core.prompt", mapOf(
                "prompt" to n.params["prompt"].orEmpty(),
                "negative" to n.params["negative"].orEmpty(),
            ))
            out += n.copy(
                params = n.params - "prompt" - "negative",
                inputs = n.inputs + ("prompt" to Source(id)),
            )
            val at = positions[n.id] ?: Pt(0f, 0f)
            pos[id] = Pt(at.x + Sizes.NODE_WIDTH + 36f, at.y)
        }
        return out to pos
    }
    val byId = nodes.associateBy { it.id }

    // The prompt node the text used to live on becomes `core.prompt`.
    val text = nodes.firstOrNull { it.type == "nd.clip_encode" }
    val promptNode = text?.let {
        Node(it.id, "core.prompt", mapOf(
            "prompt" to it.params["prompt"].orEmpty(),
            "negative" to it.params["negative"].orEmpty(),
        ))
    }

    /** Walk up past the dead types to whatever real picture fed them. */
    fun photoFor(start: Source?): Source? {
        var cur = start
        var hops = 0
        while (cur != null && hops++ < 32) {
            val n = byId[cur.node] ?: return null
            when (n.type) {
                // ⚠ A first frame means there was no photo — the clip started
                // from one the app generated.
                "nd.first_frame" -> return null
                "nd.vae_encode" -> cur = n.inputs["image"]
                // ⚠ The crop node stays a node; it is the picture.
                else -> return Source(n.id)
            }
        }
        return null
    }

    val sampler = nodes.firstOrNull { it.type == "nd.sample" }
    val encode = nodes.firstOrNull { it.type == "nd.vae_encode" }
    val decode = nodes.firstOrNull { it.type == "nd.vae_decode" }
    val fused = Node(
        id = sampler?.id ?: decode?.id ?: "video",
        type = "nd.sample",
        params = (sampler?.params.orEmpty()) +
            (decode?.params?.filterKeys { it == "upscale" } ?: emptyMap()),
        inputs = buildMap {
            promptNode?.let { put("prompt", Source(it.id)) }
            photoFor(encode?.inputs?.get("image"))?.let { put("image", it) }
        },
    )

    val gone = split.map { it.id }.toSet() + setOfNotNull(sampler?.id) - fused.id
    val kept = nodes.filterNot { it.id in gone || it.id == fused.id || it.type == "nd.clip_encode" }
        .map { n ->
            // ⚠ Anything reading the old decode now reads the fused node.
            n.copy(inputs = n.inputs.mapValues { (_, src) ->
                if (src.node in gone || src.node == decode?.id) Source(fused.id) else src
            })
        }
    val out = listOfNotNull(promptNode) + kept + fused
    val pos = positions.filterKeys { id -> out.any { it.id == id } }
    return out to (pos + (fused.id to (positions[fused.id] ?: Pt(24f, 420f))))
}

private fun migrateSamplerPrompts(
    nodes: List<Node>,
    positions: Map<String, Pt>,
): Pair<List<Node>, Map<String, Pt>> {
    if (nodes.none { isOldSamplerType(it.type) && ("prompt" in it.params || "negative" in it.params) }) {
        return nodes to positions
    }
    val taken = nodes.map { it.id }.toMutableSet()
    val out = mutableListOf<Node>()
    val pos = positions.toMutableMap()
    for (n in nodes) {
        val stale = isOldSamplerType(n.type) && ("prompt" in n.params || "negative" in n.params)
        if (!stale) {
            out += n
            continue
        }
        val sampler = n.copy(params = n.params - "prompt" - "negative")
        // ⚠ Already wired: the text node exists and this sampler's own copy was
        // the ignored one. Strip it and change nothing else -- adding a second
        // conditioning would silently replace the one the user connected.
        if ("cond" in n.inputs) {
            out += sampler
            continue
        }
        var id = "${n.id}_text"
        var i = 2
        while (id in taken) id = "${n.id}_text$i".also { i++ }
        taken += id
        // ⚠⚠ `core.prompt`, not the node this migration was written against:
        // [collapseSamplers] runs straight after and would have to undo it.
        // A migration that emits a shape another migration rewrites is two
        // passes disagreeing about the same file.
        out += Node(
            id, "core.prompt",
            params = mapOf(
                "prompt" to n.params["prompt"].orEmpty(),
                "negative" to n.params["negative"].orEmpty(),
            ),
        )
        out += sampler.copy(inputs = sampler.inputs + ("cond" to Source(id)))
        // Beside the sampler rather than above it: the nodes around it are
        // where the user put them, and pushing the column down would move
        // someone's whole layout to make room.
        val at = positions[n.id] ?: Pt(0f, 0f)
        pos[id] = Pt(at.x + Sizes.NODE_WIDTH + 36f, at.y)
    }
    return out to pos
}

/**
 * What a loaded workflow needs and this app does not have, in words a user can
 * act on.
 *
 * ⚠ A version MISMATCH is reported, not tolerated. A plugin's version is part of
 * its nodes' cache keys and may change what a node produces, so silently running
 * against a different one is how a workflow quietly stops meaning what it did.
 */
fun missingRequirements(
    requires: List<Requirement>,
    types: Map<String, NodeType>,
): List<String> {
    val present = types.values
        .mapNotNull { t ->
            t.version.substringBefore('@').takeIf { it != t.version }
                ?.let { it to t.version.substringAfter('@') }
        }
        .toMap()
    return requires.mapNotNull { req ->
        val have = present[req.pluginId]
        when {
            have == null -> "${req.pluginId} ${req.version} is not installed"
            have != req.version ->
                "${req.pluginId} is ${have}, the workflow wants ${req.version}"
            else -> null
        }
    }
}

private fun JSONObject?.toStringMap(): Map<String, String> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (k in keys()) out[k] = getString(k)
    return out
}

/**
 * Where workflows live.
 *
 * ⚠ `filesDir`, not external storage: a workflow is app data, and external
 * storage is where things the user hands us go (models, plugin packs). Mixing
 * them would make "clear the plugin directory" delete someone's graphs.
 */
class WorkflowStore(private val dir: File) {

    /** ⚠ The canvas autosave, not a user save. Same string the view model uses. */
    private val CURRENT = "current"

    fun file(name: String) = File(dir, "$name.json")

    fun save(
        name: String,
        workflow: Workflow,
        types: Map<String, NodeType>,
        view: SavedView? = null,
    ) {
        dir.mkdirs()
        // ⚠ Written to a temp file and renamed. A process death midway through
        // a direct write leaves a truncated file that fails to parse, and the
        // user's graph is gone -- for a saving mechanism that is the one
        // failure that must not happen.
        // ⚠⚠ Unique per write. A fixed `$name.json.tmp` is shared state: two
        // saves of the same workflow at once deleted and renamed it out from
        // under each other, which is how a crop drag turned into a crash. The
        // caller conflates saves now, but a temp file named after its content's
        // destination is a trap regardless of who calls it.
        val tmp = File(dir, "$name.json.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        tmp.writeText(workflow.toJson(types, view))
        val dest = file(name)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * The user's saved workflows, newest first.
     *
     * ⚠ `CURRENT` is excluded: it is the autosave of whatever is on the canvas
     * right now, not something the user chose to keep, and listing it would
     * offer people their own scratch state as a saved graph.
     */
    fun saved(): List<SavedWorkflow> =
        (dir.listFiles().orEmpty())
            .filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") to it }
            .filter { (name, _) -> name != CURRENT }
            .sortedByDescending { (_, f) -> f.lastModified() }
            .map { (name, f) -> SavedWorkflow(name, f.lastModified()) }

    /** ⚠ Returns false rather than throwing: a missing file is already the goal. */
    fun delete(name: String): Boolean = file(name).delete()

    /**
     * Rename a saved workflow. Returns why not, or null on success.
     *
     * ⚠⚠ It REFUSES to overwrite an existing name rather than silently
     * replacing it. A rename that lands on a name already in use destroys the
     * other graph, and the two are indistinguishable in a list afterwards.
     *
     * ⚠ [validName] first, for the same reason `save` needs it: the new name
     * is a FILE, and one carrying `/` or `..` writes outside this directory.
     */
    fun rename(from: String, to: String): String? {
        val target = to.trim()
        validName(target)?.let { return it }
        if (target == from) return null
        val src = file(from)
        if (!src.isFile) return "\"$from\" is gone"
        if (file(target).exists()) return "\"$target\" already exists"
        return if (src.renameTo(file(target))) null else "could not rename \"$from\""
    }

    /**
     * ⚠ Rejects anything that is not a plain name. A saved workflow is a
     * FILE, and a name carrying `/` or `..` writes outside the directory --
     * the user types this string.
     */
    fun validName(name: String): String? {
        val n = name.trim()
        return when {
            n.isEmpty() -> "give it a name"
            n == CURRENT -> "\"$CURRENT\" is reserved for the canvas autosave"
            !n.matches(Regex("[A-Za-z0-9 _-]{1,40}")) ->
                "letters, digits, spaces, - and _ only"
            else -> null
        }
    }

    /** Null when there is nothing saved; throws [WorkflowFormatError] on a bad file. */
    fun load(name: String): LoadedWorkflow? {
        val f = file(name)
        if (!f.isFile) return null
        return workflowFromJson(f.readText())
    }
}

/** One of the user's saved graphs, for the Workflows list. */
data class SavedWorkflow(val name: String, val savedAt: Long)

/**
 * ⭐⭐⭐ **Sixteen node types became seven, and every saved flow still opens.**
 *
 * `docs/ARCHITECTURE.md` §5.7. The exact inverse of [migrateVideoSampler],
 * which expanded one fused node into five — this folds ten back into one:
 *
 * ```
 *   clip_encode ─cond─┐
 *   photo ─ crop ─ mask ─ cut ─ vae_encode ─ sample ─ blend ─ vae_decode ─ paste
 * becomes
 *   prompt ─┐
 *   photo ──┴─ sample            (the mask, the cut, the blend and the paste
 *                                 are now params and toggles ON the sampler)
 * ```
 *
 * ⚠⚠ **`sd.sample` means two different things**, and that is the one hazard
 * here: an old file's `sd.sample` is the node that took a COND and returned a
 * LATENT; the live one takes a PROMPT and returns an IMAGE. They are told apart
 * by SHAPE, not by name — an old sampler has a `cond` or a `latent` input and
 * the new one has neither. ⇒ [isLegacySampler]. A version number could not have
 * answered this: the file's format did not change.
 *
 * ⚠ NOT a format bump, for the reason [migrateSamplerPrompts] gives: the file's
 * SHAPE is unchanged, and bumping would make every saved workflow fail to open
 * rather than be repaired.
 *
 * ⚠⚠ Best-effort, the user's call 2026-09-15. The shipped recipe shapes come
 * back exactly; anything else keeps every node that still exists and is rewired
 * THROUGH the ones that do not ([PASSTHROUGH]) rather than being refused.
 */
private fun collapseSamplers(
    input: Pair<List<Node>, Map<String, Pt>>,
): Pair<List<Node>, Map<String, Pt>> {
    val (nodes, positions) = input
    if (nodes.none { isLegacySampler(it) || it.type in PASSTHROUGH }) return input
    val byId = nodes.associateBy { it.id }

    /** Walk up a chain of the types that are about to disappear. */
    fun upstream(from: Source?, vararg through: String): Node? {
        var n = from?.let { byId[it.node] } ?: return null
        while (n.type in through) {
            val next = n.inputs[PASSTHROUGH[n.type]] ?: return n
            n = byId[next.node] ?: return n
        }
        return n
    }

    val folded = nodes.map { node ->
        if (!isLegacySampler(node)) return@map node
        val params = node.params.toMutableMap()
        val inputs = mutableMapOf<String, Source>()

        // The prompt: an `sd.clip_encode` becomes `core.prompt` in place, so the
        // wire only changes which PORT it lands on.
        node.inputs["cond"]?.let { inputs["prompt"] = Source(it.node) }

        // The picture: whatever fed the encoder, seen through the mask nodes.
        val encoder = node.inputs["latent"]?.let { byId[it.node] }
        val cut = encoder?.inputs?.get("image")?.let { byId[it.node] }
        val photo = upstream(
            encoder?.inputs?.get("image"),
            "sd.vae_encode", "image.mask_crop", "image.mask",
        )
        if (photo != null) inputs["image"] = Source(photo.id)

        // ⭐ The mask, the cut and the paste become params on this node. Each is
        // read off the node that used to hold it, so a user's painting, their
        // "only masked" and their "stitch" all survive.
        if (cut?.type == "image.mask_crop") {
            cut.params[MaskCropNode.ONLY_MASKED]?.let { params[MaskCropNode.ONLY_MASKED] = it }
            cut.inputs["mask"]?.let { byId[it.node] }?.takeIf { it.type == "image.mask" }?.let { m ->
                m.params[MaskNode.OPS]?.let { params[MaskNode.OPS] = it }
                m.params["grow"]?.let { params["grow"] = it }
                m.params["feather"]?.let { params["feather"] = it }
            }
        }
        nodes.firstOrNull { it.type == "image.paste" }
            ?.params?.get(PasteNode.STITCH)?.let { params[PasteNode.STITCH] = it }
        // ⚠ The encoder's seed, under the name the fused node gives it. Dropping
        // it would re-encode the source to a different latent and change a
        // picture the user had reproduced from a seed.
        encoder?.params?.get("seed")?.let { params["encode_seed"] = it }

        // ⚠ The type changes here too: one `sd.sample` becomes one of four,
        // and whether it is an inpaint is knowable only from what was folded in.
        node.copy(
            type = samplerTypeFor(node, inpaint = MaskNode.OPS in params),
            params = params,
            inputs = inputs,
        )
    }

    // ⚠⚠ Now drop what is gone and rewire THROUGH it — the same act
    // the pass above replaced performs, generalised: each dead type declares
    // input a consumer should be re-pointed at.
    val alive = folded.filterNot { it.type in PASSTHROUGH }
    val aliveIds = alive.map { it.id }.toSet()
    val liveById = folded.associateBy { it.id }

    fun resolve(s: Source): Source? {
        var cur: Source? = s
        var hops = 0
        while (cur != null && cur.node !in aliveIds) {
            // ⚠ Bounded: a hand-edited file can name a cycle, and this walk runs
            // while OPENING a workflow — a graph that will not run must still open.
            if (hops++ > 64) return null
            val n = liveById[cur.node] ?: return null
            cur = n.inputs[PASSTHROUGH[n.type]]
        }
        return cur?.let { Source(it.node, it.port?.takeIf { p -> p != "image" && p != "mask" }) }
    }

    val rewired = alive.map { n ->
        n.copy(inputs = n.inputs.mapNotNull { (port, src) -> resolve(src)?.let { port to it } }.toMap())
    }
    return rewired to positions.filterKeys { it in aliveIds }
}

/**
 * ⚠⚠ An old `sd.sample`, told apart from the live one by its PORTS.
 *
 * The type string is the same and the file records nothing else that could
 * separate them. The old node's inputs were `cond` and `latent`; the new one's
 * are `prompt`, `image` and `mask`, so a single `cond` or `latent` wire is
 * proof. ⚠ An old sampler with NOTHING wired is indistinguishable and is left
 * alone — it could not render either way, and inventing a prompt for it would
 * add a node the user never had.
 */
private fun isLegacySampler(node: Node): Boolean =
    isOldSamplerType(node.type) && ("cond" in node.inputs || "latent" in node.inputs)

/**
 * ⚠⚠ Both spellings a file may carry for the sampler that existed before the
 * fork: `sample` (pre-namespace) and `sd.sample` (post-namespace, pre-fork).
 *
 * ⚠ `sample` is NOT in [RENAMED] any more, because a rename maps one name to
 * one name and this one maps to FOUR — the family decides which
 * ([samplerTypeFor]). Leaving it in the map would have renamed it to a type
 * that no longer exists.
 */
private fun isOldSamplerType(type: String): Boolean =
    type == "sd.sample" || type == "sample"

/**
 * ⭐⭐ Which of the four types an old `sd.sample` becomes.
 *
 * ⚠⚠ The FAMILY comes from the checkpoint the node already names, never from
 * whatever is selected today: a saved SDXL flow opened while an SD 1.5 model is
 * picked must still be an SDXL flow. ⚠ An unknown id (a deleted custom model)
 * falls back to SD 1.5 rather than refusing the file — a graph that will not
 * run must still open.
 *
 * ⚠ The CAPABILITY comes from the graph's shape: a mask node or painted `ops`
 * anywhere upstream means it was an inpaint.
 */
private fun samplerTypeFor(node: Node, inpaint: Boolean): String {
    val fam = com.abrah.nightmare.ModelCatalog.byId(node.params["model"].orEmpty())?.family
        ?: com.abrah.nightmare.Family.SD15
    return com.abrah.nightmare.SdSampler.typeFor(fam, inpaint)
}

/**
 * The one input a consumer should be re-pointed at when this type disappears —
 * "the picture that went in comes out".
 *
 * ⚠ `sd.latent_blend` passes through `repaint`, not `base`: the blend's job was
 * to let the sampled latent show through the mask, and the fused node does that
 * internally, so the thing downstream wanted was always the render.
 */
private val PASSTHROUGH = mapOf(
    "sd.vae_encode" to "image",
    "sd.vae_decode" to "latent",
    "sd.latent_blend" to "repaint",
    "image.mask_crop" to "image",
    "image.mask" to "image",
    "image.paste" to "patch",
)
