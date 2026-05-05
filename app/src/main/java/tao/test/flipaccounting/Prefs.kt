package tao.test.flipaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Centralized SharedPreferences access.
 *
 * Existing call sites still use `Prefs.xxx(...)`, so this object keeps the
 * public API stable while we gradually split large helper logic out.
 */
object Prefs {
    const val AI_ENTRY_MODE_TRADITIONAL = 0
    const val AI_ENTRY_MODE_CHAT = 1

    const val ASR_MODE_API = 0
    const val ASR_MODE_WHISPER = 1
    const val OCR_MODE_LOCAL = 0
    const val OCR_MODE_MULTIMODAL = 1
    const val RECEIPT_LANG_AUTO = 0
    const val RECEIPT_LANG_CN = 1
    const val RECEIPT_LANG_FOREIGN = 2

    const val TYPE_EXPENSE = 1
    const val TYPE_INCOME = 2

    // --- General settings ---
    fun isFlipEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isFlipEnabled(ctx)
    fun setFlipEnabled(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setFlipEnabled(ctx, enabled)

    fun isVibrateFeedbackEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isVibrateFeedbackEnabled(ctx)
    fun setVibrateFeedbackEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setVibrateFeedbackEnabled(ctx, enabled)

    fun isSaveVibrateEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isSaveVibrateEnabled(ctx)
    fun setSaveVibrateEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setSaveVibrateEnabled(ctx, enabled)

    fun isFlipAlways(ctx: Context): Boolean = PrefsGeneralSupport.isFlipAlways(ctx)
    fun setFlipAlways(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setFlipAlways(ctx, enabled)

    fun isFlipDisableLandscape(ctx: Context): Boolean =
        PrefsGeneralSupport.isFlipDisableLandscape(ctx)
    fun setFlipDisableLandscape(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setFlipDisableLandscape(ctx, enabled)

    fun isHideRecents(ctx: Context): Boolean = PrefsGeneralSupport.isHideRecents(ctx)
    fun setHideRecents(ctx: Context, hide: Boolean) = PrefsGeneralSupport.setHideRecents(ctx, hide)

    fun isPermanentWakeLockEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isPermanentWakeLockEnabled(ctx)
    fun setPermanentWakeLockEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setPermanentWakeLockEnabled(ctx, enabled)

    fun isShizukuPersistenceEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isShizukuPersistenceEnabled(ctx)
    fun setShizukuPersistenceEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setShizukuPersistenceEnabled(ctx, enabled)
    fun isShizukuModeEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isShizukuModeEnabled(ctx)
    fun setShizukuModeEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setShizukuModeEnabled(ctx, enabled)

    fun isLoggingEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isLoggingEnabled(ctx)
    fun setLoggingEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setLoggingEnabled(ctx, enabled)
    fun enablePrivacyDebugLoggingForMinutes(ctx: Context, minutes: Int = 30) =
        PrefsGeneralSupport.enablePrivacyDebugLoggingForMinutes(ctx, minutes)
    fun disablePrivacyDebugLogging(ctx: Context) =
        PrefsGeneralSupport.disablePrivacyDebugLogging(ctx)
    fun isPrivacyDebugLoggingEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isPrivacyDebugLoggingEnabled(ctx)
    fun isDeveloperFullLoggingEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isDeveloperFullLoggingEnabled(ctx)
    fun setDeveloperFullLoggingEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setDeveloperFullLoggingEnabled(ctx, enabled)

    fun getAppUsageMode(ctx: Context): Int = PrefsGeneralSupport.getAppUsageMode(ctx)
    fun setAppUsageMode(ctx: Context, mode: Int) = PrefsGeneralSupport.setAppUsageMode(ctx, mode)

    fun getAsrMode(ctx: Context): Int = PrefsGeneralSupport.getAsrMode(ctx)
    fun setAsrMode(ctx: Context, mode: Int) = PrefsGeneralSupport.setAsrMode(ctx, mode)
    fun getAsrDownloadSource(ctx: Context): String = PrefsGeneralSupport.getAsrDownloadSource(ctx)
    fun setAsrDownloadSource(ctx: Context, source: String) =
        PrefsGeneralSupport.setAsrDownloadSource(ctx, source)
    fun isAssetFeatureEnabled(ctx: Context): Boolean =
        PrefsGeneralSupport.isAssetFeatureEnabled(ctx)
    fun setAssetFeatureEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setAssetFeatureEnabled(ctx, enabled)

    // --- AI settings ---
    fun getAiKey(ctx: Context): String = PrefsAiSupport.getAiKey(ctx)
    fun setAiKey(ctx: Context, key: String) = PrefsAiSupport.setAiKey(ctx, key)

    fun getAiModel(ctx: Context): String = PrefsAiSupport.getAiModel(ctx)
    fun setAiModel(ctx: Context, value: String) = PrefsAiSupport.setAiModel(ctx, value)

    fun getAiMultiModel(ctx: Context): String = PrefsAiSupport.getAiMultiModel(ctx)
    fun setAiMultiModel(ctx: Context, value: String) = PrefsAiSupport.setAiMultiModel(ctx, value)

    fun getAiModifyModel(ctx: Context): String = PrefsAiSupport.getAiModifyModel(ctx)
    fun setAiModifyModel(ctx: Context, value: String) = PrefsAiSupport.setAiModifyModel(ctx, value)

    fun getAiCategoryRefineModel(ctx: Context): String = PrefsAiSupport.getAiCategoryRefineModel(ctx)
    fun setAiCategoryRefineModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiCategoryRefineModel(ctx, value)

    fun getAiRouterModel(ctx: Context): String = PrefsAiSupport.getAiRouterModel(ctx)
    fun setAiRouterModel(ctx: Context, value: String) = PrefsAiSupport.setAiRouterModel(ctx, value)
    fun isAiLlmRouterEnabled(ctx: Context): Boolean = PrefsAiSupport.isAiLlmRouterEnabled(ctx)
    fun setAiLlmRouterEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiLlmRouterEnabled(ctx, enabled)
    fun getAiQueryModel(ctx: Context): String = PrefsAiSupport.getAiQueryModel(ctx)
    fun setAiQueryModel(ctx: Context, value: String) = PrefsAiSupport.setAiQueryModel(ctx, value)

    fun getAiRuleModel(ctx: Context): String = PrefsAiSupport.getAiRuleModel(ctx)
    fun setAiRuleModel(ctx: Context, value: String) = PrefsAiSupport.setAiRuleModel(ctx, value)
    fun getAiReceiptModel(ctx: Context): String = PrefsAiSupport.getAiReceiptModel(ctx)
    fun setAiReceiptModel(ctx: Context, value: String) = PrefsAiSupport.setAiReceiptModel(ctx, value)
    fun getAiReceiptVisionModel(ctx: Context): String = PrefsAiSupport.getAiReceiptVisionModel(ctx)
    fun setAiReceiptVisionModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiReceiptVisionModel(ctx, value)
    fun getAiScreenModel(ctx: Context): String = PrefsAiSupport.getAiScreenModel(ctx)
    fun setAiScreenModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiScreenModel(ctx, value)
    fun isScreenModelVisionSupported(ctx: Context, model: String): Boolean =
        PrefsAiSupport.isScreenModelVisionSupported(ctx, model)
    fun setScreenModelVisionSupported(ctx: Context, model: String, supported: Boolean) =
        PrefsAiSupport.setScreenModelVisionSupported(ctx, model, supported)
    fun getAiReceiptOcrRefineModel(ctx: Context): String = PrefsAiSupport.getAiReceiptOcrRefineModel(ctx)
    fun setAiReceiptOcrRefineModel(ctx: Context, value: String) =
        PrefsAiSupport.setAiReceiptOcrRefineModel(ctx, value)
    fun getAiSpeechModel(ctx: Context): String = PrefsAiSupport.getAiSpeechModel(ctx)
    fun setAiSpeechModel(ctx: Context, value: String) = PrefsAiSupport.setAiSpeechModel(ctx, value)
    fun isAiThinkingEnabled(ctx: Context): Boolean = PrefsAiSupport.getAiEnableThinking(ctx)
    fun setAiThinkingEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiEnableThinking(ctx, enabled)
    fun isAiThinkingMultiBillEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingMultiBillEnabled(ctx)
    fun setAiThinkingMultiBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingMultiBillEnabled(ctx, enabled)
    fun isAiThinkingModifyBillEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingModifyBillEnabled(ctx)
    fun setAiThinkingModifyBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingModifyBillEnabled(ctx, enabled)
    fun isAiThinkingVisionEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingVisionEnabled(ctx)
    fun setAiThinkingVisionEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingVisionEnabled(ctx, enabled)
    fun isAiThinkingCategoryRefineEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isAiThinkingCategoryRefineEnabled(ctx)
    fun setAiThinkingCategoryRefineEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiThinkingCategoryRefineEnabled(ctx, enabled)
    fun isAiQueryEnabled(ctx: Context): Boolean = PrefsAiSupport.isAiQueryEnabled(ctx)
    fun setAiQueryEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setAiQueryEnabled(ctx, enabled)
    fun getSimpleRouterStats(ctx: Context): PrefsAiSupport.SimpleRouterStats =
        PrefsAiSupport.getSimpleRouterStats(ctx)
    fun resetSimpleRouterStats(ctx: Context) = PrefsAiSupport.resetSimpleRouterStats(ctx)
    fun recordSimpleRouterBookkeeping(ctx: Context): PrefsAiSupport.SimpleRouterStats =
        PrefsAiSupport.recordSimpleRouterBookkeeping(ctx)
    fun recordSimpleRouterChat(ctx: Context): PrefsAiSupport.SimpleRouterStats =
        PrefsAiSupport.recordSimpleRouterChat(ctx)
    fun recordSimpleRouterBlockedWrite(ctx: Context): PrefsAiSupport.SimpleRouterStats =
        PrefsAiSupport.recordSimpleRouterBlockedWrite(ctx)

    fun getAiModelsCache(ctx: Context): List<String> = PrefsAiSupport.getAiModelsCache(ctx)
    fun setAiModelsCache(ctx: Context, models: List<String>) =
        PrefsAiSupport.setAiModelsCache(ctx, models)

    fun getAiProvider(ctx: Context): String = PrefsAiSupport.getAiProvider(ctx)
    fun setAiProvider(ctx: Context, value: String) = PrefsAiSupport.setAiProvider(ctx, value)

    fun getAiUrl(ctx: Context): String = PrefsAiSupport.getAiUrl(ctx)
    fun setAiUrl(ctx: Context, url: String) = PrefsAiSupport.setAiUrl(ctx, url)

    fun getAiPrompt(ctx: Context): String = PrefsAiSupport.getAiPrompt(ctx)
    fun setAiPrompt(ctx: Context, prompt: String) = PrefsAiSupport.setAiPrompt(ctx, prompt)
    fun getModifyBillPrompt(ctx: Context): String = PrefsAiSupport.getModifyBillPrompt(ctx)
    fun setModifyBillPrompt(ctx: Context, prompt: String) = PrefsAiSupport.setModifyBillPrompt(ctx, prompt)

    fun getMultiBillPrompt(ctx: Context): String = PrefsAiSupport.getMultiBillPrompt(ctx)
    fun setMultiBillPrompt(ctx: Context, prompt: String) =
        PrefsAiSupport.setMultiBillPrompt(ctx, prompt)

    fun getRulePrompt(ctx: Context): String = PrefsAiSupport.getRulePrompt(ctx)
    fun setRulePrompt(ctx: Context, prompt: String) = PrefsAiSupport.setRulePrompt(ctx, prompt)
    fun getReceiptBillPrompt(ctx: Context): String = PrefsAiSupport.getReceiptBillPrompt(ctx)
    fun setReceiptBillPrompt(ctx: Context, prompt: String) =
        PrefsAiSupport.setReceiptBillPrompt(ctx, prompt)
    fun getReceiptVisionPrompt(ctx: Context): String = PrefsAiSupport.getReceiptVisionPrompt(ctx)
    fun setReceiptVisionPrompt(ctx: Context, prompt: String) =
        PrefsAiSupport.setReceiptVisionPrompt(ctx, prompt)
    fun getScreenAccountingPrompt(ctx: Context): String =
        PrefsAiSupport.getScreenAccountingPrompt(ctx)
    fun setScreenAccountingPrompt(ctx: Context, prompt: String) =
        PrefsAiSupport.setScreenAccountingPrompt(ctx, prompt)
    fun isReceiptOcrRefineEnabled(ctx: Context): Boolean =
        PrefsAiSupport.isReceiptOcrRefineEnabled(ctx)
    fun setReceiptOcrRefineEnabled(ctx: Context, enabled: Boolean) =
        PrefsAiSupport.setReceiptOcrRefineEnabled(ctx, enabled)
    fun getReceiptOcrRefinePrompt(ctx: Context): String =
        PrefsAiSupport.getReceiptOcrRefinePrompt(ctx)
    fun setReceiptOcrRefinePrompt(ctx: Context, prompt: String) =
        PrefsAiSupport.setReceiptOcrRefinePrompt(ctx, prompt)

    // --- Bill cache management ---
    fun addBill(ctx: Context, bill: Bill) = PrefsDataSupport.addBill(ctx, bill)

    fun deleteBill(ctx: Context, bill: Bill) {
        deleteBills(ctx, setOf(bill))
    }

    fun deleteBills(ctx: Context, billsToDelete: Set<Bill>) =
        PrefsDataSupport.deleteBills(ctx, billsToDelete)

    fun getBills(ctx: Context): List<Bill> = PrefsDataSupport.getBills(ctx)

    // --- White list ---
    fun getAppWhiteList(ctx: Context): Set<String> = PrefsGeneralSupport.getAppWhiteList(ctx)
    fun setAppWhiteList(ctx: Context, list: Set<String>) = PrefsGeneralSupport.setAppWhiteList(ctx, list)

    // --- Currency and exchange rates ---
    fun getActiveCurrencies(ctx: Context): Set<String> = PrefsGeneralSupport.getActiveCurrencies(ctx)
    fun setActiveCurrencies(ctx: Context, currencies: Set<String>) =
        PrefsGeneralSupport.setActiveCurrencies(ctx, currencies)

    fun getExchangeRefreshInterval(ctx: Context): Long =
        PrefsGeneralSupport.getExchangeRefreshInterval(ctx)
    fun setExchangeRefreshInterval(ctx: Context, interval: Long) =
        PrefsGeneralSupport.setExchangeRefreshInterval(ctx, interval)

    // --- Display settings ---
    fun isShowAiText(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiText(ctx)
    fun setShowAiText(ctx: Context, show: Boolean) = PrefsDisplaySupport.setShowAiText(ctx, show)

    fun isShowAiVoice(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiVoice(ctx)
    fun setShowAiVoice(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowAiVoice(ctx, show)
    fun isShowAiImage(ctx: Context): Boolean = PrefsDisplaySupport.isShowAiImage(ctx)
    fun setShowAiImage(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowAiImage(ctx, show)
    fun isShowScreenAccounting(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowScreenAccounting(ctx)
    fun setShowScreenAccounting(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowScreenAccounting(ctx, show)

    fun isShowMultiCurrency(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowMultiCurrency(ctx)
    fun setShowMultiCurrency(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowMultiCurrency(ctx, show)

    fun isShowHomeTrendCard(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowHomeTrendCard(ctx)
    fun setShowHomeTrendCard(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowHomeTrendCard(ctx, show)

    fun isMultiBillEnabled(ctx: Context): Boolean = PrefsDisplaySupport.isMultiBillEnabled(ctx)
    fun setMultiBillEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setMultiBillEnabled(ctx, enabled)

    fun isMultiBillNotSync(ctx: Context): Boolean = PrefsDisplaySupport.isMultiBillNotSync(ctx)
    fun setMultiBillNotSync(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setMultiBillNotSync(ctx, enabled)

    fun isMultiBillFastMode(ctx: Context): Boolean = PrefsDisplaySupport.isMultiBillFastMode(ctx)
    fun setMultiBillFastMode(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setMultiBillFastMode(ctx, enabled)

    fun isShowBookEntry(ctx: Context): Boolean = PrefsDisplaySupport.isShowBookEntry(ctx)
    fun setShowBookEntry(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBookEntry(ctx, show)

    fun getOcrMode(ctx: Context): Int = PrefsDisplaySupport.getOcrMode(ctx)
    fun setOcrMode(ctx: Context, mode: Int) = PrefsDisplaySupport.setOcrMode(ctx, mode)
    fun getReceiptLangMode(ctx: Context): Int = PrefsDisplaySupport.getReceiptLangMode(ctx)
    fun setReceiptLangMode(ctx: Context, mode: Int) =
        PrefsDisplaySupport.setReceiptLangMode(ctx, mode)
    fun isSaveOcrDebugEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isSaveOcrDebugEnabled(ctx)
    fun setSaveOcrDebugEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setSaveOcrDebugEnabled(ctx, enabled)
    fun isAmountGroupingEnabled(ctx: Context): Boolean =
        PrefsDisplaySupport.isAmountGroupingEnabled(ctx)
    fun setAmountGroupingEnabled(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setAmountGroupingEnabled(ctx, enabled)
    fun isShowBillCategoryIcon(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowBillCategoryIcon(ctx)
    fun setShowBillCategoryIcon(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBillCategoryIcon(ctx, show)
    fun isShowBillFullCategory(ctx: Context): Boolean =
        PrefsDisplaySupport.isShowBillFullCategory(ctx)
    fun setShowBillFullCategory(ctx: Context, show: Boolean) =
        PrefsDisplaySupport.setShowBillFullCategory(ctx, show)
    fun isBillRemarkPriority(ctx: Context): Boolean =
        PrefsDisplaySupport.isBillRemarkPriority(ctx)
    fun setBillRemarkPriority(ctx: Context, enabled: Boolean) =
        PrefsDisplaySupport.setBillRemarkPriority(ctx, enabled)

    fun addOcrDebugRecord(ctx: Context, text: String, source: String = "local_ocr_before_ai") =
        PrefsAiSupport.addOcrDebugRecord(ctx, text, source)

    fun getOcrDebugRecords(ctx: Context): List<OcrDebugRecord> =
        PrefsAiSupport.getOcrDebugRecords(ctx)

    fun clearOcrDebugRecords(ctx: Context) = PrefsAiSupport.clearOcrDebugRecords(ctx)

    fun getAiRules(ctx: Context): List<AiRule> = PrefsAiSupport.getAiRules(ctx)

    fun saveAiRules(ctx: Context, rules: List<AiRule>) = PrefsAiSupport.saveAiRules(ctx, rules)

    // --- Asset management ---
    fun getAssets(ctx: Context): List<Asset> = PrefsDataSupport.getAssets(ctx)

    fun saveAssets(ctx: Context, assets: List<Asset>) = PrefsDataSupport.saveAssets(ctx, assets)

    // --- Category management ---
    fun getCategories(ctx: Context, type: Int): MutableList<CategoryNode> =
        PrefsDataSupport.getCategories(ctx, type)

    fun loadDefaultFromRaw(ctx: Context, type: Int): MutableList<CategoryNode> =
        PrefsDataSupport.loadDefaultFromRaw(ctx, type)

    fun loadAssetsFromRaw(ctx: Context): List<BuiltInCategory> =
        PrefsDataSupport.loadAssetsFromRaw(ctx)

    fun saveCategories(ctx: Context, type: Int, list: List<CategoryNode>) =
        PrefsDataSupport.saveCategories(ctx, type, list)

    fun deleteCategory(ctx: Context, type: Int, name: String) =
        PrefsDataSupport.deleteCategory(ctx, type, name)

    // --- Serialization helpers ---
    fun serializeAssetList(assets: List<Asset>): JSONArray =
        PrefsDataSupport.serializeAssetList(assets)

    fun serializeCategoryList(list: List<CategoryNode>): JSONArray =
        PrefsDataSupport.serializeCategoryList(list)

    fun importAll(ctx: Context, root: JSONObject) = PrefsBackupSupport.importAll(ctx, root)

    // --- Flip sensitivity ---
    fun getFlipSensitivity(ctx: Context): Int = PrefsGeneralSupport.getFlipSensitivity(ctx)
    fun setFlipSensitivity(ctx: Context, level: Int) =
        PrefsGeneralSupport.setFlipSensitivity(ctx, level)

    fun getFlipDuration(ctx: Context): Long = PrefsGeneralSupport.getFlipDuration(ctx)
    fun setFlipDuration(ctx: Context, duration: Long) =
        PrefsGeneralSupport.setFlipDuration(ctx, duration)

    // --- Quick gesture master switch ---
    fun isQuickGestureEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isQuickGestureEnabled(ctx)
    fun setQuickGestureEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setQuickGestureEnabled(ctx, enabled)

    // --- Double tap detection ---
    fun isDoubleTapEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isDoubleTapEnabled(ctx)
    fun setDoubleTapEnabled(ctx: Context, enabled: Boolean) =
        PrefsGeneralSupport.setDoubleTapEnabled(ctx, enabled)
    fun hasSeenDoubleTapGuide(ctx: Context): Boolean = PrefsGeneralSupport.hasSeenDoubleTapGuide(ctx)
    fun setDoubleTapGuideSeen(ctx: Context) = PrefsGeneralSupport.setDoubleTapGuideSeen(ctx)

    // --- Tap back settings ---
    fun getTapModel(ctx: Context): String = PrefsGeneralSupport.getTapModel(ctx)
    fun setTapModel(ctx: Context, model: String) = PrefsGeneralSupport.setTapModel(ctx, model)
    fun getTapSensitivityLevel(ctx: Context): Int = PrefsGeneralSupport.getTapSensitivityLevel(ctx)
    fun setTapSensitivityLevel(ctx: Context, level: Int) = PrefsGeneralSupport.setTapSensitivityLevel(ctx, level)
    fun isTapNnapiLowPower(ctx: Context): Boolean = PrefsGeneralSupport.isTapNnapiLowPower(ctx)
    fun setTapNnapiLowPower(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapNnapiLowPower(ctx, enabled)
    fun isTapTripleEnabled(ctx: Context): Boolean = PrefsGeneralSupport.isTapTripleEnabled(ctx)
    fun setTapTripleEnabled(ctx: Context, enabled: Boolean) = PrefsGeneralSupport.setTapTripleEnabled(ctx, enabled)
    fun getTapActionDouble(ctx: Context): String = PrefsGeneralSupport.getTapActionDouble(ctx)
    fun setTapActionDouble(ctx: Context, actionId: String) = PrefsGeneralSupport.setTapActionDouble(ctx, actionId)
    fun getTapActionTriple(ctx: Context): String = PrefsGeneralSupport.getTapActionTriple(ctx)
    fun setTapActionTriple(ctx: Context, actionId: String) = PrefsGeneralSupport.setTapActionTriple(ctx, actionId)

    // --- Custom sensitivity ---
    fun isUseCustomSensitivity(ctx: Context): Boolean =
        PrefsGeneralSupport.isUseCustomSensitivity(ctx)
    fun setUseCustomSensitivity(ctx: Context, use: Boolean) =
        PrefsGeneralSupport.setUseCustomSensitivity(ctx, use)

    fun getCustomGThreshold(ctx: Context): Float = PrefsGeneralSupport.getCustomGThreshold(ctx)
    fun setCustomGThreshold(ctx: Context, g: Float) =
        PrefsGeneralSupport.setCustomGThreshold(ctx, g)

    fun getCustomMaxDuration(ctx: Context): Long = PrefsGeneralSupport.getCustomMaxDuration(ctx)
    fun setCustomMaxDuration(ctx: Context, duration: Long) =
        PrefsGeneralSupport.setCustomMaxDuration(ctx, duration)

    // --- White list serialization helpers ---
    fun serializeWhiteList(set: Set<String>): String = PrefsGeneralSupport.serializeWhiteList(set)

    fun importWhiteList(ctx: Context, jsonStr: String) =
        PrefsGeneralSupport.importWhiteList(ctx, jsonStr)

    fun serializeSettings(ctx: Context): String = PrefsBackupSupport.serializeSettings(ctx)

    // --- AI correction settings ---
    fun isAiPromptCorrectionEnabled(context: Context): Boolean =
        PrefsAiSupport.isAiPromptCorrectionEnabled(context)

    fun setAiPromptCorrectionEnabled(context: Context, enabled: Boolean) =
        PrefsAiSupport.setAiPromptCorrectionEnabled(context, enabled)

    fun isLocalRuleOverrideEnabled(context: Context): Boolean =
        PrefsAiSupport.isLocalRuleOverrideEnabled(context)

    fun setLocalRuleOverrideEnabled(context: Context, enabled: Boolean) =
        PrefsAiSupport.setLocalRuleOverrideEnabled(context, enabled)

    // --- AI chat settings ---
    fun isShowAiChatEntry(ctx: Context): Boolean = PrefsChatSupport.isShowAiChatEntry(ctx)
    fun setShowAiChatEntry(ctx: Context, show: Boolean) =
        PrefsChatSupport.setShowAiChatEntry(ctx, show)

    /** Accounting entry mode: traditional input or AI chat. */
    fun getAiEntryMode(ctx: Context): Int = PrefsChatSupport.getAiEntryMode(ctx)
    fun setAiEntryMode(ctx: Context, mode: Int) = PrefsChatSupport.setAiEntryMode(ctx, mode)

    fun getAiChatName(ctx: Context): String = PrefsChatSupport.getAiChatName(ctx)
    fun setAiChatName(ctx: Context, name: String) = PrefsChatSupport.setAiChatName(ctx, name)

    fun getAiChatIdentity(ctx: Context): String = PrefsChatSupport.getAiChatIdentity(ctx)
    fun setAiChatIdentity(ctx: Context, identity: String) =
        PrefsChatSupport.setAiChatIdentity(ctx, identity)

    fun getUserChatName(ctx: Context): String = PrefsChatSupport.getUserChatName(ctx)
    fun setUserChatName(ctx: Context, name: String) = PrefsChatSupport.setUserChatName(ctx, name)
    fun getUserProfileDesc(ctx: Context): String = PrefsChatSupport.getUserProfileDesc(ctx)
    fun setUserProfileDesc(ctx: Context, text: String) = PrefsChatSupport.setUserProfileDesc(ctx, text)

    fun getAiChatAvatarPath(ctx: Context): String = PrefsChatSupport.getAiChatAvatarPath(ctx)
    fun setAiChatAvatarPath(ctx: Context, path: String) =
        PrefsChatSupport.setAiChatAvatarPath(ctx, path)

    fun getUserChatAvatarPath(ctx: Context): String = PrefsChatSupport.getUserChatAvatarPath(ctx)
    fun setUserChatAvatarPath(ctx: Context, path: String) =
        PrefsChatSupport.setUserChatAvatarPath(ctx, path)

    fun getAiChatBgPath(ctx: Context): String = PrefsChatSupport.getAiChatBgPath(ctx)
    fun setAiChatBgPath(ctx: Context, path: String) = PrefsChatSupport.setAiChatBgPath(ctx, path)

    fun getAiChatModel(ctx: Context): String = PrefsChatSupport.getAiChatModel(ctx)
    fun setAiChatModel(ctx: Context, value: String) = PrefsChatSupport.setAiChatModel(ctx, value)

    fun getAiChatReplyStyle(ctx: Context): String = PrefsChatSupport.getAiChatReplyStyle(ctx)
    fun setAiChatReplyStyle(ctx: Context, value: String) =
        PrefsChatSupport.setAiChatReplyStyle(ctx, value)
    fun getAiChatReplyStyleCustomPrompt(ctx: Context): String =
        PrefsChatSupport.getAiChatReplyStyleCustomPrompt(ctx)
    fun setAiChatReplyStyleCustomPrompt(ctx: Context, value: String) =
        PrefsChatSupport.setAiChatReplyStyleCustomPrompt(ctx, value)
    fun getAiChatModelAudioSupport(ctx: Context, model: String): Boolean? =
        PrefsChatSupport.getAiChatModelAudioSupport(ctx, model)
    fun setAiChatModelAudioSupport(ctx: Context, model: String, supported: Boolean) =
        PrefsChatSupport.setAiChatModelAudioSupport(ctx, model, supported)

    fun getAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String): String =
        PrefsChatSupport.getAiChatSessionTitle(ctx, bookName, conversationId)

    fun setAiChatSessionTitle(ctx: Context, bookName: String, conversationId: String, title: String) =
        PrefsChatSupport.setAiChatSessionTitle(ctx, bookName, conversationId, title)

    fun serializeSettingsModules(ctx: Context): Map<String, String> =
        PrefsBackupSupport.serializeSettingsModules(ctx)

}
