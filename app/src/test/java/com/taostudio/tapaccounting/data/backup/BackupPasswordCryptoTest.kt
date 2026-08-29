package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupPasswordCryptoTest {
    private val parameters = BackupPasswordKdfParameters(
        salt = ByteArray(BackupPasswordCrypto.SALT_BYTES) { it.toByte() },
        iterations = BackupPasswordCrypto.MIN_ITERATIONS
    )

    @Test
    fun `same PIN and parameters derive the same 256 bit key`() {
        val first = BackupPasswordCrypto.derive("12345678", parameters)
        val second = BackupPasswordCrypto.derive("12345678", parameters)

        assertArrayEquals(first, second)
        assertEquals(BackupPasswordCrypto.KEY_BYTES, first.size)
    }

    @Test
    fun `Android compatible implementation matches standard PBKDF2 SHA256`() {
        val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(
                    "12345678".toCharArray(),
                    parameters.salt,
                    parameters.iterations,
                    BackupPasswordCrypto.KEY_BYTES * 8
                )
            )
            .encoded

        assertArrayEquals(expected, BackupPasswordCrypto.derive("12345678", parameters))
    }

    @Test
    fun `different PIN produces a different key`() {
        val first = BackupPasswordCrypto.derive("12345678", parameters)
        val second = BackupPasswordCrypto.derive("87654321", parameters)

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `short and non numeric PINs are rejected`() {
        listOf("1234", "1234567", "1234567a", "1234567890123").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                BackupPasswordCrypto.requireValidPin(invalid)
            }
        }
    }
}
