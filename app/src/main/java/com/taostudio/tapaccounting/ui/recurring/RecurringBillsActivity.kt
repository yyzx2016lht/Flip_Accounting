package com.taostudio.tapaccounting.ui.recurring

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 周期账单管理页。
 * Tab：待确认 / 已确认 / 已忽略
 */
class RecurringBillsActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: RecurringPatternAdapter
    private lateinit var rvBills: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tabLayout: TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recurring_bills)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        db = AppDatabase.getDatabase(this)
        rvBills = findViewById(R.id.rv_recurring_bills)
        rvBills.layoutManager = LinearLayoutManager(this)
        tvEmpty = findViewById(R.id.tv_recurring_empty)
        tabLayout = findViewById(R.id.tab_recurring)

        adapter = RecurringPatternAdapter(
            onConfirm = { pattern -> updateStatus(pattern, RecurringStatus.CONFIRMED, "已确认") },
            onDismiss = { pattern -> updateStatus(pattern, RecurringStatus.DISMISSED, "已忽略") }
        )
        rvBills.adapter = adapter

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
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = when (status) {
                    RecurringStatus.SUGGESTED -> "没有待确认的周期账单"
                    RecurringStatus.CONFIRMED -> "没有已确认的周期账单"
                    RecurringStatus.DISMISSED -> "没有已忽略的周期账单"
                }
                rvBills.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvBills.visibility = View.VISIBLE
                adapter.submitList(bills)
            }
        }
    }
}
