package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class BillCreateTransferTool(
    private val context: Context,
    private val db: AppDatabase
) : AgentTool {
    override val id = "bill.create_transfer"
    override val category = "记账"
    override val risk = RiskLevel.WRITE
    override val description = "创建转账记录（从一个资产转到另一个资产）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "转账描述，如：从微信转100到支付宝")
            })
        })
        put("required", org.json.JSONArray().apply { put("text") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentValidationResult.invalidParams("请提供转账描述", listOf("text"))
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentToolResult.failure("请提供转账描述")
        }

        return try {
            val result = AIService.analyzeAccounting(
                ctx = this.context,
                userInput = text,
                isMultiModeOverride = true,
                isFromChat = true
            )

            if (result == null) {
                return AgentToolResult.failure("无法识别转账内容，请重试")
            }

            val bills = result.optJSONArray("bills")
            if (bills == null || bills.length() == 0) {
                return AgentToolResult.failure("未识别到转账信息")
            }

            AgentToolResult.success(
                facts = result
            )
        } catch (e: Exception) {
            AgentToolResult.failure("转账失败：${e.message}")
        }
    }
}
