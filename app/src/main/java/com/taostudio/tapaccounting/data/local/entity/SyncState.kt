package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_state",
    foreignKeys = [ForeignKey(entity = SharedLedger::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)]
)
data class SyncState(
    @PrimaryKey val ledgerId: Long,
    val deviceId: String,
    val lastSyncTime: Long = 0,
    val lastError: String? = null,
    val isSyncing: Boolean = false
)
