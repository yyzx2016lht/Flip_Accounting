package tao.test.flipaccounting

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop

class AssetAdapter(
    private var list: MutableList<Asset>,
    private val onLongClick: (Asset) -> Unit
) : RecyclerView.Adapter<AssetAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val ivIcon: ImageView = v.findViewById(R.id.iv_asset_icon)
        val tvName: TextView = v.findViewById(R.id.tv_asset_name)
        val tvType: TextView = v.findViewById(R.id.tv_asset_type)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_grid, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        holder.tvName.text = item.name
        holder.tvType.text = "${item.type} · ${item.currency}"
        // 加载图标，如果有 URL 就加载，没有就用默认占位
        if (item.icon.isNotEmpty()) {
            Glide.with(holder.itemView)
                .load(item.icon)
                .transform(CircleCrop()) // 圆形图标更好看
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(holder.ivIcon)
        } else {
            holder.ivIcon.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.itemView.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }
    override fun getItemCount() = list.size
    fun updateData(newList: List<Asset>) {
        list.clear()
        list.addAll(newList)
        notifyDataSetChanged()
    }
}