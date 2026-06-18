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
 * Holds a user-selected image that has been copied to app storage but not yet sent.
 * [uri] is the local file URI (for display; nullable for testability),
 * [base64] and [mime] are for the AI payload.
 */
data class PendingImage(
    val uri: Uri?,
    val base64: String,
    val mime: String
)
