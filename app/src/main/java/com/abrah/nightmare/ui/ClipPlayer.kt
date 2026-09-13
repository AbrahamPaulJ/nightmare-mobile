package com.abrah.nightmare.ui

import android.widget.VideoView
import androidx.compose.runtime.Composable
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
@Composable
fun ClipPlayer(path: String, modifier: Modifier = Modifier) {
    if (!File(path).isFile) return
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
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
                setOnErrorListener { _, _, _ -> true }
            }
        },
        update = { v ->
            if (v.tag != path) {
                v.tag = path
                v.setVideoPath(path)
            }
        },
        // ⚠⚠ Stopped on the way out, or the SurfaceView keeps a decoder and an
        // audio track alive behind a screen that is no longer on display —
        // which on this app means holding it through a 25 s NPU render.
        onRelease = { it.stopPlayback() },
        modifier = modifier,
    )
}
