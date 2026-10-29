package com.abrah.nightmare

import java.io.File
import java.io.IOException

/**
 * ⭐⭐ NSFW-API's NSFW_Wan_1.3b (a Wan 2.1 T2V 1.3B fine-tune, `exp_e14`, BF16 `.safetensors`) → the GGUF the
 * DiT engine runs. Nothing re-hosted: the phone downloads NSFW-API's file and converts it once, like [IrisGguf].
 *
 * ⭐ Q8_0 for every matrix whose rows are a multiple of 32 (all 306 of the 1.3B's: 1536, 8960, 4096 wide); F32
 * for the rest: 1-D norms and biases, the per-block `modulation` [1, 6, 1536] the engine adds to F32 time
 * embeddings, and the 5-D `patch_embedding` [1536, 16, 1, 2, 2] (its last dim is 2), which the Wan 5B GGUFs
 * carry as F32 too.
 */
object WanGguf {
    /** NSFW-API's `wan_1.3B_exp_e14.safetensors`, converted (computed from its header: 306 Q8_0 + 519 F32). */
    const val NSFW13_BYTES = 1_511_714_912L

    internal val rule = object : SafetensorsGguf.Rule {
        override val prefixes = listOf("model.diffusion_model.")

        override fun check(names: List<String>) {
            if ("patch_embedding.weight" !in names || "blocks.0.self_attn.q.weight" !in names || "text_embedding.0.weight" !in names) {
                throw IOException("not a Wan DiT (no patch_embedding / blocks.0.self_attn / text_embedding)")
            }
            // ⚠ An image to video file carries the CLIP image projection; this model is text to video only.
            if (names.any { it.startsWith("img_emb.") }) throw IOException("a Wan image to video DiT, not text to video")
        }

        override fun type(name: String, outShape: LongArray, dtype: String): Int =
            if (outShape.size == 2 && outShape.last() % 32 == 0L) SafetensorsGguf.Q8_0 else SafetensorsGguf.F32
    }

    val converter = Converter { src, dst, onProgress, isCancelled -> convert(src, dst, onProgress, isCancelled) }

    fun convert(src: File, dst: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) =
        SafetensorsGguf.convert(src, dst, rule, onProgress, isCancelled)
}
