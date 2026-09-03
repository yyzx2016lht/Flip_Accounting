package com.taostudio.tapaccounting.widget

import android.content.Context
import java.util.Calendar

/**
 * 桌面小组件支持的三种尺寸。每种尺寸对应一个独立的 AppWidgetProvider，
 * 这样用户在系统"添加小组件"选择器里能直接看到三个不同大小的条目。
 */
enum class WidgetSize {
    COMPACT,   // 2x1，只放得下一个核心数字
    STANDARD,  // 2x2，核心数字 + 1-2 个次要指标
    DETAILED   // 4x2，全部指标 + 预算进度条
}

/** 小组件展示的统计周期。 */
enum class WidgetPeriod {
    THIS_MONTH,
    THIS_WEEK,
    LAST_7_DAYS;

    fun label(): String = when (this) {
        THIS_MONTH -> "本月"
        THIS_WEEK -> "本周"
        LAST_7_DAYS -> "最近7日"
    }

    /** 返回 [start, end] 闭区间的毫秒时间戳，与统计页的日期口径保持一致（周一为周首）。 */
    fun range(now: Long = System.currentTimeMillis()): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        return when (this) {
            THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                setStartOfDay(cal)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            THIS_WEEK -> {
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                setStartOfDay(cal)
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 6)
                setEndOfDay(cal)
                start to cal.timeInMillis
            }
            LAST_7_DAYS -> {
                setEndOfDay(cal)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, -6)
                setStartOfDay(cal)
                cal.timeInMillis to end
            }
        }
    }

    private fun setStartOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun setEndOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
    }
}

/** 小组件可勾选展示的指标。预算类指标始终反映"当前自然月"的总预算，与周期选择无关。 */
enum class WidgetMetric {
    EXPENSE,    // 所选周期内的总支出
    BUDGET,     // 本月总预算
    REMAINING;  // 本月预算剩余

    fun label(): String = when (this) {
        EXPENSE -> "总支出"
        BUDGET -> "本月预算"
        REMAINING -> "预算剩余"
    }
}

/** 单个小组件实例（appWidgetId）的配置。 */
data class WidgetConfig(
    val bookName: String,
    val period: WidgetPeriod = WidgetPeriod.THIS_MONTH,
    val metrics: Set<WidgetMetric> = setOf(WidgetMetric.EXPENSE, WidgetMetric.BUDGET, WidgetMetric.REMAINING)
) {
    companion object {
        fun default(context: Context): WidgetConfig {
            return WidgetConfig(
                bookName = com.taostudio.tapaccounting.BookAccountManager.getSelectedBook(context)
            )
        }
    }
}
