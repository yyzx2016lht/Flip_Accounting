package com.taostudio.tapaccounting

import android.content.Context

/**
 * Central place for provider/model input capability decisions.
 *
 * User probes stored in Prefs win over provider defaults. Provider defaults are
 * intentionally conservative except for MiMo/Qwen, where the chat-compatible
 * audio/file paths are first-class product flows.
 */
object AiModelCapabilities {

    fun supportsDirectAudioInput(ctx: Context, model: String = AiModelSlots.resolveChatModel(ctx)): Boolean {
        Prefs.getAiChatModelAudioSupport(ctx, model)?.let { return it }
        return when (Prefs.getAiProvider(ctx)) {
            AiProviderRegistry.PROVIDER_MIMO,
            AiProviderRegistry.PROVIDER_QWEN -> model.isNotBlank()
            else -> false
        }
    }

    fun supportsNativeDocumentFiles(ctx: Context): Boolean =
        when (Prefs.getAiProvider(ctx)) {
            AiProviderRegistry.PROVIDER_MIMO,
            AiProviderRegistry.PROVIDER_QWEN,
            AiProviderRegistry.PROVIDER_KIMI -> true
            else -> false
        }

    fun chatMultimodalModel(ctx: Context): String =
        AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveChatModel(ctx) }
}
