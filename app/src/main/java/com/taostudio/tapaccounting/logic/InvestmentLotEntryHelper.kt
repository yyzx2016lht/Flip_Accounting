package com.taostudio.tapaccounting.logic

import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset

object InvestmentLotEntryHelper {
    suspend fun persistConfirmedLots(
        context: Context,
        db: AppDatabase,
        asset: Asset,
        lots: List<InvestmentLotDraft>
    ) {
        InvestmentInterestService.ensureInvestmentCategories(db)
        lots.forEach { lot ->
            InvestmentInterestService.createLotForAssetBalance(
                db = db,
                asset = asset.copy(balance = lot.amount),
                schedule = lot.schedule
            )
        }
        db.assetDao().getAssetById(asset.id)?.let { latestAsset ->
            InvestmentInterestService.reconcileAssetLotsToBalance(db, latestAsset)
        }
        InvestmentLotDraftStorage.clear(context, asset.id)
    }

    fun saveDrafts(context: Context, assetId: Long, drafts: List<InvestmentLotDraft>) {
        InvestmentLotDraftStorage.save(context, assetId, drafts)
    }
}
