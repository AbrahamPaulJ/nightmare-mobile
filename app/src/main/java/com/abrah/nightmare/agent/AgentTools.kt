package com.abrah.nightmare.agent

import android.content.Context
import com.abrah.nightmare.ImageStore
import com.abrah.nightmare.LoraSources
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Prefs
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.api.ApiCore
import com.abrah.nightmare.api.ApiError
import com.abrah.nightmare.api.RunRequest
import com.abrah.nightmare.api.applyOverrides
import com.abrah.nightmare.applyDefaults
import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.swapWired
import com.abrah.nightmare.canvas.switchingOffAfterRemoving
import com.abrah.nightmare.canvas.switchingOffFor
import com.abrah.nightmare.canvas.switchingOnFor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ⭐⭐⭐ **The agent's tools** (`docs/AGENT-API.md` §4b, the user's design 2026-10-08): it works ON
 * THE CANVAS the person is looking at — opens a flow, sets its params, adds and wires nodes,
 * saves it, presses Run — and on the LoRA files (search online, download, delete). Reads that
 * need no canvas ([ApiCore]: models, flows) and `civitai_settings` complete it. ⚠ It never SEES a
 * result (`view_result` went 2026-10-10 — `ToolResult` has why).
 *
 * ⚠ Every canvas edit goes through the SAME rules the screen uses: [applyOverrides] (checked
 * against the widget schema), `swapWired` for a Swap node's ControlNet / IP-Adapter pictures,
 * `switchingOnFor` / `switchingOffFor` for a wire into `control` / `reference`.
 *
 * [ask] puts a question with buttons in the chat and BLOCKS until the person taps one — for a
 * LoRA delete and for replacing an unsaved flow (the user's choice of what asks first).
 */
class AgentTools(
    private val ctx: Context,
    private val core: ApiCore,
    private val host: CanvasHost,
    private val ask: (question: String, options: List<String>) -> String = { _, o -> o.first() },
    /** ⭐ Pictures the person attached in the chat, `attachment:N` → file (`AgentSession.describe`). */
    private val attachments: () -> Map<String, String> = { emptyMap() },
) : ToolBox {

    override val specs: JSONArray = JSONArray()
        .put(fn("list_models", "The checkpoints installed on this phone: id, family, native size, recommended steps / cfg / scheduler, features (lora, cn = ControlNet, ip = IP-Adapter).", JSONObject()))
        .put(fn("list_flows", "The flows that can be opened: recipes (txt2img, img2img, inpaint, upscale, swap_t2i, swap_i2i, flux_edit, t2v, i2v) and saved flows (saved:<name>).", JSONObject()))
        .put(fn("get_canvas", "The flow on the canvas now: its name, whether it has unsaved edits, and every node (id, type, wires, params, and the widgets it accepts with types, ranges and options). Call before editing.", JSONObject()))
        .put(
            fn(
                "new_flow", "Replace the canvas with a recipe or a saved flow (the person is asked first if the canvas has unsaved edits). Optionally switch its model.",
                props("flow" to str("A flow id from list_flows"), "model" to str("A model id from list_models")), listOf("flow"),
            ),
        )
        .put(
            fn(
                "set_params",
                "Change the canvas flow. Shortcuts apply to every sampler that has the setting; 'params' sets any widget of a node by id; 'model' switches every sampler to that model with its recommended settings.",
                props(
                    "model" to str("A model id from list_models"),
                    "prompt" to str("The positive prompt (every prompt node)"),
                    "negative" to str("The negative prompt"),
                    "seed" to JSONObject().put("type", "integer").put("description", "0 = random every run"),
                    "steps" to JSONObject().put("type", "integer"),
                    "cfg" to JSONObject().put("type", "number"),
                    "denoise" to JSONObject().put("type", "number").put("description", "img2img strength, 0..1"),
                    "scheduler" to str("One the sampler's widget lists"),
                    "width" to JSONObject().put("type", "integer"),
                    "height" to JSONObject().put("type", "integer"),
                    "aspect" to str("e.g. 3:4, 16:9 on fixed-canvas families"),
                    "loras" to str("Installed LoRA files: name.safetensors@0.8, other.safetensors"),
                    "params" to JSONObject().put("type", "object").put("description", "{nodeId: {widget: value}}"),
                    "input_images" to JSONObject().put("type", "object")
                        .put("description", "{imageNodeId: resultUrl or attachment:N} — put an earlier result, or a picture the person attached, into an image node"),
                ),
            ),
        )
        .put(
            fn(
                "add_node", "Add a node to the canvas; returns its id. Types: core.prompt, core.image, core.output, or a sampler type seen in get_canvas.",
                props("type" to str("Node type"), "params" to JSONObject().put("type", "object").put("description", "{widget: value}")), listOf("type"),
            ),
        )
        .put(
            fn(
                "connect", "Wire node 'from' (its output) into input 'port' of node 'to'.",
                props("from" to str("Source node id"), "to" to str("Target node id"), "port" to str("An input port of the target, e.g. prompt, image, media, control, reference")),
                listOf("from", "to", "port"),
            ),
        )
        .put(fn("disconnect", "Remove the wire into input 'port' of node 'to'.", props("to" to str("Node id"), "port" to str("Input port")), listOf("to", "port")))
        .put(fn("remove_node", "Delete a node and its wires.", props("id" to str("Node id")), listOf("id")))
        .put(fn("save_flow", "Save the canvas flow — under its own name if it has one, else the name given, else a suggested one.", props("name" to str("Letters, digits, spaces, - and _"))))
        .put(fn("run", "Press Run on the canvas and wait; the person watches the nodes and the log. Returns any error, the log's last lines and the result urls. Takes seconds (SD 1.5) to minutes (SDXL, FLUX.2, video).", JSONObject()))
        .put(fn("list_loras", "The LoRA files installed on the phone with each one's note (its first line is the trigger words to put in the prompt) — use them in set_params 'loras'.", JSONObject()))
        .put(
            fn(
                "search_loras", "Search LoRAs online (CivitAI, else Hugging Face) for the canvas model's family. Returns ids and versions for download_lora.",
                props("query" to str("What to look for"), "source" to str("civitai or huggingface (default civitai)")), listOf("query"),
            ),
        )
        .put(
            fn(
                "download_lora", "Download a LoRA found by search_loras onto the phone, and wait for it.",
                props("source" to str("civitai or huggingface"), "id" to str("The hit's id"), "version" to str("A version id (default: the newest)")), listOf("source", "id"),
            ),
        )
        .put(fn("delete_lora", "Delete an installed LoRA file (the person is asked first).", props("name" to str("A file name from list_loras")), listOf("name")))
        .put(
            fn(
                "civitai_settings",
                "Research on CivitAI: for a checkpoint name, its base model, trigger words and what its most-liked images used (sampler, steps, cfg, size, example prompts).",
                props("query" to str("The checkpoint's name, e.g. 'Illustrious XL'")), listOf("query"),
            ),
        )

    override fun summary(name: String, args: JSONObject): String = when (name) {
        "new_flow" -> listOfNotNull(args.optString("flow"), args.optString("model").takeIf { it.isNotBlank() }).joinToString(" · ")
        "set_params" -> buildList {
            args.optString("model").takeIf { it.isNotBlank() }?.let { add(it) }
            args.optString("prompt").takeIf { it.isNotBlank() }?.let { add("“" + it.take(50) + (if (it.length > 50) "…" else "") + "”") }
            for (k in RunRequest.KNOBS) if (args.has(k) && k != "loras") add("$k ${args.opt(k)}")
            args.optString("loras").takeIf { it.isNotBlank() }?.let { add(it) }
            args.optJSONObject("params")?.keys()?.forEach { add(it) }
        }.joinToString(" · ")
        "add_node" -> args.optString("type")
        "connect" -> "${args.optString("from")} → ${args.optString("to")}.${args.optString("port")}"
        "disconnect" -> "${args.optString("to")}.${args.optString("port")}"
        "remove_node" -> args.optString("id")
        "save_flow" -> args.optString("name")
        "search_loras", "civitai_settings" -> args.optString("query")
        "download_lora" -> args.optString("id")
        "delete_lora" -> args.optString("name")
        else -> ""
    }

    override fun call(name: String, args: JSONObject): ToolResult = try {
        when (name) {
            "list_models" -> ToolResult(core.models())
            "list_flows" -> ToolResult(core.flows())
            "get_canvas" -> ToolResult(canvas())
            "new_flow" -> newFlow(args)
            "set_params" -> setParams(args)
            "add_node" -> addNode(args)
            "connect" -> connect(args)
            "disconnect" -> disconnect(args)
            "remove_node" -> removeNode(args.getString("id"))
            "save_flow" -> ToolResult(JSONObject().put("saved", host.save(args.optString("name").takeIf { it.isNotBlank() })))
            "run" -> run()
            "list_loras" -> ToolResult(JSONObject().put("loras", loraList()))
            "search_loras" -> searchLoras(args)
            "download_lora" -> downloadLora(args)
            "delete_lora" -> deleteLora(args.getString("name"))
            "civitai_settings" -> ToolResult(civitai(args.getString("query")))
            else -> ToolResult(JSONObject().put("error", "no tool \"$name\""))
        }
    } catch (e: ApiError) {
        ToolResult(JSONObject().put("error", e.message))
    } catch (e: org.json.JSONException) {
        ToolResult(JSONObject().put("error", "bad arguments: ${e.message}"))
    } catch (e: IllegalArgumentException) {
        ToolResult(JSONObject().put("error", e.message))
    }

    private fun canvas(): JSONObject {
        val (flowName, dirty) = host.flowState()
        return core.schemaOf("current", host.workflow(), host.types)
            .put("name", flowName ?: JSONObject.NULL)
            .put("unsaved", dirty)
    }

    /** A short read-back after an edit: ids, types and wires, no widget schema. */
    private fun brief(): JSONObject = JSONObject().put(
        "nodes",
        JSONArray(host.graph().nodes.map { n ->
            JSONObject().put("id", n.id).put("type", n.type).put("inputs", JSONObject(n.inputs.mapValues { it.value.node }))
        }),
    )

    private fun newFlow(args: JSONObject): ToolResult {
        val id = args.getString("flow")
        val wf = core.workflowFor(id)
        val (flowName, dirty) = host.flowState()
        if (dirty) {
            val what = flowName?.let { "“$it”" } ?: "The flow on the canvas"
            when (ask("$what has unsaved changes. Save it before opening $id?", listOf(SAVE, DISCARD, CANCEL))) {
                SAVE -> host.save(null)
                DISCARD -> Unit
                else -> return ToolResult(JSONObject().put("error", "the person cancelled — the canvas was left as it was"))
            }
        }
        host.replace(wf, id.takeUnless { it.startsWith("saved:") || it == "current" })
        args.optString("model").takeIf { it.isNotBlank() }?.let { m ->
            setParams(JSONObject().put("model", m)).takeIf { !it.ok }?.let { return it }
        }
        return ToolResult(brief().put("opened", id))
    }

    private fun setParams(args: JSONObject): ToolResult {
        refusal(args)?.let { return ToolResult(JSONObject().put("error", it)) }
        val body = JSONObject(args.toString()).put("flow", "current")
        val inputs = body.optJSONObject("input_images")
        body.remove("input_images")
        val req = RunRequest.parse(body.toString())
        val spec = req.model?.let { m ->
            ModelCatalog.byId(m)?.takeIf { it.installed(ctx) }
                ?: throw ApiError(409, "model \"$m\" is not installed — list_models lists what is")
        }
        var g = applyOverrides(host.graph(), host.types, req, spec) { _, _ -> throw ApiError(400, "send pictures with input_images") }
        inputs?.keys()?.forEach { node ->
            val n = g.byId[node] ?: throw ApiError(400, "input_images: no node \"$node\"")
            if (n.type != "core.image") throw ApiError(400, "input_images: \"$node\" is not an image node")
            val ref = inputs.getString(node)
            val path = if (ref.startsWith("attachment:")) {
                attachments()[ref] ?: throw ApiError(400, "no picture \"$ref\" was attached")
            } else {
                core.resultFileOf(ref).absolutePath
            }
            g = g.withParam(node, "uri", path)
        }
        val swapEdits = req.params.flatMap { (id, p) -> p.map { (k, v) -> Triple(id, k, v) } }
            .filter { (id, _, _) -> g.byId[id]?.type?.let { SdSampler.isSwapType(it) } == true }
        val result = g
        host.edit { s ->
            // ⭐ A Swap node's ControlNet / IP-Adapter edits wire their picture nodes exactly as the
            // inspector's do (`swapWired`) — one set of wiring rules, whoever edits.
            swapEdits.fold(s.copy(workflow = s.workflow.copy(graph = result))) { acc, (id, k, v) -> acc.swapWired(id, k, v, s) }
        }
        return ToolResult(brief())
    }

    private fun addNode(args: JSONObject): ToolResult {
        val name = args.getString("type")
        val type = host.types[name] ?: throw ApiError(400, "no node type \"$name\"")
        var id = ""
        host.edit { s ->
            val right = s.workflow.positions.values.maxByOrNull { it.x }
            val added = s.addNode(type, right?.let { Pt(it.x + 420f, it.y) } ?: Pt(0f, 0f))
            id = added.workflow.graph.nodes.last().id
            added.copy(editing = s.editing)
        }
        args.optJSONObject("params")?.let { p ->
            setParams(JSONObject().put("params", JSONObject().put(id, p))).takeIf { !it.ok }?.let { return it }
        }
        return ToolResult(JSONObject().put("id", id))
    }

    private fun connect(args: JSONObject): ToolResult {
        val from = args.getString("from")
        val to = args.getString("to")
        val port = args.getString("port")
        val g = host.graph()
        if (g.byId[from] == null) throw ApiError(400, "no node \"$from\"")
        val target = g.byId[to] ?: throw ApiError(400, "no node \"$to\"")
        val ports = host.types[target.type]?.inputs?.map { it.name }.orEmpty()
        if (port !in ports) throw ApiError(400, "\"$to\" has no input \"$port\" — it has ${ports.joinToString()}")
        host.edit { s -> s.copy(workflow = s.workflow.copy(graph = s.workflow.graph.connected(to, port, from).switchingOnFor(to, port))) }
        return ToolResult(brief())
    }

    private fun disconnect(args: JSONObject): ToolResult {
        val to = args.getString("to")
        val port = args.getString("port")
        host.edit { s -> s.copy(workflow = s.workflow.copy(graph = s.workflow.graph.disconnected(to, port).switchingOffFor(to, port))) }
        return ToolResult(brief())
    }

    private fun removeNode(id: String): ToolResult {
        if (host.graph().byId[id] == null) throw ApiError(400, "no node \"$id\"")
        host.edit { s ->
            val cut = s.removeNode(id)
            cut.copy(workflow = cut.workflow.copy(graph = cut.workflow.graph.switchingOffAfterRemoving(s.workflow.graph, id)))
        }
        return ToolResult(brief())
    }

    /** ⭐ Presses Run, waits, and hands back what the output nodes hold — and the chat shows the pictures. */
    private fun run(): ToolResult {
        host.run()?.let { return ToolResult(JSONObject().put("error", it)) }
        val (error, log, pictures) = host.lastRun()
        val runId = "r" + System.currentTimeMillis().toString(36)
        val dir = File(core.resultsDir, runId).apply { mkdirs() }
        val results = JSONArray()
        val paths = mutableListOf<String>()
        for ((node, bmp) in pictures) {
            val f = File(dir, "$node.png").apply { writeBytes(ImageStore.encodePng(bmp)) }
            paths += f.absolutePath
            results.put(JSONObject().put("node", node).put("w", bmp.width).put("h", bmp.height).put("url", "/results/$runId/${f.name}"))
        }
        val out = JSONObject().put("results", results).put("log_tail", JSONArray(log.takeLast(8)))
        error?.let { out.put("error", it) }
        return ToolResult(out, paths, log = log.takeLast(LOG_LINES))
    }

    /**
     * ⭐ Installed LoRAs with their NOTES (`LoraNotes` — the person's own, or the trigger words a
     * download wrote): the model needs the trigger words to use a LoRA well.
     */
    private fun loraList(): JSONArray {
        val notes = com.abrah.nightmare.LoraNotes.read(com.abrah.nightmare.BackendProcess.lorasDir(ctx))
        return JSONArray(host.loras().map { name ->
            JSONObject().put("name", name).apply {
                notes[name]?.let { n ->
                    put("note", n)
                    com.abrah.nightmare.LoraNotes.trigger(n)?.let { put("trigger", it) }
                }
            }
        })
    }

    /** The LoRA family of the canvas's sampler model, or a refusal saying why there is none. */
    private fun loraTarget(): LoraSources.Target {
        val (node, type) = host.graph().nodes.firstNotNullOfOrNull { n -> (host.types[n.type] as? SdSampler)?.let { n to it } }
            ?: throw ApiError(400, "the canvas has no sampler — open a flow first")
        val family = ModelCatalog.byId(applyDefaults(type.widgets, node)["model"].orEmpty())?.family ?: type.family
        return LoraSources.Target.of(family)
            ?: throw ApiError(400, "${family.name} does not take LoRAs from the online search — only SD 1.5 Swap and SDXL Swap models do")
    }

    private fun searchLoras(args: JSONObject): ToolResult {
        val target = loraTarget()
        val q = args.getString("query")
        val key = Prefs.civitaiKey.takeIf { it.isNotBlank() }
        var note: String? = null
        val page = if (args.optString("source").equals("huggingface", ignoreCase = true)) {
            LoraSources.search(LoraSources.Source.HUGGINGFACE, target, q, LoraSources.Sort.DOWNLOADS)
        } else {
            try {
                LoraSources.search(LoraSources.Source.CIVITAI, target, q, LoraSources.Sort.DOWNLOADS, mature = Prefs.matureContent, key = key)
            } catch (e: java.io.IOException) {
                note = "CivitAI: ${e.message} — these are from Hugging Face"
                LoraSources.search(LoraSources.Source.HUGGINGFACE, target, q, LoraSources.Sort.DOWNLOADS)
            }
        }
        val hits = JSONArray(page.hits.take(6).map { h ->
            JSONObject().put("source", h.source.name.lowercase()).put("id", h.id).put("name", h.name)
                .put("creator", h.creator ?: JSONObject.NULL).put("downloads", h.downloads)
                .put("versions", JSONArray(h.versions.take(3).map { v ->
                    JSONObject().put("id", v.id).put("name", v.name).put("base", v.baseModel)
                        .put("mb", (v.file?.bytes ?: 0) / (1 shl 20)).put("trigger", JSONArray(v.trigger))
                }))
        })
        return ToolResult(JSONObject().put("family", target.label).put("hits", hits).apply { note?.let { put("note", it) } })
    }

    private fun downloadLora(args: JSONObject): ToolResult {
        val target = loraTarget()
        val id = args.getString("id")
        val hit = if (args.optString("source").equals("huggingface", ignoreCase = true)) {
            LoraSources.hfRepo(id, target)
        } else {
            LoraSources.civitaiModel(id, target, Prefs.matureContent, Prefs.civitaiKey.takeIf { it.isNotBlank() })
                ?: throw ApiError(404, "no CivitAI model $id for ${target.label}")
        }
        val want = args.optString("version").takeIf { it.isNotBlank() }
        val version = hit.versions.firstOrNull { (want == null || it.id == want) && it.file != null }
            ?: throw ApiError(404, "no downloadable .safetensors in ${hit.name}" + (want?.let { " (version $it)" } ?: ""))
        val before = host.loras().toSet()
        host.downloadLora(hit.name, version)?.let { return ToolResult(JSONObject().put("error", it)) }
        val added = host.loras().filterNot { it in before }
        val notes = com.abrah.nightmare.LoraNotes.read(com.abrah.nightmare.BackendProcess.lorasDir(ctx))
        return ToolResult(
            JSONObject().put("installed", JSONArray(added)).put("trigger", JSONArray(version.trigger))
                .put("notes", JSONObject(added.mapNotNull { n -> notes[n]?.let { n to it } }.toMap())),
        )
    }

    private fun deleteLora(name: String): ToolResult {
        if (name !in host.loras()) throw ApiError(404, "no installed LoRA \"$name\"")
        if (ask("Delete the LoRA “$name” from the phone?", listOf(DELETE, CANCEL)) != DELETE) {
            return ToolResult(JSONObject().put("error", "the person kept it"))
        }
        host.deleteLora(name)
        return ToolResult(JSONObject().put("deleted", name))
    }

    /**
     * ⭐ What people use with a checkpoint: CivitAI's top match for [query], then the `meta` of its
     * latest version's most-reacted images, summarised. ⚠ CivitAI answers 451 in some regions.
     */
    private fun civitai(query: String): JSONObject {
        val host = LoraSources.civitaiHost(Prefs.matureContent)
        val key = Prefs.civitaiKey.takeIf { it.isNotBlank() }
        val models = JSONObject(get("$host/api/v1/models?limit=3&types=Checkpoint&sort=Most%20Downloaded&query=" + enc(query), key))
        val m = models.optJSONArray("items")?.optJSONObject(0) ?: return JSONObject().put("error", "nothing on CivitAI matches \"$query\"")
        val v = m.optJSONArray("modelVersions")?.optJSONObject(0) ?: return JSONObject().put("error", "\"${m.optString("name")}\" has no versions")
        val imgs = JSONObject(get("$host/api/v1/images?limit=30&sort=Most%20Reactions&modelVersionId=${v.optLong("id")}", key))
            .optJSONArray("items") ?: JSONArray()
        val metas = (0 until imgs.length()).mapNotNull { imgs.optJSONObject(it)?.optJSONObject("meta") }
        fun nums(k: String) = metas.mapNotNull { it.opt(k)?.toString()?.toDoubleOrNull() }.sorted()
        fun median(l: List<Double>): Any = if (l.isEmpty()) JSONObject.NULL else l[l.size / 2]
        fun common(k: String) = metas.mapNotNull { it.optString(k).takeIf { s -> s.isNotBlank() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { "${it.key} (${it.value})" }
        return JSONObject()
            .put("model", m.optString("name"))
            .put("version", v.optString("name"))
            .put("base_model", v.optString("baseModel"))
            .put("trigger_words", JSONArray((0 until (v.optJSONArray("trainedWords")?.length() ?: 0)).map { v.getJSONArray("trainedWords").getString(it) }))
            .put("images_read", metas.size)
            .put("median_steps", median(nums("steps")))
            .put("median_cfg", median(nums("cfgScale")))
            .put("common_samplers", JSONArray(common("sampler")))
            .put("common_sizes", JSONArray(common("Size")))
            .put("common_clip_skip", JSONArray(common("clipSkip")))
            .put("example_prompts", JSONArray(metas.mapNotNull { it.optString("prompt").takeIf { p -> p.isNotBlank() }?.take(400) }.take(3)))
            .put("example_negative", metas.firstNotNullOfOrNull { it.optString("negativePrompt").takeIf { p -> p.isNotBlank() }?.take(400) } ?: JSONObject.NULL)
    }

    private fun get(url: String, key: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            key?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            val code = c.responseCode
            if (code == 451) throw ApiError(451, "CivitAI does not serve this region (HTTP 451)")
            if (code >= 400) throw ApiError(502, "CivitAI answered HTTP $code")
            return c.inputStream.bufferedReader().readText()
        } catch (e: java.io.IOException) {
            throw ApiError(502, "could not reach CivitAI: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** How much of a run's log stays in the chat. */
        const val LOG_LINES = 30

        // ⭐ The answers [ask] offers — the chat's buttons. ⚠ Compared as strings; keep them stable.
        const val SAVE = "Save first"
        const val DISCARD = "Discard"
        const val DELETE = "Delete"
        const val CANCEL = "Cancel"

        /**
         * ⭐⭐ The one line the APP holds whatever the model, provider or instructions allow
         * (`docs/AGENT-API.md` §4): nothing sexual involving minors. Everything else — adult NSFW
         * included — is the model's and the person's business. Read over every string the agent
         * writes into a flow. ⚠ Not editable, and kept narrow so it never catches adult content.
         */
        fun refusal(args: JSONObject): String? {
            val text = buildString { collect(args, this) }.lowercase()
            val minor = MINOR.containsMatchIn(text) || AGE.findAll(text).any { (it.groupValues[1].toIntOrNull() ?: 99) < 18 }
            return if (minor && SEXUAL.containsMatchIn(text)) {
                "refused: Nightmare's agent does not make sexual content involving minors"
            } else {
                null
            }
        }

        private fun collect(v: Any?, out: StringBuilder) {
            when (v) {
                is JSONObject -> v.keys().forEach { collect(v.opt(it), out) }
                is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), out)
                is String -> out.append(v).append(' ')
            }
        }

        // ⚠⚠ NARROW on purpose (the user, 2026-10-08: a large share of users make adult NSFW, and an
        // app-side filter must not get in their way). Words that adult characters are routinely
        // described with — schoolgirl, teen, kid, minor, young — are NOT here; only words that
        // unambiguously mean a child, and an age under 18.
        private val MINOR = Regex("\\b(child|children|loli|lolicon|shota|shotacon|underage|toddler|preteen|pre-teen|little girl|little boy)\\b")
        private val AGE = Regex("\\b(\\d{1,2})\\s*(?:-|\\s)?\\s*(?:yo|y/o|years?\\s*old)\\b")
        private val SEXUAL = Regex("\\b(nsfw|nude|naked|sex|sexual|porn|explicit|erotic|hentai|lewd|topless|bottomless|nipples?|genitals?|penis|vagina|pussy|cum|orgasm)\\b")

        private fun fn(name: String, description: String, params: JSONObject, required: List<String> = emptyList()) =
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", name).put("description", description).put(
                    "parameters",
                    JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
                ),
            )

        private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { (k, v) -> put(k, v) } }
        private fun str(d: String) = JSONObject().put("type", "string").put("description", d)

        /** ⭐ What a chat's starting choice tells the agent (`ui/AgentScreen.kt`'s three chips). */
        fun modeNote(mode: AgentMode?): String = when (mode) {
            AgentMode.CREATE -> "[Mode: Create — build the flow on the canvas (new_flow, set_params, add_node, connect) and stop. Do not run it. Say what you set.]"
            AgentMode.CREATE_RUN -> "[Mode: Create & Run — build the flow on the canvas, then run it and show the result.]"
            AgentMode.EDIT -> "[Mode: Edit current — change the flow already on the canvas (get_canvas first). Do not replace it with new_flow unless asked.]"
            null -> ""
        }

        /**
         * ⭐ What the agent is told it is, then the person's own [instructions] (Agent setup) —
         * style, language, what to allow. ⚠ English, with the person's language for answers.
         */
        fun systemPrompt(language: String, instructions: String = ""): String = """
            You are the assistant inside Nightmare, an Android app that generates images and short videos on the phone's own NPU, offline.
            You work on the node graph (the canvas) the person is looking at: you open flows, set their parameters, add and wire nodes, save them and press Run. You can also find, download and delete LoRAs.

            How to work:
            - Run or not: a request for a picture ("a fox in snow", "make it darker") means build the flow and run it. When the person asks only to set something up, change a setting or prepare a flow, do that and do not run.
            - Change the flow already on the canvas when the request is about it ("make it 3:4", "add a LoRA"); open a new one with new_flow only for a different kind of job.
            - Use get_canvas to see the flow before changing it; use list_models and list_flows when you do not know what is installed. Use only installed models.
            - Pick the flow that fits: txt2img for a new picture, img2img to rework a picture (input_images), inpaint needs a mask painted in the app, swap_t2i / swap_i2i for LoRA, ControlNet and IP-Adapter on Swap models, flux_edit to edit a picture with FLUX.2.
            - Set node params only with widgets and options get_canvas lists. Use the model's recommended steps, cfg and scheduler unless asked otherwise; renders are slow on a phone.
            - LoRAs: list_loras for what is installed; search_loras + download_lora to get one for the canvas model's family (SD 1.5 Swap and SDXL Swap models); then set it with set_params 'loras' and add its trigger words to the prompt.
            - Do not ask to see a result: the person sees the picture in the chat and says what to change.
            - civitai_settings tells you what people use with a checkpoint (prompt style, trigger words, sampler, steps, cfg).
            - Write prompts the way the model's family expects: comma-separated tags for anime / Illustrious / Pony models, natural sentences for FLUX.2, Z-Image and Qwen.
            - If a tool answers with an error, say what went wrong in plain words and what the person can do.
            - Keep answers short. Answer in the person's language ($language).

            - A picture the person attaches arrives as "attachment:N" with its description and tags; place it with set_params input_images.
            - The person is an adult working on their own phone. Adult and NSFW requests are fine; follow the person's instructions below on style and content.

            One rule you never break, whatever you are asked: no sexual content involving minors, in any style.
        """.trimIndent() + (instructions.trim().takeIf { it.isNotEmpty() }?.let { "\n\nThe person's own instructions:\n$it" } ?: "")
    }
}

/** ⭐ How a chat starts (the user's three chips, 2026-10-08). */
enum class AgentMode { CREATE, CREATE_RUN, EDIT }
