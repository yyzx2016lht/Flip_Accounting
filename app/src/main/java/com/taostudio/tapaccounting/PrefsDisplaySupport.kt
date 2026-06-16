package com.taostudio.tapaccounting

import android.content.Context

object PrefsDisplaySupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_SHOW_AI_TEXT = "show_ai_text"
    private const val KEY_SHOW_AI_VOICE = "show_ai_voice"
    private const val KEY_SHOW_AI_IMAGE = "show_ai_image"
    private const val KEY_SHOW_SCREEN_ACCOUNTING = "show_screen_accounting"
    private const val KEY_SHOW_MULTI_CURRENCY = "show_multi_currency"
    private const val KEY_SHOW_HOME_TREND_CARD = "show_home_trend_card"
    private const val KEY_MULTI_BILL_ENABLED = "multi_bill_enabled"
    private const val KEY_MULTI_BILL_NOT_SYNC = "multi_bill_not_sync"
    private const val KEY_SHOW_BOOK_ENTRY = "show_book_entry"
    private const val KEY_OCR_MODE = "ocr_engine_mode"
    private const val KEY_RECEIPT_LANG_MODE = "receipt_lang_mode"
    private const val KEY_SAVE_OCR_DEBUG = "save_ocr_debug_before_ai"
    private const val KEY_AMOUNT_GROUPING = "amount_grouping_enabled"
    private const val KEY_BILL_SHOW_CATEGORY_ICON = "bill_show_category_icon_v1"
    private const val KEY_BILL_SHOW_FULL_CATEGORY = "bill_show_full_category_v1"
    private const val KEY_BILL_REMARK_PRIORITY = "bill_remark_priority_v1"
    private const val KEY_INDEPENDENT_DETAIL = "independent_detail_enabled_v1"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isShowAiText(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_AI_TEXT, false)
    fun setShowAiText(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_AI_TEXT, show).apply()

    fun isShowAiVoice(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_AI_VOICE, false)
    fun setShowAiVoice(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_AI_VOICE, show).apply()

    fun isShowAiImage(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_AI_IMAGE, false)
    fun setShowAiImage(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_AI_IMAGE, show).apply()

    fun isShowScreenAccounting(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_SCREEN_ACCOUNTING, false)
    fun setShowScreenAccounting(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_SCREEN_ACCOUNTING, show).apply()

    fun isShowMultiCurrency(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_MULTI_CURRENCY, false)
    fun setShowMultiCurrency(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_MULTI_CURRENCY, show).apply()

    fun isShowHomeTrendCard(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SHOW_HOME_TREND_CARD, true)
    fun setShowHomeTrendCard(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_HOME_TREND_CARD, show).apply()

    fun isMultiBillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MULTI_BILL_ENABLED, false)
    fun setMultiBillEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_MULTI_BILL_ENABLED, enabled).apply()

    fun isMultiBillNotSync(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MULTI_BILL_NOT_SYNC, false)
    fun setMultiBillNotSync(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_MULTI_BILL_NOT_SYNC, enabled).apply()

    fun isShowBookEntry(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHOW_BOOK_ENTRY, false)
    fun setShowBookEntry(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SHOW_BOOK_ENTRY, show).apply()

    fun getOcrMode(ctx: Context): Int = prefs(ctx).getInt(KEY_OCR_MODE, Prefs.OCR_MODE_MULTIMODAL)
    fun setOcrMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_OCR_MODE, mode).apply()

    fun getReceiptLangMode(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RECEIPT_LANG_MODE, Prefs.RECEIPT_LANG_AUTO)
    fun setReceiptLangMode(ctx: Context, mode: Int) =
        prefs(ctx).edit().putInt(KEY_RECEIPT_LANG_MODE, mode).apply()

    fun isSaveOcrDebugEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SAVE_OCR_DEBUG, false)
    fun setSaveOcrDebugEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SAVE_OCR_DEBUG, enabled).apply()

    fun isAmountGroupingEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AMOUNT_GROUPING, true)
    fun setAmountGroupingEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AMOUNT_GROUPING, enabled).apply()

    fun isShowBillCategoryIcon(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BILL_SHOW_CATEGORY_ICON, true)
    fun setShowBillCategoryIcon(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BILL_SHOW_CATEGORY_ICON, show).apply()

    fun isShowBillFullCategory(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BILL_SHOW_FULL_CATEGORY, true)
    fun setShowBillFullCategory(ctx: Context, show: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BILL_SHOW_FULL_CATEGORY, show).apply()

    fun isBillRemarkPriority(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_BILL_REMARK_PRIORITY, false)
    fun setBillRemarkPriority(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BILL_REMARK_PRIORITY, enabled).apply()

    fun isIndependentDetailEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_INDEPENDENT_DETAIL, false)
    fun setIndependentDetailEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_INDEPENDENT_DETAIL, enabled).apply()
}

