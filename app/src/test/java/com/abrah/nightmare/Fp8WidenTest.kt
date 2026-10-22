package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ⭐ A Krea 2 merge with fp8 norm scales aborted the engine (2026-10-19); [Fp8Widen] makes those F32 at import. */
@RunWith(RobolectricTestRunner::class)
class Fp8WidenTest {

    private class T(val name: String, val dtype: String, val shape: List<Int>, val bytes: ByteArray)

    private fun file(tensors: List<T>): ByteArray {
        val h = JSONObject().put("__metadata__", JSONObject().put("format", "pt"))
        var at = 0L
        for (t in tensors) {
            h.put(t.name, JSONObject().put("dtype", t.dtype).put("shape", JSONArray(t.shape))
                .put("data_offsets", JSONArray().put(at).put(at + t.bytes.size)))
            at += t.bytes.size
        }
        val json = h.toString().toByteArray()
        return ByteArrayOutputStream().apply {
            write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(json.size.toLong()).array())
            write(json)
            tensors.forEach { write(it.bytes) }
        }.toByteArray()
    }

    private fun parse(b: ByteArray): Pair<JSONObject, ByteArray> {
        val n = ByteBuffer.wrap(b, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
        return JSONObject(String(b, 8, n, Charsets.UTF_8)) to b.copyOfRange(8 + n, b.size)
    }

    private fun data(h: JSONObject, body: ByteArray, name: String): ByteArray =
        h.getJSONObject(name).getJSONArray("data_offsets").let { body.copyOfRange(it.getInt(0), it.getInt(1)) }

    private fun floats(b: ByteArray): List<Float> {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return List(b.size / 4) { bb.float }
    }

    private fun copy(src: ByteArray): Pair<Int, ByteArray> {
        val out = ByteArrayOutputStream()
        val n = Fp8Widen.copy(src.inputStream(), out)
        return n to out.toByteArray()
    }

    @Test
    fun theNonMatmulFp8TensorsBecomeF32AndTheRestIsUntouched() {
        val matmul = ByteArray(64 * 32) { it.toByte() }
        val bias = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).putFloat(-2f).array()
        val src = file(listOf(
            T("blocks.0.attn.wq.weight", "F8_E4M3", listOf(64, 32), matmul),
            // 1.0, 2.0, -1.0, the smallest subnormal (2^-9), the largest finite (448)
            T("blocks.0.attn.qknorm.qnorm.scale", "F8_E4M3", listOf(5), byteArrayOf(0x38, 0x40, 0xB8.toByte(), 0x01, 0x7E)),
            T("first.bias", "F32", listOf(2), bias),
            T("last.modulation.lin", "F8_E4M3", listOf(2, 32), ByteArray(64) { 0x38 }),
            T("txtfusion.projector.weight", "F8_E4M3", listOf(1, 12), ByteArray(12) { 0x30 }),
            T("scaled_fp8", "F8_E4M3", listOf(2), byteArrayOf(0, 0)),
        ))
        val (n, out) = copy(src)
        assertEquals(3, n)
        val (h, body) = parse(out)
        assertEquals("pt", h.getJSONObject("__metadata__").getString("format"))
        assertEquals("F8_E4M3", h.getJSONObject("blocks.0.attn.wq.weight").getString("dtype"))
        assertArrayEquals(matmul, data(h, body, "blocks.0.attn.wq.weight"))
        assertEquals("F32", h.getJSONObject("blocks.0.attn.qknorm.qnorm.scale").getString("dtype"))
        assertEquals(listOf(1f, 2f, -1f, 0.001953125f, 448f), floats(data(h, body, "blocks.0.attn.qknorm.qnorm.scale")))
        assertArrayEquals(bias, data(h, body, "first.bias"))
        assertEquals(List(64) { 1f }, floats(data(h, body, "last.modulation.lin")))
        assertEquals(List(12) { 0.5f }, floats(data(h, body, "txtfusion.projector.weight")))
        assertEquals("F8_E4M3", h.getJSONObject("scaled_fp8").getString("dtype"))
        assertEquals(JSONArray(listOf(2, 32)).toString(), h.getJSONObject("last.modulation.lin").getJSONArray("shape").toString())
        // ⚠ The header is padded to 8, and the data ends exactly where the last offset says.
        assertEquals(0, (out.size - body.size - 8) % 8)
        val ends = h.keys().asSequence().filter { it != "__metadata__" }
            .map { h.getJSONObject(it).getJSONArray("data_offsets").getLong(1) }.max()
        assertEquals(body.size.toLong(), ends)
    }

    /** ⚠ A file that is already right (the built-in Klein's shape) goes across byte for byte. */
    @Test
    fun aWellMadeFp8FileIsCopiedUnchanged() {
        val src = file(listOf(
            T("double_blocks.0.img_attn.qkv.weight", "F8_E4M3", listOf(96, 32), ByteArray(96 * 32) { 7 }),
            T("double_blocks.0.img_attn.norm.key_norm.scale", "BF16", listOf(4), ByteArray(8) { 1 }),
            T("scaled_fp8", "F8_E4M3", listOf(2), byteArrayOf(0, 0)),
        ))
        val (n, out) = copy(src)
        assertEquals(0, n)
        assertArrayEquals(src, out)
    }

    @Test
    fun onlyMatmulShapesStayFp8() {
        assertFalse(Fp8Widen.widens("a.weight", "F8_E4M3", listOf(6144L, 6144L)))
        assertFalse(Fp8Widen.widens("first.weight", "F8_E4M3", listOf(6144L, 64L)))
        assertTrue(Fp8Widen.widens("mod.lin", "F8_E4M3", listOf(36864L)))
        assertTrue(Fp8Widen.widens("pad", "F8_E4M3", listOf(1L, 3840L)))
        assertFalse(Fp8Widen.widens("norm.scale", "BF16", listOf(128L)))
    }

    /** ⭐ A Klein 9B "pruned_fp8" all in E5M2 (1.6.214, the engine reads no E5M2): norms to F32, matmuls to E4M3, same size. */
    @Test
    fun e5m2NormsBecomeF32AndE5m2MatmulsBecomeE4m3() {
        // E5M2: 1.0 = 0x3C, 2.0 = 0x40, -1.0 = 0xBC, 1.25 = 0x3D, 2^-8 = 0x1C, 448 = 0x5F (exactly 448: 1.75 * 2^8)
        val matmulBytes = ByteArray(64 * 32) { listOf(0x3C, 0x40, 0xBC, 0x3D, 0x1C, 0x5F)[it % 6].toByte() }
        val src = file(listOf(
            T("double_blocks.0.img_attn.norm.key_norm.weight", "F8_E5M2", listOf(4), byteArrayOf(0x3C, 0x40, 0xBC.toByte(), 0x3D)),
            T("double_blocks.0.img_attn.qkv.weight", "F8_E5M2", listOf(64, 32), matmulBytes),
            // ⚠ A KJ file's marker in E5M2: the engine reads its dtype (and nothing else) at load.
            T("scaled_fp8", "F8_E5M2", listOf(2), byteArrayOf(0, 0)),
        ))
        val (n, out) = copy(src)
        assertEquals(3, n)
        assertEquals("F8_E4M3", parse(out).first.getJSONObject("scaled_fp8").getString("dtype"))
        val (h, body) = parse(out)
        assertEquals("F32", h.getJSONObject("double_blocks.0.img_attn.norm.key_norm.weight").getString("dtype"))
        assertEquals(listOf(1f, 2f, -1f, 1.25f), floats(data(h, body, "double_blocks.0.img_attn.norm.key_norm.weight")))
        assertEquals("F8_E4M3", h.getJSONObject("double_blocks.0.img_attn.qkv.weight").getString("dtype"))
        val q = data(h, body, "double_blocks.0.img_attn.qkv.weight")
        assertEquals(matmulBytes.size, q.size)
        // E4M3: 1.0 = 0x38, 2.0 = 0x40, -1.0 = 0xB8, 1.25 = 0x3A, 2^-8 = 0x02 (subnormal 2 * 2^-9), 448 = 0x7E
        assertEquals(listOf(0x38, 0x40, 0xB8, 0x3A, 0x02, 0x7E), q.take(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(1f, 2f, -1f, 1.25f, 1f / 256, 448f), floats(Fp8Widen.toF32(q, 6)))
    }

    /** ⭐ The table is right at its edges: saturation past 448, NaN kept, and every in-range code exact. */
    @Test
    fun e5m2ToE4m3IsExactInE4m3sNormalRange() {
        val e5 = (0 until 256).map { Fp8Widen.toF32(byteArrayOf(it.toByte()), 1, e5m2 = true).let { b -> ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float } }
        for (c in 0 until 256) {
            val v = e5[c]
            val back = Fp8Widen.toF32(byteArrayOf(Fp8Widen.E5M2_TO_E4M3[c]), 1).let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).float }
            val a = kotlin.math.abs(v)
            when {
                v.isNaN() -> assertTrue(back.isNaN())
                a >= 448f -> assertEquals(if (v < 0) -448f else 448f, back)
                a >= 1f / 64 -> assertEquals("code $c", v, back)
            }
        }
    }
}
