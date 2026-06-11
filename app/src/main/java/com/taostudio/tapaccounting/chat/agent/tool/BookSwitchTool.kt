package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BookSwitchTool(private val context: Context) : AgentTool {
    override val id = "book.switch"
    override val category = "账本"
    override val risk = RiskLevel.WRITE
    override val description = "切换当前账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "要切换到的账本名称")
            })
        })
        put("required", org.json.JSONArray().apply { put("bookName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val bookName = params.optString("bookName", "").trim()
        if (bookName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定账本名称", listOf("bookName"))
        }
        val books = context.queryContext.availableBooks
        val match = books.find { it.equals(bookName, ignoreCase = true) }
        if (match == null) {
            val candidates = books.filter { it.contains(bookName, ignoreCase = true) }
            if (candidates.size > 1) {
                return AgentValidationResult.ambiguous("找到多个匹配账本：${candidates.joinToString("、")}，请明确指定")
            }
            if (candidates.isEmpty()) {
                return AgentValidationResult.notFound("未找到名为「$bookName」的账本，可用账本：${books.joinToString("、")}")
            }
        }
        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val bookName = params.optString("bookName", "").trim()
        val books = context.queryContext.availableBooks
        val resolvedName = books.find { it.equals(bookName, ignoreCase = true) }
            ?: books.find { it.contains(bookName, ignoreCase = true) }
            ?: return AgentToolResult.failure("未找到账本「$bookName」")

        BookAccountManager.setSelectedBook(this.context, resolvedName)
        return AgentToolResult.success(
            facts = JSONObject().apply { put("bookName", resolvedName) },
            userMessage = "已切换到账本「$resolvedName」"
        )
    }
}
