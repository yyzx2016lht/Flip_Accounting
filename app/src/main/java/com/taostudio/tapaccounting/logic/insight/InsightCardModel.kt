package com.taostudio.tapaccounting.logic.insight

enum class InsightSeverity { WARN, POSITIVE, INFO }

enum class InsightType {
    MONTH_TOTAL_DELTA,     // 本月总支出环比
    MONTH_CATEGORY_DELTA,  // 分类支出环比
    LARGE_EXPENSE,         // 单笔大额
    RECURRING_HINT,        // 周期扣费提示
    WEEKEND_SPEND,         // 周末支出占比
    CATEGORY_CONCENTRATION // 分类支出集中
}

enum class InsightActionType {
    OPEN_STATS,            // 跳转统计页
    OPEN_CATEGORY,         // 打开分类详情
    OPEN_CALENDAR          // 打开日历
}

data class InsightAction(
    val type: InsightActionType,
    val payload: Map<String, String> = emptyMap()
)

data class InsightCardModel(
    val id: String,
    val type: InsightType,
    val title: String,
    val body: String,
    val severity: InsightSeverity,
    val action: InsightAction? = null,
    val payload: Map<String, String> = emptyMap(),
    val generatedAt: Long = System.currentTimeMillis()
)
