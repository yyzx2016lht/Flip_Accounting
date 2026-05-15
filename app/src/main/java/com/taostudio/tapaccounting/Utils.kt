package com.taostudio.tapaccounting

import android.content.Context
import android.widget.Toast
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

import kotlinx.coroutines.*

object Utils {

    private var currentOverlayToastJob: kotlinx.coroutines.Job? = null
    private var currentOverlayView: android.view.View? = null

    fun toast(ctx: Context, msg: String) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post { toast(ctx, msg) }
            return
        }
        
        try {
            val t = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT)
            @Suppress("DEPRECATION")
            var v = t.view
            
            if (v == null) {
                try {
                    val resId = android.content.res.Resources.getSystem().getIdentifier("transient_notification", "layout", "android")
                    if (resId != 0) {
                        v = android.view.LayoutInflater.from(ctx).inflate(resId, null)
                        val tv = v?.findViewById<android.widget.TextView>(android.R.id.message)
                        tv?.text = msg
                    }
                } catch (e: Exception) {}
            }
            
            if (v == null) {
                // 如果实在取不到系统的 View，还是回退到一个尽量美观的默认样式
                v = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(android.graphics.Color.parseColor("#E6333333"))
                        cornerRadius = 24 * resources.displayMetrics.density
                    }
                    val padH = (20 * resources.displayMetrics.density).toInt()
                    val padV = (12 * resources.displayMetrics.density).toInt()
                    setPadding(padH, padV, padH, padV)
                    
                    addView(android.widget.TextView(ctx).apply {
                        text = msg
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 14f
                    })
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && android.provider.Settings.canDrawOverlays(ctx)) {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                
                currentOverlayView?.let { 
                    try { wm.removeView(it) } catch (e: Exception) {} 
                }
                currentOverlayToastJob?.cancel()

                val yOffset = try { t.yOffset.takeIf { it != 0 } ?: (64 * ctx.resources.displayMetrics.density).toInt() } catch(e: Exception) { (64 * ctx.resources.displayMetrics.density).toInt() }

                val params = android.view.WindowManager.LayoutParams(
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                        android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = try { t.gravity.takeIf { it != 0 } ?: (android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL) } catch(e: Exception) { android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL }
                    y = yOffset
                    windowAnimations = android.R.style.Animation_Toast
                }

                try {
                    wm.addView(v, params)
                    currentOverlayView = v
                    
                    currentOverlayToastJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(2000)
                        if (currentOverlayView == v) {
                            try { wm.removeView(v) } catch (e: Exception) {}
                            currentOverlayView = null
                        }
                    }
                    return
                } catch (e: Exception) {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 构建钱迹自动化记账 URL
     * 支持手续费 fee 参数
     */
    fun buildQianjiUrl(
        type: String,
        money: String,
        time: String?,
        remark: String?,
        catename: String?,
        accountname: String?,
        accountname2: String? = null,
        bookname: String? = " ",
        currency: String? = "CNY",
        fee: String? = "0",
        showresult: String? = "0"
    ): String {
        val base = StringBuilder("qianji://publicapi/addbill?")

        fun appendKV(k: String, v: String?) {
            if (v.isNullOrBlank()) return
            // 钱迹要求空格编码为 %20 而不是 +，这里手动处理一下
            val enc = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
            if (base.last() == '?') base.append("$k=$enc") else base.append("&$k=$enc")
        }

        // 1. 基础参数
        appendKV("type", type)
        appendKV("money", money)

        // 2. 币种 (固定CNY)
        val upCurrency = if (currency.isNullOrBlank()) "CNY" else currency.uppercase()
        appendKV("currency", upCurrency)

        // 3. 手续费 (转账/还款有效，且必须 <= money)
        if ((type == "2" || type == "3") && !fee.isNullOrEmpty() && fee != "0") {
            appendKV("fee", fee)
        }

        // 4. 时间
        val finalTime = time ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        appendKV("time", finalTime)

        // 5. 分类与资产
        appendKV("catename", catename)
        appendKV("accountname", accountname)
        appendKV("accountname2", accountname2)

        appendKV("bookname", bookname)
        appendKV("remark", remark)
        appendKV("showresult", showresult)

        return base.toString()
    }

    fun getCategoryIcon(name: String): Int {
        return when {
            name.contains("餐饮") || name.contains("吃") -> android.R.drawable.ic_menu_today
            name.contains("交通") || name.contains("车") -> android.R.drawable.ic_menu_directions
            name.contains("购物") || name.contains("买") -> android.R.drawable.ic_menu_view
            name.contains("娱乐") || name.contains("玩") -> android.R.drawable.ic_menu_slideshow
            name.contains("医疗") -> android.R.drawable.ic_menu_mylocation
            name.contains("学习") -> android.R.drawable.ic_menu_edit
            else -> android.R.drawable.ic_menu_help
        }
    }

    /**
     * 系统震动反馈。返回 false 时调用方可以记录具体业务分支，避免“识别了但没震”的黑盒体验。
     */
    fun vibrate(
        ctx: Context,
        duration: Long = 50,
        reason: String = "general",
        amplitude: Int = 180
    ): Boolean {
        return try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val manager = ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as? android.os.VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            if (vibrator == null) {
                Logger.d(ctx, "Utils", "Vibration skipped: vibrator service missing. reason=$reason")
                return false
            }
            if (!vibrator.hasVibrator()) {
                Logger.d(ctx, "Utils", "Vibration skipped: device has no vibrator. reason=$reason")
                return false
            }

            val safeDuration = duration.coerceIn(10L, 120L)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val safeAmplitude = amplitude.coerceIn(1, 255)
                val effect = android.os.VibrationEffect.createOneShot(safeDuration, safeAmplitude)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val attrs = android.os.VibrationAttributes.Builder()
                        .setUsage(android.os.VibrationAttributes.USAGE_TOUCH)
                        .build()
                    vibrator.vibrate(effect, attrs)
                } else {
                    vibrator.vibrate(effect)
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(safeDuration)
            }
            true
        } catch (se: SecurityException) {
            Logger.d(ctx, "Utils", "Vibration skipped: missing permission. reason=$reason, error=${se.message}")
            false
        } catch (e: Exception) {
            Logger.d(ctx, "Utils", "Vibration failed. reason=$reason, error=${e.message}")
            false
        }
    }
}

/**
 * 让备注类 EditText 在回车时收起输入法，而不是插入换行符。
 * 三重保险：EditorAction / KeyEvent / TextWatcher，兼容国内各输入法。
 */
fun android.widget.EditText.dismissKeyboardOnEnter() {
    // 1. 标准 IME_ACTION_DONE（原生键盘、Google 输入法）
    setOnEditorActionListener { v, actionId, _ ->
        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
            actionId == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT ||
            actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
            val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        } else false
    }
    // 2. KeyEvent 拦截（搜狗、百度等输入法直接发 KEYCODE_ENTER）
    setOnKeyListener { v, keyCode, event ->
        if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
            event.action == android.view.KeyEvent.ACTION_DOWN) {
            val imm = v.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        } else false
    }
    // 3. TextWatcher 兜底：过滤掉任何已插入的换行符
    addTextChangedListener(object : android.text.TextWatcher {
        private var isFiltering = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            if (isFiltering || s == null) return
            val raw = s.toString()
            if ('\n' in raw || '\r' in raw) {
                isFiltering = true
                val cleaned = raw.replace("\r\n", "").replace("\n", "").replace("\r", "")
                s.replace(0, s.length, cleaned)
                isFiltering = false
                // 收起输入法
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
            }
        }
    })
}

