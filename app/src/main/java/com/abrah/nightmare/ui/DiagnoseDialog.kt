package com.abrah.nightmare.ui

import com.abrah.nightmare.ui.StudioTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R

/**
 * ⭐ Diagnose's progress ([com.abrah.nightmare.BlankDiagnosis]): which leg of how many, Cancel. Once [report] is
 * there it is [ErrorDetails], the report shown before it leaves the phone, as every other report is.
 */
@Composable
fun DiagnoseDialog(step: Int, total: Int, what: String, report: String?, onCancel: () -> Unit, onClose: () -> Unit) {
    if (report != null) {
        ErrorDetails(report, onDismiss = onClose)
        return
    }
    AlertDialog(
        // ⚠ Not dismissed by a tap outside: it is ten minutes of work, and Cancel says what it does.
        onDismissRequest = {},
        title = { Text(stringResource(R.string.diagnose)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else (step - 1).coerceAtLeast(0) / total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (step == 0) stringResource(R.string.diagnose_starting)
                    else stringResource(R.string.diagnose_progress, step, total, what),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(stringResource(R.string.diagnose_keep_open), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { StudioTextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) } },
    )
}
