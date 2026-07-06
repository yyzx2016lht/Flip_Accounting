package com.taostudio.tapaccounting.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot

@Dao
interface InvestmentLotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLot(lot: InvestmentLot): Long

    @Update
    suspend fun updateLot(lot: InvestmentLot)

    @Query("SELECT * FROM investment_lots WHERE remainingPrincipal > 0.0 ORDER BY startEarningAt ASC, id ASC")
    suspend fun getOpenLots(): List<InvestmentLot>

    @Query("SELECT * FROM investment_lots WHERE assetId = :assetId AND remainingPrincipal > 0.0 ORDER BY startEarningAt ASC, id ASC")
    suspend fun getOpenLotsByAssetId(assetId: Long): List<InvestmentLot>

    @Query("SELECT * FROM investment_lots WHERE assetId = :assetId AND remainingPrincipal > 0.0 AND annualInterestRate != 0.0 ORDER BY createTime DESC, id DESC LIMIT 1")
    suspend fun getLatestOpenLotWithRateByAssetId(assetId: Long): InvestmentLot?

    @Query("SELECT * FROM investment_lots ORDER BY startEarningAt ASC, id ASC")
    suspend fun getAllLots(): List<InvestmentLot>

    @Query("SELECT * FROM investment_lots WHERE sourceBillId = :billId LIMIT 1")
    suspend fun getLotBySourceBillId(billId: Long): InvestmentLot?

    @Query("DELETE FROM investment_lots WHERE sourceBillId = :billId")
    suspend fun deleteBySourceBillId(billId: Long)

    @Query("DELETE FROM investment_lots WHERE assetId = :assetId")
    suspend fun deleteByAssetId(assetId: Long)

    @Query("DELETE FROM investment_lots")
    suspend fun deleteAll()
}

