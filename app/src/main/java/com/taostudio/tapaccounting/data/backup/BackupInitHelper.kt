package com.taostudio.tapaccounting.data.backup

import android.app.Activity
import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.Prefs

/**
 * 首次启动时初始化默认备份目录和自动备份。
 *
 * - 创建 `/storage/emulated/0/TapAccounting/` 目录
 * - 启用自动备份（12 小时间隔，lite 模式）
 * - 目录创建成功后标记已初始化
 *
 * 由于 Application.onCreate() 中没有存储权限，
 * 目录创建可能失败。此时不标记已初始化，
 * 在 BackupHomeActivity.onResume() 中会再次尝试。
 */
object BackupInitHelper {

    private const val TAG = "BackupInitHelper"

    /**
     * Application.onCreate() 中调用。
     * 尝试创建目录并启用自动备份。
     * 仅在目录创建成功时标记已初始化。
     */
    fun initializeIfNeeded(ctx: Context) {
        if (Prefs.isBackupInitialized(ctx)) return

        Log.d(TAG, "First launch detected, initializing default backup")

        // 无论权限如何，都先启用自动备份
        AutoBackupWorker.saveSettings(
            ctx,
            enabled = true,
            intervalHours = 12,
            cloudEnabled = false,
            mode = "lite"
        )

        // 尝试创建默认目录（可能因无权限失败）
        val dirCreated = BackupDefaultDirHelper.ensureDefaultDirExists()
        if (dirCreated) {
            Log.d(TAG, "Default backup directory created: ${BackupDefaultDirHelper.getDefaultBackupDir().absolutePath}")
            Prefs.markBackupInitialized(ctx)
        } else {
            Log.w(TAG, "Storage permission not granted, will retry when Activity opens")
            // 不标记，下次 BackupHomeActivity.onResume() 会再试
        }
    }

    /**
     * BackupHomeActivity / BackupActivity 中调用。
     * 在有 Activity 上下文时请求权限并创建目录。
     */
    fun ensureDefaultDirWithPermission(activity: Activity) {
        // 目录已存在，无需操作
        if (BackupDefaultDirHelper.getDefaultBackupDir().exists()) {
            if (!Prefs.isBackupInitialized(activity)) {
                Prefs.markBackupInitialized(activity)
            }
            return
        }

        // 已有权限但目录不存在（可能被用户删除），重新创建
        if (BackupDefaultDirHelper.hasStoragePermission()) {
            val created = BackupDefaultDirHelper.ensureDefaultDirExists()
            if (created) {
                Log.d(TAG, "Default backup directory re-created")
                if (!Prefs.isBackupInitialized(activity)) {
                    Prefs.markBackupInitialized(activity)
                }
            }
            return
        }

        // 没有权限，请求之
        BackupDefaultDirHelper.requestStoragePermissionIfNeeded(activity)
    }
}
