package com.taostudio.tapaccounting.ui.main.stats

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillStatsContribution
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsBillContributionTest {

    @Test
    fun `refund does not reduce an expense whose amount is already adjusted`() {
        val originalExpense = BillStatsContribution.from(expense(amount = 50.0, originalAmount = 100.0), 50.0)
        val refundRecord = BillStatsContribution.from(refund(amount = 50.0, relatedBillId = 1L), 50.0)

        assertEquals(50.0, originalExpense.expense + refundRecord.expense, 0.001)
        assertEquals(50.0, originalExpense.refund + refundRecord.refund, 0.001)
    }

    @Test
    fun `refund is neither regular income nor negative expense`() {
        val contribution = BillStatsContribution.from(refund(amount = 48.36, relatedBillId = 1L), 48.36)

        assertEquals(0.0, contribution.expense, 0.001)
        assertEquals(0.0, contribution.income, 0.001)
        assertEquals(48.36, contribution.refund, 0.001)
    }

    @Test
    fun `unlinked legacy refund restores expense until a source is reconciled`() {
        val contribution = BillStatsContribution.from(refund(amount = 0.72, relatedBillId = null), 0.72)

        assertEquals(-0.72, contribution.expense, 0.001)
        assertEquals(0.0, contribution.income, 0.001)
        assertEquals(0.72, contribution.refund, 0.001)
    }

    @Test
    fun `excluded bill contributes nothing to current or previous statistics`() {
        val contribution = BillStatsContribution.from(expense(amount = 80.0, excludeFromStats = true), 80.0)

        assertEquals(BillStatsContribution(), contribution)
    }

    private fun expense(
        amount: Double,
        originalAmount: Double = amount,
        excludeFromStats: Boolean = false
    ) = Bill(
        id = 1L,
        type = Bill.TYPE_EXPENSE,
        amount = amount,
        originalAmount = originalAmount,
        time = 1L,
        excludeFromStats = excludeFromStats
    )

    private fun refund(amount: Double, relatedBillId: Long?) = Bill(
        id = 2L,
        type = Bill.TYPE_INCOME,
        subType = Bill.SUBTYPE_REFUND,
        amount = amount,
        time = 2L,
        relatedBillId = relatedBillId
    )
}
