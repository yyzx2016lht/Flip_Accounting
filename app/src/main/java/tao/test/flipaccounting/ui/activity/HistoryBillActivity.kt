package tao.test.flipaccounting.ui.activity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.DeletedBill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import tao.test.flipaccounting.ui.widget.SecondaryPageHeaderView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryBillActivity : AppCompatActivity() {

    private lateinit var rvDeletedBills: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var layoutBottomActions: View
    private lateinit var btnRestore: TextView
    private lateinit var btnPermanentDelete: TextView

    private val adapter = HistoryBillAdapter()
    private val selectedBills = mutableSetOf<DeletedBill>()
    private var isSelectMode = false

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val headerDateFormat = SimpleDateFormat("MM.dd", Locale.getDefault())
    private val weekdayFormat = SimpleDateFormat("EEEE", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_bills)

        initViews()
        setupListeners()
        loadDeletedBills()
    }

    private fun initViews() {
        val header: SecondaryPageHeaderView = findViewById(R.id.secondary_header)
        header.findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        rvDeletedBills = findViewById(R.id.rv_deleted_bills)
        tvEmpty = findViewById(R.id.tv_empty)
        layoutBottomActions = findViewById(R.id.layout_bottom_actions)
        btnRestore = findViewById(R.id.btn_restore)
        btnPermanentDelete = findViewById(R.id.btn_permanent_delete)

        rvDeletedBills.layoutManager = LinearLayoutManager(this)
        rvDeletedBills.adapter = adapter

        adapter.onItemClick = { bill ->
            if (isSelectMode) {
                toggleSelection(bill)
            } else {
                showBillActionDialog(bill)
            }
        }

        adapter.onItemLongClick = { bill ->
            if (!isSelectMode) {
                isSelectMode = true
                selectedBills.clear()
                selectedBills.add(bill)
                updateUI()
            }
        }
    }

    private fun setupListeners() {
        btnRestore.setOnClickListener {
            val billsToRestore = selectedBills.toList()
            if (billsToRestore.isEmpty()) return@setOnClickListener

            showConfirmDialog(
                title = "恢复账单",
                message = "确定要恢复选中的 ${billsToRestore.size} 条账单吗？",
                confirmText = "确认恢复",
                isDanger = false
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@HistoryBillActivity)
                    val billDao = db.billDao()
                    val deletedBillDao = db.deletedBillDao()

                    billsToRestore.forEach { deletedBill ->
                        billDao.insertBill(deletedBill.toBill())
                        deletedBillDao.delete(deletedBill)
                    }

                    withContext(Dispatchers.Main) {
                        exitSelectMode()
                        loadDeletedBills()
                        Toast.makeText(this@HistoryBillActivity, "已恢复 ${billsToRestore.size} 条账单", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnPermanentDelete.setOnClickListener {
            val billsToDelete = selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            showConfirmDialog(
                title = "永久删除",
                message = "确定要永久删除选中的 ${billsToDelete.size} 条账单吗？此操作不可恢复。",
                confirmText = "永久删除",
                isDanger = true
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@HistoryBillActivity)
                    db.deletedBillDao().delete(billsToDelete)
                    withContext(Dispatchers.Main) {
                        exitSelectMode()
                        loadDeletedBills()
                        Toast.makeText(this@HistoryBillActivity, "已永久删除 ${billsToDelete.size} 条账单", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun toggleSelection(bill: DeletedBill) {
        if (selectedBills.contains(bill)) {
            selectedBills.remove(bill)
            if (selectedBills.isEmpty()) {
                isSelectMode = false
            }
        } else {
            selectedBills.add(bill)
        }
        updateUI()
    }

    private fun showBillActionDialog(bill: DeletedBill) {
        val themeCtx = ContextThemeWrapper(this, R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = "账单操作"
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = "选择对此账单的操作"

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnOk = panel.findViewById<TextView>(R.id.btn_followup_confirm_ok)
        val btnCancel = panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel)

        btnCancel.text = "恢复"
        btnCancel.setBackgroundResource(R.drawable.bg_delete_followup_primary_btn)
        btnCancel.setOnClickListener {
            dialog.dismiss()
            restoreBill(bill)
        }

        btnOk.text = "永久删除"
        btnOk.setBackgroundResource(R.drawable.bg_delete_followup_danger_btn)
        btnOk.setOnClickListener {
            dialog.dismiss()
            permanentlyDeleteBill(bill)
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun restoreBill(bill: DeletedBill) {
        showConfirmDialog(
            title = "恢复账单",
            message = "确定要恢复此账单吗？",
            confirmText = "确认恢复",
            isDanger = false
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@HistoryBillActivity)
                db.billDao().insertBill(bill.toBill())
                db.deletedBillDao().delete(bill)
                withContext(Dispatchers.Main) {
                    loadDeletedBills()
                    Toast.makeText(this@HistoryBillActivity, "已恢复账单", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun permanentlyDeleteBill(bill: DeletedBill) {
        showConfirmDialog(
            title = "永久删除",
            message = "确定要永久删除此账单吗？此操作不可恢复。",
            confirmText = "永久删除",
            isDanger = true
        ) {
            lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(this@HistoryBillActivity)
                db.deletedBillDao().delete(bill)
                withContext(Dispatchers.Main) {
                    loadDeletedBills()
                    Toast.makeText(this@HistoryBillActivity, "已永久删除账单", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadDeletedBills() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@HistoryBillActivity)
            val deletedBills = db.deletedBillDao().getAllDeletedBills()
            val grouped = buildGroupedItems(deletedBills)
            withContext(Dispatchers.Main) {
                adapter.submitList(grouped, selectedBills)
                tvEmpty.visibility = if (deletedBills.isEmpty()) View.VISIBLE else View.GONE
                rvDeletedBills.visibility = if (deletedBills.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun buildGroupedItems(bills: List<DeletedBill>): List<ListItem> {
        val items = mutableListOf<ListItem>()
        val cal = Calendar.getInstance()

        // 按日期分组
        val grouped = bills.groupBy { bill ->
            cal.timeInMillis = bill.time
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}-${cal.get(Calendar.DAY_OF_MONTH)}"
        }

        grouped.forEach { (_, dayBills) ->
            if (dayBills.isEmpty()) return@forEach
            val firstBill = dayBills.first()
            cal.timeInMillis = firstBill.time

            val dateStr = headerDateFormat.format(Date(firstBill.time))
            val weekdayStr = weekdayFormat.format(Date(firstBill.time))
            val expense = dayBills.filter { it.type == Bill.TYPE_EXPENSE }.sumOf { it.amount }
            val income = dayBills.filter { it.type == Bill.TYPE_INCOME }.sumOf { it.amount }

            items.add(ListItem.Header(dateStr, weekdayStr, income, expense))
            dayBills.forEach { bill ->
                items.add(ListItem.Item(bill))
            }
        }

        return items
    }

    private fun exitSelectMode() {
        isSelectMode = false
        selectedBills.clear()
        adapter.setSelectedBills(emptySet())
        adapter.setSelectMode(false)
        layoutBottomActions.visibility = View.GONE
    }

    private fun updateUI() {
        adapter.setSelectedBills(selectedBills)
        adapter.setSelectMode(isSelectMode)
        updateBottomActions()
    }

    private fun updateBottomActions() {
        if (selectedBills.isEmpty()) {
            layoutBottomActions.visibility = View.GONE
        } else {
            layoutBottomActions.visibility = View.VISIBLE
            btnRestore.text = "恢复(${selectedBills.size})"
            btnPermanentDelete.text = "永久删除(${selectedBills.size})"
        }
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_FlipAccounting))
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    // 扩展函数：DeletedBill -> Bill
    private fun DeletedBill.toBill(): Bill {
        return Bill(
            type = type,
            subType = subType,
            amount = amount,
            originalAmount = originalAmount,
            currency = currency,
            exchangeRate = exchangeRate,
            categoryId = categoryId,
            accountId = accountId,
            toAccountId = toAccountId,
            categoryName = categoryName,
            accountName = accountName,
            toAccountName = toAccountName,
            time = time,
            remark = remark,
            fee = fee,
            bookName = bookName,
            relatedBillId = relatedBillId,
            excludeFromStats = excludeFromStats
        )
    }

    // 列表项类型
    sealed class ListItem {
        data class Header(
            val dateStr: String,
            val weekdayStr: String,
            val income: Double,
            val expense: Double
        ) : ListItem()

        data class Item(val bill: DeletedBill) : ListItem()
    }

    // Adapter
    inner class HistoryBillAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items = listOf<ListItem>()
        private var selectMode = false
        private var selectedItems = setOf<DeletedBill>()

        var onItemClick: ((DeletedBill) -> Unit)? = null
        var onItemLongClick: ((DeletedBill) -> Unit)? = null

        fun submitList(newItems: List<ListItem>, selected: Set<DeletedBill>) {
            items = newItems
            selectedItems = selected
            notifyDataSetChanged()
        }

        fun setSelectMode(mode: Boolean) {
            selectMode = mode
            notifyDataSetChanged()
        }

        fun setSelectedBills(selected: Set<DeletedBill>) {
            selectedItems = selected
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.Header -> 0
                is ListItem.Item -> 1
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                0 -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bill_header, parent, false)
                    HeaderViewHolder(view)
                }
                else -> {
                    val view = LayoutInflater.from(parent.context).inflate(R.layout.item_home_transaction, parent, false)
                    ItemViewHolder(view)
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is ListItem.Header -> (holder as HeaderViewHolder).bind(item)
                is ListItem.Item -> (holder as ItemViewHolder).bind(item.bill)
            }
        }

        override fun getItemCount() = items.size

        inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvDate: TextView = itemView.findViewById(R.id.tv_header_date)
            private val tvSummary: TextView = itemView.findViewById(R.id.tv_header_summary)
            private val cbSelectDay: CheckBox = itemView.findViewById(R.id.cb_select_day)

            fun bind(header: ListItem.Header) {
                tvDate.text = "${header.dateStr} ${header.weekdayStr}"

                val summary = buildString {
                    if (header.income > 0) append("收:${CurrencyManager.getSymbol("CNY")}${String.format("%.2f", header.income)}")
                    if (header.income > 0 && header.expense > 0) append(" ")
                    if (header.expense > 0) append("支:${CurrencyManager.getSymbol("CNY")}${String.format("%.2f", header.expense)}")
                }
                tvSummary.text = summary

                cbSelectDay.visibility = if (selectMode) View.VISIBLE else View.GONE
                // 获取该日期下的所有账单
                val headerPos = adapterPosition
                val dayBills = mutableListOf<DeletedBill>()
                for (i in (headerPos + 1) until items.size) {
                    val item = items[i]
                    if (item is ListItem.Item) {
                        dayBills.add(item.bill)
                    } else {
                        break
                    }
                }
                val allSelected = dayBills.isNotEmpty() && dayBills.all { selectedItems.contains(it) }
                cbSelectDay.isChecked = allSelected
                cbSelectDay.setOnClickListener {
                    if (allSelected) {
                        selectedBills.removeAll(dayBills.toSet())
                    } else {
                        selectedBills.addAll(dayBills)
                    }
                    updateUI()
                }
            }
        }

        inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val cbSelect: CheckBox = itemView.findViewById(R.id.cb_bill_select)
            private val layoutIcon: View = itemView.findViewById(R.id.layout_icon_container)
            private val ivIcon: ImageView = itemView.findViewById(R.id.iv_bill_category_icon)
            private val tvCategory: TextView = itemView.findViewById(R.id.tv_bill_category)
            private val tvDetail: TextView = itemView.findViewById(R.id.tv_bill_detail)
            private val tvAmount: TextView = itemView.findViewById(R.id.tv_bill_amount)
            private val tvAsset: TextView = itemView.findViewById(R.id.tv_bill_asset)

            fun bind(bill: DeletedBill) {
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND

                // 设置无圆角背景
                itemView.setBackgroundResource(R.drawable.bg_history_bill_item)

                // 分类名
                val categoryName = when {
                    isTransfer -> "转账"
                    bill.categoryName.isBlank() -> "未分类"
                    else -> BillDisplayFormatter.stripRefundPrefix(bill.categoryName)
                }
                tvCategory.text = categoryName

                // 详情
                val detail = buildString {
                    append(bill.accountName)
                    if (bill.remark.isNotBlank()) append(" | ${bill.remark}")
                }
                tvDetail.text = detail

                // 金额
                val symbol = CurrencyManager.getSymbol(bill.currency)
                val amountText = when {
                    isRefund -> "$symbol${String.format("%.2f", bill.amount)}"
                    bill.type == Bill.TYPE_EXPENSE -> "-$symbol${String.format("%.2f", bill.amount)}"
                    bill.type == Bill.TYPE_INCOME -> "+$symbol${String.format("%.2f", bill.amount)}"
                    else -> "$symbol${String.format("%.2f", bill.amount)}"
                }
                tvAmount.text = amountText

                val amountColor = when {
                    isRefund -> Color.parseColor("#9AA1AA")
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#757575")
                }
                tvAmount.setTextColor(amountColor)

                // 资产信息
                tvAsset.text = bill.accountName
                tvAsset.visibility = View.VISIBLE

                // 图标
                val iconColor = when {
                    isRefund -> Color.parseColor("#9E9E9E")
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#9E9E9E")
                }
                ivIcon.setColorFilter(iconColor)
                layoutIcon.setBackgroundResource(
                    when (bill.type) {
                        Bill.TYPE_EXPENSE -> R.drawable.bg_home_transaction_icon_refined
                        Bill.TYPE_INCOME -> R.drawable.bg_home_transaction_icon_refined
                        else -> R.drawable.bg_home_transaction_icon_refined
                    }
                )

                // 加载图标
                val lookupName = if (isRefund) categoryName else bill.categoryName
                val lookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                ivIcon.setImageResource(R.mipmap.ic_launcher)
                itemView.post {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val iconUrl = CategoryIconHelper.findCategoryIcon(this@HistoryBillActivity, lookupName, lookupType)
                        withContext(Dispatchers.Main) {
                            if (iconUrl.isNotEmpty()) {
                                Glide.with(ivIcon)
                                    .load(iconUrl)
                                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                                    .into(ivIcon)
                            }
                        }
                    }
                }

                // 选择模式
                cbSelect.visibility = if (selectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = selectedItems.contains(bill)

                // 点击事件
                itemView.setOnClickListener {
                    onItemClick?.invoke(bill)
                }
                itemView.setOnLongClickListener {
                    onItemLongClick?.invoke(bill)
                    true
                }
            }
        }
    }
}
