package com.taostudio.tapaccounting.data.backup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 管理自动备份默认目录 `/storage/emulated/0/TapAccounting/`。
 *
 * - Android 10 及以下：使用 `WRITE_EXTERNAL_STORAGE` 直接创建目录
 * - Android 11+：使用 `MANAGE_EXTERNAL_STORAGE` 创建目录
 */
object BackupDefaultDirHelper {

    private const val FOLDER_NAME = "TapAccounting"
    const val LATEST_BACKUP_FILE_NAME = "TapAccount_Backup_Latest.bak"
    const val REQUEST_CODE_STORAGE_PERMISSION = 7701

    /** 默认备份目录 */
    fun getDefaultBackupDir(): File =
        File(Environment.getExternalStorageDirectory(), FOLDER_NAME)

    /** 默认备份文件（覆盖） */
    fun getDefaultBackupFile(): File =
        File(getDefaultBackupDir(), LATEST_BACKUP_FILE_NAME)

    /** 生成带时间戳的手动备份文件名 */
    fun generateManualBackupFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "TapAccount_Backup_$ts.bak"
    }

    /** 确保默认目录存在，返回是否成功 */
    fun ensureDefaultDirExists(): Boolean {
        val dir = getDefaultBackupDir()
        if (dir.exists()) return true
        if (!hasStoragePermission()) return false
        return dir.mkdirs()
    }

    /** 是否已授予存储权限 */
    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                com.taostudio.tapaccounting.TapApplication.app(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求存储权限。
     * - Android 11+：跳转到系统设置的"所有文件访问"页面
     * - Android 10 及以下：使用标准运行时权限请求
     *
     * @return true 表示已拥有权限，false 表示需要请求（已发起请求或跳转）
     */
    fun requestStoragePermissionIfNeeded(activity: Activity): Boolean {
        if (hasStoragePermission()) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：跳转到 MANAGE_EXTERNAL_STORAGE 设置页
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivityForResult(intent, REQUEST_CODE_STORAGE_PERMISSION)
        } else {
            // Android 10 及以下：标准权限请求
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
        return false
    }
}
