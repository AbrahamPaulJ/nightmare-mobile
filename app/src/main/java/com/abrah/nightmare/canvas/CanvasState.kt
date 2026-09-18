package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.NodeType

/** Which of a picked wire's controls a tap landed on. */
private enum class WireButton { DELETE, CONFIRM, CANCEL }

/**
 * What a touch is currently doing.
 *
 * ⚠⚠ A state machine, in a file with no Compose types, because the *decisions*
 * are where a canvas goes wrong — "did this press start a wire or a pan?" — and
 * they cannot be tested through a pointer. The Compose layer feeds it positions
 * and does nothing else.
 */
sealed interface Gesture {
    data object Idle : Gesture

    /**
     * Moving the viewport. Also what a press on empty space becomes.
     *
     * @param moved whether the view has actually changed -- a drag, or a second
     *   finger arriving. ⚠⚠ This is what separates a TAP on the background from
     *   a PINCH that merely started there, and only the tap clears the
     *   selection. Without it, putting two fingers down to zoom while
     *   multi-selecting cancelled the selection on the way in: the first finger
     *   landed on empty space, and that alone used to mean "deselect
     *   everything". Reported from the phone, 2026-09-09.
     */
    data class Panning(val moved: Boolean = false, val onWire: Boolean = false) : Gesture

    /**
     * @param grab where inside the node the finger went down, so the node does
     *   not jump to centre itself under the touch.
     * @param moved whether the finger has actually travelled. ⚠ This is what
     *   separates a TAP from a DRAG: a tap opens the node's inspector, a drag
     *   must not. Without it, every attempt to move a node would also open a
     *   sheet over the graph the user was rearranging.
     */
    data class DraggingNode(val id: String, val grab: Pt, val moved: Boolean = false) : Gesture

    /** @param error non-null once the wire is over somewhere it cannot land. */
    data class DraggingWire(val from: PortRef, val to: Pt, val error: String? = null) : Gesture

    /**
     * A node held long enough to start a multi-selection.
     *
     * ⚠ Its own state so the RELEASE knows not to treat the lift as a tap. A
     * long press that also opened the inspector would put a sheet over the
     * selection it had just made.
     */
    data class LongPressed(val id: String) : Gesture

    /**
     * Resizing a node from its bottom-right corner.
     *
     * ⚠ Its own gesture rather than a mode on [DraggingNode]: the two do
     * opposite things with the same finger, and a boolean would make every
     * `drag` branch ask which it was.
     */
    data class ResizingNode(
        val id: String,
        val startWidth: Float,
        val from: Pt,
        /**
         * ⭐ The node's prose line budget when the drag began, so a VERTICAL
         * drag on the same corner grows the prompt boxes.
         *
         * ⚠ Lines rather than pixels, and from the START value like
         * [startWidth] — accumulating per-event deltas drifts, and a node that
         * ends a size different from where the finger is looks broken.
         */
        val startProseLines: Int = Sizes.PROSE_DEFAULT_LINES,
    ) : Gesture
}

/** The whole interactive state of the canvas, and the rules for changing it. */
data class CanvasState(
    val workflow: Workflow,
    val viewport: Viewport = Viewport(),
    val gesture: Gesture = Gesture.Idle,
    /**
     * The selected nodes.
     *
     * ⭐ A SET, because a long press starts a multi-selection and delete then
     * applies to all of it.
     *
     * ⚠⚠ **Selection means multi-select, and NOTHING else puts a node in here.**
     * A plain press used to select the node it landed on, which meant an
     * ordinary tap both opened the inspector AND turned the run bar into the
     * contextual delete row -- the app announcing a mode the user had not asked
     * for, on every single tap. Reported from the phone 2026-09-09. ⇒ The
     * invariant is now `selection.isNotEmpty()` ⇒ [multiSelect], and the only
     * ways in are [longPress], [selectAll] and a toggle-tap inside the mode.
     */
    val selection: Set<String> = emptySet(),
    /** The last refusal, for the UI to show. Cleared by the next successful action. */
    val message: String? = null,
    /** The node whose inspector is open, or null. */
    val editing: String? = null,
    /** Whether the add-a-node palette is open. */
    val showPalette: Boolean = false,
    /**
     * Node id -> (image id, aspect ratio) for the picture a node is showing.
     *
     * ⚠⚠ It lives HERE, beside the user's own edits, rather than only in the
     * drawing layer, because a preview changes a node's HEIGHT. Hit-testing and
     * drawing must agree on where a node ends: if only the canvas knew about
     * previews, every port and the resize corner of a node with a picture would
     * be tested at the wrong place, and taps would land on nothing.
     */
    val previews: Map<String, Pair<String, Float>> = emptyMap(),
    /**
     * ⭐ Node id -> (image id, aspect ratio) for what a before/after node
     * RECEIVED — [NodeBox.beforePreview], drawn above [NodeBox.preview].
     * `image.upscale` only, for now.
     *
     * ⚠⚠ Same reason [previews] lives here and not only in the drawing layer:
     * it changes a node's HEIGHT, so hit-testing (`boxes`) and drawing must
     * agree on it or a resize corner and a tap land somewhere the node is not.
     * Kept as its OWN map rather than folded into [previews], because the two
     * answer different questions for the same node — what it MADE stays
     * governed by [com.abrah.nightmare.NodeType.showsResult] (a renderer never
     * shows its own render there), and this is a second, narrower exception on
     * top of that rule, not a replacement for it.
     */
    val beforePreviews: Map<String, Pair<String, Float>> = emptyMap(),
    /**
     * ⭐⭐ Node id -> the image id it RENDERED on the last Run — including the
     * nodes that do not SHOW their result ([com.abrah.nightmare.NodeType.showsResult]).
     *
     * ⚠⚠ Why it is not [previews]: a sampler's preview is its framed INPUT, and
     * a text-to-image sampler has none, so a node wired to a sampler found no
     * picture there or the wrong one — the inpaint editor had nothing to paint
     * on in a generate → inpaint chain (found 2026-09-17). ⇒ [pictureInto].
     */
    val rendered: Map<String, String> = emptyMap(),
    /**
     * ⭐⭐ Node id -> the MP4 a video node produced.
     *
     * ⚠⚠ Separate from [previews] because a clip is not a picture: the canvas
     * draws the POSTER (which is in `previews` like any other image) and this is
     * the only route to the thing the poster is a still OF. Without it a video
     * node is indistinguishable from a node that made one picture — reported
     * from the phone as *"i dont see output as video"*.
     *
     * ⚠ A path, not a handle. The file outlives the run and the process; the
     * `ImageStore` entry beside it does not.
     */
    val videos: Map<String, String> = emptyMap(),
    /**
     * ⭐⭐ The field the inspector should open FOCUSED on, with the keyboard up.
     *
     * ⚠⚠ Set by a tap on a prompt box on the canvas. Typing in place was
     * built and then withdrawn (the user's call, 2026-09-11): a real composable
     * floated over the drawn canvas is the option `docs/ROADMAP.md` weighed and
     * set aside, and even for one field it meant a text cursor, a scrim and the
     * canvas's own gestures competing for the same pixels. Opening the sheet on
     * the right field is the same two taps' worth of intent with none of that.
     *
     * ⚠ Cleared once consumed, or reopening the sheet by any other route would
     * jump the keyboard up again.
     */
    val focusField: String? = null,
    /**
     * ⭐ A request to open a sampler's CROP popup: the node, and a count so the
     * same node asked twice is two requests. Set when a model is picked on the
     * node (asked for 2026-09-17) — the framing was just reset for the new shape,
     * and that is the moment to look at it.
     *
     * ⚠ Cleared with the inspector, or reopening the sheet by any other route
     * would pop the crop open again.
     */
    val cropRequest: Pair<String, Int>? = null,
    /** ⭐ Which tab [cropRequest] opens: 0 Crop, 1 Mask (a paused inpaint asks for Mask). */
    val cropRequestTab: Int = 0,
    /**
     * True while a long press has put the canvas in multi-select.
     *
     * ⚠⚠ An explicit flag rather than `selection.size > 1`. Long-pressing ONE
     * node must already behave differently — the next tap adds to the selection
     * instead of opening an inspector — and inferring the mode from the count
     * would make that first tap open a sheet over the selection the user was
     * building.
     */
    val multiSelect: Boolean = false,
    /**
     * The wire the user tapped, and whether they have asked to delete it.
     *
     * ⭐ The two-step is the whole interaction: a tap on a wire puts a delete
     * mark at its midpoint, and a tap on THAT reveals a tick in the same place
     * with a cancel beside it — so a double tap on the middle of a wire deletes
     * it, and a single tap never can.
     */
    val wire: WireRef? = null,
    val wireConfirming: Boolean = false,
    /** The image id being shown fullscreen, or null. */
    val viewing: String? = null,
    /**
     * ⭐⭐⭐ WHICH node's picture is open — recorded at the tap, never inferred.
     *
     * ⚠⚠ [viewing] is an IMAGE id, and an image id is a CONTENT address: a
     * sampler whose framing is the whole photo at the render size produces
     * pixels identical to the photo, so both nodes' previews are the SAME id.
     * Looking the node back up by that id then picks whichever comes first in
     * the map — and on 2026-09-15 that put the photo node's pick/bin buttons
     * over a sampler's preview, seen in a screenshot.
     *
     * ⇒ The tap knows the node. Recording it is the fix; deriving it is the bug
     * `docs/ARCHITECTURE.md` §5.6 names — *filter by the rule, then pick, never
     * pick then test*.
     */
    val viewingNode: String? = null,
    /**
     * ⭐ Pinch is ignored while this is on; two fingers still pan.
     *
     * ⚠ A canvas is worked at one zoom for long stretches -- framing a crop,
     * wiring a row of ports -- and every one of those gestures puts two fingers
     * on the glass. Without a lock the zoom drifts a few percent on each one and
     * the graph is never twice the same size. ⚠ SAVED with the workflow, along
     * with the viewport: it is how the user had the canvas set up to work on
     * THIS graph, so reopening it should hand that back. [SavedView].
     */
    val zoomLocked: Boolean = false,
    /**
     * ⭐ The canvas cannot be panned while this is on.
     *
     * ⚠ Its own lock, separate from [zoomLocked], because they solve different
     * annoyances: that one keeps the graph the same SIZE while you work at it,
     * this one keeps it in the same PLACE. ⚠ Node drags are untouched — locking
     * the VIEW must not lock the graph.
     */
    val panLocked: Boolean = false,
) {

    private fun boxes(types: Map<String, NodeType>) = layout(workflow, types, previews, beforePreviews)

    /**
     * A finger goes down at [world].
     *
     * ⚠⚠ **Ports are tested BEFORE node bodies**, and the order is the whole
     * decision. A port sits on the node's edge, so its hit-box overlaps the
     * body; testing the body first would make every wire-drag start a node-drag
     * instead, and the canvas would look like it simply could not connect
     * anything.
     */
    fun press(world: Pt, types: Map<String, NodeType>): CanvasState {
        val bs = boxes(types)
        // ⭐⭐ The wire's own controls come FIRST, before everything. They are
        // transient, they float over whatever is underneath, and they are what
        // the user is aiming at the moment they exist -- a tick that lost to the
        // node behind it would be a button that visibly does nothing.
        wireButtonAt(bs, world)?.let { hit ->
            return when (hit) {
                WireButton.DELETE -> copy(gesture = Gesture.Idle, wireConfirming = true, message = null)
                WireButton.CANCEL -> copy(gesture = Gesture.Idle, wire = null, wireConfirming = false)
                WireButton.CONFIRM -> {
                    val w = wire!!
                    copy(
                        workflow = workflow.copy(graph = workflow.graph.disconnected(w.toNode, w.toPort)),
                        gesture = Gesture.Idle,
                        wire = null,
                        wireConfirming = false,
                        message = null,
                    )
                }
            }
        }
        // ⚠⚠ Before ports AND before bodies. The handle sits on the node's
        // corner, so its hit-box overlaps both; testing it later would make a
        // resize read as a node drag and the corner would never work.
        bs.firstOrNull { it.onResizeHandle(world) }?.let { box ->
            // ⚠ Does not select: see [selection]. Grabbing a corner is a resize,
            // not a request to enter multi-select.
            return copy(
                gesture = Gesture.ResizingNode(
                    box.id, workflow.widthOf(box.id), world, workflow.proseLinesOf(box.id),
                ),
                message = null,
            )
        }
        portAt(bs, world)?.let { port ->
            return copy(gesture = Gesture.DraggingWire(port, world), message = null)
        }
        nodeAt(bs, world)?.let { box ->
            // ⚠⚠ The press changes the SELECTION not at all, in either mode.
            //
            // In multi-select the RELEASE toggles, and a press that also added
            // would make the two cancel out -- add on the way down, remove on
            // the way up, so tapping an unselected node would select nothing.
            // Caught by `tappingInMultiSelectTogglesAndOpensNothing`.
            //
            // Outside it there is nothing to change: a press is a tap or a drag,
            // and neither selects. Only a long press does. See [selection].
            return copy(
                gesture = Gesture.DraggingNode(box.id, world - box.topLeft),
                message = null,
            )
        }
        // ⭐ A wire, once nothing nearer was hit. ⚠ AFTER nodes: a wire passes
        // through the space around a node, and a tap that landed on a wire
        // instead of the node it runs behind would be maddening.
        wireAt(bs, world)?.let { w ->
            // ⭐⭐ A wire BETWEEN two selected nodes drags the group, exactly as
            // a selected node does. Asked for 2026-09-15 — *"moved as a group by
            // dragging any part (nodes/wires)"*.
            //
            // ⚠ Both ends must be in the selection. A wire with one end outside
            // it belongs as much to the node that is not selected, and dragging
            // it would move half of what the user is looking at.
            if (multiSelect && w.from.node in selection && w.toNode in selection) {
                val anchor = workflow.positions[w.from.node]
                if (anchor != null) {
                    return copy(
                        gesture = Gesture.DraggingNode(w.from.node, world - anchor),
                        wire = null,
                        message = null,
                    )
                }
            }
            return copy(
                // ⚠ [onWire] so the release does not immediately un-pick it:
                // this press is what SELECTED the wire, and the background tap
                // that dismisses one has to be a different press.
                gesture = Gesture.Panning(onWire = true),
                wire = w,
                // ⚠ A different wire starts over: the confirm belongs to the
                // wire it was opened on, never to whichever one is picked next.
                wireConfirming = false,
                message = null,
            )
        }
        // ⚠⚠ Empty space starts a PAN and changes nothing else. The clearing
        // happens on RELEASE, and only if nothing moved -- see [release].
        return copy(gesture = Gesture.Panning(), message = null)
    }

    /** Which of a picked wire's controls is under [world], if any. */
    private fun wireButtonAt(bs: List<NodeBox>, world: Pt): WireButton? {
        val at = wireButtons(bs) ?: return null
        // ⚠ Grows past the base radius as the canvas zooms out, so the
        // TAPPABLE area never shrinks below a comfortable size even though
        // everything else world-anchored does. Must agree with what
        // `drawWireButton` actually draws — see [Sizes.wireButtonRadius].
        val r = Sizes.wireButtonRadius(viewport.scale)
        fun near(p: Pt) = kotlin.math.hypot(world.x - p.x, world.y - p.y) <= r
        if (!wireConfirming) return if (near(at.first)) WireButton.DELETE else null
        if (near(at.first)) return WireButton.CONFIRM
        if (near(at.second)) return WireButton.CANCEL
        return null
    }

    /**
     * Where the picked wire's controls sit: the primary spot, and the cancel.
     *
     * ⚠⚠ The tick occupies the SAME point the delete mark did, which is what
     * makes "double-tap the middle of a wire" delete it. The cancel is offset,
     * so the destructive tap is the one that repeats and the safe one is a
     * deliberate move.
     */
    fun wireButtons(bs: List<NodeBox>): Pair<Pt, Pt>? {
        val w = wire ?: return null
        val ends = wires(bs).firstOrNull { it.first.id == w.id }?.second ?: return null
        val mid = wireMidpoint(ends.first, ends.second)
        return mid to Pt(mid.x + Sizes.wireButtonGap(viewport.scale), mid.y)
    }

    /**
     * The finger moves to [world], having travelled [screenDelta] on screen.
     *
     * ⚠ Both are needed and they are not interchangeable: a node moves in WORLD
     * units (so it keeps up with the finger at any zoom), while a pan moves the
     * viewport in SCREEN units (so the graph tracks the finger exactly).
     */
    fun drag(world: Pt, screenDelta: Pt, types: Map<String, NodeType>): CanvasState =
        when (val g = gesture) {
            // ⚠ Through [pan], which is what marks the gesture as having moved.
            is Gesture.Panning -> pan(screenDelta)

            // ⭐⭐⭐ **A selected node drags the whole selection with it.**
            //
            // Asked for 2026-09-15. Multi-select could delete a group and run a
            // group but not MOVE one, so rearranging four nodes meant four
            // drags and losing the arrangement between them.
            //
            // ⚠⚠ Every node moves by the same DELTA, not to the same place: the
            // grabbed node follows the finger exactly (`world - g.grab`, as it
            // always did) and the others keep their offsets from it. Moving them
            // all to the pointer would stack them.
            //
            // ⚠ Only when the grabbed node is IN the selection. Dragging an
            // unselected node while a selection exists moves that one node —
            // otherwise a stray drag would shift work the user had arranged and
            // forgotten they had selected.
            is Gesture.DraggingNode ->
                copy(
                    workflow = if (g.id in selection && selection.size > 1) {
                        // ⚠ The delta is where the grabbed node WOULD land less
                        // where it is now, so the group follows the finger at the
                        // grabbed node's own rate.
                        workflow.movedBy(
                            selection,
                            (world - g.grab) - (workflow.positions[g.id] ?: (world - g.grab)),
                        )
                    } else {
                        workflow.moved(g.id, world - g.grab)
                    },
                    gesture = g.copy(moved = true),
                )

            // ⚠ From the gesture's START width and the total travel, not by
            // accumulating per-event deltas: accumulating drifts, and a node
            // that ends a size different from where the finger is looks broken.
            // ⭐⭐ One corner, BOTH axes. Horizontal travel is the body width as
            // it always was; vertical travel grows the prose boxes.
            //
            // ⚠⚠ Height is still never stored as a number. A node's height is
            // derived (ports + picture + prose), and storing a pixel height
            // beside that would let the two disagree — the exact reason `sizes`
            // is width-only. What the drag sets is a LINE COUNT, which the
            // derivation then uses. Asked for from the phone 2026-09-11.
            is Gesture.ResizingNode ->
                copy(
                    workflow = workflow
                        .resized(g.id, g.startWidth + (world.x - g.from.x))
                        .proseResized(
                            g.id,
                            g.startProseLines +
                                ((world.y - g.from.y) / Sizes.PROSE_LINE_HEIGHT).toInt(),
                        ),
                )

            is Gesture.DraggingWire -> {
                // ⭐ The refusal is computed WHILE the finger is down, so the
                // canvas can colour the wire before the user commits. Finding
                // out on release is the worst moment to be told no.
                val over = portAt(boxes(types), world)
                copy(gesture = g.copy(to = world, error = over?.let { refusal(g.from, it, types) }))
            }

            // ⚠ A long press holds still by definition; any movement after it
            // is the user changing their mind, and the selection stays put
            // rather than turning into a drag halfway through.
            is Gesture.LongPressed -> this

            Gesture.Idle -> this
        }

    /**
     * The finger lifts. Returns the new state; a completed wire has already been
     * applied to [workflow].
     */
    fun release(world: Pt, types: Map<String, NodeType>): CanvasState {
        val g = gesture
        // ⭐ A press on a node that never moved is a TAP, and a tap opens the
        // inspector. Editing a prompt by dragging it a pixel would be absurd,
        // and opening a sheet every time a node is rearranged would be worse.
        if (g is Gesture.DraggingNode) {
            if (g.moved) return copy(gesture = Gesture.Idle)
            // ⭐⭐ In multi-select a tap TOGGLES membership and opens nothing.
            // Building a selection is the task; a sheet appearing over it on
            // every second tap would make the mode unusable.
            if (multiSelect) {
                val next = if (g.id in selection) selection - g.id else selection + g.id
                // ⭐⭐ Deselecting the LAST node leaves the mode.
                //
                // ⚠ Long-pressing one node and tapping it again used to leave
                // `multiSelect` on with nothing selected: a run bar reading
                // "0 of 3 selected", taps that silently toggled instead of
                // opening the inspector, and no way out but a tap on the
                // background — which is not discoverable from that state. The
                // gesture that entered the mode is the one that should leave it.
                return copy(
                    gesture = Gesture.Idle,
                    selection = next,
                    multiSelect = next.isNotEmpty(),
                )
            }
            // ⭐ A tap on the PICTURE opens it fullscreen; a tap anywhere else on
            // the node opens the inspector. Tapping a preview to edit a prompt
            // is not what anyone means by tapping a picture.
            val box = boxes(types).firstOrNull { it.id == g.id }
            // ⭐⭐ A tap on a PROMPT BOX edits that prompt, on the canvas.
            //
            // ⚠ Checked before the preview and before the inspector, because it
            // is the most specific target: the box is inside the node, and
            // falling through would open the sheet the user was avoiding.
            // ⭐⭐ A tap on a PROMPT BOX opens the inspector ON that prompt, with
            // the keyboard already up.
            //
            // ⚠ Checked before the preview and before the plain
            // open-the-inspector below, because it is the most specific target.
            box?.proseRects()?.firstOrNull { (_, top, bottom) ->
                world.y in top..bottom
            }?.let { (field, _, _) ->
                return copy(gesture = Gesture.Idle, editing = g.id, focusField = field)
            }
            val onPreview = box?.preview != null && world.y >= box.previewTop
            // ⚠⚠ …UNLESS the node is interactive, and the cropper is why. Its
            // picture is not something to look at, it is the control you frame
            // with -- so a tap on it must open the framing view. Opening a
            // fullscreen copy of the thing you were trying to adjust is the
            // gesture landing on the wrong surface, and it was reported as
            // exactly that from the phone.
            return if (onPreview && box!!.type?.interactive != true) {
                copy(gesture = Gesture.Idle, viewing = box.preview!!.imageId, viewingNode = box.id)
            } else {
                copy(gesture = Gesture.Idle, editing = g.id, focusField = null)
            }
        }
        // ⭐⭐ A long press starts multi-select on the node under the finger.
        if (g is Gesture.LongPressed) {
            return copy(gesture = Gesture.Idle, multiSelect = true, selection = setOf(g.id))
        }
        // ⚠ A resize never opens the inspector, however short it was.
        if (g is Gesture.ResizingNode) return copy(gesture = Gesture.Idle)
        // ⭐⭐ A SHORT PRESS on the background is the cancel -- and only that.
        //
        // ⚠⚠ It used to happen on the way DOWN, which made it impossible to
        // pinch while multi-selecting: the first of the two fingers landed on
        // empty space and the selection was gone before the second arrived. A
        // pan or a zoom that started on the background now leaves everything
        // where it was. ⚠ Still unconditional for the wire: a picked wire's
        // controls are transient, and a tap anywhere else is how you dismiss
        // them.
        if (g is Gesture.Panning) {
            if (g.moved || g.onWire) return copy(gesture = Gesture.Idle)
            return copy(
                gesture = Gesture.Idle,
                selection = emptySet(),
                multiSelect = false,
                wire = null,
                wireConfirming = false,
            )
        }
        if (g !is Gesture.DraggingWire) return copy(gesture = Gesture.Idle)

        val over = portAt(boxes(types), world)
            ?: return copy(gesture = Gesture.Idle)   // dropped on nothing: no-op, no complaint

        refusal(g.from, over, types)?.let {
            return copy(gesture = Gesture.Idle, message = it)
        }

        // ⚠ Normalise direction. The user may drag either way round, and the
        // graph only stores "this input reads that source" -- so the input end
        // decides where the edge is written regardless of which end was grabbed.
        val (input, output) = if (g.from.isInput) g.from to over else over to g.from
        return copy(
            workflow = workflow.copy(
                // ⚠ The OUTPUT PORT is recorded, not just its node: the canvas
                // hit-tested a specific dot, and dropping which one it was is
                // exactly what made two outputs indistinguishable afterwards.
                graph = workflow.graph.connected(
                    input.nodeId, input.port.name,
                    com.abrah.nightmare.Source(output.nodeId, output.port.name),
                )
            ),
            gesture = Gesture.Idle,
            message = null,
        )
    }

    /** Why this pair may not be joined, or null. */
    private fun refusal(from: PortRef, to: PortRef, types: Map<String, NodeType>): String? {
        connectionError(from, to)?.let { return it }
        val (input, output) = if (from.isInput) from to to else to to from
        // ⚠⚠ The cycle check needs the GRAPH, so it cannot live in
        // `connectionError` beside the other rules. Without it the executor
        // finds the loop at run time -- after the user pressed Run and waited.
        if (workflow.graph.wouldCycle(output.nodeId, input.nodeId)) {
            return "that would make a loop"
        }
        // ⭐⭐ …and neither can the SIZE check, for the same reason and one
        // more: it needs the node types too. `vae_encode` refuses anything but
        // an exact 512², and `load_image` cannot promise any size at all now
        // that it hands the photo on whole -- so that pair is a graph the user
        // can draw, that looks entirely reasonable, and that can only fail. It
        // is refused at the drop, naming the fix. `Framing.kt`.
        com.abrah.nightmare.sizeRefusal(
            workflow.graph, types, output.nodeId, input.nodeId, input.port.name,
        )?.let { return it }
        return null
    }

    /**
     * Change one widget on one node.
     *
     * ⚠ Params only — never positions, never wiring. The executor keys on
     * params, so this is also what makes the node re-run and everything
     * downstream of it, which is the whole point of editing a prompt.
     */
    fun setParam(nodeId: String, name: String, value: String) = copy(
        workflow = workflow.copy(
            graph =
                // ⭐ A DIFFERENT picture resets the framing and painting on it —
                // here, so the inspector's picker and the fullscreen viewer's
                // (and anything added later) cannot disagree. [Graph.withNewPicture].
                // ⚠ Not on a clear: an emptied node still shows the old framing
                // for the photo it may be given back.
                if (name == "uri" && value.isNotBlank() &&
                    workflow.graph.byId[nodeId]?.params?.get("uri") != value
                ) workflow.graph.withNewPicture(nodeId, value)
                else workflow.graph.withParam(nodeId, name, value),
        ),
    )

    /** ⚠ One revision for a tuple that means one thing. [Graph.withParams]. */
    fun setParams(nodeId: String, values: Map<String, String>) = copy(
        workflow = workflow.copy(graph = workflow.graph.withParams(nodeId, values)),
    )

    fun closeInspector() = copy(editing = null, focusField = null, cropRequest = null)

    /**
     * ⭐⭐ The picture coming INTO [nodeId] on [port] — what its upstream
     * RENDERED when it renders, what it shows otherwise.
     *
     * ⚠⚠ THE one lookup. The editors, the canvas preview, the auto-fit and the
     * paint-time rules all ask "which picture is going in", and five hand-rolled
     * `previews[up]` reads gave the wrong answer for a sampler upstream.
     */
    fun pictureInto(
        nodeId: String,
        types: Map<String, NodeType>,
        port: String = "image",
    ): String? {
        val up = workflow.graph.byId[nodeId]?.inputs?.get(port)?.node ?: return null
        val upType = workflow.graph.byId[up]?.type ?: return null
        // ⚠⚠ By the upstream's KIND, never "rendered, else preview", which was
        // wrong both ways (reported 2026-09-17):
        //  - a RENDERER before its first Run has nothing to hand on. Falling back
        //    to its preview passed its framed INPUT through, so an inpaint fed by
        //    a generate node showed the generate node's photo.
        //  - anything else is read by what it shows NOW. A photo's last-Run
        //    output outranked its new preview, so picking another photo after a
        //    run opened the cropper on the old one.
        return if (types[upType]?.showsResult == false) rendered[up] else previews[up]?.first
    }

    /**
     * ⭐ Long press on a node: enter multi-select with it chosen.
     *
     * ⚠ It is a GESTURE, not an immediate state change, because the finger is
     * still down -- the canvas must show the selection now, and the release must
     * not then also read as a tap that opens the inspector.
     */
    fun longPress(): CanvasState = when (val g = gesture) {
        is Gesture.DraggingNode ->
            copy(gesture = Gesture.LongPressed(g.id), multiSelect = true, selection = setOf(g.id))
        else -> this
    }

    fun selectAll() = copy(multiSelect = true, selection = workflow.graph.nodes.map { it.id }.toSet())

    fun clearSelection() = copy(selection = emptySet(), multiSelect = false)

    /**
     * Delete every selected node.
     *
     * ⚠ Through [removeNode] one at a time rather than a bulk graph edit,
     * because that is what also strips the wires that pointed at each of them --
     * a dangling input makes `topoSort` refuse the whole graph at a node the
     * user never touched.
     */
    fun removeSelected(): CanvasState =
        selection.fold(this) { st, id -> st.removeNode(id) }.clearSelection()

    fun openPalette() = copy(showPalette = true)

    fun closePalette() = copy(showPalette = false)

    /**
     * Add a node of [type] at [at] (world space).
     *
     * ⚠ Every widget's default is written into the node's params. A node that
     * arrived half-populated would fail at Run with "missing param" — an error
     * about the app rather than about the empty prompt the user can see.
     *
     * ⚠ The new node's inspector opens. Adding a sampler and being left to find
     * it is a worse first second than one extra sheet, and the first thing
     * anyone does with a new node is set it up. ⚠ It is NOT selected —
     * [selection] means multi-select, and arriving in that mode by adding a node
     * is exactly the surprise this change removed.
     */
    fun addNode(type: NodeType, at: Pt): CanvasState {
        val id = workflow.graph.freeId(type.defaultId ?: type.name.nodeLabel.lowercase())
        val node = com.abrah.nightmare.Node(
            id = id,
            type = type.name,
            params = type.widgets.mapNotNull { w -> w.default?.let { w.name to it } }.toMap(),
        )
        return copy(
            workflow = Workflow(
                graph = workflow.graph.copy(nodes = workflow.graph.nodes + node),
                positions = workflow.positions + (id to at),
            ),
            // ⚠⚠ **A NEW node must never inherit a picture.** [freeId] hands back
            // the lowest unused name, so deleting `upscale` and adding another
            // one gets the id `upscale` straight back -- and the preview map is
            // keyed by id, so the new node opened showing the DELETED node's
            // last output. Reported from the phone 2026-09-11: a freshly
            // dropped upscale node already had an old render on it.
            // ⚠ Belt and braces with [removeNode], which now drops it too. This
            // one also covers an id freed by any other route.
            //
            // ⚠⚠⚠ **[rendered] and [beforePreviews] need the SAME clearing,
            // and did not get it when they were added.** [pictureInto] reads
            // `rendered[up]` for any renderer upstream, so a stale entry there
            // leaks into whatever a REUSED id's new node feeds -- not just the
            // reused node's own display. Reported 2026-09-18: an upscale node
            // wired to inpaint showed the mask picture a PREVIOUS, deleted node
            // had left behind under the same id, and a freshly dropped upscale
            // node already had an old render on it (the 2026-09-11 bug, back,
            // for the two maps this rule did not yet cover).
            previews = previews - id,
            rendered = rendered - id,
            beforePreviews = beforePreviews - id,
            // ⚠ And the clip, for the same reason — a video node reusing a
            // freed id must not inherit the poster's MP4 either.
            videos = videos - id,
            editing = id,
            showPalette = false,
            message = null,
        )
    }

    /**
     * ⭐⭐ Rename a node. Its id IS its name on the canvas.
     *
     * ⚠⚠ **An id is not a label — it is what every wire points at.** So this
     * is not a cosmetic edit: every `inputs` [Source] naming the old id has to
     * move with it, and so does its entry in each of the maps keyed by id
     * (positions, sizes, previews, selection, the open inspector). Renaming the
     * node alone would silently disconnect the graph.
     *
     * ⚠ Refused, unchanged, when the new name is blank, already taken, or
     * contains [Source.SEP] — `topoSort` refuses an id with a ':' by name,
     * because the wire format reads the tail as a port.
     */
    fun renameNode(from: String, to: String): CanvasState {
        val name = to.trim()
        if (name == from) return this
        if (name.isEmpty() || com.abrah.nightmare.Source.SEP in name) return this
        if (workflow.graph.byId[name] != null) return this
        if (workflow.graph.byId[from] == null) return this

        val nodes = workflow.graph.nodes.map { n ->
            val renamed = if (n.id == from) n.copy(id = name) else n
            // ⚠ Every wire, on every node -- not just the renamed one's own.
            val rewired = renamed.inputs.mapValues { (_, src) ->
                if (src.node == from) src.copy(node = name) else src
            }
            if (rewired == renamed.inputs) renamed else renamed.copy(inputs = rewired)
        }
        fun <V> Map<String, V>.moveKey(): Map<String, V> =
            if (!containsKey(from)) this else (this - from) + (name to getValue(from))
        return copy(
            workflow = Workflow(
                graph = workflow.graph.copy(nodes = nodes),
                positions = workflow.positions.moveKey(),
                sizes = workflow.sizes.moveKey(),
            ),
            previews = previews.moveKey(),
            rendered = rendered.moveKey(),
            beforePreviews = beforePreviews.moveKey(),
            videos = videos.moveKey(),
            selection = if (from in selection) selection - from + name else selection,
            editing = if (editing == from) name else editing,
        )
    }

    /**
     * Delete a node.
     *
     * ⚠ Clears the selection and the inspector along with it. A sheet left open
     * on a node that no longer exists renders nothing and dismisses to a canvas
     * the user has to guess at.
     */
    fun removeNode(id: String) = copy(
        workflow = Workflow(
            graph = workflow.graph.without(id),
            positions = workflow.positions - id,
        ),
        // ⚠ The picture goes with the node. Leaving it behind makes the map
        // grow forever, and worse, hands it to the next node that takes this id.
        // ⚠⚠ Same for [rendered], [beforePreviews] and [videos] — see [addNode]'s note.
        previews = previews - id,
        rendered = rendered - id,
        beforePreviews = beforePreviews - id,
        videos = videos - id,
        selection = selection - id,
        editing = if (editing == id) null else editing,
        // ⚠ A picked wire that ended on this node no longer exists.
        wire = wire?.takeIf { it.toNode != id && it.from.node != id },
        wireConfirming = if (wire?.toNode == id || wire?.from?.node == id) false else wireConfirming,
    )

    /**
     * Pinch. [focus] is the midpoint between the fingers, in screen space.
     *
     * ⚠ Refused rather than clamped when [zoomLocked]. The gesture layer still
     * reports the pinch and still applies its pan, so two fingers on a locked
     * canvas move it without resizing it -- which is the point of the lock.
     */
    fun zoom(focus: Pt, factor: Float) =
        if (zoomLocked) moving() else moving().copy(viewport = viewport.zoomedAround(focus, factor))

    /**
     * Move the viewport by [screenDelta].
     *
     * ⚠⚠ Here rather than in `CanvasGestures`, and that is a fix as much as a
     * feature: the two-finger branch of the gesture loop called `viewport.panned`
     * itself, which put a DECISION in the one file whose whole rule is that it
     * makes none -- so the lock would have applied to one-finger panning and
     * silently not to two.
     */
    fun pan(screenDelta: Pt) =
        if (panLocked) moving() else moving().copy(viewport = viewport.panned(screenDelta))

    /**
     * ⚠⚠ A pan or a pinch is no longer a tap, LOCK OR NO LOCK.
     *
     * Marked here rather than in `drag`, because the pinch branch of the gesture
     * loop calls [pan] and [zoom] directly -- and marked even when the lock
     * refuses the movement, or locking the view would turn every two-finger
     * gesture back into a tap that clears the selection.
     */
    private fun moving(): CanvasState {
        val g = gesture
        return if (g is Gesture.Panning && !g.moved) copy(gesture = g.copy(moved = true)) else this
    }

    /**
     * ⚠ Clears the refusal with it: that message was about the last gesture.
     *
     * ⚠⚠ It deliberately does NOT announce itself in the message strip. That
     * strip is where a refusal lives -- "that would make a loop" -- so a notice
     * in it reads as something having gone wrong, and this went right. The
     * control says what it is instead: the run bar's readout writes "locked" and
     * turns the selection colour, which is state you can see rather than a
     * sentence that scrolls away.
     */
    fun toggleZoomLock() = copy(zoomLocked = !zoomLocked, message = null)

    /** ⚠ Same reasoning as [toggleZoomLock]: the control shows its own state. */
    fun togglePanLock() = copy(panLocked = !panLocked, message = null)

    /** Where the canvas is and how it is held, for the workflow file. */
    val savedView: SavedView
        get() = SavedView(viewport.offset, viewport.scale, zoomLocked, panLocked)

    /**
     * Restore a saved view, or start fresh when there is none.
     *
     * ⚠ A file written before views were saved gets the DEFAULT viewport rather
     * than whatever the user was looking at, because the alternative is what the
     * saved view exists to fix.
     */
    fun withView(view: SavedView?) = copy(
        viewport = view?.let { Viewport(it.offset, it.scale) } ?: fitted(),
        zoomLocked = view?.zoomLocked ?: false,
        panLocked = view?.panLocked ?: false,
    )

    /**
     * ⭐⭐ A viewport that FRAMES the graph, for a flow that recorded none.
     *
     * ⚠⚠ [withView] used to hand back a default [Viewport] here, on the
     * stated assumption that "a recipe's nodes are laid out near the origin".
     * That stopped being true the moment the recipes went diagonal so their
     * wires could run forward (`Workflows.diagonal`): an eight-node inpaint is
     * ~1470 units wide, and a default viewport opened it showing the first two
     * nodes and a lot of grid.
     *
     * ⚠ Width only. A phone canvas scrolls vertically without complaint — that
     * is the gesture people already make — but a node off the RIGHT edge is one
     * nobody knows is there. ⇒ Fit the width, leave the top where it is.
     *
     * ⚠ Clamped to the same 0.25..1 band a pinch can reach, and never zoomed
     * IN: a two-node graph magnified to fill the screen looks broken.
     */
    private fun fitted(): Viewport {
        val pts = workflow.positions.values
        if (pts.isEmpty()) return Viewport()
        // ⚠⚠ [Sizes.PROSE_NODE_WIDTH], not [Sizes.NODE_WIDTH]. The right-most
        // node in every recipe is the OUTPUT, and an output is 380 wide since
        // 2026-09-15 — measuring it at 190 left the last node half off-screen,
        // which is the exact failure this function exists to prevent.
        val widest = pts.maxOf { it.x } + Sizes.PROSE_NODE_WIDTH
        val scale = ((REFERENCE_WIDTH - REFERENCE_MARGIN) / widest).coerceIn(0.25f, 1f)
        // ⚠⚠ **Offset stays ZERO, and clearing the top bar is the LAYOUT's job**
        // (`Workflows.TOP`). It is tempting to push the graph down by an offset
        // here; it cannot be done correctly, because `Viewport.offset` is in
        // device PIXELS while everything else in this class is dp —
        // `forDevice` scales `scale` and leaves `offset` alone — and this class
        // cannot see the density. A recipe's `TOP` is in world units, so
        // `TOP * scale` is a dp clearance on every device: the density cancels.
        return Viewport(Pt(0f, 0f), scale)
    }

    private companion object {
        /**
         * ⚠⚠ A REFERENCE width in world units, not the real viewport — this
         * class is Compose-free and unit-tested, so it cannot measure the
         * screen. It is a phone's short edge in dp — and world units ARE dp
         * (`Viewport.forDevice`), so the two are directly comparable.
         *
         * ⚠⚠ It said **1100** until 2026-09-15, which is not any phone's
         * short edge and made this function a no-op: every recipe came out at
         * scale ~0.75 or clamped to 1, and a four-node flow 1600 units wide
         * was drawn 1200dp wide on a 411dp screen. The user had to pinch out
         * by hand every time a flow was opened, and that is what "all nodes
         * fit on the screen" was asking for.
         *
         * ⚠⚠⚠ **360, not this phone's 411**, and that is the second
         * correction of the same day — *"i fking told u it shd fit screen"*.
         * 411dp is the S25 Ultra at its DEFAULT display size; Android's screen
         * -zoom setting raises the density, which lowers the dp width, and a
         * fit computed against the developer's own untouched phone overflows
         * on anybody who made their UI bigger. 360 is the long-standing
         * baseline width and covers that. ⚠ A wider screen gets more margin
         * than it needed, which is the harmless direction to be wrong in; a
         * narrower one loses a node off the edge, which is not.
         */
        const val REFERENCE_WIDTH = 360f

        /**
         * ⚠ A margin in the same dp, subtracted before the fit rather than
         * added to the content — so it is a real gap on screen and not a few
         * world units that shrink with everything else.
         */
        const val REFERENCE_MARGIN = 32f
    }

    /**
     * The single selected node, when there is exactly one.
     *
     * ⚠ For the callers that genuinely mean "the one node" — the inspector, the
     * old golden states. A multi-selection deliberately answers null rather than
     * picking a member.
     */
    val selected: String? get() = selection.singleOrNull()

    /** What the canvas should draw as a wire in flight, if any. */
    val pending: PendingWire?
        get() = (gesture as? Gesture.DraggingWire)?.let { PendingWire(it.from, it.to, it.error) }
}
