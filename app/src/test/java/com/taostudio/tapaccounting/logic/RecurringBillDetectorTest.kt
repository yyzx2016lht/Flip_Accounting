package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RecurringBillDetectorTest {

    @Test
    fun detect_ignoresFrequentButUnanchoredShopping() {
        val bills = listOf(
            appleBill(2.07, millis(2026, 3, 17)),
            appleBill(2.92, millis(2026, 4, 1)),
            appleBill(2.21, millis(2026, 4, 9)),
            appleBill(2.06, millis(2026, 6, 16))
        )

        val candidates = RecurringBillDetector.detect(bills, amountTolerance = 1.0)

        assertTrue(candidates.none { it.merchantKey == "苹果" })
    }

    @Test
    fun detect_acceptsMonthlyBillsWithStableDayAnchor() {
        val bills = listOf(
            subscriptionBill(8.0, millis(2026, 3, 5)),
            subscriptionBill(8.0, millis(2026, 4, 5)),
            subscriptionBill(8.0, millis(2026, 5, 5)),
            subscriptionBill(8.0, millis(2026, 6, 5))
        )

        val candidates = RecurringBillDetector.detect(bills, amountTolerance = 1.0)

        assertEquals(1, candidates.size)
        assertEquals(RecurringFrequency.MONTHLY, candidates.first().frequency)
        assertEquals(5, candidates.first().dayOfMonthHint)
    }

    private fun appleBill(amount: Double, time: Long) = Bill(
        type = Bill.TYPE_EXPENSE,
        amount = amount,
        categoryName = "吃的 - 水果",
        accountName = "Visa",
        bookName = "波兰",
        remark = "苹果",
        time = time
    )

    private fun subscriptionBill(amount: Double, time: Long) = Bill(
        type = Bill.TYPE_EXPENSE,
        amount = amount,
        categoryName = "通信 - 话费",
        accountName = "移动话费",
        bookName = "波兰",
        remark = "话费",
        time = time
    )

    private fun millis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }.timeInMillis
    }
}
