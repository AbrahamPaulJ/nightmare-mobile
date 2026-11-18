package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ⭐ Item 73 (Saved #8071): a plain fp8 Krea 2 imported below v79 becomes a Q8_0 GGUF, its scales kept ([Fp8Q8Gguf]). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fp8Q8GgufTest {
    private fun safetensors(json: String, data: ByteArray): ByteArray {
        val h = json.toByteArray()
        return ByteBuffer.allocate(8 + h.size + data.size).order(ByteOrder.LITTLE_ENDIAN).putLong(h.size.toLong()).put(h).put(data).array()
    }

    private val fp8Head = CustomModels.DitHead(
        """{"txtfusion.a.weight":{"dtype":"F8_E4M3","shape":[64,64],"data_offsets":[0,4096]},"n.scale":{"dtype":"BF16","shape":[64],"data_offsets":[4096,4224]}}""",
        false, emptySet(),
    )

    private fun caps(arch: Int) = DeviceProbe.Caps(arch = arch, vtcmMb = 8, measured = true, soc = "SM0000")

    @Test
    fun onlyAnFp8Krea2BelowV79Converts() {
        assertTrue("the 8 Gen 3", Fp8Q8Gguf.converts(fp8Head, Family.KREA2, caps(75)))
        assertTrue("the 8 Gen 2", Fp8Q8Gguf.converts(fp8Head, Family.KREA2, caps(73)))
        assertFalse("the 8 Elite runs fp8 natively", Fp8Q8Gguf.converts(fp8Head, Family.KREA2, caps(79)))
        assertFalse("another family keeps its fp8", Fp8Q8Gguf.converts(fp8Head, Family.ZIMAGE, caps(75)))
        // ⭐ Item 86 (Saved #8177): an fp8 MOSS below v79 too, in MossGguf's layout; on v79 it stays fp8.
        assertTrue("MOSS on the 8 Gen 3", Fp8Q8Gguf.converts(fp8Head, Family.MOSS, caps(75)))
        assertFalse("MOSS on the 8 Elite", Fp8Q8Gguf.converts(fp8Head, Family.MOSS, caps(79)))
        val moss = Fp8Q8Gguf.rule(Family.MOSS)
        assertEquals(listOf("general.architecture" to "moss_image"), moss.kv)
        assertEquals(listOf("model.diffusion_model."), moss.prefixes)
        assertEquals(SafetensorsGguf.F32, moss.type("time_embedding.linear_1.weight", longArrayOf(64, 64), "F8_E4M3"))
        assertEquals(SafetensorsGguf.Q8_0, moss.type("layers.0.attn.proj.qkv.weight", longArrayOf(64, 64), "F8_E4M3"))
        assertEquals(emptyList<Pair<String, String>>(), Fp8Q8Gguf.rule(Family.KREA2).kv)
        assertFalse("a GGUF", Fp8Q8Gguf.converts(CustomModels.DitHead(fp8Head.json, true, emptySet()), Family.KREA2, caps(75)))
        val bf16 = CustomModels.DitHead("""{"a.weight":{"dtype":"BF16","shape":[64,64],"data_offsets":[0,8192]}}""", false, emptySet())
        assertFalse("no fp8 matmul", Fp8Q8Gguf.converts(bf16, Family.KREA2, caps(75)))
        val e5m2 = CustomModels.DitHead("""{"a.weight":{"dtype":"F8_E5M2","shape":[64,64],"data_offsets":[0,4096]}}""", false, emptySet())
        assertFalse("E5M2 stays with Fp8Widen", Fp8Q8Gguf.converts(e5m2, Family.KREA2, caps(75)))
        val mx = CustomModels.DitHead(
            """{"a.weight":{"dtype":"F8_E4M3","shape":[128,128],"data_offsets":[0,16384]},"a.weight_scale":{"dtype":"U8","shape":[128,4],"data_offsets":[16384,16896]}}""",
            false, emptySet(),
        )
        assertFalse("mxfp8 has its own converter", Fp8Q8Gguf.converts(mx, Family.KREA2, caps(75)))
    }

    /** ⭐ The weight as Q8_0 with its values, its scale kept as the 1-D the Linear declares, the settings and marker dropped. */
    @Test
    fun weightsBecomeQ8AndTheScaleIsKept() {
        val one = 0x38.toByte() // E4M3: exp 7, mantissa 0 = 1.0
        val w = ByteArray(64 * 64) { one }
        val scale = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.5f).array()
        val norm = ByteArray(64) { one }
        val quant = "{\"format\":\"float8_e4m3fn\"}".toByteArray()
        var at = 0
        fun span(n: Int): String = "[$at,${(at + n).also { at = it }}]"
        val json = "{" +
            "\"model.diffusion_model.blocks.0.attn.weight\":{\"dtype\":\"F8_E4M3\",\"shape\":[64,64],\"data_offsets\":${span(w.size)}}," +
            "\"model.diffusion_model.blocks.0.attn.weight_scale\":{\"dtype\":\"F32\",\"shape\":[],\"data_offsets\":${span(4)}}," +
            "\"model.diffusion_model.blocks.0.norm.weight\":{\"dtype\":\"F8_E4M3\",\"shape\":[64],\"data_offsets\":${span(64)}}," +
            "\"model.diffusion_model.blocks.0.attn.comfy_quant\":{\"dtype\":\"U8\",\"shape\":[${quant.size}],\"data_offsets\":${span(quant.size)}}" +
            "}"
        val bytes = safetensors(json, w + scale + norm + quant)
        val dst = File.createTempFile("fp8q8", ".gguf")
        try {
            SafetensorsGguf.convertStream({ ByteArrayInputStream(bytes) }, dst, Fp8Q8Gguf.rule(Family.KREA2), { _, _ -> }, { false })
            val table = GgufHeader.parse(dst.readBytes())
            assertEquals("Q8_0", table.typeOf["model.diffusion_model.blocks.0.attn.weight"])
            assertEquals("F32", table.typeOf["model.diffusion_model.blocks.0.attn.weight_scale"])
            assertEquals("F32", table.typeOf["model.diffusion_model.blocks.0.norm.weight"])
            assertFalse(table.typeOf.keys.any { it.endsWith("comfy_quant") })
            assertTrue(table.json, table.json.contains("\"model.diffusion_model.blocks.0.attn.weight_scale\":{\"dtype\":\"F32\",\"shape\":[1]"))
        } finally {
            dst.delete()
        }
    }
}
