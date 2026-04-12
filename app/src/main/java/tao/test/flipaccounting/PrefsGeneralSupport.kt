package tao.test.flipaccounting

import android.content.Context
import org.json.JSONArray

object PrefsGeneralSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_FLIP_ENABLED = "flip_enabled"
    private const val KEY_FLIP_ALWAYS = "flip_always_on"
    private const val KEY_FLIP_DISABLE_LANDSCAPE = "flip_disable_landscape"
    private const val KEY_WHITE_LIST = "app_white_list"
    private const val KEY_HIDE_RECENTS = "hide_recents_card"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_FLIP_SENSITIVITY = "flip_sensitivity_level"
    private const val KEY_FLIP_DURATION = "flip_duration_threshold"
    private const val KEY_USE_CUSTOM_SENSITIVITY = "use_custom_sensitivity"
    private const val KEY_CUSTOM_G_THRESHOLD = "custom_g_threshold"
    private const val KEY_CUSTOM_MAX_DURATION = "custom_max_duration"
    private const val KEY_ACTIVE_CURRENCIES = "active_currencies_v1"
    private const val KEY_EXCHANGE_REFRESH_INTERVAL = "exchange_refresh_interval_v1"
    private const val KEY_PERMANENT_WAKELOCK = "advanced_permanent_wakelock"
    private const val KEY_SHIZUKU_PERSISTENCE = "advanced_shizuku_persistence"
    private const val KEY_SHIZUKU_MODE = "advanced_shizuku_mode"
    private const val KEY_VIBRATE_FEEDBACK = "vibrate_feedback"
    private const val KEY_APP_USAGE_MODE = "app_usage_mode"
    private const val KEY_ASR_MODE = "asr_engine_mode"
    private const val KEY_ASR_DOWNLOAD_SOURCE = "asr_download_source_v1"
    private const val KEY_ASSET_FEATURE_ENABLED = "asset_feature_enabled_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isFlipEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLIP_ENABLED, false)
    fun setFlipEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_FLIP_ENABLED, enabled).apply()

    fun isVibrateFeedbackEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIBRATE_FEEDBACK, true)
    fun setVibrateFeedbackEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VIBRATE_FEEDBACK, enabled).apply()

    fun isFlipAlways(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FLIP_ALWAYS, true)
    fun setFlipAlways(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_FLIP_ALWAYS, enabled).apply()

    fun isFlipDisableLandscape(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FLIP_DISABLE_LANDSCAPE, false)
    fun setFlipDisableLandscape(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_FLIP_DISABLE_LANDSCAPE, enabled).apply()

    fun isHideRecents(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HIDE_RECENTS, false)
    fun setHideRecents(ctx: Context, hide: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_HIDE_RECENTS, hide).apply()

    fun isPermanentWakeLockEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_PERMANENT_WAKELOCK, false)
    fun setPermanentWakeLockEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_PERMANENT_WAKELOCK, enabled).apply()

    fun isShizukuPersistenceEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_PERSISTENCE, false)
    fun setShizukuPersistenceEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_PERSISTENCE, enabled).apply()

    fun isShizukuModeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_MODE, false)
    fun setShizukuModeEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_MODE, enabled).apply()

    fun isLoggingEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOGGING_ENABLED, false)
    fun setLoggingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_LOGGING_ENABLED, enabled).apply()

    fun getAppUsageMode(ctx: Context): Int = prefs(ctx).getInt(KEY_APP_USAGE_MODE, 0)
    fun setAppUsageMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_APP_USAGE_MODE, mode).apply()

    fun getAsrMode(ctx: Context): Int = prefs(ctx).getInt(KEY_ASR_MODE, Prefs.ASR_MODE_API)
    fun setAsrMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_ASR_MODE, mode).apply()

    fun getAsrDownloadSource(ctx: Context): String =
        prefs(ctx).getString(KEY_ASR_DOWNLOAD_SOURCE, "github") ?: "github"
    fun setAsrDownloadSource(ctx: Context, source: String) =
        prefs(ctx).edit().putString(KEY_ASR_DOWNLOAD_SOURCE, source).apply()

    fun isAssetFeatureEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ASSET_FEATURE_ENABLED, true)
    fun setAssetFeatureEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_ASSET_FEATURE_ENABLED, enabled).apply()

    fun getAppWhiteList(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_WHITE_LIST, emptySet()) ?: emptySet()
    fun setAppWhiteList(ctx: Context, list: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_WHITE_LIST, list).apply()

    fun getActiveCurrencies(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_ACTIVE_CURRENCIES, setOf("CNY")) ?: setOf("CNY")
    fun setActiveCurrencies(ctx: Context, currencies: Set<String>) =
        prefs(ctx).edit().putStringSet(KEY_ACTIVE_CURRENCIES, currencies).apply()

    fun getExchangeRefreshInterval(ctx: Context): Long =
        prefs(ctx).getLong(KEY_EXCHANGE_REFRESH_INTERVAL, 12 * 3600 * 1000L)
    fun setExchangeRefreshInterval(ctx: Context, interval: Long) =
        prefs(ctx).edit().putLong(KEY_EXCHANGE_REFRESH_INTERVAL, interval).apply()

    fun getFlipSensitivity(ctx: Context): Int = prefs(ctx).getInt(KEY_FLIP_SENSITIVITY, 50)
    fun setFlipSensitivity(ctx: Context, level: Int) =
        prefs(ctx).edit().putInt(KEY_FLIP_SENSITIVITY, level).apply()

    fun getFlipDuration(ctx: Context): Long = prefs(ctx).getLong(KEY_FLIP_DURATION, 400L)
    fun setFlipDuration(ctx: Context, duration: Long) =
        prefs(ctx).edit().putLong(KEY_FLIP_DURATION, duration).apply()

    fun isUseCustomSensitivity(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_USE_CUSTOM_SENSITIVITY, false)
    fun setUseCustomSensitivity(ctx: Context, use: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_USE_CUSTOM_SENSITIVITY, use).apply()

    fun getCustomGThreshold(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_CUSTOM_G_THRESHOLD, 7.25f)
    fun setCustomGThreshold(ctx: Context, g: Float) =
        prefs(ctx).edit().putFloat(KEY_CUSTOM_G_THRESHOLD, g).apply()

    fun getCustomMaxDuration(ctx: Context): Long =
        prefs(ctx).getLong(KEY_CUSTOM_MAX_DURATION, 550L)
    fun setCustomMaxDuration(ctx: Context, duration: Long) =
        prefs(ctx).edit().putLong(KEY_CUSTOM_MAX_DURATION, duration).apply()

    fun serializeWhiteList(set: Set<String>): String = JSONArray(set).toString()

    fun importWhiteList(ctx: Context, jsonStr: String) {
        runCatching {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            setAppWhiteList(ctx, list.toSet())
        }.onFailure { it.printStackTrace() }
    }
}
