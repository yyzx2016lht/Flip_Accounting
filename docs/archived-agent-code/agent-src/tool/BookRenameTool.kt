package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BookRenameTool(private val context: Context) : AgentTool {
    override val id = "book.rename"
    override val category = "账本"
    override val risk = RiskLevel.WRITE
    override val description = "重命名账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("oldName", JSONObject().apply {
                put("type", "string")
                put("description", "当前账本名称")
            })
            put("newName", JSONObject().apply {
                put("type", "string")
                put("description", "新账本名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("oldName"); put("newName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val oldName = params.optString("oldName", "").trim()
        val newName = params.optString("newName", "").trim()
        if (oldName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定当前账本名称", listOf("oldName"))
        }
        if (newName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定新账本名称", listOf("newName"))
        }
        if (newName.length > 20) {
            return AgentValidationResult.invalidParams("账本名称不能超过20个字符", listOf("newName"))
        }
        val books = context.queryContext.availableBooks
        val match = books.find { it.equals(oldName, ignoreCase = true) }
            ?: books.find { it.contains(oldName, ignoreCase = true) }
        if (match == null) {
            return AgentValidationResult.notFound("未找到账本「$oldName」")
        }
        if (books.any { it.equals(newName, ignoreCase = true) }) {
            return AgentValidationResult.invalidParams("已存在同名账本「$newName」", listOf("newName"))
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val oldName = params.optString("oldName", "").trim()
        val newName = params.optString("newName", "").trim()
        val books = context.queryContext.availableBooks
        val resolvedOld = books.find { it.equals(oldName, ignoreCase = true) }
            ?: books.find { it.contains(oldName, ignoreCase = true) }
            ?: return AgentToolResult.failure("未找到账本「$oldName」")

        val success = BookAccountManager.renameBookAccount(this.context, resolvedOld, newName)
        return if (success) {
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("oldName", resolvedOld)
                    put("newName", newName)
                },
                userMessage = "已将账本「$resolvedOld」重命名为「$newName」"
            )
        } else {
            AgentToolResult.failure("重命名失败")
        }
    }
}
