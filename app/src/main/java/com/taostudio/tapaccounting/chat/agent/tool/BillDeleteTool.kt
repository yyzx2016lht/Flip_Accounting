package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BillDeleteHelper
import org.json.JSONObject

class BillDeleteTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.delete"
    override val category = "记账"
    override val risk = RiskLevel.DESTRUCTIVE
    override val description = "删除单笔账单"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "账单ID")
            })
        })
        put("required", org.json.JSONArray().apply { put("billId") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        if (billId <= 0) {
            return AgentToolResult.failure("请提供有效的账单ID")
        }

        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        return try {
            BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
            AgentToolResult.success(
                userMessage = "已删除账单：${bill.categoryName} ${String.format("%.2f", bill.amount)}元"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("删除失败：${e.message}")
        }
    }
}
