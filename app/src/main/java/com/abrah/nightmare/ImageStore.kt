package com.abrah.nightmare

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The app-side half of "handles, not buffers" (docs/ARCHITECTURE.md §6).
 *
 * ⭐ A plugin node never receives pixels. It receives an id, passes it to a host
 * op, and gets another id back — so a 512² image crosses the JS boundary as ~40
 * bytes of JSON rather than 780 KB of array, and the sandbox costs nothing.
 * Latents and conditionings live in the backend's own table; images live here,
 * because image ops are app-side and never touch the backend (§3).
 *
 * ⚠⚠ Ids are CONTENT ADDRESSES — a SHA of the actual pixels, not a counter.
 * That is what makes the executor's cache work across recomputation: two runs
 * that produce the same image produce the same id, so everything downstream of
 * them stays cached. A counter would make every re-run look like new data and
 * quietly turn the cache off.
 */
class ImageStore(private val limit: Int = 12) {

    private class Entry(val bitmap: Bitmap, var png: ByteArray?)

    /** Access-ordered: the least recently *used* is the one that goes. */
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    val size: Int get() = synchronized(entries) { entries.size }

    /**
     * @param id an id the caller already knows to be a content address for
     *   these pixels — the backend's `rgb_sha` for a decoded image. ⚠ Pass it
     *   when you have it: hashing 1 MB of pixels costs milliseconds, and the
     *   backend already paid for that hash.
     */
    fun put(bitmap: Bitmap, png: ByteArray? = null, id: String? = null): String {
        val key = id ?: ("img_" + hashPixels(bitmap))
        synchronized(entries) {
            entries[key] = Entry(bitmap, png)
            // ⚠ Bounded, and it has to be: a 512² ARGB bitmap is 1 MB of heap
            // and a canvas will hold many more nodes than this. An unbounded
            // store on a device that runs for hours is a leak nobody notices
            // until the app is killed mid-render.
            while (entries.size > limit) {
                val it = entries.keys.iterator()
                it.next()
                it.remove()
            }
        }
        return key
    }

    fun get(id: String): Bitmap? = synchronized(entries) { entries[id]?.bitmap }

    operator fun contains(id: String): Boolean = synchronized(entries) { entries.containsKey(id) }

    /**
     * The PNG bytes, encoded on first ask and then remembered.
     *
     * ⚠ Lazy because most images never need to be bytes: an intermediate in a
     * graph is decoded, resized and consumed without anything ever asking for a
     * file. Encoding every one eagerly would add ~20 ms per node for nothing.
     */
    fun png(id: String): ByteArray? = synchronized(entries) {
        val e = entries[id] ?: return null
        e.png?.let { return it }
        val out = ByteArrayOutputStream(64 * 1024)
        e.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray().also { e.png = it }
    }

    fun clear() = synchronized(entries) { entries.clear() }

    /**
     * PNG (or JPEG, or whatever BitmapFactory knows) bytes to a bitmap.
     *
     * ⚠ Here rather than at each call site so that "what config do decoded
     * images have" has one answer. [hashPixels] depends on it: a HARDWARE
     * bitmap cannot be hashed at all, so the day someone adds
     * `inPreferredConfig` for speed, this is the place the consequence is
     * written down.
     */
    /**
     * @param maxEdge when > 0, the longest edge the result may have. ⚠⚠ A
     *   MEMORY limit, not a quality one, and it is applied with `inSampleSize`
     *   so the full-size bitmap is never allocated in the first place: a 12 MP
     *   photo is ~48 MB as ARGB_8888, this store holds a dozen images, and
     *   [hashPixels] copies the whole buffer to content-address it. ⚠ Powers of
     *   two only, which is all `inSampleSize` honours, so the result is at most
     *   [maxEdge] and never less than half of it.
     */
    fun decode(bytes: ByteArray, maxEdge: Int = 0): Bitmap? {
        if (maxEdge <= 0) {
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        val probe = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        var sample = 1
        // ⚠ Compares the LONG edge, so a panorama is sampled by the dimension
        // that would actually blow the budget.
        while (maxOf(probe.outWidth, probe.outHeight) / sample > maxEdge) sample *= 2
        return android.graphics.BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private companion object {
        /**
         * ⚠ Hashes the PIXELS, not the PNG. Two encoders can produce different
         * bytes for the same image (and Android's own encoder is free to change
         * between releases), which would make the id depend on the compressor
         * rather than on the picture.
         *
         * ⚠⚠ The SHAPE goes in first, and it has to. `copyPixelsToBuffer`
         * writes rows end to end with no geometry, so a 2x8 and an 8x2 solid
         * bitmap produce byte-identical buffers -- and a mask IS a solid
         * bitmap. Without the dimensions the two collide onto one id, the
         * second `put` evicts the first, and a node downstream is handed a
         * correctly-cached image of the wrong shape with nothing raising an
         * error. Found by ImageStoreTest.shapeIsPartOfTheAddress.
         */
        fun hashPixels(bitmap: Bitmap): String {
            // ⚠ A HARDWARE-config bitmap has no readable pixels at all and
            // throws here. Nothing in this app makes one today, so this is a
            // guard against a future decode option rather than a live case --
            // and it fails with a sentence rather than an IllegalStateException
            // from deep inside Bitmap.
            require(bitmap.config != Bitmap.Config.HARDWARE) {
                "cannot hash a HARDWARE bitmap: its pixels are not readable"
            }
            val buf = ByteBuffer.allocate(bitmap.byteCount)
            bitmap.copyPixelsToBuffer(buf)
            val md = MessageDigest.getInstance("SHA-256")
            // ⚠ The config too: the same picture at RGB_565 and ARGB_8888 has
            // different pixels *and* a different byteCount, so it is already a
            // different address -- but naming it here says that is deliberate.
            md.update("${bitmap.width}x${bitmap.height}:${bitmap.config}\u001F".toByteArray())
            val d = md.digest(buf.array())
            return d.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
