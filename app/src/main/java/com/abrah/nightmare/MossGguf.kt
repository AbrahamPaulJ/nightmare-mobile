package com.abrah.nightmare

import java.io.IOException

/**
 * ⭐⭐ A 16-bit MOSS-Image `.safetensors` import → a Q8_0 GGUF, on the phone, as it is imported ([CustomModels.importDit]
 * with [SafetensorsGguf.convertStream]). A tester's fp16 checkpoint (MiaoMiao Harem MOSS, CivitAI 3015023: 9.47 GB fp16,
 * or 5.04 GB int8 ConvRot, which the engine cannot run) was reaped on a 12 GB S25 at its first render and filled a phone
 * with 1 GB free (Saved #7118/#7119, 2026-10-23). As Q8_0 it is ~5 GB: what an fp8 MOSS import costs, and it renders.
 *
 * ⚠ The layout is molbal's Turbo GGUF's, which the engine already loads (`general.architecture = moss_image`, the
 * fused `proj.qkv` names kept as they are), read off the hosted file 2026-10-23. ⚠ His keeps the non-block weights BF16;
 * the writer makes BF16 only from a BF16 source, so here they are Q8_0, and the three smallest (the time and video
 * embedders, the final linear) F32, exact.
 */
object MossGguf {
    internal val rule = object : SafetensorsGguf.Rule {
        override val prefixes = listOf("model.diffusion_model.")
        override val kv = listOf("general.architecture" to "moss_image")
        override val kvU32 = listOf("general.quantization_version" to 2, "general.file_type" to 7)
        override fun check(names: List<String>) {
            if (names.none { it.startsWith("shared_block_modulation.") } || names.none { it.startsWith("video_embedder.") }) {
                throw IOException("not a MOSS-Image DiT")
            }
        }
        override fun type(name: String, outShape: LongArray, dtype: String): Int =
            if (outShape.size == 1 || outShape.last() % 32 != 0L || F32_PREFIXES.any { name.startsWith(it) }) SafetensorsGguf.F32
            else SafetensorsGguf.Q8_0
    }

    private val F32_PREFIXES = listOf("time_embedding.", "video_embedder.", "final_linear_video.")

    /**
     * ⭐ Whether an import's header ([CustomModels.DitHead.json]) holds 16-bit or 32-bit block weights: those convert;
     * fp8 (already ~5 GB) and int8 (refused before this) do not.
     */
    fun converts(headerJson: String): Boolean = runCatching {
        val h = org.json.JSONObject(headerJson)
        h.keys().asSequence().filter { it.contains("layers.") && it.endsWith(".weight") }.any { k ->
            val t = h.getJSONObject(k)
            t.getJSONArray("shape").length() == 2 && t.getString("dtype") in setOf("F16", "BF16", "F32")
        }
    }.getOrDefault(false)
}
