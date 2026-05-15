package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_rule")
data class AiRule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val keyword: String,       // 例如："花呗"
    val targetType: Int?,      // 目标记账类型 (0:支出, 1:收入, 2:转账...)，可为空则不强求
    val targetCategory: String?, // 目标分类名称，可为空
    val targetAccount1: String?, // 目标出账/收入账户名称，可为空
    val targetAccount2: String?, // 目标入账账户（转账用），可为空
    val isEnabled: Boolean = true
)

