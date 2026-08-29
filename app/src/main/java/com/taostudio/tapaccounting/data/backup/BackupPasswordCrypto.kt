package com.taostudio.tapaccounting.data.backup

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class BackupPasswordKdfParameters(
    val salt: ByteArray,
    val iterations: Int
) {
    fun requireValid() {
        require(salt.size == BackupPasswordCrypto.SALT_BYTES) { "备份密码盐值长度无效" }
        require(iterations in BackupPasswordCrypto.MIN_ITERATIONS..BackupPasswordCrypto.MAX_ITERATIONS) {
            "备份密码派生轮数无效"
        }
    }
}

data class BackupPasswordKeyMaterial(
    val keyBytes: ByteArray,
    val parameters: BackupPasswordKdfParameters
) {
    fun requireValid() {
        require(keyBytes.size == BackupPasswordCrypto.KEY_BYTES) { "备份密钥长度无效" }
        parameters.requireValid()
    }
}

/** Pure-Java password derivation shared by Android storage, envelope code and unit tests. */
object BackupPasswordCrypto {
    const val MIN_PIN_DIGITS = 8
    const val MAX_PIN_DIGITS = 12
    const val SALT_BYTES = 16
    const val KEY_BYTES = 32
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 2_000_000

    private val pinPattern = Regex("^\\d{$MIN_PIN_DIGITS,$MAX_PIN_DIGITS}$")

    fun requireValidPin(pin: String) {
        require(pinPattern.matches(pin)) { "备份密码必须是 8 至 12 位数字" }
    }

    fun create(pin: String, random: SecureRandom = SecureRandom()): BackupPasswordKeyMaterial {
        val parameters = BackupPasswordKdfParameters(
            salt = ByteArray(SALT_BYTES).also(random::nextBytes),
            iterations = DEFAULT_ITERATIONS
        )
        return BackupPasswordKeyMaterial(derive(pin, parameters), parameters)
    }

    fun derive(pin: String, parameters: BackupPasswordKdfParameters): ByteArray {
        requireValidPin(pin)
        parameters.requireValid()
        val passwordBytes = pin.toByteArray(Charsets.UTF_8)
        val firstBlock = parameters.salt.copyOf(parameters.salt.size + 4).apply {
            this[size - 1] = 1 // PBKDF2 block index, big-endian INT_32_BE(1)
        }
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        }
        var current = mac.doFinal(firstBlock)
        var next = ByteArray(mac.macLength)
        val result = current.copyOf()
        return try {
            for (round in 1 until parameters.iterations) {
                mac.update(current)
                mac.doFinal(next, 0)
                for (index in result.indices) result[index] = (result[index].toInt() xor next[index].toInt()).toByte()
                val swap = current
                current = next
                next = swap
            }
            result.also { require(it.size == KEY_BYTES) { "备份密钥派生失败" } }
        } finally {
            passwordBytes.fill(0)
            firstBlock.fill(0)
            current.fill(0)
            next.fill(0)
        }
    }
}
