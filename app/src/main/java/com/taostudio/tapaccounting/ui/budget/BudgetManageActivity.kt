package com.taostudio.tapaccounting.ui.budget

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.BudgetService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 预算管理页。
 * 展示当月各分类预算列表，支持新增/编辑/删除预算。
 */
class BudgetManageActivity : AppCompatActivity() {

    private lateinit var budgetService: BudgetService
    private lateinit var currentBook: String
    private lateinit var yearMonth: String
    private lateinit var adapter: BudgetAdapter
    private lateinit var rvBudgets: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutSummary: View
    private lateinit var tvSummaryTitle: TextView
    private lateinit var tvSummaryStatus: TextView
    private lateinit var tvSummaryDetail: TextView
    private lateinit var progressSummary: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_budget_manage)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        val db = AppDatabase.getDatabase(this)
        budgetService = BudgetService(db.budgetDao(), db.billDao())
        currentBook = BookAccountManager.getSelectedBook(this)
        yearMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(
            Calendar.getInstance().time
        )

        rvBudgets = findViewById(R.id.rv_budgets)
        rvBudgets.layoutManager = LinearLayoutManager(this)
        tvEmpty = findViewById(R.id.tv_budget_empty)
        layoutSummary = findViewById(R.id.layout_budget_summary)
        tvSummaryTitle = findViewById(R.id.tv_budget_summary_title)
        tvSummaryStatus = findViewById(R.id.tv_budget_summary_status)
        tvSummaryDetail = findViewById(R.id.tv_budget_summary_detail)
        progressSummary = findViewById(R.id.progress_budget_summary)
        val btnSuggest = findViewById<View>(R.id.btn_suggest_budget)
        val btnAdd = findViewById<View>(R.id.btn_add_budget)

        adapter = BudgetAdapter(
            onItemClick = { budget -> showEditDialog(budget) },
            onItemLongClick = { budget -> showDeleteDialog(budget) }
        )
        rvBudgets.adapter = adapter

        btnAdd.setOnClickListener { showAddBudgetDialog() }
        loadBudgets()

        btnSuggest.setOnClickListener {
            lifecycleScope.launch {
                val suggested = withContext(Dispatchers.IO) {
                    budgetService.suggestBudgetFromHistory(currentBook, null, yearMonth)
                }
                if (suggested != null) {
                    showAddBudgetDialog(prefillAmount = suggested, forceTotalBudget = true)
                } else {
                    Toast.makeText(
                        this@BudgetManageActivity,
                        getString(R.string.budget_suggest_empty),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun loadBudgets() {
        lifecycleScope.launch {
            val budgetsWithProgress = withContext(Dispatchers.IO) {
                budgetService.getMonthBudgetsWithProgress(currentBook, yearMonth)
            }

            if (budgetsWithProgress.isEmpty()) {
                layoutSummary.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = getString(R.string.budget_empty_hint)
                rvBudgets.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBudgets.visibility = View.VISIBLE
                bindSummary(budgetsWithProgress)
                adapter.submitList(budgetsWithProgress)
            }
        }
    }

    private fun bindSummary(items: List<BudgetService.BudgetOverview>) {
        val total = items.firstOrNull { it.budget.categoryId == null } ?: items.firstOrNull() ?: return
        val budget = total.budget
        val progress = total.progress
        val statusColor = when (progress.status) {
            BudgetService.BudgetStatus.EXCEEDED -> Color.parseColor("#FF5252")
            BudgetService.BudgetStatus.WARNING -> Color.parseColor("#FF9800")
            BudgetService.BudgetStatus.NORMAL -> Color.parseColor("#4CAF50")
        }
        layoutSummary.visibility = View.VISIBLE
        tvSummaryTitle.text = getString(
            R.string.budget_summary_title_fmt,
            yearMonth,
            budget.categoryName ?: getString(R.string.budget_monthly_total)
        )
        tvSummaryStatus.text = when (progress.status) {
            BudgetService.BudgetStatus.EXCEEDED -> getString(R.string.budget_status_exceeded)
            BudgetService.BudgetStatus.WARNING -> getString(R.string.budget_status_warning)
            BudgetService.BudgetStatus.NORMAL -> getString(R.string.budget_status_normal)
        }
        tvSummaryStatus.setTextColor(statusColor)
        tvSummaryDetail.text = if (progress.remaining >= 0) {
            getString(R.string.budget_summary_detail_fmt, budget.amount, progress.usedAmount, progress.remaining)
        } else {
            getString(R.string.budget_over_budget_fmt, budget.amount, progress.usedAmount, -progress.remaining)
        }
        progressSummary.progress = (progress.percent * 100).toInt().coerceAtMost(100)
        progressSummary.progressTintList = ColorStateList.valueOf(statusColor)
    }

    private fun showAddBudgetDialog(prefillAmount: Double? = null, forceTotalBudget: Boolean = false) {
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) {
                CategoryRepository(AppDatabase.getDatabase(this@BudgetManageActivity).categoryDao())
                    .getCategoriesListByType(0)
                    .map { it.name }
                    .distinct()
            }
            val labels = listOf(getString(R.string.budget_monthly_total)) + categories
            val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
            val spinner = dialogView.findViewById<Spinner>(R.id.spinner_budget_category)
            val etAmount = dialogView.findViewById<EditText>(R.id.et_budget_amount)
            if (prefillAmount != null) {
                etAmount.setText(String.format(Locale.getDefault(), "%.0f", prefillAmount))
            }
            spinner.adapter = ArrayAdapter(
                this@BudgetManageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )
            spinner.isEnabled = !forceTotalBudget
            dialogView.findViewById<TextView>(R.id.tv_budget_dialog_title).text = getString(R.string.budget_set)
            if (forceTotalBudget) {
                Toast.makeText(
                    this@BudgetManageActivity,
                    getString(R.string.budget_suggest_apply_fmt, prefillAmount ?: 0.0),
                    Toast.LENGTH_SHORT
                ).show()
            }

            val dialog = BottomSheetDialog(this@BudgetManageActivity)
            dialog.setContentView(dialogView)
            dialogView.findViewById<View>(R.id.btn_budget_cancel).setOnClickListener { dialog.dismiss() }
            dialogView.findViewById<View>(R.id.btn_budget_save).setOnClickListener {
                    val amount = etAmount.text.toString().toDoubleOrNull()
                    if (amount == null || amount <= 0) {
                        Toast.makeText(this@BudgetManageActivity, getString(R.string.budget_amount_invalid), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val selectedIndex = spinner.selectedItemPosition
                    val categoryName = if (selectedIndex <= 0) null else labels[selectedIndex]
                    lifecycleScope.launch {
                        val categoryId = if (categoryName == null) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                CategoryRepository(AppDatabase.getDatabase(this@BudgetManageActivity).categoryDao())
                                    .findCategoryByDisplayName(0, categoryName)?.id
                            }
                        }
                        withContext(Dispatchers.IO) {
                            val db = AppDatabase.getDatabase(this@BudgetManageActivity)
                            val now = System.currentTimeMillis()
                            val existing = if (categoryId == null) {
                                db.budgetDao().getTotalBudget(yearMonth, currentBook)
                            } else {
                                db.budgetDao().getBudgetByBookAndCategory(yearMonth, currentBook, categoryId)
                            }
                            if (existing == null) {
                                db.budgetDao().insert(
                                    Budget(
                                        bookName = currentBook,
                                        categoryId = categoryId,
                                        categoryName = categoryName,
                                        yearMonth = yearMonth,
                                        amount = amount,
                                        createdAt = now,
                                        updatedAt = now
                                    )
                                )
                            } else {
                                db.budgetDao().update(
                                    existing.copy(
                                        categoryName = categoryName,
                                        amount = amount,
                                        updatedAt = now
                                    )
                                )
                            }
                        }
                        loadBudgets()
                        dialog.dismiss()
                    }
                }
            dialog.show()
        }
    }

    private fun showEditDialog(budget: Budget) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_budget, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinner_budget_category)
        val etAmount = dialogView.findViewById<EditText>(R.id.et_budget_amount)
        val label = budget.categoryName ?: getString(R.string.budget_monthly_total)
        dialogView.findViewById<TextView>(R.id.tv_budget_dialog_title).text = getString(R.string.budget_edit)
        etAmount.setText(String.format(Locale.getDefault(), "%.0f", budget.amount))
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(label)
        )
        spinner.isEnabled = false

        val dialog = BottomSheetDialog(this)
        dialog.setContentView(dialogView)
        dialogView.findViewById<View>(R.id.btn_budget_cancel).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btn_budget_save).setOnClickListener {
            val newAmount = etAmount.text.toString().toDoubleOrNull()
            if (newAmount == null || newAmount <= 0) {
                Toast.makeText(this, getString(R.string.budget_amount_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(this@BudgetManageActivity)
                        .budgetDao()
                        .update(budget.copy(amount = newAmount, updatedAt = System.currentTimeMillis()))
                }
                loadBudgets()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showDeleteDialog(budget: Budget) {
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle(getString(R.string.budget_delete))
            .setMessage(getString(R.string.budget_delete_confirm, budget.categoryName ?: getString(R.string.budget_monthly_total)))
            .setPositiveButton(getString(R.string.rule_learn_confirm)) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(this@BudgetManageActivity).budgetDao().delete(budget)
                    }
                    loadBudgets()
                }
            }
            .setNegativeButton(getString(R.string.rule_learn_cancel), null)
            .show()
    }
}
