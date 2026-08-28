package com.taostudio.tapaccounting.logic

object SharedBudgetDisplayDefaultPolicy {
    fun shouldEnable(
        isSharedLedger: Boolean,
        hasBudget: Boolean,
        hasExplicitPreference: Boolean
    ): Boolean = isSharedLedger && hasBudget && !hasExplicitPreference
}
