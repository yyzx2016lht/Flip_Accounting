package tao.test.flipaccounting

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.CompoundButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

class AiFeatureSettingsActivity : AppCompatActivity() {

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
        val isFlipOverlayEnabled = Prefs.isFlipEnabled(this)

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

        val layoutAsrMode = findViewById<View>(R.id.layout_asr_mode)
        val layoutAsrModel = findViewById<View>(R.id.layout_asr_model_info)
        val spinnerAsr = findViewById<Spinner>(R.id.spinner_asr_mode)
        val layoutOcrMode = findViewById<View>(R.id.layout_ocr_mode)
        val layoutReceiptLang = findViewById<View>(R.id.layout_receipt_lang)
        val spinnerOcrMode = findViewById<Spinner>(R.id.spinner_ocr_mode)
        val spinnerReceiptLang = findViewById<Spinner>(R.id.spinner_receipt_lang)
        val tvAsrModelDesc = findViewById<TextView>(R.id.tv_asr_model_desc)
        val btnDeleteModel = findViewById<View>(R.id.btn_delete_offline_model)

        fun updateAsrUi(mode: Int) {
            if (mode == Prefs.ASR_MODE_WHISPER) {
                if (LocalAsrService.isModelReady(this)) {
                    tvAsrModelDesc.text = "离线版阿里SenseVoice\n模型 (识别精准，约140M大小)"
                    tvAsrModelDesc.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
                    btnDeleteModel.visibility = View.VISIBLE
                    (btnDeleteModel as? TextView)?.text = "删除模型"
                    btnDeleteModel.setOnClickListener {
                        val dialog = AlertDialog.Builder(this)
                            .setTitle("删除模型")
                            .setMessage("确定要删除本地模型数据释放空间吗？")
                            .setPositiveButton("删除") { _, _ ->
                                LocalAsrService.deleteModel(this)
                                Prefs.setAsrMode(this, Prefs.ASR_MODE_API)
                                spinnerAsr.setSelection(Prefs.ASR_MODE_API)
                                updateAsrUi(Prefs.ASR_MODE_API)
                            }
                            .setNegativeButton("取消", null)
                            .create()
                        OverlayDialogs.showPageCenterDialog(
                            dialog = dialog,
                            ctx = this,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                } else {
                    tvAsrModelDesc.text = "未下载离线模型\n(推荐日常使用开启)"
                    tvAsrModelDesc.setTextColor(android.graphics.Color.parseColor("#607D8B"))
                    btnDeleteModel.visibility = View.VISIBLE
                    (btnDeleteModel as? TextView)?.text = "下载模型"
                    btnDeleteModel.setOnClickListener {
                        val dialog = AlertDialog.Builder(this)
                            .setTitle("安装离线模型")
                            .setMessage("在线下载: 约45MB\n本地导入: 选择手机中的模型压缩文件")
                            .setPositiveButton("在线下载") { _, _ ->
                                LocalAsrService.downloadModelWithUI(this) {
                                    runOnUiThread {
                                        updateAsrUi(Prefs.ASR_MODE_WHISPER)
                                        Utils.toast(this, "模型下载完成已部署")
                                    }
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
                            ctx = this,
                            cancelOnTouchOutside = true,
                            useSolidPanelBackground = true
                        )
                    }
                }
            } else {
                tvAsrModelDesc.text = "云端 (仅需联网)"
                tvAsrModelDesc.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
                btnDeleteModel.visibility = View.VISIBLE
                btnDeleteModel.setOnClickListener {
                    Utils.toast(this, "正在使用的是在线 API 服务")
                }
            }
        }

        findViewById<CompoundButton>(R.id.switch_show_voice).apply {
            isChecked = Prefs.isShowAiVoice(this@AiFeatureSettingsActivity)
            layoutAsrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
            layoutAsrModel.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && ContextCompat.checkSelfPermission(this@AiFeatureSettingsActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
                }
                Prefs.setShowAiVoice(this@AiFeatureSettingsActivity, isChecked)
                layoutAsrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
                layoutAsrModel.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        val layoutOcrDebugPanel = findViewById<View>(R.id.layout_ocr_debug_panel)
        findViewById<CompoundButton>(R.id.switch_show_ai_image).apply {
            isChecked = Prefs.isShowAiImage(this@AiFeatureSettingsActivity)
            layoutOcrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
            layoutReceiptLang.visibility = if (isChecked) View.VISIBLE else View.GONE
            layoutOcrDebugPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setShowAiImage(this@AiFeatureSettingsActivity, isChecked)
                layoutOcrMode.visibility = if (isChecked) View.VISIBLE else View.GONE
                layoutReceiptLang.visibility = if (isChecked) View.VISIBLE else View.GONE
                layoutOcrDebugPanel.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        val btnViewOcrDebug = findViewById<View>(R.id.btn_view_ocr_debug)
        findViewById<CompoundButton>(R.id.switch_save_ocr_debug).apply {
            isChecked = Prefs.isSaveOcrDebugEnabled(this@AiFeatureSettingsActivity)
            btnViewOcrDebug.visibility = if (isChecked) View.VISIBLE else View.GONE
            setOnCheckedChangeListener { _, isChecked ->
                Prefs.setSaveOcrDebugEnabled(this@AiFeatureSettingsActivity, isChecked)
                btnViewOcrDebug.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        spinnerAsr.setSelection(Prefs.getAsrMode(this))
        updateAsrUi(Prefs.getAsrMode(this))
        spinnerAsr.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                val currentMode = Prefs.getAsrMode(this@AiFeatureSettingsActivity)
                if (currentMode != pos) {
                    if (pos == Prefs.ASR_MODE_WHISPER && !LocalAsrService.isModelReady(this@AiFeatureSettingsActivity)) {
                        Prefs.setAsrMode(this@AiFeatureSettingsActivity, pos)
                        updateAsrUi(pos)
                        Utils.toast(this@AiFeatureSettingsActivity, "请点击下方下载模型使用离线语音")
                    } else {
                        Prefs.setAsrMode(this@AiFeatureSettingsActivity, pos)
                        updateAsrUi(pos)
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        spinnerOcrMode.setSelection(Prefs.getOcrMode(this))
        spinnerOcrMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (Prefs.getOcrMode(this@AiFeatureSettingsActivity) != position) {
                    Prefs.setOcrMode(this@AiFeatureSettingsActivity, position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerReceiptLang.setSelection(Prefs.getReceiptLangMode(this))
        spinnerReceiptLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (Prefs.getReceiptLangMode(this@AiFeatureSettingsActivity) != position) {
                    Prefs.setReceiptLangMode(this@AiFeatureSettingsActivity, position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnViewOcrDebug.setOnClickListener { showOcrDebugRecordsDialog() }

        findViewById<View>(R.id.btn_manage_ai_rules).setOnClickListener {
            startActivity(Intent(this, AiRuleManageActivity::class.java))
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_ai_detailed_config).apply {
            text = "AI 详细配置"
            setOnClickListener {
                startActivity(Intent(this@AiFeatureSettingsActivity, AiConfigActivity::class.java))
            }
        }

        try {
            val toggleIds = intArrayOf(
                R.id.switch_ai_chat_mode,
                R.id.switch_show_ai_chat_entry,
                R.id.switch_ai_llm_router,
                R.id.switch_local_rule_override,
                R.id.switch_show_voice,
                R.id.switch_show_ai_image,
                R.id.switch_save_ocr_debug
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
