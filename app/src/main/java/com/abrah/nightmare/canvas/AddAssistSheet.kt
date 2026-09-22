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
 * ⚠⚠⚠ **Checkboxes here, not the radios `docs/UI.md` §8.10 prescribes**, and
 * the difference is real rather than an oversight. §8.10's radios are for an
 * either/or with one right answer — take the checkpoint's prompt or keep mine,
 * where exactly one outcome happens. This is a LIST of independent additions:
 * keeping the prompt and dropping the photo is a legitimate combination, and a
 * radio group cannot say it. ⚠ The one place it is an either/or is the SOURCE
 * of a single input port, and that is enforced by [pick] rather than by the
 * control: ticking a second candidate for the same port unticks the first,
 * because an input takes one wire ([com.abrah.nightmare.Graph.connected]).
 *
 * ⚠⚠ It is not shown when it would offer nothing ([AddPlan.isEmpty]) — the node
 * is simply added, as it always was. A dialog with nothing in it is what
 * teaches people to tap through dialogs.
 */
@Composable
fun AddAssistSheet(
    plan: AddPlan,
    onCancel: () -> Unit,
    onAdd: (helperPorts: Set<String>, snaps: Map<String, SnapCandidate>) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add ${plan.type.paletteName.knobLabel}") },
        text = { AddAssistContent(plan, onAdd) },
        // ⚠ The confirm lives INSIDE the content, because it has to carry the
        // tick state. This slot keeps the dismiss where every other dialog puts
        // it.
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
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
    onAdd: (helperPorts: Set<String>, snaps: Map<String, SnapCandidate>) -> Unit,
) {
    var helperPorts by remember(plan) {
        mutableStateOf(plan.helpers.map { it.port }.toSet())
    }
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
        if (plan.helpers.isNotEmpty()) {
            Section("Also add")
            for (h in plan.helpers) {
                CheckRow(
                    checked = h.port in helperPorts,
                    title = h.type.paletteName.knobLabel,
                    subtitle = "feeds ${h.port.knobLabel.lowercase()}",
                    onToggle = { on ->
                        helperPorts = if (on) helperPorts + h.port else helperPorts - h.port
                    },
                )
            }
        }
        if (plan.snaps.isNotEmpty()) {
            Section("Connect to")
            for (c in plan.snaps) {
                CheckRow(
                    checked = picked[c.port] === c,
                    title = c.fromNode,
                    subtitle = "into ${c.port.knobLabel.lowercase()}",
                    onToggle = { on ->
                        // ⚠⚠ One source per PORT — see the class note.
                        picked = if (on) picked + (c.port to c) else picked - c.port
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onAdd(helperPorts, picked) }) { Text("Add") }
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
