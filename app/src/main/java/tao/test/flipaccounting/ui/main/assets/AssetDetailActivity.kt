package tao.test.flipaccounting.ui.main.assets

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.bumptech.glide.Glide
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONObject
import tao.test.flipaccounting.logic.AccountingFormController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.AddAssetActivity
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.repository.AssetRepository
import tao.test.flipaccounting.logic.BillAssetImpactService
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.logic.CurrencyUtils
import tao.test.flipaccounting.ui.activity.EditBillActivity
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssetDetailActivity : AppCompatActivity() {

    private lateinit var tvBalance: TextView
    private lateinit var tvToolbarAssetName: TextView
    private lateinit var tvBtnSearch: TextView
    private lateinit var layoutAssetSearchBar: View
    private lateinit var etAssetBillSearch: EditText
    private lateinit var rvTransactions: RecyclerView
    private lateinit var adapter: TransactionAdapter

    private var assetId: Long = -1
    private var currentAsset: Asset? = null
    private var allAssetBills: List<Bill> = emptyList()
    private var assetSearchKeyword: String = ""
    private var isAssetSearchMode: Boolean = false
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val assetRepository by lazy { AssetRepository(db.assetDao(), db.billDao(), db) }
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    data class MonthHeaderRow(
        val monthLabel: String,
        val inflow: Double,
        val outflow: Double
    )

    data class BillRow(val bill: Bill)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_detail)

        assetId = intent.getLongExtra("ASSET_ID", -1)
        if (assetId == -1L) {
            finish()
            return
        }

        initViews()
        observeData()
    }

    private fun initViews() {
        tvBalance = findViewById(R.id.tv_asset_balance)
        tvToolbarAssetName = findViewById(R.id.tv_toolbar_asset_name)
        tvBtnSearch = findViewById(R.id.tv_btn_search)
        layoutAssetSearchBar = findViewById(R.id.layout_asset_search_bar)
        etAssetBillSearch = findViewById(R.id.et_asset_bill_search)
        rvTransactions = findViewById(R.id.rv_transactions)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = ""

        tvBtnSearch.setOnClickListener {
            handleAssetSearchClick()
        }
        findViewById<View>(R.id.tv_btn_edit).setOnClickListener {
            val intent = Intent(this, AddAssetActivity::class.java)
            intent.putExtra("ASSET_ID", assetId)
            startActivity(intent)
        }

        findViewById<View>(R.id.tv_btn_delete).setOnClickListener {
            showDeleteConfirmDialog()
        }

        rvTransactions.layoutManager = LinearLayoutManager(this)
        adapter = TransactionAdapter()
        rvTransactions.adapter = adapter
        etAssetBillSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!isAssetSearchMode) return
                applyAssetBillSearch(s?.toString().orEmpty())
            }
        })
        refreshAssetSearchButton()

        val fabAddBill = findViewById<FloatingActionButton>(R.id.fab_add_bill)
        fabAddBill.setOnClickListener {
            showAddBillForAsset()
        }
    }

    private fun observeData() {
        lifecycleScope.launch {
            db.assetDao().observeAssetById(assetId).filterNotNull().collectLatest { asset ->
                currentAsset = asset
                updateAssetUI(asset)
            }
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.billDao().backfillAssetLinksByName()
            }
            val assetName = currentAsset?.name.orEmpty()
            db.billDao().getBillsByAssetIdOrName(assetId, assetName).collectLatest { bills ->
                allAssetBills = bills
                adapter.submitList(filterAssetBillsByKeyword(bills))
            }
        }
    }

    private fun updateAssetUI(asset: Asset) {
        tvToolbarAssetName.text = asset.name
        tvBalance.text = CurrencyUtils.formatAmount(asset.balance, asset.currency)
    }

    private fun handleAssetSearchClick() {
        if (!isAssetSearchMode) {
            isAssetSearchMode = true
            layoutAssetSearchBar.visibility = View.VISIBLE
            etAssetBillSearch.requestFocus()
            etAssetBillSearch.setText(assetSearchKeyword)
            etAssetBillSearch.setSelection(etAssetBillSearch.text.length)
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etAssetBillSearch, InputMethodManager.SHOW_IMPLICIT)
            refreshAssetSearchButton()
            return
        }

        if (assetSearchKeyword.isNotBlank()) {
            etAssetBillSearch.setText("")
            return
        }

        isAssetSearchMode = false
        layoutAssetSearchBar.visibility = View.GONE
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etAssetBillSearch.windowToken, 0)
        refreshAssetSearchButton()
    }

    private fun refreshAssetSearchButton() {
        tvBtnSearch.text = if (!isAssetSearchMode) "搜索" else if (assetSearchKeyword.isBlank()) "关闭" else "清空"
    }

    private fun applyAssetBillSearch(rawKeyword: String) {
        assetSearchKeyword = rawKeyword.trim()
        refreshAssetSearchButton()
        adapter.submitList(filterAssetBillsByKeyword(allAssetBills))
    }

    private fun filterAssetBillsByKeyword(source: List<Bill>): List<Bill> {
        val keyword = assetSearchKeyword.lowercase(Locale.getDefault())
        if (keyword.isBlank()) return source
        val normalizedKeyword = keyword.replace(',', '.')

        return source.filter { bill ->
            val textMatched = listOf(
                bill.remark,
                bill.categoryName,
                BillDisplayFormatter.stripRefundPrefix(bill.categoryName),
                bill.accountName,
                bill.toAccountName
            ).any { text ->
                text.isNotBlank() && text.lowercase(Locale.getDefault()).contains(keyword)
            }
            if (textMatched) return@filter true

            val numericCandidates = listOf(
                String.format(Locale.US, "%.2f", bill.amount),
                bill.amount.toString(),
                String.format(Locale.US, "%.2f", bill.originalAmount),
                bill.originalAmount.toString(),
                String.format(Locale.US, "%.2f", bill.amount * bill.exchangeRate)
            )
            numericCandidates.any { value ->
                val v = value.lowercase(Locale.getDefault())
                v.contains(keyword) || v.contains(normalizedKeyword)
            }
        }
    }

    private fun showAddBillForAsset() {
        val asset = currentAsset ?: return
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_floating_window, null)
        val formController = AccountingFormController(
            ctx = this,
            rootView = view,
            onCloseRequest = { _ -> bottomSheet.dismiss() }
        )
        val prefill = JSONObject().apply {
            put("asset_name", asset.name)
        }
        formController.fillDataToUi(prefill, showToast = false)
        bottomSheet.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                formController.handleBackPressed()
            } else {
                false
            }
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }

    private fun showDeleteConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("\u5220\u9664\u8D26\u6237")
            .setMessage("\u786E\u5B9A\u5220\u9664\u8BE5\u8D26\u6237\u5417\uFF1F\u76F8\u5173\u7684\u8D26\u5355\u5C06\u5931\u53BB\u8D26\u6237\u5173\u8054\u3002")
            .setPositiveButton("\u5220\u9664") { _, _ ->
                lifecycleScope.launch {
                    currentAsset?.let { assetRepository.deleteAssetWithCleanup(it) }
                    finish()
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .show()
    }

    inner class TransactionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val typeMonthHeader = 0
        private val typeBillItem = 1

        private val rows = mutableListOf<Any>()
        private val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
    private val monthLabelFormat = SimpleDateFormat("yyyy.MM\u6708", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())

        fun submitList(newList: List<Bill>) {
            rows.clear()
            if (newList.isNotEmpty()) {
                val sorted = newList.sortedWith(compareByDescending<Bill> { it.time }.thenByDescending { it.id })
                val grouped = sorted.groupBy { monthKeyFormat.format(Date(it.time)) }

                grouped.forEach { (_, monthBills) ->
                    var monthlyInflow = 0.0
                    var monthlyOutflow = 0.0

                    monthBills.forEach { bill ->
                        when {
                            bill.subType == Bill.SUBTYPE_REFUND -> {
                                monthlyInflow += amountInAssetCurrency(bill, assetId, isInflow = true)
                            }

                            bill.type == Bill.TYPE_EXPENSE -> {
                                monthlyOutflow += amountInAssetCurrency(bill, assetId, isInflow = false)
                            }

                            bill.type == Bill.TYPE_INCOME -> {
                                monthlyInflow += amountInAssetCurrency(bill, assetId, isInflow = true)
                            }

                            bill.type == Bill.TYPE_TRANSFER -> {
                                val isTransferOut = bill.accountId == assetId && bill.toAccountId != assetId
                                val isTransferIn = bill.toAccountId == assetId && bill.accountId != assetId
                                if (isTransferOut) monthlyOutflow += amountInAssetCurrency(bill, assetId, isInflow = false)
                                if (isTransferIn) monthlyInflow += amountInAssetCurrency(bill, assetId, isInflow = true)
                            }
                        }
                    }

                    val monthLabel = monthLabelFormat.format(Date(monthBills.first().time))
                    rows.add(MonthHeaderRow(monthLabel, monthlyInflow, monthlyOutflow))
                    monthBills.forEach { rows.add(BillRow(it)) }
                }
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is MonthHeaderRow -> typeMonthHeader
                is BillRow -> typeBillItem
                else -> typeBillItem
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == typeMonthHeader) {
                MonthHeaderViewHolder(inflater.inflate(R.layout.item_asset_month_header, parent, false))
            } else {
                BillViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is MonthHeaderRow -> (holder as MonthHeaderViewHolder).bind(row)
                is BillRow -> (holder as BillViewHolder).bind(row.bill, position)
            }
        }

        override fun getItemCount(): Int = rows.size

        inner class MonthHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvMonth = v.findViewById<TextView>(R.id.tv_month_title)
            private val tvSummary = v.findViewById<TextView>(R.id.tv_month_summary)

            fun bind(header: MonthHeaderRow) {
                val symbol = CurrencyManager.getSymbol(currentAsset?.currency ?: "CNY")
                tvMonth.text = header.monthLabel
                tvSummary.text = "\u6D41\u5165:${symbol}${String.format(Locale.getDefault(), "%.2f", header.inflow)}\n\u6D41\u51FA:${symbol}${String.format(Locale.getDefault(), "%.2f", header.outflow)}"
            }
        }

        inner class BillViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvCategory = v.findViewById<TextView>(R.id.tv_bill_category)
            private val tvDetail = v.findViewById<TextView>(R.id.tv_bill_detail)
            private val tvAmount = v.findViewById<TextView>(R.id.tv_bill_amount)
            private val tvTime = v.findViewById<TextView>(R.id.tv_bill_time)
            private val ivIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
            private val iconContainer = v.findViewById<View>(R.id.layout_icon_container)
            private val cbSelect = v.findViewById<View>(R.id.cb_bill_select)

            fun bind(bill: Bill, position: Int) {
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND
                val baseCategory = stripRefundPrefix(bill.categoryName)
                val displayCurrency = currentAsset?.currency ?: bill.currency
                val symbol = CurrencyManager.getSymbol(displayCurrency)
                val displayAmount = amountForAssetRow(bill, assetId)

                val isGroupEnd = position == rows.lastIndex || rows.getOrNull(position + 1) is MonthHeaderRow
                itemView.setBackgroundResource(
                    if (isGroupEnd) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_middle
                )
                iconContainer.setBackgroundResource(R.drawable.bg_circle_soft)
                cbSelect.visibility = View.GONE

                tvCategory.text = when {
                    isRepayment -> "\u8FD8\u6B3E"
                    isTransfer -> "\u8F6C\u8D26"
                    isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
                    else -> bill.categoryName.ifEmpty { "\u672A\u5206\u7C7B" }
                }
                tvCategory.setTextColor(if (isRefund) Color.parseColor("#8E98A3") else Color.parseColor("#333333"))

                val refundAmount = refundedAmountInBillCurrency(bill)
                tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
                    BillDisplayFormatter.buildRefundedExpenseAmountText(
                        netAmount = bill.amount,
                        originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                        currency = bill.currency
                    )
                } else {
                    val amountPrefix = when {
                        isRefund -> ""
                        bill.type == Bill.TYPE_EXPENSE -> "-"
                        bill.type == Bill.TYPE_INCOME -> "+"
                        else -> ""
                    }
                    "$amountPrefix$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
                }
                tvAmount.setTextColor(
                    when {
                        isRefund -> Color.parseColor("#9AA1AA")
                        bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                        bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                        else -> Color.parseColor("#5F6772")
                    }
                )

                tvTime.visibility = View.GONE
                tvDetail.text = buildString {
                    append(dateFormat.format(Date(bill.time)))
                    if (bill.remark.isNotBlank()) {
                        append(" ")
                        append(bill.remark)
                    }
                }
                tvDetail.visibility = View.VISIBLE
                tvDetail.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    ivIcon.imageTintList = null
                }
                val iconTint = when {
                    isRefund -> Color.parseColor("#8E98A3")
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#9E9E9E")
                }
                ivIcon.setColorFilter(iconTint)

                val iconName = if (isRefund) baseCategory else bill.categoryName
                val iconType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
                ivIcon.setImageResource(R.mipmap.ic_launcher)
                CoroutineScope(Dispatchers.IO).launch {
                    val iconUrl = CategoryIconHelper.findCategoryIcon(itemView.context, iconName, iconType)
                    withContext(Dispatchers.Main) {
                        if (iconUrl.isNotEmpty()) {
                            Glide.with(itemView.context)
                                .load(iconUrl)
                                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                                .into(ivIcon)
                        }
                    }
                }

                itemView.setOnClickListener { showBillDetailSheet(bill) }
            }
        }

        private fun stripRefundPrefix(categoryName: String): String {
            return categoryName
                .removePrefix("\u9000\u6B3E\u00B7")
                .removePrefix("\u9000\u6B3E\uFF1A")
                .trim()
        }
    }

    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    private fun stripRefundPrefix(categoryName: String): String {
        return categoryName
            .removePrefix("退款·")
            .removePrefix("退款：")
            .trim()
    }

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        tvCategory.text = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> bill.categoryName.ifEmpty { "未分类" }
        }

        val refundAmount = refundedAmountInBillCurrency(bill)
        tvAmount.text = if (!forceGrayStyle && !isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val sign = when {
                forceGrayStyle || isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        }

        if (forceGrayStyle || isRefund) {
            tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
            tvCategory.setTextColor(Color.parseColor("#8E98A3"))
            tvDetail.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#333333"))
            tvDetail.setTextColor(Color.parseColor("#999999"))
            tvTime.setTextColor(Color.parseColor("#999999"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#C62828"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#757575"))
            }
        }

        val detailStr = buildString {
            if (isTransfer) {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            } else {
                if (bill.accountName.isNotEmpty()) append(bill.accountName)
                if (!forceGrayStyle) {
                    val refundAmount = refundedAmountInBillCurrency(bill)
                    if (refundAmount > 0.0 && bill.type == Bill.TYPE_EXPENSE) {
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
        }

        val shortTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        if (forceGrayStyle) {
            tvDetail.text = shortTimeFormat.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            if (bill.accountName.isNotEmpty()) {
                tvTime.text = bill.accountName
                tvTime.visibility = View.VISIBLE
            } else {
                tvTime.visibility = View.GONE
            }
        } else {
            if (detailStr.isNotEmpty()) {
                tvDetail.text = detailStr
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }
            tvTime.text = shortTimeFormat.format(Date(bill.time))
            tvTime.visibility = View.VISIBLE
        }

        val iconLookupName = if (isRefund) baseCategory else bill.categoryName
        val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
        val iconTint = when {
            forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
            bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#C62828")
            bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#9E9E9E")
        }
        ivIcon.setImageResource(R.mipmap.ic_launcher)
        ivIcon.setColorFilter(iconTint)
        lifecycleScope.launch(Dispatchers.IO) {
            val iconUrl = CategoryIconHelper.findCategoryIcon(this@AssetDetailActivity, iconLookupName, iconLookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(row)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .into(ivIcon)
                }
            }
        }
    }

    private fun addLinkedBillRow(
        container: LinearLayout,
        bill: Bill,
        forceGrayStyle: Boolean,
        onClick: (() -> Unit)? = null
    ) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(row, bill, forceGrayStyle)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { onClick?.invoke() }
        container.addView(row)
    }

    private fun renderRefundRecords(view: View, sourceBill: Bill, onItemClick: (Bill) -> Unit) {
        val section = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        section.visibility = View.GONE
        container.removeAllViews()

        lifecycleScope.launch(Dispatchers.IO) {
            val refunds = db.billDao().getRefundBillsBySourceId(sourceBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    section.visibility = View.GONE
                    return@withContext
                }
                section.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addLinkedBillRow(container, refundBill, forceGrayStyle = true) {
                        onItemClick(refundBill)
                    }
                }
            }
        }
    }

    private fun renderOriginalBill(view: View, originalBill: Bill) {
        val section = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        container.removeAllViews()
        section.visibility = View.VISIBLE
        addLinkedBillRow(container, originalBill, forceGrayStyle = false) {
            showBillDetailSheet(originalBill, detailOwnerAssetId(originalBill))
        }
    }

    private fun showBillDetailSheet(bill: Bill, displayAssetId: Long = detailOwnerAssetId(bill)) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)
        val detailTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val recordTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val assetCurrency = currentAsset?.currency ?: bill.currency
        val symbol = CurrencyManager.getSymbol(assetCurrency)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val layoutCategory = view.findViewById<View>(R.id.layout_detail_category)
        val lineCategory = view.findViewById<View>(R.id.line_category)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = bill.subType == Bill.SUBTYPE_REFUND
        val displayAmount = amountForAssetRow(bill, displayAssetId)
        var linkedOriginalForRefund: Bill? = null

        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val layoutIncoming = view.findViewById<View>(R.id.layout_detail_incoming)
        val lineIncoming = view.findViewById<View>(R.id.line_incoming)
        val tvIncomingAmount = view.findViewById<TextView>(R.id.tv_detail_incoming_amount)
        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) "\u8FD8\u6B3E\u8BE6\u60C5" else "\u8F6C\u8D26\u8BE6\u60C5"
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))

            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE

            tvAccount.text = buildString {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            }

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-$symbol${String.format(Locale.getDefault(), "%.2f", bill.fee)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            // 多币种：异步查转入账户货币
            lifecycleScope.launch(Dispatchers.IO) {
                val toAsset = bill.toAccountId?.let { AppDatabase.getDatabase(this@AssetDetailActivity).assetDao().getAssetById(it) }
                val toAssetCurrency = toAsset?.currency ?: "CNY"
                withContext(Dispatchers.Main) {
                    val sourceCurrency = bill.currency
                    val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        tvAmountLabel.text = "转出金额"
                        tvAmount.text = "$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
                        val targetAmount = bill.amount * bill.exchangeRate
                        val toSymbol = CurrencyManager.getSymbol(toAssetCurrency)
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = "$toSymbol${String.format(Locale.getDefault(), "%.2f", targetAmount)}"
                    } else {
                        tvAmountLabel.text = if (isRepayment) "\u8FD8\u6B3E\u91D1\u989D" else "\u8F6C\u8D26\u91D1\u989D"
                        tvAmount.text = "$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
                    }
                }
            }
        } else {
            tvTitle.text = "\u8BE6\u60C5"
            tvAmountLabel.text = "\u91D1\u989D"

            val sign = when {
                isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            tvAmount.text = if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
                BillDisplayFormatter.buildRefundedExpenseAmountText(
                    netAmount = bill.amount,
                    originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                    currency = bill.currency
                )
            } else {
                "$sign$symbol${String.format(Locale.getDefault(), "%.2f", displayAmount)}"
            }
            tvAmount.setTextColor(
                when {
                    isRefund -> Color.parseColor("#9AA1AA")
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF3B30")
                    bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
                    else -> Color.parseColor("#5F6772")
                }
            )

            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tv_detail_category).text = bill.categoryName

            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            if (isRefund) {
                tvAccount.text = bill.accountName
                lifecycleScope.launch(Dispatchers.IO) {
                    val original = bill.relatedBillId?.let { db.billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccount.text = bill.accountName
                if (bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
                    renderRefundRecords(view, bill) { refundBill ->
                        showBillDetailSheet(refundBill, detailOwnerAssetId(refundBill))
                    }
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val crossCurrencyText = buildAssetDetailFormula(bill, displayAssetId)
                    withContext(Dispatchers.Main) {
                        if (!crossCurrencyText.isNullOrBlank()) {
                            tvAmountFormula.visibility = View.VISIBLE
                            tvAmountFormula.text = crossCurrencyText
                        }
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.tv_detail_time).text = detailTimeFormat.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text =
            "\u8BB0\u5F55\u4E8E ${recordTimeFormat.format(Date(bill.time))}"
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.DEFAULT_BOOK }

        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = if (bill.remark.isNotBlank()) bill.remark else "\u65E0\u5907\u6CE8"
        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && refundedAmountInBillCurrency(bill) > 0.0) {
            lifecycleScope.launch(Dispatchers.IO) {
                val refunds = db.billDao().getRefundBillsBySourceId(bill.id)
                withContext(Dispatchers.Main) {
                    tvRemark.text = BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refunds)
                }
            }
        }

        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnCopy = view.findViewById<View>(R.id.btn_copy)

        if (isRefund) {
            btnCopy.visibility = View.GONE
            btnRefund.visibility = View.GONE
        } else if (bill.type == Bill.TYPE_INCOME || bill.type == Bill.TYPE_TRANSFER || bill.amount <= 0.0) {
            btnRefund.visibility = View.GONE
        } else {
            btnRefund.visibility = View.VISIBLE
        }

        btnRefund.setOnClickListener {
            bottomSheet.dismiss()
            showRefundSheet(bill)
        }

        btnEdit.setOnClickListener {
            bottomSheet.dismiss()
            if (isRefund) {
                val cachedOriginal = linkedOriginalForRefund
                if (cachedOriginal != null) {
                    showRefundSheet(cachedOriginal, bill)
                    return@setOnClickListener
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val source = bill.relatedBillId?.let { db.billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(this@AssetDetailActivity, EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            startActivity(intent)
                        }
                    }
                }
                return@setOnClickListener
            }
            val intent = Intent(this, EditBillActivity::class.java)
            intent.putExtra("BILL_ID", bill.id)
            startActivity(intent)
        }

        btnCopy.setOnClickListener {
            bottomSheet.dismiss()
            val intent = Intent(this, EditBillActivity::class.java)
            intent.putExtra("BILL_ID", bill.id)
            intent.putExtra("IS_COPY", true)
            startActivity(intent)
        }

        view.findViewById<View>(R.id.btn_delete).setOnClickListener {
            bottomSheet.dismiss()
            AlertDialog.Builder(this)
                .setTitle("\u5220\u9664\u8D26\u5355")
                .setMessage("\u786E\u5B9A\u5220\u9664\u8FD9\u7B14\u8D26\u5355\u5417\uFF1F")
                .setPositiveButton("\u5220\u9664") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AssetDetailActivity, "\u5DF2\u5220\u9664", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("\u53D6\u6D88", null)
                .show()
        }

        bottomSheet.setContentView(view)
        configureDetailBottomSheet(bottomSheet)
        bottomSheet.show()
    }

    private fun configureDetailBottomSheet(bottomSheet: BottomSheetDialog) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + resources.displayMetrics.density.times(24).toInt(),
                    (screenHeight * 0.88f).toInt()
                )
                bottomSheetView.layoutParams = bottomSheetView.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheetView.requestLayout()
                behavior.peekHeight = desiredHeight
            }
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun formatMoney(amount: Double, currency: String): String =
        BillDisplayFormatter.formatMoney(amount, currency)

    private fun amountInAssetCurrency(bill: Bill, ownerAssetId: Long, isInflow: Boolean): Double {
        val assetCurrency = currentAsset?.currency ?: bill.currency
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val baseExpenseAmount = baseOriginalAmount(bill)
                BillAssetImpactService.convertAmountBetweenCurrencies(baseExpenseAmount, bill.currency, assetCurrency)
            }

            bill.type == Bill.TYPE_TRANSFER && isInflow && bill.toAccountId == ownerAssetId -> {
                val grossTarget = bill.amount * bill.exchangeRate
                val feeInTarget = if (bill.fee > 0.0) {
                    BillAssetImpactService.convertAmountBetweenCurrencies(bill.fee, bill.currency, assetCurrency)
                } else {
                    0.0
                }
                grossTarget - feeInTarget
            }

            bill.type == Bill.TYPE_TRANSFER && !isInflow && bill.accountId == ownerAssetId -> {
                val sourceAmount = BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
                val feeInSource = if (bill.fee > 0.0) {
                    BillAssetImpactService.convertAmountBetweenCurrencies(bill.fee, bill.currency, assetCurrency)
                } else {
                    0.0
                }
                sourceAmount + feeInSource
            }

            else -> BillAssetImpactService.convertAmountBetweenCurrencies(bill.amount, bill.currency, assetCurrency)
        }
    }

    private fun amountForAssetRow(bill: Bill, ownerAssetId: Long): Double {
        val isInflow = when {
            bill.subType == Bill.SUBTYPE_REFUND -> true
            bill.type == Bill.TYPE_INCOME -> true
            bill.type == Bill.TYPE_TRANSFER -> bill.toAccountId == ownerAssetId && bill.accountId != ownerAssetId
            else -> false
        }
        return amountInAssetCurrency(bill, ownerAssetId, isInflow)
    }

    private fun baseOriginalAmount(bill: Bill): Double {
        return if (bill.originalAmount > 0.0) {
            kotlin.math.max(bill.originalAmount, bill.amount)
        } else {
            bill.amount
        }
    }

    private fun detailOwnerAssetId(bill: Bill): Long {
        return when {
            bill.subType == Bill.SUBTYPE_REFUND -> bill.accountId ?: assetId
            bill.type == Bill.TYPE_TRANSFER -> when {
                bill.accountId == assetId || bill.toAccountId == assetId -> assetId
                bill.accountId != null -> bill.accountId
                else -> bill.toAccountId ?: assetId
            }
            else -> bill.accountId ?: assetId
        }
    }

    private fun refundedAmountInBillCurrency(bill: Bill): Double {
        if (bill.type != Bill.TYPE_EXPENSE || bill.subType == Bill.SUBTYPE_REFUND) return 0.0
        return (baseOriginalAmount(bill) - bill.amount).coerceAtLeast(0.0)
    }

    private fun buildAssetDetailFormula(bill: Bill, ownerAssetId: Long): String? {
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val refundedAmount = refundedAmountInBillCurrency(bill)
                if (refundedAmount > 0.0) {
                    "退款${formatMoney(refundedAmount, bill.currency)}，净支出${formatMoney(bill.amount, bill.currency)}"
                } else {
                    buildCrossCurrencyDetailFormula(bill, "CNY")
                }
            }

            else -> buildCrossCurrencyDetailFormula(bill, "CNY")
        }
    }

    private fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_refund_bottom_sheet, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvOrigAmount = view.findViewById<TextView>(R.id.tv_orig_amount)
        val tvOrigCategory = view.findViewById<TextView>(R.id.tv_orig_category)
        val etRefundAmount = view.findViewById<EditText>(R.id.et_refund_amount)
        val layoutRefundAccount = view.findViewById<View>(R.id.layout_refund_account)
        val tvRefundAccount = view.findViewById<TextView>(R.id.tv_refund_account)
        val layoutRefundTime = view.findViewById<View>(R.id.layout_refund_time)
        val tvRefundTime = view.findViewById<TextView>(R.id.tv_refund_time)
        val etRefundRemark = view.findViewById<EditText>(R.id.et_refund_remark)
        val btnSaveRefund = view.findViewById<View>(R.id.btn_save_refund)
        val btnBack = view.findViewById<View>(R.id.btn_back)

        tvTitle.text = if (editingRefund == null) "退款" else "编辑退款"
        val sourceOriginalAmount = baseOriginalAmount(originalBill)
        tvOrigAmount.text = formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = stripRefundPrefix(originalBill.categoryName)

        val defaultRefundAmount = editingRefund?.amount ?: originalBill.amount
        etRefundAmount.setText(String.format(Locale.getDefault(), "%.2f", defaultRefundAmount))

        var selectedAccount = editingRefund?.accountName ?: originalBill.accountName
        tvRefundAccount.text = selectedAccount

        var selectedTimeStr = if (editingRefund == null) {
            dfDetailTime.format(Date())
        } else {
            dfDetailTime.format(Date(editingRefund.time))
        }
        tvRefundTime.text = selectedTimeStr

        if (editingRefund != null) {
            etRefundRemark.setText(editingRefund.remark)
        }

        btnBack?.setOnClickListener { bottomSheet.cancel() }
        bottomSheet.setOnCancelListener {
            showBillDetailSheet(editingRefund ?: originalBill, detailOwnerAssetId(editingRefund ?: originalBill))
        }

        layoutRefundAccount.setOnClickListener {
            OverlayDialogs.showGridAssetPicker(this, tvRefundAccount.text.toString(), "选择退款入账账户") { account ->
                selectedAccount = account
                tvRefundAccount.text = account
            }
        }

        layoutRefundTime.setOnClickListener {
            OverlayDialogs.showCustomTimePicker(this) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val refundAmount = etRefundAmount.text.toString().toDoubleOrNull() ?: 0.0
            if (refundAmount <= 0.0) {
                Toast.makeText(this, "请输入有效的退款金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "选择账户") {
                Toast.makeText(this, "请选择入账账户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "退款：${stripRefundPrefix(originalBill.categoryName)}"
            }
            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val account = db.assetDao().getAssetByName(selectedAccount)
                val refundBill = Bill(
                    id = editingRefund?.id ?: 0,
                    amount = refundAmount,
                    originalAmount = refundAmount,
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_REFUND,
                    accountId = account?.id ?: editingRefund?.accountId,
                    accountName = selectedAccount,
                    categoryName = originalBill.categoryName,
                    time = refundTimeLong,
                    remark = finalRemark,
                    currency = originalBill.currency
                )

                try {
                    tao.test.flipaccounting.logic.BillMutationService.saveRefundBill(
                        db = db,
                        originalBill = originalBill,
                        refundBill = refundBill,
                        previousRefundBill = editingRefund
                    )
                } catch (_: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AssetDetailActivity, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AssetDetailActivity, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AssetDetailActivity, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }

    private fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? =
        BillDisplayFormatter.buildCrossCurrencyAmountFormula(bill, accountCurrency)

    private fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? =
        BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)
}
