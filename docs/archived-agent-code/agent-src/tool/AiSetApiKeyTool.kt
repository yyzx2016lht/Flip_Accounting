package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.AiConfigActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class AiSetApiKeyTool(private val context: Context) : AgentTool {
    override val id = "ai.set_api_key"
    override val category = "设置"
    override val risk = RiskLevel.NAV
    override val description = "打开 AI 服务配置页面（API Key 需在页面中手动设置，出于安全考虑不支持通过对话直接配置）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, AiConfigActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开 AI 服务配置页面。请在页面中设置 API Key。",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
