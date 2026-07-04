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
        val providerId = Prefs.getAiProvider(ctx)
        return resolveDirectAudioInput(
            providerId = providerId,
            model = model,
            cachedSupport = Prefs.getAiChatModelAudioSupport(ctx, model)
        )
    }

    internal fun resolveDirectAudioInput(
        providerId: String,
        model: String,
        cachedSupport: Boolean?
    ): Boolean {
        if (model.isBlank()) return false
        if (providerId == AiProviderRegistry.PROVIDER_MIMO || model.contains("mimo", ignoreCase = true)) {
            return true
        }
        cachedSupport?.let { return it }
        return providerId == AiProviderRegistry.PROVIDER_QWEN
    }

    fun chatMultimodalModel(ctx: Context): String =
        AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveChatModel(ctx) }
}
