package com.abrah.nightmare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Build
import com.abrah.nightmare.Family
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.UpscalerBuild
import com.abrah.nightmare.UpscalerSpec
import com.abrah.nightmare.ModelSpec

/**
 * What the model list needs to draw one row.
 *
 * ⚠ A plain data class rather than a `ModelSpec` plus a `Context`: this screen
 * is previewable and testable only if it can be handed a finished state, and
 * "is it installed" is a disk read that a preview cannot do.
 */
/**
 * ⭐ One upscaler, as its card shows it.
 *
 * ⚠ Deliberately NOT a [ModelRow] with a flag. An upscaler has no `selected`
 * (nothing global points at one — a NODE names it), no `missing` list (it is
 * one file), and no family. Three fields that would always be dead is what a
 * separate type costs less than.
 */
data class UpscalerRow(
    val spec: UpscalerSpec,
    /** ⚠ Null when no published tier loads on this HTP — the card must say so. */
    val build: UpscalerBuild?,
    val installed: Boolean,
    val progress: ModelInstaller.Progress? = null,
    val onDisk: Long = 0,
)

data class ModelRow(
    val spec: ModelSpec,
    /**
     * ⭐ The published build this device can load, or **null** when there is
     * none. ⚠ Null is a real state and the row must SAY so: SDXL publishes one
     * tier, so on an older HTP the honest answer is "not for this phone", not a
     * Download button that spends 3.5 GB before failing at load.
     */
    val build: Build? = null,
    val installed: Boolean,
    val selected: Boolean,
    /** Non-null while this model is being fetched. */
    val progress: ModelInstaller.Progress? = null,
    val onDisk: Long = 0,
    /**
     * ⭐ Required files that are absent, for an imported model that arrived
     * incomplete.
     *
     * ⚠ Passed in for the same reason [installed] is: this is a disk read, and
     * the screen has to stay renderable from a finished state so a preview and
     * a golden can draw it. ⚠ Only meaningful when
     * [ModelSpec.isCustom] — a built-in that is missing files has a Download
     * button, so the list would be noise.
     */
    val missing: List<String> = emptyList(),
)

/**
 * The model picker: what exists, what is installed, and which one the app uses.
 *
 * ⭐ This screen is the difference between a demo and something a person can
 * use. Before it, a model reached the phone only by an `adb push` from a
 * developer's machine.
 */
@Composable
fun ModelsScreen(
    rows: List<ModelRow>,
    busy: Boolean,
    error: String?,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onSelect: (ModelSpec) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * ⭐ Import a checkpoint the user already has, as a zip.
     *
     * ⚠ Nullable so this screen still renders in a preview and a golden, where
     * there is no `ActivityResultLauncher` to hand it. Null hides the button
     * rather than showing a dead one.
     */
    onImport: ((name: String) -> Unit)? = null,
    /**
     * ⭐ The name of an import in flight, or null. Drawn as a banner ABOVE the
     * family tabs — an imported model has no row to hang progress on until the
     * scan finds it, which is exactly why nothing was visible before.
     */
    importing: String? = null,
    importProgress: ModelInstaller.Progress? = null,
    /**
     * ⭐⭐ The second KIND of model — see `Upscalers.kt`. Empty renders no tab
     * at all, which is what a preview and a golden with no catalogue want.
     */
    upscalers: List<UpscalerRow> = emptyList(),
    onInstallUpscaler: (UpscalerSpec) -> Unit = {},
    onDeleteUpscaler: (UpscalerSpec) -> Unit = {},
) {
    // ⚠⚠ The confirm is intercepted HERE rather than inside the card, so the
    // card stays a dumb row and there is exactly one place that can delete a
    // model. A dialog per card would be one per row on screen.
    var deleting by remember { mutableStateOf<ModelSpec?>(null) }
    // ⚠ The same pattern for the other catalogue: ONE owner of the confirm, so
    // there is exactly one place that can delete an upscaler. It shipped
    // without a confirm at all while the checkpoint beside it had one — the
    // inconsistency the phone reported.
    var deletingUpscaler by remember { mutableStateOf<UpscalerSpec?>(null) }

    // ⚠ No header and no `statusBarsPadding` any more: [LibraryScreen] owns
    // both, because this screen is now a TAB rather than a whole screen. A
    // second inset here would double it.
    Column(modifier.fillMaxSize()) {
        if (error != null) {
            Text(
                error,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        // ⭐ "Something is happening", for the one case with no row to say so.
        if (importing != null) {
            Card(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.importing_model, importing),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        // ⚠ The PHASE, not a percentage: a zip's uncompressed
                        // size is unknown until it is read, so there is no
                        // honest percentage to show during the unpack.
                        importProgress?.phase ?: stringResource(R.string.importing_working),
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⚠ Indeterminate whenever the total is unknown, which is
                    // most of an import. A bar parked at 100% through a minute
                    // of unpacking reads as a hang.
                    val p = importProgress
                    if (p != null && p.total > 0) {
                        LinearProgressIndicator(
                            progress = { p.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
        // ⭐⭐ One sub-tab per FAMILY, swipeable.
        //
        // ⚠ Fifteen rows in one list is not merely long, it is misleading: an
        // SD 1.5 entry and an SDXL entry look alike and differ by 3.5x in
        // download, by a whole generation of chip, and in what they can even
        // run on. The split is the honest presentation of a catalogue with two
        // families in it, and it is where a third would go.
        //
        // ⚠ Built from the rows actually PRESENT rather than from a hardcoded
        // pair, so a family with no entries shows no tab instead of an empty
        // page — and adding one needs no change here.
        val families = Family.entries.filter { f -> rows.any { it.spec.family == f } }
        // ⚠⚠ Upscalers get their OWN tab rather than rows among the
        // checkpoints. They are not a third family — they are a different kind
        // of model entirely (`Upscalers.kt`): 8-24 MB rather than 1-3.7 GB, no
        // launch contract, and nothing to "use". A row that looked like a
        // checkpoint but had no Use button would read as a broken checkpoint.
        val hasUpscalers = upscalers.isNotEmpty()
        SwipeTabs(
            labels = families.map { it.label } + if (hasUpscalers) listOf(stringResource(R.string.upscalers)) else emptyList(),
            modifier = Modifier.padding(top = 8.dp),
        ) { page ->
            if (hasUpscalers && page == families.size) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            // ⚠ The contrast with the checkpoint tabs is the
                            // point: these are megabytes, not gigabytes, and a
                            // user who has learned to fear this screen's
                            // download sizes should be told immediately.
                            stringResource(R.string.upscalers_note),
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(upscalers, key = { it.spec.id }) { row ->
                        UpscalerCard(row, busy, onInstallUpscaler, onCancel) {
                            deletingUpscaler = it
                        }
                    }
                }
                return@SwipeTabs
            }
            val family = families[page]
            // ⭐ Installed first, and the one in use at the very top.
            //
            // ⚠ The catalogue order is a curator's order -- it says which
            // checkpoints are worth having. Once a user HAS some, that stops
            // being the useful order: what they came here to do is switch
            // between the ones already on the phone, and those were scattered
            // among ten they have not downloaded. ⚠ Stable within each group,
            // so the curated order still shows through.
            val shown = rows.filter { it.spec.family == family }
                .sortedByDescending { (if (it.selected) 2 else 0) + (if (it.installed) 1 else 0) }
            LazyColumn(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ⚠⚠ Says the size out loud, PER FAMILY, and inside the page so
                // it describes the list under it. A gigabyte is a thing a person
                // should be told about BEFORE they tap, not discovered
                // afterwards on a mobile plan -- and one sentence covering both
                // families had to say "1 GB or 3.5 GB", which is the shape of
                // warning people learn to skip.
                // ⭐⭐ Bring your own checkpoint, FIRST in the list.
                //
                // ⚠ It was at the bottom, below fifteen catalogue rows, on the
                // reasoning that it is the rarer path — and it was simply not
                // found. A user who already has a checkpoint is not browsing
                // ours, and making them scroll past all of it to reach the one
                // thing they came for is the wrong default. Reported from the
                // phone 2026-09-10.
                //
                // ⚠ On every family tab, because the family is INFERRED from the
                // archive rather than chosen — a zip picked on the SD 1.5 tab
                // that turns out to be SDXL lands correctly on the other one.
                if (onImport != null) {
                    item { ImportCard(busy, onImport) }
                }
                item {
                    Text(
                        when (family) {
                            Family.SD15 -> "About 1 GB each. Use Wi-Fi."
                            // ⚠ The free-space figure is the one that surprises:
                            // the archive and its unpacked copy are both on disk
                            // at once, so a 3.5 GB download needs ~7.5 GB free.
                            else -> "About 3.5 GB each, and ~7.5 GB free while " +
                                "it unpacks. Use Wi-Fi."
                        },
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(shown, key = { it.spec.id }) { row ->
                    ModelCard(row, busy, onInstall, onCancel, { deleting = it }, onSelect)
                }
            }
        }
    }

    // ⚠ Same shape as the checkpoint confirm below, deliberately: the number
    // is smaller but the interaction must not be a different one.
    deletingUpscaler?.let { spec ->
        val row = upscalers.firstOrNull { it.spec.id == spec.id }
        AlertDialog(
            onDismissRequest = { deletingUpscaler = null },
            title = { Text("Delete ${spec.label}?") },
            text = {
                Text(
                    "Frees ${mb(row?.onDisk ?: 0L)} MB. Getting it back is a " +
                        "${mb(row?.build?.bytes ?: 0L)} MB download. " +
                        // ⚠ Says what else changes, as the checkpoint dialog
                        // does — here it is a FLOW that breaks, not a selection.
                        "Any flow with an Upscale node set to it will fail until " +
                        "you install it again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteUpscaler(spec)
                    deletingUpscaler = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingUpscaler = null }) { Text("Keep") }
            },
        )
    }

    // ⭐⭐ A confirm before a delete that costs a ~1 GB re-download on a mobile
    // plan. ⚠ The button sat next to "use", enabled, one tap from gone -- and
    // deleting the SELECTED model also moves the selection, so a mis-tap changed
    // what every node in every graph would render with.
    deleting?.let { spec ->
        val row = rows.firstOrNull { it.spec.id == spec.id }
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${spec.label}?") },
            text = {
                Text(
                    buildString {
                        append("Frees ${mb(row?.onDisk ?: 0L)} MB. ")
                        // ⚠⚠ A custom model has NO archive -- there is no URL
                        // that could produce it again. Quoting a download size
                        // used to `spec.best.bytes` on an empty build list,
                        // which threw; and even fixed, offering a number would
                        // promise a re-download that does not exist. The user's
                        // own zip is the only way back, and they have to still
                        // have it.
                        val bytes = row?.build?.bytes ?: spec.best?.bytes
                        if (spec.isCustom || bytes == null) {
                            append("You imported it, so getting it back means importing the zip again.")
                        } else {
                            append("Getting it back is a ${mb(bytes)} MB download.")
                        }
                        // ⚠ Says what ELSE changes. The selection moving is not
                        // something a user would predict from "delete".
                        if (row?.selected == true) {
                            append("\n\nIt is the model in use, so another will be selected.")
                        }
                    },
                    style = LogTextStyle,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(spec); deleting = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The "import a zip" row, plus the name dialog.
 *
 * ⚠⚠ A name is asked for BEFORE the picker opens, and it is not optional. The
 * name is the directory, the id, and the value written into every node's
 * `model` param in every workflow saved against it — so it cannot be derived
 * from a content-provider display name that may be `document.zip` or absent
 * entirely, and it cannot be renamed later without rewriting saved graphs.
 */
@Composable
private fun ImportCard(busy: Boolean, onImport: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    // ⚠ [ImportCallout] owns the look; this file owns the naming dialog that
    // follows. The three importers in the app share one card shape.
    ImportCallout(
        title = "Import a checkpoint",
        body =
            // ⚠ Says what the app CANNOT do, because the alternative is a user
            // picking a `.safetensors` and reading "not a checkpoint" without
            // knowing why. Conversion is a PC step and there is no runtime
            // compiler on the NPU.
            "A zip of QNN model files, converted on a PC. SD 1.5 or SDXL — it works out which.",
        enabled = !busy,
        onImport = { name = ""; naming = true },
    )

    if (naming) {
        val trimmed = name.trim()
        val reserved = trimmed.isNotEmpty() && com.abrah.nightmare.CustomModels.isReserved(trimmed)
        val ok = com.abrah.nightmare.CustomModels.isValidName(trimmed) && !reserved
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name it") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("model name") },
                    )
                    Text(
                        when {
                            reserved -> "That is a built-in model's name; pick another."
                            trimmed.isNotEmpty() && !ok -> "No slashes, colons or leading dots."
                            // ⚠ Warns BEFORE the picker, not after the copy: an
                            // import is gigabytes, and finding out afterwards
                            // that the name is permanent is finding out too late.
                            else -> "This becomes the folder name and the id saved " +
                                "into every workflow that uses it."
                        },
                        style = LogTextStyle,
                        color = if (reserved) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { naming = false; onImport(trimmed) },
                    enabled = ok,
                ) { Text("Pick a zip") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun ModelCard(
    row: ModelRow,
    busy: Boolean,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onSelect: (ModelSpec) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (row.selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        row.spec.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (row.selected) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        when {
                            row.progress != null -> row.progress.phase
                            row.installed && row.selected -> "in use  ${mb(row.onDisk)} MB"
                            row.installed -> stringResource(R.string.installed_mb, mb(row.onDisk))
                            // ⚠⚠ A custom model is never "not installed" and
                            // never "unsupported": its files are already on the
                            // phone, so the only failure it can have is being
                            // INCOMPLETE -- and it must say which files, because
                            // there is no Download button that could fix it and
                            // no other place that would ever tell the user.
                            //
                            // ⚠ Falling through to the `build == null` arm below
                            // would have said "this device cannot run it", which
                            // is a claim we cannot make: nothing in a QNN context
                            // directory says which HTP it was compiled for.
                            row.spec.isCustom ->
                                "incomplete -- missing ${row.missing.joinToString()}"
                            // ⚠⚠ The size of the build THIS DEVICE would get,
                            // not of the preferred one: they differ by up to
                            // 60 MB between tiers, and quoting the wrong one is
                            // quoting a number the user cannot reach.
                            row.build != null -> stringResource(R.string.not_installed_mb, mb(row.build.bytes))
                            else -> stringResource(R.string.cannot_run_it)
                        },
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ⭐ The family and the size it renders at.
                    //
                    // ⚠ The whole point of curating ten SDXL checkpoints rather
                    // than listing all 46 is that a user can tell them apart,
                    // and "DreamShaper XL" against "ChilloutMix" says nothing
                    // about which one costs 3.5 GB or which produces a 1024
                    // picture.
                    //
                    // ⚠⚠ **This says NATIVE, and the chips below say what is
                    // chosen.** The two differ now: an SD 1.5 model ships
                    // resolution patches and the selected size may be any of
                    // them. Native is still the right thing on the line that
                    // compares models to each other -- it is a property of the
                    // checkpoint, where the chosen size is a property of the
                    // session.
                    Text(
                        // ⭐ Family, size, and the BUILD TIER -- which is a
                        // statement about the chip, not a detail: `_min` is
                        // ~2.5x slower per image than `_8gen2`, and a user
                        // comparing two phones deserves to see why.
                        // ⭐ For a custom model the third slot says "imported"
                        // where a built-in names its build tier. ⚠ That word is
                        // doing real work: it is the only thing on the card that
                        // explains why this row has no size, no tier and no
                        // Download, and the family and resolution beside it were
                        // INFERRED from the files rather than published by us.
                        "${row.spec.family.label}  ${row.spec.native}" +
                            when {
                                row.spec.isCustom -> "  imported"
                                row.build != null -> "  ${row.build.tier.removePrefix("_")}"
                                else -> ""
                            },
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ModelAction(row, busy, onInstall, onCancel, onDelete, onSelect)
            }
            val p = row.progress
            if (p != null) {
                // ⚠ Indeterminate during extraction: the unpacked size is not
                // known up front, and a bar that sat at 100% through a minute of
                // unzipping would read as a hang.
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun ModelAction(
    row: ModelRow,
    busy: Boolean,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onSelect: (ModelSpec) -> Unit,
) {
    when {
        row.progress != null -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        // ⚠ No delete on the model in use: removing it would leave the backend
        // pointed at a directory that is gone. ModelInstaller refuses it too --
        // this only keeps the button from being offered.
        row.installed && row.selected -> OutlinedButton(
            onClick = { onDelete(row.spec) },
            enabled = false,
        ) { Text(stringResource(R.string.in_use)) }
        row.installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onDelete(row.spec) }, enabled = !busy) { Text(stringResource(R.string.delete)) }
            Button(onClick = { onSelect(row.spec) }, enabled = !busy) { Text(stringResource(R.string.use)) }
        }
        // ⚠⚠ An INCOMPLETE import: the only action is to remove it. There is no
        // Download that could complete it -- we have no URL for the user's own
        // files -- so offering one would be a button that cannot work, which is
        // the mistake this whole arm exists to avoid. ⚠ Before [ModelSpec.isCustom]
        // existed this fell into the `build == null` arm and read "Unsupported",
        // blaming the phone for a truncated zip.
        row.spec.isCustom -> OutlinedButton(
            onClick = { onDelete(row.spec) },
            enabled = !busy,
        ) { Text(stringResource(R.string.remove)) }
        // ⚠⚠ No build this HTP can load: the button is DISABLED and says why,
        // rather than being offered and failing after a multi-gigabyte
        // download. The row's own line already names the arch it needs.
        row.build == null -> OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.unsupported)) }
        else -> Button(onClick = { onInstall(row.spec) }, enabled = !busy) { Text(stringResource(R.string.download)) }
    }
}


/**
 * One upscaler's card.
 *
 * ⚠⚠ **Built to the SAME shape as [ModelCard], and the first version was not.**
 * It had a bare `TextButton("Delete")` that deleted on the first tap while the
 * checkpoint beside it needed a confirm; its labels and button styles were
 * different; and it stated its status in a sentence where the other card uses a
 * line plus an action. Reported from the phone as "the upscalers tab isn't
 * consistent with the other model tabs", and it was — two catalogues of models,
 * one screen, two different interaction languages. `docs/UI.md` §6 has the rule
 * this broke and why it was mine to get right the first time.
 *
 * ⚠ The one deliberate difference is that there is no "Use": nothing selects an
 * upscaler globally, a flow's Upscale node names one. That absence is a design
 * decision; the rest was an oversight.
 */
@Composable
private fun UpscalerCard(
    row: UpscalerRow,
    busy: Boolean,
    onInstall: (UpscalerSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (UpscalerSpec) -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.spec.label, style = MaterialTheme.typography.titleMedium)
                    // ⚠ Same three-line shape as a checkpoint: what it is, what
                    // it costs, and what it is for.
                    Text(
                        when {
                            row.installed -> stringResource(R.string.installed_mb, mb(row.onDisk))
                            row.build != null -> stringResource(R.string.not_installed_mb, mb(row.build.bytes))
                            else -> stringResource(R.string.cannot_run_it)
                        },
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        row.spec.about +
                            (row.build?.let { "  ${it.tier}" } ?: ""),
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    row.progress != null ->
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    row.installed -> OutlinedButton(
                        onClick = { onDelete(row.spec) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.delete)) }
                    // ⚠⚠ Disabled and SAYING WHY, exactly as a checkpoint does:
                    // never a Download that spends the bytes and then fails to
                    // load.
                    row.build == null ->
                        OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.unsupported)) }
                    else -> Button(
                        onClick = { onInstall(row.spec) },
                        enabled = !busy,
                    ) { Text(stringResource(R.string.download)) }
                }
            }
            val p = row.progress
            if (p != null) {
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }
    }
}

private fun mb(bytes: Long): Long = bytes shr 20
