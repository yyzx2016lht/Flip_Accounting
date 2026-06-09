package com.taostudio.tapaccounting.chat.agent

import org.json.JSONObject

interface AgentTool {
    val id: String
    val category: String
    val risk: RiskLevel
    val description: String
    val parameterSchema: JSONObject

    suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult
}
