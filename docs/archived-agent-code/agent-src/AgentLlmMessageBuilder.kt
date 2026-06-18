package com.taostudio.tapaccounting.chat.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taostudio.tapaccounting.data.local.entity.ChatMessage

/**
 * Builds LLM message arrays for the Agent orchestrator.
 *
 * This is extracted as a standalone helper so it can be tested directly
 * without Android dependencies or LLM API calls.
 *
 * Key invariants:
 * - historySnapshot is the ONLY source of history (no DB re-read).
 * - The current userText is appended exactly once as the final "user" message.
 * - historySnapshot must NOT contain the current user message.
 */
object AgentLlmMessageBuilder {

    private const val HISTORY_MAX_TURN_CHARS = 2000
    private const val HISTORY_MAX_TOTAL_CHARS = 24_000

    /**
     * Build the full messages array for tool selection LLM call.
     *
     * @param systemPrompt The system prompt with tool definitions.
     * @param userText The current user input (added once as the last user message).
     * @param historySnapshot Pre-fetched history EXCLUDING the current user message.
     * @return JsonArray of messages ready for the LLM API.
     */
    fun buildToolSelectionMessages(
        systemPrompt: String,
        userText: String,
        historySnapshot: List<ChatMessage>?,
        images: List<AgentImageInput> = emptyList()
    ): JsonArray {
        return JsonArray().apply {
            add(buildTextMessage("system", systemPrompt))
            val history = if (historySnapshot != null) {
                truncateAndFormatHistory(historySnapshot)
            } else {
                JsonArray() // Empty — caller should always provide snapshot
            }
            for (msg in history) {
                add(msg)
            }
            if (images.isEmpty()) {
                add(buildTextMessage("user", userText))
            } else {
                add(buildMultimodalUserMessage(userText, images))
            }
        }
    }

    /**
     * Build the full messages array for natural reply LLM call.
     *
     * @param userText The current user input (added once as the last user message).
     * @param historySnapshot Pre-fetched history EXCLUDING the current user message.
     * @return JsonArray of messages ready for the LLM API.
     */
    fun buildNaturalReplyMessages(
        userText: String,
        historySnapshot: List<ChatMessage>?
    ): JsonArray {
        return JsonArray().apply {
            add(buildTextMessage("system", "你是一个记账助手，用简洁自然的口语回复用户。"))
            val history = if (historySnapshot != null) {
                truncateAndFormatHistory(historySnapshot)
            } else {
                JsonArray()
            }
            for (msg in history) {
                add(msg)
            }
            add(buildTextMessage("user", userText))
        }
    }

    /**
     * Convert a ChatMessage list to a JsonArray of LLM messages.
     * Returns the raw list for test inspection.
     */
    fun buildHistoryFromSnapshot(snapshot: List<ChatMessage>): List<Pair<String, String>> {
        return truncateAndFormatHistoryToList(snapshot)
    }

    /**
     * Build a text message JsonObject.
     */
    fun buildTextMessage(role: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

    private fun buildMultimodalUserMessage(
        userText: String,
        images: List<AgentImageInput>
    ): JsonObject = JsonObject().apply {
        addProperty("role", "user")
        add("content", JsonArray().apply {
            images.forEach { image ->
                add(JsonObject().apply {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply {
                        addProperty("url", "data:${image.mimeType};base64,${image.base64}")
                    })
                })
            }
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", userText)
            })
        })
    }

    // --- Internal formatting ---

    private fun truncateAndFormatHistory(messages: List<ChatMessage>): JsonArray {
        val result = JsonArray()
        for ((role, content) in truncateAndFormatHistoryToList(messages)) {
            result.add(buildTextMessage(role, content))
        }
        return result
    }

    private fun truncateAndFormatHistoryToList(messages: List<ChatMessage>): List<Pair<String, String>> {
        if (messages.isEmpty()) return emptyList()
        // Step 1: Truncate from newest to oldest
        val selected = mutableListOf<Pair<ChatMessage, String>>()
        var totalChars = 0
        for (msg in messages.reversed()) {
            val content = summarizeMessageForHistory(msg)
            if (content.isBlank()) continue
            if (totalChars + content.length > HISTORY_MAX_TOTAL_CHARS) {
                if (selected.isEmpty() && msg.msgType in 0..2 && content.length > HISTORY_MAX_TOTAL_CHARS) {
                    val truncated = content.take(HISTORY_MAX_TOTAL_CHARS - 1).trimEnd() + "…"
                    selected.add(msg to truncated)
                }
                break
            }
            totalChars += content.length
            selected.add(msg to content)
        }
        // Step 2: Reverse to chronological order
        selected.reverse()
        // Step 3: Drop leading orphaned assistant messages
        val firstUserIndex = selected.indexOfFirst { it.first.msgType in 0..2 }
        val trimmed = if (firstUserIndex > 0) selected.subList(firstUserIndex, selected.size) else selected
        return trimmed.map { (msg, content) ->
            val role = if (msg.msgType in 0..2) "user" else "assistant"
            role to content
        }
    }

    private fun summarizeMessageForHistory(msg: ChatMessage): String {
        val raw = msg.content.trim()
        if (raw.isBlank() && msg.msgType != 4) return ""
        val text = when (msg.msgType) {
            0 -> raw  // user text
            1 -> "[图片消息]"
            2 -> {
                val transcript = runCatching {
                    org.json.JSONObject(raw).optString("transcript").trim()
                }.getOrDefault("")
                if (transcript.isNotBlank()) "语音：$transcript" else "[语音消息]"
            }
            4 -> {
                val billIds = runCatching {
                    org.json.JSONArray(msg.billIds ?: "[]")
                }.getOrNull()
                if (billIds != null && billIds.length() > 0) "[已记账 ${billIds.length()} 笔]" else "[账单结果]"
            }
            3 -> raw  // ai text
            else -> raw
        }
        if (text.length <= HISTORY_MAX_TURN_CHARS) return text
        return text.take(HISTORY_MAX_TURN_CHARS).trimEnd() + "…"
    }
}

data class AgentImageInput(
    val base64: String,
    val mimeType: String
)
