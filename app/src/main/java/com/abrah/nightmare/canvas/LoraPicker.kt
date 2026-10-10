package com.abrah.nightmare.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.LoraSpec
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐⭐⭐ **Pick LoRAs by tapping them, and set each one's strength on a slider.**
 *
 * The `loras` param shipped as free text (1.5.557) with its own format in a
 * hint — which works and is the wrong control: a name is typed from memory, a
 * strength has no bounds, and the one thing the app knows for certain — WHICH
 * FILES ARE ACTUALLY THERE — was not on screen. The user's call, 2026-09-21.
 *
 * ⚠⚠ **A named-but-missing LoRA is SHOWN, not dropped.** A workflow that came
 * from another phone names files this one does not have, and silently hiding
 * them would leave a graph that refuses at Run naming a LoRA the sheet swore
 * was not in it. Same rule as a flow this phone cannot run (`CLAUDE.md`):
 * shown, marked, and refusing is recoverable — hidden is not.
 *
 * ⚠ Writes LIVE, with no confirm button. Every other knob in the inspector
 * commits as you touch it; a picker that needed an OK would be the one control
 * where backing out means something different.
 */
@Composable
fun LoraPicker(
    installed: List<Pair<String, Long>>,
    spec: String,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * ⭐⭐⭐ **Import a `.safetensors` from HERE.** Null hides the button —
     * a golden draws the content with no Activity to launch a picker from.
     *
     * ⚠⚠ Asked for 2026-09-22: the empty state said "import one on the
     * Settings tab", which is a screen away, behind the gear, past the theme
     * and the battery rows — and it is the one moment a person is certain they
     * want a LoRA. A hint that names another screen is a hint that could have
     * been a button.
     */
    onImport: (() -> Unit)? = null,
    /**
     * ⭐⭐ "Search online" — the LoRA browser (`docs/LORA-BROWSER.md`), on this
     * node's family. Null hides it: a golden, or a family it does not serve.
     */
    onBrowse: (() -> Unit)? = null,
    /** ⭐ Each LoRA's note ([com.abrah.nightmare.LoraNotes]), by file name. */
    notes: Map<String, String> = emptyMap(),
    /** ⭐ Save one note. Null hides the ⋮ — a golden has no folder to write to. */
    onSaveNote: ((name: String, note: String) -> Unit)? = null,
    /**
     * ⭐ Append text to the prompt wired into this sampler. Null = no prompt node
     * to append to; the button is then DIMMED and says why (`docs/UI.md` §8.18).
     */
    onAddToPrompt: ((String) -> Unit)? = null,
    /** ⭐ Replace that prompt's text with the trigger words; null exactly when [onAddToPrompt] is. */
    onReplacePrompt: ((String) -> Unit)? = null,
    /**
     * ⭐⭐ Delete the file from `_loras` — the SAME `HarnessViewModel.deleteLora`
     * Settings → Add-ons calls, behind the same [ConfirmDeleteLora]. Null hides it.
     */
    onDelete: ((String) -> Unit)? = null,
    /** ⭐ Each file's date and base ([com.abrah.nightmare.LoraLibrary]); null = names and sizes only. */
    library: List<com.abrah.nightmare.LoraLibrary.Item>? = null,
    /** ⭐ The base this node takes — its filter chip comes first. */
    nodeBase: com.abrah.nightmare.LoraLibrary.Base? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.loras_title)) },
        text = {
            LoraPickerContent(installed, spec, onSet, onImport, notes, onSaveNote, onAddToPrompt, onReplacePrompt, onDelete, library, nodeBase)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.crop_done)) } },
        // ⚠ The dismiss SLOT, so Files sits left of Done — the same
        // "destructive-or-secondary left, primary right" order `InstalledActions`
        // and `PictureActions` keep (`docs/UI.md` §8.1, §8.3).
        // ⭐ Search online sits beside Files: both are ways to get a LoRA in.
        // ⭐ FILES, not "Add" — it opens the file browser, and "Add" read as adding the
        // LoRA to the node (the user's call, 2026-10-08). The same word as `FilesButton`.
        dismissButton = if (onImport == null && onBrowse == null) null else {
            {
                Row {
                    onImport?.let { TextButton(onClick = it) { Text(androidx.compose.ui.res.stringResource(com.abrah.nightmare.R.string.pick_files)) } }
                    onBrowse?.let {
                        TextButton(onClick = it) {
                            Text(androidx.compose.ui.res.stringResource(com.abrah.nightmare.R.string.lb_open))
                        }
                    }
                }
            }
        },
    )
}

/**
 * ⭐ The dialog's content, separate so a golden can draw it — an [AlertDialog]
 * is a window of its own and a screenshot test cannot reach inside one. Same
 * split, for the same reason, as [NodePaletteContent].
 */
@Composable
fun LoraPickerContent(
    installed: List<Pair<String, Long>>,
    spec: String,
    onSet: (String) -> Unit,
    onImport: (() -> Unit)? = null,
    notes: Map<String, String> = emptyMap(),
    onSaveNote: ((name: String, note: String) -> Unit)? = null,
    onAddToPrompt: ((String) -> Unit)? = null,
    onReplacePrompt: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    library: List<com.abrah.nightmare.LoraLibrary.Item>? = null,
    nodeBase: com.abrah.nightmare.LoraLibrary.Base? = null,
) {
    // ⚠ Seeded from the param and re-seeded when it changes, so the sheet shows
    // what the node actually carries rather than a copy that drifted.
    var chosen by remember(spec) { mutableStateOf(LoraSpec.parse(spec)) }

    fun commit(next: List<LoraSpec.Entry>) {
        chosen = next
        onSet(LoraSpec.format(next))
    }

    // ⭐ Which LoRA's note is open, if any; which one is being deleted.
    var noting by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    noting?.let { name ->
        LoraNoteDialog(
            name = name,
            note = notes[name].orEmpty(),
            onSave = { onSaveNote?.invoke(name, it) },
            onAddToPrompt = onAddToPrompt,
            onReplacePrompt = onReplacePrompt,
            onDelete = onDelete?.let { { deleting = name } },
            onDismiss = { noting = null },
        )
    }
    deleting?.let { name ->
        ConfirmDeleteLora(
            name,
            onConfirm = {
                noting = null
                // ⚠ Unticked on THIS node too: deleting a LoRA from the node
                // that uses it means "not this one", and leaving it named would
                // only make the next Run refuse.
                if (chosen.any { it.name == name }) commit(chosen.filterNot { it.name == name })
                onDelete?.invoke(name)
            },
            onDismiss = { deleting = null },
        )
    }

    // ⚠⚠ Chosen ones FIRST and in their own order, then everything else
    // alphabetically. The order in the param is the order the engine applies
    // them in, so re-sorting the list would silently re-sort the stack.
    // ⭐⭐ The library view (a user, 2026-10-09: "sorting like ComfyUI's LoRA Manager"): sort by
    // name / newest / most used / size, filter by base model and by name, favourites first.
    // ⚠⚠ The CHOSEN ones stay first and in their own order, whatever the sort and filter: the order
    // in the param is the order the engine applies them in, and a LoRA that is on must stay visible.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val items = library ?: installed.map { com.abrah.nightmare.LoraLibrary.Item(it.first, it.second, 0L, com.abrah.nightmare.LoraLibrary.Base.OTHER) }
    var sort by remember { mutableStateOf(com.abrah.nightmare.LoraLibrary.Sort.NAME) }
    var base by remember { mutableStateOf<com.abrah.nightmare.LoraLibrary.Base?>(null) }
    var query by remember { mutableStateOf("") }
    var favourites by remember { mutableStateOf(com.abrah.nightmare.LoraLibrary.favourites(ctx)) }
    val uses = remember { com.abrah.nightmare.LoraLibrary.uses(ctx) }
    val bases = items.map { it.base }.distinct().sortedBy { if (it == nodeBase) -1 else it.ordinal }
    val arranged = com.abrah.nightmare.LoraLibrary.arrange(items, sort, base, query, favourites, uses)
    val installedNames = items.map { it.name }.toSet()
    val rows = chosen.map { it.name }.distinct() + arranged.map { it.name }.filter { name -> chosen.none { it.name == name } }
    val sizes = items.associate { it.name to it.bytes }
    val baseOf = items.associate { it.name to it.base }

    if (rows.isEmpty()) {
        Text(
            // ⚠ It names the button below it rather than another screen. The
            // Settings tab still imports; it is no longer the only way.
            if (onImport != null) stringResource(R.string.lora_none_add)
            else stringResource(R.string.lora_none_settings),
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ⭐ The controls, once there is something to sort.
        if (items.size >= 2) {
            // ⚠ The sort menu OUTSIDE the scrolling chips: its label is weighted, and inside a
            // horizontal scroll it was measured to nothing (the golden showed a bare arrow).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                com.abrah.nightmare.ui.PillMenu(
                    com.abrah.nightmare.LoraLibrary.Sort.entries, sort,
                    { stringResource(sortLabel(it)) },
                ) { sort = it }
                if (bases.size > 1) Row(
                    Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    com.abrah.nightmare.ui.Pill(stringResource(R.string.lora_base_all), base == null) { base = null }
                    for (b in bases) com.abrah.nightmare.ui.Pill(b.label, base == b) { base = if (base == b) null else b }
                }
            }
            if (items.size >= 8) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.lora_search_installed)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        for (name in rows) {
            val entry = chosen.firstOrNull { it.name == name }
            val missing = name !in installedNames
            // ⭐ Violet Studio: each LoRA a card; a chosen one tinted with a violet edge, its
            // strength inside it (the actual 0–2 range, 1.0 at the middle).
            com.abrah.nightmare.ui.StudioCard(selected = entry != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = entry != null,
                    onCheckedChange = { on ->
                        if (on) com.abrah.nightmare.LoraLibrary.markUsed(ctx, name)
                        commit(
                            if (on) chosen + LoraSpec.Entry(name, LoraSpec.FULL)
                            else chosen.filterNot { it.name == name },
                        )
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    baseOf[name]?.takeIf { it != com.abrah.nightmare.LoraLibrary.Base.OTHER }?.let { b ->
                        Text(b.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        // ⚠ The MB is why the size is carried at all: an
                        // adapter is tens of megabytes and a merged checkpoint
                        // is gigabytes, and someone who picked the wrong file
                        // sees it here rather than at Run.
                        if (missing) stringResource(R.string.lora_missing)
                        else stringResource(R.string.device_mb, (sizes[name] ?: 0L) shr 20),
                        style = NoteTextStyle,
                        color = if (missing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⭐⭐ ⋮ = this LoRA's note. A dot beside it says one exists, so
                // the LoRAs that have a recipe written down are findable at a
                // glance (the mockup's indicator).
                // ⚠ Not on a missing file: there is nothing to note or delete.
                // ⭐ Favourite: the app's one star, its colour the state (`PictureActions`).
                if (!missing) {
                    val fav = name in favourites
                    IconButton(onClick = {
                        com.abrah.nightmare.LoraLibrary.setFavourite(ctx, name, !fav)
                        favourites = com.abrah.nightmare.LoraLibrary.favourites(ctx)
                    }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = stringResource(if (fav) R.string.lora_cd_unfavourite else R.string.lora_cd_favourite, name),
                            tint = if (fav) com.abrah.nightmare.ui.Warning else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (onSaveNote != null && !missing) {
                    if (!notes[name].isNullOrBlank()) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    IconButton(onClick = { noting = name }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.lora_cd_note, name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ⚠ Only for a LoRA that is ON. A slider under an unticked row is a
            // control for a thing that will not happen.
            if (entry != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Slider(
                        value = entry.strength.toFloat(),
                        onValueChange = { v ->
                            // ⚠ Snapped to the step before it is stored, so the
                            // string never carries a float's tail — `0.8500001`
                            // is not a strength anyone typed.
                            val snapped = Math.round(v / LoraSpec.STEP) * LoraSpec.STEP
                            commit(
                                chosen.map {
                                    if (it.name == name) it.copy(strength = snapped) else it
                                },
                            )
                        },
                        valueRange = LoraSpec.MIN.toFloat()..LoraSpec.MAX.toFloat(),
                        // ⚠⚠ **No `steps`, so no TICKS.** Passing the step count
                        // makes Material draw a mark per stop — forty of them
                        // across a phone-width slider, which came out as a
                        // barcode and was the only ticked slider in the app.
                        // Not one [SliderRow] in the inspector passes `steps`
                        // either. The quantising happens above, on the VALUE,
                        // which is the half that actually matters.
                        modifier = Modifier.weight(1f),
                    )
                    com.abrah.nightmare.ui.MetaPill(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            LoraSpec.number(entry.strength),
                            style = com.abrah.nightmare.ui.metaValueStyle(),
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * ⭐⭐ One LoRA's note: trigger words on the first line, then whatever helps —
 * strength, sampler, what it is good for. Copy takes the whole note; "Add to
 * prompt" appends the first line to the prompt wired into this sampler.
 *
 * ⚠⚠ SAVED ON CLOSE, however it closes — Done, back, or a tap outside. A
 * field the user typed into and then dismissed must not silently discard what
 * they typed (the inspector's own rule, [NodeInspector]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LoraNoteDialog(
    name: String,
    note: String,
    onSave: (String) -> Unit,
    onAddToPrompt: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    /** ⭐ Replace the wired prompt with the trigger words — beside "Add to prompt", same rules. */
    onReplacePrompt: ((String) -> Unit)? = null,
    /** ⭐ Opens [ConfirmDeleteLora]; null hides Delete. */
    onDelete: (() -> Unit)? = null,
) {
    var text by remember(name) { mutableStateOf(note) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun close() {
        if (text != note) onSave(text)
        onDismiss()
    }
    fun toast(msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 10,
                    placeholder = {
                        Text(
                            stringResource(R.string.lora_note_hint),
                            style = NoteTextStyle,
                        )
                    },
                )
                val trigger = com.abrah.nightmare.LoraNotes.trigger(text)
                // ⭐ A FlowRow: three buttons do not fit one dialog-wide row.
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.trim()))
                            toast(ctx.getString(R.string.lora_note_copied))
                        },
                    ) { Text(stringResource(R.string.err_copy)) }
                    // ⚠ DIMMED, never removed, and it says why (`docs/UI.md`
                    // §8.18): the row must not change shape with the graph.
                    // ⭐ Add appends; Replace swaps the whole prompt for the
                    // trigger words (asked for 2026-10-07). No confirm on Replace:
                    // replacing is the button's whole meaning.
                    for ((label, act, done) in listOf(
                        Triple(stringResource(R.string.lora_add_prompt), onAddToPrompt, R.string.lora_added_prompt),
                        Triple(stringResource(R.string.lora_replace_prompt), onReplacePrompt, R.string.lora_prompt_replaced),
                    )) {
                        OutlinedButton(
                            enabled = trigger != null,
                            onClick = {
                                val t = trigger ?: return@OutlinedButton
                                if (act == null) {
                                    toast(ctx.getString(R.string.lora_no_prompt))
                                } else {
                                    act(t)
                                    toast(ctx.getString(done, t))
                                }
                            },
                            modifier = if (act == null) Modifier.alpha(0.38f) else Modifier,
                        ) { Text(label) }
                    }
                }
            }
        },
        // ⚠ Delete in the dismiss slot, left of Done — destructive left,
        // primary right (`docs/UI.md` §8.1). It asks first ([ConfirmDeleteLora]).
        dismissButton = onDelete?.let {
            {
                TextButton(onClick = it) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.abrah.nightmare.R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.crop_done)) } },
    )
}

/**
 * ⭐ "Delete <LoRA>?" — ONE confirm for both places a LoRA file is deleted
 * (Settings → Add-ons and the node's ⋮), so the two name the same cost (§8.2).
 */
@Composable
fun ConfirmDeleteLora(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    com.abrah.nightmare.ui.ConfirmDelete(
        title = stringResource(R.string.lora_delete_title, name),
        body = stringResource(R.string.lora_delete_body),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

private fun sortLabel(s: com.abrah.nightmare.LoraLibrary.Sort): Int = when (s) {
    com.abrah.nightmare.LoraLibrary.Sort.NAME -> R.string.lora_sort_name
    com.abrah.nightmare.LoraLibrary.Sort.NEWEST -> R.string.lora_sort_newest
    com.abrah.nightmare.LoraLibrary.Sort.MOST_USED -> R.string.lora_sort_used
    com.abrah.nightmare.LoraLibrary.Sort.SIZE -> R.string.lora_sort_size
}
