package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQueryYearSummaryTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_year_summary"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询本年度收支总览（收入、支出、结余）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("year", JSONObject().apply {
                put("type", "integer")
                put("description", "年份，如 2026。不填则为当前年")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val year = params.optInt("year", Calendar.getInstance().get(Calendar.YEAR))
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val calendar = Calendar.getInstance()
        calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startMillis = calendar.timeInMillis

        calendar.set(year + 1, Calendar.JANUARY, 1, 0, 0, 0)
        val endMillis = calendar.timeInMillis

        val billSource = RoomQueryBillSource(db)
        var bills = billSource.loadBetween(startMillis, endMillis, null)

        if (bookName != null) {
            bills = bills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        val expense = bills.filter { it.type == 0 && it.subType != 4 }.sumOf { it.amount }
        val income = bills.filter { it.type == 1 }.sumOf { it.amount }
        val balance = income - expense

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("year", year)
                put("bookName", bookName ?: "所有账本")
                put("expense", String.format("%.2f", expense))
                put("income", String.format("%.2f", income))
                put("balance", String.format("%.2f", balance))
                put("billCount", bills.size)
            },
            userMessage = "${year}年收支总览：支出 ${String.format("%.2f", expense)} 元，收入 ${String.format("%.2f", income)} 元，结余 ${String.format("%.2f", balance)} 元"
        )
    }
}
