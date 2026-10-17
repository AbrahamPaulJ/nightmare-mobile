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
    private const val KEY_CORS = "cors"

    /** ⚠ Not 8188 (ComfyUI) or 7860 (A1111): a client written for those must not think it found one. */
    const val PORT = 8820

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    /**
     * ⭐ "Allow browser pages" (GitHub #54, 1.6.193; off by default): a web page may call the API. [CORS_HEADERS] on
     * every answer and an `OPTIONS` preflight answered before the token check ([NightmareApi.corsPreflight]).
     * ⚠ The token stays the only gate: a page needs it exactly as a script does, and `*` sends no cookies.
     */
    fun cors(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CORS, false)

    fun setCors(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CORS, on).apply()
    }

    /**
     * ⭐ What a browser needs to read the answer and to send `Authorization` and a JSON body. ⚠ The private-network
     * line is Chrome's, for a page served from the internet calling a phone on the LAN.
     */
    val CORS_HEADERS = linkedMapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
        "Access-Control-Allow-Headers" to "Authorization, Content-Type",
        "Access-Control-Allow-Private-Network" to "true",
        "Access-Control-Max-Age" to "600",
    )

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
