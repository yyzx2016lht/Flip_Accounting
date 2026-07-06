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
    val createTime: Long = System.currentTimeMillis()
)

