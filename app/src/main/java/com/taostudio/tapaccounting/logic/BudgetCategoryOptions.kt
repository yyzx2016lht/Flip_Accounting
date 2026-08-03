package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository

data class BudgetCategoryOption(
    val categoryId: Long?,
    val label: String
)

object BudgetCategoryOptions {
    fun build(totalBudgetLabel: String, categories: List<Category>): List<BudgetCategoryOption> {
        val displayNames = CategoryRepository.displayNamesById(categories)
        return listOf(BudgetCategoryOption(null, totalBudgetLabel)) +
            categories.map { category ->
                BudgetCategoryOption(
                    categoryId = category.id,
                    label = displayNames[category.id] ?: category.name
                )
            }
    }
}
