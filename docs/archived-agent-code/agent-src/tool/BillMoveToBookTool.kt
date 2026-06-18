package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class BillMoveToBookTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.move_to_book"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "将账单移动到另一个账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "账单ID")
            })
            put("targetBookName", JSONObject().apply {
                put("type", "string")
                put("description", "目标账本名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("billId"); put("targetBookName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val billId = params.optLong("billId", 0)
        if (billId <= 0) {
            return AgentValidationResult.invalidParams("请提供有效的账单ID", listOf("billId"))
        }
        val bill = db.billDao().getBillById(billId)
        if (bill == null) {
            return AgentValidationResult.notFound("未找到ID为 $billId 的账单")
        }
        val targetBookName = params.optString("targetBookName", "").trim()
        if (targetBookName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定目标账本", listOf("targetBookName"))
        }
        val books = context.queryContext.availableBooks
        val match = books.find { it.equals(targetBookName, ignoreCase = true) }
            ?: books.find { it.contains(targetBookName, ignoreCase = true) }
        if (match == null) {
            return AgentValidationResult.notFound("未找到账本「$targetBookName」")
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        val targetBookName = params.optString("targetBookName", "").trim()

        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        val books = context.queryContext.availableBooks
        val resolvedBook = books.find { it.equals(targetBookName, ignoreCase = true) }
            ?: books.find { it.contains(targetBookName, ignoreCase = true) }
            ?: return AgentToolResult.failure("未找到账本「$targetBookName」")

        return try {
            val updatedBill = bill.copy(bookName = resolvedBook)
            db.billDao().updateBill(updatedBill)
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("billId", bill.id)
                    put("fromBook", bill.bookName)
                    put("toBook", resolvedBook)
                    put("categoryName", bill.categoryName)
                },
                userMessage = "已将「${bill.categoryName}」从「${bill.bookName}」移动到「$resolvedBook」"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("移动失败：${e.message}")
        }
    }
}
