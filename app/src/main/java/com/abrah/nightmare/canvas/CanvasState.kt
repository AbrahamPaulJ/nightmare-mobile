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
    data class ResizingNode(val id: String, val startWidth: Float, val from: Pt) : Gesture
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

    private fun boxes(types: Map<String, NodeType>) = layout(workflow, types, previews)

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
                gesture = Gesture.ResizingNode(box.id, workflow.widthOf(box.id), world),
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
        val r = Sizes.WIRE_BUTTON_RADIUS
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
        return mid to Pt(mid.x + Sizes.WIRE_BUTTON_GAP, mid.y)
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

            is Gesture.DraggingNode ->
                copy(
                    workflow = workflow.moved(g.id, world - g.grab),
                    gesture = g.copy(moved = true),
                )

            // ⚠ From the gesture's START width and the total travel, not by
            // accumulating per-event deltas: accumulating drifts, and a node
            // that ends a size different from where the finger is looks broken.
            is Gesture.ResizingNode ->
                copy(workflow = workflow.resized(g.id, g.startWidth + (world.x - g.from.x)))

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
            val onPreview = box?.preview != null && world.y >= box.previewTop
            // ⚠⚠ …UNLESS the node is interactive, and the cropper is why. Its
            // picture is not something to look at, it is the control you frame
            // with -- so a tap on it must open the framing view. Opening a
            // fullscreen copy of the thing you were trying to adjust is the
            // gesture landing on the wrong surface, and it was reported as
            // exactly that from the phone.
            return if (onPreview && box!!.type?.interactive != true) {
                copy(gesture = Gesture.Idle, viewing = box.preview!!.imageId)
            } else {
                copy(gesture = Gesture.Idle, editing = g.id)
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
        workflow = workflow.copy(graph = workflow.graph.withParam(nodeId, name, value)),
    )

    /** ⚠ One revision for a tuple that means one thing. [Graph.withParams]. */
    fun setParams(nodeId: String, values: Map<String, String>) = copy(
        workflow = workflow.copy(graph = workflow.graph.withParams(nodeId, values)),
    )

    fun closeInspector() = copy(editing = null)

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
        val id = workflow.graph.freeId(type.name.nodeLabel.lowercase())
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
            previews = previews - id,
            editing = id,
            showPalette = false,
            message = null,
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
        previews = previews - id,
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
        viewport = view?.let { Viewport(it.offset, it.scale) } ?: Viewport(),
        zoomLocked = view?.zoomLocked ?: false,
        panLocked = view?.panLocked ?: false,
    )

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
