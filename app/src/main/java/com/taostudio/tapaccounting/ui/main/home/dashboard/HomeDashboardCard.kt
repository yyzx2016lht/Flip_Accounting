package com.taostudio.tapaccounting.ui.main.home.dashboard

import com.taostudio.tapaccounting.logic.insight.InsightAction

/**
 * 首页驾驶舱卡片 sealed class。
 * 最多展示 3 张卡片，按优先级裁剪。
 */
sealed class HomeDashboardCard {
    abstract val priority: Int

    /** 预算进度 */
    data class BudgetProgress(
        val categoryName: String,
        val percent: Double,
        val title: String,
        val body: String,
        val action: InsightAction? = null
    ) : HomeDashboardCard() {
        override val priority = 2
    }

    /** 重要提醒（信用卡还款/异常洞察） */
    data class Reminder(
        val title: String,
        val body: String,
        val action: InsightAction? = null
    ) : HomeDashboardCard() {
        override val priority = 1
    }
}
