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
 * | `/sdapi/v1/…` | Automatic1111's txt2img, sd-models, options, samplers, progress ([A1111]) |
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

    /** ⭐ Automatic1111's calls ([A1111]), for apps that speak only that (Layla). */
    private val a1111 = A1111(core)

    fun handle(req: MiniHttp.Request, res: MiniHttp.Response) {
        try {
            if (corsPreflight(req, res, ApiSettings.cors(ctx))) return
            route(req, res)
        } catch (e: ApiError) {
            if (!res.started) res.json(e.code, MiniHttp.err(e.message ?: "error"))
        }
    }

    private fun route(req: MiniHttp.Request, res: MiniHttp.Response) {
        if (req.path == "/info" && req.method == "GET") return res.json(200, info().toString())
        val all = req.path.trim('/').split('/').filter { it.isNotEmpty() }
        // ⭐ `/t/<token>/…`: the token in the address, for an app with no header or password field that appends its
        // own paths to the address it was given (an A1111 client's `/sdapi/v1/txt2img`), where `?token=` would break.
        val inPath = all.getOrNull(1)?.takeIf { all.first() == "t" }
        val parts = if (inPath != null) all.drop(2) else all
        if (!ApiSettings.matches(ApiSettings.token(ctx), req.headers["authorization"], inPath ?: req.query["token"])) {
            throw ApiError(401, "a token is needed. Settings → API on the phone")
        }
        if (a1111.handles(parts)) return a1111.route(req.method, parts, req::text, res)
        // ⚠ A tester's Layla POSTed its txt2img body to "/" with a valid token (2026-10-19): the base URL as typed.
        if (req.method == "POST" && parts.isEmpty()) return a1111.route("POST", A1111.TXT2IMG, req::text, res)
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

        /**
         * ⭐ "Allow browser pages" ([ApiSettings.cors]): the CORS headers on this answer, and true when [req] was a
         * preflight, answered here with 204 before the token check (a browser sends no `Authorization` with it).
         * Off: nothing changes, and an `OPTIONS` meets the token check like any call.
         */
        fun corsPreflight(req: MiniHttp.Request, res: MiniHttp.Response, cors: Boolean): Boolean {
            if (!cors) return false
            res.extra.putAll(ApiSettings.CORS_HEADERS)
            if (req.method != "OPTIONS") return false
            res.send(204, "text/plain", ByteArray(0))
            return true
        }
    }
}
