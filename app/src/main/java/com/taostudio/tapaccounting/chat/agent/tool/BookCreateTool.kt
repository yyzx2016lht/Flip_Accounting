package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BookCreateTool(private val context: Context) : AgentTool {
    override val id = "book.create"
    override val category = "账本"
    override val risk = RiskLevel.WRITE
    override val description = "创建新账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "新账本名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("bookName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val bookName = params.optString("bookName", "").trim()
        if (bookName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定账本名称", listOf("bookName"))
        }
        if (bookName.length > 20) {
            return AgentValidationResult.invalidParams("账本名称不能超过20个字符", listOf("bookName"))
        }
        val existing = context.queryContext.availableBooks
        if (existing.any { it.equals(bookName, ignoreCase = true) }) {
            return AgentValidationResult.invalidParams("已存在同名账本「$bookName」", listOf("bookName"))
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val bookName = params.optString("bookName", "").trim()
        val success = BookAccountManager.addBookAccount(this.context, bookName)
        return if (success) {
            AgentToolResult.success(
                facts = JSONObject().apply { put("bookName", bookName) },
                userMessage = "已创建账本「$bookName」"
            )
        } else {
            AgentToolResult.failure("创建账本失败，可能已存在同名账本")
        }
    }
}
