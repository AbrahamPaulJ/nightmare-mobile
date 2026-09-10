package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.BatchAxis
import com.abrah.nightmare.BatchSpec
import com.abrah.nightmare.BatchValues
import com.abrah.nightmare.Graph
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.ui.LogTextStyle

/**
 * ⭐⭐ **Build a sweep, and see what it costs before it starts.**
 *
 * ⚠⚠ The RUN COUNT is the whole design. A sweep is the first thing in this app
 * that runs for minutes, and two axes multiply — 4 seeds at 2 cfgs is 8 renders
 * and about three minutes on SDXL. So the count is shown live, next to the
 * button, and the button is disabled at zero. Nobody should learn the size of
 * their sweep by waiting for it.
 *
 * ⚠ One axis today. Two is a `+` away and the model already expands N
 * (`BatchSpec.expand`) — but one axis is what "try four seeds" needs, and it is
 * the thing worth getting on a phone first.
 */
@Composable
fun BatchSheet(
    graph: Graph,
    types: Map<String, NodeType>,
    onDismiss: () -> Unit,
    onRun: (BatchSpec) -> Unit,
) {
    // ⚠⚠⚠ **NUMERIC widgets only, and DERIVED ones excluded.** The first
    // version filtered on "not a context key and not a dropdown", which let
    // through `uri`, `prompt`, `negative`, the mask's `ops` blob and the crop's
    // four rect numbers — and then `take(12)` truncated the list before it ever
    // reached the sampler. So the sheet offered everything a sweep cannot
    // usefully vary and none of `seed`, `steps`, `cfg`, `denoise`. Reported
    // from the phone, 2026-09-10.
    //
    // ⚠ `out_w`/`out_h` are excluded too: they are written by the canvas from
    // whatever consumes the node (`Framing.deriveSizes`), so sweeping one is
    // overwritten before it runs.
    // ⚠ `locked` widgets likewise — a knob the inspector refuses to edit is not
    // one a sweep may edit behind its back.
    val choices = remember(graph, types) {
        graph.nodes.flatMap { n ->
            (types[n.type]?.widgets.orEmpty())
                .filter { w ->
                    w.numeric &&
                        w.options == null &&
                        w.locked == null &&
                        w.name !in DERIVED &&
                        BatchSpec.refusalFor(w.name) == null
                }
                .map { w -> n.id to w.name }
        }
            // ⭐ Sampler knobs first. `seed`, `steps`, `cfg` and `denoise` are
            // what anyone means by "batch", and graph order buries them behind
            // the crop's geometry every time.
            .sortedBy { (_, param) ->
                val i = PREFERRED.indexOf(param)
                if (i >= 0) i else PREFERRED.size
            }
    }
    var picked by remember(choices) { mutableStateOf(choices.firstOrNull()) }
    var text by remember { mutableStateOf("") }

    val values = BatchValues.parse(text)
    val spec = picked?.let { (node, param) ->
        BatchSpec(listOf(BatchAxis(node, param, values)))
    } ?: BatchSpec()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.batch)) },
        text = {
            // ⚠ Scrollable, so nothing added later can push the values field
            // off the bottom again. An AlertDialog does not scroll its own
            // content, and on a short phone in landscape this body is already
            // taller than the window.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (choices.isEmpty()) {
                    Text(
                        stringResource(R.string.batch_nothing_to_sweep),
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                    return@Column
                }
                Text(
                    stringResource(R.string.batch_what_varies),
                    style = MaterialTheme.typography.labelLarge,
                )
                // ⚠⚠ A single SCROLLING ROW, not a stacked column of buttons.
                // Twelve stacked TextButtons filled an AlertDialog on a phone
                // and pushed the values field off the bottom — so the control
                // that the whole sheet exists for was invisible, which is what
                // "I don't see where I can type" meant.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (c in choices) {
                        val on = picked == c
                        FilterChip(
                            selected = on,
                            onClick = { picked = c },
                            label = { Text("${c.first}  ${c.second}", fontSize = 12.sp) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.batch_values)) },
                    placeholder = { Text(stringResource(R.string.batch_values_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // ⭐⭐ The count, live. This is the number that decides whether
                // someone presses the button.
                Text(
                    when {
                        text.isBlank() -> stringResource(R.string.batch_type_values)
                        values.isEmpty() ->
                            stringResource(R.string.batch_unreadable, BatchValues.MAX_VALUES)
                        else -> stringResource(
                            if (spec.runCount == 1) R.string.batch_one_run else R.string.batch_runs,
                            spec.runCount,
                        ) + "  ·  " + values.joinToString(", ")
                    },
                    style = LogTextStyle,
                    color = if (values.isEmpty() && text.isNotBlank()) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                // ⚠ Says where the pictures go. A sweep that quietly filled
                // Results would be a surprise the size of eight renders.
                Text(
                    stringResource(R.string.batch_kept),
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRun(spec) },
                enabled = !spec.isEmpty,
            ) {
                Text(
                    if (spec.isEmpty) stringResource(R.string.run)
                    else stringResource(R.string.batch_run_n, spec.runCount)
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * ⚠ Written by the canvas from whatever consumes the node, so a sweep over one
 * is overwritten before it runs. `Framing.deriveSizes` owns them.
 */
private val DERIVED = setOf("out_w", "out_h")

/**
 * ⭐ The knobs anyone actually means by "batch", in the order they are wanted.
 * ⚠ A preference, not a filter: everything numeric is still offered, this only
 * decides what is reachable without scrolling.
 */
private val PREFERRED = listOf("seed", "steps", "cfg", "denoise", "feather", "grow")

/**
 * ⚠ The live sweep row moved INTO `RunLogPanel`, so it sits between the armed
 * knobs and the run container rather than above both. Kept here only as a
 * pointer, because two places drawing "batch 3 of 8" would be one too many.
 */
