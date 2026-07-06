package com.taostudio.tapaccounting.logic

data class InvestmentLotDraft(
    val amount: Double,
    val schedule: InvestmentInterestService.InvestmentSchedule
)
