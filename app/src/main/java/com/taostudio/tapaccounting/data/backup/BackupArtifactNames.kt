package com.taostudio.tapaccounting.data.backup

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Metadata encoded in both legacy and immutable backup file names. */
data class BackupArtifactName(
    val fileName: String,
    val deviceName: String,
    val mode: String,
    val timestamp: String,
    val createdAt: LocalDateTime,
    val backupId: String?
)

/**
 * Centralises backup naming so local and WebDAV publishers produce names that
 * can be discovered by the same history/retention code.
 */
object BackupArtifactNames {
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val legacyOrImmutablePattern = Regex(
        pattern = "^backup_(.+)_(lite|full|custom)_(\\d{8}_\\d{6})(?:_([A-Za-z0-9-]+))?\\.bak$",
        option = RegexOption.IGNORE_CASE
    )

    fun create(
        deviceName: String,
        mode: String,
        createdAt: Instant,
        backupId: String,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val safeDevice = safeSegment(deviceName, "device")
        val safeMode = mode.trim().lowercase()
        require(safeMode in setOf("lite", "full", "custom")) { "Unsupported backup mode: $mode" }
        val safeBackupId = backupId.trim()
            .replace(Regex("[^A-Za-z0-9-]+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "id" }
        val timestamp = timestampFormatter.format(createdAt.atZone(zoneId))
        return "backup_${safeDevice}_${safeMode}_${timestamp}_${safeBackupId}.bak"
    }

    fun parse(fileName: String): BackupArtifactName? {
        val match = legacyOrImmutablePattern.matchEntire(fileName) ?: return null
        val timestamp = match.groupValues[3]
        val createdAt = try {
            LocalDateTime.parse(timestamp, timestampFormatter)
        } catch (_: DateTimeParseException) {
            return null
        }
        return BackupArtifactName(
            fileName = fileName,
            deviceName = match.groupValues[1],
            mode = match.groupValues[2].lowercase(),
            timestamp = timestamp,
            createdAt = createdAt,
            backupId = match.groupValues[4].ifBlank { null }
        )
    }

    fun safeSegment(value: String, fallback: String): String {
        val sanitized = value.trim()
            .replace(Regex("[^\\p{L}\\p{N}._-]+"), "_")
            .trim('.', '_', '-')
            .take(64)
        return sanitized.ifBlank { fallback }
    }
}
