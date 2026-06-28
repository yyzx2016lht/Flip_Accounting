package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class RecurringFrequency { WEEKLY, MONTHLY, YEARLY }
enum class RecurringStatus { SUGGESTED, CONFIRMED, DISMISSED }

@Entity(tableName = "recurring_patterns")
data class RecurringPattern(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val merchantKey: String,           // 归一化商户/备注关键词
    val categoryId: Long?,
    val categoryName: String?,
    val accountName: String?,
    val bookName: String,
    val amountApprox: Double,          // 中位数金额
    val amountTolerance: Double,       // 允许波动
    val frequency: RecurringFrequency,
    val dayOfMonthHint: Int?,          // 常见扣款日
    val lastSeenAt: Long,
    val nextExpectedAt: Long?,
    val status: RecurringStatus,
    val createdAt: Long,
    val updatedAt: Long
)
