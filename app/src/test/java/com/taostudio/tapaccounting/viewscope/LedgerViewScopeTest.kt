package com.taostudio.tapaccounting.viewscope

import com.taostudio.tapaccounting.data.local.entity.Bill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerViewScopeTest {
    private val books = listOf(
        ViewBookOption(1L, "日常账本", isShared = false),
        ViewBookOption(2L, "家庭账本", isShared = true),
        ViewBookOption(3L, "旅行账本", isShared = true)
    )
    private val members = mapOf(
        "家庭账本" to SharedBookMemberContext(
            localMemberId = "family-me",
            memberNames = mapOf("family-me" to "我", "family-other" to "小明")
        ),
        "旅行账本" to SharedBookMemberContext(
            localMemberId = "trip-me",
            memberNames = mapOf("trip-me" to "我", "trip-other" to "小红")
        )
    )

    @Test
    fun `mine uses the local identity belonging to each shared book`() {
        val scope = resolved(LedgerBookSelection.All, LedgerMemberScope.MINE)

        assertTrue(scope.includes(bill("日常账本")))
        assertTrue(scope.includes(bill("家庭账本", isShared = true, memberId = "family-me")))
        assertFalse(scope.includes(bill("家庭账本", isShared = true, memberId = "family-other")))
        assertTrue(scope.includes(bill("旅行账本", isShared = true, memberId = "trip-me")))
        assertFalse(scope.includes(bill("旅行账本", isShared = true, memberId = "trip-other")))
    }

    @Test
    fun `mine keeps device-only bills inside a shared book`() {
        val scope = resolved(LedgerBookSelection.All, LedgerMemberScope.MINE)

        assertTrue(scope.includes(bill("家庭账本", isShared = false, memberId = null)))
    }

    @Test
    fun `mine excludes shared bills whose ledger identity cannot be resolved`() {
        val scope = ResolvedLedgerViewScope(
            LedgerViewScope(LedgerBookSelection.All, LedgerMemberScope.MINE),
            books,
            memberContextsByBookName = emptyMap()
        )

        assertFalse(scope.includes(bill("家庭账本", isShared = true, memberId = "family-me")))
    }

    @Test
    fun `selected books restrict the result independently from member scope`() {
        val scope = resolved(LedgerBookSelection.Selected(setOf(1L, 3L)), LedgerMemberScope.EVERYONE)

        assertTrue(scope.includes(bill("日常账本")))
        assertFalse(scope.includes(bill("家庭账本", isShared = true, memberId = "family-other")))
        assertTrue(scope.includes(bill("旅行账本", isShared = true, memberId = "trip-other")))
    }

    @Test
    fun `labels make aggregate and member semantics explicit`() {
        assertEquals("我的账单", resolved(LedgerBookSelection.All, LedgerMemberScope.MINE).displayLabel)
        assertEquals("全部账本", resolved(LedgerBookSelection.All, LedgerMemberScope.EVERYONE).displayLabel)
        assertEquals(
            "2 个账本 · 仅我",
            resolved(LedgerBookSelection.Selected(setOf(1L, 2L)), LedgerMemberScope.MINE).displayLabel
        )
    }

    @Test
    fun `member names are resolved within the bill's own shared book`() {
        val scope = resolved(LedgerBookSelection.All, LedgerMemberScope.EVERYONE)

        assertEquals(
            "小明",
            scope.memberNameFor(bill("家庭账本", isShared = true, memberId = "family-other"))
        )
        assertEquals(
            "小红",
            scope.memberNameFor(bill("旅行账本", isShared = true, memberId = "trip-other"))
        )
    }

    @Test
    fun `budget summary is limited to scopes with a defined budget meaning`() {
        assertTrue(resolved(LedgerBookSelection.All, LedgerMemberScope.EVERYONE).supportsBudgetSummary)
        assertTrue(
            resolved(LedgerBookSelection.Selected(setOf(2L)), LedgerMemberScope.EVERYONE)
                .supportsBudgetSummary
        )
        assertTrue(
            resolved(LedgerBookSelection.Selected(setOf(1L)), LedgerMemberScope.MINE)
                .supportsBudgetSummary
        )
        assertFalse(resolved(LedgerBookSelection.All, LedgerMemberScope.MINE).supportsBudgetSummary)
        assertFalse(
            resolved(LedgerBookSelection.Selected(setOf(1L, 2L)), LedgerMemberScope.EVERYONE)
                .supportsBudgetSummary
        )
    }

    private fun resolved(
        booksSelection: LedgerBookSelection,
        memberScope: LedgerMemberScope
    ) = ResolvedLedgerViewScope(
        LedgerViewScope(booksSelection, memberScope),
        books,
        members
    )

    private fun bill(bookName: String, isShared: Boolean = false, memberId: String? = null) = Bill(
        type = Bill.TYPE_EXPENSE,
        amount = 10.0,
        time = 1L,
        bookName = bookName,
        isShared = isShared,
        memberId = memberId
    )
}
