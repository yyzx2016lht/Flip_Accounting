package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "budgets")
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookName: String,              // 空字符串表示全部账本
    val categoryId: Long?,             // null 表示"总支出预算"
    val categoryName: String?,         // 冗余展示
    val yearMonth: String,             // "2026-06"
    val amount: Double,
    val currency: String = "CNY",
    val alertThreshold: Double = 0.8,  // 80% 提醒
    val createdAt: Long,
    val updatedAt: Long
)
