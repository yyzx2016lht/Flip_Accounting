package com.taostudio.tapaccounting.logic

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object CurrencyManager {
    private const val PREF_KEY_RATES = "currency_rates_json"
    private const val PREF_KEY_LAST_UPDATE = "currency_rates_update_time"
    private const val API_URL = "https://api.exchangerate-api.com/v4/latest/CNY"
    private const val PREF_KEY_ENABLED_CURRENCIES = "enabled_currencies_list"
    private const val PREF_KEY_INTERVAL_MINUTES = "currency_refresh_interval_min"

    // Default fallback rates (against CNY base)
    private val DEFAULT_RATES = mapOf(
        "CNY" to 1.0,
        "USD" to 0.14,
        "EUR" to 0.13,
        "PLN" to 0.56,
        "HKD" to 1.09,
        "JPY" to 20.0
    )

    private var rates: MutableMap<String, Double> = java.util.concurrent.ConcurrentHashMap(DEFAULT_RATES)
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private val isUpdatingRates = AtomicBoolean(false)
    private val pendingUpdateCallbacks = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val missingRateCurrencies = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun getPrefs(context: Context) = context.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE)
    private fun normalizeCurrency(code: String): String = code.trim().uppercase()

    fun init(context: Context) {
        val jsonStr = getPrefs(context).getString(PREF_KEY_RATES, "")
        if (jsonStr != null && jsonStr.isNotEmpty()) {
            try {
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = normalizeCurrency(keys.next())
                    rates[key] = json.getDouble(key)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val lastUpdate = getPrefs(context).getLong(PREF_KEY_LAST_UPDATE, 0L)
        val intervalMins = getRefreshInterval(context)
        if (System.currentTimeMillis() - lastUpdate > intervalMins * 60 * 1000) {
            updateRates(context)
        }
    }

    // 将指定货币转换为 CNY
    fun convertToCny(amount: Double, currency: String): Double {
        val normalized = normalizeCurrency(currency)
        if (normalized == "CNY") return amount
        val rate = rates[normalized]
        if (rate == null) {
            markMissingRate(normalized)
            return amount
        }
        return if (rate != 0.0) amount / rate else amount
    }

    // 将 CNY 转换为指定货币
    fun convertFromCny(amountCny: Double, targetCurrency: String): Double {
        val normalized = normalizeCurrency(targetCurrency)
        if (normalized == "CNY") return amountCny
        val rate = rates[normalized]
        if (rate == null) {
            markMissingRate(normalized)
            return amountCny
        }
        return amountCny * rate
    }

    fun getSupportedCurrencies(): List<String> {
        val popular = listOf("CNY", "USD", "EUR", "PLN", "HKD", "JPY", "GBP")
        val all = linkedSetOf<String>()
        all.addAll(popular)
        all.addAll(
            java.util.Currency.getAvailableCurrencies()
                .map { it.currencyCode.uppercase() }
                .filter { com.taostudio.tapaccounting.CurrencyData.isSelectableCurrencyCode(it) }
                .sorted()
        )
        all.addAll(
            rates.keys
                .map { it.uppercase() }
                .filter { com.taostudio.tapaccounting.CurrencyData.isSelectableCurrencyCode(it) }
                .sorted()
        )
        return all.toList()
    }

    fun getEnabledCurrencies(context: Context): List<String> {
        val s = getPrefs(context).getString(PREF_KEY_ENABLED_CURRENCIES, "")
        return if (s.isNullOrEmpty()) listOf("CNY") else s.split(",").filter { it.isNotEmpty() }
    }

    fun setEnabledCurrencies(context: Context, list: List<String>) {
        val s = list.joinToString(",")
        getPrefs(context).edit().putString(PREF_KEY_ENABLED_CURRENCIES, s).apply()
    }

    fun getRefreshInterval(context: Context): Int {
        return getPrefs(context).getInt(PREF_KEY_INTERVAL_MINUTES, 60)
    }

    fun setRefreshInterval(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(PREF_KEY_INTERVAL_MINUTES, minutes).apply()
    }

    fun getRate(currency: String): Double? {
        val normalized = normalizeCurrency(currency)
        val rate = rates[normalized]
        if (rate == null && normalized != "CNY") {
            markMissingRate(normalized)
        }
        return rate
    }

    fun hasRate(currency: String): Boolean {
        val normalized = normalizeCurrency(currency)
        if (normalized == "CNY") return true
        return rates[normalized] != null
    }

    fun getLastUpdateTime(context: Context): Long {
        return getPrefs(context).getLong(PREF_KEY_LAST_UPDATE, 0L)
    }

    fun updateRates(context: Context, callback: ((Boolean) -> Unit)? = null) {
        callback?.let { pendingUpdateCallbacks.add(it) }
        if (!isUpdatingRates.compareAndSet(false, true)) {
            return
        }

        updateExecutor.execute {
            var success = false
            try {
                val url = URL(API_URL)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()
                    val response = sb.toString()
                    val json = JSONObject(response)
                    val ratesJson = json.getJSONObject("rates")

                    val newRates = HashMap<String, Double>()
                    val keys = ratesJson.keys()
                    while (keys.hasNext()) {
                        val key = normalizeCurrency(keys.next())
                        newRates[key] = ratesJson.getDouble(key)
                    }

                    synchronized(this) {
                        rates.putAll(newRates)
                        clearResolvedMissingRatesLocked()
                    }

                    getPrefs(context).edit()
                        .putString(PREF_KEY_RATES, ratesJson.toString())
                        .putLong(PREF_KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()

                    success = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isUpdatingRates.set(false)
                dispatchPendingCallbacks(success)
            }
        }
    }

    fun hasMissingRates(): Boolean = missingRateCurrencies.isNotEmpty()

    fun getMissingRateCurrencies(): Set<String> = missingRateCurrencies.toSet()

    fun clearMissingRateCurrencies() {
        missingRateCurrencies.clear()
    }

    fun getRateStatusSummary(context: Context): String {
        val last = getLastUpdateTime(context)
        val hasMissing = hasMissingRates()
        return when {
            last <= 0L && hasMissing -> "汇率未初始化，存在缺失币种"
            last <= 0L -> "汇率未初始化"
            hasMissing -> "汇率已更新，但存在缺失币种"
            else -> "汇率状态正常"
        }
    }

    private fun markMissingRate(currency: String) {
        if (currency.isBlank() || currency == "CNY") return
        missingRateCurrencies.add(currency)
    }

    private fun clearResolvedMissingRatesLocked() {
        missingRateCurrencies.removeIf { code -> rates.containsKey(code) }
    }

    private fun dispatchPendingCallbacks(success: Boolean) {
        val callbacks = pendingUpdateCallbacks.toList()
        pendingUpdateCallbacks.clear()
        Handler(Looper.getMainLooper()).post {
            callbacks.forEach { cb ->
                try {
                    cb.invoke(success)
                } catch (_: Exception) {
                }
            }
        }
    }

    fun getSymbol(code: String): String {
        val info = com.taostudio.tapaccounting.CurrencyData.getInfo(code)
        return info?.symbol ?: try {
            java.util.Currency.getInstance(code).symbol
        } catch (e: Exception) {
            code
        }
    }
}

