package tao.test.flipaccounting.data.repository

import android.util.Log
import androidx.room.withTransaction
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.Category
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.logic.CategoryNameNormalizer

class BackupRepository(private val db: AppDatabase) {

    suspend fun getFullData(): Map<String, Any> {
        return mapOf(
            "assets" to db.assetDao().getAllAssetsList(),
            "bills" to db.billDao().getAllBillsList(),
            "categories" to db.categoryDao().getAllCategoriesList(),
            "rules" to db.aiRuleDao().getAllRulesList(),
            "chat_messages" to db.chatMessageDao().getAll()
        )
    }

    suspend fun restoreFullData(
        assets: List<Asset>?,
        bills: List<Bill>?,
        categories: List<Category>?,
        rules: List<AiRule>?,
        chatMessages: List<ChatMessage>?
    ) {
        db.withTransaction {
            val categoryIdMap = mutableMapOf<Long, Long>()
            val assetIdMap = mutableMapOf<Long, Long>()

            if (categories != null) {
                Log.d("BackupRepo", "开始恢复分类，共 ${categories.size} 条")
                db.categoryDao().deleteAll()
                val roots = categories.filter { it.parentId == null }
                val children = categories.filter { it.parentId != null }
                roots.forEach { cat ->
                    val newId = db.categoryDao().insertCategory(cat.copy(id = 0))
                    categoryIdMap[cat.id] = newId
                }
                children.forEach { cat ->
                    val newParentId = cat.parentId?.let { categoryIdMap[it] }
                    val newId = db.categoryDao().insertCategory(cat.copy(id = 0, parentId = newParentId))
                    categoryIdMap[cat.id] = newId
                }
            }

            if (assets != null) {
                db.assetDao().deleteAll()
                assets.forEach { asset ->
                    val newId = db.assetDao().insertAsset(asset.copy(id = 0))
                    assetIdMap[asset.id] = newId
                }
            }

            if (bills != null) {
                db.billDao().deleteAll()
                val existingCategoriesByName = db.categoryDao().getAllCategoriesList().associateBy { it.name }
                val existingAssetsByName = db.assetDao().getAllAssetsList().associateBy { it.name }
                val billIdMap = mutableMapOf<Long, Long>()
                val pendingRelated = mutableListOf<Pair<Long, Long>>()

                bills.forEach { bill ->
                    val remappedCategoryId = resolveCategoryId(bill, categoryIdMap, existingCategoriesByName)
                    val remappedAccountId = resolveAssetId(bill.accountId, bill.accountName, assetIdMap, existingAssetsByName)
                    val remappedToAccountId = resolveAssetId(bill.toAccountId, bill.toAccountName, assetIdMap, existingAssetsByName)

                    val insertedId = try {
                        db.billDao().insertBill(
                            bill.copy(
                                id = 0,
                                categoryId = remappedCategoryId,
                                accountId = remappedAccountId,
                                toAccountId = remappedToAccountId,
                                categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName),
                                relatedBillId = null
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(
                            "BackupRepo",
                            "恢复账单失败 oldBillId=${bill.id}, type=${bill.type}, amount=${bill.amount}, book=${bill.bookName}",
                            e
                        )
                        throw e
                    }
                    billIdMap[bill.id] = insertedId
                    bill.relatedBillId?.let { pendingRelated.add(insertedId to it) }
                }

                pendingRelated.forEach { (newBillId, oldRelatedId) ->
                    val newRelatedId = billIdMap[oldRelatedId] ?: return@forEach
                    val current = db.billDao().getBillById(newBillId) ?: return@forEach
                    db.billDao().updateBill(current.copy(relatedBillId = newRelatedId))
                }
            }

            if (rules != null) {
                db.aiRuleDao().deleteAll()
                rules.forEach { db.aiRuleDao().insertRule(it.copy(id = 0)) }
            }

            if (chatMessages != null) {
                val existingIds = db.chatMessageDao().getAll().map { it.id }
                if (existingIds.isNotEmpty()) {
                    db.chatMessageDao().deleteByIds(existingIds)
                }
                chatMessages.forEach { db.chatMessageDao().insert(it.copy(id = 0)) }
            }
        }
    }

    private fun resolveCategoryId(
        bill: Bill,
        categoryIdMap: Map<Long, Long>,
        existingCategoriesByName: Map<String, Category>
    ): Long? {
        val oldCategoryId = bill.categoryId ?: return null
        categoryIdMap[oldCategoryId]?.let { return it }
        for (name in categoryNameCandidates(bill.categoryName)) {
            existingCategoriesByName[name]?.id?.let { return it }
        }
        return null
    }

    private fun resolveAssetId(
        oldAssetId: Long?,
        assetName: String,
        assetIdMap: Map<Long, Long>,
        existingAssetsByName: Map<String, Asset>
    ): Long? {
        oldAssetId?.let { assetIdMap[it]?.let { mapped -> return mapped } }
        val key = assetName.trim()
        if (key.isBlank()) return null
        return existingAssetsByName[key]?.id
    }

    private fun categoryNameCandidates(raw: String): List<String> {
        val s = CategoryNameNormalizer.normalizeForStorage(raw)
        if (s.isBlank()) return emptyList()
        val set = linkedSetOf<String>()
        set.add(s)
        val gt = s.substringAfterLast('>', "").trim()
        if (gt.isNotBlank()) set.add(gt)
        return set.toList()
    }
}

