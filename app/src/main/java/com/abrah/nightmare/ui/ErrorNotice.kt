package com.abrah.nightmare.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R

/**
 * ⭐⭐ How a failure becomes a report: given the message on screen, the full
 * text behind it ([com.abrah.nightmare.ErrorReport]). Provided once, by
 * `MainActivity`, from the view model — which is the one place that knows the
 * run log and the canvas's prompts. Null (a golden, a preview) hides the icon.
 */
val LocalErrorReport = staticCompositionLocalOf<((String) -> String)?> { null }

/**
 * ⭐⭐ THE way this app says "that did not happen" — a Run that failed, a wire
 * refused at the drop, a download or an import that broke, a flow that would not
 * open.
 *
 * ⚠⚠ There were five treatments for it (`docs/UI.md` §8.5): a red banner, red
 * text in a dark chip, a bare red line above a tab row, red under a node, and a
 * disabled knob's hint in error red. One style now, by the user's call in the
 * design review, 2026-09-15. ⚠ The one exception is a node that failed ON THE
 * CANVAS: that message is drawn under the node it belongs to, because where it is
 * says which node — a chip elsewhere could not.
 *
 * ⭐⭐ [reportable] adds a share icon that opens [ErrorDetails] — the stack, the
 * backend's log, the phone (the user's ask, 2026-10-01: *"the full stack trace
 * … a popup showing the trace before i share it"*). Set it on a FAILURE — a Run,
 * an import, a flow — never on a refusal: "that would make a loop" has no trace
 * and a share button would make it look like a bug.
 *
 * ⚠ A LOCKED knob is not a failure and does not use this.
 */
@Composable
fun ErrorNotice(
    text: String,
    modifier: Modifier = Modifier,
    reportable: Boolean = false,
    /** ⭐ A ✕ after the share icon (the user, 2026-10-09) — only where the error can be cleared. */
    onClose: (() -> Unit)? = null,
) {
    val report = LocalErrorReport.current.takeIf { reportable }
    var shown by remember { mutableStateOf<String?>(null) }
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 10.dp, end = if (report != null || onClose != null) 0.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (report != null) {
            // ⚠ Built on the TAP, not per recomposition: it walks the model's
            // directory and copies 400 log lines.
            IconButton(onClick = { shown = report(text) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    ShareIcon,
                    contentDescription = stringResource(R.string.err_details),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        if (onClose != null) {
            IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
    shown?.let { ErrorDetails(it, onDismiss = { shown = null }) }
}

/**
 * ⭐⭐ The report, in full, BEFORE it leaves the phone — the user's call: it is
 * shown, then Copy or Share. Same shape as [CrashNotice]'s trace: monospaced,
 * scrollable both ways and bounded, because it is evidence to hand on, not
 * prose to read.
 */
@Composable
fun ErrorDetails(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.err_details)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.err_details_note),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text,
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { com.abrah.nightmare.Share.report(ctx, text) }
                onDismiss()
            }) { Text(stringResource(R.string.err_share)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                    Text(stringResource(R.string.err_copy))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
