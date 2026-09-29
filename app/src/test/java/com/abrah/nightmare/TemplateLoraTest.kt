package com.abrah.nightmare

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
class TemplateLoraTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun raw(f: File): FloatArray {
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(bb.remaining()).also { bb.get(it) }
    }

    /** A minimal safetensors writer: F16 or F32 tensors, in order. */
    private fun writeSafetensors(f: File, tensors: List<Triple<String, List<Int>, FloatArray>>, f16: Boolean) {
        val header = JSONObject()
        var off = 0L
        val blobs = tensors.map { (name, shape, data) ->
            val bytes = if (f16) {
                ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b ->
                    data.forEach { b.putShort(floatToHalf(it)) }
                }.array()
            } else {
                ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b ->
                    data.forEach { b.putFloat(it) }
                }.array()
            }
            header.put(name, JSONObject()
                .put("dtype", if (f16) "F16" else "F32")
                .put("shape", JSONArray(shape))
                .put("data_offsets", JSONArray(listOf(off, off + bytes.size))))
            off += bytes.size
            bytes
        }
        val hb = header.toString().toByteArray()
        f.outputStream().use { o ->
            o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(hb.size.toLong()).array())
            o.write(hb)
            blobs.forEach { o.write(it) }
        }
    }

    // Round-to-nearest-even is not needed: the test builds the expectation from the
    // SAME f16 values it wrote, by reading them back through the packer's decoder.
    private fun floatToHalf(v: Float): Short {
        val bits = v.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        val exp = ((bits ushr 23) and 0xFF) - 127 + 15
        val mant = (bits ushr 13) and 0x3FF
        return when {
            exp <= 0 -> sign.toShort()
            exp >= 31 -> (sign or 0x7C00).toShort()
            else -> (sign or (exp shl 10) or mant).toShort()
        }
    }

    private val targetsJson = JSONObject()
        .put("rank", 8)
        .put("targets", JSONArray()
            .put(JSONObject().put("name", "down_blocks.0.attentions.0.transformer_blocks.0.attn1.q")
                .put("idx", 0).put("din", 6).put("dout", 6))
            .put(JSONObject().put("name", "down_blocks.0.attentions.0.transformer_blocks.0.ff.proj")
                .put("idx", 1).put("din", 6).put("dout", 12))
            .put(JSONObject().put("name", "mid_block.attentions.0.transformer_blocks.0.attn2.out")
                .put("idx", 2).put("din", 6).put("dout", 6)))
        .toString()

    @Test
    fun keyNamesFollowKohyaAndDiffusers() {
        assertEquals(
            "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn1_to_q",
            TemplateLora.kohyaPrefix("down_blocks.0.attentions.0.transformer_blocks.0.attn1.q"),
        )
        assertEquals(
            "lora_unet_up_blocks_1_attentions_2_transformer_blocks_0_ff_net_0_proj",
            TemplateLora.kohyaPrefix("up_blocks.1.attentions.2.transformer_blocks.0.ff.proj"),
        )
        assertEquals(
            "mid_block.attentions.0.transformer_blocks.0.ff.net.2",
            TemplateLora.modulePath("mid_block.attentions.0.transformer_blocks.0.ff.out"),
        )
        assertEquals(
            "down_blocks.1.attentions.0.transformer_blocks.0.attn2.to_out.0",
            TemplateLora.modulePath("down_blocks.1.attentions.0.transformer_blocks.0.attn2.out"),
        )
    }

    /** A B S must reconstruct (alpha/r) D^T U^T; padding zero; an untrained target gets no file. */
    @Test
    fun packReconstructsTheDeltaAndPads() {
        val rnd = Random(7)
        val r = 3
        val alpha = 1.5f
        val q = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn1_to_q"
        val ff = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_ff_net_0_proj"
        val dq = FloatArray(r * 6) { rnd.nextFloat() - 0.5f }
        val uq = FloatArray(6 * r) { rnd.nextFloat() - 0.5f }
        val dff = FloatArray(r * 6) { rnd.nextFloat() - 0.5f }
        val uff = FloatArray(12 * r) { rnd.nextFloat() - 0.5f }
        val lora = tmp.newFile("x.safetensors")
        writeSafetensors(lora, listOf(
            Triple("$q.lora_down.weight", listOf(r, 6), dq),
            Triple("$q.lora_up.weight", listOf(6, r), uq),
            Triple("$q.alpha", listOf(), floatArrayOf(alpha)),
            Triple("$ff.lora_down.weight", listOf(r, 6, 1, 1), dff),   // conv-shaped 1x1, flattened
            Triple("$ff.lora_up.weight", listOf(12, r, 1, 1), uff),
            Triple("lora_te_text_model_encoder_layers_0_mlp_fc1.lora_down.weight", listOf(r, 6), dq),
            Triple("lora_unet_down_blocks_0_resnets_0_conv1.lora_down.weight", listOf(r, 6), dq),
        ), f16 = false)
        val out = tmp.newFolder("packed")
        File(out, "la_2.raw").writeBytes(ByteArray(4))   // a stale file from a previous LoRA
        val p = TemplateLora.pack(lora, TemplateLora.readTargets(targetsJson), out)

        assertEquals(2, p.matched)
        assertEquals(3, p.total)
        assertEquals(r, p.loraRank)
        assertEquals(1, p.textEncoderKeys)
        assertEquals(1, p.unusedUnetKeys)
        assertFalse("stale la_2.raw must be gone", File(out, "la_2.raw").exists())

        val s = raw(File(out, "lora_S.raw"))
        assertEquals(0f, s[2])
        for ((idx, pair) in listOf(0 to Triple(dq, uq, alpha), 1 to Triple(dff, uff, r.toFloat()))) {
            val (d, u, a) = pair
            val dout = if (idx == 0) 6 else 12
            val la = raw(File(out, "la_$idx.raw"))
            val lb = raw(File(out, "lb_$idx.raw"))
            assertEquals(6 * 8, la.size)
            assertEquals(8 * dout, lb.size)
            assertTrue(la.all { kotlin.math.abs(it) <= 1f } && lb.all { kotlin.math.abs(it) <= 1f })
            for (i in 0 until 6) for (j in r until 8) assertEquals(0f, la[i * 8 + j])
            for (i in 0 until 6) for (o in 0 until dout) {
                var got = 0.0
                var want = 0.0
                for (j in 0 until 8) got += la[i * 8 + j].toDouble() * lb[j * dout + o]
                for (j in 0 until r) want += d[j * 6 + i].toDouble() * u[o * r + j]
                assertEquals(want * a / r, got * s[idx], 1e-5)
            }
        }
    }

    /**
     * Rank above the template: a rank-12 LoRA whose product is really rank 7
     * (four of U's columns zero) must survive truncation to R = 8 EXACTLY.
     */
    @Test
    fun rankAboveTheTemplateTruncatesToTheBestApproximation() {
        val rnd = Random(11)
        val r = 12
        val din = 6
        val dout = 12
        val ff = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_ff_net_0_proj"
        val d = FloatArray(r * din) { rnd.nextFloat() - 0.5f }
        val u = FloatArray(dout * r) { i -> if (i % r >= 7) 0f else rnd.nextFloat() - 0.5f }
        val lora = tmp.newFile("big.safetensors")
        writeSafetensors(lora, listOf(
            Triple("$ff.lora_down.weight", listOf(r, din), d),
            Triple("$ff.lora_up.weight", listOf(dout, r), u),
        ), f16 = false)
        val out = tmp.newFolder("o")
        val p = TemplateLora.pack(lora, TemplateLora.readTargets(targetsJson), out)
        assertEquals(1, p.matched)
        assertEquals(12, p.loraRank)
        val la = raw(File(out, "la_1.raw"))
        val lb = raw(File(out, "lb_1.raw"))
        val s = raw(File(out, "lora_S.raw"))[1]
        for (i in 0 until din) for (o in 0 until dout) {
            var got = 0.0
            var want = 0.0
            for (j in 0 until 8) got += la[i * 8 + j].toDouble() * lb[j * dout + o]
            for (j in 0 until r) want += d[j * din + i].toDouble() * u[o * r + j]
            assertEquals(want, got * s, 1e-5)   // alpha absent = r, so alpha/r = 1
        }
    }

    /**
     * ⭐ Rank > 64 on a real LoRA (Colorwater v4, rank 128): the truncated
     * delta must match `pack_lora.py`'s torch SVD path. Compared through the
     * PRODUCT (the factors differ by signs/rotations), probed with random x:
     * `((x A) B) S`. Env: TEMPLATE_LORA_HIRANK_FILE, TEMPLATE_LORA_TARGETS,
     * TEMPLATE_LORA_HIRANK_REF.
     */
    @Test
    fun truncationMatchesPackLoraPyOnTheProduct() {
        val lora = File(System.getenv("TEMPLATE_LORA_HIRANK_FILE") ?: "")
        val targetsFile = File(System.getenv("TEMPLATE_LORA_TARGETS") ?: "")
        val ref = File(System.getenv("TEMPLATE_LORA_HIRANK_REF") ?: "")
        assumeTrue(lora.exists() && targetsFile.exists() && ref.isDirectory)
        val targets = TemplateLora.readTargets(targetsFile.readText())
        val out = tmp.newFolder("hi")
        val t0 = System.nanoTime()
        val p = TemplateLora.pack(lora, targets, out)
        println("rank-${p.loraRank} pack: ${(System.nanoTime() - t0) / 1_000_000} ms")
        assertEquals(160, p.matched)
        assertTrue(p.loraRank > 64)
        val sK = raw(File(out, "lora_S.raw"))
        val sP = raw(File(ref, "lora_S.raw"))
        val rnd = Random(3)
        var worst = 0.0
        for (t in targets.list) {
            val aK = raw(File(out, "la_${t.idx}.raw")); val bK = raw(File(out, "lb_${t.idx}.raw"))
            val aP = raw(File(ref, "la_${t.idx}.raw")); val bP = raw(File(ref, "lb_${t.idx}.raw"))
            repeat(2) {
                val x = DoubleArray(t.din) { rnd.nextDouble() - 0.5 }
                fun apply(a: FloatArray, b: FloatArray, s: Float): DoubleArray {
                    val h = DoubleArray(64) { j -> (0 until t.din).sumOf { i -> x[i] * a[i * 64 + j] } }
                    return DoubleArray(t.dout) { o -> (0 until 64).sumOf { j -> h[j] * b[j * t.dout + o] } * s }
                }
                val yK = apply(aK, bK, sK[t.idx])
                val yP = apply(aP, bP, sP[t.idx])
                val num = yK.indices.sumOf { (yK[it] - yP[it]).let { d -> d * d } }
                val den = yP.sumOf { it * it }
                worst = maxOf(worst, kotlin.math.sqrt(num / den))
            }
        }
        assertTrue("worst relative delta difference $worst", worst < 1e-3)
    }

    /**
     * ⭐ Byte parity with npuforge's `pack_lora.py` on a real LoRA of rank <= 64
     * (measured 2026-09-29: lora-camera-style-sd15, 160/160 targets, 321 files
     * identical). Runs only where the reference files exist -- the
     * PC that built the template: TEMPLATE_LORA_FILE (the .safetensors),
     * TEMPLATE_LORA_TARGETS (the template's targets json), TEMPLATE_LORA_REF (the
     * directory pack_lora.py wrote).
     */
    @Test
    fun matchesPackLoraPy() {
        val lora = File(System.getenv("TEMPLATE_LORA_FILE") ?: "")
        val targets = File(System.getenv("TEMPLATE_LORA_TARGETS") ?: "")
        val ref = File(System.getenv("TEMPLATE_LORA_REF") ?: "")
        assumeTrue(lora.exists() && targets.exists() && ref.isDirectory)
        val out = tmp.newFolder("cw")
        val p = TemplateLora.pack(lora, TemplateLora.readTargets(targets.readText()), out)
        assertEquals(160, p.matched)
        val refFiles = ref.listFiles { f -> f.name.matches(Regex("(la|lb)_\\d+\\.raw|lora_S\\.raw")) }!!
        assertEquals(321, refFiles.size)
        for (f in refFiles) assertArrayEquals(f.name, f.readBytes(), File(out, f.name).readBytes())
    }
}
