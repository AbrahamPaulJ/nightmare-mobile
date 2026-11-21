package com.abrah.nightmare.segment

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * ⭐ RMBG 3.0's matte per PHOTO, for the mask editor's Auto ([com.abrah.nightmare.MaskOp.Subject], GitHub #136).
 *
 * ⚠ Kept in memory AND on disk: finding it relaunches the DiT engine and renders for about a minute, so a reopened
 * flow, a restarted app and the run itself must all find the same one without that again. Keyed by
 * [Segmenter.photoKey], the key the segmenter and the parser cache by.
 */
object SubjectMatte {

    private val memory = object : LinkedHashMap<Long, Bitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Bitmap>?) = size > 4
    }

    private fun file(context: Context, key: Long) = File(File(context.cacheDir, "rmbg_matte").apply { mkdirs() }, "$key.png")

    /** ⭐ The ALPHA_8 matte for [photo], or null when Auto has not been run on it. [context] null = memory only. */
    fun cached(context: Context?, photo: Bitmap): Bitmap? {
        val key = Segmenter.photoKey(photo)
        synchronized(memory) { memory[key] }?.let { return it }
        val f = context?.let { file(it, key) }?.takeIf { it.isFile } ?: return null
        val grey = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return null
        return alphaOf(grey).also { synchronized(memory) { memory[key] = it } }
    }

    /** ⭐ Keeps [matte] (the engine's grey picture) for [photo]; returns it as ALPHA_8. */
    fun put(context: Context, photo: Bitmap, matte: Bitmap): Bitmap {
        val key = Segmenter.photoKey(photo)
        runCatching { file(context, key).outputStream().use { matte.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        return alphaOf(matte).also { synchronized(memory) { memory[key] = it } }
    }

    /** ⚠ Luma, as [com.abrah.nightmare.Matte.alphaFrom] reads it. */
    private fun alphaOf(grey: Bitmap): Bitmap {
        val w = grey.width
        val h = grey.height
        val px = IntArray(w * h).also { grey.getPixels(it, 0, w, 0, 0, w, h) }
        val black = IntArray(w * h)
        val a = com.abrah.nightmare.Matte.alphaFrom(black, px)
        return SegmentModel.alphaBitmap(ByteArray(w * h) { (a[it] ushr 24).toByte() }, w, h)
    }
}
