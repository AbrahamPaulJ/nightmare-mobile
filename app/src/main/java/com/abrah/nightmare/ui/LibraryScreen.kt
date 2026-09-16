package com.abrah.nightmare.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch

/** The two halves of the library: what you can render WITH, and what you can render. */
/**
 * ⚠ RESULTS is last because it is where you go AFTER making something, and
 * the other two are where you go before.
 */
enum class LibraryTab(val label: String) {
    // ⚠ "Results", kept — it was renamed History for a day and the user asked
    // for the name back (2026-09-17); only the LAYOUT follows DreamUI's History.
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
        // ⭐ The APP's name and logo, not the tab's — the tab row directly under
        // it already says Models / Flows / Results, so a title repeating it was
        // redundant (the user's call, 2026-09-17).
        BrandHeader(onClose = onClose)
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
 * ⭐⭐ "Nightmare", stylised, beside the logo, with the ✕ in the corner every
 * panel over the canvas keeps it ([ScreenHeader]'s corner, same icon, same tint).
 */
@Composable
fun BrandHeader(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.abrah.nightmare.R.drawable.brand_logo),
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp)),
        )
        Text(
            "Nightmare",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Black,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                letterSpacing = (-0.5).sp,
                // ⭐ The logo's own violet, fading to lavender.
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(Color(0xFF9B6BFF), Color(0xFFD9C8FF)),
                ),
            ),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.IconButton(onClick = onClose) {
            androidx.compose.material3.Icon(
                androidx.compose.material.icons.Icons.Filled.Close,
                contentDescription = "close — back to the canvas",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    /**
     * ⚠⚠ True when the caller gives this a FIXED height and every page should
     * fill it. Inside a bottom sheet a pager sized by its page resizes the whole
     * sheet on every swipe, so the pills and cards jump away from the finger and
     * a tap lands on the scrim and closes it — reported 2026-09-17 on Add node.
     */
    fillHeight: Boolean = false,
    /** ⚠ For a golden, which cannot swipe. */
    initialPage: Int = 0,
    /** ⭐ The settled page, for a caller whose state follows the tab (History's filter). */
    onPage: (Int) -> Unit = {},
    page: @Composable (Int) -> Unit,
) {
    val state = rememberPagerState(initialPage = initialPage, pageCount = { labels.size })
    androidx.compose.runtime.LaunchedEffect(state.currentPage) { onPage(state.currentPage) }
    val scope = rememberCoroutineScope()

    // ⭐ Keeps the selected pill on screen when a swipe lands on one that is
    // scrolled out of view.
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(state.currentPage) {
        row.animateScrollToItem((state.currentPage - 1).coerceAtLeast(0))
    }

    Column(modifier.fillMaxWidth()) {
        // ⚠⚠⚠ **Scrollable, each pill its own text's width — never `weight(1f)`.**
        // Five tabs (three families, Upscalers, Video) split one phone width
        // into slivers: "Upscalers" did not fit, and when selected it went
        // SemiBold, got WIDER, wrapped to a second line and made the whole row
        // taller — so the page under it jumped down every time that tab took
        // focus. Reported from the phone 2026-09-16 as a "snapping animation"
        // on the Upscalers and Video tabs, and as text that does not fit.
        // ⇒ One line, one weight whether selected or not (colour carries the
        // selection), and room to scroll instead of room to squeeze.
        androidx.compose.foundation.lazy.LazyRow(
            state = row,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(labels.size) { i ->
                val label = labels[i]
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
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (on) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .widthIn(min = 72.dp)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
        }
        // ⚠⚠ **`key(i)`, or a page inherits the scroll of the one before it.**
        // A pager REUSES composition slots between pages, so the `LazyListState`
        // a `LazyColumn` remembers is handed to whichever page lands in that
        // slot next. Swiping from a scrolled checkpoint list to the two-row
        // Upscalers tab composed it at the old offset, which then clamped to 0
        // — the list visibly started halfway down and snapped to the top.
        // Reported from the phone 2026-09-15.
        //
        // ⚠ Keyed for EVERY page, not just the short one: the same reuse makes
        // any two tabs of unequal length do it, and the upscalers tab is only
        // where it is most obvious.
        HorizontalPager(
            state = state,
            modifier = Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
            verticalAlignment = androidx.compose.ui.Alignment.Top,
        ) { i ->
            key(i) { page(i) }
        }
    }
}
