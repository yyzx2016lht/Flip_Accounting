package com.taostudio.tapaccounting.data.local.dao

import com.taostudio.tapaccounting.data.local.entity.Budget
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetDaoTest {
    @Test
    fun saveForSlot_updatesExistingRowInsteadOfCreatingDuplicate() = runBlocking {
        val dao = RecordingBudgetDao()
        val original = budget(amount = 1000.0)

        val firstId = dao.saveForSlot(original)
        val secondId = dao.saveForSlot(original.copy(amount = 1200.0, updatedAt = 2))

        assertEquals(firstId, secondId)
        assertEquals(1, dao.rows.size)
        assertEquals(1200.0, dao.rows.single().amount, 0.0)
        assertEquals(1L, dao.rows.single().createdAt)
    }

    @Test
    fun saveForSlot_keepsTotalAndCategorySlotsSeparate() = runBlocking {
        val dao = RecordingBudgetDao()

        dao.saveForSlot(budget(categoryId = null, amount = 2000.0))
        dao.saveForSlot(budget(categoryId = 7, amount = 500.0))

        assertEquals(2, dao.rows.size)
        assertEquals(setOf(0L, 7L), dao.rows.map { it.categoryKey }.toSet())
    }

    @Test
    fun clearSharedState_removesMemberBudgetAllocationFromFormerSharedLedger() = runBlocking {
        val dao = RecordingBudgetDao()
        dao.insert(
            budget(categoryId = null, amount = 1_500.0).copy(
                isShared = true,
                memberBudgetAllocations = "{\"member-a\":1000,\"member-b\":500}"
            )
        )

        dao.clearSharedState(bookId = 3)

        assertEquals(false, dao.rows.single().isShared)
        assertEquals(null, dao.rows.single().memberBudgetAllocations)
    }

    @Test
    fun getTotalBudgetsBetween_returnsOnlyTotalBudgetsInRequestedPeriod() = runBlocking {
        val dao = RecordingBudgetDao()
        dao.insert(budget(categoryId = null, amount = 1_000.0).copy(yearMonth = "2026-01"))
        dao.insert(budget(categoryId = null, amount = 1_500.0).copy(yearMonth = "2026-02"))
        dao.insert(budget(categoryId = 7, amount = 300.0).copy(yearMonth = "2026-02"))
        dao.insert(budget(categoryId = null, amount = 2_000.0).copy(yearMonth = "2027-01"))

        val result = dao.getTotalBudgetsBetween("2026-01", "2026-12", "旅行账本")

        assertEquals(listOf(1_000.0, 1_500.0), result.map { it.amount })
    }

    private fun budget(categoryId: Long? = 7, amount: Double) = Budget(
        bookId = 3,
        bookName = "旅行账本",
        categoryId = categoryId,
        categoryName = categoryId?.let { "交通" },
        yearMonth = "2026-08",
        amount = amount,
        createdAt = 1,
        updatedAt = 1
    )

    private class RecordingBudgetDao : BudgetDao {
        val rows = mutableListOf<Budget>()
        private var nextId = 1L

        override suspend fun insert(budget: Budget): Long {
            val id = if (budget.id == 0L) nextId++ else budget.id
            rows += budget.copy(id = id)
            return id
        }

        override suspend fun update(budget: Budget) {
            val index = rows.indexOfFirst { it.id == budget.id }
            if (index >= 0) rows[index] = budget
        }

        override suspend fun delete(budget: Budget) {
            rows.removeAll { it.id == budget.id }
        }

        override suspend fun deleteById(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun getBudgetsByMonthAndBook(yearMonth: String, bookName: String) =
            rows.filter { it.yearMonth == yearMonth && it.bookName == bookName }

        override fun observeBudgetsByMonthAndBook(yearMonth: String, bookName: String) =
            flowOf(rows.filter { it.yearMonth == yearMonth && it.bookName == bookName })

        override suspend fun getBudgetByBookAndCategory(
            yearMonth: String,
            bookName: String,
            categoryId: Long
        ) = rows.firstOrNull {
            it.yearMonth == yearMonth && it.bookName == bookName && it.categoryKey == categoryId
        }

        override suspend fun getTotalBudget(yearMonth: String, bookName: String) =
            rows.firstOrNull {
                it.yearMonth == yearMonth && it.bookName == bookName && it.categoryKey == 0L
            }

        override suspend fun getTotalBudgetsBetween(
            startYearMonth: String,
            endYearMonth: String,
            bookName: String
        ) = rows.filter {
            it.yearMonth in startYearMonth..endYearMonth &&
                it.bookName == bookName && it.categoryKey == 0L
        }.sortedBy { it.yearMonth }

        override suspend fun getBySlot(bookId: Long, yearMonth: String, categoryKey: Long) =
            rows.firstOrNull {
                it.bookId == bookId && it.yearMonth == yearMonth && it.categoryKey == categoryKey
            }

        override suspend fun getAll() = rows.toList()

        override fun observeAll() = flowOf(rows.toList())

        override suspend fun getAllByBookId(bookId: Long) = rows.filter { it.bookId == bookId }

        override suspend fun getBySharedId(sharedId: String) = rows.firstOrNull { it.sharedId == sharedId }

        override suspend fun clearSharedState(bookId: Long) {
            rows.replaceAll { budget ->
                if (budget.bookId == bookId) budget.copy(
                    sharedId = null,
                    revision = 0,
                    isShared = false,
                    sharedDeviceId = null,
                    memberBudgetAllocations = null
                ) else budget
            }
        }

        override suspend fun deleteAllByBookId(bookId: Long) {
            rows.removeAll { it.bookId == bookId }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }
    }
}
