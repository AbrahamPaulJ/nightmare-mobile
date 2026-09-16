package com.abrah.nightmare.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.NodeType
import com.abrah.nightmare.ui.LogTextStyle
import com.abrah.nightmare.ui.SwipeTabs

/**
 * Every node type that can be placed, in three tabs ([paletteTabs]).
 *
 * ⭐ **Plugins appear here with no special case.** The list is the executor's
 * registry, so a pack pushed to the device shows up beside `sample` and
 * `vae_decode` — which is the whole tier-0 promise made visible: a contributor's
 * node is not a second-class citizen in the palette either.
 *
 * ⚠ A bottom sheet rather than a side rail: a phone has no room for a permanent
 * palette, and docs/CLAUDE.md decided the palette is a sheet when the canvas was
 * still a plan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodePalette(
    types: Map<String, NodeType>,
    onPick: (NodeType) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // ⚠ Three quarters of the screen, whatever the tab — never the page's own height.
        val screen = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
        NodePaletteContent(types, onPick, height = (screen * 0.75f).dp)
    }
}

/**
 * ⭐ The sheet's content, separate so a golden can draw it (a `ModalBottomSheet`
 * is a window of its own and a screenshot test cannot reach it).
 *
 * ⚠⚠ A FIXED height — [SwipeTabs] with `fillHeight`. Sized by its page, the sheet
 * grew and shrank on every swipe, so taps landed on the scrim and closed it
 * (2026-09-17).
 * ⚠⚠ ONE card per row, full width, name on one line. Two narrow cards a row
 * wrapped "Select object" and "Video" over several lines, and a row of two
 * cards of different heights reads as a broken grid (the same day's report).
 */
@Composable
fun NodePaletteContent(
    types: Map<String, NodeType>,
    onPick: (NodeType) -> Unit,
    height: androidx.compose.ui.unit.Dp = 520.dp,
    initialTab: Int = 0,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Add a node", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        val tabs = paletteTabs(types)
        // ⭐ The same pills as the Models and Flows sub-tabs ([SwipeTabs]).
        SwipeTabs(
            labels = tabs.map { tabTitle(it.first) },
            modifier = Modifier.weight(1f),
            fillHeight = true,
            initialPage = initialTab,
        ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 10.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (card in tabs[page].second) PaletteCard(card, onPick, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * ⭐⭐ The palette as three TABS of CARDS — Common, Generate, Inpaint. The
 * user's call, 2026-09-17, replacing six category sections (2026-09-16).
 *
 * ⚠ The tab is decided by [NodeType.category]: `generate` and `inpaint` are
 * their own tabs, and EVERYTHING else — prompt, image, crop, upscale, output,
 * and a plugin's pixel ops — is Common, the light family-agnostic nodes. A
 * plugin that declares `generate` lands beside the samplers with no special
 * case. ⚠ The category still gives the canvas its hue; only the palette folds.
 *
 * ⚠ A card is every type sharing a [NodeType.paletteGroup]; the SD samplers of
 * three families are one card with a chip each. Pure, so the grouping is
 * testable without a composable.
 *
 * ⚠ `hidden` types still RUN — they are the set §5.7 replaces, kept alive for
 * one build to compare against. They must not be offered.
 */
fun paletteTabs(types: Map<String, NodeType>): List<Pair<String, List<List<NodeType>>>> {
    val shown = types.values.filterNot { it.hidden }
    val byTab = shown.groupBy { tabFor(it.category) }
    return TAB_ORDER.filter { it in byTab }.map { tab ->
        tab to byTab.getValue(tab)
            .groupBy { it.paletteGroup }
            .values
            .map { group -> group.sortedBy { FAMILY_ORDER.indexOf(it.paletteVariant).let { i -> if (i < 0) 99 else i } } }
            // ⚠ The order a flow is BUILT in — source, edit, generate, output —
            // then by name, and a plugin's categories after ours. An unordered
            // map reshuffles the sheet between runs, and muscle memory is most
            // of what makes a node editor fast.
            .sortedWith(
                compareBy<List<NodeType>> { card ->
                    CATEGORY_ORDER.indexOf(card.first().category).let { if (it < 0) 99 else it }
                }.thenBy { it.first().paletteName.lowercase() }
            )
    }
}

private fun tabFor(category: String): String = when (category) {
    "generate", "inpaint" -> category
    else -> "common"
}

private val TAB_ORDER = listOf("common", "generate", "inpaint")
private val CATEGORY_ORDER = listOf("source", "edit", "generate", "inpaint", "output")
private val FAMILY_ORDER = listOf("SD 1.5", "SDXL", "Anima")

private fun tabTitle(tab: String): String = when (tab) {
    "common" -> "Common"
    "generate" -> "Generate"
    "inpaint" -> "Inpaint"
    else -> tab.replaceFirstChar { it.uppercase() }
}

@Composable
private fun PaletteCard(card: List<NodeType>, onPick: (NodeType) -> Unit, modifier: Modifier) {
    val first = card.first()
    // ⭐ Tapping the card itself adds the SELECTED model's family when this card
    // offers it — the node a person most likely wants — else the first chip.
    val preferred = card.firstOrNull { it.paletteVariant == com.abrah.nightmare.SelectedModel.spec.family.label }
        ?: first
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onPick(preferred) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // ⚠ Name and chips on ONE row: the name never wraps, and a card is the
        // same shape whether it offers one family or three.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The same hue the node will have on the canvas.
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(CanvasColors.forCategory(first.category))
            )
            Text(
                first.paletteName.replaceFirstChar { it.uppercase() },
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                softWrap = false,
            )
            Spacer(Modifier.weight(1f))
            if (card.size > 1) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (variant in card) {
                        Surface(
                            onClick = { onPick(variant) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                variant.paletteVariant ?: variant.paletteName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        }
        if (first.about.isNotBlank()) {
            Text(
                first.about,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        // ⚠ The qualified name, so two packs shipping a `Resize` are
        // distinguishable in the one place the user picks between them.
        if (first.name.contains(':')) {
            Text(
                first.name.substringBeforeLast(':'),
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
