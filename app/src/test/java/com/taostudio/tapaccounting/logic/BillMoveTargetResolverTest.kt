package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import com.taostudio.tapaccounting.viewscope.ViewBookOption
import org.junit.Assert.assertEquals
import org.junit.Test

class BillMoveTargetResolverTest {
    @Test
    fun `targets come from the complete book catalog instead of the selected bill scope`() {
        val viewScope = ResolvedLedgerViewScope(
            scope = LedgerViewScope(
                books = LedgerBookSelection.Selected(setOf(1L)),
                members = LedgerMemberScope.MINE
            ),
            availableBooks = listOf(
                ViewBookOption(id = 1L, name = "默认账本", isShared = false),
                ViewBookOption(id = 2L, name = "旅行账本", isShared = false),
                ViewBookOption(id = 3L, name = "空账本", isShared = false)
            ),
            memberContextsByBookName = emptyMap()
        )
        val targets = BillMoveTargetResolver.resolve(
            viewScope = viewScope,
            selectedBillBookNames = listOf("默认账本")
        )

        assertEquals(
            listOf(
                BillMoveTargetResolver.Target("默认账本", isNoOp = true),
                BillMoveTargetResolver.Target("旅行账本", isNoOp = false),
                BillMoveTargetResolver.Target("空账本", isNoOp = false)
            ),
            targets
        )
    }

    @Test
    fun `aggregate placeholders are excluded and catalog order is preserved`() {
        val targets = BillMoveTargetResolver.resolve(
            availableBookNames = listOf(
                "旅行账本",
                BookAccountManager.ALL_BOOK,
                "默认账本",
                BookAccountManager.COLLAPSED_BOOK_GROUP,
                "旅行账本"
            ),
            selectedBillBookNames = listOf("默认账本")
        )

        assertEquals(
            listOf(
                BillMoveTargetResolver.Target("旅行账本", isNoOp = false),
                BillMoveTargetResolver.Target("默认账本", isNoOp = true)
            ),
            targets
        )
    }

    @Test
    fun `a real book remains actionable for a mixed-source selection`() {
        val targets = BillMoveTargetResolver.resolve(
            availableBookNames = listOf("默认账本", "旅行账本"),
            selectedBillBookNames = listOf("默认账本", "旅行账本")
        )

        assertEquals(
            listOf(
                BillMoveTargetResolver.Target("默认账本", isNoOp = false),
                BillMoveTargetResolver.Target("旅行账本", isNoOp = false)
            ),
            targets
        )
    }
}
