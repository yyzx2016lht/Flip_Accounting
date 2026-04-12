package tao.test.flipaccounting.ui.activity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import tao.test.flipaccounting.R
import java.io.File

data class BookOverviewItem(
    val bookName: String,
    val themeColor: Int,
    val bannerPath: String?,
    val expense: Double,
    val income: Double,
    val isCurrentBook: Boolean
)

class BookOverviewAdapter(
    private val onCardClick: (BookOverviewItem) -> Unit,
    private val onOrderChanged: (newOrder: List<String>) -> Unit
) : RecyclerView.Adapter<BookOverviewAdapter.ViewHolder>() {

    private val items = mutableListOf<BookOverviewItem>()

    fun submitList(newItems: List<BookOverviewItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<BookOverviewItem> = items.toList()

    /** 供 ItemTouchHelper 在拖拽结束时调用，交换相邻条目 */
    fun onItemMove(fromPos: Int, toPos: Int) {
        val item = items.removeAt(fromPos)
        items.add(toPos, item)
        notifyItemMoved(fromPos, toPos)
    }

    /** 拖拽结束，通知外部持久化新顺序 */
    fun onDragEnd() {
        onOrderChanged(items.map { it.bookName })
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book_overview_card, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val flCover: View = itemView.findViewById(R.id.flBookCover)
        private val ivCover: ImageView = itemView.findViewById(R.id.ivBookCover)
        private val vGradient: View = itemView.findViewById(R.id.vCoverGradient)
        private val tvName: TextView = itemView.findViewById(R.id.tvBookName)
        private val tvTag: TextView = itemView.findViewById(R.id.tvActiveTag)
        private val tvExpense: TextView = itemView.findViewById(R.id.tvBookExpense)
        private val tvIncome: TextView = itemView.findViewById(R.id.tvBookIncome)

        fun bind(item: BookOverviewItem) {
            tvName.text = item.bookName
            tvTag.visibility = if (item.isCurrentBook) View.VISIBLE else View.GONE

            // 封面：图片优先，否则纯色
            val bannerFile = item.bannerPath?.let { File(it) }
            if (bannerFile != null && bannerFile.exists()) {
                ivCover.visibility = View.VISIBLE
                vGradient.visibility = View.VISIBLE
                flCover.setBackgroundColor(item.themeColor)
                Glide.with(itemView.context)
                    .load(bannerFile)
                    .centerCrop()
                    // Local file + transform should cache original data only to avoid
                    // NoResultEncoderAvailableException for transformed File results.
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                    .into(ivCover)
            } else {
                ivCover.visibility = View.GONE
                vGradient.visibility = View.GONE
                flCover.setBackgroundColor(item.themeColor)
            }

            tvExpense.text = "¥${String.format("%.2f", item.expense)}"
            tvIncome.text = "¥${String.format("%.2f", item.income)}"

            itemView.setOnClickListener { onCardClick(item) }
        }
    }
}
