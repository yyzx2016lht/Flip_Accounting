package tao.test.flipaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Backup/restore serialization helpers for SharedPreferences.
 *
 * These methods used to live directly inside [Prefs]. They are kept here so
 * the public Prefs API can stay stable while the implementation becomes easier
 * to read and maintain.
 */
object PrefsBackupSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val CLOUD_PREFS_NAME = "flip_cloud_backup_prefs"
    private const val KEY_ASSETS = "assets_v1"
    private const val KEY_CAT_EXPENSE = "cat_expense_v1"
    private const val KEY_CAT_INCOME = "cat_income_v1"
    private const val KEY_BILLS = "bills_list"
    private const val KEY_WHITE_LIST = "app_white_list"
    private const val KEY_ACTIVE_CURRENCIES = "active_currencies_v1"
    private const val KEY_EXCHANGE_REFRESH_INTERVAL = "exchange_refresh_interval_v1"
    private const val KEY_FLIP_ENABLED = "flip_enabled"
    private const val KEY_FLIP_ALWAYS = "flip_always_on"
    private const val KEY_FLIP_DISABLE_LANDSCAPE = "flip_disable_landscape"
    private const val KEY_FLIP_SENSITIVITY = "flip_sensitivity_level"
    private const val KEY_FLIP_DURATION = "flip_duration_threshold"
    private const val KEY_USE_CUSTOM_SENSITIVITY = "use_custom_sensitivity"
    private const val KEY_CUSTOM_G_THRESHOLD = "custom_g_threshold"
    private const val KEY_CUSTOM_MAX_DURATION = "custom_max_duration"
    private const val KEY_HIDE_RECENTS = "hide_recents_card"
    private const val KEY_SHOW_AI_TEXT = "show_ai_text"
    private const val KEY_SHOW_AI_VOICE = "show_ai_voice"
    private const val KEY_SHOW_AI_IMAGE = "show_ai_image"
    private const val KEY_SHOW_SCREEN_ACCOUNTING = "show_screen_accounting"
    private const val KEY_SHOW_MULTI_CURRENCY = "show_multi_currency"
    private const val KEY_SHOW_HOME_TREND_CARD = "show_home_trend_card"
    private const val KEY_SHOW_BOOK_ENTRY = "show_book_entry"
    private const val KEY_SHOW_AI_CHAT_ENTRY = "show_ai_chat_entry"
    private const val KEY_SAVE_OCR_DEBUG = "save_ocr_debug_before_ai"
    private const val KEY_AI_KEY = "ai_api_key"
    private const val KEY_AI_URL = "ai_api_url"
    private const val KEY_AI_MODEL = "ai_model_id"
    private const val KEY_AI_PROMPT = "ai_system_prompt"
    private const val KEY_AI_SINGLE_MODEL = "ai_single_model_id"
    private const val KEY_AI_MULTI_MODEL = "ai_multi_model_id"
    private const val KEY_AI_RULE_MODEL = "ai_rule_model_id"
    private const val KEY_AI_RECEIPT_MODEL = "ai_receipt_model_id"
    private const val KEY_AI_RECEIPT_VISION_MODEL = "ai_receipt_vision_model_id"
    private const val KEY_AI_SCREEN_MODEL = "ai_screen_model_id"
    private const val KEY_AI_RECEIPT_OCR_REFINE_MODEL = "ai_receipt_ocr_refine_model_id"
    private const val KEY_AI_SPEECH_MODEL = "ai_speech_model_id"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_MODELS_CACHE = "ai_models_cache"
    private const val KEY_AI_ENTRY_MODE = "ai_entry_mode"
    private const val KEY_AI_CHAT_NAME = "ai_chat_name"
    private const val KEY_USER_CHAT_NAME = "user_chat_name"
    private const val KEY_USER_PROFILE_DESC = "user_profile_desc"
    private const val KEY_AI_CHAT_AVATAR_PATH = "ai_chat_avatar_path"
    private const val KEY_USER_CHAT_AVATAR_PATH = "user_chat_avatar_path"
    private const val KEY_AI_CHAT_BG_PATH = "ai_chat_bg_path"
    private const val KEY_AI_CHAT_MODEL = "ai_chat_model"
    private const val KEY_AI_CHAT_REPLY_STYLE = "ai_chat_reply_style"
    private const val KEY_AI_CHAT_REPLY_STYLE_CUSTOM = "ai_chat_reply_style_custom"
    private const val KEY_AI_CHAT_MODEL_AUDIO_SUPPORT = "ai_chat_model_audio_support"
    private const val KEY_MULTI_BILL_PROMPT = "multi_bill_prompt"
    private const val KEY_RULE_PROMPT = "rule_extract_prompt"
    private const val KEY_RECEIPT_BILL_PROMPT = "receipt_bill_prompt"
    private const val KEY_RECEIPT_VISION_PROMPT = "receipt_vision_prompt"
    private const val KEY_SCREEN_ACCOUNTING_PROMPT = "screen_accounting_prompt"
    private const val KEY_RECEIPT_OCR_REFINE_PROMPT = "receipt_ocr_refine_prompt"
    private const val KEY_MULTI_BILL_ENABLED = "multi_bill_enabled"
    private const val KEY_MULTI_BILL_NOT_SYNC = "multi_bill_not_sync"
    private const val KEY_ASR_MODE = "asr_engine_mode"
    private const val KEY_OCR_MODE = "ocr_engine_mode"
    private const val KEY_RECEIPT_OCR_REFINE_ENABLED = "receipt_ocr_refine_enabled"
    private const val KEY_RECEIPT_LANG_MODE = "receipt_lang_mode"
    private const val KEY_PERMANENT_WAKELOCK = "advanced_permanent_wakelock"
    private const val KEY_SHIZUKU_PERSISTENCE = "advanced_shizuku_persistence"
    private const val KEY_VIBRATE_FEEDBACK = "vibrate_feedback"
    private const val KEY_APP_USAGE_MODE = "app_usage_mode"
    private const val KEY_ASR_DOWNLOAD_SOURCE = "asr_download_source_v1"
    private const val KEY_ASSET_FEATURE_ENABLED = "asset_feature_enabled_v1"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_MULTI_BILL_FAST_MODE = "multi_bill_fast_mode"
    private const val KEY_BILL_SHOW_CATEGORY_ICON = "bill_show_category_icon_v1"
    private const val KEY_BILL_SHOW_FULL_CATEGORY = "bill_show_full_category_v1"
    private const val KEY_BILL_REMARK_PRIORITY = "bill_remark_priority_v1"
    private const val KEY_SCREEN_VISION_SUPPORTED_MODELS = "screen_vision_supported_models"
    private const val KEY_CLOUD_WEBDAV_URL = "webdav_url"
    private const val KEY_CLOUD_WEBDAV_USER = "webdav_user"
    private const val KEY_CLOUD_WEBDAV_PASS = "webdav_pass"
    private const val KEY_CLOUD_WEBDAV_DIR = "webdav_dir"
    private const val KEY_CLOUD_DEVICE_NAME = "webdav_device_name"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private fun cloudPrefs(ctx: Context) = ctx.getSharedPreferences(CLOUD_PREFS_NAME, Context.MODE_PRIVATE)

    fun importAll(ctx: Context, root: JSONObject) {
        val edit = prefs(ctx).edit()
        val currencyEdit = ctx.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE).edit()
        val cloudEdit = cloudPrefs(ctx).edit()

        if (root.has("assets_v1")) {
            edit.putString(KEY_ASSETS, root.get("assets_v1").toString())
        }
        if (root.has("cat_expense_v1")) {
            edit.putString(KEY_CAT_EXPENSE, root.get("cat_expense_v1").toString())
        }
        if (root.has("cat_income_v1")) {
            edit.putString(KEY_CAT_INCOME, root.get("cat_income_v1").toString())
        }

        val billsJson = when {
            root.has("bills_v1") -> root.get("bills_v1").toString()
            root.has(KEY_BILLS) -> root.get(KEY_BILLS).toString()
            else -> null
        }
        if (billsJson != null) edit.putString(KEY_BILLS, billsJson)

        if (root.has("app_white_list_v1")) {
            runCatching {
                val arr = JSONArray(root.getString("app_white_list_v1"))
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                edit.putStringSet(KEY_WHITE_LIST, set)
            }
        } else if (root.has(KEY_WHITE_LIST)) {
            runCatching {
                val obj = root.get(KEY_WHITE_LIST)
                if (obj is JSONArray) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until obj.length()) set.add(obj.getString(i))
                    edit.putStringSet(KEY_WHITE_LIST, set)
                }
            }
        }

        if (root.has("active_currencies_v1")) {
            val raw = root.getString("active_currencies_v1")
            val currencies = raw.split(",").filter { it.isNotBlank() }.toSet()
            if (currencies.isNotEmpty()) edit.putStringSet(KEY_ACTIVE_CURRENCIES, currencies)
        }
        if (root.has("exchange_refresh_interval_v1")) {
            edit.putLong(KEY_EXCHANGE_REFRESH_INTERVAL, root.getLong("exchange_refresh_interval_v1"))
        }
        val cmCurrenciesRaw = when {
            root.has("cm_enabled_currencies_v1") -> root.getString("cm_enabled_currencies_v1")
            root.has("active_currencies_v1") -> root.getString("active_currencies_v1")
            else -> null
        }
        if (cmCurrenciesRaw != null) {
            val cmList = cmCurrenciesRaw.split(",").filter { it.isNotBlank() }
            if (cmList.isNotEmpty()) {
                tao.test.flipaccounting.logic.CurrencyManager.setEnabledCurrencies(ctx, cmList)
            }
        }

        if (root.has("flip_enabled_v1")) edit.putBoolean(KEY_FLIP_ENABLED, root.getBoolean("flip_enabled_v1"))
        if (root.has("flip_always_v1")) edit.putBoolean(KEY_FLIP_ALWAYS, root.getBoolean("flip_always_v1"))
        if (root.has("flip_disable_landscape_v1")) edit.putBoolean(KEY_FLIP_DISABLE_LANDSCAPE, root.getBoolean("flip_disable_landscape_v1"))
        if (root.has("flip_sensitivity_v1")) edit.putInt(KEY_FLIP_SENSITIVITY, root.getInt("flip_sensitivity_v1"))
        if (root.has("flip_debounce_v1")) edit.putLong(KEY_FLIP_DURATION, root.getLong("flip_debounce_v1"))
        if (root.has("use_custom_sensitivity_v1")) edit.putBoolean(KEY_USE_CUSTOM_SENSITIVITY, root.getBoolean("use_custom_sensitivity_v1"))
        if (root.has("custom_g_threshold_v1")) edit.putFloat(KEY_CUSTOM_G_THRESHOLD, root.getDouble("custom_g_threshold_v1").toFloat())
        if (root.has("custom_max_duration_v1")) edit.putLong(KEY_CUSTOM_MAX_DURATION, root.getLong("custom_max_duration_v1"))

        if (root.has("hide_recents_v1")) edit.putBoolean(KEY_HIDE_RECENTS, root.getBoolean("hide_recents_v1"))

        if (root.has("show_ai_text_v1")) edit.putBoolean(KEY_SHOW_AI_TEXT, root.getBoolean("show_ai_text_v1"))
        if (root.has("show_ai_voice_v1")) edit.putBoolean(KEY_SHOW_AI_VOICE, root.getBoolean("show_ai_voice_v1"))
        if (root.has("show_multi_cur_v1")) edit.putBoolean(KEY_SHOW_MULTI_CURRENCY, root.getBoolean("show_multi_cur_v1"))
        if (root.has("show_ai_image_v1")) edit.putBoolean(KEY_SHOW_AI_IMAGE, root.getBoolean("show_ai_image_v1"))
        if (root.has("show_screen_accounting_v1")) edit.putBoolean(KEY_SHOW_SCREEN_ACCOUNTING, root.getBoolean("show_screen_accounting_v1"))
        if (root.has("show_home_trend_card_v1")) edit.putBoolean(KEY_SHOW_HOME_TREND_CARD, root.getBoolean("show_home_trend_card_v1"))
        if (root.has("show_book_entry_v1")) edit.putBoolean(KEY_SHOW_BOOK_ENTRY, root.getBoolean("show_book_entry_v1"))
        if (root.has("show_ai_chat_entry_v1")) edit.putBoolean(KEY_SHOW_AI_CHAT_ENTRY, root.getBoolean("show_ai_chat_entry_v1"))
        if (root.has("multi_bill_fast_mode_v1")) edit.putBoolean(KEY_MULTI_BILL_FAST_MODE, root.getBoolean("multi_bill_fast_mode_v1"))
        if (root.has("save_ocr_debug_v1")) edit.putBoolean(KEY_SAVE_OCR_DEBUG, root.getBoolean("save_ocr_debug_v1"))
        if (root.has("amount_grouping_v1")) edit.putBoolean("amount_grouping_enabled", root.getBoolean("amount_grouping_v1"))
        if (root.has("bill_show_category_icon_v1")) edit.putBoolean(KEY_BILL_SHOW_CATEGORY_ICON, root.getBoolean("bill_show_category_icon_v1"))
        if (root.has("bill_show_full_category_v1")) edit.putBoolean(KEY_BILL_SHOW_FULL_CATEGORY, root.getBoolean("bill_show_full_category_v1"))
        if (root.has("bill_remark_priority_v1")) edit.putBoolean(KEY_BILL_REMARK_PRIORITY, root.getBoolean("bill_remark_priority_v1"))

        if (root.has("ai_api_key_v1")) edit.putString(KEY_AI_KEY, root.getString("ai_api_key_v1"))
        if (root.has("ai_api_url_v1")) edit.putString(KEY_AI_URL, root.getString("ai_api_url_v1"))
        if (root.has("ai_model_id_v1")) edit.putString(KEY_AI_MODEL, root.getString("ai_model_id_v1"))
        if (root.has("ai_system_prompt_v1")) edit.putString(KEY_AI_PROMPT, root.getString("ai_system_prompt_v1"))
        if (root.has("ai_single_model_v1")) edit.putString(KEY_AI_SINGLE_MODEL, root.getString("ai_single_model_v1"))
        if (root.has("ai_multi_model_v1")) edit.putString(KEY_AI_MULTI_MODEL, root.getString("ai_multi_model_v1"))
        if (root.has("ai_rule_model_v1")) edit.putString(KEY_AI_RULE_MODEL, root.getString("ai_rule_model_v1"))
        if (root.has("ai_receipt_model_v1")) edit.putString(KEY_AI_RECEIPT_MODEL, root.getString("ai_receipt_model_v1"))
        if (root.has("ai_receipt_vision_model_v1")) edit.putString(KEY_AI_RECEIPT_VISION_MODEL, root.getString("ai_receipt_vision_model_v1"))
        if (root.has("ai_screen_model_v1")) edit.putString(KEY_AI_SCREEN_MODEL, root.getString("ai_screen_model_v1"))
        if (root.has("ai_receipt_ocr_refine_model_v1")) edit.putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, root.getString("ai_receipt_ocr_refine_model_v1"))
        if (root.has("ai_speech_model_v1")) edit.putString(KEY_AI_SPEECH_MODEL, root.getString("ai_speech_model_v1"))
        if (root.has("ai_provider_v1")) edit.putString(KEY_AI_PROVIDER, root.getString("ai_provider_v1"))
        if (root.has("screen_vision_supported_models_v1")) {
            runCatching {
                val models = root.getString("screen_vision_supported_models_v1")
                    .split("\\n")
                    .filter { it.isNotBlank() }
                    .toSet()
                edit.putStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, models)
            }
        }
        if (root.has("ai_models_cache_v1")) {
            val models = root.getString("ai_models_cache_v1").split("\\n").filter { it.isNotBlank() }.toSet()
            edit.putStringSet(KEY_AI_MODELS_CACHE, models)
        }
        if (root.has("ai_entry_mode_v1")) edit.putInt(KEY_AI_ENTRY_MODE, root.getInt("ai_entry_mode_v1"))
        if (root.has("ai_chat_name_v1")) edit.putString(KEY_AI_CHAT_NAME, root.getString("ai_chat_name_v1"))
        if (root.has("user_chat_name_v1")) edit.putString(KEY_USER_CHAT_NAME, root.getString("user_chat_name_v1"))
        if (root.has("user_profile_desc_v1")) edit.putString(KEY_USER_PROFILE_DESC, root.getString("user_profile_desc_v1"))
        if (root.has("ai_chat_avatar_path_v1")) edit.putString(KEY_AI_CHAT_AVATAR_PATH, root.getString("ai_chat_avatar_path_v1"))
        if (root.has("user_chat_avatar_path_v1")) edit.putString(KEY_USER_CHAT_AVATAR_PATH, root.getString("user_chat_avatar_path_v1"))
        if (root.has("ai_chat_bg_path_v1")) edit.putString(KEY_AI_CHAT_BG_PATH, root.getString("ai_chat_bg_path_v1"))
        if (root.has("ai_chat_model_v1")) edit.putString(KEY_AI_CHAT_MODEL, root.getString("ai_chat_model_v1"))
        if (root.has("ai_chat_reply_style_v1")) edit.putString(KEY_AI_CHAT_REPLY_STYLE, root.getString("ai_chat_reply_style_v1"))
        if (root.has("ai_chat_reply_style_custom_v1")) edit.putString(KEY_AI_CHAT_REPLY_STYLE_CUSTOM, root.getString("ai_chat_reply_style_custom_v1"))
        if (root.has("ai_chat_model_audio_support_v1")) edit.putString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, root.getString("ai_chat_model_audio_support_v1"))

        if (root.has("multi_bill_prompt_v1")) edit.putString(KEY_MULTI_BILL_PROMPT, root.getString("multi_bill_prompt_v1"))
        if (root.has("rule_prompt_v1")) edit.putString(KEY_RULE_PROMPT, root.getString("rule_prompt_v1"))
        if (root.has("receipt_bill_prompt_v1")) edit.putString(KEY_RECEIPT_BILL_PROMPT, root.getString("receipt_bill_prompt_v1"))
        if (root.has("receipt_vision_prompt_v1")) edit.putString(KEY_RECEIPT_VISION_PROMPT, root.getString("receipt_vision_prompt_v1"))
        if (root.has("screen_accounting_prompt_v1")) edit.putString(KEY_SCREEN_ACCOUNTING_PROMPT, root.getString("screen_accounting_prompt_v1"))
        if (root.has("receipt_ocr_refine_prompt_v1")) edit.putString(KEY_RECEIPT_OCR_REFINE_PROMPT, root.getString("receipt_ocr_refine_prompt_v1"))

        if (root.has("multi_bill_enabled_v1")) edit.putBoolean(KEY_MULTI_BILL_ENABLED, root.getBoolean("multi_bill_enabled_v1"))
        if (root.has("multi_bill_not_sync_v1")) edit.putBoolean(KEY_MULTI_BILL_NOT_SYNC, root.getBoolean("multi_bill_not_sync_v1"))

        if (root.has("asr_mode_v1")) edit.putInt(KEY_ASR_MODE, root.getInt("asr_mode_v1"))
        if (root.has("asr_download_source_v1")) edit.putString(KEY_ASR_DOWNLOAD_SOURCE, root.getString("asr_download_source_v1"))
        if (root.has("asset_feature_enabled_v1")) edit.putBoolean(KEY_ASSET_FEATURE_ENABLED, root.getBoolean("asset_feature_enabled_v1"))
        if (root.has("ocr_mode_v1")) edit.putInt(KEY_OCR_MODE, root.getInt("ocr_mode_v1"))
        if (root.has("receipt_ocr_refine_enabled_v1")) edit.putBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, root.getBoolean("receipt_ocr_refine_enabled_v1"))
        if (root.has("receipt_lang_mode_v1")) edit.putInt(KEY_RECEIPT_LANG_MODE, root.getInt("receipt_lang_mode_v1"))

        if (root.has("permanent_wakelock_v1")) edit.putBoolean(KEY_PERMANENT_WAKELOCK, root.getBoolean("permanent_wakelock_v1"))
        if (root.has("shizuku_persistence_v1")) edit.putBoolean(KEY_SHIZUKU_PERSISTENCE, root.getBoolean("shizuku_persistence_v1"))
        if (root.has("shizuku_mode_v1")) edit.putBoolean("advanced_shizuku_mode", root.getBoolean("shizuku_mode_v1"))
        if (root.has("vibrate_feedback_v1")) edit.putBoolean(KEY_VIBRATE_FEEDBACK, root.getBoolean("vibrate_feedback_v1"))
        if (root.has("app_usage_mode_v1")) edit.putInt(KEY_APP_USAGE_MODE, root.getInt("app_usage_mode_v1"))
        if (root.has("first_day_of_week_v1")) edit.putInt("first_day_of_week", root.getInt("first_day_of_week_v1"))
        if (root.has("ai_prompt_correction_v1")) edit.putBoolean("enable_ai_prompt_correction", root.getBoolean("ai_prompt_correction_v1"))
        if (root.has("local_rule_override_v1")) edit.putBoolean("enable_local_rule_override", root.getBoolean("local_rule_override_v1"))
        if (root.has("logging_enabled_v1")) edit.putBoolean(KEY_LOGGING_ENABLED, root.getBoolean("logging_enabled_v1"))
        if (root.has("book_accounts_v1")) edit.putString("book_accounts_v1", root.getString("book_accounts_v1"))
        if (root.has("selected_book_v1")) edit.putString("selected_book_name_v1", root.getString("selected_book_v1"))
        if (root.has("default_book_v1")) edit.putString("default_book_name_v1", root.getString("default_book_v1"))

        edit.apply()

        if (root.has("book_colors_v1")) {
            runCatching {
                val colorsObj = JSONObject(root.getString("book_colors_v1"))
                val colorEdit = prefs(ctx).edit()
                val keys = colorsObj.keys()
                while (keys.hasNext()) {
                    val bookName = keys.next()
                    colorEdit.putInt("book_color_$bookName", colorsObj.getInt(bookName))
                }
                colorEdit.apply()
            }
        }

        if (root.has("book_banners_v1")) {
            runCatching {
                val bannersObj = JSONObject(root.getString("book_banners_v1"))
                val bannerEdit = prefs(ctx).edit()
                val keys = bannersObj.keys()
                while (keys.hasNext()) {
                    val bookName = keys.next()
                    bannerEdit.putString("book_banner_$bookName", bannersObj.getString(bookName))
                }
                bannerEdit.apply()
            }
        }

        if (root.has("cm_rates_json_v1")) currencyEdit.putString("currency_rates_json", root.getString("cm_rates_json_v1"))
        if (root.has("cm_rates_update_time_v1")) currencyEdit.putLong("currency_rates_update_time", root.getLong("cm_rates_update_time_v1"))
        if (root.has("cm_refresh_interval_min_v1")) currencyEdit.putInt("currency_refresh_interval_min", root.getInt("cm_refresh_interval_min_v1"))
        currencyEdit.apply()

        if (root.has("cloud_webdav_url_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_URL, root.getString("cloud_webdav_url_v1"))
        if (root.has("cloud_webdav_user_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_USER, root.getString("cloud_webdav_user_v1"))
        if (root.has("cloud_webdav_pass_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_PASS, root.getString("cloud_webdav_pass_v1"))
        if (root.has("cloud_webdav_dir_v1")) cloudEdit.putString(KEY_CLOUD_WEBDAV_DIR, root.getString("cloud_webdav_dir_v1"))
        if (root.has("cloud_device_name_v1")) cloudEdit.putString(KEY_CLOUD_DEVICE_NAME, root.getString("cloud_device_name_v1"))
        cloudEdit.apply()

        if (root.has("ai_chat_session_titles_v1")) {
            runCatching {
                val titleObj = JSONObject(root.getString("ai_chat_session_titles_v1"))
                val titleEdit = prefs(ctx).edit()
                val keys = titleObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    titleEdit.putString(key, titleObj.getString(key))
                }
                titleEdit.apply()
            }
        }
    }

    fun serializeSettings(ctx: Context): String {
        val currencyPrefs = ctx.getSharedPreferences("flip_currency_prefs", Context.MODE_PRIVATE)
        val cloudPrefs = cloudPrefs(ctx)
        return JSONObject().apply {
            put("flip_enabled_v1", Prefs.isFlipEnabled(ctx))
            put("flip_always_v1", Prefs.isFlipAlways(ctx))
            put("flip_disable_landscape_v1", Prefs.isFlipDisableLandscape(ctx))
            put("hide_recents_v1", Prefs.isHideRecents(ctx))
            put("vibrate_feedback_v1", Prefs.isVibrateFeedbackEnabled(ctx))
            put("permanent_wakelock_v1", Prefs.isPermanentWakeLockEnabled(ctx))
            put("shizuku_persistence_v1", Prefs.isShizukuPersistenceEnabled(ctx))
            put("shizuku_mode_v1", Prefs.isShizukuModeEnabled(ctx))
            put("app_usage_mode_v1", Prefs.getAppUsageMode(ctx))
            put("first_day_of_week_v1", prefs(ctx).getInt("first_day_of_week", Calendar.MONDAY))
            put("asset_feature_enabled_v1", Prefs.isAssetFeatureEnabled(ctx))

            put("flip_sensitivity_v1", Prefs.getFlipSensitivity(ctx))
            put("flip_debounce_v1", Prefs.getFlipDuration(ctx))
            put("use_custom_sensitivity_v1", Prefs.isUseCustomSensitivity(ctx))
            put("custom_g_threshold_v1", Prefs.getCustomGThreshold(ctx).toDouble())
            put("custom_max_duration_v1", Prefs.getCustomMaxDuration(ctx))

            put("app_white_list_v1", Prefs.serializeWhiteList(Prefs.getAppWhiteList(ctx)))
            put("active_currencies_v1", Prefs.getActiveCurrencies(ctx).joinToString(","))
            put("exchange_refresh_interval_v1", Prefs.getExchangeRefreshInterval(ctx))
            put("cm_enabled_currencies_v1", tao.test.flipaccounting.logic.CurrencyManager.getEnabledCurrencies(ctx).joinToString(","))
            put("cm_rates_json_v1", currencyPrefs.getString("currency_rates_json", "") ?: "")
            put("cm_rates_update_time_v1", currencyPrefs.getLong("currency_rates_update_time", 0L))
            put("cm_refresh_interval_min_v1", currencyPrefs.getInt("currency_refresh_interval_min", 60))

            put("show_ai_text_v1", Prefs.isShowAiText(ctx))
            put("show_ai_voice_v1", Prefs.isShowAiVoice(ctx))
            put("show_ai_image_v1", Prefs.isShowAiImage(ctx))
            put("show_screen_accounting_v1", Prefs.isShowScreenAccounting(ctx))
            put("show_multi_cur_v1", Prefs.isShowMultiCurrency(ctx))
            put("show_home_trend_card_v1", Prefs.isShowHomeTrendCard(ctx))
            put("show_book_entry_v1", Prefs.isShowBookEntry(ctx))
            put("show_ai_chat_entry_v1", Prefs.isShowAiChatEntry(ctx))
            put("multi_bill_fast_mode_v1", Prefs.isMultiBillFastMode(ctx))
            put("save_ocr_debug_v1", Prefs.isSaveOcrDebugEnabled(ctx))
            put("amount_grouping_v1", Prefs.isAmountGroupingEnabled(ctx))
            put("bill_show_category_icon_v1", Prefs.isShowBillCategoryIcon(ctx))
            put("bill_show_full_category_v1", Prefs.isShowBillFullCategory(ctx))
            put("bill_remark_priority_v1", Prefs.isBillRemarkPriority(ctx))

            put("multi_bill_enabled_v1", Prefs.isMultiBillEnabled(ctx))
            put("multi_bill_not_sync_v1", Prefs.isMultiBillNotSync(ctx))

            put("asr_mode_v1", Prefs.getAsrMode(ctx))
            put("asr_download_source_v1", Prefs.getAsrDownloadSource(ctx))
            put("ocr_mode_v1", Prefs.getOcrMode(ctx))
            put("receipt_ocr_refine_enabled_v1", Prefs.isReceiptOcrRefineEnabled(ctx))
            put("receipt_lang_mode_v1", Prefs.getReceiptLangMode(ctx))

            put("ai_api_key_v1", Prefs.getAiKey(ctx))
            put("ai_api_url_v1", Prefs.getAiUrl(ctx))
            put("ai_provider_v1", Prefs.getAiProvider(ctx))
            put("ai_model_id_v1", Prefs.getAiModel(ctx))
            put("ai_single_model_v1", Prefs.getAiSingleModel(ctx))
            put("ai_multi_model_v1", Prefs.getAiMultiModel(ctx))
            put("ai_rule_model_v1", Prefs.getAiRuleModel(ctx))
            put("ai_receipt_model_v1", Prefs.getAiReceiptModel(ctx))
            put("ai_receipt_vision_model_v1", Prefs.getAiReceiptVisionModel(ctx))
            put("ai_screen_model_v1", Prefs.getAiScreenModel(ctx))
            put("ai_receipt_ocr_refine_model_v1", Prefs.getAiReceiptOcrRefineModel(ctx))
            put("ai_speech_model_v1", Prefs.getAiSpeechModel(ctx))
            put("screen_vision_supported_models_v1", (prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet()) ?: emptySet()).joinToString("\\n"))
            put("ai_models_cache_v1", Prefs.getAiModelsCache(ctx).joinToString("\\n"))

            put("ai_system_prompt_v1", Prefs.getAiPrompt(ctx))
            put("multi_bill_prompt_v1", Prefs.getMultiBillPrompt(ctx))
            put("rule_prompt_v1", Prefs.getRulePrompt(ctx))
            put("receipt_bill_prompt_v1", Prefs.getReceiptBillPrompt(ctx))
            put("receipt_vision_prompt_v1", Prefs.getReceiptVisionPrompt(ctx))
            put("screen_accounting_prompt_v1", Prefs.getScreenAccountingPrompt(ctx))
            put("receipt_ocr_refine_prompt_v1", Prefs.getReceiptOcrRefinePrompt(ctx))

            put("ai_prompt_correction_v1", Prefs.isAiPromptCorrectionEnabled(ctx))
            put("local_rule_override_v1", Prefs.isLocalRuleOverrideEnabled(ctx))
            put("logging_enabled_v1", Prefs.isLoggingEnabled(ctx))

            val bookAccounts = BookAccountManager.getBookAccounts(ctx)
            put("book_accounts_v1", BookAccountManager.serializeBookAccounts(ctx))
            put("selected_book_v1", BookAccountManager.getSelectedBook(ctx))
            put("default_book_v1", BookAccountManager.getDefaultBook(ctx))

            val bookColorsObj = JSONObject()
            val bookBannersObj = JSONObject()
            for (book in bookAccounts) {
                val norm = BookAccountManager.normalizeBookName(book)
                val colorVal = prefs(ctx).getInt("book_color_$norm", Int.MIN_VALUE)
                if (colorVal != Int.MIN_VALUE) bookColorsObj.put(norm, colorVal)
                val bannerPath = BookAccountManager.getBookBannerPath(ctx, norm)
                if (!bannerPath.isNullOrEmpty()) bookBannersObj.put(norm, bannerPath)
            }
            put("book_colors_v1", bookColorsObj.toString())
            put("book_banners_v1", bookBannersObj.toString())
            put("ai_entry_mode_v1", Prefs.getAiEntryMode(ctx))
            put("ai_chat_name_v1", Prefs.getAiChatName(ctx))
            put("user_chat_name_v1", Prefs.getUserChatName(ctx))
            put("user_profile_desc_v1", Prefs.getUserProfileDesc(ctx))
            put("ai_chat_avatar_path_v1", Prefs.getAiChatAvatarPath(ctx))
            put("user_chat_avatar_path_v1", Prefs.getUserChatAvatarPath(ctx))
            put("ai_chat_bg_path_v1", Prefs.getAiChatBgPath(ctx))
            put("ai_chat_model_v1", Prefs.getAiChatModel(ctx))
            put("ai_chat_reply_style_v1", Prefs.getAiChatReplyStyle(ctx))
            put("ai_chat_reply_style_custom_v1", Prefs.getAiChatReplyStyleCustomPrompt(ctx))
            put("ai_chat_model_audio_support_v1", prefs(ctx).getString(KEY_AI_CHAT_MODEL_AUDIO_SUPPORT, "") ?: "")
            put("ai_chat_session_titles_v1", serializeChatSessionTitles(ctx).toString())
            put("cloud_webdav_url_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_URL, "") ?: "")
            put("cloud_webdav_user_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_USER, "") ?: "")
            put("cloud_webdav_pass_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_PASS, "") ?: "")
            put("cloud_webdav_dir_v1", cloudPrefs.getString(KEY_CLOUD_WEBDAV_DIR, "") ?: "")
            put("cloud_device_name_v1", cloudPrefs.getString(KEY_CLOUD_DEVICE_NAME, "") ?: "")
        }.toString()
    }

    fun serializeSettingsModules(ctx: Context): Map<String, String> {
        val full = JSONObject(serializeSettings(ctx))
        return linkedMapOf(
            "settings_general_basic" to filterSettingsModule(full, "flip_enabled_v1", "flip_always_v1", "flip_disable_landscape_v1", "hide_recents_v1", "app_usage_mode_v1", "first_day_of_week_v1", "app_white_list_v1"),
            "settings_general_assets" to filterSettingsModule(full, "asset_feature_enabled_v1", "active_currencies_v1", "exchange_refresh_interval_v1", "cm_enabled_currencies_v1", "cm_rates_json_v1", "cm_rates_update_time_v1", "cm_refresh_interval_min_v1"),
            "settings_general_cloud" to filterSettingsModule(full, "cloud_webdav_url_v1", "cloud_webdav_user_v1", "cloud_webdav_pass_v1", "cloud_webdav_dir_v1", "cloud_device_name_v1"),
            "settings_display_entries" to filterSettingsModule(full, "show_ai_text_v1", "show_ai_voice_v1", "show_ai_image_v1", "show_screen_accounting_v1", "show_multi_cur_v1", "show_home_trend_card_v1", "show_book_entry_v1", "show_ai_chat_entry_v1"),
            "settings_display_bills" to filterSettingsModule(full, "amount_grouping_v1", "bill_show_category_icon_v1", "bill_show_full_category_v1", "bill_remark_priority_v1"),
            "settings_display_multibill" to filterSettingsModule(full, "multi_bill_enabled_v1", "multi_bill_not_sync_v1", "multi_bill_fast_mode_v1", "save_ocr_debug_v1"),
            "settings_general" to filterSettingsModule(full, "flip_enabled_v1", "flip_always_v1", "flip_disable_landscape_v1", "hide_recents_v1", "app_usage_mode_v1", "first_day_of_week_v1", "asset_feature_enabled_v1", "app_white_list_v1", "active_currencies_v1", "exchange_refresh_interval_v1", "cm_enabled_currencies_v1", "cm_rates_json_v1", "cm_rates_update_time_v1", "cm_refresh_interval_min_v1", "cloud_webdav_url_v1", "cloud_webdav_user_v1", "cloud_webdav_pass_v1", "cloud_webdav_dir_v1", "cloud_device_name_v1"),
            "settings_display" to filterSettingsModule(full, "show_ai_text_v1", "show_ai_voice_v1", "show_ai_image_v1", "show_screen_accounting_v1", "show_multi_cur_v1", "show_home_trend_card_v1", "show_book_entry_v1", "show_ai_chat_entry_v1", "multi_bill_enabled_v1", "multi_bill_not_sync_v1", "multi_bill_fast_mode_v1", "save_ocr_debug_v1", "amount_grouping_v1", "bill_show_category_icon_v1", "bill_show_full_category_v1", "bill_remark_priority_v1"),
            "settings_ai_core" to filterSettingsModule(full, "ai_api_key_v1", "ai_api_url_v1", "ai_provider_v1", "ai_model_id_v1", "ai_single_model_v1", "ai_multi_model_v1", "ai_rule_model_v1", "ai_receipt_model_v1", "ai_receipt_vision_model_v1", "ai_screen_model_v1", "ai_receipt_ocr_refine_model_v1", "ai_speech_model_v1", "screen_vision_supported_models_v1", "ai_models_cache_v1", "asr_mode_v1", "asr_download_source_v1", "ocr_mode_v1", "receipt_ocr_refine_enabled_v1", "receipt_lang_mode_v1", "ai_prompt_correction_v1", "local_rule_override_v1"),
            "settings_ai_prompts" to filterSettingsModule(full, "ai_system_prompt_v1", "multi_bill_prompt_v1", "rule_prompt_v1", "receipt_bill_prompt_v1", "receipt_vision_prompt_v1", "screen_accounting_prompt_v1", "receipt_ocr_refine_prompt_v1"),
            "settings_ai_chat" to filterSettingsModule(full, "ai_entry_mode_v1", "ai_chat_name_v1", "user_chat_name_v1", "user_profile_desc_v1", "ai_chat_avatar_path_v1", "user_chat_avatar_path_v1", "ai_chat_bg_path_v1", "ai_chat_model_v1", "ai_chat_reply_style_v1", "ai_chat_reply_style_custom_v1", "ai_chat_model_audio_support_v1", "ai_chat_session_titles_v1"),
            "settings_books" to filterSettingsModule(full, "book_accounts_v1", "selected_book_v1", "default_book_v1", "book_colors_v1", "book_banners_v1"),
            "settings_advanced_runtime" to filterSettingsModule(full, "vibrate_feedback_v1", "permanent_wakelock_v1", "shizuku_persistence_v1", "shizuku_mode_v1", "logging_enabled_v1"),
            "settings_advanced_flip" to filterSettingsModule(full, "flip_sensitivity_v1", "flip_debounce_v1", "use_custom_sensitivity_v1", "custom_g_threshold_v1", "custom_max_duration_v1"),
            "settings_advanced" to filterSettingsModule(full, "vibrate_feedback_v1", "permanent_wakelock_v1", "shizuku_persistence_v1", "shizuku_mode_v1", "logging_enabled_v1", "show_multi_cur_v1", "show_screen_accounting_v1", "flip_sensitivity_v1", "flip_debounce_v1", "use_custom_sensitivity_v1", "custom_g_threshold_v1", "custom_max_duration_v1")
        )
    }

    private fun filterSettingsModule(root: JSONObject, vararg keys: String): String {
        val result = JSONObject()
        keys.forEach { key -> if (root.has(key)) result.put(key, root.get(key)) }
        return result.toString()
    }

    private fun serializeChatSessionTitles(ctx: Context): JSONObject {
        val all = prefs(ctx).all
        val result = JSONObject()
        all.forEach { (key, value) ->
            if (key.startsWith("ai_chat_session_title_") && value is String) {
                result.put(key, value)
            }
        }
        return result
    }
}

