package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * When a node's picture can be made without a Run, and when it has gone stale.
 *
 * ⚠⚠ Both halves fail SILENTLY, which is why they are pure functions with a test
 * rather than three lines inside a coroutine. A signature that misses a change
 * shows the previous photo on a node whose params say otherwise — the exact bug
 * reported from the phone on 2026-09-09 — and one that changes for no reason
 * re-decodes a 12 MP JPEG on every frame of a crop drag. Neither throws.
 */
class PreviewSigTest {

    private val types = NODE_TYPES

    /** photo -> frame -> encode: two free nodes and one that needs the backend. */
    private val graph = Graph(
        listOf(
            Node("photo", "image.load", mapOf("uri" to "/a.png")),
            Node("frame", "image.crop", mapOf("w" to "0.5"), sources("image" to "photo")),
            Node(
                "encode", "sd.vae_encode",
                mapOf("model" to V1_MODEL, "width" to "512", "height" to "512", "seed" to "1"),
                sources("image" to "frame"),
            ),
        )
    )

    /** ⭐ The whole point: a Tier 0 chain costs no backend, so it can be shown now. */
    @Test
    fun anAppSideChainIsFree() {
        val need = freeAncestry(graph, "frame", types)
        assertEquals(listOf("photo", "frame"), need?.map { it.id })
    }

    /**
     * ⚠⚠ …and a chain that needs the NPU is NOT, however cheap its own node
     * looks. Starting a backend and a render because a sheet opened would be a
     * far worse surprise than a node with no picture yet.
     */
    @Test
    fun aChainThatNeedsTheBackendIsRefused() {
        assertNull(freeAncestry(graph, "encode", types))
    }

    /**
     * ⚠ An uninstalled plugin's node is not free either. Its `contextKey` cannot
     * be asked, and guessing null would run a graph the executor refuses by name.
     */
    @Test
    fun anUnknownTypeIsNotFree() {
        val g = Graph(listOf(Node("x", "somepack:blur", inputs = sources("image" to "photo"))))
        assertNull(freeAncestry(g, "x", types))
    }

    @Test
    fun aNodeThatIsNotInTheGraphHasNoAncestry() {
        assertNull(freeAncestry(graph, "nope", types))
    }

    /** ⭐ A new photo on the SOURCE invalidates the crop node below it. */
    @Test
    fun changingAnUpstreamParamMovesTheSignature() {
        val before = previewSig(freeAncestry(graph, "frame", types)!!)
        val after = previewSig(
            freeAncestry(graph.withParam("photo", "uri", "/b.png"), "frame", types)!!
        )
        assertNotEquals(before, after)
    }

    /** ⭐⭐ …and so does dragging the frame, which is what the cropper writes. */
    @Test
    fun changingTheCropMovesTheSignature() {
        val before = previewSig(freeAncestry(graph, "frame", types)!!)
        val after = previewSig(
            freeAncestry(
                graph.withParams("frame", mapOf("x" to "0.1", "w" to "0.4")), "frame", types
            )!!
        )
        assertNotEquals(before, after)
    }

    /**
     * ⚠⚠ Rewiring counts too. Pointing the crop node at a different picture
     * changes no param at all, so a signature over params alone would leave the
     * old photo on the node forever.
     */
    @Test
    fun rewiringMovesTheSignature() {
        val other = graph.copy(
            nodes = graph.nodes + Node("photo2", "image.load", mapOf("uri" to "/c.png"))
        )
        val before = previewSig(freeAncestry(other, "frame", types)!!)
        val after = previewSig(
            freeAncestry(other.connected("frame", "image", Source("photo2")), "frame", types)!!
        )
        assertNotEquals(before, after)
    }

    // --- which nodes get one at all ----------------------------------------

    /**
     * ⭐⭐ The whole graph is swept, not just what is open — the first build
     * targeted "the node being edited plus whatever already has a picture",
     * which resolves NOTHING on a cold start and left a restored graph as a row
     * of empty boxes.
     */
    @Test
    fun everyNodeThatMakesAPictureIsATarget() {
        assertEquals(listOf("photo", "frame"), previewTargets(graph, types))
    }

    /**
     * ⚠⚠ `encode_text` has NO context key — encoding a prompt does not care
     * about the resolution — and it calls the backend anyway. Without the
     * IMAGE-output filter, opening a canvas would start a server.
     */
    @Test
    fun aTextEncoderIsNotAPreviewTarget() {
        val g = Graph(listOf(Node("t", "sd.clip_encode", mapOf("prompt" to "a cat"))))
        assertEquals(emptyList<String>(), previewTargets(g, types))
        // ⚠⚠ **This assertion FLIPPED, and the flip is the fix.** It used to be
        // `assertNotNull`, recording the trap that `encode_text` was excluded
        // only by the IMAGE filter above and NOT by the free test — it has no
        // context key and calls the backend anyway. Its own comment said "if
        // this ever returns null, say so here".
        //
        // It returns null now: [freeAncestry] tests [NodeType.appSide], which
        // `sd.clip_encode` declares false. The change was forced by
        // `image.upscale`, which has no context key, emits an IMAGE, and calls
        // /upscale — so the IMAGE filter would no longer have caught it and
        // opening a canvas would have started the backend.
        assertNull("a node that reaches the backend is never free", freeAncestry(g, "t", types))
    }

    /**
     * ⭐⭐ The node that forced [NodeType.appSide] to exist.
     *
     * ⚠⚠ `image.upscale` has NO context key — an upscaler is loaded per request
     * from a path, so it binds nothing at launch — and it emits an IMAGE, so
     * `previewTargets` wants it. If [freeAncestry] still tested `contextKey ==
     * null`, opening a canvas holding one would launch the backend and run a 4x
     * upscale for a picture nobody asked for.
     */
    @Test
    fun anUpscaleIsNeverRunForAPreview() {
        val g = Graph(
            listOf(
                Node("photo", "image.load", mapOf("uri" to "/a.png")),
                Node("up", "image.upscale", mapOf("upscaler" to "upscaler_anime"),
                    sources("image" to "photo")),
            )
        )
        // It IS a target — it makes a picture — which is exactly why the free
        // test has to be the thing that refuses it.
        assertEquals(listOf("photo", "up"), previewTargets(g, types))
        assertNull("an upscale reaches the backend", freeAncestry(g, "up", types))
        // ⚠ …and the photo feeding it is still free on its own, or choosing a
        // picture would stop showing one the moment an upscaler was wired after it.
        assertNotNull(freeAncestry(g, "photo", types))
    }

    /**
     * ⚠⚠ `output` SAVES A FILE when `save` is true. It declares no outputs, so
     * the same filter excludes it — otherwise touching the canvas would write a
     * PNG to the gallery.
     */
    @Test
    fun theOutputNodeIsNotAPreviewTarget() {
        val g = Graph(
            listOf(
                Node("photo", "image.load", mapOf("uri" to "/a.png")),
                Node("out", "image.output", mapOf("save" to "true"), sources("image" to "photo")),
            )
        )
        assertEquals(listOf("photo"), previewTargets(g, types))
    }

    /**
     * ⚠ A param map rebuilt in a different order is the SAME node. Otherwise a
     * preview goes stale for having been round-tripped through a file.
     */
    @Test
    fun paramOrderIsNotPartOfTheSignature() {
        val a = Node("n", "image.crop", linkedMapOf("x" to "0.1", "w" to "0.4"))
        val b = Node("n", "image.crop", linkedMapOf("w" to "0.4", "x" to "0.1"))
        assertEquals(previewSig(listOf(a)), previewSig(listOf(b)))
    }
}
