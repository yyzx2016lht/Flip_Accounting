package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.insight.InsightEngine
import com.taostudio.tapaccounting.logic.insight.InsightSeverity
import com.taostudio.tapaccounting.logic.insight.InsightType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InsightEngineTest {

    @Test
    fun generate_includesPositiveTotalDeltaWhenMonthSpendDrops() {
        val current = listOf(
            expense(100.0, "餐饮", day = 3),
            expense(120.0, "交通", day = 8),
            expense(80.0, "日用", day = 12)
        )
        val previous = listOf(
            expense(300.0, "餐饮", day = 3),
            expense(250.0, "交通", day = 8),
            expense(250.0, "日用", day = 12)
        )

        val cards = InsightEngine.generateForStats(current, previous)

        assertTrue(cards.any {
            it.type == InsightType.MONTH_TOTAL_DELTA && it.severity == InsightSeverity.POSITIVE
        })
    }

    @Test
    fun generate_detectsLargeExpenseInHistoricalMonth() {
        val current = listOf(
            expense(20.0, "餐饮", day = 1),
            expense(30.0, "餐饮", day = 2),
            expense(25.0, "餐饮", day = 3),
            expense(35.0, "餐饮", day = 4),
            expense(800.0, "购物", day = 27, id = 88)
        )

        val cards = InsightEngine.generateForStats(current, emptyList())

        assertTrue(cards.any {
            it.type == InsightType.LARGE_EXPENSE && it.payload["billId"] == "88"
        })
    }

    @Test
    fun generate_usesStableIdsForSameInput() {
        val current = listOf(
            expense(100.0, "餐饮", day = 1),
            expense(200.0, "餐饮", day = 2),
            expense(300.0, "交通", day = 3)
        )
        val previous = listOf(
            expense(60.0, "餐饮", day = 1),
            expense(80.0, "餐饮", day = 2),
            expense(100.0, "交通", day = 3)
        )

        val first = InsightEngine.generateForStats(current, previous).map { it.id }
        val second = InsightEngine.generateForStats(current, previous).map { it.id }

        assertEquals(first, second)
    }

    private fun expense(
        amount: Double,
        category: String,
        day: Int,
        id: Long = day.toLong()
    ): Bill {
        val cal = Calendar.getInstance().apply {
            set(2024, Calendar.MARCH, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return Bill(
            id = id,
            type = Bill.TYPE_EXPENSE,
            amount = amount,
            categoryName = category,
            time = cal.timeInMillis
        )
    }
}
