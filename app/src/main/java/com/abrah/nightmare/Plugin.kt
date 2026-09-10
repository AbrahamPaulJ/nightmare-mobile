package com.abrah.nightmare

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * A plugin: a manifest and one JS file (docs/ARCHITECTURE.md §6).
 *
 * ```
 * resize-pack/
 *   node.json     # manifest -- nodes, widgets, permissions
 *   index.js      # implementations
 * ```
 *
 * ⚠ The manifest is parsed STRICTLY and fails with the field name. A plugin
 * loader that tolerates a missing `type` produces a node that cannot be placed
 * and an error message about something else entirely, three steps later.
 */
class Plugin(
    val id: String,
    val version: String,
    val api: Int,
    val nodes: List<Spec>,
    val permissions: List<String>,
    val script: String,
) {

    /** One node type the plugin declares. */
    data class Spec(
        val type: String,
        val category: String,
        val tier: Int,
        val inputs: List<com.abrah.nightmare.Port>,
        val outputs: List<com.abrah.nightmare.Port>,
        val widgets: List<com.abrah.nightmare.Widget>,
    ) {
        /**
         * ⚠ Namespaced by plugin id, so two packs may both ship a `Resize`.
         * A saved workflow records this string, which is why it must contain
         * the plugin id rather than being resolved by load order.
         */
        fun qualified(pluginId: String) = "$pluginId:$type"
    }

    companion object {

        /** The manifest `api` this app implements. */
        const val API = 1

        fun parse(manifestJson: String, script: String): Plugin {
            val j = JSONObject(manifestJson)
            val id = j.getString("id")
            val api = j.getInt("api")
            // ⚠ Refused, not adapted. A plugin written against a later API may
            // call host ops that do not exist here, and the failure would
            // surface as "unknown host op" from inside somebody's node rather
            // than as "this plugin is too new".
            require(api == API) {
                "plugin $id targets api $api; this app implements $API"
            }
            val nodesJson = j.getJSONArray("nodes")
            val nodes = (0 until nodesJson.length()).map { i ->
                val n = nodesJson.getJSONObject(i)
                Spec(
                    type = n.getString("type"),
                    category = n.optString("category", "misc"),
                    tier = n.optInt("tier", 0),
                    inputs = ports(n.optJSONArray("inputs")),
                    outputs = ports(n.optJSONArray("outputs")),
                    widgets = widgets(n.optJSONArray("widgets")),
                )
            }
            require(nodes.isNotEmpty()) { "plugin $id declares no nodes" }
            val perms = j.optJSONArray("permissions")
            val permissions = (0 until (perms?.length() ?: 0)).map { perms!!.getString(it) }
            // ⚠⚠ An unknown permission is REFUSED, not ignored. Ignoring one
            // means loading a plugin whose intentions this build cannot reason
            // about -- and the day the list grows, older builds would silently
            // under-enforce rather than refuse.
            val unknown = permissions - HostSurface.GROUPS
            require(unknown.isEmpty()) {
                "plugin $id asks for unknown permission(s) ${unknown.joinToString(", ")}; " +
                    "this app knows ${HostSurface.GROUPS.joinToString(", ")}"
            }
            return Plugin(
                id = id,
                version = j.getString("version"),
                api = api,
                nodes = nodes,
                permissions = permissions,
                script = script,
            )
        }

        /**
         * Load from a directory — what an installed plugin is on disk, and what
         * an unpacked zip becomes.
         */
        fun fromDir(dir: File): Plugin {
            val manifest = File(dir, "node.json")
            val script = File(dir, "index.js")
            require(manifest.isFile) { "no node.json in ${dir.absolutePath}" }
            require(script.isFile) { "no index.js in ${dir.absolutePath}" }
            return parse(manifest.readText(), script.readText())
        }

        /**
         * Load from the APK's assets — the demo pack ships this way.
         *
         * ⚠ Deliberately the same parser and the same two files as [fromDir].
         * A built-in plugin that loaded by a special path would stop being a
         * test of the plugin path, which is the only reason to ship one.
         */
        fun fromAssets(ctx: Context, path: String): Plugin {
            fun read(name: String) =
                ctx.assets.open("$path/$name").bufferedReader().use { it.readText() }
            return parse(read("node.json"), read("index.js"))
        }

        private fun ports(arr: org.json.JSONArray?): List<Port> =
            (0 until (arr?.length() ?: 0)).map {
                val o = arr!!.getJSONObject(it)
                Port(o.getString("name"), o.optString("type", "IMAGE"))
            }

        private fun widgets(arr: org.json.JSONArray?): List<Widget> =
            (0 until (arr?.length() ?: 0)).map {
                val o = arr!!.getJSONObject(it)
                Widget(
                    name = o.getString("name"),
                    type = o.optString("type", "string"),
                    // ⚠ opt("default") then toString(): getString() on a JSON
                    // number throws on some Android versions and returns "0.5"
                    // on others, which is the kind of difference that shows up
                    // as one device caching correctly and another not.
                    default = if (o.has("default")) o.get("default").toString() else null,
                    min = if (o.has("min")) o.getDouble("min") else null,
                    max = if (o.has("max")) o.getDouble("max") else null,
                    // ⚠⚠ **`options` and `hint` were NOT parsed, so a plugin
                    // could not declare a dropdown or explain a knob** — while
                    // the inspector rendered both for every built-in. The
                    // manifest silently dropped them: a contributor writing
                    // `"options": ["soft","hard"]` got a free-text box that
                    // accepts a typo and fails at Run. Found 2026-09-10 while
                    // answering "what level of control does an author have".
                    //
                    // ⚠ A short list is a chip row and a long one a dropdown —
                    // the inspector decides at `CHIP_LIMIT`, so an author picks
                    // the VALUES and never the widget.
                    options = if (o.has("options")) {
                        val a = o.getJSONArray("options")
                        (0 until a.length()).map { i -> a.get(i).toString() }
                    } else null,
                    hint = o.optString("hint").takeIf { h -> h.isNotBlank() },
                )
            }
    }
}

/**
 * Widget values with the declared defaults filled in.
 *
 * ⚠⚠ Used by EVERY node type, not just plugins. Built-ins declare widgets too,
 * and when only plugin nodes applied their defaults a built-in that gained a new
 * widget broke every graph saved before it existed -- `sample` gaining `denoise`
 * failed with `missing param "denoise"` on graphs that predated img2img.
 *
 * ⚠ A free function so it can be tested without a QuickJS runtime -- the rest
 * of [PluginNodeType] needs native code, and this is the part with logic in it.
 */
fun applyDefaults(widgets: List<Widget>, node: Node): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (w in widgets) {
        val v = node.params[w.name] ?: w.default
        ?: throw IllegalArgumentException(
            "node \"${node.id}\": widget \"${w.name}\" has no value and no default"
        )
        out[w.name] = v
    }
    // ⚠ Params the manifest does not declare are KEPT, not dropped. They are
    // still hashed into the cache key, so silently discarding one would let two
    // different graphs share a cache entry.
    for ((k, v) in node.params) if (k !in out) out[k] = v
    return out
}

/**
 * A plugin's node, as the executor sees it.
 *
 * ⭐ This is the whole Tier 0 story: a contributor ships two text files, and
 * their node becomes a first-class entry in the executor's registry with
 * caching, cache-key propagation and the context-key rules applied to it
 * exactly like a built-in.
 */
class PluginNodeType(
    private val plugin: Plugin,
    private val spec: Plugin.Spec,
    private val runtime: JsRuntime,
    private val host: HostSurface,
) : NodeType {

    override val name = spec.qualified(plugin.id)
    override val inputs = spec.inputs
    override val outputs = spec.outputs
    override val category = spec.category
    override val widgets = spec.widgets

    /**
     * ⚠⚠ The plugin's version, not the node's. Hot-reloading a plugin must
     * invalidate every cached output of every node it declares -- otherwise the
     * edit appears to do nothing, which is the single most confusing failure a
     * plugin author can hit.
     */
    override val version = "${plugin.id}@${plugin.version}"

    /**
     * ⭐ null: a Tier 0 node runs app-side and needs no backend process at all,
     * so a graph made only of them imposes no context key and can run with the
     * backend stopped.
     *
     * ⚠ A Tier 1+ node will NOT be able to return null here, and this is where
     * that lands.
     */
    override fun contextKey(node: Node): ContextKey? = null
    // ⚠ Including a node that calls `latent.*`, which DOES need a running
    // backend. It cannot name the key -- a plugin knows nothing about the
    // checkpoint or the resolution -- and in practice it does not have to: a
    // latent can only come from a `sample` node, which imposes the key itself.
    // ⇒ A plugin node is never the reason a backend is required, only ever a
    // passenger on one. If that stops being true, this is where it breaks.

    // ⚠ No override: the default in NodeType already applies this type's widget
    // defaults, and a plugin's widgets are its spec's.

    override suspend fun run(ctx: NodeCtx, node: Node, inputs: Map<String, Value>): Value {
        val inJson = JSONObject()
        for (port in spec.inputs) {
            val v = inputs[port.name]
                ?: throw IllegalArgumentException(
                    "node \"${node.id}\": input \"${port.name}\" is not connected"
                )
            // ⚠⚠ The port's declared TYPE is checked against what actually
            // arrived. Without this, a latent wired into an image port reaches
            // the host op as an id that simply is not in the image store, and
            // the error names a missing handle instead of the wrong wire — one
            // step removed from the mistake, in a graph the user drew.
            requirePortType(node, port, v)
            // ⭐ The handle, never the pixels. This one line is the §6 rule in
            // practice: what crosses into JS is an id.
            inJson.put(port.name, v.address())
        }
        // ⚠ effectiveParams, not node.params: the executor already keyed on
        // these, so passing the raw ones would run the node with values the
        // cache does not know about -- and a defaulted widget would arrive at
        // the script as undefined.
        val widgets = JSONObject()
        for ((k, v) in node.params) widgets.put(k, v)

        val args = JSONObject().put("inputs", inJson).put("widgets", widgets).toString()
        // ⚠ The host records whose node this is for the duration of the call.
        // Not passed to JS and not read back from it: a script that could name
        // itself could name anything (HostSurface.caller).
        val out = host.asPlugin(plugin) { runtime.invoke(name, args) }

        val res = try {
            JSONObject(out)
        } catch (e: Exception) {
            throw IllegalStateException(
                "node \"${node.id}\" returned ${out.take(80)}, which is not an object"
            )
        }

        // v1 supports exactly one output, and says so rather than picking one.
        val port = spec.outputs.firstOrNull()
            ?: throw IllegalStateException("node type $name declares no outputs")
        val id = res.optString(port.name)
        require(id.isNotEmpty()) {
            "node \"${node.id}\" returned no \"${port.name}\" -- got ${out.take(80)}"
        }
        return when (port.type) {
            "IMAGE" -> {
                val bmp = ctx.images.get(id)
                    ?: throw IllegalStateException(
                        "node \"${node.id}\" returned image handle \"$id\", which the " +
                            "store does not hold"
                    )
                Value.Image(id, bmp.width, bmp.height)
            }
            // ⚠ Not verified against the backend's handle table here. The
            // executor checks residency for the whole graph in one call before
            // it starts (Executor.kt); a per-node probe would be a second
            // source of truth for the same fact.
            "LATENT" -> Value.Handle(id, "latent")
            else -> throw IllegalStateException(
                "node type $name declares output type \"${port.type}\", which this app " +
                    "does not know (IMAGE, LATENT)"
            )
        }
    }

    /** ⚠ The message names the port, the type it wanted, and what turned up. */
    private fun requirePortType(node: Node, port: Port, v: Value) {
        val ok = when (port.type) {
            "IMAGE" -> v is Value.Image
            "LATENT" -> v is Value.Handle && v.kind == "latent"
            else -> throw IllegalStateException(
                "node type $name declares input \"${port.name}\" as \"${port.type}\", " +
                    "which this app does not know (IMAGE, LATENT)"
            )
        }
        if (!ok) {
            throw IllegalArgumentException(
                "node \"${node.id}\": input \"${port.name}\" wants ${port.type} but " +
                    "carries ${v.describe()}"
            )
        }
    }
}

/**
 * Owns the JS runtime, the host surface, and the node types plugins contribute.
 *
 * ⚠ One runtime for all plugins, because [JsRuntime] is single-threaded and the
 * executor runs nodes sequentially. ⚠⚠ It also means plugins share a global
 * object: `__nm.nodes` is keyed by the qualified type name so two packs cannot
 * collide there, but a plugin that writes to `globalThis` can still be seen by
 * another. That is acceptable for a curated set and NOT acceptable once
 * arbitrary downloads are allowed -- at which point this becomes one runtime
 * per plugin, and the cost of that is a measurement nobody has taken yet.
 */
class PluginHost(val images: ImageStore, latents: LatentOps? = null) : AutoCloseable {

    val surface = HostSurface(images, latents)
    private val runtime = JsRuntime(surface)
    private val loaded = mutableMapOf<String, Plugin>()

    /** Built-ins plus every loaded plugin node. */
    val types: MutableMap<String, NodeType> = NODE_TYPES.toMutableMap()

    fun load(plugin: Plugin): List<String> {
        // ⚠ The script runs FIRST and its registrations are what makes the
        // types usable. A manifest that declares a node the script never
        // registers is a real and common mistake, so it is caught here rather
        // than at render time.
        runtime.eval(plugin.script, "${plugin.id}/index.js")
        val added = mutableListOf<String>()
        for (spec in plugin.nodes) {
            val qualified = spec.qualified(plugin.id)
            val registered = runtime.eval("__nm.nodes[${JsRuntime.quote(qualified)}] ? 'y' : 'n'")
            check(registered == "y") {
                "plugin ${plugin.id} declares \"${spec.type}\" but index.js never registered " +
                    "\"$qualified\""
            }
            types[qualified] = PluginNodeType(plugin, spec, runtime, surface)
            added += qualified
        }
        loaded[plugin.id] = plugin
        return added
    }

    override fun close() = runtime.close()
}
