package com.taostudio.tapaccounting.ui.main.home.dashboard

import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill

/**
 * 首页驾驶舱卡片 Provider 聚合器。
 * 从各 feature 收集数据，按优先级返回最多 3 张卡片。
 */
object HomeDashboardProvider {

    private const val MAX_DASHBOARD_CARDS = 3

    /**
     * 加载所有驾驶舱卡片。
     */
    suspend fun loadDashboardCards(
        ctx: Context,
        db: AppDatabase,
        currentBills: List<Bill>
    ): List<HomeDashboardCard> {
        val cards = mutableListOf<HomeDashboardCard>()

        // 1. 信用卡还款提醒（3 天内到期）
        try {
            val creditAssets = db.assetDao().getAllAssetsList().filter {
                it.assetCategory == com.taostudio.tapaccounting.data.local.entity.Asset.CATEGORY_CREDIT_CARD
            }
            val cycleService = com.taostudio.tapaccounting.logic.CreditCardCycleService()
            for (asset in creditAssets) {
                val snapshot = cycleService.calculateSnapshot(asset, emptyList())
                if (snapshot != null && snapshot.daysToDue != null && snapshot.daysToDue <= 3 && snapshot.amountDue > 0) {
                    cards.add(
                        HomeDashboardCard.Reminder(
                            title = "信用卡还款",
                            body = "${asset.name} 还款日将至，待还 ¥${String.format("%.0f", snapshot.amountDue)}"
                        )
                    )
                    break // 只显示一张
                }
            }
        } catch (_: Exception) { /* 静默忽略 */ }

        // 2. 预算超支提醒
        try {
            val yearMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())
                .format(java.util.Date())
            val budgetService = com.taostudio.tapaccounting.logic.BudgetService(db.budgetDao(), db.billDao())
            val budgets = budgetService.getMonthBudgetsWithProgress("", yearMonth)
            val exceeded = budgets.filter { it.second.status == com.taostudio.tapaccounting.logic.BudgetService.BudgetStatus.EXCEEDED }
            if (exceeded.isNotEmpty()) {
                val top = exceeded.first()
                cards.add(
                    HomeDashboardCard.Reminder(
                        title = "预算超支",
                        body = "${top.first.categoryName ?: "总预算"} 已超支 ${String.format("%.0f", top.second.percent * 100)}%"
                    )
                )
            }
        } catch (_: Exception) { /* 静默忽略 */ }

        return cards.sortedBy { it.priority }.take(MAX_DASHBOARD_CARDS)
    }
}
