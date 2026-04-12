package tao.test.flipaccounting

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide

class SettingsActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private var expandedParentName: String? = null

    // 默认为支出 (1)
    private var currentType = Prefs.TYPE_EXPENSE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        container = findViewById(R.id.container_main)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

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
            renderUI()
        }
    }

    override fun onResume() {
        super.onResume()
        renderUI()
    }

    private fun renderUI() {
        container.removeAllViews()

        val allCats = Prefs.getCategories(this, currentType)
        val spanCount = 4

        allCats.chunked(spanCount).forEach { row ->
            val rowLayout = createRowLayout(spanCount)

            row.forEach { cat ->
                val isSelected = (expandedParentName == cat.name)
                val itemView = createView(cat, isSub = false, isSelected = isSelected)

                itemView.setOnClickListener {
                    expandedParentName = if (expandedParentName == cat.name) null else cat.name
                    renderUI()
                }
                // 长按一级分类 (parent = null)
                itemView.setOnLongClickListener {
                    showActionMenu(cat, null)
                    true
                }
                rowLayout.addView(itemView)
            }
            fillPlaceholder(rowLayout, spanCount - row.size)
            container.addView(rowLayout)

            val matchedParent = row.find { it.name == expandedParentName }
            if (matchedParent != null) {
                container.addView(createSubPanel(matchedParent))
            }
        }
    }

    private fun createSubPanel(parent: CategoryNode): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            setPadding(20, 20, 20, 20)
        }

        val spanCount = 4
        val allItems = parent.subs.toMutableList()
        val totalItems = allItems + listOf(null) // null 代表添加按钮

        totalItems.chunked(spanCount).forEach { rowItems ->
            val rowLayout = createRowLayout(spanCount)

            rowItems.forEach { item ->
                if (item != null) {
                    val itemView = createView(item, isSub = true)
                    // 长按二级分类 (parent = parent)
                    itemView.setOnLongClickListener {
                        showActionMenu(item, parent)
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
        return panel
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

        if (isSelected) view.setBackgroundColor(Color.parseColor("#E0E0E0"))
        else view.setBackgroundColor(Color.TRANSPARENT)

        view.findViewById<TextView>(R.id.tv_category_name).text = cat.name
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        if (isSelected) {
            iv.setColorFilter(Color.parseColor("#2196F3"))
            view.findViewById<TextView>(R.id.tv_category_name).setTextColor(Color.parseColor("#2196F3"))
        } else {
            iv.setColorFilter(Color.parseColor("#757575"))
            view.findViewById<TextView>(R.id.tv_category_name).setTextColor(Color.parseColor("#333333"))
        }
        if (cat.icon.isNotEmpty()) Glide.with(this).load(cat.icon).into(iv)
        return view
    }

    private fun createAddSubButton(parent: CategoryNode): View {
        val view = LayoutInflater.from(this).inflate(R.layout.item_category_grid, null)
        view.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        view.findViewById<TextView>(R.id.tv_category_name).text = "添加子类"
        val iv = view.findViewById<ImageView>(R.id.iv_category_icon)
        iv.setImageResource(android.R.drawable.ic_menu_add)
        iv.setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN)

        view.setOnClickListener {
            val intent = Intent(this, AddCategoryActivity::class.java)
            intent.putExtra("type", currentType)
            intent.putExtra("parentName", parent.name)
            startActivity(intent)
        }
        return view
    }

    // --- 核心新功能：显示操作菜单 ---
    private fun showActionMenu(target: CategoryNode, parent: CategoryNode?) {
        val options = arrayOf("修改分类", "删除分类")
        AlertDialog.Builder(this)
            .setTitle("操作: ${target.name}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> { // 修改
                        val intent = Intent(this, AddCategoryActivity::class.java)
                        intent.putExtra("type", currentType)
                        intent.putExtra("parentName", parent?.name) // 如果是根节点，这里是 null，逻辑正确
                        intent.putExtra("isEdit", true) // 标记为编辑模式
                        intent.putExtra("oldName", target.name)
                        intent.putExtra("oldIcon", target.icon)
                        startActivity(intent)
                    }
                    1 -> { // 删除
                        showDeleteConfirm(target.name)
                    }
                }
            }
            .show()
    }

    private fun showDeleteConfirm(name: String) {
        AlertDialog.Builder(this)
            .setTitle("确定删除 [$name] 吗？")
            .setMessage("删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                Prefs.deleteCategory(this, currentType, name)
                if (expandedParentName == name) expandedParentName = null
                renderUI()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}