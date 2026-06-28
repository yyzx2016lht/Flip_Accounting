package com.taostudio.tapaccounting.ui.import

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导入审查页。
 * CSV 导入后展示临时资产、未识别分类等，引导用户处理。
 */
class ImportReviewActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_review)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        setupReviewList()
        setupDoneButton()
    }

    private fun setupReviewList() {
        val rvReview = findViewById<RecyclerView>(R.id.rv_review_items)
        rvReview?.layoutManager = LinearLayoutManager(this)

        val db = AppDatabase.getDatabase(this)

        val adapter = TempAssetAdapter(
            onMerge = { asset -> showMergeDialog(asset, db) },
            onRename = { asset -> showRenameDialog(asset, db) }
        )
        rvReview?.adapter = adapter

        lifecycleScope.launch {
            val tempAssets = withContext(Dispatchers.IO) {
                db.assetDao().getAllAssetsList().filter {
                    it.remark.contains("CSV_TEMP_ASSET_MARKER") || it.type == "CSV导入待确认"
                }
            }

            val tvEmpty = findViewById<TextView>(R.id.tv_review_empty)
            if (tempAssets.isEmpty()) {
                tvEmpty?.visibility = View.VISIBLE
                tvEmpty?.text = "没有需要审查的导入项"
                rvReview?.visibility = View.GONE
            } else {
                tvEmpty?.visibility = View.GONE
                rvReview?.visibility = View.VISIBLE
                adapter.submitList(tempAssets)
            }
        }
    }

    private fun showMergeDialog(tempAsset: Asset, db: AppDatabase) {
        lifecycleScope.launch {
            val allAssets = withContext(Dispatchers.IO) {
                db.assetDao().getAllAssetsList().filter {
                    it.id != tempAsset.id && it.type != "CSV导入待确认" && !it.remark.contains("CSV_TEMP_ASSET_MARKER")
                }
            }
            val names = allAssets.map { it.name }.toTypedArray()

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(ContextThemeWrapper(this@ImportReviewActivity, R.style.Theme_TapAccounting))
                    .setTitle("合并到已有资产")
                    .setItems(names) { _, which ->
                        val target = allAssets[which]
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                // 将临时资产的账单转移到目标资产
                                val bills = db.billDao().getBillsByAssetIdList(tempAsset.id)
                                for (bill in bills) {
                                    db.billDao().updateBill(bill.copy(accountId = target.id, accountName = target.name))
                                }
                                // 删除临时资产
                                db.assetDao().deleteAsset(tempAsset)
                            }
                            Toast.makeText(this@ImportReviewActivity, "已合并到「${target.name}」", Toast.LENGTH_SHORT).show()
                            setupReviewList() // 刷新列表
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    private fun showRenameDialog(asset: Asset, db: AppDatabase) {
        val et = EditText(this).apply {
            setText(asset.name)
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setTitle("重命名资产")
            .setView(et)
            .setPositiveButton("确定") { _, _ ->
                val newName = et.text.toString().trim()
                if (newName.isNotBlank()) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.assetDao().updateAsset(asset.copy(name = newName, type = newName, remark = ""))
                        }
                        Toast.makeText(this@ImportReviewActivity, "已重命名", Toast.LENGTH_SHORT).show()
                        setupReviewList()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupDoneButton() {
        findViewById<View>(R.id.btn_review_done)?.setOnClickListener {
            Prefs.setImportReviewCompleted(this, true)
            Toast.makeText(this, "审查完成", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
