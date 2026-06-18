package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.RoomQueryBillSource
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject
import java.util.Calendar

class BookQueryOverviewTool(private val db: AppDatabase) : AgentTool {
    override val id = "book.query_overview"
    override val category = "账本"
    override val risk = RiskLevel.READ
    override val description = "查询各账本的收支概览"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("timeRangeKey", JSONObject().apply {
                put("type", "string")
                put("description", "时间范围：this_month, last_month, this_year")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val timeRangeKey = params.optString("timeRangeKey", "this_month").trim().lowercase()

        val calendar = Calendar.getInstance()
        val now = calendar.timeInMillis

        val (startMillis, label) = when (timeRangeKey) {
            "last_month" -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                val thisMonthStart = calendar.timeInMillis
                calendar.add(Calendar.MONTH, -1)
                calendar.timeInMillis to "上月"
            }
            "this_year" -> {
                calendar.set(Calendar.DAY_OF_YEAR, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to "本年"
            }
            else -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1); calendar.set(Calendar.HOUR_OF_DAY, 0); calendar.set(Calendar.MINUTE, 0); calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis to "本月"
            }
        }

        val billSource = RoomQueryBillSource(db)
        val bills = billSource.loadBetween(startMillis, now, null)
        val books = context.queryContext.availableBooks

        val overviews = books.map { book ->
            val bookBills = bills.filter { it.bookName == book }
            val expense = bookBills.filter { it.type == 0 && it.subType != 4 }.sumOf { it.amount }
            val income = bookBills.filter { it.type == 1 }.sumOf { it.amount }
            Triple(book, expense, income)
        }

        val sb = StringBuilder("${label}各账本概览：\n")
        for ((book, expense, income) in overviews) {
            sb.appendLine("• $book: 支出 ${String.format("%.2f", expense)} 元, 收入 ${String.format("%.2f", income)} 元")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("timeRangeLabel", label)
                put("books", overviews.map { (book, expense, income) ->
                    JSONObject().apply {
                        put("name", book)
                        put("expense", String.format("%.2f", expense))
                        put("income", String.format("%.2f", income))
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
