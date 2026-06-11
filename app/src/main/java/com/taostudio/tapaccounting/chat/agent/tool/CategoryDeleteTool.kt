package com.taostudio.tapaccounting.chat.agent.tool

import com.taostudio.tapaccounting.chat.agent.AgentTool
import com.taostudio.tapaccounting.chat.agent.AgentToolResult
import com.taostudio.tapaccounting.chat.agent.AgentSessionContext
import com.taostudio.tapaccounting.chat.agent.AgentValidationResult
import com.taostudio.tapaccounting.chat.agent.RiskLevel
import com.taostudio.tapaccounting.chat.query.QueryCategoryOption
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import androidx.room.withTransaction
import org.json.JSONObject

class CategoryDeleteTool(private val db: AppDatabase) : AgentTool {
    override val id = "category.delete"
    override val category = "分类"
    override val risk = RiskLevel.DESTRUCTIVE
    override val description = "删除分类（关联账单的分类将被清空，子分类会被一并删除）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("categoryName", JSONObject().apply {
                put("type", "string")
                put("description", "要删除的分类名称")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "分类类型：EXPENSE(支出) 或 INCOME(收入)。当同名分类存在于不同类型时必填")
            })
        })
        put("required", org.json.JSONArray().apply { put("categoryName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val categoryName = params.optString("categoryName", "").trim()
        if (categoryName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定分类名称", listOf("categoryName"))
        }

        return when (val resolved = resolveCategoryOption(categoryName, params.optString("type", ""), context.queryContext.categories)) {
            is CategoryOptionResolveResult.Found -> AgentValidationResult.success()
            is CategoryOptionResolveResult.NotFound -> AgentValidationResult.notFound("未找到分类「$categoryName」")
            is CategoryOptionResolveResult.Ambiguous -> {
                AgentValidationResult.ambiguous(
                    "找到多个同名分类「$categoryName」，请指定类型：EXPENSE 或 INCOME"
                )
            }
            is CategoryOptionResolveResult.InvalidType -> {
                AgentValidationResult.invalidParams("分类类型只支持 EXPENSE 或 INCOME", listOf("type"))
            }
        }
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val categoryName = params.optString("categoryName", "").trim()
        val typeFilter = params.optString("type", "").trim().uppercase()

        val categories = db.categoryDao().getAllCategoriesList()
        val target = when (val resolved = resolveCategory(categoryName, typeFilter, categories)) {
            is CategoryResolveResult.Found -> resolved.category
            is CategoryResolveResult.NotFound -> return AgentToolResult.failure("未找到分类「$categoryName」")
            is CategoryResolveResult.Ambiguous -> return AgentToolResult.failure("找到多个同名分类「$categoryName」，请指定类型：EXPENSE 或 INCOME")
            is CategoryResolveResult.InvalidType -> return AgentToolResult.failure("分类类型只支持 EXPENSE 或 INCOME")
        }

        val repository = CategoryRepository(db.categoryDao(), db.billDao())
        val billCount = repository.countBillsUnderCategory(target.id)
        val children = db.categoryDao().getChildrenByParentId(target.id)

        return try {
            db.withTransaction {
                repository.deleteCategoryAndMigrateBills(target.id, null)
            }

            val typeLabel = if (target.type == 1) "收入" else "支出"
            val msg = buildString {
                append("已删除${typeLabel}分类「${target.name}」")
                if (children.isNotEmpty()) append("及其 ${children.size} 个子分类")
                if (billCount > 0) append("，$billCount 笔关联账单的分类已清空")
            }
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("categoryId", target.id)
                    put("categoryName", target.name)
                    put("type", typeLabel)
                    put("affectedBillCount", billCount)
                    put("deletedChildCount", children.size)
                },
                userMessage = msg
            )
        } catch (e: Exception) {
            AgentToolResult.failure("删除分类失败：${e.message}")
        }
    }

    private fun resolveCategory(input: String, typeHint: String, categories: List<Category>): CategoryResolveResult {
        val normalizedType = typeHint.trim().uppercase()
        val typeFilter = when (normalizedType) {
            "" -> null
            "INCOME" -> 1
            "EXPENSE" -> 0
            else -> return CategoryResolveResult.InvalidType
        }
        val matches = categories.filter {
            it.name.equals(input, ignoreCase = true) && (typeFilter == null || it.type == typeFilter)
        }
        return when (matches.size) {
            0 -> CategoryResolveResult.NotFound
            1 -> CategoryResolveResult.Found(matches.first())
            else -> CategoryResolveResult.Ambiguous(matches)
        }
    }

    private sealed class CategoryResolveResult {
        data class Found(val category: Category) : CategoryResolveResult()
        data class Ambiguous(val categories: List<Category>) : CategoryResolveResult()
        data object NotFound : CategoryResolveResult()
        data object InvalidType : CategoryResolveResult()
    }

    private fun resolveCategoryOption(
        input: String,
        typeHint: String,
        categories: List<QueryCategoryOption>
    ): CategoryOptionResolveResult {
        val normalizedType = typeHint.trim().uppercase()
        val typeFilter = when (normalizedType) {
            "" -> null
            "INCOME" -> 1
            "EXPENSE" -> 0
            else -> return CategoryOptionResolveResult.InvalidType
        }
        val matches = categories.filter {
            it.name.equals(input, ignoreCase = true) && (typeFilter == null || it.type == typeFilter)
        }
        return when (matches.size) {
            0 -> CategoryOptionResolveResult.NotFound
            1 -> CategoryOptionResolveResult.Found(matches.first())
            else -> CategoryOptionResolveResult.Ambiguous(matches)
        }
    }

    private sealed class CategoryOptionResolveResult {
        data class Found(val category: QueryCategoryOption) : CategoryOptionResolveResult()
        data class Ambiguous(val categories: List<QueryCategoryOption>) : CategoryOptionResolveResult()
        data object NotFound : CategoryOptionResolveResult()
        data object InvalidType : CategoryOptionResolveResult()
    }
}
