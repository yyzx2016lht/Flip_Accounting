package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentToolRegistry
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class AgentListCapabilitiesTool : AgentTool {
    override val id = "agent.list_capabilities"
    override val category = "对话"
    override val risk = RiskLevel.READ
    override val description = "列出所有可用功能"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val tools = AgentToolRegistry.getAll()
        val grouped = tools.groupBy { it.category }

        val sb = StringBuilder("我可以帮你做以下事情：\n\n")
        for ((category, categoryTools) in grouped) {
            sb.appendLine("【$category】")
            for (tool in categoryTools) {
                sb.appendLine("• ${tool.description}")
            }
            sb.appendLine()
        }

        return AgentToolResult.success(userMessage = sb.toString().trim())
    }
}
