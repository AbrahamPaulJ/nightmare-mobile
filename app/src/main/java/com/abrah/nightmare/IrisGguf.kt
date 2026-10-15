package com.abrah.nightmare

import java.io.File
import java.io.IOException

/**
 * ⭐⭐ Iris 3B Turbo's official BF16 `.safetensors` (speridlabs, Apache 2.0) → the GGUF the DiT
 * engine runs (`docs/IRIS.md`). Nothing re-hosted: the phone downloads speridlabs' file and
 * converts it once, like [AnimaGguf].
 *
 * ⭐ Q8_0 for every matrix whose rows are a multiple of 32; BF16 kept for 1-D tensors, the
 * timestep and text embedders, the final layer and the text position table — the tensors
 * `sd-cli convert --type q8_0` left unquantized in the file the first renders were checked on.
 *
 * ⭐⭐ The trunk SwiGLU hidden size 6826 is zero-padded to 6912 ([MLP_MULTIPLE], ComfyUI-Iris's
 * `pad_mlp`): exact, since silu(0) * 0 = 0, and it turns all 32 trunk `w2` matrices from BF16 into
 * Q8_0. ⚠ The engine reads the hidden size off `w1`'s rows, so a padded file needs no flag.
 */
object IrisGguf {
    /** speridlabs' `turbo/model.safetensors`, converted. */
    const val TURBO_BYTES = 3_442_564_640L

    private const val MLP_MULTIPLE = 256L
    /**
     * ⚠ The text refiner's two MLPs too: the engine reads ONE hidden size, off the trunk's `w1`, for
     * both (`iris.hpp`), and refused a file with the trunk padded alone ("wrong shape … expected
     * [2560, 6912]"). ComfyUI sizes the refiner separately, which is why its `pad_mlp` does not.
     */
    private val MLP = Regex("""^(?:blocks\.\d+\.mlp(?:_[xy])?|y_embedder\.refiner\.blocks\.\d+\.mlp)\.(w1|w2|w3)\.weight$""")
    private val KEEP = listOf("t_embedder.", "y_embedder.", "final_layer.", "y_pos_embedding")

    internal val rule = object : SafetensorsGguf.Rule {
        override fun check(names: List<String>) {
            if ("y_embedder.layer_pool.weight" !in names || "pixel_blocks.0.compress.weight" !in names) {
                throw IOException("not an Iris-3B DiT (no y_embedder.layer_pool / pixel_blocks)")
            }
        }

        override fun outShape(name: String, shape: LongArray): LongArray {
            val m = MLP.matchEntire(name) ?: return shape
            val axis = if (m.groupValues[1] == "w2") 1 else 0
            return shape.copyOf().also { it[axis] = (it[axis] + MLP_MULTIPLE - 1) / MLP_MULTIPLE * MLP_MULTIPLE }
        }

        override fun type(name: String, outShape: LongArray, dtype: String): Int = when {
            outShape.size == 1 || KEEP.any { name.startsWith(it) } || outShape.last() % 32 != 0L ->
                if (dtype == "BF16") SafetensorsGguf.BF16 else SafetensorsGguf.F32
            else -> SafetensorsGguf.Q8_0
        }
    }

    val converter = Converter { src, dst, onProgress, isCancelled -> convert(src, dst, onProgress, isCancelled) }

    fun convert(src: File, dst: File, onProgress: (Long, Long) -> Unit, isCancelled: () -> Boolean) =
        SafetensorsGguf.convert(src, dst, rule, onProgress, isCancelled)
}
