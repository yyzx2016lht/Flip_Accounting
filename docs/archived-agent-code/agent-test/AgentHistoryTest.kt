package com.taostudio.tapaccounting.chat.agent

import com.taostudio.tapaccounting.ChatMessagePipeline
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that directly call production code for:
 * - ChatConversationMode classification (SQL GLOB consistency)
 * - ChatMessagePipeline error classification
 * - ChatMessagePipeline history truncation (round-aware, oversized handling)
 * - AgentLlmMessageBuilder message construction (userText dedup, history threading)
 * - ChatConversationMode ID generation and mode consistency
 */
class AgentHistoryTest {

    // ===== ChatConversationMode.modeOf — SQL GLOB consistency =====

    @Test
    fun `modeOf agent prefix returns AGENT`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf("agent_1234_abc"))
    }

    @Test
    fun `modeOf conv prefix returns ACCOUNTING`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("conv_1234_abc"))
    }

    @Test
    fun `modeOf legacy returns ACCOUNTING`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("legacy"))
    }

    @Test
    fun `modeOf empty string returns ACCOUNTING`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf(""))
    }

    @Test
    fun `modeOf random string returns ACCOUNTING`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("some_random_id"))
    }

    @Test
    fun `modeOf agentX (no underscore) returns ACCOUNTING - GLOB consistency`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("agentX123"))
    }

    @Test
    fun `modeOf agent- (dash not underscore) returns ACCOUNTING - GLOB consistency`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("agent-123"))
    }

    @Test
    fun `modeOf agentA (uppercase A) returns ACCOUNTING - GLOB consistency`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("agentA123"))
    }

    @Test
    fun `modeOf agent_ (with underscore) returns AGENT - GLOB consistency`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf("agent_123"))
    }

    @Test
    fun `modeOf agent_ with UUID returns AGENT`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf("agent_1718000000000_a1b2c3d4"))
    }

    // ===== ChatConversationMode.belongsTo =====

    @Test
    fun `belongsTo correctly classifies agent conversations`() {
        assertTrue(ChatConversationMode.belongsTo("agent_1234_abc", ChatConversationMode.AGENT))
        assertFalse(ChatConversationMode.belongsTo("agent_1234_abc", ChatConversationMode.ACCOUNTING))
    }

    @Test
    fun `belongsTo correctly classifies accounting conversations`() {
        assertTrue(ChatConversationMode.belongsTo("conv_1234_abc", ChatConversationMode.ACCOUNTING))
        assertFalse(ChatConversationMode.belongsTo("conv_1234_abc", ChatConversationMode.AGENT))
    }

    @Test
    fun `belongsTo correctly classifies legacy conversations`() {
        assertTrue(ChatConversationMode.belongsTo("legacy", ChatConversationMode.ACCOUNTING))
        assertFalse(ChatConversationMode.belongsTo("legacy", ChatConversationMode.AGENT))
    }

    // ===== ChatConversationMode.createId =====

    @Test
    fun `createId for AGENT starts with agent_ prefix`() {
        val id = ChatConversationMode.createId(ChatConversationMode.AGENT)
        assertTrue(id.startsWith("agent_"))
    }

    @Test
    fun `createId for ACCOUNTING starts with conv_ prefix`() {
        val id = ChatConversationMode.createId(ChatConversationMode.ACCOUNTING)
        assertTrue(id.startsWith("conv_"))
    }

    @Test
    fun `createId generates unique IDs`() {
        val ids = (1..100).map { ChatConversationMode.createId(ChatConversationMode.AGENT) }
        assertEquals(100, ids.toSet().size)
    }

    @Test
    fun `createId AGENT matches GLOB agent_ pattern`() {
        val id = ChatConversationMode.createId(ChatConversationMode.AGENT)
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf(id))
    }

    @Test
    fun `createId ACCOUNTING does NOT match agent pattern`() {
        val id = ChatConversationMode.createId(ChatConversationMode.ACCOUNTING)
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf(id))
    }

    // ===== ChatConversationMode.fromActivityMode / toActivityMode =====

    @Test
    fun `fromActivityMode maps correctly`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.fromActivityMode(1))
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.fromActivityMode(0))
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.fromActivityMode(-1))
    }

    @Test
    fun `toActivityMode maps correctly`() {
        assertEquals(1, ChatConversationMode.toActivityMode(ChatConversationMode.AGENT))
        assertEquals(0, ChatConversationMode.toActivityMode(ChatConversationMode.ACCOUNTING))
    }

    @Test
    fun `fromActivityMode and toActivityMode are inverse`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.fromActivityMode(ChatConversationMode.toActivityMode(ChatConversationMode.AGENT)))
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.fromActivityMode(ChatConversationMode.toActivityMode(ChatConversationMode.ACCOUNTING)))
    }

    // ===== Mode isolation =====

    @Test
    fun `agent conversations are not visible in accounting mode`() {
        val all = listOf("agent_1000_abc", "conv_2000_def", "legacy", "agent_3000_ghi", "conv_4000_jkl")
        val accounting = all.filter { ChatConversationMode.belongsTo(it, ChatConversationMode.ACCOUNTING) }
        assertTrue(accounting.none { it.startsWith("agent_") })
        assertEquals(3, accounting.size)
    }

    @Test
    fun `accounting conversations are not visible in agent mode`() {
        val all = listOf("agent_1000_abc", "conv_2000_def", "legacy", "agent_3000_ghi", "conv_4000_jkl")
        val agent = all.filter { ChatConversationMode.belongsTo(it, ChatConversationMode.AGENT) }
        assertTrue(agent.all { it.startsWith("agent_") })
        assertEquals(2, agent.size)
    }

    @Test
    fun `agentX is not misclassified as agent mode`() {
        assertFalse(ChatConversationMode.belongsTo("agentX123", ChatConversationMode.AGENT))
        assertTrue(ChatConversationMode.belongsTo("agentX123", ChatConversationMode.ACCOUNTING))
    }

    // ===== ChatMessagePipeline.mapAgentErrorToUserMessage =====

    @Test
    fun `error mapping - 401 authentication`() {
        assertEquals("API 认证失败，请检查 API Key 是否正确", ChatMessagePipeline.mapAgentErrorToUserMessage("HTTP 401"))
    }

    @Test
    fun `error mapping - timeout`() {
        assertEquals("请求超时，请稍后重试", ChatMessagePipeline.mapAgentErrorToUserMessage("Connection timed out"))
    }

    @Test
    fun `error mapping - DNS failure`() {
        assertEquals("网络连接失败，请检查网络设置", ChatMessagePipeline.mapAgentErrorToUserMessage("Unable to resolve host"))
    }

    @Test
    fun `error mapping - 500 server error`() {
        assertEquals("服务暂时不可用，请稍后重试", ChatMessagePipeline.mapAgentErrorToUserMessage("HTTP 500"))
    }

    @Test
    fun `error mapping - null message`() {
        assertEquals("服务异常，请稍后重试", ChatMessagePipeline.mapAgentErrorToUserMessage(null))
    }

    @Test
    fun `error messages never contain sensitive content`() {
        val inputs = listOf("sk-abc123", "https://api.example.com", "Bearer sk-xxx", "java.lang.NullPointerException")
        for (input in inputs) {
            val msg = ChatMessagePipeline.mapAgentErrorToUserMessage(input)
            assertFalse("Should not contain raw input: $input", msg.contains(input))
        }
    }

    // ===== ChatMessagePipeline.truncateHistory — round-aware trimming =====

    @Test
    fun `truncateHistory preserves newest messages when budget exceeded`() {
        val messages = listOf(0 to "A".repeat(30), 3 to "B".repeat(30), 0 to "C".repeat(30), 3 to "D".repeat(30), 0 to "E".repeat(30))
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 65)
        assertTrue(result.any { it.second == "E".repeat(30) })
        assertFalse(result.any { it.second == "A".repeat(30) })
    }

    @Test
    fun `truncateHistory result is in chronological order`() {
        val messages = listOf(0 to "first", 3 to "second", 0 to "third", 3 to "fourth", 0 to "fifth")
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 1000)
        assertEquals(listOf("first", "second", "third", "fourth", "fifth"), result.map { it.second })
    }

    @Test
    fun `truncateHistory ensures first message is user message`() {
        val messages = listOf(0 to "user1", 3 to "assistant1", 0 to "user2", 3 to "assistant2")
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 20)
        if (result.isNotEmpty()) {
            assertTrue(result.first().first in 0..2)
        }
    }

    @Test
    fun `truncateHistory single oversized user message is truncated not dropped`() {
        val messages = listOf(0 to "很长的用户消息".repeat(100))
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 50)
        assertEquals(1, result.size)
        assertTrue(result[0].second.length <= 50)
        assertTrue(result[0].second.endsWith("…"))
    }

    @Test
    fun `truncateHistory single oversized assistant message is dropped`() {
        val messages = listOf(3 to "很长的助手回复".repeat(100))
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 50)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `truncateHistory consecutive duplicate texts are preserved`() {
        val messages = listOf(0 to "same", 3 to "r1", 0 to "same", 3 to "r2")
        val result = ChatMessagePipeline.truncateHistory(messages, maxTotalChars = 1000)
        assertEquals(2, result.count { it.second == "same" })
    }

    // ===== Mode consistency =====

    @Test
    fun `agent conversationId with accounting mode should be rejected`() {
        assertNotEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("agent_1234_abc"))
    }

    @Test
    fun `accounting conversationId with agent mode should be rejected`() {
        assertNotEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf("conv_1234_abc"))
    }

    // ===== Search result mode derivation =====

    @Test
    fun `search result from agent conversation derives AGENT mode`() {
        assertEquals(ChatConversationMode.AGENT, ChatConversationMode.modeOf("agent_1234_abc"))
    }

    @Test
    fun `search result from legacy conversation derives ACCOUNTING mode`() {
        assertEquals(ChatConversationMode.ACCOUNTING, ChatConversationMode.modeOf("legacy"))
    }

    // ===== AgentLlmMessageBuilder: tool selection messages =====

    @Test
    fun `tool selection messages - userText appears exactly once as last message`() {
        val snapshot = listOf(
            makeChatMessage(0, "previous question", 1000L),
            makeChatMessage(3, "previous answer", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages("system prompt", "当前问题", snapshot)

        // Count user messages with content "当前问题"
        val currentUserCount = countMessagesWithContent(messages, "当前问题")
        assertEquals("Current userText should appear exactly once", 1, currentUserCount)

        // Last message should be the current userText
        val lastMsg = messages[messages.size() - 1].asJsonObject
        assertEquals("user", lastMsg.get("role").asString)
        assertEquals("当前问题", lastMsg.get("content").asString)
    }

    @Test
    fun `tool selection messages - history snapshot does not contain current message`() {
        val currentText = "查微信余额"
        val snapshot = listOf(
            makeChatMessage(0, "上一个问题", 1000L),
            makeChatMessage(3, "上一个回答", 2000L)
        )
        // snapshot does NOT contain currentText
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages("system", currentText, snapshot)

        // The history portion should not contain currentText
        val historyContent = extractHistoryContent(messages)
        assertFalse("History should not contain current userText", historyContent.contains(currentText))
    }

    @Test
    fun `tool selection messages - old same text is preserved in history`() {
        val currentText = "查余额"
        val snapshot = listOf(
            makeChatMessage(0, "查余额", 1000L),  // same text, but older
            makeChatMessage(3, "余额100元", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages("system", currentText, snapshot)

        // Old "查余额" should be in history, plus current "查余额" as last user
        val allContent = extractAllContent(messages)
        val count = countMessagesWithContent(messages, "查余额")
        assertEquals("Old same text in history + current text as last user = 2", 2, count)
    }

    @Test
    fun `tool selection messages - empty snapshot produces system + user only`() {
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages("system prompt", "问题", emptyList())
        assertEquals(2, messages.size()) // system + user
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
        assertEquals("问题", messages[1].asJsonObject.get("content").asString)
    }

    @Test
    fun `tool selection messages - multimodal user message carries image and text together`() {
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages(
            systemPrompt = "system prompt",
            userText = "这张图里有什么",
            historySnapshot = emptyList(),
            images = listOf(AgentImageInput(base64 = "abc123", mimeType = "image/png"))
        )

        assertEquals(2, messages.size())
        val lastMsg = messages[messages.size() - 1].asJsonObject
        assertEquals("user", lastMsg.get("role").asString)
        val content = lastMsg.getAsJsonArray("content")
        assertEquals("image_url", content[0].asJsonObject.get("type").asString)
        assertEquals("data:image/png;base64,abc123", content[0].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        assertEquals("text", content[1].asJsonObject.get("type").asString)
        assertEquals("这张图里有什么", content[1].asJsonObject.get("text").asString)
    }

    // ===== AgentLlmMessageBuilder: natural reply messages =====

    @Test
    fun `natural reply messages - userText appears exactly once as last message`() {
        val snapshot = listOf(
            makeChatMessage(0, "旧问题", 1000L),
            makeChatMessage(3, "旧回答", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildNaturalReplyMessages("新问题", snapshot)

        val currentUserCount = countMessagesWithContent(messages, "新问题")
        assertEquals("Current userText should appear exactly once", 1, currentUserCount)

        val lastMsg = messages[messages.size() - 1].asJsonObject
        assertEquals("user", lastMsg.get("role").asString)
        assertEquals("新问题", lastMsg.get("content").asString)
    }

    @Test
    fun `natural reply messages - snapshot used instead of DB re-read`() {
        val snapshot = listOf(
            makeChatMessage(0, "snapshot question", 1000L),
            makeChatMessage(3, "snapshot answer", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildNaturalReplyMessages("current", snapshot)

        // History should come from snapshot, not DB
        val historyContent = extractHistoryContent(messages)
        assertTrue("History should contain snapshot content", historyContent.contains("snapshot question"))
        assertTrue("History should contain snapshot content", historyContent.contains("snapshot answer"))
    }

    // ===== AgentLlmMessageBuilder: voice transcript dedup =====

    @Test
    fun `voice transcript appears exactly once in tool selection messages`() {
        val transcript = "查微信余额"
        val snapshot = listOf(
            makeChatMessage(2, """{"transcript":"查微信余额","audioPath":"/path"}""", 1000L),
            makeChatMessage(3, "余额100元", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages("system", transcript, snapshot)

        // The transcript should appear once in history (from voice message summary)
        // and once as the final user message
        val totalCount = countMessagesWithContent(messages, "查微信余额")
        // History has "语音：查微信余额" (not exact match), final user has "查微信余额"
        val lastMsg = messages[messages.size() - 1].asJsonObject
        assertEquals("user", lastMsg.get("role").asString)
        assertEquals(transcript, lastMsg.get("content").asString)
    }

    @Test
    fun `voice natural reply messages - transcript appears exactly once as user`() {
        val transcript = "查微信余额"
        val snapshot = listOf(
            makeChatMessage(2, """{"transcript":"查微信余额","audioPath":"/path"}""", 1000L),
            makeChatMessage(3, "余额100元", 2000L)
        )
        val messages = AgentLlmMessageBuilder.buildNaturalReplyMessages(transcript, snapshot)

        val lastMsg = messages[messages.size() - 1].asJsonObject
        assertEquals("user", lastMsg.get("role").asString)
        assertEquals(transcript, lastMsg.get("content").asString)
    }

    // ===== AgentLlmMessageBuilder: history formatting =====

    @Test
    fun `buildHistoryFromSnapshot formats messages correctly`() {
        val snapshot = listOf(
            makeChatMessage(0, "user text", 1000L),
            makeChatMessage(3, "ai reply", 2000L)
        )
        val result = AgentLlmMessageBuilder.buildHistoryFromSnapshot(snapshot)
        assertEquals(2, result.size)
        assertEquals("user" to "user text", result[0])
        assertEquals("assistant" to "ai reply", result[1])
    }

    @Test
    fun `buildHistoryFromSnapshot voice message produces user role summary`() {
        // In unit tests, org.json.JSONObject is stubbed and throws,
        // so runCatching returns empty transcript → summary is "[语音消息]"
        // At runtime, the actual transcript is extracted correctly.
        val snapshot = listOf(
            makeChatMessage(2, """{"transcript":"午饭花了35","audioPath":"/path"}""", 1000L)
        )
        val result = AgentLlmMessageBuilder.buildHistoryFromSnapshot(snapshot)
        assertEquals(1, result.size)
        assertEquals("user", result[0].first)
        // Summary should be non-empty (either "语音：..." or "[语音消息]")
        assertTrue(result[0].second.isNotBlank())
    }

    @Test
    fun `buildHistoryFromSnapshot empty snapshot returns empty`() {
        val result = AgentLlmMessageBuilder.buildHistoryFromSnapshot(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `buildHistoryFromSnapshot drops leading orphaned assistant`() {
        val snapshot = listOf(
            makeChatMessage(3, "orphaned reply", 1000L),
            makeChatMessage(0, "user text", 2000L),
            makeChatMessage(3, "normal reply", 3000L)
        )
        val result = AgentLlmMessageBuilder.buildHistoryFromSnapshot(snapshot)
        // First element should be user, not the orphaned assistant
        assertEquals("user", result[0].first)
        assertEquals("user text", result[0].second)
    }

    // ===== Helpers =====

    private fun makeChatMessage(msgType: Int, content: String, timestamp: Long, billIds: String = ""): ChatMessage {
        return ChatMessage(
            id = timestamp, // use timestamp as ID for simplicity
            msgType = msgType,
            content = content,
            timestamp = timestamp,
            billIds = billIds,
            bookName = "test-book",
            conversationId = "test-conv"
        )
    }

    private fun countMessagesWithContent(messages: com.google.gson.JsonArray, content: String): Int {
        var count = 0
        for (i in 0 until messages.size()) {
            val msg = messages[i].asJsonObject
            if (msg.get("content").asString == content) count++
        }
        return count
    }

    private fun extractHistoryContent(messages: com.google.gson.JsonArray): String {
        // History is between system (index 0) and last user (index size-1)
        val sb = StringBuilder()
        for (i in 1 until messages.size() - 1) {
            sb.append(messages[i].asJsonObject.get("content").asString).append("\n")
        }
        return sb.toString()
    }

    private fun extractAllContent(messages: com.google.gson.JsonArray): String {
        val sb = StringBuilder()
        for (i in 0 until messages.size()) {
            sb.append(messages[i].asJsonObject.get("content").asString).append("\n")
        }
        return sb.toString()
    }
}
