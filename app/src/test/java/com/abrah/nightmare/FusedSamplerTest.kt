package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐⭐ The fused sampler — `docs/ARCHITECTURE.md` §5.7.
 *
 * ⚠⚠ **What is worth testing here is the half that is now INVISIBLE.** When
 * `vae_encode`, `latent_blend` and `vae_decode` were nodes, a wrong wire was a
 * wrong wire and `MaskTest` could read it off the graph. Inside one node the
 * same mistake is an argument order, and the failure it produces is a plausible
 * picture with the wrong region replaced — which nothing throws on.
 * ⇒ These assert the CALLS the node makes, not the pixels it returns.
 */
@RunWith(RobolectricTestRunner::class)
// ⚠⚠ NATIVE, or every Canvas draw is a no-op and a pixel check reads 0 —
// which passes "this should be black" for nothing (found 2026-09-17).
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class FusedSamplerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // core.image reads a file, so it needs a platform context.
    private fun exec(host: OpHost) =
        Executor(host, android = ApplicationProvider.getApplicationContext())

    /** A sampler wired to a prompt, plus whatever else the test asks for. */
    private fun graph(
        photo: String? = null,
        params: Map<String, String> = emptyMap(),
        // ⚠ The INPAINT type by default: most of these tests are about the
        // mask half, and a `sample` type declares none of its params.
        type: String = SdSampler.SD15_INPAINT.name,
    ): Graph {
        val wires = mutableMapOf("prompt" to Source("prompt"))
        if (photo != null) wires["image"] = Source("photo")
        return Graph(
            listOfNotNull(
                Node("prompt", "core.prompt", mapOf("prompt" to "a cat", "negative" to "blurry")),
                photo?.let { Node("photo", "core.image", mapOf("uri" to it)) },
                Node(
                    "sample", type,
                    mapOf(
                        "model" to "m", "width" to "64", "height" to "64",
                        "steps" to "4", "cfg" to "7.5", "seed" to "1",
                    ) + params,
                    wires,
                ),
            )
        )
    }

    /** A real PNG on disk, because `core.image` decodes a file. */
    private fun photoFile(w: Int = 96, h: Int = 96): String {
        val f = tmp.newFile("photo.png")
        f.writeBytes(solidPng(w, h, 0xFF3366AA.toInt()))
        return f.absolutePath
    }

    // --- what each wiring means ---------------------------------------------

    @Test
    fun aPromptAloneIsTextToImage() = runBlocking {
        val host = RecordingHost()
        val r = exec(host).run(graph())
        assertNull(r.error)
        // ⚠ One of each, inside ONE node: the encode, the sample and the decode
        // are what the three deleted nodes used to be.
        assertEquals(1, host.encodesText)
        assertEquals(1, host.samples)
        assertEquals(1, host.decodes)
        // ⭐ No VAE encode: nothing was wired, so there is nothing to start from.
        assertEquals(0, host.encodes)
        assertNull("txt2img must start from noise", host.lastLatentHandle)
    }

    @Test
    fun aPhotoWiredInIsImageToImage() = runBlocking {
        val host = RecordingHost()
        // ⚠ The plain `sample` type, not the default `inpaint` — this is
        // testing generic photo-wiring, and since 2026-09-19 an inpaint with
        // no mask refuses rather than falling back to a plain i2i render.
        val r = exec(host).run(graph(photo = photoFile(), type = SdSampler.SD15.name))
        assertNull(r.error)
        assertEquals(1, host.encodes)
        // ⭐ …and the sampler started from what the encode produced.
        assertEquals(host.lastEncodeHandle, host.lastLatentHandle)
    }

    /**
     * ⭐⭐ **The WIRE decides, and nothing else.**
     *
     * ⚠⚠ There was a `start from` switch here so a flow could flip without
     * rewiring. The user removed it 2026-09-15 — *"start from is decided by
     * whether an image is connected, simple as that"* — and they were right for
     * this node: the alternative to a wired photo is nothing at all, so the knob
     * restated what the canvas already showed. ⚠ The VIDEO sampler keeps its
     * switch, where the alternative is four seconds of SSD1B making a first frame.
     */
    @Test
    fun noPhotoWiredMeansNoEncode() = runBlocking {
        val host = RecordingHost()
        assertNull(exec(host).run(graph()).error)
        assertEquals("nothing to start from, so nothing to encode", 0, host.encodes)
        assertNull(host.lastLatentHandle)
    }

    // --- the inpaint half, which used to be four nodes -----------------------

    private val painted = mapOf(
        // ⚠ One stroke over the middle. [MaskState.decode]'s own format.
        MaskNode.OPS to "s0.3:0.5,0.5~0,0.02",
    )

    @Test
    fun aPaintedMaskBlendsInLatentSpace() = runBlocking {
        val host = RecordingHost()
        val r = exec(host).run(graph(photo = photoFile(), params = painted))
        assertNull(r.error)
        assertEquals(1, host.blends)
    }

    /**
     * ⚠⚠⚠ **base then repaint, and never the other way round.** The mask's
     * WHITE area is where the new pixels show through; swapped, it replaces
     * everything EXCEPT what was painted — a plausible picture and a silent
     * mistake. This is what `MaskTest.theInpaintRecipeWiresBlendCorrectly` used
     * to read off the graph before the wire moved inside the node.
     */
    @Test
    fun theBlendTakesTheSourceAsBaseAndTheRenderAsRepaint() = runBlocking {
        val host = RecordingHost()
        exec(host).run(graph(photo = photoFile(), params = painted))
        assertEquals("base must be the encoded SOURCE", host.lastEncodeHandle, host.blendA)
        assertEquals("repaint must be the SAMPLED latent", host.lastSampleHandle, host.blendB)
    }

    /**
     * ⭐⭐ **An inpaint sends its picture and mask with `sample`** — the ones a
     * 9-channel checkpoint conditions on — and they are the SAME bytes the
     * encode and the blend got. The backend drops them for any other UNet
     * (`backend-patches/006`), which is why the node never asks which kind of
     * model is loaded. ⚠ A mask that differed from the blend's would make the
     * model repaint one region while the blend keeps another.
     */
    @Test
    fun anInpaintSendsItsPictureAndMaskToTheSampler() = runBlocking {
        val host = RecordingHost()
        assertNull(exec(host).run(graph(photo = photoFile(), params = painted)).error)
        assertNotNull("a 9-channel model needs the picture", host.lastInpaintImage)
        assertArrayEquals("the picture must be the one encoded", host.lastEncodePng, host.lastInpaintImage)
        assertArrayEquals("the mask must be the one blended", host.lastBlendMask, host.lastInpaintMask)
    }

    /** ⚠ …and nothing else does: image-to-image has no mask to condition on. */
    @Test
    fun imageToImageSendsNoInpaintFields() = runBlocking {
        val host = RecordingHost()
        assertNull(exec(host).run(graph(photo = photoFile(), type = SdSampler.SD15.name)).error)
        assertNull(host.lastInpaintImage)
        assertNull(host.lastInpaintMask)
    }

    /**
     * ⭐⭐ It FITS whatever it is given — the rule that retired `sizeRefusal`
     * for the image path. A 96x96 photo into a 64x64 model is not an error.
     */
    @Test
    fun aPhotoOfAnySizeIsFittedRatherThanRefused() = runBlocking {
        val host = RecordingHost()
        // ⚠ Plain `sample`, same reason as `aPhotoWiredInIsImageToImage`.
        val r = exec(host).run(graph(photo = photoFile(w = 123, h = 41), type = SdSampler.SD15.name))
        assertNull("a photo of any shape must render", r.error)
        assertEquals(64, host.lastEncodeSize?.first)
        assertEquals(64, host.lastEncodeSize?.second)
    }

    // --- the two editors have something to drive -----------------------------

    /**
     * ⭐⭐ The framing params are `image.crop`'s OWN names, and that is what lets
     * `CropEditor` drive this node without a second spelling.
     *
     * ⚠⚠ 1.4.54 shipped with the params moved onto the sampler and the editors
     * left behind, so inpaint could not be painted by hand at all. The storage
     * and the interface are one change; this is the half a test can hold.
     */
    @Test
    fun theSamplerCarriesTheFramingParamsTheEditorReads() {
        val names = SdSampler.SD15_INPAINT.widgets.map { it.name }.toSet()
        for (p in listOf("x", "y", "w", "h")) {
            assertTrue("the crop editor reads \"$p\"", p in names)
        }
        // ⚠ …and the painting params the mask editor reads.
        for (p in listOf(MaskNode.OPS, "grow", "feather")) {
            assertTrue("the mask editor reads \"$p\"", p in names)
        }
    }

    /**
     * ⭐ A crop frames for its own `out_w`/`out_h`; the sampler frames for its
     * RENDER size. One function answers both, so the editor's viewport and the
     * pixels the node emits cannot disagree.
     */
    @Test
    fun theFramingSizeIsTheRenderSizeOnASampler() {
        val sampler = graph().byId.getValue("sample")
        assertEquals(64 to 64, com.abrah.nightmare.canvas.framingOutSize(sampler))
        val crop = Node("c", "image.crop", mapOf("out_w" to "300", "out_h" to "200"))
        assertEquals(300 to 200, com.abrah.nightmare.canvas.framingOutSize(crop))
    }

    // --- the knobs that must reach the backend -------------------------------

    @Test
    fun theModelsSchedulerAndSeedAreSent() = runBlocking {
        val host = RecordingHost()
        exec(host).run(graph(params = mapOf("scheduler" to "euler_a", "seed" to "77")))
        assertEquals("euler_a", host.lastScheduler)
        assertEquals(77, host.lastSeed)
    }

    /**
     * ⚠ Two samplers on one prompt encode the text TWICE, and that is the cost
     * §5.7 accepts: a conditioning belongs to the checkpoint that made it, so
     * the wire carries text instead. The backend content-addresses the result,
     * so the second encode is ~0 ms — asserted here as the same handle rather
     * than as a timing.
     */
    @Test
    fun twoSamplersOnOnePromptGetTheSameConditioning() = runBlocking {
        val host = RecordingHost()
        val g = graph()
        val two = Graph(
            g.nodes + Node(
                "second", "sd15.sample",
                g.byId.getValue("sample").params + ("seed" to "2"),
                mapOf("prompt" to Source("prompt")),
            )
        )
        assertNull(exec(host).run(two).error)
        assertEquals(2, host.encodesText)
        assertEquals(1, host.distinctConds.size)
    }

    /**
     * ⭐⭐ The aspect chip belongs to the node's FAMILY, not to the top bar.
     *
     * ⚠⚠ Reported from the phone 2026-09-17 as "switching a node to SD 1.5
     * does not change it": the node had switched, but with SDXL selected in the
     * top bar its inspector still drew SDXL's aspect chip instead of SD 1.5's
     * resolutions. Checked both ways round, so a declaration that ignored the
     * family entirely fails one of them.
     */
    /**
     * ⭐⭐ An SDXL aspect frames the ASPECT's rectangle and encodes it centred
     * on the 1024² canvas — upstream's `padBitmapToCanvas`.
     *
     * ⚠⚠ Reported 2026-09-17: the crop and mask stayed square on SDXL while the
     * backend kept only the middle band, so the framing chosen was not the one
     * rendered. Checked by PIXEL: the band above the rectangle must be black
     * and its middle the photo, which a square encode fails.
     */
    @Test
    fun anSdxlAspectEncodesTheFrameCentredOnTheCanvas() = runBlocking {
        val sdxl = ModelCatalog.all.first { it.family == Family.SDXL }.id
        val host = RecordingHost()
        val g = graph(
            photo = photoFile(),
            type = SdSampler.SDXL.name,
            params = mapOf("model" to sdxl, "width" to "1024", "height" to "1024", "aspect" to "16:9"),
        )
        assertEquals(1024 to 576, SdSampler.SDXL.framesTo(g.byId.getValue("sample")))
        assertNull(exec(host).run(g).error)
        val png = host.lastEncodePng!!
        val enc = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(1024, enc.width)
        assertEquals(1024, enc.height)
        // (1024 - 576) / 2 = 224 rows of black above the frame.
        assertEquals("above the aspect rectangle is canvas", 0, enc.getPixel(512, 100) and 0xFFFFFF)
        assertEquals("inside it is the photo", 0x3366AA, enc.getPixel(512, 512) and 0xFFFFFF)
    }

    /**
     * ⭐⭐ OUTPAINT: a frame hanging off the photo is MASKED there with nothing
     * painted at all — DreamUI's `protect`, and the reason Run is not a plain
     * image-to-image that repaints invented edges as if they were content.
     */
    @Test
    fun aPaddedInpaintFrameMasksThePaddingWithNothingPainted() = runBlocking {
        val host = RecordingHost()
        val r = exec(host).run(
            graph(
                photo = photoFile(),
                params = mapOf("x" to "-0.2", "y" to "-0.2", "w" to "1.4", "h" to "1.4"),
            )
        )
        assertNull(r.error)
        assertEquals("the padding alone is a mask", 1, host.blends)
        val png = host.lastBlendMask!!
        val m = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals("a padded corner is repainted", 0xFF, m.getPixel(1, 1) and 0xFF)
        assertEquals("the photo's middle is kept", 0, m.getPixel(m.width / 2, m.height / 2) and 0xFF)
    }

    /**
     * ⭐⭐ "Stitch to original" pastes into the whole PHOTO. ⚠ Lost in the fusion
     * and reported 2026-09-17: the toggle was read by nothing. The control is
     * the same half-frame without it, which must stay frame-sized.
     */
    @Test
    fun stitchPastesIntoTheWholePhoto() = runBlocking {
        val half = painted + mapOf("x" to "0.25", "y" to "0.25", "w" to "0.5", "h" to "0.5")
        val off = exec(RecordingHost()).run(graph(photo = photoFile(), params = half))
        val on = exec(RecordingHost()).run(
            graph(photo = photoFile2(), params = half + (PasteNode.STITCH to "true"))
        )
        assertNull(off.error)
        assertNull(on.error)
        val a = off.outputs["sample"] as Value.Image
        val b = on.outputs["sample"] as Value.Image
        assertEquals("without stitch: the frame", 48 to 48, a.w to a.h)
        assertEquals("with stitch: the photo", 96 to 96, b.w to b.h)
    }

    private fun photoFile2(): String {
        val f = tmp.newFile("photo2.png")
        f.writeBytes(solidPng(96, 96, 0xFF3366AA.toInt()))
        return f.absolutePath
    }

    // --- a chain through a node a person must act on --------------------------

    /** generate -> inpaint, the chain the rules below are about. */
    private fun chain(inpaintParams: Map<String, String> = emptyMap()) = Graph(
        listOf(
            Node("prompt", "core.prompt", mapOf("prompt" to "a cat", "negative" to "")),
            Node(
                "gen", SdSampler.SD15.name,
                mapOf("model" to "m", "width" to "64", "height" to "64", "steps" to "4", "seed" to "1"),
                sources("prompt" to "prompt"),
            ),
            Node(
                "inp", SdSampler.SD15_INPAINT.name,
                mapOf("model" to "m", "width" to "64", "height" to "64", "steps" to "4", "seed" to "1") + inpaintParams,
                sources("prompt" to "prompt", "image" to "gen"),
            ),
        )
    )

    /**
     * ⭐⭐ Nothing painted on a GENERATED picture: the render upstream is made,
     * and the inpaint WAITS — no error, and nothing repainted.
     *
     * ⚠⚠ **The rule went through two wrong shapes in one day, 2026-09-18/19,
     * before landing here.** First it skipped the wait and rendered a
     * full-frame repaint unattended (wrong: the person still wants to be
     * stopped). Then it kept the wait but pre-filled the mask editor with a
     * full mask on open (also wrong, reverted the next day — *"lets not do
     * the full masking thing for inpaint. instead if user doesnt mask just
     * show error saying nothing masked, this should be always true for
     * inpaint nodes"*). ⇒ Just the refusal, no auto-fill, and — the actual
     * behaviour change this landed on — it now applies to a PHOTO with
     * nothing painted too, which used to run as a plain re-render and is the
     * second case in this test.
     */
    @Test
    fun anInpaintOnAGeneratedPictureWaitsToBePainted() = runBlocking {
        val host = RecordingHost()
        val r = exec(host).run(chain())
        assertNull("waiting is not an error", r.error)
        assertEquals("inp", r.waiting?.first)
        assertEquals(Outcome.BLOCKED, r.runs.first { it.id == "inp" }.outcome)
        assertEquals("the picture upstream is made", Outcome.RAN, r.runs.first { it.id == "gen" }.outcome)
        assertEquals("only the generate sampled", 1, host.samples)

        val photo = exec(RecordingHost()).run(graph(photo = photoFile()))
        assertEquals("a photo with nothing painted now refuses too", "sample", photo.waiting?.first)
    }

    /**
     * ⭐⭐ Painted on one picture, handed another: WAIT by name. Painted on the
     * picture that arrives: run. Both in one test, so each is the other's control.
     */
    @Test
    fun aMaskPaintedOnADifferentPictureWaits() = runBlocking {
        val first = exec(RecordingHost()).run(chain())
        val made = (first.outputs["gen"] as Value.Image).id
        val stroke = MaskNode.OPS to "s0.3:0.5,0.5~0,0.02"

        val stale = exec(RecordingHost()).run(chain(mapOf(stroke, MaskNode.PAINTED_ON to "img_somethingelse")))
        assertEquals("inp", stale.waiting?.first)
        assertTrue(stale.waiting!!.second.contains("changed"))

        val host = RecordingHost()
        val fresh = exec(host).run(chain(mapOf(stroke, MaskNode.PAINTED_ON to made)))
        assertNull(fresh.waiting)
        assertNull(fresh.error)
        assertEquals("the inpaint repainted", 1, host.blends)
    }

    /** ⭐ DreamUI's rule, per kind: inpaint may pad, image-to-image never. */
    @Test
    fun onlyInpaintMayPad() {
        assertEquals(PadRule.OUTPAINT, padRuleFor(SdSampler.SDXL_INPAINT.name))
        assertEquals(PadRule.NEVER, padRuleFor(SdSampler.SD15.name))
        assertEquals(PadRule.WHEN_TOO_SMALL, padRuleFor("image.crop"))
    }

    @Test
    fun theAspectChipFollowsTheNodesFamilyNotTheSelection() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sdxl = ModelCatalog.all.first { it.family == Family.SDXL }.id
        fun declaresAspect(t: SdSampler) = t.widgets.any { it.name == "aspect" }
        try {
            SelectedModel.set(ctx, sdxl)
            assertEquals(false, declaresAspect(SdSampler.SD15))
            assertTrue(declaresAspect(SdSampler.SDXL))
            SelectedModel.set(ctx, V1_MODEL)
            assertTrue(declaresAspect(SdSampler.SDXL_INPAINT))
            assertEquals(false, declaresAspect(SdSampler.SD15_INPAINT))
        } finally {
            SelectedModel.set(ctx, V1_MODEL)
        }
    }
}

/**
 * ⚠ Its own fake rather than [ExecutorTest]'s: what these tests assert is the
 * ARGUMENTS, and recording them in the shared one would add fields nothing else
 * reads.
 */
private class RecordingHost : OpHost {
    val resident = mutableSetOf<String>()
    var samples = 0
    var decodes = 0
    var encodes = 0
    var encodesText = 0
    var blends = 0

    val distinctConds = mutableSetOf<String>()
    var lastLatentHandle: String? = null
    var lastInpaintImage: ByteArray? = null
    var lastInpaintMask: ByteArray? = null
    var lastEncodeHandle: String? = null
    var lastSampleHandle: String? = null
    var lastEncodeSize: Pair<Int, Int>? = null
    var lastScheduler: String? = null
    var lastSeed: Int? = null
    var blendA: String? = null
    var blendB: String? = null
    var lastEncodePng: ByteArray? = null
    var lastBlendMask: ByteArray? = null

    override suspend fun residentHandles(): Set<String> = resident.toSet()

    override suspend fun encodeText(prompt: String, negative: String): Ops.Result<Ops.Cond> {
        encodesText++
        val id = "cond_" + (prompt + "|" + negative).hashCode().toUInt().toString(16)
        distinctConds += id
        resident += id
        return Ops.Result.Ok(Ops.Cond(id, 77, 768, "neg", "pos", 129L, 130L))
    }

    override suspend fun vaeEncode(
        png: ByteArray, seed: Int, width: Int, height: Int,
    ): Ops.Result<Ops.Sampled> {
        encodes++
        lastEncodeSize = width to height
        lastEncodePng = png
        val id = "lat_enc_" + listOf(png.size, seed, width, height).joinToString("|")
            .hashCode().toUInt().toString(16)
        lastEncodeHandle = id
        resident += id
        return Ops.Result.Ok(Ops.Sampled(id, "sha_$id", 0, seed, 88L, 90L, 0, -1L, -1L))
    }

    override suspend fun sample(
        steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, latentHandle: String?, denoise: Double,
        scheduler: String, condHandle: String, aspect: String?,
        inpaintImage: ByteArray?, inpaintMask: ByteArray?,
        onProgress: (Ops.Progress) -> Unit,
    ): Ops.Result<Ops.Sampled> {
        samples++
        lastLatentHandle = latentHandle
        lastInpaintImage = inpaintImage
        lastInpaintMask = inpaintMask
        lastScheduler = scheduler
        lastSeed = seed
        val id = "lat_" + listOf(condHandle, steps, cfg, seed, width, height, latentHandle.orEmpty())
            .joinToString("|").hashCode().toUInt().toString(16)
        lastSampleHandle = id
        resident += id
        return Ops.Result.Ok(Ops.Sampled(id, "sha_$id", steps, seed, 100L, 101L, steps, 5L, 99L))
    }

    override suspend fun latentBlend(
        a: String, b: String, maskPng: ByteArray,
    ): Ops.Result<Ops.Blended> {
        blends++
        blendA = a
        blendB = b
        lastBlendMask = maskPng
        val id = "lat_mix_" + listOf(a, b).joinToString("|").hashCode().toUInt().toString(16)
        resident += id
        return Ops.Result.Ok(Ops.Blended(id, "sha_$id", "mask_sha", 50L))
    }

    override suspend fun vaeDecode(
        latentHandle: String, width: Int, height: Int,
    ): Ops.Result<Ops.Decoded> {
        decodes++
        if (latentHandle !in resident) return Ops.Result.Err(400, "unknown latent: $latentHandle")
        return Ops.Result.Ok(
            Ops.Decoded(solidPng(width, height, latentHandle.hashCode() or 0xFF000000.toInt()),
                "rgb_$latentHandle", 190L, 195L)
        )
    }

    override suspend fun upscale(
        rgb: ByteArray, width: Int, height: Int, upscalerPath: String,
    ): Ops.Result<Ops.Upscaled> = Ops.Result.Ok(Ops.Upscaled(ByteArray(0), width, height, 1, 1))
}

private fun solidPng(w: Int, h: Int, color: Int): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.eraseColor(color)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
