package tao.test.tapaccounting.data.backup

import com.google.gson.Gson
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private val gson = Gson()
    private const val BANNER_ZIP_PREFIX = "banners/"
    private const val CHAT_MEDIA_ZIP_PREFIX = "chat_media/"

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
                    result[name.removeSuffix(".json")] = zis.bufferedReader().readText()
                }
                entry = zis.nextEntry
            }
        }
        return result
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
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith(CHAT_MEDIA_ZIP_PREFIX) && !entry.isDirectory) {
                    val relativePath = name.removePrefix(CHAT_MEDIA_ZIP_PREFIX)
                    val outFile = safeZipOutputFile(targetRoot, relativePath)
                    if (outFile != null) {
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
                }
                entry = zis.nextEntry
            }
        }
        return restored
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
}



