package com.abrah.nightmare.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R

/**
 * ⭐⭐⭐ THE confirm for every destructive action in the app.
 *
 * ⚠⚠ It exists because there were eleven hand-written copies and they had
 * drifted into four verbs (Delete / Remove / Clear / Forget), two dismiss labels
 * (Cancel / Keep) and two confirm styles (an error-filled Button, a plain
 * TextButton) — and one surface, the inspector's bin, had no confirm at all.
 * The design review, 2026-09-15; the verb is `Delete` everywhere by the user's
 * call. `docs/UI.md` §8.2.
 *
 * ⚠ [body] must name the COST — what it frees, what getting it back costs, what
 * else stops working (§7.5). A body that only says "this cannot be undone" is
 * the ceremony that rule forbids.
 */
@Composable
fun ConfirmDelete(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** ⚠ Only for a count — `Delete 8`. Never a different verb. */
    confirmLabel: String = stringResource(R.string.delete),
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = LogTextStyle) },
        confirmButton = {
            // ⚠ Dismiss BEFORE acting: the action often closes the surface this
            // dialog belongs to, and a flag left set re-opens it over the next
            // screen asking about something already gone.
            Button(
                onClick = { onDismiss(); onConfirm() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
