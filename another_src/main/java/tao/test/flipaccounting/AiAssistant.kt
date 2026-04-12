package tao.test.flipaccounting

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AiAssistant(private val ctx: Context) {

    private var currentDialog: AlertDialog? = null
    private var tvThinkingLog: TextView? = null
    private var tvRecordedTextPreview: TextView? = null
    private var analyzeJob: kotlinx.coroutines.Job? = null

    // 用于外部绑定麦克风按钮的手势
    var voiceInputBtnSetup: ((View) -> Unit)? = null

    // 图片分析回调（由 OverlayManager/Activity 设置，用于触发系统图片选择器）
    var imagePicker: (() -> Unit)? = null

    companion object {
        const val MODE_INPUT = 0      // 纯文本输入模式
        const val MODE_RECORDING = 1  // 录音中模式
        const val MODE_LOADING = 2    // 识别/分析中模式
        const val MODE_CANCEL = 3     // [新增] 松开取消模式
    }

    /**
     * 显示 AI 面板
     * @param defaultText 默认填入的文字（语音转文字的结果）
     * @param mode 初始启动模式
     * @param onResult 最终拿到记账数据的回调
     */
    fun showInputPanel(
        defaultText: String? = null,
        mode: Int = MODE_INPUT,
        isMultiMode: Boolean? = null, // [新增]
        onResult: (JSONObject) -> Unit
    ) {
        val finalMode = mode

        // 如果弹窗已存在，直接复用，避免闪烁
        if (currentDialog?.isShowing == true) {
            updatePanelState(finalMode, defaultText)
            // 如果是 Loading 模式且有文字且不是默认提示语，说明语音转写完成了，触发分析
            if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != "正在解析语音...") {
                startAnalysis(defaultText, isMultiMode, onResult)
            }
            return
        }

        // 仅在真正弹出新面板时，通知停止翻转监测，减少输入干扰并释放传感器句柄
        if (Prefs.isFlipEnabled(ctx)) {
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

        // --- 初始化弹窗 ---
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_dialog_ai_input, null)

        val dialog = AlertDialog.Builder(themeContext)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.setOnDismissListener {
            // 弹窗关闭后，恢复翻转检测（如果开启了的话）
            if (Prefs.isFlipEnabled(ctx)) {
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
            currentDialog = null
        }

        dialog.window?.apply {
            setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            attributes.y = 300
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        currentDialog = dialog
        dialog.show()

        // 调整宽度
        dialog.window?.let { win ->
            val dm = ctx.resources.displayMetrics
            val widthPx = (340 * dm.density).toInt() // 对应 XML 中的 340dp
            win.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        }

        // --- 绑定控件 ---
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val btnIdentify = view.findViewById<View>(R.id.btn_dialog_identify)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)

        // [修复] 解决在部分系统（如 OPPO/Vivo/三星）中，Service 覆盖层 EditText 点击多次导致的浮动工具栏崩溃 (UnsupportedOperationException)
        // 该异常是因为 Service Context 并非视觉 Context，不关联 Display，导致系统尝试弹出“复制/粘贴/插入”浮动菜单时失败。
        // 在 Service 环境中通过禁用 Insertion/Selection ActionModeCallback 来阻止系统调起该工具栏。
        if (ctx !is Activity) {
            val blankCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
            etInput.customInsertionActionModeCallback = blankCallback
            etInput.customSelectionActionModeCallback = blankCallback
        }

        // 保存全局引用方便 updatePanelState 使用
        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        // 绑定关闭事件
        btnClose.setOnClickListener { dismiss() }

        // 绑定内部麦克风按钮（若存在）
        val btnDialogVoice = view.findViewById<View>(R.id.btn_dialog_voice)
        if (btnDialogVoice != null) {
            voiceInputBtnSetup?.invoke(btnDialogVoice)
        }

        // 绑定“开始分析”按钮事件
        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                updatePanelState(MODE_LOADING, "正在分析语义...")
                startAnalysis(text, isMultiMode, onResult)
            } else {
                Utils.toast(ctx, "请输入记账内容")
            }
        }

        // --- 初始状态设置 ---
        updatePanelState(finalMode, defaultText)

        // 如果是输入模式且带文字 (比如重试，或语音识别完毕转入编辑)
        if (finalMode == MODE_INPUT && !defaultText.isNullOrEmpty() && defaultText != "正在解析语音...") {
            etInput.setText(defaultText)
            etInput.setSelection(defaultText.length)
        } else if (finalMode == MODE_LOADING && !defaultText.isNullOrEmpty() && defaultText != "正在解析语音...") {
            startAnalysis(defaultText, isMultiMode, onResult)
        }
    }

    /**
     * 根据状态切换 UI 显隐
     */
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
                if (!text.isNullOrEmpty() && text != "正在解析语音...") {
                    val etInput = layoutInput.findViewById<EditText>(R.id.et_ai_input)
                    if (etInput != null) {
                        etInput.setText(text)
                        etInput.setSelection(text.length)
                    }
                }
            }
            MODE_RECORDING -> {
                layoutInput.visibility = View.GONE
                layoutLoading.visibility = View.VISIBLE
                layoutResult.visibility = View.GONE
                tvThinkingLog?.text = if (!text.isNullOrEmpty()) "正在听：$text" else "倾听中..."
                tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))
                tvRecordedTextPreview?.visibility = View.GONE // 隐藏预览因为已经放在thinking里
                dialog.setCancelable(false)
                btnClose.visibility = View.GONE
            }
            MODE_CANCEL -> { // [新增]
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
                
                // 因为语音结果会直接转 MODE_INPUT，这里只负责显示 loading 的提示词
                tvThinkingLog?.text = text ?: "正在处理..."
                tvRecordedTextPreview?.visibility = View.GONE

                // 允许取消
                dialog.setCancelable(true)
                btnClose.visibility = View.VISIBLE
            }
        }
    }

    /**
     * 执行 AI 分析请求
     */
    private fun startAnalysis(text: String, isMultiMode: Boolean?, onResult: (JSONObject) -> Unit) {
        analyzeJob?.cancel()
        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AIService.analyzeAccounting(ctx, text, isMultiMode)
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        if (result.has("bills")) {
                            val bills = result.getJSONArray("bills")
                            for (i in 0 until bills.length()) {
                                bills.getJSONObject(i).put("original_text_from_user", text)
                            }
                        } else {
                            result.put("original_text_from_user", text)
                        }
                        showResult(result, onResult)
                    } else {
                        Utils.toast(ctx, "识别失败：AI 返回内容无法解析")
                        // 失败后退回输入模式，保留刚才的文字方便修改
                        updatePanelState(MODE_INPUT, text)
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

    /**
     * 显示结果卡片
     */
    private fun showResult(result: JSONObject, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val layoutResult = view.findViewById<View>(R.id.layout_result)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        
        val tvResTime = view.findViewById<TextView>(R.id.tv_res_time)
        val tvResMoney = view.findViewById<TextView>(R.id.tv_res_money)
        val tvResCate = view.findViewById<TextView>(R.id.tv_res_cate)
        val tvResAsset = view.findViewById<TextView>(R.id.tv_res_asset)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_fill)

        if (result.has("bills")) {
            // 多账单模式显示预览
            val bills = result.getJSONArray("bills")
            val count = bills.length()
            
            tvResMoney.text = "识别到 $count 条账单"
            tvResMoney.setTextColor(android.graphics.Color.parseColor("#5C6BC0"))
            
            // 取第一条作为简单的预览
            if (count > 0) {
                val first = bills.getJSONObject(0)
                val amt = first.optDouble("amount", 0.0)
                val cat = first.optString("category_name", "").replace("/:::/", " > ")
                tvResCate.text = "首笔: $cat ($amt)"
                tvResAsset.text = "点击确认后将依次处理"
            }
            tvResTime.visibility = View.GONE
        } else {
            // 原有的单笔模式解析数据
            val type = result.optInt("type", 0)
            val amt = result.optDouble("amount", 0.0)
            val fee = result.optDouble("fee", 0.0)
            val symbol = when(type) {
                1 -> "+"
                2 -> "⇄"
                3 -> "💸" // 还款标志
                else -> "-"
            }

            tvResMoney.text = if (type == 1) "+$amt" else if (type == 2 || type == 3) "$amt" else "-$amt"
            tvResMoney.setTextColor(android.graphics.Color.parseColor(if (type == 1) "#E91E63" else "#2E7D32"))
            
            val timeStr = result.optString("time", "")
            if (timeStr.isNotEmpty()) {
                tvResTime.text = "时间: $timeStr"
                tvResTime.visibility = View.VISIBLE
            } else {
                tvResTime.text = "时间: 现在"
            }

            when (type) {
                2 -> { // 转账
                    tvResCate.text = "转入: ${result.optString("to_asset_name", "--")}"
                    tvResAsset.text = "转出: ${result.optString("asset_name", "--")}"
                }
                3 -> { // 还款
                    tvResCate.text = "还款给: ${result.optString("to_asset_name", "--")}"
                    tvResAsset.text = "支付方: ${result.optString("asset_name", "--")}"
                }
                else -> { // 支出、收入
                    val cat = result.optString("category_name", "--")
                    tvResCate.text = "分类: ${cat.replace("/:::/", " > ")}"
                    val assetName = result.optString("asset_name", "")
                    tvResAsset.text = "账户: ${if (assetName.isEmpty()) "未识别" else assetName}"
                }
            }
        }

        // 切换视图
        layoutLoading.visibility = View.GONE
        layoutResult.visibility = View.VISIBLE
        dialog.setCancelable(true)
        btnClose.visibility = View.VISIBLE

        btnConfirm.setOnClickListener {
            dismiss()
            onResult(result)
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

    /**
     * 图片分析入口（ML Kit 本地 OCR 模式）：
     *   1. 先跑 OCR，把识别文字填入输入框供用户预览
     *   2. 用户确认后点"识别"按钮，再调 AIService.analyzeReceiptByOcrText 分析
     *
     * 多模态模式（直接发图）直接走原有流程不做预览中间步骤。
     */
    fun analyzeImage(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        val ocrMode = Prefs.getOcrMode(ctx)

        // 图片模式固定使用多账单模式，避免摘要文字无法正确填写
        Prefs.setMultiBillEnabled(ctx, true)

        if (ocrMode == Prefs.OCR_MODE_MULTIMODAL) {
            // 多模态模式：直接发图，走旧的 loading 流程
            analyzeImageMultimodal(imageUri, onResult)
            return
        }

        // ---- 本地 OCR 模式：先识别文字，显示到输入框让用户确认 ----
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_dialog_ai_input, null)
        val dialog = AlertDialog.Builder(themeContext)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.apply {
            setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            attributes.y = 300
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

        currentDialog = dialog
        dialog.show()
        dialog.window?.let { win ->
            val dm = ctx.resources.displayMetrics
            win.setLayout((340 * dm.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        // 绑定内部麦克风按钮（与 showInputPanel 保持一致）
        val btnDialogVoice = view.findViewById<View>(R.id.btn_dialog_voice)
        if (btnDialogVoice != null) {
            voiceInputBtnSetup?.invoke(btnDialogVoice)
        }

        // 阶段一：展示 Loading，跑 OCR
        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = "正在识别图片文字..."
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))

        // 禁用 EditText 浮动菜单（Service 环境）
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        if (ctx !is Activity && etInput != null) {
            val blankCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
            etInput.customInsertionActionModeCallback = blankCallback
            etInput.customSelectionActionModeCallback = blankCallback
        }

        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // OCR + AI 摘要（直接调用 analyzeImage 它内部会自动串联）
                val summary = ReceiptOcrHelper.analyzeImage(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }

                // 阶段二：解析完成，自动切换回输入框并填入摘要
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

    /**
     * 图片分析（多模态直接发图模式）- 直接返回自然语言摘要，再调用多账单
     */
    private fun analyzeImageMultimodal(imageUri: Uri, onResult: (JSONObject) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_dialog_ai_input, null)
        val dialog = AlertDialog.Builder(themeContext)
            .setView(view)
            .setCancelable(false)
            .create()

        dialog.window?.apply {
            setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM)
            attributes.y = 300
        }

        currentDialog = dialog
        dialog.show()
        dialog.window?.let { win ->
             val dm = ctx.resources.displayMetrics
             win.setLayout((340 * dm.density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        }

        tvThinkingLog = view.findViewById(R.id.tv_thinking_log)
        tvRecordedTextPreview = view.findViewById(R.id.tv_recorded_text_preview)

        // 绑定内部麦克风按钮（与 showInputPanel 保持一致）
        val btnDialogVoiceM = view.findViewById<View>(R.id.btn_dialog_voice)
        if (btnDialogVoiceM != null) {
            voiceInputBtnSetup?.invoke(btnDialogVoiceM)
        }

        view.findViewById<View>(R.id.layout_input)?.visibility = View.GONE
        view.findViewById<View>(R.id.layout_loading)?.visibility = View.VISIBLE
        view.findViewById<View>(R.id.layout_result)?.visibility = View.GONE
        view.findViewById<View>(R.id.btn_close)?.visibility = View.GONE
        tvRecordedTextPreview?.visibility = View.GONE
        tvThinkingLog?.text = "正在压缩图片，发送给 AI..."
        tvThinkingLog?.setTextColor(android.graphics.Color.parseColor("#7B61FF"))

        // 禁用 EditText 浮动菜单（Service 环境）
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        if (ctx !is Activity && etInput != null) {
            val blankCallback = object : android.view.ActionMode.Callback {
                override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
                override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
                override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
            }
            etInput.customInsertionActionModeCallback = blankCallback
            etInput.customSelectionActionModeCallback = blankCallback
        }

        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // ReceiptOcrHelper.analyzeImage 现在返回 String (自然语言摘要)
                val summary = ReceiptOcrHelper.analyzeImage(ctx, imageUri) { progressMsg ->
                    Handler(Looper.getMainLooper()).post {
                        tvThinkingLog?.text = progressMsg
                    }
                }
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) { dismiss(); Utils.toast(ctx, "已取消") }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    dismiss()
                    Utils.toast(ctx, "图片分析失败: ${e.message ?: "未知错误"}")
                }
            }
        }

        view.findViewById<View>(R.id.btn_close)?.setOnClickListener {
            analyzeJob?.cancel()
            dismiss()
        }
    }

    /**
     * 小票 AI 解析（使用小票专用提示词，返回自然语言摘要）
     * 解析完成后，将摘要文本填回输入框，供用户二次确认，然后转为多账单记账。
     */
    private fun startReceiptAnalysis(ocrText: String, onResult: (JSONObject) -> Unit) {
        analyzeJob?.cancel()
        analyzeJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // AIService.analyzeReceiptByOcrText 现在返回 String (自然语言摘要)
                val summary = AIService.analyzeReceiptByOcrText(ctx, ocrText)
                
                withContext(Dispatchers.Main) {
                    showReceiptSummary(summary, onResult)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(Dispatchers.Main) { dismiss(); Utils.toast(ctx, "已取消") }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, "小票解析失败: ${e.message ?: "未知异常"}")
                    // 失败后回到输入框，保留文字方便重试
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

    /**
     * 显示小票解析后的自然语言摘要，并提供生成最终账单的入口
     */
    private fun showReceiptSummary(summary: String, onResult: (JSONObject) -> Unit) {
        val dialog = currentDialog ?: return
        val view = dialog.findViewById<View>(android.R.id.content) ?: return

        val layoutInput = view.findViewById<View>(R.id.layout_input)
        val layoutLoading = view.findViewById<View>(R.id.layout_loading)
        val btnClose = view.findViewById<View>(R.id.btn_close)
        val etInput = view.findViewById<EditText>(R.id.et_ai_input)
        val btnIdentify = view.findViewById<android.widget.TextView>(R.id.btn_dialog_identify)

        layoutLoading.visibility = View.GONE
        layoutInput.visibility = View.VISIBLE
        btnClose.visibility = View.VISIBLE
        
        dialog.setCancelable(true)

        // 填入摘要
        etInput.setText(summary)
        etInput.setSelection(summary.length)
        etInput.hint = "请核对小票详情，可补充时间、资产等信息"

        // 修改按钮功能为“生成账单”
        btnIdentify.text = "生成账单"
        btnIdentify.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isNotEmpty()) {
                updatePanelState(MODE_LOADING, "正在生成结构化账单...")
                // 使用多账单模式进行最终解析
                startAnalysis(text, true, onResult)
            } else {
                Utils.toast(ctx, "请输入内容")
            }
        }
    }
}