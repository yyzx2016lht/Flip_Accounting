package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taostudio.tapaccounting.data.local.entity.SharedLedger

@Dao
interface SharedLedgerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(value: SharedLedger): Long
    @Update suspend fun update(value: SharedLedger)
    @Query("SELECT * FROM shared_ledger WHERE id=:id") suspend fun getById(id: Long): SharedLedger?
    @Query("SELECT * FROM shared_ledger WHERE uuid=:uuid") suspend fun getByUuid(uuid: String): SharedLedger?
    @Query("SELECT * FROM shared_ledger WHERE bookId=:bookId") suspend fun getByBookId(bookId: Long): SharedLedger?
    @Query("SELECT shared_ledger.* FROM shared_ledger JOIN books ON books.id=shared_ledger.bookId WHERE books.name=:bookName LIMIT 1") suspend fun getByBookName(bookName: String): SharedLedger?
    @Query("SELECT * FROM shared_ledger ORDER BY createdAt") suspend fun getAll(): List<SharedLedger>
    @Query("DELETE FROM shared_ledger WHERE id=:id") suspend fun deleteById(id: Long)
}
