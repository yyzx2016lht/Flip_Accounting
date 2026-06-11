package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.activity.EditBillActivity
import org.json.JSONObject

class BillEditTool(private val context: Context, private val db: AppDatabase) : AgentTool {
    override val id = "bill.edit"
    override val category = "记账"
    override val risk = RiskLevel.NAV
    override val description = "打开账单编辑页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "要编辑的账单ID")
            })
        })
        put("required", org.json.JSONArray().apply { put("billId") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val billId = params.optLong("billId", 0)
        if (billId <= 0) {
            return AgentValidationResult.invalidParams("请提供有效的账单ID", listOf("billId"))
        }
        val bill = db.billDao().getBillById(billId)
        if (bill == null) {
            return AgentValidationResult.notFound("未找到ID为 $billId 的账单")
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        val intent = Intent(this.context, EditBillActivity::class.java).apply {
            putExtra("bill_id", billId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("billId", bill.id)
                put("categoryName", bill.categoryName)
                put("amount", bill.amount)
            },
            userMessage = "已打开账单编辑页面：${bill.categoryName} ${bill.amount}元",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
