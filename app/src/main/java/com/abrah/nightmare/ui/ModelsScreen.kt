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
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color

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
/**
 * ⭐ One download on the Tools tab — today only the segmenter
 * (`docs/SEGMENTER.md`). ⚠ Its own tab, the user's call 2026-09-16: it is not a
 * checkpoint and has no Use button, and a row among checkpoints without one
 * would read as a broken checkpoint (the upscalers' reasoning).
 */
data class ToolRow(
    val label: String,
    val bytes: Long,
    val installed: Boolean,
    val onDisk: Long = 0,
    val progress: ModelInstaller.Progress? = null,
)

/**
 * ⭐ One textual-inversion embedding, on the Tools tab beside the segmenter.
 *
 * ⚠ Upstream `local-dream` has an Embedding Manager (import/list/delete a
 * `.safetensors` by name); DreamUI dropped it along with `config.json` and
 * the rest of its Kotlin-layer simplifications. The BACKEND never lost the
 * feature — `text_encoder->loadTextualInversions()` already loads whatever is
 * in `embeddings/` (two directories above `--model_dir`) at launch and
 * matches a prompt TOKEN against a file's name — so this is purely the app
 * half growing back. `docs/ARCHITECTURE.md`.
 */
data class EmbeddingRow(val name: String, val bytes: Long)

data class UpscalerRow(
    val spec: UpscalerSpec,
    /** ⚠ Null when no published tier loads on this HTP — the card must say so. */
    val build: UpscalerBuild?,
    val installed: Boolean,
    val progress: ModelInstaller.Progress? = null,
    val onDisk: Long = 0,
)

/**
 * ⭐⭐ The video models, as one row — because they are one decision.
 *
 * ⚠⚠ **A THIRD kind of model**, next to a checkpoint and an upscaler, and
 * it gets its own tab for the same reason they do. It is not a family: there
 * is no launch contract, nothing to "Use", no `--type`, and the catalogue
 * cannot describe it at all ([com.abrah.nightmare.npu.NpuFiles]).
 *
 * ⚠ **One row for 13 files**, not thirteen. They are useless individually —
 * the pipeline maps all of them — so thirteen cards would be thirteen
 * decisions a user cannot make separately.
 *
 * @param supported null while the canary has not run; false means this chip
 *   refused a real context binary and the download would be wasted.
 */
data class VideoRow(
    val installedBytes: Long,
    val totalBytes: Long,
    /** ⚠ Graphs, not host weights — [weightsMissing] is the other half. */
    val missing: List<String>,
    /**
     * ⚠ The host-side weights, which download with the graphs since
     * 2026-09-13 — they are 0.7% of the bytes and the app cannot render a
     * frame without them, so [VideoInstaller] fetches them FIRST.
     */
    val weightsMissing: List<String>,
    val supported: Boolean? = null,
    val progress: ModelInstaller.Progress? = null,
) {
    val complete: Boolean get() = missing.isEmpty() && weightsMissing.isEmpty()
}

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
    /**
     * ⭐⭐ A Use waiting on a flow choice — see
     * [com.abrah.nightmare.HarnessViewModel.PendingUse]. Null when nothing is
     * pending, which is almost always.
     */
    pendingUse: com.abrah.nightmare.HarnessViewModel.PendingUse? = null,
    recipes: List<com.abrah.nightmare.canvas.Recipe> = emptyList(),
    onConfirmUse: (ModelSpec?, com.abrah.nightmare.canvas.Recipe?) -> Unit = { _, _ -> },
    onCancelUse: () -> Unit = {},
    /** ⭐ Use on an upscaler row — opens the Upscale flow. */
    onUseUpscaler: (UpscalerSpec) -> Unit = {},
    /** ⭐ Use on the video row — offers the two video flows. */
    onUseVideo: () -> Unit = {},
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
    /** ⭐ Bring your own upscaler `.bin`. Null hides the button. */
    onImportUpscaler: (() -> Unit)? = null,
    /**
     * ⭐⭐ The video models. Null renders no tab at all — which is what a
     * preview, a golden, and a phone whose chip cannot run them all want.
     */
    video: VideoRow? = null,
    onInstallVideo: () -> Unit = {},
    onDeleteVideo: () -> Unit = {},
    /**
     * ⚠⚠ Asked for by the TAB, not at app start: it runs the canary, which
     * brings the whole QNN backend up and costs seconds on a cold app. A user
     * who never opens this tab never pays for it.
     */
    onProbeVideo: () -> Unit = {},
    /** ⭐ The Tools tab. Null renders no tab, as for [video]. */
    segmenter: ToolRow? = null,
    onInstallSegmenter: () -> Unit = {},
    onDeleteSegmenter: () -> Unit = {},
    /**
     * ⭐ The installed embeddings, on the Tools tab. Null hides the whole
     * section — same convention as [segmenter] and [video] — which is what a
     * preview and a golden with no picker want.
     */
    embeddings: List<EmbeddingRow>? = null,
    onImportEmbedding: (() -> Unit)? = null,
    onDeleteEmbedding: ((String) -> Unit)? = null,
) {
    // ⚠⚠ The confirm is intercepted HERE rather than inside the card, so the
    // card stays a dumb row and there is exactly one place that can delete a
    // model. A dialog per card would be one per row on screen.
    // ⭐⭐⭐ **Use asks what to open with it**, rather than silently retargeting
    // whatever happens to be on the canvas.
    //
    // ⚠⚠ Before this, someone who came here to START something with a
    // checkpoint pressed Use, went to Flows, and picked a recipe — and if the
    // canvas held unsaved work, Use had already rewritten it on the way past
    // with nothing said. Asked for 2026-09-15.
    pendingUse?.let { p ->
        // ⭐⭐⭐ **Use asks what to open with it**, rather than silently
        // retargeting whatever happens to be on the canvas.
        //
        // ⚠⚠ The flows live in the TEXT slot as rows, not in `confirmButton`.
        // A Column of buttons in that slot is laid out by AlertDialog in a Row
        // beside the dismiss button, which is why the first version came out a
        // mess — reported 2026-09-15. A dialog's action slots hold ONE action
        // each; a list of choices is content.
        AlertDialog(
            onDismissRequest = onCancelUse,
            title = { Text(p.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ⚠⚠ The warning first, and ONLY when it is true. A dialog
                    // that always warns is one people learn to tap through.
                    if (p.unsavedFlow) {
                        Text(
                            "The flow on the canvas has not been saved — opening one of " +
                                "these replaces it.",
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    // ⚠⚠ The offer is computed by the CALLER
                    // ([HarnessViewModel.PendingUse.offer]) and not filtered here.
                    // It used to be `recipes.filter { it.usesCheckpoint }`, which
                    // asks "does this flow need a checkpoint" and never "can it
                    // use THIS one" — so Use on Z-Image offered Inpaint, which
                    // Z-Image has no type for. Reported 2026-09-20.
                    for (r in p.offer) {
                        Surface(
                            onClick = { onConfirmUse(p.spec, r) },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                Text(r.label, fontWeight = FontWeight.Medium)
                                Text(
                                    r.about,
                                    style = LogTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // ⭐ …and the old behaviour, named: keep what is open and
                    // just point it at this checkpoint.
                    // ⚠⚠ CHECKPOINTS only. An upscaler and the video models are
                    // not selected globally, so there is nothing for "keep the
                    // flow" to do — offering it would be a button that closes the
                    // dialog and changes nothing.
                    if (p.spec != null) Surface(
                        onClick = { onConfirmUse(p.spec, null) },
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Keep the flow on the canvas",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            },
            // ⚠ ONE action button. Choosing a flow IS the confirmation, so
            // there is nothing for a confirm button to do.
            confirmButton = { TextButton(onClick = onCancelUse) { Text("Cancel") } },
        )
    }


    var deleting by remember { mutableStateOf<ModelSpec?>(null) }
    // ⚠ The same pattern for the other catalogue: ONE owner of the confirm, so
    // there is exactly one place that can delete an upscaler. It shipped
    // without a confirm at all while the checkpoint beside it had one — the
    // inconsistency the phone reported.
    var deletingUpscaler by remember { mutableStateOf<UpscalerSpec?>(null) }
    // ⚠⚠⚠ …and the same for the video models, which is the BIGGEST delete in
    // the app and shipped without one. A checkpoint asks before costing a ~1 GB
    // re-download and an upscaler asks before costing 24 MB; this threw away
    // 8.6 GB on a single tap. Reported from the phone, 2026-09-13.
    var deletingVideo by remember { mutableStateOf(false) }
    var deletingSegmenter by remember { mutableStateOf(false) }
    var deletingEmbedding by remember { mutableStateOf<String?>(null) }

    // ⚠ No header and no `statusBarsPadding` any more: [LibraryScreen] owns
    // both, because this screen is now a TAB rather than a whole screen. A
    // second inset here would double it.
    Column(modifier.fillMaxSize()) {
        if (error != null) {
            ErrorNotice(error, Modifier.padding(top = 8.dp))
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
            labels = families.map { it.label } +
                (if (hasUpscalers) listOf(stringResource(R.string.upscalers)) else emptyList()) +
                (if (video != null) listOf(stringResource(R.string.video)) else emptyList()) +
                (if (segmenter != null || embeddings != null) listOf(stringResource(R.string.tools)) else emptyList()),
            modifier = Modifier.padding(top = 8.dp),
        ) { page ->
            // ⚠ LAST again, after Video, so adding it moved no existing index.
            if ((segmenter != null || embeddings != null) &&
                page == families.size + (if (hasUpscalers) 1 else 0) + (if (video != null) 1 else 0)
            ) {
                LazyColumn(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        Text(
                            stringResource(R.string.tools_note),
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (segmenter != null) {
                        item { ToolCard(segmenter, busy, onInstallSegmenter, onCancel) { deletingSegmenter = true } }
                    }
                    // ⭐⭐ **The embeddings IMPORT lives in Settings, not here** —
                    // the user's call, 2026-09-20: *"remove embedding import btn
                    // from Tools tab, it is redundant. only keep the one in
                    // settings."*
                    //
                    // ⚠⚠ It was in BOTH, which is the duplicate-surface
                    // mistake `docs/ARCHITECTURE.md` §5.6 keeps naming: two
                    // callouts, two places to find the same file picker, and no
                    // rule saying which one is the home. Importing is a
                    // one-off setup act, which is what Settings is for.
                    //
                    // ⚠ The installed LIST stays here, beside the segmenter,
                    // because that is where a person looks for what is on the
                    // phone — removing the button did not remove the tab's job.
                    if (embeddings != null) {
                        items(embeddings, key = { it.name }) { row ->
                            DownloadCard(
                                title = row.name,
                                emphasised = false,
                                status = stringResource(R.string.installed_mb, mb(row.bytes)),
                                detail = "",
                                progress = null,
                            ) {
                                OutlinedButton(
                                    onClick = { deletingEmbedding = row.name },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
                return@SwipeTabs
            }
            // ⚠ LAST, after the upscalers, so adding it moved no existing index.
            if (video != null && page == families.size + (if (hasUpscalers) 1 else 0)) {
                VideoModelsTab(
                    video, busy, onInstallVideo, onCancel,
                    onConfirmDelete = { deletingVideo = true },
                    onProbe = onProbeVideo,
                    onUse = onUseVideo,
                )
                return@SwipeTabs
            }
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
                    // ⭐ Bring your own — a bare `.bin`, same shape as
                    // checkpoint import. Reported 2026-09-18.
                    if (onImportUpscaler != null) {
                        item {
                            ImportCallout(
                                title = stringResource(R.string.import_upscaler_title),
                                body = stringResource(R.string.import_upscaler_body),
                                enabled = !busy,
                                onImport = onImportUpscaler,
                            )
                        }
                    }
                    items(upscalers, key = { it.spec.id }) { row ->
                        UpscalerCard(
                            row, busy, onInstallUpscaler, onCancel,
                            onDelete = { deletingUpscaler = it },
                            onUse = onUseUpscaler,
                        )
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
                            // ⚠ Measured 2026-09-16 on an 11.4 GB phone: 6.4 s a
                            // step, and killed by Android while other apps were
                            // in use. Said here, before 4 GB is downloaded.
                            Family.ANIMA -> "About 4.3 GB each, and ~9 GB free while it " +
                                "unpacks. Slow: about 80 s a picture. Close other apps " +
                                "while it renders. Use Wi-Fi."
                            // ⚠ Plain files straight into place, so no unpack
                            // headroom — but only an 8 Elite or newer runs them.
                            Family.FLUX2 -> "About 6.7 GB. 8 Elite or newer only. Any size " +
                                "from 512 to 2048. Use Wi-Fi."
                            // ⚠⚠ Said before 8.8 GB is downloaded: upstream's own
                            // build crashes on the dev phone (8 Elite), and works
                            // on some 8 Elite Gen 5 phones (the user, 2026-09-19).
                            Family.ZIMAGE -> "About 8.8 GB. 8 Elite or newer only. Still " +
                                "maturing: it crashes on some 8 Elite phones. Use Wi-Fi."
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

    // ⚠ All three kinds of model ask through [ConfirmDelete] — one dialog, one
    // verb, one pair of buttons (`docs/UI.md` §8.2). They shipped as three
    // copies with two dismiss labels and two confirm styles.
    deletingUpscaler?.let { spec ->
        val row = upscalers.firstOrNull { it.spec.id == spec.id }
        ConfirmDelete(
            title = "Delete ${spec.label}?",
            body = "Frees ${mb(row?.onDisk ?: 0L)} MB. Getting it back is a " +
                "${mb(row?.build?.bytes ?: 0L)} MB download. " +
                // ⚠ Says what else changes, as the checkpoint dialog does —
                // here it is a FLOW that breaks, not a selection.
                "Any flow with an Upscale node set to it will fail until " +
                "you install it again.",
            onConfirm = { onDeleteUpscaler(spec) },
            onDismiss = { deletingUpscaler = null },
        )
    }

    if (deletingSegmenter && segmenter != null) {
        ConfirmDelete(
            title = "Delete ${segmenter.label}?",
            body = "Frees ${mb(segmenter.onDisk)} MB. Getting it back is a " +
                "${mb(segmenter.bytes)} MB download. Any flow with a tapped mask " +
                "will refuse to run until you install it again.",
            onConfirm = onDeleteSegmenter,
            onDismiss = { deletingSegmenter = false },
        )
    }

    deletingEmbedding?.let { name ->
        ConfirmDelete(
            title = "Delete $name?",
            body = "Any prompt naming it renders without that embedding — no error, " +
                "just the ordinary tokens instead.",
            onConfirm = { onDeleteEmbedding?.invoke(name) },
            onDismiss = { deletingEmbedding = null },
        )
    }

    if (deletingVideo && video != null) {
        ConfirmDelete(
            title = stringResource(R.string.video_delete_title),
            // ⚠⚠ The number is the whole point of the dialog. "Frees 8198 MB"
            // and "getting it back is an 8198 MB download" are the same figure
            // said twice on purpose — the second is the one that stops the tap.
            body = stringResource(
                R.string.video_delete_body,
                mb(video.installedBytes), mb(video.totalBytes),
            ),
            onConfirm = onDeleteVideo,
            onDismiss = { deletingVideo = false },
        )
    }

    // ⭐⭐ A confirm before a delete that costs a ~1 GB re-download on a mobile
    // plan. ⚠ The button sat next to "use", enabled, one tap from gone -- and
    // deleting the SELECTED model also moves the selection, so a mis-tap changed
    // what every node in every graph would render with.
    deleting?.let { spec ->
        val row = rows.firstOrNull { it.spec.id == spec.id }
        ConfirmDelete(
            title = "Delete ${spec.label}?",
            body = buildString {
                append("Frees ${mb(row?.onDisk ?: 0L)} MB. ")
                // ⚠⚠ A custom model has NO archive -- there is no URL that
                // could produce it again. The user's own zip is the only way
                // back, and they have to still have it.
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
            onConfirm = { onDelete(spec) },
            onDismiss = { deleting = null },
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
            "A zip of QNN model files, converted on a PC. SD 1.5, SDXL or Anima — it works out which.",
        enabled = !busy,
        onImport = { name = ""; naming = true },
    )

    if (naming) {
        val trimmed = name.trim()
        val reserved = trimmed.isNotEmpty() && com.abrah.nightmare.CustomModels.isReserved(trimmed)
        // ⭐ EMPTY is allowed: the zip's own file name is used
        // ([com.abrah.nightmare.CustomModels.nameFromFile]). Asked for 2026-09-16.
        val ok = trimmed.isEmpty() ||
            (com.abrah.nightmare.CustomModels.isValidName(trimmed) && !reserved)
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name it") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Model name") },
                    )
                    Text(
                        when {
                            reserved -> "That is a built-in model's name; pick another."
                            trimmed.isNotEmpty() && !ok -> "No slashes, colons or leading dots."
                            // ⚠ Warns BEFORE the picker, not after the copy: an
                            // import is gigabytes, and finding out afterwards
                            // that the name is permanent is finding out too late.
                            trimmed.isEmpty() -> "Leave it empty to use the zip's file name. " +
                                "It becomes the folder name and the id saved into every workflow."
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

/**
 * ⭐⭐⭐ ONE row for every downloadable thing — checkpoint, upscaler, video.
 *
 * ⚠⚠ There were three hand-built cards and they had drifted exactly the way
 * `docs/ARCHITECTURE.md` §5.6 says two lookups will: a checkpoint's progress
 * read "downloading <its own name>" with no bytes, an upscaler's read nothing
 * at all, and only the video card counted bytes in flight. The design review,
 * 2026-09-15 — `docs/UI.md` §8.1 is the shape, and this is the only thing that
 * draws it.
 *
 * ⚠ Only [status], [detail] and [action] vary by kind. Progress is drawn HERE,
 * from the one [ModelInstaller.Progress], so the three cannot disagree again.
 */
@Composable
private fun DownloadCard(
    title: String,
    /** ⚠ In use — the one fact that also changes the card's colour and weight. */
    emphasised: Boolean,
    status: String,
    detail: String,
    progress: ModelInstaller.Progress?,
    action: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasised) {
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
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (emphasised) FontWeight.Bold else FontWeight.Normal,
                    )
                    Text(
                        // ⚠⚠⚠ While fetching, the LIVE byte count (`docs/UI.md`
                        // §7.6) — never a figure of completed files, which froze
                        // at 295 MB for twenty minutes on the video row.
                        if (progress != null && progress.total > 0) {
                            stringResource(R.string.mb_of_total, mb(progress.done), mb(progress.total))
                        } else {
                            status
                        },
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(detail, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                action()
            }
            if (progress != null) {
                // ⚠ Indeterminate while the total is unknown — an unzip. A bar
                // parked at 100% through a minute of unpacking reads as a hang.
                if (progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                // ⭐ The phase under the bar: which file, or "extracting".
                Text(progress.phase, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** ⚠ Fields on a status or detail line are joined by ONE separator (§8.1). */
private fun fields(vararg parts: String?): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun ModelCard(
    row: ModelRow,
    busy: Boolean,
    onInstall: (ModelSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onUse: (ModelSpec) -> Unit,
) {
    DownloadCard(
        title = row.spec.label,
        emphasised = row.selected,
        status = when {
            row.installed && row.selected -> stringResource(R.string.in_use_mb, mb(row.onDisk))
            row.installed -> stringResource(R.string.installed_mb, mb(row.onDisk))
            // ⚠⚠ A custom model is never "not installed" and never
            // "unsupported": its files are already on the phone, so the only
            // failure it can have is being INCOMPLETE -- and it must say which
            // files, because nothing else would ever tell the user.
            row.spec.isCustom -> stringResource(R.string.incomplete_missing, row.missing.joinToString())
            // ⚠⚠ The size of the build THIS DEVICE would get, not of the
            // preferred one: they differ by up to 60 MB between tiers.
            row.build != null -> stringResource(R.string.not_installed_mb, mb(row.build.bytes))
            else -> stringResource(R.string.cannot_run_it)
        },
        // ⭐ Family, NATIVE size, and the BUILD TIER -- `_min` is ~2.5x slower
        // per image than `_8gen2`, and a user comparing phones deserves to see
        // why. ⚠ An imported model says "imported" where a built-in names its
        // tier: its family and size were INFERRED from the files.
        detail = fields(
            row.spec.family.label,
            row.spec.native.toString(),
            when {
                row.spec.isCustom -> "imported"
                row.build != null -> row.build.tier.removePrefix("_")
                else -> null
            },
        ),
        progress = row.progress,
    ) {
        when {
            row.progress != null -> OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            // ⚠ No delete on the model in use: removing it would leave the
            // backend pointed at a directory that is gone.
            row.installed && row.selected ->
                OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.in_use)) }
            row.installed ->
                InstalledActions(busy, onDelete = { onDelete(row.spec) }, onUse = { onUse(row.spec) })
            // ⚠⚠ An INCOMPLETE import: the only action is to delete it. There
            // is no Download that could complete it. ⚠ `Delete`, not `Remove`:
            // the dialog it opens says Delete, and one action has one verb.
            row.spec.isCustom ->
                OutlinedButton(onClick = { onDelete(row.spec) }, enabled = !busy) { Text(stringResource(R.string.delete)) }
            // ⚠⚠ No build this HTP can load: DISABLED and saying why, rather
            // than failing after a multi-gigabyte download.
            row.build == null ->
                OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.unsupported)) }
            else -> Button(onClick = { onInstall(row.spec) }, enabled = !busy) { Text(stringResource(R.string.download)) }
        }
    }
}

/**
 * ⭐⭐ The video models, as one row on their own tab.
 *
 * ⚠⚠ **Bytes, not files.** "12 of 13" reads as nearly done when the absent
 * one is 1.5 GB of 8.5, and the bytes are what the user is actually waiting
 * for.
 */
@Composable
private fun VideoModelsTab(
    row: VideoRow,
    busy: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    /** ⚠ ASKS first — see [deletingVideo]. Never deletes on the tap. */
    onConfirmDelete: () -> Unit,
    onProbe: () -> Unit,
    /**
     * ⭐⭐ **Use — open a flow that uses these weights.** Added 2026-09-20 at
     * the user's ask, for the reason [UpscalerCard] gives: the video models bind
     * no context key and nothing selects them globally, but they have TWO flows
     * (text to video, image to video) and picking between them is exactly what
     * this dialog is for.
     */
    onUse: () -> Unit = {},
) {
    // ⚠ Once, when the tab is first composed. [HarnessViewModel.probeVideoSupport]
    // is itself idempotent, so a pager pre-composing this page costs one call.
    androidx.compose.runtime.LaunchedEffect(Unit) { onProbe() }
    LazyColumn(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                stringResource(R.string.video_models_note, gb(row.totalBytes)),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            DownloadCard(
                title = stringResource(R.string.video_models),
                emphasised = false,
                status = when {
                    row.complete -> stringResource(R.string.installed_mb, mb(row.installedBytes))
                    else -> stringResource(R.string.mb_of_total, mb(row.installedBytes), mb(row.totalBytes))
                },
                // ⚠⚠ The canary's verdict, in the one place a user is about to
                // spend 8 GB. It runs a real context binary rather than
                // consulting a chip list — `docs/DEVICES.md` §2.
                detail = when (row.supported) {
                    false -> stringResource(R.string.cannot_run_it)
                    else -> stringResource(R.string.video_about)
                },
                progress = row.progress,
            ) {
                when {
                    row.progress != null ->
                        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    row.supported == false ->
                        OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.unsupported)) }
                    row.complete ->
                        InstalledActions(busy, onDelete = onConfirmDelete, onUse = onUse)
                    else -> Button(onClick = onInstall, enabled = !busy) { Text(stringResource(R.string.download)) }
                }
            }
        }
    }
}

/**
 * ⭐⭐⭐ **The actions on an INSTALLED model: Delete left, Use right.**
 *
 * ⚠⚠⚠ Reported from the phone 2026-09-20, angrily and rightly: the
 * upscaler and video cards shipped Use on the LEFT and no spacing at all,
 * under a comment of mine claiming they matched the checkpoint rows. They
 * did not. I asserted the convention instead of reading it, which is the
 * one failure `CLAUDE.md` opens with — **find the sibling before you write
 * the thing** — and a comment that states a rule it breaks is worse than no
 * comment, because the next reader believes it.
 *
 * ⇒ There is now ONE of these and three callers, so a fourth card cannot
 * invent a fourth order. The shape, from the checkpoint row that always had
 * it right: **outlined Delete, filled Use, `Arrangement.spacedBy(8.dp)`**.
 * Destructive first, primary last where the thumb lands — the same order
 * `PictureActions` uses (`docs/UI.md` §8.3).
 */
@Composable
private fun InstalledActions(busy: Boolean, onDelete: () -> Unit, onUse: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onDelete, enabled = !busy) {
            Text(stringResource(R.string.delete))
        }
        Button(onClick = onUse, enabled = !busy) {
            Text(stringResource(R.string.use))
        }
    }
}

/**
 * One upscaler's card.
 *
 * ⭐⭐ **It has a Use button since 2026-09-20**, at the user's ask. The note
 * here used to say it could not: *"nothing selects an upscaler globally, a
 * flow's Upscale node names one"*. That is still true and it turned out not to
 * be the point — Use never meant "select", it means **open a flow that uses
 * this**, which an upscaler has exactly one of. Asking what to open is the
 * whole shape of the dialog ([HarnessViewModel.PendingUse]), and it works the
 * same whether or not a global selection comes with it.
 *
 * ⚠ Only once installed: Use on a model that is not here would offer a flow
 * that cannot run.
 */
@Composable
private fun UpscalerCard(
    row: UpscalerRow,
    busy: Boolean,
    onInstall: (UpscalerSpec) -> Unit,
    onCancel: () -> Unit,
    onDelete: (UpscalerSpec) -> Unit,
    onUse: (UpscalerSpec) -> Unit = {},
) {
    DownloadCard(
        title = row.spec.label,
        emphasised = false,
        status = when {
            row.installed -> stringResource(R.string.installed_mb, mb(row.onDisk))
            row.build != null -> stringResource(R.string.not_installed_mb, mb(row.build.bytes))
            else -> stringResource(R.string.cannot_run_it)
        },
        detail = fields(row.spec.about, row.build?.tier),
        progress = row.progress,
    ) {
        when {
            row.progress != null ->
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            row.installed ->
                InstalledActions(busy, onDelete = { onDelete(row.spec) }, onUse = { onUse(row.spec) })
            row.build == null ->
                OutlinedButton(onClick = {}, enabled = false) { Text(stringResource(R.string.unsupported)) }
            else -> Button(onClick = { onInstall(row.spec) }, enabled = !busy) { Text(stringResource(R.string.download)) }
        }
    }
}

/** ⚠ [UpscalerCard]'s shape: no Use, one action, the shared [DownloadCard]. */
@Composable
private fun ToolCard(
    row: ToolRow,
    busy: Boolean,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    DownloadCard(
        title = row.label,
        emphasised = false,
        status = if (row.installed) stringResource(R.string.installed_mb, mb(row.onDisk))
        else stringResource(R.string.not_installed_mb, mb(row.bytes)),
        detail = stringResource(R.string.segmenter_about),
        progress = row.progress,
    ) {
        when {
            row.progress != null ->
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            row.installed ->
                OutlinedButton(onClick = onDelete, enabled = !busy) { Text(stringResource(R.string.delete)) }
            else -> Button(onClick = onInstall, enabled = !busy) { Text(stringResource(R.string.download)) }
        }
    }
}

private fun mb(bytes: Long): Long = bytes shr 20

/** ⚠ The SAME unit as [mb], one step up: `8198 MB` is `8.0 GB`, never `8.6`. */
private fun gb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024 * 1024))
