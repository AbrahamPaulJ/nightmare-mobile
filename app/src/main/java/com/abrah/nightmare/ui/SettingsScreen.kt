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
import androidx.compose.material3.OutlinedButton
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
    modifier: Modifier = Modifier,
) {
    var deletingEmbedding by remember { mutableStateOf<String?>(null) }
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
        }
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
