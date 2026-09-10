package com.abrah.nightmare

/**
 * ⭐⭐ **Which knobs are armed for a sweep, stored ON the node.**
 *
 * ⚠⚠ In the node's params, deliberately (the user's call, 2026-09-10): a saved
 * flow then carries its sweep, sharing a flow shares the sweep, and it survives
 * a restart — all for free, because `WorkflowIo` already round-trips params
 * verbatim. The seed lock works the same way and for the same reason: it IS a
 * param, so there is one copy of the fact.
 *
 * ⚠⚠ The one thing that had to be handled for that to be safe: a `batch:` param
 * is **excluded from the cache key** (`Graph.cacheKey`). It records a RANGE, not
 * a value, and changes nothing about the picture the node renders — so leaving
 * it in the key would make arming a sweep re-render the sampler and everything
 * after it, spending 15 s to change an icon's colour.
 *
 * ⚠ Compose-free and Android-free, like `Batch.kt` and `Framing.kt`: this
 * decides how many renders a person spends, and it is arithmetic with a test.
 */
object BatchParams {

    /** ⚠ A `:` cannot appear in a widget name, so this can never shadow one. */
    const val PREFIX = "batch:"

    /**
     * ⚠⚠ Ten per axis, and it is a hard cap rather than advice. At the measured
     * ~11.5 s sample, ten is two minutes; the grid below is what makes that
     * matter.
     */
    const val MAX_PER_AXIS = 10

    /**
     * ⚠⚠ TWO axes, so a grid is at most 10x10 = 100 renders — about 20 minutes
     * and ~150 MB of kept results. That is the user's chosen ceiling and it is
     * deliberately shown as an estimate before anything starts, not enforced
     * downward: the person spending the time gets to see the number.
     */
    const val MAX_AXES = 2

    /**
     * ⭐ The params a sweep may vary today.
     *
     * ⚠ A short explicit list rather than "anything numeric". Everything here
     * is a SAMPLER knob: they are what "try a few" means, they all re-run the
     * same node, and none of them touches the context key. ⚠ `scheduler` is in
     * the list and is NOT a range — it is a choice of options, so its control is
     * a multi-select. [isRange] is the test.
     */
    val ALLOWED = mapOf(
        "sd.sample" to listOf("seed", "steps", "cfg", "denoise", "scheduler"),
    )

    /**
     * ⭐⭐ **How a knob is swept, per knob.** This is what makes the popup
     * sliders rather than a text box.
     *
     * ⚠⚠ `step` is the knob's OWN natural increment — the smallest move that
     * means anything for that parameter. `cfg` moves in 0.1, `steps` in whole
     * numbers, `denoise` in 0.05. The popup lets a user make the step BIGGER
     * (in multiples of this) and never smaller: a sweep at 0.01 across cfg's
     * 1..20 is 1900 renders, and the cap would silently refuse it after the
     * typing rather than before.
     *
     * ⚠ `int` decides how a value is written back: a seed or a step count of
     * "3.0" is not a seed or a step count.
     */
    data class Sweep(val min: Double, val max: Double, val step: Double, val int: Boolean)

    /**
     * ⚠ Falls back to the widget's own bounds when a knob is not listed, so a
     * new batchable param works before anyone remembers to add it here — with a
     * step of 1 for an int and a hundredth of the range otherwise.
     */
    fun sweepFor(param: String, widgetMin: Double?, widgetMax: Double?, isInt: Boolean): Sweep =
        when (param) {
            // ⚠ `cfg` 1..20 in tenths: the checkpoint recipes publish 1.5 and
            // 7.5, so halves are not fine enough to land on a model's own value.
            "cfg" -> Sweep(1.0, 20.0, 0.1, false)
            // ⚠ Whole steps only. "12.5 steps" is not a thing the sampler can do.
            "steps" -> Sweep(1.0, 50.0, 1.0, true)
            // ⚠ 0.05 across 0..1: twentieths. Finer than that is below what a
            // denoise visibly changes, and 0..1 by 0.01 is 101 renders.
            "denoise" -> Sweep(0.0, 1.0, 0.05, false)
            // ⚠⚠ Seeds 1..10, whole numbers. Small deliberately: a seed has no
            // magnitude, so the numbers themselves mean nothing — what matters
            // is that there are several of them and that the same sweep gives
            // the same pictures again.
            SEED -> Sweep(1.0, 10.0, 1.0, true)
            else -> {
                val lo = widgetMin ?: 0.0
                val hi = widgetMax ?: 1.0
                Sweep(lo, hi, if (isInt) 1.0 else ((hi - lo) / 100.0), isInt)
            }
        }

    const val SEED = "seed"

    /**
     * ⚠⚠ **Nothing is swept by "count" any more.** The seed was, briefly: its
     * values were N placeholder zeros that `runRolled` turned into fresh random
     * seeds per iteration. That worked, but it made every card's label useless
     * ("seed 0" ×5) and it made a sweep unreproducible — rerunning gave five
     * different pictures again. ⇒ The seed is an ordinary RANGE now (the user's
     * call, 2026-09-10): seeds 1..5 are seeds 1, 2, 3, 4 and 5, the same five
     * pictures every time, each card labelled with the seed that made it.
     */
    fun isCount(param: String) = false

    /**
     * ⚠⚠ **Two is the smallest sweep.** One value is not a batch — it is the
     * knob's ordinary value with extra ceremony — and allowing it would put a
     * "1 run" batch in the run bar that does exactly what Run already does.
     * The user's call, 2026-09-10.
     */
    const val MIN_PER_AXIS = 2

    fun isBatchable(nodeType: String, param: String): Boolean =
        ALLOWED[nodeType]?.contains(param) == true

    /**
     * ⚠ Three shapes, not two: a numeric RANGE (a from/to/step slider), an
     * option SET (`scheduler`, a multi-select) and a COUNT (`seed`).
     */
    fun isRange(param: String): Boolean = param != "scheduler"

    fun keyFor(param: String) = PREFIX + param

    /** The stored spec for [param] on [node], or null when it is not armed. */
    fun armed(node: Node, param: String): String? =
        node.params[keyFor(param)]?.takeIf { it.isNotBlank() }

    /** Every armed axis on a graph, in graph order. ⚠ Node id, param, values. */
    fun axesOf(graph: Graph): List<BatchAxis> =
        graph.nodes.flatMap { n ->
            n.params.entries
                .filter { it.key.startsWith(PREFIX) && it.value.isNotBlank() }
                .mapNotNull { (k, v) ->
                    val param = k.removePrefix(PREFIX)
                    val values = valuesOf(param, v)
                    if (values.isEmpty()) null else BatchAxis(n.id, param, values)
                }
        }

    /**
     * ⚠ The stored string parsed back into values.
     *
     * A range uses [BatchValues]; an option set is a plain comma list, so a
     * scheduler never goes near the range grammar (`dpm..euler` is nonsense).
     */
    fun valuesOf(param: String, spec: String): List<String> {
        val out = if (isRange(param)) {
            BatchValues.parse(spec)
        } else {
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        // ⚠ Outside [MIN_PER_AXIS]..[MAX_PER_AXIS] is "not a sweep", not a
        // clamped one: silently running two when nine were asked for is worse
        // than refusing and saying why.
        return if (out.size !in MIN_PER_AXIS..MAX_PER_AXIS) emptyList() else out
    }

    /**
     * ⭐⭐ Why this spec will not do, or null — the validation the popup shows
     * and the run refuses on.
     *
     * ⚠⚠ Said in the popup rather than at Run. A sweep that is refused after
     * the button is pressed has already made the user wait to be told something
     * that was knowable while they were typing.
     */
    fun refusalFor(param: String, spec: String, alreadyArmed: Int): String? {
        // ⚠⚠ RAW, uncapped. [valuesOf] returns an empty list past the cap, so
        // computing the refusal from it would report "not a list or a range"
        // for eleven perfectly readable values — hiding the one number the
        // user needs in order to fix it. Caught by a test, 2026-09-10.
        val values = if (isRange(param)) {
            BatchValues.parse(spec)
        } else {
            spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return when {
            spec.isBlank() -> null
            values.isEmpty() && isRange(param) ->
                "not a list or a range — try 1, 2, 3 or 1..20 by 2"
            values.size == 1 -> "one value is not a sweep — pick at least $MIN_PER_AXIS"
            values.isEmpty() -> "pick at least $MIN_PER_AXIS"
            values.size > MAX_PER_AXIS ->
                "${values.size} values — at most $MAX_PER_AXIS per knob" +
                    (if (isRange(param)) ", so widen the step" else "")
            alreadyArmed >= MAX_AXES ->
                "$MAX_AXES knobs can be swept at once — release one first"
            else -> null
        }
    }

    /** ⚠ Every armed axis multiplied out. 0 when nothing is armed. */
    fun runCount(graph: Graph): Int =
        axesOf(graph).fold(0) { acc, a -> if (acc == 0) a.values.size else acc * a.values.size }
}
