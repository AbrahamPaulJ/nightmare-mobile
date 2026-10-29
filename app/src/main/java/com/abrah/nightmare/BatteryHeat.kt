package com.abrah.nightmare

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ⭐⭐ **Heat on a video render** (the user, 2026-10-30: "a proper warning for all video models about heating, it can
 * damage phones"; a tester, Saved #7354: lithium cells wear fast above ~45 °C while the chip itself is fine at 100).
 * A video render keeps the NPU busy for minutes, so the run log says the battery's temperature when it is already
 * warm, and says so again when the clip pushed it high. With [Prefs.heatPause] on, the render WAITS between steps
 * from [PAUSE_AT] until it cools to [RESUME_AT] (backend 051's `/pause`). An app cannot set clocks; waiting is what
 * it can do. The battery's own sensor (`ACTION_BATTERY_CHANGED`, no permission), the one the tester asked for.
 */
object BatteryHeat {

    const val WARN_AT = 40.0
    const val PAUSE_AT = 45.0
    const val RESUME_AT = 40.0
    private const val POLL_MS = 10_000L

    /** The battery's temperature in °C, or null where the phone does not say. */
    fun celsius(context: Context): Double? = runCatching {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t == Int.MIN_VALUE || t <= -400) null else t / 10.0
    }.getOrNull()

    /** ⚠ Pure: whether to be paused at [c] °C, given [paused] now (a gap between the two edges, so it never flaps). */
    fun nextPaused(paused: Boolean, c: Double): Boolean = if (paused) c > RESUME_AT else c >= PAUSE_AT

    fun format(c: Double): String = String.format(java.util.Locale.ROOT, "%.0f", c)

    /** ⭐ The run log's warning before a video render when the battery is already warm; the reading, or null. */
    fun warnIfWarm(context: Context, ctx: NodeCtx): Double? {
        val start = celsius(context)
        if (start != null && start >= WARN_AT) {
            ctx.warn(ctx.text(R.string.run_heat_start,
                "The battery is at %1\$s °C before this clip. Long video renders heat the phone, and heat wears the battery: keep it off the charger and out of the sun, and stop if it gets hot to hold. Settings, Performance can pause a render while the battery is hot",
                format(start)))
        }
        return start
    }

    /** ⭐ The run log's warning after a render that took the battery to [PAUSE_AT] ([peak] the hottest reading). */
    fun warnIfHot(peak: Double?, ctx: NodeCtx) {
        if (peak != null && peak >= PAUSE_AT) {
            ctx.warn(ctx.text(R.string.run_heat_end,
                "The battery reached %1\$s °C during this clip. Let the phone cool before the next one",
                format(peak)))
        }
    }

    /**
     * ⭐ Runs [render] (one video clip, all its parts) while watching the battery: a warning before it when the battery
     * is already at [WARN_AT], the pause when [pause] is on, and a warning after it when the clip took the battery
     * to [PAUSE_AT]. [setPaused] is backend 051's `/pause`; it is sent again on every poll while paused, so a part
     * that starts a new request (which clears the pause) is held too. Always left unpaused.
     */
    suspend fun <T> watch(
        context: Context,
        pause: Boolean,
        ctx: NodeCtx,
        setPaused: suspend (Boolean) -> Unit,
        render: suspend () -> T,
    ): T {
        val start = warnIfWarm(context, ctx)
        var peak = start ?: 0.0
        var paused = false
        return try {
            coroutineScope {
                val watcher = launch {
                    while (true) {
                        delay(POLL_MS)
                        val c = celsius(context) ?: continue
                        peak = maxOf(peak, c)
                        if (!pause) continue
                        val next = nextPaused(paused, c)
                        if (next && !paused) {
                            ctx.warn(ctx.text(R.string.run_heat_paused,
                                "Paused: the battery is at %1\$s °C. The clip carries on when it cools to %2\$s °C",
                                format(c), format(RESUME_AT)))
                        } else if (!next && paused) {
                            ctx.say("  battery at ${format(c)} °C: carrying on")
                        }
                        if (next || paused) runCatching { setPaused(next) }
                        paused = next
                    }
                }
                try {
                    render()
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            if (paused) runCatching { setPaused(false) }
            warnIfHot(peak, ctx)
        }
    }
}
