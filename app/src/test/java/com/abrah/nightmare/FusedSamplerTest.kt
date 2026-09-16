package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val r = exec(host).run(graph(photo = photoFile()))
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
     * ⭐⭐ It FITS whatever it is given — the rule that retired `sizeRefusal`
     * for the image path. A 96x96 photo into a 64x64 model is not an error.
     */
    @Test
    fun aPhotoOfAnySizeIsFittedRatherThanRefused() = runBlocking {
        val host = RecordingHost()
        val r = exec(host).run(graph(photo = photoFile(w = 123, h = 41)))
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
    var lastEncodeHandle: String? = null
    var lastSampleHandle: String? = null
    var lastEncodeSize: Pair<Int, Int>? = null
    var lastScheduler: String? = null
    var lastSeed: Int? = null
    var blendA: String? = null
    var blendB: String? = null

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
        onProgress: (Ops.Progress) -> Unit,
    ): Ops.Result<Ops.Sampled> {
        samples++
        lastLatentHandle = latentHandle
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
