package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.SdSampler
import com.abrah.nightmare.sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⭐⭐ The reference thumbnail on a FLUX.2 sampler.
 *
 * ⚠⚠ [NodeBox.refPreview] is DERIVED in [layout] rather than stored, which is
 * the cheap way to get it — but derived wiring is exactly the kind that stops
 * working in silence. Nothing throws when a thumbnail is simply absent, and
 * the node still renders correctly, so only a test notices.
 *
 * ⚠ The picture shown is the one the UPSTREAM node is already showing: a
 * reference is somebody else's output, so there is no second source of truth
 * to keep in step.
 */
class ReferencePreviewTest {

    private val previews = mapOf(
        "base" to ("img_base" to 1f),
        "ref" to ("img_ref" to 0.5f),
    )

    private fun graph(wireReference: Boolean) = Workflow(
        Graph(
            listOf(
                Node("p", "core.prompt"),
                Node("base", "core.image"),
                Node("ref", "core.image"),
                Node(
                    "gen", SdSampler.FLUX2.name,
                    params = mapOf("model" to "flux2_klein_4b"),
                    inputs = if (wireReference) {
                        sources("prompt" to "p", "image" to "base", "reference" to "ref")
                    } else {
                        sources("prompt" to "p", "image" to "base")
                    },
                ),
            )
        ),
        mapOf("p" to Pt(0f, 0f), "base" to Pt(0f, 100f), "ref" to Pt(0f, 200f), "gen" to Pt(400f, 0f)),
    )

    private fun samplerBox(wireReference: Boolean) =
        layout(graph(wireReference), NODE_TYPES, previews).first { it.id == "gen" }

    /** ⭐ Wired: the sampler shows the reference's OWN picture, not the base's. */
    @Test
    fun aWiredReferenceIsShownOnTheSampler() {
        val box = samplerBox(wireReference = true)
        assertNotNull("no reference thumbnail on a sampler with one wired", box.refPreview)
        assertEquals(
            "the thumbnail showed the wrong picture",
            "img_ref",
            box.refPreview!!.imageId,
        )
    }

    /** ⚠ Unwired: every other node is unchanged, which is most of them. */
    @Test
    fun aSamplerWithNoReferenceHasNoExtraThumbnail() {
        assertNull(samplerBox(wireReference = false).refPreview)
    }

    /**
     * ⚠⚠ The box must GROW by the thumbnail, or the picture draws outside the
     * node and over whatever is beneath it. Height and the stacking order are
     * one change, and a wrong `refPreviewTop` still renders — just in the
     * wrong place.
     */
    @Test
    fun theNodeGrowsToFitTheReferenceAndStacksItOnTop() {
        val with = samplerBox(wireReference = true)
        val without = samplerBox(wireReference = false)
        assertTrue(
            "the node did not grow for its reference thumbnail",
            with.height > without.height,
        )
        assertTrue(
            "the reference must sit ABOVE the node's own preview",
            with.refPreviewTop < with.previewTop,
        )
    }
}
