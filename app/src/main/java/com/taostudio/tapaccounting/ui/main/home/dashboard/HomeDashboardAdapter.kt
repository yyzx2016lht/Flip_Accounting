package com.taostudio.tapaccounting.ui.main.home.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R

/**
 * 首页驾驶舱卡片 Adapter。
 * 展示最多 3 张卡片：待处理、预算进度、重要提醒。
 */
class HomeDashboardAdapter(
    private val onCardClick: ((HomeDashboardCard) -> Unit)? = null
) : RecyclerView.Adapter<HomeDashboardAdapter.ViewHolder>() {

    private val items = mutableListOf<HomeDashboardCard>()

    fun submitList(newItems: List<HomeDashboardCard>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val old = items[oldPos]
                val new = newItems[newPos]
                return old::class == new::class
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_insight_card, parent, false) // 复用洞察卡片布局
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val severityBar: View = itemView.findViewById(R.id.viewSeverityBar)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvInsightTitle)
        private val tvBody: TextView = itemView.findViewById(R.id.tvInsightBody)

        fun bind(card: HomeDashboardCard) {
            when (card) {
                is HomeDashboardCard.BudgetProgress -> {
                    tvTitle.text = card.title
                    tvBody.text = card.body
                }
                is HomeDashboardCard.Reminder -> {
                    tvTitle.text = card.title
                    tvBody.text = card.body
                }
            }

            val color = when (card) {
                is HomeDashboardCard.BudgetProgress -> {
                    if (card.percent >= 1.0) Color.parseColor("#FF5252")
                    else if (card.percent >= 0.8) Color.parseColor("#FF9800")
                    else Color.parseColor("#4CAF50")
                }
                is HomeDashboardCard.Reminder -> Color.parseColor("#2196F3")
            }
            severityBar.setBackgroundColor(color)

            itemView.setOnClickListener { onCardClick?.invoke(card) }
        }
    }
}
