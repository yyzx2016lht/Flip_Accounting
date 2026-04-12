package tao.test.flipaccounting

import java.util.Currency
import java.util.Locale

data class CurrencyInfo(
    val code: String,          // e.g. "USD"
    val nameZh: String,        // e.g. "美元"
    val countryZh: String,     // e.g. "美国"
    val flagEmoji: String,     // e.g. "🇺🇸"
    val symbol: String         // e.g. "$"
) {
    fun getDisplayName(): String {
        return "$flagEmoji $code $nameZh ($countryZh)"
    }

    fun getShortName(): String {
        return "$flagEmoji $code"
    }
    
    // Helper search
    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        return code.lowercase().contains(q) || 
               nameZh.lowercase().contains(q) || 
               countryZh.lowercase().contains(q)
    }
}

object CurrencyData {
    // A comprehensive list of common currencies with metadata
    val ALL_CURRENCIES = listOf(
        CurrencyInfo("CNY", "人民币", "中国", "🇨🇳", "¥"),
        CurrencyInfo("USD", "美元", "美国", "🇺🇸", "$"),
        CurrencyInfo("EUR", "欧元", "欧盟", "🇪🇺", "€"),
        CurrencyInfo("JPY", "日元", "日本", "🇯🇵", "¥"),
        CurrencyInfo("GBP", "英镑", "英国", "🇬🇧", "£"),
        CurrencyInfo("AUD", "澳元", "澳大利亚", "🇦🇺", "$"),
        CurrencyInfo("CAD", "加元", "加拿大", "🇨🇦", "$"),
        CurrencyInfo("HKD", "港币", "中国香港", "🇭🇰", "$"),
        CurrencyInfo("MOP", "澳门元", "中国澳门", "🇲🇴", "MOP$"),
        CurrencyInfo("TWD", "新台币", "中国台湾", "🇹🇼", "NT$"),
        CurrencyInfo("KRW", "韩元", "韩国", "🇰🇷", "₩"),
        CurrencyInfo("SGD", "新元", "新加坡", "🇸🇬", "$"),
        CurrencyInfo("MYR", "林吉特", "马来西亚", "🇲🇾", "RM"),
        CurrencyInfo("THB", "泰铢", "泰国", "🇹🇭", "฿"),
        CurrencyInfo("IDR", "印尼盾", "印尼", "🇮🇩", "Rp"),
        CurrencyInfo("VND", "越南盾", "越南", "🇻🇳", "₫"),
        CurrencyInfo("PHP", "比索", "菲律宾", "🇵🇭", "₱"),
        CurrencyInfo("INR", "卢比", "印度", "🇮🇳", "₹"),
        CurrencyInfo("RUB", "卢布", "俄罗斯", "🇷🇺", "₽"),
        CurrencyInfo("PLN", "兹罗提", "波兰", "🇵🇱", "zł"),
        CurrencyInfo("CHF", "法郎", "瑞士", "🇨🇭", "Fr"),
        CurrencyInfo("SEK", "克朗", "瑞典", "🇸🇪", "kr"),
        CurrencyInfo("NOK", "克朗", "挪威", "🇳🇴", "kr"),
        CurrencyInfo("DKK", "克朗", "丹麦", "🇩🇰", "kr"),
        CurrencyInfo("NZD", "纽元", "新西兰", "🇳🇿", "$"),
        CurrencyInfo("MXN", "比索", "墨西哥", "🇲🇽", "$"),
        CurrencyInfo("BRL", "雷亚尔", "巴西", "🇧🇷", "R$"),
        CurrencyInfo("ZAR", "兰特", "南非", "🇿🇦", "R"),
        CurrencyInfo("TRY", "里拉", "土耳其", "🇹🇷", "₺"),
        CurrencyInfo("AED", "迪拉姆", "阿联酋", "🇦🇪", "dh"),
        CurrencyInfo("SAR", "里亚尔", "沙特", "🇸🇦", "﷼")
    )

    fun getInfo(code: String): CurrencyInfo? {
        return ALL_CURRENCIES.find { it.code == code }
    }
}
