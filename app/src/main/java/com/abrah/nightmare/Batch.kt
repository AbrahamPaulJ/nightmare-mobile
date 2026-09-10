package com.abrah.nightmare

/**
 * ⭐⭐ **Batching: run one graph N times with a map of param overrides.**
 *
 * ⚠⚠ Compose-free and Android-free on purpose, like `Framing.kt` and
 * `CanvasModel.kt`, and for the same reason: the expansion decides how many
 * renders a person is about to spend, and getting it wrong costs minutes rather
 * than throwing. Everything here is arithmetic over lists with a JVM test.
 *
 * ⭐ **A run MODE, not a graph change** (`docs/ARCHITECTURE.md` §8b). The graph
 * on the canvas is untouched — overrides go onto a copy per iteration, exactly
 * as `HarnessOps.runRolled` already copies for the rolled seed. That is what
 * makes the executor's cache do the heavy lifting for free: a 4-seed sweep
 * re-runs the sampler and everything after it, and reuses the photo, the crop,
 * the mask and the conditioning, because their keys never move.
 */

/** One param varied across a set of values. */
data class BatchAxis(
    val nodeId: String,
    val param: String,
    /** ⚠ Strings, because a param IS a string — see `Node.params`. */
    val values: List<String>,
)

/**
 * A sweep, as the run bar describes it.
 *
 * ⚠ Axes are combined as a CARTESIAN product: two axes of 4 and 2 are 8 runs,
 * not 4. That is the useful reading — "every seed at both cfgs" — and it is
 * also the one that gets expensive fastest, which is why [runCount] is shown
 * before anything starts.
 */
data class BatchSpec(val axes: List<BatchAxis> = emptyList()) {

    val runCount: Int get() = axes.fold(1) { n, a -> n * a.values.size.coerceAtLeast(1) }

    val isEmpty: Boolean get() = axes.isEmpty() || axes.any { it.values.isEmpty() }

    /**
     * ⭐⭐ Every combination, as `nodeId -> (param -> value)` maps.
     *
     * ⚠⚠ **The FIRST axis varies SLOWEST.** This is not cosmetic. §4's
     * pathological case is alternating a costly thing per iteration, and a
     * sweep is precisely where someone writes that by accident — so the axis a
     * user lists first, which is the one they think of as the outer loop, is
     * the outer loop. With `model` refused as an axis this cannot currently
     * cause a relaunch, but the ordering has to be right before it can.
     */
    fun expand(): List<Map<String, Map<String, String>>> {
        if (isEmpty) return emptyList()
        var out = listOf<Map<String, Map<String, String>>>(emptyMap())
        // ⚠ Reversed, then built inner-first, so the FIRST axis ends up
        // outermost in the emitted order.
        for (axis in axes.reversed()) {
            val next = mutableListOf<Map<String, Map<String, String>>>()
            for (v in axis.values) {
                for (base in out) {
                    val forNode = (base[axis.nodeId] ?: emptyMap()) + (axis.param to v)
                    next += base + (axis.nodeId to forNode)
                }
            }
            out = next
        }
        // The loop above emits the last axis fastest; reverse the pairing so
        // the first axis reads as the outer one.
        return out
    }

    /** How each run differs, for a log line and a Results label. */
    fun labelFor(overrides: Map<String, Map<String, String>>): String =
        axes.mapNotNull { a -> overrides[a.nodeId]?.get(a.param)?.let { "${a.param} $it" } }
            .joinToString("  ")

    companion object {
        /**
         * ⚠⚠ **Params that CANNOT be swept, and why it is a refusal rather than
         * a warning.** `model`, `width` and `height` are the context key: they
         * are bound at backend launch, so varying one costs a kill + relaunch
         * of 2.3-5 s per iteration — about a whole render each — and v1 pins
         * one context key for the whole graph (`ARCHITECTURE` §5). A sweep
         * across them does not run slowly, it refuses to run at all with
         * "needs 2 backend contexts", which describes our roadmap rather than
         * the user's graph.
         */
        val REFUSED = setOf("model", "width", "height")

        /** Why this axis cannot be swept, or null. */
        fun refusalFor(param: String): String? =
            if (param in REFUSED) {
                "\"$param\" is bound when the backend launches, so changing it " +
                    "costs a restart per run. Pick another parameter."
            } else null
    }
}

/**
 * ⭐ Parse what a person typed into a list of values.
 *
 * Accepts a comma list (`1, 2, 3`) or a range with an optional step
 * (`1..4`, `1..10 by 3`, `1.5..3 by 0.5`).
 *
 * ⚠⚠ Returns an EMPTY list rather than throwing on anything it does not
 * understand, and the caller shows the count — so "0 runs" is the feedback for
 * a typo, before the button can be pressed. A sweep that started and then
 * failed on iteration one would have spent a render to say the same thing.
 *
 * ⚠ Bounded. A range is one number away from a million iterations, and the
 * person typing it is doing so on a phone.
 */
object BatchValues {

    const val MAX_VALUES = 64

    fun parse(text: String): List<String> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val range = Regex("^(-?[0-9.]+)\\s*\\.\\.\\s*(-?[0-9.]+)(?:\\s*by\\s*(-?[0-9.]+))?$")
            .find(t)
        if (range != null) {
            val from = range.groupValues[1].toDoubleOrNull() ?: return emptyList()
            val to = range.groupValues[2].toDoubleOrNull() ?: return emptyList()
            val stepRaw = range.groupValues[3]
            val step = if (stepRaw.isEmpty()) 1.0 else stepRaw.toDoubleOrNull() ?: return emptyList()
            if (step <= 0.0 || to < from) return emptyList()
            // ⚠ Counted before it is built: `0.001` over `1..1000` is a million
            // strings, and allocating them to then reject the list is the wrong
            // order on a device with 12 GB and a 4096² render in flight.
            val n = ((to - from) / step).toInt() + 1
            if (n > MAX_VALUES) return emptyList()
            // ⚠ Whole numbers stay whole: a seed of "3.0" is not a seed.
            val whole = from % 1.0 == 0.0 && to % 1.0 == 0.0 && step % 1.0 == 0.0
            return (0 until n).map { i ->
                val v = from + step * i
                if (whole) v.toLong().toString() else trimNumber(v)
            }
        }
        val parts = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size > MAX_VALUES) emptyList() else parts
    }

    /** ⚠ `1.50` reads as a different value from `1.5` to a cache key. */
    private fun trimNumber(v: Double): String {
        val s = String.format(java.util.Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')
        return if (s.isEmpty() || s == "-") "0" else s
    }
}

/**
 * ⭐⭐ **Where a batch's pictures come from: the last IMAGE output nothing
 * consumes.**
 *
 * ⚠⚠ A RULE, not a list of node types (the user's call, 2026-09-10). "decode,
 * upscale, output" would need editing every time a node type is added, and a
 * plugin node that makes a final picture would never be collected — which is
 * the whole Tier 0 promise quietly not applying to a feature.
 *
 * ⚠⚠ Returns a LIST, and the caller must refuse rather than pick when there is
 * more than one. A graph with two unconsumed image nodes has no single answer,
 * and choosing silently is how a sweep collects the wrong picture for twenty
 * minutes — the exact failure shape `Framing.SizeDemand.Conflict` exists for.
 */
fun terminalImageNodes(graph: Graph, types: Map<String, NodeType>): List<String> {
    val consumed = graph.nodes.flatMap { n -> n.inputs.values.map { it.node } }.toSet()
    return graph.nodes
        .filter { n -> types[n.type]?.outputs?.any { it.type == "IMAGE" } == true }
        .filter { it.id !in consumed }
        .map { it.id }
}

/**
 * ⭐ What a sweep is about to cost, in the units a person decides with.
 *
 * ⚠⚠ From the LAST measured sample, not from a constant. A step count is not a
 * time — 8 steps and 30 steps differ by 3x on the same model — and a hardcoded
 * "about 15 s" would be wrong on every device but this one. Null when nothing
 * has been rendered yet, and then the sheet says the count alone rather than
 * inventing a duration.
 */
data class BatchEstimate(val runs: Int, val millis: Long?, val bytes: Long?)

fun estimateBatch(runs: Int, lastRunMillis: Long?, lastResultBytes: Long?) = BatchEstimate(
    runs = runs,
    millis = lastRunMillis?.let { it * runs },
    bytes = lastResultBytes?.let { it * runs },
)
