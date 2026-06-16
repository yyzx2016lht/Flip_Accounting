package com.taostudio.tapaccounting

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
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
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

class AiConfigActivity : AppCompatActivity() {
    private enum class ThinkingBinding {
        TEXT,
        VISION,
        FIXED_OFF
    }

    private lateinit var tvCurrentProvider: TextView
    private lateinit var tvProviderCapabilities: TextView
    private lateinit var layoutProviderSelector: View
    private lateinit var switchManualModelSelection: SwitchMaterial
    private lateinit var tvDefaultModelReadonlyHint: TextView
    private lateinit var btnRestoreDefaultModels: MaterialButton
    private lateinit var tvSelectedModel: TextView
    private lateinit var layoutModelSelector: View
    private lateinit var btnVisionModel: Chip
    private lateinit var btnSpeech: Chip

    private var currentPreset: AiProviderPreset = AiProviderRegistry.allPresets().first()
    private var currentMode = "text"
    private var updatingManualSwitch = false
    private lateinit var etKey: EditText
    private lateinit var modeModels: MutableMap<String, String>
    private lateinit var allModelsList: MutableList<String>
    private lateinit var refreshProviderState: () -> Unit

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main_ai)

        findViewById<View>(R.id.btn_back_to_settings).setOnClickListener { finish() }

        currentPreset = AiProviderRegistry.resolvePreset(this)

        etKey = findViewById(R.id.et_api_key)
        tvSelectedModel = findViewById(R.id.tv_selected_model)
        layoutModelSelector = findViewById(R.id.layout_model_selector)
        tvCurrentProvider = findViewById(R.id.tv_current_provider)
        tvProviderCapabilities = findViewById(R.id.tv_provider_capabilities)
        layoutProviderSelector = findViewById(R.id.layout_provider_selector)
        switchManualModelSelection = findViewById(R.id.switch_manual_model_selection)
        tvDefaultModelReadonlyHint = findViewById(R.id.tv_default_model_readonly_hint)
        btnRestoreDefaultModels = findViewById(R.id.btn_restore_default_models)

        val btnTextModel = findViewById<Chip>(R.id.btn_text_model)
        btnVisionModel = findViewById(R.id.btn_vision_model)
        btnSpeech = findViewById(R.id.btn_speech_prompt)

        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<View>(R.id.btn_save_config)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val switchEnableThinkingCurrent = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_current)
        val tvThinkingScopeHint = findViewById<TextView>(R.id.tv_thinking_scope_hint)
        val switchEnableReceiptOcrRefine = findViewById<SwitchMaterial>(R.id.switch_enable_receipt_ocr_refine)

        etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))
        tvCurrentProvider.text = currentPreset.displayName
        tvProviderCapabilities.text = AiProviderRegistry.capabilitySummary(currentPreset)

        switchEnableReceiptOcrRefine.isChecked = Prefs.isReceiptOcrRefineEnabled(this)
        switchEnableReceiptOcrRefine.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setReceiptOcrRefineEnabled(this, isChecked)
        }

        var updatingThinkingUi = false
        modeModels = mutableMapOf(
            "text" to effectiveModelForMode("text"),
            "vision" to effectiveModelForMode("vision"),
            "speech" to effectiveModelForMode("speech")
        )

        allModelsList = mutableListOf()
        tvEditPrompt.visibility = View.GONE

        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        if (cachedModels.isNotEmpty()) {
            allModelsList.addAll(cachedModels)
        }

        layoutProviderSelector.setOnClickListener {
            AiProviderSetupDialog.show(
                activity = this,
                initialProviderId = currentPreset.id,
                cancelable = true,
                onFinished = { result ->
                    if (result != null) {
                        applyProviderSetupResult(result)
                    } else {
                        etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))
                        refreshProviderState()
                    }
                }
            )
        }

        fun thinkingBindingForMode(mode: String): ThinkingBinding = when (mode) {
            "text" -> ThinkingBinding.TEXT
            "vision" -> ThinkingBinding.VISION
            else -> ThinkingBinding.FIXED_OFF
        }

        fun isThinkingEnabled(binding: ThinkingBinding): Boolean = when (binding) {
            ThinkingBinding.TEXT -> Prefs.isAiThinkingMultiBillEnabled(this)
            ThinkingBinding.VISION -> Prefs.isAiThinkingVisionEnabled(this)
            ThinkingBinding.FIXED_OFF -> false
        }

        fun setThinkingEnabled(binding: ThinkingBinding, enabled: Boolean) {
            when (binding) {
                ThinkingBinding.TEXT -> {
                    Prefs.setAiThinkingMultiBillEnabled(this, enabled)
                    Prefs.setAiThinkingCategoryRefineEnabled(this, enabled)
                }
                ThinkingBinding.VISION -> Prefs.setAiThinkingVisionEnabled(this, enabled)
                ThinkingBinding.FIXED_OFF -> Unit
            }
        }

        fun updateThinkingUi() {
            val binding = thinkingBindingForMode(currentMode)
            val modeTitle = when (currentMode) {
                "text" -> "主文本模型"
                "vision" -> "视觉模型"
                "speech" -> "语音识别"
                else -> "当前模式"
            }
            updatingThinkingUi = true
            when (binding) {
                ThinkingBinding.FIXED_OFF -> {
                    switchEnableThinkingCurrent.isEnabled = false
                    switchEnableThinkingCurrent.isChecked = false
                    switchEnableThinkingCurrent.text = "$modeTitle：思考固定关闭"
                    tvThinkingScopeHint.text = "该模式当前固定关闭思考，避免额外耗时。"
                }

                else -> {
                    switchEnableThinkingCurrent.isEnabled = true
                    switchEnableThinkingCurrent.isChecked = isThinkingEnabled(binding)
                    switchEnableThinkingCurrent.text = "$modeTitle：启用思考"
                    tvThinkingScopeHint.text = when (binding) {
                        ThinkingBinding.TEXT -> "会同时影响记账、多账单、修改账单、二段分类、规则生成和 OCR 整理。"
                        ThinkingBinding.VISION -> "会同时影响小票图片识别、视觉重试和截屏识别。"
                        ThinkingBinding.FIXED_OFF -> ""
                    }
                }
            }
            updatingThinkingUi = false
        }

        switchEnableThinkingCurrent.setOnCheckedChangeListener { _, isChecked ->
            if (updatingThinkingUi) return@setOnCheckedChangeListener
            val binding = thinkingBindingForMode(currentMode)
            if (binding != ThinkingBinding.FIXED_OFF) {
                setThinkingEnabled(binding, isChecked)
            }
        }

        fun showModelSearchDialog() {
            if (!Prefs.isAiManualModelSelectionEnabled(this)) return
            if (!isModeSupported(currentMode)) {
                showCapabilityNotSupportedToast(currentMode)
                return
            }
            val dialogLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 16)
            }
            val etSearch = EditText(this).apply {
                hint = "搜索 AI 模型"
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
                .setTitle("选择 AI 模型")
                .setView(dialogLayout)
                .create()

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s?.toString()?.trim().orEmpty()
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

        fun updateCapabilityChips() {
            btnVisionModel.isEnabled = currentPreset.supportsVision
            btnSpeech.isEnabled = currentPreset.supportsCloudSpeech
            btnVisionModel.alpha = if (currentPreset.supportsVision) 1f else 0.45f
            btnSpeech.alpha = if (currentPreset.supportsCloudSpeech) 1f else 0.45f
        }

        fun updateModelSelectionUi() {
            val manual = Prefs.isAiManualModelSelectionEnabled(this)
            layoutModelSelector.isEnabled = manual
            layoutModelSelector.alpha = if (manual) 1f else 0.55f
            tvDefaultModelReadonlyHint.visibility = if (manual) View.GONE else View.VISIBLE
        }

        fun updateUI() {
            btnTextModel.isChecked = currentMode == "text"
            btnVisionModel.isChecked = currentMode == "vision"
            btnSpeech.isChecked = currentMode == "speech"
            tvSelectedModel.text = modeModels[currentMode].orEmpty().ifBlank {
                defaultLabelForUnsupportedMode(currentMode)
            }
            updateThinkingUi()
            updateCapabilityChips()
            updateModelSelectionUi()
        }

        fun switchMode(newMode: String) {
            if (newMode != "text" && !isModeSupported(newMode)) {
                showCapabilityNotSupportedToast(newMode)
                return
            }
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() }?.let {
                if (!it.startsWith("（")) {
                    modeModels[currentMode] = it
                }
            }
            currentMode = newMode
            updateUI()
        }

        btnTextModel.setOnClickListener { switchMode("text") }
        btnVisionModel.setOnClickListener { switchMode("vision") }
        btnSpeech.setOnClickListener { switchMode("speech") }

        refreshProviderState = {
            currentPreset = AiProviderRegistry.resolvePreset(this)
            tvCurrentProvider.text = currentPreset.displayName
            tvProviderCapabilities.text = AiProviderRegistry.capabilitySummary(currentPreset)
            etKey.setText(Prefs.getAiProviderKey(this, currentPreset.id))
            reloadModelsCache()
            syncModeModelsFromPrefs()
            updateUI()
        }

        switchManualModelSelection.isChecked = Prefs.isAiManualModelSelectionEnabled(this)
        switchManualModelSelection.setOnCheckedChangeListener { _, isChecked ->
            if (updatingManualSwitch) return@setOnCheckedChangeListener
            if (isChecked) {
                showManualModelDisclaimer {
                    Prefs.setAiManualModelSelectionEnabled(this, true)
                    btnRestoreDefaultModels.visibility = View.VISIBLE
                    updateModelSelectionUi()
                }
            } else {
                Prefs.setAiManualModelSelectionEnabled(this, false)
                AiProviderRegistry.applyDefaultModels(this, currentPreset)
                modeModels["text"] = effectiveModelForMode("text")
                modeModels["vision"] = effectiveModelForMode("vision")
                modeModels["speech"] = effectiveModelForMode("speech")
                btnRestoreDefaultModels.visibility = View.GONE
                updateModelSelectionUi()
                updateUI()
            }
        }
        btnRestoreDefaultModels.visibility =
            if (Prefs.isAiManualModelSelectionEnabled(this)) View.VISIBLE else View.GONE
        btnRestoreDefaultModels.setOnClickListener {
            AiProviderRegistry.applyDefaultModels(this, currentPreset)
            modeModels["text"] = effectiveModelForMode("text")
            modeModels["vision"] = effectiveModelForMode("vision")
            modeModels["speech"] = effectiveModelForMode("speech")
            updateUI()
            Utils.toast(this, getString(R.string.ai_default_models_restored))
        }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isEmpty()) {
                Utils.toast(this, getString(R.string.please_input_api_key))
                return@setOnClickListener
            }

            btnTest.isEnabled = false
            btnTest.text = getString(R.string.ai_provider_testing)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsForProvider(currentPreset, key)
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        Prefs.setAiKey(this@AiConfigActivity, key)
                        if (models.isNotEmpty()) {
                            val cleanedModels = models.map { it.trim() }.filter { it.isNotEmpty() }
                            Prefs.setAiModelsCache(this@AiConfigActivity, cleanedModels)
                            allModelsList.clear()
                            allModelsList.addAll(cleanedModels)
                            syncModeModelsFromPrefs()
                            updateUI()
                            Utils.toast(this@AiConfigActivity, "连接成功，已加载 ${cleanedModels.size} 个可用模型")
                        } else {
                            Prefs.setAiModelsCache(this@AiConfigActivity, emptyList())
                            allModelsList.clear()
                            syncModeModelsFromPrefs()
                            updateUI()
                            Utils.toast(this@AiConfigActivity, "连接成功，但未找到可用模型")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        val msg = e.message.orEmpty()
                        Utils.toast(
                            this@AiConfigActivity,
                            when {
                                msg.contains("timeout") || msg.contains("Failed to connect") || msg.contains("Unable to resolve host") -> "连接超时，请检查网络或 API 地址"
                                msg.contains("401") -> "认证失败：API Key 可能错误或已过期"
                                else -> "连接失败，请稍后重试"
                            }
                        )
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() && !it.startsWith("（") }?.let {
                modeModels[currentMode] = it
            }

            Prefs.setAiKey(this, etKey.text.toString().trim())

            if (!Prefs.isAiManualModelSelectionEnabled(this)) {
                AiProviderRegistry.applyDefaultModels(this, currentPreset)
            } else {
                val verifiedModels = allModelsList.toSet()
                if (verifiedModels.isNotEmpty()) {
                    modeModels.keys.toList().forEach { key ->
                        val selected = modeModels[key].orEmpty()
                        if (selected.isNotBlank() && selected !in verifiedModels) {
                            modeModels[key] = ""
                        }
                    }
                }

                val textModel = modeModels["text"].orEmpty().ifBlank { currentPreset.defaultTextModel }
                val visionModel = if (currentPreset.supportsVision) {
                    modeModels["vision"].orEmpty().ifBlank { currentPreset.defaultVisionModel }
                } else {
                    ""
                }
                val speechModel = if (currentPreset.supportsCloudSpeech) {
                    modeModels["speech"].orEmpty().ifBlank { currentPreset.defaultSpeechModel }
                } else {
                    ""
                }

                Prefs.setAiModel(this, textModel)
                Prefs.setAiMultiModel(this, textModel)
                Prefs.setAiModifyModel(this, textModel)
                Prefs.setAiCategoryRefineModel(this, textModel)
                Prefs.setAiRouterModel(this, textModel)
                Prefs.setAiQueryModel(this, textModel)
                Prefs.setAiRuleModel(this, textModel)
                Prefs.setAiReceiptModel(this, textModel)
                Prefs.setAiReceiptOcrRefineModel(this, textModel)
                Prefs.setAiReceiptVisionModel(this, visionModel)
                Prefs.setAiScreenModel(this, visionModel)
                Prefs.setAiSpeechModel(this, speechModel)
            }

            val currentThinkingBinding = thinkingBindingForMode(currentMode)
            if (currentThinkingBinding != ThinkingBinding.FIXED_OFF) {
                setThinkingEnabled(currentThinkingBinding, switchEnableThinkingCurrent.isChecked)
            }
            Prefs.setReceiptOcrRefineEnabled(this, switchEnableReceiptOcrRefine.isChecked)

            Utils.toast(this, "所有 AI 配置已保存")
            finish()
        }

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        if (::tvCurrentProvider.isInitialized) {
            refreshProviderState()
        }
    }

    private fun applyProviderSetupResult(result: AiProviderSetupResult) {
        etKey.setText(result.apiKey)
        allModelsList.clear()
        allModelsList.addAll(result.models)
        Prefs.resetChatModelOnProviderChange(this)
        refreshProviderState()
    }

    private fun reloadModelsCache() {
        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        allModelsList.clear()
        allModelsList.addAll(cachedModels)
    }

    private fun syncModeModelsFromPrefs() {
        modeModels["text"] = effectiveModelForMode("text")
        modeModels["vision"] = effectiveModelForMode("vision")
        modeModels["speech"] = effectiveModelForMode("speech")
    }

    private fun effectiveModelForMode(mode: String): String {
        if (!Prefs.isAiManualModelSelectionEnabled(this)) {
            return when (mode) {
                "vision" -> if (currentPreset.supportsVision) currentPreset.defaultVisionModel else ""
                "speech" -> if (currentPreset.supportsCloudSpeech) currentPreset.defaultSpeechModel else ""
                else -> currentPreset.defaultTextModel
            }
        }
        return when (mode) {
            "text" -> Prefs.getAiMultiModel(this)
            "vision" -> Prefs.getAiReceiptVisionModel(this)
            "speech" -> Prefs.getAiSpeechModel(this)
            else -> ""
        }
    }

    private fun isModeSupported(mode: String): Boolean = when (mode) {
        "vision" -> currentPreset.supportsVision
        "speech" -> currentPreset.supportsCloudSpeech
        else -> true
    }

    private fun defaultLabelForUnsupportedMode(mode: String): String = when (mode) {
        "vision" -> if (!currentPreset.supportsVision) "（当前提供商不支持）" else ""
        "speech" -> if (!currentPreset.supportsCloudSpeech) "（当前提供商不支持）" else ""
        else -> ""
    }

    private fun showCapabilityNotSupportedToast(mode: String) {
        val capability = when (mode) {
            "vision" -> getString(R.string.ai_capability_vision)
            "speech" -> getString(R.string.ai_capability_speech)
            else -> return
        }
        Utils.toast(
            this,
            getString(R.string.ai_capability_not_supported_fmt, currentPreset.displayName, capability)
        )
    }

    private fun showManualModelDisclaimer(onConfirm: () -> Unit) {
        fun resetSwitch() {
            if (Prefs.isAiManualModelSelectionEnabled(this)) return
            updatingManualSwitch = true
            switchManualModelSelection.isChecked = false
            updatingManualSwitch = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ai_manual_model_disclaimer_title)
            .setMessage(R.string.ai_manual_model_disclaimer_message)
            .setPositiveButton(R.string.confirm_btn) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel_btn) { _, _ -> resetSwitch() }
            .create()
        dialog.setOnCancelListener { resetSwitch() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }
}
