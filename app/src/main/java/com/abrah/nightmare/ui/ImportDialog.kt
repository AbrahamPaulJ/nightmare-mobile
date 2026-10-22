package com.abrah.nightmare.ui

import com.abrah.nightmare.ui.StudioButton
import com.abrah.nightmare.ui.StudioTextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Family
import com.abrah.nightmare.R

/**
 * ⭐⭐ The Models title's "+" (the user, 2026-10-14: *"a plus icon next to the Models page header
 * and open a popup where we handle what import it is, rather than an import button on every
 * page"*). Moved out of the pages after testers took a CivitAI Anima file to the Anima page,
 * which asks for a zip.
 */
@Composable
fun ImportButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.import_add_cd), tint = MaterialTheme.colorScheme.primary)
    }
}

/**
 * ⭐⭐ What can be imported, an optional name, and ONE "Choose a file": the app reads the file and
 * routes it ([com.abrah.nightmare.CustomModels.sniffImport]). The name rules are the ones the
 * per-page card had ([com.abrah.nightmare.CustomModels.isValidName]); empty takes the file's name.
 */
@Composable
fun ImportDialog(onDismiss: () -> Unit, onChoose: (name: String) -> Unit) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val reserved = trimmed.isNotEmpty() && com.abrah.nightmare.CustomModels.isReserved(trimmed)
    val ok = trimmed.isEmpty() || (com.abrah.nightmare.CustomModels.isValidName(trimmed) && !reserved)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.import_body), style = MaterialTheme.typography.bodyMedium)
                for (res in listOf(R.string.import_kind_file, R.string.import_kind_zip, R.string.import_kind_upscaler)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(res), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.models_model_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    when {
                        reserved -> stringResource(R.string.models_name_reserved)
                        trimmed.isNotEmpty() && !ok -> stringResource(R.string.models_name_invalid)
                        trimmed.isEmpty() -> stringResource(R.string.models_name_empty_hint)
                        else -> stringResource(R.string.models_name_hint)
                    },
                    style = NoteTextStyle,
                    color = if (reserved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { StudioButton(onClick = { onChoose(trimmed) }, enabled = ok) { Text(stringResource(R.string.import_choose_file)) } },
        dismissButton = { StudioTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** ⭐ The DiT families a checkpoint file can be imported as ([com.abrah.nightmare.CustomModels.DitVariant]). */
val IMPORTABLE_DIT = listOf(Family.ANIMA_DIT, Family.FLUX2, Family.ZIMAGE, Family.KREA2, Family.MOSS)

/** ⭐ Asked only when the file's tensor names named no family. Null = cancel. */
@Composable
fun FamilyAskDialog(onAnswer: (Family?) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(stringResource(R.string.import_family_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.import_family_body), style = MaterialTheme.typography.bodyMedium)
                for (f in IMPORTABLE_DIT) {
                    Surface(
                        onClick = { onAnswer(f) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.size(10.dp)
                                    .then(Modifier.clip(androidx.compose.foundation.shape.CircleShape))
                                    .background(familyColor(f)),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(pageLabel(f), fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = { StudioTextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}
