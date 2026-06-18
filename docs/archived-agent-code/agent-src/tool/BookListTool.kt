package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class BookListTool : AgentTool {
    override val id = "book.list"
    override val category = "账本"
    override val risk = RiskLevel.READ
    override val description = "列出所有账本"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val books = context.queryContext.availableBooks
        val currentBook = context.bookName

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("currentBook", currentBook)
                put("count", books.size)
                put("books", books)
            }
        )
    }
}
