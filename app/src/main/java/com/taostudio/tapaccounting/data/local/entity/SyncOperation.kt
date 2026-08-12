package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 已应用操作，同时保留每个实体的版本和删除墓碑。 */
@Entity(
    tableName = "sync_operation",
    foreignKeys = [ForeignKey(entity = SharedLedger::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ledgerId"), Index(value = ["ledgerId", "entityType", "entityId", "revision", "deviceId"])]
)
data class SyncOperation(
    @PrimaryKey val operationId: String,
    val ledgerId: Long,
    val entityType: String,
    val entityId: String,
    val action: String,
    val revision: Long,
    val deviceId: String,
    val memberId: String,
    val payload: String?,
    val appliedAt: Long
)
