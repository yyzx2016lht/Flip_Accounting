package com.taostudio.tapaccounting

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewPropertyAnimator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.AccountingFormController
import com.taostudio.tapaccounting.logic.VoiceInputHandler
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayManager(private val ctx: Context) {
    companion object {
        private const val OVERLAY_FINAL_Y_DP = 48f
        private const val OVERLAY_ENTER_DURATION_MS = 285L
        private const val OVERLAY_EXIT_DURATION_MS = 190L
        private const val OVERLAY_VISIBILITY_DURATION_MS = 170L
        private const val OVERLAY_ENTER_TRANSLATION_DP = 46f
        private const val OVERLAY_EXIT_TRANSLATION_DP = 30f
        private const val OVERLAY_RESTORE_TRANSLATION_DP = 14f
        private const val OVERLAY_ENTER_SCALE = 0.955f
        private const val OVERLAY_EXIT_SCALE = 0.965f
        private const val OVERLAY_HIDDEN_SCALE = 0.985f
        private const val CAPTURE_CARD_ENTER_DURATION_MS = 260L
        private const val CAPTURE_CARD_EXIT_DURATION_MS = 150L
    }

    private val overlayEnterInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val overlayExitInterpolator = PathInterpolator(0.4f, 0f, 1f, 1f)
    private val overlaySoftInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var isRemovingOverlay = false
    private var currentAnimator: Animator? = null
    private var currentViewAnimator: ViewPropertyAnimator? = null

    private fun navigationBarHeight(): Int {
        val res = ctx.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
    }

    private fun overlayFinalY(): Int {
        return (OVERLAY_FINAL_Y_DP * ctx.resources.displayMetrics.density).toInt() + navigationBarHeight()
    }

    private var captureLoadingView: View? = null
    private var captureLoadingParams: WindowManager.LayoutParams? = null
    private var captureScanAnimator: ValueAnimator? = null
    private var captureCardEnterAnimator: AnimatorSet? = null
    private var isScreenCaptureInProgress = false
    private var screenCaptureTriggerBtn: View? = null
    private var screenCaptureJob: Job? = null

    private val pendingBills = mutableListOf<JSONObject>()

    private val aiAssistant = AiAssistant(ctx)
    private var formController: AccountingFormController? = null
    private var voiceHandler: VoiceInputHandler? = null

    fun isShowing(): Boolean = overlayView != null

    fun handleExternalAiResult(result: JSONObject, showSaveOnly: Boolean = false) {
        handleAiResult(result)
    }

    fun showAiInputPanel(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Utils.toast(ctx, "无法唤出 AI 输入框：请先开启悬浮窗权限")
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
            return false
        }
        val tapVoiceHandler = VoiceInputHandler(
            ctx, aiAssistant, handleAiResult,
            onBeforeRecording = { (ctx as? OverlayService)?.enterMicrophoneMode() ?: true },
            onAfterRecording = { (ctx as? OverlayService)?.exitMicrophoneMode() }
        )
        aiAssistant.voiceInputBtnSetup = { btn ->
            tapVoiceHandler.setupVoiceButton(btn)
        }
        aiAssistant.showInputPanel(
            hideStreamText = true,
            onResult = handleAiResult
        )
        return true
    }

    fun startScreenCaptureFromTap() {
        startScreenCaptureRecognition()
    }

    private val handleAiResult: (JSONObject) -> Unit = { resultJson ->
        val sourceKind = resultJson.optString("source_kind", "")
        val isVisualReviewDraft = sourceKind == "screen_capture" || sourceKind == "receipt_image"
        val requiresReview = resultJson.optBoolean("requires_review", false) || isVisualReviewDraft
        if (resultJson.has("bills")) {
            val billsArray = resultJson.getJSONArray("bills")
            if (Prefs.isMultiBillNotSync(ctx) && !requiresReview) {
                for (i in 0 until billsArray.length()) {
                    saveJsonToLocal(billsArray.getJSONObject(i))
                }
                Utils.toast(ctx, "已识别 ${billsArray.length()} 条账单并保存至列表")
                if (overlayView != null) removeOverlay()
            } else {
                if (overlayView != null && formController != null) {
                    formController?.fillDataToUi(resultJson, showToast = true, forceMultiMode = true)
                } else {
                    showOverlay(resultJson, showSaveOnly = true)
                }
            }
        } else {
            if (overlayView != null && formController != null) {
                formController?.fillDataToUi(resultJson, showToast = true)
                formController?.setCurrency(resultJson.optString("currency", "CNY"))
            } else {
                showOverlay(resultJson, showSaveOnly = false)
            }
        }
    }

    fun showOverlay(prefill: JSONObject? = null, showSaveOnly: Boolean = false): Boolean {
        if (overlayView != null) return true
        if (isRemovingOverlay) {
            Utils.toast(ctx, "悬浮窗正在关闭，请稍后再试")
            return false
        }
        Logger.d(ctx, "OverlayManager", "Showing Overlay")

        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Utils.toast(ctx, "无法唤出悬浮窗：请先开启悬浮窗权限")
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${ctx.packageName}")
            )
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
            return false
        }

        windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (windowManager == null) {
            Logger.d(ctx, "OverlayManager", "showOverlay failed: WindowManager unavailable")
            Utils.toast(ctx, "无法唤出悬浮窗：系统窗口服务不可用")
            return false
        }
        overlayView = LayoutInflater.from(themeContext).inflate(R.layout.layout_floating_window, null)
        overlayView?.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = ctx.resources.displayMetrics.density * 16
        }

        overlayView?.findViewById<View>(R.id.layout_bill_mode_switch)?.visibility = View.GONE
        overlayView?.findViewById<RadioGroup>(R.id.rg_bill_mode)?.visibility = View.GONE

        overlayParams = WindowManager.LayoutParams().apply {
            width = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = overlayFinalY()
            windowAnimations = 0
        }

        setupLogic(overlayView!!, prefill, showSaveOnly)

        val view = overlayView!!
        val enterOffsetPx = OVERLAY_ENTER_TRANSLATION_DP * ctx.resources.displayMetrics.density

        // addView 前设置初始态，避免首帧闪烁
        overlayParams!!.y = overlayFinalY()
        view.translationY = enterOffsetPx
        view.alpha = 0f
        view.scaleX = OVERLAY_ENTER_SCALE
        view.scaleY = OVERLAY_ENTER_SCALE

        try {
            windowManager?.addView(view, overlayParams)
        } catch (e: Exception) {
            Logger.d(ctx, "OverlayManager", "showOverlay addView failed: ${e.message}")
            overlayView = null
            overlayParams = null
            Utils.toast(ctx, "无法显示悬浮窗，请检查悬浮窗权限")
            return false
        }

        startOverlayEnterAnimation(view)

        overlayView?.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_OUTSIDE) {
                removeOverlay(isSaved = false)
                true
            } else {
                false
            }
        }
        return true
    }

    private fun setupLogic(view: View, prefill: JSONObject?, showSaveOnly: Boolean) {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                if (formController?.handleBackPressed() == true) {
                    true
                } else {
                    removeOverlay(isSaved = false)
                    true
                }
            } else {
                false
            }
        }
        view.requestFocus()

        formController = AccountingFormController(
            ctx = ctx,
            rootView = view,
            onCloseRequest = { isSaved -> removeOverlay(isSaved) }
        )

        if (prefill != null) {
            val forceMulti = prefill.has("bills")
            formController?.fillDataToUi(prefill, showToast = false, forceMultiMode = forceMulti)
        }

        voiceHandler = VoiceInputHandler(
            ctx, aiAssistant, handleAiResult,
            onBeforeRecording = { (ctx as? OverlayService)?.enterMicrophoneMode() ?: true },
            onAfterRecording = { (ctx as? OverlayService)?.exitMicrophoneMode() }
        )
        aiAssistant.voiceInputBtnSetup = { btn ->
            voiceHandler?.setupVoiceButton(btn)
        }
        voiceHandler?.setupVoiceButton(formController!!.btnVoice)

        val btnAiScreenshot = view.findViewById<android.widget.ImageView>(R.id.btn_ai_screenshot)
        val btnAiImage = view.findViewById<android.widget.ImageView>(R.id.btn_ai_image)
        val hasAccessibility = KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val hasShizuku = Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)
        val canUseScreenAccounting = Prefs.isShowScreenAccounting(ctx) && (hasAccessibility || hasShizuku)
        btnAiScreenshot?.visibility = if (canUseScreenAccounting) View.VISIBLE else View.GONE
        btnAiScreenshot?.setOnClickListener { btn ->
            // 按钮弹跳反馈
            btn.animate().scaleX(0.72f).scaleY(0.72f).setDuration(90L).withEndAction {
                btn.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }.start()
            startScreenCaptureRecognition(btn)
        }

        if (Prefs.isShowAiImage(ctx)) {
            btnAiImage?.visibility = View.VISIBLE
            btnAiImage?.setOnClickListener {
                overlayView?.visibility = View.INVISIBLE
                ImagePickerActivity.onImagePicked = { uri ->
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                    overlayView?.visibility = View.VISIBLE
                    aiAssistant.analyzeImage(uri, handleAiResult)
                }
                ImagePickerActivity.onPickCancelled = {
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                    overlayView?.visibility = View.VISIBLE
                }
                val intent = android.content.Intent(ctx, ImagePickerActivity::class.java).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                ctx.startActivity(intent)
            }
        } else {
            btnAiImage?.visibility = View.GONE
        }

        formController!!.layoutAiTextEntry.setOnClickListener {
            aiAssistant.showInputPanel(
                isMultiMode = true,
                hideStreamText = true,
                onResult = handleAiResult
            )
        }
    }

    private fun startScreenCaptureRecognition(triggerBtn: View? = null) {
        if (isScreenCaptureInProgress) {
            Utils.toast(ctx, "正在识别屏幕，请稍候...")
            return
        }

        isScreenCaptureInProgress = true
        screenCaptureTriggerBtn = triggerBtn
        triggerBtn?.isEnabled = false
        triggerBtn?.animate()?.alpha(0.45f)?.setDuration(120L)?.start()

        val screenModel = Prefs.getAiReceiptVisionModel(ctx).trim()
        Logger.d(ctx, "OverlayManager", "Screen capture recognition clicked. model=$screenModel, isMulti=true")
        if (screenModel.isEmpty()) {
            Logger.d(ctx, "OverlayManager", "Screen capture aborted: no vision model configured")
            Utils.toast(ctx, "请先在智能配置中选择视觉重试模型")
            finishScreenCaptureFlow(restoreOverlay = true)
            return
        }
        val availableModels = Prefs.getAiModelsCache(ctx)
        if (availableModels.isNotEmpty() && screenModel !in availableModels) {
            Logger.d(ctx, "OverlayManager", "Screen capture aborted: model=$screenModel missing from current cache")
            Prefs.setScreenModelVisionSupported(ctx, screenModel, false)
            Utils.toast(ctx, "当前视觉重试模型已失效，请先刷新模型并重新选择")
            finishScreenCaptureFlow(restoreOverlay = true)
            return
        }

        val isProbeCachedSuccess = Prefs.isScreenModelVisionSupported(ctx, screenModel)
        if (isProbeCachedSuccess) {
            Logger.d(ctx, "OverlayManager", "Using cached vision probe support. model=$screenModel")
        }

        showScreenCaptureLoadingOverlay("正在准备截屏识别...")
        updateScreenCaptureLoadingHint("正在检测模型能力")

        setOverlayVisible(false)

        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            val support = if (isProbeCachedSuccess) {
                true
            } else {
                runCatching { AIService.probeVisionInputSupport(ctx, screenModel) }
                    .onSuccess { ok ->
                        Prefs.setScreenModelVisionSupported(ctx, screenModel, ok)
                    }
                    .getOrElse {
                        Prefs.setScreenModelVisionSupported(ctx, screenModel, false)
                        false
                    }
            }
            withContext(Dispatchers.Main) {
                Logger.d(ctx, "OverlayManager", "Screen capture vision support probe result: model=$screenModel, support=$support")
                if (!support) {
                    hideScreenCaptureLoadingOverlay()
                    setOverlayVisible(true)
                    Utils.toast(ctx, "当前模型不支持截屏识别，请更换支持视觉输入的模型")
                    finishScreenCaptureFlow(restoreOverlay = false)
                    return@withContext
                }
                if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                    Logger.d(ctx, "OverlayManager", "Using in-place Shizuku screencap flow")
                    startShizukuScreenCaptureRecognition()
                    return@withContext
                }
                if (KeepAliveAccessibilityService.isServiceEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Logger.d(ctx, "OverlayManager", "Using accessibility screencap flow")
                    startAccessibilityScreenCaptureRecognition()
                    return@withContext
                }
                launchSystemScreenCaptureActivity()
            }
        }
    }

    private fun launchSystemScreenCaptureActivity() {
        ScreenCaptureActivity.onRecognitionResult = { result -> handleScreenCaptureResult(result) }
        ScreenCaptureActivity.onRecognitionError = { message -> handleScreenCaptureError(message) }
        ScreenCaptureActivity.onRecognitionCancelled = { handleScreenCaptureCancelled() }
        val intent = android.content.Intent(ctx, ScreenCaptureActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ScreenCaptureActivity.EXTRA_IS_MULTI_MODE, true)
        }
        Logger.d(ctx, "OverlayManager", "Launching ScreenCaptureActivity")
        updateScreenCaptureLoadingStatus("正在请求截屏权限...")
        updateScreenCaptureLoadingHint("请在系统弹窗中确认")
        runCatching { ctx.startActivity(intent) }
            .onFailure {
                Logger.d(ctx, "OverlayManager", "Launching ScreenCaptureActivity failed: ${it.message}")
                hideScreenCaptureLoadingOverlay()
                setOverlayVisible(true)
                Utils.toast(ctx, "无法启动截屏，请稍后重试")
                finishScreenCaptureFlow(restoreOverlay = false)
            }
    }

    private fun startShizukuScreenCaptureRecognition() {
        updateScreenCaptureLoadingStatus("正在通过 Shizuku 截屏...")
        updateScreenCaptureLoadingHint("请保持当前页面不动")
        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = ShizukuShell.execBytes("screencap -p")
                if (bytes == null || bytes.isEmpty()) {
                    Logger.d(ctx, "OverlayManager", "Shizuku screencap returned empty bytes")
                    withContext(Dispatchers.Main) {
                        Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture")
                        launchSystemScreenCaptureActivity()
                    }
                    return@launch
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Logger.d(ctx, "OverlayManager", "Shizuku screencap decode returned null")
                    withContext(Dispatchers.Main) {
                        Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture after decode failure")
                        launchSystemScreenCaptureActivity()
                    }
                    return@launch
                }

                Logger.d(ctx, "OverlayManager", "Shizuku screencap succeeded. bytes=${bytes.size}, size=${bitmap.width}x${bitmap.height}")
                withContext(Dispatchers.Main) {
                    updateScreenCaptureLoadingStatus("正在识别屏幕中...")
                    updateScreenCaptureLoadingHint("AI 正在提取交易信息")
                }

                val result = AIService.analyzeScreenAccountingByImage(
                    ctx = ctx,
                    imageBase64 = bitmapToBase64(bitmap),
                    mimeType = "image/jpeg",
                    isMultiModeOverride = true
                ) ?: JSONObject().apply {
                    put("no_bill", true)
                    put("reply", "未识别到可记账内容")
                }

                withContext(Dispatchers.Main) {
                    handleScreenCaptureResult(result)
                }
            } catch (e: Exception) {
                Logger.d(ctx, "OverlayManager", "Shizuku in-place screen recognition failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Logger.d(ctx, "OverlayManager", "Falling back to MediaProjection capture after exception")
                    launchSystemScreenCaptureActivity()
                }
            }
        }
    }

    private fun startAccessibilityScreenCaptureRecognition() {
        updateScreenCaptureLoadingStatus("正在通过无障碍服务截屏...")
        updateScreenCaptureLoadingHint("请保持当前页面不动")
        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                KeepAliveAccessibilityService.takeScreenshotCompat { bitmap ->
                    if (bitmap == null) {
                        Logger.d(ctx, "OverlayManager", "Accessibility screencap returned null")
                        CoroutineScope(Dispatchers.Main).launch {
                            Logger.d(ctx, "OverlayManager", "Falling back to Shizuku or MediaProjection capture")
                            if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                                startShizukuScreenCaptureRecognition()
                            } else {
                                launchSystemScreenCaptureActivity()
                            }
                        }
                        return@takeScreenshotCompat
                    }

                    Logger.d(ctx, "OverlayManager", "Accessibility screencap succeeded. size=${bitmap.width}x${bitmap.height}")
                    CoroutineScope(Dispatchers.Main).launch {
                        updateScreenCaptureLoadingStatus("正在识别屏幕中...")
                        updateScreenCaptureLoadingHint("AI 正在提取交易信息")
                    }

                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val result = AIService.analyzeScreenAccountingByImage(
                                ctx = ctx,
                                imageBase64 = bitmapToBase64(bitmap),
                                mimeType = "image/jpeg",
                                isMultiModeOverride = true
                            ) ?: JSONObject().apply {
                                put("no_bill", true)
                                put("reply", "未识别到可记账内容")
                            }

                            withContext(Dispatchers.Main) {
                                handleScreenCaptureResult(result)
                            }
                        } catch (e: Exception) {
                            Logger.d(ctx, "OverlayManager", "Accessibility screen recognition failed: ${e.message}")
                            withContext(Dispatchers.Main) {
                                handleScreenCaptureError("截屏识别失败，请稍后重试")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.d(ctx, "OverlayManager", "Accessibility screencap failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    Logger.d(ctx, "OverlayManager", "Falling back to Shizuku or MediaProjection capture after exception")
                    if (Prefs.isShizukuModeEnabled(ctx) && ShizukuSafe.isReady(ctx)) {
                        startShizukuScreenCaptureRecognition()
                    } else {
                        launchSystemScreenCaptureActivity()
                    }
                }
            }
        }
    }

    private fun handleScreenCaptureResult(result: JSONObject) {
        Logger.d(
            ctx,
            "OverlayManager",
            "Screen capture recognition result received. no_bill=${result.optBoolean("no_bill", false)}, hasBills=${result.has("bills")}, hasAmount=${result.has("amount")}"
        )
        if (!result.optBoolean("no_bill", false)) {
            AIService.markVisualAccountingReviewDraft(
                root = result,
                sourceKind = "screen_capture",
                includePaymentMethod = Prefs.isAssetFeatureEnabled(ctx)
            )
        }
        val shouldRestoreOverlay = shouldRestoreOverlayForScreenResult(result)
        hideScreenCaptureLoadingOverlay()
        if (shouldRestoreOverlay) {
            setOverlayVisible(true)
            markOverlayRestoredNow()
        }
        finishScreenCaptureFlow(restoreOverlay = false)
        if (result.optBoolean("no_bill", false)) {
            Utils.toast(ctx, result.optString("reply", "未识别到可记账内容"))
        } else {
            showScreenCaptureDraftDialog(result)
        }
    }

    private fun showScreenCaptureDraftDialog(result: JSONObject) {
        val draft = result.optString("natural_summary", "").trim()
            .ifBlank { buildDraftTextFromResult(result) }
        if (draft.isBlank()) {
            Utils.toast(ctx, "未生成可核对的文本草稿")
            return
        }

        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_visual_accounting_draft, null)
        val title = view.findViewById<TextView>(R.id.tv_visual_draft_title)
        val hint = view.findViewById<TextView>(R.id.tv_visual_draft_hint)
        val etDraft = view.findViewById<android.widget.EditText>(R.id.et_visual_draft)
        val btnCancel = view.findViewById<TextView>(R.id.btn_visual_draft_cancel)
        val btnConfirm = view.findViewById<TextView>(R.id.btn_visual_draft_confirm)

        title.text = "核对截屏记账"
        hint.text = "AI 已从截屏整理出可编辑草稿，确认后再生成账单。"
        btnConfirm.text = "生成账单"
        etDraft.setText(draft)
        etDraft.setSelection(draft.length)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(themeContext)
            .setView(view)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
            Utils.toast(ctx, "已取消截屏记账")
        }
        btnConfirm.setOnClickListener {
            val edited = etDraft.text?.toString().orEmpty().trim()
            if (edited.isBlank()) {
                Utils.toast(ctx, "请保留可识别的记账内容")
                return@setOnClickListener
            }
            dialog.dismiss()
            analyzeConfirmedScreenDraft(edited)
        }

        OverlayDialogs.showOverlayCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.92f,
            cancelOnTouchOutside = false,
            useSolidPanelBackground = false
        )
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun buildDraftTextFromResult(result: JSONObject): String {
        val bills = result.optJSONArray("bills") ?: return ""
        val lines = mutableListOf<String>()
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val typeText = when (bill.optInt("type", 0)) {
                Bill.TYPE_INCOME -> "收到"
                Bill.TYPE_TRANSFER -> "转账"
                else -> "支付"
            }
            val remark = bill.optString("remarks", "").ifBlank { bill.optString("remark", "") }
            val amount = bill.optDouble("amount", 0.0)
            val currency = bill.optString("currency", "CNY").ifBlank { "CNY" }
            val time = bill.optString("time", "")
            val asset = bill.optString("asset_name", "")
            val paymentText = if (Prefs.isAssetFeatureEnabled(ctx) && asset.isNotBlank()) "，用了${asset}支付" else ""
            val timeText = if (time.isNotBlank()) "，时间 $time" else ""
            lines += "$typeText ${remark.ifBlank { "待确认事项" }} 花了 $amount $currency$paymentText$timeText"
        }
        return lines.joinToString("\n")
    }

    private fun analyzeConfirmedScreenDraft(confirmedDraft: String) {
        showScreenCaptureLoadingOverlay("正在生成账单...")
        updateScreenCaptureLoadingHint("根据已确认文本提取结构化账单")
        screenCaptureJob?.cancel()
        screenCaptureJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = AIService.analyzeAccounting(
                    ctx = ctx,
                    userInput = confirmedDraft,
                    isMultiModeOverride = true
                ) ?: JSONObject().apply {
                    put("no_bill", true)
                    put("reply", "未识别到可记账内容")
                }
                AIService.markVisualAccountingReviewDraft(
                    root = result,
                    sourceKind = "screen_capture",
                    naturalSummary = confirmedDraft,
                    includePaymentMethod = Prefs.isAssetFeatureEnabled(ctx)
                )
                withContext(Dispatchers.Main) {
                    hideScreenCaptureLoadingOverlay()
                    if (result.optBoolean("no_bill", false)) {
                        Utils.toast(ctx, result.optString("reply", "未识别到可记账内容"))
                    } else {
                        handleAiResult(result)
                    }
                }
            } catch (e: Exception) {
                Logger.d(ctx, "OverlayManager", "Confirmed screen draft analyze failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    hideScreenCaptureLoadingOverlay()
                    Utils.toast(ctx, "生成账单失败，请稍后重试")
                }
            }
        }
    }

    private fun shouldRestoreOverlayForScreenResult(result: JSONObject): Boolean {
        val isMulti = true
        val isAutoSaveMultiBills = isMulti && result.has("bills") && Prefs.isMultiBillNotSync(ctx)
        return result.optBoolean("requires_review", false) || !isAutoSaveMultiBills
    }

    private fun handleScreenCaptureError(message: String) {
        Logger.d(ctx, "OverlayManager", "Screen capture recognition error callback: $message")
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, message.ifBlank { "截屏识别失败，请稍后重试" })
    }

    private fun handleScreenCaptureCancelled() {
        Logger.d(ctx, "OverlayManager", "Screen capture recognition cancelled")
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, "已取消截屏记账")
    }

    private fun markOverlayRestoredNow() {
        // 保留空实现，供调用方兼容
    }

    private fun setOverlayVisible(visible: Boolean) {
        Logger.d(ctx, "OverlayManager", "setOverlayVisible($visible)")
        val view = overlayView ?: return
        cancelOverlayAnimations(view)
        if (visible) {
            overlayParams?.y = overlayFinalY()
            runCatching { overlayParams?.let { windowManager?.updateViewLayout(view, it) } }
            view.visibility = View.VISIBLE
            if (view.alpha >= 0.98f && view.scaleX >= 0.995f) {
                view.alpha = 1f
                view.translationY = 0f
                view.scaleX = 1f
                view.scaleY = 1f
                return
            }
            val restoreOffset = OVERLAY_RESTORE_TRANSLATION_DP * ctx.resources.displayMetrics.density
            view.alpha = 0f
            view.translationY = restoreOffset
            view.scaleX = OVERLAY_HIDDEN_SCALE
            view.scaleY = OVERLAY_HIDDEN_SCALE
            currentViewAnimator = view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(OVERLAY_VISIBILITY_DURATION_MS)
                .setInterpolator(overlaySoftInterpolator)
                .withLayer()
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        currentViewAnimator = null
                        currentAnimator = null
                    }
                })
            currentViewAnimator?.start()
        } else {
            currentViewAnimator = view.animate()
                .alpha(0f)
                .translationY(OVERLAY_RESTORE_TRANSLATION_DP * ctx.resources.displayMetrics.density)
                .scaleX(OVERLAY_HIDDEN_SCALE)
                .scaleY(OVERLAY_HIDDEN_SCALE)
                .setDuration(OVERLAY_VISIBILITY_DURATION_MS)
                .setInterpolator(overlayExitInterpolator)
                .withLayer()
                .setListener(object : AnimatorListenerAdapter() {
                    private var cancelled = false
                    override fun onAnimationCancel(animation: Animator) {
                        cancelled = true
                    }

                    override fun onAnimationEnd(animation: Animator) {
                        currentViewAnimator = null
                        currentAnimator = null
                        if (!cancelled) view.visibility = View.INVISIBLE
                    }
                })
            currentViewAnimator?.start()
        }
    }

    private fun showScreenCaptureLoadingOverlay(status: String) {
        captureLoadingView?.let {
            updateScreenCaptureLoadingStatus(status)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(ctx)) {
            Logger.d(ctx, "OverlayManager", "Skip screen capture loading overlay: missing overlay permission")
            return
        }
        val wm = windowManager ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
        if (wm == null || captureLoadingView != null) return

        val themeContext = android.view.ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val loadingView = LayoutInflater.from(themeContext).inflate(R.layout.activity_screen_capture, null)
        loadingView.findViewById<TextView>(R.id.tv_capture_status)?.text = status
        loadingView.findViewById<TextView>(R.id.tv_capture_hint)?.text = "请保持当前页面不动"

        captureLoadingParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = 0
        }

        captureLoadingView = loadingView
        try {
            wm.addView(loadingView, captureLoadingParams)
        } catch (e: Exception) {
            Logger.d(ctx, "OverlayManager", "showScreenCaptureLoadingOverlay addView failed: ${e.message}")
            captureLoadingView = null
            captureLoadingParams = null
            return
        }

        loadingView.isFocusable = true
        loadingView.isFocusableInTouchMode = true
        loadingView.requestFocus()
        loadingView.setOnTouchListener { _, _ -> true }
        loadingView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                cancelScreenCaptureByUser()
                true
            } else {
                false
            }
        }

        startCaptureLoadingAnimation(loadingView)
    }

    private fun updateScreenCaptureLoadingStatus(status: String) {
        captureLoadingView?.findViewById<TextView>(R.id.tv_capture_status)?.text = status
    }

    private fun updateScreenCaptureLoadingHint(hint: String) {
        captureLoadingView?.findViewById<TextView>(R.id.tv_capture_hint)?.text = hint
    }

    private fun finishScreenCaptureFlow(restoreOverlay: Boolean) {
        if (!isScreenCaptureInProgress) return
        isScreenCaptureInProgress = false
        screenCaptureJob = null
        if (restoreOverlay) {
            setOverlayVisible(true)
        }
        screenCaptureTriggerBtn?.isEnabled = true
        screenCaptureTriggerBtn?.animate()?.alpha(1f)?.setDuration(120L)?.start()
        screenCaptureTriggerBtn = null
        ScreenCaptureActivity.onRecognitionResult = null
        ScreenCaptureActivity.onRecognitionError = null
        ScreenCaptureActivity.onRecognitionCancelled = null
    }

    private fun cancelScreenCaptureByUser() {
        if (!isScreenCaptureInProgress) return
        Logger.d(ctx, "OverlayManager", "Screen capture interrupted by back press")
        screenCaptureJob?.cancel()
        hideScreenCaptureLoadingOverlay()
        setOverlayVisible(true)
        markOverlayRestoredNow()
        finishScreenCaptureFlow(restoreOverlay = false)
        Utils.toast(ctx, "已取消截屏记账")
    }

    private fun hideScreenCaptureLoadingOverlay() {
        stopCaptureLoadingAnimation()
        val view = captureLoadingView ?: return
        val wm = windowManager ?: ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        captureLoadingView = null
        captureLoadingParams = null
        val captureCard = view.findViewById<View>(R.id.layout_capture_card)
        view.animate().cancel()
        captureCard?.animate()?.cancel()
        view.animate()
            .alpha(0f)
            .setDuration(CAPTURE_CARD_EXIT_DURATION_MS)
            .setInterpolator(overlayExitInterpolator)
            .withLayer()
            .withEndAction { runCatching { wm?.removeView(view) } }
            .start()
        captureCard?.animate()
            ?.scaleX(0.985f)
            ?.scaleY(0.985f)
            ?.translationY(8f * ctx.resources.displayMetrics.density)
            ?.setDuration(CAPTURE_CARD_EXIT_DURATION_MS)
            ?.setInterpolator(overlayExitInterpolator)
            ?.start()
    }

    private fun startCaptureLoadingAnimation(root: View) {
        stopCaptureLoadingAnimation()
        val captureCard = root.findViewById<View>(R.id.layout_capture_card) ?: return
        val scanLine   = root.findViewById<View>(R.id.view_scan_line) ?: return

        root.alpha = 0f
        root.animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(overlaySoftInterpolator)
            .withLayer()
            .start()

        captureCard.translationY = 18f * ctx.resources.displayMetrics.density

        val scaleX = ObjectAnimator.ofFloat(captureCard, "scaleX", 0.94f, 1f)
        val scaleY = ObjectAnimator.ofFloat(captureCard, "scaleY", 0.94f, 1f)
        val alpha  = ObjectAnimator.ofFloat(captureCard, "alpha", 0f, 1f)
        val translationY = ObjectAnimator.ofFloat(captureCard, "translationY", captureCard.translationY, 0f)
        captureCardEnterAnimator = AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha, translationY)
            duration = CAPTURE_CARD_ENTER_DURATION_MS
            interpolator = overlaySoftInterpolator
            start()
        }

        // 扫描线：translationY 从 -屏幕高 到 +屏幕高 循环
        val screenH = ctx.resources.displayMetrics.heightPixels.toFloat()
        captureScanAnimator = ValueAnimator.ofFloat(-screenH, screenH).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val progress = it.animatedFraction
                scanLine.translationY = it.animatedValue as Float
                scanLine.alpha = (0.25f + 0.75f * kotlin.math.sin(progress * Math.PI).toFloat())
                    .coerceIn(0.25f, 1f)
            }
            start()
        }
    }

    private fun stopCaptureLoadingAnimation() {
        captureCardEnterAnimator?.cancel()
        captureCardEnterAnimator = null
        captureScanAnimator?.cancel()
        captureScanAnimator = null
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1440
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(
                maxDim.toFloat() / bitmap.width,
                maxDim.toFloat() / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
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
            if (pendingBills.isNotEmpty()) {
                Utils.toast(ctx, "剩余 ${pendingBills.size} 条待记录")
            } else {
                Utils.toast(ctx, "这是最后一条记录")
            }
        } else {
            showOverlay(next, showSaveOnly = false)
        }
    }

    private fun saveJsonToLocal(obj: JSONObject) {
        val typeIndex = obj.optInt("type", 0)
        val amount = obj.optDouble("amount", 0.0)
        val asset1 = obj.optString("asset_name", "")

        var categoryName = com.taostudio.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(obj.optString("category_name", ""))
        var toAssetId: Long? = null
        if (typeIndex == 2 || typeIndex == 3) {
            val asset2 = obj.optString("to_asset_name", "")
            categoryName = if (typeIndex == 2) "转账到$asset2" else "还款到$asset2"
        }

        val timeStr = obj.optString(
            "time",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        )
        val parsedTime = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time
                ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
        val remark = obj.optString("remarks", "")
        val recognizedCurrency = obj.optString("currency", "CNY").ifBlank { "CNY" }
        val fee = obj.optDouble("fee", 0.0).coerceAtLeast(0.0)

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            val asset1Obj = db.assetDao().getAssetByName(asset1)
            var toAssetObj: com.taostudio.tapaccounting.data.local.entity.Asset? = null
            if (typeIndex == 2 || typeIndex == 3) {
                val asset2 = obj.optString("to_asset_name", "")
                toAssetObj = db.assetDao().getAssetByName(asset2)
                toAssetId = toAssetObj?.id
            }
            val effectiveCurrency = if (typeIndex == Bill.TYPE_TRANSFER) {
                asset1Obj?.currency?.takeIf { it.isNotBlank() } ?: recognizedCurrency
            } else {
                recognizedCurrency
            }

            val exchangeRate = when {
                typeIndex == 2 && toAssetObj != null && amount > 0.0 ->
                    com.taostudio.tapaccounting.logic.BillAssetImpactService.estimateExchangeRateToTarget(
                        amount,
                        effectiveCurrency,
                        toAssetObj.currency
                    )
                effectiveCurrency == "CNY" -> 1.0
                else -> com.taostudio.tapaccounting.logic.BillAssetImpactService.estimateExchangeRateToCny(effectiveCurrency)
            }

            val bill = Bill(
                amount = amount,
                type = typeIndex,
                currency = effectiveCurrency,
                exchangeRate = exchangeRate,
                fee = fee,
                accountName = asset1,
                accountId = asset1Obj?.id,
                toAccountId = toAssetId,
                categoryName = categoryName,
                time = parsedTime,
                remark = remark,
                bookName = BookAccountManager.getSelectedBook(ctx)
            )
            com.taostudio.tapaccounting.logic.BillMutationService.insertBillAndApplyImpact(db, bill)
        }
    }

    fun removeOverlay(isSaved: Boolean = true) {
        hideScreenCaptureLoadingOverlay()
        finishScreenCaptureFlow(restoreOverlay = false)
        voiceHandler?.release()
        voiceHandler = null

        if (overlayView == null || isRemovingOverlay) {
            formController = null
            overlayParams = null
            return
        }

        isRemovingOverlay = true
        Logger.d(ctx, "OverlayManager", "Removing Overlay with animation")

        // 立即锁定表单，阻止用户继续交互
        formController = null
        val view = overlayView!!
        cancelOverlayAnimations(view)

        val exitOffset = OVERLAY_EXIT_TRANSLATION_DP * ctx.resources.displayMetrics.density
        // 退场改为“下滑 + 淡出 + 轻缩放”，可感知更强，同时保持稳定
        currentViewAnimator = view.animate()
            .alpha(0f)
            .translationY(exitOffset)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(OVERLAY_EXIT_DURATION_MS)
            .setInterpolator(overlayExitInterpolator)
            .withLayer()
            .setListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }
                override fun onAnimationEnd(animation: Animator) {
                    currentViewAnimator = null
                    currentAnimator = null
                    if (cancelled) {
                        isRemovingOverlay = false
                        return
                    }
                    view.visibility = View.INVISIBLE
                    runCatching { windowManager?.removeView(view) }
                    overlayView = null
                    overlayParams = null
                    isRemovingOverlay = false
                    handlePostRemove(isSaved)
                }
            })
        currentViewAnimator?.start()
    }

    private fun startOverlayEnterAnimation(view: View) {
        cancelOverlayAnimations(view)
        currentViewAnimator = view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(OVERLAY_ENTER_DURATION_MS)
            .setInterpolator(overlayEnterInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                }

                override fun onAnimationEnd(animation: Animator) {
                    view.setLayerType(View.LAYER_TYPE_NONE, null)
                    currentViewAnimator = null
                    currentAnimator = null
                }
            })
        currentViewAnimator?.start()
    }

    private fun cancelOverlayAnimations(view: View) {
        currentAnimator?.cancel()
        currentAnimator = null
        currentViewAnimator?.setListener(null)
        currentViewAnimator?.cancel()
        currentViewAnimator = null
        view.animate().setListener(null)
        view.animate().cancel()
        view.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun handlePostRemove(isSaved: Boolean) {
        if (!isSaved) {
            if (pendingBills.isNotEmpty()) {
                pendingBills.clear()
                Utils.toast(ctx, "多账单识别已取消")
            }
            return
        }
        if (pendingBills.isNotEmpty()) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                processNextPendingBill()
            }, 350)
        }
    }
}

