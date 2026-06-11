package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BillRestoreHelper
import org.json.JSONObject

class BillRestoreFromBinTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.restore_from_bin"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "从回收站恢复已删除的账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "要恢复的已删除账单ID")
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
            val restored = BillRestoreHelper.restoreBills(db, listOf(deletedBill))
            if (restored.isNotEmpty()) {
                val bill = restored.first()
                AgentToolResult.success(
                    facts = JSONObject().apply {
                        put("billId", bill.id)
                        put("categoryName", bill.categoryName)
                        put("amount", bill.amount)
                    },
                    userMessage = "已恢复账单：${bill.categoryName} ${bill.amount}元"
                )
            } else {
                AgentToolResult.failure("恢复失败")
            }
        } catch (e: Exception) {
            AgentToolResult.failure("恢复失败：${e.message}")
        }
    }
}
