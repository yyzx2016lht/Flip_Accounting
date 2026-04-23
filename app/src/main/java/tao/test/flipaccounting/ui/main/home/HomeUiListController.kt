package tao.test.flipaccounting.ui.main.home

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import tao.test.flipaccounting.MainActivity
import tao.test.flipaccounting.R
import tao.test.flipaccounting.ui.common.StatusBarStyle

internal class HomeUiListController(
    private val fragment: Fragment,
    private val rvTransactions: RecyclerView,
    private val layoutEmptyView: View,
    private val layoutMultiSelectActions: View,
    private val btnMsDelete: View,
    private val swipeRefreshLayout: SwipeRefreshLayout,
    private val cvChartContainer: View,
    private val layoutStickyTopBar: View,
    private val ivBookSwitcher: View,
    private val tvMonthSelector: View,
    private val ivCalendarView: View,
    private val ivSearchBill: View,
    private val layoutHeaderSummary: View,
    private val getHomeAppBar: () -> com.google.android.material.appbar.AppBarLayout?,
    private val getAppBarVerticalOffset: () -> Int,
    private val isBookDrawerOpen: () -> Boolean,
    private val getIsMultiSelectModeActive: () -> Boolean,
    private val setIsMultiSelectModeActive: (Boolean) -> Unit,
    private val getHomeAdapter: () -> HomeAdapter,
    private val setHomeAdapter: (HomeAdapter) -> Unit,
    private val homeViewModel: HomeViewModel,
    private val onShowBillDetailSheet: (tao.test.flipaccounting.data.local.entity.Bill) -> Unit,
    private val onRefreshAccountCurrencyCache: () -> Unit,
) {
    fun setupRecyclerView() {
        val homeAdapter = homeViewModel.adapter
        setHomeAdapter(homeAdapter)

        val lm = LinearLayoutManager(fragment.context)
        lm.initialPrefetchItemCount = 12
        lm.recycleChildrenOnDetach = false
        rvTransactions.setHasFixedSize(false)
        rvTransactions.layoutManager = lm
        rvTransactions.adapter = homeAdapter
        rvTransactions.setItemViewCacheSize(36)
        (fragment.activity as? MainActivity)?.homeRecycledViewPool?.let {
            rvTransactions.setRecycledViewPool(it)
        }
        (rvTransactions.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        rvTransactions.itemAnimator = null

        homeAdapter.onBillItemClick = { bill ->
            onShowBillDetailSheet(bill)
        }

        homeAdapter.onSelectionChanged = { count ->
            if (homeAdapter.isMultiSelectMode) {
                setIsMultiSelectModeActive(true)
                layoutMultiSelectActions.visibility = View.VISIBLE
                (btnMsDelete as TextView).text = if (count > 0) "删除($count)" else "删除"
            } else {
                setIsMultiSelectModeActive(false)
                layoutMultiSelectActions.visibility = View.GONE
            }
            updateHomeFabVisibilityByDrawerState()
        }

        rvTransactions.clearOnScrollListeners()
        rvTransactions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val fab = (fragment.activity as? MainActivity)
                    ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
                if (!fragment.isAdded || !fragment.isVisible || layoutEmptyView.visibility == View.VISIBLE || homeAdapter.itemCount <= 1) {
                    updateHomeFabVisibilityByDrawerState()
                    return
                }
                if (dy > 8) fab.hide()
                else if (dy < -8) updateHomeFabVisibilityByDrawerState()
            }
        })
        homeAdapter.chartView = cvChartContainer
        onRefreshAccountCurrencyCache()

        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            val firstVisible = (rvTransactions.layoutManager as? LinearLayoutManager)
                ?.findFirstCompletelyVisibleItemPosition() ?: RecyclerView.NO_POSITION
            if (firstVisible != 0) return@setOnChildScrollUpCallback true
            if (getAppBarVerticalOffset() != 0) return@setOnChildScrollUpCallback true
            false
        }
    }

    fun setupTopBarDoubleTapToTop() {
        val detector = android.view.GestureDetector(fragment.requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean = true

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                getHomeAppBar()?.setExpanded(true, true)
                (rvTransactions.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                rvTransactions.post { rvTransactions.smoothScrollToPosition(0) }
                return true
            }
        })

        layoutStickyTopBar.setOnTouchListener { _, event ->
            if (isTouchInsideView(event, ivBookSwitcher) ||
                isTouchInsideView(event, tvMonthSelector) ||
                isTouchInsideView(event, ivCalendarView) ||
                isTouchInsideView(event, ivSearchBill)
            ) {
                return@setOnTouchListener false
            }
            detector.onTouchEvent(event)
        }
    }

    fun updateHomeFabVisibilityByDrawerState() {
        val fab = (fragment.activity as? MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (isBookDrawerOpen() || getIsMultiSelectModeActive()) {
            fab.hide()
            return
        }
        fab.show()
        fab.alpha = 1f
        fab.scaleX = 1f
        fab.scaleY = 1f
    }

    fun applyHomeFabDrawerProgress(slideOffset: Float) {
        val fab = (fragment.activity as? MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (getIsMultiSelectModeActive()) return
        val clamped = slideOffset.coerceIn(0f, 1f)
        if (clamped <= 0f) {
            if (layoutEmptyView.visibility != View.VISIBLE) {
                fab.alpha = 1f
                fab.scaleX = 1f
                fab.scaleY = 1f
            }
            return
        }
        fab.show()
        fab.alpha = 1f - clamped
        val scale = 1f - 0.18f * clamped
        fab.scaleX = scale
        fab.scaleY = scale
        if (clamped >= 0.999f) {
            fab.hide()
        }
    }

    fun applyStatusBarForHome() {
        if (!fragment.isAdded) return
        val window = fragment.requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val statusBar = getStatusBarHeight()
        val topLp = layoutStickyTopBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (topLp != null) {
            val targetTop = statusBar
            if (topLp.topMargin != targetTop) {
                topLp.topMargin = targetTop
                layoutStickyTopBar.layoutParams = topLp
            }
        }

        val baseSummaryTop = (76f * fragment.resources.displayMetrics.density).toInt()
        if (layoutHeaderSummary.paddingTop != baseSummaryTop + statusBar) {
            layoutHeaderSummary.setPadding(
                layoutHeaderSummary.paddingLeft,
                baseSummaryTop + statusBar,
                layoutHeaderSummary.paddingRight,
                layoutHeaderSummary.paddingBottom
            )
        }
    }

    fun restoreDefaultStatusBarForOtherTabs() {
        if (!fragment.isAdded) return
        val window = fragment.requireActivity().window
        StatusBarStyle.applyByColor(
            window = window,
            statusBarColor = android.graphics.Color.WHITE,
            decorFitsSystemWindows = false
        )
    }

    fun applyHomeCollapseByScroll(offsetPx: Int) {
        // keep compatibility with old call sites
    }

    private fun getStatusBarHeight(): Int {
        val resId = fragment.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) fragment.resources.getDimensionPixelSize(resId) else 0
    }

    private fun isTouchInsideView(event: android.view.MotionEvent, target: View): Boolean {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + target.width
        val bottom = top + target.height
        return event.rawX in left..right && event.rawY in top..bottom
    }
}
