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
import tao.test.flipaccounting.Prefs
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
    private val billDetailSheetController by lazy(LazyThreadSafetyMode.NONE) {
        AssetBillDetailSheetController(
            activity = this,
            db = db,
            scope = lifecycleScope,
            getCurrentAssetCurrency = { currentAsset?.currency },
            getDefaultAssetId = { assetId },
            amountForAssetRow = ::amountForAssetRow,
            detailOwnerAssetId = ::detailOwnerAssetId,
            refundedAmountInBillCurrency = ::refundedAmountInBillCurrency,
            baseOriginalAmount = ::baseOriginalAmount,
            buildAssetDetailFormula = ::buildAssetDetailFormula
        )
    }

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
            onBillItemClick = { bill -> billDetailSheetController.showBillDetailSheet(bill, detailOwnerAssetId(bill)) }
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
        if (asset.assetCategory == Asset.CATEGORY_INVESTMENT && asset.annualInterestRate != 0.0) {
            noteParts += "年利率 ${formatCompactDecimal(asset.annualInterestRate)}%"
        }
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
        OverlayDialogs.showPageCenterDialog(
            dialog,
            this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
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
        private val currentYearMonthLabelFormat = SimpleDateFormat("MM\u6708", Locale.getDefault())
        private val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())
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

                    val monthLabel = formatMonthHeaderLabel(Date(monthBills.first().time))
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

        private fun formatMonthHeaderLabel(monthDate: Date): String {
            return if (yearFormat.format(monthDate) == yearFormat.format(Date())) {
                currentYearMonthLabelFormat.format(monthDate)
            } else {
                monthLabelFormat.format(monthDate)
            }
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
                val showCategoryIcon = Prefs.isShowBillCategoryIcon(itemView.context)
                val showFullCategory = Prefs.isShowBillFullCategory(itemView.context)
                val remarkPriority = Prefs.isBillRemarkPriority(itemView.context)
                val baseCategory = stripRefundPrefix(bill.categoryName)
                val displayCurrency = currentAsset?.currency ?: bill.currency
                val symbol = CurrencyManager.getSymbol(displayCurrency)
                val displayAmount = amountForAssetRow(bill, assetId)

                val hasMonthHeaderAbove = rows.getOrNull(position - 1) is MonthHeaderRow
                val prevIsBill = rows.getOrNull(position - 1) is BillRow
                val nextIsBill = rows.getOrNull(position + 1) is BillRow
                val isGroupStart = !prevIsBill
                val isGroupEnd = !nextIsBill
                itemView.setBackgroundResource(
                    when {
                        isGroupStart && isGroupEnd ->
                            if (hasMonthHeaderAbove) R.drawable.bg_bill_group_bottom else R.drawable.bg_bill_group_single
                        isGroupStart ->
                            if (hasMonthHeaderAbove) R.drawable.bg_bill_group_middle else R.drawable.bg_bill_group_top
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

                val categoryText = when {
                    isRepayment -> "\u8FD8\u6B3E"
                    isTransfer -> "\u8F6C\u8D26"
                    else -> BillDisplayFormatter.formatCategoryByPreference(bill.categoryName, showFullCategory).ifEmpty { "\u672A\u5206\u7C7B" }
                }
                val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
                    categoryText = categoryText,
                    remarkText = bill.remark,
                    suffixText = dateFormat.format(Date(bill.time)),
                    remarkPriority = remarkPriority
                )
                tvCategory.text = primaryText
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
                tvDetail.text = secondaryText.ifBlank { dateFormat.format(Date(bill.time)) }
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

                if (!showCategoryIcon) {
                    iconContainer.setBackgroundColor(Color.TRANSPARENT)
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (itemView.resources.displayMetrics.density * 10).toInt()
                        val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        width = widthPx
                        height = heightPx
                    }
                    ivIcon.clearColorFilter()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 6).toInt()
                        width = px
                        height = px
                    }
                    ivIcon.setImageResource(
                        when (bill.type) {
                            Bill.TYPE_EXPENSE -> R.drawable.bg_bill_dot_expense
                            Bill.TYPE_INCOME -> R.drawable.bg_bill_dot_income
                            else -> R.drawable.bg_bill_dot_neutral
                        }
                    )
                } else {
                    iconContainer.layoutParams = iconContainer.layoutParams.apply {
                        val widthPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        val heightPx = (itemView.resources.displayMetrics.density * 44).toInt()
                        width = widthPx
                        height = heightPx
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
                    ivIcon.layoutParams = ivIcon.layoutParams.apply {
                        val px = (ivIcon.resources.displayMetrics.density * 21).toInt()
                        width = px
                        height = px
                    }

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

    private fun formatMoney(amount: Double, currency: String): String =
        BillDisplayFormatter.formatMoney(amount, currency)

    private fun formatCompactDecimal(value: Double): String {
        return String.format(Locale.getDefault(), "%.4f", value)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun amountInAssetCurrency(bill: Bill, ownerAssetId: Long, isInflow: Boolean): Double {
        val assetCurrency = currentAsset?.currency ?: bill.currency
        return when {
            bill.type == Bill.TYPE_EXPENSE && bill.accountId == ownerAssetId -> {
                val baseExpenseAmount = baseOriginalAmount(bill)
                BillAssetImpactService.convertAmountBetweenCurrencies(baseExpenseAmount, bill.currency, assetCurrency)
            }

            bill.type == Bill.TYPE_TRANSFER && isInflow && bill.toAccountId == ownerAssetId -> {
                bill.amount * bill.exchangeRate
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

    private fun buildCrossCurrencyAmountFormula(bill: Bill, accountCurrency: String): String? =
        BillDisplayFormatter.buildCrossCurrencyAmountFormula(bill, accountCurrency)

    private fun buildCrossCurrencyDetailFormula(bill: Bill, targetCurrency: String = "CNY"): String? =
        BillDisplayFormatter.buildCrossCurrencyDetailFormula(bill, targetCurrency)
}


