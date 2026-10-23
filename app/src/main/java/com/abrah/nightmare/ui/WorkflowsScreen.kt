package com.abrah.nightmare.ui

import com.abrah.nightmare.ui.StudioButton
import com.abrah.nightmare.ui.StudioTextButton
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.canvas.Recipe
import com.abrah.nightmare.canvas.SavedWorkflow

/**
 * Recommended graphs, and the user's own.
 *
 * ⭐ The point of the split: a person opening this app for the first time should
 * find a working img2img graph rather than have to wire one. `RECIPES` are
 * built-ins that always open; the saved list is whatever they kept.
 */
@Composable
fun WorkflowsScreen(
    recipes: List<Recipe>,
    saved: List<SavedWorkflow>,
    error: String?,
    onDismissError: () -> Unit = {},
    onOpenRecipe: (Recipe) -> Unit,
    onOpenSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onRenameSaved: (String, String) -> Unit = { _, _ -> },
    /** ⭐ Hand a saved flow to another app, as importable JSON. */
    onShareSaved: (String) -> Unit = {},
    /**
     * ⭐ What this phone can run, for the per-flow gate ([Recipe.runsOnDevice]).
     *
     * ⚠ A parameter rather than a [com.abrah.nightmare.DeviceProbe] call in
     * the body, so a golden can draw both sides of the gate without a device.
     */
    caps: com.abrah.nightmare.DeviceProbe.Caps = com.abrah.nightmare.DeviceProbe.caps(),
    modifier: Modifier = Modifier,
) {
    // ⚠ The name being edited, and the one being deleted. Local: an
    // unanswered dialog is not something to persist.
    var renaming by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }

    // ⚠ No header and no `statusBarsPadding`: [LibraryScreen] owns both, since
    // this is a TAB now rather than a whole screen. A second inset would
    // double it, and a second header would sit under the tab row.
    Column(modifier.fillMaxSize()) {
        if (error != null) {
            ErrorNotice(error, Modifier.padding(top = 8.dp), reportable = true, onClose = onDismissError)
        }

        // ⭐⭐ Recommended and Saved as SUB-TABS — the same pills Models uses
        // for its families ([SwipeTabs]), the user's call 2026-09-17. One list
        // with two section headings put every saved flow below every recipe,
        // so the list a returning user wants was always the one scrolled to.
        SwipeTabs(
            labels = listOf(
                stringResource(R.string.flows_recommended),
                stringResource(R.string.flows_saved),
            ),
            modifier = Modifier.padding(top = 8.dp),
        ) { page ->
            LazyColumn(
                // ⚠ No `navigationBarsPadding()` here any more: [LibraryScreen]
                // applies it for all three tabs, and both would double it.
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (page == 0) {
                    // ⭐⭐ One CARD per section ([Recipe.section]: Pictures, Faces, Advanced, Edit, Video), the user's
                    // call 2026-10-23; a run of recipes sharing one is a section, so the list's cost order holds.
                    val sections = recipes.fold(mutableListOf<MutableList<Recipe>>()) { acc, r ->
                        if (acc.lastOrNull()?.first()?.section == r.section) acc.last().add(r) else acc.add(mutableListOf(r))
                        acc
                    }
                    items(sections, key = { "s-${it.first().id}" }) { run ->
                        PanelCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(PANEL_PAD), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                run.first().section?.let {
                                    Text(sectionLabel(it), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.studio.accentText)
                                }
                                for (r in run) {
                                    // ⭐ The CARD opens it. An "Open" button beside a row whose
                                    // only purpose is to be opened is a second target for one
                                    // intent -- and on a phone the card is the bigger, easier one.
                                    //
                                    // ⭐⭐⭐ …unless this phone cannot run it. A flow
                                    // needing an arch this chip does not have is drawn dimmed,
                                    // is not clickable, and says why — the Models tab's
                                    // "Unsupported" treatment for a whole flow
                                    // ([Recipe.runsOnDevice]).
                                    val ok = r.runsOnDevice(caps)
                                    RecipeCard(r, ok, caps) { onOpenRecipe(r) }
                                }
                            }
                        }
                    }
                } else {
                    if (saved.isEmpty()) {
                        item {
                            Text(
                                // ⚠ Says how, not just that it is empty. "No saved
                                // workflows" tells a user nothing they cannot see.
                                // ⚠ And it names where Save actually IS: this screen no
                                // longer has one, so copy pointing at a button on this
                                // screen would send the user looking for it here.
                                stringResource(R.string.flows_empty_saved),
                                style = NoteTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(saved, key = { "s-${it.name}" }) { w ->
                        // ⭐ Tap the card to open, as with a recipe.
                        //
                        // ⚠⚠ The rename and delete icons stay ICONS inside it and keep
                        // their own click targets. Delete already asks first, which is
                        // what makes a destructive control safe to sit on a surface
                        // that is itself tappable -- it deleted a workflow on a single
                        // mis-tap once, and that is the fix that has to hold here.
                        Card(
                            Modifier.fillMaxWidth().clickable { onOpenSaved(w.name) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    w.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // ⭐ Rename. A saved graph accumulates meaning as it
                                    // is worked on, and the name chosen in the first
                                    // thirty seconds is rarely the one that fits.
                                    IconButton(onClick = { renaming = w.name }) {
                                        Icon(
                                            Icons.Filled.Create,
                                            contentDescription = stringResource(R.string.flows_cd_rename, w.name),
                                        )
                                    }
                                    // ⭐ Share the flow as the same JSON the Import
                                    // button accepts — so what you send is what someone
                                    // else can open, with no second format.
                                    IconButton(onClick = { onShareSaved(w.name) }) {
                                        // ⚠ The share ARROW. The node-graph glyph means
                                        // OPEN a flow (Results, the user's swap
                                        // 2026-09-11) — here it meant Share, one tab away.
                                        Icon(
                                            com.abrah.nightmare.ui.ShareIcon,
                                            contentDescription = stringResource(R.string.flows_cd_share, w.name),
                                        )
                                    }
                                    // ⚠⚠ Behind a confirm now. This deleted a workflow on
                                    // a single mis-tap, next to "open", with no undo and
                                    // no trace on disk.
                                    IconButton(onClick = { deleting = w.name }) {
                                        Icon(
                                            Icons.Filled.Delete,
                                            contentDescription = stringResource(R.string.flows_cd_delete, w.name),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                // ⚠ No Import card: a flow is imported with the "+" in the sheet's header, as a model is
                // (`LibraryScreen` flowsAction, the user's call 2026-10-23).
            }
        }
    }

    renaming?.let { from ->
        NameDialog(
            title = stringResource(R.string.flows_rename_title, from),
            initial = from,
            confirm = stringResource(R.string.flows_rename),
            onDismiss = { renaming = null },
            onConfirm = { onRenameSaved(from, it); renaming = null },
        )
    }

    deleting?.let { name ->
        ConfirmDelete(
            title = stringResource(R.string.flows_delete_title, name),
            // ⚠ Names what goes and what does not (`docs/UI.md` §7.5) — "this
            // cannot be undone" alone is the ceremony that rule forbids.
            body = stringResource(R.string.flows_delete_body),
            onConfirm = { onDeleteSaved(name) },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * One name, typed into a dialog.
 *
 * ⚠ Shared by save and rename because they are the same question, and two
 * copies of it would drift on the day one of them learns something.
 */
@Composable
internal fun NameDialog(
    title: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            StudioButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initial,
            ) { Text(confirm) }
        },
        dismissButton = { StudioOutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * ⭐⭐ A flow, in the Models card's style (the user's call, 2026-10-05):
 * rounded, its name, a chip badge, what it does, and Open on the right.
 * ⚠ The whole card opens it too — the bigger target on a phone. A flow this
 * phone cannot run is dimmed, does not open, and says why ([Recipe.runsOnDevice]).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecipeCard(
    r: Recipe,
    ok: Boolean,
    caps: com.abrah.nightmare.DeviceProbe.Caps,
    onOpen: () -> Unit,
) {
    val fade = if (ok) 1f else 0.45f
    // ⭐ Violet Studio: a plum card with a quiet border, an icon tile naming the kind of flow, the
    // text, and one Open.
    Card(
        Modifier.fillMaxWidth().then(if (ok) Modifier.clickable(onClick = onOpen) else Modifier),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(recipeIcon(r.id), contentDescription = null, tint = MaterialTheme.studio.accentText.copy(alpha = fade), modifier = Modifier.size(24.dp))
            }
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    r.labelRes?.let { stringResource(it) } ?: r.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = fade),
                )
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (r.minArch > 68) Badge(archLabel(r.minArch))
                    // ⚠ No section badge: the section is the card around this one (its heading).
                }
                Text(
                    r.aboutRes?.let { stringResource(it) } ?: r.about,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade),
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (!ok) {
                    // ⚠⚠ Names THIS phone, not just the requirement.
                    Text(
                        stringResource(
                            R.string.flow_unsupported,
                            caps.soc.ifEmpty { stringResource(R.string.flow_this_chip) },
                            caps.arch,
                            r.minArch,
                            archLabel(r.minArch),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (ok) {
                com.abrah.nightmare.ui.StudioButton(onClick = onOpen, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    androidx.compose.foundation.layout.Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.flows_open))
                }
            }
        }
    }
}

/** ⭐ The icon a recipe's card wears — what kind of flow it is, at a glance (Violet Studio). */
private fun recipeIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    "txt2img", "swap_t2i" -> ResultsIcon
    "img2img", "swap_i2i" -> SendToIcon
    "inpaint" -> EraserIcon
    "upscale" -> UpscaleIcon
    "face_edit" -> Icons.Filled.Face
    "animate_portrait" -> Icons.Filled.PlayArrow
    "flux_edit" -> Icons.Filled.Edit
    "t2v", "i2v", "motion" -> Icons.Filled.PlayArrow
    else -> FlowsIcon
}

/**
 * ⭐ A recipe section's heading in the reader's language. ⚠ [com.abrah.nightmare.canvas.Recipe.section] stays an id
 * ([com.abrah.nightmare.canvas.ADVANCED]): the list groups by it, so it must not change with the locale.
 */
@Composable
private fun sectionLabel(section: String): String = when (section) {
    com.abrah.nightmare.canvas.PICTURES -> stringResource(R.string.flows_section_pictures)
    com.abrah.nightmare.canvas.FACES -> stringResource(R.string.flows_section_faces)
    com.abrah.nightmare.canvas.ADVANCED -> stringResource(R.string.flows_section_advanced)
    com.abrah.nightmare.canvas.EDIT -> stringResource(R.string.flows_section_edit)
    com.abrah.nightmare.canvas.VIDEO -> stringResource(R.string.flows_section_video)
    else -> section
}
