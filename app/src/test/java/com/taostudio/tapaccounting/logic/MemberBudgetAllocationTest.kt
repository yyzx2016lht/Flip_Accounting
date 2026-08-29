package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MemberBudgetAllocationTest {
    @Test
    fun `one entered amount automatically assigns the remainder to the other member`() {
        assertEquals(
            mapOf("member-a" to 1_000.0, "member-b" to 500.0),
            MemberBudgetAllocation.complete(
                totalBudget = 1_500.0,
                firstMemberId = "member-a",
                firstAmount = 1_000.0,
                secondMemberId = "member-b",
                secondAmount = null
            )
        )
    }

    @Test
    fun `blank member amounts keep the shared total budget behavior`() {
        assertNull(
            MemberBudgetAllocation.complete(
                totalBudget = 1_500.0,
                firstMemberId = "member-a",
                firstAmount = null,
                secondMemberId = "member-b",
                secondAmount = null
            )
        )
    }

    @Test
    fun `explicit member amounts must add up to total budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemberBudgetAllocation.complete(
                totalBudget = 1_500.0,
                firstMemberId = "member-a",
                firstAmount = 900.0,
                secondMemberId = "member-b",
                secondAmount = 500.0
            )
        }
    }

    @Test
    fun `encoded allocations round trip and old budgets fall back to shared total`() {
        val encoded = MemberBudgetAllocation.encode(mapOf("member-a" to 1_000.0, "member-b" to 500.0))

        assertEquals(1_000.0, MemberBudgetAllocation.amountFor(1_500.0, encoded, "member-a"), 0.0)
        assertEquals(500.0, MemberBudgetAllocation.amountFor(1_500.0, encoded, "member-b"), 0.0)
        assertEquals(1_500.0, MemberBudgetAllocation.amountFor(1_500.0, null, "member-a"), 0.0)
    }

    @Test
    fun `five member allocations divide the unassigned remainder between blank members`() {
        val completed = MemberBudgetAllocation.complete(
            totalBudget = 2_000.0,
            memberAmounts = linkedMapOf(
                "member-a" to 800.0,
                "member-b" to 300.0,
                "member-c" to null,
                "member-d" to null,
                "member-e" to 300.0
            )
        )!!

        assertEquals(800.0, completed.getValue("member-a"), 0.001)
        assertEquals(300.0, completed.getValue("member-b"), 0.001)
        assertEquals(300.0, completed.getValue("member-c"), 0.001)
        assertEquals(300.0, completed.getValue("member-d"), 0.001)
        assertEquals(300.0, completed.getValue("member-e"), 0.001)
        assertEquals(2_000.0, completed.values.sum(), 0.001)
    }

    @Test
    fun `all blank member allocations retain the shared total budget behavior`() {
        assertNull(
            MemberBudgetAllocation.complete(
                totalBudget = 2_000.0,
                memberAmounts = linkedMapOf(
                    "member-a" to null,
                    "member-b" to null,
                    "member-c" to null
                )
            )
        )
    }

    @Test
    fun `five member explicit allocations must add up to total budget`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemberBudgetAllocation.complete(
                totalBudget = 2_000.0,
                memberAmounts = linkedMapOf(
                    "member-a" to 500.0,
                    "member-b" to 400.0,
                    "member-c" to 300.0,
                    "member-d" to 200.0,
                    "member-e" to 100.0
                )
            )
        }
    }
}
