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
    internal val rule = object : SafetensorsGguf.Rule() {
        override val prefixes = listOf("model.diffusion_model.")
        override val kv = listOf("general.architecture" to "moss_image")
        override val kvU32 = listOf("general.quantization_version" to 2, "general.file_type" to 7)
        // ⚠⚠ A kept scale must be the 1-D tensor the engine's `Linear` declares, which multiplies its output by it on any
        // weight type (`ggml_block.hpp`). A tester's Refined Realism bf16 V2 has them as `[out, 1]`, so its converted GGUF
        // never started: `weight_scale has wrong shape in model metadata: got [1, 3072, 1, 1], expected [3072, 1, 1, 1]`
        // (Saved #7903, 1.6.281). Only the shape changes; the values and what the engine does with them do not.
        override fun sourceShape(name: String, shape: LongArray): LongArray =
            if (name.endsWith(".weight_scale")) longArrayOf(shape.fold(1L) { a, b -> a * b }) else shape
        // ⚠ U8 JSON settings, which the writer has no type for: `unsupported dtype U8` (Saved #7822, #7852).
        override fun keep(name: String): Boolean = !name.endsWith(".comfy_quant")
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
     * ⚠⚠ ANY fp8 weight rules it out, not only all of them: ComfyUI's new fp8-scaled files keep half their block weights
     * BF16 beside `F8_E4M3` ones with a scalar scale and `comfy_quant` (Realism By Stable Yogi Moss v1.5 fp8, 168 of each,
     * read off CivitAI 2026-11-09), so `any` sent them here and they failed at `unsupported dtype U8` (Saved #7822, #7852).
     * The writer takes no fp8, and the engine reads such a file as it is, settings and all (`safetensors_io.cpp`).
     */
    fun converts(headerJson: String): Boolean = runCatching {
        val h = org.json.JSONObject(headerJson)
        val weights = h.keys().asSequence().filter { it.contains("layers.") && it.endsWith(".weight") }.map { h.getJSONObject(it) }.toList()
        weights.none { it.getString("dtype").startsWith("F8_") } &&
            weights.any { it.getJSONArray("shape").length() == 2 && it.getString("dtype") in setOf("F16", "BF16", "F32") }
    }.getOrDefault(false)
}
