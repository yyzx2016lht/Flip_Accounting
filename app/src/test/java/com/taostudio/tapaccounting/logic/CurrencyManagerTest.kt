package com.taostudio.tapaccounting.logic

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CurrencyManagerTest {

    @Before
    fun setUp() {
        CurrencyManager.clearMissingRateCurrencies()
    }

    @After
    fun tearDown() {
        CurrencyManager.clearMissingRateCurrencies()
    }

    @Test
    fun convertToCny_marksMissingCurrency_whenRateNotFound() {
        val amount = CurrencyManager.convertToCny(123.45, "XYZ")

        assertEquals(123.45, amount, 0.000001)
        assertTrue(CurrencyManager.getMissingRateCurrencies().contains("XYZ"))
    }

    @Test
    fun getRate_marksMissingCurrency_whenUnknown() {
        val rate = CurrencyManager.getRate("unknown")

        assertEquals(null, rate)
        assertTrue(CurrencyManager.getMissingRateCurrencies().contains("UNKNOWN"))
    }

    @Test
    fun convertToCny_supportsLowercaseCurrencyCode() {
        val amountCny = CurrencyManager.convertToCny(0.14, "usd")

        assertEquals(1.0, amountCny, 0.000001)
    }

    @Test
    fun missingRateState_canBeObservedAndCleared() {
        CurrencyManager.getRate("NOPE")

        assertTrue(CurrencyManager.hasMissingRates())
        CurrencyManager.clearMissingRateCurrencies()
        assertFalse(CurrencyManager.hasMissingRates())
    }
}

