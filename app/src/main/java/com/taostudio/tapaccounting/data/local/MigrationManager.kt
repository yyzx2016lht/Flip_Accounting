package com.taostudio.tapaccounting.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer
import java.text.SimpleDateFormat
import java.util.Locale

object MigrationManager {

    private const val PREF_KEY_MIGRATED = "has_migrated_to_room"
    private const val PREF_KEY_CATEGORY_NAME_NORMALIZED = "has_normalized_category_name_storage_v2"
    private const val PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED = "balance_snapshot_backfilled_v1"

    internal fun normalizeLegacyBillTypeAndSubtype(legacyType: Int): Pair<Int, Int> {
        return when (legacyType) {
            Bill.TYPE_EXPENSE,
            Bill.TYPE_INCOME,
            Bill.TYPE_TRANSFER -> legacyType to Bill.SUBTYPE_NORMAL
            Bill.TYPE_REPAYMENT -> Bill.TYPE_TRANSFER to Bill.SUBTYPE_REPAYMENT
            else -> Bill.TYPE_EXPENSE to Bill.SUBTYPE_NORMAL
        }
    }

    suspend fun migrateIfNecessary(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)

        if (!sharedPrefs.getBoolean(PREF_KEY_MIGRATED, false)) {
            withContext(Dispatchers.IO) {
                try {
                    Log.d("Migration", "start migrating legacy data to Room")

                    database.withTransaction {
                        val assetMap = mutableMapOf<String, Long>()
                        val categoryMap = mutableMapOf<String, Long>()

                        val oldAssets = Prefs.getAssets(context)
                        for (oldAsset in oldAssets) {
                            val assetType = when (oldAsset.type) {
                                "资金" -> "资金"
                                "信用" -> "信用"
                                "投资" -> "投资"
                                else -> oldAsset.type.ifEmpty { "其他" }
                            }
                            val newAsset = Asset(
                                name = oldAsset.name,
                                type = assetType,
                                currency = oldAsset.currency,
                                icon = oldAsset.icon,
                                balance = 0.0,
                                initialBalance = 0.0,
                                includeInNetAsset = true
                            )
                            val id = database.assetDao().insertAsset(newAsset)
                            assetMap[oldAsset.name] = id
                        }

                        val oldExpenseCats = Prefs.getCategories(context, Prefs.TYPE_EXPENSE)
                        migrateCategories(database, oldExpenseCats, 0, null, categoryMap)

                        val oldIncomeCats = Prefs.getCategories(context, Prefs.TYPE_INCOME)
                        migrateCategories(database, oldIncomeCats, 1, null, categoryMap)

                        val oldBills = Prefs.getBills(context)
                        val newBills = mutableListOf<Bill>()
                        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        val sdfShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                        for (oldBill in oldBills) {
                            var timeMillis = System.currentTimeMillis()
                            try {
                                val date = sdf.parse(oldBill.time) ?: sdfShort.parse(oldBill.time)
                                if (date != null) timeMillis = date.time
                            } catch (_: Exception) {
                                Log.e("Migration", "failed to parse time: ${oldBill.time}")
                            }

                            val (type, subType) = normalizeLegacyBillTypeAndSubtype(oldBill.type)
                            val normalizedCategoryName = CategoryNameNormalizer.normalizeForStorage(oldBill.categoryName)
                            val bill = Bill(
                                type = type,
                                subType = subType,
                                amount = oldBill.amount,
                                originalAmount = oldBill.amount,
                                currency = "CNY",
                                exchangeRate = 1.0,
                                categoryId = categoryMap[oldBill.categoryName],
                                accountId = assetMap[oldBill.assetName],
                                toAccountId = null,
                                categoryName = normalizedCategoryName,
                                accountName = oldBill.assetName,
                                time = timeMillis,
                                remark = oldBill.remarks,
                                isSynced = true
                            )
                            newBills.add(bill)
                        }

                        if (newBills.isNotEmpty()) {
                            database.billDao().insertBills(newBills)
                        }
                    }

                    sharedPrefs.edit().putBoolean(PREF_KEY_MIGRATED, true).apply()
                    Log.d("Migration", "legacy migration done")
                } catch (e: Exception) {
                    Log.e("Migration", "legacy migration failed", e)
                }
            }
        }

        normalizeStoredCategoryNamesIfNeeded(context, database)
        backfillBalanceSnapshotsIfNeeded(context, database)
    }

    private suspend fun backfillBalanceSnapshotsIfNeeded(context: Context, @Suppress("UNUSED_PARAMETER") database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED, false)) {
            return
        }
        // Asset detail shows balances derived from current balance + bills (not DB snapshot columns).
        sharedPrefs.edit().putBoolean(PREF_KEY_BALANCE_SNAPSHOT_BACKFILLED, true).apply()
        Log.d("Migration", "balance snapshot backfill skipped (display uses backward derivation)")
    }

    private suspend fun normalizeStoredCategoryNamesIfNeeded(context: Context, database: AppDatabase) {
        val sharedPrefs = context.getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        if (sharedPrefs.getBoolean(PREF_KEY_CATEGORY_NAME_NORMALIZED, false)) {
            return
        }

        withContext(Dispatchers.IO) {
            try {
                var updatedCount = 0
                database.withTransaction {
                    val allBills = database.billDao().getAllBillsList()
                    allBills.forEach { bill ->
                        val normalized = CategoryNameNormalizer.normalizeForStorage(bill.categoryName)
                        if (normalized != bill.categoryName) {
                            database.billDao().updateBill(bill.copy(categoryName = normalized))
                            updatedCount += 1
                        }
                    }
                }
                sharedPrefs.edit().putBoolean(PREF_KEY_CATEGORY_NAME_NORMALIZED, true).apply()
                Log.d("Migration", "category name normalization done, updated=$updatedCount")
            } catch (e: Exception) {
                Log.e("Migration", "category name normalization failed", e)
            }
        }
    }

    private suspend fun migrateCategories(
        database: AppDatabase,
        oldNodes: List<com.taostudio.tapaccounting.CategoryNode>,
        type: Int,
        parentId: Long?,
        categoryMap: MutableMap<String, Long>
    ) {
        for (node in oldNodes) {
            val category = Category(
                name = node.name,
                type = type,
                parentId = parentId,
                iconId = node.icon
            )
            val id = database.categoryDao().insertCategory(category)
            categoryMap[node.name] = id

            if (node.subs.isNotEmpty()) {
                migrateCategories(database, node.subs, type, id, categoryMap)
            }
        }
    }
}

