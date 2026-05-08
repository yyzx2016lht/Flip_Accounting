package tao.test.tapaccounting.ui.main.home

import android.util.Log
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.room.InvalidationTracker
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tao.test.tapaccounting.Prefs
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.data.local.entity.Bill

internal class HomeRefreshController(
    private val fragment: Fragment,
    private val swipeRefreshLayout: SwipeRefreshLayout,
    private val homeViewModel: HomeViewModel,
    private val getSelectedBookName: () -> String,
    private val getSelectedYear: () -> Int,
    private val getSelectedMonth: () -> Int,
    private val getCurrentTimeRange: () -> Int,
    private val getCurrentType: () -> Int,
    private val onUpdateHomeFabVisibility: () -> Unit,
) {
    private data class RefreshSnapshot(
        val count: Int,
        val signature: Long
    )

    private var refreshTimeoutJob: Job? = null
    private var isPullRefreshing = false
    private var pullRefreshBeforeSnapshot: RefreshSnapshot? = null
    private var billsInvalidationObserver: InvalidationTracker.Observer? = null

    fun setupPullToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = true
            isPullRefreshing = true
            pullRefreshBeforeSnapshot = buildRefreshSnapshot(homeViewModel.uiState.value.monthlyBills)
            homeViewModel.forceReload(
                bookName = getSelectedBookName(),
                year = getSelectedYear(),
                month = getSelectedMonth(),
                timeRange = getCurrentTimeRange(),
                type = getCurrentType(),
                isChartHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())
            )

            refreshTimeoutJob?.cancel()
            refreshTimeoutJob = fragment.viewLifecycleOwner.lifecycleScope.launch {
                delay(3500)
                if (fragment.isAdded && swipeRefreshLayout.isRefreshing) {
                    swipeRefreshLayout.isRefreshing = false
                    Log.d("HomePerf", "pull refresh timeout fallback: stop spinner")
                }
            }
            onUpdateHomeFabVisibility()
        }
    }

    fun onStateCollected(monthlyBills: List<Bill>, isLoading: Boolean) {
        swipeRefreshLayout.isRefreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        if (isPullRefreshing && !isLoading) {
            showPullRefreshFeedback(monthlyBills)
        }
    }

    fun resetRefreshState() {
        swipeRefreshLayout.isRefreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        isPullRefreshing = false
        pullRefreshBeforeSnapshot = null
    }

    fun observeBillTableChanges() {
        val db = AppDatabase.getDatabase(fragment.requireContext().applicationContext)
        billsInvalidationObserver?.let { db.invalidationTracker.removeObserver(it) }
        val observer = object : InvalidationTracker.Observer("bills") {
            override fun onInvalidated(tables: Set<String>) {
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!fragment.isAdded) return@launch
                    if (!fragment.viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                    homeViewModel.forceReload(
                        bookName = getSelectedBookName(),
                        year = getSelectedYear(),
                        month = getSelectedMonth(),
                        timeRange = getCurrentTimeRange(),
                        type = getCurrentType(),
                        isChartHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())
                    )
                }
            }
        }
        billsInvalidationObserver = observer
        db.invalidationTracker.addObserver(observer)
    }

    fun onDestroyView() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        val appContext = fragment.context?.applicationContext
        if (appContext != null) {
            val db = AppDatabase.getDatabase(appContext)
            billsInvalidationObserver?.let { observer ->
                db.invalidationTracker.removeObserver(observer)
            }
        }
        billsInvalidationObserver = null
    }

    private fun buildRefreshSnapshot(bills: List<Bill>): RefreshSnapshot {
        var signature = 1125899906842597L
        bills.forEach { bill ->
            signature = signature * 31 + bill.id
            signature = signature * 31 + bill.time
            signature = signature * 31 + bill.type.toLong()
            signature = signature * 31 + bill.subType.toLong()
            signature = signature * 31 + java.lang.Double.doubleToLongBits(bill.amount)
            signature = signature * 31 + java.lang.Double.doubleToLongBits(bill.exchangeRate)
            signature = signature * 31 + bill.categoryName.hashCode().toLong()
            signature = signature * 31 + bill.accountName.hashCode().toLong()
            signature = signature * 31 + bill.remark.hashCode().toLong()
        }
        return RefreshSnapshot(
            count = bills.size,
            signature = signature
        )
    }

    private fun showPullRefreshFeedback(latestBills: List<Bill>) {
        val before = pullRefreshBeforeSnapshot
        val after = buildRefreshSnapshot(latestBills)
        val changed = before == null || before != after
        val message = if (changed) "已同步最新账单" else "已经是最新了"
        Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_SHORT).show()
        isPullRefreshing = false
        pullRefreshBeforeSnapshot = null
    }
}
