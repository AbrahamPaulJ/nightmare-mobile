package com.abrah.nightmare

import android.util.Half
import org.junit.Assert.assertArrayEquals
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

/** ⭐ An mxfp8 checkpoint (a tester's `anima_turbov11_mxfp8`): fp8 weights, one U8 exponent per 32, a grid in tiles. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MxFp8GgufTest {
    private fun safetensors(json: String, data: ByteArray): ByteArray {
        val h = json.toByteArray()
        return ByteBuffer.allocate(8 + h.size + data.size).order(ByteOrder.LITTLE_ENDIAN).putLong(h.size.toLong()).put(h).put(data).array()
    }

    /** ⚠ The positions measured on the real file: T[n, j] sits at stored position 16n + j, column 4 starts a tile at 512, column 16 at 2048. */
    @Test
    fun theGridComesOutOfItsTilesAsMeasured() {
        val rows = 256
        val cols = 64
        val stored = ByteArray(rows * cols) { (it % 251).toByte() }
        val out = MxFp8Gguf.unswizzle(stored, rows, cols)
        fun at(r: Int, c: Int) = out[r * cols + c]
        for (n in 0 until 32) for (j in 0 until 4) assertEquals("row $n col $j", stored[16 * n + j], at(n, j))
        for (n in 0 until 32) assertEquals(stored[512 + 16 * n], at(n, 4))
        assertEquals(stored[2048], at(0, 16))
        // ⚠ A line's row index is rotated: stored line 1 is row 32.
        assertEquals(stored[4], at(32, 0))
        // ⚠ The second row of tiles starts past the whole first row of them.
        assertEquals(stored[(cols / 4) * 512], at(128, 0))
        // ⚠ A permutation: nothing lost, nothing doubled.
        assertArrayEquals(stored.sortedArray(), out.sortedArray())
    }

    /** ⚠ Plain order is the bug this exists to prevent, so it must not pass by accident. */
    @Test
    fun aGridIsNotInPlainOrder() {
        val stored = ByteArray(128 * 4) { (it % 251).toByte() }
        assertFalse(stored.contentEquals(MxFp8Gguf.unswizzle(stored, 128, 4)))
    }

    @Test
    fun itKnowsAnMxfp8FileFromTheOthers() {
        fun head(json: String) = CustomModels.DitHead(json, false, emptySet())
        assertTrue(MxFp8Gguf.converts(head("""{"a.weight_scale":{"dtype":"U8","shape":[128,4],"data_offsets":[0,1]}}""")))
        assertTrue("any key order", MxFp8Gguf.converts(head("""{"a.weight_scale":{"data_offsets":[0,1],"shape":[128,4],"dtype":"U8"}}""")))
        assertFalse("an int8 file's scale", MxFp8Gguf.converts(head("""{"a.weight_scale":{"dtype":"F32","shape":[128],"data_offsets":[0,1]}}""")))
        assertFalse("settings, not scales", MxFp8Gguf.converts(head("""{"a.comfy_quant":{"dtype":"U8","shape":[27],"data_offsets":[0,1]}}""")))
        assertFalse("a GGUF", MxFp8Gguf.converts(CustomModels.DitHead("""{"a.weight_scale":{"dtype":"U8"}}""", true, emptySet())))
    }

    /** ⭐ End to end on one tensor: each block must come out as code x 2^(s-127), with its own exponent, and no scale kept. */
    @Test
    fun aWeightIsWrittenAsItsCodeTimesItsOwnBlocksPowerOfTwo() {
        val rows = 128
        val cols = 128
        val gridCols = 4
        val code = 0x78.toByte() // exp 15, mantissa 0: exactly 256, the top of a block as the real file stores it
        val data = ByteArray(rows * cols) { code }
        val stored = ByteArray(rows * gridCols) { (112 + it % 7).toByte() }
        val truth = MxFp8Gguf.unswizzle(stored, rows, gridCols)
        val json = """{"m.weight":{"dtype":"F8_E4M3","shape":[$rows,$cols],"data_offsets":[0,${data.size}]},""" +
            """"m.weight_scale":{"dtype":"U8","shape":[$rows,$gridCols],"data_offsets":[${data.size},${data.size + stored.size}]}}"""
        val bytes = safetensors(json, data + stored)
        val dst = File.createTempFile("mxfp8", ".gguf")
        try {
            MxFp8Gguf.convertStream({ ByteArrayInputStream(bytes) }, dst, Family.MOSS, { _, _, _ -> }, { false })
            val out = dst.readBytes()
            assertFalse("the scale was kept", String(out, Charsets.ISO_8859_1).contains("weight_scale"))
            val blocks = rows * gridCols
            val q = ByteBuffer.wrap(out, out.size - blocks * 34, blocks * 34).slice().order(ByteOrder.LITTLE_ENDIAN)
            for (b in 0 until blocks) {
                val d = Half.toFloat(q.getShort(b * 34))
                val want = 256f * Math.scalb(1f, (truth[b].toInt() and 0xFF) - 127)
                // Q8_0's scale is max/127, an fp16: within its rounding.
                assertEquals("block $b", want, d * 127f, want * 0.002f)
                assertEquals(127, q.get(b * 34 + 2).toInt())
            }
        } finally {
            dst.delete()
        }
    }

    /** ⚠ A grid that does not fill its tiles has no known layout: it must fail, never render noise. */
    @Test
    fun aGridOfUnknownShapeIsRefused() {
        val json = """{"m.weight":{"dtype":"F8_E4M3","shape":[100,128],"data_offsets":[0,12800]},""" +
            """"m.weight_scale":{"dtype":"U8","shape":[100,4],"data_offsets":[12800,13200]}}"""
        val bytes = safetensors(json, ByteArray(13200))
        val e = runCatching { MxFp8Gguf.scan({ ByteArrayInputStream(bytes) }) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty(), e?.message.orEmpty().contains("layout the import does not know"))
    }
}
