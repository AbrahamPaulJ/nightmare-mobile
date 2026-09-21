package com.abrah.nightmare

import com.abrah.nightmare.canvas.Pt
import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.toJson
import com.abrah.nightmare.canvas.workflowFromJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ **A shared inpaint flow is still an inpaint flow when it is opened.**
 *
 * ⚠⚠⚠ Reported 2026-09-21: a user shared a custom inpaint flow and the JSON
 * appeared to hold a *generate* node. There are two different things that could
 * mean, and only one of them is a bug:
 *
 * 1. the `type` is wrong, so the flow really does open as text-to-image — that
 *    would be a data-loss bug, and these tests are what say it is not happening;
 * 2. the `id` reads `sd15_generate` while the type is `sd15.inpaint` — which is
 *    expected and harmless. A node keeps the id it was BORN with, because wires
 *    reference it, so a node dragged out as generate and switched to an inpaint
 *    checkpoint carries a stale id for the rest of its life. `NodeNameTest` is
 *    why that no longer shows on the canvas; the raw JSON still shows it, and
 *    that is what reading a shared file looks like.
 *
 * ⚠ Robolectric, for `org.json` alone: the JVM stub throws
 * *"Method put in org.json.JSONObject not mocked"*, which looks like a failure
 * in the code under test and is not one.
 */
@RunWith(RobolectricTestRunner::class)
class ShareInpaintTest {

    private fun inpaintFlow(id: String) = Workflow(
        Graph(
            listOf(
                Node("photo", "core.image", mapOf("uri" to "content://x")),
                Node("words", "core.prompt", mapOf("text" to "a red door")),
                Node(
                    id, SdSampler.SD15_INPAINT.name,
                    mapOf("model" to "absreality_inpaint", "width" to "512", "height" to "512"),
                    mapOf("image" to Source("photo"), "prompt" to Source("words")),
                ),
                Node("out", "core.output", emptyMap(), mapOf("image" to Source(id))),
            )
        ),
        mapOf(id to Pt(0f, 0f)),
    )

    private fun roundTrip(w: Workflow): Workflow =
        workflowFromJson(w.toJson(NODE_TYPES)).workflow

    /**
     * ⭐⭐⭐ The claim that matters: the TYPE survives a share, so the flow that
     * comes back inpaints.
     */
    @Test
    fun theInpaintTypeSurvivesAShare() {
        val back = roundTrip(inpaintFlow("sd15_inpaint"))
        val sampler = back.graph.nodes.first { it.type in IMAGE_SAMPLER_TYPES }
        assertEquals("sd15.inpaint", sampler.type)
        assertTrue("it must still read as an inpaint node", sampler.type in INPAINT_TYPES)
        assertEquals("Inpaint", SdSampler.SD15_INPAINT.jobFor(sampler))
    }

    /**
     * ⭐⭐⭐ **The reported case**: the node was dragged out as a generate node
     * and became an inpaint one when its checkpoint was switched, so its id says
     * `sd15_generate` forever. The JSON shows that id — and the flow is still an
     * inpaint flow.
     *
     * ⚠⚠ This is the test that separates "a confusing id" from "a broken file".
     * If this ever fails, the share really is losing the capability.
     */
    @Test
    fun aStaleGenerateIdDoesNotMakeItAGenerateFlow() {
        val back = roundTrip(inpaintFlow("sd15_generate"))
        val sampler = back.graph.nodes.first { it.id == "sd15_generate" }
        assertEquals("the id is kept, because wires reference it", "sd15_generate", sampler.id)
        assertEquals("but the TYPE is what it does", "sd15.inpaint", sampler.type)
        // ⚠ …and the canvas shows the job, not the id — `NodeNameTest`.
        assertEquals("Inpaint", nodeNameOf(sampler).primary)
    }

    /** ⚠ The exported text itself names the inpaint type, whatever the id says. */
    @Test
    fun theJsonNamesTheInpaintType() {
        val json = inpaintFlow("sd15_generate").toJson(NODE_TYPES)
        assertTrue("the file must carry the inpaint type", json.contains("sd15.inpaint"))
    }

    /**
     * ⚠⚠ A plain generate flow must NOT come back as an inpaint one — the
     * control. Without it, a migration that typed everything `sd15.inpaint`
     * would pass every assertion above.
     */
    @Test
    fun aGenerateFlowStaysGenerate() {
        val w = Workflow(
            Graph(
                listOf(
                    Node("words", "core.prompt", mapOf("text" to "a red door")),
                    Node(
                        "sd15_generate", SdSampler.SD15.name,
                        mapOf("model" to "absolutereality", "width" to "512", "height" to "512"),
                        mapOf("prompt" to Source("words")),
                    ),
                    Node("out", "core.output", emptyMap(), mapOf("image" to Source("sd15_generate"))),
                )
            ),
            mapOf("sd15_generate" to Pt(0f, 0f)),
        )
        val sampler = roundTrip(w).graph.nodes.first { it.type in IMAGE_SAMPLER_TYPES }
        assertEquals("sd15.sample", sampler.type)
        assertTrue("it must not become an inpaint", sampler.type !in INPAINT_TYPES)
        assertEquals("Text to image", nodeNameOf(sampler).primary)
    }
}
