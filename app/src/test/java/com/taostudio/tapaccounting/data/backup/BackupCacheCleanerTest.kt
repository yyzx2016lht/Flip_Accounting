package com.taostudio.tapaccounting.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCacheCleanerTest {
    @Test
    fun recognizesOnlyOwnedBackupTemporaryNames() {
        assertTrue(BackupCacheCleaner.isBackupTempFileName("restore_payload_123.zip"))
        assertTrue(
            BackupCacheCleaner.isBackupTempFileName(
                ".temp_cloud_upload_123.bak.payload.00000000-0000-0000-0000-000000000000.zip"
            )
        )
        assertTrue(
            BackupCacheCleaner.isBackupTempFileName(
                ".restore_payload_123.zip.decrypted-envelope.abc.tmp"
            )
        )
        assertTrue(
            BackupCacheCleaner.isBackupTempFileName(
                ".restore_payload_123.zip.00000000-0000-0000-0000-000000000000.tmp"
            )
        )
        assertTrue(
            BackupCacheCleaner.isBackupTempFileName(
                ".temp_local_publish_123.bak.payload.00000000-0000-0000-0000-000000000000.zip"
            )
        )
        assertFalse(BackupCacheCleaner.isBackupTempFileName("user-export.bak"))
        assertFalse(BackupCacheCleaner.isBackupTempFileName("image_cache_payload.bin"))
    }
}
