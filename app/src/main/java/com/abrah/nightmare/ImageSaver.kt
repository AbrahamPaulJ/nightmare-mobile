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
     * @return the MediaStore uri as a string, for the caller to log.
     * @throws Exception with the platform's own words; the caller reports them.
     */
    fun savePng(ctx: Context, png: ByteArray, name: String): String {
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
            resolver.openOutputStream(uri)?.use { it.write(png) }
                ?: throw IllegalStateException("MediaStore gave no stream for $uri")
        } catch (e: Exception) {
            // ⚠ The pending row is removed on failure. Leaving it behind means a
            // permanently invisible, permanently empty image the user cannot
            // delete because no gallery will show it.
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }
}
