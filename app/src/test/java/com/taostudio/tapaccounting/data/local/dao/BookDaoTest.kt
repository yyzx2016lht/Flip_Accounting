package com.taostudio.tapaccounting.data.local.dao

import com.taostudio.tapaccounting.data.local.entity.Book
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BookDaoTest {
    @Test
    fun resolveOrCreateId_returnsStableIdForRenamedBudgetOwner() = runBlocking {
        val dao = RecordingBookDao()

        val first = dao.resolveOrCreateId("旅行账本")
        val second = dao.resolveOrCreateId("旅行账本")

        assertEquals(first, second)
        assertEquals(1, dao.books.size)
    }

    @Test
    fun resolveOrCreateId_usesReservedIdForAllBooksScope() = runBlocking {
        val dao = RecordingBookDao()

        assertEquals(BookDao.ALL_BOOKS_ID, dao.resolveOrCreateId(""))
        assertEquals(emptyList<Book>(), dao.books)
    }

    private class RecordingBookDao : BookDao {
        val books = mutableListOf<Book>()
        private var nextId = 1L

        override suspend fun getByName(name: String) = books.firstOrNull { it.name == name }

        override suspend fun getById(id: Long) = books.firstOrNull { it.id == id }

        override suspend fun getAll() = books.toList()

        override suspend fun insert(book: Book): Long {
            if (books.any { it.name == book.name }) return -1
            val id = nextId++
            books += book.copy(id = id)
            return id
        }
    }
}
