package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class InvestmentInterestServiceTest {

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun plusDays_crossDstSpringForward_usEastern() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val beforeDst = dayMillis(2024, 3, 9)
        val afterPlusOne = InvestmentInterestService.plusDays(beforeDst, 1)
        assertEquals(dayMillis(2024, 3, 10), afterPlusOne)
    }

    @Test
    fun plusDays_crossDstFallBack_usEastern() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val beforeFallback = dayMillis(2024, 11, 2)
        val afterPlusOne = InvestmentInterestService.plusDays(beforeFallback, 1)
        assertEquals(dayMillis(2024, 11, 3), afterPlusOne)
    }

    @Test
    fun daysBetween_countsCalendarDays() {
        val start = dayMillis(2024, 1, 1)
        val end = dayMillis(2024, 1, 31)
        assertEquals(30, InvestmentInterestService.daysBetween(start, end))
    }

    @Test
    fun compoundDailyInterestTotal_tPlus1_30days_matchesYuEBaoStyle() {
        val total = InvestmentInterestService.compoundDailyInterestTotal(
            initialPrincipal = 12000.0,
            annualInterestRatePercent = 10.0,
            days = 30
        )
        assertEquals(99.01, total, 0.05)
    }

    @Test
    fun compoundDailyInterestTotal_negativeRate_stopsAtZeroPrincipal() {
        val total = InvestmentInterestService.compoundDailyInterestTotal(
            initialPrincipal = 0.05,
            annualInterestRatePercent = -50.0,
            days = 10
        )
        assertTrue(total <= 0.0)
    }

    @Test
    fun applyFifoPrincipalReduction_reducesEarlierLotFirst() {
        val earlyLot = lot(id = 1L, startEarningAt = dayMillis(2024, 1, 1), remainingPrincipal = 80.0)
        val lateLot = lot(id = 2L, startEarningAt = dayMillis(2024, 2, 1), remainingPrincipal = 80.0)
        val unordered = listOf(lateLot, earlyLot)
        val ordered = unordered.sortedWith(compareBy({ it.startEarningAt }, { it.id }))

        val updated = InvestmentInterestService.applyFifoPrincipalReduction(ordered, 100.0)
        val earlyUpdated = updated.first { it.id == 1L }
        val lateUpdated = updated.first { it.id == 2L }

        assertEquals(0.0, earlyUpdated.remainingPrincipal, 0.000001)
        assertEquals(60.0, lateUpdated.remainingPrincipal, 0.000001)
    }

    @Test
    fun investmentLot_keepsPerLotAnnualInterestRate() {
        val lot = lot(
            id = 1L,
            startEarningAt = dayMillis(2024, 1, 1),
            remainingPrincipal = 100.0,
            annualInterestRate = 2.35
        )

        assertEquals(2.35, lot.annualInterestRate, 0.000001)
    }

    private fun lot(
        id: Long,
        startEarningAt: Long,
        remainingPrincipal: Double,
        annualInterestRate: Double = 1.8
    ) = InvestmentLot(
        id = id,
        assetId = 1L,
        sourceBillId = null,
        principalAmount = remainingPrincipal,
        remainingPrincipal = remainingPrincipal,
        currency = "CNY",
        annualInterestRate = annualInterestRate,
        startEarningAt = startEarningAt,
        firstPayoutAt = InvestmentInterestService.plusDays(startEarningAt, 1),
        lastSettledAt = startEarningAt
    )

    private fun dayMillis(year: Int, month: Int, day: Int): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
