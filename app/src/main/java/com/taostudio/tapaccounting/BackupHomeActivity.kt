package com.taostudio.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.data.backup.AutoBackupWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupHomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_home)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_open_local_backup).setOnClickListener {
            startActivity(Intent(this, BackupActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btn_open_cloud_backup).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_CLOUD)
            )
        }

        findViewById<MaterialButton>(R.id.btn_quick_local).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_DO_BACKUP)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_restore).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_RESTORE)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_save_as).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_SAVE_AS)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
        findViewById<MaterialButton>(R.id.btn_quick_csv).setOnClickListener {
            startActivity(
                Intent(this, BackupActivity::class.java)
                    .putExtra(BackupActivity.EXTRA_OPEN_SECTION, BackupActivity.SECTION_CSV)
                    .putExtra(BackupActivity.EXTRA_QUICK_ONESHOT, true)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        updateAutoBackupHint()
    }

    private fun updateAutoBackupHint() {
        val tvHint = findViewById<TextView>(R.id.tv_quick_actions_hint) ?: return
        if (AutoBackupWorker.isEnabled(this)) {
            val lastTime = AutoBackupWorker.getLastBackupTime(this)
            val interval = AutoBackupWorker.getIntervalHours(this)
            val mode = if (AutoBackupWorker.getBackupMode(this) == "full") getString(R.string.backup_full) else getString(R.string.backup_lite)
            val cloud = if (AutoBackupWorker.isCloudEnabled(this)) getString(R.string.auto_backup_cloud_suffix) else ""
            val status = if (lastTime > 0) {
                val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                getString(R.string.home_auto_backup_last_fmt, sdf.format(Date(lastTime)))
            } else {
                getString(R.string.home_auto_backup_waiting)
            }
            tvHint.text = getString(R.string.home_auto_backup_status_fmt, interval, mode, cloud, status)
        } else {
            tvHint.text = getString(R.string.manual_backup_hint)
        }
    }
}

