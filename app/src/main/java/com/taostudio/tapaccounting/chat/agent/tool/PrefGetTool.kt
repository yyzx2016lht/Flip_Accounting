package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class PrefGetTool(private val context: Context) : AgentTool {
    override val id = "pref.get"
    override val category = "设置"
    override val risk = RiskLevel.READ
    override val description = "查询设置项的值"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("key", JSONObject().apply {
                put("type", "string")
                put("description", "设置项名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("key") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val key = params.optString("key", "").trim()
        if (key.isEmpty()) {
            return AgentToolResult.failure("请指定设置项名称")
        }

        val value = getPrefValue(key, context)
        return if (value != null) {
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("key", key)
                    put("value", value)
                },
                userMessage = "$key 的当前值为: $value"
            )
        } else {
            AgentToolResult.failure("未找到设置项: $key")
        }
    }

    private fun getPrefValue(key: String, sessionContext: AgentSessionContext): Any? {
        return when (key.lowercase()) {
            "ai_key", "apikey" -> "***已设置***"
            "ai_url" -> Prefs.getAiUrl(context)
            "ai_model" -> Prefs.getAiModel(context)
            "current_book" -> sessionContext.bookName
            "show_ai_text" -> Prefs.isShowAiText(context)
            "show_ai_voice" -> Prefs.isShowAiVoice(context)
            "show_ai_image" -> Prefs.isShowAiImage(context)
            "show_screen_accounting" -> Prefs.isShowScreenAccounting(context)
            "multi_bill_enabled" -> Prefs.isMultiBillEnabled(context)
            "vibrate_feedback" -> Prefs.isVibrateFeedbackEnabled(context)
            "logging_enabled" -> Prefs.isLoggingEnabled(context)
            else -> null
        }
    }
}
