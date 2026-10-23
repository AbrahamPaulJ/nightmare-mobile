package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R

/**
 * ⭐⭐ Opening a flow over an unsaved one ASKS. `HarnessViewModel.ActiveFlow` has the reasoning; the short
 * version is that this used to replace the canvas silently and rely on a 150 ms autosave debounce having fired.
 *
 * ⭐ Three answers, one dialog everywhere a flow is opened (Use, Flows, Results; the user's call, 2026-10-10):
 * Save (then open), Open anyway, Cancel. ⚠ Stacked ([DialogButtonStack]): three in a row crushed Save to an
 * empty sliver (2026-10-23). Its own function so a golden can draw it (`DialogGoldenTest`).
 */
@Composable
fun OpenFlowDialog(label: String, onSave: () -> Unit, onOpenAnyway: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.flow_open_unsaved_title, label)) },
        text = { Text(stringResource(R.string.flow_open_unsaved_body)) },
        confirmButton = {
            DialogButtonStack {
                val wide = Modifier.fillMaxWidth()
                StudioButton(onClick = onSave, modifier = wide) { Text(stringResource(R.string.save)) }
                // ⚠ Red: it throws the unsaved edits away.
                StudioOutlinedButton(onClick = onOpenAnyway, modifier = wide, colors = dangerOutlinedColors()) {
                    Text(stringResource(R.string.flow_open_anyway))
                }
                StudioOutlinedButton(onClick = onCancel, modifier = wide) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}
