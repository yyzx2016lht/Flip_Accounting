package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "budgets",
    indices = [
        Index(
            value = ["bookId", "yearMonth", "categoryKey"],
            unique = true
        )
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long = 0,              // 0 表示“全部账本”聚合预算
    val bookName: String,              // 备份兼容快照，归属以 bookId 为准
    val categoryId: Long?,             // null 表示"总支出预算"
    val categoryKey: Long = categoryId ?: TOTAL_CATEGORY_KEY,
    val categoryName: String?,         // 冗余展示
    val yearMonth: String,             // "2026-06"
    val amount: Double,
    val currency: String = "CNY",
    val alertThreshold: Double = 0.8,  // 80% 提醒
    val createdAt: Long,
    val updatedAt: Long,
    val sharedId: String? = null,
    val revision: Long = 0,
    val isShared: Boolean = false,
    val sharedDeviceId: String? = null
) {
    companion object {
        const val TOTAL_CATEGORY_KEY = 0L
    }
}
