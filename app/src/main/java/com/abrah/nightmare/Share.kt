package com.abrah.nightmare

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * ⭐⭐ **Sending a picture or a flow to another app.**
 *
 * ⚠⚠ Through a [FileProvider], not a `file://` URI. Android has refused raw
 * file URIs across app boundaries since API 24 — `FileUriExposedException`,
 * thrown at the moment of sharing rather than at build time — so the receiving
 * app gets a `content://` URI plus a one-shot read grant instead.
 *
 * ⚠ Everything shared is written into `cacheDir/share/` first, even when the
 * original already exists elsewhere: a result's PNG lives in app-private
 * storage that no provider path covers, and a workflow is built in memory. One
 * staging directory means one provider path and one place that gets cleaned.
 *
 * ⚠ This is the ONE outward-facing thing this app does. `docs/ARCHITECTURE.md`
 * §7 says generation is entirely offline and nothing leaves the device; sharing
 * is the user deliberately handing one file to one app they chose, which is a
 * different thing from the app talking to a network, and it is worth keeping
 * that distinction visible.
 */
object Share {

    private const val AUTHORITY_SUFFIX = ".share"

    /** ⚠ Matches `res/xml/file_paths.xml`. Both must change together. */
    private const val DIR = "share"

    /**
     * ⚠ Cleared on every share rather than never: these are copies, the
     * receiving app has already read what it wanted by the time the next share
     * happens, and an unbounded staging directory full of 1024² PNGs is a leak
     * nobody would look for.
     */
    private fun staging(context: Context): File =
        File(context.cacheDir, DIR).apply {
            if (isDirectory) listFiles()?.forEach { it.delete() }
            mkdirs()
        }

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

    /** ⭐ Hand a PNG to whatever the user picks. */
    fun image(context: Context, png: ByteArray, name: String) {
        val f = File(staging(context), sanitise(name) + ".png")
        f.writeBytes(png)
        send(context, uriFor(context, f), "image/png", "Share picture")
    }

    /**
     * ⭐ Hand a workflow's JSON to whatever the user picks.
     *
     * ⚠ `application/json` with a `.json` name. Some targets route on the
     * extension and some on the MIME type, and a flow that arrives as
     * `share.bin` is one the receiver cannot open.
     */
    fun workflow(context: Context, json: String, name: String) {
        val f = File(staging(context), sanitise(name) + ".json")
        f.writeText(json)
        send(context, uriFor(context, f), "application/json", "Share flow")
    }

    private fun send(context: Context, uri: android.net.Uri, mime: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            // ⚠ The grant is what makes the URI readable by the other app at
            // all; without it the receiver gets a SecurityException that looks
            // like a corrupt file.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // ⚠ `createChooser` rather than launching directly: it is what shows a
        // picker, and it also gives the grant to whichever app is chosen.
        context.startActivity(
            Intent.createChooser(intent, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** ⚠ A file name, not a caption: a `/` in a prompt would escape the dir. */
    private fun sanitise(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(48).ifBlank { "nightmare" }
}
