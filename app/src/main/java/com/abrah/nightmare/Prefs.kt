package com.abrah.nightmare

import android.content.Context

/**
 * ⭐ The app's own settings, as opposed to the graph's.
 *
 * ⚠⚠ A separate store from `SelectedModel`, which is also a preference but is
 * a property of the WORK — a graph names its checkpoint, and opening someone
 * else's flow moves it. Theme is a property of the person, and nothing a
 * workflow does should ever change it.
 *
 * ⚠ Read once into a mutable state at start-up and written through, like
 * `SelectedModel`: a `SharedPreferences` read on every recomposition is a
 * disk touch in the draw path.
 */
object Prefs {

    private const val FILE = "nightmare.prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_MIRROR = "download_base"

    /**
     * ⚠⚠ THREE values, not a boolean. "Follow the system" is the default and
     * it is not expressible as on/off — a switch alone would silently pin the
     * app to whatever the phone happened to be when it was first opened, and
     * the user would have no way back to following.
     */
    enum class Theme { SYSTEM, DARK, LIGHT }

    /** ⚠ Backing state so Compose recomposes; loaded by [load] before first draw. */
    var theme: Theme = Theme.SYSTEM
        private set

    fun load(context: Context) {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME, null)
        // ⚠ An unknown value falls back rather than throwing. A preferences
        // file survives a downgrade, and a theme nobody recognises must not
        // stop the app opening.
        theme = Theme.entries.firstOrNull { it.name == raw } ?: Theme.SYSTEM
        downloadBase = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_MIRROR, null)?.takeIf { it.isNotBlank() } ?: HF_ORIGIN
    }

    fun setTheme(context: Context, value: Theme) {
        theme = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, value.name).apply()
    }

    // ---- download mirror -------------------------------------------------

    /**
     * ⭐⭐ **Where model files are fetched from.** Asked for 2026-09-20:
     * huggingface.co is unreachable from mainland China, and upstream
     * `local-dream` has offered a mirror since long before this fork
     * (`ui/screens/ModelListScreen.kt`, "Download source").
     *
     * ⚠⚠ **Ours is a REWRITE, not a base.** Upstream stores a base URL and
     * builds every model URL from it. This catalogue stores whole URLs across
     * eight constants in five files ([ModelCatalog.SD15_BASE_URL] and friends,
     * [Upscalers], [segment.Segmenter], [npu.VideoInstaller]), so rebasing them
     * all would mean touching every one and keeping them in step forever. ⇒
     * one substitution at the two places that actually open a connection
     * ([apply]), which cannot go out of step with a catalogue entry because it
     * never reads one.
     *
     * ⚠ Only `huggingface.co` is rewritten. The DiT engine comes from a
     * GitHub release tag and is deliberately left alone ([DitEngine]): a mirror
     * of Hugging Face says nothing about GitHub, and silently pointing a
     * release download at it would 404.
     */
    const val HF_ORIGIN = "https://huggingface.co/"

    /** ⚠ hf-mirror.com is the one upstream offers, so it is the one we offer. */
    const val HF_MIRROR = "https://hf-mirror.com/"

    /**
     * The chosen origin, always ending in `/`. [HF_ORIGIN] unless changed.
     * ⚠ A custom value is whatever the user typed, so it is normalised on the
     * way in rather than trusted on the way out.
     */
    var downloadBase: String = HF_ORIGIN
        private set

    fun setDownloadBase(context: Context, value: String) {
        downloadBase = normalise(value)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_MIRROR, downloadBase).apply()
    }

    /**
     * ⭐⭐⭐ The substitution itself — every download goes through this.
     *
     * ⚠⚠ Pure and total: an unknown host, an empty setting or the default
     * all return [url] unchanged, because a download that silently goes
     * nowhere is worse than one that ignores a preference.
     */
    fun apply(url: String): String =
        if (downloadBase == HF_ORIGIN || !url.startsWith(HF_ORIGIN)) url
        else downloadBase + url.removePrefix(HF_ORIGIN)

    /** ⚠ Trailing slash guaranteed; blank falls back to the default. */
    private fun normalise(value: String): String {
        val t = value.trim()
        if (t.isEmpty()) return HF_ORIGIN
        return if (t.endsWith("/")) t else "$t/"
    }

    // ---- low RAM: upstream local-dream's three Settings switches -----------

    /**
     * ⭐⭐ **SDXL low RAM, Anima low RAM, Anima sequential DiT** — upstream's own
     * Settings (`sdxl_lowram`, `anima_lowram`, `anima_seq_dit`, same keys). They
     * were forced per catalogue entry here until 2026-09-28, which put a 16 GB
     * phone in the slow mode with no live preview for no reason (`docs/DEVICES.md` §6c).
     *
     * ⚠ An app SETTING, never a node param: a node param is saved into shared
     * flows and would carry one phone's choice to another ([Residency]'s argument,
     * which is about nodes and does not reach this).
     *
     * ⭐ Unset means the DEFAULT, computed from this phone's RAM rather than stored:
     * the two low-RAM switches are on below [LOWRAM_BELOW_BYTES] — upstream's hint
     * says they are "required on devices with less than 16GB RAM", and upstream
     * defaults them on everywhere. ⚠ Sequential DiT stays OFF by default, as
     * upstream has it: its own hint says to enable it only when low RAM mode alone
     * still cannot run, and it is slower per step.
     *
     * ⚠ Read from storage at every LAUNCH ([lowRamFor]) rather than from a loaded
     * copy, because a headless `OpService` op launches backends without ever
     * running [load].
     */
    const val KEY_SDXL_LOWRAM = "sdxl_lowram"
    const val KEY_ANIMA_LOWRAM = "anima_lowram"
    const val KEY_ANIMA_SEQ_DIT = "anima_seq_dit"

    /**
     * ⚠ 13 GiB, between the classes rather than at "16": a 12 GB phone reports
     * ~10.9–11.4 GiB of `totalMem` and a 16 GB one ~14.5–15 GiB, the rest being
     * reserved before Android sees it.
     */
    const val LOWRAM_BELOW_BYTES = 13L shl 30

    data class LowRam(val sdxl: Boolean, val anima: Boolean, val animaSeqDit: Boolean)

    /** ⚠ Unknown RAM (0) answers ON — the safe side is the slow mode, never the kill. */
    fun lowRamDefault(totalRamBytes: Long): Boolean =
        totalRamBytes <= 0L || totalRamBytes < LOWRAM_BELOW_BYTES

    private fun totalRam(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            ?: return 0L
        return android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    }

    fun lowRam(context: Context): LowRam {
        val p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val def = lowRamDefault(totalRam(context))
        fun read(key: String, fallback: Boolean) =
            if (p.contains(key)) p.getBoolean(key, fallback) else fallback
        return LowRam(
            sdxl = read(KEY_SDXL_LOWRAM, def),
            anima = read(KEY_ANIMA_LOWRAM, def),
            animaSeqDit = read(KEY_ANIMA_SEQ_DIT, false),
        )
    }

    fun setLowRam(context: Context, key: String, on: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
    }

    /**
     * ⭐ Whether a launch of [spec] passes `--lowram`. SDXL and Anima follow the
     * switches; every other family keeps its catalogue entry's own answer
     * ([ModelSpec.lowram] — Z-Image's `te=disk`; Qwen is `all=disk` in the backend
     * whatever this says).
     */
    fun lowRamFor(context: Context, spec: ModelSpec): Boolean = when (spec.family) {
        Family.SDXL, Family.SDXL_SWAP -> lowRam(context).sdxl
        Family.ANIMA -> lowRam(context).anima
        else -> spec.lowram
    }
}
