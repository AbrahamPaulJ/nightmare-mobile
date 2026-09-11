package com.abrah.nightmare.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.Outcome

/**
 * The node canvas: custom-drawn, because M3 has no opinion about port hit-boxes
 * or edge routing (docs/UI.md §1).
 *
 * ⚠ This file DRAWS and nothing else. Where things are, what a tap hits and
 * whether a wire is legal all live in `CanvasModel.kt`, which has no Compose
 * types in it and is tested on the JVM. Mixing the two would put the arithmetic
 * that actually breaks behind a screenshot.
 */

/**
 * ⭐⭐ The floor the LABEL FONT stops shrinking at — not a floor below which
 * labels vanish, which is what this used to be.
 *
 * ⚠⚠ Dropping the text at 0.45x was defended as "the graph is still navigable
 * by colour and shape", and on a phone it is not: zoom out far enough to see a
 * six-node workflow at once and every node becomes an anonymous grey box, so the
 * one view that shows the whole graph is the one view that cannot say which node
 * is which. Reported from the phone, 2026-09-09.
 *
 * ⇒ Below this the NODE keeps shrinking with the zoom and the TEXT does not.
 * The cost is that a label no longer scales with the box it sits in, which is
 * why every label below is measured against the room it actually has and
 * ellipsised into it, or dropped on its own when there is none.
 */
private const val LABEL_MIN_ZOOM = 0.5f

/** What the canvas knows about a node's last run, so it can say so on the node. */
data class NodeStatus(
    val outcome: Outcome? = null,
    /** step to total while this node is rendering, null otherwise. */
    val progress: Pair<Int, Int>? = null,
    val detail: String? = null,
)

/**
 * ⚠ Node CATEGORY is the only thing that earns a hue (docs/UI.md §1). Spending
 * colour anywhere else here would make the one signal that matters compete with
 * decoration.
 */
object CanvasColors {
    val background = Color(0xFF0B0B10)
    val grid = Color(0xFF16161F)
    val nodeBody = Color(0xFF1A1A24)
    val nodeStroke = Color(0xFF2A2A38)
    val selectedStroke = Color(0xFF9B6BFF)

    /**
     * ⭐ A selected node is drawn RAISED, and these are the two halves of it: a
     * lighter surface (the dark-theme convention for a thing nearer the viewer)
     * and a halo in the selection hue.
     *
     * ⚠ Not a violation of "category is the only thing that earns a hue" — the
     * halo is the SAME purple as the selection stroke it surrounds, so the
     * canvas still spends exactly one colour on state and one on category.
     */
    val selectedBody = Color(0xFF26263A)
    val selectedGlow = Color(0xFF9B6BFF)

    /** ⚠ Under a raised node. Only ever drawn against [background]. */
    val shadow = Color(0xFF000000)
    val title = Color(0xFFEDEDF2)
    val label = Color(0xFF9A9AA8)
    val edge = Color(0xFF4A4A5C)
    val edgeLive = Color(0xFF9B6BFF)
    val ran = Color(0xFF6BFF9B)
    val cached = Color(0xFF6BA8FF)
    val failed = Color(0xFFFF6B6B)

    /** Deterministic per category, so a shared screenshot means the same thing everywhere. */
    fun forCategory(category: String?): Color = when (category) {
        "sampling" -> Color(0xFFB07BFF)
        "latent" -> Color(0xFF7BC7FF)
        "image" -> Color(0xFF7BFFB0)
        "mask" -> Color(0xFFFFD37B)
        else -> Color(0xFF8A8A9A)
    }

    fun forType(portType: String): Color = when (portType) {
        "LATENT" -> Color(0xFF7BC7FF)
        "IMAGE" -> Color(0xFF7BFFB0)
        "COND" -> Color(0xFFFFB07B)
        else -> Color(0xFF8A8A9A)
    }
}

/**
 * A wire being dragged, before it lands.
 *
 * ⚠ Carries [error] so the canvas can show WHY a drop will be refused while the
 * finger is still down. Refusing silently on release is the worst outcome: the
 * user has already committed the gesture.
 */
data class PendingWire(val from: PortRef, val to: Pt, val error: String? = null)

@Composable
fun GraphCanvas(
    workflow: Workflow,
    types: Map<String, NodeType>,
    viewport: Viewport,
    modifier: Modifier = Modifier,
    /** The selected nodes. ⚠ A set: a long press starts a multi-selection. */
    selected: Set<String> = emptySet(),
    /** The wire the user picked, and whether its confirm is showing. */
    wire: WireRef? = null,
    wireConfirming: Boolean = false,
    status: Map<String, NodeStatus> = emptyMap(),
    pending: PendingWire? = null,
    /**
     * node id -> (image id, aspect). ⚠ Must be the SAME map the state uses for
     * hit-testing, or a node is drawn one height and tapped at another.
     */
    previews: Map<String, Pair<String, Float>> = emptyMap(),
    /** Resolves an image id to pixels. Null while the bitmap is not resident. */
    imageFor: (String) -> ImageBitmap? = { null },

) {
    val measurer = rememberTextMeasurer()
    Box(modifier.background(CanvasColors.background)) {
        Canvas(Modifier.fillMaxSize()) {
            // ⚠ Geometry in device pixels, fonts in sp. See Viewport.forDevice.
            val vp = viewport.forDevice(density)
            drawGrid(vp)
            val boxes = layout(workflow, types, previews)
            val byId = boxes.associateBy { it.id }

            // Edges first, so a node always sits on top of its own wires.
            for (box in boxes) {
                box.node.inputs.forEach { (portName, src) ->
                    val up = byId[src.node] ?: return@forEach
                    val portIndex = box.inputs.indexOfFirst { it.name == portName }
                    if (portIndex < 0) return@forEach
                    // ⚠ The wire's OWN output port. A bare source (no port named)
                    // is the sole-output case and draws from dot 0; an unknown
                    // name also falls back to 0 rather than dropping the wire,
                    // because a graph the executor will refuse by name must still
                    // be VISIBLE -- an edge that silently vanished would leave the
                    // user looking at two unconnected nodes and no explanation.
                    val outIndex = src.port
                        ?.let { p -> up.outputs.indexOfFirst { it.name == p } }
                        ?.takeIf { it >= 0 } ?: 0
                    // ⚠ A picked wire is drawn hot, so the controls that appear
                    // over it are visibly attached to something.
                    val picked = wire?.toNode == box.id && wire.toPort == portName
                    drawEdge(
                        vp.toScreen(up.outputPort(outIndex)),
                        vp.toScreen(box.inputPort(portIndex)),
                        if (picked) CanvasColors.edgeLive else CanvasColors.edge,
                        vp.scale * (if (picked) 1.6f else 1f),
                    )
                }
            }

            pending?.let { p ->
                drawEdge(
                    vp.toScreen(p.from.at),
                    vp.toScreen(p.to),
                    if (p.error == null) CanvasColors.edgeLive else CanvasColors.failed,
                    vp.scale,
                )
            }

            // ⚠ Selected nodes LAST, so a raised node is not overpainted by the
            // flat one beside it. `sortedBy` is stable, so everything else keeps
            // the graph's own order. A halo half-covered by its neighbour is
            // exactly how a "pop out" stops reading as one.
            for (box in boxes.sortedBy { if (it.id in selected) 1 else 0 }) {
                drawNode(box, vp, viewport.scale, measurer, box.id in selected,
                    status[box.id], imageFor)
            }

            // ⭐⭐ The picked wire's controls, LAST, so they sit over every node
            // -- they are transient and they are what the finger is aiming at.
            wire?.let { w ->
                val ends = wires(boxes).firstOrNull { it.first.id == w.id }?.second
                if (ends != null) {
                    val mid = vp.toScreen(wireMidpoint(ends.first, ends.second))
                    if (wireConfirming) {
                        // ⚠⚠ The tick takes the delete mark's EXACT place, which
                        // is what makes a double tap on the middle of a wire
                        // delete it. The cancel is offset, so the destructive tap
                        // repeats and the safe one is a deliberate move.
                        drawWireButton(mid, CanvasColors.ran, vp.scale, Glyph.TICK)
                        drawWireButton(
                            Pt(mid.x + Sizes.WIRE_BUTTON_GAP * vp.scale, mid.y),
                            CanvasColors.label, vp.scale, Glyph.CROSS,
                        )
                    } else {
                        drawWireButton(mid, CanvasColors.failed, vp.scale, Glyph.BIN)
                    }
                }
            }
        }
    }
}

/**
 * ⚠ Drawn in SCREEN space with the world positions transformed, rather than by
 * scaling the whole canvas. A `scale()` would multiply stroke widths and text
 * with everything else, so a zoomed-out graph would have hairline borders and
 * unreadable labels — the two things that must stay constant.
 */
private fun DrawScope.drawNode(
    box: NodeBox,
    /** Device-pixel viewport: geometry only. */
    viewport: Viewport,
    /** The LOGICAL zoom, for font sizes — `sp` applies density itself. */
    zoom: Float,
    measurer: TextMeasurer,
    selected: Boolean,
    status: NodeStatus?,
    imageFor: (String) -> ImageBitmap?,
) {
    val tl = viewport.toScreen(box.topLeft)
    val w = box.width * viewport.scale
    val h = box.height * viewport.scale
    val corner = Sizes.CORNER * viewport.scale

    // ⭐⭐ A selected node POPS OUT of the canvas: a shadow cast below it, a halo
    // around it, and a lighter body under everything that follows.
    //
    // ⚠⚠ This is the whole signal that multi-select is ON, so it has to survive
    // being glanced at. A 3px stroke did not: the stroke says "this node", the
    // lift says "the canvas is in a mode", and only the second one is legible
    // from arm's length on a graph zoomed out to 0.55x.
    //
    // ⚠ Every pixel of it is spent OUTSIDE the body, never on making the body
    // bigger — `Sizes.SELECT_LIFT` says why.
    if (selected) {
        val lift = Sizes.SELECT_LIFT * viewport.scale
        drawRoundRect(
            color = CanvasColors.shadow.copy(alpha = 0.55f),
            topLeft = Offset(tl.x + lift * 0.35f, tl.y + lift),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )
        // Two rings rather than one: a single hard outline reads as a second
        // border, two of falling alpha read as a glow.
        for ((grow, alpha) in listOf(lift to 0.10f, lift * 0.5f to 0.22f)) {
            drawRoundRect(
                color = CanvasColors.selectedGlow.copy(alpha = alpha),
                topLeft = Offset(tl.x - grow, tl.y - grow),
                size = Size(w + 2 * grow, h + 2 * grow),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    corner + grow, corner + grow,
                ),
            )
        }
    }

    drawRoundRect(
        color = if (selected) CanvasColors.selectedBody else CanvasColors.nodeBody,
        topLeft = Offset(tl.x, tl.y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
    )

    // The category stripe: the one place colour is spent.
    val headerH = Sizes.HEADER_HEIGHT * viewport.scale
    clipRect(Rect(tl.x, tl.y, tl.x + w, tl.y + headerH)) {
        drawRoundRect(
            color = CanvasColors.forCategory(box.type?.category).copy(alpha = 0.22f),
            topLeft = Offset(tl.x, tl.y),
            size = Size(w, headerH * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        )
    }

    // ⭐ The picture the node is showing, drawn INSIDE its body at the image's
    // own aspect ratio (the box was laid out to fit it, so no stretch).
    box.preview?.let { p ->
        val bmp = imageFor(p.imageId)
        val pad = Sizes.BODY_PADDING * viewport.scale
        val top = viewport.toScreen(Pt(box.topLeft.x, box.previewTop))
        val pw = w - 2 * pad
        val ph = p.height * viewport.scale
        val r = androidx.compose.ui.geometry.CornerRadius(6f * viewport.scale, 6f * viewport.scale)
        if (bmp == null) {
            // ⚠ A placeholder rather than nothing: a node laid out with room for
            // a picture and no picture in it reads as a broken render, when in
            // fact the graph has simply not been run yet.
            drawRoundRect(
                color = CanvasColors.nodeStroke,
                topLeft = Offset(tl.x + pad, top.y),
                size = Size(pw, ph),
                cornerRadius = r,
            )
        } else {
            clipPath(androidx.compose.ui.graphics.Path().apply {
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        Rect(tl.x + pad, top.y, tl.x + pad + pw, top.y + ph), r,
                    )
                )
            }) {
                drawImage(
                    image = bmp,
                    srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                    srcSize = androidx.compose.ui.unit.IntSize(bmp.width, bmp.height),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (tl.x + pad).toInt(), top.y.toInt(),
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(pw.toInt(), ph.toInt()),
                )
            }
        }
    }

    // ⚠ The resize corner, drawn so the handle is discoverable. Without a mark
    // the only way to learn a node can be resized is to be told.
    val grip = 5f * viewport.scale
    for (i in 1..2) {
        drawCircle(
            color = CanvasColors.label,
            radius = grip * 0.35f,
            center = Offset(tl.x + w - i * grip * 1.6f, tl.y + h - grip * 1.6f),
        )
    }

    drawRoundRect(
        color = when {
            selected -> CanvasColors.selectedStroke
            status?.outcome == Outcome.FAILED -> CanvasColors.failed
            else -> CanvasColors.nodeStroke
        },
        topLeft = Offset(tl.x, tl.y),
        size = Size(w, h),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(corner, corner),
        style = Stroke(width = if (selected) 3f else 1.5f),
    )

    // ⚠⚠ Text scales with the viewport DOWN TO A FLOOR, and both halves of
    // that were bought the hard way. The first version clamped at 0.8x, so a
    // node drawn at 0.55x carried 0.8x text and every label overflowed its box.
    // The second dropped labels below 0.45x entirely, which turned the
    // zoomed-out view -- the only one that shows a whole workflow -- into a
    // field of unlabelled rectangles.
    //
    // ⇒ [LABEL_MIN_ZOOM] is the floor, and the overflow it would otherwise
    // cause is handled where it happens instead: every label is measured against
    // the room it has, ellipsised into it, and dropped only when there is none.
    val textZoom = maxOf(zoom, LABEL_MIN_ZOOM)
    val pad = 8f * viewport.scale
    val inset = 12f * viewport.scale
    // ⚠⚠ Never negative, and never zero. `drawText(measurer, string, ...)`
    // derives its own constraints from the canvas and asks for a negative width
    // for anything near the right edge -- the `maxWidth(-40) must be >=
    // minWidth(0)` crash that took a golden out on 2026-09-08. Measuring first
    // against a positive constraint is what avoids it.
    val room = (w - 2 * inset).toInt().coerceAtLeast(1)

    val titleStyle = TextStyle(
        color = CanvasColors.title,
        fontSize = (14f * textZoom).sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.SansSerif,
    )
    // ⚠ Measured, not guessed from the font size. A baseline computed from
    // `fontSize` put the title half outside the stripe. ⚠ Ellipsised to the
    // node's own width, which is what makes a floored font safe: below the floor
    // the type no longer shrinks with the node, so without this a long id would
    // run out over the canvas.
    val title = measurer.measure(
        box.node.id, titleStyle,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = room),
    )
    drawText(title, topLeft = Offset(tl.x + inset, tl.y + pad))

    // ⚠ The type sits INSIDE the header, under the title. In the body it
    // shared a line with the first port's label, and those two are at the same
    // height by construction -- body padding could never separate them.
    // ⚠⚠ …so it is dropped when the header has no room left for it, rather
    // than drawn over the first port's name. The title is the half that
    // survives: a node's id is what you are looking for, and its type is
    // readable from the stripe colour and the ports.
    val subtitle = measurer.measure(
        box.node.type.nodeLabel,
        TextStyle(
            color = CanvasColors.label,
            fontSize = (10f * textZoom).sp,
            fontFamily = FontFamily.Monospace,
        ),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = room),
    )
    if (pad + title.size.height + subtitle.size.height <=
        Sizes.HEADER_HEIGHT * viewport.scale
    ) {
        drawText(subtitle, topLeft = Offset(tl.x + inset, tl.y + pad + title.size.height))
    }

    // ⭐⭐ The node's own text, drawn in its body — each field in its own BOX
    // with its caption, mirroring the inspector's text fields.
    //
    // ⚠⚠ Both captions always, even for an empty value. Drawing only what was
    // typed made an empty negative vanish, so a node with one prompt filled in
    // looked like a node that HAS one field. Reported from the phone
    // 2026-09-11.
    //
    // ⚠ Display only -- never hit-tested. That is what makes this cheap where
    // the batch thumbnail strip is not (`docs/ROADMAP.md`): a tappable body
    // needs hit-testing against the same viewport transform the pan/zoom uses,
    // and text needs none of it.
    box.prose?.let { prose ->
        var y = viewport.toScreen(Pt(box.topLeft.x, box.proseTop)).y
        val bodyW = (w - 2 * Sizes.BODY_PADDING * viewport.scale)
            .toInt().coerceAtLeast(1)
        val boxPad = Sizes.PROSE_BOX_PAD * viewport.scale
        // ⚠ The node's own line budget -- what the vertical resize sets.
        val maxLines = prose.maxLines
        for ((field, value) in prose.fields) {
            val cap = measurer.measure(
                field,
                TextStyle(
                    color = CanvasColors.label,
                    fontSize = (9f * textZoom).sp,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = bodyW),
            )
            drawText(cap, topLeft = Offset(tl.x + inset, y))
            y += cap.size.height

            // ⚠⚠ A POSITIVE constraint, always -- the `maxWidth(-40)` crash
            // that took a golden out on 2026-09-08 came from letting drawText
            // derive its own near the canvas edge.
            val inner = (bodyW - 2 * boxPad).toInt().coerceAtLeast(1)
            val body = measurer.measure(
                // ⚠ A placeholder rather than nothing, so an empty box still
                // has a line's height and reads as "empty" instead of "broken".
                value.ifBlank { "—" },
                TextStyle(
                    color = if (value.isBlank()) CanvasColors.label else CanvasColors.title,
                    fontSize = (10f * textZoom).sp,
                    fontFamily = FontFamily.Monospace,
                ),
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = inner),
            )
            // ⚠ The box is sized from the MEASURED text, so it never clips the
            // glyphs it contains -- and the node's height was computed from the
            // same line count, so the two agree.
            val boxH = body.size.height + 2 * boxPad
            drawRoundRect(
                color = CanvasColors.label.copy(alpha = 0.30f),
                topLeft = Offset(tl.x + inset, y),
                size = Size(bodyW.toFloat(), boxH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                    6f * viewport.scale, 6f * viewport.scale,
                ),
                style = Stroke(width = 1f * viewport.scale),
            )
            drawText(body, topLeft = Offset(tl.x + inset + boxPad, y + boxPad))
            y += boxH + boxPad
        }
    }

    // ⚠ A port name is dropped only when two of them would collide: the rows
    // are PORT_SPACING apart in world units, and at a low enough zoom a floored
    // font is taller than that gap.
    val portRoom = Sizes.PORT_SPACING * viewport.scale
    box.inputs.forEachIndexed { i, port ->
        drawPort(viewport.toScreen(box.inputPort(i)), port.type, viewport.scale)
        drawPortLabel(measurer, viewport.toScreen(box.inputPort(i)), port.name,
            viewport.scale, textZoom, true, w, portRoom)
    }
    box.outputs.forEachIndexed { i, port ->
        drawPort(viewport.toScreen(box.outputPort(i)), port.type, viewport.scale)
        drawPortLabel(measurer, viewport.toScreen(box.outputPort(i)), port.name,
            viewport.scale, textZoom, false, w, portRoom)
    }

    status?.let { drawStatus(it, tl, w, h, viewport.scale) }

    // ⭐ The message, UNDER the node rather than inside it. A node's body is
    // sized for its ports, so text inside would either be clipped to nothing or
    // force every node in the graph to be as tall as the worst error in it.
    if (status?.outcome == Outcome.FAILED) {
        val note = failureNote(status.detail)
        val style = TextStyle(
            color = CanvasColors.failed,
            fontSize = (10f * textZoom).sp,
            fontFamily = FontFamily.Monospace,
        )
        // ⚠⚠ A positive constraint, always. `drawText(measurer, string, …)`
        // derives its own from the canvas and asks for a negative width for
        // anything near the right edge -- the `maxWidth(-40)` crash that took a
        // golden out on 2026-09-08. Measuring first is what avoids it, and the
        // width here is the node's, so the note wraps to the node.
        val measured = measurer.measure(
            note, style,
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = w.toInt().coerceAtLeast(1)),
        )
        drawText(measured, topLeft = Offset(tl.x, tl.y + h + 4f * viewport.scale))
    }
}

/** The three marks a picked wire can carry. */
private enum class Glyph { BIN, TICK, CROSS }

/**
 * One of a wire's controls: a filled disc with a mark on it.
 *
 * ⚠⚠ Drawn on the canvas rather than composed as a button, because it has to
 * follow a curve through world space that only this layer knows. ⚠ Its hit-box
 * is `Sizes.WIRE_BUTTON_RADIUS` in `CanvasState`, and the two must agree — a
 * control drawn one size and tapped at another is the silent kind of broken.
 */
private fun DrawScope.drawWireButton(at: Pt, color: Color, scale: Float, glyph: Glyph) {
    val r = Sizes.WIRE_BUTTON_RADIUS * scale
    drawCircle(CanvasColors.background, radius = r, center = Offset(at.x, at.y))
    drawCircle(color, radius = r, center = Offset(at.x, at.y), style = Stroke(width = 2.5f))
    val k = r * 0.42f
    val w = 2.5f
    when (glyph) {
        Glyph.BIN -> {
            // A lid and a body: readable at a glance, and no font needed.
            drawLine(color, Offset(at.x - k, at.y - k * 0.7f), Offset(at.x + k, at.y - k * 0.7f), w)
            drawLine(color, Offset(at.x - k * 0.6f, at.y - k * 0.7f), Offset(at.x - k * 0.45f, at.y + k), w)
            drawLine(color, Offset(at.x + k * 0.6f, at.y - k * 0.7f), Offset(at.x + k * 0.45f, at.y + k), w)
            drawLine(color, Offset(at.x - k * 0.45f, at.y + k), Offset(at.x + k * 0.45f, at.y + k), w)
        }
        Glyph.TICK -> {
            drawLine(color, Offset(at.x - k, at.y), Offset(at.x - k * 0.2f, at.y + k * 0.8f), w)
            drawLine(color, Offset(at.x - k * 0.2f, at.y + k * 0.8f), Offset(at.x + k, at.y - k * 0.8f), w)
        }
        Glyph.CROSS -> {
            drawLine(color, Offset(at.x - k, at.y - k), Offset(at.x + k, at.y + k), w)
            drawLine(color, Offset(at.x + k, at.y - k), Offset(at.x - k, at.y + k), w)
        }
    }
}

private fun DrawScope.drawPort(at: Pt, type: String, scale: Float) {
    drawCircle(
        color = CanvasColors.forType(type),
        radius = Sizes.PORT_RADIUS * scale,
        center = Offset(at.x, at.y),
    )
    drawCircle(
        color = CanvasColors.background,
        radius = Sizes.PORT_RADIUS * scale * 0.45f,
        center = Offset(at.x, at.y),
    )
}

private fun DrawScope.drawPortLabel(
    measurer: TextMeasurer,
    at: Pt,
    name: String,
    /** Device pixels, for the offset from the port. */
    scale: Float,
    /** Logical zoom, floored at [LABEL_MIN_ZOOM], for the font. */
    zoom: Float,
    isInput: Boolean,
    /** The node's width on screen, so a name is ellipsised rather than run out over it. */
    nodeWidth: Float,
    /** How much vertical room this row has before it meets its neighbour. */
    rowHeight: Float,
) {
    val style = TextStyle(
        color = CanvasColors.label,
        fontSize = (10f * zoom).sp,
        fontFamily = FontFamily.Monospace,
    )
    // ⚠ Half the node less the inset the label already sits at: the two
    // sides' labels share the width and must not meet in the middle.
    val room = (nodeWidth / 2f - 16f * scale).toInt().coerceAtLeast(1)
    val measured = measurer.measure(
        name, style,
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        constraints = Constraints(maxWidth = room),
    )
    // ⚠ Dropped rather than drawn on top of the next port's name.
    if (measured.size.height > rowHeight) return
    val x = if (isInput) at.x + 14f * scale else at.x - 14f * scale - measured.size.width
    drawText(measured, topLeft = Offset(x, at.y - measured.size.height / 2f))
}

/**
 * ⭐ Per-node state, on the node. §4 asks for exactly this rather than a spinner
 * over the whole graph: the backend streams per-step progress and the executor
 * knows which nodes it skipped, so a graph that showed one indeterminate bar
 * would be throwing both away.
 */
private fun DrawScope.drawStatus(
    status: NodeStatus,
    tl: Pt,
    w: Float,
    h: Float,
    scale: Float,
) {
    val color = when (status.outcome) {
        Outcome.RAN -> CanvasColors.ran
        Outcome.CACHED -> CanvasColors.cached
        Outcome.FAILED -> CanvasColors.failed
        Outcome.BLOCKED -> CanvasColors.label
        null -> CanvasColors.label
    }
    val barY = tl.y + h - 6f * scale
    status.progress?.let { (step, total) ->
        val frac = if (total > 0) step.toFloat() / total else 0f
        drawRoundRect(
            color = CanvasColors.grid,
            topLeft = Offset(tl.x + 10f * scale, barY),
            size = Size(w - 20f * scale, 4f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale, 2f * scale),
        )
        drawRoundRect(
            color = CanvasColors.edgeLive,
            topLeft = Offset(tl.x + 10f * scale, barY),
            size = Size((w - 20f * scale) * frac, 4f * scale),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * scale, 2f * scale),
        )
        return
    }
    // A dot rather than a word: at a glance the user wants "did this run", and
    // four labels on four nodes is more text than the graph can carry.
    drawCircle(
        color = color,
        radius = 4f * scale,
        center = Offset(tl.x + w - 14f * scale, tl.y + 14f * scale),
    )
}

/**
 * ⚠ A cubic, not a straight line, and the control points are horizontal.
 * Straight wires between stacked nodes overlap into an unreadable bundle; the
 * horizontal tangent is what makes an edge visibly leave one port and arrive at
 * another.
 */
private fun DrawScope.drawEdge(from: Pt, to: Pt, color: Color, scale: Float) {
    val dx = ((to.x - from.x) * 0.5f).coerceAtLeast(40f * scale)
    val path = Path().apply {
        moveTo(from.x, from.y)
        cubicTo(from.x + dx, from.y, to.x - dx, to.y, to.x, to.y)
    }
    drawPath(path, color, style = Stroke(width = 2.5f * scale.coerceAtLeast(0.6f)))
}

/**
 * ⚠ The grid is what makes a pan visible. Without it, dragging empty space on a
 * flat background looks like nothing happened until a node scrolls into view.
 */
private fun DrawScope.drawGrid(viewport: Viewport) {
    val step = 48f * viewport.scale
    if (step < 8f) return   // too dense to read, and expensive to draw
    val startX = viewport.offset.x % step
    val startY = viewport.offset.y % step
    var x = startX
    while (x < size.width) {
        drawLine(CanvasColors.grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = startY
    while (y < size.height) {
        drawLine(CanvasColors.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private inline fun DrawScope.clipRect(rect: Rect, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(rect)
    block()
    drawContext.canvas.restore()
}
