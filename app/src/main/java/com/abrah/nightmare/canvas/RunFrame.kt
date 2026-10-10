package com.abrah.nightmare.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.MeasureTextStyle

/**
 * ⭐⭐ **The run, drawn where its picture will be** (the user's calls, 2026-10-10): a frame at the
 * render's aspect — the size of the output it is making — FILLED by the run: a header with the
 * node at work, a big [RunLogState.stepLabel], a ticking clock and a ring, then the log's lines
 * filling the rest and following the tail, over a slowly moving gradient so a long render reads
 * as alive rather than frozen.
 *
 * The Default view shows it in the OUTPUT page's picture slot while a run goes — the pages stay,
 * the run opens on that one ([NodeInspector]); no dialog on a tap there. Chat shows it in place of
 * its live card (`AgentScreen`), where a tap opens the whole log. One function, so the two cannot
 * drift (`docs/UI.md` §8).
 *
 * ⚠ Fitted INSIDE the space it is given: a 9:16 frame on a short screen is as tall as the room,
 * not as wide as the screen.
 */
@Composable
fun RunFrame(
    log: RunLogState,
    aspect: Float,
    modifier: Modifier = Modifier,
    /** ⭐ A tap opens the whole log — Chat's; the Default view's frame does nothing on a tap. */
    fullLogOnTap: Boolean = true,
) {
    var full by remember { mutableStateOf(false) }
    val a = aspect.takeIf { it.isFinite() && it > 0f }?.coerceIn(0.3f, 3f) ?: 1f
    // ⭐ Alive: the gradient drifts and the dot breathes; the clock ticks ten times a second.
    // ⚠⚠ Still under Robolectric: a golden waits for the screen to go idle, and an endless
    // animation never does — every golden with a run in progress sat until its timeout and
    // `verifyRoborazziDebug` ran 25 min instead of ~2 (2026-10-10).
    var drift = 0.5f
    var pulse = 1f
    if (ANIMATED) {
        val motion = rememberInfiniteTransition(label = "run")
        drift = motion.animateFloat(0f, 1f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Reverse), label = "drift").value
        pulse = motion.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse").value
    }
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    if (ANIMATED) LaunchedEffect(log.startedAtMs) {
        while (log.running) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(100)
        }
    }
    val c1 = MaterialTheme.colorScheme.primaryContainer
    val c2 = MaterialTheme.colorScheme.surfaceVariant
    val c3 = MaterialTheme.colorScheme.secondaryContainer
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val w = if (constraints.hasBoundedHeight) minOf(maxWidth, maxHeight * a) else maxWidth
        Box(
            Modifier
                .size(w, w / a)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.linearGradient(
                        listOf(c1, c2, c3),
                        start = Offset(0f, 1200f * drift),
                        end = Offset(900f, 1200f * (1f - drift)),
                    ),
                )
                .then(if (fullLogOnTap) Modifier.clickable { full = true } else Modifier),
        ) {
            val frac = log.step?.takeIf { it.second > 0 }?.let { (s, t) -> s.toFloat() / t }
            Column(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        log.now ?: stringResource(R.string.run_starting),
                        style = MeasureTextStyle, fontSize = 13.sp, color = Color.White,
                        maxLines = 1, modifier = Modifier.weight(1f),
                    )
                    if (frac != null) {
                        CircularProgressIndicator(progress = { frac }, modifier = Modifier.size(22.dp), strokeWidth = 3.dp, color = Color.White)
                    } else {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp, color = Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    Text(
                        log.stepLabel ?: "…",
                        fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                    )
                    Spacer(Modifier.weight(1f))
                    if (log.running && log.startedAtMs > 0L) {
                        Text(formatMs(now - log.startedAtMs), style = MeasureTextStyle, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
                    }
                }
                // ⭐ The log fills the rest, newest at the bottom, following the tail.
                val scroll = rememberScrollState()
                LaunchedEffect(log.lines.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.Bottom),
                ) {
                    for ((i, l) in log.lines.withIndex()) {
                        val last = i == log.lines.lastIndex
                        Text(
                            "${l.id}  ${l.text}", style = MeasureTextStyle, fontSize = 11.sp,
                            color = when {
                                l.bad -> Color(0xFFFF8A80)
                                last -> Color.White
                                else -> Color.White.copy(alpha = 0.7f)
                            },
                        )
                    }
                }
            }
        }
    }
    if (full) {
        AlertDialog(
            onDismissRequest = { full = false },
            confirmButton = { TextButton(onClick = { full = false }) { Text(stringResource(R.string.close)) } },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    for (l in log.lines) {
                        Text(
                            "${l.id}  ${l.text}", style = MeasureTextStyle, fontSize = 11.sp,
                            color = if (l.bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )
    }
}

/** ⚠ False under Robolectric (JVM goldens), where an endless animation never lets a capture settle. */
private val ANIMATED: Boolean = android.os.Build.FINGERPRINT != "robolectric"

/**
 * ⭐ The aspect of what a run makes: the first node that has a size (a sampler's `width` /
 * `height`, defaults applied). 1 when nothing does.
 */
fun runAspect(graph: com.abrah.nightmare.Graph, types: Map<String, com.abrah.nightmare.NodeType>): Float =
    graph.nodes.firstNotNullOfOrNull { n ->
        val p = com.abrah.nightmare.applyDefaults(types[n.type]?.widgets.orEmpty(), n)
        val w = p["width"]?.toIntOrNull()
        val h = p["height"]?.toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null
    } ?: 1f
