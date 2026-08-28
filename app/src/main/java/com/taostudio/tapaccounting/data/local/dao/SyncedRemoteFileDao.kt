package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SyncedRemoteFile

@Dao
interface SyncedRemoteFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun markProcessed(value: SyncedRemoteFile)

    @Query("SELECT remotePath FROM sync_remote_file WHERE ledgerId=:ledgerId")
    suspend fun getProcessedPaths(ledgerId: Long): List<String>
}
