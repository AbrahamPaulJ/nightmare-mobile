package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The two halves of the library: what you can render WITH, and what you can render. */
/**
 * ⚠ RESULTS is last because it is where you go AFTER making something, and
 * the other two are where you go before.
 */
enum class LibraryTab(val label: String) {
    MODELS("Models"), FLOWS("Flows"), RESULTS("Results")
}

/**
 * ⭐⭐ Models and Flows as one screen with two tabs, rather than two screens.
 *
 * ⚠ They were separate full screens reached from separate buttons, so moving
 * between them meant closing one, finding the canvas bar, and opening the
 * other — three taps to compare "which checkpoints do I have" against "which
 * recipes want one". They are the same question asked twice, and the answer to
 * one is usually why you are asking the other.
 *
 * ⚠ The top row switches by TAP, not by swipe, and that is deliberate: the
 * swipe axis belongs to the family sub-tabs inside Models. Two nested pagers on
 * one axis is a gesture a user has to think about, and the inner one is the one
 * that earns it — a 15-row list whose two halves differ in size by 3.5×.
 */
@Composable
fun LibraryScreen(
    tab: LibraryTab,
    onTab: (LibraryTab) -> Unit,
    onClose: () -> Unit,
    models: @Composable () -> Unit,
    flows: @Composable () -> Unit,
    results: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        // ⚠ The title follows the tab rather than saying "Library": the ✕ closes
        // to the canvas either way, and a name the user did not choose is one
        // more word between them and the list.
        ScreenHeader(tab.label, onClose = onClose)
        TabRow(
            selectedTabIndex = tab.ordinal,
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            for (t in LibraryTab.entries) {
                Tab(
                    selected = t == tab,
                    onClick = { onTab(t) },
                    text = {
                        Text(
                            t.label,
                            fontWeight = if (t == tab) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                )
            }
        }
        when (tab) {
            LibraryTab.MODELS -> models()
            LibraryTab.FLOWS -> flows()
            LibraryTab.RESULTS -> results()
        }
    }
}

/**
 * ⭐ A **pill** row over a swipeable pager, for a list that splits by family.
 *
 * ⚠⚠ **Pills, not a second `TabRow`, and that is the whole point of this
 * change.** This sits directly under [LibraryScreen]'s own tab row, and two
 * underlined tab rows stacked five dp apart read as one confused control:
 * nothing says which of "Models / Flows" and "SD 1.5 / SDXL" is the parent, and
 * the two underlines compete. Reported from the phone 2026-09-10.
 *
 * ⇒ The OUTER level keeps the underlined tabs — it is the primary navigation —
 * and this inner one becomes a segmented pill group, which is Material's own
 * idiom for filtering the content of a page rather than changing pages.
 *
 * ⚠⚠ The PAGER is the source of truth and the row follows it. Driving both from
 * one externally-held index makes a half-finished swipe leave the row and the
 * page disagreeing, and the row then highlights a tab the user is not looking
 * at.
 *
 * ⚠ A tap animates rather than jumps, so a tap and a swipe land the user in the
 * same place by the same motion — otherwise the two read as different features.
 */
@Composable
fun SwipeTabs(
    labels: List<String>,
    modifier: Modifier = Modifier,
    page: @Composable (Int) -> Unit,
) {
    val state = rememberPagerState(pageCount = { labels.size })
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            labels.forEachIndexed { i, label ->
                val on = i == state.currentPage
                Surface(
                    onClick = { scope.launch { state.animateScrollToPage(i) } },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        // ⚠ Transparent rather than `surfaceVariant`: an unselected
                        // pill with its own fill makes the group look like two
                        // buttons, and the selected one stops standing out.
                        Color.Transparent
                    },
                    border = if (on) null else BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                    ),
                    // ⚠ Weighted, so two families split the width evenly and a
                    // third would still fit rather than pushing off the edge.
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }
        }
        HorizontalPager(state = state, modifier = Modifier.fillMaxWidth()) { i -> page(i) }
    }
}
