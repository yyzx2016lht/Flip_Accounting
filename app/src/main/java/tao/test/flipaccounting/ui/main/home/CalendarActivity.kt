package tao.test.flipaccounting.ui.main.home

import androidx.appcompat.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.icu.util.ChineseCalendar
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.activity.EditBillActivity
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import tao.test.flipaccounting.ui.main.SharedYearMonthSession
import tao.test.flipaccounting.ui.main.YearMonthPickerDialog
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class CalendarActivity : AppCompatActivity() {

    private lateinit var tvMonthSelector: TextView
    private lateinit var rvCalendar: RecyclerView
    private lateinit var rvDailyBills: RecyclerView
    private lateinit var tvMonthSummary: TextView
    private lateinit var tvDailyDate: TextView
    private lateinit var tvDailySummary: TextView
    private lateinit var tvDailyEmpty: TextView
    private lateinit var btnMore: ImageView
    private lateinit var swipeRefreshCalendar: SwipeRefreshLayout

    private lateinit var tvW1: TextView
    private lateinit var tvW2: TextView
    private lateinit var tvW3: TextView
    private lateinit var tvW4: TextView
    private lateinit var tvW5: TextView
    private lateinit var tvW6: TextView
    private lateinit var tvW7: TextView

    private lateinit var btnModeBoth: TextView
    private lateinit var btnModeBalance: TextView
    private lateinit var btnModeIncome: TextView
    private lateinit var btnModeExpense: TextView
    private lateinit var modesContainer: LinearLayout

    // Multi-select UI elements
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: TextView
    private lateinit var btnMsSelectAll: TextView
    private lateinit var btnMsDelete: TextView
    private var isMultiSelectModeActive = false

    private var selectedYear: Int = 2025
    private var selectedMonth: Int = 12
    private var selectedDay: Int = 1
    private var selectedBookName: String = BookAccountManager.DEFAULT_BOOK

    private val dfChartKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private var firstDayOfWeekOption = Calendar.MONDAY
    private var currentMode = MODE_BOTH // 0=Both, 1=Balance, 2=Income, 3=Expense

    companion object {
        const val MODE_BOTH = 0
        const val MODE_BALANCE = 1
        const val MODE_INCOME = 2
        const val MODE_EXPENSE = 3
    }

    private var currentMonthBills = listOf<Bill>()
    private val dailySummaryMap = mutableMapOf<String, DailySummary>()
    private val incomeLevelMap = mutableMapOf<String, Int>()
    private val expenseLevelMap = mutableMapOf<String, Int>()
    private val balanceLevelMap = mutableMapOf<String, Int>()

    private val solarFestivals = mapOf(
        Pair(1, 1) to "元旦",
        Pair(2, 14) to "情人节",
        Pair(3, 8) to "妇女节",
        Pair(3, 12) to "植树节",
        Pair(5, 1) to "劳动节",
        Pair(6, 1) to "儿童节",
        Pair(10, 1) to "国庆",
        Pair(12, 25) to "圣诞节"
    )

    private val lunarFestivals = mapOf(
        Pair(1, 1) to "春节",
        Pair(1, 15) to "元宵",
        Pair(5, 5) to "端午",
        Pair(7, 7) to "七夕",
        Pair(8, 15) to "中秋",
        Pair(9, 9) to "重阳",
        Pair(12, 8) to "腊八",
        Pair(12, 23) to "小年"
    )

    data class DailySummary(var income: Double = 0.0, var expense: Double = 0.0)

    private lateinit var calendarAdapter: CalendarAdapter
    private lateinit var dailyAdapter: HomeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calendar)

        val prefs = getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
        firstDayOfWeekOption = prefs.getInt("first_day_of_week", Calendar.MONDAY)

        val sessionYearMonth = SharedYearMonthSession.getYearMonth()
        selectedYear = intent.getIntExtra("YEAR", sessionYearMonth.first)
        selectedMonth = intent.getIntExtra("MONTH", sessionYearMonth.second)
        selectedDay = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        selectedBookName = BookAccountManager.normalizeBookName(
            intent.getStringExtra("BOOK_NAME").orEmpty().ifBlank {
                BookAccountManager.getSelectedBook(this)
            }
        )

        initViews()
        updateWeekdaysHeader()
        setupCalendar()
        setupDailyList()
        setupListeners()
        setupMultiSelectActions()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isMultiSelectModeActive) {
                    dailyAdapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressed()
                }
            }
        })

        updateMonthText()
        loadDataForMonth()
    }

    override fun onResume() {
        super.onResume()
        val latestBook = BookAccountManager.normalizeBookName(BookAccountManager.getSelectedBook(this))
        if (latestBook != selectedBookName) {
            selectedBookName = latestBook
            loadDataForMonth()
        }
    }

    private fun initViews() {
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        tvMonthSelector = findViewById(R.id.tvMonthSelector)
        btnMore = findViewById(R.id.btnMore)
        rvCalendar = findViewById(R.id.rvCalendar)
        rvDailyBills = findViewById(R.id.rvDailyBills)
        swipeRefreshCalendar = findViewById(R.id.swipeRefreshCalendar)
        tvMonthSummary = findViewById(R.id.tvMonthSummary)
        tvDailyDate = findViewById(R.id.tvDailyDate)
        tvDailySummary = findViewById(R.id.tvDailySummary)
        tvDailyEmpty = findViewById(R.id.tvDailyEmpty)

        tvW1 = findViewById(R.id.tvW1)
        tvW2 = findViewById(R.id.tvW2)
        tvW3 = findViewById(R.id.tvW3)
        tvW4 = findViewById(R.id.tvW4)
        tvW5 = findViewById(R.id.tvW5)
        tvW6 = findViewById(R.id.tvW6)
        tvW7 = findViewById(R.id.tvW7)

        btnModeBoth = findViewById(R.id.btnModeBoth)
        btnModeBalance = findViewById(R.id.btnModeBalance)
        btnModeIncome = findViewById(R.id.btnModeIncome)
        btnModeExpense = findViewById(R.id.btnModeExpense)
        modesContainer = findViewById(R.id.modesContainer)

        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = findViewById(R.id.btn_ms_select_all)
        btnMsDelete = findViewById(R.id.btn_ms_delete)
    }

    private fun updateWeekdaysHeader() {
        val weekdays = when(firstDayOfWeekOption) {
            Calendar.SUNDAY -> listOf("日", "一", "二", "三", "四", "五", "六")
            Calendar.SATURDAY -> listOf("六", "日", "一", "二", "三", "四", "五")
            else -> listOf("一", "二", "三", "四", "五", "六", "日")
        }
        tvW1.text = weekdays[0]
        tvW2.text = weekdays[1]
        tvW3.text = weekdays[2]
        tvW4.text = weekdays[3]
        tvW5.text = weekdays[4]
        tvW6.text = weekdays[5]
        tvW7.text = weekdays[6]
    }

    private fun updateMonthText() {
        tvMonthSelector.text = "${selectedYear}.${String.format(Locale.getDefault(), "%02d", selectedMonth)}"
    }

    private fun setupListeners() {
        tvMonthSelector.setOnClickListener { showUnifiedMonthYearPicker() }

        btnMore.setOnClickListener { showFirstDayOfWeekDialog() }

        swipeRefreshCalendar.setOnRefreshListener {
            loadDataForMonth()
        }

        btnModeBoth.setOnClickListener { setDisplayMode(MODE_BOTH) }
        btnModeBalance.setOnClickListener { setDisplayMode(MODE_BALANCE) }
        btnModeIncome.setOnClickListener { setDisplayMode(MODE_INCOME) }
        btnModeExpense.setOnClickListener { setDisplayMode(MODE_EXPENSE) }

        setupMonthSwipeGesture()
    }

    private fun setupMonthSwipeGesture() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > 120f && kotlin.math.abs(velocityX) > 200f) {
                    if (dx < 0) moveMonth(1) else moveMonth(-1)
                    return true
                }
                return false
            }
        })

        val touchListener = View.OnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
        rvCalendar.setOnTouchListener(touchListener)
        findViewById<View>(R.id.layoutWeekdays).setOnTouchListener(touchListener)
    }

    private fun moveMonth(delta: Int) {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, delta)
        }
        selectedYear = cal.get(Calendar.YEAR)
        selectedMonth = cal.get(Calendar.MONTH) + 1
        selectedDay = 1
        SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
        updateMonthText()
        loadDataForMonth()
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener {
            dailyAdapter.clearSelection()
        }
        btnMsSelectAll.setOnClickListener {
            val allItems = dailyAdapter.items.mapNotNull { if (it is HomeAdapter.ListItem.Item) it.displayBill.bill else null }.toSet()
            if (dailyAdapter.selectedBills.size == allItems.size) {
                dailyAdapter.clearSelection()
            } else {
                dailyAdapter.selectedBills.addAll(allItems)
                dailyAdapter.notifyDataSetChanged()
                dailyAdapter.onSelectionChanged?.invoke(dailyAdapter.selectedBills.size)
            }
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = dailyAdapter.selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            val db = AppDatabase.getDatabase(this)
            lifecycleScope.launch {
                tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)

                dailyAdapter.clearSelection()
                Toast.makeText(this@CalendarActivity, "已删除 ${billsToDelete.size} 条账单", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showFirstDayOfWeekDialog() {
        val options = arrayOf("星期一", "星期六", "星期日")
        val values = arrayOf(Calendar.MONDAY, Calendar.SATURDAY, Calendar.SUNDAY)
        val checkedItem = values.indexOf(firstDayOfWeekOption).takeIf { it >= 0 } ?: 0

        val dialog = AlertDialog.Builder(this)
            .setTitle("每周第一天")
            .setSingleChoiceItems(options, checkedItem) { d, which ->
                firstDayOfWeekOption = values[which]
                getSharedPreferences("flip_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt("first_day_of_week", firstDayOfWeekOption)
                    .apply()
                updateWeekdaysHeader()
                calendarAdapter.buildCalendar()
                d.dismiss()
            }
            .create()
        OverlayDialogs.showStyledCenterDialog(dialog, this, widthRatio = 0.86f)
    }
    private fun setDisplayMode(mode: Int) {
        currentMode = mode

        for (i in 0 until modesContainer.childCount) {
            val child = modesContainer.getChildAt(i) as TextView
            child.setBackgroundResource(0)
            child.setTextColor(Color.parseColor("#666666"))
        }

        val selectedBtn = when(mode) {
            MODE_BOTH -> btnModeBoth
            MODE_BALANCE -> btnModeBalance
            MODE_INCOME -> btnModeIncome
            else -> btnModeExpense
        }
        selectedBtn.setBackgroundResource(R.drawable.bg_segmented_selected)
        selectedBtn.setTextColor(Color.parseColor("#333333"))

        calendarAdapter.notifyDataSetChanged()
    }

    private fun showMonthYearPicker() {
        val pickerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 50, 0, 50)
        }

        val npYear = NumberPicker(this).apply {
            minValue = 2000
            maxValue = 2050
            value = selectedYear
        }

        val npMonth = NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            value = selectedMonth
            setFormatter { i -> String.format(Locale.getDefault(), "%02d", i) }
        }

        pickerLayout.addView(npYear)
        pickerLayout.addView(npMonth)

        val dialog = AlertDialog.Builder(this)
            .setTitle("选择月份")
            .setView(pickerLayout)
            .setPositiveButton("确定") { _, _ ->
                selectedYear = npYear.value
                selectedMonth = npMonth.value
                selectedDay = 1
                SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
                updateMonthText()
                loadDataForMonth()
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showStyledCenterDialog(dialog, this, widthRatio = 0.86f)
    }
    private fun setupCalendar() {
        rvCalendar.layoutManager = GridLayoutManager(this, 7)
        calendarAdapter = CalendarAdapter()
        rvCalendar.adapter = calendarAdapter
    }

    private fun showUnifiedMonthYearPicker() {
        YearMonthPickerDialog.show(
            context = this,
            title = "选择月份",
            initialYear = selectedYear,
            initialMonth = selectedMonth
        ) { year, month ->
            selectedYear = year
            selectedMonth = month
            selectedDay = 1
            SharedYearMonthSession.setYearMonth(selectedYear, selectedMonth)
            updateMonthText()
            loadDataForMonth()
        }
    }

    private fun setupDailyList() {
        rvDailyBills.layoutManager = LinearLayoutManager(this)
        dailyAdapter = HomeAdapter()
        dailyAdapter.onBillItemClick = { bill ->
            showBillDetailSheet(bill)
        }
        dailyAdapter.onSelectionChanged = { count ->
            if (dailyAdapter.isMultiSelectMode) {
                isMultiSelectModeActive = true
                layoutMultiSelectActions.visibility = View.VISIBLE
                btnMsDelete.text = if (count > 0) "删除($count)" else "删除"
            } else {
                isMultiSelectModeActive = false
                layoutMultiSelectActions.visibility = View.GONE
            }
        }
        rvDailyBills.adapter = dailyAdapter
    }

    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    private fun stripRefundPrefix(categoryName: String): String {
        return BillDisplayFormatter.stripRefundPrefix(categoryName)
    }

    private fun originalAmountOfExpenseBill(bill: Bill): Double {
        val base = if (bill.originalAmount > 0.0) bill.originalAmount else bill.amount
        return max(base, bill.amount)
    }

    private fun refundAmountOfExpenseBill(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || isRefundBill(bill)) return 0.0
        return (originalAmountOfExpenseBill(bill) - bill.amount).coerceAtLeast(0.0)
    }

    private fun formatMoney(amount: Double, currency: String = "CNY"): String {
        val symbol = CurrencyManager.getSymbol(currency)
        return "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
    }

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(if (forceGrayStyle) R.drawable.bg_bill_item_refund else R.drawable.bg_bill_item)
        iconContainer?.setBackgroundResource(if (forceGrayStyle) R.drawable.bg_circle_refund else R.drawable.bg_circle_soft)

        tvCategory.text = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> bill.categoryName.ifEmpty { "未分类" }
        }

        val refundAmount = refundAmountOfExpenseBill(bill)
        tvAmount.text = if (!forceGrayStyle && !isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val sign = when {
                forceGrayStyle || isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        }

        if (forceGrayStyle || isRefund) {
            tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
            tvCategory.setTextColor(Color.parseColor("#8E98A3"))
            tvDetail.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#333333"))
            tvDetail.setTextColor(Color.parseColor("#999999"))
            tvTime.setTextColor(Color.parseColor("#999999"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#FF5252"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#757575"))
            }
        }

        val detailStr = buildString {
            if (isTransfer) {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            } else {
                if (bill.accountName.isNotEmpty()) append(bill.accountName)
                if (!forceGrayStyle) {
                    val refundAmount = refundAmountOfExpenseBill(bill)
                    if (refundAmount > 0.0 && bill.type == Bill.TYPE_EXPENSE) {
                        append("(退款")
                        append(symbol)
                        append(String.format(Locale.getDefault(), "%.2f", refundAmount))
                        append(")")
                    }
                }
            }
            if (bill.remark.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append(bill.remark)
            }
        }

        if (forceGrayStyle) {
            tvDetail.text = dfDetailTimeShort.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            if (bill.accountName.isNotEmpty()) {
                tvTime.text = bill.accountName
                tvTime.visibility = View.VISIBLE
            } else {
                tvTime.visibility = View.GONE
            }
        } else {
            if (detailStr.isNotEmpty()) {
                tvDetail.text = detailStr
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }
            tvTime.text = dfDetailTimeShort.format(Date(bill.time))
            tvTime.visibility = View.VISIBLE
        }

        val iconLookupName = if (isRefund) baseCategory else bill.categoryName
        val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
        lifecycleScope.launch {
            val iconUrl = CategoryIconHelper.findCategoryIcon(this@CalendarActivity, iconLookupName, iconLookupType)
            if (iconUrl.isNotEmpty()) {
                Glide.with(row).load(iconUrl).into(ivIcon)
            } else {
                ivIcon.setImageResource(R.mipmap.ic_launcher)
            }
        }
    }

    private fun addLinkedBillRow(container: LinearLayout, bill: Bill, forceGrayStyle: Boolean, onClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(row, bill, forceGrayStyle)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { onClick?.invoke() }
        container.addView(row)
    }

    private fun renderRefundRecords(view: View, sourceBill: Bill, onItemClick: (Bill) -> Unit) {
        val section = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        section.visibility = View.GONE
        container.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            val refunds = AppDatabase.getDatabase(this@CalendarActivity).billDao().getRefundBillsBySourceId(sourceBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    section.visibility = View.GONE
                    return@withContext
                }
                section.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addLinkedBillRow(container, refundBill, forceGrayStyle = true) {
                        onItemClick(refundBill)
                    }
                }
            }
        }
    }

    private fun renderOriginalBill(view: View, originalBill: Bill) {
        val section = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        container.removeAllViews()
        section.visibility = View.VISIBLE
        addLinkedBillRow(container, originalBill, forceGrayStyle = false) {
            showBillDetailSheet(originalBill)
        }
    }

    private fun showBillDetailSheet(bill: Bill) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val layoutCategory = view.findViewById<View>(R.id.layout_detail_category)
        val lineCategory = view.findViewById<View>(R.id.line_category)
        val tvCategory = view.findViewById<TextView>(R.id.tv_detail_category)
        val tvAccountLabel = view.findViewById<TextView>(R.id.tv_detail_account_label)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val tvTimeLabel = view.findViewById<TextView>(R.id.tv_detail_time_label)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)

        val btnCopy = view.findViewById<View>(R.id.btn_copy)
        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnDelete = view.findViewById<View>(R.id.btn_delete)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        var linkedOriginalForRefund: Bill? = null

        tvAmountFormula.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) "还款详情" else "转账详情"
            tvAmountLabel.text = if (isRepayment) "还款金额" else "转账金额"
            tvAmount.text = formatMoney(bill.amount, bill.currency)
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = "账户"
            tvTimeLabel.text = "时间"
            tvAccount.text = bill.accountName + if (bill.toAccountName.isNotEmpty()) " -> ${bill.toAccountName}" else ""
            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = "详情"
            tvAmountLabel.text = "金额"
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = bill.categoryName

            if (isRefund) {
                tvAmount.text = formatMoney(bill.amount, bill.currency)
                tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                tvAccountLabel.text = "入账账户"
                tvTimeLabel.text = "入账时间"
                tvAccount.text = bill.accountName

                lifecycleScope.launch(Dispatchers.IO) {
                    val original = bill.relatedBillId?.let { AppDatabase.getDatabase(this@CalendarActivity).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccountLabel.text = "账户"
                tvTimeLabel.text = "时间"
                tvAccount.text = bill.accountName

                if (bill.type == Bill.TYPE_EXPENSE) {
                    val originalAmount = originalAmountOfExpenseBill(bill)
                    val refundedAmount = refundAmountOfExpenseBill(bill)
                    if (refundedAmount > 0.0) {
                        tvAmount.text = BillDisplayFormatter.buildRefundedExpenseAmountText(
                            netAmount = bill.amount,
                            originalAmount = originalAmount,
                            currency = bill.currency
                        )
                        tvAmountFormula.visibility = View.VISIBLE
                        tvAmountFormula.text = "退款${formatMoney(refundedAmount, bill.currency)}，实际支出${formatMoney(bill.amount, bill.currency)}"
                        renderRefundRecords(view, bill) { refundBill -> showBillDetailSheet(refundBill) }
                    } else {
                        tvAmount.text = "-${formatMoney(bill.amount, bill.currency)}"
                    }
                    tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                } else {
                    tvAmount.text = "+${formatMoney(bill.amount, bill.currency)}"
                    tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                }
            }
        }

        val timeStr = dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_time).text = timeStr

        val recordTimeStr = dfDetailTime.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text = "记录于 $recordTimeStr"
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(this@CalendarActivity) }
        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = bill.remark.ifEmpty { "无备注" }
        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmountOfExpenseBill(bill) > 0.0) {
            lifecycleScope.launch(Dispatchers.IO) {
                val refunds = AppDatabase.getDatabase(this@CalendarActivity).billDao().getRefundBillsBySourceId(bill.id)
                withContext(Dispatchers.Main) {
                    tvRemark.text = BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refunds)
                }
            }
        }

        if (isRefund) {
            btnCopy.visibility = View.GONE
            btnRefund.visibility = View.GONE
        } else if (bill.type == Bill.TYPE_INCOME || bill.type == Bill.TYPE_TRANSFER || bill.amount <= 0.0) {
            btnRefund.visibility = View.GONE
        } else {
            btnRefund.visibility = View.VISIBLE
        }

        btnCopy.setOnClickListener {
            bottomSheet.dismiss()
            val intent = Intent(this, EditBillActivity::class.java)
            intent.putExtra("BILL_ID", bill.id)
            intent.putExtra("IS_COPY", true)
            startActivity(intent)
        }

        btnRefund.setOnClickListener {
            bottomSheet.dismiss()
            showRefundSheet(bill)
        }

        btnEdit.setOnClickListener {
            bottomSheet.dismiss()
            if (isRefund) {
                val cachedOriginal = linkedOriginalForRefund
                if (cachedOriginal != null) {
                    showRefundSheet(cachedOriginal, bill)
                    return@setOnClickListener
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val source = bill.relatedBillId?.let { AppDatabase.getDatabase(this@CalendarActivity).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(this@CalendarActivity, EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(this, EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                startActivity(intent)
            }
        }

        btnDelete.setOnClickListener {
            bottomSheet.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@CalendarActivity)
                tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CalendarActivity, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureDetailBottomSheet(bottomSheet)
        bottomSheet.show()
    }

    private fun configureDetailBottomSheet(bottomSheet: BottomSheetDialog) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + resources.displayMetrics.density.times(24).toInt(),
                    (screenHeight * 0.88f).toInt()
                )
                bottomSheetView.layoutParams = bottomSheetView.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheetView.requestLayout()
                behavior.peekHeight = desiredHeight
            }
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_refund_bottom_sheet, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvOrigAmount = view.findViewById<TextView>(R.id.tv_orig_amount)
        val tvOrigCategory = view.findViewById<TextView>(R.id.tv_orig_category)

        val etRefundAmount = view.findViewById<EditText>(R.id.et_refund_amount)
        val layoutRefundAccount = view.findViewById<View>(R.id.layout_refund_account)
        val tvRefundAccount = view.findViewById<TextView>(R.id.tv_refund_account)
        val layoutRefundTime = view.findViewById<View>(R.id.layout_refund_time)
        val tvRefundTime = view.findViewById<TextView>(R.id.tv_refund_time)
        val etRefundRemark = view.findViewById<EditText>(R.id.et_refund_remark)
        val btnSaveRefund = view.findViewById<View>(R.id.btn_save_refund)
        val btnBack = view.findViewById<View>(R.id.btn_back)

        tvTitle.text = if (editingRefund == null) "退款" else "编辑退款"

        val sourceOriginalAmount = originalAmountOfExpenseBill(originalBill)
        tvOrigAmount.text = formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = stripRefundPrefix(originalBill.categoryName)

        val defaultRefundAmount = editingRefund?.amount ?: originalBill.amount
        etRefundAmount.setText(String.format(Locale.getDefault(), "%.2f", defaultRefundAmount))

        var selectedAccount = editingRefund?.accountName ?: originalBill.accountName
        tvRefundAccount.text = selectedAccount

        var selectedTimeStr = if (editingRefund == null) {
            dfDetailTime.format(Date())
        } else {
            dfDetailTime.format(Date(editingRefund.time))
        }
        tvRefundTime.text = selectedTimeStr

        if (editingRefund != null) {
            etRefundRemark.setText(editingRefund.remark)
        }

        btnBack?.setOnClickListener {
            bottomSheet.cancel()
        }

        bottomSheet.setOnCancelListener {
            showBillDetailSheet(editingRefund ?: originalBill)
        }

        layoutRefundAccount.setOnClickListener {
            OverlayDialogs.showGridAssetPicker(this, tvRefundAccount.text.toString(), "选择退款入账账户") { account ->
                selectedAccount = account
                tvRefundAccount.text = account
            }
        }

        layoutRefundTime.setOnClickListener {
            val initialTimeMillis = try {
                dfDetailTime.parse(selectedTimeStr)?.time
            } catch (_: Exception) {
                null
            }
            OverlayDialogs.showCustomTimePicker(this, initialTimeMillis = initialTimeMillis) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val amountStr = etRefundAmount.text.toString()
            val refundAmount = amountStr.toDoubleOrNull() ?: 0.0

            if (refundAmount <= 0) {
                Toast.makeText(this, "请输入有效的退款金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "选择账户") {
                Toast.makeText(this, "请选择入账账户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "退款：${stripRefundPrefix(originalBill.categoryName)}"
            }

            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@CalendarActivity)
                val account = db.assetDao().getAssetByName(selectedAccount)
                val refundBill = Bill(
                    id = editingRefund?.id ?: 0,
                    amount = refundAmount,
                    originalAmount = refundAmount,
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_REFUND,
                    accountId = account?.id ?: editingRefund?.accountId,
                    accountName = selectedAccount,
                    categoryName = originalBill.categoryName,
                    time = refundTimeLong,
                    remark = finalRemark,
                    currency = originalBill.currency
                )

                try {
                    tao.test.flipaccounting.logic.BillMutationService.saveRefundBill(
                        db = db,
                        originalBill = originalBill,
                        refundBill = refundBill,
                        previousRefundBill = editingRefund
                    )
                } catch (_: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CalendarActivity, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@CalendarActivity, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@CalendarActivity, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }

    private fun loadDataForMonth() {
        val db = AppDatabase.getDatabase(this)

        val calStart = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val calEnd = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }

        lifecycleScope.launch {
            db.billDao().getBillsBetweenTimes(calStart.timeInMillis, calEnd.timeInMillis).collectLatest { bills ->
                currentMonthBills = bills
                    .filter { BookAccountManager.isBillInBook(it.bookName, selectedBookName) }
                    .sortedByDescending { it.time }
                processMonthData()
                updateSelectedDayDetails()
                swipeRefreshCalendar.isRefreshing = false
            }
        }
    }

    private fun processMonthData() {
        dailySummaryMap.clear()
        incomeLevelMap.clear()
        expenseLevelMap.clear()
        balanceLevelMap.clear()
        var totalIncome = 0.0
        var totalExpense = 0.0

        for (bill in currentMonthBills) {
            val dateKey = dfChartKey.format(Date(bill.time))
            val summary = dailySummaryMap.getOrPut(dateKey) { DailySummary() }
            val convertedAmount = if (bill.currency.equals("CNY", ignoreCase = true)) {
                bill.amount
            } else {
                bill.amount * bill.exchangeRate
            }
            if (bill.subType == Bill.SUBTYPE_REFUND) {
                summary.income += convertedAmount
                totalIncome += convertedAmount
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                summary.expense += convertedAmount
                totalExpense += convertedAmount
            } else if (bill.type == Bill.TYPE_INCOME) {
                summary.income += convertedAmount
                totalIncome += convertedAmount
            }
        }

        tvMonthSummary.text = "月收入￥${String.format(Locale.getDefault(), "%,.2f", totalIncome)}, 月支出￥${String.format(Locale.getDefault(), "%,.2f", totalExpense)}, 月结余￥${String.format(Locale.getDefault(), "%,.2f", totalIncome - totalExpense)}"
        buildLevelMapForMode(MODE_INCOME, incomeLevelMap)
        buildLevelMapForMode(MODE_EXPENSE, expenseLevelMap)
        buildLevelMapForMode(MODE_BALANCE, balanceLevelMap)

        calendarAdapter.buildCalendar()
    }

    private fun buildLevelMapForMode(mode: Int, target: MutableMap<String, Int>) {
        val values = mutableListOf<Pair<String, Double>>()
        for ((date, summary) in dailySummaryMap) {
            val value = when (mode) {
                MODE_INCOME -> summary.income
                MODE_EXPENSE -> summary.expense
                else -> kotlin.math.abs(summary.income - summary.expense)
            }
            if (value > 0.0) {
                values.add(date to value)
            }
        }

        values.sortByDescending { it.second }
        if (values.isEmpty()) return

        val n = values.size
        values.forEachIndexed { index, (date, _) ->
            val level = if (n == 1) 31 else (31 - (index * 30 / (n - 1)))
            target[date] = level.coerceIn(1, 31)
        }
    }

    private fun updateSelectedDayDetails() {
        val selectedDateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", selectedYear, selectedMonth, selectedDay)
        val dailyBills = currentMonthBills.filter { dfChartKey.format(Date(it.time)) == selectedDateStr }

        val calNow = Calendar.getInstance()
        val todayStr = dfChartKey.format(calNow.time)
        calNow.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dfChartKey.format(calNow.time)

        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, selectedYear)
            set(Calendar.MONTH, selectedMonth - 1)
            set(Calendar.DAY_OF_MONTH, selectedDay)
        }
        val weekdayStr = SimpleDateFormat("E", Locale.CHINESE).format(cal.time)

        val suffix = when (selectedDateStr) {
            todayStr -> "今天"
            yesterdayStr -> "昨天"
            else -> weekdayStr
        }

        tvDailyDate.text = "${String.format(Locale.getDefault(), "%02d.%02d", selectedMonth, selectedDay)} $suffix"

        val summary = dailySummaryMap[selectedDateStr] ?: DailySummary()

        val sb = java.lang.StringBuilder()
        if (summary.income > 0) sb.append("收 ¥${String.format(Locale.getDefault(), "%.2f", summary.income)} ")
        if (summary.expense > 0) sb.append("支 ¥${String.format(Locale.getDefault(), "%.2f", summary.expense)}")
        tvDailySummary.text = sb.toString().trim()

        if (dailyBills.isEmpty()) {
            rvDailyBills.visibility = View.GONE
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.GONE
            tvDailyEmpty.visibility = View.VISIBLE
        } else {
            rvDailyBills.visibility = View.VISIBLE
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.VISIBLE
            tvDailyEmpty.visibility = View.GONE

            // To prevent duplicated headers inside HomeAdapter, we just pass the raw list
            // HomeAdapter will automatically generate ONE header at the top matching the day.
            // Since we already have tvDailyDate, we hide ours.
            findViewById<View>(R.id.layoutDailyHeader).visibility = View.GONE
            dailyAdapter.submitList(dailyBills)
        }
    }

    private fun lunarOrFestivalText(year: Int, month: Int, day: Int): String {
        val solar = solarFestivals[Pair(month, day)]
        if (solar != null) return solar

        return try {
            val g = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day)
            }
            val cc = ChineseCalendar().apply { timeInMillis = g.timeInMillis }
            val lunarMonth = cc.get(ChineseCalendar.MONTH) + 1
            val lunarDay = cc.get(ChineseCalendar.DAY_OF_MONTH)

            lunarFestivals[Pair(lunarMonth, lunarDay)]
                ?: if (lunarDay == 1) lunarMonthName(lunarMonth) else lunarDayName(lunarDay)
        } catch (e: Exception) {
            ""
        }
    }

    private fun lunarMonthName(month: Int): String {
        val names = arrayOf(
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"
        )
        return names.getOrElse((month - 1).coerceIn(0, 11)) { "" }
    }

    private fun lunarDayName(day: Int): String {
        val names = arrayOf(
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
        )
        return names.getOrElse((day - 1).coerceIn(0, 29)) { "" }
    }
    private fun buildCellBackgroundColor(dateStr: String, summary: DailySummary): Int {
        if (currentMode == MODE_BOTH) {
            return Color.parseColor("#F5F6F8")
        }

        val level: Int
        val positive: Boolean
        when (currentMode) {
            MODE_INCOME -> {
                level = incomeLevelMap[dateStr] ?: 0
                positive = true
            }
            MODE_EXPENSE -> {
                level = expenseLevelMap[dateStr] ?: 0
                positive = false
            }
            else -> {
                val balance = summary.income - summary.expense
                level = balanceLevelMap[dateStr] ?: 0
                positive = balance > 0
            }
        }

        if (level <= 0) return Color.parseColor("#F5F6F8")
        val t = (level - 1) / 30f
        return if (positive) {
            blendColor(Color.parseColor("#ECF9F2"), Color.parseColor("#7AD9B1"), t)
        } else {
            blendColor(Color.parseColor("#FDF0F2"), Color.parseColor("#F39AA5"), t)
        }
    }

    private fun blendColor(startColor: Int, endColor: Int, t: Float): Int {
        val clamped = t.coerceIn(0f, 1f)
        val sr = Color.red(startColor)
        val sg = Color.green(startColor)
        val sb = Color.blue(startColor)
        val er = Color.red(endColor)
        val eg = Color.green(endColor)
        val eb = Color.blue(endColor)
        return Color.rgb(
            (sr + (er - sr) * clamped).toInt(),
            (sg + (eg - sg) * clamped).toInt(),
            (sb + (eb - sb) * clamped).toInt()
        )
    }

    inner class CalendarAdapter : RecyclerView.Adapter<CalendarAdapter.DayViewHolder>() {

        private val cells = mutableListOf<DayCell>()

        fun buildCalendar() {
            cells.clear()
            val cal = Calendar.getInstance().apply {
                set(Calendar.YEAR, selectedYear)
                set(Calendar.MONTH, selectedMonth - 1)
                set(Calendar.DAY_OF_MONTH, 1)
            }

            val firstDayOfCurrentMonth = cal.get(Calendar.DAY_OF_WEEK)

            var offset = firstDayOfCurrentMonth - firstDayOfWeekOption
            if (offset < 0) offset += 7

            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

            for (i in 0 until offset) {
                cells.add(DayCell(0, "", DailySummary()))
            }

            for (day in 1..daysInMonth) {
                val dateStr = String.format(Locale.getDefault(), "%04d-%02d-%02d", selectedYear, selectedMonth, day)
                cells.add(DayCell(day, dateStr, dailySummaryMap[dateStr] ?: DailySummary()))
            }

            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_calendar_day, parent, false)
            return DayViewHolder(view)
        }

        override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
            holder.bind(cells[position])
        }

        override fun getItemCount(): Int = cells.size

        inner class DayViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDayNumber: TextView = itemView.findViewById(R.id.tvDayNumber)
            private val tvTopValue: TextView = itemView.findViewById(R.id.tvTopValue)
            private val tvBottomValue: TextView = itemView.findViewById(R.id.tvBottomValue)
            private val layoutCell: View = itemView.findViewById(R.id.layoutCell)

            fun bind(cell: DayCell) {
                if (cell.day == 0) {
                    tvDayNumber.text = ""
                    tvTopValue.visibility = View.GONE
                    tvBottomValue.visibility = View.GONE
                    layoutCell.isSelected = false
                    layoutCell.background = null
                    itemView.setOnClickListener(null)
                    return
                }

                tvDayNumber.text = cell.day.toString()
                tvTopValue.visibility = View.INVISIBLE
                tvBottomValue.visibility = View.INVISIBLE
                tvTopValue.text = ""
                tvBottomValue.text = ""

                val s = cell.summary
                val selected = (cell.day == selectedDay)

                when (currentMode) {
                    MODE_BOTH -> {
                        if (s.expense > 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = "-${formatAmt(s.expense)}"
                            tvTopValue.setTextColor(Color.parseColor("#FF5252"))
                        }
                        if (s.income > 0) {
                            tvBottomValue.visibility = View.VISIBLE
                            tvBottomValue.text = "+${formatAmt(s.income)}"
                            tvBottomValue.setTextColor(Color.parseColor("#4CAF50"))
                        }
                        if (s.expense <= 0 && s.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(selectedYear, selectedMonth, cell.day)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                    MODE_BALANCE -> {
                        val balance = s.income - s.expense
                        if (balance != 0.0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = if (balance > 0) "+${formatAmt(balance)}" else "${formatAmt(balance)}"
                            tvTopValue.setTextColor(if (balance > 0) Color.parseColor("#4CAF50") else Color.parseColor("#FF5252"))
                        } else if (s.expense <= 0 && s.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(selectedYear, selectedMonth, cell.day)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                    MODE_INCOME -> {
                        if (s.income > 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = "+${formatAmt(s.income)}"
                            tvTopValue.setTextColor(Color.parseColor("#4CAF50"))
                        } else if (s.expense <= 0 && s.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(selectedYear, selectedMonth, cell.day)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                    MODE_EXPENSE -> {
                        if (s.expense > 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = "-${formatAmt(s.expense)}"
                            tvTopValue.setTextColor(Color.parseColor("#FF5252"))
                        } else if (s.expense <= 0 && s.income <= 0) {
                            tvTopValue.visibility = View.VISIBLE
                            tvTopValue.text = lunarOrFestivalText(selectedYear, selectedMonth, cell.day)
                            tvTopValue.setTextColor(Color.parseColor("#8E97A4"))
                        }
                    }
                }

                val bgColor = if (selected) Color.parseColor("#DCEBFF") else buildCellBackgroundColor(cell.dateStr, s)
                layoutCell.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 10f * resources.displayMetrics.density
                    setColor(bgColor)
                }

                if (selected) {
                    tvDayNumber.setTextColor(Color.parseColor("#4080FF"))
                    tvTopValue.setTextColor(Color.parseColor("#4080FF"))
                    tvBottomValue.setTextColor(Color.parseColor("#4080FF"))
                } else {
                    tvDayNumber.setTextColor(Color.parseColor("#333333"))
                }

                itemView.setOnClickListener {
                    selectedDay = cell.day
                    notifyDataSetChanged()
                    updateSelectedDayDetails()
                }
            }

            private fun formatAmt(amt: Double): String {
                val s = String.format(Locale.getDefault(), "%.2f", amt)
                return if (s.endsWith(".00")) s.replace(".00", "") else s
            }
        }
    }

    data class DayCell(val day: Int, val dateStr: String, val summary: DailySummary)
}
