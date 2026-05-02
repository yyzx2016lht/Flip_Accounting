package tao.test.flipaccounting

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import tao.test.flipaccounting.ui.main.SharedYearMonthSession
import tao.test.flipaccounting.ui.common.AddBillEntrySheetLauncher
import tao.test.flipaccounting.ui.main.home.HomeFragment
import tao.test.flipaccounting.ui.main.stats.StatsFragment
import tao.test.flipaccounting.ui.main.assets.AssetsFragment
import tao.test.flipaccounting.ui.main.profile.ProfileFragment
import kotlin.math.abs

// 支持水平滑动接管的 FrameLayout（用于页面切换手势）
/**
 * 在 onInterceptTouchEvent 中识别水平滑动意图。
 * 确认开始后接管事件，并通过回调把 dx 传给 Activity 做 translationX。
 * 不会影响普通点击与垂直滚动。
 */
class SwipeFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /**
     * 手势意图确认时回调参数含义：
     *   dir      1=向左（下一页） / -1=向右（上一页）
     *   rawX/rawY 按下时的屏幕坐标（用于判断是否可接管）
     * 返回 true 才会接管后续事件；返回 false 表示放弃并交给子 View（如 Drawer）。
     */
    var onSwipeStart: ((dir: Int, rawX: Float, rawY: Float) -> Boolean)? = null
    var onHorizontalDrag: ((dx: Float) -> Unit)? = null
    var onHorizontalSettle: ((dx: Float, vx: Float) -> Unit)? = null

    private var vt: VelocityTracker? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var dragging = false
    private var rejected = false   // 已判定为拒绝，后续手势不再接管
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxVel = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX
                downRawY = ev.rawY
                dragging = false
                rejected = false
                vt?.recycle()
                vt = VelocityTracker.obtain()
                vt!!.addMovement(ev)
                return false          // DOWN 不拦截，保留子 View 点击能力
            }
            MotionEvent.ACTION_MOVE -> {
                if (rejected) return false
                vt?.addMovement(ev)
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging) {
                    when {
                        abs(dx) > slop && abs(dx) > abs(dy) -> {
                            val dir = if (dx < 0f) 1 else -1
                            // 使用屏幕坐标，作为回调入参
                            val loc = IntArray(2).also { getLocationOnScreen(it) }
                            val localDownX = downRawX - loc[0]
                            val shouldStart = onSwipeStart?.invoke(dir, downRawX, downRawY) ?: true
                            if (!shouldStart) {
                                rejected = true
                                vt?.recycle(); vt = null
                                parent?.requestDisallowInterceptTouchEvent(false)
                                return false
                            }

                            // 水平意图确认，开始接管事件
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        abs(dy) > slop -> {
                            // 纵向意图明显，不接管
                            rejected = true
                            vt?.recycle(); vt = null
                            return false
                        }
                        else -> return false
                    }
                }
                if (dragging) {
                    onHorizontalDrag?.invoke(dx)
                    return true   // 接管后的 MOVE
                }
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.apply {
                        addMovement(ev)
                        computeCurrentVelocity(1000, maxVel.toFloat())
                    }
                    val vx = vt?.xVelocity ?: 0f
                    val dx = ev.rawX - downRawX
                    vt?.recycle(); vt = null
                    dragging = false
                    rejected = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        onHorizontalSettle?.invoke(dx, vx)
                    } else {
                        onHorizontalSettle?.invoke(0f, 0f)
                    }
                    return true
                }
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                return false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // only called when dragging=true; onInterceptTouchEvent already consumed MOVE
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                onHorizontalDrag?.invoke(ev.rawX - downRawX)
            }
            MotionEvent.ACTION_UP -> {
                vt?.apply {
                    addMovement(ev)
                    computeCurrentVelocity(1000, maxVel.toFloat())
                }
                val vx = vt?.xVelocity ?: 0f
                val dx = ev.rawX - downRawX
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onHorizontalSettle?.invoke(dx, vx)
            }
            MotionEvent.ACTION_CANCEL -> {
                vt?.recycle(); vt = null
                dragging = false
                rejected = false
                parent?.requestDisallowInterceptTouchEvent(false)
                onHorizontalSettle?.invoke(0f, 0f)
            }
        }
        return true
    }
}

// MainActivity：主页容器与底部导航切页控制
class MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_OPEN_TAB_INDEX = "open_tab_index"
    }

    private var fabApp: FloatingActionButton? = null
    private var bottomNavigationView: BottomNavigationView? = null
    private lateinit var swipeContainer: SwipeFrameLayout

    /**
     * 跨 HomeFragment 重建共享的 RecycledViewPool。
     * Fragment 每次被 replace() 重建时，ViewHolder 不会被丢弃，下次直接复用，
     * 彻底跳过 inflate，消除首帧布局卡顿。
     * TYPE_HEADER=0, TYPE_ITEM=1；
     * 为应对“少账本 <-> 多账本”频繁切换，提升缓存上限，减少 2->164 这种场景的重新 inflate。
     */
    val homeRecycledViewPool = androidx.recyclerview.widget.RecyclerView.RecycledViewPool().also {
        it.setMaxRecycledViews(0, 80)    // TYPE_HEADER：按天分组头，适度提高
        it.setMaxRecycledViews(1, 260)   // TYPE_ITEM：重点提高，尽量覆盖大账本回切
    }

    // Tab 顺序
    private val tabIds = listOf(R.id.nav_home, R.id.nav_stats, R.id.nav_assets, R.id.nav_profile)
    private var currentTabIndex = 0

    // 4个 Tab 的 Fragment 实例，一次性创建，永不销毁
    private val tabFragments = arrayOfNulls<Fragment>(4)

    // 最小判定滑动速度
    private var minFlingVelocity = 0

    // 当前运行中的回弹/切换动画
    private var settleAnimator: ValueAnimator? = null

    // 滑动手势：预加载的下一个 Fragment（add 但未 replace）
    private var peekFragment: Fragment? = null
    // 滑动方向：+1 右→左（下一页）/-1 左→右（上一页）
    private var swipeDir = 0
    // 预加载的目标 tab 索引
    private var peekIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bottomNavigationView = findViewById(R.id.bottom_navigation)
        fabApp = findViewById(R.id.fab_add)
        swipeContainer = findViewById(R.id.fragment_container)
        refreshBottomNavigationTabs(ensureValidSelection = false)

        minFlingVelocity = ViewConfiguration.get(this).scaledMinimumFlingVelocity

        if (savedInstanceState == null) {
            currentTabIndex = resolveRequestedTabIndex(intent) ?: 0
            // 一次性 add 全部 4 个 Fragment，hide 非当前的，永不 replace
            val tx = supportFragmentManager.beginTransaction()
            for (i in tabIds.indices) {
                val f = makeFragment(i)
                tabFragments[i] = f
                tx.add(R.id.fragment_container, f, "tab_$i")
                if (i != currentTabIndex) tx.hide(f)
            }
            tx.commitNow()
            bottomNavigationView?.selectedItemId = tabIds[currentTabIndex]
            updateFabVisibility()

            // 预加载首页数据：直接获取 ViewModel 并启动数据查询（比 Fragment.onViewCreated 早很多）
            val homeViewModel = androidx.lifecycle.ViewModelProvider(this)
                .get(tao.test.flipaccounting.ui.main.home.HomeViewModel::class.java)
            val (sessionYear, sessionMonth) = SharedYearMonthSession.getYearMonth()
            homeViewModel.syncAndLoad(
                bookName = tao.test.flipaccounting.BookAccountManager.getSelectedBook(this),
                year = sessionYear,
                month = sessionMonth,
                timeRange = 0,
                type = 0,
                isChartHidden = false
            )
            Log.d("HomePerf", "MainActivity.onCreate: preload started")
        } else {
            val restoredTabIndex = savedInstanceState.getInt("tab_index", 0)
            currentTabIndex = restoredTabIndex
            val shouldFallbackToHome = !isTabVisible(currentTabIndex)
            if (shouldFallbackToHome) {
                currentTabIndex = 0
            }
            // 恢复时从 FragmentManager 找回已有实例
            for (i in tabIds.indices) {
                tabFragments[i] = supportFragmentManager.findFragmentByTag("tab_$i")
            }
            if (shouldFallbackToHome) {
                val tx = supportFragmentManager.beginTransaction()
                tabFragments.forEachIndexed { index, fragment ->
                    if (fragment != null) {
                        if (index == currentTabIndex) tx.show(fragment) else tx.hide(fragment)
                    }
                }
                tx.commitNowAllowingStateLoss()
            }
            bottomNavigationView?.selectedItemId = tabIds.getOrElse(currentTabIndex) { R.id.nav_home }
            fabApp?.post { updateFabVisibility() }
        }

    // BottomNav 点击切换：统一走 switchTab，保持与滑动动画一致
        bottomNavigationView?.setOnItemSelectedListener { item ->
            val newIndex = tabIds.indexOf(item.itemId)
            if (newIndex < 0 || newIndex == currentTabIndex) return@setOnItemSelectedListener true
            val dir = if (newIndex > currentTabIndex) 1 else -1
            switchTab(newIndex, dir, fromSwipe = false)
            true
        }

    // 滑动开始：预加载下一页（show 但不 replace），与当前页并排摆放
        swipeContainer.onSwipeStart = { dir, rawX, rawY ->
            val home = curFragment() as? HomeFragment
            val nextIdx = findAdjacentVisibleTabIndex(dir)
            when {
                // If on Stats page and touch is on PieChart, let PieChart handle the gesture
                currentTabIndex == 1 && isSwipeTouchOnPieChart(rawX, rawY) -> false
                home?.isBookDrawerOpen() == true -> false
                currentTabIndex == 0 && dir < 0 -> {
                    val loc = IntArray(2).also { swipeContainer.getLocationOnScreen(it) }
                    val localDownX = rawX - loc[0]
                    if (localDownX < swipeContainer.width / 3f) {
                        home?.openBookDrawerFromHost()
                    }
                    false
                }
                nextIdx == null -> false
                else -> {
                    if (peekFragment == null) {
                        swipeDir = dir
                        peekIndex = nextIdx
                        val frag = tabFragments[nextIdx] ?: makeFragment(nextIdx).also { tabFragments[nextIdx] = it }
                        peekFragment = frag
                        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                        hideAssetFabForTransition(frag)
                        supportFragmentManager.beginTransaction()
                            .show(frag)
                            .commitNow()
                        // 目标页初始贴在当前页对侧
                        frag.view?.apply {
                            translationX = if (dir > 0) w else -w
                            alpha = 0.78f
                            scaleX = 0.985f
                            scaleY = 0.985f
                        }
                    }
                    true
                }
            }
        }

    // 拖动中：当前页与目标页同步平移/缩放/透明度
        swipeContainer.onHorizontalDrag = { dx ->
            if (peekFragment != null && swipeDir != 0 && peekIndex in tabIds.indices) {
                val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                val clampedDx = dx.coerceIn(-w, w)
                // 找到当前页与预加载页的 View
                val allFrags = supportFragmentManager.fragments
                val curFrag = tabFragments.getOrNull(currentTabIndex)
                val curView = curFrag?.view
                val peekView = peekFragment?.view

                // 当前页跟随手势移动
                curView?.translationX = clampedDx
                val progress = (abs(clampedDx) / w).coerceIn(0f, 1f)
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (swipeDir > 0) clampedDx + w else clampedDx - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale

                // 目标页始终贴在当前页对侧，形成并排跟手
                peekView?.translationX = if (swipeDir > 0) clampedDx + w else clampedDx - w
            }
        }

        // 松手后：决定切换还是回弹
        swipeContainer.onHorizontalSettle = { dx, vx ->
            if (peekFragment != null && swipeDir != 0 && peekIndex in tabIds.indices) {
                val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
                val distPass = abs(dx) > w * 0.25f
                val velPass = abs(vx) >= minFlingVelocity
                // 方向需与预览方向一致，避免反向手势误切换
                val dirMatch = (dx < 0f && swipeDir > 0) || (dx > 0f && swipeDir < 0)

                if ((distPass || velPass) && dirMatch && peekIndex != currentTabIndex) {
                    commitSwipe()
                } else {
                    snapBack()
                }
            }
        }

        fabApp?.setOnClickListener {
            if (Prefs.getAiEntryMode(this) == Prefs.AI_ENTRY_MODE_CHAT) {
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_SOURCE_BOOK, BookAccountManager.getSelectedBook(this))
                )
            } else {
                showAddBillBottomSheet()
            }
        }

    // 若已开启翻转或敲击，则在启动时拉起悬浮服务
        val serviceIntent = Intent(this, OverlayService::class.java)
        val needsService = Prefs.isFlipEnabled(this) || Prefs.isDoubleTapEnabled(this)
        if (needsService) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab_index", currentTabIndex)
    }

    override fun onResume() {
        super.onResume()
        refreshBottomNavigationTabs()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveRequestedTabIndex(intent)?.let { requestedIndex ->
            if (requestedIndex in tabIds.indices && isTabVisible(requestedIndex)) {
                val previousIndex = currentTabIndex
                if (requestedIndex != previousIndex) {
                    switchTab(requestedIndex, if (requestedIndex > previousIndex) 1 else -1, fromSwipe = false)
                } else {
                    refreshBottomNavigationTabs()
                }
            }
        }
    }

    private fun updateFabVisibility() {
        val fab = fabApp ?: return
        if (currentTabIndex == 0) {
            fab.show()
        } else {
            fab.hide()
        }
    }

    private fun isTabVisible(index: Int): Boolean {
        if (index !in tabIds.indices) return false
        return tabIds[index] != R.id.nav_assets || Prefs.isAssetFeatureEnabled(this)
    }

    private fun resolveRequestedTabIndex(intent: Intent?): Int? {
        if (intent == null || !intent.hasExtra(EXTRA_OPEN_TAB_INDEX)) return null
        return intent.getIntExtra(EXTRA_OPEN_TAB_INDEX, 0)
    }

    private fun findAdjacentVisibleTabIndex(direction: Int): Int? {
        var index = currentTabIndex + direction
        while (index in tabIds.indices) {
            if (isTabVisible(index)) return index
            index += direction
        }
        return null
    }

    fun refreshBottomNavigationTabs(ensureValidSelection: Boolean = true) {
        bottomNavigationView?.menu?.findItem(R.id.nav_assets)?.isVisible = Prefs.isAssetFeatureEnabled(this)
        if (ensureValidSelection && !isTabVisible(currentTabIndex)) {
            currentTabIndex = 0
            val tx = supportFragmentManager.beginTransaction()
            tabFragments.forEachIndexed { index, fragment ->
                if (fragment != null) {
                    if (index == currentTabIndex) tx.show(fragment) else tx.hide(fragment)
                }
            }
            tx.commitNowAllowingStateLoss()
        }
        bottomNavigationView?.selectedItemId = tabIds[currentTabIndex]
        updateFabVisibility()
    }

    // Tab 切换动画相关

    /** 返回当前显示的 Fragment */
    private fun curFragment(): Fragment? = tabFragments.getOrNull(currentTabIndex)

    /**
     * 确认切换：让当前页与目标页在同一时间轴内平移并完成过渡。
     */
    private fun commitSwipe() {
        settleAnimator?.cancel()
        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)

        val peekFrag = peekFragment
        val curFrag = curFragment()
        val curView = curFrag?.view
        val peekView = peekFrag?.view

        hideAssetFabForTransition(curFrag)
        hideAssetFabForTransition(peekFrag)

    // 当前页起始偏移
        val fromOffset = curView?.translationX ?: 0f
    // 目标：当前页移出屏幕，目标页归位到 0
        val toOffset = if (swipeDir > 0) -w else w

        val newIndex = peekIndex
        val savedDir = swipeDir
        closeHomeDrawerIfLeaving(newIndex)

        settleAnimator = ValueAnimator.ofFloat(fromOffset, toOffset).apply {
            duration = 220L
            interpolator = DecelerateInterpolator(2.0f)
            addUpdateListener { va ->
                val offset = va.animatedValue as Float
                val progress = (abs(offset) / w).coerceIn(0f, 1f)
                curView?.translationX = offset
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (savedDir > 0) offset + w else offset - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    // hide 当前，show 目标，保留 View 树，零重建
                    val cur = curFrag
                    val target = peekFrag ?: tabFragments[newIndex] ?: makeFragment(newIndex)
                    val tx = supportFragmentManager.beginTransaction()
                    if (cur != null) tx.hide(cur)
                    tx.show(target)
                    tx.commitNow()
                    resetTabViewState(cur?.view)
                    resetTabViewState(target.view)
                    currentTabIndex = newIndex
                    peekFragment = null
                    peekIndex = -1
                    swipeDir = 0
                    bottomNavigationView?.setOnItemSelectedListener(null)
                    bottomNavigationView?.selectedItemId = tabIds[newIndex]
                    rebindBottomNav()
                    updateFabVisibility()
                    showAssetFabForActiveTab(target)
                }
            })
            start()
        }
    }

    /**
     * 回弹：把当前页恢复到 offset=0，并隐藏预加载页。
     */
    private fun snapBack() {
        settleAnimator?.cancel()
        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)

        val peekFrag = peekFragment
        val curFrag = curFragment()
        val curView = curFrag?.view
        val peekView = peekFrag?.view

        val fromOffset = curView?.translationX ?: 0f
        val savedDir = swipeDir

        settleAnimator = ValueAnimator.ofFloat(fromOffset, 0f).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(2.5f)
            addUpdateListener { va ->
                val offset = va.animatedValue as Float
                val progress = (abs(offset) / w).coerceIn(0f, 1f)
                curView?.translationX = offset
                curView?.alpha = 1f - 0.18f * progress
                val curScale = 1f - 0.035f * progress
                curView?.scaleX = curScale
                curView?.scaleY = curScale
                peekView?.translationX = if (savedDir > 0) offset + w else offset - w
                peekView?.alpha = 0.78f + 0.22f * progress
                val peekScale = 0.985f + 0.015f * progress
                peekView?.scaleX = peekScale
                peekView?.scaleY = peekScale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    resetTabViewState(curView)
                    if (peekFrag != null) {
                        supportFragmentManager.beginTransaction()
                            .hide(peekFrag)
                            .commitNow()
                    }
                    resetTabViewState(peekView)
                    peekFragment = null
                    peekIndex = -1
                    swipeDir = 0
                }
            })
            start()
        }
    }

    /**
     * BottomNav 点击切换入口；必要时带入动画参数。
     */
    private fun switchTab(newIndex: Int, dir: Int, fromSwipe: Boolean, currentDx: Float = 0f) {
        closeHomeDrawerIfLeaving(newIndex)
        // 清理临时 peek 状态
        settleAnimator?.cancel()
        if (peekFragment != null) {
            try {
                supportFragmentManager.beginTransaction()
                    .hide(peekFragment!!)
                    .commitNow()
            } catch (_: Exception) {}
            peekFragment = null; peekIndex = -1; swipeDir = 0
        }

        val w = swipeContainer.width.toFloat().coerceAtLeast(1f)
        val curFrag = curFragment()
        val newFrag = tabFragments[newIndex] ?: makeFragment(newIndex).also { tabFragments[newIndex] = it }
        val tx = supportFragmentManager.beginTransaction()
        tx.show(newFrag)
        tx.commitNow()

        hideAssetFabForTransition(curFrag)
        hideAssetFabForTransition(newFrag)

        val curView = curFrag?.view
        val newView = newFrag.view
        val useSlideMotion = fromSwipe
        val enterFrom = if (useSlideMotion) {
            if (dir > 0) w * 0.45f else -w * 0.45f
        } else {
            0f
        }
        newView?.translationX = enterFrom
        newView?.alpha = if (useSlideMotion) {
            if (fromSwipe) 0.78f else 0.92f
        } else {
            0f
        }
        newView?.scaleX = if (useSlideMotion) {
            if (fromSwipe) 0.985f else 0.998f
        } else {
            0.992f
        }
        newView?.scaleY = if (useSlideMotion) {
            if (fromSwipe) 0.985f else 0.998f
        } else {
            0.992f
        }
        curView?.translationX = 0f
        curView?.alpha = 1f
        curView?.scaleX = 1f
        curView?.scaleY = 1f

        currentTabIndex = newIndex

        var finishedAnimations = 0
        val expectedAnimations = (if (curView != null && curFrag != newFrag) 1 else 0) + (if (newView != null) 1 else 0)
        val finishSwitch = {
            finishedAnimations += 1
            if (finishedAnimations >= expectedAnimations) {
                if (curFrag != null && curFrag != newFrag) {
                    supportFragmentManager.beginTransaction()
                        .hide(curFrag)
                        .commitNowAllowingStateLoss()
                    resetTabViewState(curView)
                }
                resetTabViewState(newView)
                showAssetFabForActiveTab(newFrag)
            }
        }

        if (curView != null && curFrag != newFrag) {
            curView.animate()
                .translationX(if (useSlideMotion) {
                    if (dir > 0) {
                        if (fromSwipe) -w * 0.12f else -w * 0.015f
                    } else {
                        if (fromSwipe) w * 0.12f else w * 0.015f
                    }
                } else {
                    0f
                })
                .alpha(if (useSlideMotion) {
                    if (fromSwipe) 0.82f else 0.96f
                } else {
                    0f
                })
                .scaleX(if (useSlideMotion) {
                    if (fromSwipe) 0.975f else 0.998f
                } else {
                    0.992f
                })
                .scaleY(if (useSlideMotion) {
                    if (fromSwipe) 0.975f else 0.998f
                } else {
                    0.992f
                })
                .setDuration(if (useSlideMotion) {
                    if (fromSwipe) 240L else 150L
                } else {
                    120L
                })
                .setInterpolator(if (useSlideMotion) {
                    if (fromSwipe) AccelerateInterpolator(1.45f) else DecelerateInterpolator(1.6f)
                } else {
                    DecelerateInterpolator(1.5f)
                })
                .withLayer()
                .withEndAction(finishSwitch)
                .start()
        }

        if (newView != null) {
            newView.animate().cancel()
            newView.animate()
                .translationX(0f)
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(if (useSlideMotion) {
                    if (fromSwipe) 240L else 150L
                } else {
                    120L
                })
                .setInterpolator(if (useSlideMotion) {
                    if (fromSwipe) DecelerateInterpolator(2.0f) else DecelerateInterpolator(1.8f)
                } else {
                    DecelerateInterpolator(1.5f)
                })
                .withLayer()
                .withEndAction(finishSwitch)
                .start()
        } else if (expectedAnimations == 0) {
            showAssetFabForActiveTab(newFrag)
        }

        bottomNavigationView?.setOnItemSelectedListener(null)
        bottomNavigationView?.selectedItemId = tabIds[newIndex]
        rebindBottomNav()
        updateFabVisibility()
    }

    private fun animateTo(
        target: android.view.View?,
        toX: Float, toAlpha: Float,
        durationMs: Long,
        interp: android.view.animation.Interpolator,
        onEnd: (() -> Unit)? = null
    ) {
        target ?: return
        settleAnimator?.cancel()
        val fromX = target.translationX
        val fromA = target.alpha
        settleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = interp
            addUpdateListener {
                val t = it.animatedFraction
                target.translationX = fromX + (toX - fromX) * t
                target.alpha = fromA + (toAlpha - fromA) * t
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    target.translationX = toX
                    target.alpha = toAlpha
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    private fun hideAssetFabForTransition(fragment: Fragment?) {
        (fragment as? AssetsFragment)?.hideAssetFab()
    }

    private fun showAssetFabForActiveTab(fragment: Fragment?) {
        (fragment as? AssetsFragment)?.view?.post {
            (fragment as? AssetsFragment)?.showAssetFab()
        }
    }

    private fun resetTabViewState(view: android.view.View?) {
        view ?: return
        view.translationX = 0f
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    private fun rebindBottomNav() {
        bottomNavigationView?.setOnItemSelectedListener { item ->
            val newIndex = tabIds.indexOf(item.itemId)
            if (newIndex < 0 || newIndex == currentTabIndex) return@setOnItemSelectedListener true
            val dir = if (newIndex > currentTabIndex) 1 else -1
            switchTab(newIndex, dir, fromSwipe = false)
            true
        }
    }

    private fun closeHomeDrawerIfLeaving(targetTabIndex: Int) {
        if (currentTabIndex == 0 && targetTabIndex != 0) {
            (tabFragments.getOrNull(0) as? HomeFragment)?.closeBookDrawerFromHost()
        }
    }

    private fun makeFragment(index: Int): Fragment = when (tabIds[index]) {
        R.id.nav_home -> HomeFragment()
        R.id.nav_stats -> StatsFragment()
        R.id.nav_assets -> AssetsFragment()
        R.id.nav_profile -> ProfileFragment()
        else -> HomeFragment()
    }

    private fun commitFragment(fragment: Fragment, animate: Boolean) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun showAddBillBottomSheet() {
        AddBillEntrySheetLauncher.show(
            activity = this,
            onShow = { fabApp?.hide() },
            onDismiss = { updateFabVisibility() }
        )
    }

    /**
     * Check if touch point (screen absolute coordinates) is on the PieChart in Stats page.
     * Stats page (index 1) PieChart should handle gestures independently,
     * not intercepted by page swipe.
     */
    private fun isSwipeTouchOnPieChart(rawX: Float, rawY: Float): Boolean {
        val statsFrag = tabFragments.getOrNull(1)
        val statsView = statsFrag?.view ?: return false

        val pieChart = statsView.findViewById<com.github.mikephil.charting.charts.PieChart?>(R.id.pie_chart)
            ?: return false

        // Use screen absolute coordinates to compare with PieChart's screen position
        val location = IntArray(2)
        pieChart.getLocationOnScreen(location)
        val left = location[0]
        val top = location[1]
        val right = left + pieChart.width
        val bottom = top + pieChart.height

        return rawX >= left && rawX <= right && rawY >= top && rawY <= bottom
    }
}
