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

    /**
     * ⭐ An 8 Gen 3 (v75): the S25 forced to the v75 skel rendered 480x832 1 s at 2.75 GiB, 35-41 dB from v79
     * (`docs/DEVICES.md` §3e). It gets the 14B up to that area, the low-arch budget and smaller encode chunks; 720p
     * there is not measured.
     */
    @Test
    fun anEightGenThreeGetsTheFourteenBUpTo480p() {
        assertEquals(75, ModelCatalog.WAN22_A14B_I2V.builds.minOf { it.minArch })
        val low = WanVideoNode.profileOf(WanA14Node.name, 75)
        assertEquals(832 * 480, low.maxArea)
        assertEquals(WanVideoNode.A14B, WanVideoNode.profileOf(WanA14Node.name, 79))
        assertEquals(WanVideoNode.A14B, WanVideoNode.profileOf(WanA14Node.name, 0))  // unknown chip: everything
        assertTrue(low.alwaysStream)
        // ⚠ Every 14B clip streams at 2.75 there, even one that gets 4.5 on v79.
        val big = WanVideoNode.pinnedBytes(832, 480, 49, low)
        assertEquals("2.75", WanVideoNode.memoryBudget(big, 12L shl 30, 75, stream = false, prof = low))
        assertEquals("4.5", WanVideoNode.memoryBudget(WanVideoNode.pinnedBytes(832, 480, 81, WanVideoNode.A14B), 12L shl 30, 79, stream = false, prof = WanVideoNode.A14B))
        val dir = tmp.newFolder(ModelCatalog.WAN22_A14B_I2V.id)
        java.io.File(dir, ModelCatalog.WAN_HIGH_DIT).writeText("x")
        assertEquals("3", BackendProcess.wanA14Env(dir, 75)["SD_TAEHV_ENCODE_CHUNK_MPX"])
        assertEquals(BackendProcess.I2V_ENCODE_MPX, BackendProcess.wanA14Env(dir, 79)["SD_TAEHV_ENCODE_CHUNK_MPX"])
        // Below v75 the 14B is still not offered.
        assertTrue(ModelCatalog.WAN22_A14B_I2V.builds.none { it.minArch < 75 })
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
        // ⚠ Not offered since 1.6.240 (one 14B download: its I2V experts), but still the engine's T2V entry.
        val a14 = ModelCatalog.WAN22_A14B
        assertEquals("wan22_ti2v_turbo", a14.partsFrom)
        assertEquals(ModelCatalog.WAN, a14.backendType)
        fun url(spec: com.abrah.nightmare.ModelSpec, name: String) = spec.files.single { it.name == name }.url
        assertEquals(url(ModelCatalog.WAN22, "umt5.gguf"), url(a14, "umt5.gguf"))
        // ⚠ The Wan 2.1 latent space: taew2_1, never Turbo's taew2_2.
        assertTrue(url(a14, "tae.safetensors").endsWith("taew2_1.safetensors"))
        assertEquals(ModelCatalog.DIT_MIN_ARCH, a14.builds.single().minArch)
    }

    /** ⭐ One 14B download (1.6.240): the node is its image to video experts, picture or not; text to video is a flow. */
    @Test
    fun theFourteenBIsItsImageToVideoExpertsOnly() {
        val bare = Node("v", WanA14Node.name)
        val i2v = Node("v", WanA14Node.name, inputs = mapOf("image" to com.abrah.nightmare.Source("pic")))
        assertEquals(ModelCatalog.WAN22_A14B_I2V, WanVideoNode.specOf(bare))
        assertEquals(ModelCatalog.WAN22_A14B_I2V, WanVideoNode.specOf(i2v))
        assertEquals("Image to video (14B)", WanA14Node.titleFor(i2v))
        // ⚠ The text to video pair is not offered: the catalogue lists one 14B.
        assertTrue(ModelCatalog.WAN22_A14B_I2V in ModelCatalog.video)
        assertTrue(ModelCatalog.WAN22_A14B !in ModelCatalog.video)
        // ⭐ The 5B's and Fun's nodes are their models, picture or not.
        assertEquals(ModelCatalog.WAN22, WanVideoNode.specOf(Node("w", WanVideoNode.name, inputs = i2v.inputs)))
        assertEquals(ModelCatalog.WAN22_FUN, WanVideoNode.specOf(Node("f", WanFunNode.name)))
        assertEquals(null, WanVideoNode.specOf(Node("p", "core.prompt")))
    }

    /** ⭐ Text to video on the 14B: the prompt makes a picture with the top bar's model, the 14B animates it. */
    @Test
    fun theFourteenBTextToVideoFlowMakesAPictureFirst() {
        val recipe = com.abrah.nightmare.canvas.recipesWithId(com.abrah.nightmare.canvas.T2V14).single()
        val g = recipe.build().graph
        val video = g.nodes.single { it.type == WanA14Node.name }
        val pic = g.byId.getValue(video.inputs.getValue("image").node)
        assertEquals("generate", pic.id)
        assertEquals("prompt", pic.inputs.getValue("prompt").node)
        assertEquals("prompt", video.inputs.getValue("prompt").node)
        assertEquals(ModelCatalog.WAN22_A14B_I2V, WanVideoNode.specOf(video))
        // ⚠ Its first frame uses the checkpoint, so the flow is a checkpoint flow, and it needs what the 14B needs.
        assertTrue(recipe.usesCheckpoint)
        assertEquals(ModelCatalog.WAN22_A14B_I2V.builds.minOf { it.minArch }, recipe.minArch)
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
        // ⭐ dit/052 sizes chunks by area: at the 14B's largest clip (1280x720) 8 frames (2.7 GB of NPU memory measured
        // at 720x1280), never 9 or more (a 16-frame chunk asked 5.7 GB and failed).
        val mpx = env.getValue("SD_TAEHV_ENCODE_CHUNK_MPX").toDouble()
        val atMax = (mpx * 1e6 / WanVideoNode.A14B.maxArea).toInt()
        assertTrue("frames at 1280x720: $atMax", atMax in 8..8)
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
        assertEquals(ModelCatalog.DIT_GGML_MIN_ARCH, i2v.builds.single().minArch)  // 8 Gen 3 since 2026-10-26
    }
    /** ⭐ The 14B sizes on its own 16 px grid up to its native 1280x720; the 5B keeps 32 px and 1280x704. */
    @Test
    fun theFourteenBHasItsOwnNativeSizeGrid() {
        val wan = WanVideoNode
        val a14 = WanVideoNode.A14B
        assertEquals(720 to 1280, wan.sizeOf(mapOf("width" to "720", "height" to "1280"), a14))
        assertEquals(1280 to 720, wan.fitArea(1280, 1280, widthMoved = true, a14))
        assertEquals(720 to 1280, wan.matchShape(1080, 1920, a14))
        // The 5B is unchanged: 720 is off its grid, and its area stops at 704.
        assertEquals(736 to 1280, wan.sizeOf(mapOf("width" to "720", "height" to "1280")))
        assertEquals(1280 to 704, wan.fitArea(1280, 1280, widthMoved = true))
        // The 14B node's picker and crop read the same grid.
        val node = com.abrah.nightmare.Node("v", WanA14Node.name, params = mapOf("width" to "720", "height" to "1280"))
        assertEquals(720 to 1280, WanA14Node.framesTo(node))
        assertEquals(16, WanA14Node.widgets.first { it.name == "width" }.step)
        assertEquals(16, WanA14Node.widgets.first { it.name == "height" }.step)
    }
    /** ⭐ The 14B's own knobs (the user, 2026-10-25): 1 s by default, 16 fps, no LoRAs, the picture's shape in its flow. */
    @Test
    fun theFourteenBSheetIsItsOwn() {
        val w = WanA14Node.widgets.associateBy { it.name }
        assertTrue("no LoRA row on the 14B", com.abrah.nightmare.SdSampler.LORAS !in w)
        assertEquals("1", w.getValue("seconds").default)
        assertEquals(1.0, w.getValue("seconds").min!!, 0.0)
        assertEquals(com.abrah.nightmare.R.string.hint_wan14_seconds, w.getValue("seconds").hintRes)
        assertEquals("false", w.getValue(WanVideoNode.FOLLOW_PICTURE).default)
        // The 5B keeps its LoRAs and its own seconds hint.
        val turbo = WanVideoNode.widgets.associateBy { it.name }
        assertTrue(com.abrah.nightmare.SdSampler.LORAS in turbo)
        assertEquals(com.abrah.nightmare.R.string.hint_wan_seconds, turbo.getValue("seconds").hintRes)
        // ⭐ Text to video (14B) follows its picture's shape.
        val video = com.abrah.nightmare.canvas.recipesWithId(com.abrah.nightmare.canvas.T2V14).single().build().graph.nodes
            .single { it.type == WanA14Node.name }
        assertEquals("true", video.params[WanVideoNode.FOLLOW_PICTURE])
    }

    /** ⭐ A picture's shape at the size's pixel count: AbsoluteReality's 512x768 stays itself at 480p, 768x1168 at 720p. */
    @Test
    fun aFollowedPictureKeepsItsShapeAtTheAskedPixels() {
        val a14 = WanVideoNode.A14B
        assertEquals(512 to 768, WanVideoNode.matchShape(512, 768, a14, area = 832 * 480))
        assertEquals(768 to 1168, WanVideoNode.matchShape(512, 768, a14, area = 1280 * 720))
        // ⭐ Exactly the picture's shape when the grid has it: nothing stretched, nothing trimmed.
        assertEquals(512 to 768, WanVideoNode.exactShape(512, 768, a14, area = 832 * 480))
        assertEquals(768 to 1152, WanVideoNode.exactShape(512, 768, a14, area = 1280 * 720))
        assertEquals(960 to 960, WanVideoNode.exactShape(1024, 1024, a14, area = 1280 * 720))
        assertEquals(720 to 1280, WanVideoNode.exactShape(1080, 1920, a14, area = 1280 * 720))
        assertEquals(832 to 480, WanVideoNode.matchShape(1664, 960, a14, area = 832 * 480))
        // Never past the model's own cap.
        val (w, h) = WanVideoNode.matchShape(1024, 1024, a14, area = 4096 * 4096)
        assertTrue("${w}x$h", w.toLong() * h <= a14.maxArea)
    }
    /** ⭐ 3 s at most on the 14B (an hour at 720p already); the 5B keeps 5. */
    @Test
    fun theFourteenBStopsAtThreeSeconds() {
        assertEquals(3.0, WanA14Node.widgets.first { it.name == "seconds" }.max!!, 0.0)
        val asked = com.abrah.nightmare.Node("v", WanA14Node.name, params = mapOf("seconds" to "5"))
        assertEquals(3 * 16 + 1, WanVideoNode.framesOf(asked, WanA14Node.effectiveParams(asked)))
        val turbo = com.abrah.nightmare.Node("w", WanVideoNode.name, params = mapOf("seconds" to "5"))
        assertEquals(5 * 24 + 1, WanVideoNode.framesOf(turbo, WanVideoNode.effectiveParams(turbo)))
    }

    /** ⭐ A followed picture is never stretched: its frame has the clip's exact shape (the grid's sliver trimmed). */
    @Test
    fun aFollowedPictureIsFramedNotStretched() {
        for ((src, clip) in listOf((512 to 768) to (768 to 1168), (1024 to 1024) to (848 to 848), (1920 to 1080) to (1280 to 720))) {
            val (x, y, w, h) = WanVideoNode.centredFrame(src.first, src.second, clip.first, clip.second)
            val framed = (w * src.first) / (h * src.second)
            assertEquals("$src into $clip", clip.first.toFloat() / clip.second, framed, 0.001f)
            assertTrue("centred", kotlin.math.abs(x - (1f - w) / 2f) < 1e-6f && kotlin.math.abs(y - (1f - h) / 2f) < 1e-6f)
            assertTrue("trims only a sliver", w > 0.98f && h > 0.98f || src.first.toFloat() / src.second != clip.first.toFloat() / clip.second)
        }
    }
}
