package com.abrah.nightmare

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ⭐⭐ **The QNN runtime, downloaded for this phone's NPU** (the user's call 2026-11-01, `docs/DEVICES.md` §4b): QAIRT
 * 2.51's shared pair (`libQnnHtp.so`, `libQnnSystem.so`) and the ONE arch trio the phone needs (v68 to v85), ~28 MB,
 * fetched once into the runtime dir ([BackendProcess.prepareRuntime]) and kept there through app updates. Until
 * 1.6.272 the APK carried every arch's 2.50 trio (52 MB of an 85 MB APK) and a v85 phone laid 2.51 over 2.50.
 *
 * ⚠ One SDK for every file, by construction: a 2.51 `libQnnHtp.so` asks the DSP for the 2.51 skel by name, and a
 * 2.50/2.51 mix was a real v85 failure ("Stub lib id mismatch", SM8975, 1.6.209/211). Mr.J's advice, and what
 * local-dream alpha.6 did. ⚠ Safe for the binaries we host: 2.51 ran our 2.50-built contexts on the S25
 * bit-identically (`DEVICES.md`), and a context runs forward only.
 * ⚠ Each file arrives through a `.part`, its SHA256 checked, then RENAMED into place: never written over, since a
 * truncated `.so` a process has mapped SIGSEGVs it (`BackendProcess.prepareRuntime`). Every 2.51 file differs in
 * size from its 2.50 one, so a 2.50 runtime left by an older APK is fetched again, once.
 * Hosted unchanged at `AbrahamPJ/nightmare-qnn-251`.
 */
object QnnPack {

    private const val TAG = "QnnPack"

    /**
     * The arches past the chip table: a part number one generation on is assumed one of these and then measured
     * ([DeviceProbe.PACK_ARCHES]). ⚠ Every arch's runtime is downloaded now; this set only names the guessed one.
     */
    val ARCHES = setOf(85)

    const val LABEL = "The NPU runtime for this phone (QAIRT 2.51)"

    private const val BASE = "https://huggingface.co/AbrahamPJ/nightmare-qnn-251/resolve/main/"

    /** name to (bytes, sha256), measured off the uploaded files (`../LocalDream/qairt-runtime/2.51.0.260929`). */
    val FILES: Map<String, Pair<Long, String>> = mapOf(
        "libQnnHtp.so" to (4_158_224L to "06bd420b3e8f19a6ab0e8181d1aac37d7fccd55b5958a102458b4125bbd7373f"),
        "libQnnSystem.so" to (4_125_232L to "199371bf2d3e61188bdfd19fbc6c34273d53ba11794444d85ef60e482807ce54"),
        "libQnnHtpV68.so" to (13_440_964L to "205e017b69fa23f2cd9d645cffd3f5dffa9f7a32e618a97794126074b0a7768a"),
        "libQnnHtpV68Skel.so" to (12_984_164L to "3f71eb57ce13a23d9dd92c6732782203ebb50245d2411812b06000008a2e1c21"),
        "libQnnHtpV68Stub.so" to (795_168L to "42f836611337e4de12122724e4243b5dae7ad1227d89f68bf0ee350d5158d5c0"),
        "libQnnHtpV69.so" to (13_480_132L to "cda3ef7c14f74ff043225a50ff7586b8d453660ac566e5fd5b399b4b5f925e0d"),
        "libQnnHtpV69Skel.so" to (13_027_556L to "b2f2fff2e6950d7ce71849c7c99e8b9ba6c16efdb84e63fa1fbd0c1a8efe9e91"),
        "libQnnHtpV69Stub.so" to (795_168L to "81192853575e692455c7639b47060aee6414ab7ef9fdbb5f8ae86bbdb357d9da"),
        "libQnnHtpV73.so" to (13_448_444L to "f81c233cb0e4fca6d5fac4da33c802c4f9741c794fdb036eef60eac2de99b1be"),
        "libQnnHtpV73Skel.so" to (13_018_320L to "4b53a5499d70459814079722dc4b57ba0bd23b915d50b89cdee99266a0624a28"),
        "libQnnHtpV73Stub.so" to (802_632L to "0f0a5cb8410d3dd699ff3b0155b213d6012e354a437b39f43c37c80d17a4bde2"),
        "libQnnHtpV75.so" to (13_432_060L to "2bc4353d10e0e856525dffb31e3dffb451fc81fe26b2d15523620276709b90ab"),
        "libQnnHtpV75Skel.so" to (12_997_844L to "58ff15eafba5963adb8ace60f79c17be8ff8a75011b3c613419d1d9e07fcc188"),
        "libQnnHtpV75Stub.so" to (802_632L to "b001da8f6aae9fa65066eda6ec4433616d59a2079428c6678e07315b4e4b4a33"),
        "libQnnHtpV79.so" to (13_652_088L to "d0780ea697d27ac716fe2d3332ec80fa89f79d299b26d3c4fb7b3ee78eedc7a4"),
        "libQnnHtpV79Skel.so" to (13_215_784L to "a90f3493f6f5c38672eca8d1bf61d645e7c5b2838db3fc89e62638187110f3d1"),
        "libQnnHtpV79Stub.so" to (802_632L to "bb7deaf56f7a507bffd2b125e1064cdcb8d82dfefeb03678f2f140c6992fa05f"),
        "libQnnHtpV81.so" to (14_630_488L to "5f521f597f749d230c09bd4e3599520eeabea0078964557cd06fffb5ffc226e7"),
        "libQnnHtpV81Skel.so" to (14_198_532L to "e1eeb53f7fe1699fbe842ea2bb495a310ae7ac638c33e1ae42077d48a1ff0764"),
        "libQnnHtpV81Stub.so" to (827_056L to "d26d6ba50964d0fcaead5a518b34360feb728e09bb0742dfc54c65eeb56f20c8"),
        "libQnnHtpV85.so" to (14_454_868L to "3e76ca9bbbe5b33adce4939c75b74c5308af034fa152dca06becbbe611f96291"),
        "libQnnHtpV85Skel.so" to (14_049_836L to "dcc6be053edf17763fbfa0200214212edf5bae96550d2c5c1667d551d0e2da6b"),
        "libQnnHtpV85Stub.so" to (875_568L to "96d3a1a72233271d0a822aca81bb51e68a7dc2ec6ff69d7e3911a117ac29c40e"),
    )

    /** The arches a runtime exists for, oldest first. ⚠ [DeviceProbe.STAGED_ARCHES] plus [ARCHES] must match it. */
    val RUNTIME_ARCHES: List<Int> =
        FILES.keys.mapNotNull { Regex("""libQnnHtpV(\d{2})\.so""").matchEntire(it)?.groupValues?.get(1)?.toInt() }.sorted()

    /** Bytes [names] still need in [dir]: what a launch on this phone would download. */
    fun missingBytes(dir: File, names: Collection<String>): Long =
        names.sumOf { n -> FILES[n]?.let { (bytes, _) -> if (File(dir, n).length() == bytes) 0L else bytes } ?: 0L }

    /** The 1.6.202-272 v85 pack's own folder, laid over the 2.50 runtime then; the runtime holds 2.51 itself now. */
    fun legacyDir(context: Context): File = File(context.filesDir, "qnnpack-v85")

    /**
     * ⭐ [names] into [dir], each fetched only when its size is wrong, checked against its SHA256 and renamed into
     * place. ⚠ Blocking: off the main thread, under [BackendProcess.prepareRuntime]'s lock. Throws an [IOException]
     * that says what failed and what to do, which a launch reports as its reason.
     */
    fun ensure(dir: File, names: Collection<String>, onProgress: (ModelInstaller.Progress) -> Unit = {}) {
        // ⭐ One bar for the whole runtime: done counts every file fetched before this one.
        val total = missingBytes(dir, names)
        var before = 0L
        for (name in names) {
            val v = FILES[name] ?: continue
            val dst = File(dir, name)
            if (dst.length() == v.first) continue
            val part = File(dir, "$name.part")
            try {
                UpscalerCatalog.download(Prefs.apply(BASE + name), part, v.first, { p ->
                    onProgress(ModelInstaller.Progress(p.phase, before + p.done, total))
                }) { false }
            } catch (e: IOException) {
                part.delete()
                throw IOException(
                    "$LABEL could not be downloaded (${missingBytes(dir, names) / 1_000_000} MB, once): " +
                        "${e.message ?: e.javaClass.simpleName}. Connect to the internet and run again", e,
                )
            }
            val got = sha256(part)
            if (got != v.second) {
                part.delete()
                throw IOException("$LABEL: $name sha256 is $got, expected ${v.second}. The download is corrupt")
            }
            part.setReadable(true, false)
            part.setExecutable(true, false)
            if (!part.renameTo(dst)) throw IOException("$LABEL: cannot replace $dst")
            before += v.first
            Log.i(TAG, "fetched $name (${v.first} bytes)")
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
