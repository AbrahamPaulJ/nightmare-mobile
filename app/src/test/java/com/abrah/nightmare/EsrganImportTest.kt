package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ⭐ An ESRGAN upscaler from CivitAI goes through the one import as an upscaler (S#7774), never as a QNN model zip
 * (a `.pth` IS a zip) or a DiT checkpoint (a `.safetensors` has a tensor table). Measured on the S25 (1.6.277):
 * RealESRGAN_x4plus_anime_6B 1024² to 4096² in 22.4 s and RealESRGAN_x2plus to 2048² in 21.1 s on the NPU.
 */
class EsrganImportTest {

    /**
     * A real torch `.pth`'s first bytes, as 4x_foolhardy_Remacri and 2x-AnimeSharpV3 have them: a local file header with
     * flags 0x0808 (the data-descriptor bit) and method 0 (STORED), then `archive/data.pkl`, then the pickle in the clear.
     * ⚠⚠ Hand-built on purpose: `ZipOutputStream` writes DEFLATED, which is the one form `ZipInputStream` reads, so a
     * fixture made with it passed while every real zip `.pth` failed on the phone (GitHub #93).
     */
    private fun pth(pickleText: String): ByteArray = ByteArrayOutputStream().also { o ->
        val name = "archive/data.pkl".toByteArray()
        o.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0x08, 0x08, 0, 0))   // magic, version 0, flags 0x0808, method 0
        o.write(ByteArray(16))                                                 // time, crc and sizes: all in the descriptor
        o.write(byteArrayOf(name.size.toByte(), 0, 0, 0))                      // name length, extra length
        o.write(name)
        o.write(pickleText.toByteArray(Charsets.ISO_8859_1))
    }.toByteArray()

    @Test
    fun theFixtureIsTheFormZipInputStreamRefuses() {
        val thrown = runCatching {
            java.util.zip.ZipInputStream(ByteArrayInputStream(pth("x"))).use { it.nextEntry }
        }.exceptionOrNull()
        assertTrue("$thrown", thrown is java.util.zip.ZipException)
    }

    @Test
    fun aQnnZipIsNotReadToItsEnd() {
        // ⭐ S#7927: a 3.86 GB QNN zip sat silent while the sniff walked every entry. Past the first bytes must not be read.
        var read = 0L
        val endless = object : java.io.InputStream() {
            private val head = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { z -> z.putNextEntry(ZipEntry("unet.bin")); z.write(ByteArray(64)); z.closeEntry() }
            }.toByteArray()
            override fun read(): Int { read++; return if (read <= head.size) head[(read - 1).toInt()].toInt() and 0xFF else 0 }
        }
        assertEquals(CustomModels.ImportKind.Zip, CustomModels.sniffImport("big.zip") { endless })
        assertTrue("read $read bytes", read <= (5L shl 20))
    }

    @Test
    fun anUpscalerTheEngineCannotReadIsToldSo() {
        // 4xNomos8kDAT's layout (S#7874): conv_first and conv_last, no RRDB blocks. Asking which image model it is was wrong.
        val json = """{"conv_first.weight":{"dtype":"F32","shape":[64,3,3,3],"data_offsets":[0,1]},""" +
            """"before_RG.0.weight":{"dtype":"F32","shape":[1],"data_offsets":[1,2]},"conv_last.weight":{"dtype":"F32","shape":[1],"data_offsets":[2,3]}}"""
        assertEquals(CustomModels.ImportKind.OtherUpscaler, CustomModels.kindOfHeader(json))
        val dat = pth("conv_first.weight\u0000before_RG.0.weight\u0000conv_last.weight")
        assertEquals(CustomModels.ImportKind.OtherUpscaler, CustomModels.sniffImport("4xDAT.pth") { ByteArrayInputStream(dat) })
    }

    @Test
    fun bothEsrganArchitecturesAreKnownByTheirLayers() {
        // Real-ESRGAN's names (the x4plus, x2plus and anime_6B releases) and the original's (4x-UltraSharp and most others).
        assertTrue(CustomModels.isEsrganKeys("params_ema conv_first.weight body.0.rdb1.conv1.weight conv_last.weight"))
        assertTrue(CustomModels.isEsrganKeys("model.0.weight model.1.sub.0.RDB1.conv1.0.weight"))
        // A DiT, a LoRA or an SD UNet is not one.
        assertFalse(CustomModels.isEsrganKeys("double_blocks.0.img_attn.qkv.weight"))
        assertFalse(CustomModels.isEsrganKeys("lora_unet_down_blocks_0 lora_down.weight"))
    }

    @Test
    fun aPthEsrganIsAnUpscalerAndAQnnZipStaysAZip() {
        val esrgan = pth("params_ema\u0000conv_first.weight\u0000body.0.rdb1.conv1.weight")
        assertEquals(CustomModels.ImportKind.GgmlUpscaler("pth"), CustomModels.sniffImport("4x-anime.pth") { ByteArrayInputStream(esrgan) })
        // The original architecture's names, as Remacri's and AnimeSharpV3's pickles have them.
        val remacri = pth("model.0.weight\u0000model.1.sub.0.RDB1.conv1.0.weight")
        assertEquals(CustomModels.ImportKind.GgmlUpscaler("pth"), CustomModels.sniffImport("4x_foolhardy_Remacri.pth") { ByteArrayInputStream(remacri) })
        val qnn = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { z -> z.putNextEntry(ZipEntry("unet.bin")); z.write(ByteArray(64)); z.closeEntry() }
        }.toByteArray()
        assertEquals(CustomModels.ImportKind.Zip, CustomModels.sniffImport("my-mix.zip") { ByteArrayInputStream(qnn) })
    }

    @Test
    fun aLegacyPicklePthIsAnUpscalerToo() {
        // ⭐ 4x-UltraSharp (lokCX on Hugging Face) is torch's pre-zip format: a pickle from byte 0. sd.cpp loads it
        // (S25 NPU, 1024² to 4096² in 61.5 s); the zip check alone refused it at import.
        val legacy = byteArrayOf(0x80.toByte(), 0x02, 0x8a.toByte(), 0x0a) +
            "model.0.weight\u0000model.1.sub.0.RDB1.conv1.0.weight".toByteArray(Charsets.ISO_8859_1)
        assertEquals(CustomModels.ImportKind.GgmlUpscaler("pth"), CustomModels.sniffImport("4x-UltraSharp.pth") { ByteArrayInputStream(legacy) })
    }

    @Test
    fun aSafetensorsEsrganIsAnUpscaler() {
        val json = """{"conv_first.weight":{"dtype":"F32","shape":[64,3,3,3],"data_offsets":[0,6912]},""" +
            """"body.0.rdb1.conv1.weight":{"dtype":"F32","shape":[32,64,3,3],"data_offsets":[6912,80640]}}"""
        assertEquals(CustomModels.ImportKind.GgmlUpscaler("safetensors"), CustomModels.kindOfHeader(json))
    }
}
