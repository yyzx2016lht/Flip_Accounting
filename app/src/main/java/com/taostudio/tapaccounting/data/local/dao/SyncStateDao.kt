package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.taostudio.tapaccounting.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: SyncState)
    @Query("SELECT * FROM sync_state WHERE ledgerId=:ledgerId") suspend fun get(ledgerId: Long): SyncState?
    @Query("SELECT * FROM sync_state WHERE ledgerId=:ledgerId") fun observe(ledgerId: Long): Flow<SyncState?>
}
