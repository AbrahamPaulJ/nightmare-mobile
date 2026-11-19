package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ⭐ MiniMax H3 Image (`docs/MINIMAX-H3.md` §7): a `krea2` launch on FastH3's text route, streamed on every phone. */
class H3ImageFamilyTest {

    private val spec = ModelCatalog.byId("minimax_h3_image")!!
    private val gb = 1L shl 30

    @Test
    fun theBuiltInCarriesItsOwnVaeAndClipProj() {
        assertEquals(Family.H3IMAGE, spec.family)
        assertEquals(ModelCatalog.KREA2, spec.backendType)
        assertEquals(6, spec.steps)
        val names = spec.files.map { it.name }
        for (f in listOf("dit.gguf", "vae.safetensors", ModelCatalog.H3_CLIPPROJ, "llm.gguf", "tokenizer.json")) {
            assertTrue("$f in $names", f in names)
        }
        // ⚠ Krea 2 lends its 4B and tokenizer, never its Wan VAE: borrowing matches the URL ([ModelSpec.borrows]).
        assertEquals("krea2_turbo", spec.partsFrom)
        val krea = ModelCatalog.byId("krea2_turbo")!!
        val vae = spec.files.first { it.name == "vae.safetensors" }
        assertFalse(krea.files.any { it.name == vae.name && it.url == vae.url })
        assertTrue(krea.files.any { it.name == "llm.gguf" && it.url == spec.files.first { f -> f.name == "llm.gguf" }.url })
        assertTrue(spec.licence!!.url.contains("MiniMax-H3-Image"))
    }

    @Test
    fun theEightGenThreeRunsIt() {
        // The S25 forced to v75 at the 2.75 GiB below-v79 budget: the v79 picture, 115 s at 1024² against 105 s.
        assertEquals(ModelCatalog.DIT_GGML_MIN_ARCH, spec.best!!.minArch)
        assertEquals(75, spec.best!!.minArch)
    }

    @Test
    fun itStreamsOnEveryPhone() {
        assertEquals("the 12 GB S25", BackendProcess.H3_IMAGE_BUDGET, BackendProcess.kreaBudget(spec, 12 * gb, 7 * gb))
        assertEquals("a 24 GB phone", BackendProcess.H3_IMAGE_BUDGET, BackendProcess.kreaBudget(spec, 24 * gb, 18 * gb))
    }

    @Test
    fun textToImageOnlyAtItsOwnSchedule() {
        assertFalse(SdSampler.H3IMAGE.inpaint)
        assertEquals(Family.H3IMAGE, SdSampler.H3IMAGE.family)
    }
}
