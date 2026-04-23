package tao.test.flipaccounting.ui.activity

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import java.util.Calendar
import java.util.Locale

class BookOverviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CURRENT_BOOK = "extra_current_book"
    }

    private var selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR)
    private var selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH) + 1
    private var isYearMode: Boolean = false

    private lateinit var tvCurrentPeriod: TextView
    private lateinit var tvTotalExpense: TextView
    private lateinit var tvTotalIncome: TextView
    private lateinit var tvTotalBalance: TextView
    private lateinit var btnPeriodPrev: TextView
    private lateinit var btnPeriodNext: TextView
    private lateinit var btnViewMonth: TextView
    private lateinit var btnViewYear: TextView
    private lateinit var rvBookOverview: RecyclerView

    private lateinit var adapter: BookOverviewAdapter
    private var currentBook: String = BookAccountManager.DEFAULT_BOOK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_book_overview)

        currentBook = intent.getStringExtra(EXTRA_CURRENT_BOOK) ?: BookAccountManager.DEFAULT_BOOK

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        bindViews()
        setupAdapter()
        setupDragDrop()
        setupPeriodControls()
        loadData()
    }

    private fun bindViews() {
        tvCurrentPeriod = findViewById(R.id.tvCurrentPeriod)
        tvTotalExpense = findViewById(R.id.tvTotalExpense)
        tvTotalIncome = findViewById(R.id.tvTotalIncome)
        tvTotalBalance = findViewById(R.id.tvTotalBalance)
        btnPeriodPrev = findViewById(R.id.btnPeriodPrev)
        btnPeriodNext = findViewById(R.id.btnPeriodNext)
        btnViewMonth = findViewById(R.id.btnViewMonth)
        btnViewYear = findViewById(R.id.btnViewYear)
        rvBookOverview = findViewById(R.id.rvBookOverview)
    }

    private fun setupAdapter() {
        adapter = BookOverviewAdapter(
            onCardClick = { item ->
                // 点击卡片返回选中的账本
                val resultIntent = android.content.Intent()
                resultIntent.putExtra(EXTRA_CURRENT_BOOK, item.bookName)
                setResult(RESULT_OK, resultIntent)
                finish()
            },
            onOrderChanged = { newOrder ->
                // 拖拽结束后持久化新顺序
                BookAccountManager.reorderBookAccounts(this, newOrder)
            }
        )
        adapter.setYearMode(isYearMode)
        rvBookOverview.layoutManager = LinearLayoutManager(this)
        rvBookOverview.adapter = adapter
    }

    private fun setupDragDrop() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private var dragFrom = -1
            private var dragTo = -1

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (dragFrom == -1) dragFrom = from
                dragTo = to
                adapter.onItemMove(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 只有真正发生了移动才持久化
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    adapter.onDragEnd()
                }
                dragFrom = -1
                dragTo = -1
                // 恢复卡片透明度
                viewHolder.itemView.alpha = 1f
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    // 拖拽中：轻微放大 + 半透明，给出被"拎起"的视觉反馈
                    viewHolder?.itemView?.apply {
                        animate().scaleX(1.03f).scaleY(1.03f).alpha(0.92f).setDuration(120).start()
                    }
                }
            }

            override fun isLongPressDragEnabled(): Boolean = true
        }

        ItemTouchHelper(callback).attachToRecyclerView(rvBookOverview)
    }

    private fun setupPeriodControls() {
        btnPeriodPrev.setOnClickListener { stepPeriod(-1) }
        btnPeriodNext.setOnClickListener { stepPeriod(+1) }

        btnViewMonth.setOnClickListener {
            if (isYearMode) {
                isYearMode = false
                updateViewModeStyle()
                updatePeriodText()
                adapter.setYearMode(isYearMode)
                loadData()
            }
        }
        btnViewYear.setOnClickListener {
            if (!isYearMode) {
                isYearMode = true
                updateViewModeStyle()
                updatePeriodText()
                adapter.setYearMode(isYearMode)
                loadData()
            }
        }
        updatePeriodText()
        updateViewModeStyle()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    private fun stepPeriod(delta: Int) {
        if (isYearMode) {
            selectedYear += delta
        } else {
            selectedMonth += delta
            if (selectedMonth > 12) { selectedMonth = 1; selectedYear++ }
            if (selectedMonth < 1)  { selectedMonth = 12; selectedYear-- }
        }
        updatePeriodText()
        loadData()
    }

    private fun updatePeriodText() {
        tvCurrentPeriod.text = if (isYearMode) {
            "${selectedYear}年"
        } else {
            "${selectedYear}年${String.format(Locale.getDefault(), "%02d", selectedMonth)}月"
        }
    }

    private fun updateViewModeStyle() {
        val activeColor = android.graphics.Color.parseColor("#4080FF")
        val inactiveColor = android.graphics.Color.parseColor("#888888")
        btnViewMonth.apply {
            setTextColor(if (!isYearMode) activeColor else inactiveColor)
            setBackgroundResource(if (!isYearMode) R.drawable.bg_segmented_selected else android.R.color.transparent)
            setTypeface(null, if (!isYearMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        btnViewYear.apply {
            setTextColor(if (isYearMode) activeColor else inactiveColor)
            setBackgroundResource(if (isYearMode) R.drawable.bg_segmented_selected else android.R.color.transparent)
            setTypeface(null, if (isYearMode) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildOverviewItems() }
            val totalExpense = items.sumOf { it.expense }
            val totalIncome = items.sumOf { it.income }
            val totalBalance = totalIncome - totalExpense

            adapter.submitList(items)

            tvTotalExpense.text = "¥${String.format("%.2f", totalExpense)}"
            tvTotalIncome.text = "¥${String.format("%.2f", totalIncome)}"
            val balanceSign = if (totalBalance >= 0) "+" else ""
            tvTotalBalance.text = "${balanceSign}¥${String.format("%.2f", totalBalance)}"
            tvTotalBalance.setTextColor(
                android.graphics.Color.parseColor(if (totalBalance >= 0) "#2FA36B" else "#E05A5A")
            )
        }
    }

    private suspend fun buildOverviewItems(): List<BookOverviewItem> {
        val ctx = this
        val db = AppDatabase.getDatabase(ctx)
        val books = BookAccountManager.getBookAccounts(ctx)
            .map { BookAccountManager.normalizeBookName(it) }
            .filter { it != BookAccountManager.ALL_BOOK }
        val (startMs, endMs) = getPeriodRange()
        val allBills: List<Bill> = db.billDao().getBillsBetweenTimesList(startMs, endMs)

        return books.map { normalizedBook ->
            val billsForBook = allBills.filter {
                BookAccountManager.isBillInBook(it.bookName, normalizedBook)
            }
            val expense = billsForBook
                .sumOf { bill ->
                    val amount = if (bill.currency == "CNY") bill.amount else bill.amount * bill.exchangeRate
                    when {
                        bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> 0.0
                        bill.subType == Bill.SUBTYPE_REFUND -> -amount
                        bill.type == Bill.TYPE_EXPENSE -> amount
                        else -> 0.0
                    }
                }
            val income = billsForBook
                .sumOf { bill ->
                    val amount = if (bill.currency == "CNY") bill.amount else bill.amount * bill.exchangeRate
                    when {
                        bill.subType == Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED -> 0.0
                        bill.subType == Bill.SUBTYPE_REFUND -> 0.0
                        bill.type == Bill.TYPE_INCOME -> amount
                        else -> 0.0
                    }
                }

            BookOverviewItem(
                bookName = normalizedBook,
                themeColor = BookAccountManager.getBookColor(ctx, normalizedBook),
                bannerPath = BookAccountManager.getBookBannerPath(ctx, normalizedBook),
                expense = expense,
                income = income,
                isCurrentBook = normalizedBook == BookAccountManager.normalizeBookName(currentBook)
            )
        }
    }

    private fun getPeriodRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        return if (isYearMode) {
            cal.set(selectedYear, Calendar.JANUARY, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(selectedYear, Calendar.DECEMBER, 31, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        } else {
            cal.set(selectedYear, selectedMonth - 1, 1)
            val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            cal.set(selectedYear, selectedMonth - 1, 1, 0, 0, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.set(selectedYear, selectedMonth - 1, daysInMonth, 23, 59, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        }
    }
}
