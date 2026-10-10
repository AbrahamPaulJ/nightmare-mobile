package com.abrah.nightmare.agent

import org.json.JSONArray
import org.json.JSONObject

/** ⭐ What the chat shows, one line or picture at a time. */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem
    /** A tool the agent used, in a few words; [ok] false = it was refused or failed. */
    data class Tool(val name: String, val summary: String, val ok: Boolean) : ChatItem
    /** A picture a render made, on disk. */
    data class Picture(val path: String) : ChatItem
    data class Error(val text: String) : ChatItem
    /** ⭐ A question with buttons; [answer] is set once tapped (`AgentSession.answer`). */
    data class Ask(val id: Int, val question: String, val options: List<String>, val answer: String? = null) : ChatItem
    /** A finished run's log, kept in the chat (the live one is drawn from the canvas's own). */
    data class Log(val lines: List<String>) : ChatItem
}

/**
 * What a tool call gave back: [json] for the model, [pictures] for the chat.
 * ⚠⚠ No picture is ever sent TO the model (the user, 2026-10-10): `view_result` sent each finished
 * render as an image, and a text-only model's provider answered "No endpoints found that support
 * input image (HTTP 404)" — for that turn and every later one, the image staying in the history.
 */
class ToolResult(
    val json: JSONObject,
    val pictures: List<String> = emptyList(),
    /** A run's log, for the chat. */
    val log: List<String> = emptyList(),
) {
    val ok get() = !json.has("error")
}

/** ⭐ The agent's tools — their schemas and how to call one. `AgentTools` is the real one. */
interface ToolBox {
    val specs: JSONArray
    fun call(name: String, args: JSONObject): ToolResult
    /** A few words for the chat's tool line. */
    fun summary(name: String, args: JSONObject): String = name
}

/**
 * ⭐⭐⭐ **The agent loop** (`docs/AGENT-API.md` §4): the person's message, then the model and
 * the tools in turn until the model answers without calling one — at most [MAX_STEPS] calls
 * of the model per message, so a model stuck re-trying cannot run renders forever.
 *
 * ⚠ The whole conversation is kept here and sent every turn ([history]); tool answers go back
 * as `tool` messages, and a picture the model asked to SEE as a `user` message with an
 * `image_url` part (an OpenAI tool message carries no image).
 * ⚠ Blocking — the model and the tools both are. [cancelled] is checked between steps.
 */
class Agent(private val model: ChatModel, private val tools: ToolBox, system: String) {

    val history = JSONArray().put(JSONObject().put("role", "system").put("content", system))

    fun send(text: String, emit: (ChatItem) -> Unit, cancelled: () -> Boolean = { false }) {
        history.put(JSONObject().put("role", "user").put("content", text))
        repeat(MAX_STEPS) {
            if (cancelled()) return
            val msg = try {
                model.complete(history, tools.specs)
            } catch (e: LlmError) {
                emit(ChatItem.Error(e.message.orEmpty()))
                return
            }
            val content = msg.optString("content").takeIf { !msg.isNull("content") && it.isNotBlank() }
            val calls = msg.optJSONArray("tool_calls")?.takeIf { it.length() > 0 }
            // ⚠ Echoed back as the provider sent it, minus fields some providers refuse on input.
            history.put(
                JSONObject().put("role", "assistant").put("content", content ?: JSONObject.NULL)
                    .apply { calls?.let { put("tool_calls", it) } },
            )
            content?.let { emit(ChatItem.Assistant(it)) }
            if (calls == null) return
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrNull()
                val result = if (args == null) {
                    ToolResult(JSONObject().put("error", "the arguments were not JSON"))
                } else if (cancelled()) {
                    ToolResult(JSONObject().put("error", "cancelled by the person"))
                } else {
                    try {
                        tools.call(name, args)
                    } catch (e: Exception) {
                        ToolResult(JSONObject().put("error", e.message ?: e.javaClass.simpleName))
                    }
                }
                emit(ChatItem.Tool(name, args?.let { tools.summary(name, it) } ?: name, result.ok))
                if (result.log.isNotEmpty()) emit(ChatItem.Log(result.log))
                result.pictures.forEach { emit(ChatItem.Picture(it)) }
                history.put(
                    JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                        .put("content", result.json.toString()),
                )
            }
        }
        emit(ChatItem.Error("stopped after $MAX_STEPS steps — say \"go on\" to let it continue"))
    }

    companion object {
        const val MAX_STEPS = 12
    }
}
