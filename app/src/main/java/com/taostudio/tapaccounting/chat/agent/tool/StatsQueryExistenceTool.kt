package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class StatsQueryExistenceTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_existence"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询是否买过某样东西"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("keyword", JSONObject().apply {
                put("type", "string")
                put("description", "关键词，如：咖啡、星巴克")
            })
            put("timeRangeKey", JSONObject().apply {
                put("type", "string")
                put("description", "时间范围：this_month, last_month, this_year, today, yesterday, this_week, last_week")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
        put("required", org.json.JSONArray().apply { put("keyword") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val keyword = params.optString("keyword", "").trim()
        if (keyword.isEmpty()) {
            return AgentToolResult.failure("请提供关键词")
        }
        val timeRangeKey = params.optString("timeRangeKey", "").trim().ifBlank { null }
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val bills = db.billDao().getRecentBills(500)
        
        var filtered = bills.filter { bill ->
            bill.remark.contains(keyword, ignoreCase = true) ||
            bill.categoryName.contains(keyword, ignoreCase = true)
        }

        if (bookName != null) {
            filtered = filtered.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        val count = filtered.size
        val totalAmount = filtered.sumOf { it.amount }

        return if (count > 0) {
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("found", true)
                    put("keyword", keyword)
                    put("count", count)
                    put("totalAmount", String.format("%.2f", totalAmount))
                    put("recentItems", filtered.take(3).map { 
                        "${it.categoryName} ${String.format("%.2f", it.amount)}元"
                    })
                }
            )
        } else {
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("found", false)
                    put("keyword", keyword)
                }
            )
        }
    }
}
