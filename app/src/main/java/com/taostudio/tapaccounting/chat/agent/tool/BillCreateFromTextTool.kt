package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.chat.agent.AgentEffect
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class BillCreateFromTextTool(
    private val context: Context,
    private val db: AppDatabase
) : AgentTool {
    override val id = "bill.create_from_text"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "通过文本描述创建账单（如：午饭花了35）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "记账描述文本，如：午饭花了35、工资到账8000")
            })
        })
        put("required", org.json.JSONArray().apply { put("text") })
    }

    private val destructiveKeywords = listOf("删除", "删掉", "移除", "覆盖", "批量", "全部", "所有")

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentValidationResult.invalidParams("请提供记账描述", listOf("text"))
        }

        if (destructiveKeywords.any { text.contains(it) }) {
            return AgentValidationResult.invalidParams("记账描述包含危险关键词，请确认操作意图")
        }

        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentToolResult.failure("请提供记账描述")
        }

        return try {
            val result = AIService.analyzeAccounting(
                ctx = this.context,
                userInput = text,
                isMultiModeOverride = true,
                isFromChat = true
            )

            if (result == null) {
                return AgentToolResult.failure("无法识别记账内容，请重试")
            }

            val bills = result.optJSONArray("bills")
            if (bills == null || bills.length() == 0) {
                return AgentToolResult.failure("未识别到账单信息")
            }

            AgentToolResult.success(
                facts = result,
                effects = listOf(AgentEffect.ProcessAccountingResult(result, text))
            )
        } catch (e: Exception) {
            AgentToolResult.failure("记账失败：${e.message}")
        }
    }
}
