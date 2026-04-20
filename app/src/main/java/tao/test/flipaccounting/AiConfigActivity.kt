package tao.test.flipaccounting

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiConfigActivity : AppCompatActivity() {

    private val providers = listOf(
        "硅基流动",
        "DeepSeek",
        "ChatGPT",
        "Gemini",
        "Kimi",
        "智谱清言",
        "OpenRouter",
        "通义千问",
        "小米MiMo",
        "自定义"
    )

    private val providerUrls = mapOf(
        "硅基流动" to "https://api.siliconflow.cn",
        "DeepSeek" to "https://api.deepseek.com",
        "ChatGPT" to "https://api.openai.com",
        "Gemini" to "https://generativelanguage.googleapis.com/v1beta/openai",
        "Kimi" to "https://api.moonshot.cn",
        "智谱清言" to "https://open.bigmodel.cn/api",
        "OpenRouter" to "https://openrouter.ai/api",
        "通义千问" to "https://dashscope.aliyuncs.com/compatible-mode",
        "小米MiMo" to "https://api.xiaomimimo.com"
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main_ai)

        findViewById<View>(R.id.btn_back_to_settings).setOnClickListener { finish() }

        val spinnerProviders = findViewById<Spinner>(R.id.spinner_providers)
        val etUrl = findViewById<EditText>(R.id.et_api_url)
        val etKey = findViewById<EditText>(R.id.et_api_key)
        val tvSelectedModel = findViewById<TextView>(R.id.tv_selected_model)
        val layoutModelSelector = findViewById<View>(R.id.layout_model_selector)

        val etSingle = findViewById<EditText>(R.id.et_custom_prompt)
        val etMulti = findViewById<EditText>(R.id.et_multi_prompt)
        val etRule = findViewById<EditText>(R.id.et_rule_prompt)
        val etReceipt = findViewById<EditText>(R.id.et_receipt_prompt)
        val etReceiptVision = findViewById<EditText>(R.id.et_receipt_vision_prompt)
        val etScreenAccounting = findViewById<EditText>(R.id.et_screen_accounting_prompt)
        val etOcrRefine = findViewById<EditText>(R.id.et_receipt_ocr_refine_prompt)

        val btnSingle = findViewById<Chip>(R.id.btn_single_prompt)
        val btnMulti = findViewById<Chip>(R.id.btn_multi_prompt)
        val btnRule = findViewById<Chip>(R.id.btn_rule_prompt)
        val btnReceipt = findViewById<Chip>(R.id.btn_receipt_prompt)
        val btnReceiptVision = findViewById<Chip>(R.id.btn_receipt_vision_prompt)
        val btnScreenAccounting = findViewById<Chip>(R.id.btn_screen_accounting_prompt)
        val btnOcrRefine = findViewById<Chip>(R.id.btn_ocr_refine_prompt)
        val btnSpeech = findViewById<Chip>(R.id.btn_speech_prompt)
        val promptModeGrid = findViewById<GridLayout>(R.id.chip_group_prompt_modes)

        val btnReset = findViewById<MaterialButton>(R.id.btn_reset_prompt)
        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<MaterialButton>(R.id.btn_save_config)
        val tvToggleExpand = findViewById<TextView>(R.id.tv_toggle_expand)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val switchEnableReceiptOcrRefine = findViewById<SwitchMaterial>(R.id.switch_enable_receipt_ocr_refine)

        // ---- 隐藏重复的 AI 智能功能入口 ----
        Prefs.setShowAiChatEntry(this, false)
        val legacyEntryModeCard = findViewById<View>(R.id.layout_mode_traditional)?.parent as? View
        val legacyChatEntryCard = findViewById<View>(R.id.switch_show_ai_chat_entry)?.parent as? View
        val scroll = findViewById<android.widget.ScrollView>(R.id.root_scroll_view)
        val content = scroll.getChildAt(0) as? LinearLayout
        val legacySectionTitle = content?.let { container ->
            val card = legacyEntryModeCard ?: return@let null
            val idx = container.indexOfChild(card)
            if (idx > 0) container.getChildAt(idx - 1) else null
        }
        legacySectionTitle?.visibility = View.GONE
        legacyEntryModeCard?.visibility = View.GONE
        legacyChatEntryCard?.visibility = View.GONE
        // ---- end ----

        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        spinnerProviders.adapter = providerAdapter
        spinnerProviders.setSelection(providers.indexOf(Prefs.getAiProvider(this)).coerceAtLeast(0))

        etUrl.setText(Prefs.getAiUrl(this))
        etKey.setText(Prefs.getAiKey(this))
        etSingle.setText(Prefs.getAiPrompt(this).ifBlank { AIService.getDefaultSingleBillPrompt(this) })
        etMulti.setText(Prefs.getMultiBillPrompt(this).ifBlank { AIService.getDefaultMultiBillPrompt(this) })
        etRule.setText(Prefs.getRulePrompt(this).ifBlank { AIService.RULE_EXTRACT_PROMPT_DEFAULT })
        etReceipt.setText(Prefs.getReceiptBillPrompt(this).ifBlank { AIService.RECEIPT_BILL_PROMPT })
        etReceiptVision.setText(Prefs.getReceiptVisionPrompt(this).ifBlank { AIService.RECEIPT_VISION_RETRY_PROMPT_DEFAULT })
        etScreenAccounting.setText(Prefs.getScreenAccountingPrompt(this).ifBlank { AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT })
        etOcrRefine.setText(Prefs.getReceiptOcrRefinePrompt(this).ifBlank { AIService.RECEIPT_OCR_REFINE_PROMPT_DEFAULT })

        switchEnableReceiptOcrRefine.isChecked = Prefs.isReceiptOcrRefineEnabled(this)
        switchEnableReceiptOcrRefine.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setReceiptOcrRefineEnabled(this, isChecked)
        }

        var currentMode = "single"
        val modeModels = mutableMapOf(
            "single" to Prefs.getAiSingleModel(this),
            "multi" to Prefs.getAiMultiModel(this),
            "rule" to Prefs.getAiRuleModel(this),
            "receipt" to Prefs.getAiReceiptModel(this),
            "receipt_vision" to Prefs.getAiReceiptVisionModel(this),
            "screen_accounting" to Prefs.getAiScreenModel(this),
            "ocr_refine" to Prefs.getAiReceiptOcrRefineModel(this),
            "speech" to Prefs.getAiSpeechModel(this)
        )

        val allModelsList = mutableListOf<String>()
        var isEditMode = false
        var isExpanded = false
        val canShowScreenAccounting = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)

        btnScreenAccounting.visibility = if (canShowScreenAccounting) View.VISIBLE else View.GONE
        if (!canShowScreenAccounting && currentMode == "screen_accounting") {
            currentMode = "single"
        }
        if (!canShowScreenAccounting) {
            modeModels["screen_accounting"] = ""
            // 避免 GridLayout 在隐藏中间项时出现“空格子”。
            promptModeGrid.removeView(btnScreenAccounting)
        }

        fun modeHasPrompt(mode: String): Boolean = mode != "speech"

        fun updateLockState() {
            val promptEnabled = isEditMode && modeHasPrompt(currentMode)
            etSingle.isEnabled = isEditMode
            etMulti.isEnabled = isEditMode
            etRule.isEnabled = isEditMode
            etReceipt.isEnabled = isEditMode
            etReceiptVision.isEnabled = isEditMode
            etScreenAccounting.isEnabled = isEditMode
            etOcrRefine.isEnabled = isEditMode

            val alpha = if (promptEnabled) 1.0f else 0.7f
            etSingle.alpha = alpha
            etMulti.alpha = alpha
            etRule.alpha = alpha
            etReceipt.alpha = alpha
            etReceiptVision.alpha = alpha
            etScreenAccounting.alpha = alpha
            etOcrRefine.alpha = alpha

            tvEditPrompt.text = if (isEditMode) "锁定内容" else "启用编辑"
            tvEditPrompt.setTextColor(if (isEditMode) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
            val promptControlsVisible = if (modeHasPrompt(currentMode)) View.VISIBLE else View.GONE
            tvEditPrompt.visibility = promptControlsVisible
            tvToggleExpand.visibility = promptControlsVisible
            btnReset.visibility = if (isEditMode && modeHasPrompt(currentMode)) View.VISIBLE else View.GONE
        }

        updateLockState()

        tvEditPrompt.setOnClickListener {
            isEditMode = !isEditMode
            updateLockState()
            Utils.toast(this, if (isEditMode) "提示词已解锁，可以编辑" else "提示词已锁定，防止误改")
        }

        val touchListener = View.OnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if ((event.action and MotionEvent.ACTION_MASK) == MotionEvent.ACTION_UP) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        etSingle.setOnTouchListener(touchListener)
        etMulti.setOnTouchListener(touchListener)
        etRule.setOnTouchListener(touchListener)
        etReceipt.setOnTouchListener(touchListener)
        etReceiptVision.setOnTouchListener(touchListener)
        etScreenAccounting.setOnTouchListener(touchListener)
        etOcrRefine.setOnTouchListener(touchListener)

        tvToggleExpand.setOnClickListener {
            isExpanded = !isExpanded
            val maxLines = if (isExpanded) 100 else 8
            etSingle.maxLines = maxLines
            etMulti.maxLines = maxLines
            etRule.maxLines = maxLines
            etReceipt.maxLines = maxLines
            etReceiptVision.maxLines = maxLines
            etScreenAccounting.maxLines = maxLines
            etOcrRefine.maxLines = maxLines
            tvToggleExpand.text = if (isExpanded) "收起内容" else "展开内容"
        }

        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        if (cachedModels.isNotEmpty()) {
            allModelsList.addAll(cachedModels)
        }

        fun updateModelDisplay() {
            tvSelectedModel.text = modeModels[currentMode] ?: ""
        }

        fun showModelSearchDialog() {
            val dialogLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
            }
            val etSearch = EditText(this).apply {
                hint = "搜索模型..."
                setSingleLine()
            }
            val listView = ListView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            dialogLayout.addView(etSearch, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            dialogLayout.addView(listView)

            val filteredList = mutableListOf<String>().also { it.addAll(allModelsList) }
            val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredList)
            listView.adapter = listAdapter

            val dialog = AlertDialog.Builder(this)
                .setTitle("选择模型")
                .setView(dialogLayout)
                .create()

            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.9).toInt(),
                (resources.displayMetrics.heightPixels * 0.7).toInt()
            )

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim() ?: ""
                    filteredList.clear()
                    if (query.isEmpty()) {
                        filteredList.addAll(allModelsList)
                    } else {
                        filteredList.addAll(allModelsList.filter { it.contains(query, ignoreCase = true) })
                    }
                    listAdapter.notifyDataSetChanged()
                }
            })

            listView.setOnItemClickListener { _, _, position, _ ->
                val selected = listAdapter.getItem(position) ?: return@setOnItemClickListener
                modeModels[currentMode] = selected
                tvSelectedModel.text = selected
                dialog.dismiss()
            }

            dialog.show()
        }

        layoutModelSelector.setOnClickListener {
            showModelSearchDialog()
        }

        updateModelDisplay()

        fun updateUI() {
            etSingle.visibility = if (currentMode == "single") View.VISIBLE else View.GONE
            etMulti.visibility = if (currentMode == "multi") View.VISIBLE else View.GONE
            etRule.visibility = if (currentMode == "rule") View.VISIBLE else View.GONE
            etReceipt.visibility = if (currentMode == "receipt") View.VISIBLE else View.GONE
            etReceiptVision.visibility = if (currentMode == "receipt_vision") View.VISIBLE else View.GONE
            etScreenAccounting.visibility = if (canShowScreenAccounting && currentMode == "screen_accounting") View.VISIBLE else View.GONE
            etOcrRefine.visibility = if (currentMode == "ocr_refine") View.VISIBLE else View.GONE
            updateLockState()

            btnSingle.isChecked = currentMode == "single"
            btnMulti.isChecked = currentMode == "multi"
            btnRule.isChecked = currentMode == "rule"
            btnReceipt.isChecked = currentMode == "receipt"
            btnReceiptVision.isChecked = currentMode == "receipt_vision"
            btnScreenAccounting.isChecked = canShowScreenAccounting && currentMode == "screen_accounting"
            btnOcrRefine.isChecked = currentMode == "ocr_refine"
            btnSpeech.isChecked = currentMode == "speech"

            tvSelectedModel.text = modeModels[currentMode] ?: ""
        }

        fun switchMode(newMode: String) {
            if (newMode == "screen_accounting" && !canShowScreenAccounting) return
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() }?.let {
                modeModels[currentMode] = it
            }
            currentMode = newMode
            updateUI()
        }

        btnSingle.setOnClickListener { switchMode("single") }
        btnMulti.setOnClickListener { switchMode("multi") }
        btnRule.setOnClickListener { switchMode("rule") }
        btnReceipt.setOnClickListener { switchMode("receipt") }
        btnReceiptVision.setOnClickListener { switchMode("receipt_vision") }
        btnScreenAccounting.setOnClickListener { switchMode("screen_accounting") }
        btnOcrRefine.setOnClickListener { switchMode("ocr_refine") }
        btnSpeech.setOnClickListener { switchMode("speech") }

        btnReset.setOnClickListener {
            when (currentMode) {
                "single" -> etSingle.setText(AIService.getDefaultSingleBillPrompt(this))
                "multi" -> etMulti.setText(AIService.getDefaultMultiBillPrompt(this))
                "rule" -> etRule.setText(AIService.RULE_EXTRACT_PROMPT_DEFAULT)
                "receipt" -> etReceipt.setText(AIService.RECEIPT_BILL_PROMPT)
                "receipt_vision" -> etReceiptVision.setText(AIService.RECEIPT_VISION_RETRY_PROMPT_DEFAULT)
                "screen_accounting" -> etScreenAccounting.setText(AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT)
                "ocr_refine" -> etOcrRefine.setText(AIService.RECEIPT_OCR_REFINE_PROMPT_DEFAULT)
                "speech" -> modeModels["speech"] = PrefsAiSupport.defaultSpeechModelForUrl(etUrl.text.toString().trim())
            }
            updateModelDisplay()
            Utils.toast(this, if (currentMode == "speech") "已恢复云端语音默认模型" else "已恢复当前模式默认提示词")
        }

        spinnerProviders.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (providers[position] != "自定义") {
                    providerUrls[providers[position]]?.let { etUrl.setText(it) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (key.isEmpty()) {
                Utils.toast(this, "请输入 API 令牌")
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            btnTest.text = "正在连接..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsWithDetails(url, key)
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        if (models.isNotEmpty()) {
                            val cleanedModels = models.map { it.trim() }.filter { it.isNotEmpty() }
                            allModelsList.clear()
                            allModelsList.addAll(cleanedModels)
                            Prefs.setAiModelsCache(this@AiConfigActivity, cleanedModels)
                            updateModelDisplay()
                            Utils.toast(this@AiConfigActivity, "连接成功，已获取 ${cleanedModels.size} 个模型")
                        } else {
                            Utils.toast(this@AiConfigActivity, "连接成功，但未找到可用模型")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        val msg = e.message ?: ""
                        Utils.toast(
                            this@AiConfigActivity,
                            when {
                                msg.contains("timeout") || msg.contains("Failed to connect") || msg.contains("Unable to resolve host") -> "连接超时，请检查网络或 API 地址"
                                msg.contains("401") -> "认证失败：API Key 可能错误或已过期"
                                else -> "请求失败 (${e.javaClass.simpleName}): $msg"
                            }
                        )
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() }?.let {
                modeModels[currentMode] = it
            }

            val oldProvider = Prefs.getAiProvider(this)
            val oldUrl = Prefs.getAiUrl(this)
            val newProvider = spinnerProviders.selectedItem?.toString() ?: "自定义"
            val newUrl = etUrl.text.toString().trim()
            val providerChanged = oldProvider != newProvider || oldUrl != newUrl

            if (providerChanged) {
                Prefs.setAiModelsCache(this, emptyList())
                allModelsList.clear()
                modeModels.keys.toList().forEach { key ->
                    modeModels[key] = ""
                }
            }

            val verifiedModels = allModelsList.toSet()
            if (verifiedModels.isNotEmpty()) {
                modeModels.keys.toList().forEach { key ->
                    val selected = modeModels[key].orEmpty()
                    if (selected.isNotBlank() && selected !in verifiedModels) {
                        modeModels[key] = ""
                    }
                }
            }

            Prefs.setAiProvider(this, newProvider)
            Prefs.setAiUrl(this, newUrl)
            Prefs.setAiKey(this, etKey.text.toString().trim())

            Prefs.setAiModel(this, modeModels["single"] ?: "")
            Prefs.setAiSingleModel(this, modeModels["single"] ?: "")
            Prefs.setAiMultiModel(this, modeModels["multi"] ?: "")
            Prefs.setAiRuleModel(this, modeModels["rule"] ?: "")
            Prefs.setAiReceiptModel(this, modeModels["receipt"] ?: "")
            Prefs.setAiReceiptVisionModel(this, modeModels["receipt_vision"] ?: "")
            Prefs.setAiScreenModel(this, if (canShowScreenAccounting) (modeModels["screen_accounting"] ?: "") else "")
            Prefs.setAiReceiptOcrRefineModel(this, modeModels["ocr_refine"] ?: "")
            Prefs.setAiSpeechModel(this, modeModels["speech"] ?: "")

            val singleText = etSingle.text.toString().trim()
            val multiText = etMulti.text.toString().trim()
            val ruleText = etRule.text.toString().trim()
            val receiptText = etReceipt.text.toString().trim()
            val receiptVisionText = etReceiptVision.text.toString().trim()
            val screenText = etScreenAccounting.text.toString().trim()
            val ocrRefineText = etOcrRefine.text.toString().trim()

            val singleDefault = AIService.getDefaultSingleBillPrompt(this).trim()
            val multiDefault = AIService.getDefaultMultiBillPrompt(this).trim()
            val ruleDefault = AIService.RULE_EXTRACT_PROMPT_DEFAULT.trim()
            val receiptDefault = AIService.RECEIPT_BILL_PROMPT.trim()
            val receiptVisionDefault = AIService.RECEIPT_VISION_RETRY_PROMPT_DEFAULT.trim()
            val screenDefault = AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT.trim()
            val ocrRefineDefault = AIService.RECEIPT_OCR_REFINE_PROMPT_DEFAULT.trim()

            // 与默认提示词一致时不落库存储，运行时自动走“默认提示词 + 代码拼接规则”。
            Prefs.setAiPrompt(this, if (singleText == singleDefault) "" else singleText)
            Prefs.setMultiBillPrompt(this, if (multiText == multiDefault) "" else multiText)
            Prefs.setRulePrompt(this, if (ruleText == ruleDefault) "" else ruleText)
            Prefs.setReceiptBillPrompt(this, if (receiptText == receiptDefault) "" else receiptText)
            Prefs.setReceiptVisionPrompt(this, if (receiptVisionText == receiptVisionDefault) "" else receiptVisionText)
            Prefs.setScreenAccountingPrompt(this, if (screenText == screenDefault) "" else screenText)
            Prefs.setReceiptOcrRefinePrompt(this, if (ocrRefineText == ocrRefineDefault) "" else ocrRefineText)
            Prefs.setReceiptOcrRefineEnabled(this, switchEnableReceiptOcrRefine.isChecked)

            Utils.toast(this, "所有 AI 配置已保存")
            finish()
        }

        updateUI()
    }
}
