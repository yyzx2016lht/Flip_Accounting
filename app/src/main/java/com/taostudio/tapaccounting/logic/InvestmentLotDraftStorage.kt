package com.taostudio.tapaccounting.logic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object InvestmentLotDraftStorage {
    const val PREFS_NAME = "investment_lot_drafts"

    private fun draftKey(assetId: Long): String = "asset_$assetId"

    fun hasDraft(context: Context, assetId: Long): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains(draftKey(assetId))
    }

    fun load(context: Context, assetId: Long): List<InvestmentLotDraft> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(draftKey(assetId), null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val startEarningAt = item.optLong("startEarningAt", 0L)
                if (startEarningAt <= 0L) return@mapNotNull null
                InvestmentLotDraft(
                    amount = item.optDouble("amount", 0.0),
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = startEarningAt,
                        firstPayoutAt = item.optLong(
                            "firstPayoutAt",
                            InvestmentInterestService.plusDays(startEarningAt, 1)
                        ),
                        annualInterestRate = item.optDouble("annualInterestRate", 0.0),
                        settlementCycle = item.optInt(
                            "settlementCycle",
                            com.taostudio.tapaccounting.data.local.entity.InvestmentLot.CYCLE_DAILY
                        )
                    )
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, assetId: Long, drafts: List<InvestmentLotDraft>) {
        val array = JSONArray()
        drafts.forEach { draft ->
            array.put(JSONObject().apply {
                put("amount", draft.amount)
                put("startEarningAt", draft.schedule.startEarningAt)
                put("firstPayoutAt", draft.schedule.firstPayoutAt)
                put("annualInterestRate", draft.schedule.annualInterestRate)
                put("settlementCycle", draft.schedule.settlementCycle)
            })
        }
        check(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(draftKey(assetId), array.toString())
            .commit()) { "无法保存投资批次草稿" }
    }

    fun clear(context: Context, assetId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(draftKey(assetId))
            .apply()
    }

    fun clearAll(context: Context) {
        check(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()) { "无法清理投资批次草稿" }
    }
}
