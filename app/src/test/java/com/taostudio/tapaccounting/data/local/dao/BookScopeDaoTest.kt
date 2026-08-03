package com.taostudio.tapaccounting.data.local.dao

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BookScopeDaoTest {
    @Test
    fun renameBookReferences_updatesEveryBookScopedTable() = runBlocking {
        val dao = RecordingBookScopeDao()
        val aliases = listOf("旧账本", "历史别名")

        dao.renameBookReferences(aliases, "新账本")

        val expected = aliases to "新账本"
        assertEquals(expected, dao.billRename)
        assertEquals(expected, dao.chatRename)
        assertEquals(expected, dao.budgetRename)
        assertEquals(expected, dao.bookIdentityRename)
        assertEquals(expected, dao.recurringPatternRename)
        assertEquals(expected, dao.deletedBillRename)
    }

    @Test
    fun renameBookReferences_preservesBlankAllBooksBudgetScope() = runBlocking {
        val dao = RecordingBookScopeDao()

        dao.renameBookReferences(listOf("", "日常账本"), "新账本")

        assertEquals(listOf("", "日常账本") to "新账本", dao.billRename)
        assertEquals(listOf("日常账本") to "新账本", dao.budgetRename)
        assertEquals(listOf("日常账本") to "新账本", dao.bookIdentityRename)
    }

    private class RecordingBookScopeDao : BookScopeDao {
        var billRename: Pair<List<String>, String>? = null
        var chatRename: Pair<List<String>, String>? = null
        var budgetRename: Pair<List<String>, String>? = null
        var bookIdentityRename: Pair<List<String>, String>? = null
        var recurringPatternRename: Pair<List<String>, String>? = null
        var deletedBillRename: Pair<List<String>, String>? = null

        override suspend fun renameBills(sourceBookNames: List<String>, targetBookName: String) {
            billRename = sourceBookNames to targetBookName
        }

        override suspend fun renameChatMessages(sourceBookNames: List<String>, targetBookName: String) {
            chatRename = sourceBookNames to targetBookName
        }

        override suspend fun renameBudgets(sourceBookNames: List<String>, targetBookName: String) {
            budgetRename = sourceBookNames to targetBookName
        }

        override suspend fun renameBookIdentities(sourceBookNames: List<String>, targetBookName: String) {
            bookIdentityRename = sourceBookNames to targetBookName
        }

        override suspend fun renameRecurringPatterns(sourceBookNames: List<String>, targetBookName: String) {
            recurringPatternRename = sourceBookNames to targetBookName
        }

        override suspend fun renameDeletedBills(sourceBookNames: List<String>, targetBookName: String) {
            deletedBillRename = sourceBookNames to targetBookName
        }
    }
}
