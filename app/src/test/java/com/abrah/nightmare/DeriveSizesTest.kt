package com.abrah.nightmare

import com.abrah.nightmare.canvas.img2imgWorkflow
import com.abrah.nightmare.canvas.inpaintWorkflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumer-derived sizes settling through a CHAIN.
 *
 * ⚠⚠ These exist because of a shipped regression. `crop -> vae_encode` is one
 * link and a single derivation pass handled it; the inpaint recipe added
 * `crop -> mask -> latent_blend`, where the middle node is itself
 * [NodeType.sizedByConsumer]. In one pass the crop reads the mask's STALE size,
 * disagrees with `vae_encode`'s fresh one, resolves to a conflict, and promises
 * nothing — so the crop emitted at the photo's own size and every render after
 * a model switch was the wrong size.
 *
 * Reported from the phone as "switching to SDXL always degrades the output",
 * and no sampler setting could have fixed it.
 */
class DeriveSizesTest {

    /** Point every backend node at [size], as a model switch does. */
    private fun retargeted(w: com.abrah.nightmare.canvas.Workflow, size: Int) =
        Graph(
            w.graph.nodes.map { n ->
                if (n.params.containsKey("width")) {
                    n.copy(
                        params = n.params + mapOf(
                            "width" to size.toString(),
                            "height" to size.toString(),
                        )
                    )
                } else {
                    n
                }
            }
        )

    /** ⭐ One link. This always worked, and must keep working. */
    @Test
    fun img2imgCropTakesItsSizeFromTheEncoder() {
        val g = deriveSizes(retargeted(img2imgWorkflow(), 1024), NODE_TYPES)
        assertEquals("1024", g.byId["frame"]!!.params["out_w"])
        assertEquals("1024", g.byId["frame"]!!.params["out_h"])
    }

    /**
     * ⭐⭐ TWO links, **on a graph that was already settled at another size** —
     * which is the case that regressed and the only one that reproduces it.
     *
     * ⚠⚠ A FRESH inpaint graph settles in a single pass, because `mask` has no
     * size yet and therefore demands nothing of `frame`; the crop sees only
     * `vae_encode`'s demand and takes it. That is why the first version of this
     * test passed against the broken code and proved nothing.
     *
     * The failure needs a STALE size in the middle of the chain: settle at 512,
     * switch the model to 1024, and now `frame` sees `mask` still asking 512
     * while `vae_encode` asks 1024 — a conflict, which resolves to "promise
     * nothing", which makes the crop emit at the photo's own size. That is
     * exactly "switch to SDXL and the output is degraded".
     */
    @Test
    fun inpaintSettlesAfterAModelSwitch() {
        // As the canvas actually is before the switch: derived, at 512.
        val at512 = deriveSizes(retargeted(inpaintWorkflow(), 512), NODE_TYPES)
        assertEquals("512", at512.byId["mask"]!!.params["out_w"])

        // The switch: every backend node is retargeted, the derived ones are not.
        val switched = Graph(
            at512.nodes.map { n ->
                if (n.params.containsKey("width")) {
                    n.copy(params = n.params + mapOf("width" to "1024", "height" to "1024"))
                } else {
                    n
                }
            }
        )
        val settled = deriveSizes(switched, NODE_TYPES)
        assertEquals("the mask must follow the blend", "1024", settled.byId["mask"]!!.params["out_w"])
        assertEquals(
            "the crop must agree with BOTH consumers, not give up",
            "1024", settled.byId["frame"]!!.params["out_w"],
        )
        assertEquals("1024", settled.byId["frame"]!!.params["out_h"])
    }

    /** ⚠ …and at 512, so this is about settling rather than about one number. */
    @Test
    fun inpaintSettlesAt512Too() {
        val g = deriveSizes(retargeted(inpaintWorkflow(), 512), NODE_TYPES)
        assertEquals("512", g.byId["mask"]!!.params["out_w"])
        assertEquals("512", g.byId["frame"]!!.params["out_w"])
    }

    /**
     * ⭐⭐ The failure this replaced: **a size of 0 means "promise nothing"**,
     * and a crop that promises nothing emits at the source's own size. Nothing
     * downstream can then be right, and no sampler knob is involved — which is
     * why the report was "output is very bad" rather than "output is 512".
     */
    @Test
    fun nothingIsLeftPromisingNothing() {
        val at512 = deriveSizes(retargeted(inpaintWorkflow(), 512), NODE_TYPES)
        val switched = Graph(
            at512.nodes.map { n ->
                if (n.params.containsKey("width")) {
                    n.copy(params = n.params + mapOf("width" to "1024", "height" to "1024"))
                } else {
                    n
                }
            }
        )
        val g = deriveSizes(switched, NODE_TYPES)
        for (n in g.nodes) {
            if (NODE_TYPES[n.type]?.sizedByConsumer != true) continue
            assertEquals(
                "${n.id} promised nothing, so it will emit at its source's size",
                false, n.params["out_w"] == "0",
            )
        }
    }

    /** ⚠ Settling is idempotent: a second call must change nothing. */
    @Test
    fun derivingTwiceIsTheSameAsOnce() {
        val once = deriveSizes(retargeted(inpaintWorkflow(), 1024), NODE_TYPES)
        val twice = deriveSizes(once, NODE_TYPES)
        assertEquals(
            once.nodes.map { it.id to it.params },
            twice.nodes.map { it.id to it.params },
        )
    }

    /**
     * ⭐⭐ A mismatch is REPORTED, not silently rendered.
     *
     * ⚠⚠ This is the guard the whole session argued for. A crop that promises
     * nothing still renders — the sampler runs, the decoder returns a picture —
     * and what the user sees is a smear they blame on the model. Three wrong
     * diagnoses came out of that before anyone checked the sizes.
     */
    @Test
    fun anUnsettledGraphSaysWhichNodeIsWrong() {
        // A graph deliberately left stale: the model wants 1024, the crop says 512.
        val stale = Graph(
            retargeted(img2imgWorkflow(), 1024).nodes.map { n ->
                if (n.id == "frame") {
                    n.copy(params = n.params + mapOf("out_w" to "512", "out_h" to "512"))
                } else {
                    n
                }
            }
        )
        val why = sizeMismatches(stale, NODE_TYPES)
        assertEquals(1, why.size)
        assertTrue(why[0], why[0].contains("frame"))
        assertTrue(why[0], why[0].contains("1024"))
    }

    /** ⚠ …and a settled graph says nothing, or the warning is noise. */
    @Test
    fun aSettledGraphReportsNothing() {
        val g = deriveSizes(retargeted(inpaintWorkflow(), 1024), NODE_TYPES)
        assertEquals(emptyList<String>(), sizeMismatches(g, NODE_TYPES))
    }
}
