package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.taostudio.tapaccounting.data.local.entity.Asset

@Dao
interface AssetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: Asset): Long

    @Update
    suspend fun updateAsset(asset: Asset)

    @Delete
    suspend fun deleteAsset(asset: Asset)

    @Query("SELECT * FROM assets ORDER BY sortOrder ASC, includeInNetAsset DESC, id ASC")
    fun getAllAssets(): Flow<List<Asset>>

    @Query("SELECT * FROM assets ORDER BY sortOrder ASC, includeInNetAsset DESC, id ASC")
    suspend fun getAllAssetsList(): List<Asset>

    /** 记账选择器专用：按 pickerSortOrder 排序，pickerSortOrder 为 0 时回退到 sortOrder */
    @Query("SELECT * FROM assets ORDER BY CASE WHEN pickerSortOrder = 0 THEN sortOrder ELSE pickerSortOrder END ASC, id ASC")
    suspend fun getAllAssetsListForPicker(): List<Asset>

    @Query("UPDATE assets SET pickerSortOrder = :order WHERE id = :assetId")
    suspend fun updatePickerSortOrder(assetId: Long, order: Int)

    @Query("SELECT COUNT(*) FROM assets WHERE pickerSortOrder = 0")
    suspend fun countAssetsWithDefaultPickerOrder(): Int

    @Query("SELECT MAX(pickerSortOrder) FROM assets")
    suspend fun getMaxPickerSortOrder(): Int?

    @Transaction
    suspend fun ensurePickerSortOrderBackfilled() {
        if (countAssetsWithDefaultPickerOrder() == 0) return
        val orderedAssets = getAllAssetsListForPicker()
        orderedAssets.forEachIndexed { idx, asset ->
            updatePickerSortOrder(asset.id, (idx + 1) * 10)
        }
    }

    @Transaction
    suspend fun reorderPickerSortOrders(assetIdsInOrder: List<Long>) {
        assetIdsInOrder.forEachIndexed { idx, assetId ->
            updatePickerSortOrder(assetId, (idx + 1) * 10)
        }
    }

    @Query("SELECT * FROM assets WHERE id = :assetId")
    suspend fun getAssetById(assetId: Long): Asset?

    @Query("SELECT * FROM assets WHERE id = :assetId")
    fun observeAssetById(assetId: Long): Flow<Asset?>

    @Query("UPDATE assets SET balance = :newBalance WHERE id = :assetId")
    suspend fun updateBalance(assetId: Long, newBalance: Double)

    @Query("""
        UPDATE assets SET
            name = :name,
            type = :type,
            initialBalance = :initialBalance,
            currency = :currency,
            icon = :icon,
            remark = :remark,
            includeInNetAsset = :includeInNetAsset,
            sortOrder = :sortOrder,
            pickerSortOrder = :pickerSortOrder,
            createTime = :createTime,
            assetCategory = :assetCategory,
            creditLimit = :creditLimit,
            billingDay = :billingDay,
            annualInterestRate = :annualInterestRate,
            interestLastSettledAt = :interestLastSettledAt,
            isArchived = :isArchived
        WHERE id = :id
    """)
    suspend fun updateAssetInfo(
        id: Long,
        name: String,
        type: String,
        initialBalance: Double,
        currency: String,
        icon: String,
        remark: String,
        includeInNetAsset: Boolean,
        sortOrder: Int,
        pickerSortOrder: Int,
        createTime: Long,
        assetCategory: String,
        creditLimit: Double,
        billingDay: Int,
        annualInterestRate: Double,
        interestLastSettledAt: Long,
        isArchived: Boolean
    )

    @Query("UPDATE assets SET balance = :newBalance, interestLastSettledAt = :settledAt WHERE id = :assetId")
    suspend fun updateBalanceAfterInterest(assetId: Long, newBalance: Double, settledAt: Long)

    @Query("UPDATE assets SET interestLastSettledAt = :settledAt WHERE id = :assetId")
    suspend fun updateInterestLastSettledAt(assetId: Long, settledAt: Long)

    @Query("UPDATE assets SET balance = balance + :delta WHERE id = :assetId")
    suspend fun addBalanceDelta(assetId: Long, delta: Double)

    @Query("UPDATE assets SET sortOrder = :sortOrder WHERE id = :assetId")
    suspend fun updateSortOrder(assetId: Long, sortOrder: Int)

    @Query("""
        UPDATE assets
        SET isArchived = :archived,
            includeInNetAsset = CASE WHEN :archived THEN 0 ELSE includeInNetAsset END
        WHERE id = :assetId
    """)
    suspend fun updateArchived(assetId: Long, archived: Boolean)

    /** 获取指定类别中最大的 sortOrder，若该类别无资产则返回 null */
    @Query("SELECT MAX(sortOrder) FROM assets WHERE assetCategory = :category")
    suspend fun getMaxSortOrderInCategory(category: String): Int?

    /**
     * 在一个事务内批量设置所有资产的 sortOrder，然后交换 fromId 与 toId 的顺序。
     * [orders] 为 (assetId -> sortOrder) 的映射，包含全部需要写入的值（含交换后的两条）。
     */
    @Transaction
    suspend fun reorderAssets(orders: Map<Long, Int>) {
        orders.forEach { (id, order) -> updateSortOrder(id, order) }
    }

    @Query("SELECT * FROM assets WHERE name = :name LIMIT 1")
    suspend fun getAssetByName(name: String): Asset?

    @Query("DELETE FROM assets")
    suspend fun deleteAll()
}

