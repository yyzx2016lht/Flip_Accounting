package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class GestureGetStatusTool(private val context: Context) : AgentTool {
    override val id = "gesture.get_status"
    override val category = "系统"
    override val risk = RiskLevel.READ
    override val description = "查询手势功能状态（翻转、双击、三击等）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val quickGestureEnabled = Prefs.isQuickGestureEnabled(this.context)
        val flipEnabled = Prefs.isFlipEnabled(this.context)
        val doubleTapEnabled = Prefs.isDoubleTapEnabled(this.context)
        val tripleTapEnabled = Prefs.isTapTripleEnabled(this.context)

        val sb = StringBuilder("手势功能状态：\n")
        sb.appendLine("• 快捷手势总开关：${if (quickGestureEnabled) "开启" else "关闭"}")
        sb.appendLine("• 翻转手势：${if (flipEnabled) "开启" else "关闭"}")
        sb.appendLine("• 双击手势：${if (doubleTapEnabled) "开启" else "关闭"}")
        sb.appendLine("• 三击手势：${if (tripleTapEnabled) "开启" else "关闭"}")

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("quickGestureEnabled", quickGestureEnabled)
                put("flipEnabled", flipEnabled)
                put("doubleTapEnabled", doubleTapEnabled)
                put("tripleTapEnabled", tripleTapEnabled)
            },
            userMessage = sb.toString().trim()
        )
    }
}
