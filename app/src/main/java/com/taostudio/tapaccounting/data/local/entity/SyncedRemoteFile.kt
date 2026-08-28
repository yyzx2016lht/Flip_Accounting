package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** 不可变远端操作包的本地处理记录，避免每轮同步重复下载历史包。 */
@Entity(
    tableName = "sync_remote_file",
    primaryKeys = ["ledgerId", "remotePath"],
    foreignKeys = [ForeignKey(
        entity = SharedLedger::class,
        parentColumns = ["id"],
        childColumns = ["ledgerId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("ledgerId")]
)
data class SyncedRemoteFile(
    val ledgerId: Long,
    val remotePath: String,
    val processedAt: Long
)
