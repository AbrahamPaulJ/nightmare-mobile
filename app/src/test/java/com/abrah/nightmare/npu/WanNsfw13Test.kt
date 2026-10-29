package com.abrah.nightmare.npu

import com.abrah.nightmare.ModelCatalog
import com.abrah.nightmare.Node
import com.abrah.nightmare.SafetensorsGguf
import com.abrah.nightmare.WanGguf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * ⭐ NSFW Wan 1.3B ([WanNsfw13Node]): its profile against the 14B's and Turbo's, its node shape (text to video only)
 * and its catalogue entry (converted on the phone, CausVid beside it, community).
 */
class WanNsfw13Test {

    @Test
    fun itHasTheFourteenBsTokensAtSixteenFps() {
        val prof = WanVideoNode.NSFW13
        assertEquals(prof, WanVideoNode.profileOf(WanNsfw13Node.name))
        assertEquals(52L * 30 * 9, WanVideoNode.tokens(832, 480, 33, prof))
        assertEquals(33, WanVideoNode.framesOf(Node("v", WanNsfw13Node.name, mapOf("seconds" to "2")), mapOf("seconds" to "2")))
        assertEquals("euler", prof.sampler)
        assertEquals(16, prof.grid)
        assertEquals(832 * 480, prof.maxArea)
        assertFalse(prof.alwaysStream)
    }

    @Test
    fun itsDecodeTilesEveryFrame() {
        // ⚠ Its 832x480 decode whole got the app lmkd-killed (3 of 3 on the S25). Turbo's and the 14B's stay as they were.
        assertEquals(32, WanVideoNode.vaeTileFor(832, 480, WanVideoNode.NSFW13))
        assertEquals(32, WanVideoNode.vaeTileFor(480, 272, WanVideoNode.NSFW13))
        for (prof in listOf(WanVideoNode.TURBO, WanVideoNode.A14B, WanVideoNode.A14B_LOW_ARCH)) {
            assertEquals(0, WanVideoNode.vaeTileFor(832, 480, prof))
            assertEquals(32, WanVideoNode.vaeTileFor(1280, 720, prof))
        }
    }

    @Test
    fun itRunsCausVidsFiveRealSteps() {
        // ⭐ sd.cpp's 6-step discrete schedule at shift 5 without its 0 -> 0 last step.
        val s = WanVideoNode.NSFW13.sigmas
        assertEquals(6, s.size)
        assertEquals(1.0, s.first(), 0.0)
        assertEquals(0.0, s.last(), 0.0)
        assertTrue(s.zipWithNext().all { (a, b) -> a > b })
        // Turbo's and the 14B's stay their own.
        assertEquals(WanVideoNode.SIGMAS, WanVideoNode.TURBO.sigmas)
        assertEquals(WanVideoNode.SIGMAS, WanVideoNode.A14B.sigmas)
    }

    @Test
    fun itIsTextToVideoOnly() {
        assertEquals(listOf("prompt"), WanNsfw13Node.inputs.map { it.name })
        val knobs = WanNsfw13Node.widgets.map { it.name }
        assertTrue(WanParts.KNOBS.none { it in knobs })
        assertTrue(listOf("x", "y", "w", "h").none { it in knobs })
        assertTrue(com.abrah.nightmare.SdSampler.LORAS in knobs)
        assertEquals("2", WanNsfw13Node.widgets.first { it.name == "seconds" }.default)
        assertEquals(ModelCatalog.WAN21_NSFW13, WanVideoNode.specOf(Node("v", WanNsfw13Node.name)))
        assertTrue(WanNsfw13Node.name in com.abrah.nightmare.WAN_TYPES)
        assertTrue(com.abrah.nightmare.NODE_TYPES.containsKey(WanNsfw13Node.name))
    }

    @Test
    fun theCatalogueConvertsNsfwApisFileAndSharesTurbosEncoder() {
        val spec = ModelCatalog.WAN21_NSFW13
        assertTrue(spec.community)
        assertTrue(spec in ModelCatalog.video)
        assertTrue(spec !in ModelCatalog.all)
        assertEquals("wan22_ti2v_turbo", spec.partsFrom)
        val dit = spec.files.first { it.name == "dit.gguf" }
        assertTrue("NSFW-API/NSFW_Wan_1.3b" in dit.url && dit.url.endsWith("wan_1.3B_exp_e14.safetensors"))
        assertEquals(WanGguf.NSFW13_BYTES, dit.bytes)
        assertEquals(2_838_077_328L, dit.sourceBytes)
        assertTrue(dit.convert != null)
        assertTrue(spec.files.any { it.name == ModelCatalog.CAUSVID_LORA && "CausVid" in it.url })
        assertTrue(spec.files.any { it.name == "tae.safetensors" && it.url.endsWith("taew2_1.safetensors") })
        assertEquals(ModelCatalog.DIT_MIN_ARCH, spec.builds.minOf { it.minArch })
    }

    @Test
    fun theRuleQuantizesMatricesAndKeepsTheRestF32() {
        val r = WanGguf.rule
        assertEquals(SafetensorsGguf.Q8_0, r.type("blocks.0.self_attn.q.weight", longArrayOf(1536, 1536), "BF16"))
        assertEquals(SafetensorsGguf.Q8_0, r.type("blocks.0.ffn.2.weight", longArrayOf(1536, 8960), "BF16"))
        assertEquals(SafetensorsGguf.F32, r.type("blocks.0.self_attn.q.bias", longArrayOf(1536), "BF16"))
        assertEquals(SafetensorsGguf.F32, r.type("blocks.0.modulation", longArrayOf(1, 6, 1536), "BF16"))
        assertEquals(SafetensorsGguf.F32, r.type("patch_embedding.weight", longArrayOf(1536, 16, 1, 2, 2), "BF16"))
        r.check(listOf("patch_embedding.weight", "blocks.0.self_attn.q.weight", "text_embedding.0.weight"))
        for (bad in listOf(
            listOf("blocks.0.self_attn.q.weight"),  // not Wan
            listOf("patch_embedding.weight", "blocks.0.self_attn.q.weight", "text_embedding.0.weight", "img_emb.proj.0.weight"),  // I2V
        )) {
            try {
                r.check(bad)
                fail("accepted $bad")
            } catch (_: IOException) {
            }
        }
    }
}
