package com.abrah.nightmare.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.LoraSpec
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
    /** ⭐ Each LoRA's note ([com.abrah.nightmare.LoraNotes]), by file name. */
    notes: Map<String, String> = emptyMap(),
    /** ⭐ Save one note. Null hides the ⋮ — a golden has no folder to write to. */
    onSaveNote: ((name: String, note: String) -> Unit)? = null,
    /**
     * ⭐ Append text to the prompt wired into this sampler. Null = no prompt node
     * to append to; the button is then DIMMED and says why (`docs/UI.md` §8.18).
     */
    onAddToPrompt: ((String) -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("LoRAs") },
        text = { LoraPickerContent(installed, spec, onSet, onImport, notes, onSaveNote, onAddToPrompt) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        // ⚠ The dismiss SLOT, so Add sits left of Done — the same
        // "destructive-or-secondary left, primary right" order `InstalledActions`
        // and `PictureActions` keep (`docs/UI.md` §8.1, §8.3).
        dismissButton = onImport?.let { { TextButton(onClick = it) { Text("Add") } } },
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
) {
    // ⭐ Which LoRA's note is open, if any.
    var noting by remember { mutableStateOf<String?>(null) }
    noting?.let { name ->
        LoraNoteDialog(
            name = name,
            note = notes[name].orEmpty(),
            onSave = { onSaveNote?.invoke(name, it) },
            onAddToPrompt = onAddToPrompt,
            onDismiss = { noting = null },
        )
    }
    // ⚠ Seeded from the param and re-seeded when it changes, so the sheet shows
    // what the node actually carries rather than a copy that drifted.
    var chosen by remember(spec) { mutableStateOf(LoraSpec.parse(spec)) }

    fun commit(next: List<LoraSpec.Entry>) {
        chosen = next
        onSet(LoraSpec.format(next))
    }

    // ⚠⚠ Chosen ones FIRST and in their own order, then everything else
    // alphabetically. The order in the param is the order the engine applies
    // them in, so re-sorting the list would silently re-sort the stack.
    val installedNames = installed.map { it.first }.toSet()
    val rows = (chosen.map { it.name }.distinct() + installed.map { it.first }
        .filter { name -> chosen.none { it.name == name } }
        .sortedBy { it.lowercase() })
    val sizes = installed.toMap()

    if (rows.isEmpty()) {
        Text(
            // ⚠ It names the button below it rather than another screen. The
            // Settings tab still imports; it is no longer the only way.
            if (onImport != null) "No LoRAs on this phone yet. Add one and it will appear here."
            else "No LoRAs on this phone yet. Import a .safetensors on the Settings " +
                "tab and it will appear here.",
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (name in rows) {
            val entry = chosen.firstOrNull { it.name == name }
            val missing = name !in installedNames
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = entry != null,
                    onCheckedChange = { on ->
                        commit(
                            if (on) chosen + LoraSpec.Entry(name, LoraSpec.FULL)
                            else chosen.filterNot { it.name == name },
                        )
                    },
                )
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        // ⚠ The MB is why the size is carried at all: an
                        // adapter is tens of megabytes and a merged checkpoint
                        // is gigabytes, and someone who picked the wrong file
                        // sees it here rather than at Run.
                        if (missing) "not on this phone — import it or untick it"
                        else "${(sizes[name] ?: 0L) shr 20} MB",
                        style = NoteTextStyle,
                        color = if (missing) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⭐⭐ ⋮ = this LoRA's note. A dot beside it says one exists, so
                // the LoRAs that have a recipe written down are findable at a
                // glance (the mockup's indicator).
                if (onSaveNote != null) {
                    if (!notes[name].isNullOrBlank()) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    IconButton(onClick = { noting = name }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "note for $name",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // ⚠ Only for a LoRA that is ON. A slider under an unticked row is a
            // control for a thing that will not happen.
            if (entry != null) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 4.dp),
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
                    Text(
                        LoraSpec.number(entry.strength),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).width(40.dp),
                    )
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
@Composable
fun LoraNoteDialog(
    name: String,
    note: String,
    onSave: (String) -> Unit,
    onAddToPrompt: ((String) -> Unit)?,
    onDismiss: () -> Unit,
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
                            "Trigger words on the first line, then strength, " +
                                "settings, how to use it…",
                            style = NoteTextStyle,
                        )
                    },
                )
                val trigger = com.abrah.nightmare.LoraNotes.trigger(text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(text.trim()))
                            toast("Note copied")
                        },
                    ) { Text("Copy") }
                    // ⚠ DIMMED, never removed, and it says why (`docs/UI.md`
                    // §8.18): the row must not change shape with the graph.
                    OutlinedButton(
                        enabled = trigger != null,
                        onClick = {
                            val t = trigger ?: return@OutlinedButton
                            if (onAddToPrompt == null) {
                                toast("No prompt node is wired into this sampler — copy it instead")
                            } else {
                                onAddToPrompt(t)
                                toast("Added to the prompt: $t")
                            }
                        },
                        modifier = if (onAddToPrompt == null) Modifier.alpha(0.38f) else Modifier,
                    ) { Text("Add to prompt") }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text("Done") } },
    )
}
