package com.taostudio.tapaccounting

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale

object ChatAttachmentHelper {

    const val MAX_IMAGE_BYTES = 4L * 1024L * 1024L
    const val MAX_DOCUMENT_BYTES = 10L * 1024L * 1024L
    const val MAX_VIDEO_BYTES = 50L * 1024L * 1024L
    const val MAX_AUDIO_BYTES = 8L * 1024L * 1024L
    const val MAX_INLINE_TEXT_CHARS = 120_000

    fun isImageMime(mime: String): Boolean = mime.startsWith("image/", ignoreCase = true)

    fun isVideoMime(mime: String): Boolean = mime.startsWith("video/", ignoreCase = true)

    fun isAudioMime(mime: String): Boolean = mime.startsWith("audio/", ignoreCase = true)

    fun audioFormatForMime(mime: String): String = when {
        mime.contains("wav", ignoreCase = true) -> "wav"
        mime.contains("mpeg", ignoreCase = true) || mime.contains("mp3", ignoreCase = true) -> "mp3"
        mime.contains("flac", ignoreCase = true) -> "flac"
        mime.contains("ogg", ignoreCase = true) -> "ogg"
        mime.contains("mp4", ignoreCase = true) || mime.contains("m4a", ignoreCase = true) -> "m4a"
        mime.startsWith("audio/", ignoreCase = true) ->
            mime.substringAfter("audio/", "wav").substringBefore(';').ifBlank { "wav" }
        else -> "wav"
    }

    fun isInlineTextMime(mime: String): Boolean {
        return mime.startsWith("text/", ignoreCase = true) ||
            mime.equals("application/json", ignoreCase = true)
    }

    fun isDocxMime(mime: String, fileName: String): Boolean {
        if (mime.contains("wordprocessingml", ignoreCase = true)) return true
        return fileName.lowercase(Locale.getDefault()).endsWith(".docx")
    }

    fun isLegacyDocMime(mime: String, fileName: String): Boolean {
        if (mime.equals("application/msword", ignoreCase = true)) return true
        val ext = fileName.lowercase(Locale.getDefault())
        return ext.endsWith(".doc") && !ext.endsWith(".docx")
    }

    fun shouldExtractAsInlineText(mime: String, fileName: String): Boolean {
        return isInlineTextMime(mime) || isDocxMime(mime, fileName)
    }

    fun isSupportedMime(mime: String, fileName: String = ""): Boolean {
        if (isImageMime(mime)) return true
        if (isVideoMime(mime)) return true
        if (isAudioMime(mime)) return true
        if (mime.equals("application/pdf", ignoreCase = true)) return true
        if (isDocxMime(mime, fileName)) return true
        if (isLegacyDocMime(mime, fileName)) return false
        return isInlineTextMime(mime)
    }

    fun maxBytesFor(mime: String): Long = when {
        isImageMime(mime) -> MAX_IMAGE_BYTES
        isVideoMime(mime) -> MAX_VIDEO_BYTES
        isAudioMime(mime) -> MAX_AUDIO_BYTES
        else -> MAX_DOCUMENT_BYTES
    }

    fun resolveMime(context: Context, uri: Uri, fileName: String): String {
        val fromResolver = context.contentResolver.getType(uri)?.trim().orEmpty()
        if (fromResolver.isNotBlank() && fromResolver != "application/octet-stream") {
            return fromResolver
        }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: when (ext) {
                "pdf" -> "application/pdf"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "doc" -> "application/msword"
                "md", "markdown" -> "text/markdown"
                "csv" -> "text/csv"
                "json" -> "application/json"
                "txt" -> "text/plain"
                "mp4" -> "video/mp4"
                "mov" -> "video/quicktime"
                "avi" -> "video/x-msvideo"
                "wmv" -> "video/x-ms-wmv"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "wav" -> "audio/wav"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                "aac" -> "audio/aac"
                "wma" -> "audio/x-ms-wma"
                else -> "application/octet-stream"
            }
    }

    fun resolveDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        cursor.getString(index)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.trim().orEmpty().ifBlank { "file" }
    }

    fun historyPlaceholder(mimes: List<String>): String {
        if (mimes.isEmpty()) return "[用户发送了附件]"
        return when {
            mimes.all(::isImageMime) -> "[用户发送了图片]"
            mimes.all(::isVideoMime) -> "[用户发送了视频]"
            mimes.all(::isAudioMime) -> "[用户发送了语音/音频]"
            mimes.all { it.equals("application/pdf", ignoreCase = true) } -> "[用户发送了 PDF 文件]"
            mimes.any { isDocxMime(it, "") || it.contains("wordprocessingml", ignoreCase = true) } &&
                mimes.none { !isDocxMime(it, "") && !it.contains("wordprocessingml", ignoreCase = true) && !isImageMime(it) } ->
                "[用户发送了 Word 文档]"
            mimes.any(::isInlineTextMime) && mimes.none { !isInlineTextMime(it) && !isImageMime(it) } ->
                "[用户发送了文本文件]"
            else -> "[用户发送了附件]"
        }
    }

    fun fileTypeLabel(context: Context, mime: String, fileName: String = ""): String = when {
        mime.equals("application/pdf", ignoreCase = true) ||
            fileName.lowercase(Locale.getDefault()).endsWith(".pdf") ->
            context.getString(R.string.chat_file_type_pdf)
        isDocxMime(mime, fileName) ->
            context.getString(R.string.chat_file_type_docx)
        isVideoMime(mime) ->
            context.getString(R.string.chat_file_type_video)
        isAudioMime(mime) ->
            context.getString(R.string.chat_file_type_audio)
        isInlineTextMime(mime) ->
            context.getString(R.string.chat_file_type_text)
        else -> context.getString(R.string.chat_file_type_generic)
    }

    fun encodeFileMessageContent(mime: String, fileName: String): String = "$mime|$fileName"

    fun decodeFileMessageContent(content: String): Pair<String, String>? {
        val separator = content.indexOf('|')
        if (separator <= 0 || separator >= content.lastIndex) return null
        val mime = content.substring(0, separator).trim()
        val fileName = content.substring(separator + 1).trim()
        if (mime.isBlank() || fileName.isBlank()) return null
        return mime to fileName
    }

    fun groupAttachmentsForDisplay(attachments: List<PendingImage>): Pair<List<PendingImage>, List<PendingImage>> {
        val images = mutableListOf<PendingImage>()
        val files = mutableListOf<PendingImage>()
        val pdfPageGroups = mutableMapOf<String, PendingImage>()
        attachments.forEach { attachment ->
            when {
                attachment.isInlineText -> Unit
                attachment.showsAsImageThumbnail -> images.add(attachment)
                attachment.sourceUri != null -> {
                    val key = attachment.fileName.substringBefore(" (").trim().ifBlank { attachment.fileName }
                    pdfPageGroups.putIfAbsent(
                        key,
                        attachment.copy(
                            mime = "application/pdf",
                            uri = attachment.sourceUri,
                            fileName = key
                        )
                    )
                }
                else -> files.add(attachment)
            }
        }
        files.addAll(pdfPageGroups.values)
        return images to files
    }

    fun attachmentSummaryLabel(mimes: List<String>): String {
        val images = mimes.count(::isImageMime)
        val videos = mimes.count(::isVideoMime)
        val audios = mimes.count(::isAudioMime)
        val pdfs = mimes.count { it.equals("application/pdf", ignoreCase = true) }
        val docx = mimes.count { isDocxMime(it, "") || it.contains("wordprocessingml", ignoreCase = true) }
        val texts = mimes.count(::isInlineTextMime)
        val parts = buildList {
            if (images > 0) add("${images}张图片")
            if (videos > 0) add("${videos}个视频")
            if (audios > 0) add("${audios}个音频")
            if (pdfs > 0) add("${pdfs}个 PDF")
            if (docx > 0) add("${docx}个 Word")
            if (texts > 0) add("${texts}个文本文件")
            val other = mimes.size - images - videos - audios - pdfs - docx - texts
            if (other > 0) add("${other}个文件")
        }
        return parts.joinToString("、").ifBlank { "附件" }
    }
}
