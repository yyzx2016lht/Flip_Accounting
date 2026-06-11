package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetBillBalanceDisplayTest {

    @Test
    fun assetCreationDayStart_usesEarliestBillWhenCreateTimeIsLater() {
        val firstBill = dayMillis(2024, 3, 1, 10, 0)
        val lateCreate = dayMillis(2026, 5, 28, 15, 0)
        val asset = Asset(
            id = 1L,
            name = "招行",
            type = "银行",
            createTime = lateCreate
        )
        val bills = listOf(
            bill(id = 1L, time = firstBill, accountId = 1L, accountName = "招行"),
            bill(id = 2L, time = dayMillis(2025, 1, 1, 12, 0), accountId = 1L, accountName = "招行")
        )

        val creationDay = AssetBillBalanceDisplay.assetCreationDayStart(asset, bills, 1L, "招行")
        assertEquals(InvestmentInterestService.startOfDay(firstBill), creationDay)
    }

    @Test
    fun shouldShowBalance_respectsStartBeforeLatestBill() {
        val firstBill = dayMillis(2024, 3, 1, 10, 0)
        val latestBill = dayMillis(2025, 6, 1, 10, 0)
        val asset = Asset(
            id = 1L,
            name = "招行",
            type = "银行",
            createTime = dayMillis(2026, 5, 28, 15, 0),
            showBillBalanceAfter = true,
            billBalanceFromTime = 0L
        )
        val bills = listOf(
            bill(id = 1L, time = firstBill, accountId = 1L, accountName = "招行"),
            bill(id = 2L, time = latestBill, accountId = 1L, accountName = "招行")
        )

        assertTrue(
            AssetBillBalanceDisplay.shouldShowBalanceForBill(asset, firstBill, bills, 1L, "招行")
        )
        assertTrue(
            AssetBillBalanceDisplay.shouldShowBalanceForBill(asset, latestBill, bills, 1L, "招行")
        )
    }

    @Test
    fun shouldShowBalance_honorsManualLaterStartDate() {
        val firstBill = dayMillis(2024, 3, 1, 10, 0)
        val manualStart = dayMillis(2025, 1, 1, 0, 0)
        val asset = Asset(
            id = 1L,
            name = "招行",
            type = "银行",
            createTime = dayMillis(2024, 1, 1, 0, 0),
            showBillBalanceAfter = true,
            billBalanceFromTime = manualStart
        )
        val bills = listOf(
            bill(id = 1L, time = firstBill, accountId = 1L, accountName = "招行")
        )

        assertFalse(
            AssetBillBalanceDisplay.shouldShowBalanceForBill(asset, firstBill, bills, 1L, "招行")
        )
    }

    private fun bill(
        id: Long,
        time: Long,
        accountId: Long?,
        accountName: String
    ) = Bill(
        id = id,
        type = Bill.TYPE_EXPENSE,
        amount = 10.0,
        time = time,
        accountId = accountId,
        accountName = accountName
    )

    private fun dayMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(year, month - 1, day, hour, minute, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
