package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ⭐⭐ **The QNN runtime for an NPU newer than the APK's** (the user's call 2026-10-19, `docs/DEVICES.md`): QAIRT
 * 2.51's `libQnnHtp.so`, `libQnnSystem.so` and the V85 trio, ~38 MB, fetched once on a v85 phone (8 Elite Gen 6)
 * and laid over the APK's 2.50 libraries when the runtime is unpacked ([BackendProcess.prepareRuntime]). Every other
 * phone keeps the APK's 2.50 and downloads nothing, so the APK does not grow.
 *
 * ⚠ Safe to mix: 2.51 ran our 2.50-built context binaries on the S25 bit-identically (`DEVICES.md`), and a context
 * runs forward only, so every NPU build we host loads on v85. ⚠ All five come from ONE SDK: a 2.51 `libQnnHtp.so`
 * asks the DSP for the 2.51 skel by name. Hosted unchanged at `AbrahamPJ/nightmare-qnn-v85` (SHA256 below).
 */
object QnnPack {

    private const val TAG = "QnnPack"

    /** The arches this pack serves. ⚠ [DeviceProbe.PACK_ARCHES] reads this. */
    val ARCHES = setOf(85)

    const val LABEL = "QNN runtime for v85 (QAIRT 2.51)"

    private const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-qnn-v85/resolve/main/"

    /** name to (bytes, sha256), measured off the uploaded files. */
    val FILES: Map<String, Pair<Long, String>> = mapOf(
        "libQnnHtp.so" to (4_158_224L to "06bd420b3e8f19a6ab0e8181d1aac37d7fccd55b5958a102458b4125bbd7373f"),
        "libQnnSystem.so" to (4_125_232L to "199371bf2d3e61188bdfd19fbc6c34273d53ba11794444d85ef60e482807ce54"),
        "libQnnHtpV85.so" to (14_454_868L to "3e76ca9bbbe5b33adce4939c75b74c5308af034fa152dca06becbbe611f96291"),
        "libQnnHtpV85Stub.so" to (875_568L to "96d3a1a72233271d0a822aca81bb51e68a7dc2ec6ff69d7e3911a117ac29c40e"),
        "libQnnHtpV85Skel.so" to (14_049_836L to "dcc6be053edf17763fbfa0200214212edf5bae96550d2c5c1667d551d0e2da6b"),
    )

    val BYTES: Long get() = FILES.values.sumOf { it.first }

    fun dir(context: Context): File = File(context.filesDir, "qnnpack-v85")

    /** Every file present at its size (the hash was checked when it arrived). */
    fun installed(context: Context): Boolean =
        FILES.all { (n, v) -> File(dir(context), n).length() == v.first }

    /**
     * ⭐ Fetch what is missing, each checked against its SHA256. ⚠ Blocking: off the main thread. Throws an
     * [IOException] that says what failed, which [BackendProcess.start] reports as its reason.
     */
    fun ensure(context: Context, onProgress: (ModelInstaller.Progress) -> Unit = {}) {
        val d = dir(context).apply { mkdirs() }
        for ((name, v) in FILES) {
            val dst = File(d, name)
            if (dst.length() == v.first) continue
            val part = File(d, "$name.part")
            UpscalerCatalog.download(Prefs.apply(BASE + name), part, v.first, onProgress) { false }
            val got = sha256(part)
            if (got != v.second) {
                part.delete()
                throw IOException("$LABEL: $name sha256 is $got, expected ${v.second}. The download is corrupt")
            }
            if (!part.renameTo(dst)) throw IOException("$LABEL: cannot write $dst")
            Log.i(TAG, "fetched $name (${v.first} bytes)")
        }
    }

    /**
     * ⭐ The pack laid over the unpacked runtime in [runtime]: copied beside, then RENAMED into place, never written
     * over (a truncated `.so` that a process has mapped SIGSEGVs it, `BackendProcess.prepareRuntime`).
     */
    fun overlay(context: Context, runtime: File) {
        ensure(context)
        for (name in FILES.keys) {
            val tmp = File(runtime, "$name.tmp")
            File(dir(context), name).inputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.setReadable(true, false)
            tmp.setExecutable(true, false)
            if (!tmp.renameTo(File(runtime, name))) throw IOException("$LABEL: cannot replace $name")
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
