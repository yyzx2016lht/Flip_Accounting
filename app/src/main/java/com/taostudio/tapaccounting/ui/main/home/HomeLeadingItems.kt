package com.taostudio.tapaccounting.ui.main.home

internal enum class HomeLeadingItem {
    TREND_CARD,
    EMPTY_STATE
}

internal fun resolveHomeLeadingItems(
    showChart: Boolean,
    showEmptyState: Boolean
): List<HomeLeadingItem> = buildList {
    if (showChart) add(HomeLeadingItem.TREND_CARD)
    if (showEmptyState) add(HomeLeadingItem.EMPTY_STATE)
}
