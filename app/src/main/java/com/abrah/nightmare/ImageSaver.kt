package com.abrah.nightmare

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writing a finished render where the user's gallery will find it.
 *
 * ⚠ MediaStore, not a raw path. Since Android 10 an app cannot write into
 * shared storage directly, and a file dropped in the app's own directory is
 * invisible to every gallery — which for an image generator means the picture
 * effectively did not get saved.
 *
 * ⚠ No permission is requested and none is needed: an app may always insert its
 * OWN media on API 29+. Asking for `WRITE_EXTERNAL_STORAGE` would be both
 * refused on modern Android and a worse story for an app whose whole claim is
 * that nothing leaves the device.
 */
object ImageSaver {

    /** Pictures/Nightmare — its own folder, so a gallery groups the renders. */
    const val FOLDER = "Nightmare"

    /**
     * ⭐⭐ **Save a BITMAP by streaming it** — the form to prefer, and the one
     * a big picture needs.
     *
     * ⚠⚠⚠ [savePng] takes bytes, so saving a picture the app is holding
     * meant `ImageStore.png()`: a `ByteArrayOutputStream` plus its
     * `toByteArray()` copy, and then the PNG cached in the store on top. At
     * SDXL-upscale size that is a 67 MB bitmap (4096²), ~80 MB of transient
     * array and ~40 MB retained — to write a file. Reported 2026-09-13: *"for
     * sdxl after upscale, save to gallery doesn't work from the upscale node,
     * but it works from the result tab after starring"*.
     *
     * ⚠⚠ **Results works because it never builds an array**:
     * `ResultsStore.keep` compresses straight into the file's stream. This is
     * that, pointed at MediaStore — the sibling, followed rather than
     * re-derived.
     *
     * ⚠ `compress` writes as it goes, so peak heap is the bitmap and a buffer,
     * whatever the picture's size.
     */
    fun saveBitmap(ctx: Context, bitmap: android.graphics.Bitmap, name: String): String =
        insert(ctx, name) { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }

    /** ⭐ A PNG already on disk, copied out without passing through the heap. */
    fun savePngFile(ctx: Context, file: java.io.File, name: String): String =
        insert(ctx, name) { out -> file.inputStream().use { it.copyTo(out) } }

    /**
     * @return the MediaStore uri as a string, for the caller to log.
     * @throws Exception with the platform's own words; the caller reports them.
     */
    fun savePng(ctx: Context, png: ByteArray, name: String): String =
        insert(ctx, name) { out -> out.write(png) }

    /**
     * The MediaStore dance, once: pending row, write, un-pend.
     *
     * ⚠ [write] is handed the stream rather than the bytes, so a caller that
     * can produce its content incrementally never has to materialise it.
     */
    private fun insert(
        ctx: Context,
        name: String,
        write: (java.io.OutputStream) -> Unit,
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safe = name.ifBlank { "nightmare" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$safe-$stamp.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/" + FOLDER,
            )
            // ⚠⚠ IS_PENDING hides the row until the bytes are written. Without
            // it a gallery can index a zero-byte image the instant the row is
            // inserted, and the user sees a broken thumbnail that never heals.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore refused to create a row")

        try {
            resolver.openOutputStream(uri)?.use(write)
                ?: throw IllegalStateException("MediaStore gave no stream for $uri")
        } catch (e: Throwable) {
            // ⚠ The pending row is removed on failure. Leaving it behind means a
            // permanently invisible, permanently empty image the user cannot
            // delete because no gallery will show it.
            // ⚠⚠ **Throwable, not Exception.** The failure this path is most
            // likely to hit on a 4096² picture is an `OutOfMemoryError`, which
            // is an Error — it would have slipped past a narrower catch and
            // left exactly the invisible empty row described above.
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }
}
