package com.taostudio.tapaccounting

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener
import com.taostudio.tapaccounting.data.backup.AutoBackupWorker
import com.taostudio.tapaccounting.data.backup.BackupManager
import com.taostudio.tapaccounting.data.backup.BackupPinCrypto
import com.taostudio.tapaccounting.data.backup.CloudBackupConfig
import com.taostudio.tapaccounting.data.backup.CsvManager
import com.taostudio.tapaccounting.data.backup.DataExportManager
import com.taostudio.tapaccounting.data.backup.WebDavClient
import com.google.android.material.switchmaterial.SwitchMaterial
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.repository.BackupRepository
import com.taostudio.tapaccounting.data.repository.MergeRestoreResult
import com.taostudio.tapaccounting.logic.CategoryNameNormalizer
import com.taostudio.tapaccounting.ui.dialog.ElegantDatePickerSheet
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复 UI 入口。导出/导入的是 [BackupManager] 的 `.bak` 文件。
 *
 * 降级场景：若需提示用户恢复 [com.taostudio.tapaccounting.data.local.DatabaseDowngradeHelper]
 * 的自动 `.db` 备份，可在此或 [BackupHomeActivity] 检测 `listBackups()` / `getLastBackupInfo()`，
 * 与 `.bak` 流程分开处理。
 */
class BackupActivity : AppCompatActivity() {
    private enum class BackupPreset { LITE, FULL, CUSTOM }
    private enum class BackupPinMode { AUTO, FORCE, PLAIN }

    companion object {
        const val EXTRA_OPEN_SECTION = "backup_open_section"
        const val EXTRA_QUICK_ONESHOT = "backup_quick_oneshot"
        const val SECTION_DO_BACKUP = "do_backup"
        const val SECTION_RESTORE = "restore"
        const val SECTION_SAVE_AS = "save_as"
        const val SECTION_CSV = "csv"
        const val SECTION_CLOUD = "cloud"

        private const val BACKUP_PREFS = "tap_backup_prefs"
        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri_v1"
        private const val KEY_LAST_BACKUP_PIN = "backup_last_pin_v1"
        private const val LATEST_BACKUP_FILE_NAME = "TapAccount_Backup_Latest.bak"

        private const val CLOUD_PREFS = "tap_cloud_backup_prefs"
        private const val KEY_WEBDAV_URL = "webdav_url"
        private const val KEY_WEBDAV_USER = "webdav_user"
        private const val KEY_WEBDAV_PASS = "webdav_pass"
        private const val KEY_WEBDAV_DIR = "webdav_dir"
        private const val KEY_DEVICE_NAME = "webdav_device_name"
    }

    private val backupRepository = BackupRepository(AppDatabase.getDatabase(this))

    private val pickBackupFolderLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        val grantedFlags = result.data?.flags ?: 0
        val persistFlags = grantedFlags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { contentResolver.takePersistableUriPermission(uri, persistFlags) }
            .onFailure { Log.w("BackupActivity", "持久化备份目录权限失败", it) }
        saveBackupTreeUri(uri)
        updateBackupModeHint()
        Utils.toast(this, getString(R.string.backup_dir_updated))
    }

    private val saveBackupAsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::performBackup)
        } else if (isQuickOneShot()) {
            finish()
        }
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::showRestoreDialog)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_RESTORE) {
            finish()
        }
    }

    private val saveCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::performCsvExport)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) {
            finish()
        }
    }

    private val openCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let(::performCsvImport)
        } else if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_new)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        setupBackupPresetUi()
        setupPinModeUi()
        setupAutoBackupUi()
        setupCloudSettingsUi()

        findViewById<MaterialButton>(R.id.btn_do_backup).setOnClickListener {
            val fileName = "TapAccount_Backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.bak"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            saveBackupAsLauncher.launch(intent)
        }
        findViewById<MaterialButton>(R.id.btn_backup_save_as).setOnClickListener {
            val treeUri = getBackupTreeUri()
            if (treeUri == null) {
                Utils.toast(this, getString(R.string.backup_set_dir_first))
            } else {
                performBackupToDefaultTree(treeUri)
            }
        }
        findViewById<MaterialButton>(R.id.btn_change_backup_dir).setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
            pickBackupFolderLauncher.launch(intent)
        }

        findViewById<MaterialButton>(R.id.btn_do_restore).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            openDocumentLauncher.launch(intent)
        }

        findViewById<MaterialButton>(R.id.btn_export_csv).setOnClickListener {
            val fileName = "TapAccount_Bills_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/csv"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            saveCsvLauncher.launch(intent)
        }

        findViewById<MaterialButton>(R.id.btn_import_csv).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            openCsvLauncher.launch(intent)
        }
        findViewById<MaterialButton>(R.id.btn_repair_csv_assets).setOnClickListener {
            repairMissingCsvAssetBindings()
        }
        setupCleanupButtons()
        updateBackupModeHint()
        handleOpenSectionIntent()
    }

    private fun setupBackupPresetUi() {
        val rgPreset = findViewById<RadioGroup>(R.id.rg_backup_preset)
        rgPreset.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                R.id.rb_backup_preset_full -> BackupPreset.FULL
                R.id.rb_backup_preset_custom -> BackupPreset.CUSTOM
                else -> BackupPreset.LITE
            }
            applyBackupPreset(preset)
        }
        applyBackupPreset(BackupPreset.LITE)
    }

    private fun setupPinModeUi() {
        val rgPinMode = findViewById<RadioGroup>(R.id.rg_backup_pin_mode)
        rgPinMode.setOnCheckedChangeListener { _, _ -> updatePinModeHint() }
        updatePinModeHint()
    }

    private fun setupAutoBackupUi() {
        val switchEnabled = findViewById<SwitchMaterial>(R.id.switch_auto_backup)
        val switchCloud = findViewById<SwitchMaterial>(R.id.switch_auto_cloud)
        val groupOptions = findViewById<View>(R.id.group_auto_backup_options)
        val rgInterval = findViewById<RadioGroup>(R.id.rg_auto_backup_interval)
        val rgMode = findViewById<RadioGroup>(R.id.rg_auto_backup_mode)

        // Load saved settings
        val enabled = AutoBackupWorker.isEnabled(this)
        val intervalHours = AutoBackupWorker.getIntervalHours(this)
        val cloudEnabled = AutoBackupWorker.isCloudEnabled(this)
        val mode = AutoBackupWorker.getBackupMode(this)

        switchEnabled.isChecked = enabled
        switchCloud.isChecked = cloudEnabled
        groupOptions.visibility = if (enabled) View.VISIBLE else View.GONE

        when (intervalHours) {
            6 -> rgInterval.check(R.id.rb_interval_6h)
            24 -> rgInterval.check(R.id.rb_interval_24h)
            else -> rgInterval.check(R.id.rb_interval_12h)
        }
        rgMode.check(if (mode == "full") R.id.rb_auto_mode_full else R.id.rb_auto_mode_lite)

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            groupOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
            saveAutoBackupSettings()
        }
        switchCloud.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }
        rgInterval.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }
        rgMode.setOnCheckedChangeListener { _, _ -> saveAutoBackupSettings() }

        updateAutoBackupStatus()
    }

    private fun saveAutoBackupSettings() {
        val enabled = findViewById<SwitchMaterial>(R.id.switch_auto_backup).isChecked
        val cloudEnabled = findViewById<SwitchMaterial>(R.id.switch_auto_cloud).isChecked
        val intervalHours = when (findViewById<RadioGroup>(R.id.rg_auto_backup_interval).checkedRadioButtonId) {
            R.id.rb_interval_6h -> 6
            R.id.rb_interval_24h -> 24
            else -> 12
        }
        val mode = when (findViewById<RadioGroup>(R.id.rg_auto_backup_mode).checkedRadioButtonId) {
            R.id.rb_auto_mode_full -> "full"
            else -> "lite"
        }
        AutoBackupWorker.saveSettings(this, enabled, intervalHours, cloudEnabled, mode)
        updateAutoBackupStatus()
    }

    private fun updateAutoBackupStatus() {
        val tv = findViewById<TextView>(R.id.tv_auto_backup_status)
        if (!AutoBackupWorker.isEnabled(this)) {
            tv.text = getString(R.string.auto_backup_disabled)
            return
        }
        val lastTime = AutoBackupWorker.getLastBackupTime(this)
        val lastResult = AutoBackupWorker.getLastBackupResult(this)
        val interval = AutoBackupWorker.getIntervalHours(this)
        val mode = if (AutoBackupWorker.getBackupMode(this) == "full") getString(R.string.backup_full) else getString(R.string.backup_lite)
        val cloud = if (AutoBackupWorker.isCloudEnabled(this)) getString(R.string.auto_backup_cloud_suffix) else ""

        tv.text = if (lastTime > 0) {
            val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            getString(R.string.backup_status_with_history_fmt, sdf.format(Date(lastTime)), lastResult, interval, mode, cloud)
        } else {
            getString(R.string.backup_status_no_history_fmt, interval, mode, cloud)
        }
    }

    // ── Cleanup bills ──────────────────────────────────────────────

    private fun setupCleanupButtons() {
        findViewById<MaterialButton>(R.id.btn_cleanup_by_date).setOnClickListener {
            showDateRangeCleanupDialog()
        }
        findViewById<MaterialButton>(R.id.btn_cleanup_by_book).setOnClickListener {
            showBookCleanupDialog()
        }
        findViewById<MaterialButton>(R.id.btn_cleanup_all).setOnClickListener {
            showCleanupAllDialog()
        }
    }

    private fun showDateRangeCleanupDialog() {
        val cal = Calendar.getInstance()
        val endDate = cal.timeInMillis
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startDate = cal.timeInMillis

        showDatePicker(startDate, true) { startMs ->
            showDatePicker(endDate, false) { endMs ->
                if (endMs < startMs) {
                    Utils.toast(this, getString(R.string.invalid_date_range))
                    return@showDatePicker
                }
                val endOfDay = endMs + 86400000L - 1
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(this@BackupActivity)
                    val count = db.billDao().countBillsBetweenTimes(startMs, endOfDay)
                    val sum = db.billDao().sumAmountBetweenTimes(startMs, endOfDay)
                    withContext(Dispatchers.Main) {
                        if (count == 0) {
                            Utils.toast(this@BackupActivity, getString(R.string.no_bills_in_range))
                            return@withContext
                        }
                        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                        confirmCleanup(
                            title = getString(R.string.cleanup_date_range_title),
                            message = getString(R.string.cleanup_date_range_message_fmt, sdf.format(Date(startMs)), sdf.format(Date(endMs)), count, String.format("%.2f", sum)),
                            onConfirm = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.billDao().deleteBillsBetweenTimes(startMs, endOfDay)
                                    withContext(Dispatchers.Main) {
                                        Utils.toast(this@BackupActivity, getString(R.string.cleaned_count_fmt, count))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun showBookCleanupDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BackupActivity)
            val books = db.billDao().getAllBookNames()
            withContext(Dispatchers.Main) {
                if (books.isEmpty()) {
                    Utils.toast(this@BackupActivity, getString(R.string.no_book_data))
                    return@withContext
                }
                var selectedIndex = 0
                val dialog = AlertDialog.Builder(this@BackupActivity)
                    .setTitle(R.string.select_book_to_clear)
                    .setSingleChoiceItems(books.toTypedArray(), 0) { _, which -> selectedIndex = which }
                    .setPositiveButton(R.string.next_step) { _, _ ->
                        val book = books[selectedIndex]
                        lifecycleScope.launch(Dispatchers.IO) {
                            val count = db.billDao().countBillsByBookName(book)
                            withContext(Dispatchers.Main) {
                                if (count == 0) {
                                    Utils.toast(this@BackupActivity, getString(R.string.book_no_bills_fmt, book))
                                    return@withContext
                                }
                                confirmCleanup(
                                    title = getString(R.string.clear_book_title_fmt, book),
                                    message = getString(R.string.clear_book_message_fmt, count),
                                    onConfirm = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            db.billDao().deleteAllByBookName(book)
                                            withContext(Dispatchers.Main) {
                                                Utils.toast(this@BackupActivity, getString(R.string.book_cleared_fmt, book, count))
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .create()
                OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
            }
        }
    }

    private fun showCleanupAllDialog() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@BackupActivity)
            val count = db.billDao().getAllBillsList().size
            withContext(Dispatchers.Main) {
                if (count == 0) {
                    Utils.toast(this@BackupActivity, getString(R.string.no_bill_data))
                    return@withContext
                }
                confirmCleanup(
                    title = getString(R.string.clear_all_bills_title),
                    message = getString(R.string.clear_all_bills_message_fmt, count),
                    onConfirm = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.billDao().deleteAll()
                            withContext(Dispatchers.Main) {
                                Utils.toast(this@BackupActivity, getString(R.string.all_cleared_fmt, count))
                            }
                        }
                    }
                )
            }
        }
    }

    private fun showDatePicker(initialMs: Long, isStart: Boolean, onPicked: (Long) -> Unit) {
        ElegantDatePickerSheet.show(
            context = this,
            initialTimeMillis = initialMs,
            onDateSelected = onPicked
        )
    }

    private fun confirmCleanup(title: String, message: String, onConfirm: () -> Unit) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.confirm_cleanup) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun currentPinMode(): BackupPinMode =
        when (findViewById<RadioGroup>(R.id.rg_backup_pin_mode).checkedRadioButtonId) {
            R.id.rb_pin_force -> BackupPinMode.FORCE
            R.id.rb_pin_plain -> BackupPinMode.PLAIN
            else -> BackupPinMode.AUTO
        }

    private fun applyBackupPreset(preset: BackupPreset) {
        val showCustom = preset == BackupPreset.CUSTOM
        val visibility = if (showCustom) View.VISIBLE else View.GONE
        findViewById<View>(R.id.group_backup_core).visibility = visibility
        findViewById<View>(R.id.group_backup_chat).visibility = visibility
        findViewById<View>(R.id.group_backup_settings).visibility = visibility

        fun setAll(value: Boolean) {
            findViewById<MaterialCheckBox>(R.id.cb_assets).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_categories).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_bills).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_rules).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_chat_messages).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_basic).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_assets).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_general_cloud).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_entries).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_bills).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_display_multibill).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_core).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked = value
        }

        val hint = findViewById<TextView>(R.id.tv_backup_preset_hint)
        when (preset) {
            BackupPreset.LITE -> {
                setAll(true)
                findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked = false
                hint.text = getString(R.string.backup_lite_hint)
            }
            BackupPreset.FULL -> {
                setAll(true)
                hint.text = getString(R.string.backup_full_hint)
            }
            BackupPreset.CUSTOM -> {
                hint.text = getString(R.string.backup_custom_hint)
            }
        }
    }

    private fun updatePinModeHint() {
        findViewById<TextView>(R.id.tv_backup_pin_hint).text = when (currentPinMode()) {
            BackupPinMode.AUTO -> getString(R.string.backup_pin_auto_desc)
            BackupPinMode.FORCE -> getString(R.string.backup_pin_force_desc)
            BackupPinMode.PLAIN -> getString(R.string.backup_pin_none_desc)
        }
    }

    private fun handleOpenSectionIntent() {
        when (intent?.getStringExtra(EXTRA_OPEN_SECTION)) {
            SECTION_DO_BACKUP -> {
                findViewById<MaterialButton>(R.id.btn_do_backup).performClick()
            }
            SECTION_RESTORE -> {
                findViewById<MaterialButton>(R.id.btn_do_restore).performClick()
            }
            SECTION_SAVE_AS -> {
                findViewById<MaterialButton>(R.id.btn_do_backup).performClick()
            }
            SECTION_CSV -> {
                if (isQuickOneShot()) {
                    findViewById<MaterialButton>(R.id.btn_import_csv).performClick()
                } else {
                    showCsvQuickActionDialog()
                }
            }
            SECTION_CLOUD -> scrollToSection(R.id.card_cloud_backup)
        }
    }

    private fun isQuickOneShot(): Boolean =
        intent?.getBooleanExtra(EXTRA_QUICK_ONESHOT, false) == true

    private fun scrollToSection(sectionId: Int) {
        val scroll = findViewById<ScrollView>(R.id.backup_scroll)
        val target = findViewById<View>(sectionId)
        scroll.post { scroll.smoothScrollTo(0, target.top) }
    }

    private fun setupCloudSettingsUi() {
        loadCloudSettings()
        findViewById<MaterialButton>(R.id.btn_save_cloud_settings).setOnClickListener {
            saveCloudSettings()
            Utils.toast(this, getString(R.string.cloud_saved))
        }
        findViewById<MaterialButton>(R.id.btn_test_cloud_connection).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    WebDavClient.testConnection(config)
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.connection_success)) }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.connection_failed)) }
                }
            }
        }
        findViewById<MaterialButton>(R.id.btn_manual_upload).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            val options = collectBackupOptions()
            val modeTag = currentBackupModeTag()
            if (!options.hasAnyModuleSelected()) {
                Utils.toast(this, getString(R.string.select_module))
                return@setOnClickListener
            }
            resolvePinForBackup(options, existingBackupEncryptedApi = false, existingBackupUri = null) { pin ->
                if (!pin.isNullOrBlank()) saveLastBackupPin(pin)
                performCloudUpload(config, options, pin, modeTag)
            }
        }
        findViewById<MaterialButton>(R.id.btn_manual_download).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            performCloudDownload(config)
        }
        findViewById<MaterialButton>(R.id.btn_show_cleanup_policy).setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle(R.string.cloud_retention_title)
                .setMessage(getString(R.string.backup_retain_policy_desc))
                .setPositiveButton(R.string.i_know, null)
                .create()
            OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        }
    }

    private fun saveCloudSettings() {
        getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_WEBDAV_URL, findViewById<EditText>(R.id.et_webdav_url).text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_USER, findViewById<EditText>(R.id.et_webdav_user).text?.toString().orEmpty().trim())
            .putString(KEY_WEBDAV_PASS, findViewById<EditText>(R.id.et_webdav_pass).text?.toString().orEmpty())
            .putString(KEY_WEBDAV_DIR, findViewById<EditText>(R.id.et_webdav_dir).text?.toString().orEmpty().trim())
            .putString(KEY_DEVICE_NAME, findViewById<EditText>(R.id.et_device_name).text?.toString().orEmpty().trim())
            .apply()
    }

    private fun loadCloudSettings() {
        val sp = getSharedPreferences(CLOUD_PREFS, MODE_PRIVATE)
        findViewById<EditText>(R.id.et_webdav_url).setText(sp.getString(KEY_WEBDAV_URL, "https://dav.jianguoyun.com/dav/") ?: "")
        findViewById<EditText>(R.id.et_webdav_user).setText(sp.getString(KEY_WEBDAV_USER, "") ?: "")
        findViewById<EditText>(R.id.et_webdav_pass).setText(sp.getString(KEY_WEBDAV_PASS, "") ?: "")
        findViewById<EditText>(R.id.et_webdav_dir).setText(sp.getString(KEY_WEBDAV_DIR, "TapAccount") ?: "TapAccount")
        findViewById<EditText>(R.id.et_device_name).setText(sp.getString(KEY_DEVICE_NAME, android.os.Build.MODEL ?: "android") ?: "android")
    }

    private fun readCloudConfigOrToast(): CloudBackupConfig? {
        val url = findViewById<EditText>(R.id.et_webdav_url).text?.toString().orEmpty().trim()
        val user = findViewById<EditText>(R.id.et_webdav_user).text?.toString().orEmpty().trim()
        val pass = findViewById<EditText>(R.id.et_webdav_pass).text?.toString().orEmpty()
        val dir = findViewById<EditText>(R.id.et_webdav_dir).text?.toString().orEmpty().trim().ifBlank { "TapAccount" }
        val device = findViewById<EditText>(R.id.et_device_name).text?.toString().orEmpty().trim().ifBlank { "device" }

        return when {
            url.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_url))
                null
            }
            user.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_account))
                null
            }
            pass.isBlank() -> {
                Utils.toast(this, getString(R.string.input_webdav_password))
                null
            }
            else -> CloudBackupConfig(
                baseUrl = url,
                username = user,
                password = pass,
                remoteDir = dir,
                deviceName = device
            )
        }
    }

    private fun currentBackupModeTag(): String =
        when (findViewById<RadioGroup>(R.id.rg_backup_preset).checkedRadioButtonId) {
            R.id.rb_backup_preset_full -> "full"
            R.id.rb_backup_preset_custom -> "custom"
            else -> "lite"
        }

    private fun performCloudUpload(config: CloudBackupConfig, options: BackupOptions, settingsPin: String?, modeTag: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "backup_${config.deviceName}_${modeTag}_$ts.bak".replace(Regex("\\s+"), "_")
            val tempFile = File(cacheDir, "temp_cloud_upload_$ts.bak")
            try {
                buildBackupArchiveFile(tempFile, options, settingsPin)
                WebDavClient.uploadBackup(config, fileName, tempFile.readBytes())
                runCatching { WebDavClient.cleanupBackups(config) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.uploaded_fmt, fileName))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.upload_failed))
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    private fun performCloudDownload(config: CloudBackupConfig) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val latest = WebDavClient.findLatestBackup(config)
                    ?: throw IllegalStateException("云端目录中未找到可用备份")
                val bytes = WebDavClient.downloadBackup(config, latest)
                val tempFile = File(cacheDir, "temp_cloud_restore_${latest.timestamp}.bak")
                FileOutputStream(tempFile).use { it.write(bytes) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.downloaded_fmt, latest.name))
                }
                showRestoreDialogFromFile(tempFile)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.download_failed))
                }
            }
        }
    }

    private fun showCsvQuickActionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.csv_tools_title)
            .setItems(arrayOf(getString(R.string.export_csv), getString(R.string.import_bills), getString(R.string.fix_asset_binding))) { _, which ->
                when (which) {
                    0 -> findViewById<MaterialButton>(R.id.btn_export_csv).performClick()
                    1 -> findViewById<MaterialButton>(R.id.btn_import_csv).performClick()
                    2 -> repairMissingCsvAssetBindings()
                }
            }
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performBackup(uri: Uri) {
        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            Utils.toast(this, getString(R.string.select_module))
            return
        }
        resolvePinForBackup(options, existingBackupEncryptedApi = false, existingBackupUri = null) { pin ->
            if (!pin.isNullOrBlank()) saveLastBackupPin(pin)
            performBackupInternal(uri, options, pin)
        }
    }

    private fun resolvePinForBackup(
        options: BackupOptions,
        existingBackupEncryptedApi: Boolean,
        existingBackupUri: Uri?,
        onResolved: (String?) -> Unit
    ) {
        val hasApiKey = Prefs.getAiKey(this).isNotBlank()
        val sensitiveSelected = options.backupSettingsAiCore && hasApiKey
        val mode = currentPinMode()
        val lastPin = getLastBackupPin()

        when {
            mode == BackupPinMode.PLAIN || !sensitiveSelected -> onResolved(null)
            mode == BackupPinMode.FORCE -> {
                if (!lastPin.isNullOrBlank()) {
                    showPinChoiceDialog(
                        allowPlain = false,
                        hasLastPin = true,
                        onUseLastPin = { onResolved(lastPin) },
                        onSetNewPin = { promptPinSetupForBackup(onResolved) },
                        onSkipEncryption = {}
                    )
                } else {
                    promptPinSetupForBackup(onResolved)
                }
            }
            else -> {
                if (existingBackupEncryptedApi && existingBackupUri != null) {
                    promptPinVerifyForOverwrite(existingBackupUri) { pin -> onResolved(pin) }
                } else {
                    promptPinSetupForBackup(onResolved)
                }
            }
        }
    }

    private fun showPinChoiceDialog(
        allowPlain: Boolean,
        hasLastPin: Boolean,
        onUseLastPin: () -> Unit,
        onSetNewPin: () -> Unit,
        onSkipEncryption: () -> Unit
    ) {
        val labels = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        if (hasLastPin) {
            labels += getString(R.string.backup_pin_reuse)
            actions += onUseLastPin
        }
        labels += getString(R.string.backup_pin_set_new)
        actions += onSetNewPin
        if (allowPlain) {
            labels += getString(R.string.backup_pin_skip)
            actions += onSkipEncryption
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.backup_encryption_title)
            .setMessage(R.string.backup_encryption_message)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun getLastBackupPin(): String? =
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).getString(KEY_LAST_BACKUP_PIN, null)

    private fun saveLastBackupPin(pin: String) {
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_BACKUP_PIN, pin).apply()
    }

    private fun performBackupToDefaultTree(treeUri: Uri) {
        val targetFolder = DocumentFile.fromTreeUri(this, treeUri)
        if (targetFolder == null || !targetFolder.exists() || !targetFolder.canWrite()) {
            clearBackupTreeUri()
            updateBackupModeHint()
            Utils.toast(this, getString(R.string.dir_not_writable))
            return
        }
        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            Utils.toast(this, getString(R.string.select_module))
            return
        }
        val existingDoc = targetFolder.findFile(LATEST_BACKUP_FILE_NAME)
        val backupDoc = existingDoc ?: targetFolder.createFile("application/octet-stream", LATEST_BACKUP_FILE_NAME)
        if (backupDoc == null) {
            Utils.toast(this, getString(R.string.create_file_failed))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val existingEncrypted = if (existingDoc != null) hasEncryptedApiInBackupUri(existingDoc.uri) else false
            withContext(Dispatchers.Main) {
                resolvePinForBackup(
                    options = options,
                    existingBackupEncryptedApi = existingEncrypted,
                    existingBackupUri = existingDoc?.uri
                ) { pin ->
                    if (!pin.isNullOrBlank()) saveLastBackupPin(pin)
                    performBackupInternal(backupDoc.uri, options, pin)
                }
            }
        }
    }

    private fun collectBackupOptions(): BackupOptions {
        return BackupOptions(
            backupAssets = findViewById<MaterialCheckBox>(R.id.cb_assets).isChecked,
            backupCategories = findViewById<MaterialCheckBox>(R.id.cb_categories).isChecked,
            backupBills = findViewById<MaterialCheckBox>(R.id.cb_bills).isChecked,
            backupRules = findViewById<MaterialCheckBox>(R.id.cb_rules).isChecked,
            backupChatMessages = findViewById<MaterialCheckBox>(R.id.cb_chat_messages).isChecked,
            backupChatMedia = findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked,
            backupSettingsGeneralBasic = findViewById<MaterialCheckBox>(R.id.cb_settings_general_basic).isChecked,
            backupSettingsGeneralAssets = findViewById<MaterialCheckBox>(R.id.cb_settings_general_assets).isChecked,
            backupSettingsGeneralCloud = findViewById<MaterialCheckBox>(R.id.cb_settings_general_cloud).isChecked,
            backupSettingsDisplayEntries = findViewById<MaterialCheckBox>(R.id.cb_settings_display_entries).isChecked,
            backupSettingsDisplayBills = findViewById<MaterialCheckBox>(R.id.cb_settings_display_bills).isChecked,
            backupSettingsDisplayMultiBill = findViewById<MaterialCheckBox>(R.id.cb_settings_display_multibill).isChecked,
            backupSettingsAiCore = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_core).isChecked,
            backupSettingsAiChat = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked,
            backupSettingsBooks = findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked,
            backupSettingsAdvancedRuntime = findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked,
            backupBanners = findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked
        )
    }

    private fun BackupOptions.hasAnyModuleSelected(): Boolean {
        return backupAssets || backupCategories || backupBills || backupRules ||
            backupChatMessages || backupChatMedia || backupSettingsGeneralBasic || backupSettingsGeneralAssets ||
            backupSettingsGeneralCloud || backupSettingsDisplayEntries || backupSettingsDisplayBills ||
            backupSettingsDisplayMultiBill || backupSettingsAiCore ||
            backupSettingsAiChat || backupSettingsBooks || backupSettingsAdvancedRuntime ||
            backupBanners
    }

    private fun performBackupInternal(uri: Uri, options: BackupOptions, settingsPin: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val tempFile = File(cacheDir, "temp_backup.bak")
            try {
                buildBackupArchiveFile(tempFile, options, settingsPin)
                contentResolver.openOutputStream(uri)?.use { output -> tempFile.inputStream().use { it.copyTo(output) } }

                withContext(Dispatchers.Main) {
                    Utils.toast(
                        this@BackupActivity,
                        if (settingsPin.isNullOrBlank()) getString(R.string.backup_saved) else getString(R.string.backup_saved_encrypted)
                    )
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.backup_failed))
                }
            } finally {
                runCatching { tempFile.delete() }
            }
        }
    }

    private suspend fun buildBackupArchiveFile(outputFile: File, options: BackupOptions, settingsPin: String?) {
        val fullData = backupRepository.getFullData()
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

        val settingsModules = Prefs.serializeSettingsModules(this@BackupActivity)
        if (options.backupSettingsGeneralBasic) settingsModules["settings_general_basic"]?.let { toBackup["settings_general_basic"] = it }
        if (options.backupSettingsGeneralAssets) settingsModules["settings_general_assets"]?.let { toBackup["settings_general_assets"] = it }
        if (options.backupSettingsGeneralCloud) settingsModules["settings_general_cloud"]?.let { toBackup["settings_general_cloud"] = it }
        if (options.backupSettingsDisplayEntries) settingsModules["settings_display_entries"]?.let { toBackup["settings_display_entries"] = it }
        if (options.backupSettingsDisplayBills) settingsModules["settings_display_bills"]?.let { toBackup["settings_display_bills"] = it }
        if (options.backupSettingsDisplayMultiBill) settingsModules["settings_display_multibill"]?.let { toBackup["settings_display_multibill"] = it }
        if (options.backupSettingsAiCore) {
            val raw = settingsModules["settings_ai_core"] ?: "{}"
            val protected = if (!settingsPin.isNullOrBlank()) {
                BackupPinCrypto.encryptApiKeyInSettings(parseSettingsRoot(raw), settingsPin).toString()
            } else raw
            toBackup["settings_ai_core"] = protected
        }
        if (options.backupSettingsAiChat) settingsModules["settings_ai_chat"]?.let { toBackup["settings_ai_chat"] = it }
        if (options.backupSettingsBooks) settingsModules["settings_books"]?.let { toBackup["settings_books"] = it }
        if (options.backupSettingsAdvancedRuntime) settingsModules["settings_advanced_runtime"]?.let { toBackup["settings_advanced_runtime"] = it }

        val bannerDir = if (options.backupBanners) File(filesDir, "banners").takeIf { it.isDirectory } else null
        val chatMediaFiles = if (options.backupChatMedia) collectChatMediaFiles() else emptyMap()
        BackupManager.backup(outputFile, toBackup, bannerDir, chatMediaFiles)
    }

    private fun getBackupTreeUri(): Uri? {
        val raw = getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).getString(KEY_BACKUP_TREE_URI, null)
        return raw?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun saveBackupTreeUri(uri: Uri) {
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit().putString(KEY_BACKUP_TREE_URI, uri.toString()).apply()
    }

    private fun clearBackupTreeUri() {
        getSharedPreferences(BACKUP_PREFS, MODE_PRIVATE).edit().remove(KEY_BACKUP_TREE_URI).apply()
    }

    private fun updateBackupModeHint() {
        val hasDefaultDir = getBackupTreeUri() != null
        findViewById<TextView>(R.id.tv_backup_mode_hint).text = if (hasDefaultDir) {
            getString(R.string.backup_dir_set_hint)
        } else {
            getString(R.string.backup_dir_not_set_hint)
        }
    }

    private fun collectChatMediaFiles(): Map<String, File> {
        val files = linkedMapOf<String, File>()
        File(filesDir, "chat_bg").listFiles()?.filter { it.isFile }?.forEach { files["chat_bg/${it.name}"] = it }
        File(filesDir, "chat_voice").listFiles()?.filter { it.isFile }?.forEach { files["chat_voice/${it.name}"] = it }
        listOf("chat_ai_avatar.jpg", "chat_user_avatar.jpg").forEach { name ->
            val file = File(filesDir, name)
            if (file.isFile) files[name] = file
        }
        return files
    }

    private fun showRestoreDialog(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempFile = File(cacheDir, "temp_restore.bak")
                contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tempFile).use { input.copyTo(it) } }
                showRestoreDialogFromFile(tempFile)
            } catch (e: Exception) {
                Log.e("BackupActivity", "解析备份文件失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.file_corrupted))
                }
            }
        }
    }

    private suspend fun showRestoreDialogFromFile(tempFile: File) {
        val dataMap = BackupManager.restore(tempFile)
        val hasBanners = BackupManager.hasBanners(tempFile)
        val hasChatMedia = BackupManager.hasChatMedia(tempFile)
        val settingsNeedsPin = dataMap["settings_ai_core"]
            ?.let { runCatching { BackupPinCrypto.hasEncryptedApi(parseSettingsRoot(it)) }.getOrDefault(false) }
            ?: false

        withContext(Dispatchers.Main) {
            val view = LayoutInflater.from(this@BackupActivity).inflate(R.layout.dialog_restore_modules, null)
            val moduleViews = mapOf(
                "assets" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_assets),
                "categories" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_categories),
                "bills" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_bills),
                "rules" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_rules),
                "chat_messages" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_chat_messages),
                "chat_media" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_chat_media),
                "settings_general_basic" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_basic),
                "settings_general_assets" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_assets),
                "settings_general_cloud" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_cloud),
                "settings_display_entries" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_entries),
                "settings_display_bills" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_bills),
                "settings_display_multibill" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_multibill),
                "settings_ai_core" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_core),
                "settings_ai_chat" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_chat),
                "settings_books" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_books),
                "settings_advanced_runtime" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_runtime),
                "settings_general" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_legacy),
                "settings_display" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_legacy),
                "settings_advanced" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_legacy),
                "banners" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_banners)
            )
            val aiCoreHint = view.findViewById<TextView>(R.id.tv_restore_settings_ai_core_hint)
            val rgRestoreMode = view.findViewById<RadioGroup>(R.id.rg_restore_mode)
            val tvModeHint = view.findViewById<TextView>(R.id.tv_restore_mode_hint)

            rgRestoreMode.setOnCheckedChangeListener { _, checkedId ->
                tvModeHint.text = if (checkedId == R.id.rb_restore_merge) {
                    getString(R.string.restore_merge_desc)
                } else {
                    getString(R.string.restore_overwrite_desc)
                }
            }

            var hasModules = false
            moduleViews.forEach { (key, checkBox) ->
                val present = when (key) {
                    "chat_media" -> hasChatMedia
                    "banners" -> hasBanners
                    else -> dataMap.containsKey(key)
                }
                checkBox.visibility = if (present) View.VISIBLE else View.GONE
                checkBox.isChecked = present
                hasModules = hasModules || present
            }
            aiCoreHint.visibility = moduleViews.getValue("settings_ai_core").visibility

            val groupCore = view.findViewById<LinearLayout>(R.id.group_restore_core)
            val groupChat = view.findViewById<LinearLayout>(R.id.group_restore_chat)
            val groupSettings = view.findViewById<LinearLayout>(R.id.group_restore_settings)
            groupCore.visibility = visibleIfAny(
                moduleViews.getValue("assets"),
                moduleViews.getValue("categories"),
                moduleViews.getValue("bills"),
                moduleViews.getValue("rules"),
                moduleViews.getValue("banners")
            )
            groupChat.visibility = visibleIfAny(
                moduleViews.getValue("chat_messages"),
                moduleViews.getValue("chat_media")
            )
            groupSettings.visibility = visibleIfAny(
                moduleViews.getValue("settings_general_basic"),
                moduleViews.getValue("settings_general_assets"),
                moduleViews.getValue("settings_general_cloud"),
                moduleViews.getValue("settings_display_entries"),
                moduleViews.getValue("settings_display_bills"),
                moduleViews.getValue("settings_display_multibill"),
                moduleViews.getValue("settings_general"),
                moduleViews.getValue("settings_display"),
                moduleViews.getValue("settings_ai_core"),
                moduleViews.getValue("settings_ai_chat"),
                moduleViews.getValue("settings_books"),
                moduleViews.getValue("settings_advanced_runtime"),
                moduleViews.getValue("settings_advanced")
            )

            if (!hasModules) {
                Utils.toast(this@BackupActivity, getString(R.string.no_restorable_module))
                return@withContext
            }

            val dialog = AlertDialog.Builder(this@BackupActivity)
                .setView(view)
                .setPositiveButton(R.string.start_restore) { _, _ ->
                    val isMerge = rgRestoreMode.checkedRadioButtonId == R.id.rb_restore_merge

                    val options = RestoreOptions(
                        restoreAssets = moduleViews.getValue("assets").isChecked,
                        restoreCategories = moduleViews.getValue("categories").isChecked,
                        restoreBills = moduleViews.getValue("bills").isChecked,
                        restoreRules = moduleViews.getValue("rules").isChecked,
                        restoreChatMessages = moduleViews.getValue("chat_messages").isChecked,
                        restoreChatMedia = moduleViews.getValue("chat_media").isChecked,
                        restoreSettingsGeneralBasic = moduleViews.getValue("settings_general_basic").isChecked,
                        restoreSettingsGeneralAssets = moduleViews.getValue("settings_general_assets").isChecked,
                        restoreSettingsGeneralCloud = moduleViews.getValue("settings_general_cloud").isChecked,
                        restoreSettingsDisplayEntries = moduleViews.getValue("settings_display_entries").isChecked,
                        restoreSettingsDisplayBills = moduleViews.getValue("settings_display_bills").isChecked,
                        restoreSettingsDisplayMultiBill = moduleViews.getValue("settings_display_multibill").isChecked,
                        restoreSettingsAiCore = moduleViews.getValue("settings_ai_core").isChecked,
                        restoreSettingsAiChat = moduleViews.getValue("settings_ai_chat").isChecked,
                        restoreSettingsBooks = moduleViews.getValue("settings_books").isChecked,
                        restoreSettingsAdvancedRuntime = moduleViews.getValue("settings_advanced_runtime").isChecked,
                        restoreSettingsGeneralLegacy = moduleViews.getValue("settings_general").isChecked,
                        restoreSettingsDisplayLegacy = moduleViews.getValue("settings_display").isChecked,
                        restoreSettingsAdvancedLegacy = moduleViews.getValue("settings_advanced").isChecked,
                        restoreBanners = moduleViews.getValue("banners").isChecked
                    )

                    if (isMerge) {
                        val action: (String?) -> Unit = { pin -> mergeRestoreData(dataMap, options, tempFile, pin) }
                        if (options.restoreSettingsAiCore && settingsNeedsPin) promptPinForRestore(action) else action(null)
                    } else {
                        val action: (String?) -> Unit = { pin -> restoreData(dataMap, options, tempFile, pin) }
                        if (options.restoreSettingsAiCore && settingsNeedsPin) promptPinForRestore(action) else action(null)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.92f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        }
    }

    private fun restoreData(dataMap: Map<String, String>, options: RestoreOptions, tempFile: File?, settingsPin: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val aiCoreRoot = if (options.restoreSettingsAiCore) {
                    dataMap["settings_ai_core"]?.let {
                        var root = parseSettingsRoot(it)
                        if (BackupPinCrypto.hasEncryptedApi(root)) {
                            val pin = settingsPin ?: throw IllegalArgumentException("该备份中的 API Key 受 PIN 保护，请输入 4 位 PIN")
                            root = BackupPinCrypto.decryptApiKeyInSettings(root, pin)
                        }
                        root
                    }
                } else null

                backupRepository.restoreFullData(
                    assets = if (options.restoreAssets) dataMap["assets"]?.let { DataExportManager.deserializeAssets(it) } else null,
                    bills = if (options.restoreBills) dataMap["bills"]?.let { DataExportManager.deserializeBills(it) } else null,
                    deletedBills = if (options.restoreBills) dataMap["deleted_bills"]?.let { DataExportManager.deserializeDeletedBills(it) } else null,
                    investmentLots = if (options.restoreBills) dataMap["investment_lots"]?.let { DataExportManager.deserializeInvestmentLots(it) } else null,
                    categories = if (options.restoreCategories) dataMap["categories"]?.let { DataExportManager.deserializeCategories(it) } else null,
                    rules = if (options.restoreRules) dataMap["rules"]?.let { DataExportManager.deserializeAiRules(it) } else null,
                    chatMessages = if (options.restoreChatMessages) dataMap["chat_messages"]?.let { DataExportManager.deserializeChatMessages(it) } else null
                )

                val settingsModules = listOf(
                    options.restoreSettingsGeneralBasic to "settings_general_basic",
                    options.restoreSettingsGeneralAssets to "settings_general_assets",
                    options.restoreSettingsGeneralCloud to "settings_general_cloud",
                    options.restoreSettingsDisplayEntries to "settings_display_entries",
                    options.restoreSettingsDisplayBills to "settings_display_bills",
                    options.restoreSettingsDisplayMultiBill to "settings_display_multibill",
                    options.restoreSettingsAiChat to "settings_ai_chat",
                    options.restoreSettingsBooks to "settings_books",
                    options.restoreSettingsAdvancedRuntime to "settings_advanced_runtime",
                    options.restoreSettingsGeneralLegacy to "settings_general",
                    options.restoreSettingsDisplayLegacy to "settings_display",
                    options.restoreSettingsAdvancedLegacy to "settings_advanced"
                )
                settingsModules.forEach { (enabled, key) ->
                    if (enabled) dataMap[key]?.let { Prefs.importAll(this@BackupActivity, parseSettingsRoot(it)) }
                }
                aiCoreRoot?.let { Prefs.importAll(this@BackupActivity, it) }

                if (options.restoreBanners && tempFile != null && tempFile.exists()) {
                    val bannerDir = File(filesDir, "banners")
                    BackupManager.restoreBanners(tempFile, bannerDir)
                    fixRestoredBannerPaths(bannerDir)
                }

                if (options.restoreChatMedia && tempFile != null && tempFile.exists()) {
                    BackupManager.restoreChatMedia(tempFile, filesDir)
                    fixRestoredChatPreferencePaths()
                    if (options.restoreChatMessages) fixRestoredVoiceMessagePaths()
                }

                syncRestoredRuntimeState(options)

                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.restore_success))
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                Log.e("BackupActivity", "恢复数据失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.restore_failed))
                }
            }
        }
    }

    private fun mergeRestoreData(dataMap: Map<String, String>, options: RestoreOptions, tempFile: File?, settingsPin: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val aiCoreRoot = if (options.restoreSettingsAiCore) {
                    dataMap["settings_ai_core"]?.let {
                        var root = parseSettingsRoot(it)
                        if (BackupPinCrypto.hasEncryptedApi(root)) {
                            val pin = settingsPin ?: throw IllegalArgumentException("该备份中的 API Key 受 PIN 保护，请输入 4 位 PIN")
                            root = BackupPinCrypto.decryptApiKeyInSettings(root, pin)
                        }
                        root
                    }
                } else null

                val result = backupRepository.mergeRestoreFullData(
                    assets = if (options.restoreAssets) dataMap["assets"]?.let { DataExportManager.deserializeAssets(it) } else null,
                    bills = if (options.restoreBills) dataMap["bills"]?.let { DataExportManager.deserializeBills(it) } else null,
                    investmentLots = if (options.restoreBills) dataMap["investment_lots"]?.let { DataExportManager.deserializeInvestmentLots(it) } else null,
                    categories = if (options.restoreCategories) dataMap["categories"]?.let { DataExportManager.deserializeCategories(it) } else null,
                    rules = if (options.restoreRules) dataMap["rules"]?.let { DataExportManager.deserializeAiRules(it) } else null,
                    chatMessages = if (options.restoreChatMessages) dataMap["chat_messages"]?.let { DataExportManager.deserializeChatMessages(it) } else null
                )

                // 设置始终覆盖
                val settingsModules = listOf(
                    options.restoreSettingsGeneralBasic to "settings_general_basic",
                    options.restoreSettingsGeneralAssets to "settings_general_assets",
                    options.restoreSettingsGeneralCloud to "settings_general_cloud",
                    options.restoreSettingsDisplayEntries to "settings_display_entries",
                    options.restoreSettingsDisplayBills to "settings_display_bills",
                    options.restoreSettingsDisplayMultiBill to "settings_display_multibill",
                    options.restoreSettingsAiChat to "settings_ai_chat",
                    options.restoreSettingsBooks to "settings_books",
                    options.restoreSettingsAdvancedRuntime to "settings_advanced_runtime",
                    options.restoreSettingsGeneralLegacy to "settings_general",
                    options.restoreSettingsDisplayLegacy to "settings_display",
                    options.restoreSettingsAdvancedLegacy to "settings_advanced"
                )
                settingsModules.forEach { (enabled, key) ->
                    if (enabled) dataMap[key]?.let { Prefs.importAll(this@BackupActivity, parseSettingsRoot(it)) }
                }
                aiCoreRoot?.let { Prefs.importAll(this@BackupActivity, it) }

                if (options.restoreBanners && tempFile != null && tempFile.exists()) {
                    val bannerDir = File(filesDir, "banners")
                    BackupManager.restoreBanners(tempFile, bannerDir)
                    fixRestoredBannerPaths(bannerDir)
                }

                if (options.restoreChatMedia && tempFile != null && tempFile.exists()) {
                    BackupManager.restoreChatMedia(tempFile, filesDir)
                    fixRestoredChatPreferencePaths()
                    if (options.restoreChatMessages) fixRestoredVoiceMessagePaths()
                }

                syncRestoredRuntimeState(options)

                withContext(Dispatchers.Main) {
                    val msg = buildString {
                        append(getString(R.string.merge_restore_complete))
                        if (result.insertedBills > 0 || result.skippedBills > 0) {
                            append("\n${getString(R.string.merge_restore_bill_fmt, result.insertedBills, result.skippedBills)}")
                        }
                        if (result.insertedAssets > 0 || result.skippedAssets > 0) {
                            append("\n${getString(R.string.merge_restore_asset_fmt, result.insertedAssets, result.skippedAssets)}")
                        }
                        if (result.insertedCategories > 0 || result.skippedCategories > 0) {
                            append("\n${getString(R.string.merge_restore_category_fmt, result.insertedCategories, result.skippedCategories)}")
                        }
                    }
                    Utils.toast(this@BackupActivity, msg)
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                Log.e("BackupActivity", "合并恢复失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.restore_failed))
                }
            }
        }
    }

    private suspend fun fixRestoredVoiceMessagePaths() {
        val dao = AppDatabase.getDatabase(this).chatMessageDao()
        val voiceDir = File(filesDir, "chat_voice")
        val messages = dao.getAll()
        messages.forEach { msg ->
            if (msg.msgType != 2 || msg.content.isBlank()) return@forEach
            runCatching {
                val obj = JSONObject(msg.content)
                val oldPath = obj.optString("audioPath")
                if (oldPath.isBlank()) return@runCatching
                val oldFile = File(oldPath)
                if (oldFile.exists()) return@runCatching
                val restored = File(voiceDir, oldFile.name)
                if (restored.exists()) {
                    obj.put("audioPath", restored.absolutePath)
                    dao.update(msg.copy(content = obj.toString()))
                }
            }
        }
    }

    private fun fixRestoredChatPreferencePaths() {
        val aiAvatar = File(filesDir, "chat_ai_avatar.jpg")
        if (aiAvatar.exists()) Prefs.setAiChatAvatarPath(this, aiAvatar.absolutePath)
        val userAvatar = File(filesDir, "chat_user_avatar.jpg")
        if (userAvatar.exists()) Prefs.setUserChatAvatarPath(this, userAvatar.absolutePath)
        val bgPath = Prefs.getAiChatBgPath(this)
        if (bgPath.isNotBlank()) {
            val bgFile = File(bgPath)
            val restored = File(File(filesDir, "chat_bg"), bgFile.name)
            if (!bgFile.exists() && restored.exists()) {
                Prefs.setAiChatBgPath(this, restored.absolutePath)
            }
        }
    }

    private fun fixRestoredBannerPaths(bannerDir: File) {
        val books = BookAccountManager.getBookAccounts(this)
        books.forEach { book ->
            val currentPath = BookAccountManager.getBookBannerPath(this, book)
            if (!currentPath.isNullOrEmpty()) {
                val currentFile = File(currentPath)
                val restoredFile = File(bannerDir, currentFile.name)
                if (!currentFile.exists() && restoredFile.exists()) {
                    BookAccountManager.setBookBannerPath(this, book, restoredFile.absolutePath)
                }
            }
        }
    }

    private fun syncRestoredRuntimeState(options: RestoreOptions) {
        val touchedGeneral = options.restoreSettingsGeneralBasic ||
            options.restoreSettingsGeneralAssets ||
            options.restoreSettingsGeneralCloud ||
            options.restoreSettingsGeneralLegacy
        val touchedAdvanced = options.restoreSettingsAdvancedRuntime ||
            options.restoreSettingsAdvancedLegacy
        if (!touchedGeneral && !touchedAdvanced) return

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = if (Prefs.isDoubleTapEnabled(this@BackupActivity)) {
                OverlayService.ACTION_START_DOUBLE_TAP
            } else {
                OverlayService.ACTION_STOP_DOUBLE_TAP
            }
        }

        OverlayService.startCompat(this, serviceIntent)
    }

    private fun rootCauseMessage(e: Throwable): String {
        var cur: Throwable = e
        while (cur.cause != null) cur = cur.cause!!
        return cur.message?.takeIf { it.isNotBlank() } ?: e.message?.takeIf { it.isNotBlank() } ?: cur::class.java.simpleName
    }

    private fun parseSettingsRoot(payload: String): JSONObject {
        val parsed = JSONTokener(payload).nextValue()
        return when (parsed) {
            is JSONObject -> parsed
            is String -> JSONObject(parsed)
            else -> throw org.json.JSONException("Unsupported settings payload type: ${parsed?.javaClass?.name}")
        }
    }

    private suspend fun hasEncryptedApiInBackupUri(uri: Uri): Boolean {
        val tempFile = File(cacheDir, "temp_pin_check_${System.currentTimeMillis()}.bak")
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false
            val dataMap = BackupManager.restore(tempFile)
            val raw = dataMap["settings_ai_core"] ?: return false
            val root = runCatching { parseSettingsRoot(raw) }.getOrNull() ?: return false
            runCatching { BackupPinCrypto.hasEncryptedApi(root) }.getOrDefault(false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private suspend fun verifyPinForExistingBackup(uri: Uri, pin: String): Boolean {
        val tempFile = File(cacheDir, "temp_pin_verify_${System.currentTimeMillis()}.bak")
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false
            val dataMap = BackupManager.restore(tempFile)
            val raw = dataMap["settings_ai_core"] ?: return false
            val root = runCatching { parseSettingsRoot(raw) }.getOrNull() ?: return false
            if (!BackupPinCrypto.hasEncryptedApi(root)) return false
            runCatching {
                BackupPinCrypto.decryptApiKeyInSettings(root, pin)
                true
            }.getOrDefault(false)
        } catch (_: Exception) {
            false
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun visibleIfAny(vararg views: View): Int =
        if (views.any { it.visibility == View.VISIBLE }) View.VISIBLE else View.GONE

    private fun promptPinSetupForBackup(onPinConfirmed: (String) -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
        }
        val etPin = EditText(this).apply {
            hint = getString(R.string.input_4digit_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val etPinConfirm = EditText(this).apply {
            hint = getString(R.string.confirm_4digit_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        container.addView(etPin)
        container.addView(etPinConfirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.set_backup_pin)
            .setMessage(getString(R.string.backup_pin_setup_prompt))
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val pin = etPin.text?.toString().orEmpty().trim()
                val confirm = etPinConfirm.text?.toString().orEmpty().trim()
                when {
                    !pin.matches(Regex("^\\d{4}$")) -> Utils.toast(this, getString(R.string.pin_must_4digit))
                    pin != confirm -> Utils.toast(this, getString(R.string.pin_mismatch))
                    else -> onPinConfirmed(pin)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun promptPinForRestore(onPinConfirmed: (String) -> Unit) {
        val etPin = EditText(this).apply {
            hint = getString(R.string.input_4digit_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.input_backup_pin)
            .setMessage(getString(R.string.backup_pin_verify_prompt))
            .setView(etPin)
            .setPositiveButton(R.string.continue_restore) { _, _ ->
                val pin = etPin.text?.toString().orEmpty().trim()
                if (!pin.matches(Regex("^\\d{4}$"))) {
                    Utils.toast(this, getString(R.string.pin_must_4digit))
                } else {
                    onPinConfirmed(pin)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvExport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = AppDatabase.getDatabase(this@BackupActivity).billDao().getAllBillsList()
                contentResolver.openOutputStream(uri)?.use { CsvManager.export(bills, it) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.exported_bills_fmt, bills.size))
                    if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.export_failed)) }
            }
        }
    }

    private fun performCsvImport(uri: Uri) {
        val books = BookAccountManager.getBookAccounts(this)
        if (books.isEmpty()) {
            Utils.toast(this, getString(R.string.no_available_book))
            return
        }
        val selectedBook = BookAccountManager.getSelectedBook(this, books)
        var selectedIndex = books.indexOf(selectedBook).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_import_book)
            .setSingleChoiceItems(books.toTypedArray(), selectedIndex) { _, which -> selectedIndex = which }
            .setMessage(R.string.csv_import_hint)
            .setPositiveButton(R.string.continue_btn) { _, _ ->
                val targetBook = books.getOrNull(selectedIndex) ?: BookAccountManager.getDefaultBook(this)
                performCsvImportInternal(uri, targetBook)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvImportInternal(uri: Uri, targetBook: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = contentResolver.openInputStream(uri)?.use { CsvManager.import(it, fallbackBookName = targetBook) } ?: emptyList()
                if (bills.isEmpty()) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.parse_failed)) }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val dialog = AlertDialog.Builder(this@BackupActivity)
                        .setTitle(R.string.confirm_import_title)
                        .setMessage(getString(R.string.confirm_import_message, bills.size, targetBook))
                        .setPositiveButton(R.string.import_btn) { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val db = AppDatabase.getDatabase(this@BackupActivity)
                                    val importResult = importCsvBills(db, bills)
                                    withContext(Dispatchers.Main) {
                                        val assetHint = if (importResult.createdAssetNames.isNotEmpty()) {
                                            getString(R.string.import_asset_hint_fmt, importResult.createdAssetNames.size)
                                        } else {
                                            ""
                                        }
                                        Utils.toast(this@BackupActivity, getString(R.string.import_success_fmt, importResult.billCount, assetHint))

                                        // P0-3: CSV 导入后若有临时资产，跳转审查页
                                        if (importResult.createdAssetNames.isNotEmpty()) {
                                            startActivity(Intent(this@BackupActivity, com.taostudio.tapaccounting.ui.import.ImportReviewActivity::class.java))
                                        }

                                        if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.import_failed)) }
                                }
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, getString(R.string.csv_parse_failed)) }
            }
        }
    }

    private fun promptPinVerifyForOverwrite(existingBackupUri: Uri, onPinConfirmed: (String) -> Unit) {
        val etPin = EditText(this).apply {
            hint = getString(R.string.input_existing_pin)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.verify_backup_pin)
            .setMessage(getString(R.string.backup_pin_match_prompt))
            .setView(etPin)
            .setPositiveButton(R.string.verify_and_continue, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = etPin.text?.toString().orEmpty().trim()
            if (!pin.matches(Regex("^\\d{4}$"))) {
                Utils.toast(this, getString(R.string.pin_must_4digit))
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val matched = verifyPinForExistingBackup(existingBackupUri, pin)
                withContext(Dispatchers.Main) {
                    if (matched) {
                        dialog.dismiss()
                        onPinConfirmed(pin)
                    } else {
                        Utils.toast(this@BackupActivity, getString(R.string.pin_not_match))
                    }
                }
            }
        }
    }

    private suspend fun importCsvBills(db: AppDatabase, bills: List<Bill>): CsvImportResult {
        val importedIdMap = mutableMapOf<Long, Long>()
        val pendingRelations = mutableListOf<Pair<Long, Long>>()
        val importedIds = bills.mapNotNull { it.id.takeIf { id -> id > 0L } }.toSet()
        val assetResolution = ensureCsvImportAssets(db, bills)
        val assetByName = assetResolution.assetByName

        for (bill in bills) {
            val accountAsset = assetByName[normalizeAssetImportName(bill.accountName)]
            val toAccountAsset = assetByName[normalizeAssetImportName(bill.toAccountName)]
            val newId = db.billDao().insertBill(
                bill.copy(
                    id = 0L,
                    accountId = accountAsset?.id,
                    toAccountId = toAccountAsset?.id,
                    categoryName = CategoryNameNormalizer.normalizeForStorage(bill.categoryName),
                    relatedBillId = null
                )
            )
            if (bill.id > 0L) {
                importedIdMap[bill.id] = newId
            }
            val oldRelatedId = bill.relatedBillId
            if (oldRelatedId != null && oldRelatedId in importedIds) {
                pendingRelations += newId to oldRelatedId
            }
        }

        pendingRelations.forEach { (newBillId, oldRelatedId) ->
            val mappedRelatedId = importedIdMap[oldRelatedId] ?: return@forEach
            val savedBill = db.billDao().getBillById(newBillId) ?: return@forEach
            db.billDao().updateBill(savedBill.copy(relatedBillId = mappedRelatedId))
        }

        db.billDao().backfillAssetLinksByName()
        return CsvImportResult(
            billCount = bills.size,
            createdAssetNames = assetResolution.createdAssetNames
        )
    }

    private fun repairMissingCsvAssetBindings() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(this@BackupActivity)
                val result = repairMissingAssetBindings(db)
                withContext(Dispatchers.Main) {
                    val message = if (result.updatedBillCount == 0 && result.createdAssetNames.isEmpty()) {
                        getString(R.string.fix_binding_none)
                    } else {
                        getString(R.string.fix_binding_result_fmt, result.updatedBillCount) +
                            if (result.createdAssetNames.isNotEmpty()) {
                                getString(R.string.fix_binding_created_assets_fmt, result.createdAssetNames.size)
                            } else {
                                ""
                            }
                    }
                    Utils.toast(this@BackupActivity, message)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, getString(R.string.fix_failed))
                }
            }
        }
    }

    private suspend fun repairMissingAssetBindings(db: AppDatabase): CsvAssetRepairResult {
        val bills = db.billDao().getAllBillsList().filter { bill ->
            (normalizeAssetImportName(bill.accountName).isNotBlank() && bill.accountId == null) ||
                (normalizeAssetImportName(bill.toAccountName).isNotBlank() && bill.toAccountId == null)
        }
        if (bills.isEmpty()) return CsvAssetRepairResult()

        val assetResolution = ensureCsvImportAssets(db, bills)
        var updatedCount = 0
        bills.forEach { bill ->
            val accountAsset = assetResolution.assetByName[normalizeAssetImportName(bill.accountName)]
            val toAccountAsset = assetResolution.assetByName[normalizeAssetImportName(bill.toAccountName)]
            val updated = bill.copy(
                accountId = bill.accountId ?: accountAsset?.id,
                toAccountId = bill.toAccountId ?: toAccountAsset?.id
            )
            if (updated.accountId != bill.accountId || updated.toAccountId != bill.toAccountId) {
                db.billDao().updateBill(updated)
                updatedCount += 1
            }
        }

        return CsvAssetRepairResult(
            updatedBillCount = updatedCount,
            createdAssetNames = assetResolution.createdAssetNames
        )
    }

    private suspend fun ensureCsvImportAssets(db: AppDatabase, bills: List<Bill>): CsvImportAssetResolution {
        val assetDao = db.assetDao()
        val existing = assetDao.getAllAssetsList().associateBy { normalizeAssetImportName(it.name) }.toMutableMap()
        val createdAssetNames = mutableListOf<String>()
        val required = linkedMapOf<String, String>()
        bills.forEach { bill ->
            listOf(bill.accountName to bill.currency, bill.toAccountName to bill.currency).forEach { (rawName, currency) ->
                val name = normalizeAssetImportName(rawName)
                if (name.isBlank() || existing.containsKey(name) || required.containsKey(name)) return@forEach
                required[name] = currency.ifBlank { "CNY" }
            }
        }

        var nextSortOrder = (assetDao.getMaxSortOrderInCategory(Asset.CATEGORY_FUND) ?: 0) + 10
        required.forEach { (name, currency) ->
            val category = inferImportedAssetCategory(name)
            val sortOrder = if (category == Asset.CATEGORY_FUND) {
                nextSortOrder.also { nextSortOrder += 10 }
            } else {
                (assetDao.getMaxSortOrderInCategory(category) ?: 0) + 10
            }
            val asset = Asset(
                name = name,
                type = "CSV导入待确认",
                balance = 0.0,
                initialBalance = 0.0,
                currency = currency,
                remark = CSV_TEMP_ASSET_MARKER,
                includeInNetAsset = false,
                sortOrder = sortOrder,
                pickerSortOrder = sortOrder,
                assetCategory = category
            )
            val newId = assetDao.insertAsset(asset)
            existing[name] = asset.copy(id = newId)
            createdAssetNames += name
        }

        return CsvImportAssetResolution(existing, createdAssetNames)
    }

    private fun normalizeAssetImportName(raw: String): String {
        val name = raw.trim()
        return when {
            name.isBlank() -> ""
            name == "选择资产" || name == "未知账户" -> ""
            else -> name
        }
    }

    private fun inferImportedAssetCategory(name: String): String {
        return if (
            name.contains("信用卡") ||
            name.contains("花呗") ||
            name.contains("白条") ||
            name.contains("美团月付")
        ) {
            Asset.CATEGORY_CREDIT_CARD
        } else {
            Asset.CATEGORY_FUND
        }
    }
}

private const val CSV_TEMP_ASSET_MARKER = "CSV导入自动创建，请检查资产类型、币种和余额"

private data class CsvImportResult(
    val billCount: Int,
    val createdAssetNames: List<String>
)

private data class CsvImportAssetResolution(
    val assetByName: Map<String, Asset>,
    val createdAssetNames: List<String>
)

private data class CsvAssetRepairResult(
    val updatedBillCount: Int = 0,
    val createdAssetNames: List<String> = emptyList()
)

data class BackupOptions(
    val backupAssets: Boolean,
    val backupCategories: Boolean,
    val backupBills: Boolean,
    val backupRules: Boolean,
    val backupChatMessages: Boolean,
    val backupChatMedia: Boolean,
    val backupSettingsGeneralBasic: Boolean,
    val backupSettingsGeneralAssets: Boolean,
    val backupSettingsGeneralCloud: Boolean,
    val backupSettingsDisplayEntries: Boolean,
    val backupSettingsDisplayBills: Boolean,
    val backupSettingsDisplayMultiBill: Boolean,
    val backupSettingsAiCore: Boolean,
    val backupSettingsAiChat: Boolean,
    val backupSettingsBooks: Boolean,
    val backupSettingsAdvancedRuntime: Boolean,
    val backupBanners: Boolean
)

data class RestoreOptions(
    val restoreAssets: Boolean,
    val restoreCategories: Boolean,
    val restoreBills: Boolean,
    val restoreRules: Boolean,
    val restoreChatMessages: Boolean,
    val restoreChatMedia: Boolean,
    val restoreSettingsGeneralBasic: Boolean,
    val restoreSettingsGeneralAssets: Boolean,
    val restoreSettingsGeneralCloud: Boolean,
    val restoreSettingsDisplayEntries: Boolean,
    val restoreSettingsDisplayBills: Boolean,
    val restoreSettingsDisplayMultiBill: Boolean,
    val restoreSettingsAiCore: Boolean,
    val restoreSettingsAiChat: Boolean,
    val restoreSettingsBooks: Boolean,
    val restoreSettingsAdvancedRuntime: Boolean,
    val restoreSettingsGeneralLegacy: Boolean,
    val restoreSettingsDisplayLegacy: Boolean,
    val restoreSettingsAdvancedLegacy: Boolean,
    val restoreBanners: Boolean
)

