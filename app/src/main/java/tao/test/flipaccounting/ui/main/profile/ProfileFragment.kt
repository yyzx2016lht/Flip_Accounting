package tao.test.flipaccounting.ui.main.profile

import android.app.Activity
import android.os.Bundle
import android.net.Uri
import android.app.ActivityManager
import tao.test.flipaccounting.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import android.Manifest
import androidx.appcompat.app.AlertDialog
import android.content.Context
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.ui.CurrencyManagerActivity
import com.google.android.material.button.MaterialButton
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import tao.test.flipaccounting.R
import tao.test.flipaccounting.ui.FlipSensitivityActivity
import android.util.Log
import android.view.ViewTreeObserver
import com.google.android.material.appbar.AppBarLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import com.yalantis.ucrop.UCrop
import java.io.File
import java.io.FileOutputStream
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

class ProfileFragment : Fragment(R.layout.fragment_profile) {

    private var rootRef: View? = null
    private var suppressHomeTrendCardSwitchCallback = false
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
            if (error != null) Utils.toast(requireContext(), "头像裁剪失败: ${error.message ?: "未知错误"}")
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootRef = view

        checkAndRequestPermissions()
        setupMainSettings(view)

        // ── 诊断日志：追踪 AppBarLayout 高度/padding 变化 ──
        startProfileLayoutDiagnostic(view)
    }

    override fun onResume() {
        super.onResume()
        rootRef?.let { refreshOverlayReminder(it, Prefs.isFlipEnabled(requireContext())) }
        rootRef?.findViewById<View>(R.id.layout_screen_accounting_container)?.visibility =
            if (
                Prefs.isFlipEnabled(requireContext()) &&
                Prefs.isShizukuModeEnabled(requireContext())
            ) View.VISIBLE else View.GONE
        rootRef?.findViewById<View>(R.id.tv_screen_accounting_hint)?.visibility = View.GONE
        rootRef?.findViewById<CompoundButton>(R.id.switch_screen_accounting)?.isChecked =
            Prefs.isShowScreenAccounting(requireContext())
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


    private fun setupMainSettings(view: View) {
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)
        view.findViewById<View>(R.id.layout_user_profile_entry)?.setOnClickListener {
            showEditUserProfileDialog()
        }
        refreshUserAvatarCard(view)

        // --- 悬浮窗及权限 ---
        btnRequestOverlay?.setOnClickListener {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                requireActivity().startActivity(intent)
            } else {
                Utils.toast(requireContext(), "悬浮窗权限已授予")
            }
        }
        btnShowOverlay?.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(requireContext(), OverlayService::class.java).apply {
                    action = OverlayService.ACTION_SHOW_OVERLAY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    requireContext().startForegroundService(intent)
                } else {
                    requireContext().startService(intent)
                }
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
        view.findViewById<View>(R.id.btn_manage_currencies).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:manage_currencies", it)
                sampleAppbarFrames("BeforeStartActivityFrames:manage_currencies", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), CurrencyManagerActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_flip_sensitivity).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:flip_sensitivity", it)
                sampleAppbarFrames("BeforeStartActivityFrames:flip_sensitivity", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), FlipSensitivityActivity::class.java))
        }
        view.findViewById<View>(R.id.btn_backup_restore).setOnClickListener {
            rootRef?.let {
                dumpAppbarState("BeforeStartActivity:backup_restore", it)
                sampleAppbarFrames("BeforeStartActivityFrames:backup_restore", it, 6)
            }
            requireActivity().startActivity(Intent(requireContext(), BackupActivity::class.java))
        }
        val switchEnableAiInPrefs = view.findViewById<CompoundButton>(R.id.switch_enable_ai_in_prefs)
        val layoutAiFeatureEntry = view.findViewById<View>(R.id.layout_ai_feature_entry)
        val dividerAiFeatureEntry = view.findViewById<View>(R.id.divider_ai_feature_entry)
        val layoutStorageCleanupEntry = view.findViewById<View>(R.id.layout_storage_cleanup_entry)
        val dividerStorageCleanupEntry = view.findViewById<View>(R.id.divider_storage_cleanup_entry)
        fun refreshAiFeatureEntryVisibility(enabled: Boolean) {
            val aiVisibility = if (enabled) View.VISIBLE else View.GONE
            layoutAiFeatureEntry.visibility = aiVisibility
            dividerAiFeatureEntry.visibility = aiVisibility
            layoutStorageCleanupEntry.visibility = View.VISIBLE
            dividerStorageCleanupEntry.visibility = View.VISIBLE
        }
        switchEnableAiInPrefs.apply {
            isChecked = Prefs.isShowAiText(requireContext())
            refreshAiFeatureEntryVisibility(isChecked)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiText(requireContext(), isChecked)
                refreshAiFeatureEntryVisibility(isChecked)
            }
        }
        view.findViewById<View>(R.id.layout_ai_feature_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), AiFeatureSettingsActivity::class.java))
        }
        view.findViewById<View>(R.id.layout_storage_cleanup_entry)?.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), StorageCleanupActivity::class.java))
        }

        // --- 翻转手势与白名单 ---
        val switchFlip = view.findViewById<CompoundButton>(R.id.switch_flip_trigger)
    val switchFlipDisableLandscape = view.findViewById<CompoundButton>(R.id.switch_flip_disable_landscape)
        val switchShizukuMode = view.findViewById<CompoundButton>(R.id.switch_shizuku_mode)
        val layoutWhitelist = view.findViewById<View>(R.id.layout_whitelist_container)
        val layoutScreenAccounting = view.findViewById<View>(R.id.layout_screen_accounting_container)
        val tvScreenAccountingHint = view.findViewById<View>(R.id.tv_screen_accounting_hint)
        val switchWhitelistMode = view.findViewById<CompoundButton>(R.id.switch_whitelist_mode)
        val switchScreenAccounting = view.findViewById<CompoundButton>(R.id.switch_screen_accounting)
        val btnManageWhitelist = view.findViewById<View>(R.id.btn_manage_whitelist)
        var ignoreWhitelistToggle = false
        var ignoreScreenAccountingToggle = false
        fun updateWhitelistUi() {
            val flipEnabled = switchFlip.isChecked
            val shizukuModeEnabled = switchShizukuMode.isChecked
            layoutWhitelist.visibility = if (flipEnabled && shizukuModeEnabled) View.VISIBLE else View.GONE
            layoutScreenAccounting.visibility = if (flipEnabled && shizukuModeEnabled) View.VISIBLE else View.GONE
            tvScreenAccountingHint.visibility = View.GONE
            btnManageWhitelist.visibility = if (layoutWhitelist.visibility == View.VISIBLE && switchWhitelistMode.isChecked) View.VISIBLE else View.GONE
        }
        switchScreenAccounting.apply {
            isChecked = Prefs.isShowScreenAccounting(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                if (ignoreScreenAccountingToggle) return@setOnCheckedChangeListener
                if (!switchShizukuMode.isChecked) {
                    ignoreScreenAccountingToggle = true
                    post {
                        switchScreenAccounting.isChecked = false
                        ignoreScreenAccountingToggle = false
                    }
                    Utils.toast(requireContext(), "请先开启 Shizuku 模式")
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !ShizukuSafe.isReady(requireContext())) {
                    ignoreScreenAccountingToggle = true
                    post {
                        switchScreenAccounting.isChecked = false
                        ignoreScreenAccountingToggle = false
                    }
                    Utils.toast(requireContext(), "截屏记账需要先启动并授权 Shizuku")
                    tao.test.flipaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(requireContext())
                    return@setOnCheckedChangeListener
                }
                Prefs.setShowScreenAccounting(requireContext(), isChecked)
                Utils.toast(context, if (isChecked) "已开启截屏记账按钮" else "已关闭截屏记账按钮")
            }
        }
        switchFlip.apply {
            isChecked = Prefs.isFlipEnabled(requireContext())
            refreshOverlayReminder(view, isChecked)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setFlipEnabled(requireContext(), isChecked)
                updateWhitelistUi()
                updateFlipService(isChecked)
                refreshOverlayReminder(view, isChecked)
                if (isChecked) {
                    if (!Settings.canDrawOverlays(requireContext())) {
                        Utils.toast(context, "请先开启悬浮窗权限，否则翻转记账无法唤起")
                        promptOverlayPermissionDialog()
                    }
                    // 仅在用户主动开启翻转手势时，提示后台常驻相关设置
                    checkBatteryOptimization()
                    Utils.toast(context, "翻转手势已启用")
                }
            }
        }
        switchFlipDisableLandscape.apply {
            isChecked = Prefs.isFlipDisableLandscape(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setFlipDisableLandscape(requireContext(), isChecked)
                Utils.toast(context, if (isChecked) "已开启横屏不检测" else "已关闭横屏不检测")
            }
        }
        switchShizukuMode.apply {
            isChecked = Prefs.isShizukuModeEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShizukuModeEnabled(requireContext(), isChecked)
                if (!isChecked) {
                    ignoreWhitelistToggle = true
                    switchWhitelistMode.isChecked = false
                    ignoreWhitelistToggle = false
                    Prefs.setFlipAlways(requireContext(), true)
                } else if (!ShizukuSafe.isReady(requireContext())) {
                    Utils.toast(requireContext(), "Shizuku 模式已开启，白名单模式需要先启动并授权 Shizuku")
                }
                updateShizukuPersistenceVisibility(view, isChecked)
                updateWhitelistUi()
                updateFlipService(switchFlip.isChecked)
            }
        }
        // 初始化 Shizuku 持久化行的可见状态
        updateShizukuPersistenceVisibility(view, Prefs.isShizukuModeEnabled(requireContext()))
        switchWhitelistMode.apply {
            isChecked = !Prefs.isFlipAlways(requireContext())
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
                    Utils.toast(requireContext(), "请先开启 Shizuku 模式")
                    return@setOnCheckedChangeListener
                }
                if (isChecked && !ShizukuSafe.isReady(requireContext())) {
                    ignoreWhitelistToggle = true
                    post {
                        switchWhitelistMode.isChecked = false
                        ignoreWhitelistToggle = false
                    }
                    btnManageWhitelist.visibility = View.GONE
                    Utils.toast(requireContext(), "白名单模式需要先启动并授权 Shizuku")
                    tao.test.flipaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(requireContext())
                    return@setOnCheckedChangeListener
                }
                Prefs.setFlipAlways(requireContext(), !isChecked)
                btnManageWhitelist.visibility = if (isChecked) View.VISIBLE else View.GONE
                updateFlipService(switchFlip.isChecked)
                Utils.toast(context, if (isChecked) "已开启白名单模式" else "已恢复全局模式")
            }
        }
        updateWhitelistUi()

        btnManageWhitelist.setOnClickListener {
            if (!ShizukuSafe.isBinderAlive()) {
                Utils.toast(requireContext(), "请先启动 Shizuku 并授权")
                return@setOnClickListener
            }
            if (!ShizukuSafe.hasPermission(requireContext())) {
                ShizukuSafe.requestPermission(requireActivity(), 101)
            } else {
                requireActivity().startActivity(Intent(requireContext(), AppListActivity::class.java))
            }
        }

        view.findViewById<CompoundButton>(R.id.switch_vibrate_feedback).apply {
            isChecked = Prefs.isVibrateFeedbackEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked -> Prefs.setVibrateFeedbackEnabled(requireContext(), isChecked) }
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
        view.findViewById<CompoundButton>(R.id.switch_show_multi_cur).apply {
            isChecked = Prefs.isShowMultiCurrency(requireContext())
            setOnCheckedChangeListener { _, isChecked -> Prefs.setShowMultiCurrency(requireContext(), isChecked) }
        }

        val btnShareLogs = view.findViewById<View>(R.id.btn_share_logs)
        view.findViewById<CompoundButton>(R.id.switch_logging).apply {
            isChecked = Prefs.isLoggingEnabled(requireContext())
            btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setLoggingEnabled(requireContext(), isChecked)
                btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        btnShareLogs.setOnClickListener {
            requireActivity().startActivity(Intent(requireContext(), LogViewerActivity::class.java))
        }

        view.findViewById<CompoundButton>(R.id.switch_permanent_wakelock).apply {
            isChecked = Prefs.isPermanentWakeLockEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked -> Prefs.setPermanentWakeLockEnabled(requireContext(), isChecked) }
        }

        view.findViewById<CompoundButton>(R.id.switch_shizuku_persistence).apply {
            isChecked = Prefs.isShizukuPersistenceEnabled(requireContext())
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShizukuPersistenceEnabled(requireContext(), isChecked)
                if (isChecked) {
                    Utils.toast(requireContext(), "已开启 Shizuku 深度保活，将在服务启动时生效")
                }
            }
        }

        // 让包含开关的整行都可点击：点击行会触发对应的开关（不会破坏已有的 setOnCheckedChangeListener）
        try {
            val toggleIds = intArrayOf(
                R.id.switch_flip_trigger,
                R.id.switch_shizuku_mode,
                R.id.switch_asset_feature,
                R.id.switch_whitelist_mode,
                R.id.switch_screen_accounting,
                R.id.switch_vibrate_feedback,
                R.id.switch_show_home_trend_card,
                R.id.switch_enable_ai_in_prefs,
                R.id.switch_show_multi_cur,
                R.id.switch_logging,
                R.id.switch_permanent_wakelock,
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

    private fun refreshOverlayReminder(view: View, flipEnabled: Boolean) {
        val card = view.findViewById<View>(R.id.card_overlay_reminder) ?: return
        val tvTitle = view.findViewById<TextView>(R.id.tv_overlay_reminder_title)
        val tvDesc = view.findViewById<TextView>(R.id.tv_overlay_reminder_desc)
        val btnRequestOverlay = view.findViewById<MaterialButton>(R.id.btnRequestOverlay)
        val btnShowOverlay = view.findViewById<MaterialButton>(R.id.btnShowOverlay)

        val hasOverlayPermission = Settings.canDrawOverlays(requireContext())

        // 仅在「翻转手势已开启 且 缺少悬浮窗权限」时显示提醒
        if (!flipEnabled || hasOverlayPermission) {
            card.visibility = View.GONE
            return
        }

        card.visibility = View.VISIBLE
        tvTitle?.text = "⚠ 翻转记账需要悬浮窗权限"

        tvDesc?.text = "当前未授予悬浮窗权限，翻转手势将无法正常唤起记账界面。"
        btnRequestOverlay?.isEnabled = true
        btnRequestOverlay?.alpha = 1f
        btnRequestOverlay?.text = "立即去开启"
        btnShowOverlay?.visibility = View.GONE
    }

    private fun promptOverlayPermissionDialog() {
        if (!isAdded) return
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("需要悬浮窗权限")
            .setMessage("已开启翻转记账。请先授予悬浮窗权限，否则无法正常弹出记账界面。")
            .setPositiveButton("去开启") { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                requireActivity().startActivity(intent)
            }
            .setNegativeButton("稍后", null)
            .create()
        showStyledCenterDialog(dialog)
    }

    private fun checkAndRequestPermissions() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (requireContext().checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 100)
        }
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
                circleCrop = true
            )
        } else {
            ivAvatar.setImageResource(R.drawable.ic_user_avatar_default)
        }
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
                circleCrop = true
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
                Prefs.setUserChatName(ctx, etName.text?.toString().orEmpty())
                Prefs.setUserProfileDesc(ctx, etDesc.text?.toString().orEmpty())
                rootRef?.let { refreshUserAvatarCard(it) }
                Utils.toast(ctx, "个人资料已更新")
            }
            .create()
        dialog.setOnDismissListener { pendingEditUserAvatarView = null }
        showStyledCenterDialog(dialog, widthRatio = 0.9f)
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
                    circleCrop = true
                )
            }
            rootRef?.let { refreshUserAvatarCard(it) }
            Utils.toast(ctx, "用户头像已更新")
        }.onFailure {
            if (isAdded) Utils.toast(requireContext(), "用户头像更新失败: ${it.message ?: "未知错误"}")
        }
    }

    private fun checkBatteryOptimization() {
        val powerManager = requireContext().getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(requireContext().packageName)) {
            val dialog = AlertDialog.Builder(requireContext())
                .setTitle("需要忽略电池优化")
                .setMessage("为了保证翻转记账在后台不被系统休眠中断，请允许应用忽略电池优化。")
                .setPositiveButton("去设置") { _, _ ->
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:" + requireContext().packageName)
                    }
                    requireActivity().startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .create()
            showStyledCenterDialog(dialog)
        }
    }

    private fun showStyledCenterDialog(dialog: AlertDialog, widthRatio: Float = 0.88f) {
        OverlayDialogs.showStyledCenterDialog(
            dialog = dialog,
            ctx = requireContext(),
            widthRatio = widthRatio,
            cancelOnTouchOutside = true,
            applyOverlayType = false,
            useSolidPanelBackground = true
        )
    }

    private fun updateFlipService(isEnabled: Boolean) {
        val intent = Intent(requireContext(), OverlayService::class.java).apply {
            action = if (isEnabled) OverlayService.ACTION_START_FLIP else OverlayService.ACTION_STOP_FLIP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requireContext().startForegroundService(intent)
        } else {
            requireContext().startService(intent)
        }
    }

    private fun updateShizukuPersistenceVisibility(view: View, shizukuEnabled: Boolean) {
        val visibility = if (shizukuEnabled) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.layout_shizuku_persistence)?.visibility = visibility
        view.findViewById<View>(R.id.divider_shizuku_persistence)?.visibility = visibility
    }

}


