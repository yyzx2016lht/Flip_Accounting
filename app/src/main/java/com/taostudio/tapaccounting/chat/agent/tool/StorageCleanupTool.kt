package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.StorageCleanupActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class StorageCleanupTool(private val context: Context) : AgentTool {
    override val id = "storage.cleanup"
    override val category = "系统"
    override val risk = RiskLevel.NAV
    override val description = "打开存储清理页面（清理操作不可逆，请在页面中手动选择清理项并确认）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, StorageCleanupActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开存储清理页面。请在页面中选择要清理的内容并确认。",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
