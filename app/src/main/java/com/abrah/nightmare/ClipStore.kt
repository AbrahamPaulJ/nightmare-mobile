package com.abrah.nightmare

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * ⭐⭐ The frames a clip loops on a node — a GIF, in the place a picture goes.
 *
 * ⚠⚠ **Not [ImageStore], and it could not be.** That store is bounded at twelve
 * entries because one 1024² render is 2.6 MB and a canvas holds many nodes; a
 * 49-frame clip would evict every other node's picture on its own. This holds
 * whole clips, pre-scaled to thumbnail size, and keeps [LIMIT] of them.
 *
 * ⚠ **Pre-scaled and pre-thinned at the point of production**, never here: the
 * node has the full-size frames in hand for a few milliseconds between
 * rendering and encoding, and that is the only moment they are free. Asking
 * this class to downscale would mean holding 128 MB of originals to build 9 MB
 * of thumbnails.
 *
 * ⚠ In-memory only. A clip's frames do not survive the process, exactly as a
 * node's picture does not — the MP4 on disk is the durable artefact and the
 * loop is a view of it. A restored canvas draws the placeholder until Run.
 */
object ClipStore {

    /**
     * ⚠ Two: the clip being looked at and the one before it, so a re-run can be
     * compared against its predecessor. A third is ~9 MB for a frame nobody is
     * looking at.
     */
    private const val LIMIT = 2

    /** ⚠ Access-ordered, so the least recently *drawn* clip is the one that goes. */
    private val entries = LinkedHashMap<String, List<ImageBitmap>>(4, 0.75f, true)

    /**
     * ⭐ Every Nth frame, so the loop runs at real speed for half the memory.
     *
     * ⚠⚠ Thinning and the playback rate are ONE decision: 49 frames at 24 fps is
     * a 2.04 s clip, so every 2nd frame at 12 fps is the same 2.04 s. Changing
     * either alone makes the thumbnail play in slow motion or double time, and
     * nothing on screen would say why.
     */
    const val EVERY = 2

    /** ⚠ Paired with [EVERY] — see the note there. */
    const val FPS = 12

    /** ⚠ Thumbnail width. A node is ~200 dp at 1x and the sheet ~360 dp. */
    const val WIDTH = 384

    /**
     * Build the loop for [frames] and keep it under [id].
     *
     * ⚠ The caller passes the FULL-SIZE frames and this thins and scales them,
     * because that is the one moment they exist. It returns immediately after;
     * the originals are the caller's to drop.
     */
    fun put(id: String, frames: List<Bitmap>) {
        if (frames.isEmpty()) return
        val w = WIDTH
        val h = (frames[0].height.toLong() * w / frames[0].width).toInt().coerceAtLeast(1)
        val thumbs = frames.filterIndexed { i, _ -> i % EVERY == 0 }.map {
            Bitmap.createScaledBitmap(it, w, h, true).asImageBitmap()
        }
        synchronized(entries) {
            entries[id] = thumbs
            while (entries.size > LIMIT) {
                val it = entries.keys.iterator()
                it.next()
                it.remove()
            }
        }
    }

    fun get(id: String): List<ImageBitmap>? = synchronized(entries) { entries[id] }

    operator fun contains(id: String): Boolean = synchronized(entries) { id in entries }

    /** ⚠ For a readout and for tests; the store is otherwise opaque. */
    val size: Int get() = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }
}
