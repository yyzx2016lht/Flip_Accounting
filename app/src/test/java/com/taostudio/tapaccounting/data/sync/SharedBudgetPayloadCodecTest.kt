package com.taostudio.tapaccounting.data.sync

import com.taostudio.tapaccounting.data.local.entity.Budget
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedBudgetPayloadCodecTest {
    @Test
    fun `member budget allocations survive sync payload round trip`() {
        val budget = Budget(
            bookId = 7L,
            bookName = "共享账本",
            categoryId = null,
            categoryName = null,
            yearMonth = "2026-08",
            amount = 1500.0,
            createdAt = 1L,
            updatedAt = 2L,
            memberBudgetAllocations = "{\"member-a\":1000.0,\"member-b\":500.0}"
        )

        val decoded = SharedBudgetPayloadCodec.decode(SharedBudgetPayloadCodec.encode(budget))!!

        assertEquals(budget.memberBudgetAllocations, decoded.memberBudgetAllocations)
        assertEquals(1500.0, decoded.amount, 0.001)
    }
}
