package tao.test.flipaccounting.logic

import tao.test.flipaccounting.CurrencyData
import java.util.Locale

object CurrencyUtils {
    fun formatAmount(amount: Double, currencyCode: String): String {
        val info = CurrencyData.getInfo(currencyCode)
        val symbol = info?.symbol ?: ""
        return String.format(Locale.getDefault(), "%s%.2f", symbol, BillAssetImpactService.roundMoney(amount))
    }
}
