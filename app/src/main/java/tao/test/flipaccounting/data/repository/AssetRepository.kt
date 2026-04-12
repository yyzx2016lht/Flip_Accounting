package tao.test.flipaccounting.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.dao.AssetDao
import tao.test.flipaccounting.data.local.dao.BillDao
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDeleteHelper

class AssetRepository(
    private val assetDao: AssetDao,
    private val billDao: BillDao? = null,
    private val appDatabase: AppDatabase? = null
) {

    val allAssets: Flow<List<Asset>> = assetDao.getAllAssets()

    suspend fun getAllAssetsList(): List<Asset> {
        return assetDao.getAllAssetsList()
    }

    suspend fun addAsset(asset: Asset): Long {
        return assetDao.insertAsset(asset)
    }

    suspend fun updateAsset(asset: Asset) {
        assetDao.updateAsset(asset)
    }

    suspend fun getAssetById(id: Long): Asset? {
        return assetDao.getAssetById(id)
    }

    suspend fun updateBalance(id: Long, amount: Double) {
        assetDao.updateBalance(id, amount)
    }

    suspend fun deleteAssetWithCleanup(asset: Asset) {
        val dao = billDao ?: return assetDao.deleteAsset(asset)
        val db = appDatabase
        val deletedNameLabel = "${asset.name}（已删除）"

        if (db != null) {
            db.withTransaction {
                dao.backfillAssetLinksByName()
                val relatedBills = dao.getBillsByAssetIdOrNameList(asset.id, asset.name)
                val transferBills = relatedBills.filter { it.type == Bill.TYPE_TRANSFER }
                if (transferBills.isNotEmpty()) {
                    transferBills.forEach { BillDeleteHelper.deleteBillAndRevertBalance(db, it) }
                }
                dao.clearAccountId(asset.id)
                dao.clearToAccountId(asset.id)
                dao.markDeletedAccountName(asset.name, deletedNameLabel)
                dao.markDeletedToAccountName(asset.name, deletedNameLabel)
                assetDao.deleteAsset(asset)
            }
            return
        }

        dao.backfillAssetLinksByName()
        val relatedBills = dao.getBillsByAssetIdOrNameList(asset.id, asset.name)
        val transferBills = relatedBills.filter { it.type == Bill.TYPE_TRANSFER }
        if (transferBills.isNotEmpty()) {
            dao.delete(transferBills)
        }
        dao.clearAccountId(asset.id)
        dao.clearToAccountId(asset.id)
        dao.markDeletedAccountName(asset.name, deletedNameLabel)
        dao.markDeletedToAccountName(asset.name, deletedNameLabel)
        assetDao.deleteAsset(asset)
    }
}
