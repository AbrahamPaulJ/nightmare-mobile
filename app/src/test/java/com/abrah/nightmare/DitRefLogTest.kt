package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ **The `[ref]` instrumentation line says something or is not printed.**
 *
 * ⚠⚠⚠ It was unconditional, so a Z-Image text-to-image run logged
 * `image=none reference=none`. Two ways that is wrong rather than merely
 * untidy: a text-to-image run sends no picture at all, so the line carries no
 * information; and `reference` is a **FLUX.2-only port** ([SdSampler.inputs]),
 * so naming it on a Z-Image run invites the reader to hunt for a feature that
 * family does not have. Reported from the run log on 2026-09-21.
 *
 * ⚠ The line itself stays — it is the evidence for `REF_ENCODED_AT_CANVAS`,
 * and the comment on it explains what two reverted fixes cost without it.
 */
@RunWith(RobolectricTestRunner::class)
class DitRefLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val said = mutableListOf<String>()

    private val host = WholeRenderHost()

    private fun exec() = Executor(
        host,
        android = ApplicationProvider.getApplicationContext(),
    )

    /** ⚠ `onLog` is where a node's narration comes out ([Executor.run]). */
    private suspend fun run(g: Graph) =
        exec().run(g, onLog = { _, text -> said += text })

    private fun photoFile(): String {
        val f = tmp.newFile("photo.png")
        f.writeBytes(ditSolidPng(64, 64, 0xFF3366AA.toInt()))
        return f.absolutePath
    }

    private fun graph(
        type: String, photo: String? = null, reference: String? = null, extra: Map<String, String> = emptyMap(),
    ): Graph {
        val wires = mutableMapOf("prompt" to Source("prompt"))
        if (photo != null) wires["image"] = Source("photo")
        if (reference != null) wires["reference"] = Source("ref")
        return Graph(
            listOfNotNull(
                Node("prompt", "core.prompt", mapOf("prompt" to "a cat", "negative" to "")),
                photo?.let { Node("photo", "core.image", mapOf("uri" to it)) },
                reference?.let { Node("ref", "core.image", mapOf("uri" to it)) },
                Node(
                    "sample", type,
                    mapOf(
                        "model" to "m", "width" to "64", "height" to "64",
                        "steps" to "4", "cfg" to "1.0", "seed" to "1",
                    ) + extra,
                    wires,
                ),
            )
        )
    }

    private fun refLines() = said.filter { it.startsWith("[ref]") }

    @Test
    fun aZImageTextToImageSaysNothingAboutAReference() = runBlocking {
        run(graph(SdSampler.ZIMAGE.name))
        assertTrue(
            "a text-to-image run sends no picture, so [ref] has nothing to say: ${refLines()}",
            refLines().isEmpty(),
        )
    }

    /**
     * ⚠ Engine ABI 108 (backend 038): a DiT node's schedule rides on its sampler value (`euler_karras`) and must reach
     * the host whole. A filter on the bare sampler ids dropped it, and every Karras render came back Normal.
     */
    @Test
    fun aDitScheduleReachesTheHost() = runBlocking {
        run(graph(SdSampler.FLUX2.name, extra = mapOf("scheduler" to "euler_karras")))
        assertEquals("euler_karras", host.scheduler)
    }

    /** ⭐ Images 2 to 4 (2026-10-21): `reference`, `reference_3`, `reference_4`, sent to the host in that order. */
    @Test
    fun threeReferencesReachTheHostInOrder() = runBlocking {
        val colours = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        val files = colours.mapIndexed { i, c -> tmp.newFile("r$i.png").apply { writeBytes(ditSolidPng(16 + 8 * i, 16, c)) }.absolutePath }
        val base = graph(SdSampler.FLUX2.name, photo = photoFile())
        val sampler = base.byId.getValue("sample")
        val ports = listOf("reference") + SdSampler.EXTRA_REFS
        val g = base.copy(nodes = base.nodes.map { if (it.id == "sample") sampler.copy(inputs = sampler.inputs + ports.mapIndexed { i, p -> p to Source("r$i") }) else it } +
            files.mapIndexed { i, f -> Node("r$i", "core.image", mapOf("uri" to f)) })
        val r = run(g)
        assertTrue("error=${r.error}", r.error == null)
        val widths = host.refs.map { b ->
            android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                .also { android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, it) }.outWidth
        }
        assertEquals(listOf(16, 24, 32), widths)
    }

    /** ⚠ Same for FLUX.2 — the family is not the test, having a picture is. */
    @Test
    fun aKleinTextToImageSaysNothingEither() = runBlocking {
        run(graph(SdSampler.FLUX2.name))
        assertTrue(refLines().toString(), refLines().isEmpty())
    }

    /**
     * ⭐ …and it IS printed the moment a picture goes over the wire, or the
     * fix would have deleted the evidence instead of quieting it.
     */
    @Test
    fun anImageToImageRunStillStatesItsPixels() = runBlocking {
        val r = run(graph(SdSampler.FLUX2.name, photo = photoFile()))
        val line = refLines().singleOrNull()
        assertTrue("error=${r.error} said=$said", line != null)
        // ⚠ The SIZES are not asserted here — a DiT node renders at its own
        // canvas, not at the node's width/height params, and pinning a number
        // would make this a test of that rule instead of a test of the line.
        assertTrue(line!!, Regex("""image=\d+x\d+""").containsMatchIn(line))
        // ⚠ `reference=none` is INFORMATION here and stays: FLUX.2 has the
        // port, so "nothing was wired to it" is a fact about this run. It is
        // only noise on a family that has no such port at all.
        assertTrue(line, line.contains("reference=none"))
    }
}

/**
 * ⭐⭐ Klein inpaint is crop, EDIT, paste back (`SdSampler.FLUX2_INPAINT`, 2026-10-23): the engine gets the cut with
 * NO mask and strength 1, because with a mask Klein redraws almost nothing inside it (measured 2026-09-20).
 */
@RunWith(RobolectricTestRunner::class)
class FluxInpaintTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun kleinEditsTheCutWholeAndTheMaskStaysInTheApp() = runBlocking {
        val host = WholeRenderHost()
        val f = tmp.newFile("photo.png").apply { writeBytes(ditSolidPng(64, 64, 0xFF3366AA.toInt())) }
        val g = Graph(listOf(
            Node("prompt", "core.prompt", mapOf("prompt" to "make it red", "negative" to "")),
            Node("photo", "core.image", mapOf("uri" to f.absolutePath)),
            Node("s", SdSampler.FLUX2_INPAINT.name, mapOf(
                "model" to "flux2_klein_4b", "width" to "512", "height" to "512", "steps" to "4", "seed" to "1",
                MaskNode.OPS to "s0.3:0.5,0.5~0,0.02",
            ), sources("prompt" to "prompt", "image" to "photo")),
        ))
        val r = Executor(host, android = ApplicationProvider.getApplicationContext()).run(g)
        assertEquals(null, r.error)
        assertEquals("no mask reaches Klein", null, host.mask)
        assertEquals(1.0, host.denoise!!, 0.0)
        assertTrue(SdSampler.canInpaint(Family.FLUX2))
    }
}

private class WholeRenderHost : OpHost {
    /** The last scheduler a whole render was sent. */
    var scheduler: String? = null

    /** The references the last whole render was sent, in order. */
    var refs: List<ByteArray> = emptyList()

    /** The last whole render's mask and strength. */
    var mask: ByteArray? = null
    var denoise: Double? = null

    override suspend fun residentHandles(): Set<String> = emptySet()

    override suspend fun generate(
        prompt: String, negative: String, steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, imagePng: ByteArray?, denoise: Double,
        maskPng: ByteArray?, referencePngs: List<ByteArray>,
        loras: List<Pair<String, Double>>,
        onProgress: (Ops.Progress) -> Unit,
        scheduler: String?,
    ): Ops.Result<Ops.Decoded> {
        this.scheduler = scheduler
        this.refs = referencePngs
        this.mask = maskPng
        this.denoise = denoise
        return Ops.Result.Ok(Ops.Decoded(ditSolidPng(width, height, 0xFF112233.toInt()), "sha", 1L, 2L))
    }

    override suspend fun latentBlend(a: String, b: String, maskPng: ByteArray) =
        Ops.Result.Err(501, "no")

    override suspend fun vaeEncode(png: ByteArray, seed: Int, width: Int, height: Int) =
        Ops.Result.Err(501, "no")

    override suspend fun encodeText(prompt: String, negative: String) =
        Ops.Result.Err(501, "no")

    override suspend fun sample(
        steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, latentHandle: String?, denoise: Double,
        scheduler: String, condHandle: String, aspect: String?,
        inpaintImage: ByteArray?, inpaintMask: ByteArray?,
        template: Ops.TemplateInputs?,
        variation: Ops.Variation?,
        onProgress: (Ops.Progress) -> Unit,
    ) = Ops.Result.Err(501, "no")

    override suspend fun vaeDecode(latentHandle: String, width: Int, height: Int) =
        Ops.Result.Err(501, "no")

    override suspend fun upscale(rgb: ByteArray, width: Int, height: Int, upscalerPath: String) =
        Ops.Result.Err(501, "no")
}

private fun ditSolidPng(w: Int, h: Int, color: Int): ByteArray {
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    bmp.eraseColor(color)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    return out.toByteArray()
}
