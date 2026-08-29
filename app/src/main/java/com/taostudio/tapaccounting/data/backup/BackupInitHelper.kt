package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.util.Log
import com.taostudio.tapaccounting.Prefs

/**
 * 首次启动时初始化默认备份目录和自动备份。
 *
 * - 创建 `/storage/emulated/0/tapaccounting/files/backups/`
 * - 启用自动备份（12 小时间隔，lite 模式）
 * - 目录创建成功后标记已初始化
 */
object BackupInitHelper {

    private const val TAG = "BackupInitHelper"

    /**
     * Application.onCreate() 中调用。
     * 尝试创建目录并启用自动备份。
     * 仅在目录创建成功时标记已初始化。
     */
    fun initializeIfNeeded(ctx: Context) {
        // App updates may already carry an old initialization flag. Always ensure the canonical
        // directory exists before checking that flag.
        val dirCreated = BackupDefaultDirHelper.ensureDefaultDirExists(ctx)
        if (Prefs.isBackupInitialized(ctx)) {
            if (!dirCreated) Log.w(TAG, "Unable to create backup directory")
            return
        }

        Log.d(TAG, "First launch detected, initializing default backup")

        // 无论权限如何，都先启用自动备份
        AutoBackupWorker.saveSettings(
            ctx,
            enabled = true,
            intervalHours = 12,
            cloudEnabled = false,
            mode = "lite"
        )

        if (dirCreated) {
            Log.d(TAG, "Backup directory created: ${BackupDefaultDirHelper.getDefaultBackupDir(ctx).absolutePath}")
            Prefs.markBackupInitialized(ctx)
        } else {
            Log.w(TAG, "Unable to create backup directory; will retry later")
        }
    }

}
