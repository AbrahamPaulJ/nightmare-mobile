package com.abrah.nightmare

/**
 * **What shape a picture has to come out as, and where the frame that makes it
 * lands on the source.**
 *
 * ⚠⚠ Compose-free and Android-free on purpose, like `CanvasModel.kt` and
 * `Previews.kt`, and for the sharpest version of the same reason: *a framing
 * that is off by a factor renders the wrong region of the photo and never
 * throws.* The editor would look right and the output would be wrong, and no
 * screenshot of either shows it. Everything here is arithmetic over floats with
 * a JVM test; `CropNode` and `CropEditor` convert at the boundary.
 */

/**
 * The crop frame in SOURCE PIXELS.
 *
 * ⚠⚠ It may lie partly outside the bitmap, and that is the point — see
 * [CropGeometry.needsPadding]. `android.graphics.Rect` would tempt someone to
 * clamp it, and clamping silently re-frames the crop instead of padding it.
 */
data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width() = right - left
    fun height() = bottom - top
}

object CropGeometry {

    /** ⚠ A frame narrower than this in source pixels is a rounding artefact. */
    private const val MIN_PX = 1f

    /** The normalised rect, on a source of [srcW] x [srcH]. */
    fun frameOf(x: Float, y: Float, w: Float, h: Float, srcW: Int, srcH: Int): Frame {
        val fw = (w * srcW).coerceAtLeast(MIN_PX)
        val fh = (h * srcH).coerceAtLeast(MIN_PX)
        val fx = x * srcW
        val fy = y * srcH
        return Frame(fx, fy, fx + fw, fy + fh)
    }

    /**
     * The output's pixel size.
     *
     * ⚠⚠ `0` means **"the framed region at its own size"**, which is what an
     * unwired crop produces: nothing is resampled that nobody asked to be. A
     * consumer that demands a size overwrites both, and then this is simply that
     * size. ⚠ Capped, because a 0-derived size comes from a rect a finger drew
     * and a silly one would allocate a bitmap nothing can hold.
     */
    fun outputSize(outW: Int, outH: Int, frame: Frame, max: Int): Pair<Int, Int> =
        if (outW > 0 && outH > 0) {
            outW.coerceIn(1, max) to outH.coerceIn(1, max)
        } else {
            Math.round(frame.width()).coerceIn(1, max) to
                Math.round(frame.height()).coerceIn(1, max)
        }

    /**
     * ⭐⭐ Would this frame have to ENLARGE the picture to fill the output?
     *
     * That is the one condition under which the frame is allowed outside the
     * bitmap. A photo big enough to cover the demanded size must cover it — bars
     * around a picture that had the pixels all along would be a mistake, not a
     * choice. A photo that is genuinely too small has only two honest answers,
     * and padding is the one that does not quietly turn it soft.
     */
    fun needsPadding(srcW: Int, srcH: Int, outW: Int, outH: Int): Boolean =
        outW > 0 && outH > 0 && (srcW < outW || srcH < outH)

    /**
     * The smallest scale the editor may zoom out to, mapping source pixels to
     * viewport pixels.
     *
     * ⚠⚠ Two floors, and the SMALLER wins:
     *  - **cover** — the picture fills the frame. This is the floor for any
     *    picture big enough, and it is what stops a crop taking in bars of
     *    nothing it has no way to render.
     *  - **1:1** — one source pixel per output pixel. Below this the crop would
     *    be enlarging, so for a picture too small to cover, this is where the
     *    zoom stops and the remainder becomes padding.
     *
     * For a big photo `cover < 1:1`, so the result is `cover` and nothing about
     * the old behaviour changes. For a small one `1:1 < cover`, and the picture
     * is allowed to sit inside the frame with bars around it.
     *
     * ⚠ [outW] is 0 when nothing demands a size — then the output IS the framed
     * pixels, no enlargement is possible by construction, and cover is the floor.
     */
    fun minScale(
        viewW: Float, viewH: Float, imgW: Float, imgH: Float, outW: Int,
    ): Float {
        if (imgW <= 0f || imgH <= 0f) return 1f
        val cover = maxOf(viewW / imgW, viewH / imgH)
        if (outW <= 0) return cover
        return minOf(cover, viewW / outW)
    }
}

// ---------------------------------------------------------------------------
// What a consumer demands of the node feeding it
// ---------------------------------------------------------------------------

/** What the graph says [requiredOutputSize] must produce. */
sealed interface SizeDemand {

    /** Nothing downstream cares. */
    data object None : SizeDemand

    /** @param by the node ids demanding it, for the message that explains the lock. */
    data class Exactly(val width: Int, val height: Int, val by: List<String>) : SizeDemand {
        /** ⚠ Named, because a locked widget with no reason is worse than no widget. */
        fun reason() = "${by.joinToString(" and ")} needs ${width}x$height"
    }

    /**
     * Two consumers want different sizes and one node cannot satisfy both.
     *
     * ⚠⚠ Its own case rather than "pick the first". Picking would render a
     * plausible picture for one branch and a wrong one for the other, with
     * nothing on screen admitting a choice had been made.
     */
    data class Conflict(val demands: List<Pair<String, Pair<Int, Int>>>) : SizeDemand {
        fun reason() = "wanted at " + demands.joinToString(" and ") { (who, wh) ->
            "${wh.first}x${wh.second} by $who"
        }
    }
}

/**
 * ⭐⭐ What [nodeId]'s output must be, according to everything that consumes it.
 *
 * This is the "smart" half of the cropper: the node does not look downstream —
 * nodes cannot — the CANVAS does, and writes the answer into the node's params.
 */
fun requiredOutputSize(
    graph: Graph,
    types: Map<String, NodeType>,
    nodeId: String,
): SizeDemand {
    val demands = mutableListOf<Pair<String, Pair<Int, Int>>>()
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        for ((port, src) in n.inputs) {
            if (src.node != nodeId) continue
            val want = runCatching { t.requiredInputSize(n, port) }.getOrNull() ?: continue
            demands += n.id to want
        }
    }
    if (demands.isEmpty()) return SizeDemand.None
    val distinct = demands.map { it.second }.distinct()
    if (distinct.size > 1) return SizeDemand.Conflict(demands)
    return SizeDemand.Exactly(distinct[0].first, distinct[0].second, demands.map { it.first })
}

/**
 * ⭐⭐ Why this wire may not land, on size grounds — or null.
 *
 * ⚠⚠ The refusal is about a PROMISE, not a measurement. `load_image` outputs
 * whatever the user photographed, so it cannot be checked; it can only be
 * declined. Saying "512x512 required, got 3024x4032" would be worse than
 * useless — it would be wrong for the next photo.
 *
 * ⚠ Computed while the finger is still down (`CanvasState.refusal`). Finding out
 * on Run, after the wait, is the failure this exists to remove.
 */
fun sizeRefusal(
    graph: Graph,
    types: Map<String, NodeType>,
    fromNode: String,
    toNode: String,
    toPort: String,
): String? {
    val consumer = graph.byId[toNode] ?: return null
    val consumerType = types[consumer.type] ?: return null
    val want = runCatching { consumerType.requiredInputSize(consumer, toPort) }.getOrNull()
        ?: return null
    val producer = graph.byId[fromNode] ?: return null
    val producerType = types[producer.type] ?: return null

    // ⚠ A node whose own size is derived from its consumers is normally
    // allowed: wiring it here is precisely what tells it what to make.
    // ⚠⚠ …unless something else already told it something different. One
    // node cannot make two sizes, and refusing the wire that would create the
    // contradiction is far kinder than letting the graph hold one and finding
    // out at Run which branch lost.
    if (producerType.sizedByConsumer) {
        val already = requiredOutputSize(graph, types, fromNode)
        // ⚠ A graph that is ALREADY contradictory -- reached by hand-editing a
        // file, since the canvas refuses to build one -- must not quietly accept
        // a third wire on top of it.
        if (already is SizeDemand.Conflict) {
            return "$fromNode is already ${already.reason()}"
        }
        if (already is SizeDemand.Exactly &&
            (already.width to already.height) != want &&
            toNode !in already.by
        ) {
            return "$fromNode already makes ${already.width}x${already.height} " +
                "for ${already.by.joinToString(" and ")}; " +
                "$toNode needs ${want.first}x${want.second}"
        }
        return null
    }

    val promise = runCatching { producerType.outputSize(producer) }.getOrNull()
        ?: return "$toNode needs ${want.first}x${want.second} and $fromNode " +
            "cannot promise a size -- put a Crop between them"
    if (promise != want) {
        return "$toNode needs ${want.first}x${want.second}, " +
            "$fromNode makes ${promise.first}x${promise.second}"
    }
    return null
}

/**
 * ⭐⭐ Every consumer-derived size, settled to a FIXED POINT.
 *
 * ⚠⚠ **Iterated, and that is the whole point of this function.** A demand is
 * computed from the graph as it stands, so one pass can only settle a chain one
 * link deep. `crop -> vae_encode` is one link and worked; the inpaint recipe
 * added `crop -> mask -> latent_blend`, where `mask` is itself
 * [NodeType.sizedByConsumer] — and in a single pass the crop reads the mask's
 * STALE size, disagrees with `vae_encode`'s fresh one, and resolves to a
 * [SizeDemand.Conflict].
 *
 * A conflict promises nothing (0), which makes the crop emit at its own size,
 * which makes everything downstream the wrong size. Measured 2026-09-10 as
 * "switching to SDXL always degrades the output": the sizes never reached 1024,
 * and no amount of adjusting sampler knobs could have helped because the knobs
 * were never the problem.
 *
 * ⚠ Bounded rather than `while (true)`. A genuinely contradictory graph — two
 * consumers of one node wanting different sizes — never settles, and it must
 * come out as a conflict the user is told about rather than as a hang. The
 * bound is generous: it is the length of the longest possible chain.
 */
fun deriveSizes(graph: Graph, types: Map<String, NodeType>): Graph {
    var current = graph
    // ⚠ Each round can settle one more link, so the node count is a hard
    // ceiling on how many are ever needed.
    repeat(graph.nodes.size.coerceAtLeast(1)) {
        var next = current
        for (n in current.nodes) {
            if (types[n.type]?.sizedByConsumer != true) continue
            // ⚠⚠ Against `next`, NOT against the original: reading a stale
            // sibling is the bug this function exists to fix.
            val demand = requiredOutputSize(next, types, n.id)
            // ⚠ A conflict resolves to 0 -- "promise nothing" -- rather than to
            // one of the two answers. Picking would render a plausible picture
            // for one branch and a wrong one for the other, silently.
            val (w, h) = when (demand) {
                is SizeDemand.Exactly -> demand.width to demand.height
                else -> 0 to 0
            }
            val node = next.byId[n.id] ?: continue
            if (node.params["out_w"] == w.toString() && node.params["out_h"] == h.toString()) {
                continue
            }
            next = next.withParams(n.id, mapOf("out_w" to w.toString(), "out_h" to h.toString()))
        }
        // Settled: another round would change nothing.
        if (next === current) return current
        current = next
    }
    return current
}

/**
 * ⭐⭐ Nodes whose promised size does not match what something demands of them,
 * named in a sentence — or an empty list when the graph is coherent.
 *
 * ⚠⚠ **This exists because a size mismatch is SILENT and looks like a quality
 * problem.** A `crop` that promises nothing emits at its source's own size; the
 * sampler still runs, the decoder still returns a picture, and what the user
 * sees is a smeared render they reasonably blame on the model, the step count
 * or the checkpoint. Three separate wrong diagnoses came out of that on
 * 2026-09-10 — steps, feather, and the blend — before the sizes were checked.
 *
 * ⇒ Run says which node is wrong, in the units the user can act on, BEFORE the
 * 26 seconds of sampling that would otherwise produce the mystery.
 *
 * ⚠ A warning rather than a refusal: a graph can be mid-edit, and a user who
 * wants to render something odd should be allowed to. What must not happen is
 * rendering it and saying nothing.
 */
fun sizeMismatches(graph: Graph, types: Map<String, NodeType>): List<String> {
    val out = mutableListOf<String>()
    for (n in graph.nodes) {
        val t = types[n.type] ?: continue
        if (t.sizedByConsumer != true) continue
        val demand = requiredOutputSize(graph, types, n.id)
        val w = n.params["out_w"]?.toIntOrNull() ?: 0
        val h = n.params["out_h"]?.toIntOrNull() ?: 0
        when (demand) {
            is SizeDemand.Conflict ->
                out += "\"${n.id}\" is ${demand.reason()}, so it promises nothing " +
                    "and will emit at its source's own size"
            is SizeDemand.Exactly ->
                if (w != demand.width || h != demand.height) {
                    out += "\"${n.id}\" is set to ${w}x$h but ${demand.by.joinToString(" and ")} " +
                        "needs ${demand.width}x${demand.height}"
                }
            else -> Unit
        }
    }
    return out
}
