package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ⭐ A GGUF of another size is accepted when its own header proves it whole (Telegram #6310: a
 * modified Klein `llm.gguf` read "missing, repair 0 MB"), and a half-download still is not.
 * ⚠ The formula was checked against a real file first: the first 4 MB of
 * `Wan2.2-Fun-5B-Control-Q4_0.gguf` predict its 3,040,004,960 bytes exactly (2026-10-15).
 */
class GgufCompleteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Header + data: an F32 [8] at 0 (32 B) and a Q4_0 [64] at 32 (2 blocks, 36 B). */
    private fun whole(): ByteArray {
        val out = ByteArrayOutputStream()
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) { val b = s.toByteArray(); out.write(buf(8).putLong(b.size.toLong()).array()); out.write(b) }
        out.write(GgufHeader.MAGIC)
        out.write(buf(4).putInt(3).array())
        out.write(buf(8).putLong(2).array())
        out.write(buf(8).putLong(1).array())
        str("general.architecture"); out.write(buf(4).putInt(8).array()); str("llama")
        for ((name, dims, type, offset) in listOf(Quad("a.weight", 8L, 0, 0L), Quad("blk.0.ffn.weight", 64L, 2, 32L))) {
            str(name)
            out.write(buf(4).putInt(1).array())
            out.write(buf(8).putLong(dims).array())
            out.write(buf(4).putInt(type).array())
            out.write(buf(8).putLong(offset).array())
        }
        while (out.size() % 32 != 0) out.write(0)
        out.write(ByteArray(68) { 7 })
        return out.toByteArray()
    }

    private data class Quad(val a: String, val b: Long, val c: Int, val d: Long)

    @Test
    fun theHeaderSaysWhereTheDataEnds() {
        val bytes = whole()
        assertEquals(bytes.size.toLong(), GgufHeader.dataEnd(bytes)!!.first)
    }

    @Test
    fun aWholeFileOfAnySizeIsComplete() {
        val f = tmp.newFile("llm.gguf").apply { writeBytes(whole()) }
        assertTrue(GgufHeader.isComplete(f))
        // ⚠ Writers pad after the last tensor too: the padded length is whole as well.
        val padded = tmp.newFile("padded.gguf").apply { writeBytes(whole() + ByteArray(28)) }
        assertTrue(GgufHeader.isComplete(padded))
    }

    @Test
    fun aHalfDownloadIsNot() {
        val bytes = whole()
        val short = tmp.newFile("short.gguf").apply { writeBytes(bytes.copyOf(bytes.size - 1)) }
        assertFalse(GgufHeader.isComplete(short))
        val headerOnly = tmp.newFile("head.gguf").apply { writeBytes(bytes.copyOf(bytes.size - 68)) }
        assertFalse(GgufHeader.isComplete(headerOnly))
        assertFalse(GgufHeader.isComplete(tmp.newFile("empty.gguf")))
    }
}
