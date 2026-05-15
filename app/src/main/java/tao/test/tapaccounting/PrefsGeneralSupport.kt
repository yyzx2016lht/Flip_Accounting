package tao.test.tapaccounting

import android.content.Context
import org.json.JSONArray

object PrefsGeneralSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_DISABLE_LANDSCAPE = "flip_disable_landscape"
    private const val KEY_WHITE_LIST = "app_white_list"
    private const val KEY_HIDE_RECENTS = "hide_recents_card"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_ACTIVE_CURRENCIES = "active_currencies_v1"
    private const val KEY_EXCHANGE_REFRESH_INTERVAL = "exchange_refresh_interval_v1"
    private const val KEY_PERMANENT_WAKELOCK = "advanced_permanent_wakelock"
    private const val KEY_SHIZUKU_PERSISTENCE = "advanced_shizuku_persistence"
    private const val KEY_SHIZUKU_MODE = "advanced_shizuku_mode"
    private const val KEY_VIBRATE_FEEDBACK = "vibrate_feedback"
    private const val KEY_SAVE_VIBRATE = "save_vibrate_feedback"
    private const val KEY_APP_USAGE_MODE = "app_usage_mode"
    private const val KEY_ASR_MODE = "asr_engine_mode"
    private const val KEY_ASR_DOWNLOAD_SOURCE = "asr_download_source_v1"
    private const val KEY_ASSET_FEATURE_ENABLED = "asset_feature_enabled_v1"
    private const val KEY_PRIVACY_DEBUG_UNTIL_MS = "privacy_debug_until_ms_v1"
    private const val KEY_DEVELOPER_FULL_LOGGING = "developer_full_logging_v1"
    private const val KEY_QUICK_GESTURE_ENABLED = "quick_gesture_enabled"
    private const val KEY_DOUBLE_TAP_ENABLED = "double_tap_enabled"
    private const val KEY_DOUBLE_TAP_GUIDE_SEEN = "double_tap_guide_seen"
    private const val KEY_TAP_MODEL = "tap_model"
    private const val KEY_TAP_SENSITIVITY_LEVEL = "tap_sensitivity_level"
    private const val KEY_TAP_NNAPI_LOW_POWER = "tap_nnapi_low_power"
    private const val KEY_TAP_TRIPLE_ENABLED = "tap_triple_enabled"
    private const val KEY_TAP_LOW_POWER = "tap_low_power"
    private const val KEY_TAP_ACTION_DOUBLE = "tap_action_double"
    private const val KEY_TAP_ACTION_TRIPLE = "tap_action_triple"
    private const val KEY_API_CONFIG_UNLOCKED = "api_config_unlocked_v1"
    private const val KEY_AI_DETAIL_CONFIG_UNLOCKED = "ai_detail_config_unlocked_v1"
    private const val KEY_SHIZUKU_UNLOCKED = "shizuku_unlocked_v1"
    private const val KEY_AGGRESSIVE_KEEP_ALIVE = "aggressive_keep_alive"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isVibrateFeedbackEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_VIBRATE_FEEDBACK, true)
    fun setVibrateFeedbackEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_VIBRATE_FEEDBACK, enabled).apply()

    fun isSaveVibrateEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SAVE_VIBRATE, true)
    fun setSaveVibrateEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SAVE_VIBRATE, enabled).apply()

    fun isDisableLandscape(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DISABLE_LANDSCAPE, false)
    fun setDisableLandscape(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DISABLE_LANDSCAPE, enabled).apply()

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

    fun enablePrivacyDebugLoggingForMinutes(ctx: Context, minutes: Int) {
        val durationMs = minutes.coerceIn(1, 240) * 60_000L
        val untilMs = System.currentTimeMillis() + durationMs
        prefs(ctx).edit().putLong(KEY_PRIVACY_DEBUG_UNTIL_MS, untilMs).apply()
    }

    fun disablePrivacyDebugLogging(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PRIVACY_DEBUG_UNTIL_MS).apply()
    }

    fun isPrivacyDebugLoggingEnabled(ctx: Context): Boolean {
        if (isDeveloperFullLoggingEnabled(ctx)) return true
        val untilMs = prefs(ctx).getLong(KEY_PRIVACY_DEBUG_UNTIL_MS, 0L)
        if (untilMs <= 0L) return false
        val enabled = System.currentTimeMillis() < untilMs
        if (!enabled) {
            disablePrivacyDebugLogging(ctx)
        }
        return enabled
    }

    fun isDeveloperFullLoggingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DEVELOPER_FULL_LOGGING, false)

    fun setDeveloperFullLoggingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_DEVELOPER_FULL_LOGGING, enabled).apply()

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

    fun isQuickGestureEnabled(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_QUICK_GESTURE_ENABLED)) {
            return p.getBoolean(KEY_QUICK_GESTURE_ENABLED, false) || isDoubleTapEnabled(ctx)
        }
        val migrated = isDoubleTapEnabled(ctx)
        setQuickGestureEnabled(ctx, migrated)
        return migrated
    }
    fun setQuickGestureEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_QUICK_GESTURE_ENABLED, enabled)
            .putBoolean(KEY_DOUBLE_TAP_ENABLED, enabled)
            .apply()

    fun isDoubleTapEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DOUBLE_TAP_ENABLED, false)
    fun setDoubleTapEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit()
            .putBoolean(KEY_DOUBLE_TAP_ENABLED, enabled)
            .putBoolean(KEY_QUICK_GESTURE_ENABLED, enabled)
            .apply()

    fun hasSeenDoubleTapGuide(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_DOUBLE_TAP_GUIDE_SEEN, false)
    fun setDoubleTapGuideSeen(ctx: Context) =
        prefs(ctx).edit().putBoolean(KEY_DOUBLE_TAP_GUIDE_SEEN, true).apply()

    // --- Tap back settings ---
    fun getTapModel(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_MODEL, "") ?: ""
    fun setTapModel(ctx: Context, model: String) =
        prefs(ctx).edit().putString(KEY_TAP_MODEL, model).apply()

    fun getTapSensitivityLevel(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TAP_SENSITIVITY_LEVEL, 5)
    fun setTapSensitivityLevel(ctx: Context, level: Int) =
        prefs(ctx).edit().putInt(KEY_TAP_SENSITIVITY_LEVEL, level).apply()

    fun isTapNnapiLowPower(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_NNAPI_LOW_POWER, false)
    fun setTapNnapiLowPower(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_NNAPI_LOW_POWER, enabled).apply()

    fun isTapLowPower(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_LOW_POWER, false)
    fun setTapLowPower(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_LOW_POWER, enabled).apply()

    fun isTapTripleEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TAP_TRIPLE_ENABLED, false)
    fun setTapTripleEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_TAP_TRIPLE_ENABLED, enabled).apply()

    fun getTapActionDouble(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_ACTION_DOUBLE, "") ?: ""
    fun setTapActionDouble(ctx: Context, actionId: String) =
        prefs(ctx).edit().putString(KEY_TAP_ACTION_DOUBLE, actionId).apply()

    fun getTapActionTriple(ctx: Context): String =
        prefs(ctx).getString(KEY_TAP_ACTION_TRIPLE, "") ?: ""
    fun setTapActionTriple(ctx: Context, actionId: String) =
        prefs(ctx).edit().putString(KEY_TAP_ACTION_TRIPLE, actionId).apply()

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

    fun isApiConfigUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_API_CONFIG_UNLOCKED, false)
    fun setApiConfigUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_API_CONFIG_UNLOCKED, unlocked).apply()

    fun isAiDetailConfigUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_DETAIL_CONFIG_UNLOCKED, false)
    fun setAiDetailConfigUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_DETAIL_CONFIG_UNLOCKED, unlocked).apply()

    fun isShizukuUnlocked(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHIZUKU_UNLOCKED, false)
    fun setShizukuUnlocked(ctx: Context, unlocked: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHIZUKU_UNLOCKED, unlocked).apply()

    fun isAggressiveKeepAliveEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AGGRESSIVE_KEEP_ALIVE, false)
    fun setAggressiveKeepAliveEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AGGRESSIVE_KEEP_ALIVE, enabled).apply()
}
