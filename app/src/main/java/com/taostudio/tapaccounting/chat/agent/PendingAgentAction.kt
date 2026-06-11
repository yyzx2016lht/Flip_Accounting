package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

data class PendingAgentAction(
    val conversationId: String,
    val toolId: String,
    val params: JSONObject,
    val preview: String,
    val createdAt: Long,
    val expiresAt: Long,
    val remainingCalls: List<ChatAgentOrchestrator.ToolCall> = emptyList(),
    val responseGoal: String = ""
) {
    fun isExpired(): Boolean = System.currentTimeMillis() > expiresAt

    fun hasRemainingCalls(): Boolean = remainingCalls.isNotEmpty()

    companion object {
        private const val EXPIRY_DURATION_MS = 5 * 60 * 1000L // 5 minutes

        fun create(
            conversationId: String,
            toolId: String,
            params: JSONObject,
            preview: String,
            remainingCalls: List<ChatAgentOrchestrator.ToolCall> = emptyList(),
            responseGoal: String = ""
        ): PendingAgentAction {
            val now = System.currentTimeMillis()
            return PendingAgentAction(
                conversationId = conversationId,
                toolId = toolId,
                params = params,
                preview = preview,
                createdAt = now,
                expiresAt = now + EXPIRY_DURATION_MS,
                remainingCalls = remainingCalls,
                responseGoal = responseGoal
            )
        }
    }
}

object PendingActionManager {
    private val pendingActions = mutableMapOf<String, PendingAgentAction>()

    fun save(action: PendingAgentAction) {
        pendingActions[action.conversationId] = action
    }

    fun get(conversationId: String): PendingAgentAction? {
        val action = pendingActions[conversationId]
        if (action != null && action.isExpired()) {
            pendingActions.remove(conversationId)
            return null
        }
        return action
    }

    fun clear(conversationId: String) {
        pendingActions.remove(conversationId)
    }

    fun hasPending(conversationId: String): Boolean {
        return get(conversationId) != null
    }
}
