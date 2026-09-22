package com.abrah.nightmare.ui

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
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
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
    onSelectAll: () -> Unit = {},
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
    /** ⭐ Upscale with an installed upscaler; the enlarged picture becomes a new item. */
    onUpscale: (Result, String) -> Unit = { _, _ -> },
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

    Column(modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            // ⚠⚠ Says HOW to fill it.
            Text(
                "Nothing kept yet.\n\nRun something and press the star or the disk. " +
                    "The graph that made it is kept too, so you can reopen the whole " +
                    "flow later — not just look at the picture.",
                style = LogTextStyle,
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
                        "Upscaling with $what… it appears here as a new result.",
                        style = LogTextStyle,
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
                        if (fav) "Favourites" else "All",
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
                    contentDescription = "filter these results",
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
                        contentDescription = "clear the filter",
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
                "Nothing starred yet — press the star on a picture to find it here.",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }

        // ⭐⭐ The big frame SWIPES between results, and the grid follows it
        // (the user's call, 2026-09-17). ⚠ Two effects, one per direction, each
        // a no-op when the other side already agrees — so they cannot chase.
        val shownIndex = items.indexOfFirst { it.id == shownId }.coerceAtLeast(0)
        val pager = androidx.compose.foundation.pager.rememberPagerState(
            initialPage = shownIndex, pageCount = { items.size },
        )
        LaunchedEffect(pager.settledPage, items.size) {
            items.getOrNull(pager.settledPage)?.let { if (it.id != shownId) shownId = it.id }
        }
        LaunchedEffect(shownIndex) {
            if (pager.currentPage != shownIndex) pager.animateScrollToPage(shownIndex)
        }
        val shown = items.getOrNull(pager.currentPage) ?: items.first()

        androidx.compose.foundation.pager.HorizontalPager(
            state = pager,
            key = { items[it].id },
            modifier = Modifier.fillMaxWidth().weight(1.1f).padding(top = 10.dp),
        ) { i ->
            val r = items[i]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(10.dp))
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
                    "${selected.size} selected",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                // ⚠ 40dp targets (DreamUI's own minimum) so six controls and the
                // count fit one row on a 360dp phone without pushing any off.
                val small = Modifier.size(40.dp)
                TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("All") }
                // ⭐ Star the selection — no confirm, a star is one tap to undo.
                val allStarred = all.filter { it.id in selected }.let { sel -> sel.isNotEmpty() && sel.all { it.favourite } }
                IconButton(onClick = onStarSelected, modifier = small) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (allStarred) "unstar the selected" else "star the selected",
                        tint = if (allStarred) StarKept else StarIdle,
                    )
                }
                IconButton(onClick = { deletingSelection = true }, modifier = small) {
                    Icon(Icons.Filled.Delete, contentDescription = "delete the selected", tint = MaterialTheme.colorScheme.error)
                }
                IconButton(onClick = { confirmingDownload = true }, modifier = small) {
                    Icon(DownloadIcon, contentDescription = "save the selected to the gallery", tint = onSurface)
                }
                IconButton(onClick = { sharing = selected.toList() }, modifier = small) {
                    Icon(ShareIcon, contentDescription = "share the selected", tint = onSurface)
                }
                IconButton(onClick = onClearSelection, modifier = small) {
                    Icon(Icons.Filled.Close, contentDescription = "stop selecting", tint = onSurface)
                }
            } else {
                // ⚠⚠ 40dp targets, as the selection row: with Send to added, seven
                // 48dp icons pushed Open off a 360dp phone (golden `history-360`).
                val small = Modifier.size(40.dp)
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
                        contentDescription = if (shown.favourite) "remove from favourites" else "add to favourites",
                        tint = if (shown.favourite) StarKept else StarIdle,
                    )
                }
                IconButton(onClick = { onSave(shown) }, modifier = small) {
                    Icon(DownloadIcon, contentDescription = "save to the gallery", tint = onSurface)
                }
                IconButton(onClick = { sharing = listOf(shown.id) }, modifier = small) {
                    Icon(ShareIcon, contentDescription = "share", tint = onSurface)
                }
                // ⭐ Send into a flow, after share. ⚠ Shown on a clip too and
                // refusing by name, as Upscale does beside it.
                IconButton(onClick = {
                    if (shown.videoPath != null) onToast("Only a picture can be sent into a flow")
                    else onSendTo(shown)
                }, modifier = small) {
                    Icon(SendToIcon, contentDescription = "send this picture to a flow", tint = onSurface)
                }
                // ⚠ Always SHOWN, refusing by name (the user's call, 2026-09-17):
                // a button that vanishes on a clip teaches nothing.
                IconButton(onClick = {
                    val no = upscaleRefusal(shown.width, shown.height, shown.videoPath != null, upscaling)
                    if (no != null) onToast(no) else upscalingPick = shown
                }, modifier = small) {
                    Icon(
                        UpscaleIcon, contentDescription = "upscale this picture",
                        tint = onSurface.copy(alpha = if (upscaling == null) 1f else 0.38f),
                    )
                }
                IconButton(onClick = { info = shown }, modifier = small) {
                    Icon(Icons.Filled.Info, contentDescription = "what made this picture", tint = onSurface)
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
                "${results.size} kept · ${onDiskBytes shr 20} MB",
            ).joinToString(" · "),
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // ⭐ The grid — small previews. Tap shows it above; long-press selects,
        // and while selecting a tap toggles.
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(88.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp),
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
                    onClick = { if (selecting) onToggleSelect(r) else shownId = r.id },
                    onLongClick = { onToggleSelect(r) },
                )
            }
        }
    }

    info?.let { r -> ResultInfoDialog(detailsFor(r)) { info = null } }

    upscalingPick?.let { r ->
        UpscalePicker(
            upscalers = upscalers,
            onPick = { id -> upscalingPick = null; onUpscale(r, id) },
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
            title = { Text(if (n > 1) "Share $n" else "Share") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { sharing = null; onShareResults(ids, false) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (n > 1) "Share $n images" else "Share image") }
                    OutlinedButton(
                        onClick = { sharing = null; onShareResults(ids, true) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (n > 1) "Share $n workflows" else "Share workflow") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { sharing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (confirmingDownload) {
        AlertDialog(
            onDismissRequest = { confirmingDownload = false },
            title = { Text("Save ${selected.size} to the gallery?") },
            text = {
                Text(
                    "Each is written to Pictures/${com.abrah.nightmare.ImageSaver.FOLDER} as a full-size " +
                        "file — a large selection can take a while and a lot of space."
                )
            },
            confirmButton = {
                Button(onClick = { confirmingDownload = false; onSaveSelected() }) { Text("Save ${selected.size}") }
            },
            dismissButton = { TextButton(onClick = { confirmingDownload = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    // ⚠ Every delete here asks through [ConfirmDelete], and says DELETE: it was
    // "Forget" on this screen alone, a fourth verb for the one action (the
    // user's call, 2026-09-15 — one word). `docs/UI.md` §8.2.
    deletingBatch?.let { g ->
        ConfirmDelete(
            title = "Delete this batch?",
            body = "All ${g.size} pictures and the flows that made them go, and " +
                "this cannot be undone.\n\nCopies saved to the gallery are not affected.",
            confirmLabel = "Delete ${g.size}",
            onConfirm = { onDeleteGroup(g) },
            onDismiss = { deletingBatch = null },
        )
    }

    if (deletingSelection) {
        ConfirmDelete(
            title = "Delete ${selected.size} pictures?",
            body = "Each picture and the flow that made it both go, and this " +
                "cannot be undone.\n\nCopies saved to the gallery are not affected.",
            confirmLabel = "Delete ${selected.size}",
            onConfirm = onDeleteSelected,
            onDismiss = { deletingSelection = false },
        )
    }

    deleting?.let { r ->
        ConfirmDelete(
            title = "Delete this picture?",
            body = RESULT_DELETE_BODY,
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
    onUpscale: ((Result, String) -> Unit)? = null,
    onInstallUpscaler: (com.abrah.nightmare.UpscalerSpec) -> Unit = {},
    onToast: (String) -> Unit = {},
) {
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
    val viewerIcon = Modifier.size(40.dp)
    // ⚠⚠ STATE objects, not values: they are handed to [viewerZoom], whose
    // gesture lambda outlives the composition that built it.
    val scaleState = remember { mutableStateOf(1f) }
    val offsetState = remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var scale by scaleState
    var offset by offsetState
    var box by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
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
            // ⭐⭐ A kept CLIP plays, exactly as it does in the canvas viewer.
            //
            // ⚠⚠ No zoom and no pinch on this page: the player is a
            // `SurfaceView` and `graphicsLayer` moves its frame without moving
            // the surface. The pager still swipes, because a clip page never
            // zooms and the pager is only disabled while zoomed.
            //
            // ⚠ `videoPath` is the COPY in the results directory
            // ([Result.videoPath]), so a clip starred three launches ago still
            // plays -- the one the graph produced is in `cacheDir`.
            val clip = item.videoPath
            if (clip != null) {
                com.abrah.nightmare.ui.ClipPlayer(
                    path = clip,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                )
                // ⚠ Tap to close still works over the player: the pointer input
                // sits on a transparent box above it rather than on the surface.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(page) {
                            detectTapGestures(onTap = { onDismiss() })
                        }
                )
                return@HorizontalPager
            }
            (imageFor(item.id) ?: thumbnailFor(item.id))?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = item.batchLabel.ifBlank { "the kept picture" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // ⚠⚠ Clear of the actions above and the seed below,
                        // and CLIPPED to that band so a zoom cannot draw over
                        // either — the same function the canvas viewer calls.
                        .viewerPicture(scale, offset)
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
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    style = LogTextStyle,
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
                            "remove from favourites"
                        } else {
                            "add to favourites"
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
                    contentDescription = "save to the gallery",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // ⚠ The seed is drawn BELOW the picture now — see above. ⚠ Still no
            // `onLock`: locking writes onto the OPEN canvas, and a result in a
            // list is not necessarily from that graph.
            IconButton(onClick = { onShare(current) }, modifier = viewerIcon) {
                Icon(
                    ShareIcon,
                    contentDescription = "share this picture",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            // ⭐ Send into a flow, after share — [PictureActions]' order. ⚠ Not
            // on a clip: no flow takes a video as its input.
            onSendTo?.takeIf { current.videoPath == null }?.let { send ->
                IconButton(onClick = { send(current) }, modifier = viewerIcon) {
                    Icon(
                        SendToIcon,
                        contentDescription = "send this picture to a flow",
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
                        current.width, current.height, current.videoPath != null, upscaling,
                    )
                    if (no != null) onToast(no) else upscalingPick = current
                }, modifier = viewerIcon) {
                    Icon(
                        UpscaleIcon,
                        contentDescription = "upscale this picture",
                        tint = androidx.compose.ui.graphics.Color.White
                            .copy(alpha = if (upscaling == null) 1f else 0.38f),
                    )
                }
            }
            IconButton(onClick = { showInfo = !showInfo }, modifier = viewerIcon) {
                Icon(
                    Icons.Filled.Info,
                    contentDescription = "what made this picture",
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
        if (showInfo) ResultInfoDialog(detailsFor(current)) { showInfo = false }

        if (confirmingDelete) {
            // ⚠ The SAME body as the card's confirm — one constant, not two copies.
            ConfirmDelete(
                title = "Delete this picture?",
                body = RESULT_DELETE_BODY,
                onConfirm = { onDelete(current) },
                onDismiss = { confirmingDelete = false },
            )
        }
        // ⚠ The SAME chooser the Results row opens — see [UpscalePicker].
        upscalingPick?.let { r ->
            UpscalePicker(
                upscalers = upscalers,
                onPick = { id -> upscalingPick = null; onUpscale?.invoke(r, id) },
                onInstall = onInstallUpscaler,
                onDismiss = { upscalingPick = null },
            )
        }
    }
}

/**
 * ⭐⭐ A sweep, as ONE card — **its items always visible, and selected or
 * deleted as a whole.**
 *
 * ⚠⚠ It shipped collapsed behind a "show all" button with a cover thumbnail.
 * Both were wrong: the cover is a picture that stands for nine others and says
 * nothing about them, and a strip you have to reveal is a strip nobody looks
 * at. Reported from the phone, 2026-09-10 — show them by default, no hide.
 *
 * ⚠⚠ And a batch is ONE thing to the selection: long-press anywhere selects
 * the whole sweep, and deleting removes all of its items. Selecting individual
 * items inside a batch was two selection models in one list, and the user asked
 * for the batch to behave like a single card.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BatchCard(
    group: ResultGroup,
    thumbnailFor: (String) -> ImageBitmap?,
    onOpenFlow: (Result) -> Unit,
    onView: (Result) -> Unit,
    onDelete: (ResultGroup) -> Unit,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    selecting: Boolean = false,
    onSave: () -> Unit = {},
    onShareFlow: (Result) -> Unit = {},
) {
    val cover = group.cover
    Card(
        Modifier
            .fillMaxWidth()
            // ⚠⚠ Long press ANYWHERE on the card, not only on a thumbnail. The
            // card is the row; making the 84dp picture the only target meant
            // most of what looks selectable was not.
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() },
                onLongClick = onToggleSelect,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultCardHeader(
                title = "${group.size} pictures",
                label = cover.label,
                meta = listOfNotNull(cover.model, "${cover.width}x${cover.height}")
                    .joinToString("  "),
                onDelete = { onDelete(group) },
                onSave = onSave,
                onShareFlow = { onShareFlow(cover) },
                onOpenFlow = { onOpenFlow(cover) },
            )
            // ⭐ The strip, always. DreamUI's shape: bordered thumbnails in a
            // scrolling row, each labelled with the value that made it.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (item in group.items) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        thumbnailFor(item.id)?.let {
                            Image(
                                bitmap = it,
                                contentDescription = item.batchLabel,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant,
                                        RoundedCornerShape(6.dp),
                                    )
                                    // ⚠ A tap opens the viewer at THIS item, so
                                    // the swipe starts where the eye already is.
                                    // ⚠⚠ …unless a selection is on, when the
                                    // whole card is the target.
                                    .combinedClickable(
                                        onClick = {
                                            if (selecting) onToggleSelect() else onView(item)
                                        },
                                        onLongClick = onToggleSelect,
                                    ),
                            )
                        }
                        // ⭐ The value that made this one different. A strip of
                        // near-identical pictures says nothing without it.
                        Text(
                            item.batchLabel,
                            style = LogTextStyle,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ⭐⭐ A single kept picture — **laid out exactly like [BatchCard].**
 *
 * ⚠⚠ It used to be a different shape: an 84dp thumbnail on the LEFT with the
 * text beside it, where a batch put its text on top and its pictures in a strip
 * below. Two arrangements in one scrolling list read as two kinds of thing.
 * Asked for from the phone, 2026-09-10 — use the batch arrangement for both.
 *
 * ⇒ Same order everywhere now: title, label, meta; then the action row
 * (destructive, save, share, then the primary); then the picture(s). A batch
 * shows several; this shows one.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ResultCard(
    result: Result,
    thumbnail: ImageBitmap?,
    onOpenFlow: () -> Unit,
    onView: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: () -> Unit = {},
    isSelected: Boolean = false,
    selecting: Boolean = false,
    onSave: () -> Unit = {},
    onShareFlow: () -> Unit = {},
    /** ⭐ Star this result — the flag the Favourites chip filters on. */
    onToggleFavourite: (Result) -> Unit = {},
) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) onLongPress() },
                onLongClick = onLongPress,
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultCardHeader(
                // ⚠ A kept clip and a kept picture are the same card, and
                // nothing on it said which -- the thumbnail is a poster frame,
                // which looks exactly like a render. The card says so, and the
                // badge on the thumbnail below says it again where the tap is.
                title = if (result.videoPath != null) "1 clip" else "1 picture",
                label = result.label,
                // ⚠ The seed has left this line — it is its own control below,
                // because it is the one piece of metadata people want to TAKE
                // rather than read.
                meta = listOfNotNull(
                    result.model,
                    "${result.width}x${result.height}",
                ).joinToString("  "),
                onDelete = onDelete,
                onSave = onSave,
                onShareFlow = onShareFlow,
                onOpenFlow = onOpenFlow,
                // ⭐ Copyable, and on the action row rather than its own.
                // [SeedRow] is the SAME control the inspector and the viewer
                // use — not a second copy button. No `onLock` here: locking
                // writes onto the OPEN canvas, and a result in a list is not
                // necessarily from the graph that is open.
                seed = result.seed?.toString(),
                onToggleFavourite = { onToggleFavourite(result) },
                favourite = result.favourite,
            )

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                thumbnail?.let {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = it,
                            contentDescription = stringResource(R.string.cd_open_fullscreen),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant,
                                    RoundedCornerShape(6.dp),
                                )
                                .combinedClickable(
                                    onClick = { if (selecting) onLongPress() else onView() },
                                    onLongClick = onLongPress,
                                ),
                        )
                        // ⚠ Drawn OVER the poster and not clickable: the tap
                        // target is the picture underneath, so a badge that ate
                        // the touch would make the one thumbnail that plays the
                        // one thumbnail that cannot be opened.
                        if (result.videoPath != null) {
                            Text(
                                "▶",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 20.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * ⭐⭐ The header both cards share — title, label, meta, and ONE action row.
 *
 * ⚠⚠ Extracted rather than copied, because `docs/UI.md` §5 already records what
 * happens when two surfaces showing one picture drift: an action ends up on one
 * and not the other, and it reads as a bug. One function is one answer to "what
 * can I do with this".
 *
 * ⚠ Order, fixed: **delete, save, share picture, share flow, Open flow.**
 * Destructive first because the thumb lands on the right, primary last for the
 * same reason. §7.1 — every label Capitalised.
 */
@Composable
private fun ResultCardHeader(
    title: String,
    label: String,
    meta: String,
    onDelete: () -> Unit,
    onSave: () -> Unit,
    onShareFlow: () -> Unit,
    onOpenFlow: () -> Unit,
    /**
     * ⭐ The seed, shown INLINE with the actions rather than on a row of its
     * own. Null on a batch — a group has one seed per picture, so a single
     * value there would name one of eight.
     */
    seed: String? = null,
    /** ⭐ Star this one, or null on a surface that cannot. */
    onToggleFavourite: (() -> Unit)? = null,
    favourite: Boolean = false,
) {
    // ⚠⚠ **Three rows, not five.** The card was title / label / meta /
    // actions / seed stacked, which on a phone meant a handful of results filled
    // the screen with mostly chrome. Reported from the phone 2026-09-11 ("way
    // too much space per card").
    // ⇒ The count and the metadata share the first line, and the seed shares
    // the action row.
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                "  $meta",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(label, style = LogTextStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠⚠ The seed sits at the START and a weighted spacer pushes the
            // actions right, so the buttons land in the SAME place whether or
            // not there is a seed — a batch card has none, and right-aligning
            // the group would otherwise make the two card kinds disagree.
            // ⚠⚠ COMPACT here, and it has to be: the card row is five controls
            // on a phone now, and the full seed pill pushed the Open button off
            // the edge the moment the star joined it. Reported 2026-09-15.
            // ⇒ The number and its copy button, no container and no "seed"
            // caption — the row it sits in already says what it is.
            if (seed != null) {
                com.abrah.nightmare.canvas.SeedRow(seed = seed, compact = true)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_result),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
            // ⭐⭐ The STAR — the flag the Favourites chip filters on. Here as
            // well as on the canvas, because a picture is usually decided to be
            // a favourite when it is being looked at in the list, not at the
            // moment it was made.
            onToggleFavourite?.let { toggle ->
                IconButton(onClick = toggle, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription =
                            if (favourite) "remove from favourites" else "add to favourites",
                        tint = if (favourite) com.abrah.nightmare.ui.StarKept
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            // ⚠ The DOWNLOAD glyph, not the floppy: the disk means "keep in
            // Results" everywhere since 2026-09-15, and this button has always
            // written to the gallery. `PictureActions` has the table.
            IconButton(onClick = onSave, modifier = Modifier.size(32.dp)) {
                Icon(
                    com.abrah.nightmare.ui.DownloadIcon,
                    contentDescription = "save to the gallery",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // ⚠⚠ **No picture-share on the CARD.** On a batch it shared the
            // cover — one of ten — which is a control that quietly does a
            // tenth of what it looks like. Sharing all hundred is not a thing
            // anyone wants either. ⇒ The picture is shared from the ZOOM
            // VIEWER, where exactly one picture is on screen and there is no
            // ambiguity about which. The FLOW share stays here, because a batch
            // has one flow. The user's call, 2026-09-10.
            // ⚠⚠ A SECOND share, for the flow rather than the picture. They are
            // genuinely different things to send — one is a JPEG for a friend,
            // the other is a graph they can run — and collapsing them into one
            // control would make the app choose which the user meant.
            // ⚠⚠ **The glyphs swapped, 2026-09-11 at the user's request.**
            // Sharing a flow is still sharing, so it takes the ordinary share
            // arrow everyone already reads as "send this somewhere"; the
            // node-graph glyph moved to OPEN, where it names the thing being
            // opened rather than the act of sending it.
            IconButton(onClick = onShareFlow, modifier = Modifier.size(32.dp)) {
                Icon(
                    ShareIcon,
                    contentDescription = "share the flow that made it",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            // ⚠ The glyph REPLACES the label rather than joining it: the row
            // is four controls on a phone, and a text button among three icons
            // was the widest thing on the card. It stays a filled Button so it
            // still reads as the card's primary action.
            // ⚠ Tighter padding: the row gained the star, and the primary
            // button is the one that must not be the thing pushed off the edge.
            Button(
                onClick = onOpenFlow,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Icon(
                    ShareFlowIcon,
                    contentDescription = stringResource(R.string.open_flow),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}


/** ⚠ One body for deleting one result, wherever it is asked from. */
private const val RESULT_DELETE_BODY =
    "The picture and the flow that made it both go, and this cannot be undone.\n\n" +
        "A copy you saved to the gallery is not affected."

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
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
                        width = if (picked) 3.dp else 2.dp,
                        color = if (picked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
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
                    contentDescription = "a clip",
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
private fun UnreadablePicture(
    modifier: Modifier = Modifier.fillMaxSize(),
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            "can't read this picture",
            style = LogTextStyle,
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
@Composable
fun ResultInfoDialog(details: List<Pair<String, String>>, onClose: () -> Unit) {
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val all = details.joinToString("\n") { (k, v) -> "$k: $v" }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Info") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for ((k, v) in details) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        dismissButton = {
            TextButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(all)) }) {
                Icon(CopyIcon, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Copy all")
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * ⚠ The longest edge Results will upscale. The upscalers are 4x, so this caps
 * the output at 6144 px — ~150 MB of pixels held while the PNG is written,
 * which is where a phone starts refusing the allocation.
 */
const val UPSCALE_MAX_EDGE = 1536

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
fun upscaleRefusal(width: Int, height: Int, isClip: Boolean, busyWith: String?): String? = when {
    busyWith != null -> "Already upscaling with $busyWith"
    isClip -> "Upscale works on pictures, not clips"
    maxOf(width, height) > UPSCALE_MAX_EDGE ->
        "Too big to upscale: ${width}x$height. Pictures up to " +
            "$UPSCALE_MAX_EDGE px on the long edge only (4x would pass ${UPSCALE_MAX_EDGE * 4} px)."
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
    onPick: (String) -> Unit,
    onInstall: (com.abrah.nightmare.UpscalerSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upscale") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The enlarged picture is kept as a new item; this one stays.",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (u in upscalers) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(u.spec.label)
                            Text(u.spec.about, style = LogTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        when {
                            u.installed -> Button(onClick = { onPick(u.spec.id) }) { Text("Use") }
                            u.progress != null -> Text("${u.progress.done shr 20} / ${u.progress.total shr 20} MB", style = LogTextStyle)
                            u.build != null -> OutlinedButton(onClick = { onInstall(u.spec) }) {
                                Text("${u.build.bytes shr 20} MB")
                            }
                            else -> Text(stringResource(R.string.cannot_run_it), style = LogTextStyle)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
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
        title = { Text("Filter") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    label = { Text("Search prompts") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (models.isEmpty()) {
                    Text(
                        "Nothing kept here names a model yet.",
                        style = LogTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text(
                        "Models",
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onClear) { Text("Clear") } },
    )
}
