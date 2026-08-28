package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.Budget
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(budget: Budget): Long

    @Update
    suspend fun update(budget: Budget)

    @Delete
    suspend fun delete(budget: Budget)

    @Query("DELETE FROM budgets WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("""
        SELECT budgets.* FROM budgets
        LEFT JOIN books ON books.id = budgets.bookId
        WHERE budgets.yearMonth = :yearMonth
          AND ((:bookName = '' AND budgets.bookId = 0) OR books.name = :bookName)
        ORDER BY budgets.categoryKey ASC
    """)
    suspend fun getBudgetsByMonthAndBook(yearMonth: String, bookName: String): List<Budget>

    @Query("""
        SELECT budgets.* FROM budgets
        LEFT JOIN books ON books.id = budgets.bookId
        WHERE budgets.yearMonth = :yearMonth
          AND ((:bookName = '' AND budgets.bookId = 0) OR books.name = :bookName)
        ORDER BY budgets.categoryKey ASC
    """)
    fun observeBudgetsByMonthAndBook(yearMonth: String, bookName: String): Flow<List<Budget>>

    @Query("""
        SELECT budgets.* FROM budgets
        LEFT JOIN books ON books.id = budgets.bookId
        WHERE budgets.yearMonth = :yearMonth
          AND ((:bookName = '' AND budgets.bookId = 0) OR books.name = :bookName)
          AND budgets.categoryKey = :categoryId
        LIMIT 1
    """)
    suspend fun getBudgetByBookAndCategory(yearMonth: String, bookName: String, categoryId: Long): Budget?

    @Query("""
        SELECT budgets.* FROM budgets
        LEFT JOIN books ON books.id = budgets.bookId
        WHERE budgets.yearMonth = :yearMonth
          AND ((:bookName = '' AND budgets.bookId = 0) OR books.name = :bookName)
          AND budgets.categoryKey = 0
        LIMIT 1
    """)
    suspend fun getTotalBudget(yearMonth: String, bookName: String): Budget?

    @Query("""
        SELECT budgets.* FROM budgets
        LEFT JOIN books ON books.id = budgets.bookId
        WHERE budgets.yearMonth BETWEEN :startYearMonth AND :endYearMonth
          AND ((:bookName = '' AND budgets.bookId = 0) OR books.name = :bookName)
          AND budgets.categoryKey = 0
        ORDER BY budgets.yearMonth ASC
    """)
    suspend fun getTotalBudgetsBetween(
        startYearMonth: String,
        endYearMonth: String,
        bookName: String
    ): List<Budget>

    @Query("SELECT * FROM budgets WHERE bookId = :bookId AND yearMonth = :yearMonth AND categoryKey = :categoryKey LIMIT 1")
    suspend fun getBySlot(bookId: Long, yearMonth: String, categoryKey: Long): Budget?

    @Transaction
    suspend fun saveForSlot(budget: Budget): Long {
        val normalized = budget.copy(categoryKey = budget.categoryId ?: Budget.TOTAL_CATEGORY_KEY)
        val existing = getBySlot(normalized.bookId, normalized.yearMonth, normalized.categoryKey)
        if (existing == null) return insert(normalized)
        update(
            normalized.copy(
                id = existing.id,
                createdAt = existing.createdAt
            )
        )
        return existing.id
    }

    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, categoryId ASC")
    suspend fun getAll(): List<Budget>

    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, categoryId ASC")
    fun observeAll(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE bookId=:bookId")
    suspend fun getAllByBookId(bookId: Long): List<Budget>

    @Query("SELECT * FROM budgets WHERE sharedId=:sharedId LIMIT 1")
    suspend fun getBySharedId(sharedId: String): Budget?

    @Query("UPDATE budgets SET sharedId=NULL, revision=0, isShared=0, sharedDeviceId=NULL, memberBudgetAllocations=NULL WHERE bookId=:bookId")
    suspend fun clearSharedState(bookId: Long)

    @Query("DELETE FROM budgets WHERE bookId=:bookId")
    suspend fun deleteAllByBookId(bookId: Long)

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}
