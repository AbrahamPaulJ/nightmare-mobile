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
        // ⭐ 832x480 5 s (32,760 tokens) needs 4.19 GB a segment (S25, dit/046): 4.5 GiB.
        assertEquals("4.5", WanVideoNode.memoryBudget(WanVideoNode.pinnedBytes(832, 480, 81, prof), 12L shl 30, 79, stream = false, prof = prof))
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
        // ⭐ The decode stays on the NPU (direct conv): no backend override.
        assertEquals(null, env["NM_DIT_BACKEND"])
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

    @Test
    fun aWiredPictureRunsTheI2VExperts() {
        val t2v = Node("v", WanA14Node.name)
        val i2v = Node("v", WanA14Node.name, inputs = mapOf("image" to com.abrah.nightmare.Source("pic")))
        assertEquals(ModelCatalog.WAN22_A14B, WanVideoNode.specOf(t2v))
        assertEquals(ModelCatalog.WAN22_A14B_I2V, WanVideoNode.specOf(i2v))
        assertEquals("Image to video (14B)", WanA14Node.titleFor(i2v))
        // ⭐ The 5B's and Fun's nodes are their models, picture or not.
        assertEquals(ModelCatalog.WAN22, WanVideoNode.specOf(Node("w", WanVideoNode.name, inputs = i2v.inputs)))
        assertEquals(ModelCatalog.WAN22_FUN, WanVideoNode.specOf(Node("f", WanFunNode.name)))
        assertEquals(null, WanVideoNode.specOf(Node("p", "core.prompt")))
        // ⚠ The two pairs of experts are two backends: switching must relaunch.
        assertTrue(WanA14Node.contextKey(t2v) != WanA14Node.contextKey(i2v))
    }

    @Test
    fun theI2VExpertsLaunchWithTheirBoundaryAndAChunkedEncode() {
        val dir = tmp.newFolder(ModelCatalog.WAN22_A14B_I2V.id)
        java.io.File(dir, ModelCatalog.WAN_HIGH_DIT).writeText("x")
        val env = BackendProcess.wanA14Env(dir)
        assertEquals(java.io.File(dir, ModelCatalog.WAN_HIGH_DIT).absolutePath, env["NM_WAN_HIGH_NOISE_MODEL"])
        assertEquals("0.9", env["NM_WAN_MOE_BOUNDARY"])
        // ⚠ Distilled: no Lightning LoRAs.
        assertEquals(null, env["NM_WAN_HIGH_LORA"])
        assertEquals(null, env["NM_WAN_LOW_LORA"])
        // ⚠ An engine before dit/052: a chunk's content (chunk - 8 of overlap) under 8 aborts its slicing.
        val chunk = env.getValue("SD_TAEHV_ENCODE_CHUNK").toInt()
        assertTrue("chunk $chunk", chunk >= 16 && chunk < 33)
        // ⭐ dit/052 sizes chunks by area: at the largest Wan clip (1280x704) 8 frames (2.7 GB of NPU memory measured
        // at 720x1280), never 9 or more (a 16-frame chunk asked 5.7 GB and failed).
        val mpx = env.getValue("SD_TAEHV_ENCODE_CHUNK_MPX").toDouble()
        val atMax = (mpx * 1e6 / (1280.0 * 704)).toInt()
        assertTrue("frames at 1280x704: $atMax", atMax in 8..8)
        // The T2V folder gets neither.
        val t2v = tmp.newFolder(ModelCatalog.WAN22_A14B.id)
        java.io.File(t2v, ModelCatalog.WAN_HIGH_DIT).writeText("x")
        assertEquals(null, BackendProcess.wanA14Env(t2v)["SD_TAEHV_ENCODE_CHUNK"])
        assertEquals(null, BackendProcess.wanA14Env(t2v)["SD_TAEHV_ENCODE_CHUNK_MPX"])
        assertEquals(null, BackendProcess.wanA14Env(t2v)["NM_WAN_MOE_BOUNDARY"])
    }

    @Test
    fun theI2VCatalogueEntryIsTheDistilledPairWithTurbosEncoder() {
        val i2v = ModelCatalog.WAN22_A14B_I2V
        assertTrue(i2v in ModelCatalog.video)
        assertEquals("wan22_ti2v_turbo", i2v.partsFrom)
        assertEquals(ModelCatalog.WAN, i2v.backendType)
        val names = i2v.files.map { it.name }
        assertTrue(names.containsAll(listOf("dit.gguf", ModelCatalog.WAN_HIGH_DIT, "umt5.gguf", "tae.safetensors")))
        assertTrue(ModelCatalog.WAN_HIGH_LORA !in names && ModelCatalog.WAN_LOW_LORA !in names)
        // ⚠ Q4_0, the NPU's: the newer 2026-04 experts are Q4_K only.
        assertTrue(i2v.files.filter { it.name.endsWith(".gguf") && it.name != "umt5.gguf" }.all { it.url.endsWith("-Q4_0.gguf") })
        assertTrue(i2v.files.single { it.name == "tae.safetensors" }.url.endsWith("taew2_1.safetensors"))
        assertEquals(ModelCatalog.DIT_MIN_ARCH, i2v.builds.single().minArch)
    }
}
