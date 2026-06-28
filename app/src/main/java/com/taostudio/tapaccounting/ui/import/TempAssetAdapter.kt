package com.taostudio.tapaccounting.ui.import

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Asset

/**
 * CSV 导入临时资产适配器。
 */
class TempAssetAdapter(
    private val onMerge: (Asset) -> Unit,
    private val onRename: (Asset) -> Unit
) : RecyclerView.Adapter<TempAssetAdapter.ViewHolder>() {

    private val items = mutableListOf<Asset>()

    fun submitList(newItems: List<Asset>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_temp_asset, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_temp_asset_name)
        private val tvBalance: TextView = itemView.findViewById(R.id.tv_temp_asset_balance)
        private val btnMerge: View = itemView.findViewById(R.id.btn_merge_asset)
        private val btnRename: View = itemView.findViewById(R.id.btn_rename_asset)

        fun bind(asset: Asset) {
            tvName.text = asset.name
            tvBalance.text = "¥${String.format("%.2f", asset.balance)}"

            btnMerge.setOnClickListener { onMerge(asset) }
            btnRename.setOnClickListener { onRename(asset) }
        }
    }
}
