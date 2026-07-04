package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.dao.BillDao
import com.taostudio.tapaccounting.data.local.dao.BudgetDao
import com.taostudio.tapaccounting.data.local.entity.Budget
import java.util.Calendar

/**
 * 预算计算服务。
 * 所有金额来自本地 Room/DAO 计算。
 */
class BudgetService(
    private val budgetDao: BudgetDao,
    private val billDao: BillDao
) {
    sealed class BudgetScope {
        data class Book(val bookName: String) : BudgetScope()
        object AllBooks : BudgetScope()

        val daoBookName: String
            get() = when (this) {
                is Book -> bookName
                AllBooks -> ""
            }
    }

    /** 预算状态 */
    enum class BudgetStatus {
        NORMAL,   // 正常
        WARNING,  // 即将超支（>= alertThreshold）
        EXCEEDED  // 已超支
    }

    /** 预算进度 */
    data class BudgetProgress(
        val budgetAmount: Double,
        val usedAmount: Double,
        val percent: Double,
        val remaining: Double,
        val status: BudgetStatus
    )

    data class BudgetOverview(
        val budget: Budget,
        val progress: BudgetProgress
    )

    /**
     * 获取某分类在指定月份的支出金额。
     * 排除 excludeFromStats = true 的账单。
     */
    suspend fun getMonthSpend(
        bookName: String,
        categoryId: Long?,
        yearMonth: String
    ): Double {
        val (start, end) = parseYearMonthRange(yearMonth) ?: return 0.0
        return if (categoryId == null) {
            billDao.sumBudgetExpense(start, end, bookName)
        } else {
            billDao.sumBudgetExpenseByCategories(start, end, bookName, listOf(categoryId))
                .firstOrNull { it.categoryId == categoryId }
                ?.total
                ?: 0.0
        }
    }

    /**
     * 获取预算进度。
     */
    suspend fun getBudgetProgress(budget: Budget): BudgetProgress {
        val used = getMonthSpend(budget.bookName, budget.categoryId, budget.yearMonth)
        return buildProgress(budget, used)
    }

    /**
     * 根据过去 N 个月同分类月均支出，向上取整到 10 元。
     */
    suspend fun suggestBudgetFromHistory(
        bookName: String,
        categoryId: Long?,
        currentYearMonth: String,
        months: Int = 3
    ): Double? {
        val historyMonths = getPreviousMonths(currentYearMonth, months)
        if (historyMonths.isEmpty()) return null

        var totalSpend = 0.0
        var validMonths = 0
        for (month in historyMonths) {
            val spend = getMonthSpend(bookName, categoryId, month)
            if (spend > 0) {
                totalSpend += spend
                validMonths++
            }
        }

        if (validMonths == 0) return null
        val avg = totalSpend / validMonths
        // 向上取整到 10 元
        return kotlin.math.ceil(avg / 10.0) * 10.0
    }

    /**
     * 获取当月所有预算及其进度。
     */
    suspend fun getMonthBudgetsWithProgress(
        bookName: String,
        yearMonth: String
    ): List<BudgetOverview> {
        return getMonthBudgetOverview(
            scope = if (bookName.isBlank()) BudgetScope.AllBooks else BudgetScope.Book(bookName),
            yearMonth = yearMonth
        )
    }

    suspend fun getMonthBudgetOverview(
        scope: BudgetScope,
        yearMonth: String
    ): List<BudgetOverview> {
        val bookName = scope.daoBookName
        val budgets = if (bookName.isBlank()) {
            budgetDao.getBudgetsByMonth(yearMonth)
        } else {
            budgetDao.getBudgetsByMonthAndBook(yearMonth, bookName)
        }
        if (scope == BudgetScope.AllBooks) {
            return budgets.groupBy { it.bookName }
                .flatMap { (groupBookName, groupBudgets) ->
                    buildBudgetOverview(groupBudgets, groupBookName, yearMonth)
                }
        }
        return buildBudgetOverview(budgets, bookName, yearMonth)
    }

    private suspend fun buildBudgetOverview(
        budgets: List<Budget>,
        bookName: String,
        yearMonth: String
    ): List<BudgetOverview> {
        val (start, end) = parseYearMonthRange(yearMonth) ?: return emptyList()
        val totalUsed = billDao.sumBudgetExpense(start, end, bookName)
        val categoryIds = budgets.mapNotNull { it.categoryId }.distinct()
        val categoryUsage = if (categoryIds.isEmpty()) {
            emptyMap()
        } else {
            billDao.sumBudgetExpenseByCategories(start, end, bookName, categoryIds)
                .associate { it.categoryId to it.total }
        }

        return budgets.map { budget ->
            val used = budget.categoryId?.let { categoryUsage[it] ?: 0.0 } ?: totalUsed
            BudgetOverview(budget, buildProgress(budget, used))
        }
    }

    private fun buildProgress(budget: Budget, used: Double): BudgetProgress {
        val percent = if (budget.amount > 0) used / budget.amount else 0.0
        val remaining = budget.amount - used
        val status = when {
            percent >= 1.0 -> BudgetStatus.EXCEEDED
            percent >= budget.alertThreshold -> BudgetStatus.WARNING
            else -> BudgetStatus.NORMAL
        }
        return BudgetProgress(
            budgetAmount = budget.amount,
            usedAmount = used,
            percent = percent,
            remaining = remaining,
            status = status
        )
    }

    /**
     * 解析年月字符串为时间范围。
     */
    private fun parseYearMonthRange(yearMonth: String): Pair<Long, Long>? {
        val parts = yearMonth.split("-")
        if (parts.size != 2) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null

        val cal = Calendar.getInstance()
        cal.set(year, month - 1, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        return start to end
    }

    /**
     * 获取前 N 个月的年月字符串列表。
     */
    private fun getPreviousMonths(currentYearMonth: String, months: Int): List<String> {
        val parts = currentYearMonth.split("-")
        val year = parts[0].toIntOrNull() ?: return emptyList()
        val month = parts[1].toIntOrNull() ?: return emptyList()

        val result = mutableListOf<String>()
        var y = year
        var m = month
        for (i in 1..months) {
            m--
            if (m < 1) {
                m = 12
                y--
            }
            result.add(String.format("%04d-%02d", y, m))
        }
        return result
    }
}
