package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * Legacy helpers for optional DB columns [Bill.accountBalanceAfter] / [Bill.toAccountBalanceAfter].
 * Asset detail UI uses [AssetBillBalanceHistory.computeBalanceAfterByBillId] instead.
 */
object BillBalanceSnapshotService {

    suspend fun rebuildAllAssetSnapshots(db: AppDatabase) {
        db.assetDao().getAllAssetsList().forEach { asset ->
            rebuildSnapshotsForAsset(db, asset.id)
        }
    }

    suspend fun rebuildSnapshotsForAsset(db: AppDatabase, assetId: Long) {
        val asset = db.assetDao().getAssetById(assetId) ?: return
        val bills = db.billDao().getBillsByAssetIdOrNameList(asset.id, asset.name)
        if (bills.isEmpty()) return

        val balanceAfterByBillId = AssetBillBalanceHistory.computeBalanceAfterByBillId(
            bills = bills,
            assetId = asset.id,
            assetName = asset.name,
            assetCurrency = asset.currency,
            currentBalance = asset.balance
        )

        for (bill in bills) {
            val balanceAfter = balanceAfterByBillId[bill.id] ?: continue
            val patched = applySnapshotForAsset(bill, asset.id, asset.name, balanceAfter)
            if (patched != bill) {
                db.billDao().updateBill(patched)
            }
        }
    }

    fun balanceAfterForAsset(bill: Bill, assetId: Long, assetName: String): Double? {
        return when {
            AssetBillBalanceHistory.matchesSource(bill, assetId, assetName) -> bill.accountBalanceAfter
            AssetBillBalanceHistory.matchesTarget(bill, assetId, assetName) -> bill.toAccountBalanceAfter
            else -> null
        }
    }

    private fun applySnapshotForAsset(
        bill: Bill,
        assetId: Long,
        assetName: String,
        balanceAfter: Double
    ): Bill {
        val rounded = BillAssetImpactService.roundMoney(balanceAfter)
        return when {
            AssetBillBalanceHistory.matchesSource(bill, assetId, assetName) ->
                bill.copy(accountBalanceAfter = rounded)
            AssetBillBalanceHistory.matchesTarget(bill, assetId, assetName) ->
                bill.copy(toAccountBalanceAfter = rounded)
            else -> bill
        }
    }
}
