package com.abrah.nightmare.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐⭐ The agent loop against a scripted model (`docs/AGENT-API.md` §4): tool calls go out and
 * their answers come back as `tool` messages, a picture to SEE as a `user` image part, the loop
 * stops when the model answers in words — and the one content rule the app holds itself.
 */
@RunWith(RobolectricTestRunner::class)
class AgentTest {

    private fun toolCall(id: String, name: String, args: String) = JSONObject()
        .put("role", "assistant")
        .put("content", JSONObject.NULL)
        .put("tool_calls", JSONArray().put(
            JSONObject().put("id", id).put("type", "function")
                .put("function", JSONObject().put("name", name).put("arguments", args)),
        ))

    private fun words(text: String) = JSONObject().put("role", "assistant").put("content", text)

    private class Script(vararg replies: JSONObject) : ChatModel {
        val seen = mutableListOf<JSONArray>()
        private val left = ArrayDeque(replies.toList())
        override fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
            seen += JSONArray(messages.toString())
            return left.removeFirst()
        }
    }

    private class Tools : ToolBox {
        val calls = mutableListOf<Pair<String, String>>()
        override val specs = JSONArray()
        override fun call(name: String, args: JSONObject): ToolResult {
            calls += name to args.toString()
            return when (name) {
                "run_flow" -> ToolResult(JSONObject().put("results", JSONArray().put("/results/r1/output.png")), pictures = listOf("/p/output.png"))
                else -> ToolResult(JSONObject().put("error", "no tool"))
            }
        }
    }

    @Test
    fun aToolCallRunsAndItsAnswerGoesBack() {
        val model = Script(toolCall("c1", "run_flow", """{"flow":"txt2img","prompt":"a fox"}"""), words("Here is your fox."))
        val tools = Tools()
        val shown = mutableListOf<ChatItem>()
        Agent(model, tools, "sys").send("a fox please", { shown += it })
        assertEquals(listOf("run_flow" to """{"flow":"txt2img","prompt":"a fox"}"""), tools.calls)
        assertEquals(
            listOf(ChatItem.Tool("run_flow", "run_flow", true), ChatItem.Picture("/p/output.png"), ChatItem.Assistant("Here is your fox.")),
            shown,
        )
        // The second turn carried the tool's answer, tied to its call.
        val last = model.seen[1].getJSONObject(model.seen[1].length() - 1)
        assertEquals("tool", last.getString("role"))
        assertEquals("c1", last.getString("tool_call_id"))
    }

    /** ⚠ No picture goes TO the model after a run — a text-only model's provider 404s on one. */
    @Test
    fun aRunSendsNoPictureToTheModel() {
        val model = Script(toolCall("c1", "run_flow", """{"flow":"txt2img","prompt":"a fox"}"""), words("Done."))
        Agent(model, Tools(), "sys").send("a fox", {})
        assertTrue(!model.seen[1].toString().contains("image_url"))
    }

    @Test
    fun badArgumentsAreAnErrorForTheModelNotACrash() {
        val model = Script(toolCall("c1", "run_flow", "{not json"), words("Sorry."))
        val shown = mutableListOf<ChatItem>()
        Agent(model, Tools(), "sys").send("x", { shown += it })
        assertEquals(ChatItem.Tool("run_flow", "run_flow", false), shown[0])
        assertTrue(model.seen[1].getJSONObject(model.seen[1].length() - 1).getString("content").contains("not JSON"))
    }

    @Test
    fun aModelThatNeverStopsIsStopped() {
        val model = Script(*Array(Agent.MAX_STEPS) { toolCall("c$it", "list_flows", "{}") })
        val shown = mutableListOf<ChatItem>()
        Agent(model, Tools(), "sys").send("x", { shown += it })
        assertTrue(shown.last() is ChatItem.Error)
    }

    @Test
    fun aProviderErrorIsSaidInTheChat() {
        val model = ChatModel { _, _ -> throw LlmError("insufficient credits (HTTP 402)") }
        val shown = mutableListOf<ChatItem>()
        Agent(model, Tools(), "sys").send("x", { shown += it })
        assertEquals(listOf(ChatItem.Error("insufficient credits (HTTP 402)")), shown)
    }

    @Test
    fun theProvidersOwnSentenceIsRead() {
        assertEquals("Invalid API key", LlmClient.providerSays("""{"error":{"message":"Invalid API key","code":401}}"""))
        assertNull(LlmClient.providerSays("<html>bad gateway</html>"))
    }

    @Test
    fun sexualContentWithMinorsIsRefusedAndNothingElseIs() {
        fun r(prompt: String) = AgentTools.refusal(JSONObject().put("flow", "txt2img").put("prompt", prompt))
        assertNotNull(r("nude loli"))
        assertNotNull(r("a 12 year old girl, naked"))
        assertNull(r("a nude woman on a beach, 25 years old"))
        // ⚠⚠ Adult content that merely sounds young is NOT the app's business (the user, 2026-10-08).
        assertNull(r("schoolgirl uniform, nsfw, 1girl, adult"))
        assertNull(r("young woman in lingerie, nsfw"))
        assertNull(r("teen titans cosplay, topless, 21 years old"))
        assertNull(r("a child flying a kite in a park"))
        assertNull(r("a knight in armour"))
        // ⚠ Read in params too, not only the prompt.
        assertNotNull(AgentTools.refusal(JSONObject().put("params", JSONObject().put("prompt", JSONObject().put("prompt", "underage, explicit")))))
    }
}
