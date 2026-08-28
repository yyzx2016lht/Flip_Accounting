package com.taostudio.tapaccounting.logic

import kotlin.math.max

object RefundReconciliationPolicy {
    fun actualExpenseAfterLink(
        sourceAmount: Double,
        sourceOriginalAmount: Double,
        alreadyLinkedRefundTotal: Double,
        refundAmount: Double
    ): Double {
        val original = max(sourceOriginalAmount, sourceAmount)
        val alreadyReflected = (original - sourceAmount).coerceAtLeast(0.0)
        val reflectedButUnlinked = (alreadyReflected - alreadyLinkedRefundTotal).coerceAtLeast(0.0)
        val amountStillToApply = (refundAmount - reflectedButUnlinked).coerceAtLeast(0.0)
        return (sourceAmount - amountStillToApply).coerceIn(0.0, original)
    }
}
