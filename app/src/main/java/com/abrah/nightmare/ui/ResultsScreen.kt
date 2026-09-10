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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
    /** ⚠ Thumbnails are decoded lazily by the caller and may not be ready yet. */
    thumbnailFor: (String) -> ImageBitmap?,
    onOpenFlow: (Result) -> Unit,
    onView: (Result) -> Unit,
    onDelete: (Result) -> Unit,
    onDiskBytes: Long,
    modifier: Modifier = Modifier,
) {
    // ⚠ One dialog for the whole list, not one per card — the same reason the
    // models screen hoists its delete confirm.
    var deleting by remember { mutableStateOf<Result?>(null) }
    // ⚠ Its own flag: deleting a SELECTION is a different question from
    // deleting one picture, and its confirm has to name a count.
    var deletingSelection by remember { mutableStateOf(false) }
    var deletingBatch by remember { mutableStateOf<ResultGroup?>(null) }

    Column(modifier.fillMaxSize()) {
        if (results.isEmpty()) {
            // ⚠⚠ Says HOW to fill it. An empty list that only says it is empty
            // leaves the user to discover the star on the fullscreen viewer by
            // accident, and most never will.
            Text(
                "Nothing kept yet.\n\nRun something, tap the picture to open it, " +
                    "and press the star. The graph that made it is kept too, so you can " +
                    "reopen the whole flow later — not just look at the picture.",
                style = LogTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
            return@Column
        }
        Text(
            // ⚠ Says the cost. These live in app-private storage and go with an
            // uninstall, which a user keeping favourites deserves to know before
            // there are two hundred of them.
            "${results.size} kept · ${onDiskBytes shr 20} MB · " +
                "reopening a flow replaces what is on the canvas",
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // ⭐⭐ The selection bar, top right, and ONLY while something is
        // selected. A permanently visible "select all / delete" pair is a
        // destructive control sitting over a gallery with nothing chosen —
        // long press is what asks for it, so long press is what reveals it.
        if (selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${selected.size} selected",
                    style = LogTextStyle,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll) { Text("All") }
                // ⭐ Save to the gallery, from the selection. ⚠ "None" is gone:
                // clearing is what BACK already does, and a row of three words
                // beside a destructive icon is where a mis-tap lives.
                IconButton(onClick = onSaveSelected) {
                    Icon(
                        SaveIcon,
                        contentDescription = "save the selected pictures to the gallery",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { deletingSelection = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "forget the selected pictures",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        LazyColumn(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groups, key = { it.items.first().id }) { g ->
                if (g.isBatch) {
                    BatchCard(
                        group = g,
                        thumbnailFor = thumbnailFor,
                        onOpenFlow = onOpenFlow,
                        onView = onView,
                        onDelete = { deletingBatch = it },
                        // ⚠ A batch is selected as ONE thing: its cover's id
                        // stands for the group, and the view model expands it.
                        isSelected = g.cover.id in selected,
                        onToggleSelect = { onToggleSelect(g.cover) },
                        selecting = selected.isNotEmpty(),
                        // ⚠⚠ ONE call with every id, not N calls of one.
                        // `saveResultsToGallery` toasts once naming the count;
                        // ten separate calls queued ten identical "Saved 1"
                        // toasts, of which Android shows about one — so saving
                        // a batch looked like saving a single picture. Reported
                        // from the phone, 2026-09-10.
                        onSave = { onSaveGroup(g) },
                        onShareFlow = onShareFlow,
                    )
                } else {
                    val r = g.items.first()
                    ResultCard(
                        result = r,
                        thumbnail = thumbnailFor(r.id),
                        onOpenFlow = { onOpenFlow(r) },
                        // ⚠⚠ In a selection, a TAP toggles rather than opens.
                        // Opening a picture from inside a selection is how a
                        // long-press-then-tap loses the whole selection.
                        onView = {
                            if (selected.isEmpty()) onView(r) else onToggleSelect(r)
                        },
                        onLongPress = { onToggleSelect(r) },
                        isSelected = r.id in selected,
                        selecting = selected.isNotEmpty(),
                        onDelete = { deleting = r },
                        onSave = { onSave(r) },
                        onShareFlow = { onShareFlow(r) },
                    )
                }
            }
        }
    }

    deletingBatch?.let { g ->
        AlertDialog(
            onDismissRequest = { deletingBatch = null },
            title = { Text("Forget this batch?") },
            text = {
                Text(
                    "All ${g.size} pictures and the flows that made them go, and " +
                        "this cannot be undone.\n\nCopies saved to the gallery are " +
                        "not affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDeleteGroup(g); deletingBatch = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget ${g.size}") }
            },
            dismissButton = {
                TextButton(onClick = { deletingBatch = null }) { Text("Cancel") }
            },
        )
    }

    if (deletingSelection) {
        AlertDialog(
            onDismissRequest = { deletingSelection = false },
            title = { Text("Forget ${selected.size} pictures?") },
            text = {
                Text(
                    "Each picture and the flow that made it both go, and this " +
                        "cannot be undone.\n\nCopies saved to the gallery are not " +
                        "affected."
                )
            },
            confirmButton = {
                Button(
                    onClick = { deletingSelection = false; onDeleteSelected() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget ${selected.size}") }
            },
            dismissButton = {
                TextButton(onClick = { deletingSelection = false }) { Text("Cancel") }
            },
        )
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Forget this one?") },
            text = {
                Text(
                    "The picture and the flow that made it both go, and this cannot be " +
                        "undone.\n\nA copy you saved to the gallery is not affected.",
                    style = LogTextStyle,
                )
            },
            confirmButton = {
                Button(
                    onClick = { onDelete(r); deleting = null },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
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
    detailsFor: (Result) -> List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onOpenFlow: (Result) -> Unit,
    onDelete: (Result) -> Unit,
    /** ⭐ Write this picture into the phone's gallery. */
    onSave: (Result) -> Unit = {},
    /** ⭐ Hand this picture to another app. */
    onShare: (Result) -> Unit = {},
) {
    val pager = androidx.compose.foundation.pager.rememberPagerState(
        initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)),
        pageCount = { items.size },
    )
    var showInfo by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
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
            imageFor(item.id)?.let { bmp ->
                Image(
                    bitmap = bmp,
                    contentDescription = item.batchLabel.ifBlank { "the kept picture" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        // ⚠⚠⚠ **NOT `detectTransformGestures`.** That consumes
                        // every pointer event it sees, so the pager underneath
                        // never got a drag — swiping to the next picture did
                        // nothing at ALL, zoomed or not. Reported from the
                        // phone, 2026-09-10.
                        //
                        // ⇒ Hand-rolled, and it consumes ONLY when the gesture
                        // is really ours: two fingers down (a pinch), or
                        // already zoomed in (a pan). A one-finger drag at 1x
                        // falls straight through to the pager, which is exactly
                        // the swipe.
                        .pointerInput(page) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    val fingers = event.changes.count { it.pressed }
                                    val ours = fingers > 1 || scale > 1.01f
                                    if (ours) {
                                        val next = (scale * event.calculateZoom())
                                            .coerceIn(1f, 8f)
                                        val pan = event.calculatePan()
                                        scale = next
                                        // ⚠ Snapping home at 1x: a pan that
                                        // leaves the picture off-centre at rest
                                        // is a viewer needing tidying before it
                                        // can be read.
                                        offset = if (next <= 1.01f) {
                                            androidx.compose.ui.geometry.Offset.Zero
                                        } else {
                                            offset + pan
                                        }
                                        // ⚠ Only the changes that actually
                                        // MOVED. Consuming a bare down would
                                        // eat the tap that closes the viewer.
                                        event.changes.forEach {
                                            if (it.positionChanged()) it.consume()
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(page) {
                            detectTapGestures(
                                // ⚠ Double tap in and out, the gesture every
                                // photo viewer already answers to.
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        offset = androidx.compose.ui.geometry.Offset.Zero
                                    } else {
                                        scale = 3f
                                    }
                                },
                                // ⚠⚠ A single tap closes ONLY at 1x — same rule
                                // as the canvas viewer. While zoomed a tap is
                                // the end of a pan that moved less than the
                                // slop, and closing on it would discard the zoom.
                                onTap = { if (scale <= 1.01f) onDismiss() },
                            )
                        },
                )
            }
        }

        // ⚠ Position in the set, only when there IS a set. "1 of 1" is noise.
        // ⚠⚠ BELOW the picture, not above it: at the top it sat in the same
        // band as the action row and read as a label for the buttons.
        if (items.size > 1) {
            Text(
                "${pager.currentPage + 1} / ${items.size}" +
                    current.batchLabel.let { if (it.isBlank()) "" else "   $it" },
                style = LogTextStyle,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
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
                .align(Alignment.TopEnd)
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
            IconButton(onClick = { confirmingDelete = true }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_forget_result),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            // ⭐ Save to the phone's gallery. ⚠ Distinct from "keep": keeping is
            // this app remembering the flow, saving is a PNG anyone else can
            // open — and a picture worth looking at full screen is exactly
            // where someone wants that.
            IconButton(onClick = { onShare(current) }) {
                Icon(
                    ShareIcon,
                    contentDescription = "share this picture",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = { onSave(current) }) {
                Icon(
                    SaveIcon,
                    contentDescription = "save to the gallery",
                    tint = androidx.compose.ui.graphics.Color.White,
                )
            }
            IconButton(onClick = { showInfo = !showInfo }) {
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
            Button(onClick = { onOpenFlow(current) }) {
                Text(stringResource(R.string.open_flow))
            }
        }

        // ⭐ The params, as a sheet over the picture — opened deliberately,
        // closed by tapping anywhere.
        if (showInfo) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp, start = 12.dp, end = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showInfo = false }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                for ((k, v) in detailsFor(current)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            k,
                            style = LogTextStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(width = 92.dp, height = 20.dp),
                        )
                        Text(v, style = LogTextStyle)
                    }
                }
            }
        }

        if (confirmingDelete) {
            AlertDialog(
                onDismissRequest = { confirmingDelete = false },
                title = { Text("Forget this one?") },
                text = {
                    Text(
                        "The picture and the flow that made it both go, and this " +
                            "cannot be undone.\n\nA copy you saved to the gallery is " +
                            "not affected."
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { confirmingDelete = false; onDelete(current) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Forget") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
                },
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
                meta = listOfNotNull(cover.model, "${cover.width}×${cover.height}")
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
                title = "1 picture",
                label = result.label,
                meta = listOfNotNull(
                    result.model,
                    "${result.width}×${result.height}",
                    result.seed?.let { "seed $it" },
                ).joinToString("  "),
                onDelete = onDelete,
                onSave = onSave,
                onShareFlow = onShareFlow,
                onOpenFlow = onOpenFlow,
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                thumbnail?.let {
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(label, style = LogTextStyle, maxLines = 2)
        Text(
            meta,
            style = LogTextStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_forget_result),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                Icon(
                    SaveIcon,
                    contentDescription = "save to the gallery",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
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
            IconButton(onClick = onShareFlow, modifier = Modifier.size(36.dp)) {
                Icon(
                    ShareFlowIcon,
                    contentDescription = "share the flow that made it",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Button(
                onClick = onOpenFlow,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) {
                Text(
                    stringResource(R.string.open_flow),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

