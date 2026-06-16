package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["toAccountId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
        Index(value = ["toAccountId"]),
        Index(value = ["time"]),
        Index(value = ["bookName"]),
        Index(value = ["relatedBillId"])
    ]
)
data class Bill(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // Main type: 0 for Expense, 1 for Income, 2 for Transfer
    val type: Int,

    // Sub-type for special transactions: 0=Normal, 1=Repayment, 2=Refund, 3=Balance Adjustment
    val subType: Int = 0,

    val amount: Double,

    val originalAmount: Double = amount,
    val currency: String = "CNY",
    val exchangeRate: Double = 1.0,

    val categoryId: Long? = null,
    val accountId: Long? = null,
    val toAccountId: Long? = null,

    val categoryName: String = "",
    val accountName: String = "",
    val toAccountName: String = "", // Added to store target asset name for transfers

    val time: Long,
    val remark: String = "",
    val fee: Double = 0.0,

    /** Balance of [accountId] asset immediately after this bill was recorded. */
    val accountBalanceAfter: Double? = null,

    /** Balance of [toAccountId] asset immediately after this bill was recorded (transfers). */
    val toAccountBalanceAfter: Double? = null,

    // For future multi-book support
    val bookName: String = "日常账本",

    // Link for related bills, e.g. refund bill -> original expense bill
    val relatedBillId: Long? = null,

    val isSynced: Boolean = false,

    // 是否不计入统计
    val excludeFromStats: Boolean = false
) {
    companion object {
        const val TYPE_EXPENSE = 0
        const val TYPE_INCOME = 1
        const val TYPE_TRANSFER = 2
        const val TYPE_REPAYMENT = 3  // 还款（向信用卡/花呗/白条等信用账户还款，无手续费）

        const val SUBTYPE_NORMAL = 0
        const val SUBTYPE_REPAYMENT = 1
        const val SUBTYPE_REFUND = 2
        const val SUBTYPE_BALANCE_ADJUSTMENT = 3
        const val SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED = 4
    }
}

