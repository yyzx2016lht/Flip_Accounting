package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LocalBackupHistoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun cleanup_keepsSeveralRecentBackupsCreatedOnTheSameDay() {
        val directory = temp.newFolder("same-day-history")
        val newest = Instant.parse("2026-08-29T12:00:00Z")
        repeat(3) { offset ->
            val name = BackupArtifactNames.create(
                deviceName = "phone-a",
                mode = "lite",
                createdAt = newest.minusSeconds(offset * 60L),
                backupId = UUID.randomUUID().toString(),
                zoneId = ZoneOffset.UTC
            )
            ZipOutputStream(directory.resolve(name).outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("assets.json"))
                zip.write("[]".toByteArray())
                zip.closeEntry()
            }
        }

        LocalBackupHistory.cleanup(directory, zoneId = ZoneOffset.UTC)

        assertEquals(3, directory.listFiles().orEmpty().size)
    }

    @Test
    fun cleanup_keepsNewestGenerationPerDeviceAndMode() {
        val directory = temp.newFolder("history")
        val newest = Instant.parse("2026-08-29T12:00:00Z")
        listOf("phone-a", "phone-b").forEach { device ->
            listOf("lite", "full").forEach { mode ->
                listOf(newest.minusSeconds(60), newest).forEach { createdAt ->
                    val name = BackupArtifactNames.create(
                        deviceName = device,
                        mode = mode,
                        createdAt = createdAt,
                        backupId = UUID.randomUUID().toString(),
                        zoneId = ZoneOffset.UTC
                    )
                    val file = directory.resolve(name)
                    if (device == "phone-a") {
                        ZipOutputStream(file.outputStream()).use { zip ->
                            zip.putNextEntry(ZipEntry("assets.json"))
                            zip.write("[]".toByteArray())
                            zip.closeEntry()
                        }
                    } else {
                        file.writeBytes(BackupV2Envelope.MAGIC)
                    }
                }
            }
        }

        val deleted = LocalBackupHistory.cleanup(
            directory,
            BackupRetentionPolicy(daily = 0, weekly = 0, monthly = 0),
            ZoneOffset.UTC
        )

        assertEquals(4, deleted)
        assertEquals(4, directory.listFiles().orEmpty().size)
    }

    @Test
    fun cleanup_neverDeletesLegacyZipWithoutBackupId() {
        val directory = temp.newFolder("legacy-history")
        val legacy = directory.resolve("backup_phone_lite_20260829_120000.bak")
        ZipOutputStream(legacy.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("assets.json"))
            zip.write("[]".toByteArray())
            zip.closeEntry()
        }

        val deleted = LocalBackupHistory.cleanup(
            directory,
            BackupRetentionPolicy(daily = 0, weekly = 0, monthly = 0),
            ZoneOffset.UTC
        )

        assertEquals(0, deleted)
        assertEquals(true, legacy.exists())
    }
}
