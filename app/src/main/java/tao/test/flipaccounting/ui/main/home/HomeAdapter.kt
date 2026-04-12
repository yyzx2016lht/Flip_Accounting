package tao.test.flipaccounting.ui.main.home

import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    data class DisplayBill(
        val bill: Bill,
        val isDeprecated: Boolean = false
    )

    /** Adapter 级别共享协程域：SupervisorJob 保证单个子协程失败不影响其它；
     *  所有 icon 加载都在此 scope 里 launch，ViewHolder recycle 时取消对应 job */
    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    /** Diff 计算专用协程域，支持取消旧任务，避免快速切换账本时旧结果回流 */
    private val diffScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var submitJob: Job? = null
    private var submitGeneration: Long = 0L
    /** 分类图标内存缓存：key=type|name，value=url（空串表示无图标） */
    private val iconUrlCache = mutableMapOf<String, String>()
    private val itemTimeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    companion object {
        const val TYPE_CHART = 2
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1
        const val PAYLOAD_MODE_CHANGE = "PAYLOAD_MODE_CHANGE"
        const val PAYLOAD_SELECTION_CHANGE = "PAYLOAD_SELECTION_CHANGE"
        private const val COLOR_TEXT_REFUND = "#8E98A3"
        private const val COLOR_TEXT_DETAIL_REFUND = "#A1A8AF"
        private const val COLOR_TEXT_NORMAL = "#333333"
        private const val COLOR_TEXT_DETAIL_NORMAL = "#999999"
        private const val COLOR_AMOUNT_EXPENSE = "#C62828"
        private const val COLOR_AMOUNT_INCOME = "#4CAF50"
        private const val COLOR_AMOUNT_REFUND = "#9AA1AA"
        private const val COLOR_AMOUNT_OTHER = "#757575"
        private const val COLOR_ICON_OTHER = "#9E9E9E"
    }

    private val textColorRefund = Color.parseColor(COLOR_TEXT_REFUND)
    private val textColorDetailRefund = Color.parseColor(COLOR_TEXT_DETAIL_REFUND)
    private val textColorNormal = Color.parseColor(COLOR_TEXT_NORMAL)
    private val textColorDetailNormal = Color.parseColor(COLOR_TEXT_DETAIL_NORMAL)
    private val amountColorExpense = Color.parseColor(COLOR_AMOUNT_EXPENSE)
    private val amountColorIncome = Color.parseColor(COLOR_AMOUNT_INCOME)
    private val amountColorRefund = Color.parseColor(COLOR_AMOUNT_REFUND)
    private val amountColorOther = Color.parseColor(COLOR_AMOUNT_OTHER)
    private val iconColorOther = Color.parseColor(COLOR_ICON_OTHER)

    sealed class ListItem {
        /** 图表卡片（最近7日），始终排在列表第一位 */
        object Chart : ListItem()

        data class Header(
            val dateStr: String,
            val weekdayStr: String,
            val income: Double,
            val expense: Double,
            val rawDateKey: String
        ) : ListItem()

        data class Item(val displayBill: DisplayBill) : ListItem()
    }

    val items = mutableListOf<ListItem>()
    private val rawBills = mutableListOf<DisplayBill>()

    /** 由 Fragment 传入的图表 CardView，作为 position=0 的 header item 展示 */
    var chartView: android.view.View? = null
    /** 当前是否应在列表里显示图表卡片（由 Fragment 控制，随 isChartHidden / isCurrentMonth 同步更新） */
    var showChart: Boolean = false
    private var lastSubmittedShowChart: Boolean = false

    var isMultiSelectMode: Boolean = false
    val selectedBills = mutableSetOf<Bill>()

    var onBillItemClick: ((Bill) -> Unit)? = null
    var onSelectionChanged: ((Int) -> Unit)? = null
    var detailSuffixProvider: ((Bill) -> String?)? = null

    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    private fun refundAmountOfExpenseBill(bill: Bill): Double {
        return BillDisplayFormatter.refundAmountOfExpenseBill(bill)
    }

    private fun stripRefundPrefix(categoryName: String): String {
        return BillDisplayFormatter.stripRefundPrefix(categoryName)
    }

    private fun buildIconCacheKey(categoryName: String, type: Int): String = "$type|$categoryName"

    private fun getCachedIconUrl(key: String): String? = synchronized(iconUrlCache) { iconUrlCache[key] }

    private fun putCachedIconUrl(key: String, url: String) {
        synchronized(iconUrlCache) {
            if (iconUrlCache.size > 200) {
                iconUrlCache.clear()
            }
            iconUrlCache[key] = url
        }
    }

    fun submitList(newBills: List<Bill>, deprecatedBills: List<Bill> = emptyList()) {
        val activeIds = newBills.map { it.id }.toSet()
        val combinedBills = buildList {
            addAll(newBills.map { DisplayBill(it, isDeprecated = false) })
            addAll(
                deprecatedBills
                    .filter { it.id !in activeIds }
                    .map { DisplayBill(it, isDeprecated = true) }
            )
        }.sortedByDescending { it.bill.time }
        // 快速路径：如果数据完全相同，直接返回，不触发任何 DiffUtil 计算或 notify
        if (rawBills == combinedBills && lastSubmittedShowChart == showChart) {
            Log.d("HomePerf", "submitList: skip (list unchanged)")
            return
        }

        submitJob?.cancel()
        val generation = ++submitGeneration
        val includeChart = showChart   // 在子线程中读取时快照，避免竞争

        val oldItems = items.toList()

        // 在子线程构建新列表并计算 DiffUtil，避免主线程卡顿
        submitJob = diffScope.launch {
            val dfKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dfDisplay = SimpleDateFormat("MM.dd", Locale.getDefault())
            val dfWeekday = SimpleDateFormat("E", Locale.CHINESE)

            val newItems = mutableListOf<ListItem>()
            // 图表 header 始终排在最前面（由 showChart 控制是否显示）
            if (includeChart) newItems.add(ListItem.Chart)
            if (combinedBills.isNotEmpty()) {
                val grouped = combinedBills.groupBy { dfKey.format(Date(it.bill.time)) }
                for ((dateKey, billsInDay) in grouped) {
                    var dailyIncome = 0.0
                    var dailyExpense = 0.0
                    billsInDay.forEach {
                        if (it.isDeprecated) return@forEach
                        val amountCny = it.bill.amount * it.bill.exchangeRate
                        if (isRefundBill(it.bill)) {
                            dailyIncome += amountCny
                        } else if (it.bill.type == Bill.TYPE_EXPENSE) {
                            dailyExpense += amountCny
                        } else if (it.bill.type == Bill.TYPE_INCOME) {
                            dailyIncome += amountCny
                        }
                    }
                    val sampleDate = Date(billsInDay.first().bill.time)
                    val dateStr = dfDisplay.format(sampleDate)
                    val weekdayStr = dfWeekday.format(sampleDate)
                    newItems.add(ListItem.Header(dateStr, weekdayStr, dailyIncome, dailyExpense, dateKey))
                    billsInDay.forEach { displayBill -> newItems.add(ListItem.Item(displayBill)) }
                }
            }

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldItems[oldItemPosition]
                    val new = newItems[newItemPosition]
                    if (old is ListItem.Chart && new is ListItem.Chart) return true
                    if (old is ListItem.Header && new is ListItem.Header) return old.rawDateKey == new.rawDateKey
                    if (old is ListItem.Item && new is ListItem.Item) return old.displayBill.bill.id == new.displayBill.bill.id
                    return false
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition] == newItems[newItemPosition]
                }
            })

            // 切回主线程更新数据和 UI
            withContext(Dispatchers.Main) {
                if (generation != submitGeneration) {
                    Log.d("HomePerf", "submitList: drop stale generation=$generation latest=$submitGeneration")
                    return@withContext
                }
                val t0 = System.currentTimeMillis()
                val wasEmpty = items.isEmpty()
                rawBills.clear()
                rawBills.addAll(combinedBills)
                lastSubmittedShowChart = includeChart
                items.clear()
                items.addAll(newItems)

                val copy = selectedBills.toList()
                selectedBills.clear()
                selectedBills.addAll(copy.filter { selected ->
                    rawBills.any { !it.isDeprecated && it.bill == selected }
                })

                diffResult.dispatchUpdatesTo(this@HomeAdapter)
                Log.d("HomePerf", "dispatchUpdatesTo: oldSize=${oldItems.size} newSize=${newItems.size} wasEmpty=$wasEmpty  [${System.currentTimeMillis() - t0}ms on main]")
            }
        }
    }

    fun toggleMultiSelectMode() {
        isMultiSelectMode = !isMultiSelectMode
        if (!isMultiSelectMode) {
            selectedBills.clear()
        }
        onSelectionChanged?.invoke(selectedBills.size)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    fun clearSelection() {
        selectedBills.clear()
        isMultiSelectMode = false
        onSelectionChanged?.invoke(0)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    fun selectAll() {
        isMultiSelectMode = true
        selectedBills.clear()
        selectedBills.addAll(rawBills.filterNot { it.isDeprecated }.map { it.bill })
        onSelectionChanged?.invoke(selectedBills.size)
        notifyItemRangeChanged(0, items.size, PAYLOAD_MODE_CHANGE)
    }

    fun getSelectedBills(): List<Bill> = selectedBills.toList()

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ListItem.Chart -> TYPE_CHART
            is ListItem.Header -> TYPE_HEADER
            is ListItem.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_CHART -> {
                // 直接把由 Fragment 传入的 chartView 包装进一个 FrameLayout 容器，
                // 宽度 match_parent，高度 wrap_content，外部留有间距
                val container = android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        RecyclerView.LayoutParams.WRAP_CONTENT
                    )
                }
                ChartViewHolder(container)
            }
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_bill_header, parent, false))
            else -> ItemViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            if (holder is ItemViewHolder && items[position] is ListItem.Item) {
                val bill = (items[position] as ListItem.Item).displayBill.bill
                if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                    holder.updateMode(selectedBills.contains(bill), animate = true)
                } else if (payloads.contains(PAYLOAD_SELECTION_CHANGE)) {
                    holder.updateSelection(selectedBills.contains(bill))
                }
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ListItem.Chart -> (holder as ChartViewHolder).bind(chartView)
            is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ListItem.Item -> (holder as ItemViewHolder).bind(item.displayBill, position)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is ItemViewHolder) {
            // ViewHolder 被回收：取消图标加载协程 + 清除 Glide 请求，防止旧回调覆盖新 item
            holder.iconJob?.cancel()
            holder.iconJob = null
            Glide.with(holder.ivIcon.context).clear(holder.ivIcon)
        }
    }

    /** 图表卡片 ViewHolder：持有一个容器，将外部的 chartView 移入其中展示 */
    inner class ChartViewHolder(val container: android.widget.FrameLayout) : RecyclerView.ViewHolder(container) {
        fun bind(view: android.view.View?) {
            container.removeAllViews()
            if (view != null) {
                // 如果 view 已经有 parent，先从 parent 移除
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                container.addView(view, android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                ))
            }
        }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        submitJob?.cancel()
    }

    private fun resolveGroupBackground(
        isGroupStart: Boolean,
        isGroupEnd: Boolean,
        hasHeaderAbove: Boolean
    ): Int {
        if (hasHeaderAbove) {
            return if (isGroupEnd) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_middle
        }
        return when {
            isGroupStart && isGroupEnd -> R.drawable.bg_bill_group_single
            isGroupStart -> R.drawable.bg_bill_group_top
            isGroupEnd -> R.drawable.bg_bill_group_bottom
            else -> R.drawable.bg_bill_group_middle
        }
    }

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvDate: TextView = itemView.findViewById(R.id.tv_header_date)
        private val tvSummary: TextView? = itemView.findViewById(
            itemView.context.resources.getIdentifier("tv_header_summary", "id", itemView.context.packageName)
        )

        fun bind(header: ListItem.Header) {
            tvDate.text = "${header.dateStr} ${header.weekdayStr}"

            val summaryBuilder = StringBuilder()
            if (header.income > 0) {
                summaryBuilder.append("收 ¥${String.format(Locale.getDefault(), "%.2f", header.income)} ")
            }
            if (header.expense > 0) {
                summaryBuilder.append("支 ¥${String.format(Locale.getDefault(), "%.2f", header.expense)}")
            }
            tvSummary?.text = summaryBuilder.toString().trim()
            if (tvSummary?.text?.isEmpty() == true) {
                tvSummary.visibility = View.GONE
            } else {
                tvSummary?.visibility = View.VISIBLE
            }
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCategory: TextView = itemView.findViewById(R.id.tv_bill_category)
        private val tvAmount: TextView = itemView.findViewById(R.id.tv_bill_amount)
        private val tvTime: TextView = itemView.findViewById(R.id.tv_bill_time)
        private val tvDetail: TextView = itemView.findViewById(R.id.tv_bill_detail)
        private val checkBox: CheckBox = itemView.findViewById(R.id.cb_bill_select)
        val ivIcon: ImageView = itemView.findViewById(R.id.iv_bill_category_icon)
        private val iconContainer: View? = itemView.findViewById(
            itemView.context.resources.getIdentifier("layout_icon_container", "id", itemView.context.packageName)
        )

        /** 当前正在进行的图标加载协程 Job，recycle 时取消 */
        var iconJob: Job? = null
        private var boundBill: Bill? = null
        private var boundDeprecated: Boolean = false

        init {
            itemView.setOnClickListener {
                val bill = boundBill ?: return@setOnClickListener
                if (boundDeprecated) return@setOnClickListener
                if (isMultiSelectMode) {
                    if (selectedBills.contains(bill)) {
                        selectedBills.remove(bill)
                    } else {
                        selectedBills.add(bill)
                    }
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                    }
                    onSelectionChanged?.invoke(selectedBills.size)
                } else {
                    onBillItemClick?.invoke(bill)
                }
            }

            itemView.setOnLongClickListener {
                val bill = boundBill ?: return@setOnLongClickListener true
                if (boundDeprecated) return@setOnLongClickListener true
                if (!isMultiSelectMode) {
                    toggleMultiSelectMode()
                    selectedBills.add(bill)
                    val pos = adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                    }
                    onSelectionChanged?.invoke(selectedBills.size)
                }
                true
            }
        }

        fun updateMode(isSelected: Boolean, animate: Boolean) {
            if (isMultiSelectMode) {
                checkBox.visibility = View.VISIBLE
                checkBox.isChecked = isSelected
                if (animate) {
                    itemView.animate().scaleX(0.95f).scaleY(0.95f).setDuration(200).start()
                } else {
                    itemView.scaleX = 0.95f
                    itemView.scaleY = 0.95f
                }
            } else {
                checkBox.visibility = View.GONE
                checkBox.isChecked = false
                if (animate) {
                    itemView.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    iconContainer?.apply {
                        visibility = View.VISIBLE
                        alpha = 0f
                        animate().alpha(1f).setDuration(200).start()
                    }
                } else {
                    itemView.scaleX = 1f
                    itemView.scaleY = 1f
                    iconContainer?.visibility = View.VISIBLE
                }
            }
        }

        fun updateSelection(isSelected: Boolean) {
            checkBox.isChecked = isSelected
        }

        fun bind(displayBill: DisplayBill, position: Int) {
            val bill = displayBill.bill
            val isDeprecated = displayBill.isDeprecated
            boundBill = bill
            boundDeprecated = isDeprecated
            val isTransfer = bill.type == Bill.TYPE_TRANSFER
            val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
            val isRefund = isRefundBill(bill)
            val symbol = CurrencyManager.getSymbol(bill.currency)
            val refundAmount = refundAmountOfExpenseBill(bill)
            val baseCategoryName = stripRefundPrefix(bill.categoryName)
            val hasHeaderAbove = items.getOrNull(position - 1) is ListItem.Header
            val isGroupStart = position == 0 || hasHeaderAbove
            val isGroupEnd = position == items.lastIndex || items.getOrNull(position + 1) is ListItem.Header

            itemView.setBackgroundResource(
                resolveGroupBackground(
                    isGroupStart = isGroupStart,
                    isGroupEnd = isGroupEnd,
                    hasHeaderAbove = hasHeaderAbove
                )
            )
            iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)
            tvCategory.setTextColor(
                when {
                    isDeprecated -> textColorRefund
                    isRefund -> textColorRefund
                    else -> textColorNormal
                }
            )
            tvDetail.setTextColor(
                when {
                    isDeprecated -> textColorRefund
                    isRefund -> textColorDetailRefund
                    else -> textColorDetailNormal
                }
            )

            tvCategory.text = when {
                isRepayment -> "还款"
                isTransfer -> "转账"
                isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
                else -> bill.categoryName.ifEmpty { "未分类" }
            }

            tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
                BillDisplayFormatter.buildRefundedExpenseAmountText(
                    netAmount = bill.amount,
                    originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                    currency = bill.currency
                )
            } else {
                val sign = when {
                    isRefund -> ""
                    bill.type == Bill.TYPE_EXPENSE -> "-"
                    bill.type == Bill.TYPE_INCOME -> "+"
                    else -> ""
                }
                "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
            }

            if (isDeprecated) {
                tvAmount.setTextColor(amountColorRefund)
            } else if (isRefund) {
                tvAmount.setTextColor(amountColorRefund)
            } else if (bill.type == Bill.TYPE_EXPENSE) {
                tvAmount.setTextColor(amountColorExpense)
            } else if (bill.type == Bill.TYPE_INCOME) {
                tvAmount.setTextColor(amountColorIncome)
            } else {
                tvAmount.setTextColor(amountColorOther)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ivIcon.imageTintList = null
            }

            val tintColor = when {
                isRefund -> textColorRefund
                bill.type == Bill.TYPE_EXPENSE -> amountColorExpense
                bill.type == Bill.TYPE_INCOME -> amountColorIncome
                else -> iconColorOther
            }
            ivIcon.setColorFilter(tintColor)

            tvTime.text = itemTimeFormatter.format(Date(bill.time))

            val detailStr = buildString {
                if (isTransfer) {
                    append(bill.accountName)
                    if (bill.toAccountName.isNotEmpty()) {
                        append(" -> ")
                        append(bill.toAccountName)
                    }
                } else if (isRefund) {
                    if (bill.accountName.isNotEmpty()) {
                        append(bill.accountName)
                    }
                } else {
                    if (bill.accountName.isNotEmpty()) {
                        append(bill.accountName)
                        if (refundAmount > 0.0) {
                            append("(退款")
                            append(symbol)
                            append(String.format(Locale.getDefault(), "%.2f", refundAmount))
                            append(")")
                        }
                    }
                }
                if (bill.remark.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(bill.remark)
                }
                val suffix = detailSuffixProvider?.invoke(bill).orEmpty()
                if (suffix.isNotEmpty()) {
                    if (isNotEmpty()) append(" | ")
                    append(suffix)
                }
            }
            if (detailStr.isNotEmpty()) {
                tvDetail.text = detailStr
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }

            val strike = android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            if (isDeprecated) {
                tvCategory.paintFlags = tvCategory.paintFlags or strike
                tvAmount.paintFlags = tvAmount.paintFlags or strike
                tvTime.paintFlags = tvTime.paintFlags or strike
                tvDetail.paintFlags = tvDetail.paintFlags or strike
                itemView.alpha = 0.55f
            } else {
                tvCategory.paintFlags = tvCategory.paintFlags and strike.inv()
                tvAmount.paintFlags = tvAmount.paintFlags and strike.inv()
                tvTime.paintFlags = tvTime.paintFlags and strike.inv()
                tvDetail.paintFlags = tvDetail.paintFlags and strike.inv()
                itemView.alpha = 1f
            }

            val iconLookupName = if (isRefund) baseCategoryName else bill.categoryName
            val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
            val iconCacheKey = buildIconCacheKey(iconLookupName, iconLookupType)
            // 取消上一次图标加载（ViewHolder 复用时防止旧协程回调覆盖新图标）
            iconJob?.cancel()
            ivIcon.setImageDrawable(null)
            val cachedIconUrl = getCachedIconUrl(iconCacheKey)
            if (cachedIconUrl != null) {
                if (cachedIconUrl.isNotEmpty()) {
                    Glide.with(itemView.context)
                        .load(cachedIconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .into(ivIcon)
                } else {
                    ivIcon.setImageDrawable(null)
                }
            } else {
                iconJob = adapterScope.launch {
                    val iconUrl = tao.test.flipaccounting.CategoryIconHelper.findCategoryIcon(itemView.context, iconLookupName, iconLookupType)
                    putCachedIconUrl(iconCacheKey, iconUrl)
                    withContext(Dispatchers.Main) {
                        if (iconUrl.isNotEmpty()) {
                            Glide.with(itemView.context)
                                .load(iconUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(ivIcon)
                        } else {
                            ivIcon.setImageDrawable(null)
                        }
                    }
                }
            }

            updateMode(!isDeprecated && selectedBills.contains(bill), animate = false)
        }
    }
}
