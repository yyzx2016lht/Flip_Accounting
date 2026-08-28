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
}
