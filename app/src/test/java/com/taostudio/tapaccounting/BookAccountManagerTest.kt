package com.taostudio.tapaccounting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookAccountManagerTest {
    @Test
    fun normalizeBookName_preservesDefaultBookAsAUserChosenName() {
        assertEquals("默认账本", BookAccountManager.normalizeBookName(" 默认账本 "))
    }

    @Test
    fun rawAliases_doesNotTreatDefaultBookAsAnAliasOfDailyBook() {
        val aliases = BookAccountManager.rawAliases(BookAccountManager.DEFAULT_BOOK)

        assertFalse("默认账本 must remain a distinct book", "默认账本" in aliases)
    }

    @Test
    fun withAllBookOption_keepsDefaultBookWhenDailyBookIsTheActualDefault() {
        val books = BookAccountManager.withAllBookOption(
            books = listOf("默认账本"),
            defaultBookName = "日常账本"
        )

        assertEquals(listOf("日常账本", "默认账本", "全部账本"), books)
    }
}
