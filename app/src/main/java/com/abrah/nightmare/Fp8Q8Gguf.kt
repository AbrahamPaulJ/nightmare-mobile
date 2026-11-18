package com.abrah.nightmare

import org.json.JSONObject
import java.io.IOException

/**
 * ⭐⭐ A plain fp8 (`F8_E4M3`) Krea 2 `.safetensors` imported on a chip below v79 → a Q8_0 GGUF, as it is read.
 *
 * ⚠⚠ Why (Saved #8071, a vivo 8 Gen 3 = v75, 16 GB, 1.6.293): a 13.5 GB `krea2_turbo_anime_fp8_e4m3` failed at its
 * first block: `model manager cannot make enough memory available on HTP0: need 3047.72 MB device / 2816.00 MB budget`,
 * `krea2 segment 1/29 (krea2.blocks.0) failed during weight preparation`. `F8_E4M3` is native only on v79+; below it the
 * engine widens a block's fp8 weights as it prepares them, and Krea 2's 6144-wide block (~460 MB in fp8) outgrows the
 * budget. Q8_0 runs natively there at about the fp8 size (8.5 bits a weight). The user's call, 2026-11-14.
 *
 * ⭐ Everything but the fp8 tensors is copied as it is, names and all, so the engine sees the file it already loaded with
 * one dtype changed: a layer's `weight_scale` (or ComfyUI's `scale_weight`) multiplies the Linear's OUTPUT whatever the
 * weight type (`ggml_block.hpp`), so kept, it scales the Q8_0 weights exactly as it scaled the fp8 ones. Only its shape
 * is flattened to the 1-D the Linear declares ([MossGguf]'s `[out, 1]` trap; a scalar becomes `[1]`).
 * ⚠ Dropped: `comfy_quant` (U8 settings the writer has no type for; the engine reads them for `int8_tensorwise` only) and
 * the `scaled_fp8` marker (the engine ignores it). fp8 tensors off the matmul path become F32, as [Fp8Widen] makes them.
 */
object Fp8Q8Gguf {

    private const val F8 = "F8_E4M3"

    /**
     * The families it converts. ⭐ MOSS since item 86 (Saved #8177, the same vivo 8 Gen 3, 1.6.307): an fp8 Moody MOSS Mix
     * stayed a safetensors and aborted in its first block (`ggml-cpu/ops.cpp:571`), the CPU fallback for fp8 below v79.
     */
    private val FAMILIES = setOf(Family.KREA2, Family.MOSS)
    private const val NATIVE_FP8_ARCH = 79

    /**
     * ⭐ Whether this import converts: a Krea 2 safetensors with fp8 matmul weights, on a known chip below v79, that no
     * other converter takes (mxfp8 [MxFp8Gguf], int8 [Int8Gguf]) and that holds nothing the writer cannot read
     * (`F8_E5M2` goes through [Fp8Widen] as before).
     */
    fun converts(head: CustomModels.DitHead, family: Family?, caps: DeviceProbe.Caps): Boolean =
        !head.gguf && family in FAMILIES && caps.known && caps.qualcomm && caps.arch < NATIVE_FP8_ARCH &&
            !MxFp8Gguf.converts(head) && !Int8Gguf.converts(head) && plainFp8(head.json)

    /** ⭐ fp8 matmul weights, and every tensor's dtype one the writer reads (the dropped U8 settings aside). */
    internal fun plainFp8(headerJson: String): Boolean = runCatching {
        val h = JSONObject(headerJson)
        var fp8Matmul = false
        for (k in h.keys()) {
            if (k == "__metadata__" || k.endsWith(".comfy_quant")) continue
            val t = h.getJSONObject(k)
            val dtype = t.getString("dtype")
            if (dtype !in setOf(F8, "BF16", "F16", "F32")) return@runCatching false
            if (dtype == F8 && t.getJSONArray("shape").length() == 2) fp8Matmul = true
        }
        fp8Matmul
    }.getOrDefault(false)

    private fun isScale(name: String) = name.endsWith(".weight_scale") || name.endsWith(".scale_weight")

    internal fun rule(family: Family?): SafetensorsGguf.Rule = object : SafetensorsGguf.Rule() {
        // ⭐ A MOSS GGUF is the layout [MossGguf] writes: the prefix off, its architecture named.
        override val prefixes: List<String> get() = if (family == Family.MOSS) MossGguf.rule.prefixes else super.prefixes
        override val kv: List<Pair<String, String>> get() = if (family == Family.MOSS) MossGguf.rule.kv else super.kv
        override val kvU32: List<Pair<String, Int>> get() = listOf("general.quantization_version" to 2, "general.file_type" to 7)
        override fun keep(name: String): Boolean = !name.endsWith(".comfy_quant") && name != "scaled_fp8"
        override fun check(names: List<String>) { if (family == Family.MOSS) MossGguf.rule.check(names) }
        override fun sourceShape(name: String, shape: LongArray): LongArray =
            if (isScale(name) && shape.size != 1) longArrayOf(shape.fold(1L) { a, b -> a * b }) else shape
        override fun type(name: String, outShape: LongArray, dtype: String): Int = when (dtype) {
            F8 -> if (outShape.size == 2 && outShape.last() % 32 == 0L &&
                !(family == Family.MOSS && MossGguf.keepsF32(name)) &&
                !GgufWiden.widens(family, MxFp8Gguf.key(name), "Q8_0", outShape.reversedArray())
            ) SafetensorsGguf.Q8_0 else SafetensorsGguf.F32
            "BF16" -> SafetensorsGguf.BF16
            "F16" -> SafetensorsGguf.F16
            "F32" -> SafetensorsGguf.F32
            else -> throw IOException("$name is $dtype, which the fp8 import does not convert")
        }
    }
}
