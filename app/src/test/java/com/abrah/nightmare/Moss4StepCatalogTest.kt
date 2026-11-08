package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * ⭐ MOSS-Image SFT at 4 steps shares SFT's DiT file. ⚠ The first build called it installed with 1.2 GB on disk, and its
 * launch died because the backend reads `dit.gguf` from `--model_dir` alone. The launch must go to the folder that has it.
 */
class Moss4StepCatalogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val spec = ModelCatalog.byId("moss_image_4step")!!

    /** A sparse file of the right length: [ModelSpec.borrows] compares sizes. */
    private fun place(dir: File, name: String, bytes: Long) {
        dir.mkdirs()
        RandomAccessFile(File(dir, name), "rw").use { it.setLength(bytes) }
    }

    private fun bytesOf(name: String) = spec.active.first { it.name == name }.bytes

    @Test
    fun aDiTHeldByTheDonorLaunchesFromTheDonor() {
        val own = File(tmp.root, "moss_image_4step")
        val donor = File(tmp.root, "moss_image")
        own.mkdirs()
        place(donor, "dit.gguf", bytesOf("dit.gguf"))
        assertEquals(donor, spec.launchDir(own))
    }

    @Test
    fun itsOwnDiTLaunchesFromItsOwnFolder() {
        val own = File(tmp.root, "moss_image_4step")
        val donor = File(tmp.root, "moss_image")
        place(own, "dit.gguf", bytesOf("dit.gguf"))
        place(donor, "dit.gguf", bytesOf("dit.gguf"))
        assertEquals(own, spec.launchDir(own))
    }

    /** ⭐ No donor: everything is fetched, and the launch stays home. */
    @Test
    fun noDonorStaysHome() {
        val own = File(tmp.root, "moss_image_4step")
        own.mkdirs()
        assertEquals(own, spec.launchDir(own))
    }

    /** ⚠ The 4-step LoRA is not the donor's 8-step one of the same name: it must always be its own file. */
    @Test
    fun theLoraIsNeverBorrowed() {
        val own = File(tmp.root, "moss_image_4step")
        val donor = File(tmp.root, "moss_image")
        own.mkdirs()
        val lora = spec.active.first { it.name == spec.ownLora }
        place(donor, lora.name, lora.bytes)
        assertTrue(!spec.borrows(own, lora))
    }

    /** ⚠ A model with its own DiT url never moves, however alike the sizes: MOSS Base beside SFT (Saved #6990). */
    @Test
    fun baseKeepsItsOwnFolder() {
        val base = ModelCatalog.byId("moss_image_base")!!
        val own = File(tmp.root, "moss_image_base")
        val donor = File(tmp.root, "moss_image")
        own.mkdirs()
        place(donor, "dit.gguf", base.active.first { it.name == "dit.gguf" }.bytes)
        assertEquals(own, base.launchDir(own))
    }
}
