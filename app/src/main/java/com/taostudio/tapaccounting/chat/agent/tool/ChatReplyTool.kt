package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class ChatReplyTool : AgentTool {
    override val id = "chat.reply"
    override val category = "对话"
    override val risk = RiskLevel.READ
    override val description = "纯闲聊回复或解释功能"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", "回复内容")
            })
        })
        put("required", org.json.JSONArray().apply { put("message") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val message = params.optString("message", "")
        return if (message.isNotBlank()) {
            AgentToolResult.success(userMessage = message)
        } else {
            AgentToolResult.failure("回复内容不能为空")
        }
    }
}
