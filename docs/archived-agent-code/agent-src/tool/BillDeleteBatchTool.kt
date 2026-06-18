package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import org.json.JSONObject

class BillDeleteBatchTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.delete_batch"
    override val category = "记账"
    override val risk = RiskLevel.DESTRUCTIVE
    override val description = "批量删除多笔账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billIds", JSONObject().apply {
                put("type", "array")
                put("description", "要删除的账单ID列表")
                put("items", JSONObject().apply { put("type", "integer") })
            })
        })
        put("required", org.json.JSONArray().apply { put("billIds") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val billIdsArray = params.optJSONArray("billIds")
        if (billIdsArray == null || billIdsArray.length() == 0) {
            return AgentValidationResult.invalidParams("请提供要删除的账单ID列表", listOf("billIds"))
        }
        val billIds = (0 until billIdsArray.length()).map { billIdsArray.optLong(it, 0) }.filter { it > 0 }
        if (billIds.isEmpty()) {
            return AgentValidationResult.invalidParams("请提供有效的账单ID", listOf("billIds"))
        }

        for (billId in billIds) {
            val bill = db.billDao().getBillById(billId)
            if (bill == null) {
                return AgentValidationResult.notFound("未找到ID为 $billId 的账单")
            }
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billIdsArray = params.optJSONArray("billIds")
            ?: return AgentToolResult.failure("请提供账单ID列表")
        val billIds = (0 until billIdsArray.length()).map { billIdsArray.optLong(it, 0) }.filter { it > 0 }

        val bills = billIds.mapNotNull { db.billDao().getBillById(it) }
        if (bills.isEmpty()) {
            return AgentToolResult.failure("未找到要删除的账单")
        }

        return try {
            BillDeleteHelper.deleteBillsAndRevertBalance(db, bills)
            val totalAmount = bills.sumOf { it.amount }
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("deletedCount", bills.size)
                    put("totalAmount", String.format("%.2f", totalAmount))
                    put("billIds", billIds)
                },
                userMessage = "已删除 ${bills.size} 笔账单，合计 ${String.format("%.2f", totalAmount)} 元"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("批量删除失败：${e.message}")
        }
    }
}
