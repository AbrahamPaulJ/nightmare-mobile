package com.abrah.nightmare

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐⭐ A ComfyUI Kitchen `mxfp8` DiT `.safetensors` → a Q8_0 GGUF, as it is imported, as [Int8Gguf] does for int8.
 *
 * ⚠⚠ Why (a tester, `anima_turbov11_mxfp8`, an 8 Gen 3): `<m>.weight` is `F8_E4M3`, `<m>.weight_scale` is a `U8` grid of
 * one exponent per 32 weights. The engine keeps fp8 as it is and reads any `weight_scale` as a scale on the layer's OUTPUT,
 * so the file aborts in ggml-cpu's `dup` (`ops.cpp:571`).
 *
 * ⭐ The format, read off that file against circlestone's BF16 `anima-turbo-v1.1`, the same model (signs agree on every
 * weight; 100 % of the blocks of 3 tensors match, against 14-50 % in plain order; 2.5-2.7 % error, what fp8 can hold, and
 * Q8_0 adds at most 0.1 %):
 * - a weight is `e4m3(code) * 2^(s - 127)`, and the weights are in their ordinary order;
 * - ⚠ the SCALES are not: [unswizzle]. The first reading, in plain order, had 90 % error and would have rendered garbage.
 *
 * ⚠ A grid whose shape does not fill those tiles is refused: nothing has shown how such a one is laid out, and a wrong
 * reading renders noise instead of failing.
 */
object MxFp8Gguf {
    private const val F8 = "F8_E4M3"
    private const val TILE_ROWS = 128
    private const val TILE_COLS = 4
    private val PREFIXES = listOf("model.diffusion_model.", "diffusion_model.", "net.")

    // ⚠ The U8 is what tells it from an int8 file's F32 scales and from a `comfy_quant`, which is U8 under another name.
    private val MX_SCALE = Regex("\"[^\"]*\\.weight_scale\"\\s*:\\s*\\{[^{}]*\"dtype\"\\s*:\\s*\"U8\"")

    /** ⭐ Whether an import's header holds mxfp8 weights (a safetensors). */
    fun converts(head: CustomModels.DitHead): Boolean = !head.gguf && MX_SCALE.containsMatchIn(head.json)

    /** ⭐ The whole import: [scan], then the one-pass write. Returns the bytes written. */
    fun convertStream(
        open: () -> InputStream,
        dst: java.io.File,
        family: Family?,
        onProgress: (String, Long, Long) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val scales = scan(open, { done, total -> onProgress("reading mxfp8 scales", done, total) }, isCancelled)
        return SafetensorsGguf.convertStream(open, dst, rule(scales, family), { done, total -> onProgress("converting mxfp8 to Q8_0", done, total) }, isCancelled)
    }

    private fun key(name: String): String = PREFIXES.firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } ?: name

    /**
     * ⭐ The stored grid in the weights' order. Tiles of [TILE_ROWS] rows by [TILE_COLS] columns run across the columns, then
     * down; inside one, a flat position `p` is column `p % 4` of line `p / 4`, and a line's row has its index rotated:
     * `(line >> 2) | ((line & 3) << 5)`. Stored position 16 is row 1, position 4 is row 32.
     */
    internal fun unswizzle(raw: ByteArray, rows: Int, cols: Int): ByteArray {
        val tile = TILE_ROWS * TILE_COLS
        val wide = cols / TILE_COLS
        val out = ByteArray(raw.size)
        for (k in raw.indices) {
            val p = k % tile
            val t = k / tile
            val line = p / TILE_COLS
            val row = (t / wide) * TILE_ROWS + ((line shr 2) or ((line and 3) shl 5))
            val col = (t % wide) * TILE_COLS + p % TILE_COLS
            out[row * cols + col] = raw[k]
        }
        return out
    }

    /** One weight's grid, ready to read: [cols] exponents per row. */
    internal class Grid(val exponents: ByteArray, val cols: Int)

    private class Entry(val name: String, val dtype: String, val shape: LongArray, val begin: Long, val end: Long)

    /** ⭐ Pass one: every mxfp8 weight's grid, by name without its prefix. Skips everything else. */
    internal fun scan(open: () -> InputStream, onProgress: (Long, Long) -> Unit = { _, _ -> }, isCancelled: () -> Boolean = { false }): Map<String, Grid> =
        DataInputStream(java.io.BufferedInputStream(open(), 1 shl 20)).use { input ->
            val headerLen = ByteArray(8).also { input.readFully(it) }.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).long }
            if (headerLen <= 0 || headerLen > 64L shl 20) throw IOException("not a safetensors file")
            val header = JSONObject(String(ByteArray(headerLen.toInt()).also { input.readFully(it) }, Charsets.UTF_8))
            val entries = header.keys().asSequence().filter { it != "__metadata__" }.associateWith { k ->
                val t = header.getJSONObject(k)
                val off = t.getJSONArray("data_offsets")
                Entry(k, t.getString("dtype"), t.getJSONArray("shape").let { a -> LongArray(a.length()) { a.getLong(it) } }, off.getLong(0), off.getLong(1))
            }
            val wanted = entries.values.filter { it.name.endsWith(".weight_scale") && it.dtype == "U8" }.sortedBy { it.begin }
            val out = HashMap<String, Grid>()
            val last = wanted.lastOrNull()?.end ?: 0L
            var at = 0L
            for (s in wanted) {
                if (isCancelled()) throw ModelInstaller.Cancelled()
                val weightName = s.name.removeSuffix("_scale")
                val w = entries[weightName] ?: throw IOException("${s.name} has no weight")
                if (w.dtype != F8) throw IOException("$weightName is ${w.dtype}, which the mxfp8 import does not convert")
                if (w.shape.size != 2 || s.shape.size != 2 || s.shape[0] != w.shape[0] || w.shape[1] != s.shape[1] * 32 ||
                    s.shape[0] % TILE_ROWS != 0L || s.shape[1] % TILE_COLS != 0L
                ) throw IOException("$weightName ${w.shape.toList()} has its mxfp8 scales in a layout the import does not know (${s.shape.toList()})")
                if (s.begin < at) throw IOException("the file's tensors overlap")
                var skip = s.begin - at
                while (skip > 0) {
                    val n = input.skip(skip)
                    if (n <= 0) throw IOException("the file ended early")
                    skip -= n
                }
                val raw = ByteArray((s.end - s.begin).toInt())
                input.readFully(raw)
                at = s.end
                out[key(weightName)] = Grid(unswizzle(raw, s.shape[0].toInt(), s.shape[1].toInt()), s.shape[1].toInt())
                onProgress(at, last)
            }
            out
        }

    /**
     * ⭐ The writer's rule. ⭐ Anima takes [AnimaGguf.rule] whole, so an mxfp8 Anima comes out laid out as the built-in is;
     * another family as [Int8Gguf] writes it: fp8 weights to Q8_0 (F32 where [GgufWiden] says the engine reads them off
     * the matmul path), 16-bit kept. ⚠ Every scale is dropped: one left in would be read as a scale on the output.
     */
    internal fun rule(grids: Map<String, Grid>, family: Family?): SafetensorsGguf.Rule = object : SafetensorsGguf.Rule() {
        private val anima = family == Family.ANIMA_DIT
        override val prefixes: List<String> get() = if (anima) AnimaGguf.rule.prefixes else emptyList()
        override val kv: List<Pair<String, String>> get() = if (anima) AnimaGguf.rule.kv else emptyList()
        override val kvU32: List<Pair<String, Int>> get() = if (anima) AnimaGguf.rule.kvU32 else listOf("general.quantization_version" to 2, "general.file_type" to 7)
        override fun keep(name: String): Boolean = !name.endsWith(".weight_scale") && (!anima || AnimaGguf.rule.keep(name))
        override fun check(names: List<String>) { if (anima) AnimaGguf.rule.check(names) }
        override fun type(name: String, outShape: LongArray, dtype: String): Int = when {
            anima -> AnimaGguf.rule.type(name, outShape, dtype)
            dtype == F8 -> if (outShape.size == 2 && outShape.last() % 32 == 0L && !GgufWiden.widens(family, key(name), "Q8_0", outShape.reversedArray())) SafetensorsGguf.Q8_0 else SafetensorsGguf.F32
            dtype == "BF16" -> SafetensorsGguf.BF16
            dtype == "F16" -> SafetensorsGguf.F16
            dtype == "F32" -> SafetensorsGguf.F32
            else -> throw IOException("$name is $dtype, which the mxfp8 import does not convert")
        }
        override fun decoded(name: String, firstRow: Long, x: FloatArray, rows: Int, rowLen: Int, stride: Int) {
            val g = grids[key(name)] ?: return
            for (i in 0 until rows) {
                val off = i * stride
                val at = (firstRow + i).toInt() * g.cols
                for (b in 0 until g.cols) {
                    val mult = Math.scalb(1f, (g.exponents[at + b].toInt() and 0xFF) - 127)
                    val start = off + b * 32
                    for (j in start until start + 32) x[j] *= mult
                }
            }
        }
    }
}
