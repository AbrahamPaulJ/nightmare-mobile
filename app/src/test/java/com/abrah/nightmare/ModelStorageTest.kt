package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ⭐ The models-folder move ([ModelStorage.moveRoots]) on temp dirs: it carries
 * every model subtree, MERGES into what is already there (a folder that
 * survived an uninstall is adopted, never overwritten), and a re-run after an
 * interruption finishes the job.
 */
class ModelStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, rel: String, text: String) =
        File(root, rel).apply { parentFile!!.mkdirs(); writeText(text) }

    private fun noop(p: ModelInstaller.Progress) {}

    @Test
    fun movesEveryModelTreeAndEmptiesTheSource() {
        val src = tmp.newFolder("app")
        val dst = tmp.newFolder("download")
        file(src, "models/qteamix/unet.bin", "unet")
        file(src, "models/_loras/a.safetensors", "lora")
        file(src, "embeddings/easyneg.safetensors", "emb")
        file(src, "npu/ctx/t2v_v79.bin", "ctx")
        file(src, "downloads/half.zip.part", "scratch")

        val kept = ModelStorage.moveRoots(src, dst, ::noop, { false })

        assertTrue(kept.isEmpty())
        assertEquals("unet", File(dst, "models/qteamix/unet.bin").readText())
        assertEquals("lora", File(dst, "models/_loras/a.safetensors").readText())
        assertEquals("emb", File(dst, "embeddings/easyneg.safetensors").readText())
        assertEquals("ctx", File(dst, "npu/ctx/t2v_v79.bin").readText())
        assertTrue(ModelStorage.contentsOf(src).isEmpty())
        // ⚠ Part-downloads are scratch, not models: they stay for TempCleaner.
        assertTrue(File(src, "downloads/half.zip.part").isFile)
        assertFalse(File(dst, "downloads").exists())
    }

    @Test
    fun whatIsAlreadyThereWinsAndTheSourceCopyIsReported() {
        val src = tmp.newFolder("app")
        val dst = tmp.newFolder("download")
        file(src, "models/qteamix/unet.bin", "new")
        file(src, "models/qteamix/clip.mnn", "clip")
        file(dst, "models/qteamix/unet.bin", "survived an uninstall")

        val kept = ModelStorage.moveRoots(src, dst, ::noop, { false })

        assertEquals(listOf(File(src, "models/qteamix/unet.bin")), kept)
        assertEquals("survived an uninstall", File(dst, "models/qteamix/unet.bin").readText())
        assertEquals("clip", File(dst, "models/qteamix/clip.mnn").readText())
        // ⚠ The kept file keeps its directory, so it is still listed as stranded.
        assertEquals(1, ModelStorage.contentsOf(src).size)
    }

    @Test
    fun aLeftoverHalfCopyDoesNotBlockTheRerun() {
        val src = tmp.newFolder("app")
        val dst = tmp.newFolder("download")
        file(src, "models/qteamix/unet.bin", "unet")
        file(dst, "models/qteamix/unet.bin.moving", "half")

        ModelStorage.moveRoots(src, dst, ::noop, { false })

        assertEquals("unet", File(dst, "models/qteamix/unet.bin").readText())
    }

    @Test
    fun onlyMovesTheNamedItems() {
        val src = tmp.newFolder("app")
        val dst = tmp.newFolder("download")
        file(src, "models/qteamix/unet.bin", "a")
        file(src, "models/anythingv5/unet.bin", "b")

        ModelStorage.moveRoots(src, dst, ::noop, { false }, only = setOf("models/qteamix"))

        assertTrue(File(dst, "models/qteamix/unet.bin").isFile)
        assertTrue(File(src, "models/anythingv5/unet.bin").isFile)
        assertFalse(File(dst, "models/anythingv5").exists())
    }
}
