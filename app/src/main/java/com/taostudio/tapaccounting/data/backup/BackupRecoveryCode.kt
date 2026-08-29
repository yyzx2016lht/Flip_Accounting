package com.taostudio.tapaccounting.data.backup

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Arrays

/**
 * A uniformly random 256-bit key used to recover a Backup V2 archive.
 *
 * The human representation uses 52 unambiguous base-32 characters in thirteen
 * groups of four. Parsing is intentionally strict: callers must preserve the
 * uppercase letters and separators shown to the user.
 *
 * The recovery code is never serialized by the Backup V2 envelope.
 */
class BackupRecoveryCode private constructor(private val key: ByteArray) {

    init {
        require(key.size == KEY_BYTES) { "恢复码必须包含 256 位随机数据" }
    }

    /** Returns the canonical, human-copyable representation. */
    fun format(): String = encodeBase32(key).chunked(GROUP_SIZE).joinToString("-")

    internal fun copyKeyBytes(): ByteArray = key.copyOf()

    override fun equals(other: Any?): Boolean =
        other is BackupRecoveryCode && Arrays.equals(key, other.key)

    override fun hashCode(): Int = Arrays.hashCode(key)

    companion object {
        const val KEY_BITS = 256
        private const val KEY_BYTES = KEY_BITS / 8
        private const val ENCODED_CHARACTERS = 52
        private const val GROUP_SIZE = 4
        private const val GROUP_COUNT = ENCODED_CHARACTERS / GROUP_SIZE

        // No I/O or 0/1, reducing common transcription ambiguity.
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val alphabetIndex = IntArray(128) { -1 }.also { index ->
            ALPHABET.forEachIndexed { value, char -> index[char.code] = value }
        }

        /** Generates a new recovery code with 256 bits from [SecureRandom]. */
        fun generate(random: SecureRandom = SecureRandom()): BackupRecoveryCode =
            BackupRecoveryCode(ByteArray(KEY_BYTES).also(random::nextBytes))

        /**
         * Parses only the canonical `XXXX-...-XXXX` form (13 groups).
         * Lowercase, missing separators, ambiguous characters, and non-canonical
         * base-32 padding are rejected instead of being silently corrected.
         */
        fun parse(value: String): BackupRecoveryCode {
            val groups = value.split('-')
            require(groups.size == GROUP_COUNT && groups.all { it.length == GROUP_SIZE }) {
                "恢复码格式无效，应为 13 组、每组 4 个字符"
            }
            val compact = groups.joinToString("")
            require(compact.length == ENCODED_CHARACTERS && compact.all(::isAlphabetCharacter)) {
                "恢复码包含无效字符"
            }
            val decoded = decodeBase32(compact)
            val result = BackupRecoveryCode(decoded)
            require(result.format() == value) { "恢复码不是规范编码" }
            return result
        }

        /** Useful when importing a key from a QR code or another secure channel. */
        fun fromBytes(bytes: ByteArray): BackupRecoveryCode =
            BackupRecoveryCode(bytes.copyOf())

        private fun isAlphabetCharacter(char: Char): Boolean =
            char.code < alphabetIndex.size && alphabetIndex[char.code] >= 0

        private fun encodeBase32(bytes: ByteArray): String {
            val result = StringBuilder(ENCODED_CHARACTERS)
            var accumulator = 0
            var bitCount = 0
            bytes.forEach { byte ->
                accumulator = (accumulator shl 8) or (byte.toInt() and 0xff)
                bitCount += 8
                while (bitCount >= 5) {
                    bitCount -= 5
                    result.append(ALPHABET[(accumulator ushr bitCount) and 0x1f])
                }
                accumulator = accumulator and lowBitsMask(bitCount)
            }
            if (bitCount > 0) {
                result.append(ALPHABET[(accumulator shl (5 - bitCount)) and 0x1f])
            }
            check(result.length == ENCODED_CHARACTERS)
            return result.toString()
        }

        private fun decodeBase32(value: String): ByteArray {
            val output = ByteArrayOutputStream(KEY_BYTES)
            var accumulator = 0
            var bitCount = 0
            value.forEach { char ->
                val decoded = alphabetIndex[char.code]
                require(decoded >= 0) { "恢复码包含无效字符" }
                accumulator = (accumulator shl 5) or decoded
                bitCount += 5
                if (bitCount >= 8) {
                    bitCount -= 8
                    output.write((accumulator ushr bitCount) and 0xff)
                    accumulator = accumulator and lowBitsMask(bitCount)
                }
            }
            // 52 base-32 characters carry 260 bits. The final four are padding.
            require(output.size() == KEY_BYTES && bitCount == 4 && accumulator == 0) {
                "恢复码不是规范的 256 位编码"
            }
            return output.toByteArray()
        }

        private fun lowBitsMask(bitCount: Int): Int =
            if (bitCount == 0) 0 else (1 shl bitCount) - 1
    }
}
