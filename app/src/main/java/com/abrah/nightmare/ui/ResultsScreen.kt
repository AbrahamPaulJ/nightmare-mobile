package com.abrah.nightmare.ui

import kotlinx.coroutines.launch
import com.abrah.nightmare.ui.StudioButton
import com.abrah.nightmare.ui.StudioOutlinedButton
import com.abrah.nightmare.ui.StudioTextButton
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import com.abrah.nightmare.canvas.ResultGroup
import androidx.compose.material.icons.filled.Info
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.abrah.nightmare.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.abrah.nightmare.canvas.Result
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.abrah.nightmare.canvas.rememberViewerBands
import com.abrah.nightmare.canvas.room
import com.abrah.nightmare.canvas.viewerBand
import com.abrah.nightmare.canvas.viewerPicture
import com.abrah.nightmare.canvas.viewerZoom
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged

/**
 * ⭐⭐ Pictures the user kept, each with the graph that made it.
 *
 * ⚠⚠ **The point of this tab is the FLOW, not the picture.** The gallery
 * already holds pictures; what it cannot hold is "make another one like this".
 * So every card leads with what would let you do that — the prompt, the model,
 * the seed — and its primary action is **Open the flow**, not "view".
 */
@Composable
fun ResultsScreen(
    /**
     * ⭐⭐ GROUPS, not a flat list. A 10x10 sweep is a hundred cards that bury
     * every other picture in the tab, so a batch is one entry that opens into
     * its items. ⚠ An ordinary render is a group of one, so the list has a
     * single shape and no row has to branch on "is this a batch".
     */
    groups: List<ResultGroup>,
    results: List<Result>,
    /** ⭐ Whether the Favourites chip is the one selected. */
    favouritesOnly: Boolean = false,
    onFavouritesOnly: (Boolean) -> Unit = {},
    /**
     * ⭐⭐ What a result can be filtered by — `HarnessViewModel.resultTags`.
     * Defaulted so a golden and a preview need no view model.
     */
    tagsOf: (Result) -> com.abrah.nightmare.ResultTags = {
        com.abrah.nightmare.ResultTags(emptySet(), "")
    },
    /**
     * ⭐⭐ Ids the user has long-pressed into a selection. Empty means normal
     * browsing — the mode is the SET being non-empty rather than a flag, so
     * there is no way for "in selection mode with nothing selected" to exist.
     */
    selected: Set<String> = emptySet(),
    onToggleSelect: (Result) -> Unit = {},
    /** ⚠ With the ids the filters SHOW: "All" picked hidden pictures too, and Delete then took them. */
    onSelectAll: (List<String>) -> Unit = {},
    /** ⚠ Drops selected ids a filter change has hidden, so an action never reaches what is off screen. */
    onRetainSelection: (Set<String>) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onDeleteSelected: () -> Unit = {},
    /** ⚠ A batch goes as a whole — see [BatchCard]. */
    onDeleteGroup: (ResultGroup) -> Unit = {},
    /** ⭐ Write the selected pictures into the phone's gallery. */
    onSaveSelected: () -> Unit = {},
    /** ⭐ Save one result to the gallery. */
    onSave: (Result) -> Unit = {},
    /** ⭐ Save every picture in a batch, as ONE action with one message. */
    onSaveGroup: (ResultGroup) -> Unit = {},
    /** ⭐ Hand the FLOW that made it to another app, as importable JSON. */
    onShareFlow: (Result) -> Unit = {},
    /** ⭐ Star or un-star one result — the same flag the canvas's star sets. */
    onToggleFavourite: (Result) -> Unit = {},
    /** ⚠ Thumbnails are decoded lazily by the caller and may not be ready yet. */
    thumbnailFor: (String) -> ImageBitmap?,
    onOpenFlow: (Result) -> Unit,
    onView: (Result) -> Unit,
    onDelete: (Result) -> Unit,
    onDiskBytes: Long,
    modifier: Modifier = Modifier,
    /** ⭐ The picture at size for the big preview. Falls back to the thumbnail. */
    imageFor: (String) -> ImageBitmap? = thumbnailFor,
    /** ⚠ True once a result's PNG has failed to decode — see [UnreadablePicture]. */
    unreadable: (String) -> Boolean = { false },
    /** ⭐ Info — what made it, from the stored flow. */
    detailsFor: (Result) -> List<Pair<String, String>> = { emptyList() },
    /** ⭐ GitHub #33: what its Info can send to the open flow ([InfoTransfer]); null offers nothing. */
    transferFor: (Result) -> InfoTransfer? = { null },
    /** ⭐ Diagnose from its Info ([ResultInfoDialog]): null where it cannot be. */
    diagnoseFor: (Result) -> (() -> Unit)? = { null },
    /** ⭐ Upscale with an installed upscaler; the enlarged picture becomes a new item. */
    onUpscale: (Result, String, Int) -> Unit = { _, _, _ -> },
    /** ⭐ UltraFix ([com.abrah.nightmare.UltraFix]) from the same chooser; null hides it. */
    onUltraFix: ((Result, com.abrah.nightmare.UltraFix.Params) -> Unit)? = null,
    /** ⭐ Edit face on a kept picture ([com.abrah.nightmare.canvas.FaceEditSheet]), after Upscale in both rows. */
    onEditFace: ((Result, Map<String, String>) -> Unit)? = null,
    upscalers: List<UpscalerRow> = emptyList(),
    onInstallUpscaler: (com.abrah.nightmare.UpscalerSpec) -> Unit = {},
    /** ⭐ Non-null while an upscale runs, naming it. */
    upscaling: String? = null,
    /** ⭐ Share these results as pictures/clips (false) or as their flows (true). */
    onShareResults: (Collection<String>, Boolean) -> Unit = { _, _ -> },
    /** ⭐ A short refusal or notice, as a toast. */
    onToast: (String) -> Unit = {},
    /** ⭐ Star or un-star every selected result — no confirm, it is undoable. */
    onStarSelected: () -> Unit = {},
    /** ⭐ Send the shown picture into a flow ([SendToDialog]). */
    onSendTo: (Result) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ⚠ One dialog for the whole list, not one per card — the same reason the
    // models screen hoists its delete confirm.
    var deleting by remember { mutableStateOf<Result?>(null) }
    // ⚠ Its own flag: deleting a SELECTION is a different question from
    // deleting one picture, and its confirm has to name a count.
    var deletingSelection by remember { mutableStateOf(false) }
    var deletingBatch by remember { mutableStateOf<ResultGroup?>(null) }

    // ⚠ The picture in the big frame, per tab. Null shows the newest.
    var shownId by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<Result?>(null) }
    var upscalingPick by remember { mutableStateOf<Result?>(null) }
    var confirmingDownload by remember { mutableStateOf(false) }
    /** ⭐ The ids a Share popup is asking about — one, or the selection. */
    var sharing by remember { mutableStateOf<List<String>?>(null) }
    val onlyPictureToFlow = stringResource(R.string.results_only_picture_to_flow)
    val faceNotClip = stringResource(R.string.face_edit_not_clip)
    var editingFace by remember { mutableStateOf<Result?>(null) }
    editingFace?.let { r -> FaceEditFor(r, imageFor, onEditFace, onToast) { editingFace = null } }

    Column(modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            // ⚠⚠ Says HOW to fill it.
            Text(
                stringResource(R.string.results_empty),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        val all = groups.flatMap { it.items }
        // ⭐⭐⭐ **The filter, beside Favourites** — asked for 2026-09-22.
        //
        // ⚠⚠ `hiddenModels` holds what is UNTICKED, not what is ticked. Every
        // model starts selected (the user's call), and a model that appears for
        // the first time after the sheet was opened must start selected too —
        // storing the ticked set would have hidden it.
        var hiddenModels by remember { mutableStateOf(emptySet<String>()) }
        var query by remember { mutableStateOf("") }
        var filtering by remember { mutableStateOf(false) }
        val models = remember(all) { all.flatMap { tagsOf(it).models }.distinct().sorted() }
        val items = all
            .filter { !favouritesOnly || it.favourite }
            .filter { r ->
                val tags = tagsOf(r)
                // ⚠ A result whose flow names NO model is never hidden by the
                // model filter: unticking "SDXL" means "not that one", not
                // "only things I can attribute".
                (tags.models.isEmpty() || tags.models.any { it !in hiddenModels }) &&
                    (query.isBlank() || tags.text.contains(query.trim(), ignoreCase = true))
            }
        val selecting = selected.isNotEmpty()
        LaunchedEffect(items, selected) {
            val visible = items.mapTo(HashSet()) { it.id }
            if (selected.any { it !in visible }) onRetainSelection(visible)
        }

        // ⭐⭐ A PERSISTENT notice while an upscale runs — a toast is gone in
        // seconds and an upscale is not (the user's call, 2026-09-17).
        upscaling?.let { what ->
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(R.string.results_upscaling_notice, what),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        // ⭐ All / Favourites — pills you TAP (the user's call, 2026-09-17: the
        // swipe belongs to the picture, which walks between results).
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (fav in listOf(false, true)) {
                val on = favouritesOnly == fav
                Surface(
                    onClick = { onFavouritesOnly(fav) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Text(
                        stringResource(if (fav) R.string.results_favourites else R.string.all),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            // ⚠ The dot says the list is not showing everything — a filter that
            // is ON and invisible is how someone concludes their pictures were
            // deleted.
            val filtered = hiddenModels.isNotEmpty() || query.isNotBlank()
            IconButton(onClick = { filtering = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    FilterIcon,
                    contentDescription = stringResource(R.string.results_cd_filter),
                    tint = if (filtered) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // ⭐⭐⭐ **Clearing a filter takes ONE tap, not three.** Asked for
            // 2026-09-22: *"add a clear btn to the right of filter if filter is
            // active"*.
            //
            // ⚠⚠ Only while something IS filtered — a permanent ✕ beside the
            // funnel would be a control that does nothing most of the time, and
            // the pair then reads as one two-part widget rather than as a state.
            // ⚠ It is the same `onClear` the dialog's own button calls, so the
            // two cannot come to mean different things.
            if (filtered) {
                IconButton(
                    onClick = { hiddenModels = emptySet(); query = "" },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.results_cd_clear_filter),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        if (filtering) {
            ResultFilterDialog(
                models = models,
                hidden = hiddenModels,
                query = query,
                onToggleModel = { m, on ->
                    hiddenModels = if (on) hiddenModels - m else hiddenModels + m
                },
                onQuery = { query = it },
                onClear = { hiddenModels = emptySet(); query = "" },
                onDismiss = { filtering = false },
            )
        }
        if (items.isEmpty()) {
            Text(
                stringResource(R.string.results_no_favourites),
                style = NoteTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        // ⭐⭐ The big preview FOLDS like a sheet: a handle above the grid hides it, and the grid
        // takes the screen (GitHub #25, 2026-10-15). ⚠ Open, the grid still keeps two full rows
        // and a peek of the third ([RESULT_GRID_ROWS]), smaller thumbnails ([RESULT_THUMB_MIN]):
        // the reporter saw three pictures under a frame that took the rest.
        var previewOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val gridRows = resultGridHeight(maxWidth)
        val roomHigh = maxHeight
        Column(Modifier.fillMaxSize()) {
        // ⭐⭐ The big frame SWIPES between results, and the grid follows it
        // (the user's call, 2026-09-17). ⚠ Two effects, one per direction, each
        // a no-op when the other side already agrees — so they cannot chase.
        // ⚠⚠ A shown picture that is GONE (deleted, filtered away) means the one that took its
        // index, not index 0 (the user, 2026-10-11: after a delete the grid ignored every tap
        // until Favourites and back). 0 sent the pager to page 0 while it settled where it was,
        // and the two effects below wrote over each other forever — `ResultsDeleteTest`.
        var lastShownIndex by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val shownIndex = items.indexOfFirst { it.id == shownId }
            .takeIf { it >= 0 } ?: lastShownIndex.coerceIn(0, items.lastIndex)
        androidx.compose.runtime.SideEffect { lastShownIndex = shownIndex }
        val pager = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = shownIndex, pageCount = { items.size },
        )
        LaunchedEffect(pager.settledPage, items.size) {
            items.getOrNull(pager.settledPage)?.let { if (it.id != shownId) shownId = it.id }
        }
        // ⚠⚠ A JUMP, not `animateScrollToPage` (the user, 2026-10-10: a second quick tap on the grid
        // "isnt registered"). A tap during the slide cancelled it, the pager SETTLED where it had
        // stopped, and the effect above wrote that page's id over the second tap — so it took a
        // third. A jump finishes in the frame; a swipe still slides under the finger.
        LaunchedEffect(shownIndex) {
            if (pager.currentPage != shownIndex) pager.scrollToPage(shownIndex)
        }
        val shown = items.getOrNull(pager.currentPage) ?: items.first()

        if (previewOpen) androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            key = { items[it].id },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp),
        ) { i ->
            val r = items[i]
            Box(
                Modifier
                    .fillMaxSize()
                    // ⭐ Observe-only (Initial pass, nothing consumed): did a touch
                    // reach the big frame at all? With `NmViewer: open` from the
                    // view model it splits "the tap never arrived" from "it
                    // arrived and was not a click" — the fullscreen viewer broke
                    // twice with nothing in any log (2026-09-27, 2026-10-01).
                    .pointerInput(r.id) {
                        awaitPointerEventScope {
                            while (true) {
                                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                e.changes.filter { it.pressed && !it.previousPressed }.forEach {
                                    android.util.Log.i("NmViewer", "down on ${r.id} selecting=$selecting picture=${imageFor(r.id) != null || thumbnailFor(r.id) != null}")
                                }
                                e.changes.filter { !it.pressed && it.previousPressed }.forEach {
                                    android.util.Log.i("NmViewer", "up on ${r.id} consumed=${it.isConsumed}")
                                }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val clip = r.videoPath
                // ⭐⭐ A clip PLAYS in the big frame, looping — the same player the
                // fullscreen viewer uses. ⚠ Only the page in view, so a swipe
                // does not leave players running off-screen.
                if (clip != null && i == pager.currentPage) {
                    com.abrah.nightmare.ui.ClipPlayer(
                        path = clip,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                    )
                    Box(
                        Modifier.fillMaxSize().clickable {
                            if (selecting) onToggleSelect(r) else onView(r)
                        },
                    )
                } else {
                    (imageFor(r.id) ?: thumbnailFor(r.id))?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = stringResource(R.string.cd_open_fullscreen),
                            // ⚠ Fit, not Crop: the honest view, edges included.
                            contentScale = ContentScale.Fit,
                            // ⚠⚠ Sized to the PICTURE's shape before the clip (the user, 2026-10-10:
                            // "add rounded corner for image in focus"): clipping the whole frame
                            // rounded the letterbox, and the picture inside kept square corners.
                            // The fullscreen viewer stays square.
                            modifier = Modifier
                                .aspectRatio(bmp.width.toFloat() / bmp.height.coerceAtLeast(1))
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { if (selecting) onToggleSelect(r) else onView(r) },
                        )
                    } ?: run { if (unreadable(r.id)) UnreadablePicture() }
                }
            }
        }

        // ⭐⭐ ONE action row. While selecting it BECOMES the selection's row —
        // no second bar at the top (the user's call, 2026-09-17).
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val onSurface = MaterialTheme.colorScheme.onSurface
            if (selecting) {
                Text(
                    stringResource(R.string.results_selected, selected.size),
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
                // ⚠⚠ An EQUAL SHARE each, not a fixed 40dp — see the other
                // branch. The share also does the Spacer's old job of pushing
                // the controls away from the count.
                val small = Modifier.weight(1f).height(40.dp)
                // ⚠ A gap from the count: contained, its edge touched "3 selected".
                StudioTextButton(onClick = { onSelectAll(items.map { it.id }) }, modifier = Modifier.padding(start = 8.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.all))
                }
                // ⭐ Star the selection — no confirm, a star is one tap to undo.
                val allStarred = all.filter { it.id in selected }.let { sel -> sel.isNotEmpty() && sel.all { it.favourite } }
                IconButton(onClick = onStarSelected, modifier = small) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(
                            if (allStarred) R.string.results_cd_unstar_selected else R.string.results_cd_star_selected,
                        ),
                        tint = if (allStarred) StarKept else StarIdle,
                    )
                }
                IconButton(onClick = { deletingSelection = true }, modifier = small) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.results_cd_delete_selected),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { confirmingDownload = true }, modifier = small) {
                    Icon(
                        DownloadIcon,
                        contentDescription = stringResource(R.string.results_cd_save_selected),
                        tint = onSurface,
                    )
                }
                IconButton(onClick = { sharing = selected.toList() }, modifier = small) {
                    Icon(ShareIcon, contentDescription = stringResource(R.string.results_cd_share_selected), tint = onSurface)
                }
                IconButton(onClick = onClearSelection, modifier = small) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.results_cd_stop_selecting), tint = onSurface)
                }
            } else {
                // ⚠⚠⚠ **An EQUAL SHARE of the row each, not a fixed size.**
                // 40dp was the last fix (seven 48dp icons pushed Open off a 360dp
                // phone, golden `history-360`) and it ran out again: eight 40dp
                // buttons are 320dp, and a 320dp phone has 288dp here, so Open
                // was squeezed to a speck (`NarrowSweep`, 2026-09-23). A share
                // is 36dp there — the 24dp icon still fits — and wider on a
                // wider phone, so the row can never outgrow the screen again.
                val small = Modifier.weight(1f).height(40.dp)
                IconButton(onClick = { deleting = shown }, modifier = small) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_result),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = { onToggleFavourite(shown) }, modifier = small) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = stringResource(
                            if (shown.favourite) R.string.results_cd_remove_favourite else R.string.results_cd_add_favourite,
                        ),
                        tint = if (shown.favourite) StarKept else StarIdle,
                    )
                }
                IconButton(onClick = { onSave(shown) }, modifier = small) {
                    Icon(DownloadIcon, contentDescription = stringResource(R.string.results_cd_save_gallery), tint = onSurface)
                }
                IconButton(onClick = { sharing = listOf(shown.id) }, modifier = small) {
                    Icon(ShareIcon, contentDescription = stringResource(R.string.err_share), tint = onSurface)
                }
                // ⭐ Send into a flow, after share. ⚠ Shown on a clip too and
                // refusing by name, as Upscale does beside it.
                IconButton(onClick = {
                    if (shown.videoPath != null) onToast(onlyPictureToFlow)
                    else onSendTo(shown)
                }, modifier = small) {
                    Icon(SendToIcon, contentDescription = stringResource(R.string.picture_cd_send_to_flow), tint = onSurface)
                }
                // ⚠ Always SHOWN, refusing by name (the user's call, 2026-09-17):
                // a button that vanishes on a clip teaches nothing.
                IconButton(onClick = {
                    val no = upscaleRefusal(context, shown.width, shown.height, shown.videoPath != null, upscaling)
                    if (no != null) onToast(no) else upscalingPick = shown
                }, modifier = small) {
                    Icon(
                        UpscaleIcon, contentDescription = stringResource(R.string.results_cd_upscale_picture),
                        tint = onSurface.copy(alpha = if (upscaling == null) 1f else 0.38f),
                    )
                }
                // ⭐ Edit face, after Upscale, refusing a clip by name as Upscale does.
                onEditFace?.let {
                    IconButton(onClick = {
                        if (shown.videoPath != null) onToast(faceNotClip) else editingFace = shown
                    }, modifier = small) {
                        Icon(Icons.Filled.Face, contentDescription = stringResource(R.string.face_edit_open), tint = onSurface)
                    }
                }
                IconButton(onClick = { info = shown }, modifier = small) {
                    Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.picture_cd_info_picture), tint = onSurface)
                }
                // ⭐⭐⭐ **A normal icon in the row** — the user's call,
                // 2026-09-22: *"make the open result icon same size and space it
                // properly like the other icon, its a normal item in that row"*.
                //
                // ⚠⚠ It was a filled `Button` pushed to the far right by a
                // `Spacer(weight(1f))`, which made it a different size, a
                // different colour and a different distance from its neighbours
                // than the seven icons before it — for no difference in what it
                // does. ⚠ Still the flow ICON and never the words (the user,
                // 2026-09-17, twice).
                IconButton(onClick = { onOpenFlow(shown) }, modifier = small) {
                    Icon(
                        ShareFlowIcon,
                        contentDescription = stringResource(R.string.open_flow),
                        tint = onSurface,
                    )
                }
            }
        }
        Text(
            listOfNotNull(
                shown.prompt?.takeIf { it.isNotBlank() },
                stringResource(R.string.results_kept_mb, results.size, onDiskBytes shr 20),
            ).joinToString(" · "),
            style = NoteTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // ⭐ The grid — small previews. Tap shows it above; long-press selects,
        // and while selecting a tap toggles.
        // ⭐⭐ It FOLLOWS the frame: a swipe to a picture whose preview is off
        // screen scrolls that preview into view (the user's ask, 2026-10-06).
        // ⚠ Only when not already fully visible — a tap on a visible preview
        // must not make the grid jump under the finger.
        val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        LaunchedEffect(shownIndex) {
            val info = grid.layoutInfo
            val seen = info.visibleItemsInfo.firstOrNull { it.index == shownIndex }
            val inside = seen != null && seen.offset.y >= info.viewportStartOffset &&
                seen.offset.y + seen.size.height <= info.viewportEndOffset
            if (!inside) grid.animateScrollToItem(shownIndex)
        }
        // ⭐ The sheet's handle: a tap folds or opens, a drag up folds and down opens.
        ResultsSheetHandle(open = previewOpen, onOpen = { previewOpen = it })
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            state = grid,
            // ⭐ Four across on most phones with 8dp gutters (Violet Studio), more on a wide screen.
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(RESULT_THUMB_MIN),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            // ⚠ Open: at least [gridRows] tall, at most half the room beyond it is the frame's.
            modifier = Modifier.fillMaxWidth().then(
                if (previewOpen) Modifier.height(maxOf(gridRows, roomHigh * 0.45f)) else Modifier.weight(1f),
            ),
        ) {
            items(items.size, key = { items[it].id }) { i ->
                val r = items[i]
                HistoryThumb(
                    thumb = thumbnailFor(r.id),
                    unreadable = unreadable(r.id),
                    inFrame = r.id == shown.id,
                    picked = r.id in selected,
                    favourite = r.favourite,
                    isClip = r.videoPath != null,
                    // ⚠ Folded, there is no frame to show it in: a tap opens it full screen.
                    onClick = {
                        if (selecting) onToggleSelect(r)
                        else { shownId = r.id; if (!previewOpen) onView(r) }
                    },
                    onLongClick = { onToggleSelect(r) },
                )
            }
        }
        }
        }
    }

    info?.let { r -> ResultInfoDialog(detailsFor(r), onClose = { info = null }, transfer = transferFor(r), onDiagnose = diagnoseFor(r)) }

    var ultrafixing by remember { mutableStateOf<Result?>(null) }
    ultrafixing?.let { r -> UltraFixFor(r, onUltraFix) { ultrafixing = null } }
    upscalingPick?.let { r ->
        UpscalePicker(
            upscalers = upscalers,
            onUltraFix = onUltraFix?.let { { upscalingPick = null; ultrafixing = r } },
            onPick = { id, scale -> upscalingPick = null; onUpscale(r, id, scale) },
            width = r.width,
            height = r.height,
            onInstall = onInstallUpscaler,
            onDismiss = { upscalingPick = null },
        )
    }

    // ⭐⭐ Share asks WHAT — the picture or the flow that made it — and, for a
    // selection, says how many (the user's call, 2026-09-17).
    sharing?.let { ids ->
        val n = ids.size
        AlertDialog(
            onDismissRequest = { sharing = null },
            title = { Text(pluralStringResource(R.plurals.results_share_count, n, n)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton(
                        onClick = { sharing = null; onShareResults(ids, false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(pluralStringResource(R.plurals.results_share_images, n, n)) }
                    StudioOutlinedButton(
                        onClick = { sharing = null; onShareResults(ids, true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(pluralStringResource(R.plurals.results_share_workflows, n, n)) }
                }
            },
            confirmButton = {},
            dismissButton = { StudioOutlinedButton(onClick = { sharing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmingDownload) {
        AlertDialog(
            onDismissRequest = { confirmingDownload = false },
            title = { Text(stringResource(R.string.results_save_selected_title, selected.size)) },
            text = {
                Text(
                    stringResource(R.string.results_save_selected_body, com.abrah.nightmare.ImageSaver.FOLDER)
                )
            },
            confirmButton = {
                StudioButton(onClick = { confirmingDownload = false; onSaveSelected() }) {
                    Text(stringResource(R.string.results_save_count, selected.size))
                }
            },
            dismissButton = { StudioOutlinedButton(onClick = { confirmingDownload = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ⚠ Every delete here asks through [ConfirmDelete], and says DELETE: it was
    // "Forget" on this screen alone, a fourth verb for the one action (the
    // user's call, 2026-09-15 — one word). `docs/UI.md` §8.2.
    deletingBatch?.let { g ->
        ConfirmDelete(
            title = stringResource(R.string.results_delete_batch_title),
            body = stringResource(R.string.results_delete_batch_body, g.size),
            confirmLabel = stringResource(R.string.results_delete_count, g.size),
            onConfirm = { onDeleteGroup(g) },
            onDismiss = { deletingBatch = null },
        )
    }

    if (deletingSelection) {
        ConfirmDelete(
            title = stringResource(R.string.results_delete_selected_title, selected.size),
            body = stringResource(R.string.results_delete_selected_body),
            confirmLabel = stringResource(R.string.results_delete_count, selected.size),
            onConfirm = onDeleteSelected,
            onDismiss = { deletingSelection = false },
        )
    }

    deleting?.let { r ->
        ConfirmDelete(
            title = stringResource(R.string.results_delete_picture_title),
            body = stringResource(R.string.results_delete_picture_body),
            onConfirm = { onDelete(r) },
            onDismiss = { deleting = null },
        )
    }
}

/**
 * ⭐⭐ A kept picture full screen — **swipeable, with the params behind an ⓘ.**
 *
 * ⚠⚠ The picture gets the screen. The first version stacked every param under
 * it in a scrolling column, so on a phone the thing you opened was a strip at
 * the top with a wall of text below it. Asked for from the phone, 2026-09-10:
 * prioritise going fullscreen, move the params to an info icon.
 *
 * ⚠⚠ **Swiping walks the set you opened from** — the batch when it is a batch,
 * the whole tab otherwise — because comparing two renders means going back and
 * forth between them, and closing and reopening for each is the interaction
 * that makes people stop comparing. Same shape as DreamUI's viewer.
 *
 * ⚠ Params come out of the STORED FLOW, not from fields beside it: the graph is
 * already kept verbatim, so a second copy would be two things to keep in step.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResultViewer(
    /** ⭐ The set being walked, in order. Never empty. */
    items: List<Result>,
    startIndex: Int,
    imageFor: (String) -> ImageBitmap?,
    /**
     * ⭐ Shown while [imageFor] is still decoding (it loads async and returns
     * null until it's ready) — same fallback shape as the big pager on
     * [ResultsScreen]. Without it, swiping to a picture not yet in the cache
     * showed a BLANK page for as long as the full-size decode took.
     */
    thumbnailFor: (String) -> ImageBitmap? = imageFor,
    /** ⚠ True once a result's PNG has failed to decode — see [UnreadablePicture]. */
    unreadable: (String) -> Boolean = { false },
    detailsFor: (Result) -> List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onOpenFlow: (Result) -> Unit,
    /** ⭐ GitHub #33: what its Info can send to the open flow ([InfoTransfer]). */
    transferFor: (Result) -> InfoTransfer? = { null },
    /** ⭐ Diagnose from its Info ([ResultInfoDialog]): null where it cannot be. */
    diagnoseFor: (Result) -> (() -> Unit)? = { null },
    onDelete: (Result) -> Unit,
    /** ⭐ Write this picture into the phone's gallery. */
    onSave: (Result) -> Unit = {},
    /** ⭐ Hand this picture to another app. */
    onShare: (Result) -> Unit = {},
    /** ⭐ Star it from the viewer — where a favourite is usually decided. */
    onToggleFavourite: ((Result) -> Unit)? = null,
    /** ⭐ Send this picture into a flow ([SendToDialog]). */
    onSendTo: ((Result) -> Unit)? = null,
    /**
     * ⭐⭐⭐ **Upscale from HERE too.** Asked for 2026-09-22 — the Results
     * ROW had the button and the fullscreen viewer did not, which is where a
     * person decides a picture is worth enlarging.
     *
     * ⚠ The upscalers and the in-flight name come with it, because this draws
     * the SAME [UpscalePicker] and asks the SAME [upscaleRefusal]; a viewer
     * with its own copy of either would be the N−1-of-N bug again.
     */
    upscalers: List<UpscalerRow> = emptyList(),
    upscaling: String? = null,
    onUpscale: ((Result, String, Int) -> Unit)? = null,
    onInstallUpscaler: (com.abrah.nightmare.UpscalerSpec) -> Unit = {},
    onToast: (String) -> Unit = {},
    onUltraFix: ((Result, com.abrah.nightmare.UltraFix.Params) -> Unit)? = null,
    /** ⭐ Edit face on a kept picture ([com.abrah.nightmare.canvas.FaceEditSheet]), after Upscale in both rows. */
    onEditFace: ((Result, Map<String, String>) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // ⭐ The page in view's player, for Save this frame ([saveClipFrame]).
    val clipHandle = rememberClipHandle()
    val frameScope = androidx.compose.runtime.rememberCoroutineScope()
    var upscalingPick by remember { mutableStateOf<Result?>(null) }
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )
    var showInfo by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    // ⭐⭐⭐ **40dp, not the 48dp default** — the same size the Results row
    // uses, and for the same reason.
    //
    // ⚠⚠⚠ This row is EIGHT actions. At 48dp that is 384dp of buttons on a
    // 360dp screen, so the last one was clipped to a sliver — which is what
    // *"the vertical purple line in fullscreen"* was (2026-09-22): the open-flow
    // button's fill, with its icon cut off. `PictureActions` hit exactly this
    // and dropped to 32dp; this row has more width to play with because it has
    // no surrounding sheet padding.
    // ⚠ 36dp since Edit face made it NINE (2026-10-21): 9 × 36 = 324dp fits a 360dp phone; 9 × 40 would not.
    val viewerIcon = Modifier.size(36.dp)
    var editingFace by remember { mutableStateOf<Result?>(null) }
    editingFace?.let { r -> FaceEditFor(r, imageFor, onEditFace, onToast) { editingFace = null } }
    // ⚠⚠ STATE objects, not values: they are handed to [viewerZoom], whose
    // gesture lambda outlives the composition that built it.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var scale by scaleState
    var offset by offsetState
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // ⭐ The chrome's real height, measured as the canvas viewer does ([com.abrah.nightmare.canvas.ViewerBands]).
    val bands = rememberViewerBands()
    val (roomTop, roomBottom) = bands.room()
    // ⚠ Swiping to a new picture starts it unzoomed. Carrying a 3x zoom onto
    // the next one shows a corner of something the user has not seen whole.
    LaunchedEffect(pager.currentPage) {
        scale = 1f
        offset = androidx.compose.ui.geometry.Offset.Zero
    }
    val current = items.getOrNull(pager.currentPage) ?: items.first()

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.96f))
            // ⚠⚠ The tap-to-close lives on the PICTURE now, not on this Box:
            // a `clickable` here would swallow the pinch before it reached the
            // image. Tapping the letterboxed margin still closes, because the
            // Image fills the Box.
            ,
    ) {
        // ⭐⭐ **Pinch to zoom, and the pager gets out of the way while zoomed.**
        //
        // ⚠⚠ It was a plain `Image` inside the pager, so the one gesture people
        // make on a full-screen picture did nothing at all. Reported from the
        // phone, 2026-09-10.
        //
        // ⚠⚠ `userScrollEnabled` is the load-bearing half: a horizontal drag on
        // a zoomed picture must PAN it, not flick to the next one — otherwise
        // examining detail near an edge throws away the zoom you just set up.
        // The canvas viewer solves the same conflict the same way
        // (`CanvasScreen.FullscreenImage`).
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = scale <= 1.01f,
        ) { page ->
            val item = items[page]
            // ⭐⭐ A kept CLIP plays, exactly as it does in the canvas viewer, and ZOOMS like a picture (GitHub #66):
            // the player is a `TextureView` now ([com.abrah.nightmare.ui.ClipPlayer]), so [viewerPicture] scales it.
            // ⚠ The gesture sits on a transparent box ABOVE the player, the same [viewerZoom] the pictures use
            // (pinch, double tap, pan, tap to close at 1x); touches never have to pass through the Android view.
            //
            // ⚠ `videoPath` is the COPY in the results directory
            // ([Result.videoPath]), so a clip starred three launches ago still
            // plays -- the one the graph produced is in `cacheDir`.
            val clip = item.videoPath
            if (clip != null) {
                com.abrah.nightmare.ui.ClipPlayer(
                    path = clip,
                    handle = clipHandle.takeIf { page == pager.currentPage },
                    modifier = Modifier
                        .fillMaxSize()
                        .viewerPicture(scale, offset, top = roomTop, bottom = roomBottom),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .onSizeChanged { box = it }
                        .viewerZoom(
                            key = page,
                            scale = scaleState,
                            offset = offsetState,
                            bounds = { box },
                            onDismiss = onDismiss,
                        )
                )
                return@HorizontalPager
            }
            (imageFor(item.id) ?: thumbnailFor(item.id))?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = item.batchLabel.ifBlank { stringResource(R.string.cd_kept_picture) },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // ⚠⚠ Clear of the actions above and the seed below,
                        // and CLIPPED to that band so a zoom cannot draw over
                        // either — the same function the canvas viewer calls.
                        .viewerPicture(scale, offset, top = roomTop, bottom = roomBottom)
                        // ⚠⚠⚠ **[viewerZoom], the same gesture the canvas
                        // viewer answers to.** It consumes ONLY when the
                        // gesture is really ours — two fingers down, or already
                        // zoomed in — so a one-finger drag at 1x falls through
                        // to the pager, which is exactly the swipe.
                        // `detectTransformGestures` consumes everything, and
                        // with it here swiping to the next picture did nothing
                        // at ALL. Reported from the phone 2026-09-10; one
                        // function since 2026-09-22, so the next fix lands on
                        // both viewers.
                        // ⚠ `bounds` is the page's own size, so a pan cannot
                        // fling the picture off screen.
                        .onSizeChanged { box = it }
                        .viewerZoom(
                            key = page,
                            scale = scaleState,
                            offset = offsetState,
                            bounds = { box },
                            onDismiss = onDismiss,
                        ),
                )
            } ?: run {
                if (unreadable(item.id)) UnreadablePicture(
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxSize().pointerInput(page) {
                        detectTapGestures(onTap = { onDismiss() })
                    },
                )
            }
        }

        // ⚠ Position in the set, only when there IS a set. "1 of 1" is noise.
        // ⚠⚠ BELOW the picture, not above it: at the top it sat in the same
        // band as the action row and read as a label for the buttons.
        // ⭐⭐ The seed WITH its copy button, BELOW the picture and centred —
        // the canvas viewer's layout (the user's call, 2026-09-17). It sat in
        // the action row, where it read as one more button.
        androidx.compose.foundation.layout.Column(
            Modifier
                .align(Alignment.BottomCenter)
                .viewerBand(bands, top = false)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // ⭐ Room between the size / seed pills and the page count under them (the user,
            // 2026-10-09: the pills sat on top of the pagination).
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // ⭐⭐ The SIZE first, in its own pill, then the seed in its own —
            // asked for 2026-09-22, and the same pair the canvas viewer draws.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current.width > 0) {
                    com.abrah.nightmare.canvas.SizePill(
                        current.width, current.height,
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
                current.seed?.let { seed ->
                    com.abrah.nightmare.canvas.SeedRow(
                        seed = seed,
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
            if (items.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${items.size}" +
                        current.batchLabel.let { if (it.isBlank()) "" else "   $it" },
                    style = NoteTextStyle,
                    color = androidx.compose.ui.graphics.Color.White,
                )
            }
        }

        // ⚠⚠ The actions at the TOP, over the picture.
        //
        // ⚠ They were along the bottom edge, where a phone's gesture bar and
        // the swipe this viewer now uses both live — a row of targets sitting
        // exactly where the thumb sweeps. Asked for from the phone, 2026-09-10.
        //
        // ⚠ Order is the card's order still: destructive, then secondary, then
        // the primary action last. `docs/UI.md` requires the same SET on every
        // surface showing one picture; the same arrangement is what makes
        // muscle memory safe between them.
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .viewerBand(bands, top = true)
                .statusBarsPadding()
                // ⚠⚠ **`statusBarsPadding()` is not enough on its own.** It
                // clears the status bar and leaves the row flush against it,
                // which on this phone reads as "jammed into the top". Chrome
                // needs its own breathing room ON TOP of the inset — see
                // `docs/UI.md` §7, written after correcting this three times.
                // ⚠ +20dp on top of the inset: the row still read as jammed
                // into the top edge. `docs/UI.md` §7.2 — an inset is not
                // padding, and "clears the status bar" is not "has room".
                .padding(top = 32.dp, end = 8.dp, bottom = 8.dp, start = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠⚠ **Behind a confirm, like every other delete in this app.** It
            // was the one destructive control with no confirm — a single tap,
            // full screen, next to the buttons you actually came for, deleting
            // the picture AND its flow with no undo. Careless; reported from the
            // phone, 2026-09-10.
            IconButton(onClick = { confirmingDelete = true }, modifier = viewerIcon) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_result),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            // ⭐⭐⭐ **STAR before DOWNLOAD, matching the Results row above.**
            //
            // ⚠⚠⚠ Reported 2026-09-22: *"in results tab when i fullscreen img
            // the position of star and download btn get exchanged"*. They were:
            // the row draws delete · star · download · share · upscale · info
            // (`docs/UI.md` §8.11) and this viewer drew delete · download · star.
            // Opening a picture full screen moved two buttons under the thumb
            // that had just been on them.
            // ⚠ N−1 of N again (`docs/ARCHITECTURE.md` §5.6), and the missed
            // place was the one the finger found.
            onToggleFavourite?.let { toggle ->
                IconButton(onClick = { toggle(current) }, modifier = viewerIcon) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (current.favourite) {
                            stringResource(R.string.results_cd_remove_favourite)
                        } else {
                            stringResource(R.string.results_cd_add_favourite)
                        },
                        tint = if (current.favourite) com.abrah.nightmare.ui.StarKept
                            else androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
            // ⭐ Save to the phone's gallery. ⚠ Distinct from "keep": keeping is
            // this app remembering the flow, saving is a PNG anyone else can
            // open — and a picture worth looking at full screen is exactly
            // where someone wants that.
            // ⚠ The DOWNLOAD glyph: the floppy means "keep in Results"
            // everywhere since 2026-09-15 and this writes to the gallery.
            IconButton(onClick = { onSave(current) }, modifier = viewerIcon) {
                Icon(
                    com.abrah.nightmare.ui.DownloadIcon,
                    contentDescription = stringResource(R.string.results_cd_save_gallery),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // ⚠ The seed is drawn BELOW the picture now — see above. ⚠ Still no
            // `onLock`: locking writes onto the OPEN canvas, and a result in a
            // list is not necessarily from that graph.
            IconButton(onClick = { onShare(current) }, modifier = viewerIcon) {
                Icon(
                    ShareIcon,
                    contentDescription = stringResource(R.string.picture_cd_share_picture),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // ⭐ Save this frame of a clip, after share ([PictureActions]' onSaveFrame).
            current.videoPath?.let { path ->
                val saved = stringResource(R.string.clip_frame_saved)
                val failed = stringResource(R.string.clip_frame_failed)
                IconButton(onClick = {
                    val at = clipHandle.positionMs
                    frameScope.launch {
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { saveClipFrame(context, path, at) }.isSuccess
                        }
                        onToast(if (ok) saved else failed)
                    }
                }, modifier = viewerIcon) {
                    Icon(FrameIcon, contentDescription = stringResource(R.string.picture_cd_save_frame), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            // ⭐ Send into a flow, after share — [PictureActions]' order. ⚠ Not
            // on a clip: no flow takes a video as its input.
            onSendTo?.takeIf { current.videoPath == null }?.let { send ->
                IconButton(onClick = { send(current) }, modifier = viewerIcon) {
                    Icon(
                        SendToIcon,
                        contentDescription = stringResource(R.string.picture_cd_send_to_flow),
                        tint = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
            // ⚠ Before info, matching the Results row's order exactly
            // (`docs/UI.md` §8.11): delete · star · download · share · send ·
            // upscale · info · Open.
            onUpscale?.let {
                IconButton(onClick = {
                    val no = upscaleRefusal(
                        context, current.width, current.height, current.videoPath != null, upscaling,
                    )
                    if (no != null) onToast(no) else upscalingPick = current
                }, modifier = viewerIcon) {
                    Icon(
                        UpscaleIcon,
                        contentDescription = stringResource(R.string.results_cd_upscale_picture),
                        tint = androidx.compose.ui.graphics.Color.White
                            .copy(alpha = if (upscaling == null) 1f else 0.38f),
                    )
                }
            }
            // ⭐ Edit face, after Upscale, as in the Results row.
            onEditFace?.let {
                val notClip = stringResource(R.string.face_edit_not_clip)
                IconButton(onClick = { if (current.videoPath != null) onToast(notClip) else editingFace = current }, modifier = viewerIcon) {
                    Icon(Icons.Filled.Face, contentDescription = stringResource(R.string.face_edit_open), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            IconButton(onClick = { showInfo = !showInfo }, modifier = viewerIcon) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = stringResource(R.string.picture_cd_info_picture),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // ⚠⚠ No Close BUTTON. Tapping the background closes it, exactly as
            // the canvas's own fullscreen viewer does (`CanvasScreen`'s
            // `FullscreenImage`: `onTap = { if (scale <= 1.01f) onDismiss() }`).
            // Two fullscreen viewers in one app must not be left with two
            // different ways out.
            // ⭐⭐⭐ **A normal icon here too**, same as the Results row.
            //
            // ⚠⚠⚠ As a filled `Button` at the end of eight 48dp icons it did
            // not fit a 360dp screen, and what was actually on the phone was a
            // sliver of its purple background down the right edge — reported
            // 2026-09-22 as *"remove the vertical purple line in fullscreen"*
            // AND as *"show that icon in fullscreen"*, which are the same bug
            // twice: the icon was clipped away and only the fill was left.
            // Exactly the overflow that made the ⓘ a dot on the inspector row
            // (`PictureActions`), which is why the whole row is 32dp now.
            IconButton(onClick = { onOpenFlow(current) }, modifier = viewerIcon) {
                Icon(
                    ShareFlowIcon,
                    contentDescription = stringResource(R.string.open_flow),
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
        }

        // ⭐ The SAME info dialog the Results row opens — one function, so the two
        // cannot drift (`docs/ARCHITECTURE.md` §5.6).
        if (showInfo) ResultInfoDialog(detailsFor(current), onClose = { showInfo = false }, transfer = transferFor(current), onDiagnose = diagnoseFor(current))

        if (confirmingDelete) {
            // ⚠ The SAME body as the card's confirm — one constant, not two copies.
            ConfirmDelete(
                title = stringResource(R.string.results_delete_picture_title),
                body = stringResource(R.string.results_delete_picture_body),
                onConfirm = { onDelete(current) },
                onDismiss = { confirmingDelete = false },
            )
        }
        // ⚠ The SAME chooser the Results row opens — see [UpscalePicker].
        var ultrafixing by remember { mutableStateOf<Result?>(null) }
        ultrafixing?.let { r -> UltraFixFor(r, onUltraFix) { ultrafixing = null } }
        upscalingPick?.let { r ->
            UpscalePicker(
                upscalers = upscalers,
                onUltraFix = onUltraFix?.let { { upscalingPick = null; ultrafixing = r } },
                onPick = { id, scale -> upscalingPick = null; onUpscale?.invoke(r, id, scale) },
                width = r.width,
                height = r.height,
                onInstall = onInstallUpscaler,
                onDismiss = { upscalingPick = null },
            )
        }
    }
}

// ⚠ `BatchCard`, `ResultCard` and `ResultCardHeader` were deleted: nothing called them since the grid
// replaced the card list ([HistoryThumb]). `onSaveGroup`, `onShareFlow` and `onDeleteGroup` only fed them.

/** ⭐ A grid thumbnail's corners — its clip AND its ring, so the two cannot disagree. */
private val ThumbShape = RoundedCornerShape(8.dp)

/** ⭐ The grid's narrowest thumbnail: four across a 360dp phone (it was 104, three). */
internal val RESULT_THUMB_MIN = 80.dp
private val RESULT_GRID_GAP = 8.dp
/** ⭐ Rows the grid always shows under the open frame: two whole and a peek of a third. */
internal const val RESULT_GRID_ROWS = 2.35f

/** ⭐ The grid's height for [RESULT_GRID_ROWS] rows at this width, as `Adaptive` lays them out. */
internal fun resultGridHeight(width: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val cols = ((width + RESULT_GRID_GAP) / (RESULT_THUMB_MIN + RESULT_GRID_GAP)).toInt().coerceAtLeast(1)
    val cell = (width - RESULT_GRID_GAP * (cols - 1)) / cols
    return (cell + RESULT_GRID_GAP) * RESULT_GRID_ROWS
}

/**
 * ⭐ The handle over the grid, a sheet's: the chevron says which way it goes. ⚠ Full width and
 * 24dp tall so it is easy to hit, and a drag works too (up folds the frame away, down brings it).
 */
@Composable
private fun ResultsSheetHandle(open: Boolean, onOpen: (Boolean) -> Unit) {
    var dragged by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clickable { onOpen(!open) }
            .pointerInput(open) {
                detectVerticalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        val far = 24.dp.toPx()
                        if (dragged < -far && open) onOpen(false)
                        else if (dragged > far && !open) onOpen(true)
                    },
                ) { _, dy -> dragged += dy }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = stringResource(if (open) R.string.results_cd_fold_preview else R.string.results_cd_open_preview),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal const val RESULT_THUMB_TAG = "result_thumb"

/** ⭐ One small preview in History's grid. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun HistoryThumb(
    thumb: ImageBitmap?,
    unreadable: Boolean = false,
    inFrame: Boolean,
    picked: Boolean,
    favourite: Boolean,
    isClip: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(ThumbShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // ⭐ "In the frame" as `selected`, so a test can see which thumb the frame shows.
            .testTag(RESULT_THUMB_TAG)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { selected = inFrame },
    ) {
        thumb?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } ?: run { if (unreadable) UnreadablePicture() }
        // ⭐⭐⭐ **The selection ring is drawn OVER the picture** — and that is
        // the fix, not the width.
        //
        // ⚠⚠⚠ It was `.border()` in this Box's own modifier chain, which
        // draws BEFORE the children. The thumbnail is `fillMaxSize` + `Crop`, so
        // it painted straight over the ring and left at most a corner of it
        // showing. Reported 2026-09-22 as *"show a clearer border for selected
        // item"* — the border was there, the picture was on top of it.
        // ⚠⚠ 3dp for a picked item, 2dp for the one merely in the frame: the
        // two mean different things and the thicker one is the one you chose.
        if (picked || inFrame) {
            Box(
                Modifier
                    .matchParentSize()
                    .border(
                        // ⭐ Violet Studio: the picture in the frame wears the accent; a multi-select
                        // pick the filled violet, thicker.
                        width = if (picked) 3.dp else 2.dp,
                        color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.studio.accentText,
                        // ⚠ The thumbnail's own shape — a 12dp ring on an 8dp picture left
                        // the corners mismatched (the user, 2026-10-10).
                        shape = ThumbShape,
                    ),
            )
        }
        // ⭐ A clip is a STILL here with a play mark — only the big frame plays.
        if (isClip) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    androidx.compose.material.icons.Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.results_cd_clip),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (favourite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = StarKept,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(14.dp),
            )
        }
        if (picked) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)))
        }
    }
}

/**
 * ⭐ A kept result whose PNG will not decode, said in words rather than drawn as
 * an empty square that looks like it is still loading.
 *
 * ⚠ The ONE drawing of it, called by the big frame, the grid and the fullscreen
 * viewer alike — three surfaces that must agree. It stays selectable and
 * deletable like any other card, which is the point: nothing else can repair
 * the file, so the person holding the phone has to be able to find it.
 */
@Composable
internal fun UnreadablePicture(
    modifier: Modifier = Modifier.fillMaxSize(),
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    /** ⭐ [ClipPlayer] says "clip" ([R.string.clip_unreadable]). */
    text: Int = R.string.results_unreadable,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            stringResource(text),
            style = NoteTextStyle,
            color = color,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(6.dp),
        )
    }
}

/**
 * ⭐⭐ What made a result — small type so most flows fit without scrolling, and
 * ONE copy button for the whole text (the user's call, 2026-09-17: a copy per
 * row was clutter; what people paste is the lot).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ResultInfoDialog(
    details: List<Pair<String, String>>,
    /**
     * ⭐⭐ GitHub #33: the rows that can go to the open flow ([InfoTransfer]). Null on the canvas's
     * own Info, whose pictures are already on the canvas.
     */
    transfer: InfoTransfer? = null,
    /**
     * ⭐ Diagnose this picture ([com.abrah.nightmare.HarnessViewModel.diagnosePicture]): a DiT picture whose model is
     * installed. Null offers nothing. Asks first: it is many renders.
     */
    onDiagnose: (() -> Unit)? = null,
    onClose: () -> Unit,
) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var askingDiagnose by remember { mutableStateOf(false) }
    if (askingDiagnose && onDiagnose != null) {
        AlertDialog(
            onDismissRequest = { askingDiagnose = false },
            title = { Text(stringResource(R.string.diagnose)) },
            text = { Text(stringResource(R.string.diagnose_picture_note, com.abrah.nightmare.BlankDiagnosis.LEGS.size)) },
            dismissButton = { StudioOutlinedButton(onClick = { askingDiagnose = false }) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                StudioButton(onClick = { askingDiagnose = false; onClose(); onDiagnose() }) { Text(stringResource(R.string.diagnose)) }
            },
        )
        return
    }
    val all = details.joinToString("\n") { (k, v) -> "$k: $v" }
    // ⭐ Selected rows by index; -1 is the picture row. Empty = not selecting.
    var selected by remember(details) { mutableStateOf(emptySet<Int>()) }
    val items: Map<Int, TransferItem> = if (transfer == null) emptyMap() else buildMap {
        transfer.picturePath?.let { put(-1, TransferItem.Picture(it)) }
        details.forEachIndexed { i, (k, v) ->
            // ⚠ The FIRST of each kind: a chain repeats Prompt/Seed per sampler, and two of one
            // kind could only go to the same place.
            transferItemOf(k, v)?.takeIf { it -> values.none { o -> o::class == it::class } }?.let { put(i, it) }
        }
    }
    fun toggle(i: Int) { selected = if (i in selected) selected - i else selected + i }
    @Composable
    fun Modifier.transferable(i: Int): Modifier {
        if (i !in items) return this
        val on = i in selected
        return this
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = if (on) 0.28f else 0.08f))
            .combinedClickable(
                onClick = { if (selected.isNotEmpty()) toggle(i) },
                onLongClick = { toggle(i) },
            )
            .padding(horizontal = 4.dp, vertical = 2.dp)
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.results_info)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (items.isNotEmpty()) {
                    Text(
                        stringResource(R.string.transfer_hint), style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (-1 in items) {
                    Row(
                        Modifier.fillMaxWidth().transferable(-1),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.transfer_picture),
                            fontSize = 11.sp, lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(78.dp),
                        )
                        transfer?.picture?.let {
                            androidx.compose.foundation.Image(
                                bitmap = it, contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        }
                    }
                }
                details.forEachIndexed { i, (k, v) ->
                    Row(Modifier.fillMaxWidth().transferable(i), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            k,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(78.dp),
                        )
                        Text(v, fontSize = 11.sp, lineHeight = 14.sp)
                    }
                }
            }
        },
        // ⚠ Up to three buttons: stacked ([DialogButtonStack]), never a Row in one slot.
        confirmButton = {
            DialogButtonStack {
                val wide = Modifier.fillMaxWidth()
                if (selected.isNotEmpty() && transfer != null) {
                    StudioButton(onClick = {
                        val chosen = selected.sorted().mapNotNull { items[it] }
                        onClose()
                        transfer.onUse(chosen)
                    }, modifier = wide) { Text(stringResource(R.string.transfer_use_n, selected.size)) }
                }
                if (selected.isEmpty() && onDiagnose != null) {
                    StudioButton(onClick = { askingDiagnose = true }, modifier = wide) { Text(stringResource(R.string.diagnose)) }
                }
                StudioOutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(all)) }, modifier = wide) {
                    Icon(CopyIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.results_copy_all))
                }
                StudioOutlinedButton(onClick = onClose, modifier = wide) { Text(stringResource(R.string.close)) }
            }
        },
    )
}


/**
 * ⭐ The scale the upscaler chooser last used — kept for the session, so the
 * next upscale starts where the last one was set.
 */
private var upscaleScale by androidx.compose.runtime.mutableIntStateOf(com.abrah.nightmare.UpscaleNode.NATIVE_SCALE)

/**
 * ⭐⭐⭐ **Why this picture cannot be upscaled, in one sentence — or null.**
 *
 * ⚠⚠ ONE function, because three surfaces ask: the Results row, the Results
 * fullscreen viewer, and a node's output. Each had to know that a clip is not a
 * picture and that [UPSCALE_MAX_EDGE] exists, and three copies of a rule are
 * three copies that stop agreeing (`CLAUDE.md`).
 *
 * ⚠ It REFUSES BY NAME rather than hiding the button (the user's call,
 * 2026-09-17): a control that vanishes on a clip teaches nothing.
 */
fun upscaleRefusal(
    context: android.content.Context,
    width: Int,
    height: Int,
    isClip: Boolean,
    busyWith: String?,
): String? = when {
    busyWith != null -> context.getString(R.string.upscale_already_running, busyWith)
    isClip -> context.getString(R.string.upscale_pictures_only)
    // ⚠ NOT refused when no scale fits [UpscaleNode.MAX_OUT_EDGE] any more: the chooser dims
    // every scale and Use, and still offers UltraFix — the job a picture that large is for.
    else -> null
}

/**
 * ⭐⭐ The upscaler chooser — one dialog, wherever an upscale is started.
 *
 * ⚠ Offers every upscaler, installed or not: the not-installed rows carry
 * their download size, so "I have no upscaler" and "I have the wrong one" are
 * the same screen (§8.1's rule, applied to a dialog).
 */
@Composable
fun UpscalePicker(
    upscalers: List<UpscalerRow>,
    /** The upscaler's id and the scale, 2..4. */
    onPick: (String, Int) -> Unit,
    onInstall: (com.abrah.nightmare.UpscalerSpec) -> Unit,
    onDismiss: () -> Unit,
    /** ⭐ The picture's size, to dim the scales that would pass the cap; null offers all. */
    width: Int? = null,
    height: Int? = null,
    /** ⭐ UltraFix on this picture instead ([com.abrah.nightmare.UltraFix]); null hides the section. */
    onUltraFix: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.upscale_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.upscale_body),
                    style = NoteTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // ⭐⭐ 2x / 3x / 4x — local-dream's choice (the user's ask,
                // 2026-09-26). ⚠ A scale that would pass
                // [com.abrah.nightmare.UpscaleNode.MAX_OUT_EDGE] is DIMMED and
                // says why when tapped, never hidden (`docs/UI.md` §8.18).
                val fits = { s: Int ->
                    width == null || height == null || maxOf(width, height) * s <= com.abrah.nightmare.UpscaleNode.MAX_OUT_EDGE
                }
                // ⚠ Derived, not written back: a state write in composition forced a second pass, and it
                // overwrote the remembered choice for the NEXT, smaller picture.
                val scale = if (fits(upscaleScale)) upscaleScale else (upscaleScale downTo 2).firstOrNull(fits) ?: 2
                val tooBig = stringResource(R.string.upscale_scale_too_big, com.abrah.nightmare.UpscaleNode.MAX_OUT_EDGE)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in 2..com.abrah.nightmare.UpscaleNode.NATIVE_SCALE) {
                        androidx.compose.material3.FilterChip(
                            selected = scale == s,
                            onClick = {
                                if (fits(s)) upscaleScale = s
                                else android.widget.Toast.makeText(context, tooBig, android.widget.Toast.LENGTH_SHORT).show()
                            },
                            label = {
                                Text(
                                    "${s}x",
                                    color = if (fits(s)) androidx.compose.ui.graphics.Color.Unspecified
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                                )
                            },
                        )
                    }
                }
                for (u in upscalers) {
                    val upscalerLabel = u.spec.labelRes?.let { stringResource(it) } ?: u.spec.label
                    val upscalerAbout = u.spec.aboutRes?.let { stringResource(it) } ?: u.spec.about
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(upscalerLabel)
                            Text(upscalerAbout, style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        when {
                            u.installed -> StudioButton(onClick = { onPick(u.spec.id, scale) }, enabled = fits(scale)) { Text(stringResource(R.string.use)) }
                            u.progress != null -> Text(stringResource(R.string.results_mb_progress, u.progress.done shr 20, u.progress.total shr 20), style = MeasureTextStyle)
                            u.build != null -> StudioOutlinedButton(onClick = { onInstall(u.spec) }) {
                                Text(stringResource(R.string.device_mb, u.build.bytes shr 20))
                            }
                            else -> Text(stringResource(R.string.cannot_run_it), style = NoteTextStyle)
                        }
                    }
                }
                if (!fits(2)) {
                    Text(stringResource(R.string.upscale_none_fits), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // ⭐⭐ UltraFix — redraw the detail at THIS size (Local Dream's, `UltraFix.kt`).
                onUltraFix?.let { go ->
                    val big = width == null || height == null || minOf(width, height) >= 512
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text(stringResource(R.string.ultrafix_section), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (big) R.string.ultrafix_about else R.string.ultrafix_needs),
                        style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StudioOutlinedButton(onClick = go, enabled = big) { Text(stringResource(R.string.ultrafix_open)) }
                }
            }
        },
        confirmButton = { StudioButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * ⭐⭐ **The Results filter** — every model any kept flow used, all ticked,
 * over a search box. Asked for 2026-09-22.
 *
 * ⚠⚠ The search searches the RESULTS — prompt, negative and batch label —
 * not the model list beside it (the user's call when asked). A box above a list
 * that filtered the list would be the obvious reading, which is why the
 * placeholder says what it does.
 *
 * ⚠ One `Clear` that resets both, because two filters combine and "why is it
 * still empty" otherwise needs two discoveries.
 */
@Composable
private fun ResultFilterDialog(
    models: List<String>,
    hidden: Set<String>,
    query: String,
    onToggleModel: (String, Boolean) -> Unit,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.results_filter)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text(stringResource(R.string.results_search_prompts)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.results_no_models),
                        style = NoteTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        stringResource(R.string.nav_models),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    for (m in models) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = m !in hidden,
                                onCheckedChange = { on -> onToggleModel(m, on) },
                            )
                            Text(m, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = { StudioButton(onClick = onDismiss) { Text(stringResource(R.string.crop_done)) } },
        dismissButton = { StudioOutlinedButton(onClick = onClear) { Text(stringResource(R.string.picture_clear)) } },
    )
}

/** ⭐ The UltraFix dialog for [r], seeded with the last settings; confirm runs it. */
@Composable
private fun UltraFixFor(r: Result, onUltraFix: ((Result, com.abrah.nightmare.UltraFix.Params) -> Unit)?, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    UltraFixDialog(
        start = remember { com.abrah.nightmare.UltraFix.load(ctx) },
        onConfirm = { p -> onClose(); onUltraFix?.invoke(r, p) },
        onDismiss = onClose,
    )
}

/** ⭐ Results' Edit face: the SAME viewer as the node's ([com.abrah.nightmare.canvas.FaceEditSheet]), on the kept picture. */
@Composable
private fun FaceEditFor(
    r: Result,
    imageFor: (String) -> ImageBitmap?,
    onEditFace: ((Result, Map<String, String>) -> Unit)?,
    onToast: (String) -> Unit,
    onClose: () -> Unit,
) {
    val src = imageFor(r.id)
    val loading = stringResource(R.string.face_edit_loading)
    if (src == null || onEditFace == null) {
        LaunchedEffect(r.id) { onToast(loading); onClose() }
        return
    }
    com.abrah.nightmare.canvas.FaceEditSheet(
        source = src,
        start = emptyMap(),
        onDone = { values -> onClose(); onEditFace(r, values) },
        onCancel = onClose,
    )
}
