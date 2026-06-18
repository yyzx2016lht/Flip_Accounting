package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class BillToggleExcludeStatsTool(private val db: AppDatabase) : AgentTool {
    override val id = "bill.toggle_exclude_stats"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "切换账单是否计入统计"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("billId", JSONObject().apply {
                put("type", "integer")
                put("description", "账单ID")
            })
            put("exclude", JSONObject().apply {
                put("type", "boolean")
                put("description", "是否排除统计。true=不计入，false=计入")
            })
        })
        put("required", org.json.JSONArray().apply { put("billId") })
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
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val billId = params.optLong("billId", 0)
        val bill = db.billDao().getBillById(billId)
            ?: return AgentToolResult.failure("未找到ID为 $billId 的账单")

        // Toggle: if exclude param not provided, toggle current state
        val hasExplicitExclude = params.has("exclude")
        val newExclude = if (hasExplicitExclude) params.optBoolean("exclude", false) else !bill.excludeFromStats

        return try {
            db.billDao().updateExcludeStats(billId, newExclude)
            val action = if (newExclude) "已排除" else "已计入"
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("billId", bill.id)
                    put("excludeFromStats", newExclude)
                    put("categoryName", bill.categoryName)
                },
                userMessage = "「${bill.categoryName}」${action}统计"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("操作失败：${e.message}")
        }
    }
}
