package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SyncQueue

@Dao
interface SyncQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(value: SyncQueue): Long
    @Query("SELECT * FROM sync_queue WHERE ledgerId=:ledgerId ORDER BY createdAt") suspend fun getByLedgerId(ledgerId: Long): List<SyncQueue>
    @Query("SELECT COUNT(*) FROM sync_queue WHERE ledgerId=:ledgerId") suspend fun count(ledgerId: Long): Int
    @Query("DELETE FROM sync_queue WHERE operationId=:operationId") suspend fun delete(operationId: String)
    @Query("UPDATE sync_queue SET retryCount=retryCount+1,lastError=:error WHERE operationId=:operationId") suspend fun markFailure(operationId: String, error: String)
}
