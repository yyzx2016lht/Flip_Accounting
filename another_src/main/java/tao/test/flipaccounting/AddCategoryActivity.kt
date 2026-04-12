package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide

class AddCategoryActivity : AppCompatActivity() {

    private var selectedIconUrl: String = ""
    private var parentName: String? = null
    private var type: Int = Prefs.TYPE_EXPENSE

    // 编辑模式相关变量
    private var isEdit = false
    private var oldName: String = ""

    private lateinit var allIcons: List<BuiltInCategory>
    private lateinit var adapter: BuiltInCategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_category)

        parentName = intent.getStringExtra("parentName")
        type = intent.getIntExtra("type", Prefs.TYPE_EXPENSE)

        // 读取编辑模式参数
        isEdit = intent.getBooleanExtra("isEdit", false)
        oldName = intent.getStringExtra("oldName") ?: ""
        val oldIcon = intent.getStringExtra("oldIcon") ?: ""

        val etName = findViewById<EditText>(R.id.et_category_name)
        val etSearch = findViewById<EditText>(R.id.et_search)
        val tvParent = findViewById<TextView>(R.id.tv_parent_info)
        val ivPreview = findViewById<ImageView>(R.id.iv_preview_icon)
        val rv = findViewById<RecyclerView>(R.id.rv_icon_library)
        val btn = findViewById<Button>(R.id.btn_create_category)

        val typeStr = if (type == Prefs.TYPE_EXPENSE) "支出" else "收入"

        // 初始化界面：区分新增模式和编辑模式
        if (isEdit) {
            tvParent.text = "[$typeStr] 修改分类"
            btn.text = "保存修改"
            // 填充旧数据
            etName.setText(oldName)
            etName.setSelection(oldName.length)
            selectedIconUrl = oldIcon
            if (oldIcon.isNotEmpty()) {
                Glide.with(this).load(oldIcon).into(ivPreview)
            }
        } else {
            // 新增模式
            if (parentName != null) {
                tvParent.text = "[$typeStr] 父类：$parentName"
            } else {
                tvParent.text = "[$typeStr] 新增一级分类"
            }
        }

        ivPreview.setColorFilter(Color.parseColor("#424242"), PorterDuff.Mode.SRC_IN)

        // 加载图标库
        allIcons = JsonUtils.getBuiltInCategories(this)
        adapter = BuiltInCategoryAdapter(allIcons) { selected ->
            selectedIconUrl = selected.icon
            // 如果名字为空，自动填入；如果是编辑模式，且名字没改动过，也允许自动填入
            val currentText = etName.text.toString()
            if (currentText.isEmpty() || (!isEdit && currentText == selected.name)) {
                etName.setText(selected.name)
            }
            Glide.with(this).load(selected.icon).into(ivPreview)
        }
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.adapter = adapter

        if (etSearch != null) {
            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val keyword = s.toString().trim()
                    val filtered = if (keyword.isEmpty()) allIcons else allIcons.filter { it.name.contains(keyword, ignoreCase = true) }
                    adapter.updateList(filtered)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }

        // 保存/修改逻辑
        btn.setOnClickListener {
            val newName = etName.text.toString().trim()
            if (newName.isEmpty() || selectedIconUrl.isEmpty()) {
                Utils.toast(this, "请输入名称并选择图标")
                return@setOnClickListener
            }

            val allCategories = Prefs.getCategories(this, type)

            if (isEdit) {
                // --- 编辑模式：更新节点 ---
                updateCategory(allCategories, parentName, oldName, newName, selectedIconUrl)
            } else {
                // --- 新增模式：添加节点 ---
                val newNode = CategoryNode(newName, selectedIconUrl)
                if (parentName == null) {
                    allCategories.add(newNode)
                    saveAndFinish(allCategories)
                } else {
                    val parentNode = findNodeRecursive(allCategories, parentName!!)
                    if (parentNode != null) {
                        parentNode.subs.add(newNode)
                        saveAndFinish(allCategories)
                    } else {
                        Utils.toast(this, "保存失败：找不到父分类")
                    }
                }
            }
        }
    }

    // 更新分类逻辑
    private fun updateCategory(list: MutableList<CategoryNode>, parentName: String?, targetOldName: String, newName: String, newIcon: String) {
        var isUpdated = false

        if (parentName == null) {
            // 在根节点列表中查找并替换
            val index = list.indexOfFirst { it.name == targetOldName }
            if (index != -1) {
                val oldNode = list[index]
                // 使用 copy 创建新节点，保留原有的子节点列表 (subs)
                val newNode = oldNode.copy(name = newName, icon = newIcon)
                list[index] = newNode
                isUpdated = true
            }
        } else {
            // 在子节点中查找
            val parentNode = findNodeRecursive(list, parentName)
            if (parentNode != null) {
                val index = parentNode.subs.indexOfFirst { it.name == targetOldName }
                if (index != -1) {
                    val oldNode = parentNode.subs[index]
                    val newNode = oldNode.copy(name = newName, icon = newIcon)
                    parentNode.subs[index] = newNode
                    isUpdated = true
                }
            }
        }

        if (isUpdated) {
            saveAndFinish(list)
        } else {
            Utils.toast(this, "更新失败：未找到原分类")
        }
    }

    private fun saveAndFinish(data: List<CategoryNode>) {
        Prefs.saveCategories(this, type, data)
        Utils.toast(this, if(isEdit) "修改成功" else "保存成功")
        finish()
    }

    private fun findNodeRecursive(nodes: List<CategoryNode>, targetName: String): CategoryNode? {
        for (node in nodes) {
            if (node.name == targetName) return node
            val found = findNodeRecursive(node.subs, targetName)
            if (found != null) return found
        }
        return null
    }
}