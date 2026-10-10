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
) : ChatModel {

    override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
        if (tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        val reply = JSONObject(request("POST", "/chat/completions", body.toString()))
        val choice = reply.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmError("the provider sent no answer: ${reply.toString().take(300)}")
        return choice.optJSONObject("message") ?: throw LlmError("the provider's answer has no message")
    }

    /** ⭐ The provider's model ids, sorted — what the setup card offers. */
    fun models(): List<String> {
        val reply = JSONObject(request("GET", "/models", null))
        val data = reply.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotBlank() } }.sorted()
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
        /** The provider's own error sentence, when its body has one. */
        fun providerSays(body: String): String? = runCatching {
            val o = JSONObject(body)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
