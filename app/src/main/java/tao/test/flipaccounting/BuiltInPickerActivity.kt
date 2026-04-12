package tao.test.flipaccounting

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class BuiltInPickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_built_in_picker)

        val rv = findViewById<RecyclerView>(R.id.rv_built_in_categories)

        // 1. 设置网格布局，每行 5 个
        rv.layoutManager = GridLayoutManager(this, 5)

        // 2. 解析数据
        val data = JsonUtils.getBuiltInCategories(this)

        // 3. 设置适配器
        rv.adapter = BuiltInCategoryAdapter(data) { selected ->
            // 这里处理选择后的逻辑，例如返回给上一级页面
            Utils.toast(this, "选择了: ${selected.name}")
            // intent.putExtra("name", selected.name)
            // intent.putExtra("icon", selected.icon)
            // setResult(RESULT_OK, intent)
            // finish()
        }
    }
}