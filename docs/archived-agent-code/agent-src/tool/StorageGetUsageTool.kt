package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject
import java.io.File

class StorageGetUsageTool(private val context: Context) : AgentTool {
    override val id = "storage.get_usage"
    override val category = "系统"
    override val risk = RiskLevel.READ
    override val description = "查询应用存储占用情况"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val dataDir = this.context.filesDir.parentFile ?: this.context.filesDir
        val totalSize = getDirSize(dataDir)
        val dbSize = getDirSize(File(dataDir, "databases"))
        val sharedPrefsSize = getDirSize(File(dataDir, "shared_prefs"))
        val cacheSize = getDirSize(this.context.cacheDir)

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("totalSizeMB", String.format("%.2f", totalSize / 1024.0 / 1024.0))
                put("dbSizeMB", String.format("%.2f", dbSize / 1024.0 / 1024.0))
                put("prefsSizeMB", String.format("%.2f", sharedPrefsSize / 1024.0 / 1024.0))
                put("cacheSizeMB", String.format("%.2f", cacheSize / 1024.0 / 1024.0))
            },
            userMessage = "存储占用：总计 ${String.format("%.1f", totalSize / 1024.0 / 1024.0)} MB，" +
                "数据库 ${String.format("%.1f", dbSize / 1024.0 / 1024.0)} MB，" +
                "缓存 ${String.format("%.1f", cacheSize / 1024.0 / 1024.0)} MB"
        )
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        return dir.listFiles()?.sumOf { getDirSize(it) } ?: 0
    }
}
