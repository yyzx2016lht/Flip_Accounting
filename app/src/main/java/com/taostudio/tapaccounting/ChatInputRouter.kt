package com.taostudio.tapaccounting

/**
 * Pure-function routing logic for the refactored chat input model.
 * Every function here is side-effect-free and Android-free (except for constants),
 * making it straightforward to unit-test.
 */
object ChatInputRouter {

    /**
     * Decide which pipeline the current send action should use.
     *
     * @param chatMode              [ChatActivity.MODE_ACCOUNTING] or [ChatActivity.MODE_AGENT].
     * @param isExplicitAccounting  true when the user tapped the Agent "记账" button.
     */
    fun resolveInputAction(chatMode: Int, isExplicitAccounting: Boolean): InputAction = when {
        chatMode == ChatActivity.MODE_ACCOUNTING -> InputAction.ACCOUNTING
        isExplicitAccounting -> InputAction.AGENT_TO_ACCOUNTING
        else -> InputAction.AGENT_CHAT
    }

    /**
     * Returns `true` when [action] means the content should be sent to the accounting prompt
     * (as opposed to the Agent orchestrator).
     */
    fun shouldRouteToAccounting(action: InputAction): Boolean =
        action == InputAction.ACCOUNTING || action == InputAction.AGENT_TO_ACCOUNTING

    /**
     * Format the combined context text that the Agent receives when the user sends images.
     * Delegates to [ChatImageComposer] for multi-image labelling.
     */
    fun formatAgentImageContext(ocrResults: List<String>, userText: String): String =
        ChatImageComposer.formatAgentImageContext(ocrResults, userText)

    /** Backward-compatible single-image overload. */
    fun formatAgentImageContext(ocrText: String, userText: String): String =
        ChatImageComposer.formatAgentSingleImageContext(ocrText, userText)
}
