package com.abrah.nightmare

import android.content.Context

/**
 * ⭐⭐⭐ **The mask editor's checkboxes remember their last state** — the
 * user's ask, 2026-09-23: *"remember checkbox states for mask editor so its easy
 * for user not having to toggle it everytime"*.
 *
 * Enable tap to select, Enable auto mask and Enable Add Objects: whatever was
 * last ticked or unticked becomes the DEFAULT those widgets declare, so a new
 * node — and `addNode` writes every default in — starts the way the person
 * last left one. ⚠ A node that already holds a value keeps it: a saved flow is
 * not rewritten by a tick made in another.
 *
 * ⚠ Plain volatile state, loaded once at start: the widget lists are built on
 * demand from any thread, and the JVM suite sees the defaults (all off).
 */
object MaskDefaults {
    private const val PREFS = "mask_defaults"

    /** The three checkboxes this remembers. */
    val NAMES = setOf(SdSampler.TAP_SELECT, SdSampler.PICK_SELECT, AddObjects.ENABLE)

    @Volatile
    private var values: Map<String, Boolean> = emptyMap()

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        values = NAMES.associateWith { p.getBoolean(it, false) }
    }

    /** The default a widget named [name] declares: the last state set, else off. */
    fun of(name: String): String = (values[name] ?: false).toString()

    fun set(context: Context, name: String, on: Boolean) {
        if (name !in NAMES) return
        values = values + (name to on)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(name, on).apply()
    }
}
