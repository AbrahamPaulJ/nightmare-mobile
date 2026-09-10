package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NODE_TYPES
import com.abrah.nightmare.Node
import com.abrah.nightmare.sources
import com.abrah.nightmare.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a touch DOES.
 *
 * ⭐ These are the canvas bugs that cannot be seen in a screenshot and cannot be
 * felt without the device in your hand: a press that starts the wrong gesture, a
 * node that jumps when grabbed, a wire that closes a loop. All of it is pure
 * arithmetic, so all of it is testable here.
 */
class CanvasStateTest {

    private val types = NODE_TYPES

    private val graph = Graph(
        listOf(
            Node("s", "sd.sample", mapOf("model" to "m", "width" to "512", "height" to "512")),
            Node("d", "sd.vae_decode", mapOf("model" to "m")),
        )
    )

    private val state = CanvasState(
        Workflow(graph, mapOf("s" to Pt(0f, 0f), "d" to Pt(400f, 0f)))
    )

    private val boxes get() = layout(state.workflow, types)
    private fun box(id: String) = boxes.first { it.id == id }

    // --- what a press starts ------------------------------------------------

    /**
     * ⚠⚠ The whole decision. A port sits ON the node's edge, so its hit-box
     * overlaps the body: testing the body first would make every wire-drag a
     * node-drag, and the canvas would look unable to connect anything.
     */
    @Test
    fun aPressOnAPortStartsAWireNotANodeDrag() {
        val at = box("s").outputPort(0)
        val g = state.press(at, types).gesture
        assertTrue("expected a wire, got $g", g is Gesture.DraggingWire)
        assertEquals("s", (g as Gesture.DraggingWire).from.nodeId)
    }

    /**
     * ⚠⚠ A press starts a drag and selects NOTHING. Selecting on press
     * meant an ordinary tap put the canvas into multi-select -- the run bar
     * became a delete row -- while also opening the inspector. Reported from the
     * phone 2026-09-09.
     */
    @Test
    fun aPressOnTheBodyStartsANodeDragAndSelectsNothing() {
        val s = box("s")
        val next = state.press(Pt(s.topLeft.x + 90f, s.topLeft.y + 20f), types)
        assertTrue(next.gesture is Gesture.DraggingNode)
        assertTrue(next.selection.isEmpty())
        assertTrue(!next.multiSelect)
    }

    /** ⭐ …and a whole tap likewise: the inspector opens, the mode does not. */
    @Test
    fun aTapOpensTheInspectorWithoutSelecting() {
        val s = box("s")
        val at = Pt(s.topLeft.x + 90f, s.topLeft.y + 20f)
        val next = state.press(at, types).release(at, types)
        assertEquals("s", next.editing)
        assertTrue("a tap entered multi-select", next.selection.isEmpty())
        assertTrue(!next.multiSelect)
    }

    /** ⚠ Nor does grabbing the resize corner. */
    @Test
    fun aResizeDoesNotSelect() {
        val s = box("s")
        val next = state.press(Pt(s.right, s.bottom), types)
        assertTrue("expected a resize, got ${next.gesture}", next.gesture is Gesture.ResizingNode)
        assertTrue(next.selection.isEmpty())
    }

    @Test
    fun aPressOnEmptySpacePans() =
        assertEquals(Gesture.Panning(), state.press(Pt(-500f, -500f), types).gesture)


    /**
     * ⭐⭐ Deselecting the LAST node leaves multi-select entirely.
     *
     * ⚠⚠ Long-pressing one node and tapping it again used to leave the mode on
     * with nothing selected: a run bar reading "0 of n selected", taps that
     * silently toggled instead of opening the inspector, and the only way out a
     * tap on the background — which nothing on screen suggests. Reported from
     * the phone 2026-09-10.
     */
    @Test
    fun deselectingTheLastNodeLeavesMultiSelect() {
        val s = box("s")
        val at = Pt(s.topLeft.x + 90f, s.topLeft.y + 20f)
        val selected = state.copy(selection = setOf("s"), multiSelect = true)
        val after = selected.press(at, types).release(at, types)
        assertTrue("the node must be deselected", after.selection.isEmpty())
        assertTrue("and the mode must be off", !after.multiSelect)
        // ⚠ And it must NOT have opened the inspector on the way out: the tap
        // that leaves the mode is spent leaving it.
        assertEquals(null, after.editing)
    }

    /** ⚠ …but deselecting one of SEVERAL keeps the mode, which is the point of it. */
    @Test
    fun deselectingOneOfSeveralStaysInMultiSelect() {
        val s = box("s")
        val at = Pt(s.topLeft.x + 90f, s.topLeft.y + 20f)
        val selected = state.copy(selection = setOf("s", "d"), multiSelect = true)
        val after = selected.press(at, types).release(at, types)
        assertEquals(setOf("d"), after.selection)
        assertTrue(after.multiSelect)
    }

    /**
     * ⚠ …and a TAP on it deselects. A selection that survives a tap on the
     * background leaves the user unable to get rid of it.
     */
    @Test
    fun aTapOnEmptySpaceClearsTheSelection() {
        val at = Pt(-500f, -500f)
        val selected = state.copy(selection = setOf("s"), multiSelect = true)
        val after = selected.press(at, types).release(at, types)
        assertTrue(after.selection.isEmpty())
        assertTrue(!after.multiSelect)
    }

    /**
     * ⭐⭐ …but a PINCH that starts there does not, which is the whole point of
     * deferring it to the release. Zooming while multi-selecting used to cancel
     * the selection the instant the first finger touched down.
     */
    @Test
    fun pinchingDoesNotCancelAMultiSelection() {
        val at = Pt(-500f, -500f)
        val selected = state.copy(selection = setOf("s", "d"), multiSelect = true)
        val pinched = selected.press(at, types)
            .zoom(Pt(100f, 100f), 1.4f)
            .pan(Pt(10f, 4f))
            .release(at, types)
        assertEquals(setOf("s", "d"), pinched.selection)
        assertTrue(pinched.multiSelect)
    }

    /** ⚠ …and the lock must not undo that: a refused pinch is still not a tap. */
    @Test
    fun aPinchOnALockedCanvasIsStillNotATap() {
        val at = Pt(-500f, -500f)
        val locked = state.copy(
            selection = setOf("s"), multiSelect = true, zoomLocked = true, panLocked = true,
        )
        val after = locked.press(at, types)
            .zoom(Pt(100f, 100f), 1.4f)
            .pan(Pt(10f, 4f))
            .release(at, types)
        assertEquals(setOf("s"), after.selection)
    }

    /** ⚠ A DRAG of the background is a pan, not a cancel either. */
    @Test
    fun panningDoesNotCancelAMultiSelection() {
        val at = Pt(-500f, -500f)
        val selected = state.copy(selection = setOf("s"), multiSelect = true)
        val after = selected.press(at, types)
            .drag(Pt(-460f, -470f), Pt(40f, 30f), types)
            .release(Pt(-460f, -470f), types)
        assertEquals(setOf("s"), after.selection)
    }

    // --- dragging -----------------------------------------------------------

    /**
     * ⚠ The node must not jump. Grabbing it 90 units in and moving 10 must
     * leave the finger 90 units in, not re-centre the node on the touch.
     */
    @Test
    fun aDraggedNodeKeepsItsGrabOffset() {
        val grabAt = Pt(90f, 20f)
        val pressed = state.press(grabAt, types)
        val moved = pressed.drag(grabAt + Pt(10f, 5f), Pt(10f, 5f), types)
        assertEquals(Pt(10f, 5f), moved.workflow.positions["s"])
    }

    /** A pan moves the viewport in SCREEN units, so the graph tracks the finger. */
    @Test
    fun aPanMovesTheViewportByTheScreenDelta() {
        val panned = state.press(Pt(-500f, -500f), types).drag(Pt(0f, 0f), Pt(25f, -8f), types)
        assertEquals(Pt(25f, -8f), panned.viewport.offset)
    }

    /** ⚠ Dragging a node must not move the viewport, and vice versa. */
    @Test
    fun draggingANodeLeavesTheViewportAlone() {
        val moved = state.press(Pt(90f, 20f), types).drag(Pt(100f, 25f), Pt(10f, 5f), types)
        assertEquals(state.viewport, moved.viewport)
    }

    // --- tap vs drag, and the inspector -------------------------------------

    /** ⭐ A press that never moved is a tap, and a tap opens the inspector. */
    @Test
    fun aTapOnANodeOpensItsInspector() {
        val at = Pt(90f, 20f)
        val next = state.press(at, types).release(at, types)
        assertEquals("s", next.editing)
    }

    /**
     * ⚠⚠ …and a DRAG does not. Without this, every attempt to move a node would
     * also throw a sheet over the graph the user was rearranging.
     */
    @Test
    fun draggingANodeDoesNotOpenTheInspector() {
        val at = Pt(90f, 20f)
        val next = state.press(at, types)
            .drag(at + Pt(30f, 10f), Pt(30f, 10f), types)
            .release(at + Pt(30f, 10f), types)
        assertNull(next.editing)
    }

    @Test
    fun aTapOnEmptySpaceOpensNothing() =
        assertNull(state.press(Pt(-500f, -500f), types).release(Pt(-500f, -500f), types).editing)

    @Test
    fun closingTheInspectorClearsIt() {
        val open = state.press(Pt(90f, 20f), types).release(Pt(90f, 20f), types)
        assertNull(open.closeInspector().editing)
    }

    // --- editing widgets ----------------------------------------------------

    @Test
    fun settingAParamChangesOnlyThatParam() {
        val next = state.setParam("s", "seed", "77")
        assertEquals("77", next.workflow.graph.byId["s"]!!.params["seed"])
        assertEquals("512", next.workflow.graph.byId["s"]!!.params["width"])
    }

    /** ⚠ Never positions, never wiring — the canvas is where a stray edit would show. */
    @Test
    fun settingAParamLeavesLayoutAndWiringAlone() {
        val wired = state.copy(
            workflow = state.workflow.copy(graph = graph.connected("d", "latent", "s"))
        )
        val next = wired.setParam("s", "seed", "77")
        assertEquals(wired.workflow.positions, next.workflow.positions)
        // ⚠ Bare: this wire was made by `connected(.., "s")`, not by the canvas,
        // so it names no output port. That IS the sole-output spelling, and a
        // param edit must not quietly resolve it into something else.
        assertEquals(Source("s"), next.workflow.graph.byId["d"]!!.inputs["latent"])
    }

    /**
     * ⭐ An edit must change the node's CACHE KEY, or the user retypes a prompt
     * and presses Run and gets the previous picture back.
     */
    @Test
    fun anEditChangesTheCacheKey() {
        val type = types.getValue("sd.sample")
        val before = com.abrah.nightmare.cacheKey(
            "sample", type.version, type.effectiveParams(graph.byId.getValue("s")), emptyMap(),
        )
        val edited = state.setParam("s", "seed", "77").workflow.graph.byId.getValue("s")
        val after = com.abrah.nightmare.cacheKey(
            "sample", type.version, type.effectiveParams(edited), emptyMap(),
        )
        assertTrue("editing a widget did not change the key", before != after)
    }

    // --- adding and removing nodes ------------------------------------------

    @Test
    fun addingANodePlacesItAndOpensItsInspector() {
        val next = state.addNode(types.getValue("sd.vae_decode"), Pt(100f, 200f))
        val added = next.workflow.graph.nodes.last()
        assertEquals("sd.vae_decode", added.type)
        assertEquals(Pt(100f, 200f), next.workflow.positions[added.id])
        assertEquals(added.id, next.editing)
        // ⚠ …and does not put the canvas into multi-select on the way.
        assertTrue(next.selection.isEmpty())
    }

    /**
     * ⚠⚠ Ids must be unique: `topoSort` refuses duplicates outright, so a
     * palette that reused one would make the graph unrunnable the moment a
     * second node of the same type was added.
     */
    @Test
    fun aSecondNodeOfTheSameTypeGetsItsOwnId() {
        val once = state.addNode(types.getValue("sd.vae_decode"), Pt(0f, 0f))
        val twice = once.addNode(types.getValue("sd.vae_decode"), Pt(0f, 0f))
        val ids = twice.workflow.graph.nodes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    /**
     * ⚠ Every widget default is written in. A node that arrived half-populated
     * fails at Run with "missing param" -- an error about the app rather than
     * about the empty prompt the user can see.
     */
    @Test
    fun anAddedNodeCarriesItsDefaults() {
        val next = state.addNode(types.getValue("sd.sample"), Pt(0f, 0f))
        val added = next.workflow.graph.nodes.last()
        for (w in types.getValue("sd.sample").widgets) {
            assertNotNull("no value for ${w.name}", added.params[w.name])
        }
    }

    /**
     * ⚠⚠ The one that breaks a graph silently. A dangling input makes topoSort
     * refuse the WHOLE graph with "names unknown node" -- at a node the user did
     * not touch, and only when they press Run.
     */
    @Test
    fun removingANodeAlsoRemovesTheWiresIntoIt() {
        val wired = state.copy(
            workflow = state.workflow.copy(graph = graph.connected("d", "latent", "s"))
        )
        val next = wired.removeNode("s")
        assertNull(next.workflow.graph.byId["s"])
        assertTrue(next.workflow.graph.byId["d"]!!.inputs.isEmpty())
        assertNull(next.workflow.positions["s"])
    }

    /** ⚠ …and clears a sheet that would otherwise be open on nothing. */
    @Test
    fun removingTheInspectedNodeClosesTheInspector() {
        val open = state.press(Pt(90f, 20f), types).release(Pt(90f, 20f), types)
        assertEquals("s", open.editing)
        val next = open.removeNode("s")
        assertNull(next.editing)
    }

    /** Removing an unrelated node leaves the sheet where it was. */
    @Test
    fun removingAnotherNodeLeavesTheInspectorAlone() {
        val open = state.press(Pt(90f, 20f), types).release(Pt(90f, 20f), types)
        assertEquals("s", open.removeNode("d").editing)
    }

    @Test
    fun theGraphStillSortsAfterAnAddAndARemove() {
        val next = state.addNode(types.getValue("sd.vae_decode"), Pt(0f, 0f)).removeNode("s")
        assertTrue(
            "the graph no longer sorts: ${(com.abrah.nightmare.topoSort(next.workflow.graph) as? com.abrah.nightmare.Order.Broken)?.why}",
            com.abrah.nightmare.topoSort(next.workflow.graph) is com.abrah.nightmare.Order.Ok,
        )
    }

    // --- wiring -------------------------------------------------------------

    private fun wire(fromId: String, fromOut: Int, toId: String, toIn: Int): CanvasState {
        val start = box(fromId).outputPort(fromOut)
        val end = box(toId).inputPort(toIn)
        return state.press(start, types).drag(end, Pt(0f, 0f), types).release(end, types)
    }

    @Test
    fun aCompletedWireLandsInTheGraph() {
        val next = wire("s", 0, "d", 0)
        assertEquals(Source("s", "latent"), next.workflow.graph.byId["d"]!!.inputs["latent"])
        assertEquals(Gesture.Idle, next.gesture)
        assertNull(next.message)
    }

    /**
     * ⚠ Either direction. The user may drag from the input to the output, and
     * the graph only stores "this input reads that node" — so the input end
     * decides where the edge is written regardless of which end was grabbed.
     */
    @Test
    fun aWireDraggedBackwardsLandsTheSameWay() {
        val start = box("d").inputPort(0)
        val end = box("s").outputPort(0)
        val next = state.press(start, types).drag(end, Pt(0f, 0f), types).release(end, types)
        assertEquals(Source("s", "latent"), next.workflow.graph.byId["d"]!!.inputs["latent"])
    }

    /** ⭐ The refusal is computed while the finger is down, not on release. */
    @Test
    fun aWireOverAnIllegalTargetIsMarkedMidDrag() {
        val start = box("s").outputPort(0)
        val overSelf = box("s").outputPort(0)
        val g = state.press(start, types).drag(overSelf, Pt(0f, 0f), types).gesture
        assertTrue(g is Gesture.DraggingWire)
        assertNotNull("no refusal shown while dragging", (g as Gesture.DraggingWire).error)
    }

    @Test
    fun droppingOnNothingIsASilentNoOp() {
        val start = box("s").outputPort(0)
        val next = state.press(start, types).release(Pt(-900f, -900f), types)
        assertEquals(Gesture.Idle, next.gesture)
        assertNull("dropping on the background is not an error", next.message)
        assertEquals(graph, next.workflow.graph)
    }

    /** A refused drop says why, and changes nothing. */
    @Test
    fun aRefusedDropExplainsItself() {
        val start = box("s").outputPort(0)
        val end = box("s").outputPort(0)
        val next = state.press(start, types).release(end, types)
        assertNotNull(next.message)
        assertEquals(graph, next.workflow.graph)
    }

    // --- cycles -------------------------------------------------------------

    /**
     * ⚠⚠ A canvas lets people draw loops by accident, and the executor only
     * finds one at run time — after the user pressed Run and waited.
     *
     * ⚠ Needs a LATENT -> LATENT type. With only the built-ins, wiring a decode
     * back into a decode is refused on TYPE (IMAGE into LATENT) before the
     * cycle check is ever reached -- so a fixture built from them would have
     * passed while testing nothing. That is exactly the trap a green test hides.
     */
    @Test
    fun aWireThatWouldCloseALoopIsRefused() {
        val chain = Graph(
            listOf(
                Node("a", "mix"),
                Node("b", "mix", inputs = sources("latent" to "a")),
            )
        )
        val st = CanvasState(Workflow(chain, mapOf("a" to Pt(0f, 0f), "b" to Pt(400f, 0f))))
        val bs = layout(st.workflow, latentTypes)
        val start = bs.first { it.id == "b" }.outputPort(0)
        val end = bs.first { it.id == "a" }.inputPort(0)
        val next = st.press(start, latentTypes).release(end, latentTypes)
        assertEquals("that would make a loop", next.message)
        assertNull(next.workflow.graph.byId["a"]!!.inputs["latent"])
    }

    /** …and the same wire IS allowed when it closes no loop. */
    @Test
    fun theSameWireIsAllowedWhenThereIsNoLoop() {
        val pair = Graph(listOf(Node("a", "mix"), Node("b", "mix")))
        val st = CanvasState(Workflow(pair, mapOf("a" to Pt(0f, 0f), "b" to Pt(400f, 0f))))
        val bs = layout(st.workflow, latentTypes)
        val start = bs.first { it.id == "b" }.outputPort(0)
        val end = bs.first { it.id == "a" }.inputPort(0)
        val next = st.press(start, latentTypes).release(end, latentTypes)
        assertNull(next.message)
        assertEquals(Source("b", "latent"), next.workflow.graph.byId["a"]!!.inputs["latent"])
    }

    @Test
    fun dependsOnFollowsAChain() {
        val chain = Graph(
            listOf(
                Node("a", "sd.vae_decode"),
                Node("b", "sd.vae_decode", inputs = sources("latent" to "a")),
                Node("c", "sd.vae_decode", inputs = sources("latent" to "b")),
            )
        )
        assertTrue(chain.dependsOn("c", "a"))
        assertTrue(!chain.dependsOn("a", "c"))
    }

    /** ⚠ Terminates even on a graph that already contains a loop. */
    @Test
    fun dependsOnTerminatesOnACyclicGraph() {
        val looped = Graph(
            listOf(
                Node("a", "sd.vae_decode", inputs = sources("latent" to "b")),
                Node("b", "sd.vae_decode", inputs = sources("latent" to "a")),
            )
        )
        assertTrue(looped.dependsOn("a", "b"))
        assertTrue(!looped.dependsOn("a", "zzz"))
    }

    // --- graph edits --------------------------------------------------------

    /** ⚠ One wire per input: a second drop replaces rather than adds. */
    @Test
    fun connectingAnOccupiedInputReplacesTheWire() {
        val g = graph.connected("d", "latent", "s").connected("d", "latent", "d2")
        assertEquals(Source("d2"), g.byId["d"]!!.inputs["latent"])
        assertEquals(1, g.byId["d"]!!.inputs.size)
    }

    @Test
    fun disconnectingRemovesOnlyThatPort() {
        val g = graph.connected("d", "latent", "s").disconnected("d", "latent")
        assertTrue(g.byId["d"]!!.inputs.isEmpty())
    }

    /**
     * A LATENT -> LATENT node, which no built-in is. Modelled on the real
     * `com.example.latent-mix:LatentMix`, which needs QuickJS to construct.
     */
    private val latentTypes = types + ("mix" to object : com.abrah.nightmare.NodeType {
        override val name = "mix"
        override val version = "test@1"
        override val category = "latent"
        override val inputs = listOf(com.abrah.nightmare.Port("latent", "LATENT"))
        override val outputs = listOf(com.abrah.nightmare.Port("latent", "LATENT"))
        override fun contextKey(node: Node) = null
        override suspend fun run(
            ctx: com.abrah.nightmare.NodeCtx,
            node: Node,
            inputs: Map<String, com.abrah.nightmare.Value>,
        ) = throw UnsupportedOperationException("wiring only")
    })

    // --- previews and resizing ---------------------------------------------

    private val withPreview = mapOf("s" to ("img_1" to 2f))   // a 2:1 picture

    /** ⭐ A node showing a picture is TALLER, so the picture is not squeezed in. */
    @Test
    fun aPreviewMakesTheNodeTallerRatherThanSquashingTheImage() {
        val plain = layout(state.workflow, types).first { it.id == "s" }
        val shown = layout(state.workflow, types, withPreview).first { it.id == "s" }
        assertTrue("the node did not grow", shown.height > plain.height)
        // The picture is 2:1, so its height is half its width.
        val expected = (shown.width - 2 * Sizes.BODY_PADDING) / 2f
        assertEquals(expected, shown.preview!!.height, 0.5f)
    }

    /**
     * ⚠⚠ Hit-testing must use the SAME preview map as drawing. A node laid out
     * without its picture is shorter, so its ports and its resize corner would
     * be tested where the node is not.
     */
    @Test
    fun hitTestingAgreesWithTheDrawnHeight() {
        val st = state.copy(previews = withPreview)
        val box = layout(st.workflow, types, withPreview).first { it.id == "s" }
        assertTrue("the corner is not on the handle", box.onResizeHandle(Pt(box.right, box.bottom)))
        // …and the SHORT layout's corner is not where the tall node ends.
        val short = layout(st.workflow, types).first { it.id == "s" }
        assertTrue(short.bottom < box.bottom)
    }

    /** ⭐ Dragging the corner widens the node, and the width survives. */
    @Test
    fun draggingTheCornerResizesTheNode() {
        val box = layout(state.workflow, types).first { it.id == "s" }
        val corner = Pt(box.right, box.bottom)
        val next = state.press(corner, types).drag(Pt(corner.x + 120f, corner.y), Pt(120f, 0f), types)
        assertEquals(Sizes.NODE_WIDTH + 120f, next.workflow.widthOf("s"), 0.5f)
        // ⚠ And it is NOT a node drag: the node must not have moved.
        assertEquals(state.workflow.positions["s"], next.workflow.positions["s"])
    }

    /** ⚠ Clamped, so a node cannot be shrunk to something ungrabbable. */
    @Test
    fun aNodeCannotBeResizedBelowItsMinimum() {
        val box = layout(state.workflow, types).first { it.id == "s" }
        val corner = Pt(box.right, box.bottom)
        val next = state.press(corner, types).drag(Pt(corner.x - 500f, corner.y), Pt(-500f, 0f), types)
        assertEquals(Sizes.NODE_WIDTH, next.workflow.widthOf("s"), 0.5f)
    }

    /** ⭐ A tap on the PICTURE opens it fullscreen; a tap elsewhere edits. */
    @Test
    fun tappingThePreviewOpensItRatherThanTheInspector() {
        val st = state.copy(previews = withPreview)
        val box = layout(st.workflow, types, withPreview).first { it.id == "s" }
        val onPicture = Pt(box.topLeft.x + 20f, box.previewTop + 10f)
        val after = st.press(onPicture, types).release(onPicture, types)
        assertEquals("img_1", after.viewing)
        assertNull("the inspector should not have opened", after.editing)
    }

    @Test
    fun tappingTheHeaderStillOpensTheInspector() {
        val st = state.copy(previews = withPreview)
        val box = layout(st.workflow, types, withPreview).first { it.id == "s" }
        val onHeader = Pt(box.topLeft.x + 20f, box.topLeft.y + 10f)
        val after = st.press(onHeader, types).release(onHeader, types)
        assertEquals("s", after.editing)
        assertNull(after.viewing)
    }

    /**
     * ⭐⭐ …but an INTERACTIVE node's picture is its control, not something
     * to look at, so tapping it opens the node's own editor.
     *
     * ⚠⚠ Reported from the phone, 2026-09-09: tapping the cropper's preview
     * put a fullscreen copy of the picture over the thing the user was trying
     * to adjust. The rule cannot be "the crop node", which is why
     * [com.abrah.nightmare.NodeType.interactive] exists -- a Tier 0 plugin with
     * its own editor will want the same.
     */
    @Test
    fun tappingAnInteractiveNodesPictureOpensItsEditor() {
        val cropGraph = Graph(
            listOf(
                Node("photo", "image.load", mapOf("uri" to "/x.png")),
                Node("frame", "image.crop", inputs = sources("image" to "photo")),
            )
        )
        val st = CanvasState(
            Workflow(cropGraph, mapOf("photo" to Pt(0f, 0f), "frame" to Pt(400f, 0f))),
            previews = mapOf("frame" to ("img_c" to 1f)),
        )
        val box = layout(st.workflow, types, st.previews).first { it.id == "frame" }
        val onPicture = Pt(box.topLeft.x + 20f, box.previewTop + 10f)
        val after = st.press(onPicture, types).release(onPicture, types)
        assertEquals("frame", after.editing)
        assertNull("the viewer must not have opened over the editor", after.viewing)
    }

    // --- the zoom lock ------------------------------------------------------

    /** ⚠ A pinch is REFUSED while locked, not clamped to something smaller. */
    @Test
    fun aLockedCanvasDoesNotZoom() {
        val locked = state.copy(zoomLocked = true)
        val after = locked.zoom(Pt(200f, 200f), 1.6f)
        assertEquals(locked.viewport, after.viewport)
    }

    /** ⭐ …and two fingers still PAN it, which is the point of a lock. */
    @Test
    fun aLockedCanvasStillPans() {
        val locked = state.copy(zoomLocked = true)
        val after = locked.copy(viewport = locked.viewport.panned(Pt(40f, -25f)))
        assertEquals(40f, after.viewport.offset.x, 1e-4f)
        assertEquals(1f, after.viewport.scale, 1e-4f)
    }

    @Test
    fun theLockToggles() {
        val on = state.toggleZoomLock()
        assertTrue(on.zoomLocked)
        val off = on.toggleZoomLock()
        assertTrue(!off.zoomLocked)
        // ⚠ The zoom that was refused while locked works again.
        assertTrue(off.zoom(Pt(200f, 200f), 1.6f).viewport.scale > 1f)
    }

    /** ⚠ The lock is a VIEW state: it must not touch what gets saved or run. */
    @Test
    fun theLockChangesNothingAboutTheWorkflow() {
        assertTrue(state.toggleZoomLock().workflow === state.workflow)
        assertTrue(state.togglePanLock().workflow === state.workflow)
    }

    /** ⭐ The other lock: the canvas cannot be moved, at one finger or two. */
    @Test
    fun aPanLockedCanvasDoesNotMove() {
        val locked = state.copy(panLocked = true)
        assertEquals(locked.viewport, locked.pan(Pt(40f, -25f)).viewport)
        // ⚠⚠ …and through the ONE-finger path too, which is a different branch:
        // `drag` on `Panning`. The two used to move the viewport by different
        // routes, and a lock that caught only one of them is worse than none.
        val dragging = locked.press(Pt(-500f, -500f), types)
        assertEquals(locked.viewport, dragging.drag(Pt(-460f, -500f), Pt(40f, 0f), types).viewport)
    }

    /**
     * ⚠⚠ Locking the VIEW must not lock the GRAPH. A node still drags, or the
     * lock has quietly become "the canvas is read-only" — which is a different
     * feature nobody asked for.
     */
    @Test
    fun aPanLockedCanvasStillLetsNodesBeMoved() {
        val locked = state.copy(panLocked = true)
        val s = box("s")
        val grabbed = locked.press(Pt(s.topLeft.x + 90f, s.topLeft.y + 20f), types)
        val moved = grabbed.drag(Pt(s.topLeft.x + 190f, s.topLeft.y + 20f), Pt(100f, 0f), types)
        assertEquals(100f, moved.workflow.positions["s"]!!.x - s.topLeft.x, 0.5f)
    }

    /** ⚠ The two locks are independent: neither implies the other. */
    @Test
    fun theTwoLocksAreIndependent() {
        val zoom = state.toggleZoomLock()
        assertTrue(zoom.zoomLocked)
        assertTrue(!zoom.panLocked)
        val both = zoom.togglePanLock()
        assertTrue(both.zoomLocked && both.panLocked)
        // ⚠ A pan-locked canvas still zooms, unless the zoom lock is on too.
        assertTrue(state.togglePanLock().zoom(Pt(200f, 200f), 1.6f).viewport.scale > 1f)
    }

    // --- a wire that could only ever fail -----------------------------------

    /**
     * ⭐⭐ `load_image` hands the photo on whole now, so it cannot promise the
     * exact 512² a `vae_encode` demands — and that wire is refused **at the
     * drop**, with the fix in the message.
     *
     * ⚠⚠ This is the gesture half of `FramingTest`: the rule is pure, but a rule
     * the finger never meets is a rule that only fires at Run, after the wait.
     */
    @Test
    fun aPhotoWiredStraightIntoAnEncoderIsRefusedAtTheDrop() {
        val g = Graph(
            listOf(
                Node("photo", "image.load", mapOf("uri" to "/a.png")),
                Node(
                    "enc", "sd.vae_encode",
                    mapOf("model" to "m", "width" to "512", "height" to "512", "seed" to "1"),
                ),
            )
        )
        val st = CanvasState(Workflow(g, mapOf("photo" to Pt(0f, 0f), "enc" to Pt(500f, 0f))))
        val boxes = layout(st.workflow, types)
        val from = boxes.first { it.id == "photo" }.outputPort(0)
        val to = boxes.first { it.id == "enc" }.inputPort(0)

        // ⚠ The refusal is visible WHILE the finger is down, not only on release.
        val dragging = st.press(from, types).drag(to, Pt(1f, 0f), types)
        val pending = dragging.pending
        assertNotNull("no wire in flight", pending)
        assertNotNull("the drop should be refused", pending!!.error)

        val after = dragging.release(to, types)
        assertTrue("the wire must not have landed", after.workflow.graph.byId["enc"]!!.inputs.isEmpty())
        assertTrue("should name the fix: ${after.message}", after.message!!.contains("Crop"))
    }

    // --- the view a workflow reopens at --------------------------------------

    /** ⭐ A saved view is restored whole: where, how far in, and both locks. */
    @Test
    fun aSavedViewComesBack() {
        val moved = state.copy(
            viewport = Viewport(Pt(-300f, 120f), 2f), zoomLocked = true, panLocked = true,
        )
        val back = state.withView(moved.savedView)
        assertEquals(moved.viewport, back.viewport)
        assertTrue(back.zoomLocked)
        assertTrue(back.panLocked)
    }

    /**
     * ⚠⚠ …and NO saved view means a FRESH one, never the view the user happened
     * to be at. Carrying the old offset over is exactly what opened a workflow
     * onto blank space.
     */
    @Test
    fun noSavedViewMeansTheDefaultOneNotTheCurrent() {
        val somewhere = state.copy(
            viewport = Viewport(Pt(-4000f, 2500f), 0.4f), panLocked = true,
        )
        val back = somewhere.withView(null)
        assertEquals(Viewport(), back.viewport)
        assertTrue(!back.panLocked)
    }

    // --- which seed made the picture ----------------------------------------

    /** A decoder wired to a sampler, which is every txt2img graph. */
    private val decoded = Graph(
        listOf(
            Node("s", "sd.sample", mapOf("seed" to "77")),
            Node("d", "sd.vae_decode", inputs = sources("latent" to "s")),
        )
    )

    /**
     * ⚠⚠ Asking the DECODER for the seed answers with the SAMPLER's. A node
     * showing a picture is almost never the node that holds the seed, so a
     * lookup that stopped at the node itself would answer null every time.
     */
    @Test
    fun theSeedComesFromTheSamplerUpstream() {
        assertEquals("77", seedFor(decoded, "d"))
    }

    /**
     * ⭐ A ROLLED seed beats the param, because the param is the 0 that asked
     * for the roll. Showing "0" to a user who wants their picture back is worse
     * than showing nothing -- it is a number they can copy and never reproduce.
     */
    @Test
    fun aRolledSeedWinsOverTheZeroThatAskedForIt() {
        val rolling = Graph(
            listOf(
                Node("s", "sd.sample", mapOf("seed" to "0")),
                Node("d", "sd.vae_decode", inputs = sources("latent" to "s")),
            )
        )
        assertNull("a seed that has never run is not a seed", seedFor(rolling, "d"))
        assertEquals(
            "1284471903",
            seedFor(rolling, "d") { if (it == "s") "seed 1284471903  1132 ms" else null },
        )
    }

    /** ⚠ Nothing upstream samples: a photo has no seed, and must not invent one. */
    @Test
    fun aLoadedPhotoHasNoSeed() {
        val photo = Graph(
            listOf(
                Node("src", "image.load"),
                Node("c", "image.crop", inputs = sources("image" to "src")),
            )
        )
        assertNull(seedFor(photo, "c"))
    }

    // --- multi-select --------------------------------------------------------

    /** ⭐⭐ A long press on a node starts a multi-selection with it chosen. */
    @Test
    fun aLongPressStartsAMultiSelection() {
        val s = box("s")
        val at = Pt(s.topLeft.x + 90f, s.topLeft.y + 20f)
        val held = state.press(at, types).longPress()
        assertTrue(held.multiSelect)
        assertEquals(setOf("s"), held.selection)
    }

    /**
     * ⚠⚠ …and the RELEASE must not then also read as a tap. A long press that
     * opened the inspector would put a sheet over the selection it just made.
     */
    @Test
    fun aLongPressDoesNotAlsoOpenTheInspector() {
        val s = box("s")
        val at = Pt(s.topLeft.x + 90f, s.topLeft.y + 20f)
        val after = state.press(at, types).longPress().release(at, types)
        assertNull("the inspector opened over the selection", after.editing)
        assertTrue(after.multiSelect)
        assertEquals(setOf("s"), after.selection)
    }

    /** ⚠ A long press on empty space is not a selection of nothing. */
    @Test
    fun aLongPressOnTheBackgroundDoesNothing() {
        val held = state.press(Pt(-500f, -500f), types).longPress()
        assertTrue(!held.multiSelect)
        assertTrue(held.selection.isEmpty())
    }

    /** ⭐ In multi-select a tap TOGGLES membership rather than opening a sheet. */
    @Test
    fun tappingInMultiSelectTogglesAndOpensNothing() {
        val d = box("d")
        val at = Pt(d.topLeft.x + 90f, d.topLeft.y + 20f)
        val multi = state.copy(multiSelect = true, selection = setOf("s"))
        val added = multi.press(at, types).release(at, types)
        assertEquals(setOf("s", "d"), added.selection)
        assertNull(added.editing)
        // …and again removes it.
        val removed = added.press(at, types).release(at, types)
        assertEquals(setOf("s"), removed.selection)
    }

    @Test
    fun selectAllTakesEveryNodeAndClearingLeavesTheMode() {
        val all = state.selectAll()
        assertEquals(setOf("s", "d"), all.selection)
        assertTrue(all.multiSelect)
        val none = all.clearSelection()
        assertTrue(none.selection.isEmpty())
        assertTrue(!none.multiSelect)
    }

    /**
     * ⭐⭐ Delete applies to the whole selection — and takes the wires with
     * it. A dangling input makes `topoSort` refuse the whole graph at a node the
     * user never touched.
     */
    @Test
    fun deletingASelectionTakesItsWiresToo() {
        val wired = CanvasState(
            Workflow(
                Graph(
                    listOf(
                        Node("s", "sd.sample", mapOf("model" to "m", "width" to "512", "height" to "512")),
                        Node("d", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "s")),
                    )
                ),
                mapOf("s" to Pt(0f, 0f), "d" to Pt(400f, 0f)),
            ),
            selection = setOf("s"),
            multiSelect = true,
        )
        val after = wired.removeSelected()
        assertNull(after.workflow.graph.byId["s"])
        assertTrue(
            "the wire outlived the node it came from",
            after.workflow.graph.byId["d"]!!.inputs.isEmpty(),
        )
        assertTrue(after.selection.isEmpty())
        assertTrue(!after.multiSelect)
    }

    /** ⚠ A press on empty space leaves multi-select, or the mode is a trap. */
    @Test
    fun tappingTheBackgroundLeavesMultiSelect() {
        val at = Pt(-500f, -500f)
        val multi = state.copy(multiSelect = true, selection = setOf("s", "d"))
        // ⚠ press THEN release: the cancel is the short press, not the touch
        // down, so that a pinch starting on the background does not cancel.
        val after = multi.press(at, types).release(at, types)
        assertTrue(after.selection.isEmpty())
        assertTrue(!after.multiSelect)
    }

    // --- deleting a wire -----------------------------------------------------

    private val wiredGraph = Graph(
        listOf(
            Node("s", "sd.sample", mapOf("model" to "m", "width" to "512", "height" to "512")),
            Node("d", "sd.vae_decode", mapOf("model" to "m"), sources("latent" to "s")),
        )
    )
    private val wired = CanvasState(
        Workflow(wiredGraph, mapOf("s" to Pt(0f, 0f), "d" to Pt(400f, 0f)))
    )

    private fun wireMid(st: CanvasState): Pt {
        val bs = layout(st.workflow, types)
        val ends = wires(bs).first().second
        return wireMidpoint(ends.first, ends.second)
    }

    /** ⭐ A tap on a wire picks it and puts a delete mark at its middle. */
    @Test
    fun tappingAWirePicksIt() {
        val mid = wireMid(wired)
        val after = wired.press(mid, types).release(mid, types)
        assertEquals(WireRef("d", "latent", Source("s")), after.wire)
        assertTrue("nothing should be confirming yet", !after.wireConfirming)
        assertTrue("the wire must still be there", after.workflow.graph.byId["d"]!!.inputs.isNotEmpty())
    }

    /**
     * ⭐⭐ The whole interaction in one test: tap the wire, tap the mark, tap
     * the tick — and because the tick takes the mark's exact place, that reads
     * as a double tap on the middle of the wire.
     */
    @Test
    fun aDoubleTapOnTheMiddleOfAWireDeletesIt() {
        val mid = wireMid(wired)
        val picked = wired.press(mid, types).release(mid, types)
        val asked = picked.press(mid, types).release(mid, types)
        assertTrue("the delete mark should have armed the confirm", asked.wireConfirming)
        assertTrue("still not deleted", asked.workflow.graph.byId["d"]!!.inputs.isNotEmpty())

        val gone = asked.press(mid, types).release(mid, types)
        assertTrue("the wire survived", gone.workflow.graph.byId["d"]!!.inputs.isEmpty())
        assertNull(gone.wire)
        assertTrue(!gone.wireConfirming)
    }

    /** ⚠ …and the cancel, which sits beside the tick, puts it all away. */
    @Test
    fun theCancelBesideTheTickDismissesTheWholeThing() {
        val mid = wireMid(wired)
        val asked = wired.press(mid, types).release(mid, types)
            .press(mid, types).release(mid, types)
        val cancelAt = asked.wireButtons(layout(asked.workflow, types))!!.second
        val after = asked.press(cancelAt, types).release(cancelAt, types)
        assertNull(after.wire)
        assertTrue(!after.wireConfirming)
        assertTrue("cancel deleted it", after.workflow.graph.byId["d"]!!.inputs.isNotEmpty())
    }

    /**
     * ⚠⚠ A single tap can NEVER delete a wire. The confirm exists precisely
     * because a wire runs through the space a finger sweeps while panning.
     */
    @Test
    fun oneTapOnAWireDeletesNothing() {
        val mid = wireMid(wired)
        val after = wired.press(mid, types).release(mid, types)
        assertTrue(after.workflow.graph.byId["d"]!!.inputs.isNotEmpty())
    }

    /** ⚠ Picking a different wire starts over: a confirm belongs to ONE wire. */
    @Test
    fun theConfirmDoesNotCarryToAnotherWire() {
        val armed = wired.copy(wire = WireRef("d", "latent", Source("s")), wireConfirming = true)
        val at = Pt(-500f, -500f)
        // ⚠ On the RELEASE now: a wire's controls survive the touch down, so a
        // pinch that starts on the background does not dismiss them either.
        val elsewhere = armed.press(at, types).release(at, types)
        assertNull(elsewhere.wire)
        assertTrue(!elsewhere.wireConfirming)
    }

    /**
     * ⚠⚠ A NODE beats a wire. A wire passes through the space around a node,
     * and a tap that picked the wire instead of the node it runs behind would be
     * maddening.
     */
    @Test
    fun aTapOnANodeBeatsAWirePassingBehindIt() {
        val d = box("d")
        val at = Pt(d.topLeft.x + 90f, d.topLeft.y + 20f)
        val after = wired.press(at, types)
        assertNull(after.wire)
        assertTrue(after.gesture is Gesture.DraggingNode)
    }

    /** ⭐ …and with a crop between them the same drop is allowed. */
    @Test
    fun aCropBetweenThemMakesTheWireLegal() {
        val g = Graph(
            listOf(
                Node("photo", "image.load", mapOf("uri" to "/a.png")),
                Node("frame", "image.crop", inputs = sources("image" to "photo")),
                Node(
                    "enc", "sd.vae_encode",
                    mapOf("model" to "m", "width" to "512", "height" to "512", "seed" to "1"),
                ),
            )
        )
        val st = CanvasState(
            Workflow(
                g,
                mapOf("photo" to Pt(0f, 0f), "frame" to Pt(300f, 0f), "enc" to Pt(700f, 0f)),
            )
        )
        val boxes = layout(st.workflow, types)
        val from = boxes.first { it.id == "frame" }.outputPort(0)
        val to = boxes.first { it.id == "enc" }.inputPort(0)
        val after = st.press(from, types).drag(to, Pt(1f, 0f), types).release(to, types)
        assertEquals(
            Source("frame", "image"),
            after.workflow.graph.byId["enc"]!!.inputs["image"],
        )
    }
}
