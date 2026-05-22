package com.taostudio.tapaccounting.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.KeepAliveAccessibilityService
import com.taostudio.tapaccounting.KeepAliveDiagnostics
import com.taostudio.tapaccounting.OverlayService
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.RecentTasksHelper
import com.taostudio.tapaccounting.ShizukuSafe
import com.taostudio.tapaccounting.tap.TapActionRegistry
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import com.taostudio.tapaccounting.tap.TapModel
import java.util.Locale

class SensitivityActivity : AppCompatActivity() {
    private companion object {
        private const val TAP_RESTART_DEBOUNCE_MS = 1_000L
        private const val REQUEST_POST_NOTIFICATIONS = 7101
        private const val SCREEN_CAPTURE_ACTION_ID = "screen_capture"
    }

    private lateinit var cardFlipSensitivity: View
    private lateinit var cardTapSensitivity: View
    private lateinit var cardTapOptions: View
    private lateinit var cardKeepAlive: View
    private lateinit var rowLandscapeDisable: View
    private lateinit var rowHideRecents: View
    private lateinit var rowHideRecentsBottom: View
    private lateinit var rowVibrationFeedback: View
    private lateinit var rowSaveVibrate: View
    private lateinit var btnTapAdvancedToggle: View
    private lateinit var btnTapModel: View
    private lateinit var layoutTapNnapi: View
    private lateinit var layoutTapLowPower: View
    private lateinit var dividerBeforeLandscapeDisable: View
    private lateinit var dividerBeforeHideRecents: View
    private lateinit var dividerBeforeVibrationFeedback: View
    private lateinit var dividerBeforeSaveVibrate: View
    private lateinit var dividerAfterTapAdvancedToggle: View
    private lateinit var dividerAfterTapModel: View
    private lateinit var dividerAfterTapNnapi: View
    private lateinit var dividerAfterTapLowPower: View
    private lateinit var dividerAfterAccessibilityService: View
    private lateinit var tvTapAdvancedSummary: TextView
    private lateinit var ivTapAdvancedChevron: ImageView

    private lateinit var seekBarFlip: SeekBar
    private lateinit var tvFlipCurrentValue: TextView
    private lateinit var seekBarTap: SeekBar
    private lateinit var tvTapCurrentValue: TextView
    private lateinit var tvTapModelName: TextView
    private lateinit var tvTapActionDoubleName: TextView
    private lateinit var tvTapActionTripleName: TextView
    private lateinit var switchTapNnapi: SwitchMaterial
    private lateinit var switchTapLowPower: SwitchMaterial
    private lateinit var switchTapTriple: SwitchMaterial
    private lateinit var btnTapActionTriple: View
    private var isUpdatingUi = false

    private lateinit var switchFlipEnable: SwitchMaterial
    private lateinit var switchTapEnable: SwitchMaterial
    private lateinit var switchLandscapeDisable: SwitchMaterial
    private lateinit var switchHideRecents: SwitchMaterial
    private lateinit var switchVibrationFeedback: SwitchMaterial
    private lateinit var switchSaveVibrate: SwitchMaterial
    private lateinit var switchNotificationPermission: SwitchMaterial
    private lateinit var switchHideRecentsBottom: SwitchMaterial
    private var tapAdvancedExpanded = false
    private var isUpdatingHideRecentsSwitch = false
    private val tapRestartHandler = Handler(Looper.getMainLooper())
    private var tapRestartPending = false
    private var isUpdatingNotificationSwitch = false
    private val tapRestartRunnable = Runnable {
        tapRestartPending = false
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RESTART_DOUBLE_TAP
        }
        startServiceCompat(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensitivity)

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.toolbar).setOnClickListener { finish() }

        cardFlipSensitivity = findViewById(R.id.card_flip_sensitivity)
        cardTapSensitivity = findViewById(R.id.card_tap_sensitivity)
        cardTapOptions = findViewById(R.id.card_tap_options)
        cardKeepAlive = findViewById(R.id.card_keep_alive)
        rowLandscapeDisable = findViewById(R.id.row_landscape_disable)
        rowHideRecents = findViewById(R.id.row_hide_recents)
        rowHideRecentsBottom = findViewById(R.id.row_hide_recents_bottom)
        rowVibrationFeedback = findViewById(R.id.row_vibration_feedback)
        rowSaveVibrate = findViewById(R.id.row_save_vibrate)
        btnTapAdvancedToggle = findViewById(R.id.btn_tap_advanced_toggle)
        btnTapModel = findViewById(R.id.btn_tap_model)
        layoutTapNnapi = findViewById(R.id.layout_tap_nnapi)
        layoutTapLowPower = findViewById(R.id.layout_tap_low_power)
        dividerBeforeLandscapeDisable = findViewById(R.id.divider_before_landscape_disable)
        dividerBeforeHideRecents = findViewById(R.id.divider_before_hide_recents)
        dividerBeforeVibrationFeedback = findViewById(R.id.divider_before_vibration_feedback)
        dividerBeforeSaveVibrate = findViewById(R.id.divider_before_save_vibrate)
        dividerAfterTapAdvancedToggle = findViewById(R.id.divider_after_tap_advanced_toggle)
        dividerAfterTapModel = findViewById(R.id.divider_after_tap_model)
        dividerAfterTapNnapi = findViewById(R.id.divider_after_tap_nnapi)
        dividerAfterTapLowPower = findViewById(R.id.divider_after_tap_low_power)
        dividerAfterAccessibilityService = findViewById(R.id.divider_after_accessibility_service)
        tvTapAdvancedSummary = findViewById(R.id.tv_tap_advanced_summary)
        ivTapAdvancedChevron = findViewById(R.id.iv_tap_advanced_chevron)

        initGestureSwitches()
        initFlipViews()
        initTapViews()
        initTapOptions()
        initKeepAliveSettings()

        // 让包含开关的整行都可点击
        makeSwitchRowsClickable()
    }

    private fun initGestureSwitches() {
        switchFlipEnable = findViewById(R.id.switch_flip_enable)
        switchTapEnable = findViewById(R.id.switch_tap_enable)
        switchLandscapeDisable = findViewById(R.id.switch_landscape_disable)
        switchHideRecents = findViewById(R.id.switch_hide_recents)
        switchHideRecentsBottom = findViewById(R.id.switch_hide_recents_bottom)
        switchVibrationFeedback = findViewById(R.id.switch_vibration_feedback)
        switchSaveVibrate = findViewById(R.id.switch_save_vibrate)

        switchFlipEnable.isChecked = Prefs.isFlipEnabled(this)
        updateGestureDependentSections()
        switchFlipEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setFlipEnabled(this, isChecked)
            updateGestureDependentSections()
            val intent = Intent(this, OverlayService::class.java).apply {
                action = if (isChecked) OverlayService.ACTION_START_FLIP else OverlayService.ACTION_STOP_FLIP
            }
            startServiceCompat(intent)
        }

        switchTapEnable.isChecked = Prefs.isDoubleTapEnabled(this)
        updateGestureDependentSections()
        switchTapEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDoubleTapEnabled(this, isChecked)
            updateGestureDependentSections()
            val intent = Intent(this, OverlayService::class.java).apply {
                action = if (isChecked) OverlayService.ACTION_START_DOUBLE_TAP else OverlayService.ACTION_STOP_DOUBLE_TAP
            }
            startServiceCompat(intent)
            if (isChecked) {
                if (Prefs.getTapModel(this).isEmpty()) {
                    val recommended = TapModel.recommend(this)
                    Prefs.setTapModel(this, recommended.path)
                }
            }
        }

        switchLandscapeDisable.isChecked = Prefs.isDisableLandscape(this)
        switchLandscapeDisable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDisableLandscape(this, isChecked)
            restartTapDetection()
        }

        switchHideRecents.isChecked = Prefs.isHideRecents(this)
        switchHideRecentsBottom.isChecked = Prefs.isHideRecents(this)
        switchHideRecents.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingHideRecentsSwitch) return@setOnCheckedChangeListener
            syncHideRecentsSwitches(isChecked)
        }
        switchHideRecentsBottom.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingHideRecentsSwitch) return@setOnCheckedChangeListener
            syncHideRecentsSwitches(isChecked)
        }

        switchVibrationFeedback.isChecked = Prefs.isVibrateFeedbackEnabled(this)
        switchVibrationFeedback.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setVibrateFeedbackEnabled(this, isChecked)
        }

        switchSaveVibrate.isChecked = Prefs.isSaveVibrateEnabled(this)
        switchSaveVibrate.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setSaveVibrateEnabled(this, isChecked)
        }
    }

    private fun updateGestureDependentSections() {
        val flipEnabled = Prefs.isFlipEnabled(this)
        val tapEnabled = Prefs.isDoubleTapEnabled(this)
        val anyGestureEnabled = flipEnabled || tapEnabled

        cardFlipSensitivity.visibility = if (flipEnabled) View.VISIBLE else View.GONE
        cardTapSensitivity.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        cardTapOptions.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        cardKeepAlive.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE

        rowLandscapeDisable.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        dividerBeforeLandscapeDisable.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        rowHideRecents.visibility = View.GONE
        dividerBeforeHideRecents.visibility = View.GONE
        rowHideRecentsBottom.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        dividerAfterAccessibilityService.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        rowVibrationFeedback.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        dividerBeforeVibrationFeedback.visibility = if (tapEnabled) View.VISIBLE else View.GONE
        rowSaveVibrate.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
        dividerBeforeSaveVibrate.visibility = if (anyGestureEnabled) View.VISIBLE else View.GONE
    }

    private fun syncHideRecentsSwitches(enabled: Boolean) {
        isUpdatingHideRecentsSwitch = true
        if (switchHideRecents.isChecked != enabled) switchHideRecents.isChecked = enabled
        if (switchHideRecentsBottom.isChecked != enabled) switchHideRecentsBottom.isChecked = enabled
        isUpdatingHideRecentsSwitch = false

        Prefs.setHideRecents(this, enabled)
        RecentTasksHelper.applyHideRecentsPreference(this)
        Toast.makeText(
            this,
            if (enabled) "已尝试隐藏后台卡片" else "已恢复后台卡片显示",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun makeSwitchRowsClickable() {
        val toggleIds = intArrayOf(
            R.id.switch_flip_enable,
            R.id.switch_tap_enable,
            R.id.switch_landscape_disable,
            R.id.switch_hide_recents_bottom,
            R.id.switch_vibration_feedback,
            R.id.switch_save_vibrate,
            R.id.switch_notification_permission
        )
        for (tid in toggleIds) {
            val sw = findViewById<CompoundButton>(tid) ?: continue
            val parent = sw.parent as? View
            if (parent != null) {
                parent.isClickable = true
                parent.isFocusable = true
                parent.setOnClickListener { sw.performClick() }
            }
        }
    }

    private fun startServiceCompat(intent: Intent) {
        OverlayService.startCompat(this, intent)
    }

    private fun initFlipViews() {
        seekBarFlip = findViewById(R.id.seekBarFlipSensitivity)
        tvFlipCurrentValue = findViewById(R.id.tvFlipCurrentValue)

        seekBarFlip.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateFlipUI(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { level ->
                    Prefs.setFlipSensitivity(this@SensitivityActivity, level)
                }
            }
        })
    }

    private fun initTapViews() {
        seekBarTap = findViewById(R.id.seekBarTapSensitivity)
        tvTapCurrentValue = findViewById(R.id.tvTapCurrentValue)

        seekBarTap.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTapUI(progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar?.progress?.let { level ->
                    Prefs.setTapSensitivityLevel(this@SensitivityActivity, level)
                    restartTapDetection()
                }
            }
        })
    }

    private fun initTapOptions() {
        tvTapModelName = findViewById(R.id.tv_tap_model_name)
        tvTapActionDoubleName = findViewById(R.id.tv_tap_action_double_name)
        tvTapActionTripleName = findViewById(R.id.tv_tap_action_triple_name)
        switchTapNnapi = findViewById(R.id.switch_tap_nnapi)
        switchTapLowPower = findViewById(R.id.switch_tap_low_power)
        switchTapTriple = findViewById(R.id.switch_tap_triple)
        btnTapActionTriple = findViewById(R.id.btn_tap_action_triple)
        val layoutTapTriple = findViewById<View>(R.id.layout_tap_triple)

        layoutTapNnapi.setOnClickListener { if (switchTapNnapi.isEnabled) switchTapNnapi.performClick() }
        layoutTapTriple.setOnClickListener { if (switchTapTriple.isEnabled) switchTapTriple.performClick() }
        layoutTapLowPower.setOnClickListener { switchTapLowPower.performClick() }
        btnTapAdvancedToggle.setOnClickListener {
            tapAdvancedExpanded = !tapAdvancedExpanded
            updateTapAdvancedVisibility()
        }

        // 模型选择
        tvTapModelName.text = TapModel.resolve(this).displayName
        btnTapModel.setOnClickListener {
            val models = TapModel.values()
            val currentIdx = models.indexOf(TapModel.resolve(this)).coerceAtLeast(0)
            showSelectionDialog(
                title = "选择模型（设备尺寸）",
                items = models.map { SelectionItem(it.displayName, "${it.screenInches} 寸") },
                selectedIndex = currentIdx
            ) { which ->
                Prefs.setTapModel(this, models[which].path)
                tvTapModelName.text = models[which].displayName
                restartTapDetection()
            }
        }

        // NNAPI 低功耗
        switchTapNnapi.isChecked = Prefs.isTapNnapiLowPower(this)
        switchTapNnapi.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapNnapiLowPower(this, isChecked)
            restartTapDetection()
        }

        // 省电检测模式
        switchTapLowPower.isChecked = Prefs.isTapLowPower(this)
        switchTapLowPower.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setTapLowPower(this, isChecked)
            updateLowPowerDependencies()
            restartTapDetection()
        }

        // 三击模式
        switchTapTriple.isChecked = Prefs.isTapTripleEnabled(this)
        btnTapActionTriple.visibility = if (Prefs.isTapTripleEnabled(this)) View.VISIBLE else View.GONE
        switchTapTriple.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@setOnCheckedChangeListener
            Prefs.setTapTripleEnabled(this, isChecked)
            btnTapActionTriple.visibility = if (isChecked) View.VISIBLE else View.GONE
            restartTapDetection()
        }

        // 初始同步
        updateLowPowerDependencies()
        updateTapAdvancedVisibility()

        // 双击动作
        val doubleActionId = Prefs.getTapActionDouble(this)
        tvTapActionDoubleName.text = getVisibleTapActionName(doubleActionId)
        findViewById<View>(R.id.btn_tap_action_double).setOnClickListener {
            showTapActionDialog(
                title = "双击动作",
                currentActionId = Prefs.getTapActionDouble(this),
                targetView = tvTapActionDoubleName,
                onSelected = { Prefs.setTapActionDouble(this, it) }
            )
        }

        // 三击动作
        val tripleActionId = Prefs.getTapActionTriple(this)
        tvTapActionTripleName.text = getVisibleTapActionName(tripleActionId)
        findViewById<View>(R.id.btn_tap_action_triple).setOnClickListener {
            showTapActionDialog(
                title = "三击动作",
                currentActionId = Prefs.getTapActionTriple(this),
                targetView = tvTapActionTripleName,
                onSelected = { Prefs.setTapActionTriple(this, it) }
            )
        }
    }

    private data class SelectionItem(val title: String, val subtitle: String = "")

    private fun getVisibleTapActions() =
        TapActionRegistry.getAll().filter { action ->
            action.id != SCREEN_CAPTURE_ACTION_ID || isScreenAccountingAvailableForTapAction()
        }

    private fun getVisibleTapActionName(actionId: String): String {
        val action = TapActionRegistry.findById(actionId) ?: return "未设置"
        if (action.id == SCREEN_CAPTURE_ACTION_ID && !isScreenAccountingAvailableForTapAction()) {
            return "未设置"
        }
        return action.displayName
    }

    private fun isScreenAccountingAvailableForTapAction(): Boolean {
        val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val hasShizuku = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)
        return Prefs.isShowScreenAccounting(this) && (hasAccessibility || hasShizuku)
    }

    private fun showTapActionDialog(
        title: String,
        currentActionId: String,
        targetView: TextView,
        onSelected: (String) -> Unit
    ) {
        val actions = getVisibleTapActions()
        val ids = listOf("") + actions.map { it.id }
        val items = listOf(SelectionItem("无", "不执行任何操作")) +
            actions.map { SelectionItem(it.displayName, it.description) }
        val currentIdx = ids.indexOf(currentActionId).coerceAtLeast(0)
        showSelectionDialog(
            title = title,
            items = items,
            selectedIndex = currentIdx
        ) { which ->
            val selectedId = ids[which]
            onSelected(selectedId)
            targetView.text = items[which].title
        }
    }

    private fun showSelectionDialog(
        title: String,
        items: List<SelectionItem>,
        selectedIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_option_picker, null, false)
        panel.findViewById<TextView>(R.id.tv_option_picker_title).text = title
        panel.findViewById<TextView>(R.id.tv_option_picker_desc).visibility = View.GONE

        val listView = panel.findViewById<ListView>(R.id.lv_option_picker)
        val adapter = SelectionAdapter(items, selectedIndex)
        listView.adapter = adapter
        listView.divider = android.graphics.drawable.ColorDrawable(Color.parseColor("#12000000"))
        listView.dividerHeight = 1

        val maxHeight = (resources.displayMetrics.heightPixels * 0.42f).toInt()
        val estimatedItemHeight = (64 * resources.displayMetrics.density).toInt()
        val estimatedContentHeight = (items.size * estimatedItemHeight).coerceAtLeast(dp(1))
        listView.layoutParams = listView.layoutParams.apply {
            height = maxHeight.coerceAtMost(estimatedContentHeight)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in items.indices) {
                dialog.dismiss()
                onSelected(position)
            }
        }
        panel.findViewById<TextView>(R.id.btn_option_picker_cancel).setOnClickListener { dialog.dismiss() }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private inner class SelectionAdapter(
        private val items: List<SelectionItem>,
        private val selectedIndex: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): SelectionItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@SensitivityActivity)
                .inflate(R.layout.item_dialog_option_picker, parent, false)
            val titleView = view.findViewById<TextView>(R.id.tv_option_title)
            val subtitleView = view.findViewById<TextView>(R.id.tv_option_subtitle)
            val checkView = view.findViewById<TextView>(R.id.tv_option_check)
            val riskView = view.findViewById<TextView>(R.id.tv_option_risk)

            val item = getItem(position)
            titleView.text = item.title
            subtitleView.text = item.subtitle
            subtitleView.visibility = if (item.subtitle.isBlank()) View.GONE else View.VISIBLE
            checkView.visibility = if (position == selectedIndex) View.VISIBLE else View.GONE
            riskView.visibility = View.GONE
            return view
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadData() {
        val flipLevel = Prefs.getFlipSensitivity(this)
        seekBarFlip.progress = flipLevel
        updateFlipUI(flipLevel)

        if (Prefs.isDoubleTapEnabled(this)) {
            val tapLevel = Prefs.getTapSensitivityLevel(this)
            seekBarTap.progress = tapLevel
            updateTapUI(tapLevel)
        }
    }

    private fun initKeepAliveSettings() {
        // 电池优化豁免
        findViewById<View>(R.id.btn_battery_optimization)?.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
            }
        }

        // 通知权限
        switchNotificationPermission = findViewById(R.id.switch_notification_permission)
        switchNotificationPermission.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingNotificationSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                requestNotificationPermissionOrOpenSettings()
            } else {
                Toast.makeText(this, "请在系统通知设置中关闭通知权限", Toast.LENGTH_SHORT).show()
                openNotificationSettings()
            }
        }

        // 无障碍服务
        val btnAccessibility = findViewById<View>(R.id.btn_accessibility_service)
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)

        fun refreshAccessibilityStatus() {
            val isEnabled = KeepAliveAccessibilityService.isServiceEnabled()
            tvAccessibilityStatus?.text = if (isEnabled) "已开启" else "用于截屏记账和后台保活"
            (btnAccessibility as? MaterialButton)?.text = if (isEnabled) "已开启" else "去开启"
        }

        btnAccessibility?.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
            }
        }

        refreshAccessibilityStatus()
        refreshNotificationStatus()
    }

    override fun onResume() {
        super.onResume()
        KeepAliveDiagnostics.logSnapshot(this, "sensitivity-onResume")
        // 刷新无障碍服务和通知状态
        val tvAccessibilityStatus = findViewById<TextView>(R.id.tv_accessibility_status)
        val btnAccessibility = findViewById<View>(R.id.btn_accessibility_service)
        val isEnabled = KeepAliveAccessibilityService.isServiceEnabled()
        tvAccessibilityStatus?.text = if (isEnabled) "已开启" else "用于截屏记账和后台保活"
        (btnAccessibility as? MaterialButton)?.text = if (isEnabled) "已开启" else "去开启"

        refreshNotificationStatus()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            refreshNotificationStatus()
        }
    }

    private fun refreshNotificationStatus() {
        val notificationReady = isNotificationPermissionReady()
        findViewById<TextView>(R.id.tv_notification_status)?.text =
            if (notificationReady) "通知权限已开启，可显示前台服务和录音状态" else "通知权限已关闭；开启后可显示服务状态"
        if (::switchNotificationPermission.isInitialized) {
            isUpdatingNotificationSwitch = true
            switchNotificationPermission.isChecked = notificationReady
            isUpdatingNotificationSwitch = false
        }
    }

    private fun requestNotificationPermissionOrOpenSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
            return
        }

        if (!isNotificationPermissionReady()) {
            openNotificationSettings()
        } else {
            refreshNotificationStatus()
        }
    }

    private fun openNotificationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开通知设置", Toast.LENGTH_SHORT).show()
            refreshNotificationStatus()
        }
    }

    private fun isNotificationPermissionReady(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val appNotificationsEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return permissionGranted && appNotificationsEnabled && channelEnabled
    }

    private fun updateFlipUI(level: Int) {
        val label = when (level) {
            in 0..19 -> "非常稳"
            in 20..39 -> "偏稳"
            in 40..60 -> "中等"
            in 61..80 -> "灵敏"
            else -> "非常灵敏"
        }
        tvFlipCurrentValue.text = "当前等级：$label（$level）"
    }

    private fun updateTapUI(level: Int) {
        val label = when (level) {
            in 0..1 -> "非常低"
            in 2..3 -> "低"
            in 4..5 -> "中等"
            in 6..7 -> "高"
            in 8..10 -> "非常高"
            else -> "中等"
        }
        tvTapCurrentValue.text = "当前等级：$label"
    }

    private fun updateLowPowerDependencies() {
        val lowPower = Prefs.isTapLowPower(this)
        switchTapNnapi.isEnabled = !lowPower
        switchTapTriple.isEnabled = true
        btnTapActionTriple.visibility = if (Prefs.isTapTripleEnabled(this)) View.VISIBLE else View.GONE
    }

    private fun updateTapAdvancedVisibility() {
        val visibility = if (tapAdvancedExpanded) View.VISIBLE else View.GONE
        btnTapModel.visibility = visibility
        layoutTapNnapi.visibility = visibility
        layoutTapLowPower.visibility = visibility
        dividerAfterTapAdvancedToggle.visibility = visibility
        dividerAfterTapModel.visibility = visibility
        dividerAfterTapNnapi.visibility = visibility
        dividerAfterTapLowPower.visibility = visibility
        tvTapAdvancedSummary.text = if (tapAdvancedExpanded) {
            "已展开模型、省电和推理参数"
        } else {
            "模型、省电和推理参数"
        }
        ivTapAdvancedChevron.rotation = if (tapAdvancedExpanded) 90f else 0f
    }

    private fun restartTapDetection() {
        tapRestartPending = true
        tapRestartHandler.removeCallbacks(tapRestartRunnable)
        tapRestartHandler.postDelayed(tapRestartRunnable, TAP_RESTART_DEBOUNCE_MS)
    }

    override fun onDestroy() {
        tapRestartHandler.removeCallbacks(tapRestartRunnable)
        if (tapRestartPending) {
            tapRestartRunnable.run()
        }
        super.onDestroy()
    }
}

