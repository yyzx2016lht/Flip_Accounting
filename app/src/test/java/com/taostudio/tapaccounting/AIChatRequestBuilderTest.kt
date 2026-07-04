package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AIChatRequestBuilderTest {

    @Test
    fun multiTurnRequestKeepsConversationOrder() {
        val request = buildMultiTurnChatRequest(
            model = "test-model",
            temperature = 0.7,
            systemPrompt = "system",
            historyTurns = listOf(
                ChatTurn("user", "first"),
                ChatTurn("assistant", "reply")
            ),
            userText = "second"
        )

        val messages = request.getAsJsonArray("messages")
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map {
            it.asJsonObject.get("role").asString
        })
        assertEquals("second", messages.last().asJsonObject.get("content").asString)
    }

    @Test
    fun kimiK25UsesCompatibleThinkingAndTemperature() {
        val request = buildTextChatRequest(
            model = "kimi-k2.5",
            temperature = 0.3,
            userText = "hello",
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_KIMI, request)

        assertFalse(adapted.has("enable_thinking"))
        assertEquals("disabled", adapted.getAsJsonObject("thinking").get("type").asString)
        assertEquals(0.3, adapted.get("temperature").asDouble, 0.0)
    }

    @Test
    fun deepSeekStreamRequestsUsageForCacheLogging() {
        val request = buildTextChatRequest(
            model = "deepseek-v4-flash",
            temperature = 0.3,
            userText = "hello",
            stream = true,
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_DEEPSEEK, request)

        assertEquals("disabled", adapted.getAsJsonObject("thinking").get("type").asString)
        assertTrue(adapted.getAsJsonObject("stream_options").get("include_usage").asBoolean)
    }

    @Test
    fun mimoAsrDoesNotReceiveTextModelThinkingOptions() {
        val request = buildTextChatRequest(
            model = "mimo-v2.5-asr",
            temperature = 0.1,
            userText = "transcribe",
            enableThinking = false
        )

        val adapted = adaptChatRequestForProvider(AiProviderRegistry.PROVIDER_MIMO, request)

        assertFalse(adapted.has("enable_thinking"))
        assertFalse(adapted.has("thinking"))
    }
}
