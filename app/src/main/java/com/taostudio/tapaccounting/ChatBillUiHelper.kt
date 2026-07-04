package com.taostudio.tapaccounting

import com.taostudio.tapaccounting.data.local.entity.Bill
import java.util.Locale
import kotlin.math.abs

object ChatBillUiHelper {

    const val COLLAPSED_BILL_VISIBLE_COUNT = 3

    fun isBillActive(item: ChatDisplayItem, bill: Bill): Boolean =
        !item.isDeprecated &&
            !item.deprecatedBillIds.contains(bill.id) &&
            !item.editedBillIds.contains(bill.id)

    fun isBillDeletable(item: ChatDisplayItem, bill: Bill): Boolean = isBillActive(item, bill)

    fun isBillConfirmable(item: ChatDisplayItem, bill: Bill): Boolean =
        isBillActive(item, bill) && bill.id <= 0L

    fun activeBills(item: ChatDisplayItem): List<Bill> =
        item.bills.filter { isBillActive(item, it) }

    fun deletableBills(item: ChatDisplayItem): List<Bill> =
        item.bills.filter { isBillDeletable(item, it) }

    fun confirmableBills(item: ChatDisplayItem): List<Bill> =
        item.bills.filter { isBillConfirmable(item, it) }

    fun buildBatchSummaryText(bills: List<Bill>, deprecatedBillIds: Set<Long>): String {
        val active = bills.filter { it.id !in deprecatedBillIds }
        if (active.isEmpty()) return ""
        val expense = active.filter { it.type == Bill.TYPE_EXPENSE }.sumOf { it.amount }
        val income = active.filter { it.type == Bill.TYPE_INCOME }.sumOf { it.amount }
        val transfer = active.count { it.type == Bill.TYPE_TRANSFER }
        val parts = mutableListOf<String>()
        parts += "共 ${active.size} 笔"
        if (expense > 0.001) {
            parts += "支出 ${formatMoney(expense)}"
        }
        if (income > 0.001) {
            parts += "收入 ${formatMoney(income)}"
        }
        if (transfer > 0) {
            parts += "转账 $transfer 笔"
        }
        return parts.joinToString(" · ")
    }

    fun buildCopySummary(item: ChatDisplayItem): String {
        val active = activeBills(item)
        if (active.isEmpty()) return "（账单已全部撤销）"
        return active.joinToString("\n") { bill ->
            val typeLabel = when (bill.type) {
                Bill.TYPE_INCOME -> "收入"
                Bill.TYPE_TRANSFER -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) "还款" else "转账"
                else -> "支出"
            }
            val amount = formatMoney(bill.amount)
            val remark = bill.remark.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "$typeLabel $amount · ${bill.categoryName}$remark"
        }
    }

    private fun formatMoney(value: Double): String =
        String.format(Locale.getDefault(), "¥%.2f", value)

    fun billsForDisplay(item: ChatDisplayItem, expanded: Boolean): List<Bill> {
        if (item.bills.size <= COLLAPSED_BILL_VISIBLE_COUNT || expanded) {
            return item.bills
        }
        return item.bills.take(COLLAPSED_BILL_VISIBLE_COUNT)
    }

    fun hiddenBillCount(item: ChatDisplayItem, expanded: Boolean): Int {
        if (expanded || item.bills.size <= COLLAPSED_BILL_VISIBLE_COUNT) return 0
        return item.bills.size - COLLAPSED_BILL_VISIBLE_COUNT
    }
}
