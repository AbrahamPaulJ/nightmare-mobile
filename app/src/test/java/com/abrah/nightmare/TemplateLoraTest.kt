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
     * ⭐ Two LoRAs merged into the one slot, strengths baked (one negative):
     * ranks 3 + 4 fit R = 8, so `la lb S` must equal the strength-weighted SUM of
     * their deltas exactly. A target only the first trains is its delta alone.
     */
    @Test
    fun mergedPackIsTheWeightedSumOfTheDeltas() {
        val rnd = Random(5)
        val q = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn1_to_q"
        val ff = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_ff_net_0_proj"
        fun lora(name: String, r: Int, alpha: Float, withFf: Boolean): Pair<File, List<FloatArray>> {
            val d = FloatArray(r * 6) { rnd.nextFloat() - 0.5f }
            val u = FloatArray(6 * r) { rnd.nextFloat() - 0.5f }
            val dff = FloatArray(r * 6) { rnd.nextFloat() - 0.5f }
            val uff = FloatArray(12 * r) { rnd.nextFloat() - 0.5f }
            val f = tmp.newFile(name)
            writeSafetensors(f, listOfNotNull(
                Triple("$q.lora_down.weight", listOf(r, 6), d),
                Triple("$q.lora_up.weight", listOf(6, r), u),
                Triple("$q.alpha", listOf(), floatArrayOf(alpha)),
                if (withFf) Triple("$ff.lora_down.weight", listOf(r, 6), dff) else null,
                if (withFf) Triple("$ff.lora_up.weight", listOf(12, r), uff) else null,
            ), f16 = false)
            return f to listOf(d, u, dff, uff)
        }
        val (f1, t1) = lora("a.safetensors", 3, 2f, withFf = true)
        val (f2, t2) = lora("b.safetensors", 4, 4f, withFf = false)
        val out = tmp.newFolder("merged")
        val p = TemplateLora.packMerged(listOf(f1 to 0.5, f2 to -1.0), TemplateLora.readTargets(targetsJson), out)
        assertEquals(2, p.matched)
        assertEquals(7, p.loraRank)
        val s = raw(File(out, "lora_S.raw"))
        assertTrue(s.all { it >= 0f })
        fun delta(d: FloatArray, u: FloatArray, r: Int, scale: Double, dout: Int) =
            DoubleArray(6 * dout) { k ->
                val i = k / dout
                val o = k % dout
                (0 until r).sumOf { j -> d[j * 6 + i].toDouble() * u[o * r + j] } * scale
            }
        val q1 = delta(t1[0], t1[1], 3, 2.0 / 3 * 0.5, 6)
        val q2 = delta(t2[0], t2[1], 4, 4.0 / 4 * -1.0, 6)
        val wantQ = DoubleArray(q1.size) { q1[it] + q2[it] }
        val wantFf = delta(t1[2], t1[3], 3, 3.0 / 3 * 0.5, 12)
        for ((idx, want, dout) in listOf(Triple(0, wantQ, 6), Triple(1, wantFf, 12))) {
            val la = raw(File(out, "la_$idx.raw"))
            val lb = raw(File(out, "lb_$idx.raw"))
            for (i in 0 until 6) for (o in 0 until dout) {
                var got = 0.0
                for (j in 0 until 8) got += la[i * 8 + j].toDouble() * lb[j * dout + o]
                assertEquals(want[i * dout + o], got * s[idx], 1e-5)
            }
        }
    }

    /** ⭐ One LoRA through the merged path at 1.0 is the plain pack (strength then sent per request). */
    @Test
    fun mergedPackOfOneAtFullStrengthIsThePlainPack() {
        val rnd = Random(9)
        val q = "lora_unet_down_blocks_0_attentions_0_transformer_blocks_0_attn1_to_q"
        val f = tmp.newFile("one.safetensors")
        writeSafetensors(f, listOf(
            Triple("$q.lora_down.weight", listOf(5, 6), FloatArray(30) { rnd.nextFloat() - 0.5f }),
            Triple("$q.lora_up.weight", listOf(6, 5), FloatArray(30) { rnd.nextFloat() - 0.5f }),
            Triple("$q.alpha", listOf(), floatArrayOf(3f)),
        ), f16 = false)
        val targets = TemplateLora.readTargets(targetsJson)
        val a = tmp.newFolder("plain")
        val b = tmp.newFolder("merged1")
        TemplateLora.pack(f, targets, a)
        TemplateLora.packMerged(listOf(f to 1.0), targets, b)
        for (name in listOf("la_0.raw", "lb_0.raw", "lora_S.raw")) {
            val x = raw(File(a, name))
            val y = raw(File(b, name))
            assertEquals(x.size, y.size)
            for (i in x.indices) assertEquals(name, x[i], y[i], 1e-6f)
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
    /**
     * ⚠⚠ Reported 2026-10-01 from a character LoRA: `….alpha: dtype I64 is not
     * supported`. Some trainers write `alpha` as an integer scalar; FP8 is read
     * too, for the same reason.
     */
    @Test
    fun integerAndFp8TensorsReadAsNumbers() {
        val f = tmp.newFile("ints.safetensors")
        val i64 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(16L).array()
        val f8 = byteArrayOf(0x38, 0xC0.toByte()) // E4M3: 1.0, -2.0
        val header = JSONObject()
            .put("a.alpha", JSONObject().put("dtype", "I64").put("shape", JSONArray())
                .put("data_offsets", JSONArray(listOf(0, 8))))
            .put("b.weight", JSONObject().put("dtype", "F8_E4M3").put("shape", JSONArray(listOf(2)))
                .put("data_offsets", JSONArray(listOf(8, 10))))
            .toString().toByteArray()
        f.outputStream().use { o ->
            o.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(header.size.toLong()).array())
            o.write(header); o.write(i64); o.write(f8)
        }
        TemplateLora.Safetensors(f).use { st ->
            assertEquals(16f, st.floats("a.alpha").single(), 0f)
            assertArrayEquals(floatArrayOf(1f, -2f), st.floats("b.weight"), 0f)
        }
    }

    /** ⭐ SDXL LoRAs often carry LDM/SGM block names; one formula covers SD 1.5 and SDXL. */
    @Test
    fun sgmPathFollowsLdmBlockNumbering() {
        assertEquals("input_blocks.4.1.transformer_blocks.0.attn1.to_q",
            TemplateLora.sgmPath("down_blocks.1.attentions.0.transformer_blocks.0.attn1.q"))
        assertEquals("input_blocks.8.1.transformer_blocks.9.attn2.to_out.0",
            TemplateLora.sgmPath("down_blocks.2.attentions.1.transformer_blocks.9.attn2.out"))
        assertEquals("input_blocks.2.1.transformer_blocks.0.ff.net.0.proj",
            TemplateLora.sgmPath("down_blocks.0.attentions.1.transformer_blocks.0.ff.proj"))
        assertEquals("output_blocks.2.1.transformer_blocks.0.attn1.to_k",
            TemplateLora.sgmPath("up_blocks.0.attentions.2.transformer_blocks.0.attn1.k"))
        assertEquals("output_blocks.3.1.transformer_blocks.1.ff.net.2",
            TemplateLora.sgmPath("up_blocks.1.attentions.0.transformer_blocks.1.ff.out"))
        assertEquals("middle_block.1.transformer_blocks.7.attn1.to_v",
            TemplateLora.sgmPath("mid_block.attentions.0.transformer_blocks.7.attn1.v"))
    }

    /** The same LoRA under SGM names packs to the very bytes it packs to under diffusers names. */
    @Test
    fun sgmNamedLoraPacksLikeDiffusersNamed() {
        val rnd = Random(11)
        val targets = TemplateLora.readTargets(targetsJson)
        val r = 4
        fun tensors(sgm: Boolean) = targets.list.flatMap { t ->
            val k = if (sgm) "lora_unet_" + TemplateLora.sgmPath(t.name)!!.replace('.', '_')
                else TemplateLora.kohyaPrefix(t.name)
            listOf(
                Triple("$k.lora_down.weight", listOf(r, t.din), FloatArray(r * t.din) { rnd.nextFloat() - 0.5f }),
                Triple("$k.lora_up.weight", listOf(t.dout, r), FloatArray(t.dout * r) { rnd.nextFloat() - 0.5f }),
                Triple("$k.alpha", emptyList(), floatArrayOf(2f)),
            )
        }
        val diffusers = tensors(sgm = false)
        // the SAME values, renamed
        val sgmNames = tensors(sgm = true).map { it.first }
        val sgm = diffusers.mapIndexed { i, (_, shape, data) -> Triple(sgmNames[i], shape, data) }
        val fd = tmp.newFile("d.safetensors").also { writeSafetensors(it, diffusers, f16 = false) }
        val fs = tmp.newFile("s.safetensors").also { writeSafetensors(it, sgm, f16 = false) }
        val od = tmp.newFolder("od"); val os = tmp.newFolder("os")
        val pd = TemplateLora.pack(fd, targets, od)
        val ps = TemplateLora.pack(fs, targets, os)
        assertEquals(targets.list.size, pd.matched)
        assertEquals(pd.matched, ps.matched)
        val names = od.list()!!.sorted()
        assertEquals(names, os.list()!!.sorted())
        for (n in names) assertArrayEquals(n, File(od, n).readBytes(), File(os, n).readBytes())
    }
}
