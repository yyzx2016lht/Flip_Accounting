package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatsQueryLatestBillTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_latest_bill"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询最近的一笔账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查当前账本")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val bookName = params.optString("bookName", "").trim().ifBlank { null }
        val recentBills = db.billDao().getRecentBills(10)

        val filtered = if (bookName != null) {
            recentBills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        } else {
            recentBills
        }

        val bill = filtered.firstOrNull()
            ?: return AgentToolResult.success(userMessage = "暂无账单记录")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val typeLabel = when (bill.type) {
            0 -> "支出"
            1 -> "收入"
            2 -> "转账"
            3 -> "还款"
            4 -> "退款"
            else -> "其他"
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("billId", bill.id)
                put("categoryName", bill.categoryName)
                put("amount", bill.amount)
                put("type", typeLabel)
                put("accountName", bill.accountName)
                put("time", dateFormat.format(Date(bill.time)))
                put("remark", bill.remark)
                put("currency", bill.currency)
                put("bookName", bill.bookName)
            },
            userMessage = "最近一笔：${dateFormat.format(Date(bill.time))} ${bill.categoryName} $typeLabel ${bill.amount} ${bill.currency}"
        )
    }
}
