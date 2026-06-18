package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class PermGetStatusTool(private val context: Context) : AgentTool {
    override val id = "perm.get_status"
    override val category = "系统"
    override val risk = RiskLevel.READ
    override val description = "查询应用权限状态（悬浮窗、无障碍等）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val canDrawOverlay = Settings.canDrawOverlays(this.context)
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()

        val sb = StringBuilder("权限状态：\n")
        sb.appendLine("• 悬浮窗权限：${if (canDrawOverlay) "已授权" else "未授权"}")
        sb.appendLine("• 无障碍服务：${if (isAccessibilityEnabled) "已开启" else "未开启"}")

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("overlay", canDrawOverlay)
                put("accessibility", isAccessibilityEnabled)
            },
            userMessage = sb.toString().trim()
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val serviceName = "${this.context.packageName}/.OverlayService"
            val enabledServices = Settings.Secure.getString(
                this.context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabledServices.contains(serviceName, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
