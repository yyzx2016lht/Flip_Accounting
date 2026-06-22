package com.taostudio.tapaccounting.data.local.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.taostudio.tapaccounting.data.local.entity.Bill

@Dao
interface BillDao {
    @Delete
    suspend fun delete(bill: Bill)

    @Delete
    suspend fun delete(bills: List<Bill>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBill(bill: Bill): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBills(bills: List<Bill>)

    @Update
    suspend fun updateBill(bill: Bill)

    @Query("SELECT * FROM bills")
    suspend fun getAllBillsList(): List<Bill>

    @Query("SELECT * FROM bills WHERE time BETWEEN :startTime AND :endTime ORDER BY time DESC")
    fun getBillsBetweenTimes(startTime: Long, endTime: Long): Flow<List<Bill>>

    @Query("SELECT * FROM bills WHERE time BETWEEN :startTime AND :endTime ORDER BY time DESC")
    suspend fun getBillsBetweenTimesList(startTime: Long, endTime: Long): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE bookName IN (:bookNames)
          AND time BETWEEN :startTime AND :endTime
        ORDER BY time DESC
    """)
    fun getBillsByBookNamesBetweenTimes(bookNames: List<String>, startTime: Long, endTime: Long): Flow<List<Bill>>

    @Query("""
        SELECT * FROM bills
        WHERE bookName IN (:bookNames)
          AND time BETWEEN :startTime AND :endTime
        ORDER BY time DESC
    """)
    suspend fun getBillsByBookNamesBetweenTimesList(bookNames: List<String>, startTime: Long, endTime: Long): List<Bill>

    @Query("SELECT * FROM bills WHERE accountId = :assetId OR toAccountId = :assetId ORDER BY time DESC")
    fun getBillsByAssetId(assetId: Long): Flow<List<Bill>>

    @Query("""
        SELECT * FROM bills
        WHERE accountId = :assetId
           OR toAccountId = :assetId
           OR (:assetName != '' AND accountName = :assetName)
           OR (:assetName != '' AND toAccountName = :assetName)
        ORDER BY time DESC
    """)
    fun getBillsByAssetIdOrName(assetId: Long, assetName: String): Flow<List<Bill>>

    /** 批量回填：将 accountName 映射到 accountId（兼容旧账单） */
    @Query("""
        UPDATE bills
        SET accountId = (
            SELECT assets.id FROM assets
            WHERE assets.name = bills.accountName
            LIMIT 1
        )
        WHERE accountId IS NULL
          AND accountName IS NOT NULL
          AND accountName != ''
    """)
    suspend fun backfillAccountIdByName()

    /** 批量回填：将 toAccountName 映射到 toAccountId（兼容旧账单） */
    @Query("""
        UPDATE bills
        SET toAccountId = (
            SELECT assets.id FROM assets
            WHERE assets.name = bills.toAccountName
            LIMIT 1
        )
        WHERE toAccountId IS NULL
          AND toAccountName IS NOT NULL
          AND toAccountName != ''
    """)
    suspend fun backfillToAccountIdByName()

    /** 资产改名场景：按旧名称把未绑定 accountId 的账单补绑定到当前资产 */
    @Query("""
        UPDATE bills
        SET accountId = :assetId
        WHERE accountId IS NULL
          AND accountName = :oldName
    """)
    suspend fun bindAccountIdByLegacyName(assetId: Long, oldName: String)

    /** 资产改名场景：按旧名称把未绑定 toAccountId 的账单补绑定到当前资产 */
    @Query("""
        UPDATE bills
        SET toAccountId = :assetId
        WHERE toAccountId IS NULL
          AND toAccountName = :oldName
    """)
    suspend fun bindToAccountIdByLegacyName(assetId: Long, oldName: String)

    /** 同步展示名称：绑定到该资产的账单账户名统一更新为新名称 */
    @Query("UPDATE bills SET accountName = :newName WHERE accountId = :assetId")
    suspend fun syncAccountNameByAssetId(assetId: Long, newName: String)

    /** 同步展示名称：绑定到该资产的转入账户名统一更新为新名称 */
    @Query("UPDATE bills SET toAccountName = :newName WHERE toAccountId = :assetId")
    suspend fun syncToAccountNameByAssetId(assetId: Long, newName: String)

    /** 同步展示名称：绑定到该分类的账单分类名统一更新为新名称 */
    @Query("UPDATE bills SET categoryName = :newName WHERE categoryId = :categoryId")
    suspend fun syncCategoryNameByCategoryId(categoryId: Long, newName: String)

    /** 同步展示名称：无外键但分类名匹配旧叶子名的账单也一并更新 */
    @Query("""
        UPDATE bills
        SET categoryName = REPLACE(categoryName, :oldLeaf, :newLeaf)
        WHERE categoryId IS NULL
          AND (
              categoryName = :oldLeaf
              OR categoryName LIKE '% - ' || :oldLeaf
              OR categoryName = '退款：' || :oldLeaf
              OR categoryName LIKE '退款：% - ' || :oldLeaf
          )
    """)
    suspend fun syncCategoryNameByOldName(oldLeaf: String, newLeaf: String)

    @Transaction
    suspend fun backfillAssetLinksByName() {
        backfillAccountIdByName()
        backfillToAccountIdByName()
    }

    @Query("SELECT * FROM bills WHERE isSynced = 0")
    suspend fun getUnsyncedBills(): List<Bill>

    @Query("UPDATE bills SET isSynced = 1 WHERE id IN (:billIds)")
    suspend fun markAsSynced(billIds: List<Long>)
    
    @Query("SELECT * FROM bills WHERE id = :id LIMIT 1")
    suspend fun getBillById(id: Long): Bill?

    @Query("SELECT * FROM bills WHERE id IN (:ids)")
    suspend fun getBillsByIds(ids: List<Long>): List<Bill>

    @Query("SELECT * FROM bills WHERE relatedBillId = :sourceBillId AND subType = :refundSubtype ORDER BY time DESC")
    suspend fun getRefundBillsBySourceId(sourceBillId: Long, refundSubtype: Int = Bill.SUBTYPE_REFUND): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE type = :expenseType
          AND subType != :refundSubtype
          AND bookName = :bookName
          AND categoryName = :categoryName
          AND amount + 0.000001 >= :refundAmount
          AND time <= :refundTime
        ORDER BY
          CASE WHEN ABS(amount - :refundAmount) <= 0.01 THEN 0 ELSE 1 END,
          time DESC,
          CASE WHEN accountName = :refundAccountName THEN 0 ELSE 1 END,
          ABS(amount - :refundAmount) ASC,
          id DESC
        LIMIT 1
    """)
    suspend fun findLikelyRefundSourceBill(
        bookName: String,
        categoryName: String,
        refundAmount: Double,
        refundAccountName: String,
        refundTime: Long,
        expenseType: Int = Bill.TYPE_EXPENSE,
        refundSubtype: Int = Bill.SUBTYPE_REFUND
    ): Bill?

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM bills WHERE relatedBillId = :sourceBillId AND subType = :refundSubtype")
    suspend fun getRefundTotalBySourceId(sourceBillId: Long, refundSubtype: Int = Bill.SUBTYPE_REFUND): Double

    @Query("SELECT DISTINCT bookName FROM bills ORDER BY bookName ASC")
    suspend fun getAllBookNames(): List<String>

    @Query("SELECT * FROM bills WHERE bookName IN (:bookNames)")
    suspend fun getBillsByBookNamesList(bookNames: List<String>): List<Bill>

    @Query("SELECT COUNT(*) FROM bills WHERE bookName = :bookName")
    suspend fun countBillsByBookName(bookName: String): Int

    @Query("UPDATE bills SET bookName = :newBookName WHERE bookName = :oldBookName")
    suspend fun renameBookName(oldBookName: String, newBookName: String)

    /** 批量将指定 id 的账单移动到新账本 */
    @Query("UPDATE bills SET bookName = :newBookName WHERE id IN (:ids)")
    suspend fun moveBillsToBook(ids: List<Long>, newBookName: String)

    /** 查询与某资产相关的所有账单（用于删除资产前统计/处理） */
    @Query("SELECT * FROM bills WHERE accountId = :assetId OR toAccountId = :assetId")
    suspend fun getBillsByAssetIdList(assetId: Long): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE accountId = :assetId
           OR toAccountId = :assetId
           OR (:assetName != '' AND accountName = :assetName)
           OR (:assetName != '' AND toAccountName = :assetName)
        ORDER BY time DESC, id DESC
    """)
    suspend fun getBillsByAssetIdOrNameList(assetId: Long, assetName: String): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE accountId = :assetId
           OR toAccountId = :assetId
           OR (:assetName != '' AND accountName = :assetName)
           OR (:assetName != '' AND toAccountName = :assetName)
        ORDER BY time DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getBillsByAssetIdOrNameListLimited(assetId: Long, assetName: String, limit: Int): List<Bill>

    /** 查询某分类下的所有账单 */
    @Query("SELECT * FROM bills WHERE categoryId = :categoryId")
    suspend fun getBillsByCategoryIdList(categoryId: Long): List<Bill>

    @Query("SELECT id FROM bills WHERE categoryId = :categoryId")
    suspend fun getBillIdsByCategoryIdList(categoryId: Long): List<Long>

    @Query("""
        SELECT * FROM bills
        WHERE categoryId IS NULL
          AND (
              categoryName = :name
              OR categoryName LIKE '% - ' || :name
              OR categoryName = '退款：' || :name
              OR categoryName LIKE '退款：% - ' || :name
          )
    """)
    suspend fun getBillsByCategoryNameList(name: String): List<Bill>

    @Query("""
        SELECT id FROM bills
        WHERE categoryId IS NULL
          AND (
              categoryName = :name
              OR categoryName LIKE '% - ' || :name
              OR categoryName = '退款：' || :name
              OR categoryName LIKE '退款：% - ' || :name
          )
    """)
    suspend fun getBillIdsByCategoryNameList(name: String): List<Long>

    /** 将转账/还款类账单中该资产的 accountId 置 null（解除关联，不删除账单） */
    @Query("UPDATE bills SET accountId = NULL WHERE accountId = :assetId")
    suspend fun clearAccountId(assetId: Long)

    /** 将转账/还款类账单中该资产的 toAccountId 置 null（解除关联，不删除账单） */
    @Query("UPDATE bills SET toAccountId = NULL WHERE toAccountId = :assetId")
    suspend fun clearToAccountId(assetId: Long)

    /** 删除资产后，为原账户名添加删除标记，避免未来同名资产被自动回填绑定。 */
    @Query(
        """
        UPDATE bills
        SET accountName = :deletedLabel
        WHERE accountId IS NULL
          AND accountName = :oldName
        """
    )
    suspend fun markDeletedAccountName(oldName: String, deletedLabel: String)

    /** 删除资产后，为转入账户名添加删除标记，避免未来同名资产被自动回填绑定。 */
    @Query(
        """
        UPDATE bills
        SET toAccountName = :deletedLabel
        WHERE toAccountId IS NULL
          AND toAccountName = :oldName
        """
    )
    suspend fun markDeletedToAccountName(oldName: String, deletedLabel: String)

    /** 统计某分类下的账单数量 */
    @Query("SELECT COUNT(*) FROM bills WHERE categoryId = :categoryId")
    suspend fun countBillsByCategoryId(categoryId: Long): Int

    /** 将某分类下所有账单的 categoryId 迁移到新分类 */
    @Query("UPDATE bills SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun migrateCategoryId(oldCategoryId: Long, newCategoryId: Long)

    /** 将某分类下所有账单的 categoryId 置 null */
    @Query("UPDATE bills SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategoryId(categoryId: Long)

    /**
     * 统计 categoryName 精确匹配或以 "% - name" 结尾的账单数量
     * 用于补充统计 categoryId 为 null 但 categoryName 仍关联的旧账单
     */
    @Query("""
        SELECT COUNT(*) FROM bills
        WHERE categoryName = :name
           OR categoryName LIKE '% - ' || :name
           OR categoryName = '退款：' || :name
           OR categoryName LIKE '退款：% - ' || :name
    """)
    suspend fun countBillsByCategoryName(name: String): Int

    /**
     * 将 categoryName 精确匹配或以 "% - name" 结尾的账单 categoryId 迁移到新分类
     */
    @Query("""
        UPDATE bills
        SET categoryId = :newCategoryId
        WHERE categoryId IS NULL
          AND (
              categoryName = :name
              OR categoryName LIKE '% - ' || :name
              OR categoryName = '退款：' || :name
              OR categoryName LIKE '退款：% - ' || :name
          )
    """)
    suspend fun migrateCategoryByName(name: String, newCategoryId: Long)

    /**
     * 清除已删除分类关联的账单 categoryName（categoryId 已被外键 SET NULL，此处清理残留名称）
     */
    @Query("""
        UPDATE bills
        SET categoryName = ''
        WHERE categoryId IS NULL
          AND (
              categoryName = :name
              OR categoryName LIKE '% - ' || :name
              OR categoryName = '退款：' || :name
              OR categoryName LIKE '退款：% - ' || :name
          )
    """)
    suspend fun clearCategoryByName(name: String)

    @Query("DELETE FROM bills")
    suspend fun deleteAll()

    /**
     * 去重查询：判断是否存在相同账单（时间+金额+类型+账户名）
     * 用于合并恢复时跳过重复账单
     */
    @Query("""
        SELECT COUNT(*) FROM bills
        WHERE time = :time
          AND ABS(amount - :amount) < 0.001
          AND type = :type
          AND accountName = :accountName
        LIMIT 1
    """)
    suspend fun countDuplicateBills(time: Long, amount: Double, type: Int, accountName: String): Int

    @Query("DELETE FROM bills WHERE time BETWEEN :startTime AND :endTime")
    suspend fun deleteBillsBetweenTimes(startTime: Long, endTime: Long)

    @Query("DELETE FROM bills WHERE bookName = :bookName AND time BETWEEN :startTime AND :endTime")
    suspend fun deleteBillsByBookNameBetweenTimes(bookName: String, startTime: Long, endTime: Long)

    @Query("SELECT COUNT(*) FROM bills WHERE time BETWEEN :startTime AND :endTime")
    suspend fun countBillsBetweenTimes(startTime: Long, endTime: Long): Int

    @Query("SELECT COUNT(*) FROM bills WHERE bookName = :bookName AND time BETWEEN :startTime AND :endTime")
    suspend fun countBillsByBookNameBetweenTimes(bookName: String, startTime: Long, endTime: Long): Int

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM bills WHERE time BETWEEN :startTime AND :endTime")
    suspend fun sumAmountBetweenTimes(startTime: Long, endTime: Long): Double

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM bills WHERE bookName = :bookName AND time BETWEEN :startTime AND :endTime")
    suspend fun sumAmountByBookNameBetweenTimes(bookName: String, startTime: Long, endTime: Long): Double

    /** 更新单条账单的不计入统计状态 */
    @Query("UPDATE bills SET excludeFromStats = :exclude WHERE id = :billId")
    suspend fun updateExcludeStats(billId: Long, exclude: Boolean)

    /** 批量更新账单的不计入统计状态 */
    @Query("UPDATE bills SET excludeFromStats = :exclude WHERE id IN (:billIds)")
    suspend fun updateExcludeStatsForBills(billIds: List<Long>, exclude: Boolean)

    /** 删除指定账本下的所有账单 */
    @Query("DELETE FROM bills WHERE bookName = :bookName")
    suspend fun deleteAllByBookName(bookName: String)

    @Query("""
        SELECT * FROM bills
        WHERE bookName = :bookName
        ORDER BY time DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getRecentBillsByBookName(
        bookName: String,
        limit: Int
    ): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE bookName IN (:bookNames)
        ORDER BY time DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getRecentBillsByBookNames(
        bookNames: List<String>,
        limit: Int
    ): List<Bill>

    @Query("""
        SELECT * FROM bills
        ORDER BY time DESC, id DESC
        LIMIT :limit
    """)
    suspend fun getRecentBills(
        limit: Int
    ): List<Bill>

    @Query("""
        SELECT * FROM bills
        WHERE type = :expenseType
          AND subType != :refundSubtype
        ORDER BY time DESC
        LIMIT :limit
    """)
    suspend fun getRecentExpenseBills(
        limit: Int,
        expenseType: Int = Bill.TYPE_EXPENSE,
        refundSubtype: Int = Bill.SUBTYPE_REFUND
    ): List<Bill>
}

