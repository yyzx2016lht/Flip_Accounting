package tao.test.tapaccounting

import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class CurrencyInfo(
    val code: String,
    val nameZh: String,
    val countryZh: String,
    val flagEmoji: String,
    val symbol: String
) {
    fun getDisplayName(): String = "$flagEmoji $code $nameZh ($countryZh)"

    fun getShortName(): String = "$flagEmoji $code"

    fun matches(query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        return code.lowercase().contains(q) ||
            nameZh.lowercase().contains(q) ||
            countryZh.lowercase().contains(q) ||
            symbol.lowercase().contains(q)
    }
}

object CurrencyData {
    private val zhLocale = Locale.SIMPLIFIED_CHINESE
    private val infoCache = ConcurrentHashMap<String, CurrencyInfo>()

    private val preferredLocales = mapOf(
        "CNY" to Locale.CHINA,
        "USD" to Locale.US,
        "EUR" to Locale.GERMANY,
        "JPY" to Locale.JAPAN,
        "GBP" to Locale.UK,
        "AUD" to Locale("en", "AU"),
        "CAD" to Locale.CANADA,
        "HKD" to Locale("zh", "HK"),
        "MOP" to Locale("zh", "MO"),
        "TWD" to Locale.TAIWAN,
        "KRW" to Locale.KOREA,
        "SGD" to Locale("en", "SG"),
        "MYR" to Locale("ms", "MY"),
        "THB" to Locale("th", "TH"),
        "IDR" to Locale("id", "ID"),
        "VND" to Locale("vi", "VN"),
        "PHP" to Locale("en", "PH"),
        "INR" to Locale("en", "IN"),
        "RUB" to Locale("ru", "RU"),
        "PLN" to Locale("pl", "PL"),
        "CZK" to Locale("cs", "CZ"),
        "CHF" to Locale("de", "CH"),
        "SEK" to Locale("sv", "SE"),
        "NOK" to Locale("no", "NO"),
        "DKK" to Locale("da", "DK"),
        "NZD" to Locale("en", "NZ"),
        "MXN" to Locale("es", "MX"),
        "BRL" to Locale("pt", "BR"),
        "ZAR" to Locale("en", "ZA"),
        "TRY" to Locale("tr", "TR"),
        "AED" to Locale("ar", "AE"),
        "SAR" to Locale("ar", "SA")
    )

    private val preferredCountryZh = mapOf(
        "EUR" to "欧盟"
    )

    // For currencies that don't map cleanly to a single country locale.
    private val preferredFlagOverrides = mapOf(
        "EUR" to "🇪🇺"
    )

    // Some currencies still fall back to code under non-native locales.
    private val symbolOverrides = mapOf(
        "PLN" to "zł",
        "CZK" to "Kč",
        "RUB" to "₽",
        "TRY" to "₺",
        "THB" to "฿",
        "VND" to "₫",
        "PHP" to "₱",
        "KRW" to "₩",
        "INR" to "₹"
    )

    // Withdrawn / obsolete currency codes should not appear in picker lists.
    // This list can be extended whenever we find additional legacy codes in runtime data.
    private val deprecatedCurrencyCodes = setOf(
        "AFA", "ALK", "AOK", "AON", "AOR", "ARA", "ARP", "ARY", "ATS", "AZM",
        "BAD", "BEC", "BEF", "BEL", "BGL", "BOP", "BRB", "BRC", "BRE", "BRN", "BRR",
        "BYB", "BYR", "CLE", "CSJ", "CSK", "CYP", "DDM", "DEM", "EEK", "ESA", "ESB", "ESP",
        "FIM", "FRF", "GEK", "GHC", "GNE", "GNS", "GQE", "GRD", "GWE", "GWP", "HRD", "IEP",
        "ILP", "ILR", "ILY", "ISJ", "ITL", "LTL", "LTT", "LUC", "LUF", "LUL", "LVL", "LVR",
        "MGF", "MLF", "MRO", "MTL", "MTP", "MVP", "MXP", "MZE", "MZM", "NIC", "NLG", "PEH",
        "PEI", "PES", "PLZ", "PTE", "RHD", "ROL", "RUR", "SDD", "SDP", "SIT", "SKK", "SRG",
        "SUR", "TJR", "TMM", "TPE", "TRL", "UAK", "UGS", "USN", "USS", "UYP", "VEB", "VEF",
        "VNC", "XEU", "XFO", "XFU", "YDD", "YUD", "YUM", "YUN", "ZAL", "ZMK", "ZRN", "ZRZ",
        "ZWC", "ZWD", "ZWL"
    )

    private fun localeToFlag(country: String): String {
        if (country.length != 2) return ""
        val upper = country.uppercase(Locale.ROOT)
        val first = Character.codePointAt(upper, 0) - 0x41 + 0x1F1E6
        val second = Character.codePointAt(upper, 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(first)) + String(Character.toChars(second))
    }

    private fun resolveFlagEmoji(code: String, localeCountry: String?): String {
        val upper = code.uppercase()
        preferredFlagOverrides[upper]?.let { return it }
        val localeFlag = localeCountry
            ?.takeIf { it.isNotBlank() }
            ?.let(::localeToFlag)
            .orEmpty()
        if (localeFlag.isNotBlank()) return localeFlag
        // X** are usually supranational/basket/precious-metal units with no single national flag.
        if (upper.startsWith("X")) return "🌐"
        return "🏳️"
    }

    fun isSelectableCurrencyCode(code: String): Boolean {
        val upper = code.trim().uppercase()
        if (upper.length != 3) return false
        if (!upper.all { it in 'A'..'Z' }) return false
        return !deprecatedCurrencyCodes.contains(upper)
    }

    private fun bestLocaleFor(code: String): Locale? {
        preferredLocales[code]?.let { return it }
        return Locale.getAvailableLocales()
            .firstOrNull { locale ->
                val country = locale.country
                if (country.isBlank()) return@firstOrNull false
                runCatching { Currency.getInstance(locale) }.getOrNull()?.currencyCode == code
            }
    }

    private fun resolveSymbol(currency: Currency?, code: String, locale: Locale?): String {
        val upper = code.uppercase()
        return symbolOverrides[upper]
            ?: runCatching { currency?.getSymbol(locale ?: Locale.getDefault()) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: runCatching { currency?.symbol }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: upper
    }

    private fun fallbackInfo(code: String): CurrencyInfo {
        val upper = code.uppercase()
        val currency = runCatching { Currency.getInstance(upper) }.getOrNull()
        val locale = bestLocaleFor(upper)
        return CurrencyInfo(
            code = upper,
            nameZh = currency?.getDisplayName(zhLocale)?.takeIf { it.isNotBlank() } ?: upper,
            countryZh = preferredCountryZh[upper]
                ?: locale?.getDisplayCountry(zhLocale)?.takeIf { it.isNotBlank() }
                ?: upper,
            flagEmoji = resolveFlagEmoji(upper, locale?.country),
            symbol = resolveSymbol(currency, upper, locale)
        )
    }

    private fun buildInfo(code: String): CurrencyInfo {
        val upper = code.uppercase()
        val currency = runCatching { Currency.getInstance(upper) }.getOrNull() ?: return fallbackInfo(upper)
        val locale = bestLocaleFor(upper)
        val countryZh = when {
            preferredCountryZh.containsKey(upper) -> preferredCountryZh.getValue(upper)
            locale != null && locale.country.isNotBlank() -> locale.getDisplayCountry(zhLocale).ifBlank { upper }
            else -> upper
        }
        return CurrencyInfo(
            code = upper,
            nameZh = currency.getDisplayName(zhLocale).ifBlank { upper },
            countryZh = countryZh,
            flagEmoji = resolveFlagEmoji(upper, locale?.country),
            symbol = resolveSymbol(currency, upper, locale)
        )
    }

    fun getInfo(code: String): CurrencyInfo? {
        val upper = code.trim().uppercase()
        if (upper.isEmpty()) return null
        if (!isSelectableCurrencyCode(upper)) return null
        return infoCache.getOrPut(upper) { buildInfo(upper) }
    }

    fun getAllCurrencies(extraCodes: Collection<String> = emptyList()): List<CurrencyInfo> {
        val codes = linkedSetOf<String>()
        codes += "CNY"
        Currency.getAvailableCurrencies().forEach { codes += it.currencyCode.uppercase() }
        extraCodes.mapTo(codes) { it.trim().uppercase() }

        return codes.asSequence()
            .mapNotNull { getInfo(it) }
            .sortedBy { it.code }
            .toList()
    }

    val ALL_CURRENCIES: List<CurrencyInfo>
        get() = getAllCurrencies()
}
