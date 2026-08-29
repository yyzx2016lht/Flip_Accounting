package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class BackupArtifactNamesTest {
    @Test
    fun `creates immutable parseable name with canonical backup id`() {
        val name = BackupArtifactNames.create(
            deviceName = "Pixel 8 Pro",
            mode = "FULL",
            createdAt = Instant.parse("2026-08-29T12:34:56Z"),
            backupId = "id_with spaces",
            zoneId = ZoneOffset.UTC
        )

        assertEquals("backup_Pixel_8_Pro_full_20260829_123456_id-with-spaces.bak", name)
        val parsed = BackupArtifactNames.parse(name)!!
        assertEquals("full", parsed.mode)
        assertEquals("20260829_123456", parsed.timestamp)
        assertEquals("id-with-spaces", parsed.backupId)
    }

    @Test
    fun `parses legacy name and rejects partial`() {
        val parsed = BackupArtifactNames.parse("backup_phone_lite_20260829_123456.bak")!!

        assertEquals("phone", parsed.deviceName)
        assertNull(parsed.backupId)
        assertNull(BackupArtifactNames.parse(".partial-123"))
    }
}
