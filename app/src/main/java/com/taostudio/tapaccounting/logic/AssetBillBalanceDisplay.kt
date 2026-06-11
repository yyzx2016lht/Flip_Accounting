package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AssetBillBalanceDisplay {

    fun effectiveFromTime(
        asset: Asset,
        billsForAsset: List<Bill>,
        assetId: Long,
        assetName: String
    ): Long {
        val raw = asset.billBalanceFromTime.takeIf { it > 0L }
            ?: assetCreationDayStart(asset, billsForAsset, assetId, assetName)
        return InvestmentInterestService.startOfDay(raw)
    }

    /** Earliest local day for this asset: min(asset record time, first related bill time). */
    fun assetCreationDayStart(
        asset: Asset,
        bills: List<Bill>,
        assetId: Long,
        assetName: String
    ): Long {
        val recordMillis = asset.createTime.takeIf { it > 0L }
        val firstBillMillis = earliestBillTimeMillis(bills, assetId, assetName)
        val anchor = listOfNotNull(recordMillis, firstBillMillis).minOrNull()
            ?: System.currentTimeMillis()
        return InvestmentInterestService.startOfDay(anchor)
    }

    fun earliestBillTimeMillis(
        bills: List<Bill>,
        assetId: Long,
        assetName: String
    ): Long? {
        return bills.asSequence()
            .filter { billTouchesAsset(it, assetId, assetName) }
            .minOfOrNull { it.time }
    }

    fun billTouchesAsset(bill: Bill, assetId: Long, assetName: String): Boolean {
        return AssetBillBalanceHistory.matchesSource(bill, assetId, assetName) ||
            AssetBillBalanceHistory.matchesTarget(bill, assetId, assetName)
    }

    fun formatFromDateLabel(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timeMillis))
    }

    fun shouldShowBalanceForBill(
        asset: Asset,
        billTimeMillis: Long,
        billsForAsset: List<Bill>,
        assetId: Long,
        assetName: String
    ): Boolean {
        if (!asset.showBillBalanceAfter) return false
        return billTimeMillis >= effectiveFromTime(asset, billsForAsset, assetId, assetName)
    }
}
