package tao.test.flipaccounting.chat.voice

import org.json.JSONObject
import tao.test.flipaccounting.VoicePayload

object VoicePayloadCodec {

    private const val VOICE_PAYLOAD_V2_PREFIX = "__voice_v2__:"

    fun build(audioPath: String, durationSec: Int, transcript: String): String {
        val json = JSONObject().apply {
            put("audioPath", audioPath)
            put("durationSec", durationSec)
            put("transcript", transcript)
        }.toString()
        return VOICE_PAYLOAD_V2_PREFIX + json
    }

    fun parse(content: String): VoicePayload {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            VoicePayload(
                audioPath = obj.optString("audioPath"),
                durationSec = obj.optInt("durationSec", 1).coerceAtLeast(1),
                transcript = obj.optString("transcript")
            )
        } catch (_: Exception) {
            parseLoose(normalized) ?: VoicePayload(transcript = content)
        }
    }

    fun parseStrict(content: String): VoicePayload? {
        val normalized = content.removePrefix(VOICE_PAYLOAD_V2_PREFIX).trim()
        return try {
            val obj = JSONObject(normalized)
            val audioPath = obj.optString("audioPath").trim()
            val durationSec = obj.optInt("durationSec", -1)
            if (audioPath.isBlank() || durationSec <= 0) return null
            VoicePayload(
                audioPath = audioPath,
                durationSec = durationSec,
                transcript = obj.optString("transcript").trim()
            )
        } catch (_: Exception) {
            parseLoose(normalized)
        }
    }

    fun parseLoose(raw: String): VoicePayload? {
        val text = raw.trim()
        if (!text.contains("audioPath", ignoreCase = true)) return null
        val audioPath = Regex("['\\\"]audioPath['\\\"]\\s*:\\s*['\\\"]([^'\\\"]+)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val duration = Regex("['\\\"]durationSec['\\\"]\\s*:\\s*(\\d+)")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        val transcript = Regex("['\\\"]transcript['\\\"]\\s*:\\s*['\\\"]([^'\\\"]*)['\\\"]")
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (audioPath.isBlank() || duration <= 0) return null
        return VoicePayload(
            audioPath = audioPath,
            durationSec = duration.coerceAtLeast(1),
            transcript = transcript
        )
    }
}
