package com.taostudio.tapaccounting.ui.budget

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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
                    Toast.makeText(
                        this@BudgetManageActivity,
                        getString(R.string.budget_suggest_result_fmt, suggested),
                        Toast.LENGTH_LONG
                    ).show()
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
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = getString(R.string.budget_empty_hint)
                rvBudgets.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBudgets.visibility = View.VISIBLE
                adapter.submitList(budgetsWithProgress)
            }
        }
    }

    private fun showAddBudgetDialog() {
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
            spinner.adapter = ArrayAdapter(
                this@BudgetManageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                labels
            )

            AlertDialog.Builder(ContextThemeWrapper(this@BudgetManageActivity, R.style.Theme_TapAccounting))
                .setTitle(getString(R.string.budget_set))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.rule_learn_confirm)) { _, _ ->
                    val amount = etAmount.text.toString().toDoubleOrNull()
                    if (amount == null || amount <= 0) {
                        Toast.makeText(this@BudgetManageActivity, getString(R.string.budget_amount_invalid), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
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
                        }
                        loadBudgets()
                    }
                }
                .setNegativeButton(getString(R.string.rule_learn_cancel), null)
                .show()
        }
    }

    private fun showEditDialog(budget: Budget) {
        val et = EditText(this).apply {
            setText(String.format("%.0f", budget.amount))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle(getString(R.string.budget_edit))
            .setView(et)
            .setPositiveButton(getString(R.string.rule_learn_confirm)) { _, _ ->
                val newAmount = et.text.toString().toDoubleOrNull()
                if (newAmount != null && newAmount > 0) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            AppDatabase.getDatabase(this@BudgetManageActivity)
                                .budgetDao()
                                .update(budget.copy(amount = newAmount, updatedAt = System.currentTimeMillis()))
                        }
                        loadBudgets()
                    }
                }
            }
            .setNegativeButton(getString(R.string.rule_learn_cancel), null)
            .show()
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
