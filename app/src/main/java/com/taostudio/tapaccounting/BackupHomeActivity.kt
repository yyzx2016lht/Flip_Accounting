package com.taostudio.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.data.backup.AutoBackupWorker
import com.taostudio.tapaccounting.data.backup.BackupDefaultDirHelper
import com.taostudio.tapaccounting.data.backup.BackupInitHelper
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.DatabaseDowngradeHelper
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

        // P0-3: 从其他 App 迁移入口
        findViewById<MaterialButton>(R.id.btn_import_migration)?.setOnClickListener {
            startActivity(Intent(this, com.taostudio.tapaccounting.ui.import.ImportOnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 检查并创建默认备份目录（首次安装或权限恢复后）
        BackupInitHelper.ensureDefaultDirWithPermission(this)
        updateAutoBackupHint()
        checkAndShowDowngradeBackupPrompt()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BackupDefaultDirHelper.REQUEST_CODE_STORAGE_PERMISSION) {
            if (BackupDefaultDirHelper.hasStoragePermission()) {
                BackupInitHelper.ensureDefaultDirWithPermission(this)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == BackupDefaultDirHelper.REQUEST_CODE_STORAGE_PERMISSION) {
            if (BackupDefaultDirHelper.hasStoragePermission()) {
                BackupInitHelper.ensureDefaultDirWithPermission(this)
            }
        }
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

    private fun checkAndShowDowngradeBackupPrompt() {
        if (!DatabaseDowngradeHelper.hasPendingDowngradeBackup(this)) return
        val info = DatabaseDowngradeHelper.getLastBackupInfo(this) ?: return
        val (_, backupVersion) = info
        showDowngradeBackupPrompt(backupVersion)
    }

    private fun showDowngradeBackupPrompt(backupVersion: Int) {
        val canRestore = backupVersion <= AppDatabase.CODE_VERSION
        AlertDialog.Builder(this)
            .setTitle(R.string.downgrade_backup_detected_title)
            .setMessage(getString(R.string.downgrade_backup_detected_message, backupVersion))
            .apply {
                if (canRestore) {
                    setPositiveButton(R.string.downgrade_restore) { _, _ ->
                        val result = DatabaseDowngradeHelper.restoreFromDowngradeBackup(
                            this@BackupHomeActivity, "TapAccount_database", AppDatabase.CODE_VERSION
                        )
                        if (result.success) {
                            Utils.toast(this@BackupHomeActivity, getString(R.string.downgrade_restore_success))
                        } else {
                            Utils.toast(this@BackupHomeActivity, result.message)
                        }
                    }
                } else {
                    setPositiveButton(R.string.downgrade_restore) { _, _ ->
                        Utils.toast(
                            this@BackupHomeActivity,
                            getString(R.string.downgrade_restore_need_upgrade, backupVersion)
                        )
                    }
                }
            }
            .setNeutralButton(R.string.downgrade_dismiss, null)
            .setNegativeButton(R.string.downgrade_dismiss_forever) { _, _ ->
                DatabaseDowngradeHelper.dismissPendingBackup(this)
            }
            .setCancelable(false)
            .show()
    }
}

