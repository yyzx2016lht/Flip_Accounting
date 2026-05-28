package com.taostudio.tapaccounting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiFeatureSettingsActivity : AppCompatActivity() {

    private lateinit var btnAiDetailConfig: com.google.android.material.button.MaterialButton
    private lateinit var btnManageAiRules: com.google.android.material.button.MaterialButton
    private lateinit var layoutAiKeyWarning: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_feature_settings)

        findViewById<View>(R.id.btn_back_ai_feature).setOnClickListener { finish() }
        setupAiFeatureSettings()
    }

    private fun setupAiFeatureSettings() {
        val layoutAiMain = findViewById<View>(R.id.layout_ai_main_entry)
        val switchShowAi = findViewById<CompoundButton>(R.id.switch_show_ai)
        val switchAiChatMode = findViewById<CompoundButton>(R.id.switch_ai_chat_mode)
        val switchShowAiChatEntry = findViewById<CompoundButton>(R.id.switch_show_ai_chat_entry)
        val showAiChatEntryRow = switchShowAiChatEntry.parent as? View
        val layoutOpenAiChatPage = findViewById<View>(R.id.layout_open_ai_chat_page)
        val layoutMultiBillFastMode = findViewById<View>(R.id.layout_multi_bill_fast_mode)
        val switchAiLlmRouter = findViewById<CompoundButton>(R.id.switch_ai_llm_router)
        val layoutAiLlmRouter = findViewById<View>(R.id.layout_ai_llm_router)
        val dividerAiLlmRouter = findViewById<View>(R.id.divider_ai_llm_router)
        // AI 总开关由设置中心控制，这里只展示具体能力配置。
        (switchShowAi.parent as? View)?.visibility = View.GONE
        layoutAiMain.visibility = View.VISIBLE

        switchAiChatMode.isChecked = Prefs.getAiEntryMode(this) == Prefs.AI_ENTRY_MODE_CHAT
        fun updateMultiBillUiVisibility() {
            layoutMultiBillFastMode.visibility = View.VISIBLE
        }
        switchAiChatMode.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiEntryMode(
                this,
                if (isChecked) Prefs.AI_ENTRY_MODE_CHAT else Prefs.AI_ENTRY_MODE_TRADITIONAL
            )
            updateMultiBillUiVisibility()
        }
        layoutOpenAiChatPage.setOnClickListener { switchAiChatMode.performClick() }

        Prefs.setShowAiChatEntry(this, false)
        showAiChatEntryRow?.visibility = View.GONE

        val switchMultiBillFastMode = findViewById<CompoundButton>(R.id.switch_multi_bill_fast_mode)
        switchMultiBillFastMode.apply {
            // UI 显示为“详细记账”：开启详细=底层关闭极简
            isChecked = !Prefs.isMultiBillFastMode(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setMultiBillFastMode(this@AiFeatureSettingsActivity, !isChecked)
            }
        }
        layoutMultiBillFastMode.apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { switchMultiBillFastMode.performClick() }
        }

        updateMultiBillUiVisibility()
        layoutAiLlmRouter.visibility = View.GONE
        dividerAiLlmRouter.visibility = View.GONE
        switchAiLlmRouter.apply {
            isChecked = Prefs.isAiLlmRouterEnabled(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setAiLlmRouterEnabled(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        findViewById<CompoundButton>(R.id.switch_local_rule_override)?.apply {
            val unifiedLocalRuleEnabled =
                Prefs.isAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity) ||
                Prefs.isLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity)
            isChecked = unifiedLocalRuleEnabled
            Prefs.setAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity, unifiedLocalRuleEnabled)
            Prefs.setLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity, unifiedLocalRuleEnabled)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setAiPromptCorrectionEnabled(this@AiFeatureSettingsActivity, isChecked)
                Prefs.setLocalRuleOverrideEnabled(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        findViewById<CompoundButton>(R.id.switch_show_voice).apply {
            isChecked = Prefs.isShowAiVoice(this@AiFeatureSettingsActivity)
            val ctx = this@AiFeatureSettingsActivity
            val layoutAsrEngine = ctx.findViewById<View>(R.id.layout_asr_engine)
            val layoutAsrModel = ctx.findViewById<View>(R.id.layout_asr_model)
            layoutAsrEngine.visibility = if (isChecked) View.VISIBLE else View.GONE
            layoutAsrModel.visibility = View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && ContextCompat.checkSelfPermission(this@AiFeatureSettingsActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
                Prefs.setShowAiVoice(this@AiFeatureSettingsActivity, isChecked)
                layoutAsrEngine.visibility = if (isChecked) View.VISIBLE else View.GONE
                if (!isChecked) layoutAsrModel.visibility = View.GONE
            }
        }

        val btnAsrOnline = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_asr_online)
        val btnAsrOffline = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_asr_offline)
        val tvAsrModelStatus = findViewById<TextView>(R.id.tv_asr_model_status)
        val btnAsrModelAction = findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_asr_model_action)
        val layoutAsrModel = findViewById<View>(R.id.layout_asr_model)

        fun updateAsrUi() {
            val mode = Prefs.getAsrMode(this@AiFeatureSettingsActivity)
            val isOnline = mode == Prefs.ASR_MODE_API
            btnAsrOnline.apply {
                setTextColor(if (isOnline) Color.parseColor("#FFFFFF") else Color.parseColor("#4E5A6A"))
                setBackgroundColor(if (isOnline) Color.parseColor("#5C6BC0") else Color.parseColor("#FFFFFF"))
                strokeColor = if (isOnline) null else ColorStateList.valueOf(Color.parseColor("#DCE2EA"))
                isSelected = isOnline
            }
            btnAsrOffline.apply {
                setTextColor(if (!isOnline) Color.parseColor("#FFFFFF") else Color.parseColor("#4E5A6A"))
                setBackgroundColor(if (!isOnline) Color.parseColor("#5C6BC0") else Color.parseColor("#FFFFFF"))
                strokeColor = if (!isOnline) null else ColorStateList.valueOf(Color.parseColor("#DCE2EA"))
                isSelected = !isOnline
            }
            if (mode == Prefs.ASR_MODE_WHISPER) {
                layoutAsrModel.visibility = View.VISIBLE
                if (LocalAsrService.isModelReady(this@AiFeatureSettingsActivity)) {
                    tvAsrModelStatus.text = "已安装·SenseVoice离线模型 (约140MB)"
                    tvAsrModelStatus.setTextColor(Color.parseColor("#5C6BC0"))
                    btnAsrModelAction.text = "删除模型"
                    btnAsrModelAction.setOnClickListener {
                        val dialog = AlertDialog.Builder(this@AiFeatureSettingsActivity)
                            .setTitle("删除模型")
                            .setMessage("确定要删除本地模型数据释放空间吗？")
                            .setPositiveButton("删除") { _, _ ->
                                LocalAsrService.deleteModel(this@AiFeatureSettingsActivity)
                                Prefs.setAsrMode(this@AiFeatureSettingsActivity, Prefs.ASR_MODE_API)
                                updateAsrUi()
                            }
                            .setNegativeButton("取消", null)
                            .create()
                        OverlayDialogs.showPageCenterDialog(
                            dialog = dialog,
                            ctx = this@AiFeatureSettingsActivity,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                } else {
                    tvAsrModelStatus.text = "未安装离线模型 (约140MB，需联网下载)"
                    tvAsrModelStatus.setTextColor(Color.parseColor("#607D8B"))
                    btnAsrModelAction.text = "下载模型"
                    btnAsrModelAction.setOnClickListener {
                        val dialog = AlertDialog.Builder(this@AiFeatureSettingsActivity)
                            .setTitle("安装离线模型")
                            .setMessage("在线下载: 约140MB\n本地导入: 选择手机中的模型压缩文件")
                            .setPositiveButton("在线下载") { _, _ ->
                                LocalAsrService.downloadModelWithUI(this@AiFeatureSettingsActivity) {
                                    runOnUiThread { updateAsrUi() }
                                }
                            }
                            .setNeutralButton("本地导入") { _, _ ->
                                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                                startActivityForResult(intent, 2001)
                            }
                            .setNegativeButton("取消", null)
                            .create()
                        OverlayDialogs.showPageCenterDialog(
                            dialog = dialog,
                            ctx = this@AiFeatureSettingsActivity,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                }
            } else {
                layoutAsrModel.visibility = View.GONE
            }
        }

        btnAsrOnline.setOnClickListener {
            Prefs.setAsrMode(this@AiFeatureSettingsActivity, Prefs.ASR_MODE_API)
            updateAsrUi()
        }
        btnAsrOffline.setOnClickListener {
            Prefs.setAsrMode(this@AiFeatureSettingsActivity, Prefs.ASR_MODE_WHISPER)
            updateAsrUi()
        }
        updateAsrUi()

        findViewById<CompoundButton>(R.id.switch_show_ai_image).apply {
            isChecked = Prefs.isShowAiImage(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiImage(this@AiFeatureSettingsActivity, isChecked)
            }
        }

        // 截屏记账
        val switchScreenAccounting = findViewById<CompoundButton>(R.id.switch_screen_accounting)
        val tvScreenAccountingHint = findViewById<TextView>(R.id.tv_screen_accounting_hint)
        val layoutScreenAccounting = findViewById<View>(R.id.layout_screen_accounting)
        var ignoreScreenAccountingToggle = false

        fun updateScreenAccountingVisibility() {
            val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            val hasShizuku = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)
            layoutScreenAccounting.visibility = if (hasAccessibility || hasShizuku) View.VISIBLE else View.GONE
            tvScreenAccountingHint.visibility = if (hasAccessibility || hasShizuku) View.GONE else View.VISIBLE
        }

        switchScreenAccounting.apply {
            isChecked = Prefs.isShowScreenAccounting(this@AiFeatureSettingsActivity)
            setOnCheckedChangeListener { _, isChecked ->
                if (ignoreScreenAccountingToggle) return@setOnCheckedChangeListener
                val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                val hasShizuku = Prefs.isShizukuModeEnabled(this@AiFeatureSettingsActivity) && ShizukuSafe.isReady(this@AiFeatureSettingsActivity)
                if (!hasAccessibility && !hasShizuku) {
                    ignoreScreenAccountingToggle = true
                    post {
                        switchScreenAccounting.isChecked = false
                        ignoreScreenAccountingToggle = false
                    }
                    Utils.toast(this@AiFeatureSettingsActivity, getString(R.string.ai_screen_accounting_perm_hint))
                    return@setOnCheckedChangeListener
                }
                Prefs.setShowScreenAccounting(this@AiFeatureSettingsActivity, isChecked)
                Utils.toast(this@AiFeatureSettingsActivity, if (isChecked) getString(R.string.screen_accounting_enabled) else getString(R.string.screen_accounting_disabled))
            }
        }

        updateScreenAccountingVisibility()

        btnManageAiRules = findViewById(R.id.btn_manage_ai_rules)
        layoutAiKeyWarning = findViewById(R.id.layout_ai_key_warning)
        btnManageAiRules.setOnClickListener {
            startActivity(Intent(this, AiRuleManageActivity::class.java))
        }
        layoutAiKeyWarning.setOnClickListener { showSiliconFlowApiKeyDialog() }

        btnAiDetailConfig = findViewById(R.id.btn_ai_detailed_config)
        updateAiDetailConfigButton()

        try {
            val toggleIds = intArrayOf(
                R.id.switch_ai_chat_mode,
                R.id.switch_show_ai_chat_entry,
                R.id.switch_ai_llm_router,
                R.id.switch_local_rule_override,
                R.id.switch_multi_bill_fast_mode,
                R.id.switch_show_voice,
                R.id.switch_show_ai_image,
                R.id.switch_screen_accounting
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
        } catch (_: Exception) {
        }
    }

    private fun updateAiDetailConfigButton() {
        val detailUnlocked = Prefs.isAiDetailConfigUnlocked(this)
        val hasApiKey = Prefs.getAiKey(this).isNotBlank()
        if (detailUnlocked) {
            layoutAiKeyWarning.visibility = View.GONE
            btnAiDetailConfig.visibility = View.VISIBLE
            updateRuleButtonLayout(locked = false)
            btnAiDetailConfig.text = getString(R.string.ai_core_model)
            btnAiDetailConfig.setTextColor(Color.parseColor("#FFFFFF"))
            btnAiDetailConfig.setBackgroundColor(Color.parseColor("#5C6BC0"))
            btnAiDetailConfig.strokeColor = null
            btnAiDetailConfig.setOnClickListener {
                startActivity(Intent(this, AiConfigActivity::class.java))
            }
        } else {
            layoutAiKeyWarning.visibility = if (hasApiKey) View.GONE else View.VISIBLE
            btnAiDetailConfig.visibility = View.GONE
            updateRuleButtonLayout(locked = true)
        }
    }

    private fun updateRuleButtonLayout(locked: Boolean) {
        val lp = btnManageAiRules.layoutParams
        if (lp is LinearLayout.LayoutParams) {
            lp.marginEnd = if (locked) 0 else resources.displayMetrics.density.times(6).toInt()
            lp.height = resources.displayMetrics.density.times(if (locked) 52 else 44).toInt()
            lp.width = 0
            lp.weight = 1f
            btnManageAiRules.layoutParams = lp
        } else if (lp is ViewGroup.MarginLayoutParams) {
            lp.marginEnd = if (locked) 0 else resources.displayMetrics.density.times(6).toInt()
            btnManageAiRules.layoutParams = lp
        }
        btnManageAiRules.text = getString(R.string.accounting_rules_btn)
    }

    private fun showSiliconFlowApiKeyDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.ai_key_dialog_title))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvHint = TextView(this).apply {
            text = getString(R.string.ai_key_dialog_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 12)
        }
        val etKey = EditText(this).apply {
            hint = "sk-xxxxxxxx"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
            setText(Prefs.getAiKey(this@AiFeatureSettingsActivity))
        }
        val tvHowToGet = TextView(this).apply {
            text = getString(R.string.ai_key_how_to_get)
            textSize = 12f
            setTextColor(Color.parseColor("#5C6BC0"))
            setPadding(0, 12, 0, 0)
        }
        layout.addView(tvHint)
        layout.addView(etKey)
        layout.addView(tvHowToGet)
        builder.setView(layout)
        builder.setPositiveButton(getString(R.string.save_and_test_btn), null)
        builder.setNegativeButton(getString(R.string.cancel_btn), null)
        val dialog = builder.create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val input = etKey.text?.toString()?.trim().orEmpty()
                if (input == "1433223") {
                    Prefs.setAiDetailConfigUnlocked(this, true)
                    updateAiDetailConfigButton()
                    Utils.toast(this, getString(R.string.ai_config_unlocked))
                    dialog.dismiss()
                    return@setOnClickListener
                }
                if (!input.startsWith("sk-")) {
                    etKey.error = "请输入 sk- 开头的硅基流动 API Key"
                    return@setOnClickListener
                }

                confirm.isEnabled = false
                confirm.text = "正在测试..."
                CoroutineScope(Dispatchers.IO).launch {
                    val result = runCatching {
                        AIService.fetchModelsWithDetails(Prefs.getAiUrl(this@AiFeatureSettingsActivity), input)
                    }
                    withContext(Dispatchers.Main) {
                        confirm.isEnabled = true
                        confirm.text = getString(R.string.save_and_test_btn)
                        result.onSuccess { models ->
                            val cleanedModels = models.map { it.trim() }.filter { it.isNotEmpty() }
                            Prefs.setAiKey(this@AiFeatureSettingsActivity, input)
                            if (cleanedModels.isNotEmpty()) {
                                Prefs.setAiModelsCache(this@AiFeatureSettingsActivity, cleanedModels)
                            }
                            updateAiDetailConfigButton()
                            Utils.toast(
                                this@AiFeatureSettingsActivity,
                                if (cleanedModels.isNotEmpty()) {
                                    "Key 已保存，连接成功，获取到 ${cleanedModels.size} 个模型"
                                } else {
                                    "Key 已保存，连接成功，但未获取到模型列表"
                                }
                            )
                            dialog.dismiss()
                        }.onFailure { error ->
                            Utils.toast(this@AiFeatureSettingsActivity, aiKeyTestErrorMessage(error))
                        }
                    }
                }
            }
        }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun aiKeyTestErrorMessage(error: Throwable): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) ->
                "认证失败：请检查硅基流动 API Key 是否正确"
            msg.contains("timeout", ignoreCase = true) ||
                msg.contains("Failed to connect", ignoreCase = true) ||
                msg.contains("Unable to resolve host", ignoreCase = true) ->
                "连接超时，请检查网络后重试"
            else -> "连接失败，请稍后重试"
        }
    }

    private fun showAiDetailConfigUnlockDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(getString(R.string.config_key_dialog_title))
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvHint = TextView(this).apply {
            text = getString(R.string.config_key_dialog_hint)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA4B2"))
            setPadding(0, 0, 0, 16)
        }
        val etPassword = EditText(this).apply {
            hint = getString(R.string.input_key_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(tvHint)
        layout.addView(etPassword)
        builder.setView(layout)
        builder.setPositiveButton(getString(R.string.confirm_btn)) { _, _ ->
            if (etPassword.text.toString().trim() == "1433223") {
                Prefs.setAiDetailConfigUnlocked(this, true)
                updateAiDetailConfigButton()
                Utils.toast(this, getString(R.string.ai_config_unlocked))
            } else {
                Utils.toast(this, getString(R.string.key_error))
            }
        }
        builder.setNegativeButton(getString(R.string.cancel_btn), null)
        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.8f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showOcrDebugRecordsDialog() {
        val records = Prefs.getOcrDebugRecords(this)
        if (records.isEmpty()) {
            Utils.toast(this, "暂无 OCR 原文记录")
            return
        }

        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val items = records.mapIndexed { index, item ->
            val time = formatter.format(java.util.Date(item.timestamp))
            val preview = item.text.replace("\n", " ").take(24)
            "${index + 1}. $time | ${item.source} | $preview"
        }.toTypedArray()

        val dialog = AlertDialog.Builder(this)
            .setTitle("OCR 原文记录（共 ${records.size} 条）")
            .setItems(items) { _, which ->
                showSingleOcrDebugRecordDialog(records, which)
            }
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空记录") { _, _ ->
                Prefs.clearOcrDebugRecords(this)
                Utils.toast(this, "已清空 OCR 记录")
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showSingleOcrDebugRecordDialog(records: List<OcrDebugRecord>, index: Int) {
        if (index !in records.indices) return

        val item = records[index]
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        val content = buildString {
            append("时间：${formatter.format(java.util.Date(item.timestamp))}\n")
            append("来源：${item.source}\n\n")
            append(item.text)
        }

        val textView = TextView(this).apply {
            text = content
            setPadding(32, 24, 32, 24)
            textSize = 13f
            setTextIsSelectable(true)
        }
        val scrollView = ScrollView(this).apply { addView(textView) }

        val builder = AlertDialog.Builder(this)
            .setTitle("OCR 记录 ${index + 1}/${records.size}")
            .setView(scrollView)
            .setPositiveButton("复制这条") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr_debug_record_$index", item.text))
                Utils.toast(this, "已复制第 ${index + 1} 条")
            }
            .setNegativeButton("返回列表") { _, _ ->
                showOcrDebugRecordsDialog()
            }

        if (index < records.lastIndex) {
            builder.setNeutralButton("下一条") { _, _ ->
                showSingleOcrDebugRecordDialog(records, index + 1)
            }
        }

        val dialog = builder.create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.9f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data?.data != null) {
            LocalAsrService.installLocalModelWithUI(this, data.data!!) {
                recreate()
            }
        }
    }
}

