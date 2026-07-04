package com.taostudio.tapaccounting

import kotlin.math.abs

/** 将紧邻的「账单卡片 + AI 自然回复」视觉上连成一组。 */
object ChatDisplayLinkHelper {

    private const val GROUP_WINDOW_MS = 120_000L

    fun applyBillReplyGrouping(messages: MutableList<ChatDisplayItem>) {
        if (messages.isEmpty()) return
        for (index in messages.indices) {
            val item = messages[index]
            val prev = messages.getOrNull(index - 1)
            val grouped = shouldGroupWithPreviousBill(prev, item)
            val compactTop = grouped
            if (item.groupedWithBillReply != grouped || item.compactGroupedLayout != compactTop) {
                messages[index] = item.copy(
                    groupedWithBillReply = grouped,
                    compactGroupedLayout = compactTop
                )
            }
        }
    }

    private fun shouldGroupWithPreviousBill(prev: ChatDisplayItem?, current: ChatDisplayItem): Boolean {
        if (prev == null) return false
        if (prev.msgType != ChatActivity.MSG_TYPE_AI_BILL) return false
        if (current.msgType != ChatActivity.MSG_TYPE_AI_TEXT) return false
        if (current.isLoading) return false
        if (current.content.isBlank()) return false
        return abs(current.timestamp - prev.timestamp) <= GROUP_WINDOW_MS
    }
}
