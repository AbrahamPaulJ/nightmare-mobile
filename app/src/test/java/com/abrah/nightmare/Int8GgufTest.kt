package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ⭐ [Int8Gguf]: ComfyUI int8 (ConvRot or not) to a Q8_0 GGUF. The rotation against a real MOSS row and its bf16
 * original, and miniature files in both layouts uploads use (settings in `comfy_quant` tensors or in the header,
 * scales before or after their weight).
 */
@RunWith(RobolectricTestRunner::class)
class Int8GgufTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** ⭐ The butterfly is the Kronecker power of H4 / 2, symmetric and orthogonal: twice is the identity. */
    @Test fun hadamardIsTheKroneckerPowerAndItsOwnInverse() {
        val h4 = arrayOf(floatArrayOf(1f, 1f, 1f, -1f), floatArrayOf(1f, 1f, -1f, 1f), floatArrayOf(1f, -1f, 1f, 1f), floatArrayOf(-1f, 1f, 1f, 1f))
        val g = 16
        val x = FloatArray(g) { (it * 37 % 11) - 5f }
        val want = FloatArray(g) { i -> (0 until g).sumOf { j -> (h4[i / 4][j / 4] * h4[i % 4][j % 4] / 4f * x[j]).toDouble() }.toFloat() }
        val y = x.copyOf().also { Int8Gguf.hadamard(it, 0, g) }
        for (i in 0 until g) assertEquals("element $i", want[i], y[i], 1e-5f)
        Int8Gguf.hadamard(y, 0, g)
        for (i in 0 until g) assertEquals(x[i], y[i], 1e-5f)
    }

    /**
     * ⭐ Real weights: row 0 of MOSS SFT's `layers.0.proj.q` from Abiray's int8 ConvRot upload, and the same row of
     * OpenMOSS's bf16 release (both Apache-2.0). Unrotated it is the original to int8's error; not unrotated it is noise.
     */
    @Test fun aRealMossRowUnrotatesToTheOriginal() {
        val bytes = javaClass.getResourceAsStream("/int8/moss_sft_q_row0.bin")!!.readBytes()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val scale = bb.getFloat(3072)
        val orig = FloatArray(3072) { java.lang.Float.intBitsToFloat((bb.getShort(3076 + it * 2).toInt() and 0xFFFF) shl 16) }
        val rule = Int8Gguf.rule(mapOf("layers.0.proj.q" to Int8Gguf.Module(floatArrayOf(scale), 256)), Family.MOSS)
        val x = FloatArray(3072) { bytes[it].toFloat() }
        val rotated = FloatArray(3072) { x[it] * scale }
        rule.decoded("layers.0.proj.q.weight", 0, x, 1, 3072, 3072)
        assertTrue("unrotated: ${rel(x, orig)}", rel(x, orig) < 0.02)
        assertTrue("still rotated: ${rel(rotated, orig)}", rel(rotated, orig) > 1.0)
        // ⭐ …and as the Q8_0 the GGUF holds.
        assertTrue(rel(q8(x), orig) < 0.02)
    }

    private fun rel(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var n = 0.0
        for (i in a.indices) { d += (a[i] - b[i]).toDouble() * (a[i] - b[i]); n += b[i].toDouble() * b[i] }
        return sqrt(d / n)
    }

    private fun q8(x: FloatArray): FloatArray = readQ8(SafetensorsGguf.q8_0(x), x.size)

    private fun readQ8(b: ByteArray, n: Int, at: Int = 0): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(n) { i ->
            val o = at + i / 32 * 34
            TemplateLora.halfToFloat(bb.getShort(o)) * b[o + 2 + i % 32]
        }
    }

    private class T(val name: String, val dtype: String, val shape: List<Long>, val data: ByteArray)

    /** A safetensors with the tensors' data in the order given, and [meta] as `__metadata__`. */
    private fun safetensors(tensors: List<T>, meta: JSONObject? = null): File {
        val header = JSONObject()
        meta?.let { header.put("__metadata__", it) }
        var off = 0L
        for (t in tensors) {
            header.put(t.name, JSONObject().put("dtype", t.dtype).put("shape", JSONArray(t.shape)).put("data_offsets", JSONArray(listOf(off, off + t.data.size))))
            off += t.data.size
        }
        val h = header.toString().toByteArray()
        return tmp.newFile().also { f ->
            f.outputStream().use { out ->
                out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(h.size.toLong()).array())
                out.write(h)
                for (t in tensors) out.write(t.data)
            }
        }
    }

    private fun f32(v: FloatArray) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()
    private fun bf16(v: FloatArray) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort((it.toRawBits() ushr 16).toShort()) } }.array()

    /** ComfyUI's quantization of [w] (rows × cols): rotated per [group] (0: not), one scale per row or one in all. */
    private fun quantize(w: FloatArray, rows: Int, cols: Int, group: Int, perRow: Boolean): Pair<ByteArray, FloatArray> {
        val r = w.copyOf()
        if (group > 0) for (i in 0 until rows) for (g in 0 until cols step group) Int8Gguf.hadamard(r, i * cols + g, group)
        val scales = if (perRow) FloatArray(rows) { i -> (0 until cols).maxOf { abs(r[i * cols + it]) } / 127f }
        else floatArrayOf(r.maxOf { abs(it) } / 127f)
        val q = ByteArray(rows * cols) { k -> Math.rint((r[k] / scales[if (perRow) k / cols else 0]).toDouble()).toInt().coerceIn(-127, 127).toByte() }
        return q to scales
    }

    private val rows = 8
    private val cols = 256
    private val w = FloatArray(rows * cols) { k -> (((k * 7919) % 2003) / 2003f - 0.5f) * 0.1f + if (k % 97 == 0) 0.4f else 0f }

    private fun tensors(group: Int, perRow: Boolean): Map<String, T> {
        val (q, s) = quantize(w, rows, cols, group, perRow)
        return listOf(
            T("blocks.0.attn.wq.weight", "I8", listOf(rows.toLong(), cols.toLong()), q),
            T("blocks.0.attn.wq.weight_scale", "F32", if (perRow) listOf(rows.toLong(), 1L) else emptyList(), f32(s)),
            T("blocks.0.attn.wq.bias", "BF16", listOf(rows.toLong()), bf16(FloatArray(rows) { it.toFloat() })),
            T("blocks.0.norm.weight", "F32", listOf(cols.toLong()), f32(FloatArray(cols) { 1f })),
            T("first.weight", "BF16", listOf(32L, 32L), bf16(FloatArray(1024) { (it % 5).toFloat() })),
        ).associateBy { it.name }
    }

    private fun convert(src: File): File {
        val dst = File(tmp.root, "dit.gguf")
        Int8Gguf.convertStream({ src.inputStream() }, dst, Family.KREA2, { _, _, _ -> }, { false })
        return dst
    }

    /** The GGUF's tensors: name to (type, data offset in the file); the writer's kv are strings and u32s only. */
    private fun table(f: File): Map<String, Pair<String, Int>> {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun str(): String = ByteArray(b.long.toInt()).also { b.get(it) }.toString(Charsets.UTF_8)
        b.position(8)
        val n = b.long.toInt()
        val kv = b.long.toInt()
        repeat(kv) { str(); if (b.int == 8) str() else b.int }
        val names = mutableListOf<Triple<String, Int, Long>>()
        repeat(n) {
            val name = str()
            repeat(b.int) { b.long }
            names += Triple(name, b.int, b.long)
        }
        val data = (b.position() + 31) / 32 * 32
        val types = mapOf(0 to "F32", 1 to "F16", 8 to "Q8_0", 30 to "BF16")
        return names.associate { (name, type, off) -> name to (types.getValue(type) to (data + off).toInt()) }
    }

    /** ⭐ Settings in `comfy_quant` tensors stored AFTER their weight, as Abiray's MOSS and the Krea 2 upload have them. */
    @Test fun comfyQuantTensorsAfterTheWeight() {
        val t = tensors(256, perRow = true)
        val cq = """{"format": "int8_rowwise", "per_row": true, "convrot": true, "convrot_groupsize": 256}""".toByteArray()
        val src = safetensors(listOf(t.getValue("blocks.0.attn.wq.weight"), t.getValue("blocks.0.attn.wq.bias"), t.getValue("first.weight"),
            t.getValue("blocks.0.norm.weight"), t.getValue("blocks.0.attn.wq.weight_scale"),
            T("blocks.0.attn.wq.comfy_quant", "U8", listOf(cq.size.toLong()), cq)))
        val dst = convert(src)
        assertTrue(GgufHeader.isComplete(dst))
        val tab = table(dst)
        // ⚠ The scale and settings are gone: a `weight_scale` left in would be multiplied in again by the engine.
        assertEquals(setOf("blocks.0.attn.wq.weight", "blocks.0.attn.wq.bias", "blocks.0.norm.weight", "first.weight"), tab.keys)
        assertEquals("Q8_0", tab.getValue("blocks.0.attn.wq.weight").first)
        assertEquals("BF16", tab.getValue("blocks.0.attn.wq.bias").first)
        assertEquals("F32", tab.getValue("blocks.0.norm.weight").first)
        val got = readQ8(dst.readBytes(), rows * cols, tab.getValue("blocks.0.attn.wq.weight").second)
        assertTrue("error ${rel(got, w)}", rel(got, w) < 0.02)
    }

    /** ⭐ Settings in the header's `_quantization_metadata` and scales before the weight (toxicdog's MOSS Turbo). */
    @Test fun headerMetadataAndScalesFirst() {
        val t = tensors(256, perRow = true)
        val meta = JSONObject().put("_quantization_metadata",
            """{"format_version": "1.0", "layers": {"blocks.0.attn.wq": {"format": "int8_tensorwise", "convrot": true, "convrot_groupsize": 256}}}""")
        val src = safetensors(listOf(t.getValue("blocks.0.attn.wq.weight_scale"), t.getValue("blocks.0.norm.weight"),
            t.getValue("blocks.0.attn.wq.weight"), t.getValue("blocks.0.attn.wq.bias"), t.getValue("first.weight")), meta)
        val dst = convert(src)
        val tab = table(dst)
        val got = readQ8(dst.readBytes(), rows * cols, tab.getValue("blocks.0.attn.wq.weight").second)
        assertTrue("error ${rel(got, w)}", rel(got, w) < 0.02)
    }

    /**
     * ⭐ Plain `int8_tensorwise`: no rotation, one scale for the tensor, no settings at all. Against the int8 values
     * themselves: one scale over these outliers costs int8 2% by itself; Q8_0's own requantization adds 0.8%.
     */
    @Test fun plainTensorwiseWithOneScale() {
        val t = tensors(0, perRow = false)
        val src = safetensors(t.values.toList())
        val dst = convert(src)
        val tab = table(dst)
        val got = readQ8(dst.readBytes(), rows * cols, tab.getValue("blocks.0.attn.wq.weight").second)
        val (q, s) = quantize(w, rows, cols, 0, false)
        val int8 = FloatArray(q.size) { q[it] * s[0] }
        assertTrue("error ${rel(got, int8)}", rel(got, int8) < 0.01)
    }

    /** ⚠ Unrotating a file that was not rotated would scramble it: the settings decide, so they must be read. */
    @Test fun aMissingScaleOrAWrongGroupIsRefused() {
        val t = tensors(256, perRow = true)
        try {
            convert(safetensors(listOf(t.getValue("blocks.0.attn.wq.weight"), t.getValue("first.weight"))))
            fail("converted int8 with no scale")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("weight_scale"))
        }
        val cq = """{"format": "int8_tensorwise", "convrot": true, "convrot_groupsize": 128}""".toByteArray()
        try {
            convert(safetensors(listOf(t.getValue("blocks.0.attn.wq.weight"), t.getValue("blocks.0.attn.wq.weight_scale"),
                T("blocks.0.attn.wq.comfy_quant", "U8", listOf(cq.size.toLong()), cq))))
            fail("converted a ConvRot group that is not a power of four")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("ConvRot group"))
        }
    }

    /** ⭐ FLUX.2 outside the blocks: the engine reads those off the matmul path ([GgufWiden]), so they become F32. */
    @Test fun flux2OutsideTheBlocksIsF32() {
        val (q, s) = quantize(w, rows, cols, 0, true)
        val src = safetensors(listOf(
            T("img_in.weight", "I8", listOf(rows.toLong(), cols.toLong()), q), T("img_in.weight_scale", "F32", listOf(rows.toLong(), 1L), f32(s)),
            T("double_blocks.0.img_attn.qkv.weight", "I8", listOf(rows.toLong(), cols.toLong()), q),
            T("double_blocks.0.img_attn.qkv.weight_scale", "F32", listOf(rows.toLong(), 1L), f32(s)),
        ))
        val dst = File(tmp.root, "flux.gguf")
        Int8Gguf.convertStream({ src.inputStream() }, dst, Family.FLUX2, { _, _, _ -> }, { false })
        val tab = table(dst)
        assertEquals("F32", tab.getValue("img_in.weight").first)
        assertEquals("Q8_0", tab.getValue("double_blocks.0.img_attn.qkv.weight").first)
    }

    /**
     * ⚠ Opt-in, on the PC: a REAL int8 ConvRot DiT through the phone's converter. Runs only when
     * `app/build/int8.safetensors` exists (a hard link to the download), and writes `app/build/int8.gguf`.
     */
    @Test fun theRealFileConverts() {
        val src = File("build/int8.safetensors")
        org.junit.Assume.assumeTrue(src.isFile)
        val dst = File("build/int8.gguf")
        val written = Int8Gguf.convertStream({ src.inputStream() }, dst, null, { _, _, _ -> }, { false })
        assertEquals(dst.length(), written)
        assertTrue(GgufHeader.isComplete(dst))
    }
}
