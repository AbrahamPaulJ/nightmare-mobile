package com.abrah.nightmare.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.abrah.nightmare.R
import com.abrah.nightmare.npu.WanFun
import com.abrah.nightmare.npu.WanVideoNode
import com.abrah.nightmare.ui.NoteTextStyle

/**
 * ⭐⭐ **Motion transfer's driving video in the inspector** ([com.abrah.nightmare.npu.WanFunNode]):
 * its Mode ⓘ, and the picture popup's **Video** tab (the user's calls, 2026-10-15): two handles
 * choosing the part of the video that drives the clip (their gap IS the clip's length), and a crop
 * drawn on that part's first frame, at the clip's shape, cutting every frame the same way.
 * Kept out of `NodeInspector.kt`, which is past the size where a file is split.
 */

/**
 * ⭐ The Mode ⓘ (the user's call, 2026-10-15): which mode to use when. The views ⓘ's layout
 * (`ViewsInfoDialog`): a title, one name + sentence per choice, a note.
 */
@Composable
internal fun MotionModeInfo(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(stringResource(R.string.motion_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((name, body) in listOf(
                    R.string.wan_motion_pose to R.string.motion_info_pose,
                    R.string.wan_motion_as_is to R.string.motion_info_as_is,
                )) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(name), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    stringResource(R.string.motion_info_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** ⭐ What the Video tab knows of the driving video: its length, and the frame its window starts on. */
internal data class DrivingFrame(val durationMs: Long, val frame: ImageBitmap?)

/** ⚠ The longest edge the window's first frame is read at: enough to crop on, light to hold. */
private const val DRIVING_FRAME_EDGE = 1024

/**
 * ⭐ The driving video's length and the frame at [startS], read OFF the main thread
 * ([rememberOffMain]). Null while it reads; a [DrivingFrame] with no frame when the phone cannot
 * read the video. [uri] is the Video node's own `uri` (a content:// grant or a path).
 */
@Composable
internal fun rememberDrivingFrame(nodeId: String, uri: String?, startS: Double): DrivingFrame? {
    val context = LocalContext.current.applicationContext
    return rememberOffMain(nodeId, "driving frame", uri, startS, keep = true) {
        if (uri.isNullOrBlank()) return@rememberOffMain null
        val r = android.media.MediaMetadataRetriever()
        try {
            if (uri.startsWith("content://")) r.setDataSource(context, android.net.Uri.parse(uri))
            else r.setDataSource(uri)
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val us = (startS * 1_000_000).toLong().coerceIn(0L, dur * 1000L)
            val frame = r.getScaledFrameAtTime(
                us, android.media.MediaMetadataRetriever.OPTION_CLOSEST, DRIVING_FRAME_EDGE, DRIVING_FRAME_EDGE,
            )
            DrivingFrame(dur, frame?.asImageBitmap())
        } catch (e: Exception) {
            DrivingFrame(0L, null)
        } finally {
            runCatching { r.release() }
        }
    }
}

/** ⚠ Pure: the box a node's params hold on the frame, else the frame's centre at the clip's shape. */
internal fun drivingRectOf(params: Map<String, String>, frameW: Int, frameH: Int, clipW: Int, clipH: Int): CropRect =
    WanFun.rectOf(params)?.let { CropRect(it[0], it[1], it[2], it[3]) }
        ?: wholePhotoFraming(frameW, frameH, clipW.toFloat() / clipH.coerceAtLeast(1), com.abrah.nightmare.PadRule.NEVER)

/**
 * ⭐⭐ The Video tab's tile: the window's first frame, cut by the box, at the clip's shape. Empty
 * (a +) with no video wired, so it reads as "choose one here", the ControlNet tile's rule.
 */
@Composable
internal fun drivingTile(
    node: com.abrah.nightmare.Node,
    params: Map<String, String>,
    uri: String?,
    clipW: Int,
    clipH: Int,
    panel: @Composable () -> Unit,
): EditorTile {
    val (startS, _) = WanFun.windowOf(params)
    val read = rememberDrivingFrame(node.id, uri, startS)
    val rectKey = WanFun.rectOf(params)?.toList()
    val thumb = rememberOffMain(node.id, "driving tile", read?.frame, rectKey, clipW, clipH, keep = true) {
        val src = read?.frame?.asAndroidBitmap() ?: return@rememberOffMain null
        val r = drivingRectOf(params, src.width, src.height, clipW, clipH).let { b ->
            WanFun.fitRect(floatArrayOf(b.x, b.y, b.w, b.h), src.width, src.height, clipW, clipH).let { CropRect(it[0], it[1], it[2], it[3]) }
        }
        val scale = (384f / maxOf(clipW, clipH, 1)).coerceAtMost(1f)
        com.abrah.nightmare.CropNode.render(
            src, r.x, r.y, r.w, r.h,
            (clipW * scale).toInt().coerceAtLeast(1), (clipH * scale).toInt().coerceAtLeast(1),
            com.abrah.nightmare.CropNode.PAD_BLACK,
        ).first.asImageBitmap()
    }
    return EditorTile(stringResource(R.string.driving_tab), thumb, empty = uri.isNullOrBlank(), panel = panel)
}

/**
 * ⭐⭐ The Video tab's panel: the window's two handles, then the crop on its first frame.
 * ⚠ ONE write per gesture for both, the crop's rule: the handles keep a local draft while the finger
 * is down and write when it lifts; the box writes its four params together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DrivingVideoPanel(
    node: com.abrah.nightmare.Node,
    params: Map<String, String>,
    uri: String?,
    clipW: Int,
    clipH: Int,
    onSetParams: (Map<String, String>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (uri.isNullOrBlank()) {
            Text(stringResource(R.string.driving_none), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val (s0, e0) = WanFun.windowOf(params)
        val read = rememberDrivingFrame(node.id, uri, s0)
        if (read == null) {
            Text(stringResource(R.string.driving_reading), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        if (read.durationMs <= 0L) {
            Text(stringResource(R.string.driving_unreadable), style = NoteTextStyle, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        val durS = read.durationMs / 1000.0
        val stored = WanFun.constrainWindow(s0, e0, s0, -1.0, durS)
        var draft by remember(node.id, stored) { mutableStateOf<Pair<Double, Double>?>(null) }
        val (s, e) = draft ?: stored
        val frames = WanFun.framesForGap(e - s)
        Text(stringResource(R.string.driving_window), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.driving_window_value, s, e, (frames - 1).toDouble() / WanVideoNode.FPS, frames),
            style = MaterialTheme.typography.bodySmall,
        )
        RangeSlider(
            value = s.toFloat()..e.toFloat(),
            onValueChange = { r ->
                val (ps, pe) = draft ?: stored
                draft = WanFun.constrainWindow(r.start.toDouble(), r.endInclusive.toDouble(), ps, pe, durS)
            },
            valueRange = 0f..durS.toFloat(),
            onValueChangeFinished = {
                draft?.let { (ns, ne) ->
                    onSetParams(mapOf(WanFun.START to "%.3f".format(java.util.Locale.ROOT, ns), WanFun.END to "%.3f".format(java.util.Locale.ROOT, ne)))
                }
                draft = null
            },
        )
        Text(stringResource(R.string.driving_window_hint), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        read.frame?.let { frame ->
            CropEditor(
                source = frame,
                rect = drivingRectOf(params, frame.width, frame.height, clipW, clipH),
                onChange = { r ->
                    onSetParams(
                        mapOf(
                            WanFun.VX to r.x.toString(), WanFun.VY to r.y.toString(),
                            WanFun.VW to r.w.toString(), WanFun.VH to r.h.toString(),
                        )
                    )
                },
                interactive = true,
                outW = clipW,
                aspect = clipW.toFloat() / clipH.coerceAtLeast(1),
                // ⭐ Zooms out √2 past "the whole frame fits", black past it (the user's call,
                // 2026-10-16): a person at the frame's edge can be brought into the clip.
                pad = com.abrah.nightmare.CropNode.PAD_BLACK,
                rule = com.abrah.nightmare.PadRule.PAD,
            )
            Text(stringResource(R.string.crop_gestures), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.driving_crop_note), style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ⭐ A chosen video's two ends and its length, for the Video node's sheet ([VideoEnds]). */
internal data class VideoEndFrames(val first: ImageBitmap?, val last: ImageBitmap?, val durationMs: Long)

/** ⚠ The longest edge of [VideoEnds]' previews: small, two of them side by side. */
private const val END_FRAME_EDGE = 256

/**
 * ⭐⭐ The Video node's previews (the user's call, 2026-10-16: "small previews of the 1st and last
 * frame", in place of the file's name): what the clip starts and ends on, and how long it is.
 * Read off the main thread ([rememberOffMain]); nothing until they arrive.
 */
@Composable
internal fun VideoEnds(uri: String, modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier) {
    val context = LocalContext.current.applicationContext
    val ends = rememberOffMain(uri, "video ends", uri, keep = true) {
        val r = android.media.MediaMetadataRetriever()
        try {
            if (uri.startsWith("content://")) r.setDataSource(context, android.net.Uri.parse(uri)) else r.setDataSource(uri)
            val dur = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            fun at(us: Long) = runCatching {
                r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, END_FRAME_EDGE, END_FRAME_EDGE)
            }.getOrNull()?.asImageBitmap()
            VideoEndFrames(at(0L), at((dur - 1).coerceAtLeast(0L) * 1000L), dur)
        } catch (e: Exception) {
            VideoEndFrames(null, null, 0L)
        } finally {
            runCatching { r.release() }
        }
    }
    androidx.compose.foundation.layout.Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.Bottom,
    ) {
        for ((label, frame) in listOf(
            stringResource(R.string.video_in_first) to ends?.first,
            stringResource(R.string.video_in_last) to ends?.last,
        )) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val box = androidx.compose.ui.Modifier
                    .size(72.dp)
                frame?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it, contentDescription = label,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = box.then(androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))),
                    )
                } ?: androidx.compose.foundation.layout.Box(
                    box.then(androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)))
                        .then(androidx.compose.ui.Modifier.background(MaterialTheme.colorScheme.surfaceVariant)),
                )
                Text(label, style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ends?.durationMs?.takeIf { it > 0 }?.let { d ->
            Text(
                stringResource(R.string.video_in_length, d / 1000.0),
                style = NoteTextStyle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = androidx.compose.ui.Modifier.padding(bottom = 18.dp),
            )
        }
    }
}

/**
 * ⭐⭐ Motion transfer's driving window, LOOPING on its graph box (the user's call, 2026-10-17: "it
 * should show the cropped preview on the graph, currently only img, no clip"): what the node reads,
 * in the slot a Swap node's ControlNet hint uses ([CanvasState.controlHints]), with these frames
 * playing over that still. Keyed by node id; Compose state, so a loop that lands redraws the canvas.
 */
internal object DrivingLoops {
    val frames = androidx.compose.runtime.mutableStateMapOf<String, List<ImageBitmap>>()
}

/** ⚠ The loop's frame rate as sampled; each frame is shown twice at the canvas's 12 Hz tick. */
private const val LOOP_FPS = 6

/** ⚠ The loop's longest edge: a node box is ~200 dp wide. */
private const val LOOP_EDGE = 192

/**
 * ⭐ The cropped window as frames, first one first: [LOOP_FPS] a second across the node's window, each
 * cut by its box at the clip's [clipW] x [clipH] shape (the Video tab's tile, [drivingTile], frame by
 * frame). ⚠ Blocking, seconds for a 5 s window: off the main thread. Null when the video cannot be read.
 */
internal fun drivingLoop(
    context: android.content.Context,
    uri: String,
    params: Map<String, String>,
    clipW: Int,
    clipH: Int,
    /** ⭐ The frame as it is, at its own shape (Animate portrait reads the whole frame); [clipW]/[clipH] unused. */
    wholeFrame: Boolean = false,
): List<android.graphics.Bitmap>? {
    val (startS, endS) = WanFun.windowOf(params)
    val r = android.media.MediaMetadataRetriever()
    return try {
        if (uri.startsWith("content://")) r.setDataSource(context, android.net.Uri.parse(uri)) else r.setDataSource(uri)
        val durS = (r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        val end = if (durS > 0) minOf(endS, durS) else endS
        val count = ((end - startS) * LOOP_FPS).toInt().coerceIn(1, WanVideoNode.MAX_SECONDS * LOOP_FPS)
        val scale = LOOP_EDGE.toFloat() / maxOf(clipW, clipH, 1)
        val outW = (clipW * scale).toInt().coerceAtLeast(1)
        val outH = (clipH * scale).toInt().coerceAtLeast(1)
        (0 until count).mapNotNull { i ->
            val us = ((startS + i.toDouble() / LOOP_FPS) * 1_000_000).toLong()
            val src = r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST, 512, 512)
                ?: return@mapNotNull null
            if (wholeFrame) {
                val s = LOOP_EDGE.toFloat() / maxOf(src.width, src.height, 1)
                return@mapNotNull android.graphics.Bitmap.createScaledBitmap(
                    src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true,
                ).also { if (it !== src) src.recycle() }
            }
            val box = drivingRectOf(params, src.width, src.height, clipW, clipH)
            val fit = WanFun.fitRect(floatArrayOf(box.x, box.y, box.w, box.h), src.width, src.height, clipW, clipH)
            com.abrah.nightmare.CropNode.render(src, fit[0], fit[1], fit[2], fit[3], outW, outH, com.abrah.nightmare.CropNode.PAD_BLACK)
                .first.also { if (it !== src) src.recycle() }
        }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    } finally {
        runCatching { r.release() }
    }
}
