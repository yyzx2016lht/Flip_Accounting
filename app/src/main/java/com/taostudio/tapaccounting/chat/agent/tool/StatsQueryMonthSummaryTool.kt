package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class StatsQueryMonthSummaryTool(private val db: AppDatabase) : AgentTool {
    override val id = "stats.query_month_summary"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询本月收支总览"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val bookName = params.optString("bookName", "").trim().ifBlank { null }
        
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfMonth = calendar.timeInMillis
        val now = System.currentTimeMillis()

        var bills = db.billDao().getBillsBetweenTimesList(startOfMonth, now)
        
        if (bookName != null) {
            bills = bills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        val income = bills.filter { it.type == 1 }.sumOf { it.amount }
        val expense = bills.filter { it.type == 0 && it.subType != 4 }.sumOf { it.amount }
        val balance = income - expense

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("bookName", bookName ?: "所有账本")
                put("income", String.format("%.2f", income))
                put("expense", String.format("%.2f", expense))
                put("balance", String.format("%.2f", balance))
                put("billCount", bills.size)
            }
        )
    }
}
