package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.BackendProcess
import com.abrah.nightmare.LoraLibrary
import com.abrah.nightmare.LoraNotes
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle
import com.abrah.nightmare.ui.StudioOutlinedButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ⭐⭐ Settings, LoRAs: every installed LoRA, in the folders the node's picker files them in (GitHub #137).
 *
 * ⚠ Folders shipped in the picker in 1.6.164 (GitHub #36), but this list stayed flat, and it is the one a person
 * browses beside the CivitAI search: #137 asked for folders "in the LoRAs tab" a month after they existed. The same
 * folders ([LoraLibrary.folders]), the same header ([LoraFolderHeader]), the same ⋮ ([LoraNoteDialog]: folder, note,
 * model), so a LoRA filed in one place is filed in the other.
 */
@Composable
fun LoraLibraryList(rows: List<Pair<String, Long>>, onDelete: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    val dir = remember { BackendProcess.lorasDir(ctx) }
    var folders by remember { mutableStateOf(LoraLibrary.folders(ctx)) }
    var overrides by remember { mutableStateOf(LoraLibrary.baseOverrides(ctx)) }
    var openFolders by remember { mutableStateOf(emptySet<String>()) }
    var noting by remember { mutableStateOf<String?>(null) }
    var savedNotes by remember { mutableStateOf<Map<String, String>?>(null) }
    // ⚠ Off the main thread: a header read per file and a JSON read ([LoraLibrary.list], [LoraNotes.read]).
    val library by produceState<List<LoraLibrary.Item>?>(null, rows) {
        value = withContext(Dispatchers.IO) { LoraLibrary.list(dir, LoraLibrary.baseOverrides(ctx)) }
    }
    val notesRead by produceState<Map<String, String>?>(null, rows) {
        value = withContext(Dispatchers.IO) { LoraNotes.read(dir) }
    }
    val notes = savedNotes ?: notesRead.orEmpty()
    val detected = library.orEmpty().associateBy { it.name }
    val items = rows.map { (name, bytes) ->
        val d = detected[name]?.detected ?: LoraLibrary.Base.OTHER
        LoraLibrary.Item(name, bytes, 0L, overrides[name] ?: d, d)
    }

    noting?.let { name ->
        val item = items.firstOrNull { it.name == name }
        LoraNoteDialog(
            name = name,
            note = notes[name].orEmpty(),
            onSave = { savedNotes = LoraNotes.write(dir, name, it) },
            onAddToPrompt = null,
            onDismiss = { noting = null },
            base = item?.base,
            detected = item?.detected,
            onSetBase = { b ->
                LoraLibrary.setBase(ctx, name, b)
                overrides = LoraLibrary.baseOverrides(ctx)
            },
            folder = folders[name],
            folderChoices = LoraLibrary.foldersFor(items, folders, item?.base),
            onSetFolder = { fo ->
                LoraLibrary.setFolder(ctx, name, fo)
                folders = LoraLibrary.folders(ctx)
                fo?.let { openFolders = openFolders + it.trim() }
            },
        )
    }

    val row: @Composable (LoraLibrary.Item) -> Unit = { item ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall)
                    item.base.takeIf { it != LoraLibrary.Base.OTHER }?.let { b ->
                        Text(b.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        stringResource(R.string.installed_mb, item.bytes shr 20),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (onDelete != null) {
                    StudioOutlinedButton(onClick = { onDelete(item.name) }) { Text(stringResource(R.string.delete)) }
                }
                IconButton(onClick = { noting = item.name }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.lora_cd_note, item.name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val byName = items.associateBy { it.name }
    val (groups, loose) = LoraLibrary.grouped(items.map { it.name }, folders)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((folder, names) in groups) {
            val isOpen = folder in openFolders
            LoraFolderHeader(folder, names.size, isOpen) { openFolders = if (isOpen) openFolders - folder else openFolders + folder }
            if (isOpen) for (n in names) byName[n]?.let { row(it) }
        }
        for (n in loose) byName[n]?.let { row(it) }
    }
}
