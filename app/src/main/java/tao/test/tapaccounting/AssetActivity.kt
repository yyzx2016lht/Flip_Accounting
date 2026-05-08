package tao.test.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.data.local.entity.Asset
import tao.test.tapaccounting.data.repository.AssetRepository
import tao.test.tapaccounting.ui.dialog.OverlayDialogs
import java.util.Locale

class AssetActivity : AppCompatActivity() {

    private lateinit var tvNetAsset: TextView
    private lateinit var tvTotalAsset: TextView
    private lateinit var tvTotalDebt: TextView
    private lateinit var rvAssets: RecyclerView
    private lateinit var adapter: AssetListAdapter

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val assetRepository by lazy {
        AssetRepository(db.assetDao(), db.billDao(), db)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_manager)

        initViews()
        observeData()
    }

    private fun initViews() {
        tvNetAsset = findViewById(R.id.tv_net_asset)
        tvTotalAsset = findViewById(R.id.tv_total_asset)
        tvTotalDebt = findViewById(R.id.tv_total_debt)
        rvAssets = findViewById(R.id.rv_assets)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<View>(R.id.fab_add_asset).setOnClickListener {
            startActivity(Intent(this, AddAssetActivity::class.java))
        }

        rvAssets.layoutManager = LinearLayoutManager(this)
        adapter = AssetListAdapter(
            onClick = { _ ->
                // val intent = Intent(this, AssetDetailActivity::class.java)
                // intent.putExtra("ASSET_ID", asset.id)
                // startActivity(intent)
            },
            onLongClick = { asset ->
                showAssetActionMenu(asset)
            }
        )
        rvAssets.adapter = adapter
    }

    private fun observeData() {
        lifecycleScope.launch {
            db.assetDao().getAllAssets().collectLatest { assets ->
                updateHeader(assets)
                adapter.submitList(assets)
            }
        }
    }

    private fun updateHeader(assets: List<Asset>) {
        var netAsset = 0.0
        var totalAsset = 0.0
        var totalDebt = 0.0

        assets.forEach {
            if (it.includeInNetAsset) {
                val balanceCny = tao.test.tapaccounting.logic.CurrencyManager.convertToCny(it.balance, it.currency)
                netAsset += balanceCny
                if (balanceCny >= 0) totalAsset += balanceCny
                else totalDebt += kotlin.math.abs(balanceCny)
            }
        }

        tvNetAsset.text = String.format(Locale.getDefault(), "¥%.2f", netAsset)
        tvTotalAsset.text = String.format(Locale.getDefault(), "¥%.2f", totalAsset)
        tvTotalDebt.text = if (totalDebt == 0.0) "¥0.00" else String.format(Locale.getDefault(), "¥%.2f", totalDebt)
    }

    private fun showAssetActionMenu(asset: Asset) {
        val options = arrayOf("编辑账户", "删除账户")
        val dialog = AlertDialog.Builder(this)
            .setTitle("操作“${asset.name}”")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val intent = Intent(this, AddAssetActivity::class.java)
                        intent.putExtra("ASSET_ID", asset.id)
                        startActivity(intent)
                    }
                    1 -> showDeleteAssetConfirm(asset)
                }
            }
            .create()
        OverlayDialogs.showPageCenterDialog(dialog, this)
    }

    private fun showDeleteAssetConfirm(asset: Asset) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("删除账户")
            .setMessage(
                "确定删除账户吗？相关账单将失去账户关联。\n" +
                    "1. 会删除该账户本身；\n" +
                    "2. 与该账户相关的转账、收款、还款会解除账户关联；\n" +
                    "3. 不会删除该账户下历史收支账单，仅保留账户名快照。"
            )
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    assetRepository.deleteAssetWithCleanup(asset)
                    withContext(Dispatchers.Main) {
                        Utils.toast(this@AssetActivity, "账户“${asset.name}”已删除")
                    }
                }
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog, this)
    }

    inner class AssetListAdapter(
        private val onClick: (Asset) -> Unit,
        private val onLongClick: (Asset) -> Unit
    ) : RecyclerView.Adapter<AssetListAdapter.VH>() {
        private var items = listOf<Asset>()

        fun submitList(newList: List<Asset>) {
            items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_list, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val asset = items[position]
            holder.bind(asset)
        }

        override fun getItemCount() = items.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val container = v.findViewById<LinearLayout>(R.id.container_assets)

            fun bind(asset: Asset) {
                container.removeAllViews()
                val row = LayoutInflater.from(itemView.context).inflate(R.layout.item_asset_row, container, false)

                row.findViewById<TextView>(R.id.tv_asset_name).text = asset.name
                row.findViewById<TextView>(R.id.tv_asset_balance).text =
                    String.format(Locale.getDefault(), "¥%.2f", asset.balance)

                val tvRemark = row.findViewById<TextView>(R.id.tv_asset_remark)
                if (!asset.includeInNetAsset) {
                    tvRemark.visibility = View.VISIBLE
                    tvRemark.text = "不计入总资产"
                } else {
                    tvRemark.visibility = View.GONE
                }

                val ivIcon = row.findViewById<ImageView>(R.id.iv_asset_icon)
                Glide.with(itemView)
                    .load(AssetIconDefaults.withDefault(asset.icon))
                    .transform(CircleCrop())
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_placeholder)
                    .error(R.drawable.ic_placeholder)
                    .into(ivIcon)

                row.setOnClickListener { onClick(asset) }
                row.setOnLongClickListener {
                    onLongClick(asset)
                    true
                }
                container.addView(row)
            }
        }
    }
}
