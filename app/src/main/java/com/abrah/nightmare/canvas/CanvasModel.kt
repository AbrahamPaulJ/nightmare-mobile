package com.abrah.nightmare.canvas

import com.abrah.nightmare.Graph
import com.abrah.nightmare.Node
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Port
import com.abrah.nightmare.Source

/**
 * Where the nodes are, and where a finger lands.
 *
 * ⚠⚠ **No Compose types in this file, on purpose.** Every function here is pure
 * arithmetic over floats, so hit-testing and the world/screen transform can be
 * tested on the JVM with no Robolectric, no device and no golden image. Those
 * are exactly the parts that fail *silently* -- a port whose hit-box is 8 px off
 * does not throw, it just makes the app feel broken, and no screenshot shows it.
 *
 * The drawing code converts to Compose types at the boundary and does nothing
 * else clever.
 */

/** A point, in whichever space the caller is working in. */
data class Pt(val x: Float, val y: Float) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
}

/**
 * A workflow: the graph the executor runs, plus where the user put each node.
 *
 * ⚠ Positions are NOT in [Graph]. The executor must not care about pixels, and a
 * layout change must not invalidate a single cache entry -- which it would if
 * coordinates were part of a node's identity.
 */
data class Workflow(
    val graph: Graph,
    /** Node id -> top-left corner in world space. */
    val positions: Map<String, Pt>,
    /**
     * Node id -> body WIDTH in world units, for the nodes a user has resized.
     *
     * ⚠ Width only, never height: a node's height is derived from its ports plus
     * its preview at the picture's own aspect ratio. Storing both would let them
     * disagree and stretch the image, which is the thing resizing exists to fix.
     */
    val sizes: Map<String, Float> = emptyMap(),
    /**
     * ⭐⭐ Node id -> how many LINES each of its prose boxes may show.
     *
     * ⚠⚠ **Lines, not pixels.** A node's height is otherwise derived (ports
     * plus a picture at its own aspect), and storing a height beside that would
     * let the two disagree -- the exact reason [sizes] is width-only. A line
     * count composes with the derivation instead of fighting it: the box grows
     * by whole lines, and the node's height follows from the total as it always
     * has.
     */
    val proseLines: Map<String, Int> = emptyMap(),
) {
    fun moved(id: String, to: Pt) = copy(positions = positions + (id to to))

    /**
     * ⭐⭐ Shift a whole SET of nodes by one delta — a multi-select drag.
     *
     * ⚠⚠ A DELTA, not a destination: every node keeps its offset from the others,
     * which is the arrangement the user made and the only reason to move them
     * together. Moving them all to one point would stack them.
     *
     * ⚠ Nodes with no recorded position are skipped rather than defaulted to
     * (0,0) and shifted from there — that would teleport a node the user had
     * never placed.
     */
    fun movedBy(ids: Set<String>, delta: Pt) = copy(
        positions = positions + positions
            .filterKeys { it in ids }
            .mapValues { (_, at) -> Pt(at.x + delta.x, at.y + delta.y) },
    )

    /** ⚠ Clamped: a node dragged to nothing cannot be grabbed to undo it. */
    fun resized(id: String, width: Float) =
        copy(sizes = sizes + (id to width.coerceIn(Sizes.NODE_WIDTH, Sizes.NODE_MAX_WIDTH)))

    /** ⚠ Clamped to a readable band; see [Sizes.PROSE_MAX_LINES]. */
    fun proseResized(id: String, lines: Int) =
        copy(proseLines = proseLines + (id to lines.coerceIn(1, Sizes.PROSE_MAX_LINES)))

    fun widthOf(id: String): Float = sizes[id] ?: Sizes.NODE_WIDTH

    /**
     * ⭐⭐ A PROSE node is born twice as wide and at its full line budget.
     *
     * ⚠⚠ Asked for 2026-09-15 — *"increase the default width and height of the
     * prompt node (max height at default, and about 2x width)."* A prompt is the
     * one node whose content is text a person reads before pressing Run, and at
     * 190 units it was a column about 26 characters wide.
     *
     * ⚠ A DEFAULT, not a floor: dragging it narrower or shorter still works,
     * and a node the user has sized keeps its own size ([sizes] wins).
     */
    fun widthOf(id: String, type: com.abrah.nightmare.NodeType?): Float =
        sizes[id] ?: type?.defaultWidth ?: Sizes.NODE_WIDTH

    /**
     * ⚠⚠ Back to [Sizes.PROSE_DEFAULT_LINES], not the MAX.
     *
     * "Max height at default" and "don't show empty space of the textbox" are
     * both the user's, hours apart, and at the max they fight: the floor made
     * every box twelve lines tall whatever it held, which is exactly the empty
     * space the second ask was about — seen on the phone, 2026-09-15.
     *
     * ⇒ The box HUGS its text and the node is born twice as WIDE
     * ([NodeType.defaultWidth]), which is what made a prompt readable. A long
     * prompt still gets every line it needs; a short one no longer gets eleven
     * it does not.
     */
    fun proseLinesOf(id: String): Int = proseLines[id] ?: Sizes.PROSE_DEFAULT_LINES
}

/**
 * Sizes, in world units (which are dp before the viewport scale is applied).
 *
 * ⚠ These are TOUCH numbers, not desktop ones. ComfyUI's ports are ~5 px because
 * a mouse is precise; a fingertip is ~9 mm, so the visible port is 9 and the hit
 * radius is 26 -- nearly three times the drawn size. Ports are also spaced far
 * enough apart that two adjacent hit-boxes do not overlap
 * (`PORT_SPACING > 2 * PORT_HIT_RADIUS` is asserted by a test, because getting
 * it wrong makes the WRONG port connect and looks like a bug in the wiring).
 */
object Sizes {
    const val NODE_WIDTH = 190f

    /** ⚠ ~2x [NODE_WIDTH] — the width a node with prose is born at. */
    const val PROSE_NODE_WIDTH = 380f

    /** ⚠ A ceiling, so a node cannot be resized past anything a finger can pan around. */
    const val NODE_MAX_WIDTH = 900f

    /** The drag target at a node's bottom-right corner. Touch-sized, like the ports. */
    const val RESIZE_HIT = 30f

    /**
     * ⚠ Tall enough for a title AND the type beneath it. The first version put
     * the type in the body, where it collided with the first port's label --
     * the two are at the same height by construction, so no amount of padding
     * in the body would have separated them.
     */
    const val HEADER_HEIGHT = 48f
    const val PORT_SPACING = 54f
    const val PORT_TOP = 26f
    const val PORT_RADIUS = 9f
    const val PORT_HIT_RADIUS = 26f

    /**
     * ⭐ Prose in a node body — see [NodeBox.Prose].
     *
     * ⚠ [PROSE_CHAR_WIDTH] is an APPROXIMATION of one monospace character at
     * the drawn size, used only to guess how many lines the text will take.
     * `layout()` is Compose-free and unit-tested, so it cannot measure; the
     * renderer clips to whatever this predicts, which makes an under-estimate
     * an ellipsis rather than an overflow.
     */
    const val PROSE_CHAR_WIDTH = 6.2f
    const val PROSE_LINE_HEIGHT = 15f

    /**
     * ⚠⚠ The size `GraphCanvas` draws a prose body at, named HERE because the
     * layout has to count characters per line with it. Two copies of this
     * number would drift, and the symptom is a prompt that is one line short of
     * fitting — the exact thing the counting exists to prevent.
     */
    const val PROSE_FONT_SP = 10f

    /** ⚠ The smallest a prose box may be — a tap target, not a reading area. */
    const val PROSE_MIN_LINES = 2

    /** ⚠ A cap, so one pasted paragraph cannot make a node taller than the canvas. */
    const val PROSE_MAX_LINES = 12

    /**
     * Lines a prose box shows before the user drags it taller.
     *
     * ⚠ Two, not one: a one-line box is a thin strip to aim a finger at, and
     * a tap on it is what opens the prompt for editing.
     */
    const val PROSE_DEFAULT_LINES = 2

    /** Inset of the text inside its box, top and bottom. */
    const val PROSE_BOX_PAD = 4f

    /**
     * ⚠ Smaller than a port's, deliberately. A wire passes THROUGH the space
     * around a node, so a fat hit radius would swallow taps meant for the node
     * behind it — and a wire is tested after nodes for the same reason.
     */
    const val WIRE_HIT_RADIUS = 18f

    /** The tap target for a wire's delete / confirm / cancel controls. */
    const val WIRE_BUTTON_RADIUS = 22f

    /**
     * ⭐⭐ The SMALLEST a wire button may ever be on screen, in dp — a floor
     * under [WIRE_BUTTON_RADIUS], which is a WORLD unit and so shrinks with
     * every other world-anchored thing once the canvas is zoomed out.
     *
     * ⚠⚠ Reported 2026-09-18: at a low zoom the delete/tick/cross on a wire
     * became too small to tap comfortably — [WIRE_BUTTON_RADIUS] scaling
     * linearly with the viewport is right for a node's ports (a port must
     * shrink with its node, or wiring at a wide-open view would be all
     * targets and no graph) but wrong for a button meant to be pressed by a
     * finger regardless of zoom.
     *
     * ⇒ [wireButtonRadius] is the ONE function both the draw
     * (`GraphCanvas.drawWireButton`) and the hit test
     * (`CanvasState.wireButtonAt`) call, so the visible circle and the tappable
     * one can never disagree — the same discipline [WIRE_BUTTON_RADIUS]'s own
     * sibling docs already require of it.
     */
    const val WIRE_BUTTON_MIN_SCREEN_DP = 22f

    /**
     * The wire button's radius in WORLD units at [scale] — grows as the
     * canvas zooms out, past [WIRE_BUTTON_RADIUS], so that
     * `wireButtonRadius(scale) * scale` (what actually reaches the screen)
     * never drops below [WIRE_BUTTON_MIN_SCREEN_DP].
     */
    fun wireButtonRadius(scale: Float): Float =
        maxOf(WIRE_BUTTON_RADIUS, WIRE_BUTTON_MIN_SCREEN_DP / scale.coerceAtLeast(0.001f))

    /**
     * How far a selected node appears to rise off the canvas.
     *
     * ⚠⚠ Its shadow and halo only — the BODY does not move and does not grow.
     * Drawing a selected node larger than its [NodeBox] would put its outer edge
     * beyond what `nodeAt` tests, and in multi-select a tap on that edge does
     * not miss quietly: it reads as a tap on the background, which deselects
     * everything and leaves the mode. The lift is spent entirely outside the
     * geometry both halves agree on.
     */
    const val SELECT_LIFT = 7f

    /** ⚠ How far the cancel sits from the tick, centre to centre. */
    const val WIRE_BUTTON_GAP = 56f

    /**
     * ⭐ The floor under [WIRE_BUTTON_GAP], in dp — a bit over twice
     * [WIRE_BUTTON_MIN_SCREEN_DP] so the tick and the cross keep a visible
     * seam between them rather than merely stopping short of overlapping.
     * Without this, flooring the two buttons' RADIUS alone (2026-09-18) would
     * have fixed the tap target and broken the two apart into one smear at
     * the same low zoom that made the fix necessary.
     */
    const val WIRE_BUTTON_MIN_GAP_DP = 50f

    /** The gap between a wire's two buttons in WORLD units at [scale] — see [wireButtonRadius]. */
    fun wireButtonGap(scale: Float): Float =
        maxOf(WIRE_BUTTON_GAP, WIRE_BUTTON_MIN_GAP_DP / scale.coerceAtLeast(0.001f))
    const val BODY_PADDING = 14f
    const val CORNER = 14f

    /** Tall enough for the busier side, plus a little body. */
    fun nodeHeight(inputs: Int, outputs: Int): Float =
        HEADER_HEIGHT + PORT_TOP + maxOf(inputs, outputs, 1) * PORT_SPACING + BODY_PADDING

    /**
     * ⭐⭐ How far down the last PORT actually reaches — the anchor for a body
     * block, as opposed to [nodeHeight]'s minimum body.
     *
     * ⚠⚠ [nodeHeight] reserves a whole [PORT_SPACING] *past* the last port so
     * an empty node is not a sliver. For a node that HAS a body that reservation
     * is pure slack, and it showed up as a gap: a prompt node drew `cond` at 74
     * and its first prompt 66 further down, with nothing in between. Reported
     * from the phone 2026-09-11 ("too much top space above prompt").
     *
     * ⇒ A body block starts just below the last port row instead.
     */
    fun portsExtent(inputs: Int, outputs: Int): Float =
        HEADER_HEIGHT + PORT_TOP + (maxOf(inputs, outputs, 1) - 1) * PORT_SPACING +
            PORT_RADIUS + BODY_PADDING
}

/** A node laid out: where it is, how big, and where its ports are. */
data class NodeBox(
    val id: String,
    val type: NodeType?,
    val node: Node,
    val topLeft: Pt,
    val width: Float,
    val height: Float,
    /**
     * The picture this node is showing, and how tall it is at [width].
     *
     * ⚠ Height derived from the image's OWN aspect ratio, so a preview is never
     * stretched -- the node grows to fit the picture rather than the picture
     * being squeezed into the node.
     */
    val preview: Preview? = null,
    /**
     * ⭐ A SECOND picture, drawn ABOVE [preview] — what the node RECEIVED,
     * where [preview] is what it MADE. Null for every node but a before/after
     * one ([com.abrah.nightmare.UpscaleNode] today): a renderer never
     * draws its own result on the canvas ([com.abrah.nightmare.NodeType.showsResult]),
     * which is what left a chain like generate → upscale showing nothing of
     * the intermediate at all. Reported 2026-09-18.
     */
    val beforePreview: Preview? = null,
    /**
     * ⭐⭐ FLUX.2's edit REFERENCE, drawn above the other two so a glance at
     * the node says which picture is being read and which is being redrawn.
     *
     * ⚠ DERIVED in [layout] from the graph's `reference` wire, not stored:
     * the picture is the upstream node's own output, which the canvas
     * already knows. A second state map would be a second thing to clear.
     */
    val refPreview: Preview? = null,
    /** ⭐ Text drawn in the body — a prompt node's prompts. Null for every other node. */
    val prose: Prose? = null,
) {
    /** The area a preview occupies, and the drag target that resizes it. */
    data class Preview(val imageId: String, val height: Float)

    /**
     * The text a node shows in its body, as (field name, value) pairs.
     *
     * ⚠ [height] is computed from a LINE COUNT rather than measured — see
     * [layout]. The renderer clips to it.
     */
    data class Prose(
        val fields: List<Pair<String, String>>,
        val height: Float,
        /**
         * ⚠ Lines each box may use. Carried on the BOX so the renderer needs
         * nothing but what it was handed -- and so the height above and the
         * clipping below are computed from the same number.
         *
         * ⚠⚠ The MAXIMUM across the fields, kept for the renderer's clip and
         * for the resize test. Each box is drawn at its OWN height now, from
         * [lines] — see `layout`.
         */
        val maxLines: Int,
        /**
         * ⭐⭐ Lines for EACH field, in the order of [fields].
         *
         * ⚠⚠ Per-field since 2026-09-15: every box used to be the same generous
         * height, which left a one-line negative sitting in a box sized for a
         * paragraph. The user's call — *"don't show empty space of the textbox,
         * only the text parts."*
         */
        val lines: List<Int> = fields.map { maxLines },
        /**
         * ⭐ The token count for each field, in the order of [fields] — null
         * where none can be given. ⚠ The inspector's field reads the SAME
         * [com.abrah.nightmare.PromptTokens.count], so the two never disagree.
         */
        val counts: List<com.abrah.nightmare.PromptTokens.Count?> = fields.map { null },
    )

    /** ⚠ Bottom-right, and hit BEFORE the body so a resize is not read as a drag. */
    val resizeCorner get() = Pt(right, bottom)

    fun onResizeHandle(p: Pt) =
        kotlin.math.hypot(p.x - right, p.y - bottom) <= Sizes.RESIZE_HIT

    val previewTop get() = bottom - (preview?.height ?: 0f) - Sizes.BODY_PADDING

    /** ⭐ [beforePreview] sits directly above [preview], same padding rule. */
    val beforePreviewTop get() = previewTop - (beforePreview?.height ?: 0f) -
        (if (beforePreview != null) Sizes.BODY_PADDING else 0f)

    /** ⭐ [refPreview] sits above both, same padding rule again. */
    val refPreviewTop get() = beforePreviewTop - (refPreview?.height ?: 0f) -
        (if (refPreview != null) Sizes.BODY_PADDING else 0f)

    /**
     * ⭐⭐⭐ **Which picture a tap at `y` landed on** — the ONE place the
     * three bands are turned back into a picture.
     *
     * ⚠⚠ Asked for 2026-09-22: *"the canvas view of output node shows both
     * received and made preview of output img (if upscaled). if i click one it
     * should go into fullscreen for that img."* Before this, only [preview] was
     * tappable and a tap on the received one fell through to the inspector —
     * the node drew two pictures and answered to one.
     *
     * ⚠ Topmost first, and each band is its own height, so the order here
     * matches the order they are drawn in. The LAST one has no bottom edge:
     * [preview] runs to the node's bottom padding, which is what a tap below the
     * picture has always done.
     */
    fun previewAt(y: Float): Preview? {
        refPreview?.let { if (y >= refPreviewTop && y < refPreviewTop + it.height) return it }
        beforePreview?.let {
            if (y >= beforePreviewTop && y < beforePreviewTop + it.height) return it
        }
        preview?.let { if (y >= previewTop) return it }
        return null
    }

    /** ⚠ Above whichever picture is topmost — [refPreview] first, then [beforePreview]. */
    val proseTop get() = (
        if (refPreview != null) refPreviewTop
        else if (beforePreview != null) beforePreviewTop else previewTop
        ) -
        (prose?.height ?: 0f) - (if (prose != null) Sizes.BODY_PADDING else 0f)

    /**
     * ⭐⭐ Where each prose box IS, in world units — `field to (top, bottom)`.
     *
     * ⚠⚠ **One definition, two consumers.** The renderer draws these boxes and
     * the canvas hit-tests them to decide which field a tap landed on; if the
     * two computed the rect separately they would drift, and a tap would edit
     * the field next to the one under the finger. The renderer measures its
     * text and so is exact to the pixel, but it agrees with this because both
     * are driven by [Prose.maxLines].
     */
    fun proseRects(): List<Triple<String, Float, Float>> {
        val p = prose ?: return emptyList()
        var y = proseTop
        // ⚠ Each box is its OWN text's height. Still plain arithmetic, and
        // still the single definition both the renderer and the hit test use.
        return p.fields.mapIndexed { i, (field, _) ->
            val boxH = (p.lines.getOrElse(i) { p.maxLines }) * Sizes.PROSE_LINE_HEIGHT +
                2 * Sizes.PROSE_BOX_PAD
            val top = y + Sizes.PROSE_LINE_HEIGHT
            y = top + boxH + Sizes.PROSE_BOX_PAD
            Triple(field, top, top + boxH)
        }
    }

    val right get() = topLeft.x + width
    val bottom get() = topLeft.y + height

    fun contains(p: Pt) =
        p.x >= topLeft.x && p.x <= right && p.y >= topLeft.y && p.y <= bottom

    /**
     * ⚠ Inputs on the LEFT edge, outputs on the RIGHT, both centred on the
     * boundary rather than inset. A port drawn inside the node reads as
     * decoration; on the edge it reads as something a wire attaches to.
     */
    fun inputPort(i: Int) = Pt(
        topLeft.x,
        topLeft.y + Sizes.HEADER_HEIGHT + Sizes.PORT_TOP + i * Sizes.PORT_SPACING,
    )

    fun outputPort(i: Int) = Pt(
        right,
        topLeft.y + Sizes.HEADER_HEIGHT + Sizes.PORT_TOP + i * Sizes.PORT_SPACING,
    )

    val inputs: List<Port> get() = type?.inputs.orEmpty()
    val outputs: List<Port> get() = type?.outputs.orEmpty()
}

/** Which port on which node — what a drag starts from and ends on. */
data class PortRef(val nodeId: String, val port: Port, val isInput: Boolean, val at: Pt)

/**
 * One wire, named by the end that owns it.
 *
 * ⚠⚠ The INPUT end, always. The graph stores "this input reads that source", so
 * an input port identifies exactly one wire while an output may feed many —
 * naming a wire by its output would name a fan-out, and deleting it would take
 * out edges the user never touched.
 */
data class WireRef(val toNode: String, val toPort: String, val from: Source) {
    /** ⚠ Stable and cheap: the canvas compares these every frame while one is picked. */
    val id get() = "$toNode/$toPort"
}

/**
 * Where a wire runs, as the same cubic `GraphCanvas` draws.
 *
 * ⚠⚠ One definition of the curve, used by BOTH the drawing and the hit-testing.
 * The tap target for a wire has no visible outline of its own — it IS the drawn
 * line — so a second, "close enough" curve here would make wires that cannot be
 * tapped where they appear, with nothing on screen to explain it.
 */
fun wirePath(from: Pt, to: Pt): (Float) -> Pt {
    // ⚠ Identical to `drawEdge`: horizontal control points, at least 40 units
    // out, so a wire visibly LEAVES one port and ARRIVES at another.
    val dx = ((to.x - from.x) * 0.5f).coerceAtLeast(40f)
    val c1 = Pt(from.x + dx, from.y)
    val c2 = Pt(to.x - dx, to.y)
    return { t ->
        val u = 1f - t
        Pt(
            u * u * u * from.x + 3f * u * u * t * c1.x + 3f * u * t * t * c2.x + t * t * t * to.x,
            u * u * u * from.y + 3f * u * u * t * c1.y + 3f * u * t * t * c2.y + t * t * t * to.y,
        )
    }
}

/** The point halfway along the wire — where its controls appear. */
fun wireMidpoint(from: Pt, to: Pt): Pt = wirePath(from, to)(0.5f)

/**
 * Every wire in the graph, with the endpoints it is drawn between.
 *
 * ⚠ Skips a wire whose source node or port cannot be resolved rather than
 * dropping it silently from the canvas: those are drawn from dot 0 (see
 * `GraphCanvas`), and hit-testing has to agree with what is on screen.
 */
fun wires(boxes: List<NodeBox>): List<Pair<WireRef, Pair<Pt, Pt>>> {
    val byId = boxes.associateBy { it.id }
    val out = mutableListOf<Pair<WireRef, Pair<Pt, Pt>>>()
    for (box in boxes) {
        box.node.inputs.forEach { (portName, src) ->
            val up = byId[src.node] ?: return@forEach
            val portIndex = box.inputs.indexOfFirst { it.name == portName }
            if (portIndex < 0) return@forEach
            val outIndex = src.port
                ?.let { p -> up.outputs.indexOfFirst { it.name == p } }
                ?.takeIf { it >= 0 } ?: 0
            out += WireRef(box.id, portName, src) to
                (up.outputPort(outIndex) to box.inputPort(portIndex))
        }
    }
    return out
}

/**
 * The wire nearest [worldPoint] within [radius], or null.
 *
 * ⚠⚠ SAMPLED, not solved. The exact distance from a point to a cubic is a
 * quartic root-find; twenty-four samples are accurate to a fraction of the touch
 * radius and cannot fail to converge. ⚠ Nearest, not first-found, for the same
 * reason `portAt` is: iterating and returning the first hit makes the result
 * depend on declaration order, so two wires crossing would pick whichever
 * happened to be earlier in the list.
 */
fun wireAt(
    boxes: List<NodeBox>,
    worldPoint: Pt,
    radius: Float = Sizes.WIRE_HIT_RADIUS,
): WireRef? {
    var best: WireRef? = null
    var bestDist = radius * radius
    for ((ref, ends) in wires(boxes)) {
        val curve = wirePath(ends.first, ends.second)
        for (i in 0..WIRE_SAMPLES) {
            val p = curve(i.toFloat() / WIRE_SAMPLES)
            val d = dist2(p, worldPoint)
            if (d <= bestDist) {
                bestDist = d
                best = ref
            }
        }
    }
    return best
}

private const val WIRE_SAMPLES = 24

/**
 * Pan and zoom.
 *
 * `screen = world * scale + offset`, and [toWorld] is its exact inverse — which
 * a test asserts by round-tripping, because a transform that is subtly not
 * invertible makes every tap land slightly wrong only when zoomed.
 */
data class Viewport(val offset: Pt = Pt(0f, 0f), val scale: Float = 1f) {

    fun toScreen(world: Pt) = Pt(world.x * scale + offset.x, world.y * scale + offset.y)

    fun toWorld(screen: Pt) = Pt((screen.x - offset.x) / scale, (screen.y - offset.y) / scale)

    /**
     * Zoom about a fixed screen point — the midpoint between two fingers.
     *
     * ⚠ The point under the fingers must not move. Scaling the offset instead
     * of solving for it makes the graph slide away under a pinch, which reads
     * as the canvas fighting you.
     */
    fun zoomedAround(focus: Pt, factor: Float, min: Float = 0.25f, max: Float = 3f): Viewport {
        val next = (scale * factor).coerceIn(min, max)
        val world = toWorld(focus)
        return Viewport(Pt(focus.x - world.x * next, focus.y - world.y * next), next)
    }

    fun panned(by: Pt) = copy(offset = offset + by)

    /**
     * ⚠⚠ World units are **dp**, not pixels, and this is what makes that true.
     *
     * A Compose `DrawScope` works in PIXELS, but `TextStyle.fontSize` in `sp`
     * goes through the display density on its way to pixels. So drawing a
     * 48-unit header as 48 px beside a 14 sp title puts 42 px of text in a 48 px
     * box on a 3x screen — the labels overflowed their nodes and collided with
     * the port names, and no amount of padding could have fixed it because the
     * two were being measured in different units.
     *
     * ⇒ Geometry is converted here, once; font sizes stay in `sp` and are scaled
     * by the LOGICAL zoom only. Both the drawing and the hit-testing must use
     * the same converted viewport, or a tap lands where the node is not.
     */
    fun forDevice(density: Float) = copy(scale = scale * density)
}

/** The laid-out graph: boxes in declaration order, ready to draw or hit-test. */
/**
 * @param previews node id -> the image it is showing and that image's aspect
 *   ratio (width / height). ⚠ Passed in rather than read here: the layout is
 *   Compose-free and unit-tested, and an `ImageBitmap` is neither.
 * @param beforePreviews node id -> what it RECEIVED, same shape as [previews].
 *   Only a before/after node ([NodeBox.beforePreview]) ever has an entry.
 */
fun layout(
    workflow: Workflow,
    types: Map<String, NodeType>,
    previews: Map<String, Pair<String, Float>> = emptyMap(),
    beforePreviews: Map<String, Pair<String, Float>> = emptyMap(),
): List<NodeBox> =
    workflow.graph.nodes.map { n ->
        val type = types[n.type]
        val pos = workflow.positions[n.id] ?: Pt(0f, 0f)
        val width = workflow.widthOf(n.id, type)
        val nIn = type?.inputs?.size ?: 0
        val nOut = type?.outputs?.size ?: 0
        val shown = previews[n.id]
        val shownBefore = beforePreviews[n.id]
        // ⚠ The picture is inset from both edges, so its width is the node's
        // width less the padding -- using the full width would draw it over the
        // node's rounded corners.
        val previewWidth = width - 2 * Sizes.BODY_PADDING
        // ⭐⭐ How many lines EACH prose box may use, from the node's own extra
        // height. Dragging the node taller gives the prompts more room rather
        // than padding the bottom -- which is what "make the prompt node bigger
        // vertically" has to mean for a node whose height is otherwise derived.
        // ⚠⚠⚠ …and NEVER fewer than the text actually needs. The user's call,
        // 2026-09-15: *"don't hide them with '…', it should always be fully
        // visible."* A prompt is the one thing on the canvas a person reads
        // before deciding to press Run, and an ellipsis hides exactly the tail
        // that says which prompt this is.
        //
        // ⚠ Counted, not measured — this function is Compose-free and
        // unit-tested, and a `TextMeasurer` is neither. The font IS monospace
        // (the renderer draws it at [Sizes.PROSE_FONT_SP] in `FontFamily.
        // Monospace`), so characters-per-line is arithmetic rather than a
        // guess: a monospace advance is ~0.6 em.
        // ⚠ One extra line of slack, because the wrap breaks on WORDS — a long
        // word pushed to the next line makes the true count one more than the
        // character count implies.
        val usable = (width - 2 * Sizes.BODY_PADDING - 2 * Sizes.PROSE_BOX_PAD)
            .coerceAtLeast(1f)
        val perChar = Sizes.PROSE_FONT_SP * 0.6f
        val charsPerLine = (usable / perChar).toInt().coerceAtLeast(8)
        // ⭐ How many lines one field's text actually needs, at this node's
        // width. ⚠ One extra, because the wrap breaks on WORDS: a long word
        // pushed to the next line makes the true count one more than the
        // character count implies.
        fun linesFor(text: String): Int =
            // ⚠⚠ Never ONE, even empty. A one-line box is a thin strip to aim a
            // finger at, and a TAP is what opens the field for editing — which
            // is the constraint the uniform-height rule was protecting before
            // "hug the text" replaced it. Hugging wins for a box with text in
            // it; an empty one keeps a target.
            if (text.isBlank()) Sizes.PROSE_MIN_LINES
            else maxOf(
                workflow.proseLinesOf(n.id),
                (text.length + charsPerLine - 1) / charsPerLine + 1,
            )
        val perFieldLines = (type?.prose.orEmpty())
            .maxOfOrNull { linesFor(n.params[it].orEmpty()) }
            ?: workflow.proseLinesOf(n.id)
        val preview = shown?.let { (id, aspect) ->
            NodeBox.Preview(id, (previewWidth / aspect.coerceAtLeast(0.05f)))
        }
        val beforePreview = shownBefore?.let { (id, aspect) ->
            NodeBox.Preview(id, (previewWidth / aspect.coerceAtLeast(0.05f)))
        }
        // ⭐⭐ The reference, DERIVED: whatever the node's `reference` wire
        // comes from is already drawing that picture, so its entry in
        // [previews] is the one to show here too. ⚠ Absent for every node
        // that has no such wire, which is all of them but a FLUX.2 sampler
        // with a reference connected.
        val refPreview = n.inputs["reference"]?.node
            ?.let { previews[it] }
            ?.let { (id, aspect) ->
                NodeBox.Preview(id, (previewWidth / aspect.coerceAtLeast(0.05f)))
            }
        // ⭐⭐ **Prose in the body**, for a node whose whole content is text.
        //
        // ⚠⚠ A prompt node had nothing to show: its ports carry a
        // conditioning, so the canvas drew a box with one output and a name,
        // and the only way to see what it SAID was to open the inspector. Asked
        // for from the phone 2026-09-11 ("see both prompts on it").
        //
        // ⚠ It uses the SAME mechanism a picture does -- a body block that
        // adds height -- rather than a new one. That is also why this does not
        // break the deferral in `docs/ROADMAP.md` §"Batch results INSIDE the
        // node": that one needs N TAPPABLE thumbnails, and hit-testing against
        // the viewport transform is the hard part. Text is drawn and never hit,
        // so it needs none of it.
        //
        // ⚠ The height is counted in LINES here, not measured: this function is
        // Compose-free and unit-tested, and a `TextMeasurer` is neither. The
        // renderer clips to what it is given, so a long prompt ends in an
        // ellipsis rather than overflowing the node.
        // ⚠⚠ **EVERY declared field, blank or not.** Showing only what was
        // typed meant an empty negative simply vanished, so a node with one
        // prompt filled in looked like a node that has one field -- and there
        // was no way to see that the other exists without opening the
        // inspector. Both captions always show; an empty box reads as empty.
        val prose = type?.prose
            ?.takeIf { it.isNotEmpty() }
            ?.map { field -> field to n.params[field].orEmpty() }
            ?.let { fields ->
                // ⚠ Wrapped against the node's own width at the drawn font size,
                // so a WIDER node genuinely shows more -- which is what makes
                // dragging the resize corner the way to "make it bigger".
                // ⚠⚠ **Every box is [perFieldLines] tall, whatever it contains.**
                //
                // It used to be `min(wrapped, budget)`, which had two bad
                // consequences the phone found at once: a SHORT prompt pinned
                // the box to one line, so dragging the node taller did nothing
                // at all (the budget went up and the minimum ignored it); and
                // the two boxes were different heights, so the smaller one was
                // a thin strip to aim a finger at. Reported 2026-09-11 as
                // "vertical resize not working".
                //
                // ⇒ A uniform, generous target that the drag actually changes.
                // Text longer than the box is ellipsized by the renderer.
                // ⚠⚠⚠ **Each box hugs its OWN text**, rather than every box being
                // the same generous height. The user's call, 2026-09-15: *"don't
                // show empty space of the textbox, only the text parts."*
                //
                // ⚠ This reverses the 2026-09-11 fix for "vertical resize not
                // working", which made every box `perFieldLines` tall so the
                // drag visibly did something. The drag still raises the FLOOR —
                // `perFieldLines` is a minimum, not a target — so it keeps
                // working on a prompt that fits, and a prompt that overflows is
                // already at its full height. What goes is the dead space under
                // a one-line negative. `CLAUDE.md`, *The rules here are ours to
                // change*.
                val per = fields.map { (_, value) -> linesFor(value) }
                val lines = fields.size + per.sum()
                val budget = com.abrah.nightmare.PromptTokens.budgetFor(workflow.graph, n.id)
                NodeBox.Prose(
                    fields,
                    lines * Sizes.PROSE_LINE_HEIGHT + fields.size * Sizes.PROSE_BOX_PAD * 2,
                    perFieldLines,
                    per,
                    fields.map { (_, value) -> com.abrah.nightmare.PromptTokens.count(value, budget) },
                )
            }
        NodeBox(
            id = n.id,
            type = type,
            node = n,
            topLeft = pos,
            width = width,
            // ⚠ A node with a BODY anchors it just under the last port
            // ([Sizes.portsExtent]); one without keeps [Sizes.nodeHeight]'s
            // minimum, so an empty node is still a comfortable box.
            height = (if (prose != null) Sizes.portsExtent(nIn, nOut)
                else Sizes.nodeHeight(nIn, nOut)) +
                (preview?.let { it.height + Sizes.BODY_PADDING } ?: 0f) +
                (beforePreview?.let { it.height + Sizes.BODY_PADDING } ?: 0f) +
                (refPreview?.let { it.height + Sizes.BODY_PADDING } ?: 0f) +
                (prose?.let { it.height + Sizes.BODY_PADDING } ?: 0f),
            preview = preview,
            beforePreview = beforePreview,
            refPreview = refPreview,
            prose = prose,
        )
    }

/**
 * The port nearest [worldPoint] within the touch radius, or null.
 *
 * ⚠⚠ NEAREST, not first-found. Iterating and returning the first hit makes the
 * result depend on node declaration order, so two ports whose hit-boxes overlap
 * would connect the one that happens to be earlier in the list — a bug that
 * looks like the user missed.
 *
 * ⚠ The radius is in WORLD units and the caller converts, so a zoomed-out canvas
 * has a *smaller* screen hit-box. That is deliberate: the alternative makes
 * every port overlap its neighbours when zoomed out, and connecting the wrong
 * one is worse than having to zoom in.
 */
fun portAt(boxes: List<NodeBox>, worldPoint: Pt, radius: Float = Sizes.PORT_HIT_RADIUS): PortRef? {
    var best: PortRef? = null
    var bestDist = radius * radius
    for (b in boxes) {
        b.inputs.forEachIndexed { i, port ->
            val at = b.inputPort(i)
            val d = dist2(at, worldPoint)
            if (d <= bestDist) {
                bestDist = d
                best = PortRef(b.id, port, isInput = true, at = at)
            }
        }
        b.outputs.forEachIndexed { i, port ->
            val at = b.outputPort(i)
            val d = dist2(at, worldPoint)
            if (d <= bestDist) {
                bestDist = d
                best = PortRef(b.id, port, isInput = false, at = at)
            }
        }
    }
    return best
}

/**
 * The topmost node under [worldPoint].
 *
 * ⚠ LAST match wins, because later nodes are drawn on top. Returning the first
 * would pick whichever overlapping node happens to be behind.
 */
fun nodeAt(boxes: List<NodeBox>, worldPoint: Pt): NodeBox? = boxes.lastOrNull { it.contains(worldPoint) }

/**
 * Whether a wire from [from] to [to] is allowed, and why not when it is not.
 *
 * ⚠ Returns a REASON rather than a boolean. On a canvas the user has already
 * committed the gesture, so "nothing happened" is the worst possible answer;
 * the string is what the UI shows them.
 */
fun connectionError(from: PortRef, to: PortRef): String? = when {
    from.nodeId == to.nodeId -> "a node cannot feed itself"
    from.isInput == to.isInput ->
        if (from.isInput) "both ends are inputs" else "both ends are outputs"
    // ⚠⚠ The OUTPUT end first, always. A user may drag from either end (see
    // `aWireDraggedBackwardsLandsTheSameWay`), and [typeFits] is deliberately
    // one-directional — asking it in the order the finger happened to move
    // refused every backwards drag into a `MEDIA` port.
    !typeFits(if (from.isInput) to.port.type else from.port.type,
              if (from.isInput) from.port.type else to.port.type) ->
        "${from.port.type} does not fit a ${to.port.type} port"
    else -> null
}

/**
 * ⭐ `MEDIA` is the one port type that is a UNION — a picture or a clip.
 *
 * ⚠⚠ Here rather than as a set of aliases on [Port], because it is only ever
 * true in ONE direction: an IMAGE fits a MEDIA input, and a MEDIA output would
 * not fit an IMAGE input (nothing produces one, and the day something does, a
 * clip reaching a picture port must still be refused). ⇒ `core.output` accepts
 * both without every image node learning what a video is.
 */
private fun typeFits(from: String, to: String): Boolean =
    from == to || (to == "MEDIA" && (from == "IMAGE" || from == "VIDEO"))

/**
 * A failure, short enough to sit on a node.
 *
 * ⭐⭐ **The plugin's own line number is the point.** A Tier 0 contributor
 * writes JS with no debugger, no console and no stack trace anywhere they can
 * see — the engine already carries `index.js:12` in the exception, and drawing
 * only the message would throw away the single most useful thing a failing node
 * can say.
 *
 * ⚠ The FIRST line of the message, not the whole thing. A QuickJS exception is
 * message + stack, and the stack is several lines of frames that would cover
 * the graph. The location is lifted out of it and put back on the end, which is
 * the one part of the stack worth a node's width.
 *
 * ⚠ The location LEADS the message, so that what gets cut is the sentence.
 * See the comment on the line itself — a golden is what settled it.
 */
fun failureNote(detail: String?, limit: Int = 72): String {
    val first = detail.orEmpty().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    val where = JS_LOCATION.find(detail.orEmpty())?.value

    // ⚠⚠ The LOCATION LEADS, and that ordering was bought with a golden. With
    // the message first, `canvas-plugin-error` rendered
    // "TypeError: cannot read property 'width' of undefined (index.js…" -- two
    // lines of node width, and the truncation landed on the line number. The
    // sentence is guessable from the node; the line is not, so whatever gets
    // cut must be the sentence.
    //
    // ⚠ Unless the message already names the file: "SyntaxError at index.js:4"
    // prefixed with "index.js:4" reads as a bug in us, not in the plugin.
    val head = if (where != null && !first.contains(where)) "$where  " else ""
    val room = (limit - head.length).coerceAtLeast(12)
    val msg = if (first.length > room) first.take(room - 1) + "…" else first

    // ⚠ Never empty. `e.message` is null often enough (a bare
    // NullPointerException, anything thrown as a class rather than a sentence)
    // that a note built from it needs a floor, or a failed node draws a blank
    // label and looks fine.
    return (head + msg).trim().ifBlank { "failed" }
}

/** ⚠ `.js` only: a Kotlin stack frame's `Executor.kt:716` means nothing to a plugin author. */
private val JS_LOCATION = Regex("""[A-Za-z0-9_.\-]+\.js:\d+""")

private fun dist2(a: Pt, b: Pt): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return dx * dx + dy * dy
}

/**
 * ⭐ The seed that made the picture on [nodeId] — walking UPSTREAM to find it.
 *
 * ⚠⚠ The seed belongs to the sampler, but the picture belongs to whatever
 * decoded it, and those are never the same node. A user looking at a render they
 * like is looking at `decode`, so asking `decode` for its seed has to mean
 * "the seed of the sampler this came from" or the answer is always nothing.
 *
 * ⚠ [detailFor] rather than a `NodeStatus` map, to keep this file free of
 * Compose: the rolled seed arrives as the run's per-node detail line
 * (`HarnessOps.runRolled` writes `"seed 12345  …"`) and that is the ONLY place
 * it exists — a `seed = 0` node is never rewritten, by design, so its own param
 * still reads 0 after the run that rolled it.
 *
 * Returns null when nothing upstream samples, or when it has never run and its
 * seed is still 0 — there is no seed yet, and inventing "0" would be a number
 * the user could copy and never reproduce.
 */
fun seedFor(
    graph: com.abrah.nightmare.Graph,
    nodeId: String,
    detailFor: (String) -> String? = { null },
): String? {
    // ⚠ Breadth-first from the node itself, so the NEAREST sampler wins. A graph
    // with two of them feeding a blend has two seeds and no single answer; the
    // one that fed this picture most directly is the honest pick.
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(nodeId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        val node = graph.byId[id] ?: continue
        if (com.abrah.nightmare.isSampler(node.type)) {
            ROLLED_SEED.find(detailFor(id).orEmpty())?.let { return it.groupValues[1] }
            return node.params["seed"]?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        }
        node.inputs.values.forEach { queue.addLast(it.node) }
    }
    return null
}

/**
 * ⭐⭐ WHICH nodes loop their clip — **the end of the video chain, and only it.**
 *
 * ⚠⚠ Every node holding a [com.abrah.nightmare.Value.Video] used to animate, so
 * the shipped recipe played the same two seconds twice: once on the sampler and
 * once on the output node wired straight off it. Two copies of one clip is
 * not two answers to anything, it is the same answer drawn twice at 12 Hz, and
 * the sampler is the wrong place for it — a sampler's job is to be the knobs.
 * Reported from the phone, 2026-09-12: *"why is the video sampler playing the
 * output? it shouldnt"*.
 *
 * ⚠ Defined by the GRAPH rather than by node type: a
 * sampler with nothing wired off it IS the end of the chain and still loops, so
 * deleting the output node does not leave a graph that plays nothing. That also
 * keeps a contributor's own video node working without being named here.
 *
 * ⚠ [videos] is keyed by node id and outlives the nodes in it — a clip stays in
 * the map after its node is deleted — so ids the graph no longer knows are
 * dropped rather than reported as terminal.
 */
fun clipNodes(
    graph: com.abrah.nightmare.Graph,
    videos: Map<String, String>,
): Set<String> {
    if (videos.isEmpty()) return emptySet()
    val consumed = graph.nodes.flatMapTo(mutableSetOf()) { n -> n.inputs.values.map { it.node } }
    return videos.keys.filterTo(mutableSetOf()) { it in graph.byId && it !in consumed }
}

/**
 * ⭐⭐⭐ **Which node OWNS the clip this poster is a still of** — or null.
 *
 * ⚠⚠⚠ **Filter first, then pick.** `Value.Video.previewImage()` hands the
 * SAME poster id to every node the clip flows through, so a t2v graph records
 * both the sampler and the output node in `previews` under one image id. A
 * `firstOrNull` over that map answers whichever the map happened to hold first
 * — usually the sampler, which [clipNodes] correctly rejects as not owning the
 * clip — and the caller then falls back to the still.
 *
 * ⚠⚠ That exact bug has been written **twice**: once in Save and Share
 * (2026-09-13, the poster saved instead of the clip) and again in the keep path
 * (2026-09-15, every auto-kept video result filed as a still while the node
 * viewer kept playing). Both times by hand-rolling the join instead of calling
 * it. ⇒ It lives here now, and both call it. Two surfaces that must agree call
 * the SAME function (`docs/ARCHITECTURE.md` §5.6).
 *
 * @param previews node id -> the image it shows, as `CanvasState.previews` holds it
 */
fun clipOwner(
    graph: com.abrah.nightmare.Graph,
    previews: Map<String, Pair<String, Float>>,
    videos: Map<String, String>,
    imageId: String,
): String? {
    val owners = clipNodes(graph, videos)
    return previews.entries
        .filter { it.value.first == imageId }
        .map { it.key }
        .firstOrNull { it in owners }
}


/**
 * ⭐ WHICH sampler feeds [nodeId] — the node a locked seed has to be written to.
 *
 * ⚠ Same breadth-first walk as [seedFor], and deliberately so: the seed shown
 * on a picture and the node a lock writes to must be the same sampler, or the
 * lock pins a number the picture did not come from.
 */
fun samplerFor(graph: com.abrah.nightmare.Graph, nodeId: String): String? {
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(listOf(nodeId))
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!seen.add(id)) continue
        val node = graph.byId[id] ?: continue
        if (com.abrah.nightmare.isSampler(node.type)) return id
        node.inputs.values.forEach { queue.addLast(it.node) }
    }
    return null
}

/** ⚠ Anchored: `HarnessOps` prepends it, so a seed elsewhere in the line is not this. */
private val ROLLED_SEED = Regex("""^seed (\d+)""")

/**
 * ⭐⭐ A type whose stripped name is not what the thing is CALLED.
 *
 * `sd.clip_encode` names the op — it is CLIP, it produces a conditioning — and
 * "clip_encode" is what a person reading the palette has to decode before they
 * find the box they type their prompt into. Every recipe already names that node
 * `prompt` ([RECIPES]), so the palette, the node header and the id a dragged node
 * is born with were the last three places still saying the op's name. The user's
 * call, 2026-09-12.
 *
 * ⚠⚠ A label, NOT a rename: the type string stays `sd.clip_encode` in every
 * saved file, every plugin manifest and the inspector's header
 * (`docs/ARCHITECTURE.md` §5.3). Renaming the TYPE to make it read well on a node
 * header is choosing an identifier for the wrong reason, and it would break a
 * manifest that already depends on it.
 *
 * ⚠ One entry, not a table of nine. The other eight built-ins strip to the word
 * they are already called in the docs and the recipes (`sample`, `crop`, `mask`,
 * `upscale`); paraphrasing those too would put a second vocabulary between the
 * palette and every doc that discusses them.
 */
// ⚠⚠ EVERY family's text node, not one of them. `nd.clip_encode` drew as
// `clip_encode` while `sd.clip_encode` drew as `prompt` — the same job under two
// words (the design review, 2026-09-15). A new family's prompt node goes here too.
private val LABEL_OVERRIDES = mapOf(
    "sd.clip_encode" to "prompt",
    "nd.clip_encode" to "prompt",
)

/**
 ⭐ What a node type is CALLED on screen, as opposed to what it is identified by.
 *
 * ⚠⚠ The two parted company when the built-ins were namespaced: `sd.sample`
 * is the stable id a workflow file and a plugin manifest write down, and
 * `sample` is what fits on a node header. ⚠ It strips a plugin's id the
 * same way (`com.example.pack:Thing` -> `Thing`), which is what this replaced --
 * so there is now ONE rule for both instead of a `substringAfterLast(':')`
 * copied into the palette, the canvas and the id generator.
 *
 * ⚠ [LABEL_OVERRIDES] first, so a type whose stripped name reads as jargon can
 * be given the word the rest of the app uses for it.
 */
val String.nodeLabel: String
    get() = LABEL_OVERRIDES[this] ?: substringAfterLast(':').substringAfterLast('.')

// ⚠ Abbreviations a sentence-case rule would get wrong, and ids too terse to
// read as words. Everything else is the id with `_` as a space, Capitalised.
private val KNOB_OVERRIDES = mapOf(
    "cfg" to "CFG",
    // ⚠ The capitalisation everyone who trains one uses; "Loras" reads as a typo.
    "loras" to "LoRAs",
    "out_w" to "Output width",
    "out_h" to "Output height",
    "w" to "Width",
    "h" to "Height",
    "uri" to "Picture",
    // ⚠ DreamUI's own words for the same toggle.
    "stitch" to "Stitch to original image",
)

/**
 * ⭐⭐ What a KNOB is called on screen — `cfg` → `CFG`, `out_w` → `Output width`,
 * `denoise` → `Denoise`.
 *
 * ⚠⚠ The same split [nodeLabel] makes for a node: the widget's `name` is the
 * param key a workflow file and a manifest write down, and must never change;
 * this is only what is drawn. Every inspector branch, the sweep dialog, the
 * batch sheet and the node body call THIS — the user's call in the design
 * review, 2026-09-15, which found raw ids everywhere but `Karras sigmas`.
 * ⚠ `docs/UI.md` §7.4: a label added to one rendering branch and not the others
 * is the bug that section records three times.
 */
val String.knobLabel: String
    get() = KNOB_OVERRIDES[this] ?: replace('_', ' ').replaceFirstChar { it.uppercase() }

/** ⚠ The same label inside a sentence: `Batching 4 denoise values`, `4 CFG values`. */
val String.knobWord: String
    get() = knobLabel.let { if (it == it.uppercase()) it else it.lowercase() }
