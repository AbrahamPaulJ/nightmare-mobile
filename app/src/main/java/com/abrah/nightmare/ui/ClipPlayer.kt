package com.abrah.nightmare.ui

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
 * ⭐⭐ **A [TextureView], not a `VideoView`** (GitHub #66, 2026-10-24: "zoom in on videos in results, just like
 * we can for images"). A `VideoView` is a `SurfaceView`, its own layer that `graphicsLayer` cannot scale, so the
 * viewers turned zoom off for clips. A `TextureView` draws inside the view tree, so the SAME [viewerZoom] /
 * [viewerPicture] the pictures use scales and pans a playing clip. The platform [MediaPlayer] stays: no dependency.
 *
 * ⚠ Letterboxed, not stretched: a `TextureView` fills its bounds, so the view is given the clip's aspect once
 * the player reports the size; until then it draws nothing and the poster behind it shows.
 *
 * ⚠⚠ The source is keyed on the PATH: `AndroidView` runs `update` on every recomposition, and restarting the
 * player there jumped the clip back to frame 0 every time anything on screen changed.
 */
/**
 * ⭐ The player behind a [ClipPlayer], for a viewer's "save this frame" (a tester's wish, 2026-10-24): where the
 * clip is NOW. ⚠ The action lives in the viewer's own row: a button inside the player sat under the Results
 * viewer's tap-to-close layer.
 */
class ClipHandle {
    internal var view: LoopingClipView? = null

    /** The frame on screen, in ms from the start; 0 before the clip plays. */
    val positionMs: Int get() = view?.positionMs ?: 0
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

/**
 * ⭐ A [TextureView] that loops one clip with a [MediaPlayer]. The player is made when the texture exists and
 * released when it goes (scrolled away, viewer closed), so no decoder outlives the screen showing it: on this
 * app that would hold one through a 25 s NPU render.
 */
class LoopingClipView(
    ctx: android.content.Context,
    private val onSize: (Int, Int) -> Unit,
    private val onFailed: () -> Unit,
) : TextureView(ctx), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var source: String? = null

    init {
        surfaceTextureListener = this
    }

    val positionMs: Int get() = runCatching { player?.currentPosition }.getOrNull() ?: 0

    fun play(path: String) {
        if (path == source) return
        source = path
        start()
    }

    private fun start() {
        val st = surfaceTexture ?: return
        val src = source ?: return
        release()
        val s = Surface(st).also { surface = it }
        player = try {
            MediaPlayer().apply {
                if (src.startsWith("content://")) setDataSource(context, android.net.Uri.parse(src)) else setDataSource(src)
                setSurface(s)
                isLooping = true
                setOnPreparedListener { it.start() }
                setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) onSize(w, h) }
                // ⚠ True = handled: no platform "Can't play this video" dialog over ours.
                setOnErrorListener { _, _, _ -> onFailed(); true }
                prepareAsync()
            }
        } catch (e: Exception) {
            onFailed()
            null
        }
    }

    fun release() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        surface?.release()
        surface = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) = start()
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        release()
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
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
    var aspect by remember(path) { mutableStateOf<Float?>(null) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                LoopingClipView(ctx, onSize = { w, h -> aspect = w.toFloat() / h }, onFailed = { failed = true })
                    .also { handle?.view = it }
            },
            update = { v ->
                handle?.view = v
                v.play(path)
            },
            onRelease = { if (handle?.view === it) handle.view = null; it.release() },
            modifier = aspect?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        )
    }
}
