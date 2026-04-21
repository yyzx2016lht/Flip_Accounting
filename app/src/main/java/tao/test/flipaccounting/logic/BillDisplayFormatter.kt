package tao.test.flipaccounting.logic

import android.graphics.Color
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import tao.test.flipaccounting.data.local.entity.Bill
import java.util.Locale

object BillDisplayFormatter {

    private const val DELETED_ASSET_SUFFIX = "（已删除）"

    fun stripRefundPrefix(categoryName: String): String {
        return CategoryNameNormalizer.stripRefundPrefix(categoryName)
    }

    fun hasRefundPrefix(categoryName: String): Boolean {
        return categoryName.trim().startsWith("退款：") || categoryName.trim().startsWith("退款·")
    }

    fun formatAccountNameWithDeletedTag(accountName: String): CharSequence {
        val raw = accountName.ifBlank { "未设置账户" }
        val idx = raw.indexOf(DELETED_ASSET_SUFFIX)
        if (idx < 0) return raw

        val builder = SpannableStringBuilder()
        val base = raw.substring(0, idx).ifBlank { "未设置账户" }
        val tag = raw.substring(idx)
        builder.append(base)
        val tagSpan = SpannableString(tag).apply {
            setSpan(
                ForegroundColorSpan(Color.parseColor("#A1A8AF")),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        builder.append(tagSpan)
        return builder
    }

    fun buildRefundCategoryLabel(categoryName: String): String {
        val baseName = stripRefundPrefix(categoryName).ifBlank { "未分类" }
        return "退款：$baseName"
    }

    fun formatMoney(amount: Double, currency: String): String {
        val symbol = CurrencyManager.getSymbol(currency)
        return "$symbol${String.format(Locale.getDefault(), "%.2f", amount)}"
    }

    fun formatRateValue(rate: Double): String {
        return String.format(Locale.getDefault(), "%.2f", rate)
    }

    fun originalAmountOfExpenseBill(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    fun refundAmountOfExpenseBill(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || bill.subType == Bill.SUBTYPE_REFUND) return 0.0
        return (originalAmountOfExpenseBill(bill) - bill.amount).coerceAtLeast(0.0)
    }

    fun buildRefundedExpenseAmountText(
        netAmount: Double,
        originalAmount: Double,
        currency: String
    ): CharSequence {
        val currentText = "-${formatMoney(netAmount, currency)}"
        val originalText = "-${formatMoney(originalAmount, currency)}"
        val builder = SpannableStringBuilder(currentText)
        builder.append("  ")

        val struck = SpannableString(originalText).apply {
            setSpan(StrikethroughSpan(), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(Color.parseColor("#A1A8AF")),
                0,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        builder.append(struck)
        return builder
    }

    fun buildRefundFlowRemark(baseRemark: String, refunds: List<Bill>): String {
        if (refunds.isEmpty()) return baseRemark.ifBlank { "无备注" }
        val refundSummary = refunds.joinToString("；") { refund ->
            "退款 ${formatMoney(refund.amount, refund.currency)} -> ${refund.accountName.ifBlank { "未设置账户" }}"
        }
        return if (baseRemark.isBlank()) refundSummary else "$baseRemark\n$refundSummary"
    }

    fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(accountCurrency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val accountAmount = bill.amount * bill.exchangeRate
        return "${formatMoney(bill.amount, bill.currency)} × ${formatRateValue(bill.exchangeRate)} = ${formatMoney(accountAmount, accountCurrency)}"
    }

    fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? {
        if (bill.subType == Bill.SUBTYPE_REFUND) return null
        if (bill.currency.equals(targetCurrency, ignoreCase = true)) return null
        if (bill.exchangeRate == 1.0) return null
        val targetAmount = bill.amount * bill.exchangeRate
        return "≈ ${formatMoney(targetAmount, targetCurrency)}"
    }
}
