package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.CropNode
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
    }

    // ⚠⚠ RENAMES FIRST, and everything after this line may assume current
    // names. `migrateSamplerPrompts` matches on the sampler's type, so running
    // it against a file still saying `sample` would either need both spellings
    // or silently skip the repair.
    val migrated = migrateSamplerPrompts(nodes.map(::migrateType), positions)

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
        Workflow(Graph(migrated.first), migrated.second, sizes), requires, view,
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
    val renamed = RENAMED[node.type]?.let { node.copy(type = it) } ?: node
    // ⚠⚠ …and `crop`'s mirrored padding became a BLURRED mirror, under a new
    // value. Left as `mirror` it would name a fill the node no longer produces,
    // and the chip in the inspector would offer a word for something else.
    return if (renamed.type == "image.crop" && renamed.params[CropNode.PAD] == "mirror") {
        renamed.copy(params = renamed.params + (CropNode.PAD to CropNode.PAD_BLUR))
    } else renamed
}

private val RENAMED = mapOf(
    "sample" to "sd.sample",
    "encode_text" to "sd.clip_encode",
    "vae_encode" to "sd.vae_encode",
    "vae_decode" to "sd.vae_decode",
    "load_image" to "image.load",
    "crop" to "image.crop",
    "output" to "image.output",
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
private fun migrateSamplerPrompts(
    nodes: List<Node>,
    positions: Map<String, Pt>,
): Pair<List<Node>, Map<String, Pt>> {
    if (nodes.none { it.type == "sd.sample" && ("prompt" in it.params || "negative" in it.params) }) {
        return nodes to positions
    }
    val taken = nodes.map { it.id }.toMutableSet()
    val out = mutableListOf<Node>()
    val pos = positions.toMutableMap()
    for (n in nodes) {
        val stale = n.type == "sd.sample" && ("prompt" in n.params || "negative" in n.params)
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
        out += Node(
            id, "sd.clip_encode",
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
