package com.taostudio.tapaccounting.ui.import

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R

/**
 * 导入迁移引导页。
 * 首次安装时展示，引导用户从其他记账 App 迁移数据。
 */
class ImportOnboardingActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import_onboarding)

        findViewById<android.view.View>(R.id.btn_back)?.setOnClickListener { finish() }

        // 标记已看过引导
        Prefs.setImportOnboardingSeen(this, true)

        setupSourceButtons()
    }

    private fun setupSourceButtons() {
        // 随手记
        findViewById<android.view.View>(R.id.btn_source_suishouji)?.setOnClickListener {
            startCsvImport("suishouji")
        }
        // 钱迹
        findViewById<android.view.View>(R.id.btn_source_qianji)?.setOnClickListener {
            startCsvImport("qianji")
        }
        // 其他 CSV
        findViewById<android.view.View>(R.id.btn_source_csv)?.setOnClickListener {
            startCsvImport("csv")
        }
        // 本应用备份
        findViewById<android.view.View>(R.id.btn_source_backup)?.setOnClickListener {
            // 跳转到 BackupActivity
            val intent = android.content.Intent(this, com.taostudio.tapaccounting.BackupActivity::class.java)
            startActivity(intent)
        }
    }

    private fun startCsvImport(source: String) {
        // 跳转到 BackupActivity 的 CSV 导入
        val intent = android.content.Intent(this, com.taostudio.tapaccounting.BackupActivity::class.java)
        intent.putExtra("import_source", source)
        startActivity(intent)
    }
}
