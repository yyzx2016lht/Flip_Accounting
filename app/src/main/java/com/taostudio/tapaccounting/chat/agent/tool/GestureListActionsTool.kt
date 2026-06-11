package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.tap.TapActionRegistry
import org.json.JSONObject

class GestureListActionsTool : AgentTool {
    override val id = "gesture.list_actions"
    override val category = "系统"
    override val risk = RiskLevel.READ
    override val description = "列出可用的手势触发动作"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val actions = TapActionRegistry.getAll()

        val sb = StringBuilder("可用的手势动作：\n")
        for (action in actions) {
            sb.appendLine("• ${action.id}: ${action.displayName} — ${action.description}")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("actions", actions.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.displayName)
                        put("description", it.description)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
