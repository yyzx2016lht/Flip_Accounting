package tao.test.flipaccounting.ui.main.home

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.DisplayMetrics
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.room.InvalidationTracker
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.yalantis.ucrop.UCrop
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.CategoryIconPreloader
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.repository.BillRepository
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.activity.EditBillActivity
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import tao.test.flipaccounting.ui.main.YearMonthPickerDialog
import tao.test.flipaccounting.ui.main.SharedYearMonthSession
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

@Suppress("UNCHECKED_CAST")
class HomeFragment : Fragment() {

    private lateinit var barChart: BarChart
    private var roundedBarChartRenderer: RoundedBarChartRenderer? = null
    private lateinit var cvChartContainer: View  // 动态 inflate 的图表卡片，作为 adapter header item
    private lateinit var rvTransactions: RecyclerView
    private lateinit var layoutEmptyView: View
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var billRepository: BillRepository
    private var isMultiSelectModeActive = false

    // 顶部封面区域
    private lateinit var headerBannerLayout: FrameLayout
    private lateinit var ivHeaderBanner: ImageView
    private lateinit var layoutHeaderSummary: View
    private lateinit var layoutStickyTopBar: View
    private var homeAppBar: com.google.android.material.appbar.AppBarLayout? = null
    private var headerExpandedHeightPx: Int = 0
    private var headerCollapsedHeightPx: Int = 0
    private var chartExpandedHeightPx: Int = 0
    private var chartAllowedByState: Boolean = true
    private var currentScrollOffsetPx: Int = 0
    // AppBarLayout 当前垂直偏移量（0=完全展开，负值=已折叠），用于下拉刷新守卫
    private var appBarVerticalOffset: Int = 0

    // 图片选取/裁剪用的临时 Uri
    private val ucropResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val resultUri = UCrop.getOutput(result.data!!) ?: return@registerForActivityResult
                try {
                    val destFile = uriToBookBannerFile(selectedBookName)
                    // UCrop 输出的是 file:// URI，直接用 File 操作，避免 ContentResolver 在
                    // Android 7+ 上无法读取 file:// URI 导致写入失败。
                    val srcFile = resultUri.path?.let { File(it) }
                    if (srcFile != null && srcFile.exists() && srcFile.length() > 0) {
                        srcFile.copyTo(destFile, overwrite = true)
                        saveBannerAndRefresh(destFile)
                    } else {
                        Toast.makeText(requireContext(), "裁剪结果文件不存在，请重试", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "保存封面失败：${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            UCrop.RESULT_ERROR -> {
                val error = UCrop.getError(result.data!!)
                Toast.makeText(requireContext(), "裁剪失败：${error?.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 从相册选图后，启动 UCrop 内置裁剪 */
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        startUCrop(uri)
    }

    private fun saveBannerAndRefresh(destFile: File) {
        BookAccountManager.setBookBannerPath(requireContext(), selectedBookName, destFile.absolutePath)
        updateHeaderBanner()
        Toast.makeText(requireContext(), "封面已更新", Toast.LENGTH_SHORT).show()
    }

    /** 申请读图权限，授权后直接启动图片选取器 */
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickImageLauncher.launch("image/*")
        else Toast.makeText(requireContext(), "需要相册权限才能选择图片", Toast.LENGTH_SHORT).show()
    }

    private lateinit var tvMonthExpense: TextView
    private lateinit var tvMonthExpenseLabel: TextView
    private lateinit var tvMonthIncome: TextView
    private lateinit var tvMonthBalance: TextView
    private lateinit var tvMonthSelector: TextView
    private lateinit var tvChartTotal: TextView
    private lateinit var tvChartTitle: TextView
    private lateinit var vBannerGradient: View
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var ivCalendarView: ImageView
    private lateinit var ivSearchBill: ImageView
    private lateinit var ivBookSwitcher: ImageView
    private lateinit var drawerBooks: DrawerLayout
    private lateinit var layoutBookDrawer: View
    private lateinit var rvBookAccounts: RecyclerView
    private var rvBookAccountsBasePaddingTop: Int = 0
    private var rvBookAccountsBasePaddingBottom: Int = 0
    private lateinit var btnAddBookAccount: View
    private lateinit var layoutAddBookInput: View
    private lateinit var etAddBookAccountName: EditText
    private lateinit var btnConfirmAddBook: View
    private lateinit var btnCancelAddBook: View
    private var bookDrawerBasePaddingBottom: Int = 0
    private lateinit var bookAccountAdapter: BookAccountAdapter
    private var selectedBookName: String = BookAccountManager.DEFAULT_BOOK
    private var availableBookNames: List<String> = BookAccountManager.withAllBookOption(listOf(BookAccountManager.DEFAULT_BOOK))
    // 抽屉关闭动画期间不做重刷新；等 onDrawerClosed 后再切账本
    private var pendingBookSwitchName: String? = null
    // 切账本后首次数据显示时做一次轻量淡入，缓解“突然出现”的突兀感
    private var animateNextBookDataReveal: Boolean = false

    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: View
    private lateinit var btnMsSelectAll: View
    private lateinit var btnMsDelete: View
    private lateinit var btnMsMoveBook: View
    private var multiSelectActionsBaseBottomMargin: Int = 0

    // Settings state
    // range: 0=7d, 1=15d, 2=week
    private var currentTimeRange: Int = 0
    // type: 0=expense, 1=income, 2=both
    private var currentType: Int = 0
    private var isChartHidden: Boolean = false
    // fetchJob 已迁移到 HomeViewModel，Fragment 内不再持有
    // 防止 onViewCreated 之后 onResume 立即重复触发一次加载
    private var skipNextResume: Boolean = false
    private var refreshTimeoutJob: Job? = null
    private var isPullRefreshing = false
    private var pullRefreshBeforeSnapshot: RefreshSnapshot? = null
    private var billsInvalidationObserver: InvalidationTracker.Observer? = null

    /** Activity 作用域 ViewModel，跨 Fragment 重建存活，StateFlow 缓存账单数据 */
    private val homeViewModel: HomeViewModel by activityViewModels()

    // Month picker state
    private var selectedYear: Int = SharedYearMonthSession.getYearMonth().first
    private var selectedMonth: Int = SharedYearMonthSession.getYearMonth().second

    // Optimizations: avoid repeating creation of simple date formats
    private val dfMonth = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val dfChartKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val dfWeekday = SimpleDateFormat("E", Locale.CHINESE)
    private val dfDay = SimpleDateFormat("d", Locale.getDefault())
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val accountCurrencyById = mutableMapOf<Long, String>()
    private val accountCurrencyByName = mutableMapOf<String, String>()

    private data class RefreshSnapshot(
        val count: Int,
        val signature: Long
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d("HomePerf", "=== onViewCreated START ===")

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::drawerBooks.isInitialized && drawerBooks.isDrawerOpen(GravityCompat.START)) {
                    drawerBooks.closeDrawer(GravityCompat.START)
                    return
                }
                if (isMultiSelectModeActive) {
                    homeAdapter.clearSelection()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressed()
                }
            }
        })

        barChart = view.findViewById(R.id.barChart)
        // cvChartContainer 现在通过独立布局动态 inflate，不再通过 fragment_home.xml 的 findViewById
        cvChartContainer = layoutInflater.inflate(R.layout.item_home_chart, null, false)
        barChart = cvChartContainer.findViewById(R.id.barChart)
        tvChartTotal = cvChartContainer.findViewById(R.id.tvChartTotal)
        tvChartTitle = cvChartContainer.findViewById(R.id.tvChartTitle)
        cvChartContainer.findViewById<ImageView>(R.id.ivChartSettings)?.setOnClickListener {
            showChartSettingsDialog()
        }
        rvTransactions = view.findViewById(R.id.rvTransactions)
        layoutEmptyView = view.findViewById(R.id.layoutEmptyView)
        swipeRefreshLayout = view.findViewById(R.id.swipeRefreshLayout)
        drawerBooks = view.findViewById(R.id.drawerBooks)
    layoutBookDrawer = view.findViewById(R.id.layoutBookDrawer)
    bookDrawerBasePaddingBottom = layoutBookDrawer.paddingBottom

        tvMonthExpense = view.findViewById(R.id.tvMonthExpense)
        tvMonthExpenseLabel = view.findViewById(R.id.tvMonthExpenseLabel)
        tvMonthIncome = view.findViewById(R.id.tvMonthIncome)
        tvMonthBalance = view.findViewById(R.id.tvMonthBalance)
        tvMonthSelector = view.findViewById(R.id.tvMonthSelector)
        ivCalendarView = view.findViewById(R.id.ivCalendarView)
        ivSearchBill = view.findViewById(R.id.ivSearchBill)
        ivBookSwitcher = view.findViewById(R.id.ivBookSwitcher)
        // tvChartTotal / tvChartTitle 已在 cvChartContainer inflate 时绑定，此处不再重复
        vBannerGradient = view.findViewById(R.id.vBannerGradient)
        rvBookAccounts = view.findViewById(R.id.rvBookAccounts)
        rvBookAccountsBasePaddingTop = rvBookAccounts.paddingTop
        rvBookAccountsBasePaddingBottom = rvBookAccounts.paddingBottom
        btnAddBookAccount = view.findViewById(R.id.btnAddBookAccount)
        layoutAddBookInput = view.findViewById(R.id.layoutAddBookInput)
        etAddBookAccountName = view.findViewById(R.id.etAddBookAccountName)
        btnConfirmAddBook = view.findViewById(R.id.btnConfirmAddBook)
        btnCancelAddBook = view.findViewById(R.id.btnCancelAddBook)

        layoutMultiSelectActions = view.findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = view.findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = view.findViewById(R.id.btn_ms_select_all)
        btnMsDelete = view.findViewById(R.id.btn_ms_delete)
        btnMsMoveBook = view.findViewById(R.id.btn_ms_move_book)
        multiSelectActionsBaseBottomMargin =
            (layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

        headerBannerLayout = view.findViewById(R.id.headerBannerLayout)
        ivHeaderBanner = view.findViewById(R.id.ivHeaderBanner)
        layoutHeaderSummary = view.findViewById(R.id.layoutHeaderSummary)
        layoutStickyTopBar = view.findViewById(R.id.layoutStickyTopBar)

        // 监听 AppBarLayout 折叠偏移，用于：
        //   1. 保护下拉刷新（完全展开才允许触发）
        //   2. 月份统计区渐隐（layoutHeaderSummary alpha）
        //   3. 折叠后半段 banner 区渐变为纯白背景
    homeAppBar = view.findViewById(R.id.homeAppBar)
    homeAppBar?.addOnOffsetChangedListener(com.google.android.material.appbar.AppBarLayout.OnOffsetChangedListener { appBar, verticalOffset ->
            appBarVerticalOffset = verticalOffset
            val totalScrollRange = appBar.totalScrollRange
            if (totalScrollRange == 0) return@OnOffsetChangedListener
            // verticalOffset 在 [−totalScrollRange, 0] 之间，fraction: 0=完全展开, 1=完全折叠
            val fraction = (-verticalOffset).toFloat() / totalScrollRange.toFloat()

            // 月份统计摘要区：前 50% 折叠内渐隐完
            val summaryAlpha = (1f - fraction * 2f).coerceIn(0f, 1f)
            layoutHeaderSummary.alpha = summaryAlpha

            // Banner 区白色遮罩：后 50% 折叠进度内从透明渐变为纯白
            // 使 banner 图片/颜色在折叠完成时呈现为白底，和账单列表背景融合
            val whiteAlpha = ((fraction - 0.5f) * 2f).coerceIn(0f, 1f)
            val whiteOverlay = (whiteAlpha * 255).toInt()
            headerBannerLayout.foreground = android.graphics.drawable.ColorDrawable(
                android.graphics.Color.argb(whiteOverlay, 255, 255, 255)
            )
            // 同步更新固定顶栏图标/文字颜色：白色遮罩 > 50% 时切换为深色（适配白色背景）
            if (whiteAlpha > 0.5f) {
                applyBannerTextColor(useLightText = false)
            } else {
                // 恢复根据当前账本 banner 决定的颜色
                val bannerPath = BookAccountManager.getBookBannerPath(requireContext(), selectedBookName)
                applyBannerTextColor(useLightText = !bannerPath.isNullOrEmpty() ||
                    run {
                        val c = BookAccountManager.getBookColor(requireContext(), selectedBookName)
                        val lum = 0.299 * android.graphics.Color.red(c) +
                                  0.587 * android.graphics.Color.green(c) +
                                  0.114 * android.graphics.Color.blue(c)
                        lum < 160
                    })
            }
        })

        setupTopBarDoubleTapToTop()

        setupRecyclerView()
        setupChart()
        setupMultiSelectActions()
        setupMultiSelectActionsBottomOffset()
        setupBookDrawer()
    setupBookDrawerImeInsets()
        setupBannerLongPress()

        updateMonthSelectorText()
        tvMonthSelector.setOnClickListener {
            showMonthYearPicker()
        }

        ivBookSwitcher.setOnClickListener {
            drawerBooks.openDrawer(GravityCompat.START)
        }

        ivCalendarView.setOnClickListener {
            // open calendar with selected year/month
            val intent = Intent(requireContext(), CalendarActivity::class.java)
            intent.putExtra("YEAR", selectedYear)
            intent.putExtra("MONTH", selectedMonth)
            intent.putExtra("BOOK_NAME", selectedBookName)
            startActivity(intent)
        }
        ivSearchBill.setOnClickListener {
            val intent = Intent(requireContext(), BillSearchActivity::class.java).apply {
                putExtra(BillSearchActivity.EXTRA_SOURCE_BOOK, selectedBookName)
            }
            startActivity(intent)
        }

        // ivChartSettings 的点击已在 cvChartContainer inflate 时设置，此处无需再设置

        swipeRefreshLayout.setOnRefreshListener {
            // 手动刷新：强制使用当前 UI 参数触发一次新请求，避免沿用旧快照
            swipeRefreshLayout.isRefreshing = true
            isPullRefreshing = true
            pullRefreshBeforeSnapshot = buildRefreshSnapshot(homeViewModel.uiState.value.monthlyBills)
            homeViewModel.forceReload(
                bookName = selectedBookName,
                year = selectedYear,
                month = selectedMonth,
                timeRange = currentTimeRange,
                type = currentType,
                isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
            )

            // 兜底：若某些机型/时序下未及时收到 emission，最多 3.5s 自动结束转圈
            refreshTimeoutJob?.cancel()
            refreshTimeoutJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(3500)
                if (isAdded && swipeRefreshLayout.isRefreshing) {
                    swipeRefreshLayout.isRefreshing = false
                    Log.d("HomePerf", "pull refresh timeout fallback: stop spinner")
                }
            }
            updateHomeFabVisibilityByDrawerState()
        }

        // 立即用 SharedPrefs 缓存同步渲染 Banner，消除首帧闪白（selectedBookName 已从 SharedPrefs 恢复）
        selectedBookName = BookAccountManager.getSelectedBook(requireContext())
        updateHeaderBanner()

        // 每次进入首页时，在后台预热所有分类图标缓存，确保历史账单图标在联网后能正确显示
        lifecycleScope.launch(Dispatchers.IO) {
            CategoryIconPreloader.preloadAll(requireContext().applicationContext)
        }

        // 告知 ViewModel 当前参数（若首次进入或参数有变化则触发查询，否则直接用缓存）
        Log.d("HomePerf", "calling syncAndLoad, current StateFlow bills=${homeViewModel.uiState.value.monthlyBills.size}")
        homeViewModel.syncAndLoad(
            bookName = selectedBookName,
            year = selectedYear,
            month = selectedMonth,
            timeRange = currentTimeRange,
            type = currentType,
            isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
        )

        // 收集 StateFlow：Fragment 重建后立刻收到上次缓存的账单数据，无需等待新的 DB 查询
        val collectStartMs = System.currentTimeMillis()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.uiState.collect { state ->
                    val lag = System.currentTimeMillis() - collectStartMs
                    Log.d("HomePerf", "collect emission: bills=${state.monthlyBills.size}  isLoading=${state.isLoading}  [+${lag}ms since collect registered]")
                    // 同步本地 UI 状态字段（用于 UI 联动，如日历跳转等）
                    selectedBookName = state.selectedBookName
                    selectedYear = state.selectedYear
                    selectedMonth = state.selectedMonth
                    currentTimeRange = state.currentTimeRange
                    currentType = state.currentType
                    isChartHidden = state.isChartHidden
                    updateMonthSelectorText()
                    updateChartTitleLabel()
                    syncTrendCardState()
                    refreshAccountCurrencyCache()

                    val monthlyBills = state.monthlyBills
                    val adapterT0 = System.currentTimeMillis()

                    if (state.isLoading && monthlyBills.isEmpty()) {
                        // 正在切换账本/加载中，且还没有新数据：直接清空列表，不走 DiffUtil
                        homeAdapter.submitList(emptyList())
                        rvTransactions.requestLayout()
                        layoutEmptyView.visibility = View.GONE
                        rvTransactions.visibility = View.VISIBLE
                    } else {
                        val shouldAnimateReveal = animateNextBookDataReveal && !state.isLoading && monthlyBills.isNotEmpty()
                        if (shouldAnimateReveal) {
                            animateNextBookDataReveal = false
                            rvTransactions.alpha = 0.92f
                        }
                        // 直接调用 submitList，Adapter 内部已在子线程计算 DiffUtil 再切回主线程 dispatch，
                        // 不需要额外的 view.post{}，否则多个任务堆积在主线程消息队列会触发连续动画导致 5s+ 触摸冻结
                        homeAdapter.submitList(monthlyBills)
                        if (shouldAnimateReveal) {
                            rvTransactions.animate()
                                .alpha(1f)
                                .setDuration(140L)
                                .setStartDelay(16L)
                                .start()
                        }
                        Log.d("HomePerf", "submitList called: ${monthlyBills.size} bills  [${System.currentTimeMillis() - adapterT0}ms]")
                        rvTransactions.requestLayout()
                        updateSummary(monthlyBills)

                        // 只有当加载完成且真的没有账单时，才显示"暂无账单"
                        if (!state.isLoading && monthlyBills.isEmpty()) {
                            animateNextBookDataReveal = false
                            // 空列表态会隐藏 RecyclerView；若此时 AppBar 处于折叠状态，
                            // 用户无法再通过滚动把横幅拉回展开，因此这里主动复位到展开态。
                            homeAppBar?.setExpanded(true, false)
                            appBarVerticalOffset = 0
                            (rvTransactions.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(0, 0)
                            layoutEmptyView.visibility = View.VISIBLE
                            rvTransactions.visibility = View.GONE
                        } else {
                            layoutEmptyView.visibility = View.GONE
                            rvTransactions.visibility = View.VISIBLE
                        }
                    }

                    val filteredForChart = state.filteredByBook.filter {
                        it.time in state.chartStart..state.chartEnd
                    }
                    updateChart(filteredForChart)

                    swipeRefreshLayout.isRefreshing = false
                    refreshTimeoutJob?.cancel()
                    refreshTimeoutJob = null
                    if (isPullRefreshing && !state.isLoading) {
                        showPullRefreshFeedback(monthlyBills)
                    }

                    if (state.filteredByBook.isEmpty()) {
                        updateHomeFabVisibilityByDrawerState()
                    }
                }
            }
        }

        // 后台刷新账本列表 UI（异步，不阻塞账单加载）
        observeBillTableChanges()
        refreshBookAccounts(reloadTransactions = false)
        skipNextResume = true  // onViewCreated 已触发加载，紧随其后的 onResume 无需重复
        
        // 立刻关闭 loading 圈，显示上次缓存的静态数据，后台无声更新
        swipeRefreshLayout.isRefreshing = false
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        isPullRefreshing = false
        pullRefreshBeforeSnapshot = null
    }

    override fun onResume() {
        super.onResume()
        applyStatusBarForHome()
        updateChartTitleLabel()
        refreshTrendCardVisibility(forceResubmit = true)
        syncDateFromSessionIfNeeded()
        selectedBookName = BookAccountManager.normalizeBookName(
            BookAccountManager.getSelectedBook(requireContext())
        )
        // 先同步渲染已缓存的 Banner，消除从其它 Tab 切回时的闪白
        updateHeaderBanner()
        if (skipNextResume) {
            skipNextResume = false
            return  // 跳过本次，避免与 onViewCreated 的加载重复
        }
        refreshBookAccounts(reloadTransactions = true)
    }

    override fun onPause() {
        super.onPause()
        if (isMultiSelectModeActive) {
            homeAdapter.clearSelection()
        }
        // 注意：不要在 onPause 里切换 decorFitsSystemWindows。
        // 打开设置/分类等新 Activity 时，onPause 也会触发，
        // 此处若切到 decorFits=true 会导致当前窗口根视图瞬时整体下移（用户可见“抖一下”）。
        // 状态栏恢复逻辑改为仅在 Tab 真正隐藏时执行（onHiddenChanged hidden=true）。
    }

    /**
     * MainActivity 使用 hide/show 管理 Tab，切换回账单页时不触发 onResume，
     * 在此处做统计→账单的月份反向同步：
     * - 统计页处于"月模式"时：将账单页日期同步为统计页当前年月
     * - 统计页处于"年模式"时：保留账单页切走时的年月，不修改
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            if (isMultiSelectModeActive) {
                homeAdapter.clearSelection()
            }
            // 切离账单 Tab：仅更新状态栏颜色/图标，不切换 decorFits，
            // 避免下一页（如设置页）首次进入首帧整体下移。
            restoreDefaultStatusBarForOtherTabs()
            return
        }
        if (!hidden) {
            // hide/show 切回账单页时不会触发 onResume，这里补一次 Home 状态栏样式。
            applyStatusBarForHome()
            updateChartTitleLabel()
            refreshTrendCardVisibility(forceResubmit = true)
            syncDateFromSessionIfNeeded()
            selectedBookName = BookAccountManager.normalizeBookName(
                BookAccountManager.getSelectedBook(requireContext())
            )
            // 统计页可能已切换全局账本，这里补一次账本同步与数据刷新。
            refreshBookAccounts(reloadTransactions = true)
            return
        }
    }

    fun isBookDrawerOpen(): Boolean {
        return ::drawerBooks.isInitialized && drawerBooks.isDrawerOpen(GravityCompat.START)
    }

    fun openBookDrawerFromHost() {
        if (::drawerBooks.isInitialized && !drawerBooks.isDrawerOpen(GravityCompat.START)) {
            drawerBooks.openDrawer(GravityCompat.START)
        }
    }

    private fun updateMonthSelectorText() {
        tvMonthSelector.text = "$selectedYear-${String.format(Locale.getDefault(), "%02d", selectedMonth)}"
    }

    private fun showMonthYearPicker() {
        YearMonthPickerDialog.show(
            context = requireContext(),
            title = "选择月份",
            initialYear = selectedYear,
            initialMonth = selectedMonth
        ) { year, month ->
                selectedYear = year
                selectedMonth = month
                updateMonthSelectorText()
                homeViewModel.setMonth(selectedYear, selectedMonth)
        }
    }

    private fun setupBookDrawer() {
        rvBookAccounts.layoutManager = LinearLayoutManager(requireContext())
        bookAccountAdapter = BookAccountAdapter(
            onItemClick = { onBookSelected(it) },
            onRenameClick = { oldName, newName ->
                if (BookAccountManager.normalizeBookName(oldName) == BookAccountManager.ALL_BOOK) {
                    Toast.makeText(requireContext(), "「全部账本」是系统入口，不能重命名", Toast.LENGTH_SHORT).show()
                } else {
                    renameBook(oldName, newName)
                }
            },
            onDeleteClick = { name ->
                if (BookAccountManager.normalizeBookName(name) == BookAccountManager.ALL_BOOK) {
                    Toast.makeText(requireContext(), "「全部账本」是系统入口，不能删除", Toast.LENGTH_SHORT).show()
                } else {
                    deleteBook(name)
                }
            }
        )
        rvBookAccounts.adapter = bookAccountAdapter
        rvBookAccounts.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            adjustBookListBottomPaddingForWholeRows()
        }
        rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }

        btnAddBookAccount.setOnClickListener { showInlineAddBookInput() }
        btnConfirmAddBook.setOnClickListener { commitInlineAddBook() }
        btnCancelAddBook.setOnClickListener { hideInlineAddBookInput(clearText = true) }
        etAddBookAccountName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInlineAddBook()
                true
            } else {
                false
            }
        }

        // 账本总览入口
        view?.findViewById<View>(R.id.btnBookOverview)?.setOnClickListener {
            drawerBooks.closeDrawer(androidx.core.view.GravityCompat.START)
            val intent = android.content.Intent(requireContext(), tao.test.flipaccounting.ui.activity.BookOverviewActivity::class.java)
            intent.putExtra(tao.test.flipaccounting.ui.activity.BookOverviewActivity.EXTRA_CURRENT_BOOK, selectedBookName)
            startActivity(intent)
        }

        drawerBooks.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                // Drawer 开始移动时，立即通知内容区的 NestedScrollView/RecyclerView
                // 放弃它们正在追踪的触摸序列（防止 Drawer 滑动时列表也同时滚动）
                if (slideOffset > 0f) {
                    drawerBooks.getChildAt(0)?.let { content ->
                        val cancel = android.view.MotionEvent.obtain(
                            0, 0, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                        )
                        content.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                applyHomeFabDrawerProgress(slideOffset)
            }

            override fun onDrawerOpened(drawerView: View) {
                bookAccountAdapter.closeSwipeActions()
                updateHomeFabVisibilityByDrawerState()
                adjustBookListBottomPaddingForWholeRows()
                scrollBookListToSelected(animate = true)
            }

            override fun onDrawerClosed(drawerView: View) {
                hideInlineAddBookInput(clearText = true)
                bookAccountAdapter.closeSwipeActions()
                updateHomeFabVisibilityByDrawerState()

                // 关键优化：抽屉完全关闭后再触发数据刷新，避免动画和列表刷新抢同一帧主线程
                pendingBookSwitchName?.let { target ->
                    pendingBookSwitchName = null
                    homeViewModel.syncAndLoad(
                        bookName = target,
                        year = selectedYear,
                        month = selectedMonth,
                        timeRange = currentTimeRange,
                        type = currentType,
                        isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
                    )
                }
            }
        })
    }

    /**
     * 让账本抽屉底部区域在软键盘弹出时自动上移，避免“新增账本”输入框和按钮被遮挡。
     */
    private fun setupBookDrawerImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(layoutBookDrawer) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeExtra = max(0, imeBottom - navBottom)
            v.updatePadding(bottom = bookDrawerBasePaddingBottom + imeExtra)
            rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }
            insets
        }
        ViewCompat.requestApplyInsets(layoutBookDrawer)
    }

    private fun scrollBookListToSelected(animate: Boolean = false) {
        if (!::rvBookAccounts.isInitialized) return
        val layoutManager = rvBookAccounts.layoutManager as? LinearLayoutManager ?: return
        val selectedIndex = availableBookNames.indexOfFirst {
            BookAccountManager.normalizeBookName(it) == selectedBookName
        }
        if (selectedIndex < 0) return

        rvBookAccounts.post {
            if (!isAdded) return@post
            val density = resources.displayMetrics.density
            val estimatedRowHeight = ((60f + 8f) * density).toInt()
            val itemHeight = layoutManager.findViewByPosition(selectedIndex)?.height
                ?: estimatedRowHeight.coerceAtLeast(1)
            val offset = ((rvBookAccounts.height - itemHeight) / 2).coerceAtLeast(0)
            if (!animate) {
                layoutManager.scrollToPositionWithOffset(selectedIndex, offset)
                return@post
            }

            val smoothScroller = object : LinearSmoothScroller(rvBookAccounts.context) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_ANY

                override fun calculateDtToFit(
                    viewStart: Int,
                    viewEnd: Int,
                    boxStart: Int,
                    boxEnd: Int,
                    snapPreference: Int
                ): Int {
                    val viewCenter = (viewStart + viewEnd) / 2
                    val boxCenter = (boxStart + boxEnd) / 2
                    return boxCenter - viewCenter
                }

                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                    // 数值越小速度越快；这里偏慢一点，接近手势滑动观感。
                    return 110f / displayMetrics.densityDpi
                }

                override fun calculateTimeForDeceleration(dx: Int): Int {
                    return (super.calculateTimeForDeceleration(dx) * 1.15f).toInt()
                }
            }
            smoothScroller.targetPosition = selectedIndex
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    /**
     * 根据账本条目高度（item 60dp + 间距 8dp）补齐 RecyclerView 底部内边距，
     * 让初始可视区域尽量呈现整行，避免最后一条被“砍半条”。
     */
    private fun adjustBookListBottomPaddingForWholeRows() {
        if (!::rvBookAccounts.isInitialized) return
        if (!isAdded || context == null || view == null) return
        val available = rvBookAccounts.height
        if (available <= 0) return

        val density = rvBookAccounts.resources.displayMetrics.density
        val rowHeightPx = (60f * density).toInt()
        val rowGapPx = (8f * density).toInt()
        val rowUnitPx = rowHeightPx + rowGapPx
        if (rowUnitPx <= 0) return

        val remainder = available % rowUnitPx
        val topExtra = remainder / 2
        val bottomExtra = remainder - topExtra
        rvBookAccounts.updatePadding(
            top = rvBookAccountsBasePaddingTop + topExtra,
            bottom = rvBookAccountsBasePaddingBottom + bottomExtra
        )
    }

    // ─── 封面图相关 ────────────────────────────────────────────────────────────

    /** 为顶部横幅区域设置长按事件，弹出操作菜单 */
    private fun setupBannerLongPress() {
        headerBannerLayout.setOnLongClickListener {
            dismissKeyboardForDialog()
            val hasBanner = BookAccountManager.getBookBannerPath(requireContext(), selectedBookName) != null
            val dialog = Dialog(requireContext(), R.style.Theme_FlipAccounting)
            val panel = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_delete_followup_picker, null, false)
            val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "「$selectedBookName」外观设置"
            val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)

            fun addOption(label: String, onClick: () -> Unit) {
                val item = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_delete_followup_picker_option, optionsContainer, false)
                item.findViewById<TextView>(R.id.tv_followup_picker_option).text = label
                item.setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
                optionsContainer.addView(item)
            }

            addOption(if (hasBanner) "更换封面图" else "设置封面图") { pickBannerImage() }
            if (hasBanner) {
                addOption("移除封面图") { removeBanner() }
            }
            addOption("修改主题颜色") { showColorPickerDialog() }

            panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).text = "取消"
            panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
            dialog.setContentView(panel)
            dialog.setCanceledOnTouchOutside(true)
            configureDialogWindow(dialog, width = width)
            dialog.show()
            true
        }
    }

    /** 显示预设颜色选择对话框 */
    private fun showColorPickerDialog() {
        dismissKeyboardForDialog()
        val ctx = requireContext()
        val currentColor = BookAccountManager.getBookColor(ctx, selectedBookName)
        val density = resources.displayMetrics.density
        fun px(value: Int): Int = (value * density).toInt()

        // 16 种精心挑选的预设色（名称 + ARGB）
        val colorOptions = listOf(
            "蓝色"   to 0xFF4080FF.toInt(),
            "深蓝"   to 0xFF1A56CC.toInt(),
            "天蓝"   to 0xFF29A8E0.toInt(),
            "青色"   to 0xFF29A8A8.toInt(),
            "绿色"   to 0xFF2FA36B.toInt(),
            "深绿"   to 0xFF1E7A50.toInt(),
            "黄绿"   to 0xFF6BBF40.toInt(),
            "橙色"   to 0xFFE07A30.toInt(),
            "红色"   to 0xFFE05A5A.toInt(),
            "深红"   to 0xFFC0392B.toInt(),
            "粉色"   to 0xFFE0609A.toInt(),
            "紫色"   to 0xFF8A4FD1.toInt(),
            "深紫"   to 0xFF5E3596.toInt(),
            "棕色"   to 0xFF8D5524.toInt(),
            "深灰"   to 0xFF555555.toInt(),
            "炭黑"   to 0xFF222222.toInt(),
        )

        val dialog = Dialog(ctx, R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(ctx).inflate(R.layout.dialog_delete_followup_picker, null, false)
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "选择主题颜色"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)

        colorOptions.forEach { (name, color) ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_delete_option_item)
                minimumHeight = px(52)
                setPadding(px(12), px(10), px(12), px(10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = px(8)
                layoutParams = lp
            }

            val swatch = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(px(20), px(20))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                }
            }
            val nameView = TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = px(10) }
                text = name
                setTextColor(Color.parseColor("#243146"))
                textSize = 14f
            }
            val checkView = TextView(ctx).apply {
                text = if (color == currentColor) "✓" else ""
                setTextColor(Color.parseColor("#1F2937"))
                textSize = 16f
            }

            row.addView(swatch)
            row.addView(nameView)
            row.addView(checkView)
            row.setOnClickListener {
                BookAccountManager.setBookColor(ctx, selectedBookName, color)
                updateHeaderBanner()
                dialog.dismiss()
            }
            optionsContainer.addView(row)
        }

        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).text = "取消"
        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width = width)
        dialog.show()
    }

    /** 检查权限，有权限则直接选图，否则先申请 */
    private fun pickBannerImage() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(requireContext(), permission) == PackageManager.PERMISSION_GRANTED) {
            pickImageLauncher.launch("image/*")
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    /** 移除当前账本的封面图 */
    private fun removeBanner() {
        BookAccountManager.setBookBannerPath(requireContext(), selectedBookName, null)
        updateHeaderBanner()
        Toast.makeText(requireContext(), "已移除封面图", Toast.LENGTH_SHORT).show()
    }

    /**
     * 启动 UCrop 内置裁剪界面（16:9，1280×720 输出）。
     * 完全在 App 内运行，无需调用任何第三方 App，无权限兼容性问题。
     */
    private fun startUCrop(sourceUri: Uri) {
        val ctx = requireContext()
        val destFile = File(ctx.cacheDir, "banner_crop/ucrop_out_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)

        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(90)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setToolbarTitle("裁剪封面图")
            setToolbarColor(
                BookAccountManager.getBookColor(ctx, selectedBookName)
            )
            setStatusBarColor(
                BookAccountManager.getBookColor(ctx, selectedBookName)
            )
            setToolbarWidgetColor(android.graphics.Color.WHITE)
        }

        UCrop.of(sourceUri, destUri)
            .withAspectRatio(16f, 9f)
            .withMaxResultSize(1280, 720)
            .withOptions(options)
            .getIntent(ctx)
            .also { ucropResultLauncher.launch(it) }
    }

    /**
     * 根据账本名返回对应封面图的持久化文件（filesDir/banners/）。
     * 文件名用账本名的 hashCode 保证唯一性。
     */
    private fun uriToBookBannerFile(bookName: String): File {
        val dir = File(requireContext().filesDir, "banners").also { it.mkdirs() }
        return File(dir, "banner_${bookName.hashCode()}.jpg")
    }

    /** 根据当前账本刷新顶部横幅：有图片则显示图片，否则用账本专属颜色 */
    fun updateHeaderBanner() {
        if (!isAdded) return
        val ctx = requireContext()
        val bannerPath = BookAccountManager.getBookBannerPath(ctx, selectedBookName)
        val bookColor = BookAccountManager.getBookColor(ctx, selectedBookName)
        headerBannerLayout.setBackgroundColor(bookColor)
        if (!bannerPath.isNullOrEmpty()) {
            val file = File(bannerPath)
            if (file.exists()) {
                ivHeaderBanner.visibility = View.VISIBLE
                vBannerGradient.visibility = View.VISIBLE
                applyBannerTextColor(useLightText = true)
                Glide.with(this)
                    .load(file)
                    .centerCrop()
                    // File 模型 + 变换场景下，ALL 可能触发 NoResultEncoderAvailableException，
                    // 这里改为 DATA 仅缓存原始数据，避免结果编码失败
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .signature(com.bumptech.glide.signature.ObjectKey(file.lastModified()))
                    .placeholder(ivHeaderBanner.drawable)
                    .error(ivHeaderBanner.drawable)
                    .into(ivHeaderBanner)
                return
            }
        }
        // 无有效图片
        ivHeaderBanner.visibility = View.GONE
        vBannerGradient.visibility = View.GONE
        Glide.with(this).clear(ivHeaderBanner)
        val r = android.graphics.Color.red(bookColor)
        val g = android.graphics.Color.green(bookColor)
        val b = android.graphics.Color.blue(bookColor)
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        applyBannerTextColor(useLightText = luminance < 160)
    }

    /**
     * 统一设置 header 区域内所有文字和图标的颜色。
     * [useLightText] = true → 白色（适合深色/图片背景）
     * [useLightText] = false → 深灰（适合浅色主题色背景）
     */
    private fun applyBannerTextColor(useLightText: Boolean) {
        val primary = if (useLightText) android.graphics.Color.WHITE
                      else android.graphics.Color.parseColor("#1A1A1A")
        val secondary = if (useLightText) 0xCCFFFFFF.toInt()
                        else 0xBB333333.toInt()
        val tintList = android.content.res.ColorStateList.valueOf(primary)

        // 月份选择器文字 + drawableEnd 箭头
        tvMonthSelector.setTextColor(primary)
        tvMonthSelector.compoundDrawablesRelative.forEach { d ->
            d?.mutate()?.setTint(primary)
        }
        // 月支出大字
        tvMonthExpense.setTextColor(primary)
        // 月支出 label
        tvMonthExpenseLabel.setTextColor(secondary)
        // 月收入 / 本月结余
        tvMonthIncome.setTextColor(secondary)
        tvMonthBalance.setTextColor(secondary)
        // 图标
        androidx.core.widget.ImageViewCompat.setImageTintList(ivBookSwitcher, tintList)
        androidx.core.widget.ImageViewCompat.setImageTintList(ivCalendarView, tintList)
        androidx.core.widget.ImageViewCompat.setImageTintList(ivSearchBill, tintList)
    }

    // ──────────────────────────────────────────────────────────────────────────

    private fun showInlineAddBookInput() {
        btnAddBookAccount.visibility = View.GONE
        layoutAddBookInput.visibility = View.VISIBLE
        etAddBookAccountName.setText("")
        etAddBookAccountName.requestFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etAddBookAccountName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideInlineAddBookInput(clearText: Boolean) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etAddBookAccountName.windowToken, 0)
        etAddBookAccountName.clearFocus()
        if (clearText) etAddBookAccountName.setText("")
        layoutAddBookInput.visibility = View.GONE
        btnAddBookAccount.visibility = View.VISIBLE
    }

    private fun commitInlineAddBook() {
        val inputName = etAddBookAccountName.text?.toString()?.trim().orEmpty()
        val newName = BookAccountManager.normalizeBookName(inputName)
        if (newName.isBlank()) {
            etAddBookAccountName.error = "\u540d\u79f0\u4e0d\u80fd\u4e3a\u7a7a"
            return
        }
        if (availableBookNames.any { it == newName }) {
            etAddBookAccountName.error = "\u8d26\u6237\u540d\u5df2\u5b58\u5728"
            return
        }

        if (BookAccountManager.addBookAccount(requireContext(), newName)) {
            selectedBookName = newName
            hideInlineAddBookInput(clearText = true)
            refreshBookAccounts(reloadTransactions = true)
        } else {
            Toast.makeText(requireContext(), "\u65b0\u589e\u5931\u8d25\uff0c\u8bf7\u91cd\u8bd5", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHomeFabVisibilityByDrawerState() {
        val fab = (activity as? tao.test.flipaccounting.MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (isBookDrawerOpen() || isMultiSelectModeActive) {
            fab.hide()
            return
        }
        fab.show()
        fab.alpha = 1f
        fab.scaleX = 1f
        fab.scaleY = 1f
    }

    private fun applyHomeFabDrawerProgress(slideOffset: Float) {
        val fab = (activity as? tao.test.flipaccounting.MainActivity)
            ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
        if (isMultiSelectModeActive) return
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

    private fun syncDateFromSessionIfNeeded() {
        val (sessionYear, sessionMonth) = SharedYearMonthSession.getYearMonth()
        if (sessionYear != selectedYear || sessionMonth != selectedMonth) {
            selectedYear = sessionYear
            selectedMonth = sessionMonth
            homeViewModel.setMonth(sessionYear, sessionMonth)
        }
    }

    private fun refreshBookAccounts(reloadTransactions: Boolean) {
        if (!isAdded) return
        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)

            BookAccountManager.rawAliases(BookAccountManager.DEFAULT_BOOK)
                .filter { it.isNotBlank() && it != BookAccountManager.DEFAULT_BOOK }
                .forEach { alias ->
                    db.billDao().renameBookName(alias, BookAccountManager.DEFAULT_BOOK)
                }

            val dbBooks = db.billDao().getAllBookNames()
            val mergedBooks = BookAccountManager.getBookAccounts(context, dbBooks)
            val selectedFromPrefs = BookAccountManager.getSelectedBook(context, mergedBooks)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                val resolvedSelected = when {
                    mergedBooks.contains(selectedBookName) -> selectedBookName
                    else -> selectedFromPrefs
                }
                val bookChanged = resolvedSelected != selectedBookName
                selectedBookName = resolvedSelected
                // 回写一次，保证 SharedPrefs 与当前 UI 选择一致，避免后续刷新抖动回退
                BookAccountManager.setSelectedBook(requireContext(), selectedBookName)
                availableBookNames = mergedBooks
                bookAccountAdapter.submitList(availableBookNames, selectedBookName)
                if (isBookDrawerOpen()) {
                    scrollBookListToSelected(animate = false)
                }
                // 每次刷新账本列表后都同步横幅（处理新建 Fragment 时 selectedBookName 初始为 DEFAULT_BOOK 的情况）
                updateHeaderBanner()
                // reloadTransactions=true 时按原逻辑加载；
                // reloadTransactions=false（onViewCreated 已提前加载）但账本发生了变化时，也需要重新加载
                val shouldReload = reloadTransactions || bookChanged
                if (shouldReload) {
                    // 必须用 syncAndLoad 而不是 switchBook，完整携带当前 year/month/timeRange/type，
                    // 否则 startFlow 拍快照时 selectedMonth 可能是 ViewModel 里残留的错误值，
                    // 导致筛出 monthly=0 然后把列表错误清空
                    homeViewModel.syncAndLoad(
                        bookName = selectedBookName,
                        year = selectedYear,
                        month = selectedMonth,
                        timeRange = currentTimeRange,
                        type = currentType,
                        isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
                    )
                }
            }
        }
    }

    private fun onBookSelected(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        if (target == selectedBookName) {
            drawerBooks.closeDrawer(GravityCompat.START)
            return
        }
        selectedBookName = target
        animateNextBookDataReveal = true
        BookAccountManager.setSelectedBook(requireContext(), selectedBookName)
        bookAccountAdapter.submitList(availableBookNames, selectedBookName)
        updateHeaderBanner()
        pendingBookSwitchName = selectedBookName
        drawerBooks.closeDrawer(GravityCompat.START)
    }

    private fun renameBook(oldName: String, inputName: String) {
        val oldNorm = BookAccountManager.normalizeBookName(oldName)
        val newNorm = BookAccountManager.normalizeBookName(inputName)
        if (newNorm == oldNorm) return
        if (availableBookNames.any { it == newNorm }) {
            Toast.makeText(requireContext(), "\u8d26\u6237\u540d\u5df2\u5b58\u5728", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)
            BookAccountManager.rawAliases(oldNorm).forEach { alias ->
                db.billDao().renameBookName(alias, newNorm)
                db.chatMessageDao().renameBookName(alias, newNorm)
            }
            val success = BookAccountManager.renameBookAccount(context, oldNorm, newNorm)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (!success) {
                    Toast.makeText(requireContext(), "\u91cd\u547d\u540d\u5931\u8d25", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (selectedBookName == oldNorm) {
                    selectedBookName = newNorm
                }
                BookAccountManager.setSelectedBook(requireContext(), selectedBookName)
                refreshBookAccounts(reloadTransactions = true)
            }
        }
    }

    // ── 删除账本四种策略枚举 ────────────────────────────────────────────────────
    private enum class BookDeleteMode {
        MOVE_TO_OTHER_BOOK,           // 选项1：迁移到其他账本
        REMOVE_FROM_BOOK_KEEP_IN_ALL, // 选项2：账单留在全部账本
        DELETE_BILLS_KEEP_ASSETS,     // 选项3：完全删账单，不回退资产（不推荐）
        DELETE_BILLS_AND_REVERT_ASSETS// 选项4：删账单并回退资产（不推荐）
    }

    private fun deleteBook(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        // 只有"全部账本"不允许删除（已在 onDeleteClick 处拦截，这里做二次防护）
        if (target == BookAccountManager.ALL_BOOK) {
            Toast.makeText(requireContext(), "「全部账本」是系统入口，不能删除", Toast.LENGTH_SHORT).show()
            return
        }

        // 可作为迁移目标的账本：排除「全部账本」和待删除账本本身
        val transferCandidates = availableBookNames
            .map { BookAccountManager.normalizeBookName(it) }
            .filter { it != BookAccountManager.ALL_BOOK && it != target }
            .distinct()

        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val db = AppDatabase.getDatabase(ctx)
            val billCount = BookAccountManager.rawAliases(target).sumOf { alias ->
                db.billDao().countBillsByBookName(alias)
            }
            withContext(Dispatchers.Main) {
                if (billCount == 0) {
                    // 账本下无账单，直接删除账本
                    performDeleteBook(target, BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL)
                } else {
                    showDeleteBookOptions(target, transferCandidates)
                }
            }
        }
    }

    /**
     * 展示删除账本的四个选项弹窗。
     * @param target            待删除的账本名
     * @param transferCandidates 可迁移的目标账本列表（不含「全部账本」和 target 本身）
     */
    private fun showDeleteBookOptions(target: String, transferCandidates: List<String>) {
        dismissKeyboardForDialog()
        data class DeleteOption(
            val title: String,
            val desc: String,
            val highRisk: Boolean = false,
            val onClick: () -> Unit
        )

        val options = listOf(
            DeleteOption(
                title = "迁移到账本后删除",
                desc = "先把账单迁移到其他账本，再删除当前账本",
                onClick = {
                    if (transferCandidates.isEmpty()) {
                        Toast.makeText(requireContext(), "没有可迁移的目标账本", Toast.LENGTH_SHORT).show()
                    } else {
                        showTransferTargetPickerAndDelete(target, transferCandidates)
                    }
                }
            ),
            DeleteOption(
                title = "仅删除账本",
                desc = "账单归档到“全部账本”，不会丢失记录",
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "确认删除账本",
                        message = "删除后，「$target」内账单会归档到「全部账本」。",
                        confirmText = "确认删除",
                        isDanger = false
                    ) {
                            performDeleteBook(target, BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL)
                    }
                }
            ),
            DeleteOption(
                title = "删除账本和账单",
                desc = "删除账单，但不回退资产余额",
                highRisk = true,
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "高风险操作确认",
                        message = "将永久删除「$target」内所有账单，且不会回退资产余额。",
                        confirmText = "仍要删除",
                        isDanger = true
                    ) {
                            performDeleteBook(target, BookDeleteMode.DELETE_BILLS_KEEP_ASSETS)
                    }
                }
            ),
            DeleteOption(
                title = "删除账本并回退资产",
                desc = "删除账单并回退相关资产余额",
                highRisk = true,
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "高风险操作确认",
                        message = "将删除「$target」内所有账单并回退资产余额，此操作不可撤销。",
                        confirmText = "仍要删除",
                        isDanger = true
                    ) {
                            performDeleteBook(target, BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS)
                    }
                }
            )
        )

        val panel = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_book_delete_options, null, false)
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "删除账本「$target」"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "请选择删除方式"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val popupDialog = Dialog(requireContext(), R.style.Theme_FlipAccounting)
        val targetWidth = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        options.forEach { opt ->
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = opt.title
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = opt.desc
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility =
                if (opt.highRisk) View.VISIBLE else View.GONE
            item.setOnClickListener {
                popupDialog.dismiss()
                opt.onClick()
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { popupDialog.dismiss() }

        popupDialog.setContentView(panel)
        popupDialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(popupDialog, width = targetWidth)
        popupDialog.show()
    }

    private fun dismissKeyboardForDialog() {
        val act = activity ?: return
        val focus = act.currentFocus ?: return
        focus.clearFocus()
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(focus.windowToken, 0)
    }

    private fun applyDialogMotion(dialog: Dialog) {
        fun applyWindowStyle() {
            dialog.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.decorView?.fitsSystemWindows = false
                window.setWindowAnimations(R.style.Animation_FlipAccounting_DialogSoft)
            }
        }
        dialog.setOnShowListener { applyWindowStyle() }
        applyWindowStyle()
    }

    private fun configureDialogWindow(dialog: Dialog, width: Int, dimAmount: Float = 0.34f) {
        fun applyWindowStyle() {
            dialog.window?.apply {
                WindowCompat.setDecorFitsSystemWindows(this, false)
                decorView?.fitsSystemWindows = false
                setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                )
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setGravity(Gravity.CENTER)
                setDimAmount(dimAmount)
                setWindowAnimations(R.style.Animation_FlipAccounting_DialogSoft)
                attributes = attributes.apply {
                    this.width = width
                    this.height = ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
        }
        dialog.setOnShowListener { applyWindowStyle() }
        applyWindowStyle()
    }

    /** 让用户从 [transferCandidates] 中选一个目标账本，再执行迁移+删除 */
    private fun showTransferTargetPickerAndDelete(target: String, transferCandidates: List<String>) {
        dismissKeyboardForDialog()
        val dialog = Dialog(requireContext(), R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_delete_followup_picker, null, false)
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "选择迁移目标"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)
        transferCandidates.forEach { candidate ->
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_delete_followup_picker_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_followup_picker_option).text = "迁移到「$candidate」"
            item.setOnClickListener {
                dialog.dismiss()
                showDeleteFollowupConfirmDialog(
                    title = "确认迁移并删除",
                    message = "删除后，「$target」账本内的所有账单将迁移到「$candidate」。",
                    confirmText = "迁移并删除",
                    isDanger = false
                ) {
                    performDeleteBook(
                        target = target,
                        mode = BookDeleteMode.MOVE_TO_OTHER_BOOK,
                        transferToBook = candidate
                    )
                }
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width = width)
        dialog.show()
    }

    private fun showDeleteFollowupConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        isDanger: Boolean,
        onConfirm: () -> Unit
    ) {
        dismissKeyboardForDialog()
        val dialog = Dialog(requireContext(), R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_delete_followup_confirm, null, false)
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message
        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width = width)
        dialog.show()
    }

    /** 执行删除账本的核心逻辑，根据 [mode] 对账单做不同处理 */
    private fun performDeleteBook(
        target: String,
        mode: BookDeleteMode,
        transferToBook: String? = null
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ctx = requireContext().applicationContext
            val db = AppDatabase.getDatabase(ctx)
            val aliases = BookAccountManager.rawAliases(target).toSet()

            when (mode) {
                BookDeleteMode.MOVE_TO_OTHER_BOOK -> {
                    val destination = transferToBook?.let { BookAccountManager.normalizeBookName(it) }
                    if (destination.isNullOrBlank() || destination == target || destination == BookAccountManager.ALL_BOOK) {
                        withContext(Dispatchers.Main) {
                            if (isAdded) Toast.makeText(requireContext(), "迁移目标无效", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    aliases.forEach { alias ->
                        db.billDao().renameBookName(alias, destination)
                        db.chatMessageDao().renameBookName(alias, destination)
                    }
                }
                BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL -> {
                    // 将账单的 bookName 置为 ALL_BOOK，使其仅归属于「全部账本」而不属于任何小账本
                    aliases.forEach { alias ->
                        db.billDao().renameBookName(alias, BookAccountManager.ALL_BOOK)
                        db.chatMessageDao().renameBookName(alias, BookAccountManager.ALL_BOOK)
                    }
                }
                BookDeleteMode.DELETE_BILLS_KEEP_ASSETS -> {
                    aliases.forEach { alias ->
                        db.billDao().deleteAllByBookName(alias)
                        db.chatMessageDao().deleteAllByBookName(alias)
                    }
                }
                BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS -> {
                    db.billDao().backfillAssetLinksByName()
                    db.billDao().getBillsByBookNamesList(aliases.toList())
                        .forEach { tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, it) }
                    aliases.forEach { alias ->
                        db.chatMessageDao().deleteAllByBookName(alias)
                    }
                }
            }

            // 计算删除后的 fallback 选中账本
            val fallback = transferToBook
                ?.let { BookAccountManager.normalizeBookName(it) }
                ?.takeIf { it != BookAccountManager.ALL_BOOK }
                ?: availableBookNames
                    .map { BookAccountManager.normalizeBookName(it) }
                    .firstOrNull { it != BookAccountManager.ALL_BOOK && it != target }

            val removed = BookAccountManager.removeBookAccount(ctx, target, fallback)

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (!removed) {
                    Toast.makeText(requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (selectedBookName == target) {
                    selectedBookName = fallback ?: BookAccountManager.ALL_BOOK
                }
                BookAccountManager.setSelectedBook(requireContext(), selectedBookName)
                refreshBookAccounts(reloadTransactions = true)
                val tip = when (mode) {
                    BookDeleteMode.MOVE_TO_OTHER_BOOK -> "已删除账本，账单已迁移到「$transferToBook」"
                    BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL -> "已删除账本，账单已归档到「全部账本」"
                    BookDeleteMode.DELETE_BILLS_KEEP_ASSETS -> "已删除账本与所有账单（未回退资产）"
                    BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS -> "已删除账本与所有账单，并回退资产"
                }
                Toast.makeText(requireContext(), tip, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener {
            homeAdapter.clearSelection()
        }
        btnMsSelectAll.setOnClickListener {
            val allCount = homeAdapter.items.count { it is HomeAdapter.ListItem.Item }
            if (homeAdapter.selectedBills.size >= allCount && allCount > 0) {
                homeAdapter.clearSelection()
            } else {
                homeAdapter.selectAll()
            }
        }
        btnMsMoveBook.setOnClickListener {
            val billsToMove = homeAdapter.selectedBills.toList()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = homeAdapter.selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            val db = AppDatabase.getDatabase(requireContext())
            lifecycleScope.launch {
                // 必须通过 BillDeleteHelper 批量删除，才能正确回退资产余额、恢复退款关联支出
                tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)

                homeAdapter.clearSelection()
                Toast.makeText(context, "\u5df2\u5220\u9664 ${billsToDelete.size} \u6761\u8d26\u5355", Toast.LENGTH_SHORT).show()
                // Room Flow will refresh UI automatically
            }
        }
    }

    private fun setupMultiSelectActionsBottomOffset() {
        val hostActivity = activity ?: return
        val bottomNav = hostActivity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        val fallbackNavHeight = (56f * resources.displayMetrics.density).toInt()

        fun applyOffset() {
            val lp = layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val navHeight = bottomNav?.height ?: 0
            val navExtra = (navHeight - fallbackNavHeight).coerceAtLeast(0)
            val targetBottom = multiSelectActionsBaseBottomMargin + navExtra
            if (lp.bottomMargin != targetBottom) {
                lp.bottomMargin = targetBottom
                layoutMultiSelectActions.layoutParams = lp
            }
        }

        bottomNav?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyOffset()
        }
        layoutMultiSelectActions.post { applyOffset() }
    }

    /** 弹出账本选择对话框，将选中账单批量移动到目标账本 */
    private fun showMoveToBookDialog(bills: List<Bill>) {
        dismissKeyboardForDialog()
        val dialog = Dialog(requireContext(), R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_book_delete_options, null, false)
        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "移动到账本"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "选择目标账本"
        val optionsScroll = panel.findViewById<ScrollView>(R.id.scroll_delete_book_options)
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)

        availableBookNames.forEach { targetBook ->
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = targetBook
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = "将所选账单迁移到该账本"
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE
            item.setOnClickListener {
                val normalized = BookAccountManager.normalizeBookName(targetBook)
                val allSameBook = bills.all {
                    BookAccountManager.normalizeBookName(it.bookName) == normalized
                }
                if (allSameBook) {
                    Toast.makeText(
                        requireContext(),
                        "账单已在「$targetBook」中，无需转移",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                val ids = bills.map { it.id }
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext())
                    db.billDao().moveBillsToBook(ids, targetBook)
                    withContext(Dispatchers.Main) {
                        homeAdapter.clearSelection()
                        Toast.makeText(
                            requireContext(),
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
        val targetHeight = min(maxHeight, estimatedContentHeight)
        optionsScroll.layoutParams = optionsScroll.layoutParams.apply { height = targetHeight }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width = width)
        dialog.show()
    }

    private fun setupRecyclerView() {
        // 直接使用 ViewModel 里持久保存的 adapter，Fragment 重建后 items 数据仍在，
        // DiffUtil 比对无变化时跳过所有 bind/layout，消除首帧渲染卡顿
        homeAdapter = homeViewModel.adapter
        val lm = LinearLayoutManager(context)
        lm.initialPrefetchItemCount = 12
        // 在 Drawer hide/show + 快速切页场景下，recycleChildrenOnDetach 可能导致可见区域重建抖动
        lm.recycleChildrenOnDetach = false
        // 关键：RecyclerView 位于 NestedScrollView 内且高度为 wrap_content，
        // 不能开启 fixedSize，否则数据骤变（如 0 -> 140）后高度可能不重算，出现“下半部分不显示/不可触摸”。
        rvTransactions.setHasFixedSize(false)
        rvTransactions.layoutManager = lm
        rvTransactions.adapter = homeAdapter
        // 增加本地缓存，减轻账本切换后首屏 bind 压力（以内存换流畅）
        rvTransactions.setItemViewCacheSize(36)
        // 接入 Activity 级别的共享 ViewHolder 缓存池，Fragment 重建后直接复用，跳过 inflate
        (activity as? tao.test.flipaccounting.MainActivity)?.homeRecycledViewPool?.let {
            rvTransactions.setRecycledViewPool(it)
        }
        (rvTransactions.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        rvTransactions.itemAnimator = null  // 禁用所有 item 动画，消除掉帧源头

        // Fragment 实例已重建，重新绑定回调（adapter 本身复用，但 Fragment 引用变了）
        homeAdapter.onBillItemClick = { bill ->
            showBillDetailSheet(bill)
        }

        homeAdapter.onSelectionChanged = { count ->
            if (homeAdapter.isMultiSelectMode) {
                isMultiSelectModeActive = true
                layoutMultiSelectActions.visibility = View.VISIBLE
                (btnMsDelete as TextView).text = if (count > 0) "\u5220\u9664($count)" else "\u5220\u9664"
            } else {
                isMultiSelectModeActive = false
                layoutMultiSelectActions.visibility = View.GONE
            }
            updateHomeFabVisibilityByDrawerState()
        }

        rvTransactions.clearOnScrollListeners()

        // 上滑隐藏 FAB，下滑显示 FAB
        rvTransactions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val fab = (activity as? tao.test.flipaccounting.MainActivity)
                    ?.findViewById<FloatingActionButton>(R.id.fab_add) ?: return
                if (!isAdded || !isVisible || layoutEmptyView.visibility == View.VISIBLE || homeAdapter.itemCount <= 1) {
                    updateHomeFabVisibilityByDrawerState()
                    return
                }
                if (dy > 8) fab.hide()
                else if (dy < -8) updateHomeFabVisibilityByDrawerState()
            }
        })
        homeAdapter.chartView = cvChartContainer
        refreshAccountCurrencyCache()

        // 只有当 RecyclerView 滚动到最顶部（第一项完全可见）且 AppBar 完全展开时，才允许触发下拉刷新
        swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            val firstVisible = (rvTransactions.layoutManager as? LinearLayoutManager)
                ?.findFirstCompletelyVisibleItemPosition() ?: RecyclerView.NO_POSITION
            // 未在顶部 → 拦截，不触发刷新
            if (firstVisible != 0) return@setOnChildScrollUpCallback true
            // AppBar 未完全展开也阻止下拉（让 AppBar 先展开）
            if (appBarVerticalOffset != 0) return@setOnChildScrollUpCallback true
            false  // 允许触发下拉刷新
        }
    }

    private fun setupTopBarDoubleTapToTop() {
        val detector = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent): Boolean {
                // 必须返回 true，GestureDetector 才会继续识别双击
                return true
            }

            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                // 双击固定顶栏空白区：回到页面绝对顶部（含背景横幅）
                homeAppBar?.setExpanded(true, true)
                (rvTransactions.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
                rvTransactions.post { rvTransactions.smoothScrollToPosition(0) }
                return true
            }
        })

        layoutStickyTopBar.setOnTouchListener { _, event ->
            // 点击在顶栏子控件上（账本按钮、月份选择、日历）时，不拦截，交给子控件处理
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

    private fun isTouchInsideView(event: android.view.MotionEvent, target: View): Boolean {
        val loc = IntArray(2)
        target.getLocationOnScreen(loc)
        val left = loc[0].toFloat()
        val top = loc[1].toFloat()
        val right = left + target.width
        val bottom = top + target.height
        return event.rawX in left..right && event.rawY in top..bottom
    }

    private fun applyHomeCollapseByScroll(offsetPx: Int) {
        // 已迁移为 AppBarLayout 原生折叠行为，保留空实现避免旧调用点崩溃。
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    private fun applyStatusBarForHome() {
        if (!isAdded) return
        val window = requireActivity().window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val useDarkIcons = false
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = useDarkIcons

        val statusBar = getStatusBarHeight()
        // 固定顶栏（layoutStickyTopBar）需要顶部留出状态栏高度
        val topLp = layoutStickyTopBar.layoutParams as? ViewGroup.MarginLayoutParams
        if (topLp != null) {
            val targetTop = statusBar
            if (topLp.topMargin != targetTop) {
                topLp.topMargin = targetTop
                layoutStickyTopBar.layoutParams = topLp
            }
        }

        // 摘要区也要吃状态栏高度，避免和顶部月份栏文字堆叠
        val baseSummaryTop = (76f * resources.displayMetrics.density).toInt()
        val summary = layoutHeaderSummary
        if (summary.paddingTop != baseSummaryTop + statusBar) {
            summary.setPadding(
                summary.paddingLeft,
                baseSummaryTop + statusBar,
                summary.paddingRight,
                summary.paddingBottom
            )
        }
    }

    private fun restoreDefaultStatusBarForOtherTabs() {
        if (!isAdded) return
        val window = requireActivity().window
        // 保持 decorFits=false，避免切 Tab 时导致窗口根视图整体位移。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#F5F7FA")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
    }

    private fun setupChart() {
        barChart.apply {
            description.isEnabled = false
            setDrawGridBackground(false)
            setDrawBorders(false)
            setScaleEnabled(false) // disable zoom
            isDoubleTapToZoomEnabled = false
            setTouchEnabled(false) // disable all touch/click on bars
            isHighlightPerTapEnabled = false
            isHighlightPerDragEnabled = false
            setNoDataText("\u6682\u65e0\u56fe\u8868\u6570\u636e")
            setNoDataTextColor(Color.parseColor("#9AA0A6"))

            axisLeft.axisMinimum = 0f
            axisLeft.setDrawGridLines(false)
            axisLeft.setDrawLabels(false) // hide left labels
            axisLeft.setDrawAxisLine(false)
            axisRight.isEnabled = false

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.setDrawGridLines(false)
            xAxis.setDrawAxisLine(false)
            xAxis.granularity = 1f

            legend.isEnabled = false // hide legend
            // Use a renderer instance we can update later (toggle fullRound)
            val renderer = RoundedBarChartRenderer(this, animator, viewPortHandler)
            roundedBarChartRenderer = renderer
            setRenderer(renderer)
        }
    }

    private fun updateChartTitleLabel() {
        val typeStr = when (currentType) {
            2 -> "\u6536\u652f"
            1 -> "\u6536\u5165"
            else -> "\u652f\u51fa"
        }
        val rangeStr = when (currentTimeRange) {
            0 -> "\u6700\u8fd17\u65e5"
            1 -> "\u6700\u8fd115\u65e5"
            2 -> "\u672c\u5468"
            else -> ""
        }
        tvChartTitle.text = "$rangeStr$typeStr"
        return

        // hide chart when disabled or not current month
        val showByGlobalSwitch = Prefs.isShowHomeTrendCard(requireContext())
        val isCurrentMonth = selectedYear == Calendar.getInstance().get(Calendar.YEAR) &&
                             selectedMonth == (Calendar.getInstance().get(Calendar.MONTH) + 1)
        isChartHidden = !showByGlobalSwitch

        val shouldShow = showByGlobalSwitch && isCurrentMonth
        chartAllowedByState = shouldShow

        // 图表卡片现在是 RecyclerView 的第一个 adapter item：
        // 更新 adapter.showChart 标志后重新 submitList，让 DiffUtil 自动插入/删除该 item
        if (homeAdapter.showChart != shouldShow) {
            homeAdapter.showChart = shouldShow
            homeAdapter.submitList(homeViewModel.uiState.value.monthlyBills)
        }
    }

    private fun syncTrendCardState(): Boolean {
        val showByGlobalSwitch = Prefs.isShowHomeTrendCard(requireContext())
        val isCurrentMonth = selectedYear == Calendar.getInstance().get(Calendar.YEAR) &&
            selectedMonth == (Calendar.getInstance().get(Calendar.MONTH) + 1)
        isChartHidden = !showByGlobalSwitch

        val shouldShow = showByGlobalSwitch && isCurrentMonth
        chartAllowedByState = shouldShow
        val changed = homeAdapter.showChart != shouldShow
        homeAdapter.showChart = shouldShow
        return changed
    }

    private fun refreshTrendCardVisibility(forceResubmit: Boolean = false) {
        val changed = syncTrendCardState()
        if (forceResubmit || changed) {
            homeAdapter.submitList(homeViewModel.uiState.value.monthlyBills)
        }
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
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        isPullRefreshing = false
        pullRefreshBeforeSnapshot = null
    }

    private fun showChartSettingsDialog() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_chart_settings_bottom_sheet, null)
        dialog.setContentView(view)

        var tempType = currentType
        if (tempType == 1) tempType = 2 // merge income into both option
        var tempRange = currentTimeRange
        var tempHidden = !Prefs.isShowHomeTrendCard(requireContext())

        val tvTypeExpense = view.findViewById<TextView>(R.id.tvTypeExpense)
        val tvTypeBoth = view.findViewById<TextView>(R.id.tvTypeBoth)

        val tvRangeWeek = view.findViewById<TextView>(R.id.tvRangeWeek)
        val tvRange7d = view.findViewById<TextView>(R.id.tvRange7d)
        val tvRange15d = view.findViewById<TextView>(R.id.tvRange15d)
        val tvRangeHide = view.findViewById<TextView>(R.id.tvRangeHide)
        val btnConfirm = view.findViewById<TextView>(R.id.btnConfirmSettings)

        fun updateTypeBg() {
            tvTypeExpense.setBackgroundResource(if (tempType == 0) R.drawable.bg_segmented_selected else 0)
            tvTypeExpense.elevation = 0f
            tvTypeBoth.setBackgroundResource(if (tempType == 2) R.drawable.bg_segmented_selected else 0)
            tvTypeBoth.elevation = 0f
        }

        fun updateRangeBg() {
            tvRangeWeek.setBackgroundResource(if (!tempHidden && tempRange == 2) R.drawable.bg_segmented_selected else 0)
            tvRangeWeek.elevation = 0f
            tvRange7d.setBackgroundResource(if (!tempHidden && tempRange == 0) R.drawable.bg_segmented_selected else 0)
            tvRange7d.elevation = 0f
            tvRange15d.setBackgroundResource(if (!tempHidden && tempRange == 1) R.drawable.bg_segmented_selected else 0)
            tvRange15d.elevation = 0f
            tvRangeHide.setBackgroundResource(if (tempHidden) R.drawable.bg_segmented_selected else 0)
            tvRangeHide.elevation = 0f
        }

        tvTypeExpense.setOnClickListener {
            tempType = 0
            updateTypeBg()
        }
        tvTypeBoth.setOnClickListener {
            tempType = 2
            updateTypeBg()
        }

        tvRangeWeek.setOnClickListener {
            tempRange = 2
            tempHidden = false
            updateRangeBg()
        }
        tvRange7d.setOnClickListener {
            tempRange = 0
            tempHidden = false
            updateRangeBg()
        }
        tvRange15d.setOnClickListener {
            tempRange = 1
            tempHidden = false
            updateRangeBg()
        }
        tvRangeHide.setOnClickListener {
            tempHidden = true
            updateRangeBg()
        }

        updateTypeBg()
        updateRangeBg()

        btnConfirm.setOnClickListener {
            currentType = tempType
            currentTimeRange = tempRange
            isChartHidden = tempHidden
            Prefs.setShowHomeTrendCard(requireContext(), !tempHidden)
            updateChartTitleLabel()
            refreshTrendCardVisibility(forceResubmit = true)
            homeViewModel.setChartSettings(currentTimeRange, currentType, isChartHidden)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateSummary(transactions: List<Bill>) {
        var expense = 0.0
        var income = 0.0

        transactions.forEach {
            if (it.subType == Bill.SUBTYPE_REFUND) return@forEach
            // 折算为人民币等值（与柱状图保持一致）
            val amountCny = it.amount * it.exchangeRate
            if (it.type == Bill.TYPE_EXPENSE) expense += amountCny
            else if (it.type == Bill.TYPE_INCOME) income += amountCny
        }

        // 禁用动画，直接设置值，避免掉帧
        tvMonthExpense.text = "¥${String.format(Locale.getDefault(), "%.2f", expense)}"
        tvMonthIncome.text = "月收入 ¥${String.format(Locale.getDefault(), "%.2f", income)}"
        tvMonthBalance.text = "本月结余 ¥${String.format(Locale.getDefault(), "%.2f", income - expense)}"
    }

    private fun getStartTimeFromRange(rangeOpt: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        when (rangeOpt) {
            0 -> { // 7 days
                cal.add(Calendar.DAY_OF_YEAR, -6)
            }
            1 -> { // 15 days
                cal.add(Calendar.DAY_OF_YEAR, -14)
            }
            2 -> { // Week
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            }
        }
        return cal.timeInMillis
    }

    private fun getEndTimeFromRange(rangeOpt: Int): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)

        when (rangeOpt) {
            0 -> { // 7 days
                // Ends today
            }
            1 -> { // 15 days
                // Ends today
            }
            2 -> { // Week
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
            }
        }
        return cal.timeInMillis
    }

    private fun updateChart(transactions: List<Bill>) {
        val chartT0 = System.currentTimeMillis()
        val startDateMs = getStartTimeFromRange(currentTimeRange)
        val endDateMs = getEndTimeFromRange(currentTimeRange)

        val dates = mutableListOf<String>()
        val displayLabels = mutableListOf<String>()

        val currCal = Calendar.getInstance().apply { timeInMillis = startDateMs }
        val endCal = Calendar.getInstance().apply { timeInMillis = endDateMs }

        while (currCal.timeInMillis <= endCal.timeInMillis) {
            val dStr = dfChartKey.format(currCal.time)
            if (!dates.contains(dStr)) {
                dates.add(dStr)

                val label = when (currentTimeRange) {
                    1 -> { // 15 days
                        dfDay.format(currCal.time) // just the day, e.g., "15"
                    }
                    0, 2 -> { // 7 days or this week
                        dfWeekday.format(currCal.time) // weekday
                    }
                    else -> {
                        dfDay.format(currCal.time)
                    }
                }
                displayLabels.add(label)
            }
            currCal.add(Calendar.DAY_OF_YEAR, 1)
        }

        val expenseMap = mutableMapOf<String, Float>()
        val incomeMap = mutableMapOf<String, Float>()

        var totalExpense = 0f
        var totalIncome = 0f

        for (t in transactions) {
            if (t.subType == Bill.SUBTYPE_REFUND) continue
            val dStr = dfChartKey.format(Date(t.time))
            // 折算为人民币等值再绘制柱状图
            val amount = (t.amount * t.exchangeRate).toFloat()
            if (t.type == Bill.TYPE_EXPENSE) { // Expense
                expenseMap[dStr] = (expenseMap[dStr] ?: 0f) + amount
                totalExpense += amount
            } else if (t.type == Bill.TYPE_INCOME) { // Income
                incomeMap[dStr] = (incomeMap[dStr] ?: 0f) + amount
                totalIncome += amount
            }
        }

        tvChartTotal.text = when (currentType) {
            2 -> "\u6536\u5165: ${String.format(Locale.getDefault(), "%,.2f", totalIncome)}, \u652f\u51fa: ${String.format(Locale.getDefault(), "%,.2f", totalExpense)}"
            1 -> "\u603b\u8ba1: ${String.format(Locale.getDefault(), "%,.2f", totalIncome)}"
            else -> "\u603b\u8ba1: ${String.format(Locale.getDefault(), "%,.2f", totalExpense)}"
        }

        val expenseEntries = mutableListOf<BarEntry>()
        val incomeEntries = mutableListOf<BarEntry>()

        dates.forEachIndexed { index, dStr ->
            expenseEntries.add(BarEntry(index.toFloat(), expenseMap[dStr] ?: 0f))
            incomeEntries.add(BarEntry(index.toFloat(), incomeMap[dStr] ?: 0f))
        }

        barChart.xAxis.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                val idx = value.toInt()
                if (idx >= 0 && idx < displayLabels.size) return displayLabels[idx]
                return ""
            }
        }

        barChart.xAxis.setLabelCount(displayLabels.size, false)
        barChart.xAxis.textColor = requireContext().getColor(android.R.color.darker_gray)

        val dataSets = mutableListOf<BarDataSet>()

        val formatterK = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                if (value == 0f) return ""
                if (value >= 1000) {
                    val k = value / 1000f
                    val s = String.format(Locale.getDefault(), "%.2fK", k)
                    return if (s.endsWith("0K")) s.replace(".00K", "K").replace("0K", "K") else s
                }
                val s = String.format(Locale.getDefault(), "%.2f", value)
                return if (s.endsWith(".00")) s.replace(".00", "")
                       else if (s.endsWith("0")) s.substring(0, s.length - 1)
                       else s
            }
        }

        // currentType: 0=expense, 1=income, 2=both
        // Hide value labels in both mode; additionally hide in 15-day mode.
        val shouldDrawValues = (currentType != 2) && (currentTimeRange != 1)

        if (currentType == 0 || currentType == 2) {
            val setExpense = BarDataSet(expenseEntries, "\u652f\u51fa").apply {
                color = android.graphics.Color.parseColor("#FF5252") // expense
                setDrawValues(shouldDrawValues) // draw values by mode
                valueTextSize = 11f
                valueTextColor = android.graphics.Color.parseColor("#FF5252")
                valueFormatter = formatterK
            }
            dataSets.add(setExpense)
        }

        if (currentType == 1 || currentType == 2) {
            val setIncome = BarDataSet(incomeEntries, "\u6536\u5165").apply {
                color = android.graphics.Color.parseColor("#4CAF50") // income
                setDrawValues(shouldDrawValues) // draw values by mode
                valueTextSize = 11f
                valueTextColor = android.graphics.Color.parseColor("#4CAF50")
                valueFormatter = formatterK
            }
            dataSets.add(setIncome)
        }

        val barData = BarData(dataSets.toList() as List<com.github.mikephil.charting.interfaces.datasets.IBarDataSet>)

        // must set data before groupBars()
        barChart.data = barData

        // Set renderer rounding style based on time range: 7-day and 本周 -> capsule (fullRound=true),
        // 15-day -> top-rounded only (fullRound=false), other ranges keep top-rounded.
        roundedBarChartRenderer?.fullRound = (currentTimeRange == 0 || currentTimeRange == 2)

        if (currentType == 2) {
            // make bars thinner; for 7-day (currentTimeRange==0) use an extra-thin style
            // (barWidth + barSpace) * 2 + groupSpace = 1.0
            val groupSpace: Float
            val barSpace: Float
            val barWidth: Float

            if (currentTimeRange == 0 || currentTimeRange == 2) {
                // 7-day: extra thin bars
                // choose barWidth = 0.15, barSpace = 0.25, groupSpace = 0.20 -> (0.15+0.25)*2 + 0.20 = 1.0
                groupSpace = 0.2f
                barSpace = 0.25f
                barWidth = 0.15f
            } else {
                // other ranges: moderately thin
                groupSpace = 0.2f
                barSpace = 0.2f
                barWidth = 0.2f
            }

            barData.barWidth = barWidth

            barChart.xAxis.setCenterAxisLabels(true) // center labels by group
            barChart.groupBars(0f, groupSpace, barSpace)

            barChart.xAxis.axisMinimum = 0f
            barChart.xAxis.axisMaximum = dates.size.toFloat() // each group takes 1 unit
        } else {
            // single bar mode: make single bars a bit thinner
            // if 7-day or 本周, slightly thinner
            barData.barWidth = if (currentTimeRange == 0 || currentTimeRange == 2) 0.28f else 0.30f

            barChart.xAxis.setCenterAxisLabels(false) // single bar mode
            barChart.xAxis.axisMinimum = -0.5f
            barChart.xAxis.axisMaximum = dates.size - 0.5f
        }

        barChart.notifyDataSetChanged()
        barChart.invalidate()
        // 禁用动画，避免掉帧
        // barChart.animateY(800, com.github.mikephil.charting.animation.Easing.EaseOutCubic)
        Log.d("HomePerf", "updateChart done  [${System.currentTimeMillis() - chartT0}ms on main thread]")
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

    private fun formatRateValue(rate: Double): String {
        return BillDisplayFormatter.formatRateValue(rate)
    }

    private fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(accountCurrency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val accountAmount = bill.amount * bill.exchangeRate
        return "${formatMoney(bill.amount, bill.currency)} × ${formatRateValue(bill.exchangeRate)} = ${formatMoney(accountAmount, accountCurrency)}"
    }

    private fun buildCrossCurrencyListSuffix(bill: Bill): String? {
        val accountCurrency = bill.accountId?.let { accountCurrencyById[it] }
            ?: bill.accountName.takeIf { it.isNotBlank() }?.let { accountCurrencyByName[it] }
            ?: return null
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(accountCurrency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val accountAmount = bill.amount * bill.exchangeRate
        return "${formatMoney(bill.amount, bill.currency)} * ${formatRateValue(bill.exchangeRate)} = ${formatMoney(accountAmount, accountCurrency)}"
    }

    private fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? {
        return BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)
    }

    private fun refreshAccountCurrencyCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val assets = AppDatabase.getDatabase(requireContext()).assetDao().getAllAssetsList()
            val idMap = assets.filter { it.currency.isNotBlank() }.associate { it.id to it.currency }
            val nameMap = assets
                .filter { it.name.isNotBlank() && it.currency.isNotBlank() }
                .associate { it.name to it.currency }
            withContext(Dispatchers.Main) {
                accountCurrencyById.clear()
                accountCurrencyById.putAll(idMap)
                accountCurrencyByName.clear()
                accountCurrencyByName.putAll(nameMap)
                if (::homeAdapter.isInitialized && homeAdapter.itemCount > 0) {
                    homeAdapter.notifyItemRangeChanged(0, homeAdapter.itemCount)
                }
            }
        }
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

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        tvCategory.text = when {
            isRepayment -> "\u8fd8\u6b3e"
            isTransfer -> "\u8f6c\u8d26"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> bill.categoryName.ifEmpty { "\u672a\u5206\u7c7b" }
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
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#C62828"))
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
                        append("(\u9000\u6b3e")
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
        val iconTint = when {
            forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
            bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#C62828")
            bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#9E9E9E")
        }
        ivIcon.setImageResource(R.mipmap.ic_launcher)
        ivIcon.setColorFilter(iconTint)
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val iconUrl = CategoryIconHelper.findCategoryIcon(requireContext(), iconLookupName, iconLookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(row)
                        .load(iconUrl)
                        // 如果 iconUrl 是本地 File 路径，ALL 会触发 NoResultEncoderAvailableException
                        // 用 DATA 策略只缓存原始数据，避免结果编码失败
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .into(ivIcon)
                }
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
            val refunds = AppDatabase.getDatabase(requireContext()).billDao().getRefundBillsBySourceId(sourceBill.id)
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
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val layoutIncoming = view.findViewById<View>(R.id.layout_detail_incoming)
        val lineIncoming = view.findViewById<View>(R.id.line_incoming)
        val tvIncomingAmount = view.findViewById<TextView>(R.id.tv_detail_incoming_amount)
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
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) "\u8fd8\u6b3e\u8be6\u60c5" else "\u8f6c\u8d26\u8be6\u60c5"
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = "\u8d26\u6237"
            tvTimeLabel.text = "\u65f6\u95f4"

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext())
                val toAsset = db.assetDao().getAssetById(bill.toAccountId ?: -1)
                val toName = toAsset?.name ?: "\u672a\u77e5\u8d26\u6237"
                val toAssetCurrency = toAsset?.currency ?: "CNY"
                withContext(Dispatchers.Main) {
                    tvAccount.text = "${bill.accountName} -> $toName"
                    val sourceCurrency = bill.currency
                    val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        // 多币种转账：转出金额单独一行，转入金额单独一行
                        tvAmountLabel.text = "转出金额"
                        val sourceSymbol = tao.test.flipaccounting.logic.CurrencyManager.getSymbol(sourceCurrency)
                        tvAmount.text = "$sourceSymbol${String.format(java.util.Locale.getDefault(), "%.2f", bill.amount)}"
                        val targetAmount = bill.amount * bill.exchangeRate
                        val toSymbol = tao.test.flipaccounting.logic.CurrencyManager.getSymbol(toAssetCurrency)
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = "$toSymbol${String.format(java.util.Locale.getDefault(), "%.2f", targetAmount)}"
                    } else {
                        tvAmountLabel.text = if (isRepayment) "\u8fd8\u6b3e\u91d1\u989d" else "\u8f6c\u8d26\u91d1\u989d"
                        tvAmount.text = formatMoney(bill.amount, bill.currency)
                    }
                }
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = "\u8be6\u60c5"
            tvAmountLabel.text = "\u91d1\u989d"
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = bill.categoryName

            if (isRefund) {
                tvAmount.text = formatMoney(bill.amount, bill.currency)
                tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                tvAccountLabel.text = "\u5165\u8d26\u8d26\u6237"
                tvTimeLabel.text = "\u5165\u8d26\u65f6\u95f4"
                tvAccount.text = bill.accountName

                lifecycleScope.launch(Dispatchers.IO) {
                    val original = bill.relatedBillId?.let { AppDatabase.getDatabase(requireContext()).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccountLabel.text = "\u8d26\u6237"
                tvTimeLabel.text = "\u65f6\u95f4"
                tvAccount.text = bill.accountName

                if (bill.type == Bill.TYPE_EXPENSE) {
                    val refundedAmount = refundAmountOfExpenseBill(bill)
                    if (refundedAmount > 0.0) {
                        tvAmount.text = BillDisplayFormatter.buildRefundedExpenseAmountText(
                            netAmount = bill.amount,
                            originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                            currency = bill.currency
                        )
                        renderRefundRecords(view, bill) { refundBill -> showBillDetailSheet(refundBill) }
                    } else {
                        tvAmount.text = "-${formatMoney(bill.amount, bill.currency)}"
                    }
                    tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                } else {
                    tvAmount.text = "+${formatMoney(bill.amount, bill.currency)}"
                    tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                }

                lifecycleScope.launch(Dispatchers.IO) {
                    val crossCurrencyText = buildCrossCurrencyDetailFormula(bill, "CNY")
                    withContext(Dispatchers.Main) {
                        if (!crossCurrencyText.isNullOrBlank()) {
                            tvAmountFormula.visibility = View.VISIBLE
                            tvAmountFormula.text = crossCurrencyText
                        }
                    }
                }
            }
        }

        val timeStr = dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_time).text = timeStr

        val recordTimeStr = dfDetailTime.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text = "\u8bb0\u5f55\u4e8e $recordTimeStr"
        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = bill.remark.ifEmpty { "\u65e0\u5907\u6ce8" }
        view.findViewById<TextView>(R.id.tv_detail_book_name).text = bill.bookName.ifEmpty { BookAccountManager.DEFAULT_BOOK }

        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmountOfExpenseBill(bill) > 0.0) {
            lifecycleScope.launch(Dispatchers.IO) {
                val refunds = AppDatabase.getDatabase(requireContext()).billDao().getRefundBillsBySourceId(bill.id)
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
            val intent = Intent(requireContext(), EditBillActivity::class.java)
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
                    val source = bill.relatedBillId?.let { AppDatabase.getDatabase(requireContext()).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(requireContext(), EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(requireContext(), EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                startActivity(intent)
            }
        }

        btnDelete.setOnClickListener {
            bottomSheet.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext())
                tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "\u5df2\u5220\u9664", Toast.LENGTH_SHORT).show()
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
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
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

        tvTitle.text = if (editingRefund == null) "\u9000\u6b3e" else "\u7f16\u8f91\u9000\u6b3e"

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
            OverlayDialogs.showGridAssetPicker(requireContext(), tvRefundAccount.text.toString(), "\u9009\u62e9\u9000\u6b3e\u5165\u8d26\u8d26\u6237") { account ->
                selectedAccount = account
                tvRefundAccount.text = account
            }
        }

        layoutRefundTime.setOnClickListener {
            OverlayDialogs.showCustomTimePicker(requireContext()) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val amountStr = etRefundAmount.text.toString()
            val refundAmount = amountStr.toDoubleOrNull() ?: 0.0

            if (refundAmount <= 0) {
                Toast.makeText(context, "\u8bf7\u8f93\u5165\u6709\u6548\u7684\u9000\u6b3e\u91d1\u989d", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "\u9009\u62e9\u8d26\u6237") {
                Toast.makeText(context, "\u8bf7\u9009\u62e9\u5165\u8d26\u8d26\u6237", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "\u9000\u6b3e\uff1a${stripRefundPrefix(originalBill.categoryName)}"
            }

            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(requireContext())
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
                        Toast.makeText(context, "\u9000\u6b3e\u91d1\u989d\u4e0d\u80fd\u5927\u4e8e\u5269\u4f59\u652f\u51fa", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "\u539f\u8d26\u5355\u4e0d\u5b58\u5728\u6216\u4e0d\u53ef\u9000\u6b3e", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(context, if (editingRefund == null) "\u9000\u6b3e\u5df2\u4fdd\u5b58" else "\u9000\u6b3e\u5df2\u66f4\u65b0", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }

    private fun loadBills(year: Int, month: Int) {
        lifecycleScope.launch {
            val startOfMonth = getStartOfMonth(year, month)
            val endOfMonth = getEndOfMonth(year, month)
            billRepository.getBillsBetweenTimes(startOfMonth.timeInMillis, endOfMonth.timeInMillis).collect { bills ->
                homeAdapter.submitList(bills)
            }
        }
    }

    private fun getStartOfMonth(year: Int, month: Int): Calendar {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal
    }

    private fun getEndOfMonth(year: Int, month: Int): Calendar {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.MONTH, month)
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal
    }

    override fun onDestroyView() {
        refreshTimeoutJob?.cancel()
        refreshTimeoutJob = null
        val appContext = context?.applicationContext
        if (appContext != null) {
            val db = AppDatabase.getDatabase(appContext)
            billsInvalidationObserver?.let { observer ->
                db.invalidationTracker.removeObserver(observer)
            }
        }
        billsInvalidationObserver = null
        super.onDestroyView()
    }

    private fun observeBillTableChanges() {
        val db = AppDatabase.getDatabase(requireContext().applicationContext)
        billsInvalidationObserver?.let { db.invalidationTracker.removeObserver(it) }
        val observer = object : InvalidationTracker.Observer("bills") {
            override fun onInvalidated(tables: Set<String>) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                    if (!isAdded) return@launch
                    if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                    homeViewModel.forceReload(
                        bookName = selectedBookName,
                        year = selectedYear,
                        month = selectedMonth,
                        timeRange = currentTimeRange,
                        type = currentType,
                        isChartHidden = !Prefs.isShowHomeTrendCard(requireContext())
                    )
                }
            }
        }
        billsInvalidationObserver = observer
        db.invalidationTracker.addObserver(observer)
    }
}
