package tao.test.flipaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop

class AssetDetailActivity : AppCompatActivity() {

    private var assetName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_detail)

        assetName = intent.getStringExtra("ASSET_NAME") ?: run { finish(); return }

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<ImageView>(R.id.btn_delete).setOnClickListener {
            showDeleteConfirm()
        }

        findViewById<TextView>(R.id.btn_edit).setOnClickListener {
            val i = Intent(this, AddAssetActivity::class.java)
            i.putExtra("EDIT_ASSET_NAME", assetName)
            startActivity(i)
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次返回都刷新（编辑后名称可能变化，从 Prefs 重新查找）
        val asset = Prefs.getAssets(this).find { it.name == assetName }
        if (asset == null) {
            // 如果找不到了（被删除或名称改变），退出详情页
            finish()
            return
        }
        bindAsset(asset)
    }

    private fun bindAsset(asset: Asset) {
        // 更新当前持有的 name（编辑后可能已更改）
        assetName = asset.name

        val ivIcon = findViewById<ImageView>(R.id.iv_asset_icon)
        val tvName = findViewById<TextView>(R.id.tv_asset_name)
        val tvType = findViewById<TextView>(R.id.tv_asset_type)
        val tvCurrency = findViewById<TextView>(R.id.tv_asset_currency)

        tvName.text = asset.name
        tvType.text = asset.type
        tvCurrency.text = asset.currency

        if (asset.icon.isNotEmpty()) {
            Glide.with(this)
                .load(asset.icon)
                .transform(CircleCrop())
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(ivIcon)
        } else {
            ivIcon.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    private fun showDeleteConfirm() {
        AlertDialog.Builder(this)
            .setTitle("删除资产")
            .setMessage("确定要删除「$assetName」吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                val list = Prefs.getAssets(this).toMutableList()
                list.removeIf { it.name == assetName }
                Prefs.saveAssets(this, list)
                Utils.toast(this, "已删除")
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
