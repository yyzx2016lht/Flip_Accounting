package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

data class AgentToolResult(
    val success: Boolean,
    val facts: JSONObject? = null,
    val userMessage: String? = null,
    val uiAction: UiAction? = null,
    val effects: List<AgentEffect> = emptyList()
) {
    companion object {
        fun success(
            facts: JSONObject? = null,
            userMessage: String? = null,
            uiAction: UiAction? = null,
            effects: List<AgentEffect> = emptyList()
        ) = AgentToolResult(true, facts, userMessage, uiAction, effects)

        fun failure(userMessage: String) =
            AgentToolResult(false, userMessage = userMessage)
    }
}
