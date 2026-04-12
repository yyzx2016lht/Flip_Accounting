package tao.test.flipaccounting

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiConfigActivity : AppCompatActivity() {

    private val providers = listOf("硅基流动", "DeepSeek", "ChatGPT", "Gemini", "Kimi", "智谱清言", "OpenRouter", "通义千问", "小米MiMo", "自定义")
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
        val spinnerModels = findViewById<Spinner>(R.id.spinner_models)

        val etSingle = findViewById<EditText>(R.id.et_custom_prompt)
        val etMulti = findViewById<EditText>(R.id.et_multi_prompt)
        val etRule = findViewById<EditText>(R.id.et_rule_prompt)
        val etReceipt = findViewById<EditText>(R.id.et_receipt_prompt)
        val btnSingle = findViewById<TextView>(R.id.btn_single_prompt)
        val btnMulti = findViewById<TextView>(R.id.btn_multi_prompt)
        val btnRule = findViewById<TextView>(R.id.btn_rule_prompt)
        val btnReceipt = findViewById<TextView>(R.id.btn_receipt_prompt)
        val btnReset = findViewById<MaterialButton>(R.id.btn_reset_prompt)
        val btnTest = findViewById<MaterialButton>(R.id.btn_test_conn)
        val btnSave = findViewById<MaterialButton>(R.id.btn_save_config)
        val tvToggleExpand = findViewById<TextView>(R.id.tv_toggle_expand)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)

        // 初始化提供商选择器
        val providerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, providers)
        spinnerProviders.adapter = providerAdapter
        spinnerProviders.setSelection(providers.indexOf(Prefs.getAiProvider(this)).coerceAtLeast(0))

        etUrl.setText(Prefs.getAiUrl(this))
        etKey.setText(Prefs.getAiKey(this))
        etSingle.setText(Prefs.getAiPrompt(this))
        etMulti.setText(Prefs.getMultiBillPrompt(this))
        etRule.setText(Prefs.getRulePrompt(this))
        etReceipt.setText(Prefs.getReceiptBillPrompt(this))

        // 每种模式独立记录模型选择
        var currentMode = "single"
        val modeModels = mutableMapOf(
            "single"  to Prefs.getAiSingleModel(this),
            "multi"   to Prefs.getAiMultiModel(this),
            "rule"    to Prefs.getAiRuleModel(this),
            "receipt" to Prefs.getAiReceiptModel(this)
        )
        val allModelsList = mutableListOf<String>()

        // 编辑锁定状态
        var isEditMode = false
        var isExpanded = false

        fun updateLockState() {
            etSingle.isEnabled = isEditMode
            etMulti.isEnabled = isEditMode
            etRule.isEnabled = isEditMode
            etReceipt.isEnabled = isEditMode
            val alpha = if (isEditMode) 1.0f else 0.7f
            etSingle.alpha = alpha; etMulti.alpha = alpha; etRule.alpha = alpha; etReceipt.alpha = alpha
            tvEditPrompt.text = if (isEditMode) "锁定内容" else "启用编辑"
            tvEditPrompt.setTextColor(if (isEditMode) Color.parseColor("#4CAF50") else Color.parseColor("#F44336"))
            btnReset.visibility = if (isEditMode) View.VISIBLE else View.GONE
        }
        updateLockState()

        tvEditPrompt.setOnClickListener {
            isEditMode = !isEditMode
            updateLockState()
            Utils.toast(this, if (isEditMode) "提示词已解锁，可以编辑" else "提示词已锁定，防止误触")
        }

        // EditText 在 ScrollView 内可独立滚动
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

        tvToggleExpand.setOnClickListener {
            isExpanded = !isExpanded
            val maxLines = if (isExpanded) 100 else 8
            etSingle.maxLines = maxLines; etMulti.maxLines = maxLines
            etRule.maxLines = maxLines; etReceipt.maxLines = maxLines
            tvToggleExpand.text = if (isExpanded) "收起内容" else "展开内容"
        }

        // 初始化模型列表：优先从缓存恢复完整列表，再补充已保存的选中项
        val cachedModels = Prefs.getAiModelsCache(this)
        if (cachedModels.isNotEmpty()) {
            allModelsList.addAll(cachedModels)
        }
        val savedModel = Prefs.getAiModel(this)
        if (savedModel.isNotEmpty() && !allModelsList.contains(savedModel)) allModelsList.add(savedModel)
        modeModels.values.forEach { m -> if (m.isNotEmpty() && !allModelsList.contains(m)) allModelsList.add(m) }

        fun updateModelSpinner() {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, allModelsList)
            spinnerModels.adapter = adapter
            val target = modeModels[currentMode] ?: ""
            if (target.isNotEmpty() && allModelsList.contains(target))
                spinnerModels.setSelection(allModelsList.indexOf(target))
        }
        updateModelSpinner()

        fun updateUI() {
            // updateUI 只负责刷新显示，不再负责保存模型选择（由切换按钮负责）
            etSingle.visibility  = if (currentMode == "single")  View.VISIBLE else View.GONE
            etMulti.visibility   = if (currentMode == "multi")   View.VISIBLE else View.GONE
            etRule.visibility    = if (currentMode == "rule")    View.VISIBLE else View.GONE
            etReceipt.visibility = if (currentMode == "receipt") View.VISIBLE else View.GONE

            val blue = Color.parseColor("#1A73E8")
            val gray = Color.parseColor("#666666")
            btnSingle.setTextColor(if (currentMode == "single") blue else gray)
            btnSingle.setBackgroundResource(if (currentMode == "single") R.drawable.bg_segmented_selected else 0)
            btnMulti.setTextColor(if (currentMode == "multi") blue else gray)
            btnMulti.setBackgroundResource(if (currentMode == "multi") R.drawable.bg_segmented_selected else 0)
            btnRule.setTextColor(if (currentMode == "rule") blue else gray)
            btnRule.setBackgroundResource(if (currentMode == "rule") R.drawable.bg_segmented_selected else 0)
            btnReceipt.setTextColor(if (currentMode == "receipt") blue else gray)
            btnReceipt.setBackgroundResource(if (currentMode == "receipt") R.drawable.bg_segmented_selected else 0)

            // 将 spinner 定位到新模式已保存的选择
            val target = modeModels[currentMode] ?: ""
            if (target.isNotEmpty() && allModelsList.contains(target))
                spinnerModels.setSelection(allModelsList.indexOf(target))
            else if (allModelsList.isNotEmpty())
                spinnerModels.setSelection(0)
        }

        fun switchMode(newMode: String) {
            // 离开当前模式前，先把 spinner 当前选中值保存到旧模式
            spinnerModels.selectedItem?.toString()?.takeIf { it.isNotEmpty() }?.let {
                modeModels[currentMode] = it
            }
            currentMode = newMode
            updateUI()
        }

        btnSingle.setOnClickListener { switchMode("single") }
        btnMulti.setOnClickListener  { switchMode("multi")  }
        btnRule.setOnClickListener   { switchMode("rule")   }
        btnReceipt.setOnClickListener { switchMode("receipt") }

        btnReset.setOnClickListener {
            when (currentMode) {
                "single"  -> etSingle.setText("")
                "multi"   -> etMulti.setText("")
                "rule"    -> etRule.setText("")
                "receipt" -> etReceipt.setText("")
            }
            Utils.toast(this, "提示词已清空（将使用系统默认）")
        }

        spinnerProviders.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (providers[position] != "自定义")
                    providerUrls[providers[position]]?.let { etUrl.setText(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (key.isEmpty()) { Utils.toast(this, "请输入 API 令牌"); return@setOnClickListener }
            btnTest.isEnabled = false
            btnTest.text = "正在连接..."
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val models = AIService.fetchModelsWithDetails(url, key)
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        if (models.isNotEmpty()) {
                            allModelsList.clear(); allModelsList.addAll(models)
                            Prefs.setAiModelsCache(this@AiConfigActivity, models)
                            updateModelSpinner()
                            Utils.toast(this@AiConfigActivity, "连接成功，已获取 ${models.size} 个模型")
                        } else {
                            Utils.toast(this@AiConfigActivity, "连通服务器，但未找到可用模型")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnTest.isEnabled = true
                        btnTest.text = "刷新并测试模型连接"
                        val msg = e.message ?: ""
                        Utils.toast(this@AiConfigActivity, when {
                            msg.contains("timeout") || msg.contains("Failed to connect") || msg.contains("Unable to resolve host") ->
                                "连接超时，请检查网络或 URL 是否正确"
                            msg.contains("401") -> "连接被拒绝：API Key 错误或已过期"
                            else -> "请求失败 (${e.javaClass.simpleName}): $msg"
                        })
                    }
                }
            }
        }

        btnSave.setOnClickListener {
            // 保存前先记录当前模式的 spinner 选择
            spinnerModels.selectedItem?.toString()?.takeIf { it.isNotEmpty() }?.let {
                modeModels[currentMode] = it
            }
            Prefs.setAiProvider(this, spinnerProviders.selectedItem?.toString() ?: "自定义")
            Prefs.setAiUrl(this, etUrl.text.toString().trim())
            Prefs.setAiKey(this, etKey.text.toString().trim())
            Prefs.setAiModel(this, modeModels["single"] ?: "")
            Prefs.setAiSingleModel(this, modeModels["single"] ?: "")
            Prefs.setAiMultiModel(this, modeModels["multi"] ?: "")
            Prefs.setAiRuleModel(this, modeModels["rule"] ?: "")
            Prefs.setAiReceiptModel(this, modeModels["receipt"] ?: "")
            Prefs.setAiPrompt(this, etSingle.text.toString().trim())
            Prefs.setMultiBillPrompt(this, etMulti.text.toString().trim())
            Prefs.setRulePrompt(this, etRule.text.toString().trim())
            Prefs.setReceiptBillPrompt(this, etReceipt.text.toString().trim())
            Utils.toast(this, "所有 AI 配置已保存")
            finish()
        }

        updateUI()
    }
}
