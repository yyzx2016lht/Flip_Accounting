package com.taostudio.tapaccounting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelCapabilitiesTest {

    @Test
    fun mimoProviderKeepsDirectAudioEvenWhenCachedProbeFailed() {
        assertTrue(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_MIMO,
                model = "mimo-v2.5",
                cachedSupport = false
            )
        )
    }

    @Test
    fun mimoNamedModelKeepsDirectAudioEvenOnOtherProvider() {
        assertTrue(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_SILICONFLOW,
                model = "vendor/mimo-v2.5",
                cachedSupport = false
            )
        )
    }

    @Test
    fun qwenDefaultsToDirectAudioWhenNoProbeCacheExists() {
        assertTrue(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_QWEN,
                model = "qwen3.5-flash",
                cachedSupport = null
            )
        )
    }

    @Test
    fun nonDefaultProviderRespectsProbeCache() {
        assertTrue(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_SILICONFLOW,
                model = "custom-audio-model",
                cachedSupport = true
            )
        )
        assertFalse(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_SILICONFLOW,
                model = "custom-audio-model",
                cachedSupport = false
            )
        )
    }

    @Test
    fun blankModelNeverUsesDirectAudio() {
        assertFalse(
            AiModelCapabilities.resolveDirectAudioInput(
                providerId = AiProviderRegistry.PROVIDER_MIMO,
                model = "",
                cachedSupport = true
            )
        )
    }
}
