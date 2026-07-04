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
 * Images/PDF use [base64]; plain-text files may use [inlineText] instead.
 */
data class PendingImage(
    val uri: Uri?,
    val base64: String,
    val mime: String,
    val fileName: String = "",
    val inlineText: String? = null,
    /** When a PDF is rasterized to JPEG pages, points at the original PDF for UI display. */
    val sourceUri: Uri? = null
) {
    val isImage: Boolean get() = ChatAttachmentHelper.isImageMime(mime)
    val isInlineText: Boolean get() = inlineText != null
    /** True for real photos/screenshots; false for PDF pages rendered as JPEG. */
    val showsAsImageThumbnail: Boolean get() = isImage && sourceUri == null
    val showsAsFileCard: Boolean get() = !isInlineText && base64.isNotBlank() && !showsAsImageThumbnail
}
