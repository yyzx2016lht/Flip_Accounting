package com.taostudio.tapaccounting.ui.budget

import android.graphics.Color
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

    private val items = mutableListOf<Pair<Budget, BudgetService.BudgetProgress>>()

    fun submitList(newItems: List<Pair<Budget, BudgetService.BudgetProgress>>) {
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

        fun bind(pair: Pair<Budget, BudgetService.BudgetProgress>) {
            val (budget, progress) = pair
            tvCategory.text = budget.categoryName ?: itemView.context.getString(R.string.budget_monthly_total)
            tvAmount.text = "¥${String.format("%.0f", budget.amount)}"
            tvUsed.text = "已用 ¥${String.format("%.2f", progress.usedAmount)}"
            tvPercent.text = "${String.format("%.0f", progress.percent * 100)}%"

            progressBar.progress = (progress.percent * 100).toInt().coerceAtMost(100)
            progressBar.progressTintList = android.content.res.ColorStateList.valueOf(
                when (progress.status) {
                    BudgetService.BudgetStatus.EXCEEDED -> Color.parseColor("#FF5252")
                    BudgetService.BudgetStatus.WARNING -> Color.parseColor("#FF9800")
                    BudgetService.BudgetStatus.NORMAL -> Color.parseColor("#4CAF50")
                }
            )

            itemView.setOnClickListener { onItemClick(budget) }
            itemView.setOnLongClickListener { onItemLongClick(budget); true }
        }
    }
}
