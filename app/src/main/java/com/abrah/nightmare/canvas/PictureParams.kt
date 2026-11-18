package com.abrah.nightmare.canvas

import com.abrah.nightmare.LoraSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * ⭐ GitHub #133 (the user's call, 2026-11-18): a picture made by ComfyUI or A1111 opens as OUR nearest flow, Text to
 * image, with its prompt, negative, seed, steps, CFG, size and LoRAs filled in. Not a graph importer: ComfyUI's nodes
 * are read for those values only, and the rest of its graph is left behind.
 *
 * - ComfyUI writes its graph twice into a PNG: `prompt` (the API form, `{id: {class_type, inputs}}`, read here) and
 *   `workflow` (the editor's layout, not needed).
 * - A1111, Forge and CivitAI write `parameters`: the prompt, `Negative prompt: ...`, then `Steps: 20, Sampler: ...`.
 */
object PictureParams {

    data class Read(
        val prompt: String? = null,
        val negative: String? = null,
        val seed: Long? = null,
        val steps: Int? = null,
        val cfg: Double? = null,
        val width: Int? = null,
        val height: Int? = null,
        val sampler: String? = null,
        val model: String? = null,
        val loras: List<LoraSpec.Entry> = emptyList(),
        /** "ComfyUI" or "A1111", for the line that says what was read. */
        val from: String,
    ) {
        val empty: Boolean get() = prompt == null && seed == null && steps == null && loras.isEmpty()
    }

    /** ⚠ Pure: the PNG's text chunks ([com.abrah.nightmare.PngInfo.read]) to values, or null when neither form is there. */
    fun of(chunks: Map<String, String>): Read? =
        chunks["prompt"]?.let { runCatching { comfy(JSONObject(it)) }.getOrNull() }?.takeIf { !it.empty }
            ?: chunks["parameters"]?.let(::a1111)?.takeIf { !it.empty }

    // ---- ComfyUI ------------------------------------------------------------------------------------------------

    private val SAMPLERS = setOf("KSampler", "KSamplerAdvanced", "SamplerCustom", "SamplerCustomAdvanced")

    /** ⚠ Pure: ComfyUI's API graph to values. Follows the sampler's own wires where it can, and falls back to any node. */
    fun comfy(g: JSONObject): Read {
        val nodes = g.keys().asSequence().mapNotNull { k -> g.optJSONObject(k)?.let { k to it } }.toMap()
        fun type(n: JSONObject) = n.optString("class_type")
        fun inputs(n: JSONObject) = n.optJSONObject("inputs") ?: JSONObject()
        fun linked(v: Any?): JSONObject? = (v as? JSONArray)?.optString(0)?.let { nodes[it] }
        fun anyOf(vararg types: String) = nodes.values.filter { type(it) in types }

        // A text, a number or a wire to a node that holds one (a primitive, a string node, a concatenation's first part).
        fun text(v: Any?, depth: Int = 0): String? = when {
            v is String -> v
            depth > 6 -> null
            else -> linked(v)?.let { n ->
                val i = inputs(n)
                listOf("text", "string", "value", "text_g", "prompt", "string_a", "text_a")
                    .firstNotNullOfOrNull { k -> if (i.has(k)) text(i.get(k), depth + 1) else null }
            }
        }
        fun number(v: Any?, depth: Int = 0): Double? = when {
            v is Number -> v.toDouble()
            v is String -> v.toDoubleOrNull()
            depth > 6 -> null
            else -> linked(v)?.let { n ->
                val i = inputs(n)
                listOf("value", "seed", "noise_seed", "int", "float", "number")
                    .firstNotNullOfOrNull { k -> if (i.has(k)) number(i.get(k), depth + 1) else null }
            }
        }
        // A conditioning wire back to its text, through guidance and other conditioning nodes on the way.
        fun condText(v: Any?, depth: Int = 0): String? {
            val n = linked(v) ?: return null
            val i = inputs(n)
            listOf("text", "text_g", "prompt").firstNotNullOfOrNull { k -> if (i.has(k)) text(i.get(k)) else null }?.let { return it }
            if (depth > 6) return null
            return listOf("conditioning", "conditioning_1", "positive", "conditioning_to")
                .firstNotNullOfOrNull { k -> if (i.has(k)) condText(i.get(k), depth + 1) else null }
        }

        val sampler = anyOf(*SAMPLERS.toTypedArray()).firstOrNull()
        val si = sampler?.let(::inputs) ?: JSONObject()
        // SamplerCustomAdvanced: its guider holds the conditioning, its noise the seed, its sigmas the steps.
        val guider = linked(si.opt("guider"))?.let(::inputs)
        val positive = condText(si.opt("positive")) ?: guider?.let { condText(it.opt("positive")) ?: condText(it.opt("conditioning")) }
            ?: anyOf("CLIPTextEncode", "CLIPTextEncodeFlux", "CLIPTextEncodeSDXL").firstNotNullOfOrNull { n -> text(inputs(n).opt("text")) }
        val negative = condText(si.opt("negative")) ?: guider?.let { condText(it.opt("negative")) }
        val seed = number(si.opt("seed")) ?: number(si.opt("noise_seed"))
            ?: linked(si.opt("noise"))?.let { number(inputs(it).opt("noise_seed")) }
            ?: anyOf("RandomNoise").firstNotNullOfOrNull { number(inputs(it).opt("noise_seed")) }
        val steps = number(si.opt("steps"))
            ?: linked(si.opt("sigmas"))?.let { number(inputs(it).opt("steps")) }
            ?: anyOf("BasicScheduler", "Flux2Scheduler").firstNotNullOfOrNull { number(inputs(it).opt("steps")) }
        val cfg = number(si.opt("cfg")) ?: guider?.let { number(it.opt("cfg")) }
        val latent = linked(si.opt("latent_image"))
            ?: anyOf("EmptyLatentImage", "EmptySD3LatentImage", "EmptyFlux2LatentImage", "EmptyHunyuanLatentVideo").firstOrNull()
        val li = latent?.let(::inputs)
        val samplerName = si.optString("sampler_name").takeIf { it.isNotBlank() }
            ?: linked(si.opt("sampler"))?.let { inputs(it).optString("sampler_name") }?.takeIf { it.isNotBlank() }
        val model = nodes.values.firstNotNullOfOrNull { n ->
            val i = inputs(n)
            listOf("ckpt_name", "unet_name", "model_name").firstNotNullOfOrNull { k -> i.optString(k).takeIf { it.isNotBlank() } }
        }
        val loras = nodes.values.flatMap { n -> comfyLoras(type(n), inputs(n)) }
        return Read(
            prompt = positive?.trim()?.takeIf { it.isNotEmpty() },
            negative = negative?.trim(),
            seed = seed?.toLong(),
            steps = steps?.toInt(),
            cfg = cfg,
            width = li?.let { number(it.opt("width")) }?.toInt(),
            height = li?.let { number(it.opt("height")) }?.toInt(),
            sampler = samplerName,
            model = model?.let { java.io.File(it).name },
            loras = loras,
            from = "ComfyUI",
        )
    }

    /** LoraLoader, LoraLoaderModelOnly, and rgthree's Power Lora Loader (`lora_1: {on, lora, strength}`). */
    private fun comfyLoras(type: String, i: JSONObject): List<LoraSpec.Entry> = when {
        i.has("lora_name") -> {
            val s = i.optDouble("strength_model", i.optDouble("strength", LoraSpec.FULL))
            listOfNotNull(entry(i.optString("lora_name"), s))
        }
        type.contains("Lora", ignoreCase = true) -> i.keys().asSequence().mapNotNull { k ->
            val o = i.optJSONObject(k) ?: return@mapNotNull null
            if (!o.optBoolean("on", true)) return@mapNotNull null
            entry(o.optString("lora"), o.optDouble("strength", LoraSpec.FULL))
        }.toList()
        else -> emptyList()
    }

    private fun entry(path: String, strength: Double): LoraSpec.Entry? {
        val name = java.io.File(path.replace('\\', '/')).name.takeIf { it.isNotBlank() && it != "None" } ?: return null
        return LoraSpec.Entry(name, if (strength.isNaN()) LoraSpec.FULL else strength)
    }

    // ---- A1111 --------------------------------------------------------------------------------------------------

    private val LORA_TAG = Regex("""<lora:([^:>]+)(?::([-\d.]+))?[^>]*>""")

    /** ⚠ Pure: A1111's `parameters` block. LoRAs are its `<lora:name:weight>` tags, taken out of the prompt. */
    fun a1111(text: String): Read {
        val lines = text.trim().lines()
        val last = lines.indexOfLast { it.trimStart().startsWith("Steps:") }
        val head = if (last >= 0) lines.subList(0, last) else lines
        val neg = head.indexOfFirst { it.startsWith("Negative prompt:") }
        val promptText = (if (neg >= 0) head.subList(0, neg) else head).joinToString("\n")
        val negative = if (neg >= 0) head.subList(neg, head.size).joinToString("\n").removePrefix("Negative prompt:").trim() else null
        val fields = if (last >= 0) fieldsOf(lines.subList(last, lines.size).joinToString(" ")) else emptyMap()
        val size = fields["Size"]?.split('x')?.mapNotNull { it.trim().toIntOrNull() }
        val loras = LORA_TAG.findAll(promptText).map { m ->
            LoraSpec.Entry(m.groupValues[1].trim() + ".safetensors", m.groupValues[2].toDoubleOrNull() ?: LoraSpec.FULL)
        }.toList()
        return Read(
            prompt = LORA_TAG.replace(promptText, "").replace(Regex("""\s*,\s*,"""), ",").trim().trim(',').trim().takeIf { it.isNotEmpty() },
            negative = negative,
            seed = fields["Seed"]?.toLongOrNull(),
            steps = fields["Steps"]?.toIntOrNull(),
            cfg = fields["CFG scale"]?.toDoubleOrNull(),
            width = size?.getOrNull(0),
            height = size?.getOrNull(1),
            sampler = fields["Sampler"],
            model = fields["Model"],
            loras = loras,
            from = "A1111",
        )
    }

    /** `Steps: 20, Sampler: Euler a, Lora hashes: "a: 1, b: 2"` — commas inside quotes are not separators. */
    private fun fieldsOf(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var quoted = false
        val part = StringBuilder()
        fun flush() {
            val p = part.toString(); part.clear()
            val c = p.indexOf(':')
            if (c > 0) out[p.substring(0, c).trim()] = p.substring(c + 1).trim().trim('"')
        }
        for (ch in s) when {
            ch == '"' -> { quoted = !quoted; part.append(ch) }
            ch == ',' && !quoted -> flush()
            else -> part.append(ch)
        }
        flush()
        return out
    }

    // ---- into our flow --------------------------------------------------------------------------------------------

    /**
     * ⚠ Pure: [r]'s values on [flow] (Text to image: a `prompt` node and a sampler named `generate`). The sampler name
     * is kept only when it is one of [samplers] (this model's own list); the size only where [gridded] is given (a DiT
     * model takes any size on its grid; an SD checkpoint's size is bound to its launch, so it keeps its own).
     */
    fun apply(flow: Workflow, r: Read, samplers: List<String>, gridded: ((Int) -> Int)?): Workflow {
        val g = flow.graph
        val nodes = g.nodes.map { n ->
            when (n.id) {
                "prompt" -> n.copy(params = n.params + listOfNotNull(
                    r.prompt?.let { "prompt" to it },
                    r.negative?.let { "negative" to it },
                ))
                "generate" -> n.copy(params = n.params + listOfNotNull(
                    r.seed?.let { "seed" to it.toString() },
                    r.steps?.let { "steps" to it.toString() },
                    r.cfg?.let { "cfg" to LoraSpec.number(it) },
                    gridded?.let { f -> r.width?.let { "width" to f(it).toString() } },
                    gridded?.let { f -> r.height?.let { "height" to f(it).toString() } },
                    r.sampler?.let { s -> samplers.firstOrNull { it.equals(s, ignoreCase = true) || it.equals(s.replace(' ', '_'), ignoreCase = true) } }
                        ?.let { "scheduler" to it },
                    r.loras.takeIf { it.isNotEmpty() }?.let { com.abrah.nightmare.SdSampler.LORAS to LoraSpec.format(it) },
                ))
                else -> n
            }
        }
        return flow.copy(graph = g.copy(nodes = nodes))
    }
}
