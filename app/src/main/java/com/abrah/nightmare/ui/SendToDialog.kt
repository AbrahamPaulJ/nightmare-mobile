package com.abrah.nightmare.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.HarnessViewModel
import com.abrah.nightmare.canvas.Recipe

/**
 * ⭐⭐ **Send a picture into a flow** — the popup behind [SendToIcon], asked for
 * 2026-09-17.
 *
 * Three sections, each shown only when it has something in it:
 * - **This flow** — one row per image input of the canvas flow, by name, so the
 *   user picks WHICH input when there is more than one (their call).
 * - **Flows** — the built-in flows that take a picture.
 * - **Saved** — the user's own flows that take one.
 *
 * ⚠ Opening another flow over unsaved edits still asks first: the view model
 * routes it through the same guard as the Flows tab, so this dialog makes no
 * promise of its own about unsaved work.
 */
@Composable
fun SendToDialog(
    choices: HarnessViewModel.SendChoices,
    onCurrent: (String) -> Unit,
    onRecipe: (Recipe) -> Unit,
    onSaved: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send to") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (choices.current.isNotEmpty()) {
                    Section("This flow")
                    choices.current.forEach { id -> Choice("“$id”", "replace its picture") { onCurrent(id) } }
                }
                if (choices.recipes.isNotEmpty()) {
                    Section("Flows")
                    choices.recipes.forEach { r -> Choice(r.label, "open it with this picture") { onRecipe(r) } }
                }
                if (choices.saved.isNotEmpty()) {
                    Section("Saved")
                    choices.saved.forEach { n -> Choice(n, "open it with this picture") { onSaved(n) } }
                }
                if (choices.current.isEmpty() && choices.recipes.isEmpty() && choices.saved.isEmpty()) {
                    Text("No flow takes a picture.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** ⚠ The whole row is the target — a finger aims at the words, not an edge. */
@Composable
private fun Choice(label: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
