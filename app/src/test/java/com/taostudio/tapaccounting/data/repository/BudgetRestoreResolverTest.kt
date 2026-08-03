package com.taostudio.tapaccounting.data.repository

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.local.entity.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BudgetRestoreResolverTest {
    @Test
    fun resolveBudgetCategory_usesRestoreIdMapAndCurrentDisplayPath() {
        val budget = budget(categoryId = 99, categoryName = "旧分类")
        val categories = listOf(
            Category(id = 1, name = "餐饮", type = 0),
            Category(id = 2, name = "早餐", type = 0, parentId = 1)
        )

        val resolved = resolveBudgetCategoryForRestore(
            budget,
            categoryIdMap = mapOf(99L to 2L),
            currentCategories = categories
        )

        assertEquals(2L, resolved?.categoryId)
        assertEquals("餐饮 - 早餐", resolved?.categoryName)
    }

    @Test
    fun resolveBudgetCategory_matchesUniqueDisplayNameWithoutIdMap() {
        val budget = budget(categoryId = 99, categoryName = "旅行 - 交通")
        val categories = listOf(
            Category(id = 1, name = "旅行", type = 0),
            Category(id = 2, name = "交通", type = 0, parentId = 1)
        )

        val resolved = resolveBudgetCategoryForRestore(budget, emptyMap(), categories)

        assertEquals(2L, resolved?.categoryId)
        assertEquals("旅行 - 交通", resolved?.categoryName)
    }

    @Test
    fun resolveBudgetCategory_rejectsAmbiguousLeafName() {
        val budget = budget(categoryId = 99, categoryName = "交通")
        val categories = listOf(
            Category(id = 1, name = "日常", type = 0),
            Category(id = 2, name = "旅行", type = 0),
            Category(id = 3, name = "交通", type = 0, parentId = 1),
            Category(id = 4, name = "交通", type = 0, parentId = 2)
        )

        assertNull(resolveBudgetCategoryForRestore(budget, emptyMap(), categories))
    }

    @Test
    fun normalizeBudgetBookName_preservesGlobalScopeAndNormalizesLegacyDefault() {
        assertEquals("", normalizeBudgetBookName(""))
        assertEquals("", normalizeBudgetBookName(BookAccountManager.ALL_BOOK))
        assertEquals(BookAccountManager.DEFAULT_BOOK, normalizeBudgetBookName("默认账本"))
    }

    @Test
    fun budget_categoryKeyUsesZeroOnlyForTotalBudget() {
        assertEquals(0L, budget(categoryId = null, categoryName = null).categoryKey)
        assertEquals(42L, budget(categoryId = 42, categoryName = "餐饮").categoryKey)
    }

    private fun budget(categoryId: Long?, categoryName: String?) = Budget(
        bookName = "日常账本",
        categoryId = categoryId,
        categoryName = categoryName,
        yearMonth = "2026-08",
        amount = 1000.0,
        createdAt = 1,
        updatedAt = 1
    )
}
