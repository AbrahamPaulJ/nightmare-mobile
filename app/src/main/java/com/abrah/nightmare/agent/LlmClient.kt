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
        if (!capReply) return send(messages, tools, null)
        return try {
            send(messages, tools, REPLY_TOKENS)
        } catch (e: LlmError) {
            // ⭐ "You requested up to 8192 tokens, but can only afford 2471": ask for what the credits
            // allow instead of failing (the user's account, 2026-10-12). Too little to answer → the error.
            val afford = affordable(e.message.orEmpty()) ?: throw e
            if (afford < MIN_REPLY_TOKENS) throw e
            send(messages, tools, afford)
        }
    }

    private fun send(messages: JSONArray, tools: JSONArray, maxTokens: Int?): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
        maxTokens?.let { body.put("max_tokens", it) }
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
        val all = (0 until data.length()).mapNotNull { data.optJSONObject(it) }
        val budget = if (chatOnly) runCatching { perRequestBudget(all) }.getOrNull() else null
        val today = java.time.LocalDate.now().toString()
        return all.filter { !chatOnly || fitsChat(it, budget, today) }
            .mapNotNull { it.optString("id").takeIf { s -> s.isNotBlank() } }.sorted()
    }

    /**
     * ⭐⭐ The dollars ONE request may cost on this key, measured, not read: neither `/credits` nor
     * `/key` says it. Measured 2026-10-12 on the user's account (no credits bought, usage above
     * zero): OpenRouter still let each request cost up to $0.0206, the SAME on every model, and its
     * refusal states it: "can only afford N" × the model's completion price. So: ask a paid model
     * for its whole output limit; the refusal costs nothing and gives N. Not refused = a key with
     * real credits (it answered "OK" for a fraction of a cent): null, no price check, as for any
     * probe that says nothing usable.
     */
    private fun perRequestBudget(all: List<JSONObject>): Double? {
        val probe = all.firstOrNull { m ->
            val cp = m.optJSONObject("pricing")?.optString("completion")?.toDoubleOrNull() ?: 0.0
            cp > 0 && !m.optString("id").endsWith(":batch") &&
                (m.optJSONObject("top_provider")?.optInt("max_completion_tokens", 0) ?: 0) >= 32_000
        } ?: return null
        val cp = probe.getJSONObject("pricing").getString("completion").toDouble()
        val maxOut = probe.getJSONObject("top_provider").getInt("max_completion_tokens")
        val body = JSONObject().put("model", probe.getString("id")).put("max_tokens", maxOut)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply with OK.")))
        return try {
            request("POST", "/chat/completions", body.toString())
            null
        } catch (e: LlmError) {
            affordable(e.message.orEmpty())?.let { it * cp }
        }
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

        /** ⭐ The least reply worth asking for: a tool call or two, or a short answer. */
        const val MIN_REPLY_TOKENS = 1_024

        /**
         * ⭐ Context the chat needs: its instructions and tool list are ~2.5k tokens, and every
         * `get_canvas` answer stays in the history, so a few turns pass 16k.
         */
        const val CHAT_MIN_CONTEXT = 32_768

        /** A few turns of conversation, what [fitsChat] costs a model call's prompt at. */
        const val TURN_PROMPT_TOKENS = 8_000

        private val AFFORD = Regex("can only afford (\\d+)")

        /** ⭐ The N in OpenRouter's "can only afford N" refusal, or null. */
        fun affordable(error: String): Int? = AFFORD.find(error)?.groupValues?.get(1)?.toIntOrNull()

        /**
         * ⚠ Ids that refuse the chat whatever the key, seen in the probe of 2026-10-12: free models
         * "only available on agentic harnesses" (HTTP 403).
         */
        private val REFUSES_CHAT = setOf("thinkingmachines/inkling:free", "thinkingmachines/inkling-small:free")

        /**
         * ⭐⭐ Whether the chat can run on OpenRouter model [m] (one `/models` entry). The chat's real
         * first request went to all 390 tool models on the user's key (2026-10-12) and every refusal
         * became a rule: no tool calling; a `:batch` id ("cannot be used with the chat/completions
         * endpoint", 76 of them); image or video output ("requires at least $1.00 in balance");
         * [REFUSES_CHAT]; a context the conversation outgrows; an expired id; and the credits: a turn
         * ([TURN_PROMPT_TOKENS] in, [MIN_REPLY_TOKENS] out) must cost no more than [budget], the
         * dollars one request may cost (null = unknown, not checked). Free models always fit; a
         * router's variable price (`-1`) only when the budget is unknown.
         */
        fun fitsChat(m: JSONObject, budget: Double?, today: String): Boolean {
            val id = m.optString("id")
            if (id.endsWith(":batch") || id in REFUSES_CHAT) return false
            val params = m.optJSONArray("supported_parameters") ?: return false
            if ((0 until params.length()).none { params.optString(it) == "tools" }) return false
            val out = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
            val outs = out?.let { o -> (0 until o.length()).map { o.optString(it) } }.orEmpty()
            if (out != null && "text" !in outs) return false
            if ((budget ?: 0.0) < 1.0 && ("image" in outs || "video" in outs)) return false
            if (m.optInt("context_length", 0) < CHAT_MIN_CONTEXT) return false
            val expires = m.optString("expiration_date").takeIf { !m.isNull("expiration_date") && it.isNotBlank() }
            if (expires != null && expires < today) return false
            val price = m.optJSONObject("pricing")
            fun p(k: String) = price?.optString(k)?.toDoubleOrNull() ?: 0.0
            if (p("prompt") == 0.0 && p("completion") == 0.0 && p("request") == 0.0) return true
            if (budget == null) return true
            if (p("prompt") < 0 || p("completion") < 0) return false
            return TURN_PROMPT_TOKENS * p("prompt") + MIN_REPLY_TOKENS * p("completion") + p("request") <= budget
        }

        /** The provider's own error sentence, when its body has one. */
        fun providerSays(body: String): String? = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
