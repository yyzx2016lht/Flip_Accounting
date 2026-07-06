package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.Budget

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

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth ORDER BY categoryId ASC")
    suspend fun getBudgetsByMonth(yearMonth: String): List<Budget>

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND bookName = :bookName ORDER BY categoryId ASC")
    suspend fun getBudgetsByMonthAndBook(yearMonth: String, bookName: String): List<Budget>

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND categoryId = :categoryId LIMIT 1")
    suspend fun getBudgetByCategory(yearMonth: String, categoryId: Long): Budget?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND bookName = :bookName AND categoryId = :categoryId LIMIT 1")
    suspend fun getBudgetByBookAndCategory(yearMonth: String, bookName: String, categoryId: Long): Budget?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND categoryId IS NULL AND bookName = :bookName LIMIT 1")
    suspend fun getTotalBudget(yearMonth: String, bookName: String): Budget?

    @Query("SELECT * FROM budgets WHERE yearMonth = :yearMonth AND bookName IN (:bookNames) ORDER BY categoryId ASC")
    suspend fun getBudgetsByMonthAndBooks(yearMonth: String, bookNames: List<String>): List<Budget>

    @Query("DELETE FROM budgets WHERE yearMonth = :yearMonth")
    suspend fun deleteByMonth(yearMonth: String)

    @Query("SELECT * FROM budgets ORDER BY yearMonth DESC, categoryId ASC")
    suspend fun getAll(): List<Budget>

    @Query("DELETE FROM budgets")
    suspend fun deleteAll()
}
