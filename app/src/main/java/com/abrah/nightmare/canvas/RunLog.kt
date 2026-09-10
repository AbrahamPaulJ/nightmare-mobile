package com.abrah.nightmare.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.Outcome
import com.abrah.nightmare.ui.LogTextStyle

/** ⭐ A knob armed for a sweep, as the run bar shows it. */
data class ArmedSweep(
    val nodeId: String,
    val param: String,
    val count: Int,
    /** ⭐ The actual values, so the bar can show them rather than just a count. */
    val values: List<String> = emptyList(),
)

/** One finished node, as the run log shows it. */
data class RunLine(val id: String, val text: String, val bad: Boolean = false)

/**
 * ⭐ The canvas's seed, as the run bar shows it.
 *
 * ⚠ [value] null means "rolls a new one each Run", which is a state worth
 * naming rather than an absence — the row says "seed: random" for it. The whole
 * object is null only when the graph has no sampler, i.e. when there is no such
 * thing as its seed.
 *
 * ⚠ [lastRolled] is what a LOCK would pin. Without it the lock has nothing to
 * offer on a canvas that has not run yet, and the control must then be absent
 * rather than doing nothing.
 */
data class SeedState(val value: String?, val lastRolled: String? = null)

/**
 * ⭐⭐ What the canvas shows WHILE it renders.
 *
 * ⚠⚠ It exists because a Run was previously silent for its whole duration. The
 * only live signal was a progress bar a few dp wide drawn inside the node, and
 * on SDXL a single `sample` is **24 seconds** — long enough that a person with
 * no feedback concludes the button did nothing and presses it again. The node
 * bar is easy to miss even when you know where to look, and it says nothing at
 * all about the nodes that are not the sampler.
 *
 * ⚠ [startedAtMs] is `SystemClock.elapsedRealtime`, NOT wall clock: the
 * elapsed time must not jump when the clock is corrected or a timezone changes.
 * 0 means idle.
 *
 * ⚠ [totalMs] is set when the run finishes and [startedAtMs] returns to 0, so
 * the panel can keep showing what the last run cost instead of blanking the
 * moment it is over — the number a user most wants is the one they get after
 * they stop watching.
 */
data class RunLogState(
    val lines: List<RunLine> = emptyList(),
    /** The node executing right now, or null when nothing is. */
    val now: String? = null,
    /**
     * How far through [now] the backend says it is, as done-of-total.
     *
     * ⚠⚠ **NOT the user's step count, and it must not be shown as one.** The
     * backend's denominator counts pipeline PHASES:
     * `steps + (img2img ? 1 : 0) + 2` — the +2 being the CLIP encode and the
     * VAE decode — and for img2img it then subtracts the steps `denoise` skips.
     * So a 20-step txt2img reports `22`, and a 20-step img2img at denoise 0.65
     * reports `16`. Both were seen on the phone and both read as a bug in the
     * app, because a user compares the number against the one they typed.
     *
     * ⇒ Rendered as a PERCENTAGE. The fraction is exactly right whatever the
     * backend counts, where any attempt to recover "step 14 of 20" means
     * re-deriving `Pipeline.hpp`'s phase accounting in Kotlin — a guess that
     * silently rots the next time the backend adds a stage.
     */
    val step: Pair<Int, Int>? = null,
    val startedAtMs: Long = 0L,
    val totalMs: Long? = null,
) {
    val running: Boolean get() = startedAtMs > 0L
    val idle: Boolean get() = !running && lines.isEmpty() && totalMs == null
}

/** `1.2s` / `24.3s` / `1m04s` — short enough to sit in a status line. */
fun formatMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "%dm%02ds".format(ms / 60_000, (ms % 60_000) / 1000)
}

/**
 * The log panel, sitting directly above the Run button.
 *
 * ⚠ Above the button rather than over the canvas: the canvas is the user's
 * work and a panel floating on top of it hides the very nodes whose progress it
 * is describing.
 */
@Composable
fun RunLogPanel(
    state: RunLogState,
    /**
     * ⚠ Dismiss the panel. It lingers after a run on purpose — the total is the
     * number you want once you have stopped watching — but "on purpose" is not
     * "forever", and it sits over the canvas's bottom edge.
     */
    onClose: () -> Unit = {},
    /**
     * ⭐⭐ The seed pinned onto the sampler, or null when it rolls each Run.
     *
     * ⚠⚠ It is HERE rather than only on the picture because a locked seed
     * changes what Run does, silently and indefinitely. A user who locked one
     * yesterday and comes back to "why is it making the same thing" has nothing
     * to look at otherwise — the number lives in an inspector field they have no
     * reason to open.
     */
    /**
     * ⭐⭐ The seed state of the canvas: a number when pinned, null when it
     * rolls, and **absent only when there is no sampler at all**.
     *
     * ⚠⚠ Shown whether locked or not. A user cannot tell "this makes a new
     * picture every Run" from "this makes the same one" by looking at the
     * canvas, and that is the single most confusing thing about Run — so the
     * state is stated always, and the toggle is the same control either way.
     */
    seed: SeedState? = null,
    onToggleSeed: () -> Unit = {},
    /** ⭐ How many seeds a sweep will roll, or null when seed is not armed. */
    seedSweep: Int? = null,
    /** ⚠ A swept seed needs its own release — the lock toggle is hidden. */
    onReleaseSeedSweep: () -> Unit = {},
    /**
     * ⭐⭐ The knobs armed for a sweep, as `node param values` triples.
     *
     * ⚠⚠ Here, beside the seed, for exactly the reason the seed row is here: an
     * armed sweep changes what Run does, silently and indefinitely. A user who
     * armed `cfg` yesterday and comes back to "why does Run take twenty
     * minutes" has nothing to look at otherwise — the range lives in an
     * inspector field they have no reason to open.
     */
    armed: List<ArmedSweep> = emptyList(),
    onRelease: (ArmedSweep) -> Unit = {},
    /** ⭐ Which run of how many, while a sweep is going. Null when it is not. */
    batch: Pair<Int, Int>? = null,
    onCancelBatch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ⚠ The seed row keeps this panel up with nothing else to report: it is a
    // property of the canvas rather than of the last run, and the panel is the
    // only thing above Run that persists between them.
    if (state.idle && seed == null && armed.isEmpty() && batch == null) return

    // ⚠⚠ A ticking clock, not a value pushed from the view model. The elapsed
    // time has to advance while NOTHING is happening -- an SDXL sampler emits
    // one progress event per ~1.2 s, so a timer driven by those events would
    // sit frozen for over a second at a stretch and read as a hang, which is
    // the exact impression this panel exists to prevent.
    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.startedAtMs) {
        while (state.running) {
            nowMs = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(100)
        }
    }
    val elapsed = when {
        state.running -> (nowMs - state.startedAtMs).coerceAtLeast(0)
        else -> state.totalMs ?: 0L
    }

    // ⚠⚠ **TWO containers, not one.** The seed row is a property of the CANVAS
    // — it is true between runs and survives closing the log — and the panel
    // below it describes ONE run. Drawing them on a single ground said they
    // were the same thing, so closing the run log left a lone seed row sitting
    // in a box that still looked like a log. Asked for from the phone,
    // 2026-09-10: detach the seed from the rest.
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        seed?.let { st ->
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠⚠ ONE line, and a TIGHT one. The first version of this row wrapped
            // a sentence of explanation and took three lines of a bar that sits
            // over the canvas, so an always-present row has to cost one line at
            // most. The state is the number and the glyph; the meaning is
            // learned once.
                Text(
                    // ⚠⚠ A batch sweep outranks both other states: "random" is
                    // wrong when four specific seeds are about to run, and a
                    // lock cannot coexist with a sweep of the same knob.
                    //
                    // ⚠⚠⚠ This line was "fixed" once and silently was not: the
                    // edit targeted a `stringResource` form this file never had,
                    // so the replace missed and the script still reported
                    // success. Reported from the phone twice. ⇒ An edit that
                    // cannot find its anchor must FAIL, not carry on.
                    when {
                        seedSweep != null -> "Batching " + seedSweep + " seeds"
                        st.value != null -> "seed locked: ${st.value}"
                        else -> "seed: random"
                    },
                    style = LogTextStyle,
                    color = if (st.value != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // ⚠ A real glyph, not an emoji: an emoji renders in the system
                // font at whatever weight and colour that font decides, so it
                // cannot be tinted with the row it belongs to and lands
                // differently on every device.
                // ⚠⚠ A ✕ while swept, the padlock otherwise. Two different
                // actions must not share one glyph: releasing a sweep and
                // locking a seed are opposites, and the row has space for one.
                if (seedSweep != null) {
                    IconButton(onClick = onReleaseSeedSweep, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "release the seed sweep",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                if (seedSweep == null) {
                IconButton(
                    onClick = onToggleSeed,
                    // ⚠ Shrunk from the 48dp default. A touch target that size
                    // sets the height of the whole row, which is what made this
                    // line cost vertical space above AND below the text.
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (st.value != null) Icons.Filled.Lock
                        else com.abrah.nightmare.ui.LockOpenIcon,
                        contentDescription = if (st.value != null) {
                            "release the seed, so every Run makes a new picture"
                        } else {
                            "lock the last seed, so every Run makes the same picture"
                        },
                        tint = if (st.value != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(17.dp),
                    )
                }
                }
            }
        }
        // ⚠⚠ **Only when there is a run to describe.** Closing the panel clears
        // the state, but the seed row above is a property of the CANVAS rather
        // than of a run, so the panel stays up — and this row then rendered
        // "done  0ms" over a run that had been dismissed, describing nothing.
        // Reported from the phone, 2026-09-10. `idle` is exactly "no lines, no
        // total, not running", which is the condition under which there is
        // nothing here worth a row.
        // ⭐⭐ One chip per armed sweep, with a ✕ that releases it. ⚠ Its own
        // container, like the seed: it is a property of the CANVAS and it
        // survives closing the run log.
        if (armed.isNotEmpty()) {
            // ⚠⚠⚠ **ONE ROW PER SWEEP, and the text is WEIGHTED.**
            //
            // It was one Row holding a joined string plus one ✕ per sweep, and
            // the Text carried no `weight` — so the moment the label wrapped to
            // a second line it took the full width and pushed every ✕ outside
            // the row's bounds. The release button simply vanished. Reported
            // from the phone, 2026-09-10.
            //
            // ⚠ Per-row is also the honest shape: each ✕ now sits beside the
            // sweep it releases, where a row of identical ✕s after a merged
            // sentence could not say which was which.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                for (a in armed) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Batching " + a.count + " " + a.param +
                                (if (a.values.isEmpty()) "" else ": " + a.values.joinToString(", ")),
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.primary,
                            // ⚠ THE fix: the label may take every line it needs
                            // and the button keeps its own space regardless.
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { onRelease(a) },
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "release the " + a.param + " batch",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
                // ⚠ The product, only when there is one to state.
                if (armed.size > 1) {
                    Text(
                        "= " + armed.fold(1) { n, a -> n * a.count } + " runs",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ⭐⭐ The live sweep, in a container of its OWN — above the run and
        // below the locked knobs.
        //
        // ⚠⚠ It used to sit above the whole panel, which put it above the seed
        // and the armed chips. That reads as a heading for them rather than as
        // a status for the run underneath. The order that means something is:
        // what is ARMED (a property of the canvas), then what is HAPPENING (this
        // sweep), then what the current render is doing. Asked for from the
        // phone, 2026-09-10.
        batch?.let { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.batch_progress, p.first, p.second),
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onCancelBatch) {
                    Text(stringResource(R.string.batch_stop), fontSize = 12.sp)
                }
            }
        }
        // ⭐ The RUN's own container: everything below describes one render —
        // the status row, the PROGRESS BAR and the per-node lines together.
        // ⚠ The bar belongs with the lines it is describing; drawn outside the
        // container it read as a separate widget that happened to be nearby.
        if (!state.idle) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    // ⚠ A percentage, not "step n/m" -- see [RunLogState.step].
                    state.now != null && state.step != null && state.step.second > 0 ->
                        "${state.now}  ${state.step.first * 100 / state.step.second}%"
                    state.now != null -> "${state.now}…"
                    // ⚠ Named as finished rather than left showing the last
                    // node, or a completed run reads as one still in progress.
                    else -> "done"
                },
                style = LogTextStyle,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // ⭐ Live while running, and the TOTAL once it is not -- which
                    // is the number worth keeping, and the one a user asks for
                    // after the fact.
                    formatMs(elapsed),
                    style = LogTextStyle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⭐⭐ COPY the whole log. ⚠ It copies every line the panel
                // holds, not the handful on screen: the reason to copy a run
                // log is to paste it into a bug report, and a copy that
                // silently dropped what had scrolled away would make the
                // report describe less than the reader saw. Asked for from the
                // phone, 2026-09-10.
                // ⚠⚠ Only once the run is OVER. Mid-render these two are
                // noise: there is nothing complete to copy, and "hide" competes
                // for the eye with the progress it would hide. Asked for from
                // the phone, 2026-09-10.
                if (!state.running && state.lines.isNotEmpty()) {
                    val clipboard = LocalClipboardManager.current
                    IconButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(logText(state, elapsed)))
                        },
                        modifier = Modifier.size(24.dp),
                    ) {
                        Icon(
                            com.abrah.nightmare.ui.CopyIcon,
                            contentDescription = "copy the run log",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // ⚠⚠ NOT offered while running. Closing never cancelled — it
                // was "stop showing me" — but a ✕ beside a live progress bar
                // reads as "stop", and the one thing worse than a control that
                // does nothing is one that looks like it aborts a 24 s render.
                if (!state.running) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(24.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "hide the run log",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        }

        // ⭐ Full width and 6dp tall. The per-node bar it replaces was a few dp
        // wide inside a node body at whatever the canvas zoom happened to be,
        // which at 0.4x is a smear. ⚠ Indeterminate when a node is running but
        // reporting no steps: `vae_decode` takes 2 s on SDXL and has nothing to
        // count, and a bar pinned at 0% for two seconds looks like a stall.
        if (state.running) {
            val p = state.step
            if (p != null && p.second > 0) {
                LinearProgressIndicator(
                    progress = { p.first.toFloat() / p.second },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
        }

        // ⚠ Newest LAST, so the panel reads downwards like a log and the line
        // that just appeared is next to the status row it belongs under.
        //
        // ⚠⚠ **SCROLLABLE and WRAPPED, and both are corrections.** This showed
        // the last four lines, each clipped to one line with an ellipsis — so a
        // failure whose message was the whole point ("failed — node \"encode\":
        // image img_… is no longer in the store") was cut at the width of a
        // phone, and the four-line cap silently dropped the earlier nodes of any
        // graph bigger than four. Asked for from the phone, 2026-09-10: *do not
        // cut a line off, put it on the next one, and let me scroll.*
        //
        // ⇒ Bounded by HEIGHT rather than by line count. That is what keeps Run
        // on screen mid-render — the reason the cap existed — while letting one
        // long line take the two rows it needs instead of losing its tail.
        val lines = state.lines.takeLast(MAX_LINES)
        if (lines.isNotEmpty()) {
            val scroll = rememberScrollState()
            // ⚠ Follows the tail, so a running graph shows the node it is on
            // without the user chasing it — but only from the BOTTOM, so
            // scrolling back to read an earlier failure is not yanked away by
            // the next line landing.
            LaunchedEffect(state.lines.size) {
                if (scroll.value >= scroll.maxValue - AUTOSCROLL_SLACK) {
                    scroll.animateScrollTo(scroll.maxValue)
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = LINES_MAX_HEIGHT)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for (l in lines) {
                    Text(
                        "${l.id}  ${l.text}",
                        style = LogTextStyle,
                        fontSize = 11.sp,
                        // ⚠ No maxLines and no ellipsis: wrapping is the whole
                        // point. A log that hides its own tail is worse than one
                        // that costs a row.
                        color = if (l.bad) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        }
        }
    }
}

/**
 * The whole log as plain text, for the clipboard.
 *
 * ⚠ The TOTAL goes in too. "done 21.4s" is the first thing anyone asks about a
 * run, and it lives in the status row rather than in a line — so a copy of the
 * lines alone would leave it out of every report.
 */
private fun logText(state: RunLogState, elapsedMs: Long): String = buildString {
    append(if (state.running) "running" else "done")
    appendLine("  " + formatMs(elapsedMs))
    state.lines.forEach { appendLine(it.id + "  " + it.text) }
}

/**
 * ⚠ How tall the scrolling line area may get, above the run bar.
 *
 * ⚠⚠ A HEIGHT, not a line count. Lines wrap now, so "four lines" stopped being
 * a bound on anything — one long failure message is two or three rows on a
 * phone. 112dp is roughly six 11sp rows, which leaves the run bar and the
 * navigation bar their space at the shortest orientation this app runs in.
 */
private val LINES_MAX_HEIGHT = 112.dp

/**
 * ⚠ How far off the bottom still counts as "following the tail", in pixels.
 * Exactly-at-the-bottom is a test that fails on a fractional scroll position.
 */
private const val AUTOSCROLL_SLACK = 24

/**
 * ⚠ The tail kept in the panel. Raised from 4 now that the area scrolls and is
 * bounded by height instead: a graph of eight nodes had its first four silently
 * dropped, which is exactly where a failure starts. The whole history is still
 * in the harness log.
 */
private const val MAX_LINES = 40

/** How a finished node reads in the log. */
fun runLineOf(id: String, outcome: Outcome, ms: Long, detail: String): RunLine = RunLine(
    id = id,
    text = when (outcome) {
        // ⚠⚠ The DETAIL is shown, not dropped. It used to be kept only for a
        // failure, so a successful run said "ran 12ms" and a reused one said
        // "cached" — and neither carried the one fact that mattered when a
        // render came out wrong: the size the node actually produced.
        //
        // A whole session went into "switching model degrades the output" while
        // the run bar was reporting, truthfully and uselessly, that everything
        // ran. `frame ran 1.2s · 3024x4032` would have ended it in one glance.
        Outcome.RAN -> "ran ${formatMs(ms)}" + brief(detail)
        // ⚠ Especially here. "cached" alone hides WHAT was reused, and a
        // wrongly-reused value is exactly the failure that looks like a bad
        // model rather than a stale cache.
        Outcome.CACHED -> "cached" + brief(detail)
        Outcome.FAILED -> "failed — $detail"
        Outcome.BLOCKED -> "blocked — $detail"
    },
    bad = outcome == Outcome.FAILED || outcome == Outcome.BLOCKED,
)

/**
 * The useful half of a node's detail, for a one-line run log.
 *
 * ⚠ Handles and image ids are dropped: they are content addresses, forty
 * characters wide, and say nothing a person can act on. What is kept is the
 * SHAPE — `1024x1024`, `20st cfg 7.5 dpm`, `seed 12345` — which is what a
 * reader is checking against what they expect.
 */
private fun brief(detail: String): String {
    if (detail.isBlank()) return ""
    val kept = detail.split(' ').filterNot {
        it.startsWith("img_") || it.startsWith("lat_") || it.startsWith("cond_")
    }.joinToString(" ").replace(Regex("\b(image|latent|cond)\b"), "").trim()
    return if (kept.isEmpty()) "" else "  ·  $kept"
}
