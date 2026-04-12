package tao.test.flipaccounting.ui.main.assets

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.AssetActivity
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.CurrencyUtils
import tao.test.flipaccounting.ui.activity.EditBillActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AssetDataCheckActivity : AppCompatActivity() {

    private lateinit var tvSummary: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var btnRecheck: TextView
    private lateinit var rvIssues: RecyclerView

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val ignoredPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val ignoredBillIds = mutableSetOf<Long>()
    private val issues = mutableListOf<IssueItem>()
    private val adapter: IssueAdapter by lazy {
        IssueAdapter(
            items = issues,
            onGoAsset = { issue ->
                startActivity(Intent(this, AssetActivity::class.java))
                Toast.makeText(
                    this,
                    "请在资产管理里将「${issue.assetNames.joinToString("、")}」改为计入总资产",
                    Toast.LENGTH_LONG
                ).show()
            },
            onIgnore = { issue ->
                ignoredBillIds.add(issue.billId)
                persistIgnoredBillIds()
                issues.removeAll { it.billId == issue.billId }
                rvIssues.adapter?.notifyDataSetChanged()
                updateSummaryText()
            },
            onEditBill = { issue ->
                val intent = Intent(this, EditBillActivity::class.java)
                intent.putExtra("BILL_ID", issue.billId)
                startActivity(intent)
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_data_check)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        tvSummary = findViewById(R.id.tv_summary)
        tvHint = findViewById(R.id.tv_hint)
        tvEmpty = findViewById(R.id.tv_empty)
        btnRecheck = findViewById(R.id.btn_recheck)
        rvIssues = findViewById(R.id.rv_issues)

        rvIssues.layoutManager = LinearLayoutManager(this)
        rvIssues.adapter = adapter

        btnRecheck.setOnClickListener { loadIssues() }
        loadIssues()
    }

    private fun loadIssues() {
        lifecycleScope.launch(Dispatchers.IO) {
            ignoredBillIds.clear()
            ignoredBillIds.addAll(readIgnoredBillIds())

            val assets = db.assetDao().getAllAssetsList()
            val excludedAssetsById = assets
                .filter { !it.includeInNetAsset }
                .associateBy { it.id }
            val excludedAssetsByName = assets
                .filter { !it.includeInNetAsset }
                .associateBy { it.name.trim() }

            val allBills = db.billDao().getAllBillsList()
            val issueList = allBills
                .asSequence()
                .filter { it.id > 0L && !ignoredBillIds.contains(it.id) }
                .mapNotNull { bill ->
                    toIssueItemOrNull(
                        bill = bill,
                        excludedAssetsById = excludedAssetsById,
                        excludedAssetsByName = excludedAssetsByName
                    )
                }
                .sortedByDescending { it.time }
                .toList()

            withContext(Dispatchers.Main) {
                issues.clear()
                issues.addAll(issueList)
                adapter.notifyDataSetChanged()
                updateSummaryText()
            }
        }
    }

    private fun toIssueItemOrNull(
        bill: Bill,
        excludedAssetsById: Map<Long, Asset>,
        excludedAssetsByName: Map<String, Asset>
    ): IssueItem? {
        val hitAssets = linkedSetOf<String>()
        var hitDirection = ""

        val sourceAsset = resolveExcludedAsset(
            assetId = bill.accountId,
            assetName = bill.accountName,
            excludedAssetsById = excludedAssetsById,
            excludedAssetsByName = excludedAssetsByName
        )
        if (sourceAsset != null) {
            hitAssets.add(sourceAsset.name)
            hitDirection = "支出/收入账户"
        }

        val targetAsset = resolveExcludedAsset(
            assetId = bill.toAccountId,
            assetName = bill.toAccountName,
            excludedAssetsById = excludedAssetsById,
            excludedAssetsByName = excludedAssetsByName
        )
        if (targetAsset != null) {
            hitAssets.add(targetAsset.name)
            hitDirection = if (hitDirection.isBlank()) "转入账户" else "双向账户"
        }

        if (hitAssets.isEmpty()) return null

        return IssueItem(
            billId = bill.id,
            typeText = buildTypeText(bill),
            amountText = CurrencyUtils.formatAmount(bill.amount, bill.currency),
            time = bill.time,
            timeText = DISPLAY_TIME_FORMAT.format(Date(bill.time)),
            bookName = bill.bookName,
            directionText = hitDirection,
            assetNames = hitAssets.toList(),
            reasonText = "该账单关联了“不计入总资产”的资产，资产流水会变化，但净资产统计不会同步变化。"
        )
    }

    private fun resolveExcludedAsset(
        assetId: Long?,
        assetName: String,
        excludedAssetsById: Map<Long, Asset>,
        excludedAssetsByName: Map<String, Asset>
    ): Asset? {
        assetId?.let { id ->
            excludedAssetsById[id]?.let { return it }
        }
        val key = assetName.trim()
        if (key.isBlank()) return null
        return excludedAssetsByName[key]
    }

    private fun buildTypeText(bill: Bill): String {
        return when (bill.type) {
            Bill.TYPE_EXPENSE -> "支出"
            Bill.TYPE_INCOME -> "收入"
            Bill.TYPE_TRANSFER -> "转账"
            Bill.TYPE_REPAYMENT -> "还款"
            else -> "账单"
        }
    }

    private fun updateSummaryText() {
        val count = issues.size
        tvSummary.text = if (count == 0) {
            "检查完成：未发现相关问题"
        } else {
            "检查完成：发现 $count 条需确认记录"
        }
        tvEmpty.visibility = if (count == 0) View.VISIBLE else View.GONE
        rvIssues.visibility = if (count == 0) View.GONE else View.VISIBLE
        tvHint.visibility = if (count == 0) View.GONE else View.VISIBLE
    }

    private fun persistIgnoredBillIds() {
        val raw = ignoredBillIds.sorted().joinToString(",")
        ignoredPrefs.edit().putString(KEY_IGNORED_BILL_IDS, raw).apply()
    }

    private fun readIgnoredBillIds(): Set<Long> {
        val raw = ignoredPrefs.getString(KEY_IGNORED_BILL_IDS, "").orEmpty()
        if (raw.isBlank()) return emptySet()
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    private data class IssueItem(
        val billId: Long,
        val typeText: String,
        val amountText: String,
        val time: Long,
        val timeText: String,
        val bookName: String,
        val directionText: String,
        val assetNames: List<String>,
        val reasonText: String
    )

    private class IssueAdapter(
        private val items: List<IssueItem>,
        private val onGoAsset: (IssueItem) -> Unit,
        private val onIgnore: (IssueItem) -> Unit,
        private val onEditBill: (IssueItem) -> Unit
    ) : RecyclerView.Adapter<IssueAdapter.IssueViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IssueViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_asset_data_check_issue, parent, false)
            return IssueViewHolder(view)
        }

        override fun onBindViewHolder(holder: IssueViewHolder, position: Int) {
            val item = items[position]
            holder.bind(item, onGoAsset, onIgnore, onEditBill)
        }

        override fun getItemCount(): Int = items.size

        class IssueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val tvTitle: TextView = itemView.findViewById(R.id.tv_issue_title)
            private val tvMeta: TextView = itemView.findViewById(R.id.tv_issue_meta)
            private val tvReason: TextView = itemView.findViewById(R.id.tv_issue_reason)
            private val btnGoAsset: TextView = itemView.findViewById(R.id.btn_go_asset)
            private val btnIgnore: TextView = itemView.findViewById(R.id.btn_ignore)
            private val btnEditBill: TextView = itemView.findViewById(R.id.btn_edit_bill)

            fun bind(
                item: IssueItem,
                onGoAsset: (IssueItem) -> Unit,
                onIgnore: (IssueItem) -> Unit,
                onEditBill: (IssueItem) -> Unit
            ) {
                tvTitle.text = "${item.typeText} ${item.amountText} · ${item.directionText}"
                tvMeta.text = "${item.timeText} · ${item.bookName} · 关联资产：${item.assetNames.joinToString("、")}"
                tvReason.text = item.reasonText
                btnGoAsset.setOnClickListener { onGoAsset(item) }
                btnIgnore.setOnClickListener { onIgnore(item) }
                btnEditBill.setOnClickListener { onEditBill(item) }
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "asset_data_check_prefs"
        private const val KEY_IGNORED_BILL_IDS = "ignored_bill_ids"
        private val DISPLAY_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }
}
