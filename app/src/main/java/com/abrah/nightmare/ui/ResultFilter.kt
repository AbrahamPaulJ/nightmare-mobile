package com.abrah.nightmare.ui

import com.abrah.nightmare.ResultTags
import com.abrah.nightmare.canvas.Result
import java.time.Instant
import java.time.ZoneId

/** ⭐ A yes/no question a filter may leave open: upscaled, from a picture, with a reference. */
enum class Tri { ANY, YES, NO }

/** ⭐ Pictures, clips or both. */
enum class ResultKind { ANY, PICTURES, CLIPS }

/**
 * ⭐⭐⭐ **The Results filter, as data** (GitHub #117, the user's call 2026-11-14: every filter asked for).
 *
 * Pure, so the rules below are JVM-tested ([com.abrah.nightmare.ui.ResultFilterTest]) and the sheet only edits one.
 *
 * ⚠⚠ The list filters hold what is UNTICKED, never what is ticked: every value starts shown, and a model, sampler or
 * size that appears for the first time after the sheet was set up must start shown too.
 * ⚠⚠ A result that does not KNOW a fact (an Upscale flow has no sampler, a picture kept before 1.6.163 has no time) is
 * never hidden by a LIST filter (unticking Euler means "not Euler"), but a RANGE the user narrowed asks for a value,
 * and a result without one is not in it.
 */
data class ResultFilter(
    val hiddenModels: Set<String> = emptySet(),
    /** Searches the positive prompt and the sweep label. */
    val prompt: String = "",
    val negative: String = "",
    val seed: String = "",
    val hiddenSamplers: Set<String> = emptySet(),
    /** Schedule suffixes (`""` = Normal, `_karras`...), [com.abrah.nightmare.ModelCatalog.DIT_SCHEDULES]. */
    val hiddenSchedules: Set<String> = emptySet(),
    /** `"1024x768"`. */
    val hiddenSizes: Set<String> = emptySet(),
    /** Local dates as epoch days, both inclusive; null = open. */
    val fromDay: Long? = null,
    val toDay: Long? = null,
    val steps: IntRange? = null,
    val cfg: ClosedFloatingPointRange<Double>? = null,
    /** The whole flow's time, in whole seconds. */
    val seconds: IntRange? = null,
    val upscaled: Tri = Tri.ANY,
    val fromImage: Tri = Tri.ANY,
    val reference: Tri = Tri.ANY,
    val kind: ResultKind = ResultKind.ANY,
) {
    /** ⭐ The funnel's dot and the ✕ beside it: something is not shown. */
    val active: Boolean get() = this != ResultFilter()

    fun matches(r: Result, t: ResultTags, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (t.models.isNotEmpty() && t.models.none { it !in hiddenModels }) return false
        if (prompt.isNotBlank() && !(t.prompt + " " + r.batchLabel).contains(prompt.trim(), ignoreCase = true)) return false
        if (negative.isNotBlank() && !t.negative.contains(negative.trim(), ignoreCase = true)) return false
        if (seed.isNotBlank() && r.seed?.contains(seed.trim()) != true) return false
        if (t.samplers.isNotEmpty() && t.samplers.none { it !in hiddenSamplers }) return false
        if (t.schedules.isNotEmpty() && t.schedules.none { it !in hiddenSchedules }) return false
        if (r.width > 0 && sizeOf(r) in hiddenSizes) return false
        if (fromDay != null || toDay != null) {
            val day = dayOf(r.savedAt, zone)
            if (fromDay != null && day < fromDay) return false
            if (toDay != null && day > toDay) return false
        }
        steps?.let { if (t.steps == null || t.steps !in it) return false }
        cfg?.let { if (t.cfg == null || t.cfg < it.start - 1e-6 || t.cfg > it.endInclusive + 1e-6) return false }
        seconds?.let { s -> val sec = secondsOf(r) ?: return false; if (sec !in s) return false }
        if (!upscaled.allows(t.upscaled)) return false
        if (!fromImage.allows(t.fromImage)) return false
        if (!reference.allows(t.reference)) return false
        return when (kind) {
            ResultKind.ANY -> true
            ResultKind.PICTURES -> r.videoPath == null
            ResultKind.CLIPS -> r.videoPath != null
        }
    }

    companion object {
        /** ⚠ The key [com.abrah.nightmare.canvas.Result.times] keeps the whole flow's time under (HarnessViewModel's). */
        const val TOTAL_MS_KEY = "@@total"

        fun sizeOf(r: Result) = "${r.width}x${r.height}"

        fun dayOf(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

        /** ⚠ Rounded UP, so a 0.4 s Upscale is 1 s and a range starting at 1 holds it. */
        fun secondsOf(r: Result): Int? = r.times[TOTAL_MS_KEY]?.let { ((it + 999) / 1000).toInt() }
    }
}

private fun Tri.allows(v: Boolean) = when (this) {
    Tri.ANY -> true
    Tri.YES -> v
    Tri.NO -> !v
}

/**
 * ⭐ What the sheet offers: every value the kept results hold, and the bounds of each range. Nothing invented, so a
 * box is never a value no picture has.
 */
data class ResultFilterOptions(
    val models: List<String>,
    val samplers: List<String>,
    val schedules: List<String>,
    val sizes: List<String>,
    val steps: IntRange?,
    val cfg: ClosedFloatingPointRange<Double>?,
    val seconds: IntRange?,
    val hasClips: Boolean,
) {
    companion object {
        fun of(all: List<Result>, tagsOf: (Result) -> ResultTags): ResultFilterOptions {
            val tags = all.map(tagsOf)
            fun ints(xs: List<Int>) = if (xs.isEmpty()) null else xs.min()..xs.max()
            val cfgs = tags.mapNotNull { it.cfg }
            return ResultFilterOptions(
                models = tags.flatMap { it.models }.distinct().sorted(),
                samplers = tags.flatMap { it.samplers }.distinct().sorted(),
                schedules = tags.flatMap { it.schedules }.distinct().sorted(),
                // ⚠ Biggest first: what a person scanning for "the 1024 ones" looks for.
                sizes = all.filter { it.width > 0 }.sortedByDescending { it.width.toLong() * it.height }
                    .map { ResultFilter.sizeOf(it) }.distinct(),
                steps = ints(tags.mapNotNull { it.steps }),
                cfg = if (cfgs.isEmpty()) null else cfgs.min()..cfgs.max(),
                seconds = ints(all.mapNotNull { ResultFilter.secondsOf(it) }),
                hasClips = all.any { it.videoPath != null },
            )
        }
    }
}
