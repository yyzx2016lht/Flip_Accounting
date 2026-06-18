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

class BackupImportCsvTool(private val context: Context) : AgentTool {
    override val id = "backup.import_csv"
    override val category = "备份"
    override val risk = RiskLevel.NAV
    override val description = "打开 CSV 导入页面（导入 CSV 会添加数据到当前账本，请在页面中手动选择文件并确认）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, BackupActivity::class.java).apply {
            putExtra("action", "import_csv")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开 CSV 导入页面。请在页面中选择 CSV 文件并确认导入。",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
