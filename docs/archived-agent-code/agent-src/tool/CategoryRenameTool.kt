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
import org.json.JSONObject

class CategoryRenameTool(private val db: AppDatabase) : AgentTool {
    override val id = "category.rename"
    override val category = "分类"
    override val risk = RiskLevel.WRITE
    override val description = "重命名分类（会同步更新关联账单中的分类名称）"
    override val parameterSchema = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("oldName", JSONObject().apply {
                put("type", "string")
                put("description", "当前分类名称")
            })
            put("newName", JSONObject().apply {
                put("type", "string")
                put("description", "新分类名称")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "分类类型：EXPENSE(支出) 或 INCOME(收入)。当同名分类存在于不同类型时必填")
            })
        })
        put("required", org.json.JSONArray().apply { put("oldName"); put("newName") })
    }

    override suspend fun validate(params: JSONObject, context: AgentSessionContext): AgentValidationResult {
        val oldName = params.optString("oldName", "").trim()
        val newName = params.optString("newName", "").trim()
        if (oldName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定当前分类名称", listOf("oldName"))
        }
        if (newName.isEmpty()) {
            return AgentValidationResult.invalidParams("请指定新分类名称", listOf("newName"))
        }
        if (newName.length > 20) {
            return AgentValidationResult.invalidParams("分类名称不能超过20个字符", listOf("newName"))
        }

        when (val resolved = resolveCategoryOption(oldName, params.optString("type", ""), context.queryContext.categories)) {
            is CategoryOptionResolveResult.Found -> Unit
            is CategoryOptionResolveResult.NotFound -> return AgentValidationResult.notFound("未找到分类「$oldName」")
            is CategoryOptionResolveResult.Ambiguous -> {
                return AgentValidationResult.ambiguous(
                    "找到多个同名分类「$oldName」，请指定类型：EXPENSE 或 INCOME"
                )
            }
            is CategoryOptionResolveResult.InvalidType -> {
                return AgentValidationResult.invalidParams("分类类型只支持 EXPENSE 或 INCOME", listOf("type"))
            }
        }

        return AgentValidationResult.success()
    }

    override suspend fun execute(params: JSONObject, context: AgentSessionContext): AgentToolResult {
        val oldName = params.optString("oldName", "").trim()
        val newName = params.optString("newName", "").trim()
        val typeFilter = params.optString("type", "").trim().uppercase()

        val categories = db.categoryDao().getAllCategoriesList()
        val target = when (val resolved = resolveCategory(oldName, typeFilter, categories)) {
            is CategoryResolveResult.Found -> resolved.category
            is CategoryResolveResult.NotFound -> return AgentToolResult.failure("未找到分类「$oldName」")
            is CategoryResolveResult.Ambiguous -> return AgentToolResult.failure("找到多个同名分类「$oldName」，请指定类型：EXPENSE 或 INCOME")
            is CategoryResolveResult.InvalidType -> return AgentToolResult.failure("分类类型只支持 EXPENSE 或 INCOME")
        }

        return try {
            val hasSameLevelConflict = categories.any {
                it.id != target.id &&
                    it.type == target.type &&
                    it.parentId == target.parentId &&
                    it.name.equals(newName, ignoreCase = true)
            }
            if (hasSameLevelConflict) {
                return AgentToolResult.failure("已存在同名分类「$newName」")
            }
            val billCount = db.billDao().countBillsByCategoryId(target.id) +
                db.billDao().countBillsByCategoryName(target.name)
            val updated = target.copy(name = newName)
            CategoryRepository(db.categoryDao(), db.billDao()).updateCategory(updated)

            val typeLabel = if (target.type == 1) "收入" else "支出"
            AgentToolResult.success(
                facts = JSONObject().apply {
                    put("categoryId", target.id)
                    put("oldName", oldName)
                    put("newName", newName)
                    put("type", typeLabel)
                    put("syncedBillCount", billCount)
                },
                userMessage = "已将${typeLabel}分类「$oldName」重命名为「$newName」" +
                    if (billCount > 0) "，同步更新了 $billCount 笔账单" else ""
            )
        } catch (e: Exception) {
            AgentToolResult.failure("重命名分类失败：${e.message}")
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
