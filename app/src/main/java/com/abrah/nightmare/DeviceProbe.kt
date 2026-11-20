package com.abrah.nightmare

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * What this phone's NPU actually is — and it cannot be inferred from the name.
 *
 * ⚠⚠ **"Snapdragon 8 Gen 2 or newer" is not the requirement and never was.** It
 * is a *date* test, and the hardware is not ordered by date: the 8s Gen 3
 * (SM8635) is newer than an 8 Gen 2 and fails everything, because the "s" tier
 * reuses the flagship name with a cut-down HTP. A QNN context binary declares
 * every feature it needs and the device rejects the **first** one it lacks —
 * arch, then VTCM, then fp16 — one at a time, so fixing one reveals the next.
 * All of this is measured, not extrapolated: `../LocalDream/docs/DEVICE-SUPPORT.md`.
 *
 * ⇒ Two numbers decide everything here: the HTP **arch** and its **VTCM** in MB.
 * [measure] gets both from the hardware; [caps] falls back to a table only until
 * it has.
 */
object DeviceProbe {

    private const val TAG = "DeviceProbe"

    /**
     * SoC → HTP arch token, e.g. `V79`. Copied verbatim from DreamUI, which
     * built it from measurements and shipping reports.
     *
     * ⚠⚠ **Never guess an entry.** `libQnnHtp.so` dispatches on the arch of the
     * DEVICE, not of the context binary, so the matching Stub/Skel pair must be
     * unpacked or NPU init fails outright — with nothing in the error pointing
     * at the cause. An SoC that is absent here is safe: [runtimeLibs] then
     * unpacks *every* arch and lets QNN choose. A wrong entry is not safe.
     */
    // ⚠ `internal`, not private: `UnknownChipGateTest` asserts that a chip the
    // table knows still comes from the TABLE rather than the part-number parse.
    internal val SOC_TO_ARCH = mapOf(
        "SM8350" to 68,                     // 888 / 888+
        "SM8450" to 69, "SM8475" to 69,     // 8 Gen 1 / 8+ Gen 1
        "SM8550" to 73, "SM8550P" to 73,    // 8 Gen 2
        "QCS8550" to 73, "QCM8550" to 73,
        "SM8650" to 75, "SM8650P" to 75,    // 8 Gen 3
        "SM8750" to 79, "SM8750P" to 79,    // 8 Elite
        "SM8850" to 81, "SM8850P" to 81,    // 8 Elite Gen 5
        // ⭐ MEASURED v85 on a OnePlus PYB110 (`--device_info`, 1.6.213, `docs/DEVICES.md` §4). Tabled so the v81 fallback is
        // never learned on it: GitHub #142 (HONOR, 1.6.312) learned it from a QNN TRANSPORT refusal that no arch fixes, and
        // the DiT engine, which ran on that phone (#62), was then handed v81's skel for a v85 DSP.
        "SM8975" to 85,                     // 8 Elite Gen 6
        // ⭐ MEASURED on a Xiaomi 25053PC47G through `--device_info`, not read
        // off a spec sheet: v73 and 8 MB. It is the newest chip in the table and
        // the one that most looks like it should be higher.
        "SM8735" to 73,                     // 8s Gen 4
        // ⭐ v73 with UNDER 8 MB of VTCM (`docs/DEVICES.md` §1), so not in [SOC_HAS_8MB_VTCM]: SD 1.5 keeps
        // `_min`; the v73 DiT builds take it as alpha ([ModelCatalog.DIT_V73_MIN_VTCM_MB], 2026-10-17).
        "SM8635" to 73,                     // 8s Gen 3
        // ⭐ A tester's 7+ Gen 3 (Telegram #9390-9405, 2026-11-19): v73, and it ran the NPU route; offered what an 8 Gen 2
        // runs (the user, Saved #8264). Its VTCM is not known, so not in [SOC_HAS_8MB_VTCM]: the DiT kernels read it off
        // the hardware, and an HMX that fails the self-test is learned off ([recordHmxSelfTest]).
        "SM7675" to 73,                     // 7+ Gen 3
    )

    /**
     * SoCs known to carry the **8 MB** of VTCM that xororz's `_8gen1`/`_8gen2`
     * graphs ask for.
     *
     * ⚠⚠ Deliberately a SEPARATE table from [SOC_TO_ARCH], and they must never
     * be merged. That one decides which Skel to unpack and breaks NPU init when
     * wrong; this one only decides which archive to download and costs a wasted
     * download when wrong. ⚠ VTCM is not implied by the marketing generation —
     * the SM8635 is an "8s Gen 3", newer than an 8 Gen 2, and has less than
     * 8 MB. Its backend and device create fine, proving the arch is supported,
     * and only the context load fails.
     */
    private val SOC_HAS_8MB_VTCM = setOf(
        "SM8450", "SM8475",                        // 8 Gen 1 — 8 MB on a v69 HTP
        "SM8550", "SM8550P", "QCS8550", "QCM8550", // 8 Gen 2
        "SM8650", "SM8650P",                       // 8 Gen 3
        "SM8750", "SM8750P",                       // 8 Elite
        "SM8850", "SM8850P",                       // 8 Elite Gen 5
        "SM8735", "SM8845",
    )

    /** Every arch whose libraries the APK carries. ⚠ Keep in step with `tools/stage_backend.ps1`. */
    val STAGED_ARCHES = listOf(68, 69, 73, 75, 79, 81)

    /** ⭐ Arches served by a DOWNLOADED runtime ([QnnPack]: v85, QAIRT 2.51), fetched on that phone only. */
    val PACK_ARCHES: Set<Int> get() = QnnPack.ARCHES

    /**
     * ⭐⭐ Chips whose DiT models run with the NPU's matrix unit OFF (`GGML_HEXAGON_NHMX=0`), to the name a person
     * knows them by (`docs/DEVICES.md` §3b). With HMX every picture came back flat grey, and with the decode on the
     * CPU (1.6.185-1.6.192) as NOISE: Diagnose on a tester's 8s Gen 4 (Z-Image, Klein 9B, Anima 2.9B) and an 8s Gen 3
     * (Anima Turbo 1.1) found `no_hmx` the only leg with a picture in it; `vae_cpu`'s "clean" was the seed's noise
     * decoded (two Anima models, one thumbnail byte for byte). The 8 Gen 2 is clean on the same v73 skel with HMX.
     * Cost at 256x256, 2 steps: Anima 55 s for 29, Z-Image 113 s for 25, Klein 9B 323 s for 34.
     * ⭐ The 8 Gen 1 / 8+ Gen 1 (v69) for another reason: HMX is off by default below v73 (`dit/034`; HexAnima runs fp16 HMX there, untested here), so its skel
     * (`dit/034`) runs HVX only, and the same launch (HMX off, decode on the CPU) is what it gets.
     */
    val DIT_NO_HMX_SOCS = mapOf(
        "SM8735" to "8s Gen 4", "SM8635" to "8s Gen 3", "SM8450" to "8 Gen 1", "SM8475" to "8+ Gen 1",
        // ⭐ v69 with 4 MB of VTCM, measured (Saved #8058). VTCM is read off the hardware by the skel and the host
        // (`HAP_compute_res_query_VTCM`, `htp_iface_hwinfo`), and every kernel sizes its tiles to it, so 4 MB needs no gate.
        "SM7475" to "7+ Gen 2",
    )

    /**
     * The HTP as far as we know it.
     *
     * ⚠ [measured] is the whole point of the type. An unmeasured guess is good
     * enough to pick a download and **not** good enough to promise a user their
     * phone works, so the flag travels with the numbers rather than being lost
     * at the call site.
     */
    data class Caps(
        val arch: Int,
        val vtcmMb: Int,
        val measured: Boolean,
        /** `SM8750` — shown to the user, and the key both tables above use. */
        val soc: String,
        /**
         * ⭐ `MemTotal` in bytes; 0 = unknown ([ModelCatalog]'s `Build.minRamBytes`).
         * ⚠ Defaults to unknown rather than reading the system, so a test that
         * builds a Caps never sees its HOST's RAM; [caps] and [measure] fill it.
         */
        val ramBytes: Long = 0L,
        /**
         * ⭐ False on a chip KNOWN not to be a Snapdragon ([qualcommVendor]): MediaTek, Exynos, Tensor. It has no
         * Hexagon NPU, so no NPU build runs ([supportsArch]) and every flow is dimmed ([com.abrah.nightmare.canvas
         * .Recipe.runsOnDevice]). ⚠ GitHub #85: a MediaTek phone read as an "unknown chip" at the v68 floor, was
         * offered everything, and failed every launch with `QnnDsp No Snapdragon SOC detected`. Defaults true, so
         * a chip that says nothing is still offered everything ([known]).
         */
        val qualcomm: Boolean = true,
    ) {
        /** ⚠ The APK has to carry this arch's Skel, or NPU init fails whatever the model. */
        val staged: Boolean get() = arch in STAGED_ARCHES || arch in PACK_ARCHES

        /**
         * ⭐ DiT models run with HMX off on this chip: a known one ([DIT_NO_HMX_SOCS]), its self-test failed ([hmxBad]),
         * or any v69 (or a measured v68-v72): HMX is off below v73 by default (`dit/034`), so the launch must also move
         * the decode to the CPU. ⚠ A 7+ Gen 2 (SM7475) measures v69 and was in no table: with the v69 trial on it
         * decoded on the NPU without HMX, which aborts at 1024² (Saved #8058-8063, item 68d, 2026-11-15).
         */
        val ditNoHmx: Boolean get() = soc in DIT_NO_HMX_SOCS || hmxBad || (qualcomm && arch in 69 until 73 && (measured || soc in SOC_TO_ARCH))

        /**
         * ⭐⭐⭐ **Do we actually KNOW this chip, or is [arch] the floor
         * because nothing recognised it?**
         *
         * ⚠⚠⚠ Anything that HIDES a feature must ask this first. An
         * unmeasured chip that matched no table and no part number reads as
         * v68, and treating that as "too old" is exactly the 2026-09-20 bug
         * where a Snapdragon 8 Gen 5 owner was told every SDXL, Anima and
         * FLUX row was beyond their phone (`docs/DEVICES.md`).
         *
         * ⇒ Gate on `!supportsArch(n) && known` — an unknown chip is offered
         * the feature and finds out by trying, which is recoverable. Hiding it
         * is not.
         */
        // ⚠⚠ …or a chip the TABLE names: the 888 (SM8350) IS v68, the floor's own number, so it read as unknown and was
        // denied the CPU and GPU routes every chip below v73 gets (the user's S21 FE, 2026-11-21: "needs v69").
        val known: Boolean get() = measured || arch != FLOOR_ARCH || !qualcomm || soc.uppercase() in SOC_TO_ARCH

        /**
         * ⚠ A floor, never an allowlist: newer archs run older contexts — but only an arch the APK
         * ships a Skel for ([staged]). An 8 Elite Gen 6 (2026-10-09, "Device Creation failure")
         * is newer than every Skel we have, so it runs nothing on the NPU, old contexts included.
         */
        fun supportsArch(min: Int): Boolean = qualcomm && staged && arch >= min

        /** ⭐ A chip whose NPU is newer than every arch this APK's runtime knows. */
        val npuTooNew: Boolean get() = arch > ((STAGED_ARCHES + PACK_ARCHES).maxOrNull() ?: 0)
    }

    /**
     * ⭐⭐ **This phone's HMX failed the engine's self-test** (`dit/030`: one Q4_0 matmul on HMX against the CPU at
     * session start). The user's call (2026-10-19): ACT on it, so a chip no list names (#58's SM7750, Anima flat grey)
     * gets HMX off after its first DiT launch, not after a report. Two SM8735 said `WRONG (zeros)`, rel-err 1.0000.
     * ⚠ Only a WRONG or FAILED from a launch with HMX ON sets it ([recordHmxSelfTest]); a launch with HMX off says
     * "skipped". Kept per phone across restarts: the chip does not change. Loaded by [loadHmxVerdict] at app start.
     */
    @Volatile
    var hmxBad: Boolean = false
        private set

    private const val HMX_PREFS = "device_probe"
    private const val HMX_BAD_KEY = "hmx_self_test_bad"

    fun loadHmxVerdict(context: Context) {
        val prefs = context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE)
        hmxBad = prefs.getBoolean(HMX_BAD_KEY, false)
        // ⚠⚠ Saved #8083, GitHub #123 (2026-11-15): three 8 Gen 3 phones had learned 1.25 GiB, below one block of Z-Image
        // (1.52 GB) and Krea 2 (1.41 GB), so every DiT run failed in segment 1. A cap under today's floor is lifted to it.
        ditMapCap = prefs.getString(DIT_MAP_CAP_KEY, null)?.let { stored ->
            val v = stored.toDoubleOrNull()
            if (v != null && v < DIT_MAP_FLOOR_GIB) DIT_MAP_FLOOR_GIB.toString().also {
                prefs.edit().putString(DIT_MAP_CAP_KEY, it).apply()
            } else stored
        }
        ditMapCaps = decodeDitMapCaps(prefs.getString(DIT_MAP_CAPS_KEY, null))
        DitGpu.load(context)
        packFallback = prefs.getInt(PACK_FALLBACK_KEY, 0).takeIf { it > 0 }
        packConfirmed = prefs.getInt(PACK_CONFIRMED_KEY, 0).takeIf { it > 0 }
    }

    /**
     * ⭐ The arch a phone runs on after its GUESSED pack arch would not load ([learnPackFallback]); null until then.
     * ⚠ GitHub #61/#62: the first real SM8975 failed `Failed to load skel, error: 1002` on the V85 trio, and its v85
     * is [archFromPartNumber]'s guess. The context binaries run forward-only, so a v81-class chip runs them on the
     * APK's own 2.50 runtime and V81 trio. Kept per phone, like [hmxBad]: the chip does not change.
     */
    @Volatile
    var packFallback: Int? = null
        private set

    private const val PACK_FALLBACK_KEY = "pack_fallback_arch"
    const val PACK_FALLBACK_ARCH = 81

    /**
     * ⭐ A pack arch QNN itself named on this phone ([dspPackArch]): the fallback is never learned again. Per phone.
     * ⚠ A real SM8975 (OnePlus PYB110, 1.6.213): stuck on the learned v81 fallback, the 2.50 runtime asked for
     * `libQnnHtpV85Stub.so` on every launch. Only [measure] dropped the fallback, and it ran where the app crashed.
     */
    @Volatile
    private var packConfirmed: Int? = null
    private const val PACK_CONFIRMED_KEY = "pack_confirmed_arch"

    /** ⚠ Pure: the pack arch whose stub a launch asked for and did not find (the DSP's real arch), or null. */
    fun dspPackArch(tail: List<String>): Int? = tail.firstNotNullOfOrNull {
        Regex("""libQnnHtpV(\d+)Stub\.so" not found""").find(it)?.groupValues?.get(1)?.toIntOrNull()
    }?.takeIf { it in PACK_ARCHES }

    /**
     * ⭐ The v81 fallback was wrong: the launch asked for a pack arch's stub ([dspPackArch]). Drop it, remember the arch,
     * and the caller relaunches once on the pack ([HarnessOps.launchBackend]). True when it changed something.
     */
    fun unlearnPackFallback(context: Context, tail: List<String>): Boolean {
        if (packFallback == null) return false
        val arch = dspPackArch(tail) ?: return false
        packFallback = null
        packConfirmed = arch
        context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE).edit()
            .remove(PACK_FALLBACK_KEY).putInt(PACK_CONFIRMED_KEY, arch).apply()
        Log.w(TAG, "the DSP asked for the v$arch stub on ${soc()}: the v$PACK_FALLBACK_ARCH fallback is dropped")
        return true
    }

    /** ⚠ Pure: a backend log that died loading the NPU skel (the transport, before any model). */
    fun skelLoadFailed(tail: List<String>): Boolean =
        tail.any { "Failed to load skel" in it || "Transport layer setup failed" in it }

    /**
     * ⭐ A launch on a guessed pack arch died at the skel ([skelLoadFailed]): remember [PACK_FALLBACK_ARCH] and say so.
     * True when NEW, so the caller relaunches once ([HarnessOps.launchBackend]). ⚠ Never on a measured or tabled chip.
     */
    fun learnPackFallback(context: Context, tail: List<String>): Boolean {
        if (packFallback != null || packConfirmed != null || measured != null || SOC_TO_ARCH[soc()] != null) return false
        if (archFromPartNumber(soc()) !in PACK_ARCHES || !skelLoadFailed(tail)) return false
        packFallback = PACK_FALLBACK_ARCH
        context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE).edit().putInt(PACK_FALLBACK_KEY, PACK_FALLBACK_ARCH).apply()
        Log.w(TAG, "the v${archFromPartNumber(soc())} skel would not load on ${soc()}: running as v$PACK_FALLBACK_ARCH from now on")
        return true
    }

    /** ⭐ The self-test said this HMX is wrong ([BackendProcess.engineNpuLine]'s "hmx"): remembered. True when NEW. */
    fun recordHmxSelfTest(context: Context, verdict: String): Boolean {
        if (!hmxSelfTestFailed(verdict) || hmxBad) return false
        hmxBad = true
        context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE).edit().putBoolean(HMX_BAD_KEY, true).apply()
        Log.w(TAG, "HMX self-test failed on ${soc()}: DiT models run with HMX off from now on ($verdict)")
        return true
    }

    /**
     * ⭐ The DiT budget (GiB) this phone's NPU taught us, or null. Kept per phone, like [hmxBad].
     * ⚠⚠ GitHub #97: a Zenfone 10 (SM8550) refused `fastrpc_mmap` at about 1.9 GB (Anima, 1,674 MB of weights plus the
     * graph-cut cache), inside v73's 2.0 ([BackendProcess.lowArchDitBudget]), where a Xiaomi SM8550 reached 2.44 GB. The
     * ceiling is the firmware's, so it is learned from the failure and not set by chip: other phones keep their speed.
     */
    @Volatile
    var ditMapCap: String? = null
        internal set

    /**
     * ⚠ Pure: the budget after a map failure under [launched], or null when it cannot go lower than
     * [DIT_MAP_LEARN_FLOOR_GIB]. [needFloor] (GiB) is the
     * biggest single block a lift was made for ([liftDitMapCap]): stepping under it again would only fail the other way.
     */
    internal fun nextDitMapCap(launched: String?, needFloor: Double = 0.0): String? {
        val v = launched?.toDoubleOrNull() ?: return null
        val floor = maxOf(DIT_MAP_LEARN_FLOOR_GIB, needFloor)
        if (v <= floor) return null
        return maxOf(v - DIT_MAP_STEP_GIB, floor).toString()
    }

    /**
     * ⚠ Pure: the cap that fits a block needing [needMb] MB, or null when [cap] already holds it or there is no cap.
     * The next step up from the floor that leaves 0.25 GiB over the block; past the top step the cap is dropped ("").
     */
    internal fun liftedDitMapCap(cap: String?, needMb: Double): String? {
        val c = cap?.toDoubleOrNull() ?: return null
        val need = needMb / 1024.0
        if (need <= c) return null
        var v = DIT_MAP_FLOOR_GIB
        while (v < need + 0.25) v += DIT_MAP_STEP_GIB
        return if (v > DIT_MAP_TOP_GIB) "" else v.toString()
    }

    /**
     * ⭐⭐ The budget each MODEL taught us, over the phone's [ditMapCap] (GitHub #144). One phone-wide figure ping-ponged:
     * Anima 2.9B maps 36 MB blocks and needed 1.25 GiB on an HONOR SM8650 (1.6.307 worked there), while a Krea 2 block
     * needs 1.41 GB, so 1.6.308's fixed 1.75 floor for Krea 2 broke Anima ("fastrpc_mmap 0x1a" at block 4 of 42).
     * Learned down ([learnDitMapLimit]) and lifted ([liftDitMapCap]) per model id; kept per phone, like [hmxBad].
     */
    @Volatile
    private var ditMapCaps: Map<String, String> = emptyMap()

    /** The biggest block a lift was made for, GiB, per model, this session: [nextDitMapCap] does not step under it again. */
    private val ditNeedFloors = java.util.concurrent.ConcurrentHashMap<String, Double>()

    /** ⭐ The budget for [model]: its own learned one ("" = lifted past the top: no cap), else the phone's, else null. */
    fun ditMapCapFor(model: String?): String? {
        val own = model?.let { ditMapCaps[it] } ?: return ditMapCap
        return own.ifEmpty { null }
    }

    private fun saveDitMapCap(context: Context, model: String, cap: String) {
        ditMapCaps = ditMapCaps + (model to cap)
        context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE).edit()
            .putString(DIT_MAP_CAPS_KEY, encodeDitMapCaps(ditMapCaps)).apply()
    }

    /** ⚠ Pure: `id=cap;id=cap` ("" is a lift past the top). Model ids are `[a-z0-9_.-]`. */
    internal fun encodeDitMapCaps(m: Map<String, String>): String = m.entries.joinToString(";") { "${it.key}=${it.value}" }

    internal fun decodeDitMapCaps(s: String?): Map<String, String> =
        s.orEmpty().split(';').mapNotNull { e ->
            val k = e.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val v = e.substringAfter('=')
            if (v.isNotEmpty() && v.toDoubleOrNull() == null) null else k to v
        }.toMap()

    /**
     * ⭐ The learned cap was smaller than one block of [model] (the engine's "cannot make enough memory available …
     * need X MB … budget"): raise it so that block fits ([liftedDitMapCap]). Returns the new budget ("" = no cap), or null.
     */
    fun liftDitMapCap(context: Context, model: String?, needMb: Double): String? {
        val next = liftedDitMapCap(ditMapCapFor(model), needMb) ?: return null
        val key = model ?: "?"
        ditNeedFloors.merge(key, needMb / 1024.0, ::maxOf)
        saveDitMapCap(context, key, next)
        Log.w(TAG, "a $key block needs ${needMb.toInt()} MB, over the learned cap: its DiT budget ${next.ifEmpty { "uncapped" }} from now on")
        return next
    }

    /** ⭐ Remembers [nextDitMapCap] for [model]. Repeating it for the same launch changes nothing. Returns the new budget, or null. */
    fun learnDitMapLimit(context: Context, model: String?, launched: String?): String? {
        val key = model ?: "?"
        val next = nextDitMapCap(launched, ditNeedFloors[key] ?: 0.0) ?: return null
        saveDitMapCap(context, key, next)
        Log.w(TAG, "the NPU refused a mapping on ${soc()} for $key: its DiT budget $next GiB from now on (was $launched)")
        return next
    }

    /**
     * ⚠ A PHONE-wide cap stored under this is lifted to it at start: three 8 Gen 3 phones had learned 1.25 GiB from one
     * model and every other model then failed (Z-Image's prelude needs 1.52 GB at 1024², a Krea 2 block 1.41 GB; Saved
     * #8083 / GitHub #123). A model's own smaller budget still wins ([BackendProcess.cappedDitBudget]: Klein 9B's v73 1.5).
     */
    internal const val DIT_MAP_FLOOR_GIB = 1.75

    /**
     * ⭐ How low ONE model may learn: Anima 2.9B ran at 1.25 GiB on the SM8650 that refused it at 1.75 (GitHub #144). A model
     * whose block needs more is lifted back over it ([liftDitMapCap]), and only that model.
     */
    internal const val DIT_MAP_LEARN_FLOOR_GIB = 1.25
    private const val DIT_MAP_TOP_GIB = 2.75
    private const val DIT_MAP_STEP_GIB = 0.5
    private const val DIT_MAP_CAP_KEY = "dit_map_cap_gib"
    private const val DIT_MAP_CAPS_KEY = "dit_map_cap_by_model"

    /** ⚠ Pure: "HMX self-test WRONG (zeros), rel-err 1.0000…" or "…FAILED" is a failure; ok / skipped are not. */
    fun hmxSelfTestFailed(verdict: String): Boolean {
        val v = verdict.removePrefix("HMX self-test").trim()
        return v.startsWith("WRONG") || v.startsWith("FAILED")
    }

    /** Set once [measure] succeeds. ⚠ Volatile: written on IO, read on main. */
    @Volatile
    var measured: Caps? = null
        private set

    /** ⚠ Tests only: a phone's caps in place of the host's. */
    @androidx.annotation.VisibleForTesting
    internal fun measuredForTest(c: Caps?) { measured = c }

    /**
     * ⭐ The phone's RAM, from `/proc/meminfo` — readable by any app and with no
     * Context, which [caps] does not have. The same figure as
     * `ActivityManager.MemoryInfo.totalMem` ([Prefs.LOWRAM_BELOW_BYTES] has the
     * two phone classes). 0 when it cannot be read (the JVM tests).
     */
    fun totalRamBytes(): Long = runCatching {
        java.io.File("/proc/meminfo").useLines { lines ->
            lines.first { it.startsWith("MemTotal:") }
                .filter { it.isDigit() }.toLong() * 1024L
        }
    }.getOrDefault(0L)

    /** The SoC as Android reports it, uppercased — the key for both tables. */
    fun soc(): String = (Build.SOC_MODEL ?: "").uppercase()

    /**
     * ⭐⭐ What to gate and label with.
     *
     * ⚠ A measurement always wins. Failing that, the tables; failing those, the
     * **floor** — v68 with 2 MB, which is what `_min` needs and what runs
     * anywhere. Guessing high on an unknown chip is what hands a user a
     * gigabyte their HTP then refuses, which is the failure mode this whole
     * file exists to avoid.
     */
    fun caps(): Caps {
        measured?.let { return it }
        val soc = soc()
        return Caps(
            arch = SOC_TO_ARCH[soc] ?: packFallback ?: archFromPartNumber(soc) ?: FLOOR_ARCH,
            vtcmMb = when {
                soc in SOC_HAS_8MB_VTCM -> 8
                // ⚠ A chip NEWER than every 8 MB part we know is far likelier
                // to have 8 MB than 2: every flagship since the 8 Gen 1 has.
                // ⚠⚠ Still only a guess, and `runsOn` may hand out a download
                // the HTP refuses — which is why [needsMeasuring] asks for the
                // real numbers rather than leaving this standing.
                archFromPartNumber(soc) != null -> 8
                else -> FLOOR_VTCM_MB
            },
            measured = false,
            soc = soc,
            ramBytes = totalRamBytes(),
            qualcomm = qualcommVendor(Build.SOC_MANUFACTURER ?: "", soc),
        )
    }

    /**
     * ⭐ Pure: whether a chip may be a Snapdragon. False only when Android NAMES another chip maker
     * (`Build.SOC_MANUFACTURER`: "Mediatek", "Samsung" for Exynos, "Google" for Tensor, "Unisoc") or the model is
     * MediaTek's `MT…` (GitHub #85's MT6835). ⚠ A Snapdragon part number (`SM8750`, any table entry) wins over the
     * maker field, and a blank or unlisted maker is given the benefit of the doubt: hiding every flow on a real
     * Snapdragon is the worse mistake ([Caps.known]).
     */
    internal fun qualcommVendor(manufacturer: String, soc: String): Boolean {
        val s = soc.uppercase()
        if (s in SOC_TO_ARCH || Regex("^SM\\d{4}").containsMatchIn(s)) return true
        val m = manufacturer.trim().lowercase()
        if (m in NOT_QUALCOMM) return false
        return !s.startsWith("MT")
    }

    private val NOT_QUALCOMM = setOf("mediatek", "samsung", "google", "unisoc", "spreadtrum", "hisilicon")

    /**
     * ⭐⭐⭐ An arch for a chip no table knows, read out of its PART NUMBER.
     *
     * ⚠⚠ The tables are an allowlist, and an allowlist is wrong by default
     * the day a new chip ships. Reported 2026-09-20 by a Snapdragon 8 Gen 5
     * owner: every SDXL, Anima and FLUX row read "this device cannot run it",
     * because their `Build.SOC_MODEL` is not one of the strings above and the
     * floor below is v68 with 2 MB — a guess, presented as a fact about their
     * hardware.
     *
     * ⭐ The shape is upstream's own (`DitEngine.isSupportedDevice`, local-dream):
     * take the digits out of `SM8850P` and compare the NUMBER. It needs no
     * maintenance when a chip ships, which is the same reasoning the video
     * gate uses a real canary binary rather than a SoC allowlist
     * (`docs/NEODRAGON.md` §4).
     *
     * ⚠ Only ever used UPWARD, for a part number at or above the newest one
     * we know. An unknown chip BELOW that is genuinely unknown — Qualcomm's
     * numbering is only roughly chronological (SM8635 is an "8s Gen 3" with
     * less than 8 MB of VTCM) — so those keep the floor.
     *
     * ⚠⚠ And only within the newest GENERATION (the same hundred: SM885x, SM8860). A part
     * number a whole generation on (SM8950, SM8975 — the 8 Elite Gen 6) carries a new HTP,
     * and was handed v81 until a user's npuforge compile died with "Device Creation failure"
     * (2026-10-09): no Skel here knows it. It gets [NEWER_THAN_STAGED], which every NPU
     * gate refuses ([Caps.supportsArch]) and the device sheet explains.
     * ⭐ ONE generation on is assumed v85 since 1.6.202 (the user's call 2026-10-19): its runtime is fetched
     * ([QnnPack]) and the probe then MEASURES with it. Two generations on is still [NEWER_THAN_STAGED].
     */
    internal fun archFromPartNumber(soc: String): Int? {
        if (!soc.startsWith("SM")) return null
        val n = soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
            ?: return null
        // ⚠ Counted from the newest chip on a STAGED arch: the measured SM8975 (v85, a pack arch) is tabled, and counting
        // from it made the SM8950 beside it "older than known" and an 8 Elite Gen 5 variant v85.
        val staged = SOC_TO_ARCH.filterValues { it in STAGED_ARCHES }
        val newestKnown = staged.keys.mapNotNull { partNumberOf(it) }.maxOrNull() ?: return null
        if (n < newestKnown) return null
        if (n / 100 == newestKnown / 100 + 1) return PACK_ARCHES.maxOrNull() ?: NEWER_THAN_STAGED
        if (n / 100 > newestKnown / 100) return NEWER_THAN_STAGED
        return staged.values.maxOrNull()
    }

    /** ⚠ Not an arch: "newer than every Skel this APK ships" — see [archFromPartNumber]. */
    const val NEWER_THAN_STAGED = 99

    private fun partNumberOf(soc: String): Int? =
        soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()

    /**
     * ⭐⭐ True when the guess above is doing real work and should be replaced
     * by a measurement. An unknown chip is exactly the case where assuming is
     * worst, and `--device_info` reads the arch and VTCM off the hardware.
     */
    fun needsMeasuring(): Boolean = measured == null && soc() !in SOC_TO_ARCH

    /** v68 / 2 MB — what `_min` is built for, and the safe answer for an unknown chip. */
    const val FLOOR_ARCH = 68
    const val FLOOR_VTCM_MB = 2

    /**
     * Which QNN libraries this device needs unpacked.
     *
     * ⚠ The shared pair plus ONE arch trio, not all six: the full set is ~150 MB
     * and [BackendProcess] re-copies it on every start. ⚠⚠ An unrecognised SoC
     * gets **every** arch — QNN then picks, which is slower to unpack and
     * always correct, where a guessed arch is fast and sometimes fatal.
     */
    fun runtimeLibs(all: List<String>): List<String> {
        // ⭐ [all] is every runtime file ([QnnPack.FILES]); a pack arch (v85) is guessed from its part number.
        val arch = SOC_TO_ARCH[soc()] ?: measured?.arch ?: packFallback ?: archFromPartNumber(soc())?.takeIf { it in PACK_ARCHES } ?: return all
        val token = "V$arch"
        return all.filter { n ->
            // Shared libraries carry no arch token; the per-arch trio does.
            !ARCH_TOKEN.containsMatchIn(n) || n.contains(token)
        }
    }

    private val ARCH_TOKEN = Regex("V\\d{2}")

    /**
     * Ask the HTP what it is: `--device_info` prints one line of JSON and exits.
     *
     * ⭐ It calls `QnnDevice_getPlatformInfo`, which needs **no model, no
     * context and no server** — so the answer is available before a ~1 GB
     * download is committed to, which is the entire reason it is worth having.
     *
     * ```
     * {"ok":true,"devices":[{"device_id":0,"vtcm_mb":8,"soc_model":69,"arch":79,…}]}
     * ```
     *
     * ⚠⚠ It measures arch and VTCM **only, not fp16**. A clean probe is not a
     * promise that a model will load, and must never be shown to a user as one.
     *
     * ⚠ Returns null rather than throwing: an unprobeable device still has the
     * tables, and losing the app to a diagnostic would be absurd.
     */
    private const val PROBE_TIMEOUT_S = 20L

    suspend fun measure(context: Context): Caps? = withContext(Dispatchers.IO) {
        try {
            val exe = File(context.applicationInfo.nativeLibraryDir, BackendProcess.EXECUTABLE)
            if (!exe.exists()) return@withContext null
            val runtime = BackendProcess.prepareRuntime(context)
            val p = ProcessBuilder(
                exe.absolutePath, "--device_info", "--lib_dir", runtime.absolutePath,
            ).apply {
                redirectErrorStream(true)
                environment()["LD_LIBRARY_PATH"] = listOf(
                    runtime.absolutePath, "/system/lib64", "/vendor/lib64",
                ).joinToString(":")
                environment()["DSP_LIBRARY_PATH"] = runtime.absolutePath
            }.start()
            // ⚠ A wedged DSP never closes the child's stdout, and `readText` would hold this IO thread (and the
            // child) for ever. The probe answers in well under a second; past [PROBE_TIMEOUT_S] it is killed, the
            // read ends, and the tables stand in as for any unprobeable phone.
            // ⚠⚠ The interrupt below lands in `waitFor` on every clean probe: uncaught on a bare Thread it killed
            // the WHOLE APP on opening Models or Device (1.6.212-217, testers on 8 Elite Gen 5, the S25 too).
            val watchdog = Thread {
                try {
                    if (!p.waitFor(PROBE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "device_info did not exit in $PROBE_TIMEOUT_S s; killing it")
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }.apply { isDaemon = true; name = "device-probe-watchdog"; start() }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            watchdog.interrupt()

            // ⚠ The JSON is one line among the runtime's own chatter, so it is
            // found rather than assumed to be the whole output.
            val line = out.lineSequence().firstOrNull { it.trimStart().startsWith("{") && "devices" in it }
                ?: run { Log.w(TAG, "no device_info json in: ${out.take(400)}"); return@withContext null }
            val d = JSONObject(line).optJSONArray("devices")?.optJSONObject(0)
                ?: return@withContext null
            val caps = Caps(
                arch = d.optInt("arch", FLOOR_ARCH),
                vtcmMb = d.optInt("vtcm_mb", FLOOR_VTCM_MB),
                measured = true,
                soc = soc(),
                ramBytes = totalRamBytes(),
            )
            Log.i(TAG, "probed $caps")
            measured = caps
            // ⚠ A MEASURED pack arch outranks a learned fallback: 2.50 cannot drive a v85 DSP at all, so the v81
            // fallback (a guess about a part number) only ever made things worse once the chip had answered.
            if (caps.arch in PACK_ARCHES && packFallback != null) {
                packFallback = null
                context.getSharedPreferences(HMX_PREFS, Context.MODE_PRIVATE).edit().remove(PACK_FALLBACK_KEY).apply()
                Log.w(TAG, "measured v${caps.arch}: the v$PACK_FALLBACK_ARCH fallback is dropped")
            }
            caps
        } catch (e: Throwable) {
            Log.w(TAG, "device probe failed", e)
            null
        }
    }
}
