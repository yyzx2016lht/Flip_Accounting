package com.taostudio.tapaccounting.data.backup

import android.content.Context
import java.io.File

/**
 * Single source of truth for user-created files that belong in a full recovery snapshot.
 *
 * Keep this list deliberately narrow: caches, downloaded models, OCR scratch images and logs
 * are reproducible/device-local and must not enter a backup.
 */
object BackupMediaRegistry {
    private val persistentDirectories = setOf(
        "chat_bg",
        "chat_voice",
        "chat_images",
        "chat_attachments",
        // Legacy image directory kept so an old conversation is still recoverable.
        "chat_pics"
    )

    private val persistentRootFiles = setOf(
        "chat_ai_avatar.jpg",
        "chat_ai_avatar.png",
        "chat_user_avatar.jpg",
        "chat_user_avatar.png",
        // Pre-directory versions stored the selected chat background at filesDir root.
        "chat_bg.jpg",
        "chat_bg.png",
        "chat_bg.webp",
        "chat_bg.jpeg",
        "chat_bg.bmp",
        "chat_bg.gif"
    )

    fun collectChatMedia(context: Context): Map<String, File> {
        val result = linkedMapOf<String, File>()
        persistentDirectories.forEach { directoryName ->
            val directory = File(context.filesDir, directoryName)
            directory.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(directory).invariantSeparatorsPath
                    result["$directoryName/$relative"] = file
                }
        }
        persistentRootFiles.forEach { fileName ->
            File(context.filesDir, fileName)
                .takeIf { it.isFile }
                ?.let { result[fileName] = it }
        }
        return result
    }

    /**
     * Returns true only for files owned by the chat media subsystem.
     *
     * Canonical containment alone is not sufficient during restore: a ZIP entry
     * such as `chat_media/banners/x` would still be inside filesDir but could
     * overwrite a different subsystem. Keep backup collection and restoration
     * anchored to this same allowlist.
     */
    fun isAllowedRestoreRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank() || '\u0000' in relativePath || '\\' in relativePath) return false
        if (relativePath.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(relativePath)) {
            return false
        }
        val segments = relativePath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || ':' in it }) return false
        if (segments.size == 1) return segments.single() in persistentRootFiles
        return segments.first() in persistentDirectories
    }
}
