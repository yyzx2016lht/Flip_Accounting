package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import org.json.JSONObject

class BillCreateManualTool(private val context: Context) : AgentTool {
    override val id = "bill.create_manual"
    override val category = "记账"
    override val risk = RiskLevel.NAV
    override val description = "打开手动记账页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("categoryName", JSONObject().apply {
                put("type", "string")
                put("description", "预填分类名称")
            })
            put("amount", JSONObject().apply {
                put("type", "number")
                put("description", "预填金额")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val categoryName = params.optString("categoryName", "").trim()
        val amount = params.optDouble("amount", 0.0)

        val intent = Intent(this.context, EditBillActivity::class.java).apply {
            if (categoryName.isNotBlank()) putExtra("preset_category", categoryName)
            if (amount > 0) putExtra("preset_amount", amount)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val desc = buildString {
            append("已打开手动记账页面")
            if (categoryName.isNotBlank()) append("，分类：$categoryName")
            if (amount > 0) append("，金额：${String.format("%.2f", amount)}元")
        }

        return AgentToolResult.success(
            userMessage = desc,
            uiAction = UiAction.Navigate(intent)
        )
    }
}
