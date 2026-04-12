package tao.test.flipaccounting.data.local

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.Category
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MigrationManager {

    private const val PREF_KEY_MIGRATED = "has_migrated_to_room"

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
        if (sharedPrefs.getBoolean(PREF_KEY_MIGRATED, false)) {
            // 已经迁移过了
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Log.d("Migration", "开始迁移旧数据到 Room 数据库...")

                database.withTransaction {
                    val assetMap = mutableMapOf<String, Long>()
                    val categoryMap = mutableMapOf<String, Long>()

                    // 1. 迁移资产
                    val oldAssets = Prefs.getAssets(context)
                    for (oldAsset in oldAssets) {
                        val assetType = when (oldAsset.type) {
                            "资金" -> "资金"
                            "信用" -> "信用"
                            "投资" -> "投资"
                            else -> oldAsset.type.ifEmpty { "其它" }
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
                        } catch (e: Exception) {
                            Log.e("Migration", "无法解析时间: ${oldBill.time}")
                        }

                        val (type, subType) = normalizeLegacyBillTypeAndSubtype(oldBill.type)
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
                            categoryName = oldBill.categoryName,
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

                // 标记为已迁移
                sharedPrefs.edit().putBoolean(PREF_KEY_MIGRATED, true).apply()
                Log.d("Migration", "数据迁移完成！")

            } catch (e: Exception) {
                Log.e("Migration", "数据迁移失败", e)
            }
        }
    }

    private suspend fun migrateCategories(
        database: AppDatabase,
        oldNodes: List<tao.test.flipaccounting.CategoryNode>,
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
            
            // 递归子分类
            if (node.subs.isNotEmpty()) {
                migrateCategories(database, node.subs, type, id, categoryMap)
            }
        }
    }
}
