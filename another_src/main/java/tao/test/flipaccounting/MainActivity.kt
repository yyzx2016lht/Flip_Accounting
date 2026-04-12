package tao.test.flipaccounting

import tao.test.flipaccounting.R
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.delay
import org.json.JSONObject
import tao.test.flipaccounting.logic.AccountingFormController
import tao.test.flipaccounting.ui.FlipSensitivityActivity
import tao.test.flipaccounting.logic.VoiceInputHandler
import tao.test.flipaccounting.OverlayManager
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var currentSortByBillTime = true // true: 按账单时间, false: 按记账时间

    private lateinit var overlayManager: OverlayManager
    private lateinit var aiAssistant: AiAssistant
    private var billAdapter: BillAdapter? = null
    private var switchMultiBill: SwitchMaterial? = null
    private var switchMultiBillNotSync: SwitchMaterial? = null
    private var switchShowVoice: SwitchMaterial? = null  // 供权限回调回拨开关使用

    private fun syncMultiBillSwitches() {
        val multiEnabled = Prefs.isMultiBillEnabled(this)
        val multiNotSync = Prefs.isMultiBillNotSync(this)

        switchMultiBill?.setOnCheckedChangeListener(null)
        switchMultiBill?.isChecked = multiEnabled
        switchMultiBillNotSync?.isEnabled = multiEnabled

        switchMultiBillNotSync?.setOnCheckedChangeListener(null)
        switchMultiBillNotSync?.isChecked = multiNotSync

        switchMultiBill?.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setMultiBillEnabled(this, isChecked)
            switchMultiBillNotSync?.isEnabled = isChecked
            Utils.toast(this, if (isChecked) "宸插紑鍚璐﹀崟妯″紡" else "宸插叧闂璐﹀崟妯″紡")
        }

        switchMultiBillNotSync?.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setMultiBillNotSync(this, isChecked)
            Utils.toast(this, if (isChecked) "宸插紑鍚細璇嗗埆缁撴灉鐩存帴淇濆瓨鍒版湰鍦帮紝璺宠繃纭" else "宸插叧闂細璇嗗埆缁撴灉闇€閫愭潯纭")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        overlayManager = OverlayManager(this)
        aiAssistant = AiAssistant(this)
        
        // --- 请求必要权限 ---
        checkAndRequestPermissions()

        // --- 0. 初始化状态 ---
        val isHide = Prefs.isHideRecents(this)
        setExcludeFromRecents(isHide)

        // 初始化导航
        setupNavigation()

        // AI 配置入口（首页按钮跳转）
        findViewById<View>(R.id.btn_ai_detailed_config).setOnClickListener {
            startActivity(Intent(this, AiConfigActivity::class.java))
        }
        findViewById<View>(R.id.btn_manage_ai_rules).setOnClickListener {
            startActivity(Intent(this, AiRuleManageActivity::class.java))
        }

        // 核心修复：启动时如果开关是开启的，确保服务也在运行
        if (Prefs.isFlipEnabled(this)) {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_START_FLIP
            }
            startServiceCompat(intent)
        }

        // --- 1. 权限与悬浮窗 ---
        findViewById<MaterialButton>(R.id.btnRequestOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                Utils.toast(this, "悬浮窗权限已授予")
            }
        }

        findViewById<MaterialButton>(R.id.btnShowOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                Utils.toast(this, "请先授予悬浮窗权限")
                return@setOnClickListener
            }
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW_OVERLAY
            }
            startServiceCompat(intent)
        }

        // --- 2. 开关逻辑 ---

        findViewById<SwitchMaterial>(R.id.switch_permanent_wakelock).apply {
            isChecked = Prefs.isPermanentWakeLockEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setPermanentWakeLockEnabled(this@MainActivity, isChecked)
                Utils.toast(this@MainActivity, if (isChecked) "已开启强力唤醒锁模式" else "已关闭强力唤醒锁")
                
                // 重启服务以应用更改
                if (Prefs.isFlipEnabled(this@MainActivity)) {
                    val intent = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }

        findViewById<SwitchMaterial>(R.id.switch_shizuku_persistence).apply {
            isChecked = Prefs.isShizukuPersistenceEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShizukuPersistenceEnabled(this@MainActivity, isChecked)
                Utils.toast(this@MainActivity, if (isChecked) "已开启Shizuku终极保活" else "已关闭Shizuku保活")
                
                // 重启服务以应用更改
                if (Prefs.isFlipEnabled(this@MainActivity)) {
                    val intent = Intent(this@MainActivity, OverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                }
            }
        }

        // 日志开关
        val btnShareLogs = findViewById<MaterialButton>(R.id.btn_share_logs)
        findViewById<SwitchMaterial>(R.id.switch_logging).apply {
            isChecked = Prefs.isLoggingEnabled(this@MainActivity)
            btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setLoggingEnabled(this@MainActivity, isChecked)
                btnShareLogs.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (isChecked) {
                    Utils.toast(context, "日志记录已开启")
                } else {
                    Logger.clearLogs(context)
                    Utils.toast(context, "日志记录已关闭并清空")
                }
            }
        }

        btnShareLogs.setOnClickListener {
            val logFile = Logger.getLogFile(this)
            if (!logFile.exists() || logFile.length() == 0L) {
                Utils.toast(this, "当前没有日志内容")
                return@setOnClickListener
            }
            startActivity(Intent(this, LogViewerActivity::class.java))
        }

        // 翻转开关
        val layoutFlipSub = findViewById<View>(R.id.layout_flip_sub_settings)
        findViewById<SwitchMaterial>(R.id.switch_flip_trigger).apply {
            isChecked = Prefs.isFlipEnabled(this@MainActivity)
            layoutFlipSub.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setFlipEnabled(this@MainActivity, isChecked)
                layoutFlipSub.visibility = if (isChecked) View.VISIBLE else View.GONE
                val intent = Intent(this@MainActivity, OverlayService::class.java).apply {
                    action = if (isChecked) OverlayService.ACTION_START_FLIP else OverlayService.ACTION_STOP_FLIP
                }
                startServiceCompat(intent)
                if (isChecked) Utils.toast(context, "翻转触发已开启")
            }
        }

        // 白名单限制开关（OFF=全局感应默认，ON=白名单模式并展示白名单按钮）
        val btnManageWhitelist = findViewById<View>(R.id.btn_manage_whitelist)
        findViewById<SwitchMaterial>(R.id.switch_flip_always).apply {
            // Prefs.isFlipAlways 语义：true=全局感应；此开关含义相反，需取反
            isChecked = !Prefs.isFlipAlways(this@MainActivity)
            btnManageWhitelist.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                // 开关 ON = 白名单模式 = NOT 全局感应
                Prefs.setFlipAlways(this@MainActivity, !isChecked)
                btnManageWhitelist.visibility = if (isChecked) View.VISIBLE else View.GONE
                Utils.toast(this@MainActivity, if (isChecked) "已开启白名单限制模式" else "已切换为全局感应模式")
            }
        }

        // 悬浮窗震动反馈开关
        findViewById<SwitchMaterial>(R.id.switch_vibrate_feedback).apply {
            isChecked = Prefs.isVibrateFeedbackEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setVibrateFeedbackEnabled(this@MainActivity, isChecked)
            }
        }

        // 隐藏最近任务开关
        findViewById<SwitchMaterial>(R.id.switch_hide_recent).apply {
            isChecked = isHide
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setHideRecents(this@MainActivity, isChecked)
                setExcludeFromRecents(isChecked)
                Utils.toast(this@MainActivity, if (isChecked) "已隐藏最近任务卡片" else "已恢复显示")
            }
        }

        // AI 文本识别开关
        switchMultiBill = findViewById<SwitchMaterial>(R.id.switch_show_multi_bill)
        switchMultiBillNotSync = findViewById<SwitchMaterial>(R.id.switch_multi_bill_not_sync)
        val layoutAiMain = findViewById<View>(R.id.layout_ai_main_entry)
        val dividerAi = findViewById<View>(R.id.divider_ai)
        
        findViewById<SwitchMaterial>(R.id.switch_show_ai).apply {
            isChecked = Prefs.isShowAiText(this@MainActivity)
            layoutAiMain.visibility = if (isChecked) View.VISIBLE else View.GONE
            dividerAi.visibility = if (isChecked) View.VISIBLE else View.GONE
            
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiText(this@MainActivity, isChecked)
                layoutAiMain.visibility = if (isChecked) View.VISIBLE else View.GONE
                dividerAi.visibility = if (isChecked) View.VISIBLE else View.GONE
                Utils.toast(this@MainActivity, if (isChecked) "已开启 AI 智能工作流" else "已隐藏 AI 入口")
            }
        }

        // --- 多账单模式开关 ---
        switchMultiBill?.apply {
            isChecked = Prefs.isMultiBillEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setMultiBillEnabled(this@MainActivity, isChecked)
                switchMultiBillNotSync?.isEnabled = isChecked
                Utils.toast(this@MainActivity, if (isChecked) "已开启多账单模式" else "已关闭多账单模式")
            }
        }

        // 多账单不同步开关
        switchMultiBillNotSync?.apply {
            isChecked = Prefs.isMultiBillNotSync(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setMultiBillNotSync(this@MainActivity, isChecked)
                Utils.toast(this@MainActivity, if (isChecked) "已开启：识别结果直接保存到本地，跳过确认" else "已关闭：识别结果需逐条确认")
            }
        }

        // 语音功能开关
        syncMultiBillSwitches()

        val layoutAsrMode = findViewById<View>(R.id.layout_asr_mode)
        switchShowVoice = findViewById<SwitchMaterial>(R.id.switch_show_voice).apply {
            isChecked = Prefs.isShowAiVoice(this@MainActivity)
            layoutAsrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    // 开启时检查录音权限
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // 没有权限：先把开关拨回关，再弹权限申请
                        // 权限申请结果在 onRequestPermissionsResult 中处理
                        setChecked(false)
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1002)
                        return@setOnCheckedChangeListener
                    }
                }
                Prefs.setShowAiVoice(this@MainActivity, isChecked)
                layoutAsrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
                Utils.toast(this@MainActivity, if (isChecked) "已开启语音功能" else "已关闭语音功能")
            }
        }

        // ASR引擎选择
        val asrSubOptions = this@MainActivity.findViewById<android.widget.LinearLayout>(R.id.layout_asr_model_info)
        val tvAsrModelDesc = this@MainActivity.findViewById<android.widget.TextView>(R.id.tv_asr_model_desc)
        val btnDeleteModel = this@MainActivity.findViewById<View>(R.id.btn_delete_offline_model)

        findViewById<android.widget.Spinner>(R.id.spinner_asr_mode).apply {

            fun updateModelUi(mode: Int) {
                asrSubOptions?.visibility = View.VISIBLE
                if (mode == Prefs.ASR_MODE_WHISPER) {
                    val isReady = LocalAsrService.isModelReady(this@MainActivity)
                    if (isReady) {
                        tvAsrModelDesc?.text = "点击删除模型"
                        tvAsrModelDesc?.setTextColor(android.graphics.Color.parseColor("#E53935"))
                        btnDeleteModel?.setOnClickListener {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("删除本地模型")
                                .setMessage("确定要删除下载的阿里 SenseVoice 本地模型文件吗？")
                                .setPositiveButton("删除") { _, _ ->
                                    val modelDir = java.io.File(filesDir, "sherpa-onnx-sense-voice")
                                    if (modelDir.exists() && modelDir.deleteRecursively()) {
                                        setSelection(Prefs.ASR_MODE_API)
                                        Prefs.setAsrMode(this@MainActivity, Prefs.ASR_MODE_API)
                                        updateModelUi(Prefs.ASR_MODE_API)
                                        Utils.toast(this@MainActivity, "本地模型已删除")
                                    } else {
                                        Utils.toast(this@MainActivity, "删除失败或模型不存在")
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    } else {
                        tvAsrModelDesc?.text = "未下载 (点击下载)"
                        tvAsrModelDesc?.setTextColor(android.graphics.Color.parseColor("#FF9800"))
                        btnDeleteModel?.setOnClickListener {
                            // 当未下载时，手动点击也可以触发下载
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("需下载本地模型")
                                .setMessage("是否立即下载大约 45MB 的模型压缩文件（解压后约 145MB）？")
                                .setCancelable(false)
                                .setPositiveButton("开始下载") { _, _ ->
                                    Utils.toast(this@MainActivity, "开始下载 SenseVoice 模型")
                                    LocalAsrService.downloadModelWithUI(this@MainActivity) {
                                        updateModelUi(mode)
                                    }
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        }
                    }
                } else {
                    tvAsrModelDesc?.text = "云端 (仅需联网)"
                    tvAsrModelDesc?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
                    btnDeleteModel?.setOnClickListener {
                        Utils.toast(this@MainActivity, "正在使用的是在线 API 服务，无需管理本地模型。")
                    }
                }
            }

            setSelection(Prefs.getAsrMode(this@MainActivity))
            updateModelUi(Prefs.getAsrMode(this@MainActivity))

            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val currentMode = Prefs.getAsrMode(this@MainActivity)
                    if (currentMode != position) {
                        if (position == Prefs.ASR_MODE_WHISPER && !LocalAsrService.isModelReady(this@MainActivity)) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("需下载本地模型")
                                .setMessage("切换为阿里 SenseVoice 本地模式需要下载大约 45MB 的模型压缩文件（解压后约 145MB）。\n\n是否立即下载？")
                                .setCancelable(false)
                                .setPositiveButton("开始下载") { _, _ ->
                                    // 预先更新UI但模式会在成功后才能稳定使用
                                    Prefs.setAsrMode(this@MainActivity, position)
                                    updateModelUi(position)
                                    Utils.toast(this@MainActivity, "开始下载 SenseVoice 模型")
                                    LocalAsrService.downloadModelWithUI(this@MainActivity) {
                                        // 下载完成回调
                                        updateModelUi(position)
                                    }
                                }
                                .setNegativeButton("取消") { _, _ ->
                                    setSelection(currentMode)
                                }
                                .show()
                        } else {
                            Prefs.setAsrMode(this@MainActivity, position)
                            updateModelUi(position)
                            val modeName = if (position == Prefs.ASR_MODE_WHISPER) "阿里 SenseVoice 本地模式" else "云端 API"
                            Utils.toast(this@MainActivity, "已切换为 $modeName")
                        }
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        // 图片记账功能开关
        val layoutOcrMode = findViewById<View>(R.id.layout_ocr_mode)
        val layoutReceiptLang = findViewById<View>(R.id.layout_receipt_lang)
        val showImageSwitch = findViewById<SwitchMaterial>(R.id.switch_show_image)
        showImageSwitch.apply {
            isChecked = Prefs.isShowAiImage(this@MainActivity)
            layoutOcrMode?.visibility = if (isChecked) View.VISIBLE else View.GONE
            layoutReceiptLang?.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiImage(this@MainActivity, isChecked)
                layoutOcrMode?.visibility = if (isChecked) View.VISIBLE else View.GONE
                layoutReceiptLang?.visibility = if (isChecked) View.VISIBLE else View.GONE
                Utils.toast(this@MainActivity, if (isChecked) "已开启图片记账功能" else "已关闭图片记账功能")
            }
        }

        // OCR引擎选择
        findViewById<android.widget.Spinner>(R.id.spinner_ocr_mode)?.apply {
            setSelection(Prefs.getOcrMode(this@MainActivity))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (Prefs.getOcrMode(this@MainActivity) != position) {
                        Prefs.setOcrMode(this@MainActivity, position)
                        val modeName = if (position == Prefs.OCR_MODE_LOCAL) "本地 ML Kit（免费）" else "多模态云端（发送图片）"
                        Utils.toast(this@MainActivity, "已切换为 $modeName")
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        // 小票语言模式选择
        findViewById<android.widget.Spinner>(R.id.spinner_receipt_lang)?.apply {
            setSelection(Prefs.getReceiptLangMode(this@MainActivity))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (Prefs.getReceiptLangMode(this@MainActivity) != position) {
                        Prefs.setReceiptLangMode(this@MainActivity, position)
                        val modeName = when (position) {
                            Prefs.RECEIPT_LANG_CN -> "中文小票"
                            Prefs.RECEIPT_LANG_FOREIGN -> "外语小票（波兰/英语等）"
                            else -> "自动检测"
                        }
                        Utils.toast(this@MainActivity, "小票语言已切换为：$modeName")
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        // 记账多币种开关
        findViewById<SwitchMaterial>(R.id.switch_show_multi_cur).apply {
            isChecked = Prefs.isShowMultiCurrency(this@MainActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowMultiCurrency(this@MainActivity, isChecked)
                Utils.toast(this@MainActivity, if (isChecked) "已开启多币种显示" else "已按单币种显示")
            }
        }

        // --- 3. 数据管理跳转 ---
        findViewById<View>(R.id.btn_manage_assets).setOnClickListener {
            startActivity(Intent(this, AssetActivity::class.java))
        }

        findViewById<View>(R.id.btn_manage_categories).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<View>(R.id.btn_manage_currencies).setOnClickListener {
            startActivity(Intent(this, tao.test.flipaccounting.ui.CurrencyManagerActivity::class.java))
        }

        findViewById<View>(R.id.btn_backup_restore).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<View>(R.id.btn_manage_whitelist).setOnClickListener {
            if (!Shizuku.pingBinder()) {
                Utils.toast(this, "请先启动 Shizuku 并授权")
                return@setOnClickListener
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(101)
            } else {
                startActivity(Intent(this, AppListActivity::class.java))
            }
        }

        findViewById<View>(R.id.btn_flip_sensitivity).setOnClickListener {
            startActivity(Intent(this, FlipSensitivityActivity::class.java))
        }

        // Prompt 调试跳转 (或直接弹出 Dialog)
        findViewById<View>(R.id.btn_prompt_debug).setOnClickListener {
            val assets = tao.test.flipaccounting.Prefs.getAssets(this).map { it.name }
            
            val expenseCats = mutableListOf<String>()
            tao.test.flipaccounting.Prefs.getCategories(this, tao.test.flipaccounting.Prefs.TYPE_EXPENSE).forEach { parentNode ->
                if (parentNode.subs.isEmpty()) {
                    expenseCats.add(parentNode.name)
                } else {
                    parentNode.subs.forEach { childNode ->
                        expenseCats.add("${parentNode.name}/::/${childNode.name}")
                    }
                }
            }
            
            val incomeCats = mutableListOf<String>()
            tao.test.flipaccounting.Prefs.getCategories(this, tao.test.flipaccounting.Prefs.TYPE_INCOME).forEach { parentNode ->
                if (parentNode.subs.isEmpty()) {
                    incomeCats.add(parentNode.name)
                } else {
                    parentNode.subs.forEach { childNode ->
                        incomeCats.add("${parentNode.name}/::/${childNode.name}")
                    }
                }
            }
            
            val now = java.util.Date()
            val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            val weekFormat = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
            val currentTimeStr = "${timeFormat.format(now)} (${weekFormat.format(now)})"

            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            
            val msg = buildString {
                append("【TIME】\n").append(currentTimeStr).append("\n\n")
                append("【ASSETS】\n").append(gson.toJson(assets)).append("\n\n")
                append("【EXPENSE_CATS】\n").append(gson.toJson(expenseCats)).append("\n\n")
                append("【INCOME_CATS】\n").append(gson.toJson(incomeCats))
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("当前 Prompt 数据源")
                .setMessage(msg)
                .setPositiveButton("复制") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("prompt_data", msg))
                    tao.test.flipaccounting.Utils.toast(this, "已复制到剪贴板")
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    override fun onBackPressed() {
        if (overlayManager.isShowing()) {
            overlayManager.removeOverlay(isSaved = false)
            return
        }

        // 如果正在编辑历史账单（批量选择模式），先退出选择模式
        billAdapter?.let { adapter ->
            if (adapter.isSelectionMode) {
                adapter.isSelectionMode = false
                adapter.selectedBills.clear()
                findViewById<MaterialButton>(R.id.btn_edit_bills).text = "编辑"
                findViewById<View>(R.id.layout_batch_actions).visibility = View.GONE
                adapter.notifyDataSetChanged()
                return
            }
        }
        
        super.onBackPressed()
    }

    private fun setupNavigation() {
        val navHome = findViewById<View>(R.id.nav_home)
        val navBills = findViewById<View>(R.id.nav_bills_tab)

        val pageHome = findViewById<View>(R.id.page_home)
        val pageBills = findViewById<View>(R.id.page_bills)

        val tvHomeText = findViewById<TextView>(R.id.tv_home_text)
        val tvBillsText = findViewById<TextView>(R.id.tv_bills_text)

        navHome.setOnClickListener {
            pageHome.visibility = View.VISIBLE
            pageBills.visibility = View.GONE
            setNavActive(tvHomeText, listOf(tvBillsText))
        }

        navBills.setOnClickListener {
            pageHome.visibility = View.GONE
            pageBills.visibility = View.VISIBLE
            setNavActive(tvBillsText, listOf(tvHomeText))
            loadBills()
        }

        // 初始状态：显示首页
        pageHome.visibility = View.VISIBLE
        pageBills.visibility = View.GONE
        setNavActive(tvHomeText, listOf(tvBillsText))
    }

    private fun setNavActive(active: TextView, others: List<TextView>) {
        active.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
        active.setTypeface(null, android.graphics.Typeface.BOLD)
        others.forEach {
            it.setTypeface(null, android.graphics.Typeface.NORMAL)
        }
    }

    private fun findAssetIcon(ctx: Context, name: String): String {
        if (name.isEmpty()) return ""
        
        // 1. 尝试从用户自定义资产中获取
        // 注意：loadAssetsFromRaw 返回的是 List<BuiltInCategory>，而 getAssets 返回的是 List<Asset>
        // 它们的数据结构不同，但都有 name 和 icon 属性
        
        // 先检查用户资产配置
        val userAssets = Prefs.getAssets(ctx)
        val userAsset = userAssets.find { it.name == name }
        if (userAsset != null && userAsset.icon.isNotEmpty()) {
            return userAsset.icon
        }
        
        // 2. 如果用户资产没有图标，或者用户根本没存这个资产（仅仅是账单里有名字），尝试从内置库匹配
        val builtIn = Prefs.loadAssetsFromRaw(ctx)
        
        // A. 精确匹配
        var matched = builtIn.find { it.name == name }?.icon
        
        // B. 模糊匹配 (如果名字包含内置资产名，如"招商银行"包含"银行")
        if (matched == null) {
            // 这里逻辑按照长度倒序，优先匹配更长的词
             val candidate = builtIn.sortedByDescending { it.name.length }
                .find { name.contains(it.name) || it.name.contains(name) }
             
             matched = candidate?.icon
        }

        return matched ?: ""
    }

    private fun editBill(bill: Bill) {
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要权限")
                .setMessage("修改账单需要悬浮窗权限，是否前往开启？")
                .setPositiveButton("去开启") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        val json = JSONObject().apply {
            put("amount", bill.amount)
            put("type", bill.type)
            put("asset_name", bill.assetName)
            put("remarks", bill.remarks)
            put("time", bill.time)
            put("recordTime", bill.recordTime) // [新增]
            
            if (bill.type == 2) {
                // 如果是转账，解析出目标资产
                val toAsset = bill.categoryName.replace("转账到 ", "").trim()
                put("to_asset_name", toAsset)
            } else {
                put("category_name", bill.categoryName)
            }
        }
        
        OverlayManager(this).showOverlay(json, showSaveOnly = true)
    }

    private fun loadBills() {
        val swipe = findViewById<SwipeRefreshLayout>(R.id.swipe_refresh_bills)
        swipe?.setOnRefreshListener { loadBills() }

        val rv = findViewById<RecyclerView>(R.id.rv_bills)
        val empty = findViewById<View>(R.id.tv_empty_bills)
        val btnEdit = findViewById<MaterialButton>(R.id.btn_edit_bills)
        val layoutBatch = findViewById<View>(R.id.layout_batch_actions)
        val btnSelectAll = findViewById<MaterialButton>(R.id.btn_select_all)
        val btnBatchDelete = findViewById<View>(R.id.btn_batch_delete)
        val btnBatchSync = findViewById<View>(R.id.btn_batch_sync)
        val tvSortStatus = findViewById<TextView>(R.id.tv_current_sort)
        val btnToggleSort = findViewById<MaterialButton>(R.id.btn_toggle_sort)
        
        val allBills = Prefs.getBills(this)
        swipe?.isRefreshing = false // 数据加载完关闭动画

        if (allBills.isEmpty()) {
            rv.visibility = View.GONE
            empty.visibility = View.VISIBLE
            btnEdit.visibility = View.GONE
            layoutBatch.visibility = View.GONE
        } else {
            rv.visibility = View.VISIBLE
            empty.visibility = View.GONE
            btnEdit.visibility = View.VISIBLE
            rv.layoutManager = LinearLayoutManager(this)
            
            // 排序逻辑
            tvSortStatus.text = if (currentSortByBillTime) "账单时间排序" else "记录时间排序"

            val sortedBills = if (currentSortByBillTime) {
                allBills.sortedByDescending { it.time }
            } else {
                // 记账时间可能为空（旧数据），优先按记录生成顺序
                allBills.sortedWith(compareByDescending<Bill> { it.recordTime }.thenByDescending { it.time })
            }

            btnToggleSort.setOnClickListener {
                currentSortByBillTime = !currentSortByBillTime
                loadBills()
            }

            // 数据分块处理：按日期分组
            val displayItems = mutableListOf<Any>()
            var lastDate = ""
            sortedBills.forEach { bill ->
                val timeToUse = if (currentSortByBillTime) bill.time else bill.recordTime
                val date = if(timeToUse.length >= 10) timeToUse.substring(0, 10) else "未知时间"
                if (date != lastDate) {
                    displayItems.add(date) // 日期标题
                    lastDate = date
                }
                displayItems.add(bill)
            }
            
            val adapter = BillAdapter(displayItems)
            billAdapter = adapter
            rv.adapter = adapter

            btnEdit.setOnClickListener {
                if (adapter.isSelectionMode) {
                    adapter.isSelectionMode = false
                    adapter.selectedBills.clear()
                    btnEdit.text = "编辑"
                    layoutBatch.visibility = View.GONE
                } else {
                    adapter.isSelectionMode = true
                    btnEdit.text = "取消"
                    layoutBatch.visibility = View.VISIBLE
                }
                adapter.notifyDataSetChanged()
            }

            btnSelectAll.setOnClickListener {
                if (adapter.selectedBills.size == allBills.size) {
                    adapter.selectedBills.clear()
                } else {
                    adapter.selectedBills.addAll(allBills)
                }
                adapter.notifyDataSetChanged()
            }

            btnBatchDelete.setOnClickListener {
                if (adapter.selectedBills.isEmpty()) {
                    Utils.toast(this, "未选中任何账单")
                    return@setOnClickListener
                }
                AlertDialog.Builder(this)
                    .setTitle("操作确认")
                    .setMessage("确定要删除选中的 ${adapter.selectedBills.size} 条账单吗？")
                    .setPositiveButton("确定") { _, _ ->
                        Prefs.deleteBills(this, adapter.selectedBills)
                        adapter.isSelectionMode = false
                        adapter.selectedBills.clear()
                        btnEdit.text = "编辑"
                        layoutBatch.visibility = View.GONE
                        loadBills()
                        Utils.toast(this, "已批量删除")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }

            btnBatchSync.setOnClickListener {
                if (adapter.selectedBills.isEmpty()) {
                    Utils.toast(this, "未选中任何账单")
                    return@setOnClickListener
                }
                
                AlertDialog.Builder(this)
                    .setTitle("批量同步到钱迹")
                    .setMessage("确定要同步选中的 ${adapter.selectedBills.size} 条账单吗？程序将在后台每隔 5 秒自动执行同步，请保持钱迹已打开。")
                    .setPositiveButton("开始批量同步") { _, _ ->
                        val selected = adapter.selectedBills.toList().sortedBy { it.time }
                        
                        // 退出选择模式
                        adapter.isSelectionMode = false
                        adapter.selectedBills.clear()
                        btnEdit.text = "编辑"
                        layoutBatch.visibility = View.GONE
                        adapter.notifyDataSetChanged()

                        // 开启后台协程执行延时同步任务
                        CoroutineScope(Dispatchers.Main).launch {
                            Utils.toast(this@MainActivity, "开始自动批量同步(每笔5秒)，请保持屏幕开启...")
                            
                            selected.forEachIndexed { index, bill ->
                                val isTransfer = bill.type == 2 || bill.type == 3
                                // 修正分类逻辑：账单列表中展示的是 "父 > 子"，同步给钱迹需要改为 "父/::/子"
                                val finalCategory = if (isTransfer) "" else bill.categoryName.replace(" > ", "/::/")
                                
                                val qUrl = Utils.buildQianjiUrl(
                                    type = bill.type.toString(),
                                    money = String.format(Locale.US, "%.2f", bill.amount),
                                    time = bill.time,
                                    remark = bill.remarks,
                                    catename = finalCategory,
                                    accountname = bill.assetName,
                                    accountname2 = if (isTransfer) bill.categoryName.replace("转账到 ", "").replace("还款到 ", "") else null,
                                    showresult = "0"
                                )
                                
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(qUrl)).apply {
                                        // 增加标识位，尝试提升连续跳转的成功率
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                    }
                                    startActivity(intent)
                                    Logger.d(this@MainActivity, "BatchSync", "正在同步第 ${index + 1}/${selected.size} 条: ${bill.remarks}")
                                } catch (e: Exception) {
                                    Logger.d(this@MainActivity, "BatchSync", "同步失败 ${index + 1}: ${e.message}")
                                }

                                // 保持 5 秒间隔，确保钱迹有足够时间处理记录
                                if (index < selected.size - 1) {
                                    delay(5000)
                                }
                            }
                            
                            Utils.toast(this@MainActivity, "✅ 批量同步任务(共${selected.size}条)已完成")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    inner class BillAdapter(private val items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var isSelectionMode = false
        val selectedBills = mutableSetOf<Bill>()
        
        private val TYPE_DATE = 0
        private val TYPE_BILL = 1

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) TYPE_DATE else TYPE_BILL
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_DATE) {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_header, parent, false)
                DateHeaderViewHolder(v)
            } else {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_bill, parent, false)
                BillViewHolder(v)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == TYPE_DATE) {
                val dateStr = items[position] as String
                val h = holder as DateHeaderViewHolder
                h.dateText.text = dateStr
                
                h.selectDayBtn.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                
                // 找出这一天所有的账单
                val billsInDay = mutableListOf<Bill>()
                for (i in position + 1 until items.size) {
                    val item = items[i]
                    if (item is String) break
                    if (item is Bill) billsInDay.add(item)
                }

                val isAllSelected = billsInDay.isNotEmpty() && selectedBills.containsAll(billsInDay)
                h.selectDayBtn.text = if (isAllSelected) "取消全选" else "全选"
                
                h.selectDayBtn.setOnClickListener {
                    if (isAllSelected) {
                        selectedBills.removeAll(billsInDay)
                    } else {
                        selectedBills.addAll(billsInDay)
                    }
                    notifyDataSetChanged()
                }
            } else {
                val bill = items[position] as Bill
                val h = holder as BillViewHolder
                
                // 处理选择模式下的复选框
                h.checkBox.visibility = if (isSelectionMode) View.VISIBLE else View.GONE
                h.checkBox.setOnCheckedChangeListener(null)
                h.checkBox.isChecked = selectedBills.contains(bill)
                h.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedBills.add(bill) else selectedBills.remove(bill)
                }

                // 处理转账/还款类型的显示名
                if (bill.type == 2 || bill.type == 3) {
                    h.category.text = if (bill.type == 2) "转账" else "还款"
                    val icon = " ➔ "
                    h.detail.text = "${bill.assetName}$icon${bill.categoryName.replace("转账到 ", "").replace("还款到 ", "")}"
                } else {
                    h.category.text = if (bill.categoryName.contains(" > ")) bill.categoryName.split(" > ").last() else bill.categoryName
                    h.detail.text = "${bill.assetName}${if(bill.remarks.isNotEmpty()) " | ${bill.remarks}" else ""}"
                }
                
                // 根据当前排序模式显示不同的时间描述
                if (currentSortByBillTime) {
                    h.time.text = if(bill.time.length > 11) bill.time.substring(11) else ""
                } else {
                    h.time.text = if(bill.recordTime.length > 11) "记账: ${bill.recordTime.substring(11)}" else "来源: AI/Flip"
                }

                // 金额显示逻辑
                val amountText = String.format("%.2f", bill.amount)
                when(bill.type) {
                    1 -> { // 收入
                        h.amount.text = "+$amountText"
                        h.amount.setTextColor(android.graphics.Color.parseColor("#388E3C"))
                    }
                    2, 3 -> { // 转账 或 还款
                        h.amount.text = amountText
                        h.amount.setTextColor(android.graphics.Color.parseColor("#333333"))
                    }
                    else -> { // 支出
                        h.amount.text = "-$amountText"
                        h.amount.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
                    }
                }
                
                // 加载图标
                val catIcon = if (bill.iconUrl.isNotEmpty()) {
                    bill.iconUrl
                } else {
                    CategoryIconHelper.findCategoryIcon(this@MainActivity, bill.categoryName, bill.type)
                }

                val defaultResId = if(bill.type == 1) android.R.drawable.ic_input_add else android.R.drawable.ic_menu_edit
                
                if (catIcon.isNotEmpty()) {
                    Glide.with(this@MainActivity)
                        .load(catIcon)
                        .transform(CircleCrop())
                        .error(defaultResId)
                        .into(h.categoryIcon)
                } else {
                    h.categoryIcon.setImageResource(defaultResId)
                }

                // 加载资产图标
                val assetIcon = findAssetIcon(this@MainActivity, bill.assetName)
                if (assetIcon.isNotEmpty()) {
                    h.assetIcon.visibility = View.VISIBLE
                    Glide.with(this@MainActivity)
                        .load(assetIcon)
                        .transform(CircleCrop())
                        .into(h.assetIcon)
                } else {
                    h.assetIcon.visibility = View.GONE
                }

                h.itemView.setOnClickListener {
                    if (isSelectionMode) {
                        h.checkBox.isChecked = !h.checkBox.isChecked
                    } else {
                        editBill(bill)
                    }
                }

                // 长按进入编辑模式
                h.itemView.setOnLongClickListener {
                    if (isSelectionMode) return@setOnLongClickListener false
                    
                    isSelectionMode = true
                    selectedBills.add(bill)
                    
                    // 同步更新外部 UI
                    findViewById<MaterialButton>(R.id.btn_edit_bills).text = "取消"
                    findViewById<View>(R.id.layout_batch_actions).visibility = View.VISIBLE
                    
                    notifyDataSetChanged()
                    true
                }
            }
        }
        override fun getItemCount() = items.size
    }

    class BillViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val checkBox = v.findViewById<android.widget.CheckBox>(R.id.cb_bill_select)
        val categoryIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val assetIcon = v.findViewById<ImageView>(R.id.iv_bill_asset_icon)
        val category = v.findViewById<TextView>(R.id.tv_bill_category)
        val detail = v.findViewById<TextView>(R.id.tv_bill_detail)
        val time = v.findViewById<TextView>(R.id.tv_bill_time)
        val amount = v.findViewById<TextView>(R.id.tv_bill_amount)
    }

    class DateHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val dateText = v.findViewById<TextView>(R.id.tv_header_date)
        val selectDayBtn = v.findViewById<TextView>(R.id.btn_select_day)
    }



    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        syncMultiBillSwitches()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1002) {
            // 语音功能开关触发的录音权限申请
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 权限已授予：自动打开开关并保存设置
                val layoutAsrMode = findViewById<View>(R.id.layout_asr_mode)
                switchShowVoice?.isChecked = true
                Prefs.setShowAiVoice(this, true)
                layoutAsrMode?.visibility = View.VISIBLE
                Utils.toast(this, "已获得录音权限，语音功能已开启")
            } else {
                // 权限被拒绝：开关保持关闭状态，给出提示
                Utils.toast(this, "录音权限被拒绝，语音功能无法使用")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun setExcludeFromRecents(exclude: Boolean) {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(exclude) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            // RECORD_AUDIO 在开启语音功能开关时按需申请
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 100)
            }
        }
        checkBatteryOptimization()
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("需要忽略电池优化")
                    .setMessage("为了保证翻转记账在后台不被系统休眠中断，请允许应用忽略电池优化。")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }
}
