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
        assertEquals(Source("image"), v.inputs["image"])
        assertEquals("core.image", i2v.graph.byId.getValue("image").type)
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
        // ⚠ Ten since 2026-09-27: `qwen21.sample` (Qwen Image 2.1, no inpaint
        // type, rolls a seed like every other image sampler). Eleven since
        // 2026-09-28: `krea2.sample` (Krea 2 Turbo, the same shape). Twelve since
        // 2026-09-29: `sd15swap.sample` (SD 1.5 Swap). Fourteen with
        // `sd15swap.inpaint` (its latent-blend inpaint), the same day.
        assertTrue(isSampler("sd15swap.sample"))
        assertTrue(isSampler("sd15swap.inpaint"))
        // 2026-10-03: `sdxlswap.sample` / `sdxlswap.inpaint` (SDXL Swap, LoRA per render).
        assertTrue(isSampler("sdxlswap.sample"))
        assertTrue(isSampler("sdxlswap.inpaint"))
        // 2026-10-05: `zimage.inpaint`, `qwen21.inpaint`, `krea2.inpaint` (DiT masked img2img); 2026-10-13: `animadit.*`.
        assertTrue(isSampler("zimage.inpaint"))
        // 2026-10-14: `wan.sample`, the second video sampler (Wan 2.2, `docs/VIDEO-GGML.md`).
        assertTrue(isSampler("wan.sample"))
        // 2026-10-15: `wanfun.sample`, Motion transfer (Wan 2.2 Fun Control).
        assertTrue(isSampler("wanfun.sample"))
        // 2026-10-16: `iris.sample`, Iris 3B Turbo (`docs/IRIS.md`).
        assertTrue(isSampler("iris.sample"))
        // 2026-10-18: `moss.sample`, MOSS-Image (`docs/MOSS.md`).
        assertTrue(isSampler("moss.sample"))
        // 2026-10-18: `ideogram.sample`, Ideogram 4 (`docs/ROADMAP.md`).
        assertTrue(isSampler("ideogram.sample"))
        assertEquals(25, SAMPLER_TYPES.size)
        assertEquals(22, IMAGE_SAMPLER_TYPES.size)
        assertEquals(9, INPAINT_TYPES.size)
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
                Node("image", "core.image"),
                Node("video", "nd.sample", inputs = sources("image" to "image")),
            )
        )
        assertNull(
            "a photo must wire straight in now",
            com.abrah.nightmare.sizeRefusal(g, NODE_TYPES, "image", "video", "image"),
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

    // --- Wan 2.2 video: the same shapes on the second video sampler --------------

    private val wanT2v = wanVideoWorkflow(i2v = false)
    private val wanI2v = wanVideoWorkflow(i2v = true)

    /** ⭐ The Neodragon pair's shapes, node for node, on `wan.sample`. */
    @Test
    fun wanFlowsAreTheVideoShapes() {
        assertEquals(listOf("output", "prompt", "video"), wanT2v.graph.nodes.map { it.id }.sorted())
        assertEquals(listOf("image", "output", "prompt", "video"), wanI2v.graph.nodes.map { it.id }.sorted())
        for (w in listOf(wanT2v, wanI2v)) {
            val v = w.graph.byId.getValue("video")
            assertEquals(com.abrah.nightmare.npu.WanVideoNode.name, v.type)
            assertEquals(Source("prompt"), v.inputs["prompt"])
            // ⚠ Named: the node has two outputs (`last_frame`), and a bare wire from it is refused.
            assertEquals(Source("video", "video"), w.graph.nodes.single { it.type == "core.output" }.inputs["media"])
        }
        assertNull(wanT2v.graph.byId.getValue("video").inputs["image"])
        assertEquals(Source("image"), wanI2v.graph.byId.getValue("video").inputs["image"])
    }

    /**
     * ⭐ `seconds` 0 on the Wan node is ONE frame, its last frame the picture. ⚠ The node's, not a flow's: the
     * "Wan 2.2: text to image" flow went (the user, 2026-10-21: no flow just for that); the node keeps 0 s.
     */
    @Test
    fun wanAtZeroSecondsIsOneFrame() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        val v = wanT2v.graph.byId.getValue("video").let { it.copy(params = it.params + ("seconds" to "0")) }
        assertEquals(1, wan.framesOf(v, wan.effectiveParams(v)))
        assertTrue(wan.outputs.any { it.name == wan.LAST_FRAME })
    }

    /**
     * ⭐⭐ Its framing view is the CLIP's size, read off its `width`/`height` — 832x480 by default,
     * snapped to Wan's 32 px grid — and a photo wires straight in, as for `nd.sample`.
     */
    @Test
    fun wanFramesToTheClipSize() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        val node = wanI2v.graph.byId.getValue("video")
        assertEquals(832 to 480, framingOutSize(node, wan))
        assertEquals(832f / 480f, cropAspect(node, 1080, 1920, wan), 1e-4f)
        assertEquals(480 to 832, wan.sizeOf(mapOf("width" to "480", "height" to "832")))
        assertEquals(640 to 640, wan.sizeOf(mapOf("width" to "650", "height" to "630")))
        assertEquals(320 to 1280, wan.sizeOf(mapOf("width" to "100", "height" to "4000")))
        val g = Graph(listOf(Node("image", "core.image"), Node("video", wan.name, inputs = sources("image" to "image"))))
        assertNull(com.abrah.nightmare.sizeRefusal(g, NODE_TYPES, "image", "video", "image"))
    }

    /**
     * ⭐⭐ GitHub #23's shapes, by two sliders: the side NOT dragged gives way, so no pair goes past
     * 1280x704's pixels, the largest size run on a phone here.
     */
    @Test
    fun wanSizeStaysWithinTheArea() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(832 to 480, wan.fitArea(832, 480, widthMoved = true))
        assertEquals(1280 to 704, wan.fitArea(1280, 1280, widthMoved = true))
        assertEquals(704 to 1280, wan.fitArea(1280, 1280, widthMoved = false))
        assertEquals(960 to 928, wan.fitArea(960, 960, widthMoved = true))
        for (w in 320..1280 step 32) for (h in 320..1280 step 32) {
            val (fw, fh) = wan.fitArea(w, h, widthMoved = w % 64 == 0)
            assertTrue("${w}x$h -> ${fw}x$fh", fw.toLong() * fh <= wan.MAX_AREA)
            assertEquals(0, fw % 32)
            assertEquals(0, fh % 32)
        }
    }

    /**
     * ⚠⚠ GitHub #27: Results tells results apart by the poster's image id, and an image-to-video
     * poster is the start picture, so it hashed the same for every clip from that picture and only
     * one could be kept. The id is the CLIP's now: two clips differ, the same clip (from the
     * cache) matches.
     */
    @Test
    fun aClipsPosterIsNamedAfterTheClipNotItsPixels() {
        val w = com.abrah.nightmare.npu.VideoWriter
        val a = java.io.File("/c/video/clip_wan_aaaa.mp4")
        val b = java.io.File("/c/video/clip_wan_bbbb.mp4")
        assertTrue(w.posterIdFor(a) != w.posterIdFor(b))
        assertEquals(w.posterIdFor(a), w.posterIdFor(java.io.File("/other/clip_wan_aaaa.mp4")))
        assertTrue(w.posterIdFor(a).startsWith("img_"))
    }

    /** ⚠⚠ The old `shape`/`size` choosers' sizes (the file migration: `WorkflowIoTest`). */
    @Test
    fun wanReadsTheOldShapeAndSize() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(704 to 1280, wan.fromLegacy(mapOf("size" to "704p", "shape" to "portrait")))
        assertEquals(832 to 480, wan.fromLegacy(mapOf("shape" to "landscape")))
        assertNull(wan.fromLegacy(mapOf("width" to "640")))
    }

    /**
     * ⭐ GitHub #23's chaining: `last_frame` is a picture, so it wires into another Wan node's
     * `image` like a photo does, and that node continues the clip with its own prompt.
     */
    @Test
    fun wanLastFrameChainsIntoTheNextClip() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(listOf("video", wan.LAST_FRAME), wan.outputs.map { it.name })
        assertEquals("IMAGE", wan.outputs.single { it.name == wan.LAST_FRAME }.type)
        val g = Graph(
            listOf(
                Node("a", wan.name),
                Node("b", wan.name, inputs = mapOf("image" to com.abrah.nightmare.Source("a", wan.LAST_FRAME))),
            )
        )
        assertNull(com.abrah.nightmare.sizeRefusal(g, NODE_TYPES, "a", "b", "image"))
    }

    /** ⚠ Wan wants 4n+1 frames: 24 a second plus one, 1 to 5 seconds; 0 seconds is ONE frame, a still. */
    @Test
    fun wanFrameCountsAreFourNPlusOne() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(121, wan.framesFor(5))
        assertEquals(25, wan.framesFor(1))
        assertEquals(1, wan.framesFor(0))
        assertEquals(121, wan.framesFor(9))
        for (s in 1..5) assertEquals(1, wan.framesFor(s) % 4)
    }

    /**
     * ⚠⚠ The memory guard's arithmetic: the default fits a 12 GB phone with apps open (it ran
     * from 6.2 GB free), 704p for 5 s asks for more than this phone usually has free.
     */
    @Test
    fun wanMemoryGuardArithmetic() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(12_090L, wan.tokens(832, 480, 121))
        assertEquals(27_280L, wan.tokens(1280, 704, 121))
        val gib = 1L shl 30
        assertTrue(wan.fits(wan.pinnedBytes(832, 480, 121), 11_379_708L * 1024)) // the S25
        assertTrue(wan.fits(wan.pinnedBytes(1280, 704, 121), 11_379_708L * 1024))
        assertTrue(!wan.fits(wan.pinnedBytes(1280, 704, 121), 8L * gib))
    }

    /** ⭐ Real SM8650s (2026-10-14): 480p 2 s rendered, 480p 5 s failed to map. v79 and unknown uncapped. */
    @Test
    fun anEightGenThreeMapsWhatItRan() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertTrue(wan.fitsNpuMap(wan.pinnedBytes(832, 480, 49), 75))
        assertTrue(wan.fitsNpuMap(wan.pinnedBytes(480, 832, 49), 75))
        assertTrue(!wan.fitsNpuMap(wan.pinnedBytes(832, 480, 121), 75))
        assertTrue(!wan.fitsNpuMap(wan.pinnedBytes(832, 480, 73), 75))
        assertTrue(!wan.fitsNpuMap(wan.pinnedBytes(1280, 704, 25), 75))
        assertTrue(wan.fitsNpuMap(wan.pinnedBytes(1280, 704, 121), 79))
        assertTrue(wan.fitsNpuMap(wan.pinnedBytes(1280, 704, 121), 0))
    }

    /**
     * ⭐ The per-clip memory budget (S25, 2026-10-15): only what died unbudgeted gets it. On the 12 GB S25,
     * 704p 5 s (killed ~15 s in) and 3 s stream; 704p 2 s and 480p 5 s, which render resident, keep full
     * speed. 16 GB never. v75: 480p 2 s, the one size a real 8 Gen 3 ran, launches unchanged.
     */
    @Test
    fun onlyTheClipsThatDiedGetAMemoryBudget() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        val s25 = 11_652_820_992L
        val gb16 = 15L shl 30
        assertEquals("4", wan.memoryBudget(wan.pinnedBytes(704, 1280, 121), s25, 79))
        assertEquals("4", wan.memoryBudget(wan.pinnedBytes(704, 1280, 73), s25, 79))
        assertEquals(null, wan.memoryBudget(wan.pinnedBytes(704, 1280, 49), s25, 79))
        assertEquals(null, wan.memoryBudget(wan.pinnedBytes(832, 480, 121), s25, 79))
        assertEquals(null, wan.memoryBudget(wan.pinnedBytes(704, 1280, 121), gb16, 79))
        assertEquals(null, wan.memoryBudget(wan.pinnedBytes(704, 1280, 121), 0, 0))
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(480, 832, 49), s25, 75)) // every clip on v75: resident rendered noise
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(480, 832, 121), s25, 75))
        assertEquals("2.75", wan.memoryBudget(wan.pinnedBytes(704, 1280, 49), gb16, 75))
    }

    /**
     * ⭐ What the slider warning judges, against real runs: a tester's S24 Ultra (SM8650, 1.6.171, two LoRAs)
     * rendered 928x928 for 2 s and failed 5 s (`need 4847 MB device / 2816 MB budget`); the S25 forced to
     * v75 rendered 480p 5 s at 2.75 and could not fit one 704p 5 s block under 3 GiB; the S25 at v79 ran
     * 704p 5 s budgeted at 4.
     */
    @Test
    fun theFitWarningMatchesWhatPhonesRan() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        val s24u = 11_620_000_000L
        val s25 = 11_652_820_992L
        assertTrue(wan.fitsPhone(928, 928, 49, 2, s24u, 75))
        assertTrue(!wan.fitsPhone(928, 928, 121, 2, s24u, 75))
        assertEquals(2, wan.secondsThatFit(928, 928, 2, s24u, 75))
        assertTrue(wan.fitsPhone(480, 832, 121, 0, s25, 75))
        assertTrue(!wan.fitsPhone(704, 1280, 121, 0, s25, 75))
        assertTrue(wan.fitsPhone(704, 1280, 121, 0, s25, 79))
    }

    /** ⭐ GitHub #25: "Match picture" gives the picture's shape within Wan's area; a near-match is left alone. */
    @Test
    fun matchPictureKeepsTheShapeWithinWansArea() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertEquals(928 to 928, wan.matchShape(1024, 1024))
        assertEquals(704 to 1248, wan.matchShape(1080, 1920))
        assertEquals(1248 to 704, wan.matchShape(1920, 1080))
        for ((w, h) in listOf(1024 to 1024, 1080 to 1920, 3000 to 1000)) {
            val (mw, mh) = wan.matchShape(w, h)
            assertTrue(mw.toLong() * mh <= wan.MAX_AREA && mw % 32 == 0 && mh % 32 == 0)
        }
        assertTrue(!wan.shapeDiffers(832, 480, 832, 480))
        assertTrue(wan.shapeDiffers(1024, 1024, 832, 480))
    }

    /** ⭐ A budgeted clip is warned about by one block's peak: 480p 5 s fits an 8 Gen 3 that way, 704p 5 s does not. */
    @Test
    fun aBudgetedClipIsJudgedByItsPeak() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        assertTrue(wan.fitsNpuMap(wan.peakBytes(480, 832, 121, "2.75"), 75))
        assertTrue(!wan.fitsNpuMap(wan.peakBytes(704, 1280, 121, "2.75"), 75))
        assertEquals(wan.pinnedBytes(480, 832, 49), wan.peakBytes(480, 832, 49, null))
    }

    /**
     * ⚠⚠ A Wan graph NAMES a model although its node has no `model` knob — or Run launches an
     * upscaler-only backend for it (`ensureBackendFor`). Neodragon's names none: the control.
     */
    @Test
    fun wanGraphsNameTheirModel() {
        assertEquals(
            listOf(com.abrah.nightmare.ModelCatalog.WAN22.id),
            com.abrah.nightmare.contextKeyModels(wanT2v.graph, NODE_TYPES),
        )
        assertEquals(emptyList<String>(), com.abrah.nightmare.contextKeyModels(t2v.graph, NODE_TYPES))
    }

    /** ⭐ One backend process for every size: the key is the model's own, like every DiT model. */
    @Test
    fun wanKeysOnItsModelNotItsSize() {
        val wan = com.abrah.nightmare.npu.WanVideoNode
        val a = wan.contextKey(wanT2v.graph.byId.getValue("video"))
        val b = wan.contextKey(Node("video", wan.name, params = mapOf("width" to "704", "height" to "1280")))
        assertEquals(a, b)
        assertEquals(com.abrah.nightmare.ModelCatalog.WAN, a.type)
        assertEquals(com.abrah.nightmare.ModelCatalog.WAN22.id, a.model)
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

    /**
     * ⭐ An output wired a clip has no auto upscale and a dimmed Upscale (the user, 2026-10-15),
     * read off the PORT: Wan's `last_frame` is a picture and stays upscalable.
     */
    @Test
    fun anOutputKnowsWhenItIsWiredAClip() {
        val types = NODE_TYPES
        val out = com.abrah.nightmare.MediaOutputNode
        val wan = com.abrah.nightmare.npu.WanVideoNode
        fun outputFrom(src: Source) = Graph(
            listOf(
                Node("video", wan.name),
                Node("output", out.name, inputs = mapOf("media" to src)),
            )
        )
        val clip = outputFrom(Source("video"))
        assertTrue(out.takesVideo(clip, clip.byId.getValue("output"), types))
        val last = outputFrom(Source("video", wan.LAST_FRAME))
        assertTrue(!out.takesVideo(last, last.byId.getValue("output"), types))
        val t2v = t2v.graph
        assertTrue(out.takesVideo(t2v, t2v.byId.getValue("output"), types))
    }
}
