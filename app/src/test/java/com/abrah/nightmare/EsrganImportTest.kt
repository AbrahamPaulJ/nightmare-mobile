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

    /** A torch-style `.pth`: a zip whose `archive/data.pkl` names the layers. */
    private fun pth(pickleText: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { z ->
            z.putNextEntry(ZipEntry("archive/data.pkl"))
            z.write(pickleText.toByteArray(Charsets.ISO_8859_1))
            z.closeEntry()
            z.putNextEntry(ZipEntry("archive/data/0"))
            z.write(ByteArray(64))
            z.closeEntry()
        }
    }.toByteArray()

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
