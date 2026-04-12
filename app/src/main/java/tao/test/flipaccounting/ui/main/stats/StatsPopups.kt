package tao.test.flipaccounting.ui.main.stats

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.activity.EditBillActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SubCategoryBottomSheet(
    private val title: String,
    private val isExpense: Boolean,
    private val subStats: Map<String, Double>,
    private val totalAmount: Double,
    private val colors: List<Int>,
    private val onSubCategoryClick: (String) -> Unit
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.bottom_sheet_category_report, container, false)
        root.findViewById<TextView>(R.id.tv_sheet_title).text = title

        // 先构建并排序 categoryStats
        val categoryStats = subStats.map { (name, amount) ->
            CategoryStat(
                categoryName = name,
                amount = amount,
                percentage = if (totalAmount > 0) (amount / totalAmount * 100).toFloat() else 0f,
                amountDiffFromLastPeriod = 0.0
            )
        }.sortedByDescending { it.amount }

        // 缓存排序后的类目列表，用于饼图与列表颜色对齐
        val sortedCategoryNames = categoryStats.map { it.categoryName }

        val pieChart = root.findViewById<PieChart>(R.id.pie_chart_sub)
        setupPieChart(pieChart)
        updatePieChart(pieChart, sortedCategoryNames)

        val rvSub = root.findViewById<RecyclerView>(R.id.rv_sub_categories)
        rvSub.layoutManager = LinearLayoutManager(context)
        rvSub.adapter = CategoryStatsAdapter(colors, categoryStats, isExpense) {
            onSubCategoryClick(it)
        }
        return root
    }

    private fun setupPieChart(pieChart: PieChart) {
        pieChart.description.isEnabled = false
        pieChart.legend.isEnabled = false
        pieChart.isDrawHoleEnabled = true
        pieChart.setHoleColor(Color.TRANSPARENT)
        pieChart.setDrawCenterText(true)
        pieChart.setUsePercentValues(true)
        pieChart.setExtraOffsets(35f, 10f, 35f, 10f)
        pieChart.setNoDataText("暂无图表数据")
        pieChart.setNoDataTextColor(Color.parseColor("#9AA0A6"))
    }

    private fun updatePieChart(pieChart: PieChart, sortedCategoryNames: List<String>) {
        // build entries in the same order as the list
        val entries = sortedCategoryNames.map { name ->
            val amount = subStats[name] ?: 0.0
            PieEntry(amount.toFloat(), name)
        }
        val dataSet = PieDataSet(entries, "")
        
        // 关键：显式给 DataSet 传入外部颜色列表
        dataSet.colors = this.colors
        
        dataSet.xValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
        dataSet.yValuePosition = PieDataSet.ValuePosition.OUTSIDE_SLICE
        dataSet.valueLinePart1OffsetPercentage = 100f
        dataSet.valueLinePart1Length = 0.333f
        dataSet.valueLinePart2Length = 0.8f
        dataSet.setValueLineVariableLength(true)
        
        // 确保连线颜色使用扇区颜色
        dataSet.setUsingSliceColorAsValueLineColor(true)
        
        dataSet.valueTextSize = 9f
        // keep label colors aligned with slice colors
        dataSet.setValueTextColors(this.colors)
        
        dataSet.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = ""
            override fun getPieLabel(value: Float, pieEntry: PieEntry): String {
                val pct = if (totalAmount > 0) (pieEntry.value / totalAmount * 100f) else 0f
                return "${pieEntry.label} ${String.format(Locale.getDefault(), "%.1f%%", pct)}"
            }
        }

        pieChart.data = PieData(dataSet)
        pieChart.setDrawEntryLabels(false)
        pieChart.centerText = "二级分类\n比例"
        pieChart.invalidate()
    }
}

class BillListBottomSheet(
    private val title: String,
    private val bills: List<Bill>
) : BottomSheetDialogFragment() {

    private val dfDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private var newestFirst = true
    private var displayBills: List<Bill> = emptyList()

    private lateinit var rvBills: RecyclerView
    private lateinit var tvSheetTitle: TextView
    private lateinit var btnSortTime: TextView
    private lateinit var adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.bottom_sheet_bill_list, container, false)
        tvSheetTitle = root.findViewById(R.id.tv_sheet_title)
        btnSortTime = root.findViewById(R.id.btn_sheet_sort_time)
        rvBills = root.findViewById(R.id.rv_sheet_bills)

        rvBills.layoutManager = LinearLayoutManager(context)
        adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_home_transaction, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val bill = displayBills[position]
                bindBillItem(holder.itemView, bill)
                holder.itemView.setOnClickListener {
                    if (!isAdded || parentFragmentManager.isStateSaved) return@setOnClickListener
                    BillDetailBottomSheet(bill)
                        .show(parentFragmentManager, "bill_detail_${bill.id}_${System.currentTimeMillis()}")
                }
            }

            override fun getItemCount(): Int = displayBills.size
        }
        rvBills.adapter = adapter

        btnSortTime.setOnClickListener {
            newestFirst = !newestFirst
            applySort()
        }

        applySort()
        return root
    }

    override fun onStart() {
        super.onStart()
        val bsDialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet =
            bsDialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)

        bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        behavior.isFitToContents = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.isHideable = true
        behavior.skipCollapsed = true
        behavior.isDraggable = true
    }

    private fun applySort() {
        displayBills = if (newestFirst) {
            bills.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
        } else {
            bills.sortedWith(compareBy<Bill> { it.time }.thenBy { it.id })
        }
        tvSheetTitle.text = "$title (${displayBills.size}条)"
        btnSortTime.text = if (newestFirst) "时间 ↓" else "时间 ↑"
        adapter.notifyDataSetChanged()
    }

    private fun bindBillItem(itemView: View, bill: Bill) {
        val tvCategory = itemView.findViewById<TextView>(R.id.tv_bill_category)
        val tvAmount = itemView.findViewById<TextView>(R.id.tv_bill_amount)
        val tvDetail = itemView.findViewById<TextView>(R.id.tv_bill_detail)
        val tvTime = itemView.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = itemView.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = itemView.findViewById<View>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val baseCategoryName = stripRefundPrefix(bill.categoryName)
        val symbol = CurrencyManager.getSymbol(bill.currency)

        itemView.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer.setBackgroundResource(R.drawable.bg_circle_soft)
        tvCategory.setTextColor(if (isRefund) Color.parseColor("#8E98A3") else Color.parseColor("#333333"))
        tvDetail.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

        tvCategory.text = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> bill.categoryName.ifEmpty { "未分类" }
        }

        val refundAmount = BillDisplayFormatter.refundAmountOfExpenseBill(bill)
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
        tvAmount.setTextColor(
            when {
                isRefund -> Color.parseColor("#9AA1AA")
                bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#757575")
            }
        )

        val detail = buildString {
            append(dfDate.format(Date(bill.time)))
            if (bill.remark.isNotBlank()) {
                append(" ")
                append(bill.remark)
            }
        }
        tvDetail.text = detail
        tvDetail.visibility = View.VISIBLE
        tvTime.visibility = View.GONE

        val iconLookupName = if (isRefund) baseCategoryName else bill.categoryName
        val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
        val iconTint = when {
            isRefund -> Color.parseColor("#8E98A3")
            bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
            bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#9E9E9E")
        }
        ivIcon.setImageResource(R.mipmap.ic_launcher)
        ivIcon.setColorFilter(iconTint)
        CoroutineScope(Dispatchers.IO).launch {
            val iconUrl = CategoryIconHelper.findCategoryIcon(requireContext(), iconLookupName, iconLookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(this@BillListBottomSheet)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                        .into(ivIcon)
                }
            }
        }
    }

    private fun stripRefundPrefix(categoryName: String): String {
        val prefix = "退款："
        return if (categoryName.startsWith(prefix)) categoryName.removePrefix(prefix).trim() else categoryName.trim()
    }
}

class BillDetailBottomSheet(
    private val bill: Bill
) : BottomSheetDialogFragment() {

    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.layout_bill_detail_bottom_sheet, container, false)

        val btnCopy = root.findViewById<View>(R.id.btn_copy)
        val btnRefund = root.findViewById<View>(R.id.btn_refund)
        val btnDelete = root.findViewById<View>(R.id.btn_delete)
        val btnEdit = root.findViewById<View>(R.id.btn_edit)

        btnCopy.visibility = View.GONE
        btnRefund.visibility = View.GONE
        btnDelete.visibility = View.GONE

        bindDetail(root, bill)

        btnEdit.setOnClickListener {
            val hostActivity = activity ?: return@setOnClickListener
            val intent = Intent(hostActivity, EditBillActivity::class.java).apply {
                putExtra("BILL_ID", bill.id)
            }
            hostActivity.startActivity(intent)
            dismissAllowingStateLoss()
        }

        return root
    }

    private fun formatMoney(amount: Double, currency: String): String =
        BillDisplayFormatter.formatMoney(amount, currency)

    private fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? =
        BillDisplayFormatter.buildCrossCurrencyAmountFormula(bill, accountCurrency)

    private fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? =
        BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)

    private fun bindDetail(root: View, bill: Bill) {
        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val amountFormula = root.findViewById<TextView>(R.id.tv_detail_amount_formula)
        amountFormula.visibility = View.GONE

        root.findViewById<TextView>(R.id.tv_title).text = "详情"
        root.findViewById<TextView>(R.id.tv_detail_category).text = bill.categoryName
        root.findViewById<TextView>(R.id.tv_detail_account).text =
            if (isTransfer && bill.toAccountName.isNotBlank()) {
                "${bill.accountName} -> ${bill.toAccountName}"
            } else {
                bill.accountName
            }
        root.findViewById<TextView>(R.id.tv_detail_time).text = dfDetailShort.format(Date(bill.time))
        root.findViewById<TextView>(R.id.tv_detail_record_time).text =
            "记录于 ${dfDetailTime.format(Date(bill.time))}"
        root.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.DEFAULT_BOOK }
        root.findViewById<TextView>(R.id.tv_detail_remark).text =
            bill.remark.takeIf { it.isNotBlank() } ?: "无备注"

        val amountView = root.findViewById<TextView>(R.id.tv_detail_amount)
        val sign = when {
            isRefund -> ""
            bill.type == Bill.TYPE_EXPENSE -> "-"
            bill.type == Bill.TYPE_INCOME -> "+"
            else -> ""
        }
        amountView.text = "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        amountView.setTextColor(
            when {
                isRefund -> Color.parseColor("#9AA1AA")
                bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF3B30")
                bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#5F6772")
            }
        )

        val categoryLayout = root.findViewById<View>(R.id.layout_detail_category)
        val categoryLine = root.findViewById<View>(R.id.line_category)
        if (isTransfer) {
            categoryLayout.visibility = View.GONE
            categoryLine.visibility = View.GONE
        } else {
            categoryLayout.visibility = View.VISIBLE
            categoryLine.visibility = View.VISIBLE
        }

        val feeLayout = root.findViewById<View>(R.id.layout_detail_fee)
        val feeLine = root.findViewById<View>(R.id.line_fee_detail)
        val feeText = root.findViewById<TextView>(R.id.tv_detail_fee)
        if (isTransfer && bill.fee > 0.0) {
            feeLayout.visibility = View.VISIBLE
            feeLine.visibility = View.VISIBLE
            feeText.text = "-$symbol${String.format(Locale.getDefault(), "%.2f", bill.fee)}"
        } else {
            feeLayout.visibility = View.GONE
            feeLine.visibility = View.GONE
        }

        if (!isTransfer) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val crossCurrencyText = buildCrossCurrencyDetailFormula(bill, "CNY")
                withContext(Dispatchers.Main) {
                    if (!crossCurrencyText.isNullOrBlank()) {
                        amountFormula.visibility = View.VISIBLE
                        amountFormula.text = crossCurrencyText
                    }
                }
            }
        }
    }
}
