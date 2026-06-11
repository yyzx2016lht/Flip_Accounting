package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class StatsOpenAssetPageTool(private val context: Context) : AgentTool {
    override val id = "stats.open_asset_page"
    override val category = "统计"
    override val risk = RiskLevel.NAV
    override val description = "打开资产统计页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, MainActivity::class.java).apply {
            putExtra("tab", "assets")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开资产页面",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
