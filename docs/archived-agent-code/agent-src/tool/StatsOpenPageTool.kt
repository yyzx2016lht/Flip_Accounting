package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class StatsOpenPageTool(private val context: Context) : AgentTool {
    override val id = "stats.open_page"
    override val category = "统计"
    override val risk = RiskLevel.NAV
    override val description = "打开统计页面，可指定筛选条件"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("categoryName", JSONObject().apply {
                put("type", "string")
                put("description", "筛选的分类名称，如：餐饮、交通")
            })
            put("billType", JSONObject().apply {
                put("type", "string")
                put("description", "账单类型筛选：EXPENSE, INCOME")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val categoryName = params.optString("categoryName", "").trim()
        val billType = params.optString("billType", "").trim()

        val intent = Intent(this.context, MainActivity::class.java).apply {
            putExtra("tab", "stats")
            if (categoryName.isNotBlank()) putExtra("filter_category", categoryName)
            if (billType.isNotBlank()) putExtra("filter_bill_type", billType)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val desc = buildString {
            append("已打开统计页面")
            if (categoryName.isNotBlank()) append("，筛选分类：$categoryName")
        }

        return AgentToolResult.success(
            userMessage = desc,
            uiAction = UiAction.Navigate(intent)
        )
    }
}
