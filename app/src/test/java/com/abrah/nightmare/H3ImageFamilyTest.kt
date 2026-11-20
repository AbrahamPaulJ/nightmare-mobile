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

    /** ⭐ An H3 Image checkpoint imports (the user, 2026-11-20); the H3 VIDEO model's header is not taken for one. */
    @Test
    fun anH3ImageCheckpointIsRecognised() {
        val img = """{"video_patch_proj.weight":{},"token_refiner.blocks.0.attn.weight":{},"condition_proj.weight":{},""" +
            """"final_layer.video_out.weight":{},"blocks.0.attn.qkv.weight":{}}"""
        assertEquals(Family.H3IMAGE, CustomModels.ditFamilyOf(img))
        val video = img.dropLast(1) + ""","audio_patch_proj.weight":{},"final_layer.audio_out.weight":{}}"""
        assertEquals(null, CustomModels.ditFamilyOf(video))
        assertEquals(CustomModels.DitVariant.H3IMAGE, CustomModels.DitVariant.defaultFor(Family.H3IMAGE))
    }

    /** ⭐ Telegram #8301: H3 Image takes sides up to 4096; every other DiT stays at 2048. */
    @Test
    fun h3ImageGoesTo4096AndNothingElseDoes() {
        val h3 = ModelCatalog.byId("minimax_h3_image")!!
        assertEquals(4096, ModelCatalog.ditMax(h3.family))
        // ⚠ …but an area cap: 4096x4096 is kept at 3072x3072, the measured fit; 4K at 16:9 stays whole.
        assertEquals(Res(3072, 3072), ModelCatalog.keptSize(h3, 4096, 4096))
        assertEquals(Res(3840, 2176), ModelCatalog.keptSize(h3, 3840, 2160))
        assertTrue(ModelCatalog.ditAreaFits(h3.family, 4096, 2304))
        assertFalse(ModelCatalog.ditAreaFits(h3.family, 3584, 3584))
        val krea = ModelCatalog.byId("krea2_turbo")!!
        assertEquals(2048, ModelCatalog.keptSize(krea, 4096, 4096).width)
        assertEquals(4096, SdSampler.H3IMAGE.widgets.first { it.name == "width" }.max?.toInt())
        assertEquals(2048, SdSampler.KREA2.widgets.first { it.name == "width" }.max?.toInt())
    }
}
