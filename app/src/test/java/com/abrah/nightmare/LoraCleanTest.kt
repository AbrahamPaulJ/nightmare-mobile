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

    /** A safetensors file: [tensors] as (name, dtype, shape, bytes), packed in order. */
    private fun write(f: File, tensors: List<Triple<String, String, ByteArray>>) {
        val h = JSONObject()
        h.put("__metadata__", JSONObject().put("ss_network_dim", "4"))
        var at = 0L
        for ((name, dtype, bytes) in tensors) {
            h.put(name, JSONObject().put("dtype", dtype).put("shape", org.json.JSONArray().put(bytes.size / width(dtype)))
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
}
