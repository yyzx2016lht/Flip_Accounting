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

/** Manages the one user-visible backup directory on primary shared storage. */
object BackupDefaultDirHelper {
    const val REQUEST_CODE_STORAGE_PERMISSION = 7701
    const val LATEST_BACKUP_FILE_NAME = "TapAccount_Backup_Latest.bak"
    private const val RELATIVE_BACKUP_PATH = "tapaccounting/files/backups"

    /** `/storage/emulated/0/tapaccounting/files/backups/` on the primary Android user. */
    @Suppress("DEPRECATION", "UNUSED_PARAMETER")
    fun getDefaultBackupDir(context: Context): File =
        File(Environment.getExternalStorageDirectory(), RELATIVE_BACKUP_PATH)

    fun getDefaultBackupFile(context: Context): File =
        File(getDefaultBackupDir(context), LATEST_BACKUP_FILE_NAME)

    fun generateManualBackupFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "TapAccount_Backup_$ts.bak"
    }

    fun ensureDefaultDirExists(context: Context): Boolean {
        if (!hasStoragePermission(context)) return false
        val dir = getDefaultBackupDir(context)
        return dir.isDirectory || dir.mkdirs()
    }

    fun hasStoragePermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    fun requestStoragePermissionIfNeeded(activity: Activity): Boolean {
        if (hasStoragePermission(activity)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.startActivityForResult(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                },
                REQUEST_CODE_STORAGE_PERMISSION
            )
        } else {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_CODE_STORAGE_PERMISSION
            )
        }
        return false
    }
}
