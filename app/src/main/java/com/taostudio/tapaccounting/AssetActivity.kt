package com.taostudio.tapaccounting

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
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.repository.AssetRepository
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
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
                val balanceCny = com.taostudio.tapaccounting.logic.CurrencyManager.convertToCny(it.balance, it.currency)
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
        val options = arrayOf(getString(R.string.edit_account), getString(R.string.delete_account_title))
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.operate_asset_title, asset.name))
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
            .setTitle(getString(R.string.delete_account_title))
            .setMessage(getString(R.string.delete_account_confirm))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    assetRepository.deleteAssetWithCleanup(asset)
                    withContext(Dispatchers.Main) {
                        Utils.toast(this@AssetActivity, getString(R.string.account_deleted_fmt, asset.name))
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
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
                    tvRemark.text = getString(R.string.exclude_total_asset)
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

