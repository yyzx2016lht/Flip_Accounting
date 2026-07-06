package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class CreditCardCycleServiceTest {

    private val service = CreditCardCycleService()

    @Test
    fun calculateSnapshot_subtractsRepaymentsAfterStatementDate() {
        val asset = creditCard(balance = -800.0, creditLimit = 5000.0)
        val now = millis(2026, 7, 20, 12)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 1000.0, time = millis(2026, 6, 15, 10)),
                refund(id = 2L, amount = 100.0, time = millis(2026, 6, 20, 10)),
                repayment(id = 3L, amount = 300.0, time = millis(2026, 7, 15, 10)),
                expense(id = 4L, amount = 200.0, time = millis(2026, 7, 12, 10))
            ),
            now = now
        )!!

        assertEquals(900.0, snapshot.billedSpend, 0.0001)
        assertEquals(200.0, snapshot.unbilledSpend, 0.0001)
        assertEquals(300.0, snapshot.paymentsInCycle, 0.0001)
        assertEquals(600.0, snapshot.amountDue, 0.0001)
        assertEquals(4200.0, snapshot.availableLimit!!, 0.0001)
        assertEquals(8, snapshot.daysToDue)
    }

    @Test
    fun calculateSnapshot_doesNotRollUnpaidStatementToNextDueDateAfterDueDate() {
        val asset = creditCard(balance = -1000.0, creditLimit = 5000.0)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 1000.0, time = millis(2026, 6, 15, 10))
            ),
            now = millis(2026, 8, 1, 12)
        )!!

        assertEquals(1000.0, snapshot.amountDue, 0.0001)
        assertEquals(0, snapshot.daysToDue)
    }

    @Test
    fun calculateSnapshot_treatsMonthEndStatementDayAsClosedOnShortMonths() {
        val asset = creditCard(statementDay = 31, dueDay = 10)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 500.0, time = millis(2026, 2, 1, 10))
            ),
            now = millis(2026, 2, 28, 23, 59, 59, 999)
        )!!

        assertEquals(500.0, snapshot.billedSpend, 0.0001)
        assertEquals(10, snapshot.daysToDue)
    }

    @Test
    fun calculateSnapshot_doesNotDriftPreviousStatementWhenStatementDayExceedsShortMonth() {
        val asset = creditCard(statementDay = 31, dueDay = 10)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 100.0, time = millis(2026, 1, 28, 10)),
                expense(id = 2L, amount = 200.0, time = millis(2026, 1, 29, 10)),
                expense(id = 3L, amount = 300.0, time = millis(2026, 2, 1, 10))
            ),
            now = millis(2026, 2, 28, 23, 59, 59, 999)
        )!!

        assertEquals(300.0, snapshot.billedSpend, 0.0001)
    }

    @Test
    fun calculateSnapshot_usesTargetMonthEndForMonthEndDueDay() {
        val asset = creditCard(statementDay = 28, dueDay = 31)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 500.0, time = millis(2026, 2, 10, 10))
            ),
            now = millis(2026, 3, 1, 12)
        )!!

        assertEquals(30, snapshot.daysToDue)
    }

    @Test
    fun calculateSnapshot_keepsUnpaidBilledDebtAfterNextStatementCloses() {
        val asset = creditCard(balance = -1000.0, creditLimit = 5000.0)

        val snapshot = service.calculateSnapshot(
            asset = asset,
            bills = listOf(
                expense(id = 1L, amount = 1000.0, time = millis(2026, 6, 15, 10))
            ),
            now = millis(2026, 8, 15, 12)
        )!!

        assertEquals(1000.0, snapshot.amountDue, 0.0001)
        assertEquals(0, snapshot.daysToDue)
    }

    private fun creditCard(
        balance: Double = 0.0,
        creditLimit: Double = 0.0,
        statementDay: Int = 10,
        dueDay: Int = 28
    ) = Asset(
        id = 1L,
        name = "招行信用卡",
        type = "信用卡",
        balance = balance,
        assetCategory = Asset.CATEGORY_CREDIT_CARD,
        creditLimit = creditLimit,
        statementDay = statementDay,
        dueDay = dueDay
    )

    private fun expense(id: Long, amount: Double, time: Long) = Bill(
        id = id,
        type = Bill.TYPE_EXPENSE,
        amount = amount,
        time = time,
        accountId = 1L,
        accountName = "招行信用卡"
    )

    private fun refund(id: Long, amount: Double, time: Long) = Bill(
        id = id,
        type = Bill.TYPE_EXPENSE,
        subType = Bill.SUBTYPE_REFUND,
        amount = amount,
        time = time,
        accountId = 1L,
        accountName = "招行信用卡"
    )

    private fun repayment(id: Long, amount: Double, time: Long) = Bill(
        id = id,
        type = Bill.TYPE_TRANSFER,
        subType = Bill.SUBTYPE_REPAYMENT,
        amount = amount,
        time = time,
        accountId = 2L,
        accountName = "储蓄卡",
        toAccountId = 1L,
        toAccountName = "招行信用卡"
    )

    private fun millis(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
        second: Int = 0,
        millisecond: Int = 0
    ): Long {
        return Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, second)
            set(Calendar.MILLISECOND, millisecond)
        }.timeInMillis
    }
}
