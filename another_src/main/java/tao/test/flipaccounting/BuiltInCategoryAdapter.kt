package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class BuiltInCategoryAdapter(
    private var items: List<BuiltInCategory>,
    private val onSelect: (BuiltInCategory) -> Unit
) : RecyclerView.Adapter<BuiltInCategoryAdapter.VH>() {

    // 记录当前选中的图标 URL
    private var selectedIconUrl: String? = null

    // 搜索过滤更新数据
    fun updateList(newItems: List<BuiltInCategory>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val iv: ImageView = v.findViewById(R.id.iv_built_in_icon)
        val tv: TextView = v.findViewById(R.id.tv_built_in_name)
        val container: View = v.findViewById(R.id.layout_item_container) // 确保你的item布局根节点有这个ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        // 注意：这里假设你的 layout 文件名为 item_built_in_category
        // 如果根布局没有 id，请去 xml 加上 android:id="@+id/layout_item_container"
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_built_in_category, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.tv.text = item.name

        // 加载图标并着色
        Glide.with(holder.itemView).load(item.icon).into(holder.iv)
        
        // --- 核心：选中状态高亮 ---
        if (item.icon == selectedIconUrl) {
            holder.itemView.setBackgroundColor(Color.parseColor("#E3F2FD")) // 淡蓝色背景
            holder.iv.setColorFilter(Color.parseColor("#2196F3"), PorterDuff.Mode.SRC_IN)
            holder.tv.setTextColor(Color.parseColor("#2196F3"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.iv.setColorFilter(Color.parseColor("#757575"), PorterDuff.Mode.SRC_IN)
            holder.tv.setTextColor(Color.parseColor("#666666"))
        }

        holder.itemView.setOnClickListener {
            selectedIconUrl = item.icon
            notifyDataSetChanged() // 刷新列表以更新高亮状态
            onSelect(item)
        }
    }

    override fun getItemCount() = items.size
}