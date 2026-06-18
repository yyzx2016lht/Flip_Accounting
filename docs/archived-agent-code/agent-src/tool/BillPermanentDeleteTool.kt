package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class BillPermanentDeleteTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.permanent_delete"
    override val category = "记账"
    override val risk = RiskLevel.DESTRUCTIVE
    override val description = "从回收站永久删除账单（不可恢复）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "回收站中的账单ID")
            })
        })
        put("required", org.json.JSONArray().apply { put("billId") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val billId = params.optLong("billId", 0)
        if (billId <= 0) {
            return AgentValidationResult.invalidParams("请提供有效的账单ID", listOf("billId"))
        }
        val deletedBill = db.deletedBillDao().getDeletedBillById(billId)
        if (deletedBill == null) {
            return AgentValidationResult.notFound("回收站中未找到ID为 $billId 的账单")
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        val deletedBill = db.deletedBillDao().getDeletedBillById(billId)
            ?: return AgentToolResult.failure("回收站中未找到ID为 $billId 的账单")

        return try {
            db.deletedBillDao().delete(deletedBill)
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("billId", deletedBill.id)
                    put("categoryName", deletedBill.categoryName)
                    put("amount", deletedBill.amount)
                },
                userMessage = "已永久删除账单：${deletedBill.categoryName} ${String.format("%.2f", deletedBill.amount)}元（不可恢复）"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("永久删除失败：${e.message}")
        }
    }
}
