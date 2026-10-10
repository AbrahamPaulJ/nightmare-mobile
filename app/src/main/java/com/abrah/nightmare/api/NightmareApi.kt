package com.abrah.nightmare.api

import android.content.Context
import com.abrah.nightmare.BuildConfig
import com.abrah.nightmare.HarnessOps
import com.abrah.nightmare.RunLock
import org.json.JSONObject

/**
 * ⭐⭐⭐ **The Nightmare API** (`docs/AGENT-API.md` §3): the app's FLOWS over HTTP, run by the
 * same [HarnessOps.runWorkflow] as the Run button — the third front end on one command layer.
 *
 * | | |
 * |---|---|
 * | `GET /info` | identity, version, whether it is rendering — the one call with no token |
 * | `GET /models` | installed checkpoints: id, family, native size, recipe, features |
 * | `GET /flows` | the recipes, the saved flows and `current` (the canvas autosave) |
 * | `GET /flows/<id>` | a flow's nodes with every widget's schema — what [RunRequest] may set |
 * | `POST /run` | [RunRequest]; JSON at the end, or server-sent events with `"stream": true` |
 * | `GET /results/<run>/<file>` | a result picture or clip |
 * | `POST /cancel` | stops the API's render |
 *
 * ⚠ The HTTP shell only: every command is [ApiCore]'s, which the in-app agent calls too.
 * ⚠ Its own [HarnessOps] (and so its own picture store); the backend process is shared.
 */
class NightmareApi(private val ctx: Context) {

    val core = ApiCore(
        ctx,
        HarnessOps(ctx, object : HarnessOps.Sink {
            override fun say(text: String, bad: Boolean) {
                if (bad) android.util.Log.w(TAG, text) else android.util.Log.i(TAG, text)
            }
        }),
        holder = "api",
    )

    fun handle(req: MiniHttp.Request, res: MiniHttp.Response) {
        try {
            route(req, res)
        } catch (e: ApiError) {
            if (!res.started) res.json(e.code, MiniHttp.err(e.message ?: "error"))
        }
    }

    private fun route(req: MiniHttp.Request, res: MiniHttp.Response) {
        if (req.path == "/info" && req.method == "GET") return res.json(200, info().toString())
        if (!ApiSettings.matches(ApiSettings.token(ctx), req.headers["authorization"], req.query["token"])) {
            throw ApiError(401, "a token is needed — Settings → API on the phone")
        }
        val parts = req.path.trim('/').split('/').filter { it.isNotEmpty() }
        when {
            req.method == "GET" && parts == listOf("models") -> res.json(200, core.models().toString())
            req.method == "GET" && parts == listOf("flows") -> res.json(200, core.flows().toString())
            req.method == "GET" && parts.size == 2 && parts[0] == "flows" -> res.json(200, core.flowSchema(parts[1]).toString())
            req.method == "POST" && parts == listOf("run") -> run(RunRequest.parse(req.text()), res)
            req.method == "POST" && parts == listOf("cancel") -> res.json(200, core.cancel().toString())
            req.method == "GET" && parts.size == 3 && parts[0] == "results" -> {
                val f = core.resultFile(parts[1], parts[2])
                res.send(200, if (f.name.endsWith(".mp4")) "video/mp4" else "image/png", f.readBytes())
            }
            parts.isEmpty() || parts[0] !in setOf("models", "flows", "run", "cancel", "results") ->
                throw ApiError(404, "no such endpoint: ${req.path}")
            else -> throw ApiError(405, "${req.method} is not allowed on ${req.path}")
        }
    }

    private fun info() = JSONObject()
        .put("app", "nightmare")
        .put("version", BuildConfig.VERSION_NAME)
        .put("api", API_VERSION)
        .put("busy", RunLock.heldBy != null)

    private fun run(req: RunRequest, res: MiniHttp.Response) {
        // ⚠ Checked before the stream opens, so a refusal is a plain status, not an event.
        val prepared = core.prepare(req)
        val sse = if (req.stream) res.sse() else null
        val answer = try {
            core.run(prepared) { name, data -> sse?.event(name, data.toString()) }.answer
        } catch (e: ApiError) {
            if (sse == null) throw e
            JSONObject().put("error", e.message)
        }
        if (sse != null) sse.event(if (answer.has("error")) "error" else "done", answer.toString())
        else res.json(if (answer.has("error")) 500 else 200, answer.toString())
    }

    companion object {
        const val API_VERSION = 1
        private const val TAG = "NmApi"
    }
}
