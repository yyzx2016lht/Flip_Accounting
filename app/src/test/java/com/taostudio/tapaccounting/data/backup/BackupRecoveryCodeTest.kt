package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupRecoveryCodeTest {

    @Test
    fun `formats and strictly parses a 256-bit recovery code`() {
        val bytes = ByteArray(32) { it.toByte() }

        val code = BackupRecoveryCode.fromBytes(bytes)
        val formatted = code.format()

        assertEquals(13, formatted.split('-').size)
        assertEquals(List(13) { 4 }, formatted.split('-').map(String::length))
        assertEquals(code, BackupRecoveryCode.parse(formatted))
        assertArrayEquals(bytes, BackupRecoveryCode.parse(formatted).copyKeyBytes())
    }

    @Test
    fun `generated recovery codes contain independent 256-bit keys`() {
        val first = BackupRecoveryCode.generate()
        val second = BackupRecoveryCode.generate()

        assertEquals(32, first.copyKeyBytes().size)
        assertEquals(32, second.copyKeyBytes().size)
        assertFalse(first == second)
    }

    @Test
    fun `parser rejects non-canonical recovery code forms`() {
        val canonical = BackupRecoveryCode.fromBytes(ByteArray(32)).format()

        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryCode.parse(canonical.lowercase())
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryCode.parse(canonical.replace("-", ""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryCode.parse("I${canonical.drop(1)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRecoveryCode.parse(canonical.dropLast(1) + "B")
        }
    }
}
