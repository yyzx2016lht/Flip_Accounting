package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONArray
import org.json.JSONObject

class BillModifyByInstructionTool(
    private val context: Context,
    private val db: AppDatabase
) : AgentTool {
    override val id = "bill.modify_by_instruction"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "用自然语言修改账单（如：刚才那笔改成40）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("instruction", JSONObject().apply {
                put("type", "string")
                put("description", "修改指令，如：刚才那笔改成40、把咖啡的备注改成星巴克")
            })
        })
        put("required", org.json.JSONArray().apply { put("instruction") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val instruction = params.optString("instruction", "").trim()
        if (instruction.isEmpty()) {
            return AgentToolResult.failure("请提供修改指令")
        }

        return try {
            val recentBills = db.billDao().getRecentBills(5)
            val oldBillJson = JSONArray().apply {
                recentBills.forEach { bill ->
                    put(JSONObject().apply {
                        put("id", bill.id)
                        put("amount", bill.amount)
                        put("categoryName", bill.categoryName)
                        put("remark", bill.remark)
                        put("accountName", bill.accountName)
                    })
                }
            }.toString()

            val result = AIService.generateAccountingModifyReply(
                ctx = this.context,
                userInput = instruction,
                oldBillJson = oldBillJson
            )
            
            if (result.isNotBlank()) {
                AgentToolResult.success(userMessage = result)
            } else {
                AgentToolResult.failure("无法理解修改指令")
            }
        } catch (e: Exception) {
            AgentToolResult.failure("修改失败：${e.message}")
        }
    }
}
