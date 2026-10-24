package com.abrah.nightmare.ui

import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

/**
 * ⭐⭐ An MP4, playing, looping, **inside the app**.
 *
 * ⚠⚠ Until this existed the only way to watch a clip was to find it in
 * Movies/Nightmare with some other app — which is why `video.output`'s `save`
 * switch defaulted to true and apologised for itself in its own hint. A node
 * graph shows you what a node made; leaving the app to find out is not that.
 * Asked for from the phone, 2026-09-12: *"video should play in fullscreen of
 * output node viewer and results tab"*.
 *
 * ⚠ **Not the [com.abrah.nightmare.ClipStore] loop.** That is 384 px wide,
 * thinned to every second frame, in memory, and gone with the process — right
 * for a thumbnail on a node and wrong for a full screen, where it is a soft
 * 1024-wide clip playing at half its frames. This plays the file, at its own
 * resolution and rate, and works for a result kept three launches ago.
 *
 * ⚠ [VideoView] is the platform's own player and carries no dependency. It
 * measures itself to the clip's aspect inside the constraints it is given, so
 * `fillMaxSize()` letterboxes rather than stretches — the same contract
 * `ContentScale.Fit` gives the [androidx.compose.foundation.Image] it replaces.
 *
 * ⚠⚠ `update` is keyed on the PATH, not called blind: `AndroidView` runs it on
 * every recomposition, and `setVideoPath` restarts playback from frame 0 — so
 * without the guard the clip jumped back to the start every time anything on
 * the screen changed (a tap, a zoom, the seed row appearing).
 */
/**
 * ⭐ The player behind a [ClipPlayer], for a viewer's "save this frame" (a tester's wish, 2026-10-24): where the
 * clip is NOW. ⚠ The action lives in the viewer's own row: a button inside the player sat under the Results
 * viewer's tap-to-close layer.
 */
class ClipHandle {
    internal var view: VideoView? = null

    /** The frame on screen, in ms from the start; 0 before the clip plays. */
    val positionMs: Int get() = view?.currentPosition ?: 0
}

@Composable
fun rememberClipHandle(): ClipHandle = remember { ClipHandle() }

/**
 * ⭐ The frame at [positionMs] of the clip at [path] (a file or a `content://` URI), exactly, saved to the gallery
 * ([com.abrah.nightmare.ImageSaver]) as a full-size PNG. ⚠ Blocking: call it off the main thread.
 */
fun saveClipFrame(ctx: android.content.Context, path: String, positionMs: Int): String {
    val r = android.media.MediaMetadataRetriever()
    try {
        if (path.startsWith("content://")) r.setDataSource(ctx, android.net.Uri.parse(path)) else r.setDataSource(path)
        val frame = r.getFrameAtTime(positionMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
            ?: throw java.io.IOException("no frame at $positionMs ms")
        return com.abrah.nightmare.ImageSaver.saveBitmap(ctx, frame, "frame_${System.currentTimeMillis()}.png")
    } finally {
        r.release()
    }
}

@Composable
fun ClipPlayer(path: String, modifier: Modifier = Modifier, handle: ClipHandle? = null) {
    val isUri = path.startsWith("content://")
    if (!isUri && !File(path).isFile) return
    // ⚠⚠ A clip that will not decode SAYS so, as a picture does ([UnreadablePicture]): the error listener
    // below used to swallow it and leave the black rectangle its own comment promised it would not.
    var failed by remember(path) { mutableStateOf(false) }
    if (failed) {
        UnreadablePicture(modifier, text = com.abrah.nightmare.R.string.clip_unreadable)
        return
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).also { handle?.view = it }.apply {
                // ⚠ Looping is set on the MediaPlayer, which does not exist
                // until the clip is prepared. Setting it in `factory` is a null
                // dereference; setting it here is the documented moment.
                setOnPreparedListener { mp ->
                    mp.isLooping = true
                    start()
                }
                // ⚠ A clip that will not decode must not leave a black
                // rectangle the user can only close by guessing. Returning true
                // says it is handled, which stops the platform's own
                // "Can't play this video" dialog from appearing over ours.
                setOnErrorListener { _, _, _ -> failed = true; true }
            }
        },
        update = { v ->
            if (v.tag != path) {
                v.tag = path
                if (isUri) v.setVideoURI(android.net.Uri.parse(path)) else v.setVideoPath(path)
            }
        },
        // ⚠⚠ Stopped on the way out, or the SurfaceView keeps a decoder and an
        // audio track alive behind a screen that is no longer on display —
        // which on this app means holding it through a 25 s NPU render.
        onRelease = { if (handle?.view === it) handle.view = null; it.stopPlayback() },
        modifier = modifier,
    )
}
