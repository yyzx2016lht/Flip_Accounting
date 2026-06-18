package com.taostudio.tapaccounting.chat.agent

import java.util.UUID

/**
 * Centralized mode classification for chat conversations.
 *
 * Conversation IDs follow these conventions:
 * - Agent mode: "agent_<timestamp>_<uuid>"
 * - Accounting mode: "conv_<timestamp>_<uuid>" (new) or "legacy" / other old formats
 *
 * All mode detection, filtering, and ID generation MUST go through this class.
 * Do NOT use scattered startsWith("agent_") checks elsewhere.
 */
enum class ChatConversationMode {
    ACCOUNTING,
    AGENT;

    companion object {
        private const val AGENT_PREFIX = "agent_"

        /**
         * Determine the mode of a conversation by its ID.
         * Legacy and unknown formats default to ACCOUNTING.
         */
        fun modeOf(conversationId: String): ChatConversationMode {
            return if (conversationId.startsWith(AGENT_PREFIX)) AGENT else ACCOUNTING
        }

        /**
         * Check if a conversationId belongs to the given mode.
         */
        fun belongsTo(conversationId: String, mode: ChatConversationMode): Boolean {
            return modeOf(conversationId) == mode
        }

        /**
         * Generate a new conversation ID for the given mode.
         */
        fun createId(mode: ChatConversationMode): String {
            val prefix = when (mode) {
                AGENT -> AGENT_PREFIX
                ACCOUNTING -> "conv_"
            }
            return "${prefix}${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        }

        /**
         * Get the SQL prefix pattern for the given mode.
         * Note: Room's LIKE uses SQL wildcards where _ matches any single char.
         * We use GLOB or explicit patterns to avoid false matches.
         */
        fun prefixPattern(mode: ChatConversationMode): String = when (mode) {
            AGENT -> AGENT_PREFIX
            ACCOUNTING -> "conv_"
        }

        /**
         * Convert ChatActivity mode int to ChatConversationMode.
         */
        fun fromActivityMode(activityMode: Int): ChatConversationMode {
            return when (activityMode) {
                1 -> AGENT  // ChatActivity.MODE_AGENT
                else -> ACCOUNTING
            }
        }

        /**
         * Convert ChatConversationMode to ChatActivity mode int.
         */
        fun toActivityMode(mode: ChatConversationMode): Int {
            return when (mode) {
                AGENT -> 1   // ChatActivity.MODE_AGENT
                ACCOUNTING -> 0  // ChatActivity.MODE_ACCOUNTING
            }
        }
    }
}
