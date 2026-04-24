package tao.test.flipaccounting.ui.main.stats

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tao.test.flipaccounting.AmountFormatHelper
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
    companion object {
        private const val TAG = "CategoryStatsAdapter"
    }

    private var colorMap: Map<String, Int> = emptyMap()
    private var pinnedCategory: String? = null
    private var listRenderSeq = 0L
    private val iconUrlCache = mutableMapOf<String, String>()

    fun setColors(colors: List<Int>) {
        chartColors = colors
    }

    fun setColorMap(map: Map<String, Int>) {
        colorMap = map
    }

    fun submitList(newItems: List<CategoryStat>, isExpense: Boolean, currencySymbol: String) {
        val start = SystemClock.elapsedRealtime()
        items = newItems
        this.isExpense = isExpense
        this.currencySymbol = currencySymbol
        listRenderSeq++
        if (pinnedCategory != null && newItems.none { it.categoryName == pinnedCategory }) {
            pinnedCategory = null
        }
        preloadIconsAsync(newItems, listRenderSeq, isExpense)
        notifyDataSetChanged()
        Log.d(
            TAG,
            "submitList size=${newItems.size}, mode=${if (isExpense) "expense" else "income"}, costMs=${SystemClock.elapsedRealtime() - start}"
        )
    }

    fun findPositionByCategory(categoryName: String): Int =
        displayItems().indexOfFirst { it.categoryName == categoryName }

    fun pinCategory(categoryName: String) {
        if (items.none { it.categoryName == categoryName }) return
        pinnedCategory = categoryName
        notifyDataSetChanged()
    }

    fun clearPinCategory() {
        if (pinnedCategory == null) return
        pinnedCategory = null
        notifyDataSetChanged()
    }

    private fun displayItems(): List<CategoryStat> {
        val pinned = pinnedCategory ?: return items
        val index = items.indexOfFirst { it.categoryName == pinned }
        if (index <= 0) return items
        val mutable = items.toMutableList()
        val selected = mutable.removeAt(index)
        mutable.add(0, selected)
        return mutable
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.iv_category_icon)
        val tvName: TextView = view.findViewById(R.id.tv_category_name)
        val tvPercent: TextView = view.findViewById(R.id.tv_category_percent)
        val pbPercent: ProgressBar = view.findViewById(R.id.pb_category_percent)
        val tvAmount: TextView = view.findViewById(R.id.tv_category_amount)
        val ivArrow: ImageView = view.findViewById(R.id.iv_comparison_arrow)
        val tvComparison: TextView = view.findViewById(R.id.tv_comparison_amount)
        val comparisonContainer: LinearLayout = view.findViewById(R.id.ll_comparison)
        val divider: View = view.findViewById(R.id.view_item_divider)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bindStart = SystemClock.elapsedRealtime()
        val stat = displayItems()[position]

        holder.itemView.visibility = View.VISIBLE
        holder.itemView.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        holder.tvName.text = stat.categoryName
        holder.tvPercent.text = String.format(Locale.getDefault(), "%.2f%%", stat.percentage)
        holder.pbPercent.progress = stat.percentage.toInt().coerceIn(0, 100)
        holder.tvAmount.text = "$currencySymbol${AmountFormatHelper.formatAmount(stat.amount)}"

        val themeColor = colorMap[stat.categoryName]
            ?: if (chartColors.isNotEmpty()) chartColors[position % chartColors.size] else Color.GRAY
        holder.pbPercent.progressTintList = ColorStateList.valueOf(themeColor)
        holder.ivIcon.backgroundTintList = ColorStateList.valueOf(themeColor)

        val type = if (isExpense) 0 else 1
        val ctx = holder.itemView.context
        holder.ivIcon.setImageResource(R.drawable.ic_placeholder)
        val iconUrl = iconUrlCache[iconCacheKey(stat.categoryName, type)]
        if (!iconUrl.isNullOrEmpty()) {
            Glide.with(ctx)
                .load(iconUrl)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .into(holder.ivIcon)
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_placeholder)
        }
        holder.ivIcon.setColorFilter(Color.WHITE)

        holder.divider.visibility = if (position == itemCount - 1) View.GONE else View.VISIBLE
        holder.itemView.setBackgroundColor(Color.TRANSPARENT)
        holder.itemView.alpha = 1f

        when {
            stat.amountDiffFromLastPeriod > 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.comparisonContainer.setBackgroundResource(R.drawable.bg_stats_comparison_up)
                holder.ivArrow.setImageResource(R.drawable.ic_trend_up)
                holder.ivArrow.setColorFilter(Color.parseColor("#DC2626"))
                holder.tvComparison.setTextColor(Color.parseColor("#DC2626"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "+%s%.2f",
                    currencySymbol,
                    stat.amountDiffFromLastPeriod
                )
            }

            stat.amountDiffFromLastPeriod < 0 -> {
                holder.comparisonContainer.visibility = View.VISIBLE
                holder.comparisonContainer.setBackgroundResource(R.drawable.bg_stats_comparison_down)
                holder.ivArrow.setImageResource(R.drawable.ic_trend_down)
                holder.ivArrow.setColorFilter(Color.parseColor("#059669"))
                holder.tvComparison.setTextColor(Color.parseColor("#059669"))
                holder.tvComparison.text = String.format(
                    Locale.getDefault(),
                    "-%s%.2f",
                    currencySymbol,
                    abs(stat.amountDiffFromLastPeriod)
                )
            }

            else -> {
                holder.comparisonContainer.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener { onItemClick(stat.categoryName) }
        if (position < 3 || SystemClock.elapsedRealtime() - bindStart >= 8L) {
            Log.d(TAG, "bind pos=$position, category=${stat.categoryName}, costMs=${SystemClock.elapsedRealtime() - bindStart}")
        }
    }

    override fun getItemCount() = displayItems().size

    private fun preloadIconsAsync(newItems: List<CategoryStat>, seq: Long, isExpense: Boolean) {
        val type = if (isExpense) 0 else 1
        val names = newItems.map { it.categoryName }.distinct()
        if (names.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val context = lastBoundContext ?: return@launch
            val updates = mutableMapOf<String, String>()
            names.forEach { name ->
                val key = iconCacheKey(name, type)
                if (iconUrlCache.containsKey(key)) return@forEach
                val url = CategoryIconHelper.findCategoryIcon(context, name, type)
                updates[key] = url
            }
            if (updates.isEmpty()) return@launch
            CoroutineScope(Dispatchers.Main).launch {
                if (seq != listRenderSeq) return@launch
                iconUrlCache.putAll(updates)
                notifyItemRangeChanged(0, itemCount, "icon")
                Log.d(TAG, "icon preload done: updated=${updates.size}, totalCache=${iconUrlCache.size}")
            }
        }
    }

    private var lastBoundContext: android.content.Context? = null

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        lastBoundContext = holder.itemView.context.applicationContext
    }

    private fun iconCacheKey(name: String, type: Int): String = "$type|$name"
}
