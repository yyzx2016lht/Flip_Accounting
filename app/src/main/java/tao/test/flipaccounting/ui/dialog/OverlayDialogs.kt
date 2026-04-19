package tao.test.flipaccounting.ui.dialog

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import android.view.WindowManager.BadTokenException
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.CategoryNode
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Category
import tao.test.flipaccounting.data.repository.CategoryRepository
import java.io.File
import java.util.*

object OverlayDialogs {
    private fun setExactVisibleRowsHeight(target: View, rowHeight: Int, rows: Int = 4) {
        if (rowHeight <= 0) return
        val lp = target.layoutParams ?: return
        val desired = rowHeight * rows
        if (lp.height != desired) {
            lp.height = desired
            target.layoutParams = lp
        }
    }

    private fun loadSafeCategoryIcon(ctx: Context, icon: String, imageView: ImageView) {
        if (icon.isBlank()) {
            imageView.setImageDrawable(null)
            return
        }
        val file = File(icon)
        if (file.exists()) {
            Glide.with(ctx)
                .load(file)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
        } else {
            Glide.with(ctx)
                .load(icon)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .into(imageView)
        }
    }

    private fun canUseOverlayWindow(ctx: Context): Boolean {
        return if (ctx is Activity) Settings.canDrawOverlays(ctx) else true
    }

    private fun applyOverlayTypeIfAllowed(dialog: AlertDialog, ctx: Context) {
        if (!canUseOverlayWindow(ctx)) return
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE
        )
    }

    fun showAnchoredMenu(ctx: Context, anchor: View, items: List<String>, onSelected: (String) -> Unit) {
        val popup = ListPopupWindow(ctx).apply {
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items))
            anchorView = anchor
            width = anchor.width
            isModal = true
            setOnItemClickListener { _, _, pos, _ ->
                onSelected(items[pos])
                dismiss()
            }
        }
        popup.show()
    }
    fun showGridCategoryPicker(ctx: Context, currentSelectionText: String, type: Int, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        val scrollCategories = view.findViewById<ScrollView>(R.id.scroll_categories)
        val rvSortCategories = view.findViewById<RecyclerView>(R.id.rv_sort_categories)
        view.findViewById<Button>(R.id.btn_confirm_category)?.visibility = View.GONE
        var currentSelection = currentSelectionText.replace(" > ", "/::/")

        val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
        val normalIconColor = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
        val normalBgColor   = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
        val selectedIconColor = Color.parseColor("#2196F3")
        val selectedBgColor   = Color.parseColor("#E3F2FD")
        val categoryRepository = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        fun applyIconStyle(itemView: View, isSelected: Boolean) {
            val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
            val iconContainer = itemView.findViewById<View>(R.id.layout_category_icon_container)
            ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
            iconContainer.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (isSelected) selectedBgColor else normalBgColor)
            }
        }

        fun render(categories: List<CategoryNode>) {
            container.removeAllViews()
            val parts = currentSelection.split("/::/")
            val parent = categories.find { it.name == parts.getOrNull(0) }

            categories.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { cat ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    val isSelected = cat.name == parent?.name
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = cat.name
                        setTextColor(if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                    }
                    applyIconStyle(itemView, isSelected)
                    loadSafeCategoryIcon(ctx, cat.icon, itemView.findViewById(R.id.iv_category_icon))
                    itemView.setOnClickListener {
                        val selectedParent = currentSelection.split("/::/").getOrNull(0)
                        when {
                            cat.subs.isEmpty() -> {
                                onConfirm(cat.name)
                                dialog.dismiss()
                            }
                            selectedParent == cat.name -> {
                                onConfirm(cat.name)
                                dialog.dismiss()
                            }
                            else -> {
                                currentSelection = cat.name
                                render(categories)
                            }
                        }
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, -2, 1f))
                }
                container.addView(rowLayout)
                rowLayout.post {
                    setExactVisibleRowsHeight(scrollCategories, rowLayout.height)
                }
                if (parent != null && row.any { it.name == parent.name } && parent.subs.isNotEmpty()) {
                    val anchorIndex = row.indexOfFirst { it.name == parent.name }.coerceAtLeast(0)
                    container.addView(createSubPanel(ctx, parent, anchorIndex, parts.getOrNull(1), dbType, {
                        onConfirm("${parent.name} > ${it.name}")
                        dialog.dismiss()
                    }))
                }
            }
        }

        // 寮傛浠庢暟鎹簱鍔犺浇鍒嗙被锛岀劧鍚庢覆鏌?
        CoroutineScope(Dispatchers.Main).launch {
            val categories = withContext(Dispatchers.IO) { categoryRepository.getCategoryTree(dbType) }
            render(categories)
        }

        dialog.window?.let {
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    private fun createSubPanel(
        ctx: Context,
        parent: CategoryNode,
        anchorIndexInRow: Int,
        selected: String?,
        dbType: Int,
        onClick: (CategoryNode) -> Unit
    ): View {
        val normalIconColor   = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
        val normalBgColor     = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
        val selectedIconColor = Color.parseColor("#2196F3")
        val selectedBgColor   = Color.parseColor("#E3F2FD")
        val wrapper = FrameLayout(ctx)
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_rule_page_section)
            setPadding(10, 10, 10, 8)
        }
        val pointer = ImageView(ctx).apply {
            setImageResource(R.drawable.bg_category_subpanel_pointer)
        }
        val density = ctx.resources.displayMetrics.density
        val dialogContentWidth = (ctx.resources.displayMetrics.widthPixels * 0.9f).toInt() - (32 * density).toInt()
        val cellWidth = dialogContentWidth / 5f
        val pointerWidth = (20 * density).toInt()
        val pointerHeight = (10 * density).toInt()
        val panelTopMargin = pointerHeight - (1 * density).toInt()
        val pointerLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (pointerWidth / 2f)).toInt()
            .coerceAtLeast(0)
        val borderCutWidth = (22 * density).toInt()
        val borderCutHeight = (2 * density).toInt().coerceAtLeast(1)
        val borderCutLeft = (((anchorIndexInRow + 0.5f) * cellWidth) - (borderCutWidth / 2f)).toInt()
            .coerceAtLeast(0)
        val grid = GridLayout(ctx).apply {
            columnCount = 5
            setPadding(0, 0, 0, 0)
            parent.subs.forEach { sub ->
                val item = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, this, false)
                val isSelected = sub.name == selected
                item.findViewById<TextView>(R.id.tv_category_name).apply {
                    text = sub.name
                    setTextColor(if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                }
                val ivIcon = item.findViewById<ImageView>(R.id.iv_category_icon)
                ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
                item.findViewById<View>(R.id.layout_category_icon_container).background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setColor(if (isSelected) selectedBgColor else normalBgColor)
                    }
                loadSafeCategoryIcon(ctx, sub.icon, ivIcon)
                item.setOnClickListener { onClick(sub) }
                addView(
                    item,
                    GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    ).apply { width = 0 }
                )
            }
            val remainder = parent.subs.size % 5
            if (remainder != 0) {
                for (i in 0 until (5 - remainder)) {
                    addView(
                        View(ctx),
                        GridLayout.LayoutParams(
                            GridLayout.spec(GridLayout.UNDEFINED, 1f),
                            GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        ).apply { width = 0 }
                    )
                }
            }
        }
        panel.addView(grid)
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
            View(ctx).apply {
                setBackgroundColor(Color.parseColor("#F9FBFF"))
            },
            FrameLayout.LayoutParams(borderCutWidth, borderCutHeight).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = borderCutLeft
                topMargin = panelTopMargin
            }
        )
        wrapper.addView(
            pointer,
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
                setMargins(0, 2, 0, 4)
            }
        }
    }

    fun showCategorySortDialog(ctx: Context, type: Int, onSaved: (() -> Unit)? = null) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val repo = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())
        val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0

        val container = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 12)
        }

        val tip = TextView(themeContext).apply {
            text = "长按分类直接拖动排序。二级分类可点“移出一级”单独拿出来，拖动时会保持和资产选择器一样的动画手感。"
            setTextColor(Color.parseColor("#8A8A8A"))
            textSize = 12f
            setPadding(8, 0, 8, 18)
        }
        container.addView(tip)

        val rv = RecyclerView(themeContext).apply {
            layoutManager = LinearLayoutManager(themeContext)
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundResource(R.drawable.bg_search_box)
            clipToPadding = false
            setPadding(0, 8, 0, 8)
        }
        container.addView(
            rv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (themeContext.resources.displayMetrics.heightPixels * 0.55f).toInt()
            )
        )

        val flatList = mutableListOf<Category>()
        val adapter = object : RecyclerView.Adapter<SortVH>() {
            init {
                setHasStableIds(true)
            }

            override fun getItemId(position: Int): Long = flatList[position].id

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SortVH {
                val row = LinearLayout(themeContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = (54 * themeContext.resources.displayMetrics.density).toInt()
                    setPadding(18, 10, 18, 10)
                }

                val name = TextView(themeContext).apply {
                    id = android.R.id.text1
                    setTextColor(Color.parseColor("#333333"))
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val promote = TextView(themeContext).apply {
                    id = android.R.id.button1
                    text = "移出一级"
                    textSize = 11f
                    setTextColor(Color.parseColor("#5E86FF"))
                    setPadding(14, 8, 14, 8)
                    setBackgroundResource(R.drawable.bg_search_box)
                    visibility = View.GONE
                }
                val drag = TextView(themeContext).apply {
                    text = "\u2261"
                    setTextColor(Color.parseColor("#B7B7B7"))
                    textSize = 19f
                    setPadding(18, 0, 0, 0)
                }
                row.addView(name)
                row.addView(promote)
                row.addView(drag)
                return SortVH(row)
            }

            override fun getItemCount(): Int = flatList.size

            override fun onBindViewHolder(holder: SortVH, position: Int) {
                val item = flatList[position]
                val isChild = item.parentId != null
                holder.title.text = if (isChild) "    ${item.name}" else item.name
                holder.title.setTypeface(null, if (isChild) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
                holder.promote?.visibility = if (isChild) View.VISIBLE else View.GONE
                holder.promote?.setOnClickListener {
                    val index = holder.adapterPosition
                    if (index == RecyclerView.NO_POSITION) return@setOnClickListener
                    flatList[index] = flatList[index].copy(parentId = null)
                    notifyItemChanged(index)
                }
            }
        }
        rv.adapter = adapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
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
                val moved = flatList.removeAt(from)
                flatList.add(to, moved)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.alpha(0.88f)?.setDuration(120)?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            }
        }).attachToRecyclerView(rv)

        val dialog = AlertDialog.Builder(themeContext)
            .setTitle("排序分类")
            .setView(container)
            .setPositiveButton("保存排序", null)
            .setNegativeButton("取消", null)
            .create()

        CoroutineScope(Dispatchers.Main).launch {
            val all = withContext(Dispatchers.IO) { repo.getCategoriesListByType(dbType) }
            val childrenByParent = all.filter { it.parentId != null }.groupBy { it.parentId }
            all.filter { it.parentId == null }.forEach { parent ->
                flatList += parent
                flatList += childrenByParent[parent.id].orEmpty()
            }
            flatList += all.filter { it.parentId != null && flatList.none { added -> added.id == it.id } }
            adapter.notifyDataSetChanged()

            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                CoroutineScope(Dispatchers.IO).launch {
                    repo.saveOrderedCategoryTree(flatList.toList())
                    withContext(Dispatchers.Main) {
                        dialog.dismiss()
                        onSaved?.invoke()
                    }
                }
            }
        }
    }

    private class SortVH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(android.R.id.text1)
        val promote: TextView? = v.findViewById(android.R.id.button1)
    }

    /**
     * 閫夋嫨杩佺Щ鐩爣鍒嗙被鐨勭綉鏍奸潰鏉裤€?
     * @param excludeIds  闇€瑕佷粠鍒楄〃涓殣钘忕殑鍒嗙被 ID锛堟瘮濡傛鍦ㄥ垹闄ょ殑鍒嗙被鍙婂叾瀛愬垎绫伙級
     * @param title       闈㈡澘椤堕儴鏍囬
     * @param dbType      0=鏀嚭 1=鏀跺叆
     * @param onConfirm   閫変腑纭鍚庡洖璋冿紝杩斿洖閫変腑鐨?CategoryNode
     */
    fun showMigrationTargetPicker(
        ctx: Context,
        excludeIds: Set<Long>,
        title: String = "选择迁移目标分类",
        dbType: Int,
        onConfirm: (CategoryNode) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

        // 璁剧疆鑷畾涔夋爣棰?
        view.findViewById<TextView>(R.id.dialog_title)?.text = title

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        val categoryRepository = CategoryRepository(AppDatabase.getDatabase(ctx).categoryDao())

        // 褰撳墠閫変腑鐨?CategoryNode锛堢埗绾э級
        var selectedParent: CategoryNode? = null
        // 褰撳墠閫変腑鐨勫瓙绾?CategoryNode锛堝彲浠ヤ负 null 琛ㄧず閫変簡鐖剁骇鏈韩锛?
        var selectedSub: CategoryNode? = null

        fun render(categories: List<CategoryNode>) {
            container.removeAllViews()
            val normalIconColor   = if (dbType == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
            val normalBgColor     = if (dbType == 1) Color.parseColor("#E8F5E9") else Color.parseColor("#FFEBEE")
            val selectedIconColor = Color.parseColor("#2196F3")
            val selectedBgColor   = Color.parseColor("#E3F2FD")

            categories.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { cat ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    val isSelected = cat.name == selectedParent?.name
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = cat.name
                        setTextColor(if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                    }
                    val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
                    ivIcon.setColorFilter(if (isSelected) selectedIconColor else normalIconColor)
                    itemView.findViewById<View>(R.id.layout_category_icon_container).background =
                        android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(if (isSelected) selectedBgColor else normalBgColor)
                        }
                    loadSafeCategoryIcon(ctx, cat.icon, ivIcon)
                    itemView.setOnClickListener {
                        if (selectedParent?.name == cat.name) {
                            selectedParent = null
                            selectedSub = null
                        } else {
                            selectedParent = cat
                            selectedSub = null
                        }
                        render(categories)
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) rowLayout.addView(View(ctx), LinearLayout.LayoutParams(0, -2, 1f))
                }
                container.addView(rowLayout)

                // 灞曞紑瀛愬垎绫婚潰鏉?
                val curParent = selectedParent
                if (curParent != null && row.any { it.name == curParent.name } && curParent.subs.isNotEmpty()) {
                    val anchorIndex = row.indexOfFirst { it.name == curParent.name }.coerceAtLeast(0)
                    container.addView(createSubPanel(ctx, curParent, anchorIndex, selectedSub?.name, dbType, { sub ->
                        selectedSub = if (selectedSub?.name == sub.name) null else sub
                        render(categories)
                    }))
                }
            }
        }

        CoroutineScope(Dispatchers.Main).launch {
            val allCategories = withContext(Dispatchers.IO) { categoryRepository.getCategoryTree(dbType) }
            // 杩囨护鎺夐渶瑕佹帓闄ょ殑鍒嗙被锛堣嚜韬強鍏跺瓙鍒嗙被锛?
            val filtered = allCategories
                .filter { it.id !in excludeIds }
                .map { parent ->
                    val filteredSubs = parent.subs.filter { it.id !in excludeIds }.toMutableList()
                    CategoryNode(parent.name, parent.icon, filteredSubs).also { it.id = parent.id }
                }
            render(filtered)
        }

        view.findViewById<Button>(R.id.btn_confirm_category).setOnClickListener {
            val chosen = selectedSub ?: selectedParent
            if (chosen == null) {
                Toast.makeText(ctx, "请先选择一个分类", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onConfirm(chosen)
            dialog.dismiss()
        }

        dialog.window?.let {
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    fun showBookPickerDialog(ctx: Context, books: List<String>, currentBook: String, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_book_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val rv = view.findViewById<RecyclerView>(R.id.rv_books)
        val currentSelection = currentBook

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val itemView = LayoutInflater.from(themeContext).inflate(R.layout.item_book_picker, parent, false)
                return object : RecyclerView.ViewHolder(itemView) {}
            }

            override fun getItemCount(): Int = books.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val book = books[position]
                val itemView = holder.itemView
                val tvName = itemView.findViewById<TextView>(R.id.tv_book_name)
                val ivCheck = itemView.findViewById<ImageView>(R.id.iv_book_selected)
                val ivIcon = itemView.findViewById<ImageView>(R.id.iv_book_icon)
                val isSelected = book == currentSelection
                tvName.text = book
                tvName.setTextColor(if (isSelected) Color.parseColor("#1A73E8") else Color.parseColor("#333333"))
                ivCheck.visibility = if (isSelected) View.VISIBLE else View.GONE
                ivIcon.alpha = if (isSelected) 1f else 0.75f
                itemView.background = androidx.core.content.ContextCompat.getDrawable(
                    themeContext,
                    if (isSelected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal
                )
                itemView.setOnClickListener {
                    onConfirm(book)
                    dialog.dismiss()
                }
            }
        }
        rv.layoutManager = LinearLayoutManager(themeContext)
        rv.adapter = adapter
        view.findViewById<View>(R.id.btn_cancel_book_picker)?.setOnClickListener { dialog.dismiss() }
        dialog.window?.let {
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    fun showCustomTimePicker(
        ctx: Context,
        initialTimeMillis: Long? = null,
        onConfirm: (String) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_custom_time_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val cal = Calendar.getInstance().apply {
            initialTimeMillis?.let { timeInMillis = it }
        }
        val npYear = view.findViewById<NumberPicker>(R.id.np_year).apply {
            minValue = 2024
            maxValue = 2030
            value = cal.get(Calendar.YEAR)
            wrapSelectorWheel = false
        }
        val npMonth = view.findViewById<NumberPicker>(R.id.np_month).apply {
            minValue = 1
            maxValue = 12
            value = cal.get(Calendar.MONTH) + 1
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npDay = view.findViewById<NumberPicker>(R.id.np_day).apply {
            minValue = 1
            maxValue = 31
            value = cal.get(Calendar.DAY_OF_MONTH)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npHour = view.findViewById<NumberPicker>(R.id.np_hour).apply {
            minValue = 0
            maxValue = 23
            value = cal.get(Calendar.HOUR_OF_DAY)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }
        val npMin = view.findViewById<NumberPicker>(R.id.np_minute).apply {
            minValue = 0
            maxValue = 59
            value = cal.get(Calendar.MINUTE)
            wrapSelectorWheel = true
            setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        }

        var suppressLinkedUpdate = false

        fun maxDayOf(year: Int, month: Int): Int {
            val temp = Calendar.getInstance()
            temp.set(Calendar.YEAR, year)
            temp.set(Calendar.MONTH, month - 1)
            return temp.getActualMaximum(Calendar.DAY_OF_MONTH)
        }

        fun applyDateToPickers(calendar: Calendar) {
            suppressLinkedUpdate = true
            npYear.value = calendar.get(Calendar.YEAR)
            npMonth.value = calendar.get(Calendar.MONTH) + 1
            val newMaxDay = maxDayOf(npYear.value, npMonth.value)
            npDay.maxValue = newMaxDay
            npDay.value = calendar.get(Calendar.DAY_OF_MONTH).coerceAtMost(newMaxDay)
            suppressLinkedUpdate = false
        }

        fun buildCurrentDate(): Calendar {
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, npYear.value)
                set(Calendar.MONTH, npMonth.value - 1)
                set(Calendar.DAY_OF_MONTH, npDay.value.coerceAtMost(maxDayOf(npYear.value, npMonth.value)))
                set(Calendar.HOUR_OF_DAY, npHour.value)
                set(Calendar.MINUTE, npMin.value)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        fun buildDateWithValues(
            year: Int = npYear.value,
            month: Int = npMonth.value,
            day: Int = npDay.value,
            hour: Int = npHour.value,
            minute: Int = npMin.value
        ): Calendar {
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month - 1)
                set(Calendar.DAY_OF_MONTH, day.coerceAtMost(maxDayOf(year, month)))
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
        }

        npDay.maxValue = maxDayOf(npYear.value, npMonth.value)

        npYear.setOnValueChangedListener { _, _, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val newMaxDay = maxDayOf(newVal, npMonth.value)
            suppressLinkedUpdate = true
            npDay.maxValue = newMaxDay
            if (npDay.value > newMaxDay) npDay.value = newMaxDay
            suppressLinkedUpdate = false
        }

        npMonth.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val current = buildDateWithValues(month = oldVal)
            when {
                oldVal == 12 && newVal == 1 -> current.add(Calendar.YEAR, 1)
                oldVal == 1 && newVal == 12 -> current.add(Calendar.YEAR, -1)
                else -> current.set(Calendar.MONTH, newVal - 1)
            }
            applyDateToPickers(current)
        }

        npDay.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            val currentMaxDay = maxDayOf(npYear.value, npMonth.value)
            val current = buildDateWithValues(day = oldVal)
            when {
                oldVal == currentMaxDay && newVal == 1 -> current.add(Calendar.DAY_OF_MONTH, 1)
                oldVal == 1 && newVal >= 28 -> current.add(Calendar.DAY_OF_MONTH, -1)
                else -> current.set(Calendar.DAY_OF_MONTH, newVal)
            }
            applyDateToPickers(current)
        }

        npHour.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            when {
                oldVal == 23 && newVal == 0 -> {
                    val current = buildDateWithValues(hour = oldVal)
                    current.add(Calendar.HOUR_OF_DAY, 1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    suppressLinkedUpdate = false
                }
                oldVal == 0 && newVal == 23 -> {
                    val current = buildDateWithValues(hour = oldVal)
                    current.add(Calendar.HOUR_OF_DAY, -1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    suppressLinkedUpdate = false
                }
            }
        }

        npMin.setOnValueChangedListener { _, oldVal, newVal ->
            if (suppressLinkedUpdate) return@setOnValueChangedListener
            when {
                oldVal == 59 && newVal == 0 -> {
                    val current = buildDateWithValues(minute = oldVal)
                    current.add(Calendar.MINUTE, 1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    npMin.value = current.get(Calendar.MINUTE)
                    suppressLinkedUpdate = false
                }
                oldVal == 0 && newVal == 59 -> {
                    val current = buildDateWithValues(minute = oldVal)
                    current.add(Calendar.MINUTE, -1)
                    applyDateToPickers(current)
                    suppressLinkedUpdate = true
                    npHour.value = current.get(Calendar.HOUR_OF_DAY)
                    npMin.value = current.get(Calendar.MINUTE)
                    suppressLinkedUpdate = false
                }
            }
        }

        view.findViewById<View>(R.id.btn_confirm_time).setOnClickListener {
            onConfirm(String.format(Locale.getDefault(), "%d-%02d-%02d %02d:%02d:00", npYear.value, npMonth.value, npDay.value, npHour.value, npMin.value))
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_cancel_time)?.setOnClickListener { dialog.dismiss() }
        dialog.window?.let {
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            it.attributes = lp
        }
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    fun showGridAssetPicker(ctx: Context, currentSelectionText: String, title: String, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_asset_picker, null)
        view.findViewById<TextView>(R.id.tv_asset_picker_title).text = title
        view.findViewById<Button>(R.id.btn_confirm_asset)?.visibility = View.GONE
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_assets)
        var currentSelection = currentSelectionText

        // 璧勪骇鍒楄〃锛堝彲鍙橈紝鐢ㄤ簬鎷栨嫿閲嶆帓锛?
        val assetList = mutableListOf<tao.test.flipaccounting.data.local.entity.Asset>()

        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            init { setHasStableIds(true) }
            inner class AssetVH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v)

            override fun getItemId(position: Int) = assetList[position].id

            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AssetVH {
                val v = LayoutInflater.from(ctx).inflate(R.layout.item_asset_grid, parent, false)
                return AssetVH(v)
            }

            override fun getItemCount() = assetList.size

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val asset = assetList[position]
                val tv = holder.itemView.findViewById<TextView>(R.id.tv_asset_name)
                val tvType = holder.itemView.findViewById<TextView?>(R.id.tv_asset_type)
                val iv = holder.itemView.findViewById<ImageView>(R.id.iv_asset_icon)
                tv.text = asset.name
                tvType?.text = ""   // 娓呯┖澶嶇敤娈嬬暀锛岄€夋嫨鍣ㄤ笉鏄剧ず绫诲瀷
                tvType?.visibility = View.GONE
                tv.setTextColor(if (asset.name == currentSelection) Color.parseColor("#2196F3") else Color.parseColor("#333333"))
                holder.itemView.alpha = if (asset.name == currentSelection) 1f else 0.85f

                if (asset.icon.isNotEmpty()) {
                    Glide.with(ctx)
                        .load(asset.icon)
                        .transform(CircleCrop())
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.AUTOMATIC)
                        .into(iv)
                } else {
                    iv.setImageResource(R.mipmap.ic_launcher_round)
                }

                holder.itemView.setOnClickListener {
                    currentSelection = asset.name
                    onConfirm(currentSelection)
                    dialog.dismiss()
                }
            }
        }

        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(ctx, 5)
        rv.adapter = adapter

        // 闀挎寜鎷栨嫿鎺掑簭
        val touchHelper = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                androidx.recyclerview.widget.ItemTouchHelper.UP or
                androidx.recyclerview.widget.ItemTouchHelper.DOWN or
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or
                androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0
            ) {
                override fun onMove(rv: androidx.recyclerview.widget.RecyclerView, vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, target: androidx.recyclerview.widget.RecyclerView.ViewHolder): Boolean {
                    val from = vh.adapterPosition
                    val to = target.adapterPosition
                    if (from == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        to == androidx.recyclerview.widget.RecyclerView.NO_POSITION ||
                        from == to
                    ) return false
                    val moved = assetList.removeAt(from)
                    assetList.add(to, moved)
                    adapter.notifyItemMoved(from, to)
                    return true
                }

                override fun onSwiped(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder, direction: Int) {}

                override fun onSelectedChanged(viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder?, actionState: Int) {
                    super.onSelectedChanged(viewHolder, actionState)
                    if (actionState == androidx.recyclerview.widget.ItemTouchHelper.ACTION_STATE_DRAG) {
                        viewHolder?.itemView?.alpha = 0.7f
                        viewHolder?.itemView?.scaleX = 1.1f
                        viewHolder?.itemView?.scaleY = 1.1f
                    }
                }

                override fun clearView(rv: androidx.recyclerview.widget.RecyclerView, viewHolder: androidx.recyclerview.widget.RecyclerView.ViewHolder) {
                    super.clearView(rv, viewHolder)
                    viewHolder.itemView.alpha = if (assetList.getOrNull(viewHolder.adapterPosition)?.name == currentSelection) 1f else 0.85f
                    viewHolder.itemView.scaleX = 1f
                    viewHolder.itemView.scaleY = 1f
                    // 鎷栨嫿閲婃斁鍚庢壒閲忎繚瀛樻柊鐨?pickerSortOrder锛堢嫭绔嬩簬璧勪骇椤?sortOrder锛?
                    CoroutineScope(Dispatchers.IO).launch {
                        val db = AppDatabase.getDatabase(ctx)
                        assetList.forEachIndexed { idx, asset ->
                            db.assetDao().updatePickerSortOrder(asset.id, idx + 1)
                        }
                    }
                }
            }
        )
        touchHelper.attachToRecyclerView(rv)

        CoroutineScope(Dispatchers.Main).launch {
            val assets = withContext(Dispatchers.IO) { AppDatabase.getDatabase(ctx).assetDao().getAllAssetsListForPicker() }
            assetList.clear()
            assetList.addAll(assets)
            adapter.notifyDataSetChanged()
            rv.post {
                val firstChild = rv.getChildAt(0)
                if (firstChild != null) {
                    setExactVisibleRowsHeight(rv, firstChild.height)
                }
            }
        }

        dialog.window?.let {
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    fun showShizukuPrompt(ctx: Context) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val dialog = AlertDialog.Builder(themeContext).setTitle("需要 Shizuku 权限").setMessage("你想使用白名单功能，但尚未启动 Shizuku 或未授予权限。") .setPositiveButton("去授权") { d, _ ->
            d.dismiss()
            try { ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Toast.makeText(ctx, "无法打开 Shizuku", Toast.LENGTH_SHORT).show() }
        }.setNegativeButton("取消", null).create()
        applyOverlayTypeIfAllowed(dialog, ctx)
        dialog.show()
    }

    fun showExchangeRateDialog(
        ctx: Context,
        sourceAmount: Double,
        sourceCurrency: String,
        targetCurrency: String,
        initialRate: Double?,
        onConfirm: (Double, Double, Double) -> Unit
    ) {
        try {
            // 纭繚鎴戜滑鏈変竴涓湁鏁堢殑 Activity 涓婁笅鏂囨潵鏄剧ず瀵硅瘽妗?
            val activityContext = if (ctx is Activity) {
                ctx
            } else if (ctx is ContextThemeWrapper) {
                // 灏濊瘯浠?ContextThemeWrapper 涓幏鍙栧熀纭€ Context
                val baseCtx = ctx.baseContext
                if (baseCtx is Activity) baseCtx else ctx
            } else {
                ctx
            }

            val themeContext = ContextThemeWrapper(activityContext, R.style.Theme_FlipAccounting)
            val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_exchange_rate, null)

            val dialog = AlertDialog.Builder(themeContext).setView(view).setCancelable(false).create()

        val etSource = view.findViewById<EditText>(R.id.et_source_amount)
        val tvSourceCurrency = view.findViewById<TextView>(R.id.tv_source_currency)
        val etRate = view.findViewById<EditText>(R.id.et_exchange_rate)
        val btnRefresh = view.findViewById<View>(R.id.btn_refresh_rate)
        val etTarget = view.findViewById<EditText>(R.id.et_target_amount)
        val tvTargetCurrency = view.findViewById<TextView>(R.id.tv_target_currency)
        val tvFormula = view.findViewById<TextView>(R.id.tv_formula)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm)

        tvSourceCurrency.text = "(${sourceCurrency})"
        tvTargetCurrency.text = "(${targetCurrency})"

        etSource.setText(String.format("%.2f", sourceAmount))
        
        var currentRate = initialRate ?: 1.0
        if (initialRate == null) {
             val rateSource = tao.test.flipaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
             val rateTarget = tao.test.flipaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
             if (rateSource != 0.0) {
                 currentRate = rateTarget / rateSource
             }
        }
        
        etRate.setText(String.format("%.6f", currentRate))
        etTarget.setText(String.format("%.2f", sourceAmount * currentRate))

        fun updateFormula() {
            val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
            val rVal = etRate.text.toString().toDoubleOrNull() ?: 0.0
            val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
            
            val sStr = String.format("%.2f", sVal)
            val rStr = String.format("%.4f", rVal)
            val tStr = String.format("%.2f", tVal)
            tvFormula.text = "换算：$sStr $sourceCurrency × $rStr = $tStr $targetCurrency"
        }
        
        updateFormula()

        // Watchers to auto-calculate
        val textWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (etSource.hasFocus() || etRate.hasFocus()) {
                    val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                    val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
                    val tVal = sVal * rVal
                    if (!etTarget.hasFocus()) {
                        etTarget.setText(String.format("%.2f", tVal))
                    }
                    updateFormula()
                }
            }
        }
        
        val targetWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (etTarget.hasFocus()) {
                    val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                    val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                    if (sVal != 0.0) {
                        val newRate = tVal / sVal
                         if (!etRate.hasFocus()) {
                             etRate.setText(String.format("%.6f", newRate))
                         }
                    }
                    updateFormula()
                }
            }
        }

        etSource.addTextChangedListener(textWatcher)
        etRate.addTextChangedListener(textWatcher)
        etTarget.addTextChangedListener(targetWatcher)

        btnRefresh.setOnClickListener {
             val rateSource = tao.test.flipaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
             val rateTarget = tao.test.flipaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
             if (rateSource != 0.0) {
                 val newRate = rateTarget / rateSource
                 etRate.setText(String.format("%.6f", newRate))
                 val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                 etTarget.setText(String.format("%.2f", sVal * newRate))
                 updateFormula()
             }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
            val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
            val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
            onConfirm(sVal, tVal, rVal)
            dialog.dismiss()
        }

        dialog.window?.let {
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        
        try {
            dialog.show()
        } catch (e: BadTokenException) {
            // 濡傛灉鍥犱负绐楀彛浠ょ墝鏃犳晥鑰屽け璐ワ紝鍒欏皾璇曚娇鐢ㄥ簲鐢ㄤ笂涓嬫枃閲嶆柊鍒涘缓瀵硅瘽妗?
            // 杩欓€氬父鍙戠敓鍦ㄤ粠鎮诞绐楁垨鍚庡彴鏈嶅姟璋冪敤鏃?
            try {
                val appContext = activityContext.applicationContext
                val recoveryThemeContext = ContextThemeWrapper(appContext, R.style.Theme_FlipAccounting)
                val recoveryView = LayoutInflater.from(recoveryThemeContext).inflate(R.layout.dialog_exchange_rate, null)
                val recoveryDialog = AlertDialog.Builder(recoveryThemeContext).setView(recoveryView).setCancelable(false).create()
                
                // 閲嶆柊鍒濆鍖栨墍鏈塙I缁勪欢
                val etSource = recoveryView.findViewById<EditText>(R.id.et_source_amount)
                val tvSourceCurrency = recoveryView.findViewById<TextView>(R.id.tv_source_currency)
                val etRate = recoveryView.findViewById<EditText>(R.id.et_exchange_rate)
                val btnRefresh = recoveryView.findViewById<View>(R.id.btn_refresh_rate)
                val etTarget = recoveryView.findViewById<EditText>(R.id.et_target_amount)
                val tvTargetCurrency = recoveryView.findViewById<TextView>(R.id.tv_target_currency)
                val tvFormula = recoveryView.findViewById<TextView>(R.id.tv_formula)
                val btnCancel = recoveryView.findViewById<View>(R.id.btn_cancel)
                val btnConfirm = recoveryView.findViewById<View>(R.id.btn_confirm)

                tvSourceCurrency.text = "(${sourceCurrency})"
                tvTargetCurrency.text = "(${targetCurrency})"
                etSource.setText(String.format("%.2f", sourceAmount))
                
                var currentRate = initialRate ?: 1.0
                if (initialRate == null) {
                    val rateSource = tao.test.flipaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
                    val rateTarget = tao.test.flipaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
                    if (rateSource != 0.0) {
                        currentRate = rateTarget / rateSource
                    }
                }
                
                etRate.setText(String.format("%.6f", currentRate))
                etTarget.setText(String.format("%.2f", sourceAmount * currentRate))

                fun updateFormula() {
                    val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                    val rVal = etRate.text.toString().toDoubleOrNull() ?: 0.0
                    val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                    
                    val sStr = String.format("%.2f", sVal)
                    val rStr = String.format("%.4f", rVal)
                    val tStr = String.format("%.2f", tVal)
                    tvFormula.text = "换算：$sStr $sourceCurrency × $rStr = $tStr $targetCurrency"
                }
                
                updateFormula()

                val textWatcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (etSource.hasFocus() || etRate.hasFocus()) {
                            val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                            val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
                            val tVal = sVal * rVal
                            if (!etTarget.hasFocus()) {
                                etTarget.setText(String.format("%.2f", tVal))
                            }
                            updateFormula()
                        }
                    }
                }
                
                val targetWatcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        if (etTarget.hasFocus()) {
                            val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                            val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                            if (sVal != 0.0) {
                                val newRate = tVal / sVal
                                if (!etRate.hasFocus()) {
                                    etRate.setText(String.format("%.6f", newRate))
                                }
                            }
                            updateFormula()
                        }
                    }
                }

                etSource.addTextChangedListener(textWatcher)
                etRate.addTextChangedListener(textWatcher)
                etTarget.addTextChangedListener(targetWatcher)

                btnRefresh.setOnClickListener {
                    val rateSource = tao.test.flipaccounting.logic.CurrencyManager.getRate(sourceCurrency) ?: 1.0
                    val rateTarget = tao.test.flipaccounting.logic.CurrencyManager.getRate(targetCurrency) ?: 1.0
                    if (rateSource != 0.0) {
                        val newRate = rateTarget / rateSource
                        etRate.setText(String.format("%.6f", newRate))
                        val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                        etTarget.setText(String.format("%.2f", sVal * newRate))
                        updateFormula()
                    }
                }

                btnCancel.setOnClickListener { recoveryDialog.dismiss() }
                btnConfirm.setOnClickListener {
                    val sVal = etSource.text.toString().toDoubleOrNull() ?: 0.0
                    val tVal = etTarget.text.toString().toDoubleOrNull() ?: 0.0
                    val rVal = etRate.text.toString().toDoubleOrNull() ?: 1.0
                    onConfirm(sVal, tVal, rVal)
                    recoveryDialog.dismiss()
                }

                recoveryDialog.window?.let {
                    it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                    // 鎮诞绐?Service 鍦烘櫙涓嬶紝蹇呴』璁剧疆 TYPE_APPLICATION_OVERLAY锛?
                    // 鍚﹀垯 show() 浼氬洜涓?token null 鍐嶆鎶涘嚭 BadTokenException
                    it.setType(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE
                    )
                }
                
                recoveryDialog.show()
            } catch (ex: Exception) {
                // 濡傛灉鎭㈠瀵硅瘽妗嗕篃澶辫触锛屽垯鍙褰曢敊璇苟杩斿洖
                android.util.Log.e("OverlayDialogs", "Failed to show exchange rate dialog even after recovery attempt", ex)
                onConfirm(sourceAmount, sourceAmount, 1.0)  // 杩斿洖榛樿鍊?
            }
        } catch (e: Exception) {
            android.util.Log.e("OverlayDialogs", "Failed to show exchange rate dialog", e)
            onConfirm(sourceAmount, sourceAmount, 1.0)  // 杩斿洖榛樿鍊?
        }
        } catch (e: Exception) {
            android.util.Log.e("OverlayDialogs", "Failed to initialize exchange rate dialog", e)
            onConfirm(sourceAmount, sourceAmount, 1.0)  // 杩斿洖榛樿鍊?
        }
    }

    /**
     * 寮瑰嚭"閫夋嫨閫€娆炬潵婧愯处鍗?瀵硅瘽妗嗐€?
     * 璇诲彇鏈€杩戠殑鏀嚭璐﹀崟锛堥潪閫€娆撅級锛屼互鍒楄〃+澶嶉€夋褰㈠紡灞曠ず锛岀敤鎴烽€夋嫨涓€鏉″悗鐐瑰嚮纭鍥炶皟銆?
     *
     * @param ctx         涓婁笅鏂囷紙Activity 鎴?Service锛?
     * @param onConfirm   鐢ㄦ埛纭閫夋嫨鍚庣殑鍥炶皟锛屽弬鏁颁负閫変腑鐨勬敮鍑鸿处鍗?
     */
    fun showRefundBillPickerDialog(ctx: Context, onConfirm: (tao.test.flipaccounting.data.local.entity.Bill) -> Unit) {
        val themeContext = android.view.ContextThemeWrapper(ctx, tao.test.flipaccounting.R.style.Theme_FlipAccounting)
        val scope = CoroutineScope(Dispatchers.Main)

        // 鍔犺浇鏈€杩?60 鏉℃敮鍑鸿处鍗曪紙鎺掗櫎宸查€€娆惧畬鍏ㄧ殑璐﹀崟锛?
        scope.launch(Dispatchers.IO) {
            val db = tao.test.flipaccounting.data.local.AppDatabase.getDatabase(ctx)
            val expenseBills = db.billDao().getRecentExpenseBills(60)

            withContext(Dispatchers.Main) {
                if (expenseBills.isEmpty()) {
                    android.widget.Toast.makeText(ctx, "暂无可退款的支出账单", android.widget.Toast.LENGTH_SHORT).show()
                    return@withContext
                }

                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val dp = ctx.resources.displayMetrics.density

                // 鈹€鈹€ 澶栧眰瀹瑰櫒锛氬渾瑙掔櫧鍗＄墖 鈹€鈹€
                val container = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = androidx.core.content.ContextCompat.getDrawable(
                        themeContext, tao.test.flipaccounting.R.drawable.shape_dialog_bg)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
                }

                // 鈹€鈹€ 鏍囬鍖?鈹€鈹€
                val tvTitle = android.widget.TextView(themeContext).apply {
                    text = "选择退款来源账单"
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
                    gravity = android.view.Gravity.CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(0, (20 * dp).toInt(), 0, (14 * dp).toInt())
                    }
                }
                container.addView(tvTitle)

                // 鈹€鈹€ 鏍囬涓嬬粏绾?鈹€鈹€
                container.addView(android.view.View(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                })

                // 鈹€鈹€ 鎼滅储妗嗭紙甯﹀渾瑙掕儗鏅級 鈹€鈹€
                val searchContainer = android.widget.FrameLayout(themeContext).apply {
                    val dp12 = (12 * dp).toInt()
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(dp12, dp12, dp12, (4 * dp).toInt())
                    }
                }
                val searchBg = android.graphics.drawable.GradientDrawable().apply {
                    setColor(android.graphics.Color.parseColor("#F5F5F5"))
                    cornerRadius = (10 * dp)
                }
                val etSearch = android.widget.EditText(themeContext).apply {
                    hint = "搜索分类或备注"
                    textSize = 13f
                    maxLines = 1
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    background = searchBg
                    val dp10 = (10 * dp).toInt()
                    val dp14 = (14 * dp).toInt()
                    setPadding(dp14, dp10, dp14, dp10)
                    layoutParams = android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
                }
                searchContainer.addView(etSearch)
                container.addView(searchContainer)

                // 鈹€鈹€ 婊氬姩鍒楄〃锛坵eight=1 鍗犳弧鍓╀綑绌洪棿锛?鈹€鈹€
                val scrollView = android.widget.ScrollView(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                }
                val listLayout = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    val dp4 = (4 * dp).toInt()
                    setPadding(0, dp4, 0, dp4)
                }
                scrollView.addView(listLayout)
                container.addView(scrollView)

                // 鈹€鈹€ 鎸夐挳鍖轰笂缁嗙嚎 鈹€鈹€
                container.addView(android.view.View(themeContext).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                })

                var selectedBill: tao.test.flipaccounting.data.local.entity.Bill? = null

                // 鈹€鈹€ 鏋勫缓/鍒锋柊鍒楄〃鍑芥暟 鈹€鈹€
                fun renderList(filter: String) {
                    listLayout.removeAllViews()
                    val keyword = filter.trim().lowercase()
                    val filtered = if (keyword.isEmpty()) expenseBills else expenseBills.filter { b ->
                        b.categoryName.lowercase().contains(keyword) ||
                        b.remark.lowercase().contains(keyword)
                    }

                    if (selectedBill != null && filtered.none { it.id == selectedBill!!.id }) {
                        selectedBill = null
                    }

                    if (filtered.isEmpty()) {
                        listLayout.addView(android.widget.TextView(themeContext).apply {
                            text = "未找到匹配账单"
                            textSize = 13f
                            gravity = android.view.Gravity.CENTER
                            setTextColor(android.graphics.Color.parseColor("#BBBBBB"))
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                                it.setMargins(0, (24 * dp).toInt(), 0, (24 * dp).toInt())
                            }
                        })
                        return
                    }

                    val checkBoxes = mutableListOf<android.widget.CheckBox>()

                    filtered.forEachIndexed { index, bill ->
                        val isSelected = (selectedBill?.id == bill.id)

                        // 閫変腑楂樹寒鑳屾櫙
                        val itemBg = if (isSelected)
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#FFF3F3"))
                        else
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)

                        val itemLayout = android.widget.LinearLayout(themeContext).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            background = itemBg
                            val dpH  = (14 * dp).toInt()
                            val dpLR = (16 * dp).toInt()
                            setPadding(dpLR, dpH, dpLR, dpH)
                            isClickable = true
                            isFocusable = true
                        }

                        val cb = android.widget.CheckBox(themeContext).apply {
                            id = android.view.View.generateViewId()
                            isChecked = isSelected
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                        }
                        checkBoxes.add(cb)

                        val textLayout = android.widget.LinearLayout(themeContext).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                                it.marginStart = (10 * dp).toInt()
                            }
                        }

                        val tvCat = android.widget.TextView(themeContext).apply {
                            text = bill.categoryName.ifEmpty { "未分类" }
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
                        }

                        val detailSb = StringBuilder(df.format(java.util.Date(bill.time)))
                        if (bill.accountName.isNotEmpty()) detailSb.append("  路  ${bill.accountName}")
                        if (bill.remark.isNotEmpty())      detailSb.append("  路  ${bill.remark}")
                        val tvDetail = android.widget.TextView(themeContext).apply {
                            text = detailSb.toString()
                            textSize = 11f
                            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).also {
                                it.topMargin = (3 * dp).toInt()
                            }
                        }

                        val tvAmount = android.widget.TextView(themeContext).apply {
                            text = "-楼${String.format(java.util.Locale.getDefault(), "%.2f", bill.amount)}"
                            textSize = 15f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#E53935"))
                            gravity = android.view.Gravity.END
                        }

                        textLayout.addView(tvCat)
                        textLayout.addView(tvDetail)
                        itemLayout.addView(cb)
                        itemLayout.addView(textLayout)
                        itemLayout.addView(tvAmount)

                        val clickAction = {
                            val wasChecked = cb.isChecked
                            checkBoxes.forEach { it.isChecked = false }
                            cb.isChecked = !wasChecked
                            selectedBill = if (cb.isChecked) bill else null
                            // 鍒锋柊楂樹寒
                            renderList(etSearch.text.toString())
                        }
                        itemLayout.setOnClickListener { clickAction() }
                        cb.setOnClickListener {
                            val isNowChecked = cb.isChecked
                            checkBoxes.filter { it != cb }.forEach { it.isChecked = false }
                            selectedBill = if (isNowChecked) bill else null
                            renderList(etSearch.text.toString())
                        }

                        listLayout.addView(itemLayout)

                        // 鍒嗛殧绾匡紙閫変腑琛屼笉鍔狅紝瑙嗚涓婃洿鏁存磥锛?
                        if (index < filtered.size - 1 && !isSelected) {
                            listLayout.addView(android.view.View(themeContext).apply {
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1).also {
                                    it.marginStart = (56 * dp).toInt()
                                    it.marginEnd   = (16 * dp).toInt()
                                }
                                setBackgroundColor(android.graphics.Color.parseColor("#F0F0F0"))
                            })
                        }
                    }
                }

                // 鍒濆娓叉煋鍏ㄩ儴
                renderList("")

                // 鐩戝惉鎼滅储妗嗚緭鍏?
                etSearch.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        renderList(s?.toString() ?: "")
                    }
                })

                // 鈹€鈹€ 鎸夐挳琛岋細鍥哄畾楂樺害锛屾寜閽湪涓婁笅鏂瑰悜灞呬腑 鈹€鈹€
                val btnRow = android.widget.LinearLayout(themeContext).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    val dp16 = (16 * dp).toInt()
                    setPadding(dp16, 0, dp16, 0)
                    // 鍥哄畾楂樺害锛屼娇鎸夐挳鍦ㄥ垎鍓茬嚎涓庡簳閮ㄤ箣闂村畬鍏ㄥ瀭鐩村眳涓?
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        (64 * dp).toInt())
                }

                try {
                    val alertDialog = androidx.appcompat.app.AlertDialog.Builder(themeContext)
                        .setView(container)
                        .create()

                    // 鍏叡鎸夐挳鑳屾櫙锛氬～鍏呰壊鍦嗚锛屾棤鎻忚竟
                    fun makeBtnBg(fillColor: Int) = android.graphics.drawable.GradientDrawable().apply {
                        setColor(fillColor)
                        cornerRadius = (24 * dp)
                    }

                    val btnCancel = android.widget.Button(themeContext).apply {
                        text = "取消"
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#888888"))
                        background = makeBtnBg(android.graphics.Color.parseColor("#F2F2F2"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, (40 * dp).toInt(), 1f).also {
                            it.marginEnd = (6 * dp).toInt()
                        }
                        setPadding(0, 0, 0, 0)
                        isAllCaps = false
                        setOnClickListener { alertDialog.dismiss() }
                    }
                    val btnConfirm = android.widget.Button(themeContext).apply {
                        text = "纭"
                        textSize = 14f
                        setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        background = makeBtnBg(android.graphics.Color.parseColor("#E53935"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, (40 * dp).toInt(), 1f).also {
                            it.marginStart = (6 * dp).toInt()
                        }
                        setPadding(0, 0, 0, 0)
                        isAllCaps = false
                        setOnClickListener {
                            val chosen = selectedBill
                            if (chosen == null) {
                                android.widget.Toast.makeText(ctx, "请先选择一条账单", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                alertDialog.dismiss()
                                onConfirm(chosen)
                            }
                        }
                    }
                    btnRow.addView(btnCancel)
                    btnRow.addView(btnConfirm)
                    container.addView(btnRow)

                    applyOverlayTypeIfAllowed(alertDialog, ctx)
                    alertDialog.show()
                    // window 背景透明，让 container 的圆角完整显示
                    // 宽度 88%、高度 50% 屏幕，避免弹窗铺满全屏
                    alertDialog.window?.let { win ->
                        win.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        val screenW = ctx.resources.displayMetrics.widthPixels
                        val screenH = ctx.resources.displayMetrics.heightPixels
                        win.setLayout(
                            (screenW * 0.88).toInt(),
                            (screenH * 0.50).toInt()
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OverlayDialogs", "showRefundBillPickerDialog show failed", e)
                }
            }
        }
    }
}
