package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class PrefSetTool(private val context: Context) : AgentTool {
    override val id = "pref.set"
    override val category = "设置"
    override val risk = RiskLevel.WRITE
    override val description = "修改设置项的值"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("key", JSONObject().apply {
                put("type", "string")
                put("description", "设置项名称")
            })
            put("value", JSONObject().apply {
                put("description", "设置项的值")
            })
        })
        put("required", org.json.JSONArray().apply { put("key"); put("value") })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val key = params.optString("key", "").trim()
        if (key.isEmpty()) {
            return AgentToolResult.failure("请指定设置项名称")
        }

        val value = params.opt("value")
        if (value == null) {
            return AgentToolResult.failure("请提供设置项的值")
        }

        return try {
            setPrefValue(key, value)
            AgentToolResult.success(
                userMessage = "已将 $key 设置为: $value"
            )
        } catch (e: Exception) {
            AgentToolResult.failure("设置失败：${e.message}")
        }
    }

    private fun setPrefValue(key: String, value: Any) {
        when (key.lowercase()) {
            "show_ai_text" -> Prefs.setShowAiText(context, value as Boolean)
            "show_ai_voice" -> Prefs.setShowAiVoice(context, value as Boolean)
            "show_ai_image" -> Prefs.setShowAiImage(context, value as Boolean)
            "show_screen_accounting" -> Prefs.setShowScreenAccounting(context, value as Boolean)
            "multi_bill_enabled" -> Prefs.setMultiBillEnabled(context, value as Boolean)
            "multi_bill_fast_mode" -> Prefs.setMultiBillFastMode(context, value as Boolean)
            "vibrate_feedback" -> Prefs.setVibrateFeedbackEnabled(context, value as Boolean)
            "logging_enabled" -> Prefs.setLoggingEnabled(context, value as Boolean)
            else -> throw IllegalArgumentException("不支持修改的设置项: $key")
        }
    }
}
