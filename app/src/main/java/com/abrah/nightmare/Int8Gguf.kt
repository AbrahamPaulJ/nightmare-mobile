package com.abrah.nightmare

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ A ComfyUI int8 DiT `.safetensors` (`int8_tensorwise` / `int8_rowwise`, with or without ConvRot) → a Q8_0 GGUF,
 * on the phone, as it is imported ([CustomModels.importDit] with [SafetensorsGguf.convertStream]). GitHub #71; MOSS,
 * Krea 2, Fibo and Qwen 2.1 uploads come int8-only (MatlowAI's DeBand, toxicdog's MOSS Turbo, NidAll's Fibo).
 *
 * ⚠⚠ Why convert: the engine reads the format, but every int8 matmul in our ggml revision aborts
 * (`ggml_ext_linear_i8_tensorwise`), and the NPU has no int8 kernels; Q8_0 runs on HTP. Every other tensor keeps its
 * type and name, so the engine sees what the safetensors would have given it, less the scales.
 *
 * The format (upstream sd.cpp `docs/int8_convrot.md`): `<m>.weight` I8, `<m>.weight_scale` one F32 per output row
 * (or one for the tensor), the settings in a `<m>.comfy_quant` U8 JSON tensor OR in the header's
 * `__metadata__._quantization_metadata.layers` (both are uploaded). ConvRot stores `W R`, `R` the normalized Kronecker
 * power of `H4` over each group of G inputs; `R` is symmetric and orthogonal, so `W = (W R) R`: [hadamard] per group.
 * ✅ Measured on MOSS SFT `layers.0.proj.q` against OpenMOSS's bf16 (2026-10-27): 0.85% relative error unrotated,
 * 1.03% as Q8_0 (a Q8_0 of the original: 0.58%); without the inverse rotation, 137%.
 *
 * ⚠ The scales can sit before or after their weight in the file (both seen), and a stream reads once, so [scan]
 * reads them in a first pass that skips everything else.
 */
object Int8Gguf {
    /** One int8 module: its scales (one per output row, or one for all) and its ConvRot group, 0 for none. */
    internal class Module(val scale: FloatArray, val group: Int)

    private val INT8_WEIGHT = Regex("\"[^\"]*weight\"\\s*:\\s*\\{\\s*\"dtype\"\\s*:\\s*\"I8\"")

    /** ⭐ Whether an import's header holds int8 weights this converts (a safetensors; a ComfyUI GGUF's I8 is refused). */
    fun converts(head: CustomModels.DitHead): Boolean = !head.gguf && INT8_WEIGHT.containsMatchIn(head.json)

    /** ⭐ The whole import: [scan], then the one-pass write. Returns the bytes written. */
    fun convertStream(
        open: () -> InputStream,
        dst: java.io.File,
        family: Family?,
        onProgress: (String, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val modules = scan(open, { done, total -> onProgress("reading int8 scales", done, total) }, isCancelled)
        return SafetensorsGguf.convertStream(open, dst, rule(modules, family), { done, total -> onProgress("converting int8 to Q8_0", done, total) }, isCancelled)
    }

    private class Entry(val name: String, val dtype: String, val shape: LongArray, val begin: Long, val end: Long)

    /** ⭐ Pass one: every int8 module's scales and settings, by module name (the weight's, less `.weight`). */
    internal fun scan(open: () -> InputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): Map<String, Module> =
        DataInputStream(java.io.BufferedInputStream(open(), 1 shl 20)).use { input ->
            val headerLen = ByteArray(8).also { input.readFully(it) }.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long }
            if (headerLen <= 0 || headerLen > 64L shl 20) throw IOException("not a safetensors file")
            val header = JSONObject(String(ByteArray(headerLen.toInt()).also { input.readFully(it) }, Charsets.UTF_8))
            val layers = header.optJSONObject("__metadata__")?.optString("_quantization_metadata")?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(it).optJSONObject("layers") }.getOrNull() }
            val entries = header.keys().asSequence().filter { it != "__metadata__" }.associateWith { k ->
                val t = header.getJSONObject(k)
                val off = t.getJSONArray("data_offsets")
                Entry(k, t.getString("dtype"), t.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }, off.getLong(0), off.getLong(1))
            }
            val int8 = entries.values.filter { it.dtype == "I8" && it.name.endsWith(".weight") }.map { it.name.removeSuffix(".weight") }
            val wanted = int8.flatMap { m -> listOfNotNull(entries["$m.weight_scale"], entries["$m.comfy_quant"]) }.sortedBy { it.begin }
            val bytes = HashMap<String, ByteArray>()
            val last = wanted.lastOrNull()?.end ?: 0L
            var at = 0L
            for (e in wanted) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                if (e.begin < at) throw IOException("the file's tensors overlap")
                var skip = e.begin - at
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) throw IOException("the file ended early")
                    skip -= n
                }
                val b = ByteArray((e.end - e.begin).toInt())
                input.readFully(b)
                bytes[e.name] = b
                at = e.end
                onProgress(at, last)
            }
            int8.associateWith { m -> module(m, entries, bytes, layers) }
        }

    private fun module(m: String, entries: Map<String, Entry>, bytes: Map<String, ByteArray>, layers: JSONObject?): Module {
        val weight = entries.getValue("$m.weight")
        if (weight.shape.size != 2) throw IOException("$m.weight is int8 but not a matrix")
        val config = bytes["$m.comfy_quant"]?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
            ?: layers?.let { l -> PREFIXES.firstNotNullOfOrNull { p -> if (m.startsWith(p)) l.optJSONObject(m.removePrefix(p)) else null } ?: l.optJSONObject(m) }
        val format = config?.optString("format").orEmpty()
        if (format.isNotEmpty() && format !in FORMATS) throw IOException("$m is stored as $format, which the import does not convert")
        val group = if (config?.optBoolean("convrot") == true) config.optInt("convrot_groupsize", 0) else 0
        if (config?.optBoolean("convrot") == true && (!powerOfFour(group) || weight.shape[1] % group != 0L)) {
            throw IOException("$m has a ConvRot group of $group, which does not fit its ${weight.shape[1]} inputs")
        }
        val s = entries["$m.weight_scale"] ?: throw IOException("$m.weight is int8 with no weight_scale")
        val raw = bytes.getValue(s.name)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val scale = when (s.dtype) {
            "F32" -> FloatArray(raw.size / 4) { bb.getFloat(it * 4) }
            "BF16" -> FloatArray(raw.size / 2) { java.lang.Float.intBitsToFloat((bb.getShort(it * 2).toInt() and 0xFFFF) shl 16) }
            "F16" -> FloatArray(raw.size / 2) { TemplateLora.halfToFloat(bb.getShort(it * 2)) }
            else -> throw IOException("${s.name} is ${s.dtype}")
        }
        if (scale.size != 1 && scale.size.toLong() != weight.shape[0]) throw IOException("${s.name} has ${scale.size} scales for ${weight.shape[0]} rows")
        return Module(scale, group)
    }

    private val FORMATS = setOf("int8_tensorwise", "int8_rowwise")
    private val PREFIXES = listOf("model.diffusion_model.", "diffusion_model.")

    private fun powerOfFour(g: Int): Boolean {
        if (g < 4) return false
        var r = g
        while (r % 4 == 0) r /= 4
        return r == 1
    }

    /**
     * ⭐ The writer's rule: int8 weights to Q8_0 (F32 where [GgufWiden] says the engine reads them off the matmul path,
     * or a row is not a multiple of 32), BF16 / F16 / F32 kept, and the scales and settings dropped: a `weight_scale`
     * left in would be multiplied in again (`Linear` in `ggml_block.hpp` applies one to any weight type).
     */
    internal fun rule(modules: Map<String, Module>, family: Family?) = object : SafetensorsGguf.Rule {
        override val kvU32 = listOf("general.quantization_version" to 2, "general.file_type" to 7)
        override fun check(names: List<String>) {}
        override fun keep(name: String): Boolean = DROPPED.none { name.endsWith(it) && name.removeSuffix(it) in modules }
        override fun type(name: String, outShape: LongArray, dtype: String): Int = when (dtype) {
            "I8" -> {
                if (name.removeSuffix(".weight") !in modules) throw IOException("$name is int8 with no scale")
                if (outShape.size == 2 && outShape.last() % 32 == 0L && !GgufWiden.widens(family, name, "Q8_0", outShape.reversedArray())) SafetensorsGguf.Q8_0
                else SafetensorsGguf.F32
            }
            "BF16" -> SafetensorsGguf.BF16
            "F16" -> SafetensorsGguf.F16
            "F32" -> SafetensorsGguf.F32
            else -> throw IOException("$name is $dtype, which the int8 import does not convert")
        }
        override fun decoded(name: String, firstRow: Long, x: FloatArray, rows: Int, rowLen: Int, stride: Int) {
            if (!name.endsWith(".weight")) return
            val m = modules[name.removeSuffix(".weight")] ?: return
            for (i in 0 until rows) {
                val off = i * stride
                if (m.group > 0) for (g in 0 until rowLen step m.group) hadamard(x, off + g, m.group)
                val s = if (m.scale.size == 1) m.scale[0] else m.scale[(firstRow + i).toInt()]
                for (j in off until off + rowLen) x[j] *= s
            }
        }
    }

    private val DROPPED = listOf(".weight_scale", ".comfy_quant", ".input_scale")

    /**
     * ⭐ In place: [x] from [off], [g] long (a power of four), times the normalized Kronecker power of
     * `H4 = [[1,1,1,-1],[1,1,-1,1],[1,-1,1,1],[-1,1,1,1]] / 2`, one base-4 digit of the index at a time.
     */
    internal fun hadamard(x: FloatArray, off: Int, g: Int) {
        var h = 1
        while (h < g) {
            var i = off
            while (i < off + g) {
                for (j in i until i + h) {
                    val a = x[j]; val b = x[j + h]; val c = x[j + 2 * h]; val d = x[j + 3 * h]
                    x[j] = (a + b + c - d) * 0.5f
                    x[j + h] = (a + b - c + d) * 0.5f
                    x[j + 2 * h] = (a - b + c + d) * 0.5f
                    x[j + 3 * h] = (-a + b + c + d) * 0.5f
                }
                i += 4 * h
            }
            h *= 4
        }
    }
}
