package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.util.TimeZone

class QueryContextBuilder(
    private val db: AppDatabase
) {
    suspend fun build(currentBookName: String): QueryContext {
        val normalizedBook = BookAccountManager.normalizeBookName(currentBookName)
        val allAssets = db.assetDao().getAllAssetsList()
            .filterNot { it.isArchived }
        val allCategories = db.categoryDao().getAllCategoriesList()
        val dbBooks = db.billDao().getAllBookNames().map { BookAccountManager.normalizeBookName(it) }
        val books = linkedSetOf<String>().apply {
            add(normalizedBook)
            dbBooks.forEach { add(it) }
        }.filter { it.isNotBlank() }
        val recentBills = when (normalizedBook) {
            BookAccountManager.ALL_BOOK -> db.billDao().getRecentBills(24)
            else -> {
                val writable = BookAccountManager.resolveWritableBook(normalizedBook)
                db.billDao().getRecentBillsByBookName(writable, 24)
            }
        }
        val recentHints = buildRecentBillHints(recentBills)
        val currencies = linkedSetOf<String>().apply {
            allAssets.mapTo(this) { it.currency.trim().uppercase() }.filter { it.isNotBlank() }
            recentBills.mapTo(this) { it.currency.trim().uppercase() }.filter { it.isNotBlank() }
        }.toList()

        return QueryContext(
            nowMillis = System.currentTimeMillis(),
            timezoneId = TimeZone.getDefault().id,
            currentBookName = normalizedBook,
            availableBooks = books,
            assets = allAssets.map { QueryAssetOption(it.id, it.name, it.currency) },
            categories = allCategories.map { QueryCategoryOption(it.id, it.name, it.type) },
            currencies = currencies,
            capabilities = QueryCapabilities(
                canOpenStatsPage = true,
                canOpenAssetStatsPage = true,
                supportsStatsExternalFilter = true,
                supportsAssetStatsTimeRange = true,
                supportsAssetStatsBillType = true
            ),
            recentBillHints = recentHints
        )
    }

    private fun buildRecentBillHints(bills: List<Bill>): List<String> {
        return bills.mapNotNull { bill ->
            val fields = listOf(
                bill.categoryName.trim(),
                bill.remark.trim(),
                bill.accountName.trim(),
                bill.toAccountName.trim()
            ).filter { it.isNotBlank() }
            if (fields.isEmpty()) null else fields.joinToString(" | ").take(60)
        }.distinct().take(12)
    }
}

