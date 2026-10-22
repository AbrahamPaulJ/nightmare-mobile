package com.abrah.nightmare.portrait

import android.content.Context
import android.util.Log
import com.abrah.nightmare.DeviceProbe
import com.abrah.nightmare.ModelInstaller
import com.abrah.nightmare.ModelStorage
import com.abrah.nightmare.Prefs
import com.abrah.nightmare.UpscalerCatalog
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * ⭐⭐ LivePortrait (Kuaishou, MIT): the face editor and the portrait animator, on the NPU.
 *
 * ⚠ The graphs are facefusion-mobile's, hosted per chip tier at `AbrahamPJ/facefusion-mobile-models` and used
 * unchanged (the user's call, 2026-10-21: reuse them, their face detector and landmarker included). Six files a tier,
 * ~180 MB, fetched once, each SHA256-checked like [com.abrah.nightmare.QnnPack]. The native side is `nmportrait.cpp`.
 */
object LivePortrait {

    private const val TAG = "LivePortrait"
    private const val BASE = "https://huggingface.co/AbrahamPJ/facefusion-mobile-models/resolve/main/"

    /** The editor's sliders, in the native order (`ffportrait.h`), each -1..1, 0 = unchanged. */
    val SLIDERS = listOf(
        "eyebrow_direction", "eye_gaze_horizontal", "eye_gaze_vertical", "eye_open_ratio", "lip_open_ratio",
        "mouth_grim", "mouth_pout", "mouth_purse", "mouth_smile", "mouth_position_horizontal",
        "mouth_position_vertical", "head_pitch", "head_yaw", "head_roll",
    )

    data class F(val name: String, val bytes: Long, val sha256: String)

    /** facefusion-mobile's manifest (7375fe3), the LivePortrait set and its face front end. */
    val FILES: Map<String, List<F>> = mapOf(
        "v68" to listOf(
            F("yoloface_v68.bin", 3_998_864L, "5fda8b93d8bfda64308b885185dfed412c9193c9af2597c6ad09330c68415be8"),
            F("fan2d_v68.bin", 25_193_920L, "00cb455fd905495dbfcd907620605d876ae6fa3dc6cb4cbddf9cf83487475b7b"),
            F("lp_feature_v68.bin", 1_841_656L, "819fb070f7a8ecd185b50dd8bcaa4d546e36ce975b1c91efe83faab58528ebe0"),
            F("lp_motion_v68.bin", 39_719_360L, "c41da42c0e7358fe5299a5d6c3a5e2703634d0898748f7707e211441226a110f"),
            F("lp_gencut_v68.bin", 124_687_256L, "c1ba997af5e31fd63da6c302f7d07dce289d5db3b6e72396e4cb9747c0177127"),
            F("lp_mlp_v68.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
        // ⭐ The 8 Gen 1 / 8+ Gen 1 (SM8450, SM8475: v69, 8 MB VTCM). ⚠⚠ v68's generator is built for 2 MB VTCM and never
        // returned (an 8 Gen 1 tester's face edit spun forever, and blocked every preview behind it; the S25 hangs on it
        // too): the same conversions emitted for v69 at 8 MB (facefusion-mobile `emit_arch.sh v69 8`, 2026-10-22), generator
        // 140 ms on the S25 (a v69 context runs forward). Never run on a real 8 Gen 1 yet.
        "v69" to listOf(
            F("yoloface_v69.bin", 3_937_424L, "8d4b117836cb00e663cd9709479aa4059c5f701a1567110f3b74fe1608269928"),
            F("fan2d_v69.bin", 24_796_608L, "757d1fefd6607b22fbf802b6d4edc7adc03a0cb88e131d77dd1e83a94f663e2f"),
            F("lp_feature_v69.bin", 1_583_608L, "f6961d97959db97a43f08b29f1bf894056114c2f644dff8d4f12b64b515ac97a"),
            // ⚠ From `lp_motion`, not v68's `lp_motionc`: that one at v68/v69 loses the head pose (head_yaw 15 came out
            // unturned on the S25, 15.6 dB from v79's; this one turns like v79, and a zero edit returns the face, 33 dB).
            F("lp_motion_v69.bin", 39_764_416L, "918536dfa6340d2772ce3ebcd3ada397fe9e4d6bbcf82b742dc33420960f9020"),
            F("lp_gencut_v69.bin", 117_515_160L, "893e26c90788226c6ca2ead447f9f0a14a7b872cf8771acda7b2ab6b6b6d2ed7"),
            F("lp_mlp_v69.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
        "v73" to listOf(
            F("yoloface_v73.bin", 3_994_768L, "f38df2db356c2c81b2e25625961daf12f59273ec53c26b40693ee743cb0ba018"),
            F("fan2d_v73.bin", 24_993_216L, "c0726e27f9edbd1d5b265f44909bf90a7d25cb12480bd72ca480b602941e8933"),
            F("lp_feature_v73.bin", 1_579_512L, "14973c02e81af7ebecdf822fd6d1a88a70b2c95abce61fe273dd24586994e837"),
            F("lp_motion_v73.bin", 31_015_360L, "3289126e5d940150d692c2efcf2cc7a661a2fbc90ed920a016ad1744110ba00d"),
            F("lp_gencut_v73.bin", 118_158_232L, "643209bb5e5ca879beb64951a8971a14a3af4e0f2bd9da6e32302c0d498343c5"),
            F("lp_mlp_v73.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
        "v79" to listOf(
            F("yoloface_v79.bin", 4_003_000L, "b4f6dde3905ea7cbea0f494011d770a26fcd67f8aa55547bf0e468aced73d3d1"),
            F("fan2d_v79.bin", 24_931_808L, "90a77c2c6f10ce0f2e1ab147ee7670c0e1b0bacbfa0342f21cbf7444340b8b57"),
            F("lp_feature_v79.bin", 1_600_032L, "d1989bac4c3e4af4f818848011b09241de4a0d7d020d46f18c9f069c85b97275"),
            F("lp_motion_v79.bin", 31_060_448L, "f58eae7d8797fe78e7764fb129aa67c2edcc208cb53cdc7fb4a3988d4a5ae07a"),
            F("lp_gencut_v79.bin", 119_284_664L, "1511195d188a67a0e3cde24dd50d53d7af34cbab23ae8990d1322b4313ddde2c"),
            F("lp_mlp_v79.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
        "v81" to listOf(
            F("yoloface_v81.bin", 4_097_168L, "39ac701503ca6ea1e96a01c01ed783d5c008370276b4109f56545b4bc51ea374"),
            F("fan2d_v81.bin", 25_120_192L, "10e97d7e0d97b19ddc36eb892edcc74cadcb888cc873b12a55ebb2581fec2e73"),
            F("lp_feature_v81.bin", 1_677_816L, "3072c08ee424386bf1ac4a2ed1a6cf88048b34963553a5460c090c9bc6aafa8e"),
            F("lp_motion_v81.bin", 31_367_616L, "ad0fd0174f2a108fc0d099c80e385a0e63ba3ab93528610078879ea0d120e4db"),
            F("lp_gencut_v81.bin", 124_097_432L, "7d4c2db45b9b99397eb636da6b4f5d99a425d824eb0f36c1070abd35b6fc439e"),
            F("lp_mlp_v81.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
        "v81s97" to listOf(
            F("yoloface_v81s97.bin", 4_003_000L, "0f97150cd46b73d0c6e81632daffd0a03f3cb21e9842babc3eaa423db93677fb"),
            F("fan2d_v81s97.bin", 24_923_616L, "840f5bff880febbad5bddfffd121e8fe16dd96c3c5703b294a5e03040aecfd78"),
            F("lp_feature_v81s97.bin", 1_595_936L, "f1e26d40bde8922a32ffc5bf92c068d58529bb0390a0465763bc61b44d23133c"),
            F("lp_motion_v81s97.bin", 31_056_352L, "6c42d92190aa8ee65f46481cdafef818f13f96e44277982d461d295b3afd689c"),
            F("lp_gencut_v81s97.bin", 118_412_216L, "f624f8f508e60b05d49715ee5addb611c930ff9ed260f453d31e942cc809898d"),
            F("lp_mlp_v81s97.bin", 909_184L, "9873ae3399a2b38e98bcebc1ed20f6c5cb045b75a407e63c9368c8a2253beecd"),
        ),
    )

    /**
     * ⭐ The tiers to try on this phone, best first: FF's `tierChain` by chip NAME (its soc 69 = SM8750, 97 = SM8845).
     * ⚠ A v85 runs the v81 builds (context binaries run forward), as the QNN models do there.
     */
    fun tiers(caps: DeviceProbe.Caps = DeviceProbe.caps()): List<String> = when {
        caps.vtcmMb < 8 -> listOf("v68")
        caps.arch >= 81 && caps.soc == "SM8845" -> listOf("v81s97", "v73", "v68")
        caps.arch >= 81 -> listOf("v81", "v73", "v68")
        caps.arch >= 79 && caps.soc == "SM8750" -> listOf("v79", "v73", "v68")
        caps.arch >= 73 -> listOf("v73", "v68")
        // ⭐ v69 with its 8 MB: the v69 build first ([FILES]' note); v68 only as the fallback.
        caps.arch >= 69 -> listOf("v69", "v68")
        else -> listOf("v68")
    }

    fun dir(context: Context): File = File(ModelStorage.root(context), "npu/liveportrait")

    // ---- the Models, Faces row: [com.abrah.nightmare.detail.PartDetector]'s shape exactly

    const val LABEL = "Face editor (LivePortrait)"

    /** This phone's tier, whole. */
    val BYTES: Long get() = FILES.getValue(tiers().first()).sumOf { it.bytes }

    fun isInstalled(context: Context): Boolean = installed(context, tiers().first())

    fun bytesOnDisk(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    /** ⭐ The cheap answer for a row. Refreshed by [install], [delete] and app start. */
    @Volatile
    var installed: Boolean = false
        private set

    fun refresh(context: Context) {
        installed = isInstalled(context)
    }

    fun install(context: Context, onProgress: (ModelInstaller.Progress) -> Unit, isCancelled: () -> Boolean = { false }) {
        try {
            installTier(context, tiers().first(), onProgress, isCancelled)
        } finally {
            refresh(context)
        }
    }

    fun delete(context: Context) {
        close()
        dir(context).deleteRecursively()
        refresh(context)
    }

    fun installed(context: Context, tier: String = tiers().first()): Boolean =
        FILES.getValue(tier).all { File(dir(context), it.name).length() == it.bytes }

    /** Bytes still to fetch for [tier]. */
    fun missingBytes(context: Context, tier: String = tiers().first()): Long =
        FILES.getValue(tier).filter { File(dir(context), it.name).length() != it.bytes }.sumOf { it.bytes }

    /** ⚠ Blocking. Fetches what [tier] lacks, each checked against its SHA256; throws an [IOException] that says what. */
    fun installTier(
        context: Context,
        tier: String,
        onProgress: (ModelInstaller.Progress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        val d = dir(context).apply { mkdirs() }
        for (f in FILES.getValue(tier)) {
            val dst = File(d, f.name)
            if (dst.length() == f.bytes) continue
            val part = File(d, f.name + ".part")
            UpscalerCatalog.download(Prefs.apply(BASE + f.name), part, f.bytes, onProgress, isCancelled)
            val got = sha256(part)
            if (got != f.sha256) {
                part.delete()
                throw IOException("LivePortrait: ${f.name} sha256 is $got, expected ${f.sha256}. The download is corrupt")
            }
            if (!part.renameTo(dst)) throw IOException("LivePortrait: cannot write $dst")
            Log.i(TAG, "fetched ${f.name} (${f.bytes} bytes)")
        }
    }

    /** The tier open now, or null. */
    @Volatile
    var openTier: String? = null
        private set

    /**
     * ⭐⭐ Let go once idle, and at once before a render ([com.abrah.nightmare.PictureModels.releaseAll]), like every other
     * held picture model. ⚠⚠ Why (an 8 Gen 1 tester, 1.6.209 and 1.6.214, Saved #6992-7064): nothing ever closed it, so
     * after one face edit ~190 MB of context binaries stayed on the NPU for the life of the process, beside every later
     * render; on that 7.5 GB phone each SD 1.5 Swap render then failed in its VAE encoder (`Dma execution failed on the
     * skel side 1003`, 0.8 GB free). The face editor arrived in 1.6.205.
     */
    internal val idle = com.abrah.nightmare.segment.IdleRelease(com.abrah.nightmare.segment.IdleRelease.IDLE_MS) {
        close()
        Log.i(TAG, "released (idle, low memory or a render)")
    }

    /** ⭐ Let the graphs go now if nothing is using them. */
    fun trim() = idle.releaseNow()

    /** ⭐ [block] with LivePortrait open, counted as a use so a release waits for it ([idle]). Inline: suspend calls work inside. */
    internal inline fun <T> using(context: Context, block: () -> T): T {
        idle.begin()
        try {
            open(context)
            return block()
        } finally {
            idle.end()
        }
    }

    /**
     * ⭐ Opens the first installed tier that LOADS, in [tiers] order. ⚠ Blocking (~1 s). Throws with every tier's reason.
     * [only] opens that one tier and nothing else: the harness's A/B of another chip's build (`lp_edit`).
     */
    @Synchronized
    fun open(context: Context, only: String? = null, unchecked: Boolean = false): String {
        openTier?.let { if (only == null || (it == only && !unchecked)) return it else close() }
        com.abrah.nightmare.npu.QnnRunner(context).initBackend()
        val why = mutableListOf<String>()
        for (t in if (only != null) listOf(only) else tiers()) {
            // ⚠ [unchecked]: the harness's hand-assembled tier (a file swapped from another tier), sizes not ours.
            if (!unchecked && !installed(context, t)) { why += "$t not installed"; continue }
            if (NativePortrait.open(dir(context).absolutePath, t)) {
                openTier = t
                Log.i(TAG, "open: $t")
                return t
            }
            why += NativePortrait.lastError()
        }
        throw IOException("LivePortrait would not open: ${why.joinToString("; ")}")
    }

    @Synchronized
    fun close() {
        if (openTier == null) return
        NativePortrait.close()
        openTier = null
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
