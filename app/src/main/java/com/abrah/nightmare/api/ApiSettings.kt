package com.abrah.nightmare.api

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ⭐ The API's two settings: on/off (off by default) and its token (`docs/AGENT-API.md` §5).
 *
 * ⚠ The token is shown in Settings for as long as the person wants it — a LAN token that
 * can be regenerated, not a password. ⚠ Compared in constant time ([matches]).
 */
object ApiSettings {
    private const val FILE = "nightmare_api"
    private const val KEY_ON = "on"
    private const val KEY_TOKEN = "token"

    /** ⚠ Not 8188 (ComfyUI) or 7860 (A1111): a client written for those must not think it found one. */
    const val PORT = 8820

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    /** The token, made on first use. */
    fun token(ctx: Context): String =
        prefs(ctx).getString(KEY_TOKEN, null) ?: regenerate(ctx)

    fun regenerate(ctx: Context): String {
        val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = bytes.joinToString("") { "%02x".format(it) }
        prefs(ctx).edit().putString(KEY_TOKEN, t).apply()
        return t
    }

    /** ⭐ `Authorization: Bearer <token>`, or `?token=` for a picture opened in a browser. */
    fun matches(expected: String, header: String?, query: String?): Boolean {
        val given = header?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() } ?: query ?: return false
        return MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
