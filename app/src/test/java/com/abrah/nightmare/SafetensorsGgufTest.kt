package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
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

/**
 * ⭐ [SafetensorsGguf] with [IrisGguf]'s rule on a miniature Iris: the trunk MLP padded to 256,
 * Q8_0 where rows allow, BF16 copied through, and a file [GgufHeader] proves whole. And
 * [AnimaGguf]'s rule still refuses a file that is not Anima.
 */
@RunWith(RobolectricTestRunner::class)
class SafetensorsGgufTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class T(val name: String, val shape: LongArray, val values: FloatArray)

    /** BF16 safetensors; value i of every tensor is (i % 7) - 3, exact in BF16. */
    private fun safetensors(tensors: List<T>): File {
        val header = JSONObject()
        var off = 0L
        for (t in tensors) {
            val n = t.values.size * 2L
            header.put(t.name, JSONObject().put("dtype", "BF16").put("shape", JSONArray(t.shape.toList()))
                .put("data_offsets", JSONArray(listOf(off, off + n))))
            off += n
        }
        val h = header.toString().toByteArray()
        val f = tmp.newFile("in.safetensors")
        f.outputStream().use { out ->
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(h.size.toLong()).array())
            out.write(h)
            for (t in tensors) {
                val b = ByteBuffer.allocate(t.values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (v in t.values) b.putShort((java.lang.Float.floatToIntBits(v) ushr 16).toShort())
                out.write(b.array())
            }
        }
        return f
    }

    private fun t(name: String, vararg shape: Long) =
        T(name, shape, FloatArray(shape.fold(1L) { a, b -> a * b }.toInt()) { (it % 7) - 3f })

    private val iris = listOf(
        t("blocks.0.mlp_x.w1.weight", 300, 64),
        t("blocks.0.mlp_x.w2.weight", 64, 300),
        t("blocks.0.norm_x1.weight", 64),
        t("pixel_blocks.0.compress.weight", 32, 64),
        t("pixel_blocks.0.mlp.fc1.weight", 64, 16),
        t("y_embedder.layer_pool.weight", 1, 12),
        t("y_embedder.refiner.proj.weight", 64, 64),
        t("y_embedder.refiner.blocks.0.mlp.w2.weight", 64, 300),
    )

    @Test
    fun irisPadsTheTrunkMlpAndProvesWhole() {
        val src = safetensors(iris)
        val dst = File(tmp.root, "dit.gguf")
        val (plan, _) = SafetensorsGguf.plan(src, IrisGguf.rule)
        val by = plan.associateBy { it.name }
        assertArrayEquals(longArrayOf(512, 64), by.getValue("blocks.0.mlp_x.w1.weight").outShape)
        assertArrayEquals(longArrayOf(64, 512), by.getValue("blocks.0.mlp_x.w2.weight").outShape)
        assertEquals(SafetensorsGguf.Q8_0, by.getValue("blocks.0.mlp_x.w2.weight").type)
        assertEquals(SafetensorsGguf.Q8_0, by.getValue("pixel_blocks.0.compress.weight").type)
        // 1-D, a 16-wide row, and the text embedder stay BF16.
        assertEquals(SafetensorsGguf.BF16, by.getValue("blocks.0.norm_x1.weight").type)
        assertEquals(SafetensorsGguf.BF16, by.getValue("pixel_blocks.0.mlp.fc1.weight").type)
        assertEquals(SafetensorsGguf.BF16, by.getValue("y_embedder.refiner.proj.weight").type)
        // ⚠ The refiner's MLP pads with the trunk's (the engine reads one hidden size), and stays BF16.
        assertArrayEquals(longArrayOf(64, 512), by.getValue("y_embedder.refiner.blocks.0.mlp.w2.weight").outShape)
        assertEquals(SafetensorsGguf.BF16, by.getValue("y_embedder.refiner.blocks.0.mlp.w2.weight").type)

        var last = 0L to 0L
        IrisGguf.convert(src, dst, { d, total -> last = d to total }, { false })
        assertEquals(last.second, last.first)
        assertTrue("the header must account for every byte", GgufHeader.isComplete(dst))
        assertEquals(setOf("Q8_0", "BF16"), GgufHeader.parse(dst.readBytes()).types)
    }

    @Test
    fun irisPaddedColumnsAreZero() {
        val src = safetensors(listOf(t("blocks.0.mlp_x.w2.weight", 64, 300), t("y_embedder.layer_pool.weight", 1, 12),
            t("pixel_blocks.0.compress.weight", 32, 64)))
        val dst = File(tmp.root, "dit.gguf")
        IrisGguf.convert(src, dst, { _, _ -> }, { false })
        val bytes = dst.readBytes()
        // ⚠ Sorted names put w2 first, so its data starts where the tensor data does.
        val (plan, _) = SafetensorsGguf.plan(src, IrisGguf.rule)
        assertEquals("blocks.0.mlp_x.w2.weight", plan.first().name)
        val base = bytes.size - plan.sumOf { (it.outBytes + 31) / 32 * 32 }
        // Row 0: 512 values = 16 Q8_0 blocks of 34 bytes; values 300..511 are padding.
        for (block in 0 until 16) {
            val o = (base + block * 34).toInt()
            for (i in 0 until 32) {
                val col = block * 32 + i
                val q = bytes[o + 2 + i].toInt()
                if (col >= 300) assertEquals("column $col", 0, q)
            }
        }
    }

    @Test
    fun aWrongFileIsRefusedBeforeAnyWrite() {
        val src = safetensors(listOf(t("blocks.0.mlp_x.w1.weight", 300, 64)))
        for (rule in listOf(IrisGguf.rule, AnimaGguf.rule)) {
            try {
                SafetensorsGguf.plan(src, rule)
                fail("accepted a file that is not this model")
            } catch (_: IOException) {
            }
        }
    }
}
