package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import com.abrah.nightmare.TempCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Prefs

/**
 * ⭐⭐ **Settings — a single page, on purpose.**
 *
 * ⚠⚠ **Rule changed 2026-09-19, at the user's ask.** This used to be three
 * tabs: Theme, Community (a node-authoring reference page with a pack
 * importer) and Diagnostics (the op harness). Both of the other two were
 * removed, not just hidden:
 *
 * - **Community** was written for a contributor who wants to author a node
 *   pack — real, but a small enough audience that a whole settings tab of
 *   host-op tables and widget syntax was mostly noise for everyone who opens
 *   Settings for the theme or the battery card. A pack pushed to
 *   `<externalFiles>/plugins/` still loads with no UI at all
 *   (`docs/ARCHITECTURE.md`), so nothing about loading a pack actually needed
 *   a button here.
 * - **Diagnostics** is the op harness — *"i think its only for LLMs to
 *   test"*, and that is exactly right: it is a developer surface for
 *   headlessly driving the backend, not something a person using the app
 *   would ever open on purpose. It stays fully reachable the way it always
 *   was for that use — `OpService` over adb, `notes/HANDOFF.md` §5 — none of
 *   which goes through this screen at all.
 *
 * ⇒ With both gone, a `TabRow` over one page was a tab bar with nothing to
 * switch to, so it went too.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    theme: Prefs.Theme,
    onTheme: (Prefs.Theme) -> Unit,
    /**
     * ⭐ Whether this app is currently exempt from Doze/App Standby battery
     * optimisation. Read fresh from `PowerManager` by the caller — this
     * screen does not own OS state, only shows it.
     */
    batteryUnrestricted: Boolean = true,
    /** ⭐ Opens the system's "allow background activity" prompt for this app. */
    onRequestBatteryUnrestricted: () -> Unit = {},
    /**
     * ⭐ Textual-inversion embeddings — import/list/delete. Also reachable
     * from Models → Tools (`ModelsScreen`'s own copy, the same view-model
     * state and callbacks). ⚠ Null hides the section — a preview and a
     * golden with no picker want nothing drawn.
     */
    embeddings: List<EmbeddingRow>? = null,
    onImportEmbedding: (() -> Unit)? = null,
    onDeleteEmbedding: ((String) -> Unit)? = null,
    /**
     * ⭐ Commits a new download origin. ⚠ The caller writes it through
     * [Prefs.setDownloadBase] so it survives a restart; this screen only
     * reads [Prefs.downloadBase] back.
     */
    onDownloadBase: (String) -> Unit = {},
    /**
     * ⭐ Deletes what [TempCleaner] listed. ⚠ The SCAN happens here (it
     * needs no state), the delete goes to the caller so one owner reports
     * what was freed.
     */
    onCleanTemp: () -> Unit = {},
    /** ⚠⚠ True while a model install is running — [TempCleaner] then
     * leaves the download scratch alone rather than pulling a live transfer
     * out from under itself. */
    installing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var deletingEmbedding by remember { mutableStateOf<String?>(null) }
    // ⚠⚠ Hoisted to the function, not the Column that draws the button:
    // the confirm dialog below is a sibling of the whole layout, and a state
    // declared in the Column is invisible to it.
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanned by remember { mutableStateOf<Long?>(null) }
    var scanning by remember { mutableStateOf(false) }
    // ⚠⚠⚠ Laid out like [LibraryScreen] minus its `TabRow` — see the class
    // note. `statusBarsPadding().padding(16.dp)`, then `ScreenHeader`; if
    // Library's own inset changes, this must change with it.
    Column(
        modifier.fillMaxSize().statusBarsPadding().padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(stringResource(R.string.settings), onClose = onClose)
        Column(
            Modifier.fillMaxWidth().padding(top = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ⚠⚠ THREE choices, not a dark-mode switch. "Follow the system" cannot
            // be expressed as on/off, and a bare switch would pin the app to
            // whatever the phone was when it was first opened with no way back.
            // `Prefs.Theme` has the same note.
            for (t in Prefs.Theme.entries) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(selected = theme == t, onClick = { onTheme(t) })
                    Column {
                        Text(
                            stringResource(
                                when (t) {
                                    Prefs.Theme.SYSTEM -> R.string.theme_system
                                    Prefs.Theme.DARK -> R.string.theme_dark
                                    Prefs.Theme.LIGHT -> R.string.theme_light
                                }
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (t == Prefs.Theme.LIGHT) {
                            // ⚠ Said out loud rather than discovered. The canvas is
                            // drawn from its own palette (`CanvasColors`) and was
                            // designed dark; light is honest about being the less
                            // finished of the two rather than pretending otherwise.
                            Text(
                                stringResource(R.string.theme_canvas_note),
                                style = LogTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            // ⭐⭐ **Whether a render survives the app leaving the foreground.**
            //
            // ⚠⚠ A foreground service now holds this app's process priority up
            // for as long as a checkpoint is resident (`BackendKeepAliveService`),
            // which is the actual fix for a user report, 2026-09-18: the backend
            // process died in the background roughly 4 times in 10, and the whole
            // workflow reset on return. But on some OEMs — this device's Samsung
            // One UI among them — a foreground service alone is not always
            // enough against the battery manager's own app-level kill list, and
            // this is the second, user-visible half of that fix: one tap to ask
            // the OS not to restrict this app at all.
            // ⚠ Shown only while NOT already exempt — once granted there is
            // nothing to ask for, and a card that never goes away reads as
            // unresolved even after the user said yes.
            if (!batteryUnrestricted) {
                Card(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.battery_title),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            stringResource(R.string.battery_body),
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = onRequestBatteryUnrestricted) {
                            Text(stringResource(R.string.battery_allow))
                        }
                    }
                }
            }
            // ⭐⭐ Embeddings — import / list / delete. ⚠ Null hides the
            // section, same convention as every optional slot on this page.
            if (onImportEmbedding != null) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportCallout(
                        title = stringResource(R.string.embeddings_title),
                        body = stringResource(R.string.embeddings_body),
                        onImport = onImportEmbedding,
                    )
                    for (row in embeddings.orEmpty()) {
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(R.string.installed_mb, row.bytes shr 20),
                                        style = LogTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (onDeleteEmbedding != null) {
                                    OutlinedButton(onClick = { deletingEmbedding = row.name }) {
                                        Text(stringResource(R.string.delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // ⭐⭐⭐ **Where models are downloaded from.** Asked for
            // 2026-09-20: huggingface.co is unreachable from mainland China,
            // and upstream `local-dream` has offered this for far longer than
            // this fork has existed. Three choices, the same three upstream
            // offers, because a user who has been told "use hf-mirror" should
            // find the words they were told.
            //
            // ⚠⚠ It rewrites the ORIGIN of a Hugging Face URL, it does not
            // rebase the catalogue ([Prefs.apply] says why). ⚠ So it covers every
            // checkpoint, the upscalers, the segmenter and the video weights at
            // once, and deliberately does NOT cover the DiT engine, which comes
            // from a GitHub release.
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.download_source),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.download_source_note),
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠ Radios, not chips: the custom row owns a text field, which
                // no chip can hold. Same three-way shape as the theme above.
                val base = Prefs.downloadBase
                val isOrigin = base == Prefs.HF_ORIGIN
                val isMirror = base == Prefs.HF_MIRROR
                var custom by remember(base) {
                    mutableStateOf(if (isOrigin || isMirror) "" else base)
                }
                SourceRow(
                    label = stringResource(R.string.source_huggingface),
                    detail = Prefs.HF_ORIGIN,
                    selected = isOrigin,
                    onSelect = { onDownloadBase(Prefs.HF_ORIGIN) },
                )
                SourceRow(
                    label = stringResource(R.string.source_mirror),
                    detail = Prefs.HF_MIRROR,
                    selected = isMirror,
                    onSelect = { onDownloadBase(Prefs.HF_MIRROR) },
                )
                SourceRow(
                    label = stringResource(R.string.source_custom),
                    detail = null,
                    selected = !isOrigin && !isMirror,
                    // ⚠ Selecting it with nothing typed yet must not blank the
                    // setting, so it commits only what is there.
                    onSelect = { if (custom.isNotBlank()) onDownloadBase(custom) },
                ) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.source_custom_hint)) },
                        // ⚠⚠ Committed on DONE, never per keystroke: every
                        // character would otherwise be a saved preference, and
                        // half a URL is a working setting that fetches nothing.
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { if (custom.isNotBlank()) onDownloadBase(custom) },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            // ⭐⭐ **Clean temp files** — ported from upstream at the user's ask,
            // 2026-09-20. ⚠⚠ It SCANS first and shows the total, because the
            // honest question is "delete 3.4 GB?" and not "delete some files?".
            // ⚠ What it will and will not touch is [TempCleaner]'s whole
            // doc comment; the short version is in the confirm.
            Column(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.clean_temp_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(R.string.clean_temp_body),
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    enabled = !scanning,
                    onClick = {
                        scanning = true
                        scope.launch {
                            // ⚠ Off the main thread: it walks the models tree.
                            val bytes = withContext(Dispatchers.IO) {
                                TempCleaner.scan(ctx, busy = installing)
                            }
                            scanning = false
                            scanned = bytes
                        }
                    },
                ) { Text(stringResource(R.string.clean_temp_action)) }
                if (scanned == 0L) {
                    Text(
                        stringResource(R.string.clean_temp_none),
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    // ⚠⚠ Only a REAL delete gets [ConfirmDelete]. "Nothing to clean" is
    // an answer, not a destructive action, and dressing it as one would put
    // a Delete button on a dialog that deletes nothing — the dialog's own
    // contract says its verb is always Delete (`docs/UI.md` §8.2). It is
    // reported inline under the button instead.
    scanned?.takeIf { it > 0L }?.let { bytes ->
        ConfirmDelete(
            title = stringResource(R.string.clean_temp_confirm, bytes shr 20),
            body = stringResource(R.string.clean_temp_confirm_body),
            onConfirm = {
                scanned = null
                onCleanTemp()
            },
            onDismiss = { scanned = null },
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
}

/**
 * ⭐ One download-source choice: a radio, its name, what it resolves to, and
 * optional content underneath (the custom row's text field).
 *
 * ⚠ A row, not a [Chooser] chip: one of the three owns an editable field, and
 * the theme picker three inches above is already radios, so this matches the
 * page it lives on rather than the node inspector.
 */
@Composable
private fun SourceRow(
    label: String,
    detail: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(label)
            detail?.let {
                Text(it, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // ⚠ Under the row, full width: a URL field beside a radio is unreadable
    // at phone width, which is the only width there is.
    if (selected) content?.invoke()
}

