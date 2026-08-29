package com.taostudio.tapaccounting.data.backup

import java.io.File
import java.time.ZoneId

object LocalBackupHistory {
    fun cleanup(
        directory: File,
        policy: BackupRetentionPolicy? = null,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): Int {
        val candidates = directory.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                if (!file.isFile) return@mapNotNull null
                val format = BackupFileFormatDetector.detect(file)
                if (format !in setOf(
                        BackupFileFormat.ZIP,
                        BackupFileFormat.V2_ENCRYPTED,
                        BackupFileFormat.V3_PASSWORD
                    )
                ) {
                    return@mapNotNull null
                }
                val name = BackupArtifactNames.parse(file.name) ?: return@mapNotNull null
                // Only immutable versions created by the app carry backupId. Never delete an old
                // legacy file or a user-copied ZIP just because its name happens to look similar.
                if (name.backupId == null) return@mapNotNull null
                Parsed(file, name)
            }
        return candidates
            .groupBy { "${it.name.deviceName}\u0000${it.name.mode}" }
            .values
            .flatMap { group ->
                (policy ?: BackupRetentionPolicy.forMode(group.first().name.mode)).decide(
                    group,
                    createdAt = { it.name.createdAt.atZone(zoneId).toInstant() },
                    stableId = { it.name.backupId ?: it.file.absolutePath }
                ).delete
            }
            .count { it.file.delete() }
    }

    private data class Parsed(val file: File, val name: BackupArtifactName)
}
