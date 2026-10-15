package com.abrah.nightmare.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.R

/**
 * ⭐⭐ **Use a kept picture's parts on the open flow** (GitHub #33, the user's design 2026-10-15): in a
 * picture's Info, the rows that can transfer (the picture itself, its prompt, negative and seed) are
 * tinted; a long press selects one and starts selecting, then taps add or remove; "Use N" opens
 * [TransferDialog], which matches each to a node of the open flow.
 */
sealed interface TransferItem {
    data class Picture(val path: String) : TransferItem
    data class Prompt(val text: String) : TransferItem
    data class Negative(val text: String) : TransferItem
    data class Seed(val value: String) : TransferItem
}

/** ⭐ What [ResultInfoDialog] needs to offer the transfer: the picture's row and where Use goes. */
class InfoTransfer(
    val picture: ImageBitmap?,
    val picturePath: String?,
    val onUse: (List<TransferItem>) -> Unit,
)

/** ⭐ A node a [TransferItem] can go to: its id, its name, and a line of what it holds now. */
data class TransferTarget(val nodeId: String, val name: String, val current: String)

/** ⚠ The "New image node" choice's id: no node has it ([TransferDialog]). */
const val NEW_IMAGE_NODE = "\u0000new"

/** ⚠ Pure: the Info row [key] / [value] as an item, or null for a row that does not transfer. */
fun transferItemOf(key: String, value: String): TransferItem? = when (key) {
    "Prompt" -> TransferItem.Prompt(value)
    "Negative" -> TransferItem.Negative(value)
    "Seed" -> value.trim().takeIf { s -> s.toLongOrNull()?.let { it != 0L } == true }?.let { TransferItem.Seed(it) }
    else -> null
}

/**
 * ⭐⭐ The matching popup: one radio list per item, of the open flow's nodes that can take it (with
 * a line of their current value), the only match preselected and Skip on each; an item nothing
 * can take is greyed with why. An Append toggle when a prompt or negative is among them.
 */
@Composable
fun TransferDialog(
    items: List<TransferItem>,
    targetsFor: (TransferItem) -> List<TransferTarget>,
    onApply: (Map<TransferItem, String>, append: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = remember(items) { items.associateWith(targetsFor) }
    var picks by remember(items) {
        mutableStateOf(targets.mapNotNull { (item, ts) -> ts.singleOrNull()?.let { item to it.nodeId } }.toMap())
    }
    var append by remember { mutableStateOf(false) }
    val hasPrompt = items.any { it is TransferItem.Prompt || it is TransferItem.Negative }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transfer_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (item in items) {
                    val ts = targets[item].orEmpty()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(itemTitle(item), style = MaterialTheme.typography.titleSmall)
                        Text(
                            itemValue(item), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (ts.isEmpty()) {
                            Text(
                                stringResource(R.string.transfer_no_target), style = NoteTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        for (t in ts) {
                            TargetRow(t.name, t.current, picks[item] == t.nodeId) { picks = picks + (item to t.nodeId) }
                        }
                        if (ts.isNotEmpty()) {
                            TargetRow(stringResource(R.string.transfer_skip), null, picks[item] == null) { picks = picks - item }
                        }
                    }
                }
                if (hasPrompt) {
                    Row(
                        Modifier.fillMaxWidth().clickable { append = !append },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = append, onCheckedChange = { append = it })
                        Text(stringResource(R.string.transfer_append), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = picks.isNotEmpty(), onClick = { onApply(picks, append) }) {
                Text(stringResource(R.string.transfer_use))
            }
        },
    )
}

@Composable
private fun TargetRow(name: String, current: String?, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            current?.let {
                Text(
                    it, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun itemTitle(item: TransferItem): String = stringResource(
    when (item) {
        is TransferItem.Picture -> R.string.transfer_picture
        is TransferItem.Prompt -> R.string.transfer_prompt
        is TransferItem.Negative -> R.string.transfer_negative
        is TransferItem.Seed -> R.string.transfer_seed
    }
)

private fun itemValue(item: TransferItem): String = when (item) {
    is TransferItem.Picture -> item.path.substringAfterLast('/')
    is TransferItem.Prompt -> item.text
    is TransferItem.Negative -> item.text
    is TransferItem.Seed -> item.value
}
