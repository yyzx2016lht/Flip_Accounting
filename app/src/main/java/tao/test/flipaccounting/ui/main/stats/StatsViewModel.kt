package tao.test.flipaccounting.ui.main.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.data.local.dao.BillDao
import tao.test.flipaccounting.data.local.entity.Bill
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TrendStat(val index: Int, val expense: Double, val income: Double)

data class CategoryStat(
    val categoryName: String,
    val amount: Double,
    val percentage: Float,
    val amountDiffFromLastPeriod: Double
)

data class TimeReport(
    val dateString: String,
    val expense: Double,
    val income: Double,
    val balance: Double,
    val bills: List<Bill>
)

data class StatsUiState(
    val year: Int = Calendar.getInstance().get(Calendar.YEAR),
    val month: Int = Calendar.getInstance().get(Calendar.MONTH),
    val isMonthMode: Boolean = true,
    val dateLabel: String = "",
    val forcedStartTime: Long? = null,
    val forcedEndTime: Long? = null,
    val forcedLabel: String? = null,
    val selectedCurrency: String? = null,
    val selectedBookName: String? = null,
    val totalExpense: Double = 0.0,
    val totalIncome: Double = 0.0,
    val balance: Double = 0.0,
    val dailyAvg: Double = 0.0,
    val totalTransfer: Double = 0.0,
    val totalRepayment: Double = 0.0,
    val totalRefund: Double = 0.0,
    val trendStats: List<TrendStat> = emptyList(),
    val categoryStatsExpense: List<CategoryStat> = emptyList(),
    val categoryStatsIncome: List<CategoryStat> = emptyList(),
    val timeReports: List<TimeReport> = emptyList(),
    val bills: List<Bill> = emptyList()
)

class StatsViewModel(private val billDao: BillDao) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private val dfMonthLabel = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val dfDateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private fun statsAmountOf(bill: Bill, selectedCurrency: String?): Double {
        return if (selectedCurrency == null) bill.amount * bill.exchangeRate else bill.amount
    }

    init {
        loadData()
    }

    fun setMode(isMonth: Boolean) {
        _uiState.update {
            it.copy(
                isMonthMode = isMonth,
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun setYearMonth(year: Int, month: Int) {
        _uiState.update {
            it.copy(
                year = year,
                month = month,
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun prevDate() {
        val s = _uiState.value
        if (s.forcedStartTime != null && s.forcedEndTime != null) {
            if (s.forcedStartTime == 0L && s.forcedEndTime == Long.MAX_VALUE) return
            val duration = (s.forcedEndTime - s.forcedStartTime + 1L).coerceAtLeast(1L)
            val newEnd = s.forcedStartTime - 1L
            val newStart = newEnd - duration + 1L
            applyDateRange(newStart.coerceAtLeast(0L), newEnd.coerceAtLeast(0L), null)
            return
        }

        val cal = Calendar.getInstance().apply { set(s.year, s.month, 1) }
        if (s.isMonthMode) cal.add(Calendar.MONTH, -1) else cal.add(Calendar.YEAR, -1)
        setYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    fun nextDate() {
        val s = _uiState.value
        if (s.forcedStartTime != null && s.forcedEndTime != null) {
            if (s.forcedStartTime == 0L && s.forcedEndTime == Long.MAX_VALUE) return
            val duration = (s.forcedEndTime - s.forcedStartTime + 1L).coerceAtLeast(1L)
            val newStart = s.forcedEndTime + 1L
            val newEnd = newStart + duration - 1L
            applyDateRange(newStart, newEnd, null)
            return
        }

        val cal = Calendar.getInstance().apply { set(s.year, s.month, 1) }
        if (s.isMonthMode) cal.add(Calendar.MONTH, 1) else cal.add(Calendar.YEAR, 1)
        setYearMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    fun applyThisMonthFilter() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, dfMonthLabel.format(Date(start)))
    }

    fun applyLastMonthFilter() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, dfMonthLabel.format(Date(start)))
    }

    fun applyThisYearFilter() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, cal.get(Calendar.YEAR).toString())
    }

    fun applyLastYearFilter() {
        val cal = Calendar.getInstance()
        cal.add(Calendar.YEAR, -1)
        val targetYear = cal.get(Calendar.YEAR)
        cal.set(Calendar.MONTH, Calendar.JANUARY)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        cal.set(Calendar.MONTH, Calendar.DECEMBER)
        cal.set(Calendar.DAY_OF_MONTH, 31)
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis

        applyDateRange(start, end, targetYear.toString())
    }

    fun applyAllTimeFilter() {
        applyDateRange(0L, Long.MAX_VALUE, "全部")
    }

    fun applyCustomDateFilter(start: Long, end: Long) {
        val safeStart = minOf(start, end)
        val safeEnd = maxOf(start, end)
        val label = "${dfDateLabel.format(Date(safeStart))}~${dfDateLabel.format(Date(safeEnd))}"
        applyDateRange(safeStart, safeEnd, label)
    }

    fun clearDateFilter() {
        _uiState.update {
            it.copy(
                forcedStartTime = null,
                forcedEndTime = null,
                forcedLabel = null
            )
        }
        loadData()
    }

    fun setCurrencyFilter(currencyCode: String?) {
        _uiState.update { it.copy(selectedCurrency = currencyCode?.takeIf { code -> code.isNotBlank() }) }
        loadData()
    }

    fun setBookFilter(bookName: String?) {
        _uiState.update { it.copy(selectedBookName = bookName?.takeIf { name -> name.isNotBlank() }) }
        loadData()
    }

    fun getTransferBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.type == Bill.TYPE_TRANSFER && it.subType != Bill.SUBTYPE_REPAYMENT }
            .sortedByDescending { it.time }
    }

    fun getRepaymentBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.type == Bill.TYPE_TRANSFER && it.subType == Bill.SUBTYPE_REPAYMENT }
            .sortedByDescending { it.time }
    }

    fun getRefundBills(): List<Bill> {
        return _uiState.value.bills
            .filter { it.subType == Bill.SUBTYPE_REFUND }
            .sortedByDescending { it.time }
    }

    private fun applyDateRange(start: Long, end: Long, label: String?) {
        _uiState.update {
            it.copy(
                forcedStartTime = start,
                forcedEndTime = end,
                forcedLabel = label
            )
        }
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val snapshot = _uiState.value
            val (start, end, label) = resolveRange(snapshot)
            val (prevStart, prevEnd) = resolvePreviousRange(start, end)

            _uiState.update { it.copy(dateLabel = label) }

            val currentFlow = billDao.getBillsBetweenTimes(start, end)
            val prevFlow = if (prevStart <= prevEnd) {
                billDao.getBillsBetweenTimes(prevStart, prevEnd)
            } else {
                flowOf(emptyList())
            }

            combine(currentFlow, prevFlow) { currentBills, prevBills ->
                currentBills to prevBills
            }.collect { (currentBillsRaw, prevBillsRaw) ->
                val stateNow = _uiState.value
                val currentBills = applyExtraFilters(currentBillsRaw, stateNow)
                val prevBills = applyExtraFilters(prevBillsRaw, stateNow)
                processData(currentBills, prevBills, stateNow, start, end)
            }
        }
    }

    private fun resolveRange(state: StatsUiState): Triple<Long, Long, String> {
        val forcedStart = state.forcedStartTime
        val forcedEnd = state.forcedEndTime
        if (forcedStart != null && forcedEnd != null) {
            val label = state.forcedLabel ?: if (forcedEnd == Long.MAX_VALUE) {
                "全部"
            } else {
                "${dfDateLabel.format(Date(forcedStart))}~${dfDateLabel.format(Date(forcedEnd))}"
            }
            return Triple(forcedStart, forcedEnd, label)
        }

        val (start, end) = getTimeRange(state.year, state.month, state.isMonthMode)
        val label = if (state.isMonthMode) {
            String.format(Locale.getDefault(), "%04d-%02d", state.year, state.month + 1)
        } else {
            String.format(Locale.getDefault(), "%04d", state.year)
        }
        return Triple(start, end, label)
    }

    private fun resolvePreviousRange(start: Long, end: Long): Pair<Long, Long> {
        if (end == Long.MAX_VALUE) return Pair(1L, 0L)
        val duration = (end - start + 1L).coerceAtLeast(1L)
        val prevEnd = (start - 1L).coerceAtLeast(0L)
        val prevStart = (prevEnd - duration + 1L).coerceAtLeast(0L)
        return Pair(prevStart, prevEnd)
    }

    private fun applyExtraFilters(bills: List<Bill>, state: StatsUiState): List<Bill> {
        val selectedBookNormalized = state.selectedBookName?.let { BookAccountManager.normalizeBookName(it) }
        return bills.filter { bill ->
            val currencyMatched = state.selectedCurrency == null || bill.currency == state.selectedCurrency
            val bookMatched = selectedBookNormalized == null ||
                selectedBookNormalized == BookAccountManager.ALL_BOOK ||
                BookAccountManager.normalizeBookName(bill.bookName) == selectedBookNormalized
            currencyMatched && bookMatched
        }
    }

    private fun getTimeRange(year: Int, month: Int, isMonth: Boolean): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(year, if (isMonth) month else 0, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        if (isMonth) cal.add(Calendar.MONTH, 1) else cal.add(Calendar.YEAR, 1)
        cal.add(Calendar.MILLISECOND, -1)
        return Pair(start, cal.timeInMillis)
    }

    private fun topLevelCategory(name: String): String {
        val normalized = name.removePrefix("退款：").removePrefix("退款·").trim()
        return normalized
            .split(Regex("\\s*>\\s*|/::/|::|·"))
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { "未分类" }
    }

    private fun secondLevelCategory(name: String): String {
        val normalized = name.removePrefix("退款：").removePrefix("退款·").trim()
        val parts = normalized.split(Regex("\\s*>\\s*|/::/|::|·"))
        return when {
            parts.size >= 2 -> parts[1].trim().ifEmpty { parts[0].trim().ifEmpty { "未分类" } }
            parts.isNotEmpty() -> parts[0].trim().ifEmpty { "未分类" }
            else -> "未分类"
        }
    }

    private fun processData(
        bills: List<Bill>,
        prevBills: List<Bill>,
        state: StatsUiState,
        rangeStart: Long,
        rangeEnd: Long
    ) {
        var totalExpense = 0.0
        var totalIncome = 0.0
        var totalTransfer = 0.0
        var totalRepayment = 0.0
        var totalRefund = 0.0

        val categoryExpenseMap = mutableMapOf<String, Double>()
        val categoryIncomeMap = mutableMapOf<String, Double>()
        val prevCategoryExpenseMap = mutableMapOf<String, Double>()
        val prevCategoryIncomeMap = mutableMapOf<String, Double>()

        val dayMap = mutableMapOf<String, MutableList<Bill>>()
        val cal = Calendar.getInstance()

        bills.forEach { bill ->
            val amount = statsAmountOf(bill, state.selectedCurrency)
            val isRefund = bill.subType == Bill.SUBTYPE_REFUND
            val isRepayment = bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT

            if (isRefund) {
                totalRefund += amount
                // 退款抵扣支出
                totalExpense -= amount
                val topLevel = topLevelCategory(bill.categoryName)
                categoryExpenseMap[topLevel] = (categoryExpenseMap[topLevel] ?: 0.0) - amount
            } else if (isRepayment) {
                totalRepayment += amount
            } else if (bill.type == Bill.TYPE_TRANSFER) {
                totalTransfer += amount
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                totalExpense += amount
                val topLevel = topLevelCategory(bill.categoryName)
                categoryExpenseMap[topLevel] = (categoryExpenseMap[topLevel] ?: 0.0) + amount
            } else if (bill.type == Bill.TYPE_INCOME) {
                totalIncome += amount
                val topLevel = topLevelCategory(bill.categoryName)
                categoryIncomeMap[topLevel] = (categoryIncomeMap[topLevel] ?: 0.0) + amount
            }

            cal.timeInMillis = bill.time
            val dayKey = String.format(
                Locale.getDefault(),
                "%04d-%02d-%02d",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
            )
            dayMap.getOrPut(dayKey) { mutableListOf() }.add(bill)
        }

        prevBills.forEach { bill ->
            val amount = statsAmountOf(bill, state.selectedCurrency)
            val isRefund = bill.subType == Bill.SUBTYPE_REFUND
            val topLevel = topLevelCategory(bill.categoryName)
            
            if (isRefund) {
                prevCategoryExpenseMap[topLevel] = (prevCategoryExpenseMap[topLevel] ?: 0.0) - amount
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                prevCategoryExpenseMap[topLevel] = (prevCategoryExpenseMap[topLevel] ?: 0.0) + amount
            } else if (bill.type == Bill.TYPE_INCOME) {
                prevCategoryIncomeMap[topLevel] = (prevCategoryIncomeMap[topLevel] ?: 0.0) + amount
            }
        }

        val expenseCategoryTotal = categoryExpenseMap.values.sum()
        val incomeCategoryTotal = categoryIncomeMap.values.sum()

        val categoryStatsExpense = categoryExpenseMap
            .map { (name, amount) ->
                CategoryStat(
                    categoryName = name,
                    amount = amount,
                    percentage = if (expenseCategoryTotal > 0) ((amount / expenseCategoryTotal) * 100f).toFloat() else 0f,
                    amountDiffFromLastPeriod = amount - (prevCategoryExpenseMap[name] ?: 0.0)
                )
            }
            .filter { it.amount > 0 } // 只显示净支出大于0的
            .sortedByDescending { it.amount }

        val categoryStatsIncome = categoryIncomeMap
            .map { (name, amount) ->
                CategoryStat(
                    categoryName = name,
                    amount = amount,
                    percentage = if (incomeCategoryTotal > 0) ((amount / incomeCategoryTotal) * 100f).toFloat() else 0f,
                    amountDiffFromLastPeriod = amount - (prevCategoryIncomeMap[name] ?: 0.0)
                )
            }
            .sortedByDescending { it.amount }

        val timeReports = dayMap
            .map { (day, dayBills) ->
                var dayExpense = 0.0
                var dayIncome = 0.0
                dayBills.forEach { b ->
                    val a = statsAmountOf(b, state.selectedCurrency)
                    if (b.subType == Bill.SUBTYPE_REFUND) {
                        dayExpense -= a
                    } else if (b.type == Bill.TYPE_EXPENSE) {
                        dayExpense += a
                    } else if (b.type == Bill.TYPE_INCOME) {
                        dayIncome += a
                    }
                }
                TimeReport(
                    dateString = day,
                    expense = dayExpense,
                    income = dayIncome,
                    balance = dayIncome - dayExpense,
                    bills = dayBills.sortedByDescending { it.time }
                )
            }
            .sortedByDescending { it.dateString }

        val activeDays = if (rangeEnd == Long.MAX_VALUE) {
            maxOf(1, dayMap.size)
        } else {
            val days = ((rangeEnd - rangeStart) / (24L * 60L * 60L * 1000L) + 1L).coerceAtLeast(1L)
            days.toInt()
        }

        _uiState.update {
            it.copy(
                totalExpense = totalExpense,
                totalIncome = totalIncome,
                totalTransfer = totalTransfer,
                totalRepayment = totalRepayment,
                totalRefund = totalRefund,
                balance = totalIncome - totalExpense,
                dailyAvg = totalExpense / activeDays,
                categoryStatsExpense = categoryStatsExpense,
                categoryStatsIncome = categoryStatsIncome,
                timeReports = timeReports,
                bills = bills
            )
        }
    }

    fun getBillsForCategory(categoryName: String, isExpense: Boolean): List<Bill> {
        return _uiState.value.bills.filter { bill ->
            val isMatch = topLevelCategory(bill.categoryName) == categoryName
            if (isExpense) {
                // 支出模式：匹配普通支出 OR 退款（因为退款现在抵扣支出）
                (bill.type == Bill.TYPE_EXPENSE && bill.subType == Bill.SUBTYPE_NORMAL && isMatch) ||
                (bill.subType == Bill.SUBTYPE_REFUND && isMatch)
            } else {
                // 收入模式
                bill.type == Bill.TYPE_INCOME && bill.subType == Bill.SUBTYPE_NORMAL && isMatch
            }
        }
    }

    fun getSubCategoryStats(categoryName: String, isExpense: Boolean): Map<String, Double> {
        val bills = getBillsForCategory(categoryName, isExpense)
        val map = mutableMapOf<String, Double>()
        bills.forEach { bill ->
            val amount = statsAmountOf(bill, _uiState.value.selectedCurrency)
            val sub = secondLevelCategory(bill.categoryName)
            if (bill.subType == Bill.SUBTYPE_REFUND) {
                map[sub] = (map[sub] ?: 0.0) - amount
            } else {
                map[sub] = (map[sub] ?: 0.0) + amount
            }
        }
        return map.filterValues { it > 0 }
    }

    /**
     * 是否存在真正的子分类（而不是仅有顶级分类本身）。
     * 例如："餐饮" -> false；"餐饮>早餐" / "餐饮::早餐" / "餐饮·早餐" -> true
     */
    fun hasSubCategories(categoryName: String, isExpense: Boolean): Boolean {
        val bills = getBillsForCategory(categoryName, isExpense)
        return bills.any { bill ->
            val normalized = bill.categoryName.removePrefix("退款：").removePrefix("退款·").trim()
            val parts = normalized.split(Regex("\\s*>\\s*|/::/|::|·"))
            parts.size >= 2 && parts[1].trim().isNotEmpty()
        }
    }

    fun getBillsForSubCategory(categoryName: String, subCategory: String, isExpense: Boolean): List<Bill> {
        return getBillsForCategory(categoryName, isExpense).filter { bill ->
            secondLevelCategory(bill.categoryName) == subCategory
        }
    }
}

class StatsViewModelFactory(private val billDao: BillDao) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return StatsViewModel(billDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
