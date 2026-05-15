package com.taostudio.tapaccounting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PrefsAiSupport {
    private const val PREFS_NAME = "flip_prefs"
    private const val KEY_AI_KEY = "ai_api_key"
    // Legacy text-model key kept for backward compatibility.
    private const val KEY_AI_MODEL = "ai_model_id"
    private const val KEY_AI_MULTI_MODEL = "ai_multi_model_id"
    private const val KEY_AI_MODIFY_MODEL = "ai_modify_model_id"
    private const val KEY_AI_CATEGORY_REFINE_MODEL = "ai_category_refine_model_id"
    private const val KEY_AI_ROUTER_MODEL = "ai_router_model_id"
    private const val KEY_AI_LLM_ROUTER_ENABLED = "ai_llm_router_enabled"
    private const val KEY_AI_QUERY_MODEL = "ai_query_model_id"
    private const val KEY_AI_RULE_MODEL = "ai_rule_model_id"
    private const val KEY_AI_MODELS_CACHE = "ai_models_cache"
    private const val KEY_AI_PROVIDER = "ai_provider"
    private const val KEY_AI_URL = "ai_api_url"
    private const val KEY_AI_RECEIPT_MODEL = "ai_receipt_model_id"
    private const val KEY_AI_RECEIPT_VISION_MODEL = "ai_receipt_vision_model_id"
    private const val KEY_AI_SCREEN_MODEL = "ai_screen_model_id"
    private const val KEY_SCREEN_VISION_SUPPORTED_MODELS = "screen_vision_supported_models"
    private const val KEY_RECEIPT_OCR_REFINE_ENABLED = "receipt_ocr_refine_enabled"
    private const val KEY_AI_RECEIPT_OCR_REFINE_MODEL = "ai_receipt_ocr_refine_model_id"
    private const val KEY_AI_SPEECH_MODEL = "ai_speech_model_id"
    private const val KEY_AI_ENABLE_THINKING = "ai_enable_thinking"
    private const val KEY_AI_THINKING_MULTI_BILL = "ai_thinking_multi_bill"
    private const val KEY_AI_THINKING_MODIFY_BILL = "ai_thinking_modify_bill"
    private const val KEY_AI_THINKING_VISION = "ai_thinking_vision"
    private const val KEY_AI_THINKING_CATEGORY_REFINE = "ai_thinking_category_refine"
    private const val KEY_AI_QUERY_ENABLED = "ai_query_enabled"
    private const val KEY_AI_RULES = "ai_rules_v1"
    private const val KEY_OCR_DEBUG_RECORDS = "ocr_debug_records_v1"
    private const val OCR_DEBUG_MAX_RECORDS = 20
    private const val OCR_DEBUG_MAX_TEXT_LEN = 12000

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val DEFAULT_MAIN_MODEL = "Qwen/Qwen3-14B"
    private const val DEFAULT_ROUTER_MODEL = "Qwen/Qwen3-8B"
    private const val DEFAULT_VISION_MODEL = "Qwen/Qwen3-VL-30B-A3B-Instruct"
    private const val DEFAULT_SPEECH_MODEL = "FunAudioLLM/SenseVoiceSmall"

    fun getAiKey(ctx: Context): String = prefs(ctx).getString(KEY_AI_KEY, "") ?: ""
    fun setAiKey(ctx: Context, key: String) = prefs(ctx).edit().putString(KEY_AI_KEY, key).apply()

    fun getAiModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MODEL, DEFAULT_MAIN_MODEL) ?: DEFAULT_MAIN_MODEL
    fun setAiModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODEL, value).apply()

    fun getAiMultiModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_MULTI_MODEL, "") ?: getAiModel(ctx)
    fun setAiMultiModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MULTI_MODEL, value).apply()

    fun getAiModifyModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_MODIFY_MODEL, "") ?: "").ifBlank { getAiMultiModel(ctx) }
    fun setAiModifyModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_MODIFY_MODEL, value).apply()

    fun getAiCategoryRefineModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_CATEGORY_REFINE_MODEL, "") ?: "").ifBlank { getAiMultiModel(ctx) }
    fun setAiCategoryRefineModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_CATEGORY_REFINE_MODEL, value).apply()

    // Hidden legacy field. Current product flow no longer exposes router configuration.
    fun getAiRouterModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_ROUTER_MODEL, DEFAULT_ROUTER_MODEL) ?: DEFAULT_ROUTER_MODEL
    fun setAiRouterModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_ROUTER_MODEL, value).apply()
    fun isAiLlmRouterEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_LLM_ROUTER_ENABLED, false)
    fun setAiLlmRouterEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_LLM_ROUTER_ENABLED, enabled).apply()

    // Hidden legacy field. Current product flow no longer exposes query planning configuration.
    fun getAiQueryModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_QUERY_MODEL, "") ?: "").ifBlank { getAiRouterModel(ctx) }
    fun setAiQueryModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_QUERY_MODEL, value).apply()

    fun getAiRuleModel(ctx: Context): String =
        prefs(ctx).getString(KEY_AI_RULE_MODEL, "") ?: getAiModel(ctx)
    fun setAiRuleModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RULE_MODEL, value).apply()

    fun getAiReceiptModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_RECEIPT_MODEL, "") ?: "").ifBlank { getAiModel(ctx) }
    fun setAiReceiptModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_MODEL, value).apply()

    fun getAiReceiptVisionModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_RECEIPT_VISION_MODEL, "") ?: "").ifBlank { DEFAULT_VISION_MODEL }
    fun setAiReceiptVisionModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_VISION_MODEL, value).apply()

    // Legacy compatibility field. Current product flow follows the vision model.
    fun getAiScreenModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_SCREEN_MODEL, "") ?: "").ifBlank { getAiReceiptVisionModel(ctx) }
    fun setAiScreenModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_SCREEN_MODEL, value).apply()

    fun isScreenModelVisionSupported(ctx: Context, model: String): Boolean {
        val normalized = model.trim()
        if (normalized.isEmpty()) return false
        return prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet())
            ?.contains(normalized) == true
    }

    fun setScreenModelVisionSupported(ctx: Context, model: String, supported: Boolean) {
        val normalized = model.trim()
        if (normalized.isEmpty()) return
        val set = (prefs(ctx).getStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, emptySet()) ?: emptySet())
            .toMutableSet()
        if (supported) set.add(normalized) else set.remove(normalized)
        prefs(ctx).edit().putStringSet(KEY_SCREEN_VISION_SUPPORTED_MODELS, set).apply()
    }

    fun getAiReceiptOcrRefineModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, "") ?: "").ifBlank { getAiReceiptModel(ctx) }
    fun setAiReceiptOcrRefineModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_RECEIPT_OCR_REFINE_MODEL, value).apply()

    fun getAiSpeechModel(ctx: Context): String =
        (prefs(ctx).getString(KEY_AI_SPEECH_MODEL, "") ?: "").ifBlank {
            DEFAULT_SPEECH_MODEL
        }
    fun setAiSpeechModel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_SPEECH_MODEL, value).apply()

    fun getAiEnableThinking(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_ENABLE_THINKING, false)
    fun setAiEnableThinking(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_ENABLE_THINKING, enabled).apply()

    fun isAiThinkingMultiBillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_MULTI_BILL, false)
    fun setAiThinkingMultiBillEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_MULTI_BILL, enabled).apply()

    fun isAiThinkingModifyBillEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_MODIFY_BILL, false)
    fun setAiThinkingModifyBillEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_MODIFY_BILL, enabled).apply()

    fun isAiThinkingVisionEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_VISION, false)
    fun setAiThinkingVisionEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_VISION, enabled).apply()

    fun isAiThinkingCategoryRefineEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_THINKING_CATEGORY_REFINE, false)
    fun setAiThinkingCategoryRefineEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_THINKING_CATEGORY_REFINE, enabled).apply()

    // Hidden legacy flag kept for backward-compatible restore only.
    fun isAiQueryEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AI_QUERY_ENABLED, false)
    fun setAiQueryEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AI_QUERY_ENABLED, enabled).apply()

    fun getAiModelsCache(ctx: Context): List<String> =
        (prefs(ctx).getStringSet(KEY_AI_MODELS_CACHE, null) ?: emptySet()).toList()
    fun setAiModelsCache(ctx: Context, models: List<String>) =
        prefs(ctx).edit().putStringSet(KEY_AI_MODELS_CACHE, models.toSet()).apply()

    fun defaultSpeechModelForUrl(rawBaseUrl: String): String {
        val baseUrl = rawBaseUrl.lowercase()
        return when {
            "siliconflow" in baseUrl -> "FunAudioLLM/SenseVoiceSmall"
            else -> "whisper-1"
        }
    }

    fun getAiProvider(ctx: Context): String = "硅基流动"
    fun setAiProvider(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_AI_PROVIDER, "硅基流动").apply()

    fun getAiUrl(ctx: Context): String =
        "https://api.siliconflow.cn"
    fun setAiUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_AI_URL, "https://api.siliconflow.cn").apply()

    fun isReceiptOcrRefineEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, false)
    fun setReceiptOcrRefineEnabled(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_RECEIPT_OCR_REFINE_ENABLED, enabled).apply()

    fun addOcrDebugRecord(ctx: Context, text: String, source: String = "local_ocr_before_ai") {
        if (text.isBlank()) return
        val trimmedText = if (text.length > OCR_DEBUG_MAX_TEXT_LEN) {
            text.take(OCR_DEBUG_MAX_TEXT_LEN) + "\n...[TRUNCATED]"
        } else {
            text
        }

        val records = getOcrDebugRecords(ctx).toMutableList()
        records.add(0, OcrDebugRecord(System.currentTimeMillis(), source, trimmedText))
        if (records.size > OCR_DEBUG_MAX_RECORDS) {
            records.subList(OCR_DEBUG_MAX_RECORDS, records.size).clear()
        }

        val arr = JSONArray()
        records.forEach { item ->
            val obj = JSONObject()
            obj.put("timestamp", item.timestamp)
            obj.put("source", item.source)
            obj.put("text", item.text)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_OCR_DEBUG_RECORDS, arr.toString()).apply()
    }

    fun getOcrDebugRecords(ctx: Context): List<OcrDebugRecord> {
        val raw = prefs(ctx).getString(KEY_OCR_DEBUG_RECORDS, null) ?: return emptyList()
        val list = mutableListOf<OcrDebugRecord>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    OcrDebugRecord(
                        timestamp = obj.optLong("timestamp", 0L),
                        source = obj.optString("source", "local_ocr_before_ai"),
                        text = obj.optString("text", "")
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun clearOcrDebugRecords(ctx: Context) {
        prefs(ctx).edit().remove(KEY_OCR_DEBUG_RECORDS).apply()
    }

    fun getAiRules(ctx: Context): List<AiRule> {
        val jsonStr = prefs(ctx).getString(KEY_AI_RULES, null) ?: return emptyList()
        val list = mutableListOf<AiRule>()
        runCatching {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AiRule(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        keyword = obj.getString("keyword"),
                        targetType = if (obj.has("targetType") && !obj.isNull("targetType")) obj.getInt("targetType") else null,
                        targetCategory = if (obj.has("targetCategory") && !obj.isNull("targetCategory")) obj.getString("targetCategory") else null,
                        targetAccount1 = if (obj.has("targetAccount1") && !obj.isNull("targetAccount1")) obj.getString("targetAccount1") else null,
                        targetAccount2 = if (obj.has("targetAccount2") && !obj.isNull("targetAccount2")) obj.getString("targetAccount2") else null,
                        isEnabled = obj.optBoolean("isEnabled", true)
                    )
                )
            }
        }.onFailure { it.printStackTrace() }
        return list
    }

    fun saveAiRules(ctx: Context, rules: List<AiRule>) {
        val arr = JSONArray()
        rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("id", rule.id)
            obj.put("keyword", rule.keyword)
            if (rule.targetType != null) obj.put("targetType", rule.targetType)
            if (rule.targetCategory != null) obj.put("targetCategory", rule.targetCategory)
            if (rule.targetAccount1 != null) obj.put("targetAccount1", rule.targetAccount1)
            if (rule.targetAccount2 != null) obj.put("targetAccount2", rule.targetAccount2)
            obj.put("isEnabled", rule.isEnabled)
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY_AI_RULES, arr.toString()).apply()
    }

    fun isAiPromptCorrectionEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("enable_ai_prompt_correction", true)
    }

    fun setAiPromptCorrectionEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable_ai_prompt_correction", enabled).apply()
    }

    fun isLocalRuleOverrideEnabled(context: Context): Boolean {
        return prefs(context).getBoolean("enable_local_rule_override", true)
    }

    fun setLocalRuleOverrideEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enable_local_rule_override", enabled).apply()
    }
}

