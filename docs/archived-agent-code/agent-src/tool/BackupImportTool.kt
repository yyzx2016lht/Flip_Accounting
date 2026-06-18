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

class BackupImportTool(private val context: Context) : AgentTool {
    override val id = "backup.import"
    override val category = "备份"
    override val risk = RiskLevel.NAV
    override val description = "打开备份恢复页面（导入备份会覆盖现有数据，请在页面中手动选择文件并确认）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, BackupActivity::class.java).apply {
            putExtra("action", "import")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开备份恢复页面。请注意：导入备份会覆盖现有数据，请在页面中仔细确认后再操作。",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
