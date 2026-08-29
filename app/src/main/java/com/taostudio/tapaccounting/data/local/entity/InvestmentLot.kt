package com.taostudio.tapaccounting.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "investment_lots",
    foreignKeys = [
        ForeignKey(entity = Asset::class, parentColumns = ["id"], childColumns = ["assetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Bill::class, parentColumns = ["id"], childColumns = ["sourceBillId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [
        Index(value = ["assetId"]),
        Index(value = ["sourceBillId"], unique = true),
        Index(value = ["lastSettledAt"])
    ]
)
data class InvestmentLot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val assetId: Long,
    val sourceBillId: Long?,
    val principalAmount: Double,
    val remainingPrincipal: Double,
    val currency: String,
    val annualInterestRate: Double = 0.0,
    val startEarningAt: Long,
    val firstPayoutAt: Long,
    val lastSettledAt: Long,
    /** 结息周期；见 [CYCLE_DAILY] 等常量。 */
    val settlementCycle: Int = CYCLE_DAILY,
    /** 周期间隔，当前界面固定为 1；保留该字段便于后续支持“每 N 周/月”。 */
    val settlementInterval: Int = 1,
    /** 尚未达到该币种最小记账单位的收益尾差。 */
    val interestCarry: Double = 0.0,
    /** 0=计息中，1=已暂停，2=已结束。 */
    val status: Int = STATUS_ACTIVE,
    val createTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val CYCLE_DAILY = 0
        const val CYCLE_WEEKLY = 1
        const val CYCLE_MONTHLY = 2
        const val CYCLE_QUARTERLY = 3
        const val CYCLE_YEARLY = 4

        const val STATUS_ACTIVE = 0
        const val STATUS_PAUSED = 1
        const val STATUS_CLOSED = 2
    }
}

