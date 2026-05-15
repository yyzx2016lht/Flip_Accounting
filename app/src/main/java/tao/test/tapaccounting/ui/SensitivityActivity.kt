package tao.test.tapaccounting.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import tao.test.tapaccounting.KeepAliveAccessibilityService
import tao.test.tapaccounting.KeepAliveDiagnostics
import tao.test.tapaccounting.OverlayService
import tao.test.tapaccounting.Prefs
import tao.test.tapaccounting.R
import tao.test.tapaccounting.tap.TapActionRegistry
import tao.test.tapaccounting.ui.dialog.OverlayDialogs
import tao.test.tapaccounting.tap.TapModel
import java.util.Locale

class SensitivityActivity : AppCompatActivity() {

    private lateinit var cardTapSensitivity: View
    private lateinit var cardTapOptions: View

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

    private lateinit var switchTapEnable: SwitchMaterial
    private lateinit var switchLandscapeDisable: SwitchMaterial
    private lateinit var switchVibrationFeedback: SwitchMaterial
    private lateinit var switchSaveVibrate: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sensitivity)

        initViews()
        loadData()
    }

    private fun initViews() {
        findViewById<ImageView>(R.id.toolbar).setOnClickListener { finish() }

        cardTapSensitivity = findViewById(R.id.card_tap_sensitivity)
        cardTapOptions = findViewById(R.id.card_tap_options)

        initGestureSwitches()
        initTapViews()
        initTapOptions()
        initKeepAliveSettings()

        // 让包含开关的整行都可点击
        makeSwitchRowsClickable()
    }

    private fun initGestureSwitches() {
        switchTapEnable = findViewById(R.id.switch_tap_enable)
        switchLandscapeDisable = findViewById(R.id.switch_landscape_disable)
        switchVibrationFeedback = findViewById(R.id.switch_vibration_feedback)
        switchSaveVibrate = findViewById(R.id.switch_save_vibrate)

        switchTapEnable.isChecked = Prefs.isDoubleTapEnabled(this)
        cardTapSensitivity.visibility = if (switchTapEnable.isChecked) View.VISIBLE else View.GONE
        cardTapOptions.visibility = cardTapSensitivity.visibility
        switchTapEnable.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDoubleTapEnabled(this, isChecked)
            cardTapSensitivity.visibility = if (isChecked) View.VISIBLE else View.GONE
            cardTapOptions.visibility = cardTapSensitivity.visibility
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

    private fun makeSwitchRowsClickable() {
        val toggleIds = intArrayOf(
            R.id.switch_tap_enable,
            R.id.switch_landscape_disable,
            R.id.switch_vibration_feedback,
            R.id.switch_save_vibrate
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
        val layoutTapNnapi = findViewById<View>(R.id.layout_tap_nnapi)
        val layoutTapTriple = findViewById<View>(R.id.layout_tap_triple)
        val layoutTapLowPower = findViewById<View>(R.id.layout_tap_low_power)

        layoutTapNnapi.setOnClickListener { if (switchTapNnapi.isEnabled) switchTapNnapi.performClick() }
        layoutTapTriple.setOnClickListener { if (switchTapTriple.isEnabled) switchTapTriple.performClick() }
        layoutTapLowPower.setOnClickListener { switchTapLowPower.performClick() }

        // 模型选择
        tvTapModelName.text = TapModel.resolve(this).displayName
        findViewById<View>(R.id.btn_tap_model).setOnClickListener {
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

        // 双击动作
        val doubleActionId = Prefs.getTapActionDouble(this)
        tvTapActionDoubleName.text = TapActionRegistry.findById(doubleActionId)?.displayName ?: "未设置"
        findViewById<View>(R.id.btn_tap_action_double).setOnClickListener {
            val ids = TapActionRegistry.getIds()
            val actions = TapActionRegistry.getAll()
            val items = listOf(SelectionItem("无", "不执行任何操作")) +
                actions.map { SelectionItem(it.displayName, it.description) }
            val currentIdx = ids.indexOf(Prefs.getTapActionDouble(this)).coerceAtLeast(0)
            showSelectionDialog(
                title = "双击动作",
                items = items,
                selectedIndex = currentIdx
            ) { which ->
                Prefs.setTapActionDouble(this, ids[which])
                tvTapActionDoubleName.text = items[which].title
            }
        }

        // 三击动作
        val tripleActionId = Prefs.getTapActionTriple(this)
        tvTapActionTripleName.text = TapActionRegistry.findById(tripleActionId)?.displayName ?: "未设置"
        findViewById<View>(R.id.btn_tap_action_triple).setOnClickListener {
            val ids = TapActionRegistry.getIds()
            val actions = TapActionRegistry.getAll()
            val items = listOf(SelectionItem("无", "不执行任何操作")) +
                actions.map { SelectionItem(it.displayName, it.description) }
            val currentIdx = ids.indexOf(Prefs.getTapActionTriple(this)).coerceAtLeast(0)
            showSelectionDialog(
                title = "三击动作",
                items = items,
                selectedIndex = currentIdx
            ) { which ->
                Prefs.setTapActionTriple(this, ids[which])
                tvTapActionTripleName.text = items[which].title
            }
        }
    }

    private data class SelectionItem(val title: String, val subtitle: String = "")

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

        val btnNotification = findViewById<View>(R.id.btn_notification_permission)
        val tvNotificationStatus = findViewById<TextView>(R.id.tv_notification_status)

        fun refreshNotificationStatus() {
            val enabled = isNotificationPermissionReady()
            tvNotificationStatus?.text = if (enabled) "仅录音等临时场景会用到" else "当前为无常驻通知模式"
            (btnNotification as? MaterialButton)?.text = if (enabled) "已开启" else "可选"
        }

        btnNotification?.setOnClickListener {
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

        val notificationReady = isNotificationPermissionReady()
        findViewById<TextView>(R.id.tv_notification_status)?.text =
            if (notificationReady) "仅录音等临时场景会用到" else "当前为无常驻通知模式"
        (findViewById<View>(R.id.btn_notification_permission) as? MaterialButton)?.text =
            if (notificationReady) "已开启" else "可选"
    }

    private fun isNotificationPermissionReady(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channelEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.getNotificationChannel(OverlayService.CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            true
        }
        return permissionGranted && channelEnabled
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

    private fun restartTapDetection() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_RESTART_DOUBLE_TAP
        }
        startServiceCompat(intent)
    }
}
