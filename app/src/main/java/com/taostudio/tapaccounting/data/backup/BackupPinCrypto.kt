package com.taostudio.tapaccounting.data.backup

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 备份中 API Key 的 PIN 保护。
 * - 使用 4 位数字 PIN
 * - PBKDF2 派生密钥 + AES-GCM 加密
 */
object BackupPinCrypto {
    private const val FIELD_API_KEY = "ai_api_key_v1"
    private const val FIELD_API_KEY_ENC = "ai_api_key_enc_v1"

    private const val ITERATIONS = 60_000
    private const val KEY_BITS = 256
    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12

    private val pinRegex = Regex("^\\d{4}$")

    fun hasEncryptedApi(settings: JSONObject): Boolean {
        return settings.has(FIELD_API_KEY_ENC)
    }

    /**
     * 将 settings 中的 ai_api_key_v1 替换为加密字段 ai_api_key_enc_v1。
     */
    fun encryptApiKeyInSettings(settings: JSONObject, pin: String): JSONObject {
        requireValidPin(pin)

        val plainApiKey = settings.optString(FIELD_API_KEY, "")
        if (plainApiKey.isBlank()) return settings

        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(plainApiKey.toByteArray(StandardCharsets.UTF_8))

        val payload = JSONObject()
            .put("v", 1)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iter", ITERATIONS)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(cipherText, Base64.NO_WRAP))

        settings.remove(FIELD_API_KEY)
        settings.put(FIELD_API_KEY_ENC, payload)
        return settings
    }

    /**
     * 若 settings 中存在加密 API Key，则尝试用 PIN 解密恢复 ai_api_key_v1。
     */
    fun decryptApiKeyInSettings(settings: JSONObject, pin: String): JSONObject {
        requireValidPin(pin)

        if (!settings.has(FIELD_API_KEY_ENC)) return settings

        val payloadAny = settings.get(FIELD_API_KEY_ENC)
        val payload = when (payloadAny) {
            is JSONObject -> payloadAny
            is String -> JSONObject(payloadAny)
            else -> throw IllegalArgumentException("备份中的加密 API Key 格式不受支持")
        }

        val iter = payload.optInt("iter", ITERATIONS)
        val salt = Base64.decode(payload.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP)
        val ct = Base64.decode(payload.getString("ct"), Base64.NO_WRAP)

        return try {
            val key = deriveKey(pin, salt, iter)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plain = cipher.doFinal(ct)
            val apiKey = String(plain, StandardCharsets.UTF_8)

            settings.remove(FIELD_API_KEY_ENC)
            settings.put(FIELD_API_KEY, apiKey)
            settings
        } catch (_: Exception) {
            throw IllegalArgumentException("PIN错误，无法解密备份中的 API Key")
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val encoded = factory.generateSecret(spec).encoded
        return SecretKeySpec(encoded, "AES")
    }

    private fun requireValidPin(pin: String) {
        require(pinRegex.matches(pin)) { "PIN 必须是 4 位数字" }
    }
}

