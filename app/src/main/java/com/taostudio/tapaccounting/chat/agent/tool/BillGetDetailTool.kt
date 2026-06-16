package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillGetDetailTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.get_detail"
    override val category = "记账"
    override val risk = RiskLevel.READ
    override val description = "查询单笔账单详情"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "账单ID")
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
        if (billId <= 0) {
            return AgentToolResult.failure("请提供有效的账单ID")
        }

        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val date = dateFormat.format(Date(bill.time))
        val type = when (bill.type) {
            0 -> "支出"
            1 -> "收入"
            2 -> "转账"
            3 -> "还款"
            4 -> "退款"
            else -> "其他"
        }

        val sb = StringBuilder("账单详情：\n")
        sb.appendLine("类型: $type")
        sb.appendLine("金额: ${bill.amount} ${bill.currency}")
        sb.appendLine("分类: ${bill.categoryName}")
        sb.appendLine("资产: ${bill.accountName}")
        sb.appendLine("时间: $date")
        if (bill.remark.isNotBlank()) {
            sb.appendLine("备注: ${bill.remark}")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("billId", bill.id)
                put("id", bill.id)
                put("amount", bill.amount)
                put("type", bill.type)
                put("categoryName", bill.categoryName)
                put("accountName", bill.accountName)
                put("remark", bill.remark)
                put("time", bill.time)
                put("currency", bill.currency)
            },
            userMessage = sb.toString().trim()
        )
    }
}
