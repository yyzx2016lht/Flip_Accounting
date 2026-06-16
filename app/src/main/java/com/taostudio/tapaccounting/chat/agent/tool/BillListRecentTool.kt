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

class BillListRecentTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.list_recent"
    override val category = "记账"
    override val risk = RiskLevel.READ
    override val description = "查询最近几笔账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "显示数量，默认5")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val limit = params.optInt("limit", 5).coerceIn(1, 20)

        val bills = db.billDao().getRecentBills(limit)

        if (bills.isEmpty()) {
            return AgentToolResult.success(
                userMessage = "暂无账单记录"
            )
        }

        val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("最近 ${bills.size} 笔账单：\n")
        for (bill in bills) {
            val date = dateFormat.format(Date(bill.time))
            val type = when (bill.type) {
                0 -> "支出"
                1 -> "收入"
                2 -> "转账"
                3 -> "还款"
                4 -> "退款"
                else -> "其他"
            }
            sb.appendLine("• $date ${bill.categoryName} $type ${bill.amount} ${bill.currency}")
            if (bill.remark.isNotBlank()) {
                sb.appendLine("  备注: ${bill.remark}")
            }
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("count", bills.size)
                put("billId", bills.firstOrNull()?.id ?: 0)
                put("bills", bills.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("amount", it.amount)
                        put("categoryName", it.categoryName)
                        put("accountName", it.accountName)
                        put("type", it.type)
                        put("time", it.time)
                        put("remark", it.remark)
                        put("currency", it.currency)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
