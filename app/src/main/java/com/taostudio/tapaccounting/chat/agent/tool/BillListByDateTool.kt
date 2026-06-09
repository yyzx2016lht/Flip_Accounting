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

class BillListByDateTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.list_by_date"
    override val category = "记账"
    override val risk = RiskLevel.READ
    override val description = "按日期查询账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("date", JSONObject().apply {
                put("type", "string")
                put("description", "日期，格式：yyyy-MM-dd，如：2026-06-08")
            })
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "账本名称，不填则查所有账本")
            })
        })
        put("required", org.json.JSONArray().apply { put("date") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val dateStr = params.optString("date", "").trim()
        if (dateStr.isEmpty()) {
            return AgentToolResult.failure("请指定日期")
        }
        val bookName = params.optString("bookName", "").trim().ifBlank { null }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val date = try { dateFormat.parse(dateStr) } catch (e: Exception) {
            return AgentToolResult.failure("日期格式错误，请使用yyyy-MM-dd格式")
        }

        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startOfDay = calendar.timeInMillis
        calendar.add(Calendar.DAY_OF_MONTH, 1)
        val endOfDay = calendar.timeInMillis

        var bills = db.billDao().getBillsBetweenTimesList(startOfDay, endOfDay)
        
        if (bookName != null) {
            bills = bills.filter { it.bookName.contains(bookName, ignoreCase = true) }
        }

        val totalExpense = bills.filter { it.type == 0 && it.subType != 4 }.sumOf { it.amount }
        val totalIncome = bills.filter { it.type == 1 }.sumOf { it.amount }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("date", dateStr)
                put("bookName", bookName ?: "所有账本")
                put("billCount", bills.size)
                put("totalExpense", String.format("%.2f", totalExpense))
                put("totalIncome", String.format("%.2f", totalIncome))
                put("bills", bills.map { bill ->
                    JSONObject().apply {
                        put("id", bill.id)
                        put("amount", String.format("%.2f", bill.amount))
                        put("category", bill.categoryName)
                        put("type", if (bill.type == 0) "支出" else "收入")
                        put("remark", bill.remark)
                    }
                })
            }
        )
    }
}
