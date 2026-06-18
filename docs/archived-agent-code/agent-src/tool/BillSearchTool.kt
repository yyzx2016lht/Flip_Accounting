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

class BillSearchTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.search"
    override val category = "记账"
    override val risk = RiskLevel.READ
    override val description = "搜索账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("keyword", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "显示数量，默认10")
            })
        })
        put("required", org.json.JSONArray().apply { put("keyword") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val keyword = params.optString("keyword", "").trim()
        if (keyword.isEmpty()) {
            return AgentToolResult.failure("请提供搜索关键词")
        }
        val limit = params.optInt("limit", 10).coerceIn(1, 50)

        val allBills = db.billDao().getRecentBills(200)
        val filtered = allBills.filter { bill ->
            bill.categoryName.contains(keyword, ignoreCase = true) ||
            bill.remark.contains(keyword, ignoreCase = true) ||
            bill.accountName.contains(keyword, ignoreCase = true)
        }.take(limit)

        if (filtered.isEmpty()) {
            return AgentToolResult.success(
                userMessage = "未找到与「$keyword」相关的账单"
            )
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("找到 ${filtered.size} 笔相关账单：\n")
        for (bill in filtered) {
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
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("keyword", keyword)
                put("count", filtered.size)
                put("billId", filtered.firstOrNull()?.id ?: 0)
                put("bills", filtered.map {
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
