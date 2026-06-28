package com.taostudio.tapaccounting.ui.main.home

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.logic.insight.InsightCardModel
import com.taostudio.tapaccounting.logic.insight.InsightSeverity

class InsightCardAdapter(
    private val onCardClick: ((InsightCardModel) -> Unit)? = null
) : RecyclerView.Adapter<InsightCardAdapter.ViewHolder>() {

    private val items = mutableListOf<InsightCardModel>()

    fun submitList(newItems: List<InsightCardModel>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_insight_card, parent, false)
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

        fun bind(model: InsightCardModel) {
            tvTitle.text = model.title
            tvBody.text = model.body

            val color = when (model.severity) {
                InsightSeverity.WARN -> Color.parseColor("#FF5252")
                InsightSeverity.POSITIVE -> Color.parseColor("#4CAF50")
                InsightSeverity.INFO -> Color.parseColor("#FF9800")
            }
            severityBar.setBackgroundColor(color)

            itemView.setOnClickListener { onCardClick?.invoke(model) }
        }
    }
}
