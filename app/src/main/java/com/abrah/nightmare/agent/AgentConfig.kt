package com.abrah.nightmare.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ⭐ The agent's provider, address, model, key and the person's own [instructions]
 * (`docs/AGENT-API.md` §5). [hasKey] only — the key itself is read by [key] when a client is
 * built, never held in UI state.
 */
data class AgentConfig(
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val hasKey: Boolean,
    /** ⭐ Added to the agent's own instructions — style, language, what to allow. */
    val instructions: String = "",
) {
    /** ⚠ A local server may take no key; a cloud provider always does. */
    val ready get() = baseUrl.isNotBlank() && model.isNotBlank() && (hasKey || provider == Provider.CUSTOM)

    companion object {
        private const val FILE = "nightmare_agent"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun load(ctx: Context): AgentConfig {
            val p = prefs(ctx)
            val provider = Provider.of(p.getString("provider", null))
            return AgentConfig(
                provider,
                p.getString("base", null)?.takeIf { it.isNotBlank() } ?: provider.baseUrl,
                p.getString("model", "").orEmpty(),
                p.contains("key"),
                p.getString("instructions", "").orEmpty(),
            )
        }

        /** [key] null keeps the stored one; blank deletes it. */
        fun save(ctx: Context, provider: Provider, baseUrl: String, model: String, key: String?, instructions: String) {
            val e = prefs(ctx).edit()
                .putString("provider", provider.name)
                .putString("base", if (provider == Provider.CUSTOM) baseUrl.trim() else provider.baseUrl)
                .putString("model", model.trim())
                .putString("instructions", instructions.trim())
            when {
                key == null -> Unit
                key.isBlank() -> e.remove("key")
                else -> e.putString("key", KeyVault.seal(key.trim()))
            }
            e.apply()
        }

        fun key(ctx: Context): String =
            prefs(ctx).getString("key", null)?.let { runCatching { KeyVault.open(it) }.getOrNull() }.orEmpty()

        // ---- named setups (the user's choice, 2026-10-08) --------------------------------

        /** ⭐ The saved setups' names, in the order saved. */
        fun profiles(ctx: Context): List<String> = profileArray(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }

        /** ⭐ The CURRENT setup saved under [name] — its sealed key copied, never decrypted. */
        fun saveProfile(ctx: Context, name: String) {
            val p = prefs(ctx)
            val entry = JSONObject()
                .put("name", name.trim())
                .put("provider", p.getString("provider", Provider.OPENROUTER.name))
                .put("base", p.getString("base", ""))
                .put("model", p.getString("model", ""))
                .put("instructions", p.getString("instructions", ""))
                .put("key", p.getString("key", null) ?: JSONObject.NULL)
            val kept = profileArray(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
                .filter { it.getString("name") != name.trim() }
            p.edit().putString("profiles", JSONArray(kept + entry).toString()).apply()
        }

        /** ⭐ Makes the setup [name] the current one. */
        fun useProfile(ctx: Context, name: String) {
            val a = profileArray(ctx)
            val o = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("name") == name } ?: return
            val e = prefs(ctx).edit()
                .putString("provider", o.optString("provider"))
                .putString("base", o.optString("base"))
                .putString("model", o.optString("model"))
                .putString("instructions", o.optString("instructions"))
            if (o.isNull("key")) e.remove("key") else e.putString("key", o.getString("key"))
            e.apply()
        }

        fun deleteProfile(ctx: Context, name: String) {
            val kept = profileArray(ctx).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.getString("name") != name }
            prefs(ctx).edit().putString("profiles", JSONArray(kept).toString()).apply()
        }

        private fun profileArray(ctx: Context): JSONArray =
            runCatching { JSONArray(prefs(ctx).getString("profiles", "[]")) }.getOrDefault(JSONArray())
    }
}

/** ⭐ What the setup card hands back on Save. [profileName] set = also kept as a named setup. */
data class SetupInput(
    val provider: Provider,
    val baseUrl: String,
    val model: String,
    val key: String?,
    val instructions: String,
    val profileName: String?,
)

/**
 * ⭐⭐ The provider key, sealed with an AES-GCM key that lives in the Android Keystore and never
 * leaves it — the prefs file holds only ciphertext (`docs/AGENT-API.md` §4).
 */
object KeyVault {
    private const val ALIAS = "nightmare-agent-key"
    private const val STORE = "AndroidKeyStore"

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return g.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        val sealed = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        val b = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 0, 12))
        return String(c.doFinal(b, 12, b.size - 12), Charsets.UTF_8)
    }
}
