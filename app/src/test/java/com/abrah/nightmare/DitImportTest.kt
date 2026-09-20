package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ Importing a plain `.safetensors` as a DiT model — the feature proven on
 * device 2026-09-21 (`docs/ROADMAP.md` §2b), with a button on it.
 *
 * ⚠ These cover the parts that are OURS: the structural check, the marker, the
 * shared parts and the refusals. Whether a given file is really a Z-Image is
 * the engine's job and it does it properly; nothing here pretends otherwise.
 */
@RunWith(RobolectricTestRunner::class)
class DitImportTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val zimage = ModelCatalog.ditModels.first { it.id == "z_image_turbo" }

    /** A minimal but STRUCTURALLY REAL safetensors: 8-byte length, then JSON. */
    private fun safetensors(json: String): ByteArray {
        val body = json.toByteArray()
        val head = ByteArray(8)
        var n = body.size.toLong()
        for (i in 0 until 8) { head[i] = (n and 0xFF).toByte(); n = n shr 8 }
        return head + body + ByteArray(64)
    }

    private fun installBuiltIn() {
        val d = zimage.dir(ctx).apply { mkdirs() }
        for (f in zimage.files) {
            java.io.RandomAccessFile(File(d, f.name), "rw").use { it.setLength(f.bytes) }
        }
    }

    @After
    fun clean() {
        ModelCatalog.root(ctx).deleteRecursively()
        // ⚠⚠ `CustomModels.scanned` is object state and OUTLIVES the test,
        // so a leftover entry would let [itIsInTheCatalogueAfterImporting]
        // pass on the previous test's scan. Scanning an empty root clears it.
        CustomModels.scan(ctx)
    }

    @Test
    fun itImportsAndIsRecognisedAsZImage() {
        installBuiltIn()
        val spec = CustomModels.importDit(
            ctx, "myzit", Family.ZIMAGE,
            open = { ByteArrayInputStream(safetensors("""{"a.weight":{"dtype":"F8_E4M3"}}""")) },
        )
        assertEquals(Family.ZIMAGE, spec.family)
        assertEquals(ModelCatalog.ZIMAGE, spec.backendType)
        assertTrue("the family marker was not written", File(spec.dir(ctx), CustomModels.ZIMAGE_MARK).isFile)
        assertTrue("the byo marker was not written", File(spec.dir(ctx), ModelSpec.BRING_YOUR_OWN).isFile)
        // ⭐ The point of the whole exercise: installed WITHOUT its own copy of
        // the 2.6 GB of shared parts.
        assertTrue("an imported model should read as installed", spec.installed(ctx))
        assertTrue(
            "the shared parts were copied into the model instead of shared",
            !File(spec.dir(ctx), "llm.gguf").exists(),
        )
        assertTrue(
            "the shared parts are missing",
            File(File(ModelCatalog.root(ctx), CustomModels.DIT_SHARED), "llm.gguf").exists(),
        )
    }

    /**
     * ⭐⭐⭐ **A turbo checkpoint imports with TURBO defaults.**
     *
     * ⚠⚠⚠ Shipped wrong on 2026-09-21: `customSpec` set the knobs with
     * `if (family == ANIMA) … else DEFAULT_*`, the DiT families were added to
     * the detection and not to that chain, and an imported Z-Image inherited
     * SD's `dpm`/20/7.5. It RENDERED — at roughly five times the cost, because
     * cfg above 1 makes the engine run the unconditional pass too — which is
     * why only a stopwatch on the phone caught it. ⇒ Assert the numbers, per
     * family, against the built-in they are copied from.
     */
    @Test
    fun itImportsWithTheFamilysDefaultsNotSds() {
        installBuiltIn()
        val spec = CustomModels.importDit(
            ctx, "myzit", Family.ZIMAGE,
            open = { ByteArrayInputStream(safetensors("""{"a.weight":{"dtype":"F8_E4M3"}}""")) },
        )
        val builtIn = ModelCatalog.byId("z_image_turbo")!!
        assertEquals("steps", builtIn.steps, spec.steps)
        assertEquals("cfg", builtIn.cfg, spec.cfg, 0.0)
        assertEquals("scheduler", builtIn.scheduler, spec.scheduler)
        // ⚠ The arch floor belongs to the engine, so an import knows it even
        // though a QNN import cannot know its own.
        assertEquals("arch floor", ModelCatalog.DIT_MIN_ARCH, spec.minHtpArch)
        assertEquals("lowram", builtIn.lowram, spec.lowram)
    }

    /**
     * ⚠⚠ The same assertion for FLUX.2, and it is not redundant: `lowram`
     * was `family != SD15`, so the two DiT families disagreed with each other
     * and Klein's built-in says `false` on purpose (`docs/LEGACY.md`).
     */
    @Test
    fun anImportedKleinDoesNotAskForLowram() {
        val klein = ModelCatalog.byId("flux2_klein_4b")!!
        val d = klein.dir(ctx).apply { mkdirs() }
        for (f in klein.files) {
            java.io.RandomAccessFile(File(d, f.name), "rw").use { it.setLength(f.bytes) }
        }
        val spec = CustomModels.importDit(
            ctx, "myklein", Family.FLUX2,
            open = { ByteArrayInputStream(safetensors("""{"a.weight":{"dtype":"F8_E4M3"}}""")) },
        )
        assertEquals("lowram", klein.lowram, spec.lowram)
        assertEquals("steps", klein.steps, spec.steps)
        assertEquals("cfg", klein.cfg, spec.cfg, 0.0)
    }

    /**
     * ⭐⭐⭐ **An import weighs what it LOADS, not what its folder holds.**
     *
     * ⚠⚠⚠ The bug: the shared directory saved 2.6 GB of disk and made the
     * model look 2.6 GB lighter to the residency gate, which releases a
     * checkpoint over 65% of device RAM between runs. The built-in was
     * released and the import — the same weights — was not, so the SECOND
     * run was killed by lmkd. ⇒ An imported DiT must weigh the same as the
     * built-in it borrows from.
     */
    @Test
    fun anImportWeighsTheSameAsTheBuiltInItBorrowsFrom() {
        installBuiltIn()
        val spec = CustomModels.importDit(
            ctx, "myzit", Family.ZIMAGE,
            open = { ByteArrayInputStream(safetensors("""{"a.weight":{"dtype":"F8_E4M3"}}""")) },
        )
        val shared = ModelCatalog.DIT_REQUIRED
            .filter { it != ModelSpec.DIT_WEIGHTS }
            .sumOf { n -> zimage.files.first { it.name == n }.bytes }
        assertTrue(
            "the shared parts must not count as disk in this directory",
            spec.bytesOnDisk(ctx) < shared,
        )
        assertEquals(
            "a launch loads the weights AND the shared parts",
            spec.bytesOnDisk(ctx) + shared,
            spec.loadedBytes(ctx),
        )
    }

    /**
     * ⭐⭐⭐ **The catalogue knows it, not just the caller.**
     *
     * ⚠⚠ The bug this is here for shipped: [CustomModels.importDit] built
     * a [ModelSpec] and returned it without re-scanning, so the very next
     * `selectModel` hit `require(ModelCatalog.byId(newId) != null)` and a
     * 6 GB import that had worked perfectly reported "import failed: unknown
     * model". ⇒ An import is not finished until [ModelCatalog.byId] answers.
     */
    @Test
    fun itIsInTheCatalogueAfterImporting() {
        installBuiltIn()
        CustomModels.importDit(
            ctx, "myzit", Family.ZIMAGE,
            open = { ByteArrayInputStream(safetensors("""{"a.weight":{"dtype":"F8_E4M3"}}""")) },
        )
        assertTrue(
            "the imported model is not in the catalogue, so it cannot be selected",
            ModelCatalog.byId("myzit") != null,
        )
        assertTrue(
            "the imported model is not in CustomModels.registered",
            CustomModels.registered.any { it.id == "myzit" },
        )
    }

    /**
     * ⚠⚠ The prerequisite, said rather than discovered. There is nowhere else
     * to get the text encoder and VAE from.
     */
    @Test
    fun itRefusesWhenTheBuiltInIsNotInstalled() {
        val e = runCatching {
            CustomModels.importDit(
                ctx, "myzit", Family.ZIMAGE,
                open = { ByteArrayInputStream(safetensors("""{"a":{}}""")) },
            )
        }.exceptionOrNull()
        assertTrue("expected a refusal, got $e", e != null)
        assertTrue("the reason does not mention installing first: ${e?.message}",
            e!!.message!!.contains("install"))
    }

    /** ⚠ A zip, or anything that is not a safetensors, is refused up front. */
    @Test
    fun itRefusesSomethingThatIsNotASafetensors() {
        installBuiltIn()
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(256)
        val e = runCatching {
            CustomModels.importDit(ctx, "nope", Family.ZIMAGE, open = { ByteArrayInputStream(zip) })
        }.exceptionOrNull()
        assertTrue("a zip should be refused", e != null)
        assertTrue("nothing should be left behind", !File(ModelCatalog.root(ctx), "nope").exists())
    }

    /**
     * ⭐ An SD/SDXL checkpoint is the likely mistake, and it needs the
     * conversion pipeline rather than a copy — so it is named, not just refused.
     */
    @Test
    fun itNamesAnSdxlCheckpointRatherThanJustFailing() {
        installBuiltIn()
        val sdxl = safetensors("""{"conditioner.embedders.0.transformer.x":{"dtype":"F16"}}""")
        val e = runCatching {
            CustomModels.importDit(ctx, "wrong", Family.ZIMAGE, open = { ByteArrayInputStream(sdxl) })
        }.exceptionOrNull()
        assertTrue("expected a refusal", e != null)
        assertTrue("it should say what the file looks like: ${e?.message}",
            e!!.message!!.contains("SDXL"))
    }
}
