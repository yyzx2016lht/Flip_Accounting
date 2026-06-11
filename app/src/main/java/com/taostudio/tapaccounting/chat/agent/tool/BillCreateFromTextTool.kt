package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
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

    override suspend fun validate(
        params: JSONObject,
        context: AgentSessionContext
    ): AgentValidationResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentValidationResult.invalidParams("请提供记账描述", listOf("text"))
        }
        if (!containsAmount(text)) {
            return AgentValidationResult.invalidParams("请补充金额，例如“西瓜14元”", listOf("text"))
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val text = params.optString("text", "").trim()
        if (text.isEmpty()) {
            return AgentToolResult.failure("请提供记账描述")
        }

        // The existing accounting pipeline owns parsing, preview, persistence,
        // asset impact, and multi-bill handling. Do not duplicate that here.
        return AgentToolResult.success(uiAction = UiAction.StartAccounting(text))
    }

    companion object {
        private val AMOUNT_PATTERN = Regex("""(?<![\d.])\d+(?:\.\d{1,2})?(?![\d.])""")

        fun containsAmount(text: String): Boolean = AMOUNT_PATTERN.containsMatchIn(text)
    }
}
