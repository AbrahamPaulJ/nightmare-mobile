package com.abrah.nightmare.ui

import com.abrah.nightmare.ui.StudioOutlinedButton
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.R

/** ⭐ The Model card's badge: what the checkpoint the flow needs is doing. */
enum class LoadState { IDLE, LOADING, LOADED, NONE }

/**
 * ⭐ What the sidebar's Model card and the Run bar's RAM pill show — built in `MainActivity` from
 * the view model's `CanvasLoad` ([model] is the name, never with "(idle)" glued on any more).
 */
data class ShellLoad(
    val model: String,
    val state: LoadState,
    val ramFree: String,
    val ramTotal: String,
    /** ⭐ The battery temperature, whole °C, or null ([com.abrah.nightmare.HarnessViewModel.CanvasLoad.batteryTempC]). */
    val tempC: String? = null,
)

/** ⭐ THE RAM line, drawer and run bar alike: free / total, and the temperature when the phone gives one. */
@Composable
internal fun ramLine(load: ShellLoad): String =
    load.tempC?.let { stringResource(R.string.shell_ram_temp, load.ramFree, load.ramTotal, it) }
        ?: stringResource(R.string.shell_ram, load.ramFree, load.ramTotal)

/**
 * ⭐⭐⭐ **The sidebar** (the user's mock, 2026-10-09): the brand and build, Models · Flows · Results,
 * the CURRENT FLOW (its name, Unsaved, Save flow), the MODEL (name, state, RAM), then Settings and
 * About. It replaced the 3 × 2 tile grid and the status row under the brand.
 *
 * ⚠ Drawn by `MainActivity` LAST, over the library pages too: a library destination is drawn
 * after the canvas, and a sidebar inside the canvas would sit under it.
 * ⚠ Closes four ways: ✕, a tap on the scrim, a swipe left, Back. A row closes it as it opens its
 * destination.
 */
@Composable
fun ShellDrawer(
    open: Boolean,
    onClose: () -> Unit,
    version: String,
    onModels: () -> Unit,
    onFlows: () -> Unit,
    onResults: () -> Unit,
    flowName: String,
    flowDirty: Boolean,
    onSaveFlow: () -> Unit,
    load: ShellLoad?,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    BackHandler(enabled = open, onBack = onClose)
    // ⚠⚠ The canvas lets go of its text focus as the sidebar opens: a focused prompt's caret HANDLE
    // is drawn in its own popup window, above everything, and sat on top of the sidebar (the user's
    // screenshot, 2026-10-14). Clearing focus also drops the keyboard, which the sidebar never wants.
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.compose.runtime.LaunchedEffect(open) { if (open) focus.clearFocus(force = true) }
    // ⭐⭐ A row closes the sidebar at ONCE — no slide-out (the user, 2026-10-09: "it should be
    // snappy and close sidebar instantly"). ✕, the scrim, a swipe and Back still slide it away.
    var snap by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(open) { if (open) snap = false }
    val go: (() -> Unit) -> () -> Unit = { f -> { snap = true; onClose(); f() } }
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = if (snap) androidx.compose.animation.ExitTransition.None else fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { detectTapGestures { onClose() } },
            )
        }
        AnimatedVisibility(
            open,
            enter = slideInHorizontally { -it },
            exit = if (snap) androidx.compose.animation.ExitTransition.None else slideOutHorizontally { -it },
        ) {
            var pulled by remember { mutableFloatStateOf(0f) }
            Surface(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.86f)
                    .widthIn(max = 360.dp)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { d -> pulled += d },
                        onDragStarted = { pulled = 0f },
                        onDragStopped = { v -> if (pulled < -80f || v < -1200f) onClose() },
                    ),
                shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column(
                    Modifier.statusBarsPadding().navigationBarsPadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // ⭐ The brand (the app's one `BrandMark`) with the build under the name.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            BrandMark(version = "")
                            if (version.isNotEmpty()) {
                                Text(
                                    "v$version", style = NoteTextStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 52.dp),
                                )
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close))
                        }
                    }
                    DrawerRow(ModelsIcon, stringResource(R.string.nav_models), go(onModels))
                    DrawerRow(FlowsIcon, stringResource(R.string.nav_flows), go(onFlows))
                    DrawerRow(ResultsIcon, stringResource(R.string.nav_results), go(onResults))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    DrawerLabel(stringResource(R.string.drawer_current_flow))
                    StudioCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(FileIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                flowName, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            if (flowDirty) StateBadge(stringResource(R.string.shell_unsaved), MaterialTheme.studio.warning)
                        }
                        StudioOutlinedButton(
                            onClick = go(onSaveFlow),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, MaterialTheme.studio.accentText),
                        ) {
                            Icon(SaveIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.drawer_save_flow))
                        }
                    }
                    if (load != null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        DrawerLabel(stringResource(R.string.drawer_model))
                        StudioCard {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(CubeIcon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    load.model, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                )
                                when (load.state) {
                                    LoadState.IDLE -> StateBadge(stringResource(R.string.load_idle), MaterialTheme.studio.success)
                                    LoadState.LOADING -> StateBadge(stringResource(R.string.load_loading), MaterialTheme.studio.warning)
                                    LoadState.LOADED -> StateBadge(stringResource(R.string.load_loaded), MaterialTheme.studio.accentText)
                                    LoadState.NONE -> Unit
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(ChipIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    ramLine(load),
                                    style = metaValueStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    DrawerRow(Icons.Filled.Settings, stringResource(R.string.cd_settings), go(onSettings))
                    DrawerRow(Icons.Filled.Info, stringResource(R.string.drawer_about), go(onAbout))
                    // ⭐ Support (the user, 2026-10-14): Buy Me a Coffee, with why, and the other two apps.
                    DrawerLabel(stringResource(R.string.drawer_support))
                    var supporting by remember { mutableStateOf(false) }
                    DrawerRow(Icons.Filled.Favorite, stringResource(R.string.support_coffee)) { supporting = true }
                    if (supporting) SupportDialog { supporting = false }
                }
            }
        }
    }
}

/** The links the Support popup opens. ⚠ The same Buy Me a Coffee page as README and `.github/FUNDING.yml`. */
internal const val COFFEE_URL = "https://buymeacoffee.com/abrahampaulj"
internal const val NPUFORGE_URL = "https://github.com/AbrahamPaulJ/npuforge"
internal const val FACEFUSION_URL = "https://github.com/AbrahamPaulJ/facefusion-mobile"

/**
 * ⭐ Why support helps, the coffee link, and the author's other two apps (the user's words,
 * 2026-10-14). Links open in the browser; nothing is sent from the app.
 */
@Composable
internal fun SupportDialog(onDismiss: () -> Unit) {
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    fun open(url: String) = runCatching { uri.openUri(url) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.support_body), style = MaterialTheme.typography.bodyMedium)
                com.abrah.nightmare.ui.StudioButton(onClick = { open(COFFEE_URL) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.support_coffee))
                }
                DrawerLabel(stringResource(R.string.support_more))
                for ((name, body, url) in listOf(
                    Triple("npuforge", R.string.support_npuforge, NPUFORGE_URL),
                    Triple("FaceFusion Mobile", R.string.support_facefusion, FACEFUSION_URL),
                )) {
                    Surface(
                        onClick = { open(url) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { com.abrah.nightmare.ui.StudioButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

/** ⭐ A destination in the sidebar: icon, name, ›. */
@Composable
private fun DrawerRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ⭐ A section's name in the sidebar: small capitals in violet. */
@Composable
private fun DrawerLabel(text: String) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.sp),
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp),
    )
}

/** ⭐ A state as an outlined badge in its colour — Unsaved (amber), Idle (green), Loaded (violet). */
@Composable
internal fun StateBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color),
        contentColor = color,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
