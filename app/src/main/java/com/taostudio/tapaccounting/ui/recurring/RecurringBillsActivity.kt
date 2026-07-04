package com.taostudio.tapaccounting.ui.recurring

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
import com.google.android.material.tabs.TabLayout
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import com.taostudio.tapaccounting.logic.RecurringBillingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 周期账单管理页。
 * Tab：待确认 / 已确认 / 已忽略
 */
class RecurringBillsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: RecurringPatternAdapter
    private lateinit var rvBills: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_bills)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        db = AppDatabase.getDatabase(this)
        rvBills = findViewById(R.id.rv_recurring_bills)
        rvBills.layoutManager = LinearLayoutManager(this)
        tvEmpty = findViewById(R.id.tv_recurring_empty)
        tvSummary = findViewById(R.id.tv_recurring_summary)
        tabLayout = findViewById(R.id.tab_recurring)

        adapter = RecurringPatternAdapter(
            onConfirm = { pattern -> updateStatus(pattern, RecurringStatus.CONFIRMED, getString(R.string.recurring_status_confirmed)) },
            onDismiss = { pattern -> updateStatus(pattern, RecurringStatus.DISMISSED, getString(R.string.recurring_status_dismissed)) },
            onRestorePending = { pattern -> updateStatus(pattern, RecurringStatus.SUGGESTED, getString(R.string.recurring_status_pending)) },
            onEdit = { pattern -> showPatternDialog(pattern) }
        )
        rvBills.adapter = adapter
        findViewById<View>(R.id.btn_add_recurring)?.setOnClickListener { showPatternDialog(null) }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> loadBills(RecurringStatus.SUGGESTED)
                    1 -> loadBills(RecurringStatus.CONFIRMED)
                    2 -> loadBills(RecurringStatus.DISMISSED)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        // 默认加载待确认
        loadBills(RecurringStatus.SUGGESTED)
    }

    private fun showPatternDialog(existing: RecurringPattern?) {
        lifecycleScope.launch {
            val categories = withContext(Dispatchers.IO) {
                db.categoryDao().getCategoriesListByType(0)
            }
            val accounts = withContext(Dispatchers.IO) {
                db.assetDao().getAllAssetsList().map { it.name }.filter { it.isNotBlank() }.distinct()
            }
            val view = layoutInflater.inflate(R.layout.dialog_recurring_pattern, null)
            val etMerchant = view.findViewById<EditText>(R.id.et_recurring_merchant)
            val etAmount = view.findViewById<EditText>(R.id.et_recurring_amount)
            val etTolerance = view.findViewById<EditText>(R.id.et_recurring_tolerance)
            val etDay = view.findViewById<EditText>(R.id.et_recurring_day)
            val spFrequency = view.findViewById<Spinner>(R.id.spinner_recurring_frequency)
            val spCategory = view.findViewById<Spinner>(R.id.spinner_recurring_category)
            val spAccount = view.findViewById<Spinner>(R.id.spinner_recurring_account)

            val frequencyItems = listOf(
                getString(R.string.recurring_frequency_weekly),
                getString(R.string.recurring_frequency_monthly),
                getString(R.string.recurring_frequency_yearly)
            )
            spFrequency.adapter = ArrayAdapter(this@RecurringBillsActivity, android.R.layout.simple_spinner_dropdown_item, frequencyItems)
            val categoryLabels = listOf(getString(R.string.budget_not_set)) + categories.map { it.name }
            spCategory.adapter = ArrayAdapter(this@RecurringBillsActivity, android.R.layout.simple_spinner_dropdown_item, categoryLabels)
            val accountLabels = listOf(getString(R.string.budget_not_set)) + accounts
            spAccount.adapter = ArrayAdapter(this@RecurringBillsActivity, android.R.layout.simple_spinner_dropdown_item, accountLabels)

            existing?.let { pattern ->
                etMerchant.setText(pattern.merchantKey)
                etAmount.setText(String.format(Locale.getDefault(), "%.2f", pattern.amountApprox))
                etTolerance.setText(String.format(Locale.getDefault(), "%.2f", pattern.amountTolerance))
                pattern.dayOfMonthHint?.let { etDay.setText(it.toString()) }
                spFrequency.setSelection(
                    when (pattern.frequency) {
                        RecurringFrequency.WEEKLY -> 0
                        RecurringFrequency.MONTHLY -> 1
                        RecurringFrequency.YEARLY -> 2
                    }
                )
                val categoryIndex = categories.indexOfFirst { it.id == pattern.categoryId }
                if (categoryIndex >= 0) spCategory.setSelection(categoryIndex + 1)
                val accountIndex = accounts.indexOf(pattern.accountName)
                if (accountIndex >= 0) spAccount.setSelection(accountIndex + 1)
            } ?: run {
                spFrequency.setSelection(1)
                etTolerance.setText("5")
            }

            AlertDialog.Builder(ContextThemeWrapper(this@RecurringBillsActivity, R.style.Theme_TapAccounting))
                .setTitle(if (existing == null) getString(R.string.recurring_add_title) else getString(R.string.recurring_edit_title))
                .setView(view)
                .setPositiveButton(getString(R.string.rule_learn_confirm), null)
                .setNegativeButton(getString(R.string.rule_learn_cancel), null)
                .create()
                .also { dialog ->
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            savePatternFromDialog(
                                dialog = dialog,
                                existing = existing,
                                etMerchant = etMerchant,
                                etAmount = etAmount,
                                etTolerance = etTolerance,
                                etDay = etDay,
                                spFrequency = spFrequency,
                                spCategory = spCategory,
                                spAccount = spAccount,
                                categories = categories,
                                accounts = accounts
                            )
                        }
                    }
                    dialog.show()
                }
        }
    }

    private fun savePatternFromDialog(
        dialog: AlertDialog,
        existing: RecurringPattern?,
        etMerchant: EditText,
        etAmount: EditText,
        etTolerance: EditText,
        etDay: EditText,
        spFrequency: Spinner,
        spCategory: Spinner,
        spAccount: Spinner,
        categories: List<Category>,
        accounts: List<String>
    ) {
        val merchant = etMerchant.text.toString().trim()
        val amount = etAmount.text.toString().toDoubleOrNull()
        val tolerance = etTolerance.text.toString().toDoubleOrNull() ?: 5.0
        val day = etDay.text.toString().toIntOrNull()
        if (merchant.isBlank() || amount == null || amount <= 0) {
            Toast.makeText(this, getString(R.string.recurring_input_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        if (day != null && day !in 1..31) {
            Toast.makeText(this, getString(R.string.recurring_day_invalid), Toast.LENGTH_SHORT).show()
            return
        }
        val frequency = when (spFrequency.selectedItemPosition) {
            0 -> RecurringFrequency.WEEKLY
            2 -> RecurringFrequency.YEARLY
            else -> RecurringFrequency.MONTHLY
        }
        val selectedCategory = categories.getOrNull(spCategory.selectedItemPosition - 1)
        val selectedAccount = accounts.getOrNull(spAccount.selectedItemPosition - 1)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val service = RecurringBillingService(db)
                if (existing == null) {
                    service.createManualPattern(
                        merchantText = merchant,
                        amountApprox = amount,
                        amountTolerance = tolerance,
                        frequency = frequency,
                        dayOfMonthHint = day,
                        bookName = BookAccountManager.getSelectedBook(this@RecurringBillsActivity),
                        categoryId = selectedCategory?.id,
                        categoryName = selectedCategory?.name,
                        accountName = selectedAccount
                    )
                } else {
                    val normalizedKey = RecurringBillingService.normalizeMerchantKey(merchant)
                    val lastSeenAt = existing.lastSeenAt
                    db.recurringPatternDao().update(
                        existing.copy(
                            merchantKey = normalizedKey,
                            amountApprox = amount,
                            amountTolerance = tolerance.coerceAtLeast(0.0),
                            frequency = frequency,
                            dayOfMonthHint = day,
                            categoryId = selectedCategory?.id,
                            categoryName = selectedCategory?.name,
                            accountName = selectedAccount,
                            status = RecurringStatus.CONFIRMED,
                            nextExpectedAt = RecurringBillingService.calculateNextExpected(lastSeenAt, frequency),
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
            dialog.dismiss()
            Toast.makeText(this@RecurringBillsActivity, getString(R.string.recurring_saved), Toast.LENGTH_SHORT).show()
            loadBills(RecurringStatus.CONFIRMED)
            tabLayout.getTabAt(1)?.select()
        }
    }

    private fun updateStatus(pattern: RecurringPattern, status: RecurringStatus, toast: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.recurringPatternDao().update(
                    pattern.copy(status = status, updatedAt = System.currentTimeMillis())
                )
            }
            Toast.makeText(this@RecurringBillsActivity, toast, Toast.LENGTH_SHORT).show()
            val currentTab = tabLayout.selectedTabPosition
            when (currentTab) {
                0 -> loadBills(RecurringStatus.SUGGESTED)
                1 -> loadBills(RecurringStatus.CONFIRMED)
                2 -> loadBills(RecurringStatus.DISMISSED)
            }
        }
    }

    private fun loadBills(status: RecurringStatus) {
        lifecycleScope.launch {
            val bills = withContext(Dispatchers.IO) {
                db.recurringPatternDao().getByStatus(status)
            }
            if (bills.isEmpty()) {
                tvSummary.visibility = View.GONE
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = when (status) {
                    RecurringStatus.SUGGESTED -> getString(R.string.recurring_empty_pending)
                    RecurringStatus.CONFIRMED -> getString(R.string.recurring_empty_confirmed)
                    RecurringStatus.DISMISSED -> getString(R.string.recurring_empty_dismissed)
                }
                rvBills.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBills.visibility = View.VISIBLE
                bindSummary(status, bills)
                adapter.submitList(bills)
            }
        }
    }

    private fun bindSummary(status: RecurringStatus, bills: List<RecurringPattern>) {
        val now = System.currentTimeMillis()
        val dueSoonAt = now + 3L * 24L * 3600_000L
        val dueSoonCount = bills.count { next ->
            val expectedAt = next.nextExpectedAt ?: return@count false
            expectedAt in now..dueSoonAt
        }
        val overdueCount = bills.count { (it.nextExpectedAt ?: Long.MAX_VALUE) < now }
        val label = when (status) {
            RecurringStatus.SUGGESTED -> getString(R.string.recurring_tab_pending)
            RecurringStatus.CONFIRMED -> getString(R.string.recurring_tab_confirmed)
            RecurringStatus.DISMISSED -> getString(R.string.recurring_tab_dismissed)
        }
        tvSummary.visibility = View.VISIBLE
        tvSummary.text = getString(
            R.string.recurring_summary_fmt,
            label,
            bills.size,
            dueSoonCount,
            overdueCount
        )
    }
}
