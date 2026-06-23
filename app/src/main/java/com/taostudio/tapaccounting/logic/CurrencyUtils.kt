package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.CurrencyData
import java.text.NumberFormat
import java.util.Locale

object CurrencyUtils {
    fun formatAmount(amount: Double, currencyCode: String): String {
        val info = CurrencyData.getInfo(currencyCode)
        val symbol = info?.symbol ?: ""
        val rounded = MoneyConversionService.roundMoneyForCurrency(amount, currencyCode)

        val formatter = NumberFormat.getNumberInstance(Locale.getDefault())
        val decimals = decimalPlaces(currencyCode)
        formatter.minimumFractionDigits = decimals
        formatter.maximumFractionDigits = decimals

        return "$symbol${formatter.format(rounded)}"
    }

    fun decimalPlaces(currencyCode: String): Int {
        return when (currencyCode.uppercase()) {
            "JPY", "KRW", "VND", "HUF", "CLP", "ISK", "BIF", "DJF", "GNF", "KMF", "MGA", "PYG", "RWF", "UGX", "VUV", "XAF", "XOF", "XPF" -> 0
            else -> 2
        }
    }
}

