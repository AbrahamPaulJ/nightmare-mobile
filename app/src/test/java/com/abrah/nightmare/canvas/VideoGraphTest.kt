package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.IMAGE_SAMPLER_TYPES
import com.abrah.nightmare.INPAINT_TYPES
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SAMPLER_TYPES
import com.abrah.nightmare.Source
import com.abrah.nightmare.isSampler
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐⭐ The video flows — **one sampler, three nodes**, 2026-09-15.
 *
 * ⚠⚠ This file used to pin the FIVE-node split of 2026-09-13
 * (`nd.clip_encode` → `nd.first_frame` → `nd.vae_encode` → `nd.sample` →
 * `nd.vae_decode`). The user reversed it: text-to-video and image-to-video are
 * now the same shape as text-to-image and image-to-image, and the only
 * difference between them is whether a photo is wired.
 *
 * ⚠ What that cost, recorded here because it was a deliberate trade: the first
 * frame is no longer a node you can look at or re-roll before paying ~20 s for
 * the clip. It is made inside the sampler.
 */
class VideoGraphTest {

    private val t2v = textToVideoWorkflow()
    private val i2v = imageToVideoWorkflow()

    @Test
    fun textToVideoIsPromptSamplerOutput() {
        assertEquals(
            listOf("output", "prompt", "video"),
            t2v.graph.nodes.map { it.id }.sorted(),
        )
        val v = t2v.graph.byId.getValue("video")
        assertEquals("nd.sample", v.type)
        assertEquals(Source("prompt"), v.inputs["prompt"])
        // ⭐ No photo: the sampler makes its own first frame.
        assertNull("text to video wires no picture", v.inputs["image"])
    }

    /**
     * ⭐⭐ **i2v is t2v with a photo wired, and nothing else.** No crop node
     * either — the sampler frames what it is given, the same rule the image path
     * follows (docs/ARCHITECTURE.md §5.7).
     */
    @Test
    fun imageToVideoIsTextToVideoWithAPhotoWired() {
        val v = i2v.graph.byId.getValue("video")
        assertEquals("nd.sample", v.type)
        assertEquals(Source("photo"), v.inputs["image"])
        assertEquals("core.image", i2v.graph.byId.getValue("photo").type)
        assertTrue("no crop node stands in front of it any more",
            i2v.graph.nodes.none { it.type == "image.crop" })
    }

    /** ⚠ Both flows end where every other one does. */
    @Test
    fun bothEndInAnOutputNode() {
        for (w in listOf(t2v, i2v)) {
            val out = w.graph.nodes.single { it.type == "core.output" }
            assertEquals(Source("video"), out.inputs["media"])
        }
    }

    /**
     * ⭐ The video sampler ROLLS A SEED, so `seed: random` and the lock apply to
     * it exactly as they do to an SD one.
     */
    @Test
    fun theVideoSamplerIsASampler() {
        assertTrue(isSampler("nd.sample"))
        assertTrue(isSampler("sd15.sample"))
        assertTrue(isSampler("sdxl.inpaint"))
        // ⚠ NOT the prompt or the output: neither has a seed to roll.
        assertTrue(!isSampler("core.prompt"))
        assertTrue(!isSampler("core.output"))
        // ⚠ Anima's two DO roll: they are the same [SdSampler] class.
        assertTrue(isSampler("anima.sample"))
        // ⭐⭐⭐ …and so do the two DiT families, since 2026-09-19. They did NOT
        // until then, and this assertion is why the gap is worth a comment: the
        // count was right at 7 and stayed right, because `SAMPLER_TYPES` was
        // built from a LITERAL list that the DiT registration never joined. A
        // type that is in no set at all does not move a count. ⇒ The sets are
        // now read off [SdSampler.ALL] (`Graph.kt`), so this number moves when a
        // sampler is registered — which is the failure this assertion was
        // written to produce, and it did produce it on the very next edit.
        assertTrue(isSampler("flux2.sample"))
        assertTrue(isSampler("zimage.sample"))
        // ⚠ Nine: eight image samplers (SD 1.5, SDXL and Anima × sample/inpaint,
        // plus FLUX.2 and Z-Image, neither of which has a REGISTERED inpaint
        // type) plus the one video sampler. Asserted by NUMBER so adding a type
        // without deciding whether it rolls a seed fails here rather than
        // silently. ⚠⚠ `flux2.inpaint` exists in code and is deliberately not
        // in `SdSampler.ALL` — see the note there. If this count moves to 10,
        // somebody registered it, and the measurements that argued against
        // that need re-taking first.
        assertEquals(9, SAMPLER_TYPES.size)
        assertEquals(8, IMAGE_SAMPLER_TYPES.size)
        assertEquals(3, INPAINT_TYPES.size)
    }

    @Test
    fun theVideoRecipeOffersASeedLock() {
        assertEquals(listOf("video"), t2v.graph.nodes.filter { isSampler(it.type) }.map { it.id })
    }

    /**
     * ⭐⭐ It FITS a photo rather than demanding a size — so a picture wires
     * straight in and no crop node is required between them.
     *
     * ⚠⚠ It used to declare 512x320, which made `Framing` lock a crop in front
     * of it and REFUSE a direct wire. That refusal is what the crop node existed
     * for, and both are gone.
     */
    @Test
    fun aPhotoWiresStraightIntoTheSampler() {
        val g = Graph(
            listOf(
                Node("photo", "core.image"),
                Node("video", "nd.sample", inputs = sources("image" to "photo")),
            )
        )
        assertNull(
            "a photo must wire straight in now",
            com.abrah.nightmare.sizeRefusal(g, NODE_TYPES, "photo", "video", "image"),
        )
    }

    /**
     * ⭐⭐⭐ **The framing view is 512x320, whatever shape the photo is.**
     *
     * ⚠⚠ The video sampler's frame size is a property of its compiled QNN
     * context and appears in NO param — no `out_w`/`out_h` like a crop node, no
     * `width`/`height` like an SD sampler. `framingOutSize` guessed from those
     * two, found neither, and `cropAspect` fell through to the SOURCE photo's
     * aspect: the image-to-video cropper was the shape of whatever the user
     * picked, and `Video.bitmapToChw` then centre-cropped a different rectangle
     * than the one they had dragged. Reported from the phone, 2026-09-15.
     */
    @Test
    fun theVideoSamplerFramesToItsOwnFrameSize() {
        val node = i2v.graph.byId.getValue("video")
        val type = NODE_TYPES.getValue("nd.sample")
        assertEquals(
            com.abrah.nightmare.npu.VideoStructure.frameSize,
            framingOutSize(node, type),
        )
        // ⚠ A PORTRAIT source, because that is the case the old fallback got
        // wrong in the most visible way.
        assertEquals(512f / 320f, cropAspect(node, 1080, 1920, type), 1e-4f)
        // ⚠⚠ The control: without the type it still answers the photo's shape.
        // Kept so this test cannot pass for the wrong reason.
        assertEquals(1080f / 1920f, cropAspect(node, 1080, 1920, null), 1e-4f)
    }

    // --- what loops a clip ---------------------------------------------------

    @Test
    fun onlyTheEndOfTheChainLoopsItsClip() {
        // ⚠ The OUTPUT node: a renderer no longer shows its own result, so the
        // end of the chain is where the clip is sent, not where it was made.
        val videos = mapOf("video" to "/x/clip.mp4", "output" to "/x/clip.mp4")
        assertEquals(setOf("output"), clipNodes(t2v.graph, videos))
    }

    @Test
    fun aClipFromADeletedNodeIsDropped() {
        val videos = mapOf("output" to "/x/clip.mp4", "ghost" to "/x/old.mp4")
        assertEquals(setOf("output"), clipNodes(t2v.graph, videos))
    }

    /**
     * ⭐⭐⭐ Several nodes share one poster id, so "which node owns this
     * picture" must FILTER by the rule and then pick — never pick and then
     * test. `docs/ARCHITECTURE.md` §5.6 has the bug this shape prevents.
     *
     * ⚠⚠ This test used to hand-roll the answer it was checking, which made it
     * a test of the test. It now calls [clipOwner], the function the app calls
     * — which is the point, because the bug came back on 2026-09-15 in a
     * hand-rolled copy in the KEEP path while this file stayed green.
     */
    @Test
    fun thePosterIsSharedSoTheOwnerMustBeChosen() {
        val poster = "img_poster"
        val previews = linkedMapOf("video" to (poster to 1f), "output" to (poster to 1f))
        val videos = mapOf("video" to "/x/clip.mp4", "output" to "/x/clip.mp4")

        // ⚠ The BROKEN lookup, kept as the control: it answers "video", which is
        // not an owner — so a caller using it falls back to the still.
        val firstMatch = previews.entries.first { it.value.first == poster }.key
        assertEquals("video", firstMatch)
        assertTrue("the control must NOT be an owner", firstMatch !in clipNodes(t2v.graph, videos))

        assertEquals("output", clipOwner(t2v.graph, previews, videos, poster))
    }

    /** ⚠ A poster no node owns answers null rather than a wrong node. */
    @Test
    fun aPosterFromAPictureGraphOwnsNoClip() {
        val previews = mapOf("output" to ("img_still" to 1f))
        assertNull(clipOwner(t2v.graph, previews, emptyMap(), "img_still"))
    }
}
