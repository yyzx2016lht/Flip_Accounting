package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BackupListModulesTool : AgentTool {
    override val id = "backup.list_modules"
    override val category = "备份"
    override val risk = RiskLevel.READ
    override val description = "列出可备份的数据模块"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    private val modules = listOf(
        "bills" to "账单数据",
        "assets" to "资产账户",
        "categories" to "分类设置",
        "rules" to "记账规则",
        "chat_messages" to "聊天记录",
        "chat_media" to "聊天媒体",
        "banners" to "账本封面",
        "settings_general_basic" to "基础设置",
        "settings_ai_core" to "AI核心设置",
        "settings_ai_chat" to "AI聊天设置",
        "settings_books" to "账本设置"
    )

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val sb = StringBuilder("可备份的数据模块：\n")
        for ((id, name) in modules) {
            sb.appendLine("• $id: $name")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("modules", modules.map { (id, name) ->
                    JSONObject().apply {
                        put("id", id)
                        put("name", name)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
