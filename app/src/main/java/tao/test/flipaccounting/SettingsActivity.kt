package tao.test.flipaccounting

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Category
import tao.test.flipaccounting.data.repository.CategoryRepository
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var scrollNormalContent: View
    private lateinit var layoutSortActions: View
    private lateinit var rvSortCategories: RecyclerView
    private var expandedParentName: String? = null
    private var isSortMode = false
    private var activeSortParentId: Long? = null
    private val sortCategories = mutableListOf<Category>()
    private lateinit var sortAdapter: RecyclerView.Adapter<*>

    // 榛樿涓烘敮鍑?(0)
    private var currentType = Prefs.TYPE_EXPENSE

    private val categoryRepository by lazy {
        val db = AppDatabase.getDatabase(this)
        CategoryRepository(db.categoryDao(), db.billDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        container = findViewById(R.id.container_main)
        scrollNormalContent = findViewById(R.id.scroll_normal_content)
        layoutSortActions = findViewById(R.id.layout_sort_actions)
        rvSortCategories = findViewById(R.id.rv_sort_categories)
        findViewById<View>(R.id.btn_back).setOnClickListener {
            if (isSortMode) {
                exitSortMode(resetData = true)
            } else {
                finish()
            }
        }

    // 右上角添加按钮
        findViewById<View>(R.id.btn_add_category).setOnClickListener {
            val intent = Intent(this@SettingsActivity, AddCategoryActivity::class.java)
            intent.putExtra("type", currentType)
            startActivity(intent)
        }

    // 处理顶部 Tab 切换
        val rgType = findViewById<RadioGroup>(R.id.rg_type)
        rgType.setOnCheckedChangeListener { _, checkedId ->
            currentType = if (checkedId == R.id.rb_income) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
            // 切换 Tab 时，收起展开项并刷新
            expandedParentName = null
            if (isSortMode) exitSortMode(resetData = true)
            renderUI()
        }

        setupSortRecycler()
    }

    override fun onResume() {
        super.onResume()
        if (isSortMode) enterSortMode(activeSortParentId) else renderUI()
    }

    private fun renderUI() {
        if (isSortMode) return
        lifecycleScope.launch {
            val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
            val allCats = withContext(Dispatchers.IO) {
                categoryRepository.getCategoryTree(dbType)
            }
            renderCategoryList(allCats)
        }
    }

    private fun setupSortRecycler() {
        rvSortCategories.layoutManager = GridLayoutManager(this, 4)
        sortAdapter = object : RecyclerView.Adapter<SortCategoryVH>() {
            init { setHasStableIds(true) }

            override fun getItemId(position: Int): Long = sortCategories[position].id

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): SortCategoryVH {
                val view = LayoutInflater.from(this@SettingsActivity)
                    .inflate(R.layout.item_category_grid, parent, false)
                return SortCategoryVH(view)
            }

            override fun getItemCount(): Int = sortCategories.size

            override fun onBindViewHolder(holder: SortCategoryVH, position: Int) {
                val item = sortCategories[position]
                holder.name.text = if (item.parentId != null) "· ${item.name}" else item.name
                holder.name.textSize = if (item.parentId != null) 10.5f else 11f
                holder.name.setTextColor(if (item.parentId != null) Color.parseColor("#666666") else Color.parseColor("#333333"))
                holder.iconContainer.scaleX = 0.88f
                holder.iconContainer.scaleY = 0.88f
                holder.itemView.alpha = 0.96f
                loadSafeCategoryIcon(item.iconId, holder.icon)
                holder.icon.setColorFilter(Color.parseColor("#4F5D75"))
            }
        }
        rvSortCategories.adapter = sortAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION || from == to) return false
                val moved = sortCategories.removeAt(from)
                sortCategories.add(to, moved)
                sortAdapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(0.94f)?.scaleY(0.94f)?.alpha(0.72f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(0.96f).setDuration(120).start()
            }
        }).attachToRecyclerView(rvSortCategories)

        findViewById<View>(R.id.btn_cancel_sort).setOnClickListener {
            exitSortMode(resetData = true)
        }
        findViewById<View>(R.id.btn_save_sort).setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                categoryRepository.saveOrderedCategories(sortCategories.toList())
                withContext(Dispatchers.Main) {
                    exitSortMode(resetData = false)
                    renderUI()
                }
            }
        }
    }

    private fun loadSafeCategoryIcon(icon: String, imageView: ImageView) {
        if (icon.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        val file = File(icon)
        if (file.exists()) {
            Glide.with(this)
                .load(file)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
        } else {
            Glide.with(this)
                .load(icon)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(imageView)
        }
    }

    private fun enterSortMode(sortParentId: Long?) {
        isSortMode = true
        activeSortParentId = sortParentId
        scrollNormalContent.visibility = View.GONE
        layoutSortActions.visibility = View.VISIBLE
        rvSortCategories.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_add_category).visibility = View.GONE

        lifecycleScope.launch {
            val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
            val all = withContext(Dispatchers.IO) { categoryRepository.getCategoriesListByType(dbType) }
            sortCategories.clear()
            if (sortParentId == null) {
                sortCategories += all.filter { it.parentId == null }
                findViewById<TextView>(R.id.tv_sort_tip)?.text = "拖动一级分类进行排序"
            } else {
                sortCategories += all.filter { it.parentId == sortParentId }
                findViewById<TextView>(R.id.tv_sort_tip)?.text = "拖动子分类进行排序"
            }
            sortAdapter.notifyDataSetChanged()
        }
    }

    private fun exitSortMode(resetData: Boolean) {
        isSortMode = false
        activeSortParentId = null
        layoutSortActions.visibility = View.GONE
        rvSortCategories.visibility = View.GONE
        scrollNormalContent.visibility = View.VISIBLE
        findViewById<View>(R.id.btn_add_category).visibility = View.VISIBLE
        if (resetData) sortCategories.clear()
    }

    private class SortCategoryVH(v: View) : RecyclerView.ViewHolder(v) {
        val iconContainer: View = v.findViewById(R.id.layout_category_icon_container)
        val icon: ImageView = v.findViewById(R.id.iv_category_icon)
        val name: TextView = v.findViewById(R.id.tv_category_name)
    }

    private fun renderCategoryList(allCats: List<CategoryNode>) {
        container.removeAllViews()
        val spanCount = 4

        allCats.chunked(spanCount).forEach { row ->
            val rowLayout = createRowLayout(spanCount)

            row.forEach { cat ->
                val isSelected = (expandedParentName == cat.name)
                val itemView = createView(cat, isSub = false, isSelected = isSelected)

                itemView.setOnClickListener {
                    expandedParentName = if (expandedParentName == cat.name) null else cat.name
                    renderCategoryList(allCats)
                }
                // 长按一级分类（parent = null）
                itemView.setOnLongClickListener {
                    showActionMenu(cat, null, allCats)
                    true
                }
                rowLayout.addView(itemView)
            }
            fillPlaceholder(rowLayout, spanCount - row.size)
            container.addView(rowLayout)

            val matchedParent = row.find { it.name == expandedParentName }
            if (matchedParent != null) {
                val anchorIndex = row.indexOfFirst { it.name == matchedParent.name }.coerceAtLeast(0)
                container.addView(createSubPanel(matchedParent, anchorIndex, allCats))
            }
        }
    }

    private fun createSubPanel(parent: CategoryNode, anchorIndexInRow: Int, allCats: List<CategoryNode>): View {
        val wrapper = FrameLayout(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_rule_page_section)
            setPadding(18, 18, 18, 18)
        }
        val density = resources.displayMetrics.density
        val spanCount = 4
        val dialogContentWidth = resources.displayMetrics.widthPixels - (32 * density).toInt()
        val cellWidth = dialogContentWidth / spanCount.toFloat()
        val pointerWidth = (20 * density).toInt()
        val pointerHeight = (10 * density).toInt()
        val panelTopMargin = pointerHeight - (1 * density).toInt()
        val pointerLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (pointerWidth / 2f)).toInt().coerceAtLeast(0)
        val borderCutWidth = (22 * density).toInt()
        val borderCutHeight = (2 * density).toInt().coerceAtLeast(1)
        val borderCutLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (borderCutWidth / 2f)).toInt().coerceAtLeast(0)
        val allItems = parent.subs.toMutableList()
        val totalItems = allItems + listOf(null) // null 代表添加按钮

        totalItems.chunked(spanCount).forEach { rowItems ->
            val rowLayout = createRowLayout(spanCount)

            rowItems.forEach { item ->
                if (item != null) {
                    val itemView = createView(item, isSub = true)
                    // 长按二级分类（parent = parent）
                    itemView.setOnLongClickListener {
                        showActionMenu(item, parent, allCats)
                        true
                    }
                    rowLayout.addView(itemView)
                } else {
                    val addView = createAddSubButton(parent)
                    rowLayout.addView(addView)
                }
            }
            fillPlaceholder(rowLayout, spanCount - rowItems.size)
            panel.addView(rowLayout)
        }
        wrapper.addView(
            panel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            View(this).apply {
                setBackgroundColor(Color.parseColor("#F9FBFF"))
            },
            FrameLayout.LayoutParams(borderCutWidth, borderCutHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = borderCutLeft
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.bg_category_subpanel_pointer)
            },
            FrameLayout.LayoutParams(pointerWidth, pointerHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = pointerLeft
                topMargin = 0
            }
        )
        return wrapper.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(6, 2, 6, 10)
            }
        }
    }

    private fun createRowLayout(weightSum: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            this.weightSum = weightSum.toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun fillPlaceholder(layout: LinearLayout, count: Int) {
        for (i in 0 until count) {
            layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        }
    }

    private fun createView(cat: CategoryNode, isSub: Boolean, isSelected: Boolean = false): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_category_grid, null)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        view.setBackgroundResource(if (isSelected) R.drawable.bg_category_manage_item_selected else R.drawable.bg_category_manage_item)

        val nameView = view.findViewById<TextView>(R.id.tv_category_name)
        val iconContainer = view.findViewById<View>(R.id.layout_category_icon_container)
        nameView.text = cat.name
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        if (isSelected) {
            iv.setColorFilter(Color.parseColor("#2196F3"))
            nameView.setTextColor(Color.parseColor("#2196F3"))
            iconContainer.alpha = 1f
        } else {
            iv.setColorFilter(if (isSub) Color.parseColor("#667085") else Color.parseColor("#4F5D75"))
            nameView.setTextColor(Color.parseColor("#334155"))
            iconContainer.alpha = 1f
        }
        nameView.textSize = if (isSub) 12f else 13f
        if (cat.icon.isNotEmpty()) loadSafeCategoryIcon(cat.icon, iv)
        return view
    }

    private fun createAddSubButton(parent: CategoryNode): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_category_grid, null)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        view.setBackgroundResource(R.drawable.bg_category_manage_item)
        view.findViewById<TextView>(R.id.tv_category_name).text = "\u6dfb\u52a0\u5b50\u7c7b"
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        iv.setImageResource(android.R.drawable.ic_menu_add)
        iv.setColorFilter(Color.parseColor("#2C74FF"), PorterDuff.Mode.SRC_IN)
        view.findViewById<TextView>(R.id.tv_category_name).apply {
            setTextColor(Color.parseColor("#2C74FF"))
            textSize = 13f
        }

        view.setOnClickListener {
            val intent = Intent(this, AddCategoryActivity::class.java)
            intent.putExtra("type", currentType)
            intent.putExtra("parentName", parent.name)
            startActivity(intent)
        }
        return view
    }

    // --- 核心新功能：显示操作菜单 ---
    private fun showActionMenu(target: CategoryNode, parent: CategoryNode?, allCats: List<CategoryNode>) {
        val isSubCategory = parent != null
        val options = buildList {
            add("修改分类")
            add("删除分类")
            add("排序分类")
            if (isSubCategory) {
                add("升级为一级分类")
            } else if (target.subs.isEmpty()) {
                add("调整为子分类")
            }
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("操作：${target.name}")
            .setItems(options) { _, which ->
                when (options[which]) {
                    "修改分类" -> {
                        val intent = Intent(this, AddCategoryActivity::class.java)
                        intent.putExtra("type", currentType)
                        intent.putExtra("parentName", parent?.name)
                        intent.putExtra("isEdit", true)
                        intent.putExtra("oldName", target.name)
                        intent.putExtra("oldIcon", target.icon)
                        intent.putExtra("editId", target.id)
                        startActivity(intent)
                    }
                    "删除分类" -> handleDeleteCategory(target, parent)
                    "排序分类" -> {
                        enterSortMode(if (isSubCategory) parent?.id else null)
                    }
                    "升级为一级分类" -> showPromoteConfirm(target)
                    "调整为子分类" -> showDemoteDialog(target, allCats)
                }
            }
            .show()
    }

    /** 处理删除分类逻辑 */
    private fun handleDeleteCategory(target: CategoryNode, parent: CategoryNode?) {
    // 1. 一级分类有子分类时，禁止删除
        if (parent == null && target.subs.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("无法删除")
                .setMessage("“${target.name}”下还有 ${target.subs.size} 个子分类，请先处理完子分类后再删除。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

    // 2. 查询该分类下的账单数量，再弹出处理方式选择
        lifecycleScope.launch {
            val billCount = withContext(Dispatchers.IO) {
                categoryRepository.countBillsUnderCategory(target.id)
            }
            showDeleteWithBillHandlingDialog(target, billCount)
        }
    }

    /** 弹出“删除分类并处理账单”的对话框 */
    private fun showDeleteWithBillHandlingDialog(target: CategoryNode, billCount: Int) {
        val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0

    // 先选择如何处理所属账单
        var selectedHandling: String? = null // "migrate" or "delete"
        var targetCategoryId: Long? = null

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

    // 显示账单数量提示
        val tvHint = TextView(this).apply {
            text = "- 这个分类下已经有 $billCount 条账单了，如果要删除这个分类，请先选择这些账单如何处理。"
            setTextColor(Color.parseColor("#B71C1C"))
            textSize = 13f
        }
        dialogView.addView(tvHint)

        val dialog = AlertDialog.Builder(this)
            .setTitle("所属账单如何处理？")
            .setView(dialogView)
            .setPositiveButton("确定删除", null) // 先不设置，稍后覆盖点击事件
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

    // 覆盖确定按钮，避免自动关闭
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (billCount > 0 && selectedHandling == null) {
                Utils.toast(this, "请先选择账单处理方式")
                return@setOnClickListener
            }
            dialog.dismiss()
            lifecycleScope.launch(Dispatchers.IO) {
                when (selectedHandling) {
                    "migrate" -> categoryRepository.deleteCategoryAndMigrateBills(target.id, targetCategoryId)
                    "delete" -> categoryRepository.deleteCategoryAndBills(target.id, AppDatabase.getDatabase(this@SettingsActivity))
                    else -> categoryRepository.deleteCategoryAndMigrateBills(target.id, null)
                }
                withContext(Dispatchers.Main) {
                    if (expandedParentName == target.name) expandedParentName = null
                    renderUI()
                }
            }
        }

        if (billCount > 0) {
            // 显示两个选项（迁移或连同账单删除）
            val options = arrayOf("迁移到新的分类", "连同账单一起删除")
            val listView = ListView(this)
            val optAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, options)
            listView.adapter = optAdapter
            listView.setOnItemClickListener { _, _, position, _ ->
                selectedHandling = if (position == 0) "migrate" else "delete"
                if (position == 0) {
                    // 选择迁移：弹出目标分类选择
                    dialog.dismiss()
                    showSelectTargetCategoryDialog(target, dbType)
                } else {
                    // 连同账单一起删除：直接确认
                    selectedHandling = "delete"
                    showFinalDeleteConfirm(target, "连同账单一起删除") {
                        lifecycleScope.launch(Dispatchers.IO) {
                            categoryRepository.deleteCategoryAndBills(target.id, AppDatabase.getDatabase(this@SettingsActivity))
                            withContext(Dispatchers.Main) {
                                if (expandedParentName == target.name) expandedParentName = null
                                renderUI()
                            }
                        }
                    }
                    dialog.dismiss()
                }
            }
            dialogView.addView(listView)
        }
    }

    /** 选择迁移目标分类 */
    private fun showSelectTargetCategoryDialog(target: CategoryNode, dbType: Int) {
        lifecycleScope.launch {
            val allCats = withContext(Dispatchers.IO) {
                categoryRepository.getCategoriesListByType(dbType)
            }
            // 过滤掉自身及其子分类
            val candidates = allCats.filter { it.id != target.id && it.parentId != target.id }

            if (candidates.isEmpty()) {
                Utils.toast(this@SettingsActivity, "没有可迁移的目标分类，账单将清除分类关联")
                lifecycleScope.launch(Dispatchers.IO) {
                    categoryRepository.deleteCategoryAndMigrateBills(target.id, null)
                    withContext(Dispatchers.Main) {
                        if (expandedParentName == target.name) expandedParentName = null
                        renderUI()
                    }
                }
                return@launch
            }

            // 需要隐藏的 id 集合：目标分类自身 + 其所有子分类
            val excludeIds = allCats
                .filter { it.id == target.id || it.parentId == target.id }
                .map { it.id }
                .toSet()

            tao.test.flipaccounting.ui.dialog.OverlayDialogs.showMigrationTargetPicker(
                ctx = this@SettingsActivity,
                excludeIds = excludeIds,
                title = "选择迁移目标分类",
                dbType = dbType
            ) { targetCat ->
                showFinalDeleteConfirm(target, "迁移账单到“${targetCat.name}”") {
                    lifecycleScope.launch(Dispatchers.IO) {
                        categoryRepository.deleteCategoryAndMigrateBills(target.id, targetCat.id)
                        withContext(Dispatchers.Main) {
                            if (expandedParentName == target.name) expandedParentName = null
                            renderUI()
                        }
                    }
                }
            }
        }
    }

    /** 最终确认删除 */
    private fun showFinalDeleteConfirm(target: CategoryNode, handling: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("确定删除“${target.name}”？")
            .setMessage("处理方式：$handling\n删除后不可恢复。")
            .setPositiveButton("确定删除") { _, _ -> onConfirm() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 确认将二级分类提升为一级 */
    private fun showPromoteConfirm(target: CategoryNode) {
        AlertDialog.Builder(this)
            .setTitle("升级为一级分类")
            .setMessage("将“${target.name}”升级为独立的一级分类？")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    categoryRepository.promoteToParent(target.id)
                    withContext(Dispatchers.Main) {
                        renderUI()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 选择目标父分类，将一级分类降级为子分类 */
    private fun showDemoteDialog(target: CategoryNode, allCats: List<CategoryNode>) {
        val dbType = if (currentType == Prefs.TYPE_INCOME) 1 else 0
    // 候选父分类：其他一级分类（不能是自己）
        val candidates = allCats.filter { it.id != target.id }
        if (candidates.isEmpty()) {
            Utils.toast(this, "没有其它一级分类可作为父分类")
            return
        }
        val names = candidates.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择所属的一级分类")
            .setItems(names) { _, which ->
                val newParent = candidates[which]
                AlertDialog.Builder(this)
                    .setTitle("调整为子分类")
                    .setMessage("将“${target.name}”调整为“${newParent.name}”的子分类？")
                    .setPositiveButton("确定") { _, _ ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            categoryRepository.demoteToChild(target.id, newParent.id)
                            withContext(Dispatchers.Main) {
                                expandedParentName = newParent.name
                                renderUI()
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onBackPressed() {
        if (isSortMode) {
            exitSortMode(resetData = true)
        } else {
            super.onBackPressed()
        }
    }
}
