package tao.test.flipaccounting

import java.util.Locale

object AmountFormatHelper {

    private const val FORMAT_GROUPED = "%,.2f"
    private const val FORMAT_PLAIN = "%.2f"

    fun isGroupingEnabledByDefault(): Boolean {
        return runCatching {
            Prefs.isAmountGroupingEnabled(FlipApplication.app())
        }.getOrDefault(true)
    }

    fun formatAmount(amount: Double, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        val pattern = if (useGrouping) FORMAT_GROUPED else FORMAT_PLAIN
        return String.format(Locale.getDefault(), pattern, amount)
    }

    fun formatCurrency(symbol: String, amount: Double, useGrouping: Boolean = isGroupingEnabledByDefault()): String {
        return "$symbol${formatAmount(amount, useGrouping)}"
    }
}
