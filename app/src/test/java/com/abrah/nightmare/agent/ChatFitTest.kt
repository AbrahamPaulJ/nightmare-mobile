package com.abrah.nightmare.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ Which OpenRouter models the chat's model list offers (`LlmClient.fitsChat`). The entries copy
 * OpenRouter's `/models` (2026-10-12), and each rule is a refusal seen when the chat's real first
 * request went to every tool model on the user's key.
 */
@RunWith(RobolectricTestRunner::class)
class ChatFitTest {

    private val today = "2026-10-12"

    /** The user's key on 2026-10-12: no credits bought, each request allowed up to $0.0206. */
    private val userBudget = 0.0206

    private fun model(
        id: String = "vendor/model",
        tools: Boolean = true,
        context: Int = 262_144,
        prompt: String = "0.000001",
        completion: String = "0.000003",
        expires: String? = null,
        outputs: List<String> = listOf("text"),
    ) = JSONObject()
        .put("id", id)
        .put("context_length", context)
        .put("architecture", JSONObject().put("output_modalities", JSONArray(outputs)))
        .put("pricing", JSONObject().put("prompt", prompt).put("completion", completion))
        .put("supported_parameters", JSONArray().put("max_tokens").apply { if (tools) put("tools") })
        .put("expiration_date", expires ?: JSONObject.NULL)

    @Test
    fun aModelWithoutToolCallingIsHidden() {
        assertFalse(LlmClient.fitsChat(model(tools = false), null, today))
        assertTrue(LlmClient.fitsChat(model(), null, today))
    }

    @Test
    fun aBatchIdIsHiddenItCannotChat() {
        // "openai/gpt-6-sol:batch cannot be used with the chat/completions endpoint"
        assertFalse(LlmClient.fitsChat(model(id = "openai/gpt-6-sol:batch"), null, today))
    }

    @Test
    fun theFreeModelsThatRefuseThisAppAreHidden() {
        // "inkling:free is only available on agentic harnesses" (HTTP 403)
        assertFalse(LlmClient.fitsChat(model(id = "thinkingmachines/inkling:free", prompt = "0", completion = "0"), null, today))
    }

    @Test
    fun imageOutputNeedsADollarOfBalance() {
        // "This request requires at least $1.00 in balance for image or video output"
        val banana = model(id = "google/gemini-nano-banana-2.1", outputs = listOf("text", "image"))
        assertFalse(LlmClient.fitsChat(banana, userBudget, today))
        assertTrue(LlmClient.fitsChat(banana, 5.0, today))
    }

    @Test
    fun aContextTheConversationOutgrowsIsHidden() {
        assertFalse(LlmClient.fitsChat(model(context = 16_385), null, today))   // gpt-3.5-turbo
        assertTrue(LlmClient.fitsChat(model(context = LlmClient.CHAT_MIN_CONTEXT), null, today))
    }

    @Test
    fun anExpiredModelIsHiddenAndOneExpiringLaterIsNot() {
        assertFalse(LlmClient.fitsChat(model(expires = "2026-10-11"), null, today))
        assertTrue(LlmClient.fitsChat(model(expires = "2026-10-31"), null, today))
    }

    @Test
    fun onTheUsersKeyTheModelsItCouldNotAffordAreHidden() {
        // Measured: claude-opus-5.5 could afford 1031 reply tokens, qwen3.8-max-prime 1719, gpt-6-luna 41262.
        val opus = model(id = "anthropic/claude-opus-5.5", prompt = "0.000005", completion = "0.00002")
        assertFalse(LlmClient.fitsChat(opus, userBudget, today))
        val luna = model(id = "openai/gpt-6-luna", prompt = "0.00000025", completion = "0.0000005")
        assertTrue(LlmClient.fitsChat(luna, userBudget, today))
    }

    @Test
    fun freeModelsFitEvenBelowZero() {
        val free = model(id = "google/gemma-4-31b-it:free", prompt = "0", completion = "0")
        assertTrue(LlmClient.fitsChat(free, -0.05, today))
        assertTrue(LlmClient.fitsChat(free, 0.0, today))
        assertFalse(LlmClient.fitsChat(model(), 0.0, today))
    }

    @Test
    fun anUnknownBudgetChecksNoPriceButARoutersVariablePriceNeedsOne() {
        assertTrue(LlmClient.fitsChat(model(prompt = "0.000015", completion = "0.00003"), null, today))
        assertTrue(LlmClient.fitsChat(model(id = "openrouter/auto-beta", prompt = "-1", completion = "-1"), null, today))
        assertFalse(LlmClient.fitsChat(model(id = "openrouter/auto-beta", prompt = "-1", completion = "-1"), 5.0, today))
    }

    @Test
    fun theRefusalSaysHowMuchReplyTheCreditsAllow() {
        val refusal = "This request requires more credits, or fewer max_tokens. You requested up to 8192 tokens, " +
            "but can only afford 2471. To increase, visit https://openrouter.ai/workspaces/default/keys (HTTP 402)"
        assertEquals(2471, LlmClient.affordable(refusal))
        assertNull(LlmClient.affordable("No endpoints found that support tool use (HTTP 404)"))
    }
}
