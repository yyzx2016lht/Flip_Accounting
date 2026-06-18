package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.QueryAction
import com.taostudio.tapaccounting.chat.query.QueryAggregation
import com.taostudio.tapaccounting.chat.query.QueryBillType
import com.taostudio.tapaccounting.chat.query.QueryExecutor
import com.taostudio.tapaccounting.chat.query.QueryIntent
import com.taostudio.tapaccounting.chat.query.QuerySlots
import com.taostudio.tapaccounting.chat.query.QueryTimeRange
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQuerySpendingTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_spending"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询总花销金额"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("timeRangeKey", JSONObject().apply {
                put("type", "string")
                put("description", "时间范围：this_month, last_month, this_year, today, yesterday, this_week, last_week")
            })
            put("billType", JSONObject().apply {
                put("type", "string")
                put("description", "账单类型：EXPENSE, INCOME, TRANSFER, REPAYMENT, REFUND, ANY")
            })
            put("keyword", JSONObject().apply {
                put("type", "string")
                put("description", "关键词筛选")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，如：法国账本、总账本。不填则查所有账本")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val timeRangeKey = params.optString("timeRangeKey", "this_month").trim()
        val billTypeStr = params.optString("billType", "EXPENSE").trim().uppercase()
        val billType = try {
            QueryBillType.valueOf(billTypeStr)
        } catch (e: Exception) {
            QueryBillType.EXPENSE
        }
        val keyword = params.optString("keyword", "").trim().ifBlank { null }
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val timeRange = parseTimeRange(timeRangeKey)

        val billSource = RoomQueryBillSource(db)
        var bills = billSource.loadBetween(
            timeRange.startMillis ?: 0,
            timeRange.endMillis ?: System.currentTimeMillis(),
            null
        )

        // 按账本筛选
        if (bookName != null) {
            bills = bills.filter { bill ->
                bill.bookName.contains(bookName, ignoreCase = true)
            }
        }

        // 按类型筛选
        bills = bills.filter { bill ->
            when (billType) {
                QueryBillType.EXPENSE -> bill.type == 0 && bill.subType != 4
                QueryBillType.INCOME -> bill.type == 1
                QueryBillType.ANY -> true
                else -> true
            }
        }

        val total = bills.sumOf { it.amount }
        val count = bills.size
        val byCategory = bills.groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(5)

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("timeRange", timeRangeKey)
                put("timeRangeLabel", timeRange.label ?: timeRangeKey)
                put("billType", billTypeStr)
                put("bookName", bookName ?: "所有账本")
                put("totalAmount", String.format("%.2f", total))
                put("billCount", count)
                put("byCategory", byCategory.map { 
                    JSONObject().apply { 
                        put("name", it.first)
                        put("amount", String.format("%.2f", it.second))
                    }
                })
            }
        )
    }

    private fun parseTimeRange(key: String): QueryTimeRange {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis

        return when (key.lowercase()) {
            "today" -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                QueryTimeRange(calendar.timeInMillis, now, "today", "今天")
            }
            "yesterday" -> {
                calendar.add(Calendar.DAY_OF_MONTH, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                calendar.add(Calendar.DAY_OF_MONTH, 1)
                QueryTimeRange(start, calendar.timeInMillis, "yesterday", "昨天")
            }
            "this_week" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                QueryTimeRange(calendar.timeInMillis, now, "this_week", "本周")
            }
            "last_week" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                calendar.add(Calendar.WEEK_OF_MONTH, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                calendar.add(Calendar.WEEK_OF_MONTH, 1)
                QueryTimeRange(start, calendar.timeInMillis, "last_week", "上周")
            }
            "this_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                QueryTimeRange(calendar.timeInMillis, now, "this_month", "本月")
            }
            "last_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.add(Calendar.MONTH, -1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis
                calendar.add(Calendar.MONTH, 1)
                QueryTimeRange(start, calendar.timeInMillis, "last_month", "上月")
            }
            "this_year" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                QueryTimeRange(calendar.timeInMillis, now, "this_year", "本年")
            }
            else -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                QueryTimeRange(calendar.timeInMillis, now, "this_month", "本月")
            }
        }
    }
}
