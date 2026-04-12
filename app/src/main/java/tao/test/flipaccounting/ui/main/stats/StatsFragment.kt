package tao.test.flipaccounting.ui.main.stats

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.main.YearMonthPickerDialog
import tao.test.flipaccounting.ui.main.home.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsFragment : Fragment() {

    /** 与首页共享的时间状态（Activity 作用域） */
    private val homeViewModel: HomeViewModel by activityViewModels()

    private val viewModel: StatsViewModel by viewModels {
        StatsViewModelFactory(AppDatabase.getDatabase(requireContext().applicationContext).billDao())
    }

    private lateinit var pieChart: PieChart
    private lateinit var categoryAdapter: CategoryStatsAdapter
    private var isCategoryExpense = true

    private lateinit var tvTotalExpense: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvBalance: TextView
    private lateinit var tvDailyAvg: TextView
    private lateinit var tvTotalTransfer: TextView
    private lateinit var tvTotalRepayment: TextView
    private lateinit var tvTotalRefund: TextView
    private lateinit var tvDateSelector: TextView

    private lateinit var rowTransfer: View
    private lateinit var rowRepayment: View
    private lateinit var rowRefund: View
    private lateinit var layoutOverviewExtra: View
    private lateinit var ivOverviewExpand: View

    private lateinit var emptyStateContainer: View
    private lateinit var statsContentContainer: View

    private var isOverviewExpanded = false

    private val chartColors = listOf(
        "#FF9800", "#FF5722", "#00C853", "#8BC34A", "#2196F3", "#03A9F4", "#9C27B0", "#E91E63"
    ).map { Color.parseColor(it) }

    private val dfDateLabel = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_stats, container, false)
        initViews(root)
        setupListeners(root)
        observeViewModel()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        syncDateFromHomeIfNeeded()
        // 初始化时立即按当前账本过滤，避免 ViewModel init 时用的是 null 账本
        viewModel.setBookFilter(BookAccountManager.getSelectedBook(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        syncDateFromHomeIfNeeded()
        val selectedBook = BookAccountManager.getSelectedBook(requireContext())
        val currentBook = viewModel.uiState.value.selectedBookName
        // 用 normalizeBookName 做比较，避免空字符串和 null 被判断为不同
        if (BookAccountManager.normalizeBookName(currentBook) !=
            BookAccountManager.normalizeBookName(selectedBook)) {
            viewModel.setBookFilter(selectedBook)
        }
    }

    /**
     * MainActivity 使用 hide/show 管理 Tab，切换 Tab 时不会触发 onResume，
     * 需要在 onHiddenChanged 中同步日期。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            // Fragment 从隐藏变为可见（即切换到统计 Tab）
            syncDateFromHomeIfNeeded()
            val selectedBook = BookAccountManager.getSelectedBook(requireContext())
            val currentBook = viewModel.uiState.value.selectedBookName
            if (BookAccountManager.normalizeBookName(currentBook) !=
                BookAccountManager.normalizeBookName(selectedBook)) {
                viewModel.setBookFilter(selectedBook)
            }
        }
    }

    /**
     * 将统计页日期对齐到首页账单页正在查看的年月：
     * - 月模式时：显示同年同月（如 2025-03）
     * - 年模式时：显示同年（如 2025）
     */
    private fun syncDateFromHomeIfNeeded() {
        val homeState = homeViewModel.uiState.value
        val targetYear = homeState.selectedYear
        val targetMonth = (homeState.selectedMonth - 1).coerceIn(0, 11)
        val statsState = viewModel.uiState.value

        if (statsState.year != targetYear || statsState.month != targetMonth) {
            viewModel.setYearMonth(targetYear, targetMonth)
        }
    }

    private fun syncHomeDateFromStatsIfNeeded(state: StatsUiState) {
        if (!isAdded || isHidden || !isVisible || !isResumed) return
        if (!state.isMonthMode) return
        if (state.forcedStartTime != null || state.forcedEndTime != null) return

        val targetYear = state.year
        val targetMonth = state.month + 1
        val homeState = homeViewModel.uiState.value
        if (homeState.selectedYear != targetYear || homeState.selectedMonth != targetMonth) {
            homeViewModel.setMonth(targetYear, targetMonth)
        }
    }

    private fun initViews(root: View) {
        pieChart = root.findViewById(R.id.pie_chart)

        tvTotalExpense = root.findViewById(R.id.tv_total_expense)
        tvTotalIncome = root.findViewById(R.id.tv_total_income)
        tvBalance = root.findViewById(R.id.tv_balance)
        tvDailyAvg = root.findViewById(R.id.tv_daily_avg)
        tvTotalTransfer = root.findViewById(R.id.tv_total_transfer)
        tvTotalRepayment = root.findViewById(R.id.tv_total_repayment)
        tvTotalRefund = root.findViewById(R.id.tv_total_refund)
        tvDateSelector = root.findViewById(R.id.tv_date_selector)

        rowTransfer = root.findViewById(R.id.row_total_transfer)
        rowRepayment = root.findViewById(R.id.row_total_repayment)
        rowRefund = root.findViewById(R.id.row_total_refund)
        layoutOverviewExtra = root.findViewById(R.id.layout_overview_extra)
        ivOverviewExpand = root.findViewById(R.id.iv_overview_expand)

        emptyStateContainer = root.findViewById(R.id.empty_state_container)
        statsContentContainer = root.findViewById(R.id.stats_content_container)

        val rvCategoryList = root.findViewById<RecyclerView>(R.id.rv_category_list)
        rvCategoryList.layoutManager = LinearLayoutManager(context)
        categoryAdapter = CategoryStatsAdapter(
            chartColors = chartColors,
            items = emptyList(),
            isExpense = isCategoryExpense,
            currencySymbol = "¥"
        ) { categoryName ->
            showSubCategoryDetails(categoryName)
        }
        rvCategoryList.adapter = categoryAdapter

        setupPieChart()
        updateOverviewExpandState()
    }

    private fun setupListeners(root: View) {
        root.findViewById<View>(R.id.btn_currency).setOnClickListener {
            showCurrencyFilterDialog()
        }

        root.findViewById<View>(R.id.btn_filter).setOnClickListener {
            showCustomFilterSheet()
        }

        root.findViewById<View>(R.id.btn_category_expense).setOnClickListener {
            isCategoryExpense = true
            updateButtonStyles(root, R.id.btn_category_expense, R.id.btn_category_income)
            updateUI(viewModel.uiState.value)
        }

        root.findViewById<View>(R.id.btn_category_income).setOnClickListener {
            isCategoryExpense = false
            updateButtonStyles(root, R.id.btn_category_income, R.id.btn_category_expense)
            updateUI(viewModel.uiState.value)
        }

        root.findViewById<View>(R.id.btn_mode_month).setOnClickListener {
            viewModel.setMode(true)
            updateButtonStyles(root, R.id.btn_mode_month, R.id.btn_mode_year)
        }

        root.findViewById<View>(R.id.btn_mode_year).setOnClickListener {
            viewModel.setMode(false)
            updateButtonStyles(root, R.id.btn_mode_year, R.id.btn_mode_month)
        }

        root.findViewById<View>(R.id.btn_prev_date).setOnClickListener {
            viewModel.prevDate()
        }

        root.findViewById<View>(R.id.btn_next_date).setOnClickListener {
            viewModel.nextDate()
        }

        root.findViewById<View>(R.id.layout_date_selector).setOnClickListener {
            showUnifiedMonthYearPicker()
        }

        ivOverviewExpand.setOnClickListener {
            isOverviewExpanded = !isOverviewExpanded
            updateOverviewExpandState()
        }

        rowTransfer.setOnClickListener {
            val bills = viewModel.getTransferBills()
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "当前时间段没有转账记录", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet("转账记录", bills).show(childFragmentManager, "transfer_bills")
            }
        }

        rowRepayment.setOnClickListener {
            val bills = viewModel.getRepaymentBills()
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "当前时间段没有信用卡还款记录", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet("信用卡还款记录", bills).show(childFragmentManager, "repayment_bills")
            }
        }

        rowRefund.setOnClickListener {
            val bills = viewModel.getRefundBills()
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "当前时间段没有退款记录", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet("退款记录", bills).show(childFragmentManager, "refund_bills")
            }
        }
    }

    private fun updateButtonStyles(root: View, selectedId: Int, unselectedId: Int) {
        root.findViewById<TextView>(selectedId).apply {
            setBackgroundResource(R.drawable.bg_segmented_selected)
            setTextColor(Color.parseColor("#222222"))
        }
        root.findViewById<TextView>(unselectedId).apply {
            background = null
            setTextColor(Color.parseColor("#666666"))
        }
    }

    private fun updateOverviewExpandState() {
        layoutOverviewExtra.visibility = if (isOverviewExpanded) View.VISIBLE else View.GONE
        if (ivOverviewExpand is android.widget.ImageView) {
            (ivOverviewExpand as android.widget.ImageView).animate().rotation(if (isOverviewExpanded) 180f else 0f).setDuration(180).start()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                    syncHomeDateFromStatsIfNeeded(state)
                }
            }
        }
    }

    private fun updateUI(state: StatsUiState) {
        val symbol = state.selectedCurrency?.let { CurrencyManager.getSymbol(it) } ?: "¥"

        tvDateSelector.text = if (state.dateLabel.isNotBlank()) {
            state.dateLabel
        } else if (state.isMonthMode) {
            String.format(Locale.getDefault(), "%04d-%02d", state.year, state.month + 1)
        } else {
            String.format(Locale.getDefault(), "%04d", state.year)
        }

        tvTotalExpense.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.totalExpense)
        tvTotalIncome.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.totalIncome)
        tvBalance.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.balance)
        tvDailyAvg.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.dailyAvg)
        tvTotalTransfer.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.totalTransfer)
        tvTotalRepayment.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.totalRepayment)
        tvTotalRefund.text = String.format(Locale.getDefault(), "%s%.2f", symbol, state.totalRefund)

        val hasData = state.bills.isNotEmpty()
        emptyStateContainer.visibility = if (hasData) View.GONE else View.VISIBLE
        statsContentContainer.visibility = if (hasData) View.VISIBLE else View.GONE

        updateCategoryChart(state)

        val list = if (isCategoryExpense) state.categoryStatsExpense else state.categoryStatsIncome
        categoryAdapter.submitList(list, isCategoryExpense, symbol)
    }

    private fun setupPieChart() {
        pieChart.description.isEnabled = false
        pieChart.legend.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setUsePercentValues(true)
        pieChart.setExtraOffsets(27f, 20f, 27f, 20f)
        pieChart.setNoDataText("暂无图表数据")
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
    }

    private fun updateCategoryChart(state: StatsUiState) {
        val targetStats = if (isCategoryExpense) state.categoryStatsExpense else state.categoryStatsIncome
        val total = targetStats.sumOf { it.amount }

        if (targetStats.isNotEmpty() && total > 0) {
            // 按原始顺序（大→小，与下方列表一致）建立「分类名→颜色」映射
            val colorByName = targetStats.mapIndexed { i, stat ->
                stat.categoryName to chartColors[i % chartColors.size]
            }.toMap()

            // 饼图数据从小到大排序，颜色按名字查映射，保证与列表一致
            val filteredStats = targetStats.filter { it.percentage >= 2f }.sortedBy { it.amount }
            val pieEntries = filteredStats.map { PieEntry(it.amount.toFloat(), it.categoryName) }
            val sliceColors = filteredStats.map { colorByName[it.categoryName] ?: chartColors[0] }

            val pieDataSet = PieDataSet(pieEntries, "").apply {
                colors = sliceColors
                xValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
                yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
                valueLinePart1OffsetPercentage = 100f
                valueLinePart1Length = 0.333f
                valueLinePart2Length = 0.8f
                setValueLineVariableLength(true)
                setUsingSliceColorAsValueLineColor(true)
                valueTextSize = 9f
                setValueTextColors(sliceColors)
                valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
                    override fun getFormattedValue(value: Float): String = ""
                    override fun getPieLabel(value: Float, pieEntry: PieEntry): String {
                        val pct = if (total > 0) (pieEntry.value / total * 100f) else 0f
                        return "${pieEntry.label} ${String.format(java.util.Locale.getDefault(), "%.1f%%", pct)}"
                    }
                }
            }

            pieChart.data = PieData(pieDataSet)
            pieChart.centerText = if (isCategoryExpense) "支出比例" else "收入比例"
            pieChart.setDrawEntryLabels(false)

            // 把颜色映射同步给列表 Adapter，使图标背景色/进度条颜色与饼图一致
            categoryAdapter.setColorMap(colorByName)
        } else {
            pieChart.clear()
        }

        pieChart.legend.isEnabled = false
        pieChart.invalidate()
    }

    private fun showMonthYearPicker() {
        val state = viewModel.uiState.value

        if (state.isMonthMode) {
            val pickerLayout = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40, 0, 20)
            }

            val npYear = NumberPicker(requireContext()).apply {
                minValue = 2000
                maxValue = 2100
                value = state.year
            }

            val npMonth = NumberPicker(requireContext()).apply {
                minValue = 1
                maxValue = 12
                value = state.month + 1
                setFormatter { String.format(Locale.getDefault(), "%02d", it) }
            }

            pickerLayout.addView(npYear)
            pickerLayout.addView(npMonth)

            AlertDialog.Builder(requireContext())
                .setTitle("选择月份")
                .setView(pickerLayout)
                .setPositiveButton("确定") { _, _ ->
                    viewModel.setYearMonth(npYear.value, npMonth.value - 1)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            val npYear = NumberPicker(requireContext()).apply {
                minValue = 2000
                maxValue = 2100
                value = state.year
            }

            AlertDialog.Builder(requireContext())
                .setTitle("选择年份")
                .setView(npYear)
                .setPositiveButton("确定") { _, _ ->
                    viewModel.setYearMonth(npYear.value, state.month)
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun showUnifiedMonthYearPicker() {
        val state = viewModel.uiState.value
        if (state.isMonthMode) {
            YearMonthPickerDialog.show(
                context = requireContext(),
                title = "\u9009\u62E9\u6708\u4EFD",
                initialYear = state.year,
                initialMonth = state.month + 1
            ) { year, month ->
                viewModel.setYearMonth(year, month - 1)
            }
        } else {
            YearMonthPickerDialog.show(
                context = requireContext(),
                title = "\u9009\u62E9\u5E74\u4EFD",
                initialYear = state.year,
                initialMonth = state.month + 1,
                yearOnly = true
            ) { year, _ ->
                viewModel.setYearMonth(year, state.month)
            }
        }
    }

    private fun showCurrencyFilterDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(requireContext())
            val currencies = db.assetDao().getAllAssetsList()
                .map { it.currency }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()

            withContext(Dispatchers.Main) {
                val options = mutableListOf("全部")
                options.addAll(currencies)

                val current = viewModel.uiState.value.selectedCurrency
                val checkedIndex = if (current == null) 0 else options.indexOf(current).takeIf { it >= 0 } ?: 0

                AlertDialog.Builder(requireContext())
                    .setTitle("选择币种")
                    .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                        val selected = if (which == 0) null else options[which]
                        viewModel.setCurrencyFilter(selected)
                        dialog.dismiss()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showCustomFilterSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_stats_filter_sheet, null)

        val chipThisMonth = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_filter_this_month)
        val chipLastMonth = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_filter_last_month)
        val chipThisYear = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_filter_this_year)
        val chipLastYear = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_filter_last_year)
        val chipAll = view.findViewById<com.google.android.material.chip.Chip>(R.id.chip_filter_all)

        val cardStart = view.findViewById<View>(R.id.tv_filter_start_date)
        val cardEnd = view.findViewById<View>(R.id.tv_filter_end_date)
        val tvStart = view.findViewById<TextView>(R.id.tv_filter_start_date_text)
        val tvEnd = view.findViewById<TextView>(R.id.tv_filter_end_date_text)
        val tvBook = view.findViewById<TextView>(R.id.tv_filter_book_selector)

        val btnClose = view.findViewById<View>(R.id.btn_close_filter_sheet)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_filter_sheet)
        val btnReset = view.findViewById<View>(R.id.btn_reset_filter_sheet)

        val state = viewModel.uiState.value
        var customStart: Long? = null
        var customEnd: Long? = null
        var selectedBook: String? = state.selectedBookName
        var availableBooks: List<String> = emptyList()

        fun clearQuickChips() {
            chipThisMonth.isChecked = false
            chipLastMonth.isChecked = false
            chipThisYear.isChecked = false
            chipLastYear.isChecked = false
            chipAll.isChecked = false
        }

        fun resetDateLabels() {
            tvStart.text = customStart?.let { dfDateLabel.format(Date(it)) } ?: "\u9009\u62e9\u65e5\u671f"
            tvEnd.text = customEnd?.let { dfDateLabel.format(Date(it)) } ?: "\u9009\u62e9\u65e5\u671f"
        }

        tvBook.text = selectedBook ?: "\u5168\u90e8\u8d26\u672c"
        resetDateLabels()

        when (state.forcedLabel) {
            "\u672c\u6708" -> chipThisMonth.isChecked = true
            "\u4e0a\u6708" -> chipLastMonth.isChecked = true
            "\u4eca\u5e74" -> chipThisYear.isChecked = true
            "\u53bb\u5e74" -> chipLastYear.isChecked = true
            "\u5168\u90e8" -> chipAll.isChecked = true
        }

        if (state.forcedStartTime != null && state.forcedEndTime != null && state.forcedEndTime != Long.MAX_VALUE) {
            customStart = state.forcedStartTime
            customEnd = state.forcedEndTime
            clearQuickChips()
            resetDateLabels()
        }

        cardStart.setOnClickListener {
            showDatePicker { ts ->
                customStart = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        cardEnd.setOnClickListener {
            showDatePicker { ts ->
                customEnd = ts
                resetDateLabels()
                clearQuickChips()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val books = AppDatabase.getDatabase(context).billDao().getAllBookNames()
            val mergedBooks = BookAccountManager.getBookAccounts(context, books)
            withContext(Dispatchers.Main) {
                availableBooks = mergedBooks
                if (selectedBook != null && !availableBooks.contains(selectedBook)) {
                    selectedBook = null
                    tvBook.text = "\u5168\u90e8\u8d26\u672c"
                }
            }
        }

        tvBook.setOnClickListener {
            val options = mutableListOf("\u5168\u90e8\u8d26\u672c")
            options.addAll(availableBooks)
            val checked = if (selectedBook == null) 0 else options.indexOf(selectedBook).takeIf { it >= 0 } ?: 0

            AlertDialog.Builder(requireContext())
                .setTitle("\u9009\u62e9\u8d26\u672c")
                .setSingleChoiceItems(options.toTypedArray(), checked) { d, which ->
                    selectedBook = if (which == 0) null else options[which]
                    tvBook.text = selectedBook ?: "\u5168\u90e8\u8d26\u672c"
                    d.dismiss()
                }
                .setNegativeButton("\u53d6\u6d88", null)
                .show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnReset.setOnClickListener {
            clearQuickChips()
            customStart = null
            customEnd = null
            selectedBook = null
            resetDateLabels()
            tvBook.text = "\u5168\u90e8\u8d26\u672c"
        }

        btnConfirm.setOnClickListener {
            when {
                chipThisMonth.isChecked -> viewModel.applyThisMonthFilter()
                chipLastMonth.isChecked -> viewModel.applyLastMonthFilter()
                chipThisYear.isChecked -> viewModel.applyThisYearFilter()
                chipLastYear.isChecked -> viewModel.applyLastYearFilter()
                chipAll.isChecked -> viewModel.applyAllTimeFilter()
                customStart != null && customEnd != null -> viewModel.applyCustomDateFilter(customStart!!, customEnd!!)
                customStart != null || customEnd != null -> {
                    Toast.makeText(requireContext(), "\u8bf7\u5148\u9009\u62e9\u5b8c\u6574\u7684\u5f00\u59cb\u548c\u7ed3\u675f\u65e5\u671f", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
            }

            viewModel.setBookFilter(selectedBook)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * 0.82f).toInt()
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                isFitToContents = true
                this.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun showDatePicker(onDateSelected: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onDateSelected(selected.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showSubCategoryDetails(categoryName: String) {
        val hasSubCategories = viewModel.hasSubCategories(categoryName, isCategoryExpense)
        if (!hasSubCategories) {
            val bills = viewModel.getBillsForCategory(categoryName, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "暂无该分类账单", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet(categoryName, bills).show(childFragmentManager, "bills")
            }
            return
        }

        val subStats = viewModel.getSubCategoryStats(categoryName, isCategoryExpense)
        if (subStats.isEmpty()) {
            val bills = viewModel.getBillsForCategory(categoryName, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "暂无该分类账单", Toast.LENGTH_SHORT).show()
            } else {
                BillListBottomSheet(categoryName, bills).show(childFragmentManager, "bills")
            }
            return
        }
        val total = subStats.values.sum()

        val sheet = SubCategoryBottomSheet(
            title = categoryName,
            isExpense = isCategoryExpense,
            subStats = subStats,
            totalAmount = total,
            colors = chartColors
        ) { subCategory ->
            val bills = viewModel.getBillsForSubCategory(categoryName, subCategory, isCategoryExpense)
            if (bills.isEmpty()) {
                Toast.makeText(requireContext(), "\u6682\u65E0\u8BE5\u5206\u7C7B\u8D26\u5355", Toast.LENGTH_SHORT).show()
            } else {
                val billSheet = BillListBottomSheet(subCategory, bills)
                billSheet.show(childFragmentManager, "bills")
            }
        }
        sheet.show(childFragmentManager, "sub_categories")
    }
}

