package com.abrah.nightmare.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * ⭐⭐⭐ **Violet Studio's shared pieces** (the user's design pack, 2026-10-08) — every screen calls
 * these, so a card, a metadata pill, a switch row and a labelled action look the same wherever
 * they appear (`docs/UI.md` §8.9a). Colours come from the theme's tokens (`ui/Theme.kt`).
 */

/** ⭐ A card: plum surface, quiet border, 16dp corners. [selected] = violet edge on a tinted card. */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant
    val border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Accent else MaterialTheme.colorScheme.outline)
    val inner: @Composable () -> Unit = { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border, content = inner)
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = border, content = inner)
    }
}

/** ⭐ The main screen's one side margin: every container's edge sits this far from the screen's. */
val SCREEN_GUTTER = 12.dp

/** ⭐ The space inside a [PanelCard], from its edge to what it holds. */
val PANEL_PAD = 12.dp

/**
 * ⭐⭐ **A part of the main screen** — the view's page (the node pages, the chat) and the Run bar
 * (seed, RAM, Run, its log and errors), each on its own tinted card (the user, 2026-10-10: "proper
 * containers that separate the views, nodes/form section, the run btn and its log"). The sidebar
 * cards' fill and corners, no border. ⚠ The CALLER places it [SCREEN_GUTTER] from the edges and
 * pads what is inside by [PANEL_PAD] — the inspector's pages carry their own padding.
 */
@Composable
fun PanelCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, content = content)
}

/** ⭐ A screen's title, with its actions on the right — Models' Import, Results' filter. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        actions()
    }
}

/** ⭐ A section heading inside a screen or a card, with an optional line under it. */
@Composable
fun SectionHeading(title: String, supporting: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * ⭐⭐ A metadata pill — a picture's size, its seed. 36dp high, 12dp corners, one typeface (Inter,
 * tabular figures) for every value. [onPicture] = over a photo (the fullscreen viewers): a dark
 * glass behind white text instead of a plum surface.
 * ⚠ It replaced a monospace size pill beside an Inter seed pill of another height — the pair
 * the user called "visually poor" (2026-10-08).
 */
@Composable
fun MetaPill(onPicture: Boolean = false, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(12.dp),
        // ⚠ RAISED, one step above a [PanelCard]: on the card's own fill the pill vanished and its
        // text read as indented (the Run card, 2026-10-10).
        color = if (onPicture) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, if (onPicture) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outline),
        contentColor = if (onPicture) Color.White else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp).heightIn(min = 36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

/** ⭐ The type a metadata value is set in: Inter label, tabular figures so digits do not jitter. */
@Composable
fun metaValueStyle(): TextStyle = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum")

/**
 * ⭐⭐ A setting as a row: label and supporting line on the left, a Switch on the right — the ONE
 * bool control (Settings, the output node's Autosave / Auto upscale, every bool knob).
 */
@Composable
fun SwitchRow(
    label: String,
    hint: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            ),
        )
    }
}

/** ⭐ An icon with its word under it — the Results row (Favourite · Save · Share · Use in flow · More). */
@Composable
fun LabeledAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null) {
    Column(
        modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint ?: MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
        // ⚠ Two lines allowed: "Use in flow" in a fifth of a phone is wider than one line.
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Suppress("unused")
private val keepHeight = Modifier.height(0.dp)
