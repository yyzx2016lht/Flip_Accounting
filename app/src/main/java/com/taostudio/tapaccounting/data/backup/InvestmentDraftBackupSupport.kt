package com.taostudio.tapaccounting.data.backup

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import com.taostudio.tapaccounting.logic.InvestmentLotDraft
import com.taostudio.tapaccounting.logic.InvestmentLotDraftStorage

data class InvestmentDraftItemBackup(
    val amount: Double,
    val startEarningAt: Long,
    val firstPayoutAt: Long,
    val annualInterestRate: Double,
    val settlementCycle: Int
)

data class InvestmentDraftRecordBackup(
    val assetName: String,
    val assetType: String,
    val assetCurrency: String,
    val assetCategory: String,
    val assetCreateTime: Long,
    val drafts: List<InvestmentDraftItemBackup>
)

/** Makes persistent investment-lot drafts portable without copying Room-local asset IDs. */
object InvestmentDraftBackupSupport {
    private val gson = Gson()
    private const val MAX_RECORDS = 10_000
    private const val MAX_DRAFTS_PER_ASSET = 10_000

    fun export(context: Context, assets: List<Asset>): List<InvestmentDraftRecordBackup> =
        assets.mapNotNull { asset ->
            val drafts = InvestmentLotDraftStorage.load(context, asset.id)
            if (drafts.isEmpty()) return@mapNotNull null
            InvestmentDraftRecordBackup(
                assetName = asset.name,
                assetType = asset.type,
                assetCurrency = asset.currency,
                assetCategory = asset.assetCategory,
                assetCreateTime = asset.createTime,
                drafts = drafts.map { draft ->
                    InvestmentDraftItemBackup(
                        amount = draft.amount,
                        startEarningAt = draft.schedule.startEarningAt,
                        firstPayoutAt = draft.schedule.firstPayoutAt,
                        annualInterestRate = draft.schedule.annualInterestRate,
                        settlementCycle = draft.schedule.settlementCycle
                    )
                }
            )
        }

    fun encode(records: List<InvestmentDraftRecordBackup>): String = gson.toJson(records)

    fun decode(payload: String): List<InvestmentDraftRecordBackup> {
        val type = object : TypeToken<List<InvestmentDraftRecordBackup>>() {}.type
        val records: List<InvestmentDraftRecordBackup> = try {
            gson.fromJson(payload, type) ?: emptyList()
        } catch (error: Exception) {
            throw BackupFormatException("投资草稿模块无法解析", error)
        }
        require(records.size <= MAX_RECORDS) { "投资草稿资产数量过多" }
        records.forEach { record ->
            require(record.assetName.isNotBlank() && record.assetName.length <= 512) { "投资草稿资产名称无效" }
            require(record.drafts.size <= MAX_DRAFTS_PER_ASSET) { "单个资产的投资草稿过多" }
            record.drafts.forEach { draft ->
                require(draft.amount.isFinite() && draft.amount >= 0.0) { "投资草稿金额无效" }
                require(draft.annualInterestRate.isFinite()) { "投资草稿利率无效" }
                require(draft.startEarningAt > 0L && draft.firstPayoutAt > 0L) { "投资草稿日期无效" }
                require(
                    draft.settlementCycle in setOf(
                        InvestmentLot.CYCLE_DAILY,
                        InvestmentLot.CYCLE_WEEKLY,
                        InvestmentLot.CYCLE_MONTHLY,
                        InvestmentLot.CYCLE_QUARTERLY,
                        InvestmentLot.CYCLE_YEARLY
                    )
                ) { "投资草稿结息周期无效" }
            }
        }
        return records
    }

    fun restore(
        context: Context,
        records: List<InvestmentDraftRecordBackup>,
        currentAssets: List<Asset>,
        replaceAll: Boolean
    ): Int {
        if (replaceAll) InvestmentLotDraftStorage.clearAll(context)
        var restored = 0
        records.forEach { record ->
            val exact = currentAssets.filter { asset ->
                asset.name == record.assetName &&
                    asset.type == record.assetType &&
                    asset.currency == record.assetCurrency &&
                    asset.assetCategory == record.assetCategory &&
                    asset.createTime == record.assetCreateTime
            }
            val fallback = currentAssets.filter { asset ->
                asset.name == record.assetName &&
                    asset.currency == record.assetCurrency &&
                    asset.assetCategory == record.assetCategory
            }
            val asset = exact.singleOrNull() ?: fallback.singleOrNull() ?: return@forEach
            val drafts = record.drafts.map { draft ->
                InvestmentLotDraft(
                    amount = draft.amount,
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = draft.startEarningAt,
                        firstPayoutAt = draft.firstPayoutAt,
                        annualInterestRate = draft.annualInterestRate,
                        settlementCycle = draft.settlementCycle
                    )
                )
            }
            InvestmentLotDraftStorage.save(context, asset.id, drafts)
            restored++
        }
        return restored
    }
}
