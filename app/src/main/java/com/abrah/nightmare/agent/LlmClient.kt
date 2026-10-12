package com.abrah.nightmare.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** ⭐ One chat turn: [messages] in, the assistant's message out (`content`, `tool_calls`). */
fun interface ChatModel {
    fun complete(messages: JSONArray, tools: JSONArray): JSONObject
}

/** A provider refusal or a network failure, said as a sentence. */
class LlmError(message: String) : Exception(message)

/**
 * ⭐⭐ A provider the agent talks to — every one speaks the OpenAI chat-completions shape with
 * tool calling (`docs/AGENT-API.md` §4, the user's call 2026-10-08). [CUSTOM] is any other
 * address: a local `llama-server`, LM Studio, a proxy.
 */
enum class Provider(val label: String, val baseUrl: String, val keyPage: String?) {
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys"),
    OPENAI("OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "https://platform.deepseek.com/api_keys"),
    XAI("xAI", "https://api.x.ai/v1", "https://console.x.ai"),
    GROQ("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys"),
    CUSTOM("Other (OpenAI-compatible)", "", null),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: OPENROUTER
    }
}

/**
 * ⭐⭐ The OpenAI-compatible client: `POST {base}/chat/completions` with `tools`, and
 * `GET {base}/models` for the model list. Blocking; call off the main thread.
 * ⚠ The key goes in the header and nowhere else — never a log line, never an error report.
 */
class LlmClient(
    private val baseUrl: String,
    private val key: String,
    private val model: String,
    /**
     * ⭐ OpenRouter only: a reply capped at [REPLY_TOKENS]. Without `max_tokens` OpenRouter reserves
     * the model's WHOLE output limit (64k and more) against the key's credits, and a small balance
     * answers "requires more credits, or fewer max_tokens" on every model but the free ones.
     * ⚠ Not sent elsewhere: OpenAI's newer models refuse `max_tokens` (`max_completion_tokens`).
     */
    private val capReply: Boolean = false,
) : ChatModel {

    override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
        if (capReply) body.put("max_tokens", REPLY_TOKENS)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val reply = JSONObject(request("POST", "/chat/completions", body.toString()))
        val choice = reply.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmError("the provider sent no answer: ${reply.toString().take(300)}")
        return choice.optJSONObject("message") ?: throw LlmError("the provider's answer has no message")
    }

    /**
     * ⭐ The provider's model ids, sorted — what the setup card offers. [chatOnly] (OpenRouter, the
     * user's call 2026-10-12) keeps only the models the chat can run on THIS key ([fitsChat]); a
     * name typed by hand is still used as it is.
     */
    fun models(chatOnly: Boolean = false): List<String> {
        val reply = JSONObject(request("GET", "/models", null))
        val data = reply.optJSONArray("data") ?: return emptyList()
        val balance = if (chatOnly) balance() else null
        val today = java.time.LocalDate.now().toString()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            .filter { !chatOnly || fitsChat(it, balance, today) }
            .mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }.sorted()
    }

    /**
     * ⭐ What this key can still spend, in dollars, or null when the provider will not say:
     * OpenRouter's `/credits` (bought minus used) and `/key` (the key's own limit; `is_free_tier` =
     * never bought any, so nothing to spend). The smaller wins.
     */
    private fun balance(): Double? {
        val credits = runCatching { JSONObject(request("GET", "/credits", null)).optJSONObject("data") }.getOrNull()
        val keyInfo = runCatching { JSONObject(request("GET", "/key", null)).optJSONObject("data") }.getOrNull()
        val bought = credits?.takeIf { it.has("total_credits") }?.let { it.optDouble("total_credits") - it.optDouble("total_usage", 0.0) }
        val keyLeft = keyInfo?.takeIf { it.has("limit_remaining") && !it.isNull("limit_remaining") }?.optDouble("limit_remaining")
        val never = 0.0.takeIf { bought == null && keyInfo?.optBoolean("is_free_tier") == true }
        return listOfNotNull(bought, keyLeft, never).minOrNull()
    }

    private fun request(method: String, path: String, body: String?): String {
        val c = try {
            (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            throw LlmError("bad provider address: $baseUrl")
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 20_000
            c.readTimeout = 180_000
            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Accept", "application/json")
            // ⭐ OpenRouter's attribution headers; every other provider ignores them.
            c.setRequestProperty("HTTP-Referer", "https://github.com/AbrahamPaulJ/nightmare-mobile")
            c.setRequestProperty("X-Title", "Nightmare")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code >= 400) throw LlmError("${providerSays(text) ?: "HTTP $code"} (HTTP $code)")
            return text
        } catch (e: IOException) {
            throw LlmError("could not reach $baseUrl: ${e.message}")
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** ⭐ The cap [capReply] sends; tool calls are short, but a reasoning model thinks inside it. */
        const val REPLY_TOKENS = 8_192

        /**
         * ⭐ Context the chat needs: its instructions and tool list are ~2.5k tokens, and every
         * `get_canvas` answer stays in the history, so a few turns pass 16k.
         */
        const val CHAT_MIN_CONTEXT = 32_768

        /** What one model call is costed at for [fitsChat]: a long conversation's prompt + [REPLY_TOKENS]. */
        private const val TURN_PROMPT_TOKENS = 24_000

        /**
         * ⭐⭐ Whether the chat can run on OpenRouter model [m] (one `/models` entry) — what its
         * refusals were, read back as rules: no tool calling ("No endpoints found that support
         * tool use"), a context the conversation outgrows, an expired id, and the credits ("requires
         * more credits, or fewer max_tokens"): one turn must cost no more than [balance] (null =
         * unknown, not checked). Free models always fit; a router's variable price (`-1`) fits only
         * when the balance is unknown.
         */
        fun fitsChat(m: JSONObject, balance: Double?, today: String): Boolean {
            val params = m.optJSONArray("supported_parameters") ?: return false
            if ((0 until params.length()).none { params.optString(it) == "tools" }) return false
            val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            if (out != null && (0 until out.length()).none { out.optString(it) == "text" }) return false
            if (m.optInt("context_length", 0) < CHAT_MIN_CONTEXT) return false
            val expires = m.optString("expiration_date").takeIf { !m.isNull("expiration_date") && it.isNotBlank() }
            if (expires != null && expires < today) return false
            // ⚠ Set on an authenticated `/models`: the tokens this account's credits allow per request.
            m.optJSONObject("per_request_limits")?.let { lim ->
                if (lim.optDouble("prompt_tokens", Double.MAX_VALUE) < TURN_PROMPT_TOKENS) return false
                if (lim.optDouble("completion_tokens", Double.MAX_VALUE) < REPLY_TOKENS) return false
            }
            if (balance == null) return true
            val price = m.optJSONObject("pricing")
            fun p(k: String) = price?.optString(k)?.toDoubleOrNull() ?: 0.0
            if (p("prompt") < 0 || p("completion") < 0) return false
            return TURN_PROMPT_TOKENS * p("prompt") + REPLY_TOKENS * p("completion") + p("request") <= balance
        }

        /** The provider's own error sentence, when its body has one. */
        fun providerSays(body: String): String? = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
