package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQueryTopCategoriesTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_top_categories"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询支出最多的分类排行"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("timeRangeKey", JSONObject().apply {
                put("type", "string")
                put("description", "时间范围：this_month, last_month, this_year, today, yesterday, this_week, last_week")
            })
            put("topN", JSONObject().apply {
                put("type", "integer")
                put("description", "显示前N个分类，默认5")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
    }

    private val validTimeRangeKeys = setOf("today", "yesterday", "this_week", "last_week", "this_month", "last_month", "this_year")

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val timeRangeKey = params.optString("timeRangeKey", "this_month").trim().lowercase()
        val topN = params.optInt("topN", 5).coerceIn(1, 20)
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val timeRange = parseTimeRange(timeRangeKey)
        val billSource = RoomQueryBillSource(db)
        var bills = billSource.loadBetween(timeRange.startMillis ?: 0, timeRange.endMillis ?: System.currentTimeMillis(), null)

        if (bookName != null) {
            bills = bills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        // Only expenses, exclude refunds
        bills = bills.filter { it.type == 0 && it.subType != 4 }

        val topCategories = bills.groupBy { it.categoryName.ifBlank { "未分类" } }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .take(topN)

        if (topCategories.isEmpty()) {
            return AgentToolResult.success(userMessage = "该时间段暂无支出记录")
        }

        val totalExpense = bills.sumOf { it.amount }
        val sb = StringBuilder("${timeRange.label ?: timeRangeKey}支出分类排行：\n")
        topCategories.forEachIndexed { index, (name, amount) ->
            val pct = if (totalExpense > 0) amount / totalExpense * 100 else 0.0
            sb.appendLine("${index + 1}. $name: ${String.format("%.2f", amount)} 元 (${String.format("%.1f", pct)}%)")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("timeRangeLabel", timeRange.label ?: timeRangeKey)
                put("bookName", bookName ?: "所有账本")
                put("totalExpense", String.format("%.2f", totalExpense))
                put("topCategories", topCategories.map { (name, amount) ->
                    JSONObject().apply {
                        put("name", name)
                        put("amount", String.format("%.2f", amount))
                        put("percent", if (totalExpense > 0) String.format("%.1f", amount / totalExpense * 100) else "0")
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }

    private fun parseTimeRange(key: String): com.taostudio.tapaccounting.chat.query.QueryTimeRange {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis
        return when (key) {
            "today" -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(calendar.timeInMillis, now, "today", "今天")
            }
            "yesterday" -> {
                calendar.add(Calendar.DAY_OF_MONTH, -1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis; calendar.add(Calendar.DAY_OF_MONTH, 1)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(start, calendar.timeInMillis, "yesterday", "昨天")
            }
            "this_week" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(calendar.timeInMillis, now, "this_week", "本周")
            }
            "last_week" -> {
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek); calendar.add(Calendar.WEEK_OF_MONTH, -1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis; calendar.add(Calendar.WEEK_OF_MONTH, 1)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(start, calendar.timeInMillis, "last_week", "上周")
            }
            "this_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(calendar.timeInMillis, now, "this_month", "本月")
            }
            "last_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val start = calendar.timeInMillis; calendar.add(Calendar.MONTH, 1)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(start, calendar.timeInMillis, "last_month", "上月")
            }
            "this_year" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(calendar.timeInMillis, now, "this_year", "本年")
            }
            else -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                com.taostudio.tapaccounting.chat.query.QueryTimeRange(calendar.timeInMillis, now, "this_month", "本月")
            }
        }
    }
}
