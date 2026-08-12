package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_queue",
    foreignKeys = [ForeignKey(entity = SharedLedger::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ledgerId")]
)
data class SyncQueue(
    @PrimaryKey val operationId: String,
    val ledgerId: Long,
    val operationJson: String,
    val remotePath: String,
    val createdAt: Long,
    val retryCount: Int = 0,
    val lastError: String? = null
)
