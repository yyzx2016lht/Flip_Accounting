package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedBudgetDisplayDefaultPolicyTest {
    @Test
    fun `shared ledger with budget enables an unset display preference`() {
        assertTrue(
            SharedBudgetDisplayDefaultPolicy.shouldEnable(
                isSharedLedger = true,
                hasBudget = true,
                hasExplicitPreference = false
            )
        )
    }

    @Test
    fun `explicit user preference is preserved`() {
        assertFalse(
            SharedBudgetDisplayDefaultPolicy.shouldEnable(
                isSharedLedger = true,
                hasBudget = true,
                hasExplicitPreference = true
            )
        )
    }

    @Test
    fun `ordinary ledger or empty shared ledger keeps defaults off`() {
        assertFalse(SharedBudgetDisplayDefaultPolicy.shouldEnable(false, true, false))
        assertFalse(SharedBudgetDisplayDefaultPolicy.shouldEnable(true, false, false))
    }
}
