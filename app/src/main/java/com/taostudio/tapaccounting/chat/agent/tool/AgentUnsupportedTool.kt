package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class AgentUnsupportedTool : AgentTool {
    override val id = "agent.unsupported"
    override val category = "对话"
    override val risk = RiskLevel.READ
    override val description = "当用户请求的软件操作没有对应工具或无法可靠执行时，明确说明该功能尚未实现"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("feature", JSONObject().apply {
                put("type", "string")
                put("description", "用户请求但当前尚未支持的功能")
            })
        })
    }

    override suspend fun execute(
        params: JSONObject,
        context: AgentSessionContext
    ): AgentToolResult {
        val feature = params.optString("feature", "").trim()
        return AgentToolResult.success(userMessage = formatMessage(feature))
    }

    companion object {
        fun formatMessage(feature: String): String =
            feature.trim().takeIf { it.isNotBlank() }
                ?.let { "“$it”功能尚未实现" }
                ?: "该功能尚未实现"
    }
}
