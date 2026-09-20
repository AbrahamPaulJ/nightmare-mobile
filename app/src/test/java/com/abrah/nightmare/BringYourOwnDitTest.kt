package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ **A DiT package accepts the user's own weights, and nothing else about
 * it relaxes.**
 *
 * `docs/ROADMAP.md` §2b: a DiT family loads a plain `.safetensors` at run time,
 * so a community fine-tune of the same architecture is a supportable swap. The
 * exact-size check is what stood in the way — any other file read as "not
 * installed" forever and the Run was refused before the engine saw it.
 *
 * ⚠⚠ The marker is the mechanism, deliberately not "skip the check when the
 * size differs" — that would quietly accept a half-downloaded 6 GB file, which
 * is the exact failure the size check exists for.
 */
@RunWith(RobolectricTestRunner::class)
class BringYourOwnDitTest {

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val spec = ModelCatalog.ditModels.first { it.id == "z_image_turbo" }

    private fun dir(): File = spec.dir(ctx).apply { mkdirs() }

    /** Every file at its published size — a normal, complete install. */
    private fun writeStock() {
        for (f in spec.files) File(dir(), f.name).writeBytes(ByteArray(0)).also {
            // ⚠ Sparse: a 6 GB test file is not on. `setLength` gives the size
            // `missing()` reads without the bytes.
            java.io.RandomAccessFile(File(dir(), f.name), "rw").use { raf -> raf.setLength(f.bytes) }
        }
    }

    @After
    fun clean() {
        dir().deleteRecursively()
    }

    @Test
    fun aCompleteStockInstallIsInstalled() {
        writeStock()
        assertTrue("a stock package should be installed", spec.installed(ctx))
    }

    /** ⚠ The control: without the marker, a swapped file is still refused. */
    @Test
    fun aSwappedFileIsRefusedWithoutTheMarker() {
        writeStock()
        java.io.RandomAccessFile(File(dir(), ModelSpec.DIT_WEIGHTS), "rw").use { it.setLength(1234) }
        assertFalse("a wrong-sized DiT must not read as installed", spec.installed(ctx))
        assertEquals(listOf(ModelSpec.DIT_WEIGHTS), spec.missing(ctx))
    }

    @Test
    fun theMarkerAcceptsTheUsersOwnWeights() {
        writeStock()
        java.io.RandomAccessFile(File(dir(), ModelSpec.DIT_WEIGHTS), "rw").use { it.setLength(1234) }
        File(dir(), ModelSpec.BRING_YOUR_OWN).writeBytes(ByteArray(0))
        assertTrue("the marker should accept a user's DiT weights", spec.installed(ctx))
    }

    /**
     * ⚠⚠⚠ The marker excuses the SIZE, never the FILE. An empty or absent
     * `dit.safetensors` is still missing — otherwise a failed copy would look
     * like a working model and fail inside the engine instead.
     */
    @Test
    fun theMarkerDoesNotExcuseAnEmptyFile() {
        writeStock()
        File(dir(), ModelSpec.DIT_WEIGHTS).delete()
        File(dir(), ModelSpec.BRING_YOUR_OWN).writeBytes(ByteArray(0))
        assertFalse("an absent DiT must still be missing", spec.installed(ctx))
    }

    /** ⚠ And it excuses only the DiT: the shared three stay exact. */
    @Test
    fun theSharedFilesAreStillChecked() {
        writeStock()
        File(dir(), ModelSpec.BRING_YOUR_OWN).writeBytes(ByteArray(0))
        java.io.RandomAccessFile(File(dir(), "vae.safetensors"), "rw").use { it.setLength(99) }
        assertFalse("a truncated VAE must still be missing", spec.installed(ctx))
        assertTrue("vae.safetensors" in spec.missing(ctx))
    }
}
