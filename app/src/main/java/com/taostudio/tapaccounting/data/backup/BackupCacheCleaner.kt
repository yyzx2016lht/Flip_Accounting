package com.taostudio.tapaccounting.data.backup

import android.content.Context

/** Removes app-private cleartext or partial backup artifacts left by a killed process. */
object BackupCacheCleaner {
    private val directPrefixes = listOf(
        "restore_payload_",
        "restore_source_",
        "temp_cloud_restore_",
        "temp_cloud_upload_",
        "temp_saf_publish_",
        "temp_default_publish_",
        "temp_local_publish_",
        "temp_backup_",
        "auto_snapshot_"
    )

    fun cleanupAtProcessStart(context: Context): Int =
        context.cacheDir.listFiles()
            .orEmpty()
            .filter { it.isFile && isBackupTempFileName(it.name) }
            .count { it.delete() }

    internal fun isBackupTempFileName(name: String): Boolean {
        if (directPrefixes.any(name::startsWith)) return true
        if (!name.startsWith('.')) return false
        val relatesToBackup = directPrefixes.any { prefix -> name.contains(prefix) }
        return relatesToBackup && (
            ".payload." in name ||
                ".decrypted-envelope." in name ||
                ".decrypted-payload." in name ||
                ".encrypting." in name ||
                ".previous." in name ||
                name.endsWith(".tmp")
            )
    }
}
