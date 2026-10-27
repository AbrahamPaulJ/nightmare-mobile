package com.abrah.nightmare.ui

import com.abrah.nightmare.ui.StudioTextButton
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.CrashReport
import com.abrah.nightmare.R

/**
 * ⭐⭐ **What killed the app last time, the next time it opens.**
 *
 * ⚠⚠ A dialog rather than a chip, and this is the one place in the app where
 * that is right: it is shown at most once per crash, it is never shown on an
 * ordinary launch ([CrashReport] decides, and says no far more often than yes),
 * and the thing it has to say is the one thing a person cannot otherwise find
 * out. A card on the canvas would be missed, which is how the "see Settings >
 * Diagnostics" pointer failed.
 *
 * ⚠ The TRACE is optional and usually absent — Android keeps one for a native
 * crash and an ANR, not for a memory kill. When it is there it is a tombstone,
 * so it is monospaced, scrollable in both directions and bounded in height: it
 * is evidence to copy out, not prose to read.
 */
@Composable
fun CrashNotice(
    report: CrashReport.Report,
    onDismiss: () -> Unit,
    /** ⭐ A killed Motion transfer left its seeds ([com.abrah.nightmare.npu.RunResume]): say Run carries on. */
    resumable: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(report.headline, style = MaterialTheme.typography.bodyMedium)
                // ⚠⚠ The single most useful line, when there is one. "Android
                // closed the app to free memory" is a shrug; "…while rendering"
                // tells a person which knob to move, and `docs/MODELS.md` §9 is
                // an entire section about that knob being the output size.
                report.doing?.let {
                    Text(
                        stringResource(R.string.crash_during, it),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (resumable) Text(
                    stringResource(R.string.crash_resume),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                report.trace?.let {
                    Text(
                        stringResource(R.string.crash_details),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    Text(
                        it,
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        // ⚠ Horizontal too: a tombstone's register dump is wide
                        // and wrapping it makes it unreadable as a dump.
                        // ⚠ …and VERTICAL: capped at 260dp with no scroll, a long tombstone showed its first
                        // ~20 lines and the rest only through Copy ([ErrorDetails] scrolls; this must too).
                        modifier = Modifier
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState())
                            .horizontalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = { StudioButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        // ⭐ The report as text, for a bug report: what happened, while doing what, and the
        // evidence (the breadcrumb's RAM per node, then any tombstone).
        dismissButton = {
            val clipboard = LocalClipboardManager.current
            val during = report.doing?.let { stringResource(R.string.crash_during, it) }
            StudioOutlinedButton(onClick = {
                clipboard.setText(
                    AnnotatedString(listOfNotNull(report.headline, during, report.trace).joinToString("\n\n")),
                )
            }) { Text(stringResource(R.string.copy)) }
        },
    )
}
