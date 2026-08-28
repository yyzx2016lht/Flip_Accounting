package com.taostudio.tapaccounting.viewscope

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.entity.Bill

enum class LedgerMemberScope {
    EVERYONE,
    MINE
}

sealed interface LedgerBookSelection {
    data object All : LedgerBookSelection

    data class Selected(val bookIds: Set<Long>) : LedgerBookSelection {
        init {
            require(bookIds.isNotEmpty()) { "A selected view scope must contain at least one book" }
        }
    }
}

data class LedgerViewScope(
    val books: LedgerBookSelection,
    val members: LedgerMemberScope = LedgerMemberScope.EVERYONE
)

data class ViewBookOption(
    val id: Long,
    val name: String,
    val isShared: Boolean
)

data class SharedBookMemberContext(
    val localMemberId: String,
    val memberNames: Map<String, String>
)

/**
 * A device-local, read-only view over real books.
 *
 * This is deliberately not a Book: it owns no bills, budgets or sync state.
 */
data class ResolvedLedgerViewScope(
    val scope: LedgerViewScope,
    val availableBooks: List<ViewBookOption>,
    val memberContextsByBookName: Map<String, SharedBookMemberContext>
) {
    private val normalizedContexts = memberContextsByBookName.mapKeys {
        BookAccountManager.normalizeBookName(it.key)
    }

    val selectedBooks: List<ViewBookOption> = when (val selection = scope.books) {
        LedgerBookSelection.All -> availableBooks
        is LedgerBookSelection.Selected -> availableBooks.filter { it.id in selection.bookIds }
    }

    val selectedBookNames: Set<String> = selectedBooks
        .mapTo(linkedSetOf()) { BookAccountManager.normalizeBookName(it.name) }

    val isAllBooks: Boolean = scope.books is LedgerBookSelection.All
    val singleBook: ViewBookOption? = selectedBooks.singleOrNull()
    val singleBookName: String? = singleBook?.name
    val isAggregate: Boolean = isAllBooks || selectedBooks.size > 1
    val legacyBookName: String = singleBookName ?: BookAccountManager.ALL_BOOK

    val signature: String = buildString {
        append(if (isAllBooks) "all" else "selected")
        append(':')
        append(selectedBooks.map { it.id }.sorted().joinToString(","))
        append(':')
        append(scope.members.name)
    }

    val displayLabel: String
        get() {
            if (isAllBooks) {
                return if (scope.members == LedgerMemberScope.MINE) "我的账单" else BookAccountManager.ALL_BOOK
            }
            singleBook?.let { book ->
                return if (book.isShared && scope.members == LedgerMemberScope.MINE) {
                    "${book.name} · 仅我"
                } else {
                    book.name
                }
            }
            val personalBooks = availableBooks.filterNot { it.isShared }.map { it.id }.toSet()
            val selectedIds = selectedBooks.map { it.id }.toSet()
            if (selectedIds.isNotEmpty() && selectedIds == personalBooks) return "仅个人账本"
            val suffix = if (scope.members == LedgerMemberScope.MINE) "仅我" else "全部成员"
            return "${selectedBooks.size} 个账本 · $suffix"
        }

    val supportsBudgetSummary: Boolean
        get() = when {
            singleBook != null && singleBook?.isShared == false -> true
            scope.members != LedgerMemberScope.EVERYONE -> false
            else -> isAllBooks || singleBook != null
        }

    fun includes(bill: Bill): Boolean {
        val bookName = BookAccountManager.normalizeBookName(bill.bookName)
        if (bookName !in selectedBookNames) return false
        if (scope.members == LedgerMemberScope.EVERYONE) return true
        if (!bill.isShared) return true
        val context = normalizedContexts[bookName] ?: return false
        return bill.memberId != null && bill.memberId == context.localMemberId
    }

    fun memberNameFor(bill: Bill): String? {
        val memberId = bill.memberId ?: return null
        val bookName = BookAccountManager.normalizeBookName(bill.bookName)
        return normalizedContexts[bookName]?.memberNames?.get(memberId)
    }

    fun isMine(bill: Bill): Boolean {
        if (!bill.isShared) return true
        val bookName = BookAccountManager.normalizeBookName(bill.bookName)
        return bill.memberId != null && bill.memberId == normalizedContexts[bookName]?.localMemberId
    }
}
