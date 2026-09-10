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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.Prefs

/** Which page of Settings is showing. */
enum class SettingsTab(val label: Int) {
    THEME(R.string.settings_theme),
    COMMUNITY(R.string.settings_community),
    DIAGNOSTICS(R.string.settings_diagnostics),
}

/**
 * ⭐⭐ **Settings — and the harness's new home.**
 *
 * ⚠⚠ The canvas used to carry a WRENCH that opened the op harness: a
 * developer tool, with a developer's icon, on the app's first screen. It was
 * one of three unlabelled glyphs a user had no reason to understand, and the
 * thing behind it is the least likely of the three to be what they wanted.
 * Asked from the phone, 2026-09-10: make it a settings icon, and question
 * whether the harness belongs in the UI at all.
 *
 * ⇒ Decided with the user: **keep it, behind Diagnostics.** It stays reachable
 * without a cable — which is what it is for when something fails on a phone
 * that is not plugged in — and it stops being the front door.
 */
@Composable
fun SettingsScreen(
    tab: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    onClose: () -> Unit,
    theme: Prefs.Theme,
    onTheme: (Prefs.Theme) -> Unit,
    /** ⭐ The harness, handed in whole: this screen does not know what an op is. */
    diagnostics: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠⚠⚠ **Laid out EXACTLY like [LibraryScreen], and the first version was
    // not.** It had no `statusBarsPadding` and no side padding, so the header
    // sat under the clock and every list ran into both edges — while Models and
    // Flows, one tap away, were correctly inset. It also used the PILL row,
    // which is [SwipeTabs] and belongs one level DOWN (the family sub-tabs
    // inside Models). Two screens at the same level with different chrome is
    // the inconsistency reported from the phone, 2026-09-10.
    //
    // ⇒ `statusBarsPadding().padding(16.dp)`, `ScreenHeader`, then a `TabRow` —
    // the same three lines, in the same order, with the same numbers. If
    // Library's layout changes, this must change with it.
    Column(modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        ScreenHeader(stringResource(R.string.settings), onClose = onClose)
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            for (t in SettingsTab.entries) {
                Tab(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    text = {
                        Text(
                            stringResource(t.label),
                            fontWeight = if (t == tab) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }
        when (tab) {
            SettingsTab.THEME -> ThemePage(theme, onTheme)
            SettingsTab.COMMUNITY -> CommunityPage()
            // ⚠ The harness is passed in rather than built here so this file
            // stays free of the view model.
            SettingsTab.DIAGNOSTICS -> diagnostics()
        }
    }
}

@Composable
private fun ThemePage(theme: Prefs.Theme, onTheme: (Prefs.Theme) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp),
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
    }
}

/**
 * ⭐⭐ **Everything a node author needs, in the app.**
 *
 * ⚠⚠ It is not a link with a sentence over it. The repository is PRIVATE, so a
 * link is a dead end for anyone but the owner until it goes public, and a
 * contributor reading this on a phone cannot open a repo anyway. ⇒ The page
 * carries the two files, the host op surface, the handles rule, how widgets are
 * declared, and the ceiling. The README says the same things; this is the copy
 * that works with no network and no access.
 */
@Composable
private fun CommunityPage() {
    Column(
        Modifier.fillMaxWidth().padding(top = 10.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.community_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.community_intro),
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Section(R.string.community_files_title, R.string.community_files_body)
        // ⚠ The op table is the reference an author actually keeps coming back
        // to, so it is a card of its own rather than a bullet in a paragraph.
        Section(R.string.community_ops_title, R.string.community_ops_body, mono = true)
        Section(R.string.community_handles_title, R.string.community_handles_body)
        Section(R.string.community_widgets_title, R.string.community_widgets_body)
        Section(R.string.community_cannot_title, R.string.community_cannot_body)
        Text(
            stringResource(R.string.community_reference),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.community_repo),
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.community_experimental),
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * One titled card. ⚠ [mono] for the op table: a list of names and arguments
 * only lines up in a monospaced face, and `LogTextStyle` is already the app's.
 */
@Composable
private fun Section(titleRes: Int, bodyRes: Int, mono: Boolean = false) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(bodyRes),
                style = if (mono) LogTextStyle else LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
