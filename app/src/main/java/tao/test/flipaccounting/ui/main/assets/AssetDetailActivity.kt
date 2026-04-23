package tao.test.flipaccounting.ui.main.assets

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
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
import androidx.appcompat.view.ContextThemeWrapper
import androidx.activity.OnBackPressedCallback
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
import tao.test.flipaccounting.logic.BillDeleteHelper
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.logic.CurrencyUtils
import tao.test.flipaccounting.ui.activity.EditBillActivity
import tao.test.flipaccounting.ui.common.AddBillEntrySheetLauncher
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssetDetailActivity : AppCompatActivity() {
    companion object {
        private data class AssetDetailCache(
            val asset: Asset?,
            val bills: List<Bill>,
            val updatedAtMs: Long
        )

        private val detailCacheByAssetId = mutableMapOf<Long, AssetDetailCache>()
    }

    private lateinit var tvToolbarAssetName: TextView
    private lateinit var rvTransactions: RecyclerView
    private lateinit var tvBtnSearch: TextView
    private lateinit var layoutSearchBar: View
    private lateinit var etBillSearch: EditText
    private lateinit var adapter: TransactionAdapter
    private lateinit var fabAddBill: FloatingActionButton
    private lateinit var layoutMultiSelectActions: View
    private lateinit var btnMsCancel: TextView
    private lateinit var btnMsSelectAll: TextView
    private lateinit var btnMsMove: TextView
    private lateinit var btnMsDelete: TextView
    private lateinit var toolbar: androidx.appcompat.widget.Toolbar
    private lateinit var toolbarDoubleTapDetector: GestureDetector
    private var fabHiddenByScroll = false
    private var fabScrollAccumulator = 0

    private var assetId: Long = -1
    private var currentAsset: Asset? = null
    private var allAssetBills: List<Bill> = emptyList()
    private var searchQuery: String = ""
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val assetRepository by lazy { AssetRepository(db.assetDao(), db.billDao(), db) }
    private val dfDetailTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val dfDetailTimeShort = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    data class MonthHeaderRow(
        val monthLabel: String,
        val inflow: Double,
        val outflow: Double
    )

    data class BalanceHeaderRow(
        val balanceText: String,
        val remarkText: String
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
        setupBackPressForMultiSelect()
        observeData()
    }

    private fun initViews() {
        tvToolbarAssetName = findViewById(R.id.tv_toolbar_asset_name)
        rvTransactions = findViewById(R.id.rv_transactions)
        tvBtnSearch = findViewById(R.id.tv_btn_search)
        layoutSearchBar = findViewById(R.id.layout_asset_search_bar)
        etBillSearch = findViewById(R.id.et_asset_bill_search)
        layoutMultiSelectActions = findViewById(R.id.layout_multi_select_actions)
        btnMsCancel = findViewById(R.id.btn_ms_cancel)
        btnMsSelectAll = findViewById(R.id.btn_ms_select_all)
        btnMsMove = findViewById(R.id.btn_ms_move)
        btnMsDelete = findViewById(R.id.btn_ms_delete)

        toolbar = findViewById(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = ""
        setupToolbarDoubleTapToHome()
        updateSearchButtonState(false)
        tvBtnSearch.setOnClickListener { toggleSearchPanel() }
        findViewById<View>(R.id.tv_btn_stats).setOnClickListener {
            startActivity(Intent(this, AssetStatsActivity::class.java).putExtra("ASSET_ID", assetId))
        }
        etBillSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                applyBillSearch()
            }
        })

        findViewById<View>(R.id.tv_btn_edit).setOnClickListener {
            val intent = Intent(this, AddAssetActivity::class.java)
            intent.putExtra("ASSET_ID", assetId)
            startActivity(intent)
        }

        findViewById<View>(R.id.tv_btn_delete).setOnClickListener {
            showDeleteConfirmDialog()
        }

        rvTransactions.layoutManager = LinearLayoutManager(this)
        adapter = TransactionAdapter().apply {
            onBillItemClick = { bill -> openBillEditor(bill) }
            onSelectionChanged = { count -> updateDetailMultiSelectUi(count) }
        }
        rvTransactions.adapter = adapter
        rvTransactions.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                applyFabScrollBehavior(dy)
            }
        })

        fabAddBill = findViewById(R.id.fab_add_bill)
        fabAddBill.setOnClickListener {
            showAddBillForAsset()
        }
        fabAddBill.post { showAssetDetailFab() }
        setupMultiSelectActions()
    }

    private fun setupBackPressForMultiSelect() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.isMultiSelectMode) {
                    adapter.clearSelection()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupToolbarDoubleTapToHome() {
        toolbarDoubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                rvTransactions.post { rvTransactions.smoothScrollToPosition(0) }
                return true
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::toolbarDoubleTapDetector.isInitialized && isEventInsideView(ev, toolbar)) {
            val localX = ev.rawX - toolbar.screenX()
            val localY = ev.rawY - toolbar.screenY()
            if (!toolbar.isTouchInsideChild(localX, localY)) {
                toolbarDoubleTapDetector.onTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun isEventInsideView(ev: MotionEvent, view: View): Boolean {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun View.screenX(): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[0]
    }

    private fun View.screenY(): Int {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return loc[1]
    }

    private fun View.isTouchInsideChild(x: Float, y: Float): Boolean {
        val group = this as? ViewGroup ?: return false
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return true
            }
        }
        return false
    }

    private fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener { adapter.clearSelection() }
        btnMsSelectAll.setOnClickListener {
            val allCount = adapter.getSelectableBills().size
            if (allCount > 0 && adapter.selectedBills.size >= allCount) {
                adapter.clearSelection()
            } else {
                adapter.selectAll()
            }
        }
        btnMsDelete.setOnClickListener {
            val targets = adapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                BillDeleteHelper.deleteBillsAndRevertBalance(db, targets)
                withContext(Dispatchers.Main) {
                    adapter.clearSelection()
                    Toast.makeText(
                        this@AssetDetailActivity,
                        "已删除 ${targets.size} 条账单",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        btnMsMove.setOnClickListener {
            val sourceAsset = currentAsset ?: return@setOnClickListener
            val targets = adapter.getSelectedBills()
            if (targets.isEmpty()) return@setOnClickListener
            OverlayDialogs.showGridAssetPicker(
                this,
                sourceAsset.name,
                "选择目标资产"
            ) { selectedName ->
                if (selectedName == sourceAsset.name) {
                    Toast.makeText(this, "已在当前资产中", Toast.LENGTH_SHORT).show()
                    return@showGridAssetPicker
                }
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        val targetAsset = db.assetDao().getAssetByName(selectedName) ?: return@withContext 0
                        var moved = 0
                        targets.forEach { bill ->
                            val movedBill = moveBillToTargetAsset(bill, sourceAsset, targetAsset)
                            if (movedBill != null) {
                                db.billDao().updateBill(movedBill)
                                moved++
                            }
                        }
                        moved
                    }
                    adapter.clearSelection()
                    if (result > 0) {
                        applyBillSearch()
                    }
                    Toast.makeText(this@AssetDetailActivity, "已移动 $result 条账单", Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateDetailMultiSelectUi(0)
    }

    private fun updateDetailMultiSelectUi(selectedCount: Int) {
        val active = selectedCount > 0 && adapter.isMultiSelectMode
        layoutMultiSelectActions.visibility = if (active) View.VISIBLE else View.GONE
        btnMsCancel.text = "退出多选"
        btnMsDelete.text = if (selectedCount > 0) "删除($selectedCount)" else "删除"
        if (active) {
            hideAssetDetailFab()
        } else {
            showAssetDetailFab()
        }
    }

    private fun openBillEditor(bill: Bill) {
        val intent = Intent(this, EditBillActivity::class.java)
        intent.putExtra("BILL_ID", bill.id)
        startActivity(intent)
    }

    private fun moveBillToTargetAsset(
        bill: Bill,
        sourceAsset: Asset,
        targetAsset: Asset
    ): Bill? {
        val matchSourceAccount = bill.accountId == sourceAsset.id ||
            (bill.accountId == null && bill.accountName == sourceAsset.name)
        val matchSourceToAccount = bill.toAccountId == sourceAsset.id ||
            (bill.toAccountId == null && bill.toAccountName == sourceAsset.name)

        val updated = if (bill.type == Bill.TYPE_TRANSFER) {
            when {
                matchSourceAccount && matchSourceToAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name,
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        } else {
            when {
                matchSourceAccount -> bill.copy(
                    accountId = targetAsset.id,
                    accountName = targetAsset.name
                )
                matchSourceToAccount -> bill.copy(
                    toAccountId = targetAsset.id,
                    toAccountName = targetAsset.name
                )
                else -> null
            }
        }
        return if (updated != null && updated != bill) updated else null
    }

    private fun observeData() {
        detailCacheByAssetId[assetId]?.let { cached ->
            currentAsset = cached.asset ?: currentAsset
            if (cached.asset != null) {
                updateAssetUI(cached.asset)
            }
            allAssetBills = cached.bills
            applyBillSearch()
        }

        lifecycleScope.launch {
            db.assetDao().observeAssetById(assetId).filterNotNull().collectLatest { asset ->
                currentAsset = asset
                updateAssetUI(asset)
                detailCacheByAssetId[assetId] = AssetDetailCache(
                    asset = asset,
                    bills = allAssetBills,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.billDao().backfillAssetLinksByName()
            }
            val assetName = currentAsset?.name.orEmpty()
            db.billDao().getBillsByAssetIdOrName(assetId, assetName).collectLatest { bills ->
                allAssetBills = bills
                applyBillSearch()
                detailCacheByAssetId[assetId] = AssetDetailCache(
                    asset = currentAsset,
                    bills = bills,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun toggleSearchPanel() {
        val showing = layoutSearchBar.visibility == View.VISIBLE
        if (!showing) {
            layoutSearchBar.visibility = View.VISIBLE
            updateSearchButtonState(true)
            etBillSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etBillSearch, InputMethodManager.SHOW_IMPLICIT)
            return
        }

        layoutSearchBar.visibility = View.GONE
        updateSearchButtonState(false)
        etBillSearch.setText("")
        searchQuery = ""
        applyBillSearch()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etBillSearch.windowToken, 0)
    }

    private fun updateSearchButtonState(active: Boolean) {
        tvBtnSearch.setTextColor(Color.parseColor(if (active) "#4080FF" else "#333333"))
    }

    private fun applyBillSearch() {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) {
            adapter.submitList(allAssetBills)
            return
        }
        adapter.submitList(allAssetBills.filter { billMatchesQuery(it, keyword) })
    }

    private fun billMatchesQuery(bill: Bill, keyword: String): Boolean {
        val query = keyword.lowercase(Locale.ROOT)
        val amountText = String.format(Locale.getDefault(), "%.2f", bill.amount).lowercase(Locale.ROOT)
        val category = bill.categoryName.lowercase(Locale.ROOT)
        val remark = bill.remark.lowercase(Locale.ROOT)
        val account = bill.accountName.lowercase(Locale.ROOT)
        val toAccount = bill.toAccountName.lowercase(Locale.ROOT)
        val bookName = bill.bookName.lowercase(Locale.ROOT)
        val currency = bill.currency.lowercase(Locale.ROOT)
        return amountText.contains(query) ||
            category.contains(query) ||
            remark.contains(query) ||
            account.contains(query) ||
            toAccount.contains(query) ||
            bookName.contains(query) ||
            currency.contains(query)
    }

    private fun updateAssetUI(asset: Asset) {
        tvToolbarAssetName.text = asset.name
        val balanceText = CurrencyUtils.formatAmount(asset.balance, asset.currency)
        val noteParts = mutableListOf<String>()
        if (asset.remark.isNotBlank()) noteParts += asset.remark.trim()
        if (!asset.includeInNetAsset) noteParts += "不计入总资产"
        val remarkText = noteParts.joinToString(" · ")
        if (::adapter.isInitialized) {
            adapter.updateBalanceHeader(balanceText, remarkText)
        }
    }

    private fun showAddBillForAsset() {
        val asset = currentAsset ?: return
        val prefill = JSONObject().apply {
            put("asset_name", asset.name)
        }
        AddBillEntrySheetLauncher.show(
            activity = this,
            prefillData = prefill,
            onShow = { hideAssetDetailFab() },
            onDismiss = { showAssetDetailFab() }
        )
    }

    private fun showAssetDetailFab() {
        fabHiddenByScroll = false
        fabScrollAccumulator = 0
        fabAddBill.show()
    }

    private fun hideAssetDetailFab() {
        fabHiddenByScroll = true
        fabScrollAccumulator = 0
        fabAddBill.hide()
    }

    private fun applyFabScrollBehavior(dy: Int) {
        if (dy == 0) return
        if (!rvTransactions.canScrollVertically(-1)) {
            showAssetDetailFab()
            return
        }

        fabScrollAccumulator += dy
        if (!fabHiddenByScroll && fabScrollAccumulator > 20) {
            hideAssetDetailFab()
            return
        }
        if (fabHiddenByScroll && fabScrollAccumulator < -8) {
            showAssetDetailFab()
        }
    }

    private fun showDeleteConfirmDialog() {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_FlipAccounting)
        val dialog = AlertDialog.Builder(themeContext)
            .setTitle("\u5220\u9664\u8D26\u6237")
            .setMessage("\u786E\u5B9A\u5220\u9664\u8BE5\u8D26\u6237\u5417\uFF1F\u76F8\u5173\u7684\u8D26\u5355\u5C06\u5931\u53BB\u8D26\u6237\u5173\u8054\u3002")
            .setPositiveButton("\u5220\u9664") { _, _ ->
                lifecycleScope.launch {
                    currentAsset?.let { assetRepository.deleteAssetWithCleanup(it) }
                    finish()
                }
            }
            .setNegativeButton("\u53D6\u6D88", null)
            .create()
        OverlayDialogs.showStyledCenterDialog(
            dialog,
            this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            applyOverlayType = false,
            useSolidPanelBackground = true
        )
    }

    inner class TransactionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val PAYLOAD_MODE_CHANGE = "PAYLOAD_MODE_CHANGE"
        private val PAYLOAD_SELECTION_CHANGE = "PAYLOAD_SELECTION_CHANGE"
        private val PAYLOAD_BALANCE_CHANGE = "PAYLOAD_BALANCE_CHANGE"
        private val typeBalanceHeader = 0
        private val typeMonthHeader = 1
        private val typeBillItem = 2

        private val rows = mutableListOf<Any>()
        private val monthKeyFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        private val monthLabelFormat = SimpleDateFormat("yyyy.MM\u6708", Locale.getDefault())
        private val dateFormat = SimpleDateFormat("MM-dd", Locale.getDefault())
        private var balanceHeaderRow = BalanceHeaderRow("¥0.00", "")
        var isMultiSelectMode: Boolean = false
        val selectedBills = mutableSetOf<Bill>()
        var onBillItemClick: ((Bill) -> Unit)? = null
        var onSelectionChanged: ((Int) -> Unit)? = null

        fun updateBalanceHeader(balanceText: String, remarkText: String) {
            val next = BalanceHeaderRow(balanceText, remarkText)
            if (balanceHeaderRow == next) return
            balanceHeaderRow = next
            if (rows.firstOrNull() is BalanceHeaderRow) {
                rows[0] = next
                notifyItemChanged(0, PAYLOAD_BALANCE_CHANGE)
            }
        }

        fun submitList(newList: List<Bill>) {
            rows.clear()
            rows.add(balanceHeaderRow)
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
            val availableIds = rows.mapNotNull { (it as? BillRow)?.bill?.id }.toSet()
            selectedBills.removeAll { it.id !in availableIds }
            if (selectedBills.isEmpty()) {
                isMultiSelectMode = false
            }
            onSelectionChanged?.invoke(selectedBills.size)
            notifyDataSetChanged()
        }

        fun getSelectableBills(): List<Bill> = rows.mapNotNull { (it as? BillRow)?.bill }

        fun getSelectedBills(): List<Bill> = selectedBills.toList()

        fun clearSelection() {
            selectedBills.clear()
            isMultiSelectMode = false
            onSelectionChanged?.invoke(0)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        fun selectAll() {
            isMultiSelectMode = true
            selectedBills.clear()
            selectedBills.addAll(getSelectableBills())
            onSelectionChanged?.invoke(selectedBills.size)
            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
        }

        override fun getItemViewType(position: Int): Int {
            return when (rows[position]) {
                is BalanceHeaderRow -> typeBalanceHeader
                is MonthHeaderRow -> typeMonthHeader
                is BillRow -> typeBillItem
                else -> typeBillItem
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                typeBalanceHeader -> BalanceHeaderViewHolder(
                    inflater.inflate(R.layout.item_asset_detail_balance_header, parent, false)
                )
                typeMonthHeader -> MonthHeaderViewHolder(
                    inflater.inflate(R.layout.item_asset_month_header, parent, false)
                )
                else -> BillViewHolder(inflater.inflate(R.layout.item_home_transaction, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is BalanceHeaderRow -> (holder as BalanceHeaderViewHolder).bind(row)
                is MonthHeaderRow -> (holder as MonthHeaderViewHolder).bind(row)
                is BillRow -> (holder as BillViewHolder).bind(row.bill, position)
            }
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            if (payloads.isNotEmpty() && holder is BalanceHeaderViewHolder) {
                val row = rows.getOrNull(position) as? BalanceHeaderRow
                if (row != null && payloads.contains(PAYLOAD_BALANCE_CHANGE)) {
                    holder.bind(row)
                    return
                }
            }
            if (payloads.isNotEmpty() && holder is BillViewHolder) {
                val row = rows.getOrNull(position) as? BillRow
                if (row != null) {
                    if (payloads.contains(PAYLOAD_MODE_CHANGE)) {
                        holder.updateMode(row.bill)
                        return
                    }
                    if (payloads.contains(PAYLOAD_SELECTION_CHANGE)) {
                        holder.updateSelection(row.bill)
                        return
                    }
                }
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        override fun getItemCount(): Int = rows.size

        inner class BalanceHeaderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val tvBalance = v.findViewById<TextView>(R.id.tv_header_balance)
            private val tvRemark = v.findViewById<TextView>(R.id.tv_header_remark)

            fun bind(row: BalanceHeaderRow) {
                tvBalance.text = row.balanceText
                if (row.remarkText.isBlank()) {
                    tvRemark.visibility = View.GONE
                } else {
                    tvRemark.visibility = View.VISIBLE
                    tvRemark.text = row.remarkText
                }
            }
        }

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
            private val tvAsset = v.findViewById<TextView>(R.id.tv_bill_asset)
            private val tvTime = v.findViewById<TextView>(R.id.tv_bill_time)
            private val ivIcon = v.findViewById<ImageView>(R.id.iv_bill_category_icon)
            private val iconContainer = v.findViewById<View>(R.id.layout_icon_container)
            private val cbSelect = v.findViewById<android.widget.CheckBox>(R.id.cb_bill_select)

            fun updateMode(bill: Bill) {
                cbSelect.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun updateSelection(bill: Bill) {
                cbSelect.isChecked = selectedBills.contains(bill)
            }

            fun bind(bill: Bill, position: Int) {
                val isTransfer = bill.type == Bill.TYPE_TRANSFER
                val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
                val isRefund = bill.subType == Bill.SUBTYPE_REFUND
                val baseCategory = stripRefundPrefix(bill.categoryName)
                val displayCurrency = currentAsset?.currency ?: bill.currency
                val symbol = CurrencyManager.getSymbol(displayCurrency)
                val displayAmount = amountForAssetRow(bill, assetId)

                val hasHeaderAbove = rows.getOrNull(position - 1) is MonthHeaderRow
                val isGroupStart = !hasHeaderAbove
                val isGroupEnd = position == rows.lastIndex || rows.getOrNull(position + 1) is MonthHeaderRow
                itemView.setBackgroundResource(
                    when {
                        isGroupStart && isGroupEnd -> R.drawable.bg_bill_group_single
                        isGroupStart -> R.drawable.bg_bill_group_top
                        isGroupEnd -> R.drawable.bg_bill_group_bottom
                        else -> R.drawable.bg_bill_group_middle
                    }
                )
                iconContainer.setBackgroundResource(
                    when {
                        !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                        !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                        else -> R.drawable.bg_circle_soft
                    }
                )
                updateMode(bill)

                tvCategory.text = when {
                    isRepayment -> "\u8FD8\u6B3E"
                    isTransfer -> "\u8F6C\u8D26"
                    isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
                    else -> BillDisplayFormatter.normalizeCategoryDisplayName(bill.categoryName).ifEmpty { "\u672A\u5206\u7C7B" }
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
                        else -> Color.parseColor("#757575")
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
                tvAsset.setTextColor(if (isRefund) Color.parseColor("#A1A8AF") else Color.parseColor("#999999"))

                val refundSymbol = CurrencyManager.getSymbol(bill.currency)
                val assetText = buildString {
                    if (isTransfer) {
                        append(bill.accountName)
                        if (bill.toAccountName.isNotEmpty()) {
                            append(" -> ")
                            append(bill.toAccountName)
                        }
                    } else if (bill.accountName.isNotBlank()) {
                        append(bill.accountName)
                        if (!isRefund && refundAmount > 0.0) {
                            append("(退款")
                            append(refundSymbol)
                            append(String.format(Locale.getDefault(), "%.2f", refundAmount))
                            append(")")
                        }
                    }
                }
                if (assetText.isNotEmpty()) {
                    tvAsset.text = assetText
                    tvAsset.visibility = View.VISIBLE
                } else {
                    tvAsset.visibility = View.GONE
                }

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

                itemView.setOnClickListener {
                    if (isMultiSelectMode) {
                        if (selectedBills.contains(bill)) {
                            selectedBills.remove(bill)
                        } else {
                            selectedBills.add(bill)
                        }
                        if (selectedBills.isEmpty()) {
                            isMultiSelectMode = false
                            notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
                        } else {
                            val pos = adapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                            }
                        }
                        onSelectionChanged?.invoke(selectedBills.size)
                    } else {
                        onBillItemClick?.invoke(bill)
                    }
                }
                itemView.setOnLongClickListener {
                    if (!isMultiSelectMode) {
                        isMultiSelectMode = true
                        selectedBills.clear()
                        selectedBills.add(bill)
                        notifyItemRangeChanged(0, itemCount, PAYLOAD_MODE_CHANGE)
                    } else {
                        selectedBills.add(bill)
                        val pos = adapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            notifyItemChanged(pos, PAYLOAD_SELECTION_CHANGE)
                        }
                    }
                    onSelectionChanged?.invoke(selectedBills.size)
                    true
                }
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
        val tvAsset = row.findViewById<TextView>(R.id.tv_bill_asset)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(
            when {
                !isRefund && bill.type == Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                !isRefund && bill.type == Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                else -> R.drawable.bg_circle_soft
            }
        )

        tvCategory.text = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> BillDisplayFormatter.normalizeCategoryDisplayName(bill.categoryName).ifEmpty { "未分类" }
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
            tvAsset.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#333333"))
            tvDetail.setTextColor(Color.parseColor("#999999"))
            tvAsset.setTextColor(Color.parseColor("#999999"))
            tvTime.setTextColor(Color.parseColor("#999999"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#FF5252"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#757575"))
            }
        }

        val assetStr = buildString {
            if (isTransfer) {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            } else if (bill.accountName.isNotBlank()) {
                append(bill.accountName)
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
        }
        if (assetStr.isNotEmpty()) {
            tvAsset.text = assetStr
            tvAsset.visibility = View.VISIBLE
        } else {
            tvAsset.visibility = View.GONE
        }

        val detailStr = bill.remark

        val shortTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        if (forceGrayStyle) {
            tvDetail.text = shortTimeFormat.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            tvTime.visibility = View.GONE
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
                    bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#FF5252")
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
            val themeContext = ContextThemeWrapper(this, R.style.Theme_FlipAccounting)
            val dialog = AlertDialog.Builder(themeContext)
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
                .create()
            OverlayDialogs.showStyledCenterDialog(
                dialog,
                this@AssetDetailActivity,
                widthRatio = 0.88f,
                cancelOnTouchOutside = true,
                applyOverlayType = false,
                useSolidPanelBackground = true
            )
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
                    "退款${formatMoney(refundedAmount, bill.currency)}，实际支出${formatMoney(bill.amount, bill.currency)}"
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
