package com.taostudio.tapaccounting.data.backup

import com.taostudio.tapaccounting.data.local.entity.Budget
import org.junit.Assert.assertEquals
import org.junit.Test

class DataExportManagerBudgetTest {
    @Test
    fun deserializeBudgets_acceptsLegacySnapshotWithoutIdentityFields() {
        val json = """[{"id":8,"bookName":"旅行账本","categoryId":7,"categoryName":"交通","yearMonth":"2026-08","amount":600.0,"currency":"CNY","alertThreshold":0.8,"createdAt":1,"updatedAt":2}]"""

        val restored = DataExportManager.deserializeBudgets(json).single()

        assertEquals("旅行账本", restored.bookName)
        assertEquals(7L, restored.categoryId)
    }

    @Test
    fun serializeBudgets_roundTripsStableBookAndCategoryKeys() {
        val budget = Budget(
            bookId = 12,
            bookName = "旅行账本",
            categoryId = 7,
            categoryName = "交通",
            yearMonth = "2026-08",
            amount = 600.0,
            createdAt = 1,
            updatedAt = 2
        )

        val restored = DataExportManager.deserializeBudgets(
            DataExportManager.serialize(listOf(budget))
        ).single()

        assertEquals(12L, restored.bookId)
        assertEquals(7L, restored.categoryKey)
    }
}
