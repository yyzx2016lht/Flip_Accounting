package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class AgentClarifyTool : AgentTool {
    override val id = "agent.clarify"
    override val category = "对话"
    override val risk = RiskLevel.READ
    override val description = "向用户追问以获取更多信息"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("question", JSONObject().apply {
                put("type", "string")
                put("description", "追问的问题")
            })
        })
        put("required", org.json.JSONArray().apply { put("question") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val question = params.optString("question", "")
        return if (question.isNotBlank()) {
            AgentToolResult.success(userMessage = question)
        } else {
            AgentToolResult.failure("问题内容不能为空")
        }
    }
}
