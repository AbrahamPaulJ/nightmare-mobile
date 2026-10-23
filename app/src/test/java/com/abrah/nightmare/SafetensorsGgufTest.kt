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

    /**
     * ⭐ Anima's header carries its three keys, as Bedovyy's file (and so [AnimaGguf.TURBO_11_BYTES]) does.
     * The 1.6.167 writer dropped two, every Anima Turbo 1.1 conversion came out 64 bytes short and failed.
     * The bytes are the 1.6.141 writer's: arch, then quantization_version 2, then file_type 7, then the 32 pad.
     */
    @Test
    fun animaHeaderHasItsThreeKeys() {
        val src = safetensors(listOf(t("model.diffusion_model.llm_adapter.w", 32, 32), t("model.diffusion_model.x_embedder.b", 32)))
        val dst = File(tmp.root, "anima.gguf")
        val written = AnimaGguf.convert(src, dst, { _, _ -> }, { false })
        val b = java.nio.ByteBuffer.wrap(dst.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(dst.length(), written)
        assertEquals(3L, b.getLong(16))
        val head = String(dst.readBytes(), Charsets.ISO_8859_1)
        for (k in listOf("general.architecture", "general.quantization_version", "general.file_type")) {
            assertTrue("missing $k", head.contains(k))
        }
        // The kv block, byte for byte: 3 entries after the 24-byte preamble.
        fun str(s: String) = java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(s.length.toLong()).array() + s.toByteArray()
        fun u32(v: Int) = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(v).array()
        val kv = str("general.architecture") + u32(8) + str("cosmos") +
            str("general.quantization_version") + u32(4) + u32(2) +
            str("general.file_type") + u32(4) + u32(7)
        assertTrue(dst.readBytes().copyOfRange(24, 24 + kv.size).contentEquals(kv))
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

    @Test
    fun anima29KeepsItsFortyBlocksAndDropsTheRopeTables() {
        // ⭐ Anima 2.9B's shape (1.6.186): `net.` names, a block past the 2B's 28, and the RoPE buffers the 2B lacks.
        val src = safetensors(listOf(
            t("net.llm_adapter.out_proj.weight", 64, 64),
            t("net.blocks.39.mlp.layer1.weight", 64, 64),
            t("net.pos_embedder.seq", 256),
            t("net.pos_embedder.dim_spatial_range", 21),
            t("net.final_layer.linear.weight", 64, 64),
        ))
        val (plan, _) = SafetensorsGguf.plan(src, AnimaGguf.rule)
        assertEquals(listOf("blocks.39.mlp.layer1.weight", "final_layer.linear.weight", "llm_adapter.out_proj.weight"), plan.map { it.name })
        assertEquals(SafetensorsGguf.Q8_0, plan.first().type)
    }

    /**
     * ⚠ Opt-in, on the PC: the REAL 2.9B through the phone's converter, against [AnimaGguf.ANIMA_29_PREVIEW_BYTES].
     * Runs only when `app/build/anima29.safetensors` exists (a hard link to the 5.84 GB download); skipped elsewhere.
     */
    @Test
    fun theRealAnima29ConvertsToTheCataloguedSize() {
        val src = File("build/anima29.safetensors")
        org.junit.Assume.assumeTrue(src.isFile)
        val dst = File(tmp.root, "dit.gguf")
        val written = AnimaGguf.convert(src, dst, { _, _ -> }, { false })
        assertEquals(AnimaGguf.ANIMA_29_PREVIEW_BYTES, dst.length())
        assertEquals(dst.length(), written)
        assertTrue(GgufHeader.isComplete(dst))
    }

    /**
     * ⭐ [SafetensorsGguf.convertStream] (an import read once from the picker, [MossGguf]) writes the same size of whole
     * GGUF as [SafetensorsGguf.convert] from a file, with MOSS's types: block weights Q8_0, the embedders F32.
     * ⚠ The tensors are deliberately not in name order in the file, which is what the stream has to follow.
     */
    @Test
    fun aMossStreamConvertsLikeTheFile() {
        val src = safetensors(listOf(
            t("video_embedder.weight", 64, 32),
            t("layers.0.proj.qkv.weight", 96, 64),
            t("shared_block_modulation.weight", 64, 64),
            t("layers.0.norm1.weight", 64),
            t("time_embedding.0.weight", 64, 32),
        ))
        val fromFile = tmp.newFile("file.gguf")
        val fromStream = tmp.newFile("stream.gguf")
        SafetensorsGguf.convert(src, fromFile, MossGguf.rule, { _, _ -> }, { false })
        val written = SafetensorsGguf.convertStream({ src.inputStream() }, fromStream, MossGguf.rule, { _, _ -> }, { false })
        assertEquals(fromFile.length(), fromStream.length())
        assertEquals(fromStream.length(), written)
        assertTrue(GgufHeader.isComplete(fromStream))
        assertEquals(setOf("Q8_0", "F32"), GgufHeader.parse(fromStream.readBytes()).types)
        val (plan, _) = SafetensorsGguf.plan(src, MossGguf.rule)
        val by = plan.associateBy { it.name }
        assertEquals(SafetensorsGguf.Q8_0, by.getValue("layers.0.proj.qkv.weight").type)
        assertEquals(SafetensorsGguf.Q8_0, by.getValue("shared_block_modulation.weight").type)
        assertEquals(SafetensorsGguf.F32, by.getValue("video_embedder.weight").type)
        assertEquals(SafetensorsGguf.F32, by.getValue("time_embedding.0.weight").type)
        assertEquals(SafetensorsGguf.F32, by.getValue("layers.0.norm1.weight").type)
    }

    /** ⭐ Only 16- or 32-bit block weights convert: fp8 is already MOSS-sized, int8 is refused before this. */
    @Test
    fun onlySixteenBitMossConverts() {
        fun head(dtype: String) = JSONObject().put(
            "layers.0.proj.qkv.weight",
            JSONObject().put("dtype", dtype).put("shape", JSONArray(listOf(96, 64))).put("data_offsets", JSONArray(listOf(0, 1))),
        ).toString()
        assertTrue(MossGguf.converts(head("F16")))
        assertTrue(MossGguf.converts(head("BF16")))
        assertEquals(false, MossGguf.converts(head("F8_E4M3")))
        assertEquals(false, MossGguf.converts(head("I8")))
    }
}
