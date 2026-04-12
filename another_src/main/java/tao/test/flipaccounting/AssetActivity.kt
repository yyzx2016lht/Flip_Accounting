package tao.test.flipaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class AssetActivity : AppCompatActivity() {

    private lateinit var adapter: AssetAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_manager)

        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }

        // 右上角添加按钮
        findViewById<TextView>(R.id.btn_add_asset).setOnClickListener {
            startActivity(Intent(this, AddAssetActivity::class.java))
        }

        val rvAssets = findViewById<RecyclerView>(R.id.rv_assets)
        // 使用 4 列网格
        rvAssets.layoutManager = GridLayoutManager(this, 4)

        adapter = AssetAdapter(mutableListOf()) { asset ->
            showDeleteConfirm(asset)
        }
        rvAssets.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val assets = Prefs.getAssets(this)
        adapter.updateData(assets)
    }

    private fun showDeleteConfirm(asset: Asset) {
        AlertDialog.Builder(this)
            .setTitle("删除资产")
            .setMessage("确定要删除 [${asset.name}] 吗？")
            .setPositiveButton("删除") { _, _ ->
                val list = Prefs.getAssets(this).toMutableList()
                list.removeIf { it.name == asset.name } // 简单按名字删除
                Prefs.saveAssets(this, list)
                adapter.updateData(list)
                Utils.toast(this, "已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }
}