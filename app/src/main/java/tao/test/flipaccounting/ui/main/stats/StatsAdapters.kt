package tao.test.flipaccounting.ui.main.stats

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import java.util.Locale
import kotlin.math.abs

class CategoryStatsAdapter(
    private var chartColors: List<Int> = emptyList(),
    private var items: List<CategoryStat> = emptyList(),
    private var isExpense: Boolean = true,
    private var currencySymbol: String = "¥",
    private val onItemClick: (String) -> Unit
) : RecyclerView.Adapter<CategoryStatsAdapter.ViewHolder>() {

    /** 分类名 → 颜色映射，由 StatsFragment 在更新饼图时同步传入 */
    private var colorMap: Map<String, Int> = emptyMap()

    fun setColors(colors: List<Int>) {
        chartColors = colors
    }

    fun setColorMap(map: Map<String, Int>) {
        colorMap = map
    }

    fun submitList(newItems: List<CategoryStat>, isExpense: Boolean, currencySymbol: String) {
        items = newItems
        this.isExpense = isExpense
        this.currencySymbol = currencySymbol
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_category_icon)
        val tvName: TextView = view.findViewById(R.id.tv_category_name)
        val tvPercent: TextView = view.findViewById(R.id.tv_category_percent)
        val pbPercent: ProgressBar = view.findViewById(R.id.pb_category_percent)
        val tvAmount: TextView = view.findViewById(R.id.tv_category_amount)
        val ivArrow: ImageView = view.findViewById(R.id.iv_comparison_arrow)
        val tvComparison: TextView = view.findViewById(R.id.tv_comparison_amount)
        val comparisonContainer: View = view.findViewById(R.id.ll_comparison)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val stat = items[position]

        holder.itemView.visibility = View.VISIBLE
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        holder.tvName.text = stat.categoryName
        holder.tvPercent.text = String.format(Locale.getDefault(), "%.2f%%", stat.percentage)
        holder.pbPercent.progress = stat.percentage.toInt().coerceIn(0, 100)
        holder.tvAmount.text = String.format(Locale.getDefault(), "%s%.2f", currencySymbol, stat.amount)

        val themeColor = colorMap[stat.categoryName]
            ?: if (chartColors.isNotEmpty()) chartColors[position % chartColors.size] else Color.GRAY
        holder.pbPercent.progressTintList = ColorStateList.valueOf(themeColor)
        holder.ivIcon.backgroundTintList = ColorStateList.valueOf(themeColor)

        val type = if (isExpense) 0 else 1
        val ctx = holder.itemView.context
        holder.ivIcon.setImageResource(R.drawable.ic_placeholder)
        CoroutineScope(Dispatchers.IO).launch {
            val iconUrl = CategoryIconHelper.findCategoryIcon(ctx, stat.categoryName, type)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(ctx)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .placeholder(R.drawable.ic_placeholder)
                        .into(holder.ivIcon)
                } else {
                    holder.ivIcon.setImageResource(R.drawable.ic_placeholder)
                }
            }
        }
        holder.ivIcon.setColorFilter(Color.WHITE)

        when {
            stat.amountDiffFromLastPeriod > 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.ivArrow.setImageResource(android.R.drawable.arrow_up_float)
                holder.ivArrow.setColorFilter(Color.parseColor("#E53935"))
                holder.tvComparison.setTextColor(Color.parseColor("#E53935"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "%s%.2f",
                    currencySymbol,
                    stat.amountDiffFromLastPeriod
                )
            }

            stat.amountDiffFromLastPeriod < 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.ivArrow.setImageResource(android.R.drawable.arrow_down_float)
                holder.ivArrow.setColorFilter(Color.parseColor("#4CAF50"))
                holder.tvComparison.setTextColor(Color.parseColor("#4CAF50"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "%s%.2f",
                    currencySymbol,
                    abs(stat.amountDiffFromLastPeriod)
                )
            }

            else -> {
                holder.comparisonContainer.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onItemClick(stat.categoryName) }
    }

    override fun getItemCount() = items.size
}
