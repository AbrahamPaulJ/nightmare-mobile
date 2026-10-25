package com.abrah.nightmare.canvas

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐ What a node made, on its page: the run in progress on the output node ([RunFrame]), else the picture or looping
 * clip with its action row, and Animate portrait's driving clip.
 *
 * ⚠⚠ Its own function, NOT inline in [NodeInspectorBody] (1.6.207): that body is one method of ~17k dex
 * instructions and 328 registers, and R8 gave one register an object on one path and an int on another. ART on the
 * testers' phones and on an Android 14 emulator rejected the class at startup ("copy-cat1 ... type=Conflict"); the
 * S25's (Android 17) did not, and a dev build has no R8. Keep new page sections out of that body, and run
 * `tools/verify_dex.sh` on a release APK (HANDOFF §3b).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun NodeResultPreview(
    node: com.abrah.nightmare.Node,
    preview: ImageBitmap?,
    cropSource: ImageBitmap?,
    maskSource: ImageBitmap?,
    beforeImage: ImageBitmap?,
    clip: List<ImageBitmap>?,
    hasClip: Boolean,
    upscaledFocus: BringIntoViewRequester,
    runFrame: RunLogState?,
    runFrameAspect: Float,
    faceNode: Boolean,
    drivingVideo: String?,
    onViewFullscreen: () -> Unit,
    onEditFace: () -> Unit,
    onSaveImage: (() -> Unit)?,
    onShareImage: () -> Unit,
    onSendImage: () -> Unit,
    onStarImage: (() -> Unit)?,
    favourite: Boolean,
    keepDisabledReason: String?,
    onDisabledAction: ((String) -> Unit)?,
    onKeepImage: (() -> Unit)?,
    kept: Boolean,
    onClearOutput: (() -> Unit)?,
    onDropEnlargement: (() -> Unit)?,
    onUpscale: (() -> Unit)?,
    upscaleDisabledReason: String?,
    onShowInfo: (() -> Unit)?,
    compareSource: ImageBitmap? = null,
    compareRect: CropRect? = null,
) {
    // ⭐⭐ While a Default view run goes, the output page shows the run where its picture will
    // be — the frame at the render's shape, log inside, no popup ([RunFrame], 2026-10-10).
    if (runFrame != null && node.type == "core.output") {
        RunFrame(
            runFrame, runFrameAspect,
            Modifier.fillMaxWidth().heightIn(max = pictureCap()),
            fullLogOnTap = false,
        )
    } else preview?.takeIf { cropSource == null && maskSource == null }?.let { still ->
        // ⭐ Only labelled when there is a "Received" picture above it to
        // read against — every other node with a preview still shows one
        // plain, unlabelled picture, exactly as before.
        if (beforeImage != null) {
            Text(
                stringResource(R.string.inspector_upscaled),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // ⭐⭐⭐ **The icons, ABOVE the picture they act on** — the user's
        // call, 2026-09-22, and the same order the Received half uses.
        // ⚠ The SAME row the fullscreen viewer draws, in the same order,
        // with the same confirm on the bin (`docs/UI.md` §8.3).
        // ⭐ A Face edit node draws it UNDER its picture and without the bin (the user, 2026-10-21): the
        // picture is the page there, and Run makes it again.
        val actions: @Composable () -> Unit = { if (onSaveImage != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PictureActions(
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    deleteTint = MaterialTheme.colorScheme.error,
                    isClip = hasClip,
                    // ⭐⭐ With a Received picture above, this drops the
                    // ENLARGEMENT and leaves that one as the node's output.
                    // With no pair, it empties the node as it always did.
                    onDelete = if (faceNode) null else if (beforeImage != null) onDropEnlargement else onClearOutput,
                    deleteTitle = if (beforeImage != null) stringResource(R.string.inspector_drop_upscaled_title) else null,
                    deleteBody = if (beforeImage != null) {
                        stringResource(R.string.inspector_drop_upscaled_body)
                    } else null,
                    onKeep = onKeepImage,
                    onDownload = { onSaveImage() },
                    onShare = { onShareImage() },
                    onSendTo = { onSendImage() },
                    onStar = onStarImage,
                    kept = kept,
                    favourite = favourite,
                    keepDisabledReason = keepDisabledReason,
                    onDisabledAction = onDisabledAction,
                    starKeptTint = com.abrah.nightmare.ui.StarKept,
                    starIdleTint = com.abrah.nightmare.ui.StarIdle,
                    // ⚠⚠ Disabled while `auto upscale` is on — the user's
                    // call: the node is already enlarging every render, so
                    // a button offering to enlarge it again is offering
                    // nothing.
                    onUpscale = onUpscale,
                    upscaleDisabledReason = upscaleDisabledReason,
                    onInfo = onShowInfo,
                )
            }
        } }
        if (!faceNode) actions()
        // ⭐⭐ A clip loops here exactly as it does on the node.
        //
        // ⚠ Its OWN ticker rather than one shared with the canvas: the sheet
        // is a separate window over a canvas that may be scrolled away, and
        // a clock hoisted to the screen would keep the hidden one redrawing.
        // ⚠ Keyed on the clip, so opening a different node restarts it at
        // frame 0 instead of wherever the last one happened to be.
        var frame by remember(clip) { mutableIntStateOf(0) }
        LaunchedEffect(clip) {
            if (clip == null) return@LaunchedEffect
            var last = 0L
            while (true) {
                withFrameMillis { now ->
                    if (now - last >= 1000L / com.abrah.nightmare.ClipStore.FPS) {
                        last = now
                        frame++
                    }
                }
            }
        }
        val shown = clip?.let { f -> f[frame % f.size] } ?: still
        // ⭐⭐ Image and Output nodes get the FRAME (the user's call,
        // 2026-09-23: a 1dp outline, 12dp corners, hugging the picture —
        // node view only). ⚠ Every other node keeps the plain picture.
        // ⭐ The output with an input picture behind it: before and after on one divider ([ComparePicture]).
        if (node.type == "core.output" && clip == null && compareSource != null) {
            ComparePicture(
                after = shown,
                before = compareSource,
                beforeRect = compareRect ?: CropRect(0f, 0f, 1f, 1f),
                contentDescription = stringResource(R.string.inspector_cd_node_image),
                box = if (beforeImage != null) {
                    Modifier.height(pictureCap(pair = true)).bringIntoViewRequester(upscaledFocus)
                } else Modifier.heightIn(max = pictureCap()),
                onClick = onViewFullscreen,
            )
        } else if (node.type == "core.image" || node.type == "core.output") {
            FramedPicture(
                bitmap = shown,
                contentDescription = stringResource(R.string.inspector_cd_node_image),
                box = if (beforeImage != null) {
                    Modifier.height(pictureCap(pair = true))
                        .bringIntoViewRequester(upscaledFocus)
                } else Modifier.heightIn(max = pictureCap()),
                onClick = onViewFullscreen,
            )
        } else Image(
            bitmap = shown,
            contentDescription = stringResource(R.string.inspector_cd_node_image),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                // ⚠ Bounded by the WINDOW, not by a fixed 240dp. In
                // landscape the whole window is ~360dp tall, so a flat cap
                // is most of the screen -- and on a `crop` node this picture
                // sits UNDER the framing view, which has already taken half.
                // ⚠ A fixed box when there is a Received picture above to
                // match, a cap when this is the only picture — letterboxing
                // a lone wide render would waste half the sheet.
                .then(
                    if (beforeImage != null) Modifier.height(pictureCap(pair = true))
                    else Modifier.heightIn(max = pictureCap()),
                )
                .clip(RoundedCornerShape(10.dp))
                .clickable { if (faceNode) onEditFace() else onViewFullscreen() },
        )
        if (faceNode) actions()
    }
    // ⭐ Animate portrait's page shows the driving clip PLAYING (the user, 2026-10-21: "similar to sampler of wan
    // fun", the input clip), above the seconds it reads.
    if (node.type == com.abrah.nightmare.portrait.AnimatePortraitNode.name) {
        Text(stringResource(R.string.animate_driver_title), style = MaterialTheme.typography.titleSmall)
        if (drivingVideo == null) {
            Text(stringResource(R.string.animate_driver_none), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            com.abrah.nightmare.ui.ClipPlayer(
                drivingVideo,
                Modifier.fillMaxWidth().height(pictureCap(pair = true)).clip(RoundedCornerShape(10.dp)),
            )
        }
    }
}
