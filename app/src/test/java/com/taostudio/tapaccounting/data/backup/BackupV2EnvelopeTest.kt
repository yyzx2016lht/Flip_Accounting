package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupV2EnvelopeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `encrypt and decrypt round trip preserves payload and manifest`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("backup.fab2")
        val restored = temporaryFolder.newFile("restored.zip")
        restored.delete()

        BackupV2Envelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.recoveryCode)
        val restoredManifest = BackupV2Envelope.decrypt(encrypted, restored, fixture.recoveryCode)

        assertEquals(BackupFileFormat.V2_ENCRYPTED, BackupFileFormatDetector.detect(encrypted))
        assertTrue(BackupFileFormatDetector.isV2Encrypted(encrypted))
        assertEquals(fixture.manifest, restoredManifest)
        assertArrayEquals(fixture.payload.readBytes(), restored.readBytes())
        assertFalse(
            String(encrypted.readBytes(), Charsets.ISO_8859_1)
                .contains(fixture.recoveryCode.format())
        )
    }

    @Test
    fun `wrong recovery code fails authentication and keeps existing output`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("wrong-key.fab2")
        val restored = temporaryFolder.newFile("existing-output.zip")
        val existing = "keep this output".toByteArray()
        restored.writeBytes(existing)
        BackupV2Envelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.recoveryCode)

        assertThrows(BackupAuthenticationException::class.java) {
            BackupV2Envelope.decrypt(encrypted, restored, BackupRecoveryCode.generate())
        }

        assertArrayEquals(existing, restored.readBytes())
    }

    @Test
    fun `tampered ciphertext fails authentication without publishing output`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("tampered.fab2")
        val restored = File(temporaryFolder.root, "must-not-exist.zip")
        BackupV2Envelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.recoveryCode)
        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(file.length() - 1)
            val original = file.readUnsignedByte()
            file.seek(file.length() - 1)
            file.writeByte(original xor 0x01)
        }

        assertThrows(BackupAuthenticationException::class.java) {
            BackupV2Envelope.decrypt(encrypted, restored, fixture.recoveryCode)
        }

        assertFalse(restored.exists())
    }

    @Test
    fun `invalid magic is unknown and rejected as V2`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("bad-magic.fab2")
        val restored = File(temporaryFolder.root, "bad-magic-output.zip")
        BackupV2Envelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.recoveryCode)
        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(0)
            file.writeByte('X'.code)
        }

        assertEquals(BackupFileFormat.UNKNOWN, BackupFileFormatDetector.detect(encrypted))
        assertThrows(BackupFormatException::class.java) {
            BackupV2Envelope.decrypt(encrypted, restored, fixture.recoveryCode)
        }
    }

    @Test
    fun `unsupported envelope version is reported explicitly`() {
        val fixture = fixture()
        val encrypted = temporaryFolder.newFile("future-version.fab2")
        val restored = File(temporaryFolder.root, "future-version-output.zip")
        BackupV2Envelope.encrypt(fixture.payload, encrypted, fixture.manifest, fixture.recoveryCode)
        RandomAccessFile(encrypted, "rw").use { file ->
            file.seek(BackupV2Envelope.VERSION_OFFSET.toLong())
            file.writeByte(99)
        }

        assertEquals(BackupFileFormat.V2_ENCRYPTED, BackupFileFormatDetector.detect(encrypted))
        val error = assertThrows(UnsupportedBackupVersionException::class.java) {
            BackupV2Envelope.decrypt(encrypted, restored, fixture.recoveryCode)
        }
        assertEquals(99, error.foundVersion)
    }

    @Test
    fun `detector preserves recognition of V1 zip backups`() {
        val zip = temporaryFolder.newFile("legacy.bak")
        ZipOutputStream(zip.outputStream()).use { output ->
            output.putNextEntry(ZipEntry("database.db"))
            output.write(byteArrayOf(1, 2, 3))
            output.closeEntry()
        }

        assertEquals(BackupFileFormat.ZIP, BackupFileFormatDetector.detect(zip))
        assertFalse(BackupFileFormatDetector.isV2Encrypted(zip))
    }

    @Test
    fun `encryption rejects payload changed after manifest creation`() {
        val fixture = fixture()
        val encrypted = File(temporaryFolder.root, "changed-payload.fab2")
        fixture.payload.appendBytes(byteArrayOf(42))

        assertThrows(BackupIntegrityException::class.java) {
            BackupV2Envelope.encrypt(
                fixture.payload,
                encrypted,
                fixture.manifest,
                fixture.recoveryCode
            )
        }

        assertFalse(encrypted.exists())
    }

    private fun fixture(): Fixture {
        val payload = temporaryFolder.newFile("payload-${UUID.randomUUID()}.zip")
        payload.outputStream().buffered().use { output ->
            val block = ByteArray(64 * 1024) { index -> (index * 31).toByte() }
            repeat(18) { output.write(block) }
            output.write("payload-tail".toByteArray())
        }
        val modules = listOf(
            BackupV2Module.fromFile(name = "database", itemCount = 123, file = payload)
        )
        val manifest = BackupV2Manifest.create(
            appVersion = "1.2-test",
            dbSchemaVersion = 19,
            modules = modules,
            payloadFile = payload,
            backupId = "123e4567-e89b-12d3-a456-426614174000",
            createdAt = 1_725_000_000_000
        )
        return Fixture(payload, manifest, BackupRecoveryCode.generate())
    }

    private data class Fixture(
        val payload: File,
        val manifest: BackupV2Manifest,
        val recoveryCode: BackupRecoveryCode
    )
}
