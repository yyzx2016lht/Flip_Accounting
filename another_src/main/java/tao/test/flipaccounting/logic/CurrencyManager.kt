package tao.test.flipaccounting.logic

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object CurrencyManager {
    private const val PREF_KEY_RATES = "currency_rates_json"
    private const val PREF_KEY_LAST_UPDATE = "currency_rates_update_time"
    private const val API_URL = "https://api.exchangerate-api.com/v4/latest/CNY"
    private const val PREF_KEY_ENABLED_CURRENCIES = "enabled_currencies_list"
    private const val PREF_KEY_INTERVAL_MINUTES = "currency_refresh_interval_min"

    // Default fallback rates (against CNY base) as of 2024-ish estimates
    // Note: The API returns rates relative to CNY = 1. So 1 CNY = X USD.
    // To convert FROM USD to CNY: Amount_USD / Rate_USD_per_CNY
    // Or simpler: The API returns { "USD": 0.14, "EUR": 0.13, ... } meaning 1 CNY = 0.14 USD.
    // So 1 USD = 1 / 0.14 CNY.
    private val DEFAULT_RATES = mapOf(
        "CNY" to 1.0,
        "USD" to 0.14,
        "EUR" to 0.13,
        "PLN" to 0.56,
        "HKD" to 1.09,
        "JPY" to 20.0
    )

    private var rates: MutableMap<String, Double> = java.util.concurrent.ConcurrentHashMap(DEFAULT_RATES)

    private fun getPrefs(context: Context) = context.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE)

    fun init(context: Context) {
        val jsonStr = getPrefs(context).getString(PREF_KEY_RATES, "")
        if (jsonStr != null && jsonStr.isNotEmpty()) {
            try {
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    rates[key] = json.getDouble(key)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Auto update if older than current interval setting
        val lastUpdate = getPrefs(context).getLong(PREF_KEY_LAST_UPDATE, 0L)
        val intervalMins = getRefreshInterval(context)
        if (System.currentTimeMillis() - lastUpdate > intervalMins * 60 * 1000) {
            updateRates(context)
        }
    }

    fun getSupportedCurrencies(): List<String> {
        // Return sorted list with CNY first, then popular ones, then others
        val popular = listOf("CNY", "USD", "EUR", "PLN", "HKD", "JPY", "GBP")
        val all = rates.keys.toList().sorted()
        val result = ArrayList<String>()
        result.addAll(popular.filter { rates.containsKey(it) })
        result.addAll(all.filter { !popular.contains(it) })
        return result
    }

    fun convertToCny(amount: Double, currency: String): Double {
        if (currency == "CNY") return amount
        // Rate is "How many units of Currency for 1 CNY".
        // e.g. Rate = 0.56 PLN (for 1 CNY).
        // User spends 10 PLN.
        // 10 PLN / 0.56 = 17.8571428 CNY.
        val rate = rates[currency] ?: return amount
        if (rate == 0.0) return amount
        return amount / rate
    }

    fun getEnabledCurrencies(context: Context): List<String> {
        val s = getPrefs(context).getString(PREF_KEY_ENABLED_CURRENCIES, "")
        if (s.isNullOrEmpty()) return listOf("CNY")
        return s.split(",").filter { it.isNotEmpty() }
    }

    fun setEnabledCurrencies(context: Context, list: List<String>) {
        val s = list.joinToString(",")
        getPrefs(context).edit().putString(PREF_KEY_ENABLED_CURRENCIES, s).apply()
    }

    fun getRefreshInterval(context: Context): Int {
        return getPrefs(context).getInt(PREF_KEY_INTERVAL_MINUTES, 60) // Default 60 mins
    }

    fun setRefreshInterval(context: Context, minutes: Int) {
        getPrefs(context).edit().putInt(PREF_KEY_INTERVAL_MINUTES, minutes).apply()
    }

    fun getRate(currency: String): Double? {
        return rates[currency]
    }

    fun getLastUpdateTime(context: Context): Long {
        return getPrefs(context).getLong(PREF_KEY_LAST_UPDATE, 0L)
    }

    fun updateRates(context: Context, callback: ((Boolean) -> Unit)? = null) {
        Executors.newSingleThreadExecutor().execute {
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
                        val key = keys.next()
                        newRates[key] = ratesJson.getDouble(key)
                    }

                    // Update memory
                    synchronized(this) {
                        rates.putAll(newRates)
                    }

                    // Save to Prefs
                    getPrefs(context).edit()
                        .putString(PREF_KEY_RATES, ratesJson.toString())
                        .putLong(PREF_KEY_LAST_UPDATE, System.currentTimeMillis())
                        .apply()

                    Handler(Looper.getMainLooper()).post {
                        callback?.invoke(true)
                    }
                } else {
                    Handler(Looper.getMainLooper()).post {
                        callback?.invoke(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Handler(Looper.getMainLooper()).post {
                    callback?.invoke(false)
                }
            }
        }
    }
}
