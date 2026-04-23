package tao.test.flipaccounting.ui.main.home

import tao.test.flipaccounting.AmountFormatHelper
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import kotlin.math.max

internal object HomeBillFormatHelper {
    fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    fun stripRefundPrefix(categoryName: String): String {
        return BillDisplayFormatter.stripRefundPrefix(categoryName)
    }

    fun originalAmountOfExpenseBill(bill: Bill): Double {
        val base = if (bill.originalAmount > 0.0) bill.originalAmount else bill.amount
        return max(base, bill.amount)
    }

    fun refundAmountOfExpenseBill(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || isRefundBill(bill)) return 0.0
        return (originalAmountOfExpenseBill(bill) - bill.amount).coerceAtLeast(0.0)
    }

    fun formatMoney(amount: Double, currency: String = "CNY"): String {
        val symbol = CurrencyManager.getSymbol(currency)
        return AmountFormatHelper.formatCurrency(symbol, amount)
    }

    fun formatRateValue(rate: Double): String {
        return BillDisplayFormatter.formatRateValue(rate)
    }

    fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(accountCurrency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val accountAmount = bill.amount * bill.exchangeRate
        return "${formatMoney(bill.amount, bill.currency)} × ${formatRateValue(bill.exchangeRate)} = ${formatMoney(accountAmount, accountCurrency)}"
    }

    fun buildCrossCurrencyListSuffix(
        bill: Bill,
        accountCurrency: String?
    ): String? {
        val currency = accountCurrency ?: return null
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(currency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val accountAmount = bill.amount * bill.exchangeRate
        return "${formatMoney(bill.amount, bill.currency)} * ${formatRateValue(bill.exchangeRate)} = ${formatMoney(accountAmount, currency)}"
    }

    fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? {
        return BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)
    }
}
