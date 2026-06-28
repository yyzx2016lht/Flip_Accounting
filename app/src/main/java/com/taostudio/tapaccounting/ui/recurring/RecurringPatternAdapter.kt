package com.taostudio.tapaccounting.ui.recurring

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecurringPatternAdapter(
    private val onConfirm: (RecurringPattern) -> Unit,
    private val onDismiss: (RecurringPattern) -> Unit
) : RecyclerView.Adapter<RecurringPatternAdapter.ViewHolder>() {

    private val items = mutableListOf<RecurringPattern>()
    private val df = SimpleDateFormat("MM-dd", Locale.getDefault())

    fun submitList(newItems: List<RecurringPattern>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recurring_pattern, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvMerchant: TextView = itemView.findViewById(R.id.tv_merchant_key)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_amount_approx)
        private val tvFrequency: TextView = itemView.findViewById(R.id.tv_frequency)
        private val tvLastSeen: TextView = itemView.findViewById(R.id.tv_last_seen)
        private val layoutActions: View = itemView.findViewById(R.id.layout_actions)
        private val btnConfirm: View = itemView.findViewById(R.id.btn_confirm)
        private val btnDismiss: View = itemView.findViewById(R.id.btn_dismiss)

        fun bind(pattern: RecurringPattern) {
            tvMerchant.text = pattern.merchantKey
            tvAmount.text = "¥${String.format("%.2f", pattern.amountApprox)}"
            tvFrequency.text = when (pattern.frequency) {
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.WEEKLY ->
                    itemView.context.getString(R.string.recurring_frequency_weekly)
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.MONTHLY ->
                    itemView.context.getString(R.string.recurring_frequency_monthly)
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.YEARLY ->
                    itemView.context.getString(R.string.recurring_frequency_yearly)
            }
            tvLastSeen.text = "最近：${df.format(Date(pattern.lastSeenAt))}"

            // 待确认 Tab 显示操作按钮
            layoutActions.visibility = if (pattern.status == RecurringStatus.SUGGESTED) View.VISIBLE else View.GONE

            btnConfirm.setOnClickListener { onConfirm(pattern) }
            btnDismiss.setOnClickListener { onDismiss(pattern) }
        }
    }
}
