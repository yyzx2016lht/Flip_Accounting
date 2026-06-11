package com.taostudio.tapaccounting

import android.content.Context

/**
 * Resolves which model each AI slot should use at request time.
 * Chat defaults to following the main text model unless the user picks otherwise.
 */
object AiModelSlots {

    fun resolveTextModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            return preset.defaultTextModel
        }
        val saved = Prefs.getAiMultiModel(ctx).trim()
        if (saved.isNotEmpty()) return saved
        return preset.defaultTextModel
    }

    fun resolveChatModel(ctx: Context): String {
        val main = resolveTextModel(ctx)
        val explicit = PrefsChatSupport.getAiChatModelRaw(ctx)
        if (explicit.isBlank()) return main
        val cache = Prefs.getAiModelsCache(ctx).map { it.trim() }.filter { it.isNotEmpty() }
        if (cache.isNotEmpty() && explicit !in cache) return main
        return explicit
    }

    fun resolveVisionModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!preset.supportsVision) return ""
        if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            return preset.defaultVisionModel
        }
        return Prefs.getAiReceiptVisionModel(ctx).trim().ifBlank { preset.defaultVisionModel }
    }

    fun resolveSpeechModel(ctx: Context): String {
        val preset = AiProviderRegistry.resolvePreset(ctx)
        if (!preset.supportsCloudSpeech) return ""
        if (!Prefs.isAiManualModelSelectionEnabled(ctx)) {
            return preset.defaultSpeechModel
        }
        return Prefs.getAiSpeechModel(ctx).trim().ifBlank { preset.defaultSpeechModel }
    }

    fun isChatFollowingMainText(ctx: Context): Boolean =
        Prefs.isAiChatModelFollowingMain(ctx)
}
