package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ `.gguf` DiT imports, and the two variants that bring their OWN parts —
 * Klein 9B and Krea 2 (users' reports and GitHub #5, 2026-10-01).
 *
 * ⚠ The tensor names and shapes below are copied from the real headers, read
 * off Hugging Face 2026-10-01: `leejet/FLUX.2-klein-9B-GGUF` (Q4_0) and
 * `gguf-org/krea-2-gguf` (MXFP4). GGUF stores dims innermost-first, so Klein
 * 9B's `img_in.weight` is `[128, 4096]` IN THE FILE.
 */
@RunWith(RobolectricTestRunner::class)
class GgufImportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A structurally real GGUF v3 header: one string kv, one array kv, the tensors. */
    private fun gguf(tensors: List<Triple<String, List<Long>, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun buf(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
        fun str(s: String) { val b = s.toByteArray(); out.write(buf(8).putLong(b.size.toLong()).array()); out.write(b) }
        out.write(GgufHeader.MAGIC)
        out.write(buf(4).putInt(3).array())
        out.write(buf(8).putLong(tensors.size.toLong()).array())
        out.write(buf(8).putLong(2).array())
        str("general.architecture"); out.write(buf(4).putInt(8).array()); str("flux")
        str("comfy.gguf.orig_shape.x"); out.write(buf(4).putInt(9).array())
        out.write(buf(4).putInt(5).array()); out.write(buf(8).putLong(2).array())
        out.write(buf(8).putInt(3840).putInt(64).array())
        for ((name, dims, type) in tensors) {
            str(name)
            out.write(buf(4).putInt(dims.size).array())
            dims.forEach { out.write(buf(8).putLong(it).array()) }
            out.write(buf(4).putInt(type).array())
            out.write(buf(8).putLong(0).array())
        }
        out.write(ByteArray(64))
        return out.toByteArray()
    }

    private val klein9b = gguf(listOf(
        Triple("double_blocks.0.img_attn.qkv.weight", listOf(4096L, 12288L), 2),
        Triple("single_blocks.0.linear1.weight", listOf(4096L, 36864L), 2),
        Triple("img_in.weight", listOf(128L, 4096L), 30),
        Triple("txt_in.weight", listOf(12288L, 4096L), 30),
    ))

    private val krea2 = gguf(listOf(
        Triple("blocks.0.attn.qknorm.knorm.scale", listOf(128L), 0),
        Triple("blocks.0.attn.wq.weight", listOf(6144L, 6144L), 39),
        Triple("tmlp.0.weight", listOf(256L, 6144L), 40),
        Triple("tproj.1.weight", listOf(6144L, 36864L), 40),
        Triple("txtfusion.projector.weight", listOf(12L), 0),
    ))

    private fun sparse(f: File, bytes: Long) {
        f.parentFile?.mkdirs()
        sparseFile(f, bytes)
    }

    /** The built-in a variant takes its parts from, as sparse files of the right sizes. */
    private fun installBuiltIn(id: String) {
        val spec = ModelCatalog.ditModels.first { it.id == id }
        val d = spec.dir(ctx).apply { mkdirs() }
        for (f in spec.files) sparse(File(d, f.name), f.bytes)
    }

    private fun fakeEngine() {
        sparse(DitEngine.file(ctx), DitEngine.FILE_BYTES)
        File(DitEngine.dir(ctx), ".dit-engine").writeText(DitEngine.URL)
    }

    @After
    fun clean() {
        ModelCatalog.root(ctx).deleteRecursively()
        CustomModels.scan(ctx)
    }

    @Test
    fun theTableIsReadInTorchOrderWithItsTypes() {
        val t = GgufHeader.parse(klein9b)
        assertEquals(4, t.tensors)
        assertTrue(t.json, t.json.contains(""""img_in.weight":{"dtype":"BF16","shape":[4096,128]}"""))
        assertEquals(setOf("Q4_0", "BF16"), t.types)
        assertTrue(CustomModels.isKlein9b(t.json))
        assertEquals(Family.FLUX2, CustomModels.ditFamilyOf(t.json))
        assertEquals(Family.KREA2, CustomModels.ditFamilyOf(GgufHeader.parse(krea2).json))
    }

    /**
     * ⭐⭐ A 9B fine-tune: its own Qwen3-8B parts, `te=disk`, a 768² start.
     * ⚠ A folder made by hand, not an import: an import copies 5 GB of real
     * parts, and Windows has no sparse files to fake them with.
     */
    @Test
    fun aKlein9bGgufFolderIsAdoptedAsTheNineB() {
        val d = File(ModelCatalog.root(ctx), "my9b").apply { mkdirs() }
        File(d, "dit.gguf").writeBytes(klein9b)
        val spec = CustomModels.scan(ctx).single { it.id == "my9b" }
        assertEquals(Family.FLUX2, spec.family)
        assertTrue(File(d, "KLEIN").isFile && File(d, CustomModels.KLEIN9B_MARK).isFile)
        assertTrue(spec.ownDitParts)
        assertTrue("the 9B streams its encoder like the built-in", spec.lowram)
        assertEquals(Res(768, 768), spec.startRes)
        // ⚠ Its parts are its OWN: the shared 4B encoder must not satisfy it.
        File(ModelCatalog.root(ctx), CustomModels.DIT_SHARED).apply { mkdirs() }
            .let { File(it, "llm.gguf").writeBytes(ByteArray(8)) }
        assertEquals(listOf("llm.gguf", "vae.safetensors", "tokenizer.json"), spec.missing(ctx))
    }

    /** ⭐ …and the plan puts every part in the model's own directory. */
    @Test
    fun theNineBsPartsGoInItsOwnDirectory() {
        val d = File(ModelCatalog.root(ctx), "my9b")
        val plan = CustomModels.ditPartsPlan(ctx, CustomModels.DitVariant.KLEIN_9B, d)
        assertEquals(setOf("llm.gguf", "vae.safetensors", "tokenizer.json"), plan.map { it.file.name }.toSet())
        assertTrue(plan.all { it.dest.parentFile == d })
        assertEquals(4_787_332_640L, plan.first { it.file.name == "llm.gguf" }.file.bytes)
    }

    @Test
    fun aKreaGgufFolderIsKrea2() {
        val d = File(ModelCatalog.root(ctx), "mykrea").apply { mkdirs() }
        File(d, "dit.gguf").writeBytes(krea2)
        val spec = CustomModels.scan(ctx).single { it.id == "mykrea" }
        assertEquals(Family.KREA2, spec.family)
        assertEquals(ModelCatalog.KREA2, spec.backendType)
        assertTrue(File(d, CustomModels.KREA2_MARK).isFile)
        assertEquals(listOf("dit.gguf", "llm.gguf", "vae.safetensors", "tokenizer.json"), spec.requiredFiles)
    }

    @Test
    fun aKreaFileOnTheFluxTabIsSentToItsOwnTab() {
        fakeEngine()
        val e = runCatching {
            CustomModels.importDit(ctx, "wrong", Family.FLUX2, open = { ByteArrayInputStream(krea2) })
        }.exceptionOrNull()
        assertTrue("expected a refusal naming Krea 2: ${e?.message}", e?.message?.contains("Krea 2") == true)
    }

    /** ⚠ A warning on the row, from the weights against RAM — never a refusal. */
    @Test
    fun aNineGigabyteImportIsTightOnSixteenGigabytes() {
        val d = File(ModelCatalog.root(ctx), "big9b").apply { mkdirs() }
        File(d, "dit.gguf").writeBytes(klein9b)
        val spec = CustomModels.scan(ctx).single { it.id == "big9b" }
        // ⚠ The reported 9.74 GB on 16 GB vs 24 GB, scaled to MB: Windows has
        // no sparse files, and a 9.74 GB fixture fills the build machine.
        sparse(File(d, "dit.gguf"), 9_740_000L)
        assertTrue(CustomModels.ramTight(spec, ctx, 16_000_000L))
        assertFalse(CustomModels.ramTight(spec, ctx, 24_000_000L))
    }
}
