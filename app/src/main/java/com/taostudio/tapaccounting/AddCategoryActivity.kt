package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.ui.common.StatusBarStyle

class AddCategoryActivity : AppCompatActivity() {

    private var selectedIconUrl: String = ""
    private var parentName: String? = null
    private var type: Int = Prefs.TYPE_EXPENSE

    private var isEdit = false
    private var oldName: String = ""
    private var editId: Long = 0L

    private lateinit var allIcons: List<BuiltInCategory>
    private lateinit var adapter: BuiltInCategoryAdapter

    private val categoryRepository by lazy {
        val db = AppDatabase.getDatabase(this)
        CategoryRepository(db.categoryDao(), db.billDao())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_category)

        parentName = intent.getStringExtra("parentName")
        type = intent.getIntExtra("type", Prefs.TYPE_EXPENSE)

        isEdit = intent.getBooleanExtra("isEdit", false)
        oldName = intent.getStringExtra("oldName") ?: ""
        val oldIcon = intent.getStringExtra("oldIcon") ?: ""
        editId = intent.getLongExtra("editId", 0L)

        val etName = findViewById<EditText>(R.id.et_category_name)
        val etSearch = findViewById<EditText>(R.id.et_search)
        val tvTitle = findViewById<TextView>(R.id.tv_parent_info)
        val ivPreview = findViewById<ImageView>(R.id.iv_preview_icon)
        val rv = findViewById<RecyclerView>(R.id.rv_icon_library)
        val btn = findViewById<Button>(R.id.btn_create_category)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val typeStr = if (type == Prefs.TYPE_EXPENSE) "支出" else "收入"

        if (isEdit) {
            tvTitle.text = "修改分类 [$typeStr]"
            btn.text = "保存修改"
            etName.setText(oldName)
            etName.setSelection(oldName.length)
            selectedIconUrl = oldIcon
            if (oldIcon.isNotEmpty()) {
                Glide.with(this).load(oldIcon).into(ivPreview)
            }
        } else {
            tvTitle.text = if (parentName != null) "新增子分类 [$typeStr]" else "新增分类 [$typeStr]"
        }

        ivPreview.setColorFilter(Color.parseColor("#424242"), PorterDuff.Mode.SRC_IN)

        allIcons = JsonUtils.getBuiltInCategories(this)
        adapter = BuiltInCategoryAdapter(allIcons) { selected ->
            selectedIconUrl = selected.icon
            val currentText = etName.text.toString()
            if (currentText.isEmpty() || (!isEdit && currentText == selected.name)) {
                etName.setText(selected.name)
            }
            Glide.with(this).load(selected.icon).into(ivPreview)
        }
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.adapter = adapter
        if (selectedIconUrl.isNotEmpty()) {
            adapter.setSelectedIcon(selectedIconUrl)
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s.toString().trim()
                val filtered = if (keyword.isEmpty()) {
                    allIcons
                } else {
                    allIcons.filter { it.name.contains(keyword, ignoreCase = true) }
                }
                adapter.updateList(filtered)
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        btn.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isEmpty() || selectedIconUrl.isEmpty()) {
                Utils.toast(this, "请输入名称并选择图标")
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
                if (isEdit) {
                    if (editId != 0L) {
                        val existing = categoryRepository.getCategoriesListByType(dbType)
                            .find { it.id == editId }
                        if (existing != null) {
                            categoryRepository.updateCategory(
                                existing.copy(name = newName, iconId = selectedIconUrl)
                            )
                        }
                    }
                } else {
                    val parentId: Long? = if (parentName != null) {
                        categoryRepository.getCategoryByName(parentName!!)?.id
                    } else {
                        null
                    }
                    categoryRepository.addCategory(
                        Category(
                            name = newName,
                            type = dbType,
                            parentId = parentId,
                            iconId = selectedIconUrl
                        )
                    )
                }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@AddCategoryActivity, if (isEdit) "修改成功" else "保存成功")
                    finish()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StatusBarStyle.applyByColor(window, Color.WHITE)
    }
}

