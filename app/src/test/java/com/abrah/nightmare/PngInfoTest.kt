package com.abrah.nightmare

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/** GitHub #106: what made a picture, written into its PNG and read back ([PngInfo]). */
class PngInfoTest {
    /** A real 1x1 PNG's bytes: signature, IHDR, one IDAT, IEND. */
    private val png: ByteArray = run {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val t = type.toByteArray(Charsets.ISO_8859_1)
            val crc = CRC32().apply { update(t); update(data) }.value.toInt()
            fun i(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            return i(data.size) + t + data + i(crc)
        }
        val ihdr = byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)
        val idat = java.util.zip.Deflater().run {
            setInput(byteArrayOf(0, 1, 2, 3)); finish()
            val buf = ByteArray(64); val n = deflate(buf); buf.copyOf(n)
        }
        byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10) + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    private fun written(info: PngInfo.Info?, oneByteAtATime: Boolean = false): ByteArray {
        val sink = ByteArrayOutputStream()
        PngInfo.wrap(sink, info).use { out -> if (oneByteAtATime) png.forEach { out.write(it.toInt()) } else out.write(png) }
        return sink.toByteArray()
    }

    @Test
    fun theChunksGoInAfterTheHeaderAndReadBack() {
        val info = PngInfo.Info("a cat\nNegative prompt: blurry\nSteps: 20", """{"flow":"{}"}""")
        for (single in listOf(false, true)) {
            val out = written(info, single)
            val read = PngInfo.read(out.inputStream())
            assertEquals(info.parameters, read[PngInfo.PARAMETERS])
            assertEquals(info.json, read[PngInfo.NIGHTMARE])
            // ⚠ The picture itself is untouched: the original's bytes, with the chunks after its first 33.
            assertArrayEquals(png.copyOfRange(0, 33), out.copyOfRange(0, 33))
            assertArrayEquals(png.copyOfRange(33, png.size), out.copyOfRange(out.size - (png.size - 33), out.size))
        }
    }

    @Test
    fun aPromptOutsideLatin1GoesAsUtf8() {
        val info = PngInfo.Info("一只猫", "{}")
        assertEquals("一只猫", PngInfo.read(written(info).inputStream())[PngInfo.PARAMETERS])
        assertTrue(String(PngInfo.textChunk("k", "一只猫"), 4, 4, Charsets.ISO_8859_1) == "iTXt")
        assertTrue(String(PngInfo.textChunk("k", "a cat"), 4, 4, Charsets.ISO_8859_1) == "tEXt")
    }

    @Test
    fun offWritesThePictureAloneAndNotAPngPassesThrough() {
        assertArrayEquals(png, written(null))
        val sink = ByteArrayOutputStream()
        PngInfo.wrap(sink, PngInfo.Info("x", "{}")).use { it.write("not a png".toByteArray()) }
        assertEquals("not a png", sink.toString())
        assertEquals(emptyMap<String, String>(), PngInfo.read("nope".byteInputStream()))
    }

    @Test
    fun a1111QuotesWhatItsParserWouldSplit() {
        val text = PngInfo.a1111("a cat", "", listOf("Steps" to "20", "Lora" to "<lora:a:0.8>, <lora:b:1>", "Seed" to "7", "Empty" to ""))
        assertEquals("a cat\nSteps: 20, Lora: \"<lora:a:0.8>, <lora:b:1>\", Seed: 7", text)
    }
}
