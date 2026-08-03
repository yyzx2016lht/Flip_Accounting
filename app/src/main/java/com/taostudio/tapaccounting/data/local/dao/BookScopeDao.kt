package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Maintains data whose ownership follows a book when that book is renamed.
 *
 * Keep the table list centralized here: updating only one feature's DAO leaves
 * records reachable by the old display name and makes them appear lost.
 */
@Dao
interface BookScopeDao {
    @Transaction
    suspend fun renameBookReferences(sourceBookNames: List<String>, targetBookName: String) {
        if (sourceBookNames.isEmpty()) return
        renameBills(sourceBookNames, targetBookName)
        renameChatMessages(sourceBookNames, targetBookName)
        renameRecurringPatterns(sourceBookNames, targetBookName)
        renameDeletedBills(sourceBookNames, targetBookName)

        // A blank budget bookName means the intentional "all books" scope, while
        // blank names in older tables are legacy aliases of the default book.
        val namedSources = sourceBookNames.filter { it.isNotBlank() }
        if (namedSources.isNotEmpty()) {
            renameBudgets(namedSources, targetBookName)
            renameBookIdentities(namedSources, targetBookName)
        }
    }

    @Query("UPDATE bills SET bookName = :targetBookName WHERE bookName IN (:sourceBookNames)")
    suspend fun renameBills(sourceBookNames: List<String>, targetBookName: String)

    @Query("UPDATE chat_messages SET bookName = :targetBookName WHERE bookName IN (:sourceBookNames)")
    suspend fun renameChatMessages(sourceBookNames: List<String>, targetBookName: String)

    @Query("UPDATE budgets SET bookName = :targetBookName WHERE bookName IN (:sourceBookNames)")
    suspend fun renameBudgets(sourceBookNames: List<String>, targetBookName: String)

    @Query("UPDATE OR IGNORE books SET name = :targetBookName WHERE name IN (:sourceBookNames)")
    suspend fun renameBookIdentities(sourceBookNames: List<String>, targetBookName: String)

    @Query("UPDATE recurring_patterns SET bookName = :targetBookName WHERE bookName IN (:sourceBookNames)")
    suspend fun renameRecurringPatterns(sourceBookNames: List<String>, targetBookName: String)

    @Query("UPDATE deleted_bills SET bookName = :targetBookName WHERE bookName IN (:sourceBookNames)")
    suspend fun renameDeletedBills(sourceBookNames: List<String>, targetBookName: String)
}
