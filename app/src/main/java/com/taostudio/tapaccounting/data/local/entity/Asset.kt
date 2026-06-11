package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "assets")
data class Asset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,          // 资产类型名称，如 "招商银行", "微信", "支付宝"
    val balance: Double = 0.0, // 当前余额
    val initialBalance: Double = 0.0, // 初始金额
    val currency: String = "CNY",
    val icon: String = "",     // 图标 URL 或内置图标标识
    val remark: String = "",
    val includeInNetAsset: Boolean = true, // 是否计入总资产
    val sortOrder: Int = 0,    // 排序（资产页面用）
    val pickerSortOrder: Int = 0, // 记账选择器中的排序（独立，不受资产页影响）
    val createTime: Long = System.currentTimeMillis(),
    val assetCategory: String = CATEGORY_FUND, // 资产类别：资金(FUND) 或 信用卡(CREDIT_CARD)
    val creditLimit: Double = 0.0,  // 信用卡额度（0=未设置）
    val billingDay: Int = 0,         // 信用卡还款日（保留字段，暂不使用）
    val annualInterestRate: Double = 0.0, // 投资理财年化利率百分比，如 1.8 表示 1.8%
    val interestLastSettledAt: Long = System.currentTimeMillis(), // 最近一次自动结息时间
    val isArchived: Boolean = false, // 是否收纳，收纳后默认不出现在日常资产列表与记账选择器
    val billBalanceFromTime: Long = 0L, // 账单余额起始时间
    val showBillBalanceAfter: Boolean = false // 是否显示账单后余额
) {
    companion object {
        const val CATEGORY_FUND = "FUND"
        const val CATEGORY_CREDIT_CARD = "CREDIT_CARD"
        const val CATEGORY_RECHARGE = "RECHARGE"       // 充值账户（如话费卡、公交卡等）
        const val CATEGORY_INVESTMENT = "INVESTMENT"   // 投资理财（如基金、股票账户等）

        /** 返回类别对应的中文显示名 */
        fun categoryLabel(category: String) = when (category) {
            CATEGORY_FUND -> "资金"
            CATEGORY_CREDIT_CARD -> "信用卡"
            CATEGORY_RECHARGE -> "充值账户"
            CATEGORY_INVESTMENT -> "投资理财"
            else -> "其它"
        }

        /** 固定显示顺序 */
        val CATEGORY_ORDER = listOf(
            CATEGORY_CREDIT_CARD,
            CATEGORY_FUND,
            CATEGORY_RECHARGE,
            CATEGORY_INVESTMENT
        )
    }
}

