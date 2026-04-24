package tao.test.flipaccounting.ui.main.stats

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BillAdapter(private val bills: List<Bill>) : RecyclerView.Adapter<BillAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCategory: TextView = view.findViewById(R.id.tv_bill_category)
        val tvDetail: TextView = view.findViewById(R.id.tv_bill_detail)
        val tvAmount: TextView = view.findViewById(R.id.tv_bill_amount)
        val tvTime: TextView = view.findViewById(R.id.tv_bill_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_transaction, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bill = bills[position]
        val isTransfer  = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val categoryText = when {
            isRepayment -> "还款"
            isTransfer  -> "转账"
            else        -> BillDisplayFormatter.formatCategoryByPreference(
                categoryName = bill.categoryName,
                showFullCategory = Prefs.isShowBillFullCategory(holder.itemView.context)
            ).ifBlank { "未分类" }
        }
        val detailSuffix = if (isTransfer) {
            buildString {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            }
        } else {
            bill.accountName
        }
        val (primary, secondary) = BillDisplayFormatter.resolvePrimarySecondaryText(
            categoryText = categoryText,
            remarkText = bill.remark,
            suffixText = detailSuffix,
            remarkPriority = Prefs.isBillRemarkPriority(holder.itemView.context)
        )
        holder.tvCategory.text = primary
        holder.tvDetail.text = secondary.ifBlank { bill.accountName }
        
        val refundAmount = BillDisplayFormatter.refundAmountOfExpenseBill(bill)
        holder.tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val prefix = when (bill.type) {
                Bill.TYPE_EXPENSE -> "-"
                Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            String.format(
                Locale.getDefault(),
                "%s%s%.2f",
                prefix,
                tao.test.flipaccounting.logic.CurrencyManager.getSymbol(bill.currency),
                bill.amount
            )
        }
        
        if (bill.type == Bill.TYPE_EXPENSE) {
            holder.tvAmount.setTextColor(0xFFD32F2F.toInt())
        } else if (bill.type == Bill.TYPE_INCOME) {
            holder.tvAmount.setTextColor(0xFF388E3C.toInt())
        } else {
            holder.tvAmount.setTextColor(0xFF757575.toInt())
        }

        holder.tvTime.visibility = View.VISIBLE
        holder.tvTime.text = dateFormat.format(Date(bill.time))
    }

    override fun getItemCount() = bills.size
}
