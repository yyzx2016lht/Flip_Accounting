package com.taostudio.tapaccounting.ui.main.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLeadingItemsTest {

    @Test
    fun `empty month places empty message below trend card`() {
        assertEquals(
            listOf(HomeLeadingItem.TREND_CARD, HomeLeadingItem.EMPTY_STATE),
            resolveHomeLeadingItems(showChart = true, showEmptyState = true)
        )
    }

    @Test
    fun `empty month without trend card only shows empty message`() {
        assertEquals(
            listOf(HomeLeadingItem.EMPTY_STATE),
            resolveHomeLeadingItems(showChart = false, showEmptyState = true)
        )
    }

    @Test
    fun `loaded bills can keep trend card without empty message`() {
        assertEquals(
            listOf(HomeLeadingItem.TREND_CARD),
            resolveHomeLeadingItems(showChart = true, showEmptyState = false)
        )
    }

    @Test
    fun `no enabled leading content produces no placeholder`() {
        assertEquals(
            emptyList<HomeLeadingItem>(),
            resolveHomeLeadingItems(showChart = false, showEmptyState = false)
        )
    }
}
