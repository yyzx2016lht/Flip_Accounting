package com.taostudio.tapaccounting.ui.budget

import android.graphics.Color
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Budget
import com.taostudio.tapaccounting.logic.BudgetService

class BudgetAdapter(
    private val onItemClick: (Budget) -> Unit,
    private val onItemLongClick: (Budget) -> Unit
) : RecyclerView.Adapter<BudgetAdapter.ViewHolder>() {

    private val items = mutableListOf<BudgetService.BudgetOverview>()

    fun submitList(newItems: List<BudgetService.BudgetOverview>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_budget, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategory: TextView = itemView.findViewById(R.id.tv_budget_category)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_budget_amount)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_budget)
        private val tvUsed: TextView = itemView.findViewById(R.id.tv_budget_used)
        private val tvPercent: TextView = itemView.findViewById(R.id.tv_budget_percent)
        private val tvStatus: TextView = itemView.findViewById(R.id.tv_budget_status)
        private val tvRemaining: TextView = itemView.findViewById(R.id.tv_budget_remaining)
        private val btnDelete: TextView = itemView.findViewById(R.id.btn_budget_delete)

        fun bind(item: BudgetService.BudgetOverview) {
            val budget = item.budget
            val progress = item.progress
            val context = itemView.context
            val statusColor = when (progress.status) {
                BudgetService.BudgetStatus.EXCEEDED -> Color.parseColor("#FF5252")
                BudgetService.BudgetStatus.WARNING -> Color.parseColor("#FF9800")
                BudgetService.BudgetStatus.NORMAL -> Color.parseColor("#4CAF50")
            }

            tvCategory.text = budget.categoryName ?: itemView.context.getString(R.string.budget_monthly_total)
            tvAmount.text = context.getString(R.string.budget_amount_display_fmt, budget.amount)
            tvUsed.text = context.getString(R.string.budget_used_fmt, progress.usedAmount)
            tvPercent.text = context.getString(R.string.budget_percent_fmt, progress.percent * 100)
            tvStatus.text = when (progress.status) {
                BudgetService.BudgetStatus.EXCEEDED -> context.getString(R.string.budget_status_exceeded)
                BudgetService.BudgetStatus.WARNING -> context.getString(R.string.budget_status_warning)
                BudgetService.BudgetStatus.NORMAL -> context.getString(R.string.budget_status_normal)
            }
            tvStatus.setTextColor(statusColor)
            tvRemaining.text = if (progress.remaining >= 0) {
                context.getString(R.string.budget_remaining_fmt, String.format("%.2f", progress.remaining))
            } else {
                context.getString(R.string.budget_over_budget_fmt, budget.amount, progress.usedAmount, -progress.remaining)
            }

            progressBar.progress = (progress.percent * 100).toInt().coerceAtMost(100)
            progressBar.progressTintList = ColorStateList.valueOf(statusColor)

            itemView.setOnClickListener { onItemClick(budget) }
            itemView.setOnLongClickListener { onItemLongClick(budget); true }
            btnDelete.setOnClickListener { onItemLongClick(budget) }
        }
    }
}
