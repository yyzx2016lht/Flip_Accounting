package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SyncOperation

@Dao
interface SyncOperationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIgnore(value: SyncOperation): Long
    @Query("SELECT sync_operation.* FROM sync_operation INNER JOIN sync_queue ON sync_queue.operationId=sync_operation.operationId ORDER BY sync_queue.ledgerId, sync_queue.createdAt")
    suspend fun getPending(): List<SyncOperation>
    @Query("SELECT EXISTS(SELECT 1 FROM sync_operation WHERE operationId=:operationId)") suspend fun exists(operationId: String): Boolean
    @Query("SELECT operationId FROM sync_operation WHERE ledgerId=:ledgerId") suspend fun getOperationIds(ledgerId: Long): List<String>
    @Query("SELECT * FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId ORDER BY revision DESC, deviceId DESC LIMIT 1") suspend fun getWinner(ledgerId: Long, entityType: String, entityId: String): SyncOperation?
    @Query("SELECT COALESCE(MAX(revision),0) FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId") suspend fun maxRevision(ledgerId: Long, entityType: String, entityId: String): Long
    @Query("SELECT EXISTS(SELECT 1 FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND entityId=:entityId AND action='delete')")
    suspend fun hasDelete(ledgerId: Long, entityType: String, entityId: String): Boolean
    @Query("SELECT DISTINCT entityId FROM sync_operation WHERE ledgerId=:ledgerId AND entityType=:entityType AND action='delete'")
    suspend fun getDeletedEntityIds(ledgerId: Long, entityType: String): List<String>
}
