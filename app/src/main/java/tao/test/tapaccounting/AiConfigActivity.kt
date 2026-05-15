package tao.test.tapaccounting

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
import tao.test.tapaccounting.ui.dialog.OverlayDialogs

class AiConfigActivity : AppCompatActivity() {
    private enum class ThinkingBinding {
        TEXT,
        VISION,
        FIXED_OFF
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main_ai)

        findViewById<View>(R.id.btn_back_to_settings).setOnClickListener { finish() }

        val etKey = findViewById<EditText>(R.id.et_api_key)
        val tvSelectedModel = findViewById<TextView>(R.id.tv_selected_model)
        val layoutModelSelector = findViewById<View>(R.id.layout_model_selector)

        val btnTextModel = findViewById<Chip>(R.id.btn_text_model)
        val btnVisionModel = findViewById<Chip>(R.id.btn_vision_model)
        val btnSpeech = findViewById<Chip>(R.id.btn_speech_prompt)

        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<View>(R.id.btn_save_config)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val switchEnableThinkingCurrent = findViewById<SwitchMaterial>(R.id.switch_enable_thinking_current)
        val tvThinkingScopeHint = findViewById<TextView>(R.id.tv_thinking_scope_hint)
        val switchEnableReceiptOcrRefine = findViewById<SwitchMaterial>(R.id.switch_enable_receipt_ocr_refine)

        etKey.setText(Prefs.getAiKey(this))

        switchEnableReceiptOcrRefine.isChecked = Prefs.isReceiptOcrRefineEnabled(this)
        switchEnableReceiptOcrRefine.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setReceiptOcrRefineEnabled(this, isChecked)
        }

        var currentMode = "text"
        var updatingThinkingUi = false
        val modeModels = mutableMapOf(
            "text" to Prefs.getAiMultiModel(this),
            "vision" to Prefs.getAiReceiptVisionModel(this),
            "speech" to Prefs.getAiSpeechModel(this)
        )

        val allModelsList = mutableListOf<String>()

        tvEditPrompt.visibility = View.GONE

        val cachedModels = Prefs.getAiModelsCache(this).map { it.trim() }.filter { it.isNotEmpty() }
        if (cachedModels.isNotEmpty()) {
            allModelsList.addAll(cachedModels)
        }

        fun updateModelDisplay() {
            tvSelectedModel.text = modeModels[currentMode] ?: ""
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
            btnTextModel.isChecked = currentMode == "text"
            btnVisionModel.isChecked = currentMode == "vision"
            btnSpeech.isChecked = currentMode == "speech"

            tvSelectedModel.text = modeModels[currentMode] ?: ""
            updateThinkingUi()
        }

        fun switchMode(newMode: String) {
            tvSelectedModel.text.toString().takeIf { it.isNotEmpty() }?.let {
                modeModels[currentMode] = it
            }
            currentMode = newMode
            updateUI()
        }

        btnTextModel.setOnClickListener { switchMode("text") }
        btnVisionModel.setOnClickListener { switchMode("vision") }
        btnSpeech.setOnClickListener { switchMode("speech") }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            val url = Prefs.getAiUrl(this)
            if (key.isEmpty()) {
                Utils.toast(this, getString(R.string.please_input_api_key))
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
                                else -> "连接失败，请稍后重试"
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

            val verifiedModels = allModelsList.toSet()
            if (verifiedModels.isNotEmpty()) {
                modeModels.keys.toList().forEach { key ->
                    val selected = modeModels[key].orEmpty()
                    if (selected.isNotBlank() && selected !in verifiedModels) {
                        modeModels[key] = ""
                    }
                }
            }

            Prefs.setAiKey(this, etKey.text.toString().trim())
            val textModel = modeModels["text"].orEmpty()
            val visionModel = modeModels["vision"].orEmpty()
            val speechModel = modeModels["speech"].orEmpty()

            Prefs.setAiModel(this, textModel)
            Prefs.setAiMultiModel(this, textModel)
            Prefs.setAiModifyModel(this, textModel)
            Prefs.setAiCategoryRefineModel(this, textModel)
            Prefs.setAiRouterModel(this, textModel)
            Prefs.setAiQueryModel(this, textModel)
            Prefs.setAiRuleModel(this, textModel)
            Prefs.setAiReceiptModel(this, textModel)
            Prefs.setAiReceiptVisionModel(this, visionModel)
            Prefs.setAiScreenModel(this, visionModel)
            Prefs.setAiReceiptOcrRefineModel(this, textModel)
            Prefs.setAiSpeechModel(this, speechModel)
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
}
