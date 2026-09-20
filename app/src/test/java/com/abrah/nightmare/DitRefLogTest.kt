package com.abrah.nightmare

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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

    private fun exec() = Executor(
        WholeRenderHost(),
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

    private fun graph(type: String, photo: String? = null, reference: String? = null): Graph {
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
                    ),
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

private class WholeRenderHost : OpHost {
    override suspend fun residentHandles(): Set<String> = emptySet()

    override suspend fun generate(
        prompt: String, negative: String, steps: Int, cfg: Double, seed: Int,
        width: Int, height: Int, imagePng: ByteArray?, denoise: Double,
        maskPng: ByteArray?, referencePngs: List<ByteArray>,
        onProgress: (Ops.Progress) -> Unit,
    ): Ops.Result<Ops.Decoded> =
        Ops.Result.Ok(Ops.Decoded(ditSolidPng(width, height, 0xFF112233.toInt()), "sha", 1L, 2L))

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
