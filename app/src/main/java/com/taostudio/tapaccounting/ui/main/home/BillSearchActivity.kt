package com.taostudio.tapaccounting.ui.main.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlin.math.min
import java.util.Locale

class BillSearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_BOOK = "extra_source_book"
    }

    private lateinit var etSearch: EditText
    private lateinit var rvResults: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvScope: TextView
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsDelete: TextView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val adapter = HomeAdapter().apply { showChart = false }
    private var allBills: List<Bill> = emptyList()
    private var searchJob: Job? = null
    private var hasResumedOnce = false

    private val sourceBookName by lazy {
        BookAccountManager.normalizeBookName(
            intent.getStringExtra(EXTRA_SOURCE_BOOK).orEmpty().ifBlank { BookAccountManager.ALL_BOOK }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_search)

        etSearch = findViewById(R.id.et_bill_search)
        rvResults = findViewById(R.id.rv_bill_search_results)
        tvEmpty = findViewById(R.id.tv_bill_search_empty)
        tvScope = findViewById(R.id.tv_bill_search_scope)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsDelete = findViewById(R.id.btn_ms_delete)

        findViewById<View>(R.id.btn_bill_search_back).setOnClickListener { handleBackPressed() }
        onBackPressedDispatcher.addCallback(this) {
            handleBackPressed()
        }

        rvResults.layoutManager = LinearLayoutManager(this)
        rvResults.adapter = adapter

        setupMultiSelectActions()

        adapter.onBillItemClick = { bill ->
            if (!isFinishing) {
                BillDetailSheetHelper.showBillDetailSheet(
                    context = this,
                    lifecycleOwner = this,
                    bill = bill,
                    onBillChanged = { loadAllBills() }
                )
            }
        }
        adapter.detailSuffixProvider = { bill ->
            if (sourceBookName == BookAccountManager.ALL_BOOK) {
                "账本: ${BookAccountManager.normalizeBookName(bill.bookName)}"
            } else {
                null
            }
        }

        tvScope.text = if (sourceBookName == BookAccountManager.ALL_BOOK) {
            "范围：全部账本"
        } else {
            "范围：$sourceBookName"
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val keyword = s?.toString().orEmpty().trim()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(120)
                    applyFilter(keyword)
                }
            }
        })

        loadAllBills()

        etSearch.post {
            etSearch.requestFocus()
            val imm = getSystemService(InputMethodManager::class.java)
            imm?.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasResumedOnce) {
            // 独立详情页编辑/删除后返回时，重新读取当前搜索结果。
            loadAllBills()
        } else {
            hasResumedOnce = true
        }
    }

    private fun handleBackPressed() {
        if (adapter.isMultiSelectMode) {
            adapter.clearSelection()
        } else {
            finish()
        }
    }

    private fun setupMultiSelectActions() {
        adapter.onSelectionChanged = { count ->
            layoutMultiSelectActions.visibility = if (adapter.isMultiSelectMode) View.VISIBLE else View.GONE
            btnMsDelete.text = if (count > 0) "删除($count)" else "删除"
        }

        findViewById<View>(R.id.btn_ms_cancel).setOnClickListener {
            adapter.clearSelection()
        }
        findViewById<View>(R.id.btn_ms_select_all).setOnClickListener {
            val allCount = adapter.items.count { it is HomeAdapter.ListItem.Item }
            if (allCount > 0 && adapter.selectedBills.size >= allCount) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        findViewById<View>(R.id.btn_ms_move_book).setOnClickListener {
            val billsToMove = adapter.getSelectedBills()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = adapter.getSelectedBills()
            if (billsToDelete.isEmpty()) return@setOnClickListener
            lifecycleScope.launch {
                com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)
                val deletedIds = billsToDelete.map { it.id }.toSet()
                allBills = allBills.filterNot { it.id in deletedIds }
                adapter.clearSelection()
                applyFilter(etSearch.text?.toString().orEmpty().trim())
                Toast.makeText(
                    this@BillSearchActivity,
                    "已删除 ${billsToDelete.size} 条账单",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showMoveToBookDialog(bills: List<Bill>) {
        val themeCtx = ContextThemeWrapper(this, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(this)
            .inflate(R.layout.dialog_book_delete_options, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "移动到账本"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "选择目标账本"

        val optionsScroll = panel.findViewById<ScrollView>(R.id.scroll_delete_book_options)
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val availableBookNames = allBills
            .map { BookAccountManager.normalizeBookName(it.bookName) }
            .ifEmpty { listOf(BookAccountManager.normalizeBookName(sourceBookName)) }
            .distinct()
            .sorted()

        availableBookNames.forEach { targetBook ->
            val item = LayoutInflater.from(this)
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = targetBook
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = "将所选账单迁移到该账本"
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE
            item.setOnClickListener {
                val normalized = BookAccountManager.normalizeBookName(targetBook)
                if (bills.all { BookAccountManager.normalizeBookName(it.bookName) == normalized }) {
                    Toast.makeText(this, "账单已在「$targetBook」中，无需转移", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                lifecycleScope.launch(Dispatchers.IO) {
                    db.billDao().moveBillsToBook(bills.map { it.id }, targetBook)
                    withContext(Dispatchers.Main) {
                        adapter.clearSelection()
                        loadAllBills()
                        Toast.makeText(
                            this@BillSearchActivity,
                            "已将 ${bills.size} 条账单移动到「$targetBook」",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            optionsContainer.addView(item)
        }

        val maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val estimatedItemHeight = ((66 + 10) * resources.displayMetrics.density).toInt()
        val estimatedContentHeight = (availableBookNames.size * estimatedItemHeight).coerceAtLeast(1)
        optionsScroll.layoutParams = optionsScroll.layoutParams.apply {
            height = min(maxHeight, estimatedContentHeight)
        }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun loadAllBills() {
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                db.billDao().getAllBillsList()
            }
            allBills = bills
                .asSequence()
                .filter { BookAccountManager.isBillInBook(it.bookName, sourceBookName) }
                .sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                .toList()
            applyFilter(etSearch.text?.toString().orEmpty().trim())
        }
    }

    private fun applyFilter(keyword: String) {
        val filtered = filterBillsByKeyword(allBills, keyword)
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvResults.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun filterBillsByKeyword(source: List<Bill>, keywordRaw: String): List<Bill> {
        val keyword = keywordRaw.trim().lowercase(Locale.getDefault())
        if (keyword.isBlank()) return source
        val normalizedKeyword = keyword.replace(',', '.')

        return source.filter { bill ->
            val textMatched = listOf(
                bill.remark,
                bill.categoryName,
                BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
                bill.accountName,
                bill.toAccountName,
                BookAccountManager.normalizeBookName(bill.bookName)
            ).any { value ->
                value.isNotBlank() && value.lowercase(Locale.getDefault()).contains(keyword)
            }
            if (textMatched) return@filter true

            val numericCandidates = listOf(
                String.format(Locale.US, "%.2f", bill.amount),
                bill.amount.toString(),
                String.format(Locale.US, "%.2f", bill.originalAmount),
                bill.originalAmount.toString(),
                String.format(Locale.US, "%.2f", bill.amount * bill.exchangeRate)
            )
            numericCandidates.any { candidate ->
                val value = candidate.lowercase(Locale.getDefault())
                value.contains(keyword) || value.contains(normalizedKeyword)
            }
        }
    }
}

