package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BookGetCurrentTool : AgentTool {
    override val id = "book.get_current"
    override val category = "账本"
    override val risk = RiskLevel.READ
    override val description = "查询当前账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val currentBook = context.bookName
        val allBooks = context.queryContext.availableBooks

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("currentBook", currentBook)
                put("availableBooks", allBooks)
            },
            userMessage = "当前账本是「$currentBook」\n共有 ${allBooks.size} 个账本：${allBooks.joinToString("、")}"
        )
    }
}
