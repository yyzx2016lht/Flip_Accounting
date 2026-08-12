package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shared_ledger",
    foreignKeys = [ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("uuid", unique = true), Index("bookId", unique = true)]
)
data class SharedLedger(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val bookId: Long,
    val name: String,
    val webdavUrl: String,
    val webdavUser: String,
    val remotePath: String,
    val localMemberId: String,
    val createdAt: Long
) {
    companion object {
        const val ACTIVE_MEMBER_LIMIT = 2
        const val PROTOCOL_MEMBER_LIMIT = 5
    }
}
