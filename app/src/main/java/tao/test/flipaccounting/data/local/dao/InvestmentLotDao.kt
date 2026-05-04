package tao.test.flipaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import tao.test.flipaccounting.data.local.entity.InvestmentLot

@Dao
interface InvestmentLotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLot(lot: InvestmentLot): Long

    @Update
    suspend fun updateLot(lot: InvestmentLot)

    @Query("SELECT * FROM investment_lots WHERE remainingPrincipal > 0.0 ORDER BY startEarningAt ASC, id ASC")
    suspend fun getOpenLots(): List<InvestmentLot>

    @Query("SELECT * FROM investment_lots WHERE sourceBillId = :billId LIMIT 1")
    suspend fun getLotBySourceBillId(billId: Long): InvestmentLot?

    @Query("DELETE FROM investment_lots WHERE sourceBillId = :billId")
    suspend fun deleteBySourceBillId(billId: Long)

    @Query("DELETE FROM investment_lots")
    suspend fun deleteAll()
}
