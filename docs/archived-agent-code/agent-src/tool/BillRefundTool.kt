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
import com.taostudio.tapaccounting.ui.activity.RefundActivity
import org.json.JSONObject

class BillRefundTool(private val context: Context, private val db: AppDatabase) : AgentTool {
    override val id = "bill.refund"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "对某笔支出进行退款"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "要退款的原支出账单ID")
            })
            put("refundAmount", JSONObject().apply {
                put("type", "number")
                put("description", "退款金额。不填则全额退款")
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
        if (bill.type != 0) {
            return AgentValidationResult.invalidParams("只能对支出账单进行退款")
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        val refundAmount = params.optDouble("refundAmount", bill.amount)

        // Open RefundActivity with pre-filled data
        val intent = Intent(this.context, RefundActivity::class.java).apply {
            putExtra("bill_id", billId)
            putExtra("refund_amount", refundAmount)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("billId", bill.id)
                put("categoryName", bill.categoryName)
                put("originalAmount", bill.amount)
                put("refundAmount", refundAmount)
            },
            userMessage = "已打开退款页面：${bill.categoryName} 原金额 ${bill.amount}元，退款 ${String.format("%.2f", refundAmount)}元",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
