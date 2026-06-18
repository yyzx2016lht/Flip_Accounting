package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class CloudGetConfigTool(private val context: Context) : AgentTool {
    override val id = "cloud.get_config"
    override val category = "备份"
    override val risk = RiskLevel.READ
    override val description = "查询云备份配置状态（WebDAV地址和是否已配置）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val prefs = this.context.getSharedPreferences("cloud_prefs", Context.MODE_PRIVATE)
        val url = prefs.getString("webdav_url", "") ?: ""
        val hasPassword = prefs.getString("webdav_password", "")?.isNotBlank() == true
        val configured = url.isNotBlank() && hasPassword

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("configured", configured)
                put("url", if (url.isNotBlank()) url else "未配置")
                put("hasPassword", hasPassword)
            },
            userMessage = if (configured) {
                "云备份已配置：$url"
            } else {
                "云备份未配置。请前往备份设置页面配置 WebDAV"
            }
        )
    }
}
