package tao.test.flipaccounting

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
import tao.test.flipaccounting.data.backup.BackupManager
import tao.test.flipaccounting.data.backup.BackupPinCrypto
import tao.test.flipaccounting.data.backup.CloudBackupConfig
import tao.test.flipaccounting.data.backup.CsvManager
import tao.test.flipaccounting.data.backup.DataExportManager
import tao.test.flipaccounting.data.backup.WebDavClient
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.data.repository.BackupRepository
import tao.test.flipaccounting.logic.CategoryNameNormalizer
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        private const val BACKUP_PREFS = "flip_backup_prefs"
        private const val KEY_BACKUP_TREE_URI = "backup_tree_uri_v1"
        private const val KEY_LAST_BACKUP_PIN = "backup_last_pin_v1"
        private const val LATEST_BACKUP_FILE_NAME = "FlipAccounting_Backup_Latest.bak"

        private const val CLOUD_PREFS = "flip_cloud_backup_prefs"
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
        Utils.toast(this, "默认备份目录已更新")
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
        setupCloudSettingsUi()

        findViewById<MaterialButton>(R.id.btn_do_backup).setOnClickListener {
            val fileName = "FlipAccounting_Backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.bak"
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
                Utils.toast(this, "请先点击“更换默认目录”设置备份位置")
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
            val fileName = "FlipAccounting_Bills_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.csv"
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
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_prompts).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_flip).isChecked = value
            findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked = value
        }

        val hint = findViewById<TextView>(R.id.tv_backup_preset_hint)
        when (preset) {
            BackupPreset.LITE -> {
                setAll(true)
                findViewById<MaterialCheckBox>(R.id.cb_chat_media).isChecked = false
                hint.text = "轻量备份默认不含聊天资源，体积更小，适合日常同步。"
            }
            BackupPreset.FULL -> {
                setAll(true)
                hint.text = "完整备份会包含聊天资源，体积最大，适合做完整归档。"
            }
            BackupPreset.CUSTOM -> {
                hint.text = "自定义模式已开启：请按需勾选更细的模块。"
            }
        }
    }

    private fun updatePinModeHint() {
        findViewById<TextView>(R.id.tv_backup_pin_hint).text = when (currentPinMode()) {
            BackupPinMode.AUTO -> "PIN 自动：首次备份将设置 PIN；覆盖已加密备份时需验证同一 PIN。"
            BackupPinMode.FORCE -> "PIN 强制：只要勾选 AI 核心配置，就要求输入 PIN。"
            BackupPinMode.PLAIN -> "不加密：不会对 API Key 做 PIN 加密，请注意安全风险。"
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
            Utils.toast(this, "云端设置已保存")
        }
        findViewById<MaterialButton>(R.id.btn_test_cloud_connection).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    WebDavClient.testConnection(config)
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "连接成功") }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "连接失败: ${rootCauseMessage(e)}") }
                }
            }
        }
        findViewById<MaterialButton>(R.id.btn_manual_upload).setOnClickListener {
            saveCloudSettings()
            val config = readCloudConfigOrToast() ?: return@setOnClickListener
            val options = collectBackupOptions()
            val modeTag = currentBackupModeTag()
            if (!options.hasAnyModuleSelected()) {
                Utils.toast(this, "请至少选择一个备份模块")
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
                .setTitle("云端保留策略")
                .setMessage(
                    "每台设备保留最近 10 份轻量 + 最近 3 份完整备份。\n" +
                        "超出数量会自动删除最老版本。\n\n" +
                        "当前建议手动同步，避免后台持续增长占用空间。"
                )
                .setPositiveButton("我知道了", null)
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
        findViewById<EditText>(R.id.et_webdav_dir).setText(sp.getString(KEY_WEBDAV_DIR, "FlipAccounting") ?: "FlipAccounting")
        findViewById<EditText>(R.id.et_device_name).setText(sp.getString(KEY_DEVICE_NAME, android.os.Build.MODEL ?: "android") ?: "android")
    }

    private fun readCloudConfigOrToast(): CloudBackupConfig? {
        val url = findViewById<EditText>(R.id.et_webdav_url).text?.toString().orEmpty().trim()
        val user = findViewById<EditText>(R.id.et_webdav_user).text?.toString().orEmpty().trim()
        val pass = findViewById<EditText>(R.id.et_webdav_pass).text?.toString().orEmpty()
        val dir = findViewById<EditText>(R.id.et_webdav_dir).text?.toString().orEmpty().trim().ifBlank { "FlipAccounting" }
        val device = findViewById<EditText>(R.id.et_device_name).text?.toString().orEmpty().trim().ifBlank { "device" }

        return when {
            url.isBlank() -> {
                Utils.toast(this, "请填写 WebDAV 地址")
                null
            }
            user.isBlank() -> {
                Utils.toast(this, "请填写 WebDAV 账号")
                null
            }
            pass.isBlank() -> {
                Utils.toast(this, "请填写 WebDAV 应用密码")
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
                    Utils.toast(this@BackupActivity, "已上传到云端：$fileName")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "云端上传失败: ${rootCauseMessage(e)}")
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
                    Utils.toast(this@BackupActivity, "已下载：${latest.name}")
                }
                showRestoreDialogFromFile(tempFile)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "云端下载失败: ${rootCauseMessage(e)}")
                }
            }
        }
    }

    private fun showCsvQuickActionDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("CSV 工具")
            .setItems(arrayOf("导出 CSV", "导入账单")) { _, which ->
                when (which) {
                    0 -> findViewById<MaterialButton>(R.id.btn_export_csv).performClick()
                    1 -> findViewById<MaterialButton>(R.id.btn_import_csv).performClick()
                }
            }
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performBackup(uri: Uri) {
        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            Utils.toast(this, "请至少选择一个备份模块")
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
            labels += "沿用上次 PIN"
            actions += onUseLastPin
        }
        labels += "设置新 PIN"
        actions += onSetNewPin
        if (allowPlain) {
            labels += "本次不加密"
            actions += onSkipEncryption
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("备份加密方式")
            .setMessage("检测到将备份 AI 核心配置（含 API Key），请选择本次加密方式。")
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("取消", null)
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
            Utils.toast(this, "默认目录不可写，请重新选择")
            return
        }
        val options = collectBackupOptions()
        if (!options.hasAnyModuleSelected()) {
            Utils.toast(this, "请至少选择一个备份模块")
            return
        }
        val existingDoc = targetFolder.findFile(LATEST_BACKUP_FILE_NAME)
        val backupDoc = existingDoc ?: targetFolder.createFile("application/octet-stream", LATEST_BACKUP_FILE_NAME)
        if (backupDoc == null) {
            Utils.toast(this, "无法创建默认备份文件")
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
            backupSettingsAiPrompts = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_prompts).isChecked,
            backupSettingsAiChat = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked,
            backupSettingsBooks = findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked,
            backupSettingsAdvancedRuntime = findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_runtime).isChecked,
            backupSettingsAdvancedFlip = findViewById<MaterialCheckBox>(R.id.cb_settings_advanced_flip).isChecked,
            backupBanners = findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked
        )
    }

    private fun BackupOptions.hasAnyModuleSelected(): Boolean {
        return backupAssets || backupCategories || backupBills || backupRules ||
            backupChatMessages || backupChatMedia || backupSettingsGeneralBasic || backupSettingsGeneralAssets ||
            backupSettingsGeneralCloud || backupSettingsDisplayEntries || backupSettingsDisplayBills ||
            backupSettingsDisplayMultiBill || backupSettingsAiCore || backupSettingsAiPrompts ||
            backupSettingsAiChat || backupSettingsBooks || backupSettingsAdvancedRuntime ||
            backupSettingsAdvancedFlip || backupBanners
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
                        if (settingsPin.isNullOrBlank()) "备份已保存" else "备份已保存，敏感信息已用 PIN 加密"
                    )
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "备份失败: ${rootCauseMessage(e)}")
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
        if (options.backupBills) fullData["bills"]?.let { toBackup["bills"] = it }
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
        if (options.backupSettingsAiPrompts) settingsModules["settings_ai_prompts"]?.let { toBackup["settings_ai_prompts"] = it }
        if (options.backupSettingsAiChat) settingsModules["settings_ai_chat"]?.let { toBackup["settings_ai_chat"] = it }
        if (options.backupSettingsBooks) settingsModules["settings_books"]?.let { toBackup["settings_books"] = it }
        if (options.backupSettingsAdvancedRuntime) settingsModules["settings_advanced_runtime"]?.let { toBackup["settings_advanced_runtime"] = it }
        if (options.backupSettingsAdvancedFlip) settingsModules["settings_advanced_flip"]?.let { toBackup["settings_advanced_flip"] = it }

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
            "默认目录已设置：点击“覆盖”会覆盖同名文件。"
        } else {
            "默认目录未设置：请先点击“更换默认目录”。"
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
                    Utils.toast(this@BackupActivity, "解析备份文件失败: ${rootCauseMessage(e)}")
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
                "settings_ai_prompts" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_prompts),
                "settings_ai_chat" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_chat),
                "settings_books" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_books),
                "settings_advanced_runtime" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_runtime),
                "settings_advanced_flip" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_flip),
                "settings_general" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general_legacy),
                "settings_display" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display_legacy),
                "settings_advanced" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced_legacy),
                "banners" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_banners)
            )

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
                moduleViews.getValue("settings_ai_prompts"),
                moduleViews.getValue("settings_ai_chat"),
                moduleViews.getValue("settings_books"),
                moduleViews.getValue("settings_advanced_runtime"),
                moduleViews.getValue("settings_advanced_flip"),
                moduleViews.getValue("settings_advanced")
            )

            if (!hasModules) {
                Utils.toast(this@BackupActivity, "这个备份文件里没有可恢复的数据模块")
                return@withContext
            }

            val dialog = AlertDialog.Builder(this@BackupActivity)
                .setView(view)
                .setPositiveButton("开始恢复") { _, _ ->
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
                        restoreSettingsAiPrompts = moduleViews.getValue("settings_ai_prompts").isChecked,
                        restoreSettingsAiChat = moduleViews.getValue("settings_ai_chat").isChecked,
                        restoreSettingsBooks = moduleViews.getValue("settings_books").isChecked,
                        restoreSettingsAdvancedRuntime = moduleViews.getValue("settings_advanced_runtime").isChecked,
                        restoreSettingsAdvancedFlip = moduleViews.getValue("settings_advanced_flip").isChecked,
                        restoreSettingsGeneralLegacy = moduleViews.getValue("settings_general").isChecked,
                        restoreSettingsDisplayLegacy = moduleViews.getValue("settings_display").isChecked,
                        restoreSettingsAdvancedLegacy = moduleViews.getValue("settings_advanced").isChecked,
                        restoreBanners = moduleViews.getValue("banners").isChecked
                    )
                    val action: (String?) -> Unit = { pin -> restoreData(dataMap, options, tempFile, pin) }
                    if (options.restoreSettingsAiCore && settingsNeedsPin) promptPinForRestore(action) else action(null)
                }
                .setNegativeButton("取消", null)
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
                    options.restoreSettingsAiPrompts to "settings_ai_prompts",
                    options.restoreSettingsAiChat to "settings_ai_chat",
                    options.restoreSettingsBooks to "settings_books",
                    options.restoreSettingsAdvancedRuntime to "settings_advanced_runtime",
                    options.restoreSettingsAdvancedFlip to "settings_advanced_flip",
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
                    Utils.toast(this@BackupActivity, "数据恢复成功")
                    if (isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                Log.e("BackupActivity", "恢复数据失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "恢复失败: ${rootCauseMessage(e)}")
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
            options.restoreSettingsAdvancedFlip ||
            options.restoreSettingsAdvancedLegacy
        if (!touchedGeneral && !touchedAdvanced) return

        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = if (Prefs.isFlipEnabled(this@BackupActivity)) {
                OverlayService.ACTION_START_FLIP
            } else {
                OverlayService.ACTION_STOP_FLIP
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
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
            hint = "输入4位PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val etPinConfirm = EditText(this).apply {
            hint = "再次输入4位PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        container.addView(etPin)
        container.addView(etPinConfirm)
        val dialog = AlertDialog.Builder(this)
            .setTitle("设置备份 PIN")
            .setMessage("检测到要备份 AI 核心配置，其中包含 API Key。请设置 4 位数字 PIN 用于加密。")
            .setView(container)
            .setPositiveButton("确认") { _, _ ->
                val pin = etPin.text?.toString().orEmpty().trim()
                val confirm = etPinConfirm.text?.toString().orEmpty().trim()
                when {
                    !pin.matches(Regex("^\\d{4}$")) -> Utils.toast(this, "PIN 必须是 4 位数字")
                    pin != confirm -> Utils.toast(this, "两次输入的 PIN 不一致")
                    else -> onPinConfirmed(pin)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun promptPinForRestore(onPinConfirmed: (String) -> Unit) {
        val etPin = EditText(this).apply {
            hint = "输入4位PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("输入备份 PIN")
            .setMessage("该备份中的 API Key 已用 PIN 保护，请输入 4 位数字 PIN。")
            .setView(etPin)
            .setPositiveButton("继续恢复") { _, _ ->
                val pin = etPin.text?.toString().orEmpty().trim()
                if (!pin.matches(Regex("^\\d{4}$"))) {
                    Utils.toast(this, "PIN 必须是 4 位数字")
                } else {
                    onPinConfirmed(pin)
                }
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvExport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = AppDatabase.getDatabase(this@BackupActivity).billDao().getAllBillsList()
                contentResolver.openOutputStream(uri)?.use { CsvManager.export(bills, it) }
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "已导出 ${bills.size} 条账单")
                    if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "导出失败: ${e.message}") }
            }
        }
    }

    private fun performCsvImport(uri: Uri) {
        val books = BookAccountManager.getBookAccounts(this)
        if (books.isEmpty()) {
            Utils.toast(this, "未找到可用账本，请先创建账本")
            return
        }
        val selectedBook = BookAccountManager.getSelectedBook(this, books)
        var selectedIndex = books.indexOf(selectedBook).coerceAtLeast(0)
        val dialog = AlertDialog.Builder(this)
            .setTitle("选择导入账本")
            .setSingleChoiceItems(books.toTypedArray(), selectedIndex) { _, which -> selectedIndex = which }
            .setMessage("当 CSV 中缺少 bookName 时，将导入到你选择的账本。")
            .setPositiveButton("继续") { _, _ ->
                val targetBook = books.getOrNull(selectedIndex) ?: BookAccountManager.getDefaultBook(this)
                performCsvImportInternal(uri, targetBook)
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
    }

    private fun performCsvImportInternal(uri: Uri, targetBook: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = contentResolver.openInputStream(uri)?.use { CsvManager.import(it, fallbackBookName = targetBook) } ?: emptyList()
                if (bills.isEmpty()) {
                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "未解析到有效账单，请检查文件格式") }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    val dialog = AlertDialog.Builder(this@BackupActivity)
                        .setTitle("确认导入")
                        .setMessage("共解析到 ${bills.size} 条账单，导入后将追加到现有数据（不会清空原有账单）。\n\n缺失账本字段将落到：$targetBook\n是否继续？")
                        .setPositiveButton("导入") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val db = AppDatabase.getDatabase(this@BackupActivity)
                                    importCsvBills(db, bills)
                                    withContext(Dispatchers.Main) {
                                        Utils.toast(this@BackupActivity, "成功导入 ${bills.size} 条账单")
                                        if (intent?.getStringExtra(EXTRA_OPEN_SECTION) == SECTION_CSV && isQuickOneShot()) finish()
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "导入失败: ${e.message}") }
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .create()
                    OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, cancelOnTouchOutside = true, useSolidPanelBackground = true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "解析 CSV 失败: ${e.message}") }
            }
        }
    }

    private fun promptPinVerifyForOverwrite(existingBackupUri: Uri, onPinConfirmed: (String) -> Unit) {
        val etPin = EditText(this).apply {
            hint = "输入现有备份PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("验证备份 PIN")
            .setMessage("检测到当前默认备份已启用 PIN 保护。请输入同一 PIN，验证一致后继续覆盖。")
            .setView(etPin)
            .setPositiveButton("验证并继续", null)
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(dialog = dialog, ctx = this@BackupActivity, widthRatio = 0.9f, cancelOnTouchOutside = true, useSolidPanelBackground = true)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = etPin.text?.toString().orEmpty().trim()
            if (!pin.matches(Regex("^\\d{4}$"))) {
                Utils.toast(this, "PIN 必须是 4 位数字")
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val matched = verifyPinForExistingBackup(existingBackupUri, pin)
                withContext(Dispatchers.Main) {
                    if (matched) {
                        dialog.dismiss()
                        onPinConfirmed(pin)
                    } else {
                        Utils.toast(this@BackupActivity, "PIN 与当前备份不一致")
                    }
                }
            }
        }
    }

    private suspend fun importCsvBills(db: AppDatabase, bills: List<Bill>) {
        val importedIdMap = mutableMapOf<Long, Long>()
        val pendingRelations = mutableListOf<Pair<Long, Long>>()
        val importedIds = bills.mapNotNull { it.id.takeIf { id -> id > 0L } }.toSet()

        for (bill in bills) {
            val newId = db.billDao().insertBill(
                bill.copy(
                    id = 0L,
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
    }
}

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
    val backupSettingsAiPrompts: Boolean,
    val backupSettingsAiChat: Boolean,
    val backupSettingsBooks: Boolean,
    val backupSettingsAdvancedRuntime: Boolean,
    val backupSettingsAdvancedFlip: Boolean,
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
    val restoreSettingsAiPrompts: Boolean,
    val restoreSettingsAiChat: Boolean,
    val restoreSettingsBooks: Boolean,
    val restoreSettingsAdvancedRuntime: Boolean,
    val restoreSettingsAdvancedFlip: Boolean,
    val restoreSettingsGeneralLegacy: Boolean,
    val restoreSettingsDisplayLegacy: Boolean,
    val restoreSettingsAdvancedLegacy: Boolean,
    val restoreBanners: Boolean
)


