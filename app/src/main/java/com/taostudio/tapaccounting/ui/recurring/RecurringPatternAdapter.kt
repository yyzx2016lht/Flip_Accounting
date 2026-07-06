package com.taostudio.tapaccounting.ui.recurring

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.data.local.entity.RecurringStatus
import com.taostudio.tapaccounting.logic.BillDisplayFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecurringPatternAdapter(
    private val onConfirm: (RecurringPattern) -> Unit,
    private val onDismiss: (RecurringPattern) -> Unit,
    private val onRestorePending: (RecurringPattern) -> Unit,
    private val onEdit: (RecurringPattern) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<RecurringPatternAdapter.ViewHolder>() {

    private val items = mutableListOf<RecurringPattern>()
    private val selectedIds = mutableSetOf<Long>()
    private var selectionMode = false
    private val df = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val fullDf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun submitList(newItems: List<RecurringPattern>) {
        items.clear()
        items.addAll(newItems)
        selectedIds.retainAll(newItems.map { it.id }.toSet())
        if (selectedIds.isEmpty()) selectionMode = false
        onSelectionChanged(selectedIds.size)
        notifyDataSetChanged()
    }

    fun selectedItems(): List<RecurringPattern> = items.filter { selectedIds.contains(it.id) }

    fun clearSelection() {
        selectionMode = false
        selectedIds.clear()
        onSelectionChanged(0)
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
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_recurring_status)
        private val tvNextExpected: TextView = itemView.findViewById(R.id.tv_next_expected)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_recurring_meta)
        private val tvSelected: TextView = itemView.findViewById(R.id.tv_recurring_selected)
        private val layoutActions: View = itemView.findViewById(R.id.layout_actions)
        private val btnConfirm: TextView = itemView.findViewById(R.id.btn_confirm)
        private val btnDismiss: TextView = itemView.findViewById(R.id.btn_dismiss)

        fun bind(pattern: RecurringPattern) {
            val context = itemView.context
            tvMerchant.text = pattern.merchantKey
            tvAmount.text = context.getString(
                R.string.recurring_amount_approx,
                String.format(Locale.getDefault(), "%.2f", pattern.amountApprox)
            )
            tvFrequency.text = when (pattern.frequency) {
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.WEEKLY ->
                    context.getString(R.string.recurring_frequency_weekly)
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.MONTHLY ->
                    context.getString(R.string.recurring_frequency_monthly)
                com.taostudio.tapaccounting.data.local.entity.RecurringFrequency.YEARLY ->
                    context.getString(R.string.recurring_frequency_yearly)
            }
            tvLastSeen.text = context.getString(R.string.recurring_last_seen_fmt, df.format(Date(pattern.lastSeenAt)))
            tvNextExpected.text = pattern.nextExpectedAt?.let {
                context.getString(R.string.recurring_next_expected, fullDf.format(Date(it)))
            } ?: context.getString(R.string.recurring_next_unknown)
            tvMeta.text = context.getString(
                R.string.recurring_meta_fmt,
                pattern.categoryName
                    ?.let { BillDisplayFormatter.normalizeCategoryDisplayName(it) }
                    ?.ifBlank { null }
                    ?: typeLabel(pattern),
                if (pattern.toAccountName.isNotBlank()) {
                    "${pattern.accountName.orEmpty()} -> ${pattern.toAccountName}"
                } else {
                    pattern.accountName ?: pattern.bookName
                }
            )

            val now = System.currentTimeMillis()
            val dueSoonAt = now + 3L * 24L * 3600_000L
            val statusText = when {
                pattern.nextExpectedAt != null && pattern.nextExpectedAt < now ->
                    context.getString(R.string.recurring_status_overdue)
                pattern.nextExpectedAt != null && pattern.nextExpectedAt <= dueSoonAt ->
                    context.getString(R.string.recurring_status_due_soon)
                pattern.status == RecurringStatus.SUGGESTED ->
                    context.getString(R.string.recurring_status_suggested)
                pattern.status == RecurringStatus.CONFIRMED ->
                    context.getString(R.string.recurring_status_tracking)
                pattern.status == RecurringStatus.DISMISSED ->
                    context.getString(R.string.recurring_status_muted)
                else -> context.getString(R.string.recurring_status_suggested)
            }
            tvStatus.text = statusText
            val selected = selectedIds.contains(pattern.id)
            tvSelected.visibility = if (selectionMode) View.VISIBLE else View.GONE
            tvSelected.alpha = if (selected) 1f else 0.28f
            tvSelected.text = if (selected) "✓" else ""
            itemView.alpha = if (selectionMode && !selected) 0.72f else 1f
            itemView.setOnClickListener {
                if (selectionMode) {
                    toggleSelection(pattern)
                } else {
                    onEdit(pattern)
                }
            }
            itemView.setOnLongClickListener {
                selectionMode = true
                toggleSelection(pattern)
                true
            }

            layoutActions.visibility = View.VISIBLE
            when (pattern.status) {
                RecurringStatus.SUGGESTED -> {
                    btnConfirm.visibility = View.VISIBLE
                    btnDismiss.visibility = View.VISIBLE
                    btnConfirm.text = context.getString(R.string.recurring_add_to_mine)
                    btnDismiss.text = context.getString(R.string.recurring_stop_reminding)
                    btnConfirm.setOnClickListener { onConfirm(pattern) }
                    btnDismiss.setOnClickListener { onDismiss(pattern) }
                }
                RecurringStatus.CONFIRMED -> {
                    btnConfirm.visibility = View.GONE
                    btnDismiss.visibility = View.GONE
                }
                RecurringStatus.DISMISSED -> {
                    btnConfirm.visibility = View.VISIBLE
                    btnDismiss.visibility = View.GONE
                    btnConfirm.text = context.getString(R.string.recurring_restore_pending)
                    btnConfirm.setOnClickListener { onRestorePending(pattern) }
                }
            }
        }

        private fun toggleSelection(pattern: RecurringPattern) {
            if (selectedIds.contains(pattern.id)) {
                selectedIds.remove(pattern.id)
            } else {
                selectedIds.add(pattern.id)
            }
            if (selectedIds.isEmpty()) selectionMode = false
            onSelectionChanged(selectedIds.size)
            notifyDataSetChanged()
        }

        private fun typeLabel(pattern: RecurringPattern): String {
            val context = itemView.context
            return when {
                pattern.billType == com.taostudio.tapaccounting.data.local.entity.Bill.TYPE_INCOME ->
                    context.getString(R.string.income)
                pattern.billType == com.taostudio.tapaccounting.data.local.entity.Bill.TYPE_TRANSFER &&
                    pattern.billSubType == com.taostudio.tapaccounting.data.local.entity.Bill.SUBTYPE_REPAYMENT ->
                    context.getString(R.string.repayment)
                pattern.billType == com.taostudio.tapaccounting.data.local.entity.Bill.TYPE_TRANSFER ->
                    context.getString(R.string.transfer)
                else -> context.getString(R.string.expense)
            }
        }
    }
}
