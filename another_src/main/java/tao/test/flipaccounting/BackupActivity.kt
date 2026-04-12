package tao.test.flipaccounting

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BackupActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<View>(R.id.card_export).setOnClickListener {
            BackupManager.startExport(this)
        }

        findViewById<View>(R.id.card_import).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确认恢复")
                .setMessage("恢复将覆盖当前所有资产、分类、账单及偏好设置，建议在恢复前先导出当前数据作为备份。是否继续？")
                .setPositiveButton("确定") { _, _ ->
                    BackupManager.startImport(this)
                }
                .setNegativeButton("取消", null)
                .show()
        }

        updateStats()
    }

    private fun updateStats() {
        try {
            val 资产数量 = Prefs.getAssets(this).size
            val 账单记录 = Prefs.getBills(this).size
            val 白名单应用 = Prefs.getAppWhiteList(this).size

            findViewById<TextView>(R.id.tv_stats_assets).text = "资产数量: $资产数量"
            findViewById<TextView>(R.id.tv_stats_bills).text = "账单记录: $账单记录"
            findViewById<TextView>(R.id.tv_stats_whitelist).text = "白名单应用: $白名单应用"
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            BackupManager.REQUEST_CODE_EXPORT -> {
                BackupManager.handleExportResult(this, uri)
            }
            BackupManager.REQUEST_CODE_IMPORT -> {
                BackupManager.handleImportResult(this, uri)
                // 恢复后更新概览
                updateStats()
            }
        }
    }
}
