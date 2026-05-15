package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import com.taostudio.tapaccounting.data.local.entity.DeletedBill

@Dao
interface DeletedBillDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(deletedBill: DeletedBill): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(deletedBills: List<DeletedBill>)

    @Delete
    suspend fun delete(deletedBill: DeletedBill)

    @Delete
    suspend fun delete(deletedBills: List<DeletedBill>)

    @Query("SELECT * FROM deleted_bills ORDER BY deletedAt DESC")
    suspend fun getAllDeletedBills(): List<DeletedBill>

    @Query("SELECT * FROM deleted_bills WHERE id = :id LIMIT 1")
    suspend fun getDeletedBillById(id: Long): DeletedBill?

    @Query("DELETE FROM deleted_bills WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM deleted_bills")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM deleted_bills")
    suspend fun getCount(): Int
}

