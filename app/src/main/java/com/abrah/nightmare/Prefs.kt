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
}
