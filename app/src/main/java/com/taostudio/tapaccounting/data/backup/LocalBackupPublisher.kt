package com.taostudio.tapaccounting.data.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class LocalPublishedBackup(
    val file: File,
    val backupId: String,
    val createdAt: Instant,
    val byteCount: Long
)

/**
 * Publishes a completed backup without exposing a half-written final file.
 * Existing valid backups are never replaced or removed by this class.
 */
object LocalBackupPublisher {
    private val publishMutex = Any()

    fun publish(
        sourceFile: File,
        targetDirectory: File,
        deviceName: String,
        mode: String,
        createdAt: Instant = Instant.now(),
        backupId: String = UUID.randomUUID().toString(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        validate: (File) -> Unit = ::validateNonEmptyBackup
    ): LocalPublishedBackup {
        require(sourceFile.isFile) { "Backup source does not exist: ${sourceFile.absolutePath}" }
        require(targetDirectory.exists() || targetDirectory.mkdirs()) {
            "Cannot create backup directory: ${targetDirectory.absolutePath}"
        }
        require(targetDirectory.isDirectory) {
            "Backup target is not a directory: ${targetDirectory.absolutePath}"
        }

        val finalName = BackupArtifactNames.create(
            deviceName = deviceName,
            mode = mode,
            createdAt = createdAt,
            backupId = backupId,
            zoneId = zoneId
        )
        val finalFile = File(targetDirectory, finalName)
        require(!finalFile.exists()) { "Refusing to replace published backup: ${finalFile.absolutePath}" }

        val partialFile = File(targetDirectory, ".partial-${UUID.randomUUID()}")
        var published = false
        try {
            val byteCount = copyAndSync(sourceFile, partialFile)
            check(byteCount == sourceFile.length() && partialFile.length() == byteCount) {
                "Local backup length changed while publishing"
            }
            validate(partialFile)
            publishUnderLock(partialFile, finalFile)
            partialFile.delete()
            published = true
            return LocalPublishedBackup(
                file = finalFile,
                backupId = backupId,
                createdAt = createdAt,
                byteCount = byteCount
            )
        } finally {
            if (!published) partialFile.delete()
        }
    }

    private fun copyAndSync(source: File, destination: File): Long {
        check(destination.createNewFile()) { "Destination already exists: ${destination.absolutePath}" }
        try {
            var copied = 0L
            FileInputStream(source).use { input ->
                FileOutputStream(destination, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            return copied
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    private fun publishUnderLock(partialFile: File, finalFile: File) {
        synchronized(publishMutex) {
            val lockFile = File(finalFile.parentFile, ".backup-publish.lock")
            RandomAccessFile(lockFile, "rw").use { randomAccess ->
                randomAccess.channel.use { channel ->
                    channel.lock().use {
                        check(!finalFile.exists()) {
                            "Published backup already exists: ${finalFile.absolutePath}"
                        }
                        moveIntoPlace(partialFile, finalFile)
                    }
                }
            }
        }
    }

    private fun moveIntoPlace(partialFile: File, finalFile: File) {
        // Explicit atomic move is preferred on modern runtimes. Keep it behind
        // a fallback because java.nio.file.Files is unavailable on old Android.
        try {
            Files.move(partialFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return
        } catch (_: Exception) {
            // Same-directory rename is atomic on Android's supported filesystems.
        } catch (_: LinkageError) {
            // Same-directory rename is atomic on Android's supported filesystems.
        }

        check(!finalFile.exists()) { "Published backup already exists: ${finalFile.absolutePath}" }
        if (partialFile.renameTo(finalFile)) return

        var finalCreated = false
        try {
            check(!finalFile.exists()) { "Published backup already exists: ${finalFile.absolutePath}" }
            copyAndSync(partialFile, finalFile)
            finalCreated = true
            check(finalFile.length() == partialFile.length()) { "Fallback publish length mismatch" }
            partialFile.delete()
        } catch (failure: Throwable) {
            if (finalCreated) finalFile.delete()
            throw IOException("Could not publish backup ${finalFile.absolutePath}", failure)
        }
    }

    private fun validateNonEmptyBackup(file: File) {
        check(file.isFile && file.length() > 0L) { "Backup is empty" }
    }
}
