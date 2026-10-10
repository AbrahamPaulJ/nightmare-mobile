package com.abrah.nightmare.api

import android.content.Context
import android.util.Base64
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.DitEngine
import com.abrah.nightmare.Graph
import com.abrah.nightmare.GraphRun
import com.abrah.nightmare.HarnessOps
import com.abrah.nightmare.ImageStore
import com.abrah.nightmare.MediaOutputNode
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Outcome
import com.abrah.nightmare.RunLock
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.Value
import com.abrah.nightmare.applyDefaults
import com.abrah.nightmare.canvas.RECIPES
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.WorkflowFormatError
import com.abrah.nightmare.canvas.WorkflowStore
import com.abrah.nightmare.canvas.defaultWorkflow
import com.abrah.nightmare.canvas.workflowFromJson
import com.abrah.nightmare.skipEmptyPictures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ⭐⭐⭐ **The API's commands** (`docs/AGENT-API.md` §3) — one implementation for the HTTP shell
 * ([NightmareApi]) and the in-app agent (`agent/AgentTools.kt`): the agent's tools ARE these.
 *
 * ⚠ [ops] decides whose picture store the results land in: the API brings its own, the agent
 * the view model's, so what it makes can be kept to Results like a canvas render.
 * ⚠ [holder] names who holds [RunLock] while a render runs ("api", "agent").
 * ⚠ Blocking: call off the main thread.
 */
class ApiCore(private val ctx: Context, val ops: HarnessOps, private val holder: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val resultsDir = File(ctx.filesDir, "api/results")
    private val uploadsDir = File(ctx.cacheDir, "api/uploads")
    private val store get() = WorkflowStore(File(ctx.filesDir, "workflows"))
    @Volatile private var runJob: Job? = null

    fun models(): JSONObject {
        val arr = JSONArray()
        for (s in ModelCatalog.installed(ctx)) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("label", s.label)
                    .put("family", s.family.name)
                    .put("dit", s.isDit)
                    .put("native", "${s.native.width}x${s.native.height}")
                    .put("steps", s.steps).put("cfg", s.cfg).put("scheduler", s.scheduler)
                    .put("features", JSONArray(s.featureSet.sorted())),
            )
        }
        return JSONObject().put("models", arr)
    }

    fun flows(): JSONObject {
        val caps = DeviceProbe.caps()
        val recipes = JSONArray()
        for (r in RECIPES) {
            recipes.put(
                JSONObject()
                    .put("id", r.id)
                    .put("label", r.label)
                    .put("about", r.about)
                    .put("uses_checkpoint", r.usesCheckpoint)
                    .put("families", r.families?.let { f -> JSONArray(f.map { it.name }) } ?: JSONObject.NULL)
                    .put("runs_here", r.runsOnDevice(caps)),
            )
        }
        val saved = JSONArray(listOf("current") + store.saved().map { "saved:" + it.name })
        return JSONObject().put("recipes", recipes).put("saved", saved)
    }

    /** A flow by its `/flows` id. */
    fun workflowFor(id: String): Workflow = when {
        id == "current" -> runCatching { store.load("current")?.workflow }.getOrNull() ?: defaultWorkflow()
        id.startsWith("saved:") -> try {
            store.load(id.removePrefix("saved:"))?.workflow ?: throw ApiError(404, "no saved flow \"${id.removePrefix("saved:")}\"")
        } catch (e: WorkflowFormatError) {
            throw ApiError(400, "that saved flow cannot be read: ${e.message}")
        }
        else -> RECIPES.firstOrNull { it.id == id }?.build?.invoke()
            ?: throw ApiError(404, "no flow \"$id\" — list_flows / GET /flows names them")
    }

    fun flowSchema(id: String): JSONObject = schemaOf(id, workflowFor(id), ops.nodeTypes())

    /** ⭐ A flow's nodes with every widget's schema — `/flows/<id>` and the agent's `get_canvas`. */
    fun schemaOf(id: String, wf: Workflow, types: Map<String, NodeType>): JSONObject {
        val nodes = JSONArray()
        for (n in wf.graph.nodes) {
            val t = types[n.type]
            nodes.put(
                JSONObject()
                    .put("id", n.id)
                    .put("type", n.type)
                    .put("inputs", JSONObject(n.inputs.mapValues { it.value.node }))
                    .put("params", JSONObject(t?.let { applyDefaults(it.widgets, n) } ?: n.params))
                    .put("widgets", JSONArray(t?.widgets.orEmpty().map { w ->
                        JSONObject().put("name", w.name).put("type", w.type)
                            .put("default", w.default ?: JSONObject.NULL)
                            .put("min", w.min ?: JSONObject.NULL).put("max", w.max ?: JSONObject.NULL)
                            .put("options", w.options?.let { JSONArray(it) } ?: JSONObject.NULL)
                    })),
            )
        }
        return JSONObject().put("id", id).put("nodes", nodes)
    }

    fun cancel(): JSONObject {
        val job = runJob ?: return JSONObject().put("cancelled", false)
        com.abrah.nightmare.Backend.abortInFlight()
        job.cancel()
        return JSONObject().put("cancelled", true)
    }

    /** A request checked and applied, ready to render — everything that can be a 4xx happened. */
    class Prepared internal constructor(val runId: String, val workflow: Workflow)

    /**
     * ⭐ [req] resolved, overridden and checked ([applyOverrides], the installed models, an
     * empty picture that cannot be skipped). Throws [ApiError]. [inputPath] maps a previous
     * result's url (`/results/<run>/<file>`) for the agent's `input_images`.
     */
    fun prepare(req: RunRequest, inputPaths: Map<String, String> = emptyMap()): Prepared {
        val types = ops.nodeTypes()
        val base = if (req.graph != null) {
            try {
                workflowFromJson(req.graph).workflow
            } catch (e: Exception) {
                throw ApiError(400, "graph: ${e.message}")
            }
        } else workflowFor(req.flow!!)
        val spec = req.model?.let { id ->
            ModelCatalog.byId(id)?.takeIf { it.installed(ctx) }
                ?: throw ApiError(409, "model \"$id\" is not installed — list_models / GET /models lists what is")
        }
        val runId = "r" + System.currentTimeMillis().toString(36)
        var graph = applyOverrides(base.graph, types, req, spec) { node, b64 -> upload(runId, node, b64) }
        for ((node, url) in inputPaths) {
            val n = graph.byId[node] ?: throw ApiError(400, "input_images: no node \"$node\" in this flow")
            if (n.type != "core.image") throw ApiError(400, "input_images: \"$node\" is not an image node")
            graph = graph.withParam(node, "uri", resultFileOf(url).absolutePath)
        }
        modelsMissing(graph, types)?.let { throw ApiError(409, it) }
        return Prepared(runId, base.copy(graph = graph))
    }

    /** What a render made: the answer (as the HTTP API says it) and the pictures, by node. */
    class RunResult(val answer: JSONObject, val images: List<Pair<String, String>>, val workflow: Workflow)

    /**
     * ⭐⭐ Render [p]. [event] gets the run log as it happens (`node`, `done_node`, `progress`,
     * `log`, `warn` — the HTTP API's server-sent events). ⚠ 409 while anything else renders.
     */
    fun run(p: Prepared, event: (String, JSONObject) -> Unit = { _, _ -> }): RunResult {
        if (!RunLock.tryAcquire(holder)) {
            throw ApiError(409, when (RunLock.heldBy) {
                "canvas" -> "the app is rendering — try again when it finishes"
                else -> "already rendering (${RunLock.heldBy})"
            })
        }
        try {
            event("start", JSONObject().put("run", p.runId))
            val warnings = JSONArray()
            val graph = p.workflow.graph
            val outcome = runBlocking {
                val job = scope.async {
                    com.abrah.nightmare.BackendIdle.begin()
                    try {
                        if (!ops.ensureBackendFor(graph)) return@async null
                        ops.runWorkflow(
                            p.workflow,
                            onStart = { id, type -> event("node", JSONObject().put("node", id).put("type", type)) },
                            onNode = { n ->
                                event("done_node", JSONObject().put("node", n.id).put("outcome", n.outcome.name.lowercase())
                                    .put("ms", n.ms).put("detail", n.detail))
                            },
                            onProgress = { id, step, total ->
                                event("progress", JSONObject().put("node", id).put("step", step).put("total", total))
                            },
                            onLog = { id, text -> event("log", JSONObject().put("node", id).put("text", text)) },
                            onWarn = { id, text ->
                                warnings.put(text)
                                event("warn", JSONObject().put("node", id).put("text", text))
                            },
                        )
                    } finally {
                        com.abrah.nightmare.BackendIdle.end()
                    }
                }
                runJob = job
                try { job.await() } catch (e: kotlinx.coroutines.CancellationException) { "cancelled" }
            }
            val answer = JSONObject().put("run", p.runId)
            var images = emptyList<Pair<String, String>>()
            when (outcome) {
                null -> answer.put("error", "the backend did not start for this flow")
                "cancelled" -> answer.put("error", "cancelled")
                is GraphRun -> {
                    answer.put("ms", outcome.totalMs).put("warnings", warnings)
                    outcome.error?.let { answer.put("error", it) }
                    outcome.waiting?.let { (node, why) -> answer.put("error", "$node: $why") }
                    val (json, imgs) = saveResults(p.runId, graph, outcome)
                    answer.put("results", json)
                    images = imgs
                    answer.put("failed", JSONArray(outcome.runs.filter { it.outcome == Outcome.FAILED }.map { it.id }))
                }
            }
            return RunResult(answer, images, p.workflow)
        } finally {
            runJob = null
            RunLock.release(holder)
            File(uploadsDir, p.runId).deleteRecursively()
        }
    }

    /** ⭐ What `HarnessViewModel.modelsPresentOrAsk` checks, said as a sentence. */
    private fun modelsMissing(graph: Graph, types: Map<String, NodeType>): String? {
        for (n in graph.nodes) {
            val t = types[n.type] as? SdSampler ?: continue
            val id = applyDefaults(t.widgets, n)["model"].orEmpty()
            val spec = ModelCatalog.byId(id)
            if (spec == null || !spec.installed(ctx)) return "node \"${n.id}\" needs model \"$id\", which is not installed"
            if (spec.isDit && !DitEngine.isInstalled(ctx)) return "${spec.label} needs the DiT engine — download it in the app first"
        }
        // ⚠ An empty picture node the run cannot skip ([skipEmptyPictures]) would fail; say it now.
        val (left, _) = skipEmptyPictures(graph)
        left.nodes.firstOrNull { it.type == "core.image" && it.params["uri"].isNullOrBlank() }?.let {
            return "image node \"${it.id}\" has no picture — send one"
        }
        return null
    }

    private fun upload(runId: String, node: String, b64: String): String {
        val bytes = try {
            Base64.decode(b64.substringAfter("base64,"), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw ApiError(400, "images.$node is not base64")
        }
        val dir = File(uploadsDir, runId).apply { mkdirs() }
        return File(dir, "$node.bin").apply { writeBytes(bytes) }.absolutePath
    }

    /**
     * ⭐ The pictures and clips the flow's OUTPUT nodes hold, written to `results/<run>/`
     * (the picture store keeps twelve, so a later run would evict them). A flow with no
     * output node gives its last picture. ⚠ The newest [KEEP_RUNS] runs are kept.
     */
    private fun saveResults(runId: String, graph: Graph, r: GraphRun): Pair<JSONArray, List<Pair<String, String>>> {
        val dir = File(resultsDir, runId).apply { mkdirs() }
        val picked = graph.nodes.filter { it.type == MediaOutputNode.name }.mapNotNull { n -> r.outputs[n.id]?.let { n.id to it } }
            .ifEmpty {
                r.runs.asReversed().firstNotNullOfOrNull { run -> (r.outputs[run.id] as? Value.Image)?.let { listOf(run.id to it) } }.orEmpty()
            }
        val out = JSONArray()
        val images = mutableListOf<Pair<String, String>>()
        for ((node, v) in picked) {
            when (v) {
                is Value.Video -> {
                    val f = File(dir, "$node.mp4")
                    File(v.path).copyTo(f, overwrite = true)
                    out.put(JSONObject().put("node", node).put("kind", "video").put("w", v.w).put("h", v.h)
                        .put("url", "/results/$runId/${f.name}"))
                }
                else -> {
                    val img = v as? Value.Image ?: continue
                    val bmp = ops.images.get(img.id) ?: continue
                    val f = File(dir, "$node.png").apply { writeBytes(ImageStore.encodePng(bmp)) }
                    images += node to img.id
                    out.put(JSONObject().put("node", node).put("kind", "image").put("w", img.w).put("h", img.h)
                        .put("url", "/results/$runId/${f.name}"))
                }
            }
        }
        resultsDir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(KEEP_RUNS).forEach { it.deleteRecursively() }
        return out to images
    }

    /** ⭐ A result's file by its url, `/results/<run>/<file>`. */
    fun resultFileOf(url: String): File {
        val parts = url.trim('/').split('/')
        if (parts.size != 3 || parts[0] != "results") throw ApiError(400, "not a result url: $url")
        return resultFile(parts[1], parts[2])
    }

    fun resultFile(runId: String, file: String): File {
        // ⚠ Plain names only: no `..`, no separators — it is a path on disk.
        if (!runId.matches(Regex("[a-z0-9]+")) || !file.matches(Regex("[A-Za-z0-9_.-]+")) || file.contains("..")) {
            throw ApiError(400, "bad result name")
        }
        val f = File(File(resultsDir, runId), file)
        if (!f.isFile) throw ApiError(404, "no such result")
        return f
    }

    companion object {
        const val KEEP_RUNS = 20
    }
}
