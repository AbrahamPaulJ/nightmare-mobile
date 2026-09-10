package com.abrah.nightmare.ui

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
import androidx.compose.material.icons.Icons
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
    onOpenRecipe: (Recipe) -> Unit,
    onOpenSaved: (String) -> Unit,
    onDeleteSaved: (String) -> Unit,
    onRenameSaved: (String, String) -> Unit = { _, _ -> },
    /**
     * ⭐ Bring in a flow, or a pack of nodes, that someone sent you.
     *
     * ⚠ Nullable so this screen still renders in a preview and a golden, where
     * there is no `ActivityResultLauncher` to hand it — the same shape
     * `ModelsScreen.onImport` uses. Null hides the row rather than showing dead
     * buttons.
     */
    /** ⭐ Hand a saved flow to another app, as importable JSON. */
    onShareSaved: (String) -> Unit = {},
    onImportFlow: (() -> Unit)? = null,
    onImportPack: (() -> Unit)? = null,
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
            Text(
                error,
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxWidth().padding(top = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Section(stringResource(R.string.flows_recommended)) }
            items(recipes, key = { "r-${it.id}" }) { r ->
                // ⭐ The CARD opens it. An "Open" button beside a row whose
                // only purpose is to be opened is a second target for one
                // intent -- and on a phone the card is the bigger, easier one.
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenRecipe(r) },
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(r.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            r.about,
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { Section("Saved") }
            if (saved.isEmpty()) {
                item {
                    Text(
                        // ⚠ Says how, not just that it is empty. "No saved
                        // workflows" tells a user nothing they cannot see.
                        // ⚠ And it names where Save actually IS: this screen no
                        // longer has one, so copy pointing at a button on this
                        // screen would send the user looking for it here.
                        "Nothing saved yet — build a graph on the canvas, then press Save there.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(saved, key = { "s-${it.name}" }) { w ->
                // ⭐ Tap the card to open, as with a recipe above.
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
                                Icon(Icons.Filled.Create, contentDescription = "rename \"${w.name}\"")
                            }
                            // ⭐ Share the flow as the same JSON the Import
                            // button accepts — so what you send is what someone
                            // else can open, with no second format.
                            IconButton(onClick = { onShareSaved(w.name) }) {
                                Icon(
                                    com.abrah.nightmare.ui.ShareFlowIcon,
                                    contentDescription = "share \"${w.name}\"",
                                )
                            }
                            // ⚠⚠ Behind a confirm now. This deleted a workflow on
                            // a single mis-tap, next to "open", with no undo and
                            // no trace on disk.
                            IconButton(onClick = { deleting = w.name }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "delete \"${w.name}\"",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
            // ⭐⭐ Import LAST, under the saved flows.
            //
            // ⚠ It was FIRST, on the Models tab's reasoning that someone who
            // already has a file is not browsing ours. That is true of a
            // CHECKPOINT — a rare, deliberate act — and wrong here: the Flows
            // tab is opened to pick a flow, many times a session, and a card
            // about importing sat above the thing every visit is for. The
            // user's call, 2026-09-10.
            if (onImportFlow != null || onImportPack != null) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(stringResource(R.string.flows_import), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.flows_import_note),
                                style = LogTextStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                onImportFlow?.let {
                                    OutlinedButton(onClick = it) { Text(stringResource(R.string.flows_import_flow)) }
                                }
                                onImportPack?.let {
                                    OutlinedButton(onClick = it) { Text(stringResource(R.string.flows_import_pack)) }
                                }
                            }
                            // ⚠⚠ Said where the tap happens, not in a doc. There
                            // is no validation gate on an imported pack yet
                            // (`docs/ARCHITECTURE.md` §8c): the sandbox stops it
                            // reaching the network or the disk, nothing stops it
                            // looping forever.
                            if (onImportPack != null) {
                                Text(
                                    stringResource(R.string.flows_import_warning),
                                    style = LogTextStyle,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { from ->
        NameDialog(
            title = "Rename \"$from\"",
            initial = from,
            confirm = "rename",
            onDismiss = { renaming = null },
            onConfirm = { onRenameSaved(from, it); renaming = null },
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("This cannot be undone.", style = LogTextStyle) },
            confirmButton = {
                Button(
                    onClick = { onDeleteSaved(name); deleting = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
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
private fun NameDialog(
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
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank() && name.trim() != initial,
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}
