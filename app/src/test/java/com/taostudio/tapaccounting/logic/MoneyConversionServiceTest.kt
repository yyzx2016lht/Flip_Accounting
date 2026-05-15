package com.taostudio.tapaccounting.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyConversionServiceTest {

    @Test
    fun missingCurrencies_detectsUnknownNonCny() {
        val missing = MoneyConversionService.missingCurrencies(
            currencies = listOf("CNY", "USD", "ABC"),
            rateProvider = { code -> if (code == "USD") 0.14 else null }
        )

        assertEquals(setOf("ABC"), missing)
    }

    @Test
    fun convertAmountBetweenCurrencies_worksAcrossCurrencies() {
        val converted = MoneyConversionService.convertAmountBetweenCurrencies(
            amount = 14.0,
            fromCurrency = "USD",
            toCurrency = "CNY",
            rateProvider = { code -> if (code == "USD") 0.14 else if (code == "CNY") 1.0 else null }
        )

        assertEquals(100.0, converted, 0.000001)
    }

    @Test
    fun requireCurrenciesAvailable_throwsWhenMissing() {
        runCatching {
            MoneyConversionService.requireCurrenciesAvailable(
                currencies = listOf("USD", "XYZ"),
                rateProvider = { code -> if (code == "USD") 0.14 else null }
            )
        }.onSuccess {
            throw AssertionError("Expected MissingCurrencyRateException")
        }.onFailure { ex ->
            assertTrue(ex is MissingCurrencyRateException)
            val missing = (ex as MissingCurrencyRateException).missingCurrencies
            assertEquals(setOf("XYZ"), missing)
        }
    }
}

