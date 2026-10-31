package com.abrah.nightmare

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ⭐ A LoRA with integer tensors aborted the DiT engine (2026-10-13); [LoraClean] makes them F32. */
@RunWith(RobolectricTestRunner::class)
class LoraCleanTest {

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)

    /** A safetensors file: [tensors] as (name, dtype, bytes), packed in order; the ones in [matrices] 2 rows high. */
    private fun write(f: File, tensors: List<Triple<String, String, ByteArray>>, matrices: Set<String> = emptySet()) {
        val h = JSONObject()
        h.put("__metadata__", JSONObject().put("ss_network_dim", "4"))
        var at = 0L
        for ((name, dtype, bytes) in tensors) {
            val n = bytes.size / width(dtype)
            val shape = if (name in matrices) org.json.JSONArray().put(2).put(n / 2) else org.json.JSONArray().put(n)
            h.put(name, JSONObject().put("dtype", dtype).put("shape", shape)
                .put("data_offsets", org.json.JSONArray().put(at).put(at + bytes.size)))
            at += bytes.size
        }
        val json = h.toString().toByteArray()
        f.outputStream().use { o ->
            o.write(le(8).putLong(json.size.toLong()).array())
            o.write(json)
            tensors.forEach { o.write(it.third) }
        }
    }

    private fun width(d: String) = when (d) { "F16", "BF16", "I16" -> 2; "I64" -> 8; else -> 4 }

    private fun read(f: File): Pair<JSONObject, ByteArray> {
        val all = f.readBytes()
        val len = ByteBuffer.wrap(all, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long.toInt()
        return JSONObject(String(all, 8, len)) to all.copyOfRange(8 + len, all.size)
    }

    @Test
    fun integerTensorsBecomeF32AndFloatsAreUntouched() {
        val f = File.createTempFile("lora", ".safetensors")
        val weights = ByteArray(16) { it.toByte() }
        write(f, listOf(
            Triple("a.lora_down.weight", "F16", weights),
            Triple("a.alpha", "I32", le(4).putInt(16).array()),
            Triple("b.alpha", "I64", le(8).putLong(-3).array()),
        ))
        assertTrue(LoraClean.ensure(f))
        val (h, data) = read(f)
        assertEquals("F32", h.getJSONObject("a.alpha").getString("dtype"))
        assertEquals("F32", h.getJSONObject("b.alpha").getString("dtype"))
        assertEquals("4", h.getJSONObject("__metadata__").getString("ss_network_dim"))
        val w = h.getJSONObject("a.lora_down.weight").getJSONArray("data_offsets")
        assertArrayEquals(weights, data.copyOfRange(w.getInt(0), w.getInt(1)))
        fun f32(name: String) = h.getJSONObject(name).getJSONArray("data_offsets").getInt(0)
            .let { ByteBuffer.wrap(data, it, 4).order(ByteOrder.LITTLE_ENDIAN).float }
        assertEquals(16f, f32("a.alpha"))
        assertEquals(-3f, f32("b.alpha"))
        // ⚠ The second pass finds nothing to do.
        assertFalse(LoraClean.ensure(f))
        f.delete()
    }

    @Test
    fun anAllFloatLoraIsLeftAlone() {
        val f = File.createTempFile("lora", ".safetensors")
        write(f, listOf(Triple("a.lora_up.weight", "BF16", ByteArray(8))))
        val before = f.readBytes()
        assertFalse(LoraClean.ensure(f))
        assertArrayEquals(before, f.readBytes())
        f.delete()
    }

    /** ⭐ The video nodes' rewrite: an F32 LoRA ran MiniMax H3 7x slower than the same one in BF16 (2026-10-31). */
    @Test
    fun f32MatricesBecomeBf16OnlyWhenAskedAndAlphasStay() {
        val f = File.createTempFile("lora", ".safetensors")
        val floats = floatArrayOf(1f, -2.5f, 3.1415927f, 1e-3f)
        val m = le(16).apply { floats.forEach { putFloat(it) } }.array()
        write(f, listOf(Triple("a.lora_down.weight", "F32", m), Triple("a.alpha", "F32", le(4).putFloat(32f).array())), setOf("a.lora_down.weight"))
        assertFalse("the image nodes do not ask", LoraClean.ensure(f))
        assertTrue(LoraClean.ensure(f, bf16 = true))
        val (h, data) = read(f)
        assertEquals("BF16", h.getJSONObject("a.lora_down.weight").getString("dtype"))
        assertEquals("[2,2]", h.getJSONObject("a.lora_down.weight").getJSONArray("shape").toString())
        assertEquals("a 1-D alpha keeps F32", "F32", h.getJSONObject("a.alpha").getString("dtype"))
        val w = h.getJSONObject("a.lora_down.weight").getJSONArray("data_offsets")
        val got = ByteBuffer.wrap(data, w.getInt(0), 8).order(ByteOrder.LITTLE_ENDIAN).let { b ->
            FloatArray(4) { java.lang.Float.intBitsToFloat((b.short.toInt() and 0xFFFF) shl 16) }
        }
        floats.zip(got.toList()).forEach { (a, b) -> assertEquals(a, b, kotlin.math.abs(a) / 128f) }
        val a = h.getJSONObject("a.alpha").getJSONArray("data_offsets").getInt(0)
        assertEquals(32f, ByteBuffer.wrap(data, a, 4).order(ByteOrder.LITTLE_ENDIAN).float)
        assertFalse("the second pass finds nothing to do", LoraClean.ensure(f, bf16 = true))
        f.delete()
    }
}
