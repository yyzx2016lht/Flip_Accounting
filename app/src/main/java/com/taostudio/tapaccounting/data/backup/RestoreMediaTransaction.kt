package com.taostudio.tapaccounting.data.backup

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.zip.ZipInputStream

data class RestoreMediaSelection(
    val banners: Boolean = true,
    val chatMedia: Boolean = true
)

enum class RestoreMediaTransactionState {
    STAGED,
    PUBLISHED,
    COMMITTED,
    ROLLED_BACK,
    ROLLBACK_FAILED
}

class RestoreMediaTransactionException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

/**
 * Two-phase filesystem transaction for media from an already validated backup ZIP.
 *
 * Typical integration:
 *
 * 1. Call [BackupManager.validateArchive].
 * 2. Call [stageValidatedZip]; final application files are still untouched.
 * 3. Call [publish], perform the associated database/settings restore, then [commit].
 * 4. Call [rollback] if any later restore step fails, or use this object with `use`.
 */
class RestoreMediaTransaction private constructor(
    private val core: MediaFilePublicationCore,
    private val stagedFiles: List<StagedMediaFile>
) : AutoCloseable {
    val state: RestoreMediaTransactionState
        get() = core.state

    val stagedRelativePaths: List<String> = stagedFiles.map(StagedMediaFile::relativePath)

    @Synchronized
    fun publish() {
        core.publish(stagedFiles)
    }

    @Synchronized
    fun commit() {
        core.commit()
    }

    @Synchronized
    fun rollback() {
        core.rollback()
    }

    override fun close() {
        if (state == RestoreMediaTransactionState.STAGED ||
            state == RestoreMediaTransactionState.PUBLISHED
        ) {
            rollback()
        }
    }

    companion object {
        private const val BANNER_ZIP_PREFIX = "banners/"
        private const val CHAT_MEDIA_ZIP_PREFIX = "chat_media/"
        private const val MAX_MEDIA_FILES = 50_000
        private const val MAX_MEDIA_ENTRY_BYTES = 1024L * 1024 * 1024
        private const val MAX_MEDIA_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

        fun stageValidatedZip(
            validatedZip: File,
            filesDir: File,
            selection: RestoreMediaSelection = RestoreMediaSelection()
        ): RestoreMediaTransaction {
            require(validatedZip.isFile && validatedZip.length() > 0L) {
                "Validated backup ZIP does not exist or is empty"
            }
            require(filesDir.exists() || filesDir.mkdirs()) {
                "Cannot create files directory: ${filesDir.absolutePath}"
            }
            require(filesDir.isDirectory) { "filesDir is not a directory" }

            val root = filesDir.canonicalFile
            val transactionId = UUID.randomUUID().toString()
            val staging = createUniqueStagingDirectory(root, transactionId)
            try {
                val payloadRoot = File(staging, "payload").apply {
                    check(mkdir()) { "Cannot create restore media payload directory" }
                }.canonicalFile
                val staged = extractSelectedMedia(
                    validatedZip = validatedZip,
                    filesRoot = root,
                    payloadRoot = payloadRoot,
                    selection = selection
                )
                return RestoreMediaTransaction(
                    core = MediaFilePublicationCore(root, staging, transactionId),
                    stagedFiles = staged
                )
            } catch (failure: Throwable) {
                runCatching { deleteStagingTree(root, staging, JvmMediaFileOperations) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                if (failure is RestoreMediaTransactionException) throw failure
                throw RestoreMediaTransactionException("Failed to stage restore media", failure)
            }
        }

        private fun createUniqueStagingDirectory(root: File, firstId: String): File {
            repeat(20) { attempt ->
                val id = if (attempt == 0) firstId else UUID.randomUUID().toString()
                val candidate = File(root, ".restore-media-staging-$id").canonicalFile
                RestoreMediaPaths.requireStrictChild(root, candidate)
                if (candidate.mkdir()) return candidate
                if (!candidate.exists()) {
                    throw RestoreMediaTransactionException(
                        "Cannot create media staging directory: ${candidate.absolutePath}"
                    )
                }
            }
            throw RestoreMediaTransactionException("Could not allocate a unique media staging directory")
        }

        private fun extractSelectedMedia(
            validatedZip: File,
            filesRoot: File,
            payloadRoot: File,
            selection: RestoreMediaSelection
        ): List<StagedMediaFile> {
            val result = mutableListOf<StagedMediaFile>()
            val targetPaths = hashSetOf<String>()
            var selectedCount = 0
            var totalBytes = 0L

            ZipInputStream(BufferedInputStream(FileInputStream(validatedZip))).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    val destinationPath = when {
                        selection.banners && entry.name.startsWith(BANNER_ZIP_PREFIX) -> {
                            val bannerPath = entry.name.removePrefix(BANNER_ZIP_PREFIX)
                            if (entry.isDirectory) null else "banners/$bannerPath"
                        }
                        selection.chatMedia && entry.name.startsWith(CHAT_MEDIA_ZIP_PREFIX) -> {
                            if (entry.isDirectory) {
                                null
                            } else {
                                val chatPath = entry.name.removePrefix(CHAT_MEDIA_ZIP_PREFIX)
                                if (!BackupMediaRegistry.isAllowedRestoreRelativePath(chatPath)) {
                                    throw RestoreMediaTransactionException(
                                        "Backup contains a non-chat filesDir target: $chatPath"
                                    )
                                }
                                chatPath
                            }
                        }
                        else -> null
                    }

                    if (destinationPath != null) {
                        selectedCount++
                        if (selectedCount > MAX_MEDIA_FILES) {
                            throw RestoreMediaTransactionException("Backup contains too many media files")
                        }
                        val normalized = RestoreMediaPaths.normalizeRelative(destinationPath)
                        val target = RestoreMediaPaths.resolveUnder(filesRoot, normalized)
                        val targetKey = target.canonicalPath
                        if (!targetPaths.add(targetKey)) {
                            throw RestoreMediaTransactionException(
                                "Multiple ZIP entries map to the same media target: $normalized"
                            )
                        }

                        val stagedFile = RestoreMediaPaths.resolveUnder(payloadRoot, normalized)
                        createStrictDirectories(
                            payloadRoot,
                            requireNotNull(stagedFile.parentFile) { "Staged media has no parent" }
                        )
                        val entryBytes = copyEntryAndSync(input, stagedFile, normalized)
                        totalBytes += entryBytes
                        if (totalBytes > MAX_MEDIA_TOTAL_BYTES) {
                            throw RestoreMediaTransactionException("Staged media exceeds the restore size limit")
                        }
                        result += StagedMediaFile(normalized, stagedFile)
                    }
                    input.closeEntry()
                    entry = input.nextEntry
                }
            }
            return result.sortedBy(StagedMediaFile::relativePath)
        }

        private fun copyEntryAndSync(input: ZipInputStream, outputFile: File, name: String): Long {
            check(outputFile.createNewFile()) { "Duplicate staged file: $name" }
            try {
                var copied = 0L
                FileOutputStream(outputFile, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        copied += count
                        if (copied > MAX_MEDIA_ENTRY_BYTES) {
                            throw RestoreMediaTransactionException("Media entry is too large: $name")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
                return copied
            } catch (failure: Throwable) {
                outputFile.delete()
                throw failure
            }
        }
    }
}

internal data class StagedMediaFile(
    val relativePath: String,
    val stagedFile: File
)

internal interface MediaFileOperations {
    fun rename(source: File, destination: File): Boolean
    fun delete(file: File): Boolean
}

internal object JvmMediaFileOperations : MediaFileOperations {
    override fun rename(source: File, destination: File): Boolean = source.renameTo(destination)

    override fun delete(file: File): Boolean = !file.exists() || file.delete()
}

/** Pure java.io publication core, separated from ZIP extraction for deterministic JVM tests. */
internal class MediaFilePublicationCore(
    rootDirectory: File,
    stagingDirectory: File,
    transactionId: String,
    private val operations: MediaFileOperations = JvmMediaFileOperations
) {
    private val root = rootDirectory.canonicalFile
    private val staging = stagingDirectory.canonicalFile
    private val transactionId = RestoreMediaPaths.safeTransactionId(transactionId)
    private val changes = mutableListOf<PublishedMediaChange>()
    private val createdDirectories = linkedSetOf<File>()
    private var heldLock: HeldPublicationLock? = null

    var state: RestoreMediaTransactionState = RestoreMediaTransactionState.STAGED
        private set

    init {
        require(root.isDirectory) { "Media publication root does not exist" }
        require(staging.isDirectory) { "Media staging directory does not exist" }
        RestoreMediaPaths.requireStrictChild(root, staging)
        require(staging.name.startsWith(".restore-media-staging-")) {
            "Unrecognised media staging directory"
        }
    }

    @Synchronized
    fun publish(stagedFiles: List<StagedMediaFile>) {
        check(state == RestoreMediaTransactionState.STAGED) { "Media transaction is not staged" }
        try {
            val planned = validatePlan(stagedFiles)
            acquirePublicationLock()
            planned.forEachIndexed { index, item -> publishOne(item, index) }
            state = RestoreMediaTransactionState.PUBLISHED
        } catch (failure: Throwable) {
            val rollbackFailure = rollbackPublishedChanges()
            val releaseFailure = releasePublicationLock()
            state = if (rollbackFailure == null && releaseFailure == null) {
                RestoreMediaTransactionState.ROLLED_BACK
            } else {
                RestoreMediaTransactionState.ROLLBACK_FAILED
            }
            releaseFailure?.let(failure::addSuppressed)
            rollbackFailure?.let(failure::addSuppressed)
            throw RestoreMediaTransactionException("Failed to publish restore media", failure)
        }
    }

    @Synchronized
    fun rollback() {
        when (state) {
            RestoreMediaTransactionState.ROLLED_BACK -> return
            RestoreMediaTransactionState.STAGED -> {
                val cleanupFailure = runCatching {
                    deleteStagingTree(root, staging, operations)
                }.exceptionOrNull()
                state = if (cleanupFailure == null) {
                    RestoreMediaTransactionState.ROLLED_BACK
                } else {
                    RestoreMediaTransactionState.ROLLBACK_FAILED
                }
                cleanupFailure?.let {
                    throw RestoreMediaTransactionException("Failed to discard staged media", it)
                }
            }
            RestoreMediaTransactionState.PUBLISHED -> {
                val rollbackFailure = rollbackPublishedChanges()
                val releaseFailure = releasePublicationLock()
                if (rollbackFailure == null && releaseFailure == null) {
                    state = RestoreMediaTransactionState.ROLLED_BACK
                    return
                }
                state = RestoreMediaTransactionState.ROLLBACK_FAILED
                val failure = RestoreMediaTransactionException("Failed to roll back restore media")
                rollbackFailure?.let(failure::addSuppressed)
                releaseFailure?.let(failure::addSuppressed)
                throw failure
            }
            RestoreMediaTransactionState.COMMITTED -> {
                throw IllegalStateException("Committed media cannot be rolled back")
            }
            RestoreMediaTransactionState.ROLLBACK_FAILED -> {
                throw IllegalStateException("Media rollback has already failed")
            }
        }
    }

    @Synchronized
    fun commit() {
        check(state == RestoreMediaTransactionState.PUBLISHED) { "Media transaction is not published" }
        var cleanupFailure: Throwable? = null
        changes.forEach { change ->
            val oldFile = change.oldFile ?: return@forEach
            if (oldFile.exists() && !operations.delete(oldFile)) {
                cleanupFailure = appendFailure(
                    cleanupFailure,
                    IOException("Cannot delete media rollback copy: ${oldFile.absolutePath}")
                )
            }
        }
        runCatching { deleteStagingTree(root, staging, operations) }
            .exceptionOrNull()
            ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        releasePublicationLock()
            ?.let { cleanupFailure = appendFailure(cleanupFailure, it) }
        changes.clear()
        state = RestoreMediaTransactionState.COMMITTED
        cleanupFailure?.let {
            throw RestoreMediaTransactionException(
                "Media was committed but transaction artifacts could not be fully cleaned",
                it
            )
        }
    }

    private fun validatePlan(stagedFiles: List<StagedMediaFile>): List<PlannedMediaFile> {
        val payloadRoot = File(staging, "payload").canonicalFile
        RestoreMediaPaths.requireStrictChild(staging, payloadRoot)
        val targets = hashSetOf<String>()
        return stagedFiles.map { staged ->
            val relative = RestoreMediaPaths.normalizeRelative(staged.relativePath)
            val stagedFile = staged.stagedFile.canonicalFile
            RestoreMediaPaths.requireStrictChild(payloadRoot, stagedFile)
            require(stagedFile.isFile) { "Staged media is missing: ${staged.relativePath}" }
            val target = RestoreMediaPaths.resolveUnder(root, relative)
            require(targets.add(target.canonicalPath)) { "Duplicate media target: $relative" }
            PlannedMediaFile(relative, stagedFile, target)
        }.sortedBy(PlannedMediaFile::relativePath)
    }

    private fun publishOne(item: PlannedMediaFile, index: Int) {
        val targetParent = requireNotNull(item.target.parentFile) { "Media target has no parent" }
        createStrictDirectories(root, targetParent, createdDirectories)
        require(!item.target.exists() || item.target.isFile) {
            "Media target is not a regular file: ${item.target.absolutePath}"
        }
        val oldFile = if (item.target.exists()) {
            val rollbackCopy = File(
                targetParent,
                ".restore-media-old-$transactionId-$index"
            ).canonicalFile
            RestoreMediaPaths.requireStrictChild(root, rollbackCopy)
            check(rollbackCopy.parentFile == targetParent) {
                "Rollback copy is not in the media target directory"
            }
            check(!rollbackCopy.exists()) { "Media rollback copy already exists" }
            check(operations.rename(item.target, rollbackCopy)) {
                "Cannot preserve existing media: ${item.target.absolutePath}"
            }
            rollbackCopy
        } else {
            null
        }

        val change = PublishedMediaChange(item.stagedFile, item.target, oldFile)
        changes += change
        check(!item.target.exists() && operations.rename(item.stagedFile, item.target)) {
            "Cannot publish staged media: ${item.target.absolutePath}"
        }
        change.newFilePublished = true
    }

    private fun rollbackPublishedChanges(): Throwable? {
        var rollbackFailure: Throwable? = null
        changes.asReversed().forEach { change ->
            var targetRemoved = true
            if (change.newFilePublished && change.target.exists()) {
                targetRemoved = operations.delete(change.target)
                if (!targetRemoved) {
                    rollbackFailure = appendFailure(
                        rollbackFailure,
                        IOException("Cannot remove published media: ${change.target.absolutePath}")
                    )
                }
            }
            if (targetRemoved && change.oldFile != null && change.oldFile.exists()) {
                if (change.target.exists() || !operations.rename(change.oldFile, change.target)) {
                    rollbackFailure = appendFailure(
                        rollbackFailure,
                        IOException("Cannot restore previous media: ${change.target.absolutePath}")
                    )
                }
            }
        }
        createdDirectories.sortedByDescending { it.path.length }.forEach { directory ->
            if (directory.exists() && directory.list().orEmpty().isEmpty() && !directory.delete()) {
                rollbackFailure = appendFailure(
                    rollbackFailure,
                    IOException("Cannot remove restore-created directory: ${directory.absolutePath}")
                )
            }
        }
        if (rollbackFailure == null) {
            runCatching { deleteStagingTree(root, staging, operations) }
                .exceptionOrNull()
                ?.let { rollbackFailure = appendFailure(rollbackFailure, it) }
        }
        if (rollbackFailure == null) {
            changes.clear()
            createdDirectories.clear()
        }
        return rollbackFailure
    }

    private fun acquirePublicationLock() {
        try {
            PROCESS_PUBLICATION_GATE.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RestoreMediaTransactionException("Interrupted while waiting to publish media", interrupted)
        }
        var randomAccess: RandomAccessFile? = null
        var channel: FileChannel? = null
        try {
            val lockFile = File(root, ".restore-media-publish.lock")
            randomAccess = RandomAccessFile(lockFile, "rw")
            channel = randomAccess.channel
            val fileLock = channel.lock()
            heldLock = HeldPublicationLock(randomAccess, channel, fileLock)
        } catch (failure: Throwable) {
            runCatching { channel?.close() }
            runCatching { randomAccess?.close() }
            PROCESS_PUBLICATION_GATE.release()
            throw failure
        }
    }

    private fun releasePublicationLock(): Throwable? {
        val lock = heldLock ?: return null
        heldLock = null
        var failure: Throwable? = null
        runCatching { lock.fileLock.release() }.exceptionOrNull()
            ?.let { failure = appendFailure(failure, it) }
        runCatching { lock.channel.close() }.exceptionOrNull()
            ?.let { failure = appendFailure(failure, it) }
        runCatching { lock.randomAccess.close() }.exceptionOrNull()
            ?.let { failure = appendFailure(failure, it) }
        PROCESS_PUBLICATION_GATE.release()
        return failure
    }

    private data class PlannedMediaFile(
        val relativePath: String,
        val stagedFile: File,
        val target: File
    )

    private data class PublishedMediaChange(
        val stagedFile: File,
        val target: File,
        val oldFile: File?,
        var newFilePublished: Boolean = false
    )

    private data class HeldPublicationLock(
        val randomAccess: RandomAccessFile,
        val channel: FileChannel,
        val fileLock: FileLock
    )

    companion object {
        private val PROCESS_PUBLICATION_GATE = Semaphore(1, true)
    }
}

private object RestoreMediaPaths {
    private val windowsDrive = Regex("^[A-Za-z]:")
    private val reservedPrefixes = listOf(
        ".restore-media-staging-",
        ".restore-media-old-",
        ".restore-media-publish.lock"
    )

    fun normalizeRelative(raw: String): String {
        require(raw.isNotBlank()) { "Media path is blank" }
        require('\u0000' !in raw && '\\' !in raw) { "Media path contains unsafe characters" }
        require(!raw.startsWith('/') && !windowsDrive.containsMatchIn(raw)) {
            "Media path must be relative"
        }
        val segments = raw.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Media path contains an unsafe segment"
        }
        require(segments.none { ':' in it }) { "Media path contains a platform-specific drive segment" }
        require(segments.none { segment ->
            reservedPrefixes.any { prefix -> segment.startsWith(prefix, ignoreCase = true) }
        }) { "Media path uses a reserved transaction name" }
        return segments.joinToString("/")
    }

    fun resolveUnder(root: File, relativePath: String): File {
        val normalized = normalizeRelative(relativePath)
        val candidate = File(root, normalized.replace('/', File.separatorChar)).canonicalFile
        requireStrictChild(root.canonicalFile, candidate)
        return candidate
    }

    fun requireStrictChild(root: File, candidate: File) {
        val rootPath = root.canonicalFile.toPath()
        val candidatePath = candidate.canonicalFile.toPath()
        require(candidatePath != rootPath && candidatePath.startsWith(rootPath)) {
            "Media path escapes its transaction root"
        }
    }

    fun safeTransactionId(value: String): String {
        val safe = value.replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(80)
        require(safe.isNotBlank()) { "Transaction id is empty" }
        return safe
    }
}

private fun createStrictDirectories(
    root: File,
    targetParent: File,
    createdDirectories: MutableSet<File>? = null
) {
    val canonicalRoot = root.canonicalFile
    val canonicalParent = targetParent.canonicalFile
    if (canonicalParent != canonicalRoot) {
        RestoreMediaPaths.requireStrictChild(canonicalRoot, canonicalParent)
    }
    val missing = mutableListOf<File>()
    var cursor = canonicalParent
    while (!cursor.exists()) {
        RestoreMediaPaths.requireStrictChild(canonicalRoot, cursor)
        missing += cursor
        cursor = cursor.parentFile
            ?: throw RestoreMediaTransactionException("Media directory has no parent")
    }
    require(cursor.isDirectory) { "Media parent path is not a directory: ${cursor.absolutePath}" }
    missing.asReversed().forEach { directory ->
        val created = directory.mkdir()
        if (!created && !directory.isDirectory) {
            throw RestoreMediaTransactionException(
                "Cannot create media directory: ${directory.absolutePath}"
            )
        }
        if (created) createdDirectories?.add(directory)
    }
}

private fun deleteStagingTree(root: File, staging: File, operations: MediaFileOperations) {
    val canonicalRoot = root.canonicalFile
    val canonicalStaging = staging.canonicalFile
    RestoreMediaPaths.requireStrictChild(canonicalRoot, canonicalStaging)
    require(canonicalStaging.name.startsWith(".restore-media-staging-")) {
        "Refusing to delete an unrecognised staging directory"
    }

    fun deleteNode(node: File) {
        if (!node.exists() && !Files.isSymbolicLink(node.toPath())) return
        if (!Files.isSymbolicLink(node.toPath()) && node.isDirectory) {
            node.listFiles().orEmpty().forEach(::deleteNode)
        }
        if (!operations.delete(node)) {
            throw IOException("Cannot delete media staging path: ${node.absolutePath}")
        }
    }
    deleteNode(canonicalStaging)
}

private fun appendFailure(existing: Throwable?, additional: Throwable): Throwable {
    if (existing == null) return additional
    existing.addSuppressed(additional)
    return existing
}
