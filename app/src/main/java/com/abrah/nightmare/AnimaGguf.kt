package com.abrah.nightmare

import java.io.File
import java.io.IOException

/**
 * ⭐⭐ Anima's official BF16 `.safetensors` DiT → the Q8_0 GGUF the DiT engine runs
 * fastest (`docs/ANIMA-GGML.md` §4): 56 s against 67 s at 1024², and 2.2 GB of
 * NPU memory against 4.0. The layout is Bedovyy's (`Bedovyy/Anima-GGUF`), which
 * the engine already loads: names lose the ComfyUI `model.diffusion_model.` (or
 * `net.`) prefix, dims are ggml-order, kv `general.architecture = cosmos`.
 *
 * ⚠ The type rule reproduces his file tensor for tensor (checked on all 685):
 * F32 for 1-D tensors, `final_layer.*`, `t_embedder.*` and any row not a multiple
 * of 32; Q8_0 for the rest. The official turbo 1.1 converts to exactly
 * [TURBO_11_BYTES] — the size the catalogue checks.
 */
object AnimaGguf {
    /** `anima-turbo-v1.1.safetensors` converted, and Bedovyy's Q8_0 — the same size. */
    const val TURBO_11_BYTES = 2_276_712_576L

    /**
     * ⭐ Gazingstars123's `Anima-2.9B-preview-v1.safetensors` converted (40 blocks, `net.` names, its 3 `pos_embedder.*`
     * dropped): measured on the PC by [SafetensorsGgufTest] and equal to the file that rendered clean on the S25, 1.6.186.
     */
    const val ANIMA_29_PREVIEW_BYTES = 3_159_131_808L

    /** ⭐ The writer is [SafetensorsGguf]'s; this is only Anima's half of it. */
    internal val rule = object : SafetensorsGguf.Rule() {
        override val prefixes = listOf("model.diffusion_model.", "net.")
        override val kv = listOf("general.architecture" to "cosmos")
        // ⚠ Bedovyy's file has these too, and TURBO_11_BYTES counts them.
        override val kvU32 = listOf("general.quantization_version" to 2, "general.file_type" to 7)
        // ⚠ 2.9B's RoPE tables (`dim_spatial_range`, `dim_temporal_range`, `seq`): the 2B and Bedovyy's files have none,
        // and the engine computes them.
        override fun keep(name: String) = !name.startsWith("pos_embedder.")
        override fun check(names: List<String>) {
            if (names.none { it.startsWith("llm_adapter.") }) throw IOException("not an Anima DiT (no llm_adapter)")
        }
        override fun type(name: String, outShape: LongArray, dtype: String): Int =
            if (outShape.size == 1 || name.startsWith("final_layer.") || name.startsWith("t_embedder.") ||
                outShape.last() % 32 != 0L) SafetensorsGguf.F32 else SafetensorsGguf.Q8_0
    }

    /**
     * ⭐ GitHub #146: whether an imported Anima `.safetensors` ([CustomModels.DitHead.json]) converts to Q8_0 as it is
     * read, as a 16-bit MOSS import does ([MossGguf.converts]): the built-ins' own format, about half the size, and the
     * reporter's int8 imports (which became Q8_0) ran ~50 % faster on an 8 Gen 3 than bf16. Every block weight must be
     * 16- or 32-bit (fp8 and int8 have their own paths), and the file must be one [rule] takes (an `llm_adapter`).
     */
    fun converts(headerJson: String): Boolean = runCatching {
        val h = org.json.JSONObject(headerJson)
        val names = h.keys().asSequence().filter { it != "__metadata__" }.toList()
        val weights = names.filter { it.contains("blocks.") && it.endsWith(".weight") }.map { h.getJSONObject(it) }
        names.any { n -> rule.prefixes.fold(n) { s, p -> s.removePrefix(p) }.startsWith("llm_adapter.") } &&
            weights.isNotEmpty() && weights.all { it.getString("dtype") in SIXTEEN_OR_32 } &&
            weights.any { it.getJSONArray("shape").length() == 2 }
    }.getOrDefault(false)

    private val SIXTEEN_OR_32 = setOf("F16", "BF16", "F32")

    val converter = Converter { src, dst, onProgress, isCancelled -> convert(src, dst, onProgress, isCancelled) }

    fun convert(src: File, dst: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) =
        SafetensorsGguf.convert(src, dst, rule, onProgress, isCancelled)
}
