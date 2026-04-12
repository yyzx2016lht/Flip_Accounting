package tao.test.flipaccounting

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import org.json.JSONObject
import tao.test.flipaccounting.logic.AccountingFormController
import tao.test.flipaccounting.logic.VoiceInputHandler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayManager(private val ctx: Context) {

    // 悬浮窗账单模式，true=多账单，false=单账单
    private var floatingMultiBillMode: Boolean = false

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    
    private val pendingBills = mutableListOf<JSONObject>() // [新增] 待处理账单队列

    private val aiAssistant = AiAssistant(ctx)
    private var formController: AccountingFormController? = null
    private var currentQueueShowSaveOnly = false // 保存当前队列是否展示“仅保存”按钮
    private var voiceHandler: tao.test.flipaccounting.logic.VoiceInputHandler? = null

    fun isShowing(): Boolean = overlayView != null

    /**
     * 外部入口：由首页 AI 识别调用
     */
    fun handleExternalAiResult(result: JSONObject, showSaveOnly: Boolean = false) {
        currentQueueShowSaveOnly = showSaveOnly
        handleAiResult(result)
    }

    private fun applyFloatingMultiBillMode(enabled: Boolean, persistToPrefs: Boolean = false) {
        floatingMultiBillMode = enabled
        currentQueueShowSaveOnly = enabled
        formController?.showSaveOnlyButton(enabled)

        overlayView?.findViewById<android.widget.RadioButton>(R.id.rb_single)?.isChecked = !enabled
        overlayView?.findViewById<android.widget.RadioButton>(R.id.rb_multi)?.isChecked = enabled

        if (persistToPrefs) {
            Prefs.setMultiBillEnabled(ctx, enabled)
        }
    }

    private val handleAiResult: (JSONObject) -> Unit = { resultJson ->
        // 判断悬浮窗当前的账单模式
        val isMulti = if (overlayView != null) floatingMultiBillMode else Prefs.isMultiBillEnabled(ctx)

        if (isMulti && resultJson.has("bills")) {
            val billsArray = resultJson.getJSONArray("bills")
            val isNotSync = Prefs.isMultiBillNotSync(ctx)
            if (isNotSync) {
                for (i in 0 until billsArray.length()) {
                    saveJsonToLocal(billsArray.getJSONObject(i))
                }
                Utils.toast(ctx, "✨ 已识别 ${billsArray.length()} 条账单并保存至列表")
                if (overlayView != null) removeOverlay()
            } else {
                // 开启队列模式
                currentQueueShowSaveOnly = true // 多账单队列模式强制开启“仅保存”按钮
                pendingBills.clear()
                for (i in 0 until billsArray.length()) {
                    pendingBills.add(billsArray.getJSONObject(i))
                }
                processNextPendingBill()
            }
        } else {
            // 单账单模式或未识别到 bills
            if (overlayView != null && formController != null) {
                formController?.fillDataToUi(resultJson, showToast = true)
                formController?.setCurrency(resultJson.optString("currency", "CNY"))
            } else {
                // 如果悬浮窗还没显示，显示它
                showOverlay(resultJson, showSaveOnly = currentQueueShowSaveOnly)
            }
        }
    }

    fun showOverlay(prefill: JSONObject? = null, showSaveOnly: Boolean = false) {
        if (overlayView != null) return
        aiAssistant.dismiss()
        currentQueueShowSaveOnly = showSaveOnly // [新增] 记录当前显示请求是否需要保存按钮
        Logger.d(ctx, "OverlayManager", "Showing Overlay")

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 使用 ContextThemeWrapper 确保 Material 库组件在 Service/WindowManager 中能正确渲染
        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_window, null)

        // 判断首页是否开启多账单
        val multiBillEnabled = Prefs.isMultiBillEnabled(ctx)
        // 获取悬浮窗账单模式控件（inflate后再查找）
        val layoutBillModeSwitch = overlayView?.findViewById<View>(R.id.layout_bill_mode_switch)
        val rgBillMode = overlayView?.findViewById<android.widget.RadioGroup>(R.id.rg_bill_mode)
        val rbSingle = overlayView?.findViewById<android.widget.RadioButton>(R.id.rb_single)
        val rbMulti = overlayView?.findViewById<android.widget.RadioButton>(R.id.rb_multi)
        if (multiBillEnabled && layoutBillModeSwitch != null && rgBillMode != null && rbSingle != null && rbMulti != null) {
            layoutBillModeSwitch.visibility = View.VISIBLE
            // 默认以首页为准
            floatingMultiBillMode = false
            rbSingle.isChecked = true
            rbMulti.isChecked = false
            rgBillMode.setOnCheckedChangeListener { _, checkedId ->
                floatingMultiBillMode = (checkedId == R.id.rb_multi)
                // 实时更新：如果是多账单模式，显示“仅保存”按钮；单账单模式隐藏
                currentQueueShowSaveOnly = floatingMultiBillMode
                formController?.showSaveOnlyButton(floatingMultiBillMode)
            }
            rgBillMode.setOnCheckedChangeListener { _, checkedId ->
                applyFloatingMultiBillMode(checkedId == R.id.rb_multi)
            }
        } else if (layoutBillModeSwitch != null) {
            layoutBillModeSwitch.visibility = View.GONE
            floatingMultiBillMode = false
        }

        val params = WindowManager.LayoutParams().apply {
            width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 150
        }

        setupLogic(overlayView!!, prefill, showSaveOnly)
        windowManager?.addView(overlayView, params)

        // 添加点击外部自动关闭逻辑
        overlayView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                removeOverlay(isSaved = false) // 点击外部视为取消
                true
            } else {
                false
            }
        }
    }

    private fun setupLogic(view: View, prefill: JSONObject?, showSaveOnly: Boolean) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                removeOverlay(isSaved = false) // 返回键也是取消
                true 
            } else {
                false
            }
        }
        view.requestFocus()

        // Initialize Form Controller
        formController = AccountingFormController(ctx, view) { isSaved ->
            removeOverlay(isSaved)
        }

        if (prefill != null) {
            formController?.fillDataToUi(prefill, showToast = false)
            formController?.setCurrency(prefill.optString("currency", "CNY"))
        }

        // 设置是否显示“保存账单”按钮
        formController?.showSaveOnlyButton(showSaveOnly)

        // Initialize Voice Handler
        voiceHandler = tao.test.flipaccounting.logic.VoiceInputHandler(ctx, aiAssistant, { floatingMultiBillMode }, handleAiResult)
        aiAssistant.voiceInputBtnSetup = { btn ->
            voiceHandler?.setupVoiceButton(btn)
        }
        voiceHandler?.setupVoiceButton(formController!!.btnVoice)

        // 绑定图片按钮（如果开启了图片功能则显示）
        val btnAiImage = view.findViewById<android.widget.ImageView>(R.id.btn_ai_image)
        if (Prefs.isShowAiImage(ctx)) {
            btnAiImage?.visibility = android.view.View.VISIBLE
            btnAiImage?.setOnClickListener {
                applyFloatingMultiBillMode(enabled = true, persistToPrefs = true)
                // 先隐藏悬浮窗，避免遮挡系统图片选择器
                overlayView?.visibility = android.view.View.INVISIBLE

                // 注册回调后，启动透明 ImagePickerActivity
                ImagePickerActivity.onImagePicked = { uri ->
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                    // 图片选完后恢复悬浮窗可见性
                    overlayView?.visibility = android.view.View.VISIBLE
                    aiAssistant.analyzeImage(uri, handleAiResult)
                }
                ImagePickerActivity.onPickCancelled = {
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                    // 取消选图后也恢复悬浮窗可见性
                    overlayView?.visibility = android.view.View.VISIBLE
                }
                val intent = android.content.Intent(ctx, ImagePickerActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        } else {
            btnAiImage?.visibility = android.view.View.GONE
        }

        // Initialize AI Entry Click (Text input)
        formController!!.layoutAiEntry.setOnClickListener {
            aiAssistant.showInputPanel(
                isMultiMode = floatingMultiBillMode,
                onResult = handleAiResult
            )
        }
    }

    private fun processNextPendingBill() {
        if (pendingBills.isEmpty()) {
            removeOverlay(isSaved = true)
            return
        }
        
        val next = pendingBills.removeAt(0)
        
        if (overlayView != null && formController != null) {
            formController?.fillDataToUi(next, showToast = true)
            formController?.setCurrency(next.optString("currency", "CNY"))
            formController?.showSaveOnlyButton(currentQueueShowSaveOnly) // 确保按钮状态同步
            
            if (pendingBills.isNotEmpty()) {
                Utils.toast(ctx, "剩余 ${pendingBills.size} 条待记录")
            } else {
                Utils.toast(ctx, "这是最后一条记录")
            }
        } else {
            // 如果悬浮窗还没显示（比如从首页点的识别），则显示它
            showOverlay(next, showSaveOnly = currentQueueShowSaveOnly)
        }
    }

    private fun saveJsonToLocal(obj: JSONObject) {
        val typeIndex = obj.optInt("type", 0)
        val amt = obj.optDouble("amount", 0.0)
        val asset1 = obj.optString("asset_name", "")
        
        // 核心修改：如果是转账或还款，解析出 to_asset_name 并拼接分类名
        var cat = obj.optString("category_name", "").replace("/:::/", " > ")
        if (typeIndex == 2 || typeIndex == 3) {
            val asset2 = obj.optString("to_asset_name", "")
            cat = if (typeIndex == 2) "转账到 $asset2" else "还款到 $asset2"
        }
        
        val time = obj.optString("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        val remark = obj.optString("remarks", "")
        
        val resolvedIcon = tao.test.flipaccounting.CategoryIconHelper.findCategoryIcon(ctx, if (typeIndex == 2 || typeIndex == 3) "转账" else cat, typeIndex)
        
        val bill = Bill(
            amt, typeIndex, asset1, cat, time, remark, resolvedIcon,
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
        Prefs.addBill(ctx, bill)
    }

    fun removeOverlay(isSaved: Boolean = true) {
        aiAssistant.dismiss()
        voiceHandler?.release()
        voiceHandler = null
        if (overlayView != null) {
            Logger.d(ctx, "OverlayManager", "Removing Overlay")
            windowManager?.removeView(overlayView)
        }
        overlayView = null
        formController = null
        
        // 如果是取消操作，我们清空队列，终止整个多账单流程
        if (!isSaved) {
            if (pendingBills.isNotEmpty()) {
                pendingBills.clear()
                Utils.toast(ctx, "多账单识别已取消")
            }
            return
        }

        // BUGFIX: 如果待处理队列不为空，且刚才命中了保存/发送操作，自动进入下一条
        if (pendingBills.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                processNextPendingBill() // 这里直接调用，它会内部执行 showOverlay
            }, 350)
        }
    }
}
