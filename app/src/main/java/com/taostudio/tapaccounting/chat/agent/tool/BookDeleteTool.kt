package com.taostudio.tapaccounting.chat.agent.tool

import android.content.Context
import android.content.Intent
import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.agent.UiAction
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.activity.BookOverviewActivity
import org.json.JSONObject

class BookDeleteTool(private val context: Context, private val db: AppDatabase) : AgentTool {
    override val id = "book.delete"
    override val category = "账本"
    override val risk = RiskLevel.NAV
    override val description = "打开账本管理页删除账本（需在页面中选择删除方式并确认）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("bookName", JSONObject().apply {
                put("type", "string")
                put("description", "要删除的账本名称")
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
        if (books.size <= 1) {
            return AgentValidationResult.invalidParams("至少需要保留一个账本，无法删除")
        }
        return when (val resolved = resolveBook(bookName, books)) {
            is BookResolveResult.Found -> AgentValidationResult.success()
            is BookResolveResult.NotFound -> AgentValidationResult.notFound("未找到账本「$bookName」")
            is BookResolveResult.Ambiguous -> {
                AgentValidationResult.ambiguous("找到多个匹配账本：${resolved.books.joinToString("、")}，请明确指定")
            }
        }
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val bookName = params.optString("bookName", "").trim()
        val resolvedName = when (val resolved = resolveBook(bookName, context.queryContext.availableBooks)) {
            is BookResolveResult.Found -> resolved.book
            is BookResolveResult.NotFound -> return AgentToolResult.failure("未找到账本「$bookName」")
            is BookResolveResult.Ambiguous -> return AgentToolResult.failure("找到多个匹配账本：${resolved.books.joinToString("、")}，请明确指定")
        }
        val billCount = db.billDao().countBillsByBookName(resolvedName)

        val intent = Intent(this.context, BookOverviewActivity::class.java).apply {
            putExtra(BookOverviewActivity.EXTRA_CURRENT_BOOK, resolvedName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("bookName", resolvedName)
                put("relatedBillCount", billCount)
            },
            userMessage = buildString {
                append("已打开账本管理页。请在页面中删除「$resolvedName」并选择删除方式")
                if (billCount > 0) append("；该账本当前有 $billCount 笔关联账单")
                append("。")
            },
            uiAction = UiAction.Navigate(intent)
        )
    }

    private fun resolveBook(input: String, books: List<String>): BookResolveResult {
        val exact = books.filter { it.equals(input, ignoreCase = true) }
        if (exact.size == 1) return BookResolveResult.Found(exact.first())
        if (exact.size > 1) return BookResolveResult.Ambiguous(exact)

        val fuzzy = books.filter { it.contains(input, ignoreCase = true) }
        return when (fuzzy.size) {
            0 -> BookResolveResult.NotFound
            1 -> BookResolveResult.Found(fuzzy.first())
            else -> BookResolveResult.Ambiguous(fuzzy)
        }
    }

    private sealed class BookResolveResult {
        data class Found(val book: String) : BookResolveResult()
        data class Ambiguous(val books: List<String>) : BookResolveResult()
        data object NotFound : BookResolveResult()
    }
}
