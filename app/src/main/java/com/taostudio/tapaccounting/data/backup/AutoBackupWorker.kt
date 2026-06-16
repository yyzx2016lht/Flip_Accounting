package com.taostudio.tapaccounting.data.backup

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import com.taostudio.tapaccounting.*
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.repository.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "AutoBackupWorker"
        private const val UNIQUE_NAME = "auto_backup_periodic"

        private const val BACKUP_PREFS = "tap_backup_prefs"
        private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
        private const val KEY_AUTO_BACKUP_INTERVAL_HOURS = "auto_backup_interval_hours"
        private const val KEY_AUTO_BACKUP_CLOUD_ENABLED = "auto_backup_cloud_enabled"
        private const val KEY_AUTO_BACKUP_MODE = "auto_backup_mode"
        private const val KEY_LAST_AUTO_BACKUP_TIME = "last_auto_backup_time"
        private const val KEY_LAST_AUTO_BACKUP_RESULT = "last_auto_backup_result"

        private const val CLOUD_PREFS = "tap_cloud_backup_prefs"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_WEBDAV_DIR = "webdav_dir"
        private const val KEY_DEVICE_NAME = "webdav_device_name"

        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri_v1"
        private const val LATEST_BACKUP_FILE_NAME = "TapAccount_Backup_Latest.bak"

        fun schedule(ctx: Context) {
            val sp = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            if (!sp.getBoolean(KEY_AUTO_BACKUP_ENABLED, false)) {
                cancel(ctx)
                return
            }

            val hours = sp.getInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, 12).toLong()
            val interval = hours.coerceIn(1, 72)

            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(interval, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            Log.d(TAG, "Auto backup scheduled, interval=${interval}h")
        }

        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
            Log.d(TAG, "Auto backup cancelled")
        }

        fun isEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_BACKUP_ENABLED, false)

        fun getIntervalHours(ctx: Context): Int =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, 12)

        fun isCloudEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, false)

        fun getBackupMode(ctx: Context): String =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_AUTO_BACKUP_MODE, "lite") ?: "lite"

        fun getLastBackupTime(ctx: Context): Long =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_AUTO_BACKUP_TIME, 0)

        fun getLastBackupResult(ctx: Context): String =
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_AUTO_BACKUP_RESULT, "") ?: ""

        fun saveSettings(
            ctx: Context,
            enabled: Boolean,
            intervalHours: Int,
            cloudEnabled: Boolean,
            mode: String
        ) {
            ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled)
                .putInt(KEY_AUTO_BACKUP_INTERVAL_HOURS, intervalHours.coerceIn(1, 72))
                .putBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, cloudEnabled)
                .putString(KEY_AUTO_BACKUP_MODE, mode)
                .apply()
            schedule(ctx)
        }

        private fun buildLiteOptions(): BackupOptions = BackupOptions(
            backupAssets = true, backupCategories = true, backupBills = true,
            backupRules = true, backupChatMessages = true, backupChatMedia = false,
            backupSettingsGeneralBasic = true, backupSettingsGeneralAssets = true,
            backupSettingsGeneralCloud = true, backupSettingsDisplayEntries = true,
            backupSettingsDisplayBills = true, backupSettingsDisplayMultiBill = true,
            backupSettingsAiCore = false, backupSettingsAiChat = true,
            backupSettingsBooks = true, backupSettingsAdvancedRuntime = true,
            backupBanners = true
        )

        fun buildFullOptions(): BackupOptions = BackupOptions(
            backupAssets = true, backupCategories = true, backupBills = true,
            backupRules = true, backupChatMessages = true, backupChatMedia = true,
            backupSettingsGeneralBasic = true, backupSettingsGeneralAssets = true,
            backupSettingsGeneralCloud = true, backupSettingsDisplayEntries = true,
            backupSettingsDisplayBills = true, backupSettingsDisplayMultiBill = true,
            backupSettingsAiCore = false, backupSettingsAiChat = true,
            backupSettingsBooks = true, backupSettingsAdvancedRuntime = true,
            backupBanners = true
        )

        private fun readCloudConfig(ctx: Context): CloudBackupConfig? {
            val sp = ctx.getSharedPreferences(CLOUD_PREFS, Context.MODE_PRIVATE)
            val url = sp.getString(KEY_WEBDAV_URL, "")?.trim().orEmpty()
            val user = sp.getString(KEY_WEBDAV_USER, "")?.trim().orEmpty()
            val pass = sp.getString(KEY_WEBDAV_PASS, "").orEmpty()
            val dir = sp.getString(KEY_WEBDAV_DIR, "TapAccount")?.trim()?.ifBlank { "TapAccount" } ?: "TapAccount"
            val device = sp.getString(KEY_DEVICE_NAME, "")?.trim()?.ifBlank { android.os.Build.MODEL ?: "device" } ?: (android.os.Build.MODEL ?: "device")
            if (url.isBlank() || user.isBlank() || pass.isBlank()) return null
            return CloudBackupConfig(
                baseUrl = url, username = user, password = pass,
                remoteDir = dir, deviceName = device
            )
        }
    }

    override suspend fun doWork(): Result {
        log("Starting auto backup")
        val ctx = applicationContext

        val sp = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
        val mode = sp.getString(KEY_AUTO_BACKUP_MODE, "lite") ?: "lite"
        val cloudEnabled = sp.getBoolean(KEY_AUTO_BACKUP_CLOUD_ENABLED, false)

        val options = if (mode == "full") buildFullOptions() else buildLiteOptions()

        return try {
            withContext(Dispatchers.IO) {
                val repo = BackupRepository(AppDatabase.getDatabase(ctx))
                val fullData = repo.getFullData()
                val toBackup = linkedMapOf<String, Any>()

                if (options.backupAssets) fullData["assets"]?.let { toBackup["assets"] = it }
                if (options.backupCategories) fullData["categories"]?.let { toBackup["categories"] = it }
                if (options.backupBills) {
                    fullData["bills"]?.let { toBackup["bills"] = it }
                    fullData["deleted_bills"]?.let { toBackup["deleted_bills"] = it }
                    fullData["investment_lots"]?.let { toBackup["investment_lots"] = it }
                }
                if (options.backupRules) fullData["rules"]?.let { toBackup["rules"] = it }
                if (options.backupChatMessages) fullData["chat_messages"]?.let { toBackup["chat_messages"] = it }

                val settingsModules = Prefs.serializeSettingsModules(ctx)
                if (options.backupSettingsGeneralBasic) settingsModules["settings_general_basic"]?.let { toBackup["settings_general_basic"] = it }
                if (options.backupSettingsGeneralAssets) settingsModules["settings_general_assets"]?.let { toBackup["settings_general_assets"] = it }
                if (options.backupSettingsGeneralCloud) settingsModules["settings_general_cloud"]?.let { toBackup["settings_general_cloud"] = it }
                if (options.backupSettingsDisplayEntries) settingsModules["settings_display_entries"]?.let { toBackup["settings_display_entries"] = it }
                if (options.backupSettingsDisplayBills) settingsModules["settings_display_bills"]?.let { toBackup["settings_display_bills"] = it }
                if (options.backupSettingsDisplayMultiBill) settingsModules["settings_display_multibill"]?.let { toBackup["settings_display_multibill"] = it }
                if (options.backupSettingsAiCore) settingsModules["settings_ai_core"]?.let { toBackup["settings_ai_core"] = it }
                if (options.backupSettingsAiChat) settingsModules["settings_ai_chat"]?.let { toBackup["settings_ai_chat"] = it }
                if (options.backupSettingsBooks) settingsModules["settings_books"]?.let { toBackup["settings_books"] = it }
                if (options.backupSettingsAdvancedRuntime) settingsModules["settings_advanced_runtime"]?.let { toBackup["settings_advanced_runtime"] = it }

                val bannerDir = if (options.backupBanners) File(ctx.filesDir, "banners").takeIf { it.isDirectory } else null
                val chatMediaFiles = if (options.backupChatMedia) collectChatMediaFiles(ctx) else emptyMap()

                // Step 1: Local backup to default directory
                val localOk = backupToLocal(ctx, toBackup, bannerDir, chatMediaFiles)

                // Step 2: Cloud backup if enabled and configured
                var cloudOk = true
                var cloudMsg = ""
                if (cloudEnabled) {
                    val config = readCloudConfig(ctx)
                    if (config != null) {
                        try {
                            backupToCloud(ctx, config, toBackup, bannerDir, chatMediaFiles, mode)
                            cloudOk = true
                            cloudMsg = "云端同步成功"
                        } catch (e: Exception) {
                            cloudOk = false
                            cloudMsg = "云端同步失败: ${e.message?.take(50)}"
                            Log.w(TAG, "Cloud backup failed", e)
                        }
                    } else {
                        cloudMsg = "云端未配置，跳过"
                    }
                }

                val now = System.currentTimeMillis()
                val result = buildString {
                    append(if (localOk) "本地备份成功" else "本地备份失败")
                    if (cloudEnabled) append("；$cloudMsg")
                }
                sp.edit()
                    .putLong(KEY_LAST_AUTO_BACKUP_TIME, now)
                    .putString(KEY_LAST_AUTO_BACKUP_RESULT, result)
                    .apply()

                log("Auto backup completed: $result")
                if (localOk) Result.success() else Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auto backup failed", e)
            val sp2 = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            sp2.edit()
                .putLong(KEY_LAST_AUTO_BACKUP_TIME, System.currentTimeMillis())
                .putString(KEY_LAST_AUTO_BACKUP_RESULT, "备份异常: ${e.message?.take(50)}")
                .apply()
            Result.retry()
        }
    }

    private suspend fun backupToLocal(
        ctx: Context,
        toBackup: LinkedHashMap<String, Any>,
        bannerDir: File?,
        chatMediaFiles: Map<String, File>
    ): Boolean {
        val treeUriRaw = ctx.getSharedPreferences(BACKUP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BACKUP_TREE_URI, null)
        if (treeUriRaw.isNullOrBlank()) {
            log("No default backup directory set, skipping local backup")
            return false
        }

        val treeUri = runCatching { android.net.Uri.parse(treeUriRaw) }.getOrNull() ?: return false
        val folder = DocumentFile.fromTreeUri(ctx, treeUri) ?: return false
        if (!folder.exists() || !folder.canWrite()) {
            log("Default backup directory not writable")
            return false
        }

        val tempFile = File(ctx.cacheDir, "auto_backup_temp.bak")
        return try {
            BackupManager.backup(tempFile, toBackup, bannerDir, chatMediaFiles)

            val existing = folder.findFile(LATEST_BACKUP_FILE_NAME)
            val target = existing ?: folder.createFile("application/octet-stream", LATEST_BACKUP_FILE_NAME)
            if (target == null) {
                log("Cannot create backup file in default directory")
                return false
            }

            ctx.contentResolver.openOutputStream(target.uri)?.use { output ->
                tempFile.inputStream().use { it.copyTo(output) }
            }
            log("Local backup saved to ${target.uri}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Local backup failed", e)
            false
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private suspend fun backupToCloud(
        ctx: Context,
        config: CloudBackupConfig,
        toBackup: LinkedHashMap<String, Any>,
        bannerDir: File?,
        chatMediaFiles: Map<String, File>,
        mode: String
    ) {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "backup_${config.deviceName}_${mode}_$ts.bak".replace(Regex("\\s+"), "_")
        val tempFile = File(ctx.cacheDir, "auto_cloud_upload_$ts.bak")
        try {
            BackupManager.backup(tempFile, toBackup, bannerDir, chatMediaFiles)
            WebDavClient.uploadBackup(config, fileName, tempFile.readBytes())
            runCatching { WebDavClient.cleanupBackups(config) }
            log("Cloud backup uploaded: $fileName")
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun collectChatMediaFiles(ctx: Context): Map<String, File> {
        val files = linkedMapOf<String, File>()
        File(ctx.filesDir, "chat_bg").listFiles()?.filter { it.isFile }?.forEach { files["chat_bg/${it.name}"] = it }
        File(ctx.filesDir, "chat_voice").listFiles()?.filter { it.isFile }?.forEach { files["chat_voice/${it.name}"] = it }
        listOf("chat_ai_avatar.jpg", "chat_user_avatar.jpg").forEach { name ->
            val file = File(ctx.filesDir, name)
            if (file.isFile) files[name] = file
        }
        return files
    }

    private fun log(message: String) {
        Log.d(TAG, message)
        Logger.d(applicationContext, TAG, message)
    }
}
