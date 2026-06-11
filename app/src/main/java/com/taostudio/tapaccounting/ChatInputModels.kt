package com.taostudio.tapaccounting

import android.net.Uri

/**
 * Determines which processing pipeline a user input should be routed to.
 *
 * ACCOUNTING          — the + entry; text and images always go to the accounting prompt.
 * AGENT_CHAT          — the Agent entry default; free-form conversation, images are context only.
 * AGENT_TO_ACCOUNTING — explicit "记账" button inside Agent; routes current content to accounting.
 */
enum class InputAction {
    ACCOUNTING,
    AGENT_CHAT,
    AGENT_TO_ACCOUNTING
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
