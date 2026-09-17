package com.abrah.nightmare

import com.abrah.nightmare.canvas.Workflow
import com.abrah.nightmare.canvas.Pt
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
 *
 * ⚠⚠ **The fixtures are built HERE, not read from a recipe** — since
 * 2026-09-15 no shipped SD recipe has a derived chain at all: the fused sampler
 * fits what it is given (docs/ARCHITECTURE.md §5.7). The machinery still runs
 * for the video path, and a user can still build this shape by hand, so the
 * regression stays guarded by a graph of the shape rather than by whichever
 * recipe happened to have it.
 */
class DeriveSizesTest {

    /** `photo → frame → …` at [size], in the shape the inpaint recipe had. */
    private fun chain(size: Int, twoLinks: Boolean): Workflow {
        val ctx = mapOf("model" to "m", "width" to size.toString(), "height" to size.toString())
        val nodes = mutableListOf(
            Node("photo", "core.image", mapOf("uri" to "/a.png")),
            Node("frame", "image.crop", mapOf("x" to "0.0", "y" to "0.0", "w" to "1.0", "h" to "1.0"),
                sources("image" to "photo")),
        )
        if (twoLinks) {
            nodes += Node("mask", "image.mask", mapOf("ops" to "", "grow" to "0.0", "feather" to "0.02"),
                sources("image" to "frame"))
            nodes += Node("cut", "image.mask_crop", mapOf("only_masked" to "true"),
                sources("image" to "frame", "mask" to "mask"))
            nodes += Node("enc", "sd.vae_encode", ctx + mapOf("seed" to "42"),
                sources("image" to "cut:image"))
        } else {
            nodes += Node("enc", "sd.vae_encode", ctx + mapOf("seed" to "42"),
                sources("image" to "frame"))
        }
        return Workflow(Graph(nodes), nodes.associate { it.id to Pt(0f, 0f) })
    }

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
    fun aTwoLinkChainSettlesAfterAModelSwitch() {
        // As the canvas actually is before the switch: derived, at 512.
        val at512 = deriveSizes(retargeted(chain(512, twoLinks = true), 512), NODE_TYPES)
        // ⚠ Since "Only masked" (2026-09-15) the render size stops at `cut`:
        // the frame and the mask keep their own pixels for the paste back.
        assertEquals("512", at512.byId["cut"]!!.params["out_w"])

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
        assertEquals(
            "the cut must agree with BOTH consumers, not give up",
            "1024", settled.byId["cut"]!!.params["out_w"],
        )
        assertEquals("1024", settled.byId["cut"]!!.params["out_h"])
        assertEquals("the frame must not be sized", null, settled.byId["frame"]!!.params["out_w"])
        assertEquals("the mask must not be sized", null, settled.byId["mask"]!!.params["out_w"])
    }

    /** ⚠ …and at 512, so this is about settling rather than about one number. */
    @Test
    fun aTwoLinkChainSettlesAt512Too() {
        val g = deriveSizes(retargeted(chain(512, twoLinks = true), 512), NODE_TYPES)
        assertEquals("512", g.byId["cut"]!!.params["out_w"])
        assertEquals("512", g.byId["cut"]!!.params["out_h"])
    }

    /**
     * ⭐⭐ The failure this replaced: **a size of 0 means "promise nothing"**,
     * and a crop that promises nothing emits at the source's own size. Nothing
     * downstream can then be right, and no sampler knob is involved — which is
     * why the report was "output is very bad" rather than "output is 512".
     */
    @Test
    fun nothingIsLeftPromisingNothing() {
        val at512 = deriveSizes(retargeted(chain(512, twoLinks = true), 512), NODE_TYPES)
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
        val once = deriveSizes(retargeted(chain(1024, twoLinks = true), 1024), NODE_TYPES)
        val twice = deriveSizes(once, NODE_TYPES)
        assertEquals(
            once.nodes.map { it.id to it.params },
            twice.nodes.map { it.id to it.params },
        )
    }

    /** ⚠ …and a settled graph says nothing, or the warning is noise. */
    @Test
    fun aSettledGraphReportsNothing() {
        val g = deriveSizes(retargeted(chain(1024, twoLinks = true), 1024), NODE_TYPES)
        assertEquals(emptyList<String>(), sizeMismatches(g, NODE_TYPES))
    }

    /**
     * ⭐⭐⭐ **Nothing asking for a size must not ERASE one.**
     *
     * ⚠⚠ `image.upscale` takes whatever it is given, so a crop feeding one
     * has no demand on it — and [deriveSizes] used to write 0 in that case, on
     * every canvas edit, so such a crop could never hold a size. Measured
     * 2026-09-13: a crop set to 1024x1024 emitted its 4096² input unchanged.
     *
     * ⚠ A CONFLICT still resolves to 0; that is deliberate and different.
     */
    @Test
    fun noDemandLeavesAnExplicitCropSizeAlone() {
        val g = Graph(
            listOf(
                Node("photo", "core.image"),
                Node(
                    "square", "image.crop",
                    params = mapOf("out_w" to "1024", "out_h" to "1024"),
                    inputs = sources("image" to "photo"),
                ),
                Node("up", "image.upscale", inputs = sources("image" to "square")),
            )
        )
        val out = deriveSizes(g, NODE_TYPES)
        assertEquals("1024", out.byId.getValue("square").params["out_w"])
        assertEquals("1024", out.byId.getValue("square").params["out_h"])
    }
}
