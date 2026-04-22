package tao.test.flipaccounting

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip

class AiPromptEditorActivity : AppCompatActivity() {

    private lateinit var etSingle: EditText
    private lateinit var etMulti: EditText
    private lateinit var etRule: EditText
    private lateinit var etReceipt: EditText
    private lateinit var etReceiptVision: EditText
    private lateinit var etScreenAccounting: EditText
    private lateinit var etOcrRefine: EditText

    private lateinit var btnSingle: Chip
    private lateinit var btnMulti: Chip
    private lateinit var btnRule: Chip
    private lateinit var btnReceipt: Chip
    private lateinit var btnReceiptVision: Chip
    private lateinit var btnScreenAccounting: Chip
    private lateinit var btnOcrRefine: Chip

    private var currentMode = "single"
    private var isEditMode = false
    private var isExpanded = false
    private var canShowScreenAccounting = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_prompt_editor)

        findViewById<View>(R.id.btn_back_prompt_editor).setOnClickListener { finish() }

        etSingle = findViewById(R.id.et_custom_prompt)
        etMulti = findViewById(R.id.et_multi_prompt)
        etRule = findViewById(R.id.et_rule_prompt)
        etReceipt = findViewById(R.id.et_receipt_prompt)
        etReceiptVision = findViewById(R.id.et_receipt_vision_prompt)
        etScreenAccounting = findViewById(R.id.et_screen_accounting_prompt)
        etOcrRefine = findViewById(R.id.et_receipt_ocr_refine_prompt)

        btnSingle = findViewById(R.id.btn_single_prompt)
        btnMulti = findViewById(R.id.btn_multi_prompt)
        btnRule = findViewById(R.id.btn_rule_prompt)
        btnReceipt = findViewById(R.id.btn_receipt_prompt)
        btnReceiptVision = findViewById(R.id.btn_receipt_vision_prompt)
        btnScreenAccounting = findViewById(R.id.btn_screen_accounting_prompt)
        btnOcrRefine = findViewById(R.id.btn_ocr_refine_prompt)

        val promptModeGrid = findViewById<GridLayout>(R.id.chip_group_prompt_modes)
        val tvToggleExpand = findViewById<TextView>(R.id.tv_toggle_expand)
        val tvEditPrompt = findViewById<TextView>(R.id.tv_edit_prompt)
        val btnReset = findViewById<MaterialButton>(R.id.btn_reset_prompt)
        val btnSavePrompt = findViewById<MaterialButton>(R.id.btn_save_prompt)

        canShowScreenAccounting = Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this)
        btnScreenAccounting.visibility = if (canShowScreenAccounting) View.VISIBLE else View.GONE
        if (!canShowScreenAccounting) {
            promptModeGrid.removeView(btnScreenAccounting)
        }

        etSingle.setText(Prefs.getAiPrompt(this).ifBlank { AIService.getDefaultSingleBillPrompt(this) })
        etMulti.setText(Prefs.getMultiBillPrompt(this).ifBlank { AIService.getDefaultMultiBillPrompt(this) })
        etRule.setText(Prefs.getRulePrompt(this).ifBlank { AIService.RULE_EXTRACT_PROMPT_DEFAULT })
        etReceipt.setText(Prefs.getReceiptBillPrompt(this).ifBlank { AIService.RECEIPT_BILL_PROMPT })
        etReceiptVision.setText(Prefs.getReceiptVisionPrompt(this).ifBlank { AIService.RECEIPT_VISION_RETRY_PROMPT_DEFAULT })
        etScreenAccounting.setText(Prefs.getScreenAccountingPrompt(this).ifBlank { AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT })
        etOcrRefine.setText(Prefs.getReceiptOcrRefinePrompt(this).ifBlank { AIService.RECEIPT_OCR_REFINE_PROMPT_DEFAULT })

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
        }

        fun switchMode(newMode: String) {
            if (newMode == "screen_accounting" && !canShowScreenAccounting) return
            currentMode = newMode
            updateUI()
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

        tvEditPrompt.setOnClickListener {
            isEditMode = !isEditMode
            updateLockState()
            Utils.toast(this, if (isEditMode) "提示词已解锁，可以编辑" else "提示词已锁定，防止误改")
        }

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

        btnSingle.setOnClickListener { switchMode("single") }
        btnMulti.setOnClickListener { switchMode("multi") }
        btnRule.setOnClickListener { switchMode("rule") }
        btnReceipt.setOnClickListener { switchMode("receipt") }
        btnReceiptVision.setOnClickListener { switchMode("receipt_vision") }
        btnScreenAccounting.setOnClickListener { switchMode("screen_accounting") }
        btnOcrRefine.setOnClickListener { switchMode("ocr_refine") }

        btnReset.setOnClickListener {
            when (currentMode) {
                "single" -> etSingle.setText(AIService.getDefaultSingleBillPrompt(this))
                "multi" -> etMulti.setText(AIService.getDefaultMultiBillPrompt(this))
                "rule" -> etRule.setText(AIService.RULE_EXTRACT_PROMPT_DEFAULT)
                "receipt" -> etReceipt.setText(AIService.RECEIPT_BILL_PROMPT)
                "receipt_vision" -> etReceiptVision.setText(AIService.RECEIPT_VISION_RETRY_PROMPT_DEFAULT)
                "screen_accounting" -> etScreenAccounting.setText(AIService.SCREEN_ACCOUNTING_PROMPT_DEFAULT)
                "ocr_refine" -> etOcrRefine.setText(AIService.RECEIPT_OCR_REFINE_PROMPT_DEFAULT)
            }
            Utils.toast(this, "已恢复当前模式默认提示词")
        }

        btnSavePrompt.setOnClickListener {
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

            Prefs.setAiPrompt(this, if (singleText == singleDefault) "" else singleText)
            Prefs.setMultiBillPrompt(this, if (multiText == multiDefault) "" else multiText)
            Prefs.setRulePrompt(this, if (ruleText == ruleDefault) "" else ruleText)
            Prefs.setReceiptBillPrompt(this, if (receiptText == receiptDefault) "" else receiptText)
            Prefs.setReceiptVisionPrompt(this, if (receiptVisionText == receiptVisionDefault) "" else receiptVisionText)
            Prefs.setScreenAccountingPrompt(this, if (screenText == screenDefault) "" else screenText)
            Prefs.setReceiptOcrRefinePrompt(this, if (ocrRefineText == ocrRefineDefault) "" else ocrRefineText)

            Utils.toast(this, "提示词已保存")
            finish()
        }

        updateUI()
        updateLockState()
    }
}
