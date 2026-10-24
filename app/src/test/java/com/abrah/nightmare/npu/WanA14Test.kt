package com.abrah.nightmare.npu

import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * ⭐ Wan 2.2 A14B ([WanA14Node], `docs/VIDEO-GGML.md` §8): its profile against Turbo's, its launch environment
 * (engine `dit/044`) and its catalogue entry.
 */
class WanA14Test {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theFourteenBHasFourTimesTheTokensAndSixteenFps() {
        // 832x480, 2 s: Turbo 26x15 tokens a latent frame, 13 frames (49 at 24 fps); the 14B 52x30, 9 frames (33 at 16).
        assertEquals(26L * 15 * 13, WanVideoNode.tokens(832, 480, 49))
        assertEquals(52L * 30 * 9, WanVideoNode.tokens(832, 480, 33, WanVideoNode.A14B))
        assertEquals(33, WanVideoNode.framesFor(2, WanVideoNode.A14B.fps))
        assertEquals(33, WanVideoNode.framesOf(Node("v", WanA14Node.name, mapOf("seconds" to "2")), mapOf("seconds" to "2")))
        assertEquals(49, WanVideoNode.framesOf(Node("v", WanVideoNode.name, mapOf("seconds" to "2")), mapOf("seconds" to "2")))
    }

    @Test
    fun theFourteenBAlwaysStreams() {
        val prof = WanVideoNode.A14B
        val pinned = WanVideoNode.pinnedBytes(832, 480, 33, prof)
        // ⚠ Even a 16 GB phone with the switch off: two 8.6 GB experts are held by no phone.
        assertEquals("4", WanVideoNode.memoryBudget(pinned, 16L shl 30, 79, stream = false, prof = prof))
        // Turbo at the same small clip stays resident there.
        assertEquals(null, WanVideoNode.memoryBudget(WanVideoNode.pinnedBytes(832, 480, 49), 16L shl 30, 79, stream = false))
        assertEquals("euler", prof.sampler)
        assertEquals(WanVideoNode.A14B, WanVideoNode.profileOf(WanA14Node.name))
        assertEquals(WanVideoNode.TURBO, WanVideoNode.profileOf(WanVideoNode.name))
    }

    @Test
    fun theEngineIsToldTheHighNoiseExpertAndBothLoras() {
        val dir = tmp.newFolder("wan22_t2v_a14b")
        // A 5B folder: nothing.
        assertEquals(emptyMap<String, String>(), BackendProcess.wanA14Env(dir))
        listOf(ModelCatalog.WAN_HIGH_DIT, ModelCatalog.WAN_HIGH_LORA, ModelCatalog.WAN_LOW_LORA).forEach { java.io.File(dir, it).writeText("x") }
        val env = BackendProcess.wanA14Env(dir)
        assertEquals(java.io.File(dir, ModelCatalog.WAN_HIGH_DIT).absolutePath, env["NM_WAN_HIGH_NOISE_MODEL"])
        assertEquals(java.io.File(dir, ModelCatalog.WAN_HIGH_LORA).absolutePath, env["NM_WAN_HIGH_LORA"])
        assertEquals(java.io.File(dir, ModelCatalog.WAN_LOW_LORA).absolutePath, env["NM_WAN_LOW_LORA"])
    }

    @Test
    fun theCatalogueEntryBorrowsTurbosEncoderButNotItsDecoder() {
        val a14 = ModelCatalog.WAN22_A14B
        assertTrue(a14 in ModelCatalog.video)
        assertEquals("wan22_ti2v_turbo", a14.partsFrom)
        assertEquals(ModelCatalog.WAN, a14.backendType)
        fun url(spec: com.abrah.nightmare.ModelSpec, name: String) = spec.files.single { it.name == name }.url
        assertEquals(url(ModelCatalog.WAN22, "umt5.gguf"), url(a14, "umt5.gguf"))
        // ⚠ The Wan 2.1 latent space: taew2_1, never Turbo's taew2_2.
        assertTrue(url(a14, "tae.safetensors").endsWith("taew2_1.safetensors"))
        assertEquals(ModelCatalog.DIT_MIN_ARCH, a14.builds.single().minArch)
    }
}
