package com.taostudio.tapaccounting.ui.main.profile

import android.app.Activity
import android.os.Bundle
import android.net.Uri
import android.app.ActivityManager
import com.taostudio.tapaccounting.*
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import android.content.Context
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.CurrencyManagerActivity
import com.google.android.material.button.MaterialButton
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.SensitivityActivity
import android.util.Log
import android.view.ViewTreeObserver
import com.google.android.material.appbar.AppBarLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import com.taostudio.tapaccounting.ui.common.StatusBarStyle
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var rootRef: View? = null
    private var suppressHomeTrendCardSwitchCallback = false
    private data class ToggleTracker(var count: Int = 0, var firstToggleAtMs: Long = 0L)
    private val toggleTimers = mutableMapOf<String, ToggleTracker>()
    // ── 诊断：监听 AppBarLayout 布局变化 ──
    private var appbarLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var pendingEditUserAvatarView: ImageView? = null
    private val pickUserAvatarLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && isAdded) startUserAvatarCrop(uri)
    }
    private val userAvatarCropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!isAdded) return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.let { UCrop.getOutput(it) } ?: return@registerForActivityResult
            saveUserAvatar(uri)
        } else {
            val error = result.data?.let { UCrop.getError(it) }
            if (error != null) Utils.toast(requireContext(), "头像裁剪失败，请重新选择图片")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootRef = view

        checkAndRequestPermissions()
        setupMainSettings(view)
        setupProfileInsets(view)

        // ── 诊断日志：追踪 AppBarLayout 高度/padding 变化 ──
        startProfileLayoutDiagnostic(view)
    }

    private fun setupProfileInsets(root: View) {
        val statusSpacer = root.findViewById<View>(R.id.view_profile_status_spacer)
        val scroll = findProfileScrollContainer(root)
        val baseScrollBottom = scroll?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            statusSpacer?.let { spacer ->
                spacer.layoutParams = spacer.layoutParams.apply {
                    height = statusTop.coerceAtLeast((24f * resources.displayMetrics.density).toInt())
                }
            }
            scroll?.updatePadding(bottom = baseScrollBottom + navBottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    override fun onResume() {
        super.onResume()
        applyProfileStatusBarStyle()
        rootRef?.let { refreshOverlayReminder(it, Prefs.isQuickGestureEnabled(requireContext())) }
        rootRef?.let { updateShowBookEntrySettingVisibility(it) }
        rootRef?.let { refreshUserAvatarCard(it) }
        rootRef?.let { refreshHomeTrendCardSwitch(it) }
        // ── 诊断：记录 onResume 时刻的 appbar 状态 ──
        rootRef?.let {
            dumpAppbarState("onResume", it)
            sampleAppbarFrames("onResumeFrames", it, 14)
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            applyProfileStatusBarStyle()
            rootRef?.let { refreshHomeTrendCardSwitch(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        // ── 诊断：记录 onPause 时刻的 appbar 状态 ──
        rootRef?.let {
            dumpAppbarState("onPause", it)
            sampleAppbarFrames("onPauseFrames", it, 8)
        }
    }

    /** 诊断：dump AppBarLayout 和 CoordinatorLayout 当前布局参数 */
    private fun dumpAppbarState(tag: String, root: View) {
        val appbar = root.findViewById<AppBarLayout>(R.id.appbar_profile) ?: return
        val coord = appbar.parent as? android.view.ViewGroup
        val title = root.findViewById<TextView>(R.id.tv_profile_title)
        val nsv = findProfileScrollContainer(root)
        val density = resources.displayMetrics.density
        val insetsTop = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.statusBars())
            ?.top ?: -1

        val appbarH = appbar.height
    val appbarTop = appbar.top
    val appbarY = appbar.y
    val appbarTY = appbar.translationY
        val appbarPT = appbar.paddingTop
        val appbarPB = appbar.paddingBottom
        val appbarML = (appbar.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.topMargin ?: -1

        val coordPT = coord?.paddingTop ?: -1
        val coordFsw = if (coord is androidx.coordinatorlayout.widget.CoordinatorLayout)
            coord.fitsSystemWindows else null

        val titleTop = title?.top ?: -1
        val titleY = title?.y ?: -1f
        val titleTY = title?.translationY ?: -1f
        val titlePT = title?.paddingTop ?: -1

        val appbarLocScreen = IntArray(2)
        val appbarLocWindow = IntArray(2)
        appbar.getLocationOnScreen(appbarLocScreen)
        appbar.getLocationInWindow(appbarLocWindow)

        val titleLocScreen = IntArray(2)
        val titleLocWindow = IntArray(2)
        title?.getLocationOnScreen(titleLocScreen)
        title?.getLocationInWindow(titleLocWindow)

        val rootLocScreen = IntArray(2)
        root.getLocationOnScreen(rootLocScreen)

        Log.w("PROFILE_DIAG",
            "[$tag] AppBar: h=${appbarH}px(${appbarH/density}dp) " +
            "top=${appbarTop}px y=${appbarY}px translationY=${appbarTY}px " +
            "paddingTop=${appbarPT}px(${appbarPT/density}dp) " +
            "paddingBottom=${appbarPB}px " +
            "topMargin=${appbarML}px | " +
            "CoordLayout: paddingTop=${coordPT}px fitsSystemWindows=$coordFsw | " +
            "WindowInsets.top=${insetsTop}px"
        )

        Log.w("PROFILE_DIAG",
            "[$tag] Title: top=${titleTop}px y=${titleY}px " +
            "translationY=${titleTY}px paddingTop=${titlePT}px"
        )

        Log.w("PROFILE_DIAG",
            "[$tag] Loc: root(screenY=${rootLocScreen[1]}) " +
            "appbar(screenY=${appbarLocScreen[1]} windowY=${appbarLocWindow[1]}) " +
            "title(screenY=${titleLocScreen[1]} windowY=${titleLocWindow[1]}) " +
            "nsv(scrollY=${nsv?.scrollY ?: -1})"
        )

        // 也 dump AppBarLayout 的所有直接子 View 高度
        for (i in 0 until appbar.childCount) {
            val child = appbar.getChildAt(i)
            val childH = child.height
            val childPT = child.paddingTop
            Log.w("PROFILE_DIAG",
                "[$tag]   child[$i] ${child.javaClass.simpleName} " +
                "h=${childH}px(${childH/density}dp) paddingTop=${childPT}px"
            )
        }
    }

    /** 诊断：注册 GlobalLayoutListener，每次布局变化时打印状态 */
    private fun startProfileLayoutDiagnostic(root: View) {
        val appbar = root.findViewById<AppBarLayout>(R.id.appbar_profile) ?: return
        var lastH = -1
        var lastPT = -1
        var lastTop = Int.MIN_VALUE
        var lastTY = Float.MIN_VALUE
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val h = appbar.height
            val pt = appbar.paddingTop
            val top = appbar.top
            val ty = appbar.translationY
            if (h != lastH || pt != lastPT || top != lastTop || ty != lastTY) {
                lastH = h
                lastPT = pt
                lastTop = top
                lastTY = ty
                dumpAppbarState("LayoutChange", root)
            }
        }
        appbar.viewTreeObserver.addOnGlobalLayoutListener(listener)
        appbarLayoutListener = listener
        Log.w("PROFILE_DIAG", "[Init] GlobalLayoutListener 已注册在 appbar_profile")
    }

    /** 诊断：连续若干帧采样，捕捉肉眼可见但生命周期日志漏掉的瞬时位移 */
    private fun sampleAppbarFrames(label: String, root: View, frames: Int) {
        if (frames <= 0) return
        var remain = frames
        val runner = object : Runnable {
            override fun run() {
                if (!isAdded || rootRef == null) return
                dumpAppbarState("$label#${frames - remain + 1}", root)
                remain--
                if (remain > 0) {
                    root.postOnAnimation(this)
                }
            }
        }
        root.postOnAnimation(runner)
    }

    private fun findProfileScrollContainer(root: View): NestedScrollView? {
        if (root is NestedScrollView) return root
        if (root !is ViewGroup) return null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (current is NestedScrollView) return current
            if (current is ViewGroup) {
                for (i in 0 until current.childCount) {
                    queue.add(current.getChildAt(i))
                }
            }
        }
        return null
    }

    override fun onDestroyView() {
        // ── 诊断：清理监听器，防止内存泄漏 ──
        rootRef?.findViewById<AppBarLayout>(R.id.appbar_profile)
            ?.viewTreeObserver
            ?.removeOnGlobalLayoutListener(appbarLayoutListener)
        appbarLayoutListener = null
        rootRef = null
        super.onDestroyView()
    }

    private fun applyProfileStatusBarStyle() {
        if (!isAdded) return
        StatusBarStyle.applyByColor(
            window = requireActivity().window,
            statusBarColor = Color.WHITE,
            decorFitsSystemWindows = false
        )
    }


    private fun setupMainSettings(view: View) {
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)
        view.findViewById<View>(R.id.layout_user_profile_entry)?.setOnClickListener {
            showEditUserProfileDialog()
        }
        refreshUserAvatarCard(view)

        // --- 悬浮窗及权限 ---
        btnRequestOverlay?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), GesturePermissionGuideActivity::class.java))
        }
        btnShowOverlay?.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(requireContext(), OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_OVERLAY
                }
                OverlayService.startCompat(requireContext(), intent)
                Utils.toast(requireContext(), "悬浮窗已开启")
            } else {
                Utils.toast(requireContext(), "请先授予悬浮窗权限")
            }
        }

        // --- 基础菜单跳转 ---
        view.findViewById<View>(R.id.btn_manage_categories).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:manage_categories", it)
                sampleAppbarFrames("BeforeStartActivityFrames:manage_categories", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_bill_display_settings).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:bill_display_settings", it)
                sampleAppbarFrames("BeforeStartActivityFrames:bill_display_settings", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), BillDisplaySettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_manage_currencies).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:manage_currencies", it)
                sampleAppbarFrames("BeforeStartActivityFrames:manage_currencies", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), CurrencyManagerActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_sensitivity).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:flip_sensitivity", it)
                sampleAppbarFrames("BeforeStartActivityFrames:flip_sensitivity", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), SensitivityActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_backup_restore).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:backup_restore", it)
                sampleAppbarFrames("BeforeStartActivityFrames:backup_restore", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        btnSyncRemoteConfig = view.findViewById(R.id.btn_sync_remote_config)
        dividerSyncRemoteConfig = view.findViewById(R.id.divider_sync_remote_config)
        refreshSyncRemoteConfigVisibility()
        btnSyncRemoteConfig?.setOnClickListener {
            CoroutineScope(Dispatchers.IO).launch {
                val ok = RemoteConfigManager.syncIfConfigured(requireContext())
                withContext(Dispatchers.Main) {
                    Utils.toast(requireContext(), if (ok) "远程配置已同步" else "远程配置同步失败，请检查网络")
                }
            }
        }
        shizukuModeRow = view.findViewById(R.id.layout_shizuku_mode_row)
        shizukuPersistenceDivider = view.findViewById(R.id.divider_shizuku_persistence)
        shizukuPersistenceRow = view.findViewById(R.id.layout_shizuku_persistence)
        refreshShizukuVisibility()
        val layoutAiFeatureEntry = view.findViewById<View>(R.id.layout_ai_feature_entry)
        view.findViewById<View>(R.id.layout_ai_feature_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), AiFeatureSettingsActivity::class.java))
        }
        val layoutStorageCleanupEntry = view.findViewById<View>(R.id.layout_storage_cleanup_entry)
        view.findViewById<View>(R.id.layout_storage_cleanup_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), StorageCleanupActivity::class.java))
        }

        // --- 白名单 ---
        val switchShowMultiCur = view.findViewById<CompoundButton>(R.id.switch_show_multi_cur)
        val btnManageCurrencies = view.findViewById<View>(R.id.btn_manage_currencies)
        val btnSensitivity = view.findViewById<View>(R.id.btn_sensitivity)
        val dividerAfterCurrencies = view.findViewById<View>(R.id.divider_after_currencies)
        val dividerAfterSensitivity = view.findViewById<View>(R.id.divider_after_sensitivity)
        val switchShowBookEntry = view.findViewById<CompoundButton>(R.id.switch_show_book_entry)
        val layoutShowBookEntryContainer = view.findViewById<View>(R.id.layout_show_book_entry_container)
        val dividerBeforeShowBookEntry = view.findViewById<View>(R.id.divider_before_show_book_entry)
        val switchShizukuMode = view.findViewById<CompoundButton>(R.id.switch_shizuku_mode)
        val layoutWhitelist = view.findViewById<View>(R.id.layout_whitelist_container)
        val switchWhitelistMode = view.findViewById<CompoundButton>(R.id.switch_whitelist_mode)
        val btnManageWhitelist = view.findViewById<View>(R.id.btn_manage_whitelist)
        var ignoreWhitelistToggle = false
        fun updateWhitelistUi() {
            val shizukuModeEnabled = switchShizukuMode.isChecked
            layoutWhitelist.visibility = if (shizukuModeEnabled) View.VISIBLE else View.GONE
            btnManageWhitelist.visibility = if (layoutWhitelist.visibility == View.VISIBLE && switchWhitelistMode.isChecked) View.VISIBLE else View.GONE
        }
        fun updateDataEntriesUi(multiCurrencyEnabled: Boolean) {
            btnSensitivity.visibility = View.VISIBLE
            btnManageCurrencies.visibility = if (multiCurrencyEnabled) View.VISIBLE else View.GONE
            dividerAfterCurrencies.visibility =
                if (multiCurrencyEnabled) View.VISIBLE else View.GONE
            dividerAfterSensitivity.visibility = View.VISIBLE
        }
        switchShowMultiCur.isChecked = Prefs.isShowMultiCurrency(requireContext())
        switchShowMultiCur.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setShowMultiCurrency(requireContext(), isChecked)
            updateDataEntriesUi(isChecked)
            refreshOverlayReminder(view, Prefs.isQuickGestureEnabled(requireContext()))
        }
        switchShowBookEntry.apply {
            isChecked = Prefs.isShowBookEntry(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowBookEntry(requireContext(), isChecked)
            }
        }
        val showBookEntrySetting = shouldShowBookEntrySetting(requireContext())
        layoutShowBookEntryContainer.visibility = if (showBookEntrySetting) View.VISIBLE else View.GONE
        dividerBeforeShowBookEntry.visibility = if (showBookEntrySetting) View.VISIBLE else View.GONE
        switchShizukuMode.apply {
            isChecked = Prefs.isShizukuModeEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShizukuModeEnabled(requireContext(), isChecked)
                if (!isChecked) {
                    ignoreWhitelistToggle = true
                    switchWhitelistMode.isChecked = false
                    ignoreWhitelistToggle = false
                    view.findViewById<CompoundButton>(R.id.switch_shizuku_persistence)?.isChecked = false
                    ShizukuRecoveryService.stop(requireContext())
                } else if (!ShizukuSafe.isReady(requireContext())) {
                    Utils.toast(requireContext(), "Shizuku 高级模式已开启，还需完成授权才能使用白名单")
                }
                updateShizukuPersistenceVisibility(view, isChecked)
                updateWhitelistUi()
                updateDoubleTapService(Prefs.isDoubleTapEnabled(requireContext()))
            }
        }
        // 初始化 Shizuku 持久化行的可见状态
        updateShizukuPersistenceVisibility(view, Prefs.isShizukuModeEnabled(requireContext()))
        switchWhitelistMode.apply {
            isChecked = false
            btnManageWhitelist.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                if (ignoreWhitelistToggle) return@setOnCheckedChangeListener
                if (!switchShizukuMode.isChecked) {
                    ignoreWhitelistToggle = true
                    post {
                        switchWhitelistMode.isChecked = false
                        ignoreWhitelistToggle = false
                    }
                    btnManageWhitelist.visibility = View.GONE
                    Utils.toast(requireContext(), "请先开启 Shizuku 高级模式")
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !ShizukuSafe.isReady(requireContext())) {
                    ignoreWhitelistToggle = true
                    post {
                        switchWhitelistMode.isChecked = false
                        ignoreWhitelistToggle = false
                    }
                    btnManageWhitelist.visibility = View.GONE
                    Utils.toast(requireContext(), "白名单模式需要先完成 Shizuku 授权")
                    com.taostudio.tapaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(requireContext())
                    return@setOnCheckedChangeListener
                }
                btnManageWhitelist.visibility = if (isChecked) View.VISIBLE else View.GONE
                updateDoubleTapService(Prefs.isDoubleTapEnabled(requireContext()))
                Utils.toast(context, if (isChecked) "已开启白名单模式" else "已恢复全局模式")
            }
        }
        updateWhitelistUi()

        btnManageWhitelist.setOnClickListener {
            if (!ShizukuSafe.isBinderAlive()) {
                Utils.toast(requireContext(), "请先完成 Shizuku 授权")
                return@setOnClickListener
            }
            if (!ShizukuSafe.hasPermission(requireContext())) {
                ShizukuSafe.requestPermission(requireActivity(), 101)
            } else {
                requireActivity().startActivity(Intent(requireContext(), AppListActivity::class.java))
            }
        }

        view.findViewById<CompoundButton>(R.id.switch_asset_feature).apply {
            isChecked = Prefs.isAssetFeatureEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setAssetFeatureEnabled(requireContext(), isChecked)
                (activity as? MainActivity)?.refreshBottomNavigationTabs()
            }
        }

        view.findViewById<CompoundButton>(R.id.switch_show_home_trend_card).apply {
            isChecked = Prefs.isShowHomeTrendCard(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                if (suppressHomeTrendCardSwitchCallback) return@setOnCheckedChangeListener
                Prefs.setShowHomeTrendCard(requireContext(), isChecked)
            }
        }

        // --- 高级留存设置 ---
        switchShowMultiCur.apply {
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowMultiCurrency(requireContext(), isChecked)
                updateDataEntriesUi(isChecked)
            }
        }

        val btnShareLogs = view.findViewById<View>(R.id.btn_share_logs)
        view.findViewById<CompoundButton>(R.id.switch_logging).apply {
            isChecked = Prefs.isLoggingEnabled(requireContext())
            btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setLoggingEnabled(requireContext(), isChecked)
                btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
                handleEasterEgg()
            }
        }

        btnShareLogs.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), LogViewerActivity::class.java))
        }

        view.findViewById<CompoundButton>(R.id.switch_shizuku_persistence).apply {
            isChecked = Prefs.isShizukuPersistenceEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !switchShizukuMode.isChecked) {
                    post { this.isChecked = false }
                    Utils.toast(requireContext(), "请先开启 Shizuku 高级模式")
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !ShizukuSafe.isReady(requireContext())) {
                    post { this.isChecked = false }
                    Utils.toast(requireContext(), "自动恢复需要先启动并授权 Shizuku")
                    com.taostudio.tapaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(requireContext())
                    return@setOnCheckedChangeListener
                }
                Prefs.setShizukuPersistenceEnabled(requireContext(), isChecked)
                if (isChecked) {
                    val started = ShizukuRecoveryService.ensureStarted(requireContext())
                    Utils.toast(
                        requireContext(),
                        if (started) "已开启 Shizuku 自动恢复" else "自动恢复启动失败，请检查 Shizuku"
                    )
                } else {
                    ShizukuRecoveryService.stop(requireContext())
                    Utils.toast(requireContext(), "已关闭 Shizuku 自动恢复")
                }
            }
        }

        // 让包含开关的整行都可点击：点击行会触发对应的开关（不会破坏已有的 setOnCheckedChangeListener）
        try {
            val toggleIds = intArrayOf(
                R.id.switch_show_book_entry,
                R.id.switch_shizuku_mode,
                R.id.switch_asset_feature,
                R.id.switch_whitelist_mode,
                R.id.switch_screen_accounting,
                R.id.switch_show_home_trend_card,
                R.id.switch_show_multi_cur,
                R.id.switch_logging,
                R.id.switch_shizuku_persistence
            )

            for (tid in toggleIds) {
                val sw = view.findViewById<CompoundButton>(tid) ?: continue
                val parent = sw.parent as? View
                if (parent != null) {
                    parent.isClickable = true
                    parent.isFocusable = true
                    parent.setOnClickListener { sw.performClick() }
                }
            }
        } catch (e: Exception) {
            // 防御性：如果运行时出错，不影响主流程
            e.printStackTrace()
        }
    }

    private fun refreshHomeTrendCardSwitch(root: View) {
        val switch = root.findViewById<CompoundButton>(R.id.switch_show_home_trend_card) ?: return
        val targetChecked = Prefs.isShowHomeTrendCard(requireContext())
        suppressHomeTrendCardSwitchCallback = true
        try {
            if (switch.isChecked != targetChecked) {
                switch.isChecked = targetChecked
            }
        } finally {
            suppressHomeTrendCardSwitchCallback = false
        }
    }

    private fun updateShowBookEntrySettingVisibility(root: View) {
        val show = shouldShowBookEntrySetting(requireContext())
        root.findViewById<View>(R.id.layout_show_book_entry_container)?.visibility =
            if (show) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.divider_before_show_book_entry)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun shouldShowBookEntrySetting(context: Context): Boolean {
        val hasMultipleBooks = BookAccountManager.getBookAccounts(context).size > 1
        if (!hasMultipleBooks) return false
        val usingTraditionalEntry = Prefs.getAiEntryMode(context) == Prefs.AI_ENTRY_MODE_TRADITIONAL
        val quickOverlayEnabled = Prefs.isQuickGestureEnabled(context)
        return usingTraditionalEntry || quickOverlayEnabled
    }

    private fun refreshOverlayReminder(view: View, quickGestureEnabled: Boolean) {
        val card = view.findViewById<View>(R.id.card_overlay_reminder) ?: return
        val tvTitle = view.findViewById<TextView>(R.id.tv_overlay_reminder_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_overlay_reminder_desc)
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)

        val hasOverlayPermission = Settings.canDrawOverlays(requireContext())
        val batteryReady = isIgnoringBatteryOptimizations()

        // 仅在「快捷手势已开启 且核心权限未完成」时显示提醒
        if (!quickGestureEnabled || (hasOverlayPermission && batteryReady)) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        tvTitle?.text = "快捷记账还差一步"

        tvDesc?.text = when {
            !hasOverlayPermission -> "开启悬浮窗后，翻转或敲击才能弹出记账面板。"
            !batteryReady -> "把后台运行设为不受限制后，手势会更稳定。"
            else -> "完成准备项后，翻转和敲击会更稳定。"
        }
        btnRequestOverlay?.isEnabled = true
        btnRequestOverlay?.alpha = 1f
        btnRequestOverlay?.text = "查看准备项"
        btnShowOverlay?.visibility = View.GONE
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return runCatching {
            val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)
        }.getOrDefault(false)
    }

    private fun promptOverlayPermissionDialog() {
        if (!isAdded) return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("需要悬浮窗权限")
            .setMessage("已开启快捷手势。请先授予悬浮窗权限，否则无法正常弹出记账界面。")
            .setPositiveButton("去开启") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                requireActivity().startActivity(intent)
            }
            .setNegativeButton("稍后", null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = requireContext(),
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showDoubleTapGuideDialog() {
        if (!isAdded) return
        Prefs.setDoubleTapGuideSeen(requireContext())
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("双击背板记账已开启")
            .setMessage("用指尖快速敲击手机背面两次，即可唤起记账界面。\n\n" +
                    "提示：\n" +
                    "• 请用指尖敲击，力度适中\n" +
                    "• 如果检测不灵敏，可尝试调整敲击位置\n" +
                    "• 手机壳过厚可能影响检测效果")
            .setPositiveButton("知道了", null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = requireContext(),
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun checkAndRequestPermissions() {
        // no-op: tap/flip 前台服务不再主动申请通知运行时权限
    }

    private fun refreshUserAvatarCard(root: View) {
        val ivAvatar = root.findViewById<ImageView>(R.id.iv_profile_user_avatar) ?: return
        val tvName = root.findViewById<TextView>(R.id.tv_profile_user_name)
        val tvDesc = root.findViewById<TextView>(R.id.tv_profile_user_avatar_desc)
        tvName?.text = Prefs.getUserChatName(requireContext())
        tvDesc?.text = Prefs.getUserProfileDesc(requireContext())
        val path = Prefs.getUserChatAvatarPath(requireContext())
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = ivAvatar,
                file = file,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar_default)
        }
    }

    private var btnSyncRemoteConfig: View? = null
    private var dividerSyncRemoteConfig: View? = null
    private var shizukuModeRow: View? = null
    private var shizukuPersistenceDivider: View? = null
    private var shizukuPersistenceRow: View? = null

    private fun processProfilePassword(rawDesc: String): String {
        val ctx = requireContext()
        var clean = rawDesc
        var changed = false

        if (rawDesc.contains("5201314")) {
            clean = clean.replace("5201314", "")
            Prefs.setApiConfigUnlocked(ctx, true)
            changed = true
            refreshSyncRemoteConfigVisibility()
            showApiKeyDialog()
            Utils.toast(ctx, "API配置已解锁")
        }
        if (rawDesc.contains("shizuku")) {
            clean = clean.replace("shizuku", "")
            Prefs.setShizukuUnlocked(ctx, true)
            changed = true
            refreshShizukuVisibility()
            Utils.toast(ctx, "Shizuku 模式已解锁")
        }

        return if (changed && clean.isBlank()) "" else clean.trim()
    }

    private fun refreshSyncRemoteConfigVisibility() {
        val show = Prefs.isApiConfigUnlocked(requireContext()) && RemoteConfigManager.isConfigUrlConfigured()
        btnSyncRemoteConfig?.visibility = if (show) View.VISIBLE else View.GONE
        dividerSyncRemoteConfig?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun refreshShizukuVisibility() {
        val show = Prefs.isShizukuUnlocked(requireContext())
        shizukuModeRow?.visibility = if (show) View.VISIBLE else View.GONE
        shizukuPersistenceDivider?.visibility = if (show) View.VISIBLE else View.GONE
        shizukuPersistenceRow?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun handleEasterEgg() {
        val key = "logging"
        val now = SystemClock.elapsedRealtime()
        val tracker = toggleTimers.getOrPut(key) { ToggleTracker() }
        if (now - tracker.firstToggleAtMs > 60_000) {
            tracker.count = 0
            tracker.firstToggleAtMs = now
        }
        tracker.count++
        if (tracker.count >= 10) {
            tracker.count = 0
            val ctx = requireContext()
            val newState = !Prefs.isApiConfigUnlocked(ctx)
            Prefs.setApiConfigUnlocked(ctx, newState)
            Prefs.setAiDetailConfigUnlocked(ctx, newState)
            Prefs.setShizukuUnlocked(ctx, newState)
            refreshSyncRemoteConfigVisibility()
            refreshShizukuVisibility()
            Utils.toast(ctx, if (newState) "隐藏功能已全部解锁" else "隐藏功能已全部锁定")
        }
    }

    private fun showApiKeyDialog() {
        val ctx = requireContext()
        val builder = AlertDialog.Builder(ctx)
        builder.setTitle(getString(R.string.profile_config_api_key))
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvHint = TextView(ctx).apply {
            text = getString(R.string.profile_api_key_hint)
            textSize = 12f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 16)
        }
        val etUrl = EditText(ctx).apply {
            hint = getString(R.string.profile_api_url_hint)
            setText(Prefs.getAiUrl(ctx).ifBlank { "https://api.siliconflow.cn" })
        }
        val etKey = EditText(ctx).apply {
            hint = getString(R.string.api_key)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(Prefs.getAiKey(ctx))
        }
        layout.addView(tvHint)
        layout.addView(etUrl)
        layout.addView(etKey)
        builder.setView(layout)
        builder.setPositiveButton(getString(R.string.save_btn)) { _, _ ->
            Prefs.setAiUrl(ctx, etUrl.text.toString().trim())
            Prefs.setAiKey(ctx, etKey.text.toString().trim())
            Utils.toast(ctx, getString(R.string.profile_api_key_saved))
            refreshSyncRemoteConfigVisibility()
        }
        builder.setNeutralButton(getString(R.string.test_connection_btn)) { _, _ ->
            val url = etUrl.text.toString().trim()
            val key = etKey.text.toString().trim()
            if (key.isBlank()) {
                Utils.toast(ctx, getString(R.string.please_input_api_key))
                return@setNeutralButton
            }
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsWithDetails(url, key)
                    withContext(Dispatchers.Main) {
                        if (models.isNotEmpty()) {
                            Utils.toast(ctx, "连接成功，获取到 ${models.size} 个模型")
                        } else {
                            Utils.toast(ctx, "连接成功，但未获取到模型列表")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, "连接失败，请检查 API 地址和 Key")
                    }
                }
            }
        }
        builder.setNegativeButton(getString(R.string.cancel_btn), null)
        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.85f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showEditUserProfileDialog() {
        val ctx = requireContext()
        val view = layoutInflater.inflate(R.layout.dialog_edit_user_profile, null)
        val ivAvatar = view.findViewById<ImageView>(R.id.iv_user_profile_avatar)
        val etName = view.findViewById<EditText>(R.id.et_user_profile_name)
        val etDesc = view.findViewById<EditText>(R.id.et_user_profile_desc)
        pendingEditUserAvatarView = ivAvatar

        val currentName = Prefs.getUserChatName(ctx).ifBlank { "我" }
        etName.setText(currentName)
        etName.setSelection(currentName.length)
        val currentDesc = Prefs.getUserProfileDesc(ctx).ifBlank { "点击设置名字和头像" }
        etDesc.setText(currentDesc)
        etDesc.setSelection(currentDesc.length)

        val avatarPath = Prefs.getUserChatAvatarPath(ctx)
        val avatarFile = if (avatarPath.isNotBlank()) File(avatarPath) else null
        if (avatarFile != null && avatarFile.exists()) {
            GlideLocalFiles.load(
                target = ivAvatar,
                file = avatarFile,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar_default)
        }

        ivAvatar.setOnClickListener { pickUserAvatarLauncher.launch("image/*") }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("编辑个人资料")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val rawDesc = etDesc.text?.toString().orEmpty()
                val cleanDesc = processProfilePassword(rawDesc)
                Prefs.setUserChatName(ctx, etName.text?.toString().orEmpty())
                Prefs.setUserProfileDesc(ctx, cleanDesc)
                rootRef?.let { refreshUserAvatarCard(it) }
                Utils.toast(ctx, "个人资料已更新")
            }
            .create()
        dialog.setOnDismissListener { pendingEditUserAvatarView = null }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun startUserAvatarCrop(sourceUri: Uri) {
        val ctx = requireContext()
        val destFile = File(ctx.cacheDir, "avatar_crop/profile_user_${System.currentTimeMillis()}.jpg")
            .also { it.parentFile?.mkdirs() }
        val destUri = Uri.fromFile(destFile)
        val options = UCrop.Options().apply {
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(92)
            setHideBottomControls(false)
            setFreeStyleCropEnabled(false)
            setShowCropGrid(true)
            setShowCropFrame(true)
            setToolbarTitle("裁剪用户头像")
            setToolbarColor(android.graphics.Color.parseColor("#1A73E8"))
            setStatusBarColor(android.graphics.Color.parseColor("#1A73E8"))
            setToolbarWidgetColor(android.graphics.Color.WHITE)
        }
        val intent = UCrop.of(sourceUri, destUri)
            .withAspectRatio(1f, 1f)
            .withMaxResultSize(1080, 1080)
            .withOptions(options)
            .getIntent(ctx)
        userAvatarCropLauncher.launch(intent)
    }

    private fun saveUserAvatar(uri: Uri) {
        runCatching {
            val ctx = requireContext()
            val destFile = File(ctx.filesDir, "chat_user_avatar.jpg")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
            Prefs.setUserChatAvatarPath(ctx, destFile.absolutePath)
            pendingEditUserAvatarView?.let {
                GlideLocalFiles.load(
                    target = it,
                    file = destFile,
                    placeholderRes = R.drawable.ic_user_avatar_default,
                    circleCrop = true,
                    overrideSize = 128
                )
            }
            rootRef?.let { refreshUserAvatarCard(it) }
            Utils.toast(ctx, "用户头像已更新")
        }.onFailure {
            if (isAdded) Utils.toast(requireContext(), "头像更新失败，请重试")
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("需要忽略电池优化")
                .setMessage("为了保证敲敲记账在后台不被系统休眠中断，请允许应用忽略电池优化。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + requireContext().packageName)
                    }
                    requireActivity().startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .create()
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = requireContext(),
                cancelOnTouchOutside = true,
                useSolidPanelBackground = true
            )
        }
    }

    private fun updateDoubleTapService(isEnabled: Boolean) {
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            action = if (isEnabled) OverlayService.ACTION_START_DOUBLE_TAP else OverlayService.ACTION_STOP_DOUBLE_TAP
        }
        OverlayService.startCompat(requireContext(), intent)
    }

    private fun updateShizukuPersistenceVisibility(view: View, shizukuEnabled: Boolean) {
        val visibility = if (shizukuEnabled) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.layout_shizuku_persistence)?.visibility = visibility
        view.findViewById<View>(R.id.divider_shizuku_persistence)?.visibility = visibility
    }

}

