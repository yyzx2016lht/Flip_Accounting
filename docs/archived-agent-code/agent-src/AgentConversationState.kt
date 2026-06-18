package com.taostudio.tapaccounting.chat.agent

data class AgentConversationState(
    val activeSkillIds: Set<String> = emptySet(),
    val lastToolId: String? = null,
    val recentBillIds: List<Long> = emptyList(),
    val recentAssetIds: List<Long> = emptyList(),
    val recentBookNames: List<String> = emptyList(),
    val pendingAction: PendingAgentAction? = null
) {
    companion object {
        private const val MAX_RECENT_ITEMS = 10
    }

    fun withActiveSkills(skillIds: Set<String>): AgentConversationState {
        return copy(activeSkillIds = skillIds)
    }

    fun withLastTool(toolId: String): AgentConversationState {
        return copy(lastToolId = toolId)
    }

    fun withRecentBill(billId: Long): AgentConversationState {
        val updated = (listOf(billId) + recentBillIds).distinct().take(MAX_RECENT_ITEMS)
        return copy(recentBillIds = updated)
    }

    fun withRecentAsset(assetId: Long): AgentConversationState {
        val updated = (listOf(assetId) + recentAssetIds).distinct().take(MAX_RECENT_ITEMS)
        return copy(recentAssetIds = updated)
    }

    fun withRecentBook(bookName: String): AgentConversationState {
        val updated = (listOf(bookName) + recentBookNames).distinct().take(MAX_RECENT_ITEMS)
        return copy(recentBookNames = updated)
    }

    fun withPendingAction(action: PendingAgentAction?): AgentConversationState {
        return copy(pendingAction = action)
    }
}

object ConversationStateManager {
    private val states = mutableMapOf<String, AgentConversationState>()

    fun getState(conversationId: String): AgentConversationState {
        return states.getOrPut(conversationId) { AgentConversationState() }
    }

    fun updateState(conversationId: String, updater: (AgentConversationState) -> AgentConversationState) {
        val current = getState(conversationId)
        states[conversationId] = updater(current)
    }

    fun clearState(conversationId: String) {
        states.remove(conversationId)
    }

    fun clearAll() {
        states.clear()
    }
}
