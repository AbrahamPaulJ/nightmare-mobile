package com.abrah.nightmare.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ⭐ Which OpenRouter models the chat's model list offers (`LlmClient.fitsChat`). The entries
 * copy the shape of OpenRouter's `/models` (2026-10-12): tool calling, context, expiry, and
 * whether one turn fits the key's credits.
 */
@RunWith(RobolectricTestRunner::class)
class ChatFitTest {

    private val today = "2026-10-12"

    private fun model(
        id: String = "vendor/model",
        tools: Boolean = true,
        context: Int = 262_144,
        prompt: String = "0.000001",
        completion: String = "0.000003",
        expires: String? = null,
        limits: JSONObject? = null,
    ) = JSONObject()
        .put("id", id)
        .put("context_length", context)
        .put("architecture", JSONObject().put("output_modalities", JSONArray().put("text")))
        .put("pricing", JSONObject().put("prompt", prompt).put("completion", completion))
        .put("supported_parameters", JSONArray().put("max_tokens").apply { if (tools) put("tools") })
        .put("expiration_date", expires ?: JSONObject.NULL)
        .put("per_request_limits", limits ?: JSONObject.NULL)

    @Test
    fun aModelWithoutToolCallingIsHidden() {
        assertFalse(LlmClient.fitsChat(model(tools = false), null, today))
        assertTrue(LlmClient.fitsChat(model(), null, today))
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
    fun withNoCreditsOnlyFreeModelsFit() {
        assertTrue(LlmClient.fitsChat(model(id = "google/gemma-4-31b-it:free", prompt = "0", completion = "0"), 0.0, today))
        assertFalse(LlmClient.fitsChat(model(), 0.0, today))
    }

    @Test
    fun aSmallBalanceKeepsTheCheapModelsAndDropsTheDearOnes() {
        // One turn = 24k prompt + 8k reply: $0.048 at $1/$3 per million, $0.60 at $15/$30.
        assertTrue(LlmClient.fitsChat(model(), 0.10, today))
        assertFalse(LlmClient.fitsChat(model(prompt = "0.000015", completion = "0.00003"), 0.10, today))
    }

    @Test
    fun anUnknownBalanceChecksNothingButARoutersVariablePriceNeedsOne() {
        assertTrue(LlmClient.fitsChat(model(prompt = "0.000015", completion = "0.00003"), null, today))
        assertTrue(LlmClient.fitsChat(model(id = "openrouter/auto-beta", prompt = "-1", completion = "-1"), null, today))
        assertFalse(LlmClient.fitsChat(model(id = "openrouter/auto-beta", prompt = "-1", completion = "-1"), 5.0, today))
    }

    @Test
    fun theAccountsPerRequestLimitsAreHonoured() {
        val tight = JSONObject().put("prompt_tokens", 200_000).put("completion_tokens", 2_000)
        assertFalse(LlmClient.fitsChat(model(limits = tight), null, today))
        val roomy = JSONObject().put("prompt_tokens", 200_000).put("completion_tokens", 50_000)
        assertTrue(LlmClient.fitsChat(model(limits = roomy), null, today))
    }
}
