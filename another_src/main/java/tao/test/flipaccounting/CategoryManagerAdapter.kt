package tao.test.flipaccounting

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop

class CategoryManagerAdapter(
    private val categories: List<CategoryNode>,
    private val onClick: (CategoryNode?) -> Unit // 传入 null 代表点击了添加按钮
) : RecyclerView.Adapter<CategoryManagerAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.iv_category_icon)
        val name: TextView = view.findViewById(R.id.tv_category_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        // 复用我们之前设计的 item_category_grid 布局
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category_grid, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        // 逻辑：如果位置是列表最后一位，显示“添加”按钮
        if (position == categories.size) {
            holder.name.text = "添加"
            // 使用系统自带的加号图标
            holder.icon.setImageResource(android.R.drawable.ic_menu_add)
            holder.icon.setPadding(20, 20, 20, 20) // 让加号小一点
            holder.itemView.setOnClickListener { onClick(null) }
        } else {
            val item = categories[position]
            holder.name.text = item.name
            holder.icon.setPadding(0, 0, 0, 0)

            // 使用 Glide 加载 URL 图标，并切成圆形
            Glide.with(holder.itemView.context)
                .load(item.icon)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .transform(CircleCrop())
                .into(holder.icon)

            holder.itemView.setOnClickListener { onClick(item) }
        }
    }

    // 关键点：返回 数量 + 1（为了放那个添加按钮）
    override fun getItemCount() = categories.size + 1
}