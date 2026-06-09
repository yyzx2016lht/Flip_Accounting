package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.QueryAction
import com.taostudio.tapaccounting.chat.query.QueryAggregation
import com.taostudio.tapaccounting.chat.query.QueryBillType
import com.taostudio.tapaccounting.chat.query.QueryContext
import com.taostudio.tapaccounting.chat.query.QueryExecutor
import com.taostudio.tapaccounting.chat.query.QueryIntent
import com.taostudio.tapaccounting.chat.query.QuerySlots
import com.taostudio.tapaccounting.chat.query.QueryTimeRange
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.chat.ai.AiTimeRangeParser
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQueryCategoryTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_category"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询某分类的花销金额"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("categoryName", JSONObject().apply {
                put("type", "string")
                put("description", "分类名称，如餐饮、交通")
            })
            put("timeRangeKey", JSONObject().apply {
                put("type", "string")
                put("description", "时间范围：this_month, last_month, this_year, today, yesterday, this_week, last_week")
            })
            put("billType", JSONObject().apply {
                put("type", "string")
                put("description", "账单类型：EXPENSE, INCOME, TRANSFER, REPAYMENT, REFUND, ANY")
            })
        })
        put("required", org.json.JSONArray().apply { put("categoryName") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val categoryName = params.optString("categoryName", "").trim()
        if (categoryName.isEmpty()) {
            return AgentToolResult.failure("请指定分类名称")
        }

        val timeRangeKey = params.optString("timeRangeKey", "this_month").trim()
        val billTypeStr = params.optString("billType", "EXPENSE").trim().uppercase()
        val billType = try {
            com.taostudio.tapaccounting.chat.query.QueryBillType.valueOf(billTypeStr)
        } catch (e: Exception) {
            com.taostudio.tapaccounting.chat.query.QueryBillType.EXPENSE
        }

        val timeRange = parseTimeRange(timeRangeKey)
        val resolvedCategory = context.queryContext.categories.find {
            it.name.contains(categoryName, ignoreCase = true)
        }

        val action = QueryAction(
            intent = QueryIntent.QUERY_CATEGORY_STATS,
            slots = QuerySlots(
                timeRange = timeRange,
                categoryName = resolvedCategory?.name ?: categoryName,
                categoryId = resolvedCategory?.id,
                billType = billType,
                aggregation = QueryAggregation.TOTAL
            )
        )

        val billSource = RoomQueryBillSource(db)
        val executor = QueryExecutor(billSource)
        val result = executor.execute(action, context.queryContext)

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("category", resolvedCategory?.name ?: categoryName)
                put("reply", result.reply)
            },
            userMessage = result.reply
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
