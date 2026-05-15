package com.taostudio.tapaccounting.chat.query

import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill

class RoomQueryBillSource(
    private val db: AppDatabase
) : QueryBillSource {
    override suspend fun loadRecent(limit: Int, books: List<String>?): List<Bill> {
        return if (books == null) {
            db.billDao().getRecentBills(limit)
        } else {
            db.billDao().getRecentBillsByBookNames(books, limit)
        }
    }

    override suspend fun loadBetween(startMillis: Long, endMillis: Long, books: List<String>?): List<Bill> {
        return if (books == null) {
            db.billDao().getBillsBetweenTimesList(startMillis, endMillis)
        } else {
            db.billDao().getBillsByBookNamesBetweenTimesList(books, startMillis, endMillis)
        }
    }
}

