package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Test

class AssetBillBalanceHistoryTest {

    @Test
    fun computeBalanceAfterByBillId_walksBackwardFromCurrentBalance() {
        val expense = Bill(
            id = 2L,
            amount = 3.0,
            type = Bill.TYPE_EXPENSE,
            accountId = 1L,
            currency = "CNY",
            time = 2L
        )
        val olderIncome = Bill(
            id = 1L,
            amount = 50.0,
            type = Bill.TYPE_INCOME,
            accountId = 1L,
            currency = "CNY",
            time = 1L
        )
        val balances = AssetBillBalanceHistory.computeBalanceAfterByBillId(
            bills = listOf(expense, olderIncome),
            assetId = 1L,
            assetName = "微信",
            assetCurrency = "CNY",
            currentBalance = 97.0
        )
        assertEquals(97.0, balances[2L]!!, 0.0001)
        assertEquals(100.0, balances[1L]!!, 0.0001)
    }

    @Test
    fun computeBalanceAfterByBillId_keepsOriginalExpenseImpactAfterRefund() {
        val olderIncome = Bill(
            id = 1L,
            amount = 10.0,
            type = Bill.TYPE_INCOME,
            accountId = 1L,
            currency = "CNY",
            time = 1L
        )
        val fullyRefundedExpense = Bill(
            id = 2L,
            amount = 0.0,
            originalAmount = 5.0,
            type = Bill.TYPE_EXPENSE,
            accountId = 1L,
            currency = "CNY",
            time = 2L
        )
        val refund = Bill(
            id = 3L,
            amount = 5.0,
            type = Bill.TYPE_INCOME,
            subType = Bill.SUBTYPE_REFUND,
            accountId = 1L,
            currency = "CNY",
            time = 3L,
            relatedBillId = 2L
        )

        val balances = AssetBillBalanceHistory.computeBalanceAfterByBillId(
            bills = listOf(refund, fullyRefundedExpense, olderIncome),
            assetId = 1L,
            assetName = "微信",
            assetCurrency = "CNY",
            currentBalance = 29.0
        )

        assertEquals(29.0, balances[3L]!!, 0.0001)
        assertEquals(24.0, balances[2L]!!, 0.0001)
        assertEquals(29.0, balances[1L]!!, 0.0001)
    }
}
