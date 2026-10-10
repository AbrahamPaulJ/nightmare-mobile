package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ⭐ Local Dream's models used in place (`LocalDreamModels`, the user's call 2026-10-09): found,
 * resolved, launched from — and never written or deleted.
 */
@RunWith(RobolectricTestRunner::class)
class LocalDreamModelsTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var ld: File

    @Before
    fun setUp() {
        ModelCatalog.root(ctx).deleteRecursively()
        ld = kotlin.io.path.createTempDirectory("ld").toFile()
        LocalDreamModels.rootOverride = ld
    }

    @After
    fun tearDown() {
        LocalDreamModels.rootOverride = null
        ld.deleteRecursively()
        ModelCatalog.root(ctx).deleteRecursively()
        CustomModels.scan(ctx)
    }

    private fun sd15(dir: File, extra: List<String> = emptyList()) {
        dir.mkdirs()
        for (f in ModelCatalog.SD15_REQUIRED + extra) File(dir, f).writeBytes(byteArrayOf(1))
    }

    @Test
    fun aCatalogueModelResolvesToLocalDreamsCopyOnlyWhenOursIsNotComplete() {
        val spec = ModelCatalog.byId("cuteyukimix")!!
        assertFalse(spec.installed(ctx))
        sd15(File(ld, "cuteyukimix"))
        assertTrue("Local Dream's complete copy is not used", spec.installed(ctx))
        assertEquals(File(ld, "cuteyukimix").canonicalPath, spec.dir(ctx).canonicalPath)
        assertTrue(spec.fromLocalDream(ctx))
        // ⭐ Ours, once complete, wins.
        sd15(spec.ownDir(ctx))
        assertEquals(spec.ownDir(ctx).canonicalPath, spec.dir(ctx).canonicalPath)
        assertFalse(spec.fromLocalDream(ctx))
    }

    @Test
    fun anIncompleteLocalDreamCopyIsNotInstalled() {
        val dir = File(ld, "cuteyukimix").apply { mkdirs() }
        File(dir, "unet.bin").writeBytes(byteArrayOf(1))
        val spec = ModelCatalog.byId("cuteyukimix")!!
        assertFalse(spec.installed(ctx))
        assertFalse(spec.fromLocalDream(ctx))
    }

    @Test
    fun deletingLocalDreamsCopyIsRefusedAndNothingIsRemoved() {
        sd15(File(ld, "cuteyukimix"))
        val spec = ModelCatalog.byId("cuteyukimix")!!
        try {
            ModelInstaller.delete(ctx, spec, force = true)
            fail("deleted from Local Dream's folder")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Local Dream"))
        }
        assertTrue(File(ld, "cuteyukimix/unet.bin").isFile)
    }

    @Test
    fun itsImportsAndSdxlBuiltInsAreScannedWithTheirFolders() {
        // An import with Local Dream's own marker…
        File(ld, "my_sdxl").apply { mkdirs(); File(this, "SDXL").createNewFile() }
        // …a built-in SDXL this catalogue lacks (no marker — known by id)…
        File(ld, "illustrious_v16").mkdirs()
        // …a CPU model (skipped: no CPU path here)…
        File(ld, "cpu_one").apply { mkdirs(); File(this, "finished").createNewFile(); File(this, "unet.mnn").writeBytes(byteArrayOf(1)) }
        // …a folder with no marker at all (not guessed)…
        File(ld, "mystery").mkdirs()
        // …and a built-in id ours has (resolved through the catalogue, not scanned).
        sd15(File(ld, "anythingv5"))
        val found = CustomModels.scan(ctx).associateBy { it.id }
        val imp = assertNotNull(found["my_sdxl"]).let { found["my_sdxl"]!! }
        assertEquals(Family.SDXL, imp.family)
        assertEquals(File(ld, "my_sdxl").absolutePath, imp.home)
        assertEquals("Illustrious v16", found["illustrious_v16"]!!.label)
        assertEquals(Family.SDXL, found["illustrious_v16"]!!.family)
        assertNull(found["cpu_one"])
        assertNull(found["mystery"])
        assertNull("a catalogue id was scanned as an import", found["anythingv5"])
        // ⚠ Nothing was written into Local Dream's folder.
        assertEquals(listOf("SDXL"), File(ld, "my_sdxl").list()!!.toList())
        assertTrue(File(ld, "mystery").list()!!.isEmpty())
    }
}
