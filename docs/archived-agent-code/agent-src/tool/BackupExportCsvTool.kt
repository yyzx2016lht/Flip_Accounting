package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.BackupActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import org.json.JSONObject

class BackupExportCsvTool(private val context: Context) : AgentTool {
    override val id = "backup.export_csv"
    override val category = "备份"
    override val risk = RiskLevel.NAV
    override val description = "打开备份页面进行CSV导出"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, BackupActivity::class.java).apply {
            putExtra("action", "export_csv")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开CSV导出页面，请选择保存位置",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
