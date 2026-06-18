package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CalendarQueryDayTool(private val db: AppDatabase) : AgentTool {
    override val id = "calendar.query_day"
    override val category = "统计"
    override val risk = RiskLevel.READ
    override val description = "查询指定日期的账单列表"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("date", JSONObject().apply {
                put("type", "string")
                put("description", "日期，格式 yyyy-MM-dd，如 2026-06-10。不填则为今天")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val dateStr = params.optString("date", "").trim()
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()

        if (dateStr.isNotBlank()) {
            try {
                calendar.time = dateFormat.parse(dateStr)!!
            } catch (e: Exception) {
                return AgentToolResult.failure("日期格式错误，请使用 yyyy-MM-dd 格式")
            }
        }

        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startMillis = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endMillis = calendar.timeInMillis

        val allBills = db.billDao().getRecentBills(500)
        var bills = allBills.filter { it.time in startMillis until endMillis }

        if (bookName != null) {
            bills = bills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        val expense = bills.filter { it.type == 0 && it.subType != 4 }.sumOf { it.amount }
        val income = bills.filter { it.type == 1 }.sumOf { it.amount }

        val displayDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val sb = StringBuilder("${dateFormat.format(Date(startMillis))} 账单（${bills.size} 笔）：\n")
        for (bill in bills) {
            val type = when (bill.type) {
                0 -> "支出"; 1 -> "收入"; 2 -> "转账"; 3 -> "还款"; 4 -> "退款"; else -> "其他"
            }
            sb.appendLine("• ${displayDateFormat.format(Date(bill.time))} ${bill.categoryName} $type ${bill.amount} ${bill.currency}")
        }

        if (bills.isEmpty()) {
            sb.append("暂无账单")
        }
        sb.appendLine("\n支出: ${String.format("%.2f", expense)} 元, 收入: ${String.format("%.2f", income)} 元")

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("date", dateFormat.format(Date(startMillis)))
                put("billCount", bills.size)
                put("expense", String.format("%.2f", expense))
                put("income", String.format("%.2f", income))
                put("bills", bills.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("categoryName", it.categoryName)
                        put("amount", it.amount)
                        put("type", it.type)
                        put("time", displayDateFormat.format(Date(it.time)))
                        put("currency", it.currency)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
