package com.taostudio.tapaccounting.data.backup

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 用户可见的 **`.bak` 备份文件**（ZIP 包 JSON + 媒体），与 [DatabaseDowngradeHelper] 的降级自动整库备份无关。
 *
 * ## 职责边界
 * | 机制 | 本类 / BackupActivity | DatabaseDowngradeHelper |
 * |------|----------------------|-------------------------|
 * | 触发 | 用户手动或自动备份任务 | 装旧版 APK 后首次打开 |
 * | 格式 | `.bak`（JSON 模块） | 原始 `.db` 文件 |
 * | 卸载后 | 文件仍在用户目录/云盘 | 随 App 内部存储删除 |
 *
 * ## 新增 Room 表/字段时
 * 除在 [AppDatabase] 写 Migration 外，还需同步：
 * - [BackupRepository.getFullData] / [BackupRepository.restoreFullData]
 * - [DataExportManager] 序列化
 * - [BackupActivity] 恢复模块勾选 UI（如有独立模块）
 *
 * ## 不要
 * - 不要用本类替代 Room Migration（`.bak` 是灾备，不是升级路径）。
 * - 不要在恢复逻辑里 `fallbackToDestructiveMigration()` 清库。
 */
object BackupManager {
    private val gson = Gson()
    private const val BANNER_ZIP_PREFIX = "banners/"
    private const val CHAT_MEDIA_ZIP_PREFIX = "chat_media/"
    private const val MAX_ENTRY_COUNT = 50_000
    private const val MAX_JSON_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_MEDIA_ENTRY_BYTES = 1024L * 1024 * 1024
    private const val MAX_ARCHIVE_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024

    fun backup(
        outputFile: File,
        dataMap: Map<String, Any>,
        bannerDir: File? = null,
        chatMediaFiles: Map<String, File> = emptyMap()
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            zos.setLevel(Deflater.BEST_COMPRESSION)
            for ((fileName, data) in dataMap) {
                val json = if (data is String) data else gson.toJson(data)
                zos.putNextEntry(ZipEntry("$fileName.json"))
                zos.write(json.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            if (bannerDir != null && bannerDir.isDirectory) {
                bannerDir.listFiles()?.filter { it.isFile }?.forEach { imgFile ->
                    zos.putNextEntry(ZipEntry("$BANNER_ZIP_PREFIX${imgFile.name}"))
                    imgFile.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            chatMediaFiles.forEach { (relativePath, file) ->
                if (!file.exists() || !file.isFile) return@forEach
                zos.putNextEntry(ZipEntry("$CHAT_MEDIA_ZIP_PREFIX$relativePath"))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }

    fun restore(zipFile: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!name.startsWith(BANNER_ZIP_PREFIX) && !name.startsWith(CHAT_MEDIA_ZIP_PREFIX) && !entry.isDirectory) {
                    val bytes = readEntryWithLimit(zis, MAX_JSON_ENTRY_BYTES, name)
                    result[name.removeSuffix(".json")] = bytes.toString(Charsets.UTF_8)
                }
                entry = zis.nextEntry
            }
        }
        return result
    }

    /**
     * Fully reads and validates every ZIP entry before any restore mutates app state.
     * This also verifies CRCs and bounds legacy V1 archives that do not have V2 authentication.
     */
    fun validateArchive(zipFile: File) {
        if (!zipFile.isFile || zipFile.length() <= 0L) {
            throw BackupFormatException("备份归档为空或不存在")
        }
        val names = HashSet<String>()
        var entryCount = 0
        var totalBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryCount++
                if (entryCount > MAX_ENTRY_COUNT) {
                    throw BackupFormatException("备份归档条目过多")
                }
                val name = entry.name
                validateEntryName(name, entry.isDirectory)
                if (!names.add(name)) {
                    throw BackupFormatException("备份归档含重复条目：$name")
                }

                val entryLimit = if (name.endsWith(".json")) {
                    MAX_JSON_ENTRY_BYTES
                } else {
                    MAX_MEDIA_ENTRY_BYTES
                }
                var entryBytes = 0L
                while (true) {
                    val read = zis.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    entryBytes += read
                    totalBytes += read
                    if (entryBytes > entryLimit) {
                        throw BackupFormatException("备份条目过大：$name")
                    }
                    if (totalBytes > MAX_ARCHIVE_UNCOMPRESSED_BYTES) {
                        throw BackupFormatException("备份归档解压后过大")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        if (entryCount == 0) throw BackupFormatException("备份归档不含任何内容")
    }

    /** Recomputes the logical module inventory using the same rules as Backup V2 creation. */
    fun inspectArchiveModules(
        zipFile: File,
        jsonModules: Map<String, String>
    ): List<BackupV2Module> = ZipFile(zipFile).use { archive ->
        val entries = archive.entries().asSequence().filterNot { it.isDirectory }.toList()
        val modules = entries
            .filter { '/' !in it.name && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .map { entry ->
                val moduleName = entry.name.removeSuffix(".json")
                val json = requireNotNull(jsonModules[moduleName]) {
                    "备份模块 $moduleName 无法读取"
                }
                val element = try {
                    JsonParser.parseString(json)
                } catch (error: Exception) {
                    throw BackupFormatException("备份模块 $moduleName 不是有效 JSON", error)
                }
                val itemCount = if (element.isJsonArray) element.asJsonArray.size().toLong() else 1L
                val digest = archive.getInputStream(entry).buffered().use(BackupV2Digest::of)
                BackupV2Module(moduleName, itemCount, digest.byteSize, digest.sha256)
            }
            .toMutableList()

        inspectMediaModule(archive, entries, BANNER_ZIP_PREFIX, "banners")?.let(modules::add)
        inspectMediaModule(archive, entries, CHAT_MEDIA_ZIP_PREFIX, "chat_media")?.let(modules::add)
        modules
    }

    fun restoreBanners(zipFile: File, targetDir: File): List<String> {
        val restored = mutableListOf<String>()
        targetDir.mkdirs()
        val targetRoot = targetDir.canonicalFile
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith(BANNER_ZIP_PREFIX) && !entry.isDirectory) {
                    val fileName = name.removePrefix(BANNER_ZIP_PREFIX)
                    val outFile = safeZipOutputFile(targetRoot, fileName)
                    if (outFile != null) {
                        FileOutputStream(outFile).use { fos ->
                            val buf = ByteArray(8192)
                            var len: Int
                            while (zis.read(buf).also { len = it } != -1) {
                                fos.write(buf, 0, len)
                            }
                        }
                        restored.add(fileName)
                    }
                }
                entry = zis.nextEntry
            }
        }
        return restored
    }

    fun hasBanners(zipFile: File): Boolean {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(BANNER_ZIP_PREFIX) && !entry.isDirectory) return true
                entry = zis.nextEntry
            }
        }
        return false
    }

    fun hasChatMedia(zipFile: File): Boolean {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(CHAT_MEDIA_ZIP_PREFIX) && !entry.isDirectory) return true
                entry = zis.nextEntry
            }
        }
        return false
    }

    fun restoreChatMedia(zipFile: File, targetRootDir: File): List<String> {
        val restored = mutableListOf<String>()
        targetRootDir.mkdirs()
        val targetRoot = targetRootDir.canonicalFile
        validateChatMediaRestoreTargets(zipFile, targetRoot)
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith(CHAT_MEDIA_ZIP_PREFIX) && !entry.isDirectory) {
                    val relativePath = name.removePrefix(CHAT_MEDIA_ZIP_PREFIX)
                    val outFile = requireNotNull(safeZipOutputFile(targetRoot, relativePath)) {
                        "备份归档含不安全的聊天媒体路径"
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buf = ByteArray(8192)
                        var len: Int
                        while (zis.read(buf).also { len = it } != -1) {
                            fos.write(buf, 0, len)
                        }
                    }
                    restored.add(relativePath)
                }
                entry = zis.nextEntry
            }
        }
        return restored
    }

    private fun validateChatMediaRestoreTargets(zipFile: File, targetRoot: File) {
        ZipInputStream(FileInputStream(zipFile)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(CHAT_MEDIA_ZIP_PREFIX) && !entry.isDirectory) {
                    val relativePath = entry.name.removePrefix(CHAT_MEDIA_ZIP_PREFIX)
                    if (!BackupMediaRegistry.isAllowedRestoreRelativePath(relativePath) ||
                        safeZipOutputFile(targetRoot, relativePath) == null
                    ) {
                        throw BackupFormatException("备份归档含非聊天媒体目标：$relativePath")
                    }
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private fun safeZipOutputFile(targetRoot: File, relativePath: String): File? {
        if (relativePath.isBlank()) return null
        if (relativePath.contains('\\')) return null
        val candidate = File(relativePath)
        if (candidate.isAbsolute) return null
        val outFile = File(targetRoot, relativePath).canonicalFile
        val rootPath = targetRoot.path + File.separator
        return if (outFile.path == targetRoot.path || outFile.path.startsWith(rootPath)) outFile else null
    }

    private fun validateEntryName(name: String, isDirectory: Boolean) {
        if (name.isBlank() || name.indexOf('\u0000') >= 0 || name.contains('\\') ||
            name.startsWith('/') || name.split('/').any { it == "." || it == ".." }
        ) {
            throw BackupFormatException("备份归档含不安全路径")
        }
        if (isDirectory) {
            if (!name.startsWith(BANNER_ZIP_PREFIX) && !name.startsWith(CHAT_MEDIA_ZIP_PREFIX)) {
                throw BackupFormatException("备份归档含未知目录：$name")
            }
            return
        }
        val isJsonModule = '/' !in name && name.endsWith(".json") && name.length > ".json".length
        val isMedia = name.startsWith(BANNER_ZIP_PREFIX) || name.startsWith(CHAT_MEDIA_ZIP_PREFIX)
        if (!isJsonModule && !isMedia) {
            throw BackupFormatException("备份归档含未知条目：$name")
        }
    }

    private fun readEntryWithLimit(input: ZipInputStream, limit: Long, name: String): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            copied += read
            if (copied > limit) throw IOException("备份条目过大：$name")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun inspectMediaModule(
        archive: ZipFile,
        entries: List<ZipEntry>,
        prefix: String,
        moduleName: String
    ): BackupV2Module? {
        val mediaEntries = entries.filter { it.name.startsWith(prefix) }.sortedBy { it.name }
        if (mediaEntries.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        mediaEntries.forEach { entry ->
            val relativePath = entry.name.removePrefix(prefix)
            val pathBytes = relativePath.toByteArray(Charsets.UTF_8)
            digest.update(pathBytes)
            byteSize += pathBytes.size
            archive.getInputStream(entry).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    byteSize += read
                }
            }
        }
        return BackupV2Module(
            name = moduleName,
            itemCount = mediaEntries.size.toLong(),
            byteSize = byteSize,
            sha256 = digest.digest().toHex()
        )
    }
}

