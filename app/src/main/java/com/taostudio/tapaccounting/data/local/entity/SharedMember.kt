package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shared_member",
    foreignKeys = [ForeignKey(entity = SharedLedger::class, parentColumns = ["id"], childColumns = ["ledgerId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("ledgerId"), Index(value = ["ledgerId", "memberId"], unique = true)]
)
data class SharedMember(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ledgerId: Long,
    val memberId: String,
    val displayName: String,
    val joinOrder: Int,
    val isLocal: Boolean
) {
    fun resolvedName(): String = displayName.ifBlank { "成员$joinOrder" }
}
