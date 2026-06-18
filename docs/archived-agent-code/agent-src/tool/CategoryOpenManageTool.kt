package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class CategoryOpenManageTool(private val context: Context) : AgentTool {
    override val id = "category.open_manage"
    override val category = "分类"
    override val risk = RiskLevel.NAV
    override val description = "打开分类管理页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        // Navigate to settings which contains category management
        val intent = Intent(this.context, com.taostudio.tapaccounting.MainActivity::class.java).apply {
            putExtra("tab", "profile")
            putExtra("open", "category_manage")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开分类管理页面",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
