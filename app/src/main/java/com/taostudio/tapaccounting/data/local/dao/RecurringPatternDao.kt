package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus

@Dao
interface RecurringPatternDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pattern: RecurringPattern): Long

    @Update
    suspend fun update(pattern: RecurringPattern)

    @Delete
    suspend fun delete(pattern: RecurringPattern)

    @Query("DELETE FROM recurring_patterns WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM recurring_patterns WHERE status = :status ORDER BY lastSeenAt DESC")
    suspend fun getByStatus(status: RecurringStatus): List<RecurringPattern>

    @Query("SELECT * FROM recurring_patterns ORDER BY lastSeenAt DESC")
    suspend fun getAll(): List<RecurringPattern>

    @Query("SELECT * FROM recurring_patterns WHERE merchantKey = :merchantKey LIMIT 1")
    suspend fun getByMerchantKey(merchantKey: String): RecurringPattern?

    @Query("""
        SELECT * FROM recurring_patterns
        WHERE merchantKey = :merchantKey
          AND bookName = :bookName
          AND billType = :billType
          AND billSubType = :billSubType
          AND IFNULL(categoryName, '') = IFNULL(:categoryName, '')
          AND IFNULL(accountName, '') = IFNULL(:accountName, '')
          AND IFNULL(toAccountName, '') = IFNULL(:toAccountName, '')
        LIMIT 1
    """)
    suspend fun getBySignature(
        merchantKey: String,
        bookName: String,
        categoryName: String?,
        accountName: String?,
        toAccountName: String,
        billType: Int,
        billSubType: Int
    ): RecurringPattern?

    @Query("SELECT * FROM recurring_patterns WHERE status = :status AND lastSeenAt < :before")
    suspend fun getStale(status: RecurringStatus, before: Long): List<RecurringPattern>

    @Query("DELETE FROM recurring_patterns")
    suspend fun deleteAll()
}
