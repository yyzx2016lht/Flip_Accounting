package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class RefundReconciliationPolicyTest {
    @Test
    fun `linking an unreflected legacy refund reduces the source expense`() {
        val actual = RefundReconciliationPolicy.actualExpenseAfterLink(
            sourceAmount = 100.0,
            sourceOriginalAmount = 100.0,
            alreadyLinkedRefundTotal = 0.0,
            refundAmount = 50.0
        )

        assertEquals(50.0, actual, 0.001)
    }

    @Test
    fun `linking a refund already reflected in source does not deduct twice`() {
        val actual = RefundReconciliationPolicy.actualExpenseAfterLink(
            sourceAmount = 50.0,
            sourceOriginalAmount = 100.0,
            alreadyLinkedRefundTotal = 0.0,
            refundAmount = 50.0
        )

        assertEquals(50.0, actual, 0.001)
    }

    @Test
    fun `only the unreflected part of a legacy refund is deducted`() {
        val actual = RefundReconciliationPolicy.actualExpenseAfterLink(
            sourceAmount = 80.0,
            sourceOriginalAmount = 100.0,
            alreadyLinkedRefundTotal = 10.0,
            refundAmount = 20.0
        )

        assertEquals(70.0, actual, 0.001)
    }
}
