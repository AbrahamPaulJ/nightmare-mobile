package com.abrah.nightmare.api

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.ModelSpec
import com.abrah.nightmare.SelectedModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⭐⭐ **Automatic1111's API, the part other apps call** (the user's yes, 2026-10-19: a tester's Layla could not
 * reach the API, which speaks only `/run`). The calls an "SD WebUI" client makes, mapped onto [RunRequest] and run
 * by the same [ApiCore] as `/run`: the same models, size rules, render lock and token.
 *
 * | | |
 * |---|---|
 * | `POST /sdapi/v1/txt2img` | A1111's body → `txt2img` on the model; `{"images": [base64 PNG], "parameters", "info"}` |
 * | `GET /sdapi/v1/sd-models` | the installed image models, as A1111 lists checkpoints |
 * | `GET`/`POST /sdapi/v1/options` | `sd_model_checkpoint`: the model the next txt2img uses |
 * | `GET /sdapi/v1/samplers` | the SD sampler names |
 * | `GET /sdapi/v1/progress` | the running render's step fraction |
 *
 * ⚠ Lenient where `/run` is strict, on purpose: an A1111 client sends ITS defaults (a sampler this family lacks,
 * 512x512 to a 1024² model), and the person cannot edit what it sends. A sampler we do not have keeps the model's
 * own; a size becomes the nearest the model renders. `info` says what actually ran.
 */
class A1111(private val core: ApiCore) {

    /** The model `sd_model_checkpoint` named over `/options`; null = the app's selected one. */
    @Volatile
    private var checkpoint: String? = null

    /** The running render's progress, for `/progress` (0 when idle). */
    @Volatile
    private var progress = 0.0

    /** True when [parts] is an A1111 path this class answers; the caller already checked the token. */
    fun handles(parts: List<String>): Boolean = parts.size >= 2 && parts[0] == "sdapi" && parts[1] == "v1"

    fun route(method: String, parts: List<String>, body: () -> String, res: MiniHttp.Response) {
        val call = parts.drop(2).joinToString("/")
        when {
            // ⚠ "text2img" too: what the tester typed, and nothing else lives there.
            method == "POST" && (call == "txt2img" || call == "text2img") -> res.json(200, txt2img(JSONObject(body().ifBlank { "{}" })).toString())
            method == "GET" && call == "sd-models" -> res.json(200, sdModels().toString())
            method == "GET" && call == "options" -> res.json(200, options().toString())
            method == "POST" && call == "options" -> {
                setOptions(JSONObject(body().ifBlank { "{}" }))
                res.json(200, "null")
            }
            method == "GET" && call == "samplers" -> res.json(200, samplers().toString())
            method == "GET" && call == "progress" -> res.json(200, JSONObject().put("progress", progress)
                .put("eta_relative", 0).put("state", JSONObject()).put("current_image", JSONObject.NULL).toString())
            else -> throw ApiError(404, "no such endpoint: /sdapi/v1/$call. This app answers txt2img, sd-models, options, samplers, progress")
        }
    }

    private fun imageModels(): List<ModelSpec> =
        ModelCatalog.all.filter { it.installed(core.ctx) && it.family != com.abrah.nightmare.Family.WAN }

    private fun current(): ModelSpec {
        val id = checkpoint ?: SelectedModel.id
        return ModelCatalog.byId(id)?.takeIf { it.installed(core.ctx) }
            ?: imageModels().firstOrNull()
            ?: throw ApiError(409, "no image model is installed. Download one in Models")
    }

    private fun sdModels() = JSONArray(imageModels().map { s ->
        JSONObject().put("title", s.id).put("model_name", s.id).put("hash", JSONObject.NULL)
            .put("sha256", JSONObject.NULL).put("filename", s.id).put("config", JSONObject.NULL)
    })

    private fun options() = JSONObject().put("sd_model_checkpoint", current().id)

    private fun setOptions(o: JSONObject) {
        val want = o.optString("sd_model_checkpoint").takeIf { it.isNotBlank() } ?: return
        checkpoint = modelNamed(want).id
    }

    /** ⚠ A1111 titles look like `name.safetensors [hash]`: matched on the id inside. */
    private fun modelNamed(title: String): ModelSpec {
        val bare = title.substringBefore(" [").substringBeforeLast(".safetensors").trim()
        return imageModels().firstOrNull { it.id == bare || it.label == bare }
            ?: throw ApiError(400, "no installed model \"$title\". GET /sdapi/v1/sd-models lists them")
    }

    private fun samplers() = JSONArray(ModelCatalog.SAMPLERS.map { (_, name) ->
        JSONObject().put("name", name).put("aliases", JSONArray()).put("options", JSONObject())
    })

    private fun txt2img(o: JSONObject): JSONObject {
        val spec = o.optJSONObject("override_settings")?.optString("sd_model_checkpoint")
            ?.takeIf { it.isNotBlank() }?.let(::modelNamed) ?: current()
        val count = (o.optInt("batch_size", 1).coerceAtLeast(1) * o.optInt("n_iter", 1).coerceAtLeast(1)).coerceAtMost(MAX_IMAGES)
        val seed0 = o.optLong("seed", -1L).takeIf { it >= 0 } ?: kotlin.random.Random.nextLong(0, Int.MAX_VALUE.toLong())
        val knobs = knobsFor(spec, o)
        val images = JSONArray()
        val seeds = JSONArray()
        var ran = knobs
        for (i in 0 until count) {
            val seed = seed0 + i
            val req = RunRequest(
                flow = "txt2img",
                model = spec.id,
                prompt = o.optString("prompt", ""),
                negative = o.optString("negative_prompt", "").takeIf { it.isNotEmpty() },
                knobs = knobs + ("seed" to seed.toString()),
            )
            val result = try {
                core.run(core.prepare(req)) { name, data ->
                    if (name == "progress") {
                        val total = data.optInt("total", 0)
                        if (total > 0) progress = (i + data.optInt("step", 0).toDouble() / total) / count
                    }
                }
            } finally {
                progress = 0.0
            }
            result.answer.optString("error").takeIf { it.isNotBlank() }?.let { throw ApiError(500, it) }
            val pictures = result.answer.optJSONArray("results") ?: JSONArray()
            for (j in 0 until pictures.length()) {
                val r = pictures.getJSONObject(j)
                if (r.optString("kind") != "image") continue
                images.put(android.util.Base64.encodeToString(core.resultFileOf(r.getString("url")).readBytes(), android.util.Base64.NO_WRAP))
                seeds.put(seed)
            }
            ran = knobs + ("seed" to seed.toString())
        }
        val info = JSONObject().put("sd_model_name", spec.id).put("seed", seed0).put("all_seeds", seeds)
            .put("prompt", o.optString("prompt", "")).put("negative_prompt", o.optString("negative_prompt", ""))
            .put("used", JSONObject(ran))
        return JSONObject().put("images", images).put("parameters", o).put("info", info.toString())
    }

    /**
     * ⚠ Pure apart from [spec]: A1111's knobs as this model takes them. Steps and CFG as sent; the sampler when this
     * family has it under that name (else the model's own); the size as the nearest this model renders: an aspect on
     * a fixed canvas (SDXL, Anima on the NPU), a listed size or the DiT grid otherwise.
     */
    internal fun knobsFor(spec: ModelSpec, o: JSONObject): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (o.has("steps")) out["steps"] = o.getInt("steps").coerceIn(1, 150).toString()
        if (o.has("cfg_scale")) out["cfg"] = o.getDouble("cfg_scale").toString()
        // ⭐ A1111's variation seed, where the model takes one (not a DiT, backend 037).
        val strength = o.optDouble("subseed_strength", 0.0)
        if (strength > 0.0 && !spec.isDit) {
            out["subseed"] = o.optLong("subseed", -1L).takeIf { it >= 0 }?.toString() ?: kotlin.random.Random.nextInt(1, Int.MAX_VALUE).toString()
            out["subseed_strength"] = strength.coerceIn(0.0, 1.0).toString()
        }
        samplerFor(spec, o.optString("sampler_name").ifBlank { o.optString("sampler_index") }, o.optString("scheduler"))
            ?.let { out["scheduler"] = it }
        val w = o.optInt("width", 0)
        val h = o.optInt("height", 0)
        if (w > 0 && h > 0) {
            if (spec.fixedCanvas) {
                out["aspect"] = nearestAspect(w, h)
            } else {
                val r = ModelCatalog.keptSize(spec, w, h)
                out["width"] = r.width.toString()
                out["height"] = r.height.toString()
            }
        }
        return out
    }

    companion object {
        /** ⚠ One request cannot hold the phone for an hour: batch_size x n_iter is capped. */
        const val MAX_IMAGES = 4

        val TXT2IMG = listOf("sdapi", "v1", "txt2img")

        /** ⚠ Pure: the [ModelCatalog.ASPECTS] entry nearest [w]x[h] by log ratio. */
        fun nearestAspect(w: Int, h: Int): String {
            val want = kotlin.math.ln(w.toDouble() / h)
            return ModelCatalog.ASPECTS.minBy { a ->
                kotlin.math.abs(kotlin.math.ln(ModelCatalog.ratioValue(a)!!.toDouble()) - want)
            }
        }

        /**
         * ⚠ Pure: A1111's sampler (`"DPM++ 2M Karras"`, or `"DPM++ 2M"` with scheduler `"Karras"`) as one of
         * [spec]'s scheduler values, or null when this family has no such sampler. "Automatic" is no schedule.
         */
        fun samplerFor(spec: ModelSpec, name: String, scheduler: String): String? {
            if (name.isBlank()) return null
            val sched = scheduler.trim().takeUnless { it.isEmpty() || it.equals("automatic", true) || it.equals("normal", true) }
            val want = (if (sched != null) "$name $sched" else name).trim().lowercase()
            return ModelCatalog.schedulersFor(spec.family).firstOrNull { ModelCatalog.schedulerLabel(it).lowercase() == want }
        }
    }
}
