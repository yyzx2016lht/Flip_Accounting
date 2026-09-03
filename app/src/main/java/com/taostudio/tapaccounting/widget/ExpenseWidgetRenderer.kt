package com.taostudio.tapaccounting.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.taostudio.tapaccounting.AmountFormatHelper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.MainActivity
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.BudgetService
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从数据库读取当前配置对应的数据，并渲染成对应尺寸的 RemoteViews。
 * 小组件本身不持有业务逻辑，全部复用 [BudgetService] / [com.taostudio.tapaccounting.data.local.dao.BillDao] 的既有查询。
 */
object ExpenseWidgetRenderer {

    data class MetricLine(val metric: WidgetMetric, val label: String, val value: String)

    data class Snapshot(
        val bookLabel: String,
        val periodLabel: String,
        val lines: List<MetricLine>,
        val budgetPercent: Int?,
        val hasBudget: Boolean,
        val isOverBudget: Boolean
    )

    /** all-books 场景下，DAO 层用空字符串代表"全部账本"，与应用其它页面的口径一致。 */
    private fun daoBookName(bookName: String): String {
        val normalized = BookAccountManager.normalizeBookName(bookName)
        return if (normalized == BookAccountManager.ALL_BOOK) "" else normalized
    }

    private fun bookLabel(bookName: String): String = BookAccountManager.normalizeBookName(bookName)

    suspend fun buildSnapshot(context: Context, config: WidgetConfig): Snapshot {
        val db = AppDatabase.getDatabase(context)
        val daoBook = daoBookName(config.bookName)
        val yearMonth = SimpleDateFormat("yyyy-MM", Locale.US).format(System.currentTimeMillis())

        val budget = db.budgetDao().getTotalBudget(yearMonth, daoBook)
        val budgetService = BudgetService(db.budgetDao(), db.billDao(), db.categoryDao())
        val progress = budget?.let { budgetService.getBudgetProgress(it) }

        val lines = mutableListOf<MetricLine>()
        WidgetMetric.values().forEach { metric ->
            if (metric !in config.metrics) return@forEach
            when (metric) {
                WidgetMetric.EXPENSE -> {
                    val (start, end) = config.period.range()
                    val expense = db.billDao().sumBudgetExpense(start, end, daoBook)
                    lines += MetricLine(metric, "${config.period.label()}支出", AmountFormatHelper.formatCurrency("¥", expense))
                }
                WidgetMetric.BUDGET -> {
                    val text = if (budget != null) AmountFormatHelper.formatCurrency("¥", budget.amount) else "未设置"
                    lines += MetricLine(metric, "本月预算", text)
                }
                WidgetMetric.REMAINING -> {
                    val text = if (progress != null) AmountFormatHelper.formatCurrency("¥", progress.remaining) else "未设置"
                    lines += MetricLine(metric, "预算剩余", text)
                }
            }
        }

        return Snapshot(
            bookLabel = bookLabel(config.bookName),
            periodLabel = config.period.label(),
            lines = lines,
            budgetPercent = progress?.let { (it.percent * 100).toInt().coerceAtLeast(0) },
            hasBudget = budget != null,
            isOverBudget = progress?.let { it.remaining < 0 } ?: false
        )
    }

    fun render(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        size: WidgetSize,
        snapshot: Snapshot
    ) {
        val layoutRes = when (size) {
            WidgetSize.COMPACT -> R.layout.widget_expense_compact
            WidgetSize.STANDARD -> R.layout.widget_expense_standard
            WidgetSize.DETAILED -> R.layout.widget_expense_detailed
        }
        val views = RemoteViews(context.packageName, layoutRes)

        views.setTextViewText(R.id.tv_widget_subtitle, "${snapshot.bookLabel} · ${snapshot.periodLabel}")

        val slots = listOf(
            Triple(R.id.tv_widget_primary_label, R.id.tv_widget_primary_value, 0),
            Triple(R.id.tv_widget_secondary_label, R.id.tv_widget_secondary_value, 1),
            Triple(R.id.tv_widget_tertiary_label, R.id.tv_widget_tertiary_value, 2)
        )
        slots.forEach { (labelId, valueId, index) ->
            val line = snapshot.lines.getOrNull(index)
            if (line != null) {
                views.setTextViewText(labelId, line.label)
                views.setTextViewText(valueId, line.value)
                views.setViewVisibility(labelId, android.view.View.VISIBLE)
                views.setViewVisibility(valueId, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(labelId, android.view.View.GONE)
                views.setViewVisibility(valueId, android.view.View.GONE)
            }
        }

        if (size == WidgetSize.DETAILED) {
            if (snapshot.hasBudget && snapshot.budgetPercent != null) {
                views.setViewVisibility(R.id.progress_widget_budget, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.tv_widget_status, android.view.View.VISIBLE)
                views.setProgressBar(R.id.progress_widget_budget, 100, snapshot.budgetPercent.coerceAtMost(100), false)
                views.setTextViewText(
                    R.id.tv_widget_status,
                    if (snapshot.isOverBudget) "已超出预算" else "已用 ${snapshot.budgetPercent}%"
                )
                views.setTextColor(
                    R.id.tv_widget_status,
                    if (snapshot.isOverBudget) 0xFFE53935.toInt() else 0xFF8A94A6.toInt()
                )
            } else {
                views.setViewVisibility(R.id.progress_widget_budget, android.view.View.GONE)
                views.setViewVisibility(R.id.tv_widget_status, android.view.View.GONE)
            }
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
