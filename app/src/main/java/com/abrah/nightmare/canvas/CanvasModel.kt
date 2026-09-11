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
) {
    fun moved(id: String, to: Pt) = copy(positions = positions + (id to to))

    /** ⚠ Clamped: a node dragged to nothing cannot be grabbed to undo it. */
    fun resized(id: String, width: Float) =
        copy(sizes = sizes + (id to width.coerceIn(Sizes.NODE_WIDTH, Sizes.NODE_MAX_WIDTH)))

    fun widthOf(id: String): Float = sizes[id] ?: Sizes.NODE_WIDTH
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

    /** ⚠ A cap, so one pasted paragraph cannot make a node taller than the canvas. */
    const val PROSE_MAX_LINES = 6

    /**
     * ⚠ Smaller than a port's, deliberately. A wire passes THROUGH the space
     * around a node, so a fat hit radius would swallow taps meant for the node
     * behind it — and a wire is tested after nodes for the same reason.
     */
    const val WIRE_HIT_RADIUS = 18f

    /** The tap target for a wire's delete / confirm / cancel controls. */
    const val WIRE_BUTTON_RADIUS = 22f

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
    const val BODY_PADDING = 14f
    const val CORNER = 14f

    /** Tall enough for the busier side, plus a little body. */
    fun nodeHeight(inputs: Int, outputs: Int): Float =
        HEADER_HEIGHT + PORT_TOP + maxOf(inputs, outputs, 1) * PORT_SPACING + BODY_PADDING
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
    data class Prose(val fields: List<Pair<String, String>>, val height: Float)

    /** ⚠ Bottom-right, and hit BEFORE the body so a resize is not read as a drag. */
    val resizeCorner get() = Pt(right, bottom)

    fun onResizeHandle(p: Pt) =
        kotlin.math.hypot(p.x - right, p.y - bottom) <= Sizes.RESIZE_HIT

    val previewTop get() = bottom - (preview?.height ?: 0f) - Sizes.BODY_PADDING

    /** ⚠ Above the picture when a node somehow has both; today nothing does. */
    val proseTop get() = previewTop - (prose?.height ?: 0f) -
        (if (prose != null) Sizes.BODY_PADDING else 0f)

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
 */
fun layout(
    workflow: Workflow,
    types: Map<String, NodeType>,
    previews: Map<String, Pair<String, Float>> = emptyMap(),
): List<NodeBox> =
    workflow.graph.nodes.map { n ->
        val type = types[n.type]
        val pos = workflow.positions[n.id] ?: Pt(0f, 0f)
        val width = workflow.widthOf(n.id)
        val ports = Sizes.nodeHeight(type?.inputs?.size ?: 0, type?.outputs?.size ?: 0)
        val shown = previews[n.id]
        // ⚠ The picture is inset from both edges, so its width is the node's
        // width less the padding -- using the full width would draw it over the
        // node's rounded corners.
        val previewWidth = width - 2 * Sizes.BODY_PADDING
        val preview = shown?.let { (id, aspect) ->
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
        val prose = type?.prose
            ?.mapNotNull { field -> n.params[field]?.takeIf { it.isNotBlank() }?.let { field to it } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { fields ->
                // ⚠ Wrapped against the node's own width at the drawn font size,
                // so a WIDER node genuinely shows more -- which is what makes
                // dragging the resize corner the way to "make it bigger".
                val perLine = (previewWidth / Sizes.PROSE_CHAR_WIDTH).toInt().coerceAtLeast(8)
                val lines = fields.sumOf { (_, v) ->
                    // ⚠ +1 for the field's own caption line.
                    1 + ((v.length + perLine - 1) / perLine).coerceIn(1, Sizes.PROSE_MAX_LINES)
                }
                NodeBox.Prose(fields, lines * Sizes.PROSE_LINE_HEIGHT)
            }
        NodeBox(
            id = n.id,
            type = type,
            node = n,
            topLeft = pos,
            width = width,
            height = ports +
                (preview?.let { it.height + Sizes.BODY_PADDING } ?: 0f) +
                (prose?.let { it.height + Sizes.BODY_PADDING } ?: 0f),
            preview = preview,
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
    from.port.type != to.port.type ->
        "${from.port.type} does not fit a ${to.port.type} port"
    else -> null
}

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
        if (node.type == "sd.sample") {
            ROLLED_SEED.find(detailFor(id).orEmpty())?.let { return it.groupValues[1] }
            return node.params["seed"]?.trim()?.takeIf { it.isNotBlank() && it != "0" }
        }
        node.inputs.values.forEach { queue.addLast(it.node) }
    }
    return null
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
        if (node.type == "sd.sample") return id
        node.inputs.values.forEach { queue.addLast(it.node) }
    }
    return null
}

/** ⚠ Anchored: `HarnessOps` prepends it, so a seed elsewhere in the line is not this. */
private val ROLLED_SEED = Regex("""^seed (\d+)""")

/**
 ⭐ What a node type is CALLED on screen, as opposed to what it is identified by.
 *
 * ⚠⚠ The two parted company when the built-ins were namespaced: `sd.clip_encode`
 * is the stable id a workflow file and a plugin manifest write down, and
 * `clip_encode` is what fits on a node header. ⚠ It strips a plugin's id the
 * same way (`com.example.pack:Thing` -> `Thing`), which is what this replaced --
 * so there is now ONE rule for both instead of a `substringAfterLast(':')`
 * copied into the palette, the canvas and the id generator.
 */
val String.nodeLabel: String get() = substringAfterLast(':').substringAfterLast('.')
