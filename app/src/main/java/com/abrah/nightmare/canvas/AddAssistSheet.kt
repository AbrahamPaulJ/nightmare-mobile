package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.clickable
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.ui.LogTextStyle

/**
 * ⭐⭐⭐ **"Also add, and connect to" — asked once, when the node is dropped.**
 *
 * The user's ask, 2026-09-22, and the shape they asked for: a checkbox list of
 * helper nodes ticked by default, and a checkbox list of snap candidates with
 * the sensible one already selected.
 *
 * ⭐⭐⭐ **The control says how many you may pick.** A group where exactly one
 * option can win is drawn with RADIOS; only a genuinely independent list gets
 * checkboxes. The user's call, 2026-09-22: *"if we only allow single select show
 * it as radio btn not checkbox"* — and they were right, because the sheet was
 * already ENFORCING single-select on two of its three groups while drawing boxes
 * that promised otherwise.
 *
 * | group | control | why |
 * |---|---|---|
 * | a port's source | radio | an input takes ONE wire ([com.abrah.nightmare.Graph.connected] replaces) |
 * | put it in the wire | radio | a node sits in one wire, not several |
 * | feed it into | checkbox | one output feeds as many inputs as you like |
 *
 * ⚠ Every radio group also has an OFF state: tapping the selected one clears
 * it. A radio you cannot unpick would make "wire nothing into this port" the one
 * thing the sheet could not say.
 *
 * ⚠⚠ It is not shown when it would offer nothing ([AddPlan.isEmpty]) — the node
 * is simply added, as it always was. A dialog with nothing in it is what
 * teaches people to tap through dialogs.
 */
@Composable
fun AddAssistSheet(
    plan: AddPlan,
    onCancel: () -> Unit,
    onAdd: (helperPorts: Set<String>, snaps: Map<String, SnapCandidate>, splice: SpliceCandidate?, feeds: List<FeedCandidate>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add ${plan.type.paletteName.knobLabel}") },
        text = { AddAssistContent(plan, onAdd, onCancel) },
        // ⚠ The confirm lives INSIDE the content, because it has to carry the
        // tick state. This slot keeps the dismiss where every other dialog puts
        // it.
        // ⚠ BOTH buttons live in the content — see the note there.
        confirmButton = {},
    )
}

/**
 * ⭐ The body, separate so a golden can draw it — an [AlertDialog] is its own
 * window and a screenshot test cannot reach inside one. Same split, for the same
 * reason, as [LoraPickerContent] and [NodePaletteContent].
 */
@Composable
fun AddAssistContent(
    plan: AddPlan,
    onAdd: (helperPorts: Set<String>, snaps: Map<String, SnapCandidate>, splice: SpliceCandidate?, feeds: List<FeedCandidate>) -> Unit,
    onCancel: () -> Unit = {},
) {
    // ⚠⚠ A fresh node is ticked only for a port NOTHING can feed. Where
    // something can, sharing it is the default and "new" is the opt-in —
    // otherwise adding a second sampler would silently make a second prompt.
    var helperPorts by remember(plan) {
        mutableStateOf(
            plan.helpers.map { it.port }
                .filter { port -> plan.snaps.none { it.port == port } }
                .toSet(),
        )
    }
    // ⚠ Ticked by default (the user's call): a node dropped between two wired
    // ones almost always means to sit in that wire.
    var splice by remember(plan) { mutableStateOf(plan.splices.firstOrNull()) }
    // ⚠ A FREE port is ticked; an occupied one is offered unticked — see
    // [FeedCandidate.free].
    var feeds by remember(plan) { mutableStateOf(plan.feeds.filter { it.free }.toSet()) }
    // ⚠ Seeded from the recommendation, which is what "selected intuitively"
    // means — the box is already right for the common case and is still a box.
    var picked by remember(plan) {
        mutableStateOf(
            plan.snaps.filter { it.recommended }.associateBy { it.port },
        )
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ⭐⭐⭐ **Grouped by PORT**, so "use the prompt that is there" and
        // "make a new one" sit together as the alternatives they are. Listing
        // every existing node and then every helper put the two halves of one
        // decision in different sections.
        val ports = (plan.snaps.map { it.port } + plan.helpers.map { it.port }).distinct()
        for (port in ports) {
            Section(port.knobLabel)
            for (c in plan.snaps.filter { it.port == port }) {
                PickRow(
                    selected = picked[port] === c,
                    title = c.fromNode,
                    subtitle = "use the one already here",
                    onPick = {
                        // ⚠⚠ One source per PORT. Picking an existing node drops
                        // the "new" option for the same port.
                        val was = picked[port] === c
                        picked = if (was) picked - port else picked + (port to c)
                        if (!was) helperPorts = helperPorts - port
                    },
                )
            }
            for (h in plan.helpers.filter { it.port == port }) {
                PickRow(
                    selected = port in helperPorts,
                    title = "New ${h.type.paletteName.knobLabel.lowercase()}",
                    subtitle = if (plan.snaps.any { it.port == port }) "instead of sharing"
                    else "nothing here can feed this",
                    onPick = {
                        if (port in helperPorts) {
                            helperPorts = helperPorts - port
                        } else {
                            helperPorts = helperPorts + port
                            picked = picked - port
                        }
                    },
                )
            }
        }
        if (plan.feeds.isNotEmpty()) {
            Section("Feed it into")
            for (f in plan.feeds) {
                CheckRow(
                    checked = f in feeds,
                    title = f.toNode,
                    subtitle = if (f.free) "its ${f.toPort.knobLabel.lowercase()} is empty"
                    else "replaces what feeds its ${f.toPort.knobLabel.lowercase()}",
                    onToggle = { on ->
                        // ⚠ One source per target PORT, the same rule an input has
                        // anywhere else — ticking a second for one port drops the
                        // first rather than silently losing it at apply time.
                        feeds = feeds.filterNot {
                            it.toNode == f.toNode && it.toPort == f.toPort
                        }.toSet() + if (on) setOf(f) else emptySet()
                    },
                )
            }
        }
        if (plan.splices.isNotEmpty()) {
            Section("Put it in the wire")
            for (c in plan.splices) {
                PickRow(
                    selected = splice === c,
                    title = "${c.from} → here → ${c.consumer}",
                    subtitle = "${c.consumer} reads this node instead",
                    onPick = { splice = if (splice === c) null else c },
                )
            }
        }
        // ⚠⚠ Cancel and Add in ONE row, here in the content — the user's call,
        // 2026-09-22. An `AlertDialog` puts its own dismiss and confirm on one
        // line, but Add has to carry the tick state so it cannot live in that
        // slot; splitting the pair across the content and the dialog's row put
        // them on two lines with Cancel stranded under Add.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = { onAdd(helperPorts, picked, splice, feeds.toList()) }) { Text("Add") }
        }
    }
}

@Composable
private fun Section(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * ⚠ A radio row — for a group where exactly one option can win. [onPick] is
 * given the TAP rather than a boolean: tapping the selected one CLEARS it, which
 * a `RadioButton`'s own semantics cannot express.
 */
@Composable
private fun PickRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onPick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onPick)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
