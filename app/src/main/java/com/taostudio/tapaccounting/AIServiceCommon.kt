package com.taostudio.tapaccounting

import android.content.Context
import android.util.Base64
import com.google.gson.JsonObject
import org.json.JSONObject
import retrofit2.HttpException
import java.io.File
import java.util.Locale

internal fun normalizeBaseUrl(url: String): String {
    var baseUrl = url
    if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
    if (!baseUrl.endsWith("/")) baseUrl += "/"
    return baseUrl
}

internal fun shortenForModel(text: String, maxChars: Int, preserveTail: Boolean = true): String {
    if (text.length <= maxChars) return text
    if (maxChars <= 200) return text.take(maxChars)
    val head = (maxChars * 0.7).toInt()
    val tail = if (preserveTail) maxChars - head - 32 else 0
    return if (preserveTail && tail > 0) {
        text.take(head) + "\n\n[内容过长，已省略中间部分]\n\n" + text.takeLast(tail)
    } else {
        text.take(maxChars) + "\n\n[内容过长，已截断]"
    }
}

internal fun detectSpeechAudioMimeType(audioFile: File): String = when (audioFile.extension.lowercase(Locale.ROOT)) {
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    else -> "application/octet-stream"
}

internal fun detailedHttpError(e: Exception): String {
    if (e is HttpException) {
        val code = e.code()
        val body = runCatching { e.response()?.errorBody()?.string().orEmpty() }.getOrDefault("")
        return if (body.isNotBlank()) "HTTP $code, errorBody=$body" else "HTTP $code, message=${e.message()}"
    }
    return e.message ?: e.javaClass.simpleName
}

internal fun JSONObject.optNullableString(name: String): String? {
    if (!has(name) || isNull(name)) return null
    return optString(name, "").trim().takeIf { it.isNotBlank() && it != "null" }
}

internal fun JSONObject.optNullableDouble(name: String): Double? {
    if (!has(name) || isNull(name)) return null
    return runCatching { getDouble(name) }.getOrNull()
}

internal fun cleanJsonString(input: String): String {
    var s = input.trim()
    if (s.startsWith("```json")) s = s.removePrefix("```json")
    if (s.startsWith("```")) s = s.removePrefix("```")
    if (s.endsWith("```")) s = s.removeSuffix("```")
    return s.trim()
}

internal fun adaptChatRequestForProvider(
    providerId: String,
    requestJson: JsonObject
): JsonObject {
    val adapted = requestJson.deepCopy()
    val thinkingEnabled = adapted.remove("enable_thinking")?.asBoolean == true
    val model = adapted.get("model")?.asString.orEmpty()

    when (providerId) {
        AiProviderRegistry.PROVIDER_DEEPSEEK -> {
            adapted.add("thinking", JsonObject().apply {
                addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
            })
        }

        AiProviderRegistry.PROVIDER_MIMO -> {
            if (!model.endsWith("-asr")) {
                adapted.add("thinking", JsonObject().apply {
                    addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                })
            }
        }

        AiProviderRegistry.PROVIDER_KIMI -> {
            if (model.startsWith("kimi-k2.5") || model.startsWith("kimi-k2.6")) {
                adapted.add("thinking", JsonObject().apply {
                    addProperty("type", if (thinkingEnabled) "enabled" else "disabled")
                })
                // 不覆盖调用方指定的 temperature，避免记账场景（temperature=0.3）被改为 1.0
            }
        }

        else -> if (thinkingEnabled) {
            adapted.addProperty("enable_thinking", true)
        }
    }

    if (adapted.get("stream")?.asBoolean == true &&
        providerId in setOf(
            AiProviderRegistry.PROVIDER_DEEPSEEK,
            AiProviderRegistry.PROVIDER_QWEN
        )
    ) {
        adapted.add("stream_options", JsonObject().apply {
            addProperty("include_usage", true)
        })
    }
    return adapted
}

internal fun extractFirstJsonObjectText(input: String): String? {
    val start = input.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until input.length) {
        val ch = input[i]
        if (escaped) {
            escaped = false
            continue
        }
        when (ch) {
            '\\' -> if (inString) escaped = true
            '"' -> inString = !inString
            '{' -> if (!inString) depth++
            '}' -> if (!inString) {
                depth--
                if (depth == 0) {
                    return input.substring(start, i + 1).trim()
                }
            }
        }
    }
    return null
}

internal fun buildProbeAudioBase64(): String {
    val wavBytes = byteArrayOf(
        82, 73, 70, 70, 40, 0, 0, 0, 87, 65, 86, 69, 102, 109, 116, 32,
        16, 0, 0, 0, 1, 0, 1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2, 0, 16, 0,
        100, 97, 116, 97, 4, 0, 0, 0, 0, 0, 0, 0
    )
    return Base64.encodeToString(wavBytes, Base64.NO_WRAP)
}

internal fun buildProbeImageBase64(ctx: Context): String {
    val bytes = ctx.resources.openRawResource(R.drawable.ic_screenshot).use { it.readBytes() }
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

