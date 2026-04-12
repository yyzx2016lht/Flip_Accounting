package tao.test.flipaccounting

import android.content.Context
import android.widget.Toast
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

object Utils {

    fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
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
     * 系统震动反馈
     */
    fun vibrate(ctx: Context, duration: Long = 50) {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(android.os.VibrationEffect.createOneShot(duration, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(duration)
        }
    }
}