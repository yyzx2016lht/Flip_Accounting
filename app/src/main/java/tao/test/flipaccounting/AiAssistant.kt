package tao.test.flipaccounting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class AiAssistant(private val ctx: Context) {

    private var currentDialog: AlertDialog? = null
    private var tvThinkingLog: TextView? = null
    private var tvRecordedTextPreview: TextView? = null
    private var analyzeJob: Job? = null
    private var lastReceiptImageUri: Uri? = null
    private var currentHideStreamText: Boolean = false

    // 外部可注入语音按钮绑定逻辑
    var voiceInputBtnSetup: ((View) -> Unit)? = null

    // 外部可注入图片选择逻辑
    var imagePicker: (() -> Unit)? = null

    companion object {
        const val MODE_INPUT = 0
        const val MODE_RECORDING = 1
        const val MODE_LOADING = 2
        const val MODE_CANCEL = 3

        private const val VOICE_PLACEHOLDER_TEXT = "正在解析语音..."
    }

    fun showInputPanel(
        defaultText: String? = null,
        mode: Int = MODE_INPUT,
        isMultiMode: Boolean? = null,
        hideStreamText: Boolean = false,
        onResult: (JSONObject) -> Unit
    ) {
        if (!ensureOverlayPermission()) return
        val finalMode = mode
        currentHideStreamText = hideStreamText

        // 已有对话框则直接复用
        if (currentDialog?.isShowing == true) {
            updatePanelState(finalMode, defaultText)
            if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
                startAnalysis(defaultText, isMultiMode, onResult)
            }
            return
        }

        stopFlipIfNeeded()

        val (dialog, view) = createDialog(cancelable = true)
        currentDialog = dialog

        val btnClose = view.findViewById<View>(R.id.btn_close)
        val btnIdentify = view.findViewById<View>(R.id.btn_dialog_identify)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)

        disableSelectionActionModeIfService(etInput)

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        btnClose.setOnClickListener { dismiss() }

        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Utils.toast(ctx, "请输入记账内容")
                return@setOnClickListener
            }
            updatePanelState(MODE_LOADING, "正在分析语义...")
            startAnalysis(text, isMultiMode, onResult)
        }

        updatePanelState(finalMode, defaultText)
        if (finalMode == MODE_INPUT && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
            etInput.setText(defaultText)
            etInput.setSelection(defaultText.length)
        } else if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != VOICE_PLACEHOLDER_TEXT) {
            startAnalysis(defaultText, isMultiMode, onResult)
        }
    }

    private fun updatePanelState(mode: Int, text: String? = null) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)

        when (mode) {
            MODE_INPUT -> {
                layoutInput.visibility = View.VISIBLE
                layoutLoading.visibility = View.GONE
                layoutResult.visibility = View.GONE
                dialog.setCancelable(true)
                btnClose.visibility = View.VISIBLE
                if (!text.isNullOrEmpty() && text != VOICE_PLACEHOLDER_TEXT) {
                    val etInput = layoutInput.findViewById<EditText>(R.id.et_ai_input)
                    etInput?.setText(text)
                    etInput?.setSelection(text.length)
                }
            }

            MODE_RECORDING -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.text = if (!text.isNullOrEmpty()) "正在听：$text" else "倾听中..."
                tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                tvRecordedTextPreview?.visibility = View.GONE
                dialog.setCancelable(false)
                btnClose.visibility = View.GONE
            }

            MODE_CANCEL -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.text = "松开即可取消"
                tvThinkingLog?.setTextColor(android.graphics.Color.RED)
                tvRecordedTextPreview?.visibility = View.GONE
                dialog.setCancelable(false)
                btnClose.visibility = View.GONE
            }

            MODE_LOADING -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                tvThinkingLog?.text = text ?: "正在处理..."
                tvRecordedTextPreview?.visibility = View.GONE
                dialog.setCancelable(true)
                btnClose.visibility = View.VISIBLE
            }
        }
    }

    private fun updateLoadingText(text: String) {
        tvThinkingLog?.let { label ->
            if (label.text?.toString() != text) {
                label.text = text
            }
        }
    }

    private fun startAnalysis(text: String, isMultiMode: Boolean?, onResult: (JSONObject) -> Unit) {
        analyzeJob?.cancel()
        val hideStream = currentHideStreamText
        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AIService.analyzeAccounting(ctx, text, isMultiMode) { status ->
                    Handler(Looper.getMainLooper()).post {
                        if (currentDialog?.isShowing == true) {
                            if (hideStream && status.startsWith("AI_STREAM_TEXT::")) {
                                // 流式输出时只显示加载动画，不显示JSON内容
                                updateLoadingText("记账中...")
                            } else {
                                updateLoadingText(status)
                            }
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    if (result == null) {
                        Utils.toast(ctx, "识别失败：AI 返回内容无法解析")
                        updatePanelState(MODE_INPUT, text)
                        return@withContext
                    }

                    if (result.has("bills")) {
                        val bills = result.getJSONArray("bills")
                        for (i in 0 until bills.length()) {
                            bills.getJSONObject(i).put("original_text_from_user", text)
                        }
                        if (bills.length() == 1) {
                            // 单条账单直接填入表单，无需确认
                            dismiss()
                            onResult(bills.getJSONObject(0))
                        } else {
                            // 多条账单需要用户确认
                            showResult(result, onResult)
                        }
                    } else {
                        result.put("original_text_from_user", text)
                        // 单条账单直接填入表单，无需确认
                        dismiss()
                        onResult(result)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, "已取消")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, "AIService 错误: ${e.message ?: "未知异常"}")
                    updatePanelState(MODE_INPUT, text)
                }
            }
        }
    }

    private fun showResult(result: JSONObject, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)

        val tvResTime = view.findViewById<TextView>(R.id.tv_res_time)
        val tvResMoney = view.findViewById<TextView>(R.id.tv_res_money)
        val tvResCate = view.findViewById<TextView>(R.id.tv_res_cate)
        val tvResSummary = view.findViewById<TextView>(R.id.tv_res_summary)
        val tvResAsset = view.findViewById<TextView>(R.id.tv_res_asset)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_fill)
        val assetFeatureEnabled = Prefs.isAssetFeatureEnabled(ctx)

        if (result.has("bills")) {
            val bills = result.getJSONArray("bills")
            val count = bills.length()

            tvResMoney.text = "识别到 $count 条账单"
            tvResMoney.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))

            if (count > 0) {
                val first = bills.getJSONObject(0)
                val firstAmt = first.optDouble("amount", 0.0)
                val firstCat = formatCategoryDisplay(first.optString("category_name", ""))
                val firstRemark = first.optString("remarks", first.optString("remark", "")).ifBlank { "未填写备注" }
                tvResCate.text = "首笔分类: ${firstCat.ifBlank { "待确认" }}"
                tvResSummary.text = buildMultiBillSummary(bills, firstAmt, firstRemark)
                if (assetFeatureEnabled) {
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = buildMultiBillAssetSummary(bills)
                } else {
                    tvResAsset.visibility = View.GONE
                }
            } else {
                tvResCate.text = "识别结果待确认"
                tvResSummary.text = "请确认后继续处理。"
                tvResAsset.visibility = View.GONE
            }
            tvResTime.visibility = View.GONE
        } else {
            val type = result.optInt("type", 0)
            val amt = result.optDouble("amount", 0.0)

            tvResMoney.text = when (type) {
                1 -> "+$amt"
                2, 3 -> "$amt"
                else -> "-$amt"
            }
            tvResMoney.setTextColor(
                android.graphics.Color.parseColor(
                    if (type == 1) "#E91E63" else "#2E7D32"
                )
            )

            val timeStr = result.optString("time", "")
            tvResTime.text = if (timeStr.isNotEmpty()) "时间: $timeStr" else "时间: 现在"
            tvResTime.visibility = View.VISIBLE
            val remark = result.optString("remarks", result.optString("remark", "")).ifBlank { "未填写备注" }

            when (type) {
                2 -> {
                    tvResCate.text = "转入账户: ${result.optString("to_asset_name", "--")}"
                    tvResSummary.text = remark
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = "转出账户: ${result.optString("asset_name", "--")}"
                }

                3 -> {
                    tvResCate.text = "还款给: ${result.optString("to_asset_name", "--")}"
                    tvResSummary.text = remark
                    tvResAsset.visibility = View.VISIBLE
                    tvResAsset.text = "支付方: ${result.optString("asset_name", "--")}"
                }

                else -> {
                    val cat = formatCategoryDisplay(result.optString("category_name", ""))
                    tvResCate.text = "分类: ${cat.ifBlank { "待确认" }}"
                    tvResSummary.text = remark
                    if (assetFeatureEnabled) {
                        val assetName = result.optString("asset_name", "")
                        tvResAsset.visibility = View.VISIBLE
                        tvResAsset.text = "账户: ${if (assetName.isEmpty()) "未识别" else assetName}"
                    } else {
                        tvResAsset.visibility = View.GONE
                    }
                }
            }
        }

        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        dialog.setCancelable(true)
        btnClose.visibility = View.VISIBLE

        btnConfirm.setOnClickListener {
            dismiss()
            onResult(result)
        }
    }

    private fun formatCategoryDisplay(raw: String): String {
        return raw
            .replace("/::/", " - ")
            .replace("/:::/", " - ")
            .replace("::", " - ")
            .trim()
    }

    private fun buildMultiBillSummary(bills: JSONArray, firstAmount: Double, firstRemark: String): String {
        val previewLines = mutableListOf<String>()
        previewLines += "首笔: $firstRemark  ¥${String.format("%.2f", firstAmount)}"
        val remaining = bills.length() - 1
        if (remaining > 0) {
            previewLines += "其余 $remaining 笔会在确认后继续处理。"
        }
        return previewLines.joinToString("\n")
    }

    private fun buildMultiBillAssetSummary(bills: JSONArray): String {
        val assets = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        for (i in 0 until bills.length()) {
            val bill = bills.getJSONObject(i)
            bill.optString("asset_name", "").takeIf { it.isNotBlank() }?.let { assets += it }
            bill.optString("to_asset_name", "").takeIf { it.isNotBlank() }?.let { targets += it }
        }
        return when {
            targets.isNotEmpty() && assets.isNotEmpty() ->
                "账户: ${assets.joinToString("、")}  ->  ${targets.joinToString("、")}"
            assets.isNotEmpty() ->
                "账户: ${assets.joinToString("、")}"
            else ->
                "确认后将继续逐条处理"
        }
    }

    fun dismiss() {
        analyzeJob?.cancel()
        currentDialog?.dismiss()
        currentDialog = null
    }

    fun getCurrentInputText(): String {
        val dialog = currentDialog ?: return ""
        val view = dialog.findViewById<View>(android.R.id.content) ?: return ""
        val etInput = view.findViewById<EditText>(R.id.et_ai_input) ?: return ""
        return etInput.text.toString()
    }

    fun analyzeImage(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        if (!ensureOverlayPermission()) return
        lastReceiptImageUri = imageUri

        val ocrMode = Prefs.getOcrMode(ctx)
        if (ocrMode == Prefs.OCR_MODE_MULTIMODAL) {
            analyzeImageMultimodal(imageUri, onResult)
            return
        }

        val (dialog, view) = createDialog(cancelable = false)
        currentDialog = dialog

        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        disableSelectionActionModeIfService(etInput)
        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = "正在识别图片文字..."
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            analyzeJob?.cancel()
            dismiss()
        }

        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = ReceiptOcrHelper.analyzeImage(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) { dismiss() }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "解析失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun analyzeImageMultimodal(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        if (!ensureOverlayPermission()) return
        lastReceiptImageUri = imageUri

        val (dialog, view) = createDialog(cancelable = false)
        currentDialog = dialog

        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        disableSelectionActionModeIfService(etInput)
        view.findViewById<View>(R.id.btn_dialog_voice)?.let { voiceInputBtnSetup?.invoke(it) }

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = "正在发送图片给视觉模型..."
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            analyzeJob?.cancel()
            dismiss()
        }

        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = ReceiptOcrHelper.analyzeImageByMultimodal(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "已取消")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "图片解析失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun startReceiptAnalysis(ocrText: String, onResult: (JSONObject) -> Unit) {
        analyzeJob?.cancel()
        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = AIService.analyzeReceiptByOcrText(ctx, ocrText)
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "已取消")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, "小票解析失败: ${e.message ?: "未知异常"}")
                    val dialog = currentDialog ?: return@withContext
                    val v = dialog.findViewById<View>(android.R.id.content) ?: return@withContext
                    v.findViewById<View>(R.id.layout_loading)?.visibility = View.GONE
                    v.findViewById<View>(R.id.layout_input)?.visibility = View.VISIBLE
                    v.findViewById<View>(R.id.btn_close)?.visibility = View.VISIBLE
                    tvThinkingLog?.text = "解析失败，请修改后重试"
                    dialog.setCancelable(true)
                }
            }
        }
    }

    private fun showReceiptSummary(summary: String, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        val btnIdentify = view.findViewById<TextView>(R.id.btn_dialog_identify)
        val btnRetryVision = view.findViewById<View>(R.id.btn_dialog_retry_vision)

        layoutLoading.visibility = View.GONE
        layoutInput.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        dialog.setCancelable(true)

        etInput.setText(summary)
        etInput.setSelection(summary.length)
        etInput.hint = "请核对小票内容，可补充时间、账户等信息"

        btnIdentify.text = "生成账单"
        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) {
                Utils.toast(ctx, "请输入内容")
                return@setOnClickListener
            }
            updatePanelState(MODE_LOADING, "正在生成结构化账单...")
            startAnalysis(text, true, onResult)
        }

        val canRetryWithVision = lastReceiptImageUri != null && Prefs.getOcrMode(ctx) == Prefs.OCR_MODE_LOCAL
        if (btnRetryVision != null) {
            btnRetryVision.visibility = if (canRetryWithVision) View.VISIBLE else View.GONE
            btnRetryVision.setOnClickListener {
                val imageUri = lastReceiptImageUri
                if (imageUri == null) {
                    Utils.toast(ctx, "未找到原始图片，无法重试")
                    return@setOnClickListener
                }
                retryReceiptWithVision(imageUri, onResult)
            }
        }
    }

    private fun retryReceiptWithVision(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = "正在使用视觉模型重试..."
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
        dialog.setCancelable(false)

        analyzeJob?.cancel()
        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val summary = ReceiptOcrHelper.analyzeImageByMultimodal(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "已取消")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    view.findViewById<View>(R.id.layout_loading)?.visibility = View.GONE
                    view.findViewById<View>(R.id.layout_input)?.visibility = View.VISIBLE
                    view.findViewById<View>(R.id.btn_close)?.visibility = View.VISIBLE
                    dialog.setCancelable(true)
                    Utils.toast(ctx, "视觉重试失败: ${e.message ?: "未知错误"}")
                }
            }
        }
    }

    private fun createDialog(cancelable: Boolean): Pair<AlertDialog, View> {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_dialog_ai_input, null)

        val dialog = AlertDialog.Builder(themeContext)
            .setView(view)
            .setCancelable(cancelable)
            .create()

        dialog.setOnDismissListener {
            restartFlipIfNeeded()
            currentDialog = null
        }

        dialog.window?.apply {
            val useOverlayWindow =
                ctx !is Activity &&
                    (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx))
            if (useOverlayWindow) {
                setType(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        WindowManager.LayoutParams.TYPE_PHONE
                    }
                )
            }
            setBackgroundDrawableResource(android.R.color.transparent)
            WindowCompat.setDecorFitsSystemWindows(this, false)
            setWindowAnimations(R.style.Animation_FlipAccounting_DialogSoft)
            setDimAmount(0.34f)
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            attributes.y = (120 * ctx.resources.displayMetrics.density).toInt()
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        try {
            dialog.show()
        } catch (_: BadTokenException) {
            return dialog to view
        } catch (_: IllegalStateException) {
            return dialog to view
        }
        dialog.window?.let { win ->
            val dm = ctx.resources.displayMetrics
            val maxCardWidth = (360 * dm.density).toInt()
            val widthPx = kotlin.math.min((dm.widthPixels * 0.9f).toInt(), maxCardWidth)
            win.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        return dialog to view
    }

    private fun stopFlipIfNeeded() {
        if (!Prefs.isFlipEnabled(ctx)) return
        val stopIntent = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP_FLIP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(stopIntent)
            } else {
                ctx.startService(stopIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restartFlipIfNeeded() {
        if (!Prefs.isFlipEnabled(ctx)) return
        val startIntent = Intent(ctx, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START_FLIP
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(startIntent)
            } else {
                ctx.startService(startIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disableSelectionActionModeIfService(etInput: EditText?) {
        if (ctx is Activity || etInput == null) return
        val blankCallback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onPrepareActionMode(
                mode: android.view.ActionMode?,
                menu: android.view.Menu?
            ): Boolean = false

            override fun onActionItemClicked(
                mode: android.view.ActionMode?,
                item: android.view.MenuItem?
            ): Boolean = false

            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        }
        etInput.customInsertionActionModeCallback = blankCallback
        etInput.customSelectionActionModeCallback = blankCallback
    }

    private fun ensureOverlayPermission(): Boolean {
        if (ctx is Activity) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (Settings.canDrawOverlays(ctx)) return true

        Utils.toast(ctx, "请先开启悬浮窗权限后再使用智能识别")
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }
        return false
    }
}
