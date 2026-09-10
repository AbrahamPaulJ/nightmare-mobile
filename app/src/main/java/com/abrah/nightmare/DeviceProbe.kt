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
    private val SOC_TO_ARCH = mapOf(
        "SM8350" to 68,                     // 888 / 888+
        "SM8450" to 69, "SM8475" to 69,     // 8 Gen 1 / 8+ Gen 1
        "SM8550" to 73, "SM8550P" to 73,    // 8 Gen 2
        "QCS8550" to 73, "QCM8550" to 73,
        "SM8650" to 75, "SM8650P" to 75,    // 8 Gen 3
        "SM8750" to 79, "SM8750P" to 79,    // 8 Elite
        "SM8850" to 81, "SM8850P" to 81,    // 8 Elite Gen 5
        // ⭐ MEASURED on a Xiaomi 25053PC47G through `--device_info`, not read
        // off a spec sheet: v73 and 8 MB. It is the newest chip in the table and
        // the one that most looks like it should be higher.
        "SM8735" to 73,                     // 8s Gen 4
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
    ) {
        /** ⚠ The APK has to carry this arch's Skel, or NPU init fails whatever the model. */
        val staged: Boolean get() = arch in STAGED_ARCHES
    }

    /** Set once [measure] succeeds. ⚠ Volatile: written on IO, read on main. */
    @Volatile
    var measured: Caps? = null
        private set

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
            arch = SOC_TO_ARCH[soc] ?: FLOOR_ARCH,
            vtcmMb = if (soc in SOC_HAS_8MB_VTCM) 8 else FLOOR_VTCM_MB,
            measured = false,
            soc = soc,
        )
    }

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
        val arch = SOC_TO_ARCH[soc()] ?: measured?.arch ?: return all
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
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()

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
            )
            Log.i(TAG, "probed $caps")
            measured = caps
            caps
        } catch (e: Throwable) {
            Log.w(TAG, "device probe failed", e)
            null
        }
    }
}
