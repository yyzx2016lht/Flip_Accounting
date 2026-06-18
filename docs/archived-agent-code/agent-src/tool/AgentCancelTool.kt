package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class AgentCancelTool : AgentTool {
    override val id = "agent.cancel"
    override val category = "对话"
    override val risk = RiskLevel.READ
    override val description = "取消当前待确认的操作"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        return AgentToolResult.success(
            userMessage = "已取消当前操作"
        )
    }
}
