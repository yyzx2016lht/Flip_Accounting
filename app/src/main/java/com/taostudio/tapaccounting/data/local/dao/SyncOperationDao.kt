package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SyncOperation

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(value: SyncOperation): Long
    @Query("SELECT EXISTS(SELECT 1 FROM sync_operation WHERE operationId=:operationId)") suspend fun exists(operationId: String): Boolean
    @Query("SELECT * FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId ORDER BY revision DESC, deviceId DESC LIMIT 1") suspend fun getWinner(ledgerId: Long, entityType: String, entityId: String): SyncOperation?
    @Query("SELECT COALESCE(MAX(revision),0) FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId") suspend fun maxRevision(ledgerId: Long, entityType: String, entityId: String): Long
    @Query("SELECT EXISTS(SELECT 1 FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId AND action='delete')")
    suspend fun hasDelete(ledgerId: Long, entityType: String, entityId: String): Boolean
}
