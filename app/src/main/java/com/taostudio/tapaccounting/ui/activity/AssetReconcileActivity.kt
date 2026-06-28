package com.taostudio.tapaccounting.ui.activity

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.logic.AssetReconciliationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 资产对账页。
 * 用户输入实际余额后，计算差异并分析可能原因。
 */
class AssetReconcileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ASSET_ID = "asset_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_asset_reconcile)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        val assetId = intent.getLongExtra(EXTRA_ASSET_ID, -1)
        if (assetId < 0) {
            finish()
            return
        }

        val db = AppDatabase.getDatabase(this)
        val service = AssetReconciliationService()

        val tvLedgerBalance = findViewById<TextView>(R.id.tv_ledger_balance)
        val tvActualBalance = findViewById<TextView>(R.id.tv_actual_balance)
        val tvDiff = findViewById<TextView>(R.id.tv_diff)
        val etActual = findViewById<EditText>(R.id.et_actual_balance)
        val btnReconcile = findViewById<View>(R.id.btn_reconcile)
        val btnAdjust = findViewById<View>(R.id.btn_adjust_balance)

        lifecycleScope.launch {
            val asset = withContext(Dispatchers.IO) {
                db.assetDao().getAssetById(assetId)
            }
            if (asset == null) {
                finish()
                return@launch
            }

            tvLedgerBalance.text = "¥${String.format("%.2f", asset.balance)}"

            btnReconcile?.setOnClickListener {
                val actualStr = etActual.text.toString()
                val actual = actualStr.toDoubleOrNull()
                if (actual == null) {
                    Toast.makeText(this@AssetReconcileActivity, "请输入有效金额", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch {
                    val recentBills = withContext(Dispatchers.IO) {
                        db.billDao().getBillsByAssetIdOrNameList(assetId, asset.name)
                    }

                    val report = service.reconcile(asset, actual, recentBills)

                    tvActualBalance.text = "¥${String.format("%.2f", report.actualBalance)}"
                    tvDiff.text = when {
                        report.diff > 0.01 -> "+¥${String.format("%.2f", report.diff)}"
                        report.diff < -0.01 -> "-¥${String.format("%.2f", -report.diff)}"
                        else -> "已对齐"
                    }
                    tvDiff.setTextColor(
                        when {
                            report.diff > 0.01 -> android.graphics.Color.parseColor("#4CAF50")
                            report.diff < -0.01 -> android.graphics.Color.parseColor("#FF5252")
                            else -> android.graphics.Color.parseColor("#4CAF50")
                        }
                    )

                    // 展示可能原因和相关账单
                    showCausesAndBills(report)
                }
            }

            btnAdjust?.setOnClickListener {
                // 跳转到 BalanceAdjustmentActivity
                val intent = android.content.Intent(
                    this@AssetReconcileActivity,
                    com.taostudio.tapaccounting.BalanceAdjustmentActivity::class.java
                )
                intent.putExtra("asset_id", assetId)
                startActivity(intent)
            }
        }
    }

    private fun showCausesAndBills(report: AssetReconciliationService.ReconciliationReport) {
        val layoutCauses = findViewById<LinearLayout>(R.id.layout_causes)
        val layoutBills = findViewById<LinearLayout>(R.id.layout_related_bills)

        // 展示可能原因
        if (report.likelyCauses.isNotEmpty()) {
            layoutCauses?.visibility = View.VISIBLE
            layoutCauses?.removeAllViews()
            for (cause in report.likelyCauses) {
                val tv = TextView(this).apply {
                    text = "• ${cause.description}${cause.amount?.let { " (¥${String.format("%.2f", it)})" } ?: ""}"
                    textSize = 13f
                    setTextColor(Color.parseColor("#666666"))
                    setPadding(0, 4, 0, 4)
                }
                layoutCauses?.addView(tv)
            }
        } else {
            layoutCauses?.visibility = View.GONE
        }

        // 展示相关账单
        if (report.recentUnmatchedBills.isNotEmpty()) {
            layoutBills?.visibility = View.VISIBLE
            layoutBills?.removeAllViews()
            val df = java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
            for (bill in report.recentUnmatchedBills.take(10)) {
                val typeLabel = when (bill.type) {
                    0 -> "支出"
                    1 -> "收入"
                    2 -> "转账"
                    else -> "其他"
                }
                val tv = TextView(this).apply {
                    text = "${df.format(java.util.Date(bill.time))} ${bill.remark.ifBlank { bill.categoryName }} ¥${String.format("%.2f", bill.amount)} ($typeLabel)"
                    textSize = 12f
                    setTextColor(Color.parseColor("#999999"))
                    setPadding(0, 2, 0, 2)
                }
                layoutBills?.addView(tv)
            }
        } else {
            layoutBills?.visibility = View.GONE
        }
    }
}
