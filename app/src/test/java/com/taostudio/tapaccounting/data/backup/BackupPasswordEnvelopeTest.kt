package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class BackupPasswordEnvelopeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trip preserves payload manifest and KDF metadata`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("password-backup.bak")
        val restored = File(temporaryFolder.root, "restored.zip")

        BackupPasswordEnvelope.encrypt(
            fixture.payload,
            encrypted,
            fixture.manifest,
            fixture.material
        )
        val restoredManifest = BackupPasswordEnvelope.decrypt(
            encrypted,
            restored,
            fixture.material.keyBytes
        )
        val parameters = BackupPasswordEnvelope.readKdfParameters(encrypted)

        assertEquals(BackupFileFormat.V3_PASSWORD, BackupFileFormatDetector.detect(encrypted))
        assertEquals(fixture.manifest, restoredManifest)
        assertEquals(fixture.material.parameters.iterations, parameters.iterations)
        assertArrayEquals(fixture.material.parameters.salt, parameters.salt)
        assertArrayEquals(fixture.payload.readBytes(), restored.readBytes())
        assertFalse(String(encrypted.readBytes(), Charsets.ISO_8859_1).contains("sensitive-api-key"))
    }

    @Test
    fun `wrong PIN key and tampering fail authentication without publishing output`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("protected.bak")
        BackupPasswordEnvelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.material)

        val wrongOutput = File(temporaryFolder.root, "wrong.zip")
        val wrongKey = BackupPasswordCrypto.derive("87654321", fixture.material.parameters)
        assertThrows(BackupAuthenticationException::class.java) {
            BackupPasswordEnvelope.decrypt(encrypted, wrongOutput, wrongKey)
        }
        assertFalse(wrongOutput.exists())

        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(file.length() - 1)
            val original = file.readUnsignedByte()
            file.seek(file.length() - 1)
            file.writeByte(original xor 1)
        }
        val tamperedOutput = File(temporaryFolder.root, "tampered.zip")
        assertThrows(BackupAuthenticationException::class.java) {
            BackupPasswordEnvelope.decrypt(encrypted, tamperedOutput, fixture.material.keyBytes)
        }
        assertFalse(tamperedOutput.exists())
    }

    private fun fixture(): Fixture {
        val payload = temporaryFolder.newFile("payload.zip")
        payload.writeText("financial-data:sensitive-api-key")
        val manifest = BackupV2Manifest.create(
            appVersion = "test",
            dbSchemaVersion = 19,
            modules = listOf(BackupV2Module.fromFile("settings_ai_core", 1, payload)),
            payloadFile = payload,
            backupId = "123e4567-e89b-12d3-a456-426614174000",
            createdAt = 1_725_000_000_000
        )
        val parameters = BackupPasswordKdfParameters(
            salt = ByteArray(BackupPasswordCrypto.SALT_BYTES) { (it + 3).toByte() },
            iterations = BackupPasswordCrypto.MIN_ITERATIONS
        )
        val material = BackupPasswordKeyMaterial(
            BackupPasswordCrypto.derive("12345678", parameters),
            parameters
        )
        return Fixture(payload, manifest, material)
    }

    private data class Fixture(
        val payload: File,
        val manifest: BackupV2Manifest,
        val material: BackupPasswordKeyMaterial
    )
}
