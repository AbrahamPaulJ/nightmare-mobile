package com.abrah.nightmare.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
 *
 * ⚠⚠ **Rule changed 2026-09-26, at the user's ask: pill sub-tabs again** —
 * General · Add-ons · Translation · Downloads, the [SwipeTabs] Models uses.
 * What no longer held: the page had grown to theme, battery, LoRAs,
 * embeddings, download source and clean temp, and prompt translation brought
 * two download cards more. The reason the old tabs went — tabs with nothing
 * to switch to — does not apply to four pages that each have content.
 */
@Composable
fun SettingsScreen(
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
    /**
     * ⭐⭐ The LoRA adapters, importable HERE and listed on the Models tab's
     * Tools page — the same split the embeddings have, from the same call
     * (2026-09-20): importing is a one-off setup act, which is what this screen
     * is for, and two file pickers for one file is the duplicate-surface
     * mistake `docs/ARCHITECTURE.md` §5.6 keeps naming.
     */
    loras: List<EmbeddingRow>? = null,
    onImportLora: (() -> Unit)? = null,
    onDeleteLora: ((String) -> Unit)? = null,
    embeddings: List<EmbeddingRow>? = null,
    onImportEmbedding: (() -> Unit)? = null,
    onDeleteEmbedding: ((String) -> Unit)? = null,
    /**
     * ⭐ Commits a new download origin. ⚠ The caller writes it through
     * [Prefs.setDownloadBase] so it survives a restart; this screen only
     * reads [Prefs.downloadBase] back.
     */
    /**
     * ⚠⚠ A PARAMETER, never `Prefs.downloadBase` read here. That field is a
     * plain `var`, so a screen reading it directly never recomposes when it
     * changes — which is how the radios shipped 2026-09-20 looking dead
     * while the preference underneath them was working
     * ([HarnessViewModel.downloadBase]).
     */
    downloadBase: String = Prefs.HF_ORIGIN,
    /**
     * ⭐ Upstream's three low-RAM switches ([Prefs.lowRam]). ⚠ A parameter, for
     * the reason [downloadBase] is one. Null hides the section (previews, goldens).
     */
    lowRam: Prefs.LowRam? = null,
    onLowRam: (String, Boolean) -> Unit = { _, _ -> },
    /** ⭐ The local API (`docs/AGENT-API.md`). ⚠ A parameter, for the reason [downloadBase] is one. Null hides it. */
    api: ApiState? = null,
    onApi: (Boolean) -> Unit = {},
    onRegenerateApiToken: () -> Unit = {},
    /** ⭐ The view the app opens on, and the Nodes view's direction (the user's three views). */
    defaultView: com.abrah.nightmare.MainView = com.abrah.nightmare.MainView.NODES,
    onDefaultView: (com.abrah.nightmare.MainView) -> Unit = {},
    nodesVertical: Boolean = false,
    onNodesVertical: (Boolean) -> Unit = {},
    /** ⭐ The header's ☰ — the sidebar, from inside the sheet, as on Models / Flows / Results. */
    onMenu: (() -> Unit)? = null,
    onDownloadBase: (String) -> Unit = {},
    /**
     * ⭐ The LoRA browser's two settings (`docs/LORA-BROWSER.md`). ⚠ Parameters,
     * for the reason [downloadBase] is one. Null [civitaiKey] hides the section.
     */
    civitaiKey: String? = null,
    onCivitaiKey: (String) -> Unit = {},
    matureContent: Boolean = false,
    onMatureContent: (Boolean) -> Unit = {},
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
    /**
     * ⭐ The prompt-translation models, one row per language
     * ([com.abrah.nightmare.HarnessViewModel.translateRows]). ⚠ Null hides
     * the Translation tab.
     */
    translateRows: Map<com.abrah.nightmare.PromptTranslate.Source, ToolRow>? = null,
    onInstallTranslation: (com.abrah.nightmare.PromptTranslate.Source) -> Unit = {},
    onDeleteTranslation: (com.abrah.nightmare.PromptTranslate.Source) -> Unit = {},
    onCancelInstall: () -> Unit = {},
    /** ⭐ Where models live ([com.abrah.nightmare.ModelStorage]) and the move between places. */
    modelsPlace: com.abrah.nightmare.ModelStorage.Place = com.abrah.nightmare.ModelStorage.Place.APP,
    storageAccess: Boolean = false,
    strandedModels: Pair<Int, Long> = 0 to 0L,
    moveProgress: com.abrah.nightmare.ModelInstaller.Progress? = null,
    movePlan: com.abrah.nightmare.HarnessViewModel.MovePlan? = null,
    onModelsPlace: (com.abrah.nightmare.ModelStorage.Place) -> Unit = {},
    onConfirmMove: () -> Unit = {},
    onDismissMove: () -> Unit = {},
    /**
     * ⭐ Tag autocomplete's dictionaries ([com.abrah.nightmare.TagDictionary]). ⚠ A parameter, for
     * the reason [downloadBase] is one. Null hides the card.
     */
    tags: com.abrah.nightmare.TagDictionary.State? = null,
    tagProgress: com.abrah.nightmare.ModelInstaller.Progress? = null,
    onTagsEnabled: (Boolean) -> Unit = {},
    onDownloadTags: () -> Unit = {},
    onCancelTags: () -> Unit = {},
    /** True = the translation dictionary. */
    onImportTags: (Boolean) -> Unit = {},
    onClearTags: (Boolean) -> Unit = {},
    /** ⚠ For a golden, which cannot swipe. */
    initialPage: Int = 0,
    modifier: Modifier = Modifier,
) {
    var deletingEmbedding by remember { mutableStateOf<String?>(null) }
    var deletingLora by remember { mutableStateOf<String?>(null) }
    var deletingTranslation by remember { mutableStateOf<com.abrah.nightmare.PromptTranslate.Source?>(null) }
    /** Which dictionary is being deleted: false the tags, true the translations. */
    var deletingTags by remember { mutableStateOf<Boolean?>(null) }
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
    // ⭐ Which pages exist — Translation only when there are rows to draw
    // (null hides a section, the same convention as every slot here).
    val pages = listOfNotNull(
        Page.APPEARANCE, Page.PROMPTS, Page.MODELS,
        // ⚠ Hidden when it would be empty (a golden with no memory switches, an unrestricted battery).
        Page.PERFORMANCE.takeIf { lowRam != null || !batteryUnrestricted },
        Page.ADVANCED,
    )
    Column(
        // ⚠⚠ Its OWN insets: Settings is not a [LibraryScreen] tab, so the
        // bottom one that screen applies does not reach here. Reported from
        // the phone 2026-09-20 along with the three tabs — the Clean temp
        // button was the last thing on the page and sat under the gesture bar.
        // ⚠ `fillMaxSize` is also the FIXED height a tabbed sheet needs
        // (`docs/UI.md` §8.11): each page scrolls, the sheet does not resize.
        modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
    ) {
        // ⚠ No ✕: Settings is a pull-down sheet since 2026-09-26 ([PullDownSheet]).
        // ⭐ No ☰ of its own — the main bar's, in the sheet's gap, is the one (2026-10-10).
        ScreenHeader(stringResource(R.string.settings), onClose = null, onMenu = null)
        SwipeTabs(
            labels = pages.map { stringResource(it.label) },
            modifier = Modifier.weight(1f).padding(top = 8.dp),
            fillHeight = true,
            initialPage = initialPage,
        ) { index ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
        when (pages[index]) {
        // ⭐⭐ Five groups (the user's call, 2026-10-10): what it looks like, what goes into a
        // prompt, where models come from, what it costs the phone, and the rest.
        Page.APPEARANCE -> Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioCard {
                    SectionHeading(stringResource(R.string.theme_title))
                    // ⚠⚠ THREE choices, not a dark-mode switch: "follow the system" is not on/off.
                    for (t in Prefs.Theme.entries) {
                        Row(
                            Modifier.fillMaxWidth().clickable { onTheme(t) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = theme == t, onClick = { onTheme(t) })
                            Column {
                                Text(
                                    stringResource(
                                        when (t) {
                                            Prefs.Theme.SYSTEM -> R.string.theme_system
                                            Prefs.Theme.DARK -> R.string.theme_dark
                                            Prefs.Theme.LIGHT -> R.string.theme_light
                                        },
                                    ),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (t == Prefs.Theme.LIGHT) {
                                    Text(stringResource(R.string.theme_canvas_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                // ⭐ The views (2026-10-08): which one opens, and Nodes side to side or top to bottom.
                // ⚠ Agent is not offered as the default — it needs a key first.
                StudioCard {
                    SectionHeading(stringResource(R.string.views_title), stringResource(R.string.views_note))
                    Row(Modifier.fillMaxWidth()) {
                        for (v in listOf(com.abrah.nightmare.MainView.NODES, com.abrah.nightmare.MainView.GRAPH)) {
                            Row(
                                Modifier.weight(1f).clickable { onDefaultView(v) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = defaultView == v, onClick = { onDefaultView(v) })
                                Text(
                                    stringResource(if (v == com.abrah.nightmare.MainView.NODES) R.string.views_open_nodes else R.string.views_open_graph),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    com.abrah.nightmare.canvas.BoolKnobRow(
                        stringResource(R.string.views_nodes_vertical), stringResource(R.string.views_nodes_vertical_hint),
                        nodesVertical, onNodesVertical,
                    )
                }
        }
        Page.PROMPTS -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (tags != null) {
                TagDictionaryCard(tags, tagProgress, onTagsEnabled, onDownloadTags, onCancelTags, onImportTags) { deletingTags = it }
            }
            if (translateRows != null) {
                // ⭐⭐ The two language models behind a prompt box's translate
                // button (`docs/TRANSLATE.md`) — the SAME [ToolCard] the Models
                // tab's Tools page draws, so a download here has its size, bar and
                // Cancel (`docs/UI.md` §8.2).
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.translation_note),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for ((source, row) in translateRows.orEmpty()) {
                        ToolCard(
                            row, installing, { onInstallTranslation(source) }, onCancelInstall,
                            detail = stringResource(R.string.translate_about),
                        ) { deletingTranslation = source }
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
                                        style = NoteTextStyle,
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
        Page.MODELS -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // ⭐⭐⭐ **Where models live** ([com.abrah.nightmare.ModelStorage]) —
            // the user's ask, 2026-09-26: a folder a file picker can reach.
            // ⚠ Radios, the SAME [SourceRow] the download source below uses:
            // two places, one of them chosen.
            // ⚠⚠ All files access is asked only when `Download/` is PICKED,
            // never on opening this page — *"some users would be wary"*.
            Column(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.models_folder), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.models_folder_note),
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val inDownload = modelsPlace == com.abrah.nightmare.ModelStorage.Place.DOWNLOAD
                SourceRow(
                    label = stringResource(R.string.models_folder_app),
                    detail = "Android/data/com.abrah.nightmare/files",
                    selected = !inDownload,
                    onSelect = { onModelsPlace(com.abrah.nightmare.ModelStorage.Place.APP) },
                )
                SourceRow(
                    label = stringResource(R.string.models_folder_download),
                    detail = "Download/" + com.abrah.nightmare.ModelStorage.FOLDER,
                    selected = inDownload,
                    onSelect = { onModelsPlace(com.abrah.nightmare.ModelStorage.Place.DOWNLOAD) },
                )
                // ⭐ Local Dream's folder, used in place (`LocalDreamModels`) — needs the same access.
                Text(
                    stringResource(R.string.ld_folder_note),
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (inDownload && !storageAccess) {
                    Text(
                        stringResource(R.string.models_folder_no_access),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedButton(onClick = { onModelsPlace(com.abrah.nightmare.ModelStorage.Place.DOWNLOAD) }) {
                        Text(stringResource(R.string.models_folder_allow))
                    }
                }
                // ⭐ The move, on the SAME card a download uses — size, bar,
                // Cancel (`docs/UI.md` §8.2).
                val moving = moveProgress
                if (moving != null) {
                    DownloadCard(
                        title = stringResource(R.string.models_moving),
                        emphasised = false,
                        status = stringResource(R.string.results_mb_progress, moving.done shr 20, moving.total shr 20),
                        detail = moving.phase,
                        progress = moving,
                    ) {
                        OutlinedButton(onClick = onCancelInstall) { Text(stringResource(R.string.cancel)) }
                    }
                } else if (strandedModels.first > 0) {
                    // ⭐ What the OTHER place still holds — an interrupted move,
                    // or a folder that outlived an uninstall.
                    Text(
                        stringResource(
                            R.string.models_stranded,
                            strandedModels.first, sizeLabel(strandedModels.second),
                            stringResource(
                                if (inDownload) R.string.models_folder_app else R.string.models_folder_download,
                            ),
                        ),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { onModelsPlace(modelsPlace) }, enabled = !installing) {
                        Text(stringResource(R.string.models_move_here))
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
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⚠ Radios, not chips: the custom row owns a text field, which
                // no chip can hold. Same three-way shape as the theme above.
                val base = downloadBase
                val isOrigin = base == Prefs.HF_ORIGIN
                val isMirror = base == Prefs.HF_MIRROR
                var custom by remember(base) {
                    mutableStateOf(if (isOrigin || isMirror) "" else base)
                }
                // ⭐⭐ Custom is SELECTED on the tap, before any URL exists — Local Dream's way (the
                // user, 2026-10-10: the radio "cant be selected"). It used to select only once a
                // URL had been saved, so tapping it did nothing visible. The field is pre-filled
                // with `https://` and focused; the URL is saved on Done or when the field loses
                // focus (LD's `onFocusChanged`), and only once something follows the scheme.
                var customPicked by remember { mutableStateOf(false) }
                val customOn = customPicked || (!isOrigin && !isMirror)
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                val commit = {
                    val url = custom.trim()
                    if (url.removePrefix("https://").removePrefix("http://").isNotBlank() && url != base) onDownloadBase(url)
                }
                SourceRow(
                    label = stringResource(R.string.source_huggingface),
                    detail = Prefs.HF_ORIGIN,
                    selected = isOrigin && !customPicked,
                    onSelect = { customPicked = false; onDownloadBase(Prefs.HF_ORIGIN) },
                )
                SourceRow(
                    label = stringResource(R.string.source_mirror),
                    detail = Prefs.HF_MIRROR,
                    selected = isMirror && !customPicked,
                    onSelect = { customPicked = false; onDownloadBase(Prefs.HF_MIRROR) },
                )
                SourceRow(
                    label = stringResource(R.string.source_custom),
                    detail = null,
                    selected = customOn,
                    onSelect = {
                        customPicked = true
                        if (custom.isBlank()) custom = "https://"
                    },
                ) {
                    androidx.compose.runtime.LaunchedEffect(customPicked) {
                        if (customPicked) runCatching { focus.requestFocus() }
                    }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { custom = it; customPicked = true },
                        singleLine = true,
                        label = { Text(stringResource(R.string.source_custom_hint)) },
                        // ⚠⚠ Committed on DONE or focus loss, never per keystroke: every
                        // character would otherwise be a saved preference, and
                        // half a URL is a working setting that fetches nothing.
                        keyboardOptions = KeyboardOptions(
                            imeAction = ImeAction.Done,
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        ),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { if (!it.isFocused && customOn) commit() },
                    )
                }
            }
            // ⭐⭐ **CivitAI** — the key most downloads need and the mature switch
            // (civitai.red instead of civitai.com, off by default — the user's
            // call, 2026-10-07). The key field is the browser's own
            // [CivitaiKeyField], so the two places cannot disagree.
            if (civitaiKey != null) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(stringResource(R.string.lb_civitai_title), style = MaterialTheme.typography.labelLarge)
                    Text(
                        stringResource(R.string.lb_civitai_note),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CivitaiKeyField(civitaiKey, onCivitaiKey)
                    com.abrah.nightmare.canvas.BoolKnobRow(
                        stringResource(R.string.lb_mature), stringResource(R.string.lb_mature_hint),
                        matureContent, onMatureContent,
                    )
                }
            }
            // ⭐⭐ LoRAs — import / list / delete, above the embeddings and
            // built from the same two pieces ([ImportCallout] + a row card).
            //
            // ⚠ Above rather than below because a LoRA is the one a person
            // comes here for: it is picked on a node, so the picker sends them
            // here by name. An embedding is named in a prompt and needs no trip.
            if (onImportLora != null) {
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImportCallout(
                        title = stringResource(R.string.loras_title),
                        body = stringResource(R.string.loras_body),
                        onImport = onImportLora,
                    )
                    for (row in loras.orEmpty()) {
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
                                        style = NoteTextStyle,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (onDeleteLora != null) {
                                    OutlinedButton(onClick = { deletingLora = row.name }) {
                                        Text(stringResource(R.string.delete))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        Page.PERFORMANCE -> Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ⭐ Upstream's low-RAM switches (`docs/UI.md` §8.9a's one bool control).
                if (lowRam != null) {
                    StudioCard {
                        SectionHeading(stringResource(R.string.memory_title), stringResource(R.string.memory_note))
                        com.abrah.nightmare.canvas.BoolKnobRow(
                            stringResource(R.string.sdxl_lowram), stringResource(R.string.sdxl_lowram_hint),
                            lowRam.sdxl, { onLowRam(Prefs.KEY_SDXL_LOWRAM, it) },
                        )
                        com.abrah.nightmare.canvas.BoolKnobRow(
                            stringResource(R.string.anima_lowram), stringResource(R.string.anima_lowram_hint),
                            lowRam.anima, { onLowRam(Prefs.KEY_ANIMA_LOWRAM, it) },
                        )
                        com.abrah.nightmare.canvas.BoolKnobRow(
                            stringResource(R.string.anima_seq_dit), stringResource(R.string.anima_seq_dit_hint),
                            lowRam.animaSeqDit, { onLowRam(Prefs.KEY_ANIMA_SEQ_DIT, it) },
                            enabled = lowRam.anima,
                        )
                    }
                }
                // ⭐ Shown only while the battery manager may stop the app — see BackendKeepAliveService.
                if (!batteryUnrestricted) {
                    StudioCard {
                        SectionHeading(stringResource(R.string.battery_title), stringResource(R.string.battery_body))
                        OutlinedButton(onClick = onRequestBatteryUnrestricted, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.battery_allow))
                        }
                    }
                }
        }
        Page.ADVANCED -> Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (api != null) ApiSection(api, onApi, onRegenerateApiToken)
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
                    style = NoteTextStyle,
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
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
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
    deletingLora?.let { name ->
        com.abrah.nightmare.canvas.ConfirmDeleteLora(
            name,
            onConfirm = { onDeleteLora?.invoke(name) },
            onDismiss = { deletingLora = null },
        )
    }

    movePlan?.let { plan ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissMove,
            title = { Text(stringResource(R.string.models_move_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.models_move_body,
                        plan.count, sizeLabel(plan.bytes),
                        stringResource(
                            if (plan.to == com.abrah.nightmare.ModelStorage.Place.DOWNLOAD) R.string.models_folder_download
                            else R.string.models_folder_app,
                        ),
                    )
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = onConfirmMove) {
                    Text(stringResource(R.string.models_move_action))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onDismissMove) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    deletingTranslation?.let { source ->
        val row = translateRows?.get(source)
        ConfirmDelete(
            title = stringResource(R.string.models_delete_title, row?.label ?: source.name),
            body = stringResource(
                R.string.settings_delete_translation_body,
                (row?.onDisk ?: 0L) shr 20, source.bytes shr 20,
            ),
            onConfirm = { onDeleteTranslation(source) },
            onDismiss = { deletingTranslation = null },
        )
    }

    deletingTags?.let { translation ->
        ConfirmDelete(
            title = stringResource(if (translation) R.string.tag_translation else R.string.tag_dictionary),
            body = stringResource(R.string.tag_delete_body),
            onConfirm = { onClearTags(translation) },
            onDismiss = { deletingTags = null },
        )
    }

    deletingEmbedding?.let { name ->
        ConfirmDelete(
            title = stringResource(R.string.models_delete_title, name),
            body = stringResource(R.string.settings_delete_embedding_body),
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
    // ⭐ The whole row is the target, not only the 20dp circle.
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = androidx.compose.ui.semantics.Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label)
            detail?.let {
                Text(it, style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    // ⚠ Under the row, full width: a URL field beside a radio is unreadable
    // at phone width, which is the only width there is.
    if (selected) content?.invoke()
}

/** ⭐ Settings' pages, in pill order — the five groups (2026-10-10). */
private enum class Page(val label: Int) {
    APPEARANCE(R.string.settings_appearance),
    PROMPTS(R.string.settings_prompts),
    MODELS(R.string.settings_models),
    PERFORMANCE(R.string.settings_performance),
    ADVANCED(R.string.settings_advanced),
}

/** ⚠ MB below a gigabyte, one decimal of GB above — the unit the Models tab uses. */
private fun sizeLabel(bytes: Long): String =
    if (bytes >= 1L shl 30) String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    else "${bytes shr 20} MB"

/** ⭐ What Settings shows of the API: on/off, its token, the addresses it listens on. */
data class ApiState(val on: Boolean, val token: String, val addresses: List<String>)

/**
 * ⭐ Settings → API: the switch, then — while on — where to reach it and the token every call
 * but `/info` carries (`docs/AGENT-API.md` §3). The token's Copy uses the clipboard; New token
 * cuts off every client holding the old one.
 */
@Composable
private fun ApiSection(api: ApiState, onApi: (Boolean) -> Unit, onRegenerate: () -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var revealed by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    StudioCard {
        SectionHeading(stringResource(R.string.api_title), stringResource(R.string.api_note))
        com.abrah.nightmare.canvas.BoolKnobRow(
            stringResource(R.string.api_switch), stringResource(R.string.api_switch_hint, com.abrah.nightmare.api.ApiSettings.PORT),
            api.on, onApi,
        )
        if (!api.on) return@StudioCard
        if (api.addresses.isEmpty()) {
            Text(stringResource(R.string.api_no_network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // ⭐ Each address on its own row with Copy (Violet Studio).
        for (a in api.addresses) {
            val url = "http://$a:${com.abrah.nightmare.api.ApiSettings.PORT}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(url, style = metaValueStyle(), modifier = Modifier.weight(1f))
                CopyAction(url)
            }
        }
        Text(stringResource(R.string.api_token_label), style = MaterialTheme.typography.labelLarge)
        // ⭐⭐ The token is a credential: hidden until Reveal (the pack — and a screenshot of Settings
        // must not leak it).
        MetaPill {
            Text(
                if (revealed) api.token else "•••• •••• •••• ••••",
                style = metaValueStyle(),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.TextButton(onClick = { revealed = !revealed }) {
                Text(stringResource(if (revealed) R.string.api_hide else R.string.api_reveal))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // ⚠ Destructive first, primary last (`docs/UI.md` §8.1). Rotating asks: clients holding
            // the old token stop working.
            OutlinedButton(onClick = { rotating = true }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.api_regenerate))
            }
            androidx.compose.material3.Button(
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(api.token)) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.api_copy)) }
        }
    }
    if (rotating) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { rotating = false },
            title = { Text(stringResource(R.string.api_rotate_title)) },
            text = { Text(stringResource(R.string.api_rotate_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { rotating = false; onRegenerate() }) { Text(stringResource(R.string.api_regenerate)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { rotating = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * ⭐⭐ Settings → Prompts → Tag autocomplete (Local Dream's, 2026-10-10): the switch, then the tag
 * dictionary — Download (a1111-tagcomplete's Danbooru list, hosted with the models) or Import a
 * CSV — and the optional translation dictionary, import only. Each row says what is installed.
 */
@Composable
private fun TagDictionaryCard(
    s: com.abrah.nightmare.TagDictionary.State,
    progress: com.abrah.nightmare.ModelInstaller.Progress?,
    onEnabled: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onImport: (Boolean) -> Unit,
    onDelete: (Boolean) -> Unit,
) {
    StudioCard(Modifier.padding(top = 8.dp)) {
        com.abrah.nightmare.canvas.BoolKnobRow(
            stringResource(R.string.tag_autocomplete), stringResource(R.string.tag_autocomplete_hint),
            s.enabled, onEnabled,
        )
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.tag_dictionary), style = MaterialTheme.typography.labelLarge)
        Text(
            s.mainName?.let { stringResource(R.string.tag_dictionary_status, it, s.mainCount) }
                ?: stringResource(R.string.tag_dictionary_none),
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            if (progress.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.mainName == null) {
                    androidx.compose.material3.Button(onClick = onDownload) { Text(stringResource(R.string.download)) }
                }
                OutlinedButton(onClick = { onImport(false) }) {
                    Text(stringResource(if (s.mainName == null) R.string.tag_import else R.string.tag_replace))
                }
                if (s.mainName != null) {
                    OutlinedButton(onClick = { onDelete(false) }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text(stringResource(R.string.tag_translation), style = MaterialTheme.typography.labelLarge)
        Text(
            s.translationName?.let { stringResource(R.string.tag_translation_status, it, s.translationCount) }
                ?: stringResource(R.string.tag_translation_none),
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onImport(true) }) {
                Text(stringResource(if (s.translationName == null) R.string.tag_import else R.string.tag_replace))
            }
            if (s.translationName != null) {
                OutlinedButton(onClick = { onDelete(true) }) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}
