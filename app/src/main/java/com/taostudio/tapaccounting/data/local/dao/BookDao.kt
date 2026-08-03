package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.taostudio.tapaccounting.data.local.entity.Book

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): Book?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Book?

    @Query("SELECT * FROM books ORDER BY id ASC")
    suspend fun getAll(): List<Book>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: Book): Long

    @Transaction
    suspend fun resolveOrCreateId(bookName: String): Long {
        if (bookName.isBlank()) return ALL_BOOKS_ID
        getByName(bookName)?.let { return it.id }
        val insertedId = insert(Book(name = bookName))
        if (insertedId > 0) return insertedId
        return getByName(bookName)?.id
            ?: error("Unable to resolve book identity for '$bookName'")
    }

    companion object {
        /** Reserved non-row identity for the aggregate All Books budget scope. */
        const val ALL_BOOKS_ID = 0L
    }
}
