package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope

object BillMoveTargetResolver {
    data class Target(
        val bookName: String,
        val isNoOp: Boolean
    )

    fun resolve(
        availableBookNames: List<String>,
        selectedBillBookNames: Collection<String>
    ): List<Target> {
        val selectedBooks = selectedBillBookNames
            .map(BookAccountManager::normalizeBookName)

        return availableBookNames
            .map(BookAccountManager::normalizeBookName)
            .filter { bookName ->
                bookName.isNotBlank() &&
                    bookName != BookAccountManager.ALL_BOOK &&
                    bookName != BookAccountManager.COLLAPSED_BOOK_GROUP
            }
            .distinct()
            .map { bookName ->
                Target(
                    bookName = bookName,
                    isNoOp = selectedBooks.isNotEmpty() && selectedBooks.all { it == bookName }
                )
            }
    }

    fun resolve(
        viewScope: ResolvedLedgerViewScope,
        selectedBillBookNames: Collection<String>
    ): List<Target> = resolve(
        availableBookNames = viewScope.availableBooks.map { it.name },
        selectedBillBookNames = selectedBillBookNames
    )
}
