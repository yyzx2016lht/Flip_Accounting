package tao.test.flipaccounting

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

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

        val btnSingle = findViewById<Chip>(R.id.btn_single_prompt)
        val btnMulti = findViewById<Chip>(R.id.btn_multi_prompt)
        val btnModify = findViewById<Chip>(R.id.btn_modify_prompt)
        val btnCategoryRefine = findViewById<Chip>(R.id.btn_category_refine_prompt)
        val btnRule = findViewById<Chip>(R.id.btn_rule_prompt)
        val btnReceipt = findViewById<Chip>(R.id.btn_receipt_prompt)
        val btnReceiptVision = findViewById<Chip>(R.id.btn_receipt_vision_prompt)
        val btnScreenAccounting = findViewById<Chip>(R.id.btn_screen_accounting_prompt)
        val btnOcrRefine = findViewById<Chip>(R.id.btn_ocr_refine_prompt)
        val btnSpeech = findViewById<Chip>(R.id.btn_speech_prompt)
        val btnRouter = findViewById<Chip>(R.id.btn_router_prompt)
        val promptModeGrid = findViewById<GridLayout>(R.id.chip_group_prompt_modes)

        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<View>(R.id.btn_save_config)
        val tvToggleExpand = findViewById<TextView>(R.id.tv_toggle_expand)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val switchEnableThinkingSingle = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_single)
        val switchEnableThinkingMulti = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_multi)
        val switchEnableThinkingModify = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_modify)
        val switchEnableThinkingVision = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_vision)
        val switchEnableThinkingCategoryRefine = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_category_refine)
        val switchEnableReceiptOcrRefine = findViewById<SwitchMaterial>(R.id.switch_enable_receipt_ocr_refine)

        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        spinnerProviders.adapter = providerAdapter
        spinnerProviders.setSelection(providers.indexOf(Prefs.getAiProvider(this)).coerceAtLeast(0))

        etUrl.setText(Prefs.getAiUrl(this))
        etKey.setText(Prefs.getAiKey(this))

        switchEnableThinkingSingle.isChecked = Prefs.isAiThinkingSingleBillEnabled(this)
        switchEnableThinkingSingle.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiThinkingSingleBillEnabled(this, isChecked)
        }
        switchEnableThinkingMulti.isChecked = Prefs.isAiThinkingMultiBillEnabled(this)
        switchEnableThinkingMulti.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiThinkingMultiBillEnabled(this, isChecked)
        }
        switchEnableThinkingModify.isChecked = Prefs.isAiThinkingModifyBillEnabled(this)
        switchEnableThinkingModify.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiThinkingModifyBillEnabled(this, isChecked)
        }
        switchEnableThinkingVision.isChecked = Prefs.isAiThinkingVisionEnabled(this)
        switchEnableThinkingVision.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiThinkingVisionEnabled(this, isChecked)
        }
        switchEnableThinkingCategoryRefine.isChecked = Prefs.isAiThinkingCategoryRefineEnabled(this)
        switchEnableThinkingCategoryRefine.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setAiThinkingCategoryRefineEnabled(this, isChecked)
        }

        switchEnableReceiptOcrRefine.isChecked = Prefs.isReceiptOcrRefineEnabled(this)
        switchEnableReceiptOcrRefine.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setReceiptOcrRefineEnabled(this, isChecked)
        }

        var currentMode = "single"
        val modeModels = mutableMapOf(
            "single" to Prefs.getAiSingleModel(this),
            "multi" to Prefs.getAiMultiModel(this),
            "modify" to Prefs.getAiModifyModel(this),
            "category_refine" to Prefs.getAiCategoryRefineModel(this),
            "router" to Prefs.getAiRouterModel(this),
            "rule" to Prefs.getAiRuleModel(this),
            "receipt" to Prefs.getAiReceiptModel(this),
            "receipt_vision" to Prefs.getAiReceiptVisionModel(this),
            "screen_accounting" to Prefs.getAiScreenModel(this),
            "ocr_refine" to Prefs.getAiReceiptOcrRefineModel(this),
            "speech" to Prefs.getAiSpeechModel(this)
        )

        val allModelsList = mutableListOf<String>()
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

        tvEditPrompt.setOnClickListener {
            startActivity(Intent(this, AiPromptEditorActivity::class.java))
        }
        tvEditPrompt.text = "编辑提示词"
        tvEditPrompt.setTextColor(Color.parseColor("#1A73E8"))
        tvToggleExpand.visibility = View.GONE
        findViewById<View>(R.id.btn_reset_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_custom_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_multi_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_rule_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_receipt_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_receipt_vision_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_screen_accounting_prompt).visibility = View.GONE
        findViewById<View>(R.id.et_receipt_ocr_refine_prompt).visibility = View.GONE

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

            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = this@AiConfigActivity,
                widthRatio = 0.9f
            )
            dialog.window?.let { win ->
                val targetHeight = (resources.displayMetrics.heightPixels * 0.7f).toInt()
                win.attributes = win.attributes.apply {
                    height = targetHeight
                }
            }
        }

        layoutModelSelector.setOnClickListener {
            showModelSearchDialog()
        }

        updateModelDisplay()

        fun updateUI() {
            btnSingle.isChecked = currentMode == "single"
            btnMulti.isChecked = currentMode == "multi"
            btnModify.isChecked = currentMode == "modify"
            btnCategoryRefine.isChecked = currentMode == "category_refine"
            btnRule.isChecked = currentMode == "rule"
            btnReceipt.isChecked = currentMode == "receipt"
            btnReceiptVision.isChecked = currentMode == "receipt_vision"
            btnScreenAccounting.isChecked = canShowScreenAccounting && currentMode == "screen_accounting"
            btnOcrRefine.isChecked = currentMode == "ocr_refine"
            btnSpeech.isChecked = currentMode == "speech"
            btnRouter.isChecked = currentMode == "router"

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
        btnModify.setOnClickListener { switchMode("modify") }
        btnCategoryRefine.setOnClickListener { switchMode("category_refine") }
        btnRule.setOnClickListener { switchMode("rule") }
        btnReceipt.setOnClickListener { switchMode("receipt") }
        btnReceiptVision.setOnClickListener { switchMode("receipt_vision") }
        btnScreenAccounting.setOnClickListener { switchMode("screen_accounting") }
        btnOcrRefine.setOnClickListener { switchMode("ocr_refine") }
        btnSpeech.setOnClickListener { switchMode("speech") }
        btnRouter.setOnClickListener { switchMode("router") }

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
            Prefs.setAiModifyModel(this, modeModels["modify"] ?: "")
            Prefs.setAiCategoryRefineModel(this, modeModels["category_refine"] ?: "")
            Prefs.setAiRouterModel(this, modeModels["router"] ?: "")
            Prefs.setAiRuleModel(this, modeModels["rule"] ?: "")
            Prefs.setAiReceiptModel(this, modeModels["receipt"] ?: "")
            Prefs.setAiReceiptVisionModel(this, modeModels["receipt_vision"] ?: "")
            Prefs.setAiScreenModel(this, if (canShowScreenAccounting) (modeModels["screen_accounting"] ?: "") else "")
            Prefs.setAiReceiptOcrRefineModel(this, modeModels["ocr_refine"] ?: "")
            Prefs.setAiSpeechModel(this, modeModels["speech"] ?: "")
            Prefs.setAiThinkingSingleBillEnabled(this, switchEnableThinkingSingle.isChecked)
            Prefs.setAiThinkingMultiBillEnabled(this, switchEnableThinkingMulti.isChecked)
            Prefs.setAiThinkingModifyBillEnabled(this, switchEnableThinkingModify.isChecked)
            Prefs.setAiThinkingVisionEnabled(this, switchEnableThinkingVision.isChecked)
            Prefs.setReceiptOcrRefineEnabled(this, switchEnableReceiptOcrRefine.isChecked)

            Utils.toast(this, "所有 AI 配置已保存")
            finish()
        }

        updateUI()
    }
}
