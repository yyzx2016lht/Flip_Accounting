package com.taostudio.tapaccounting

data class AiRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val keyword: String,       // 例如："花呗"
    val targetType: Int?,      // 目标记账类型 (1:支出, 2:收入, 3:转账...)
    val targetCategory: String?, // 目标分类名称
    val targetAccount1: String?, // 目标出账/收入账户名称
    val targetAccount2: String?, // 目标入账账户（转账用）
    val isEnabled: Boolean = true
)
