package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQueryComparePeriodTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_compare_period"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "比较两个时间段的花销（如本月与上月）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("period1", JSONObject().apply {
                put("type", "string")
                put("description", "第一个时间段：this_month, last_month, this_year, last_year")
            })
            put("period2", JSONObject().apply {
                put("type", "string")
                put("description", "第二个时间段：this_month, last_month, this_year, last_year")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
        put("required", org.json.JSONArray().apply { put("period1"); put("period2") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val period1 = params.optString("period1", "").trim().lowercase()
        val period2 = params.optString("period2", "").trim().lowercase()
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val range1 = getPeriodRange(period1)
        val range2 = getPeriodRange(period2)

        val billSource = RoomQueryBillSource(db)
        val bills1 = billSource.loadBetween(range1.first, range1.second, null)
        val bills2 = billSource.loadBetween(range2.first, range2.second, null)

        val filterByBook: (List<com.taostudio.tapaccounting.data.local.entity.Bill>) -> List<com.taostudio.tapaccounting.data.local.entity.Bill> = { bills ->
            if (bookName != null) bills.filter { it.bookName.contains(bookName, ignoreCase = true) } else bills
        }

        val filtered1 = filterByBook(bills1).filter { it.type == 0 && it.subType != 4 }
        val filtered2 = filterByBook(bills2).filter { it.type == 0 && it.subType != 4 }

        val total1 = filtered1.sumOf { it.amount }
        val total2 = filtered2.sumOf { it.amount }
        val diff = total1 - total2
        val percentChange = if (total2 > 0) ((total1 - total2) / total2 * 100) else 0.0

        val label1 = getPeriodLabel(period1)
        val label2 = getPeriodLabel(period2)

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("period1", JSONObject().apply {
                    put("label", label1)
                    put("total", String.format("%.2f", total1))
                    put("count", filtered1.size)
                })
                put("period2", JSONObject().apply {
                    put("label", label2)
                    put("total", String.format("%.2f", total2))
                    put("count", filtered2.size)
                })
                put("diff", String.format("%.2f", diff))
                put("percentChange", String.format("%.1f", percentChange))
                put("bookName", bookName ?: "所有账本")
            },
            userMessage = "$label1 支出 ${String.format("%.2f", total1)} 元，$label2 支出 ${String.format("%.2f", total2)} 元，" +
                "变化 ${if (diff >= 0) "+" else ""}${String.format("%.2f", diff)} 元（${if (percentChange >= 0) "+" else ""}${String.format("%.1f", percentChange)}%）"
        )
    }

    private fun getPeriodLabel(period: String): String {
        return when (period) {
            "this_month" -> "本月"
            "last_month" -> "上月"
            "this_year" -> "本年"
            "last_year" -> "去年"
            else -> period
        }
    }

    private fun getPeriodRange(period: String): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis

        return when (period) {
            "this_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to now
            }
            "last_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val thisMonthStart = calendar.timeInMillis
                calendar.add(Calendar.MONTH, -1)
                val lastMonthStart = calendar.timeInMillis
                lastMonthStart to thisMonthStart
            }
            "this_year" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to now
            }
            "last_year" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val thisYearStart = calendar.timeInMillis
                calendar.add(Calendar.YEAR, -1)
                val lastYearStart = calendar.timeInMillis
                lastYearStart to thisYearStart
            }
            else -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to now
            }
        }
    }
}
