package com.taostudio.tapaccounting

import android.net.Uri

/**
 * Determines which processing pipeline a user input should be routed to.
 *
 * ACCOUNTING — text and images always go to the accounting prompt.
 */
enum class InputAction {
    ACCOUNTING
}

/**
 * Holds a user-selected attachment copied to app storage but not yet sent.
 * Images use [base64]; PDFs use [pdfPagePayloads] (JPEG pages for the vision API).
 */
data class PendingImage(
    val uri: Uri?,
    val base64: String,
    val mime: String,
    val fileName: String = "",
    /** Rasterized JPEG pages (base64, mime) sent to the vision API for PDF attachments. */
    val pdfPagePayloads: List<Pair<String, String>> = emptyList()
) {
    val isImage: Boolean get() = ChatAttachmentHelper.isImageMime(mime)
    val isPdfAttachment: Boolean get() = ChatAttachmentHelper.isPdfMime(mime, fileName)
    val showsAsImageThumbnail: Boolean get() = isImage && !isPdfAttachment
    val showsAsFileCard: Boolean get() = isPdfAttachment
    val hasApiPayload: Boolean get() = base64.isNotBlank() || pdfPagePayloads.isNotEmpty()
}
