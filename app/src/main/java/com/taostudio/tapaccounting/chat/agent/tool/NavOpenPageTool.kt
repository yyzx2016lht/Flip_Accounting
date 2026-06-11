package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.BackupActivity
import com.taostudio.tapaccounting.ChatSearchActivity
import com.taostudio.tapaccounting.LogViewerActivity
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import com.taostudio.tapaccounting.ui.activity.BookOverviewActivity
import com.taostudio.tapaccounting.ui.activity.HistoryBillActivity
import org.json.JSONObject

// Legacy tool - kept for backward compatibility
class NavOpenStatsTool(private val context: Context) : AgentTool {
    override val id = "nav.open_stats"
    override val category = "导航"
    override val risk = RiskLevel.NAV
    override val description = "打开统计页面"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val intent = Intent(this.context, MainActivity::class.java).apply {
            putExtra("tab", "stats")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            userMessage = "已打开统计页面",
            uiAction = UiAction.Navigate(intent)
        )
    }
}

// Unified navigation tool with whitelist
class NavOpenPageTool(private val context: Context) : AgentTool {
    override val id = "nav.open_page"
    override val category = "导航"
    override val risk = RiskLevel.NAV
    override val description = "打开应用内指定页面。可选页面：home(首页), stats(统计), assets(资产), settings(设置), backup(备份), book_overview(账本总览), recycle_bin(回收站), chat_search(聊天搜索), logs(日志)"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("page", JSONObject().apply {
                put("type", "string")
                put("description", "页面标识：home, stats, assets, settings, backup, book_overview, recycle_bin, chat_search, logs")
            })
        })
        put("required", org.json.JSONArray().apply { put("page") })
    }

    private val allowedPages = setOf(
        "home", "stats", "assets", "settings",
        "backup", "book_overview", "recycle_bin", "chat_search", "logs"
    )

    fun validatePage(page: String): String? {
        if (page.isEmpty()) return "请指定要打开的页面"
        if (page !in allowedPages) return "不支持的页面: $page，可选: ${allowedPages.joinToString(", ")}"
        return null
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val page = params.optString("page", "").trim().lowercase()
        val error = validatePage(page)
        if (error != null) return AgentToolResult.failure(error)

        val intent = when (page) {
            "home" -> Intent(this.context, MainActivity::class.java).apply { putExtra("tab", "home") }
            "stats" -> Intent(this.context, MainActivity::class.java).apply { putExtra("tab", "stats") }
            "assets" -> Intent(this.context, MainActivity::class.java).apply { putExtra("tab", "assets") }
            "settings" -> Intent(this.context, MainActivity::class.java).apply { putExtra("tab", "profile") }
            "backup" -> Intent(this.context, BackupActivity::class.java)
            "book_overview" -> Intent(this.context, BookOverviewActivity::class.java)
            "recycle_bin" -> Intent(this.context, HistoryBillActivity::class.java)
            "chat_search" -> Intent(this.context, ChatSearchActivity::class.java)
            "logs" -> Intent(this.context, LogViewerActivity::class.java)
            else -> return AgentToolResult.failure("不支持的页面: $page")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val pageName = when (page) {
            "home" -> "首页"; "stats" -> "统计页"; "assets" -> "资产页"; "settings" -> "设置页"
            "backup" -> "备份页"; "book_overview" -> "账本总览"; "recycle_bin" -> "回收站"
            "chat_search" -> "聊天搜索"; "logs" -> "日志页"; else -> page
        }

        return AgentToolResult.success(
            userMessage = "已打开$pageName",
            uiAction = UiAction.Navigate(intent)
        )
    }
}
