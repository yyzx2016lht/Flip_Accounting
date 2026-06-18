package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import org.json.JSONObject

class CategoryListTool : AgentTool {
    override val id = "category.list"
    override val category = "分类"
    override val risk = RiskLevel.READ
    override val description = "列出支出或收入分类"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "分类类型：EXPENSE(支出分类), INCOME(收入分类)。默认 EXPENSE")
            })
        })
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val type = params.optString("type", "EXPENSE").trim().uppercase()

        val categories = context.queryContext.categories
        val filtered = when (type) {
            "INCOME" -> categories.filter { it.type == 1 }
            else -> categories.filter { it.type == 0 }
        }

        if (filtered.isEmpty()) {
            return AgentToolResult.success(userMessage = if (type == "INCOME") "暂无收入分类" else "暂无支出分类")
        }

        val typeLabel = if (type == "INCOME") "收入" else "支出"
        val sb = StringBuilder("${typeLabel}分类列表（${filtered.size} 个）：\n")
        for (cat in filtered) {
            sb.appendLine("• ${cat.name}")
        }

        return AgentToolResult.success(
            facts = JSONObject().apply {
                put("type", typeLabel)
                put("count", filtered.size)
                put("categories", filtered.map {
                    JSONObject().apply {
                        put("id", it.id)
                        put("name", it.name)
                    }
                })
            },
            userMessage = sb.toString().trim()
        )
    }
}
