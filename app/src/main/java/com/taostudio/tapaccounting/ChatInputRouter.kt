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
     * @param chatMode              [ChatActivity.MODE_ACCOUNTING].
     * @param isExplicitAccounting  true when the user tapped the "记账" button.
     */
    fun resolveInputAction(chatMode: Int, isExplicitAccounting: Boolean): InputAction = when {
        chatMode == ChatActivity.MODE_ACCOUNTING -> InputAction.ACCOUNTING
        else -> InputAction.ACCOUNTING
    }

    /**
     * Returns `true` when [action] means the content should be sent to the accounting prompt.
     */
    fun shouldRouteToAccounting(action: InputAction): Boolean =
        action == InputAction.ACCOUNTING
}
