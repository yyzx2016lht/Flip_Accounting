package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
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
    private var isMultiSelect = false
    private var lastAutoFilledName: String? = null
    private var selectedCategories: List<BuiltInCategory> = emptyList()

    private lateinit var allIcons: List<BuiltInCategory>
    private lateinit var adapter: BuiltInCategoryAdapter

    private val database by lazy {
        AppDatabase.getDatabase(this)
    }

    private val categoryRepository by lazy {
        CategoryRepository(database.categoryDao(), database.billDao())
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
        val btnMultiSelect = findViewById<TextView>(R.id.btn_multi_select)
        val categoryEditor = findViewById<View>(R.id.layout_category_editor)
        val iconSectionTitle = findViewById<TextView>(R.id.tv_icon_section_title)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        val typeStr = if (type == Prefs.TYPE_EXPENSE) getString(R.string.expense) else getString(R.string.income)

        if (isEdit) {
            tvTitle.text = getString(R.string.edit_category_title, typeStr)
            btn.text = getString(R.string.save_changes)
            etName.setText(oldName)
            etName.setSelection(oldName.length)
            selectedIconUrl = oldIcon
            if (oldIcon.isNotEmpty()) {
                Glide.with(this).load(oldIcon).into(ivPreview)
            }
            btnMultiSelect.visibility = View.GONE
        } else {
            tvTitle.text = if (parentName != null) getString(R.string.add_subcategory_title, typeStr) else getString(R.string.add_category_title, typeStr)
        }

        ivPreview.setColorFilter(Color.parseColor("#424242"), PorterDuff.Mode.SRC_IN)

        allIcons = JsonUtils.getBuiltInCategories(this)
        adapter = BuiltInCategoryAdapter(
            items = allIcons,
            onSelect = { selected ->
                selectedIconUrl = selected.icon
                val currentText = etName.text.toString()
                if (!isEdit && (currentText.isEmpty() || currentText == lastAutoFilledName)) {
                    etName.setText(selected.name)
                    etName.setSelection(selected.name.length)
                    lastAutoFilledName = selected.name
                }
                Glide.with(this).load(selected.icon).into(ivPreview)
            },
            onMultiSelectionChanged = { selected ->
                selectedCategories = selected
                btn.text = if (selected.isEmpty()) {
                    getString(R.string.add_selected_categories_empty)
                } else {
                    getString(R.string.add_selected_categories, selected.size)
                }
            }
        )
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.adapter = adapter
        if (selectedIconUrl.isNotEmpty()) {
            adapter.setSelectedIcon(selectedIconUrl)
        }

        btnMultiSelect.setOnClickListener {
            isMultiSelect = !isMultiSelect
            adapter.setMultiSelect(isMultiSelect)
            categoryEditor.visibility = if (isMultiSelect) View.GONE else View.VISIBLE
            btnMultiSelect.text = getString(if (isMultiSelect) R.string.cancel else R.string.multi_select)
            iconSectionTitle.text = getString(
                if (isMultiSelect) R.string.select_multiple_categories else R.string.select_icon
            )
            btn.text = getString(
                if (isMultiSelect) R.string.add_selected_categories_empty else R.string.save_category
            )
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
            if (isMultiSelect) {
                saveMultipleCategories(btn)
                return@setOnClickListener
            }

            val newName = etName.text.toString().trim()
            if (newName.isEmpty() || selectedIconUrl.isEmpty()) {
                Utils.toast(this, getString(R.string.input_name_icon))
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
                    Utils.toast(this@AddCategoryActivity, if (isEdit) getString(R.string.edit_success) else getString(R.string.save_success))
                    finish()
                }
            }
        }
    }

    private fun saveMultipleCategories(button: Button) {
        if (selectedCategories.isEmpty()) {
            Utils.toast(this, getString(R.string.select_at_least_one_category))
            return
        }

        button.isEnabled = false
        val selection = selectedCategories
            .distinctBy { it.name.trim() }
            .filter { it.name.isNotBlank() }

        lifecycleScope.launch(Dispatchers.IO) {
            val dbType = if (type == Prefs.TYPE_INCOME) 1 else 0
            val existing = categoryRepository.getCategoriesListByType(dbType)
            val parentId = parentName?.let { name ->
                existing.firstOrNull { it.parentId == null && it.name == name }?.id
            }

            if (parentName != null && parentId == null) {
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    Utils.toast(this@AddCategoryActivity, getString(R.string.parent_category_not_found))
                }
                return@launch
            }

            val existingNames = existing
                .asSequence()
                .filter { it.parentId == parentId }
                .map { it.name }
                .toSet()
            val categoriesToAdd = selection.filter { it.name.trim() !in existingNames }

            database.withTransaction {
                categoriesToAdd.forEach { selected ->
                    categoryRepository.addCategory(
                        Category(
                            name = selected.name.trim(),
                            type = dbType,
                            parentId = parentId,
                            iconId = selected.icon
                        )
                    )
                }
            }

            val skippedCount = selectedCategories.size - categoriesToAdd.size
            withContext(Dispatchers.Main) {
                button.isEnabled = true
                when {
                    categoriesToAdd.isEmpty() ->
                        Utils.toast(this@AddCategoryActivity, getString(R.string.selected_categories_exist))
                    skippedCount > 0 ->
                        Utils.toast(
                            this@AddCategoryActivity,
                            getString(
                                R.string.categories_added_with_skipped,
                                categoriesToAdd.size,
                                skippedCount
                            )
                        )
                    else ->
                        Utils.toast(
                            this@AddCategoryActivity,
                            getString(R.string.categories_added, categoriesToAdd.size)
                        )
                }
                if (categoriesToAdd.isNotEmpty()) finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        StatusBarStyle.applyByColor(window, Color.WHITE)
    }
}

