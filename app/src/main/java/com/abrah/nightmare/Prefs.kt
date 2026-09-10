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
    }

    fun setTheme(context: Context, value: Theme) {
        theme = value
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, value.name).apply()
    }
}
