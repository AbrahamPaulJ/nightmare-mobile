package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.Source
import com.abrah.nightmare.SAMPLER_TYPES
import com.abrah.nightmare.isSampler
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ The video recipe's canvas behaviour — the three things a user on the
 * phone reported on 2026-09-12, each pinned so it cannot come back.
 *
 * ⚠ The pipeline itself is not testable here (it needs an NPU); what IS
 * testable is every decision made ABOUT a clip on the way to and from it, and
 * all three bugs lived there rather than in the render.
 */
class VideoGraphTest {

    private val t2v = textToVideoWorkflow()

    /**
     * ⭐⭐ **Only the END of the video chain loops.** [clipNodes].
     *
     * ⚠ The sampler and the output both hold the same `VIDEO` value, so both
     * animated — the same two seconds drawn twice at 12 Hz, once under the
     * knobs that made it. *"why is the video sampler playing the output? it
     * shouldnt"*.
     */
    @Test
    fun onlyTheEndOfTheChainLoopsItsClip() {
        val videos = mapOf("sample" to "/x/clip.mp4", "decode" to "/x/clip.mp4")
        assertEquals(setOf("decode"), clipNodes(t2v.graph, videos))
    }

    /**
     * ⚠ A sampler with nothing wired off it IS the end of the chain, so
     * deleting the output node must not leave a graph that plays nothing. That
     * is why the rule is the GRAPH rather than `type == "video.output"`.
     */
    @Test
    fun aLoneSamplerStillLoops() {
        val g = Graph(listOf(Node("decode", "nd.vae_decode")))
        assertEquals(setOf("decode"), clipNodes(g, mapOf("decode" to "/x/clip.mp4")))
    }

    /**
     * ⭐⭐ **The i2v crop sizes ITSELF from the sampler**, exactly as the
     * img2img recipe's crop sizes itself from `sd.vae_encode`.
     *
     * ⚠ 512x320 is the ENCODE size. The clip comes out 1024x640 because
     * `upscale` doubles it afterwards — a crop that followed the OUTPUT size
     * would hand the VAE an image four times too large.
     */
    @Test
    fun theImageToVideoCropDerivesItsOwnSize() {
        val w = imageToVideoWorkflow()
        val demand = com.abrah.nightmare.requiredOutputSize(w.graph, NODE_TYPES, "frame")
        assertEquals(
            com.abrah.nightmare.SizeDemand.Exactly(512, 320, listOf("encode")),
            demand,
        )
        // ⚠ Nothing types the size: the node carries no out_w/out_h at all.
        val crop = w.graph.byId.getValue("frame")
        assertTrue("the crop must not hardcode a size", "out_w" !in crop.params)
    }

    /**
     * ⭐⭐⭐ **A photo may not be wired straight into the sampler**, and the
     * refusal has to name the fix.
     *
     * ⚠⚠ `image.load` hands the photo on whole and promises no size, so the
     * pair is a graph a user can draw, that looks entirely reasonable, and
     * that can only fail. It is the same rule that already stands between a
     * photo and `sd.vae_encode` — declaring `requiredInputSize` is what buys
     * it, with no video-specific code in the canvas.
     */
    @Test
    fun aPhotoStraightIntoTheSamplerIsRefused() {
        val g = Graph(
            listOf(
                Node("photo", "image.load"),
                Node("encode", "nd.vae_encode"),
            )
        )
        val why = com.abrah.nightmare.sizeRefusal(g, NODE_TYPES, "photo", "encode", "image")
        assertNotNull("a photo promises no size, so this wire must be refused", why)
        assertTrue("the refusal must name the fix, got: " + why, why!!.contains("Crop"))
    }

    /**
     * ⭐⭐⭐ **One poster, several nodes — the owner is the one that matters.**
     *
     * ⚠⚠ `Value.Video.previewImage()` hands back the SAME poster for every
     * node the clip flows through, so a t2v graph records both the sampler and
     * the output node in `previews` under one image id. Anything that reverses
     * that map to find "the node for this picture" gets an arbitrary answer.
     *
     * ⚠⚠⚠ That is not hypothetical: taking the first match broke Save and
     * Share on the OUTPUT node — they found the sampler, [clipNodes] correctly
     * said it does not own the clip, and both silently fell back to the still.
     * Reported from the phone, 2026-09-13. ⇒ Filter the candidates by
     * [clipNodes] instead of picking one and then testing it.
     */
    @Test
    fun thePosterIsSharedSoTheOWNERMustBeChosen() {
        val poster = "img_poster"
        // Exactly the shape `HarnessViewModel` builds: both nodes, one poster.
        val previews = linkedMapOf("sample" to poster, "decode" to poster)
        val videos = mapOf("sample" to "/x/clip.mp4", "decode" to "/x/clip.mp4")
        val owners = clipNodes(t2v.graph, videos)

        // ⚠ The BROKEN lookup, kept as the control: it answers "video", which
        // is not an owner — so a filter applied after it yields nothing.
        val firstMatch = previews.entries.first { it.value == poster }.key
        assertEquals("sample", firstMatch)
        assertTrue("the control must NOT be an owner", firstMatch !in owners)

        // ⚠ The fixed lookup: filter first, then take one.
        val owner = previews.entries
            .filter { it.value == poster }
            .map { it.key }
            .firstOrNull { it in owners }
        assertEquals("decode", owner)
    }

    /**
     * ⚠ `videos` outlives the nodes in it — a clip stays in the map after its
     * node is deleted — so an id the graph no longer knows is not terminal, it
     * is gone.
     */
    @Test
    fun aClipFromADeletedNodeIsDropped() {
        val videos = mapOf("decode" to "/x/clip.mp4", "ghost" to "/x/old.mp4")
        assertEquals(setOf("decode"), clipNodes(t2v.graph, videos))
    }

    /**
     * ⭐⭐ **The video sampler IS a sampler**, which is what makes its seed roll
     * (`HarnessOps.runRolled`), its number show on the picture it made
     * ([seedFor]), and the run bar offer a lock ([samplerFor]).
     *
     * ⚠⚠ It was in none of them: `seed = 0` hashed to `"0"`, so the executor
     * served the CACHED clip and every Run after the first returned the same
     * two seconds. *"video player seed 0 is cached, it should be random"*.
     */
    @Test
    fun theVideoSamplerIsASampler() {
        assertTrue(isSampler("nd.sample"))
        assertTrue(isSampler("sd.sample"))
        // ⭐⭐ `nd.first_frame` too: it has its OWN seed and makes the picture
        // the clip starts from, so a graph whose frame seed never rolled would
        // animate the same still on every Run.
        assertTrue(isSampler("nd.first_frame"))
        // ⚠ NOT `vae_encode`: its seed is what lets everything downstream cache.
        assertFalse(isSampler("sd.vae_encode"))
        assertFalse(isSampler("nd.vae_decode"))
        assertEquals(SAMPLER_TYPES.size, 3)
    }

    /** ⚠ The run bar reads the first sampler in the graph; t2v has to have one. */
    @Test
    fun theVideoRecipeOffersASeedLock() {
        val seeded = t2v.graph.nodes.filter { isSampler(it.type) }.map { it.id }
        assertEquals(listOf("frame", "sample"), seeded.sorted())
        // ⚠ Rolled, not pinned: 0 is what makes Run give a new clip.
        t2v.graph.nodes.filter { isSampler(it.type) }
            .forEach { assertEquals("0", it.params["seed"]) }
        // ⚠⚠ The lock writes onto the node the clip came FROM, walking up from
        // the output the user was looking at — which is the MMDiT sampler, not
        // the first frame. `samplerFor` takes the nearest one upstream.
        assertEquals("sample", samplerFor(t2v.graph, "decode"))
    }

    /**
     * ⭐⭐ **An unset `bool` reads its DECLARED default**, in the node body as
     * well as in the inspector.
     *
     * ⚠⚠ The node this was written for is gone, and the rule is not: a
     * `video.output` dropped from the palette carried no params at all, drew a
     * ticked box and wrote nothing, because its body read
     * `params["save"] != "true"`. `upscale` on the decoder is the same shape and
     * would fail the same way.
     */
    @Test
    fun anUnsetBoolReadsItsDefault() {
        assertEquals(
            "true",
            com.abrah.nightmare.npu.VideoVaeDecodeNode
                .effectiveParams(Node("decode", "nd.vae_decode"))["upscale"],
        )
    }

    /**
     * ⭐⭐ **The recipe is the decomposed chain**, and its shape is the thing
     * most likely to drift: a node added or a wire moved changes what
     * [clipNodes] calls terminal, which the first test cannot see on its own.
     *
     * ⚠⚠ `cond` and `frame_cond` are named PORTS. A bare wire would mean the
     * prompt node's first output, so the first frame would be conditioned on
     * the MMDiT's tensors — a graph that runs and produces confident nonsense.
     */
    @Test
    fun theTextToVideoRecipeIsTheDecomposedChain() {
        val g = t2v.graph
        assertEquals(
            listOf("decode", "encode", "frame", "prompt", "sample"),
            g.nodes.map { it.id }.sorted(),
        )
        assertEquals(Source("prompt", "frame_cond"), g.byId.getValue("frame").inputs["cond"])
        assertEquals(Source("frame"), g.byId.getValue("encode").inputs["image"])
        assertEquals(Source("prompt", "cond"), g.byId.getValue("sample").inputs["cond"])
        assertEquals(Source("encode"), g.byId.getValue("sample").inputs["latent"])
        assertEquals(Source("sample"), g.byId.getValue("decode").inputs["latent"])
    }

    /**
     * ⭐⭐ **Image to video differs from text to video in ONE wire.**
     *
     * ⚠ That is the claim the whole design rests on: i2v is not a flag, an
     * optional port or a special node — it is `image.crop` where
     * `nd.first_frame` would be, the same substitution img2img already makes.
     *
     * ⚠⚠ And nothing wires `frame_cond`, which is what lets the prompt node
     * skip `clipl` entirely.
     */
    @Test
    fun imageToVideoIsTextToVideoWithTheFrameSwapped() {
        val g = imageToVideoWorkflow().graph
        assertEquals(Source("frame"), g.byId.getValue("encode").inputs["image"])
        assertEquals("image.crop", g.byId.getValue("frame").type)
        // the rest of the chain is identical
        assertEquals(Source("prompt", "cond"), g.byId.getValue("sample").inputs["cond"])
        assertEquals(Source("encode"), g.byId.getValue("sample").inputs["latent"])
        assertEquals(Source("sample"), g.byId.getValue("decode").inputs["latent"])
        assertTrue(
            "nothing may read frame_cond, or clipl loads for nothing",
            g.nodes.none { n -> n.inputs.values.any { it.port == "frame_cond" } },
        )
    }
}
