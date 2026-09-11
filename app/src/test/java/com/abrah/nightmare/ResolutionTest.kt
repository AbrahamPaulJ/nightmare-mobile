package com.abrah.nightmare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolution — the third of the [ContextKey] that binds at backend launch, and
 * the aspect ratio that deliberately does not.
 *
 * ⚠⚠ **The load-bearing test here is [aspectTargetMirrorsTheBackendsArithmetic].**
 * `ModelCatalog.aspectTarget` is a SECOND COPY of a sum that lives in
 * `RequestParser.hpp`: the backend uses it to place the rectangle it paints,
 * and the app uses it to cut that rectangle back out ([VaeDecodeNode]). If the
 * two drift, the result is a plausible picture cropped off centre or at the
 * wrong size, with nothing anywhere reporting a problem. The expectations below
 * are the C++ evaluated by hand, not this function's own output.
 */
class ResolutionTest {

    // ---- patch names -----------------------------------------------------

    /**
     * ⚠ Square and rectangular patches are named differently and are NOT
     * interchangeable — `768.patch` is a square graph, `512x768.patch` a
     * portrait one. Handing a portrait build the square patch loads a graph of
     * the wrong shape and the backend does not check.
     */
    @Test
    fun patchNamesMatchWhatTheConversionPipelineEmits() {
        assertEquals("768.patch", Res(768, 768).patchName)
        assertEquals("1024.patch", Res(1024, 1024).patchName)
        assertEquals("512x768.patch", Res(512, 768).patchName)
        assertEquals("768x512.patch", Res(768, 512).patchName)
    }

    /** ⚠ 512² is the base `unet.bin` itself, so it needs no patch at all. */
    @Test
    fun theBaseResolutionNeedsNoPatch() {
        assertNull(Res(512, 512).patchName)
    }

    /** Round trip: every name this emits must parse back to the same size. */
    @Test
    fun patchNamesRoundTrip() {
        for (r in listOf(Res(768, 768), Res(1024, 1024), Res(512, 768), Res(1024, 768))) {
            assertEquals(r, Res.fromPatch(r.patchName!!))
        }
    }

    /**
     * ⚠ A directory holds more than patches. Anything that is not one must
     * parse to null rather than to a plausible size — `unet.bin` becoming a
     * resolution would put a size nobody can serve in the picker.
     */
    @Test
    fun nonPatchFilenamesParseToNothing() {
        for (n in listOf("unet.bin", "clip_v2.mnn", "config.json", "patch", ".patch", "x.patch", "512x.patch")) {
            assertNull("\"$n\" should not parse as a resolution", Res.fromPatch(n))
        }
    }

    // ---- the aspect mirror ----------------------------------------------

    /**
     * ⭐⭐ Every expectation here is `RequestParser.hpp` worked by hand:
     *
     *     if (rw >= rh) { tw = 1024; th = (int)((1024.0*rh)/rw); th = (th/8)*8; }
     *     else          { th = 1024; tw = (int)((1024.0*rw)/rh); tw = (tw/8)*8; }
     *
     * ⚠ The `/8*8` floor is not decoration: 16:9 gives 576 exactly, but 3:2
     * gives 682.67 which truncates to 682 and then floors to **680**, not 683
     * and not 688. A "close enough" ratio here is a crop of the wrong size.
     */
    @Test
    fun aspectTargetMirrorsTheBackendsArithmetic() {
        val canvas = Res(1024, 1024)
        assertEquals(Res(1024, 576), ModelCatalog.aspectTarget("16:9", canvas))
        assertEquals(Res(576, 1024), ModelCatalog.aspectTarget("9:16", canvas))
        assertEquals(Res(1024, 768), ModelCatalog.aspectTarget("4:3", canvas))
        assertEquals(Res(768, 1024), ModelCatalog.aspectTarget("3:4", canvas))
        // 1024*2/3 = 682.666 -> 682 -> 680
        assertEquals(Res(1024, 680), ModelCatalog.aspectTarget("3:2", canvas))
        assertEquals(Res(680, 1024), ModelCatalog.aspectTarget("2:3", canvas))
    }

    /**
     * ⚠⚠ The backend guards its whole aspect block on `!(rw == rh)`, so a
     * square ratio means "no crop" there. Returning a size here instead would
     * make the app cut a rectangle the backend never painted.
     */
    @Test
    fun asquareRatioMeansNoCrop() {
        assertNull(ModelCatalog.aspectTarget("1:1", Res(1024, 1024)))
        assertNull(ModelCatalog.aspectTarget("4:4", Res(1024, 1024)))
    }

    /**
     * ⚠ The backend catches its `std::stoi` and falls through to plain 1:1, so
     * malformed input is "no crop" and never an error. The app must agree, or
     * a typo would crop a picture the backend rendered square.
     */
    @Test
    fun malformedRatiosMeanNoCrop() {
        for (bad in listOf("", "16", "16:", ":9", "16:0", "0:9", "-1:2", "wide", "16:9:2")) {
            assertNull("\"$bad\" should mean no crop", ModelCatalog.aspectTarget(bad, Res(1024, 1024)))
        }
    }

    /** ⚠ Every shipped chip must resolve, or the picker offers a dead option. */
    @Test
    fun everyShippedAspectResolves() {
        val canvas = ModelCatalog.SDXL_NPU_RES
        for (a in ModelCatalog.ASPECTS) {
            if (a == ModelCatalog.DEFAULT_ASPECT) continue
            val t = ModelCatalog.aspectTarget(a, canvas)
            assertNotNull("$a did not resolve", t)
            // ⚠ Never bigger than the canvas: [VaeDecodeNode] refuses that
            // rather than clamping, so a chip that produced one would be a
            // guaranteed failed render.
            assertTrue("$a is larger than the canvas", t!!.width <= canvas.width && t.height <= canvas.height)
            assertEquals("$a is not 8-aligned", 0, t.width % 8)
            assertEquals("$a is not 8-aligned", 0, t.height % 8)
        }
    }

    /** ⚠ The long edge is pinned to the canvas; only the short one moves. */
    @Test
    fun theLongEdgeAlwaysFillsTheCanvas() {
        val canvas = Res(1024, 1024)
        for (a in ModelCatalog.ASPECTS) {
            val t = ModelCatalog.aspectTarget(a, canvas) ?: continue
            assertEquals("$a leaves the long edge short", 1024, maxOf(t.width, t.height))
        }
    }

    /**
     * ⚠⚠ **A stale `aspect` on an SD 1.5 node must be IGNORED, not obeyed.**
     *
     * Reported from the phone, 2026-09-11: a decode came back **432x768** —
     * 768 with a 9:16 left over from an SDXL session applied to it. The backend
     * only reads `aspect_ratio` for `sdxl`/`anima`, so it had rendered a full
     * frame while the app cropped it anyway; the picture was a crop of
     * something nothing had padded.
     *
     * ⚠ The param is deliberately still THERE — `applyDefaults` keeps undeclared
     * params, which is what restores the user's choice if they switch back. The
     * fix is at the point of use.
     */
    @Test
    fun aStaleAspectIsIgnoredOnAFixedSizeFamily() {
        val sd15 = Node(
            "s", "sd.sample",
            mapOf("model" to V1_MODEL, "width" to "768", "height" to "768", "aspect" to "9:16"),
        )
        assertNull("SD 1.5 must ignore a leftover aspect", nodeAspect(sd15))

        // ⭐ …and a fixed-canvas family must still honour it, or the guard has
        // simply broken the feature instead of scoping it.
        val xl = ModelCatalog.all.first { it.family == Family.SDXL }
        val sdxl = Node(
            "s", "sd.sample",
            mapOf("model" to xl.id, "width" to "1024", "height" to "1024", "aspect" to "9:16"),
        )
        assertEquals("9:16", nodeAspect(sdxl))

        // ⚠ An unknown model names no family, so it acts on nothing.
        assertNull(nodeAspect(Node("s", "sd.sample", mapOf("model" to "gone", "aspect" to "9:16"))))
    }

    // ---- the context key -------------------------------------------------

    /**
     * ⭐ Resolution is in the key, so two sizes are two processes. This is the
     * fact that makes a size a launch-bound field rather than a widget.
     */
    @Test
    fun twoResolutionsAreTwoContextKeys() {
        val a = ContextKey("sd15npu", V1_MODEL, 512, 512)
        val b = ContextKey("sd15npu", V1_MODEL, 768, 512)
        assertTrue(a != b)
    }

    /**
     * ⚠⚠ An aspect change must NOT move the context key — that is the whole
     * difference between it and a resolution, and getting it wrong would spend
     * a 2.3-5 s relaunch to change a crop.
     */
    @Test
    fun anAspectIsNotPartOfTheContextKey() {
        val square = Node(
            "s", "sd.sample",
            mapOf("model" to V1_MODEL, "width" to "1024", "height" to "1024", "aspect" to "1:1"),
        )
        val wide = square.copy(params = square.params + ("aspect" to "16:9"))
        assertEquals(backendContextKey(square), backendContextKey(wide))
    }

    // ---- the knob itself -------------------------------------------------

    /** Every node that names a checkpoint, i.e. everything that forces a launch. */
    private val backendNodes
        get() = NODE_TYPES.values.filter { t -> t.widgets.any { it.name == "model" && it.contextKey } }

    /**
     * ⭐⭐ **The size is editable and the model is not**, on every backend node.
     *
     * ⚠⚠ This pair is the whole reason [Widget.contextKey] exists as a field of
     * its own. Both knobs are bound at backend launch, so a single flag once
     * meant both "rewrite this graph-wide" and "the user may not touch it" —
     * and unlocking the size would have quietly dropped it out of
     * [contextKeyRetarget], leaving a graph with two context keys and no way
     * back by hand. The two properties are asserted together here so they
     * cannot drift apart again.
     */
    @Test
    fun sizeIsEditableAndModelIsNotOnEveryBackendNode() {
        assertTrue("no backend nodes found", backendNodes.isNotEmpty())
        for (t in backendNodes) {
            val w = t.widgets.associateBy { it.name }
            for (axis in listOf("width", "height")) {
                val k = w[axis] ?: error("${t.name} has no \"$axis\"")
                assertTrue("${t.name}.$axis must stay a context key", k.contextKey)
                assertNull("${t.name}.$axis must be editable now", k.locked)
            }
            assertEquals(
                "${t.name}.model must stay locked",
                CONTEXT_KEY_LOCK, w["model"]?.locked,
            )
        }
    }

    /**
     * ⚠ The retarget is driven by the widget declaration, so a node carrying a
     * param merely NAMED `width` must not be rewritten. That property was free
     * while the filter was `locked == CONTEXT_KEY_LOCK`; it has to survive the
     * move to [Widget.contextKey].
     */
    @Test
    fun aPlainParamCalledWidthIsNotRetargeted() {
        val g = Graph(
            listOf(
                Node("text", "sd.clip_encode", mapOf("prompt" to "x", "negative" to "")),
                // ⚠ `image.crop` really does carry sizes — `out_w`/`out_h` — and
                // they are consumer-derived, never context-key. It is the exact
                // node a name-matching retarget would corrupt.
                Node("frame", "image.crop", mapOf("out_w" to "512", "out_h" to "512")),
            )
        )
        val spec = ModelCatalog.byId(V1_MODEL)!!
        assertTrue(contextKeyRetarget(g, NODE_TYPES, spec, Res(768, 512)).isEmpty())
        assertTrue(contextKeyResolutions(g, NODE_TYPES).isEmpty())
    }

    /**
     * ⚠⚠ `image.crop` and `image.mask` stay `sizedByConsumer` — the cropper
     * FOLLOWS the render size, it does not drive it. `Framing.kt` resolves
     * demand backwards to a fixed point, and inverting that direction is what
     * the "cropper drives" option would have cost.
     */
    @Test
    fun theCropperFollowsRatherThanDrives() {
        assertTrue(NODE_TYPES.getValue("image.crop").sizedByConsumer)
        assertTrue(NODE_TYPES.getValue("image.mask").sizedByConsumer)
        // ⚠ …and it carries no context key, so it never forces a launch.
        assertNull(NODE_TYPES.getValue("image.crop").contextKey(Node("c", "image.crop")))
    }

    // ---- retargeting -----------------------------------------------------

    private fun graph() = Graph(
        listOf(
            Node("text", "sd.clip_encode", mapOf("prompt" to "a cat", "negative" to "")),
            Node(
                "sample", "sd.sample",
                mapOf("model" to V1_MODEL, "width" to "512", "height" to "512"),
                sources("cond" to "text"),
            ),
            Node(
                "decode", "sd.vae_decode",
                mapOf("model" to V1_MODEL, "width" to "512", "height" to "512"),
                sources("latent" to "sample"),
            ),
        )
    )

    /**
     * ⭐⭐ Choosing a resolution rewrites EVERY backend node, which is what keeps
     * §5.2's one-key-per-graph pin true while the size is editable. A retarget
     * that missed one would leave a graph the executor refuses, with three
     * locked knobs and no way back by hand.
     */
    @Test
    fun choosingAResolutionRewritesEveryBackendNode() {
        val spec = ModelCatalog.byId(V1_MODEL)!!
        val changes = contextKeyRetarget(graph(), NODE_TYPES, spec, Res(768, 1024))
        assertEquals(setOf("sample", "decode"), changes.keys)
        for ((_, p) in changes) {
            assertEquals("768", p["width"])
            assertEquals("1024", p["height"])
            // ⚠ The model is unchanged, so it must not appear as a change --
            // callers distinguish "nothing to do" from "done" by emptiness.
            assertNull(p["model"])
        }
    }

    /**
     * ⚠⚠ **Only what actually MOVED is reported**, per axis. Going 512x512 ->
     * 768x512 changes the width alone, and the height must be absent rather
     * than restated: a caller folds these onto the node, and an entry that
     * reports an unchanged value makes "nothing to do" indistinguishable from
     * "done" — which is the one thing [contextKeyRetarget]'s contract promises.
     */
    @Test
    fun onlyTheAxisThatMovedIsReported() {
        val spec = ModelCatalog.byId(V1_MODEL)!!
        val changes = contextKeyRetarget(graph(), NODE_TYPES, spec, Res(768, 512))
        assertEquals(setOf("sample", "decode"), changes.keys)
        for ((_, p) in changes) {
            assertEquals("768", p["width"])
            assertNull("height did not move and must not be reported", p["height"])
        }
        // ⚠ And a size that is already right is no edit at all.
        assertTrue(contextKeyRetarget(graph(), NODE_TYPES, spec, Res(512, 512)).isEmpty())
    }

    /** ⚠ The whole graph at one size is one key, which is what may run. */
    @Test
    fun aRetargetedGraphNamesExactlyOneKey() {
        val spec = ModelCatalog.byId(V1_MODEL)!!
        val changes = contextKeyRetarget(graph(), NODE_TYPES, spec, Res(768, 512))
        val moved = Graph(
            graph().nodes.map { n -> changes[n.id]?.let { n.copy(params = n.params + it) } ?: n }
        )
        val keys = moved.nodes.mapNotNull { NODE_TYPES[it.type]?.contextKey(it) }.toSet()
        assertEquals(1, keys.size)
        assertEquals(768, keys.first().width)
        assertEquals(512, keys.first().height)
    }

    /**
     * ⭐ Reading the sizes back off a graph, for the adoption a workflow gets
     * when it is opened. A graph saved at 768x512 must select 768x512, or it
     * renders against a backend launched for the picker's size.
     */
    @Test
    fun aGraphsOwnResolutionCanBeReadBack() {
        val spec = ModelCatalog.byId(V1_MODEL)!!
        val changes = contextKeyRetarget(graph(), NODE_TYPES, spec, Res(768, 512))
        val moved = Graph(
            graph().nodes.map { n -> changes[n.id]?.let { n.copy(params = n.params + it) } ?: n }
        )
        assertEquals(listOf(Res(768, 512)), contextKeyResolutions(moved, NODE_TYPES))
    }

    /**
     * ⚠ A node with no context-key knobs contributes nothing. `sd.clip_encode`
     * carries no size, and a graph of only those needs no backend at all.
     */
    @Test
    fun nodesWithoutAContextKeyContributeNoResolution() {
        val g = Graph(listOf(Node("text", "sd.clip_encode", mapOf("prompt" to "x", "negative" to ""))))
        assertTrue(contextKeyResolutions(g, NODE_TYPES).isEmpty())
    }

    /**
     * ⭐ Aspect is written graph-wide by widget, so the sampler and the decoder
     * cannot disagree — and they must not, because one places the rectangle and
     * the other cuts it out.
     */
    @Test
    fun anAspectIsWrittenToEveryNodeThatDeclaresIt() {
        val declaring = NODE_TYPES.filterValues { t -> t.widgets.any { it.name == "aspect" } }.keys
        val changes = aspectRetarget(graph(), NODE_TYPES, "16:9")
        // ⚠ Guarded: the chip only exists on a fixed-canvas family, and the
        // default selection is SD 1.5. The assertion is that the set of nodes
        // rewritten is exactly the set that declares the knob -- whichever that
        // turns out to be -- never a hardcoded pair.
        assertEquals(
            graph().nodes.filter { it.type in declaring }.map { it.id }.toSet(),
            changes.keys,
        )
    }

    /** ⚠ No change means no entry, so a caller can tell "already right" from "done". */
    @Test
    fun anUnchangedAspectProducesNoEdit() {
        val g = Graph(graph().nodes.map { it.copy(params = it.params + ("aspect" to "16:9")) })
        assertTrue(aspectRetarget(g, NODE_TYPES, "16:9").isEmpty())
    }
}
