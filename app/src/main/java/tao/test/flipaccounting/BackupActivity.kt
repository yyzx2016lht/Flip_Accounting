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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
import tao.test.flipaccounting.data.backup.CsvManager
import tao.test.flipaccounting.data.backup.DataExportManager
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.data.repository.BackupRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupActivity : AppCompatActivity() {

    private val backupRepository = BackupRepository(AppDatabase.getDatabase(this))

    private val saveDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::performBackup)
    }

    private val openDocumentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::showRestoreDialog)
    }

    private val saveCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::performCsvExport)
    }

    private val openCsvLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.data?.let(::performCsvImport)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup_new)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }

        findViewById<MaterialButton>(R.id.btn_do_backup).setOnClickListener {
            val fileName = "FlipAccounting_Backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.bak"
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_TITLE, fileName)
            }
            saveDocumentLauncher.launch(intent)
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
    }

    private fun performBackup(uri: Uri) {
        val options = collectBackupOptions()
        val shouldProtectApi = options.backupSettingsAiCore && Prefs.getAiKey(this).isNotBlank()
        if (shouldProtectApi) {
            promptPinSetupForBackup { pin -> performBackupInternal(uri, options, pin) }
        } else {
            performBackupInternal(uri, options, null)
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
            backupSettingsGeneral = findViewById<MaterialCheckBox>(R.id.cb_settings_general).isChecked,
            backupSettingsDisplay = findViewById<MaterialCheckBox>(R.id.cb_settings_display).isChecked,
            backupSettingsAiCore = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_core).isChecked,
            backupSettingsAiPrompts = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_prompts).isChecked,
            backupSettingsAiChat = findViewById<MaterialCheckBox>(R.id.cb_settings_ai_chat).isChecked,
            backupSettingsBooks = findViewById<MaterialCheckBox>(R.id.cb_settings_books).isChecked,
            backupSettingsAdvanced = findViewById<MaterialCheckBox>(R.id.cb_settings_advanced).isChecked,
            backupBanners = findViewById<MaterialCheckBox>(R.id.cb_banners).isChecked
        )
    }

    private fun performBackupInternal(uri: Uri, options: BackupOptions, settingsPin: String?) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fullData = backupRepository.getFullData()
                val toBackup = linkedMapOf<String, Any>()
                if (options.backupAssets) fullData["assets"]?.let { toBackup["assets"] = it }
                if (options.backupCategories) fullData["categories"]?.let { toBackup["categories"] = it }
                if (options.backupBills) fullData["bills"]?.let { toBackup["bills"] = it }
                if (options.backupRules) fullData["rules"]?.let { toBackup["rules"] = it }
                if (options.backupChatMessages) fullData["chat_messages"]?.let { toBackup["chat_messages"] = it }

                val settingsModules = Prefs.serializeSettingsModules(this@BackupActivity)
                if (options.backupSettingsGeneral) settingsModules["settings_general"]?.let { toBackup["settings_general"] = it }
                if (options.backupSettingsDisplay) settingsModules["settings_display"]?.let { toBackup["settings_display"] = it }
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
                if (options.backupSettingsAdvanced) settingsModules["settings_advanced"]?.let { toBackup["settings_advanced"] = it }

                val bannerDir = if (options.backupBanners) File(filesDir, "banners").takeIf { it.isDirectory } else null
                val chatMediaFiles = if (options.backupChatMedia) collectChatMediaFiles() else emptyMap()

                val tempFile = File(cacheDir, "temp_backup.bak")
                BackupManager.backup(tempFile, toBackup, bannerDir, chatMediaFiles)
                contentResolver.openOutputStream(uri)?.use { output -> tempFile.inputStream().use { it.copyTo(output) } }
                tempFile.delete()

                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, if (settingsPin.isNullOrBlank()) "备份已保存" else "备份已保存，API Key 已用 PIN 加密")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "备份失败: ${rootCauseMessage(e)}")
                }
            }
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
                        "settings_general" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_general),
                        "settings_display" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_display),
                        "settings_ai_core" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_core),
                        "settings_ai_prompts" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_prompts),
                        "settings_ai_chat" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_ai_chat),
                        "settings_books" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_books),
                        "settings_advanced" to view.findViewById<MaterialCheckBox>(R.id.cb_restore_settings_advanced),
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
                        moduleViews.getValue("settings_general"),
                        moduleViews.getValue("settings_display"),
                        moduleViews.getValue("settings_ai_core"),
                        moduleViews.getValue("settings_ai_prompts"),
                        moduleViews.getValue("settings_ai_chat"),
                        moduleViews.getValue("settings_books"),
                        moduleViews.getValue("settings_advanced")
                    )

                    if (!hasModules) {
                        Utils.toast(this@BackupActivity, "这个备份文件里没有可恢复的数据模块")
                        return@withContext
                    }

                    AlertDialog.Builder(this@BackupActivity)
                        .setView(view)
                        .setPositiveButton("开始恢复") { _, _ ->
                            val options = RestoreOptions(
                                restoreAssets = moduleViews.getValue("assets").isChecked,
                                restoreCategories = moduleViews.getValue("categories").isChecked,
                                restoreBills = moduleViews.getValue("bills").isChecked,
                                restoreRules = moduleViews.getValue("rules").isChecked,
                                restoreChatMessages = moduleViews.getValue("chat_messages").isChecked,
                                restoreChatMedia = moduleViews.getValue("chat_media").isChecked,
                                restoreSettingsGeneral = moduleViews.getValue("settings_general").isChecked,
                                restoreSettingsDisplay = moduleViews.getValue("settings_display").isChecked,
                                restoreSettingsAiCore = moduleViews.getValue("settings_ai_core").isChecked,
                                restoreSettingsAiPrompts = moduleViews.getValue("settings_ai_prompts").isChecked,
                                restoreSettingsAiChat = moduleViews.getValue("settings_ai_chat").isChecked,
                                restoreSettingsBooks = moduleViews.getValue("settings_books").isChecked,
                                restoreSettingsAdvanced = moduleViews.getValue("settings_advanced").isChecked,
                                restoreBanners = moduleViews.getValue("banners").isChecked
                            )
                            val action: (String?) -> Unit = { pin -> restoreData(dataMap, options, tempFile, pin) }
                            if (options.restoreSettingsAiCore && settingsNeedsPin) promptPinForRestore(action) else action(null)
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                Log.e("BackupActivity", "解析备份文件失败", e)
                withContext(Dispatchers.Main) {
                    Utils.toast(this@BackupActivity, "解析备份文件失败: ${rootCauseMessage(e)}")
                }
            }
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
                    options.restoreSettingsGeneral to "settings_general",
                    options.restoreSettingsDisplay to "settings_display",
                    options.restoreSettingsAiPrompts to "settings_ai_prompts",
                    options.restoreSettingsAiChat to "settings_ai_chat",
                    options.restoreSettingsBooks to "settings_books",
                    options.restoreSettingsAdvanced to "settings_advanced"
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
        val touchedGeneral = options.restoreSettingsGeneral
        val touchedAdvanced = options.restoreSettingsAdvanced
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
        AlertDialog.Builder(this)
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
            .show()
    }

    private fun promptPinForRestore(onPinConfirmed: (String) -> Unit) {
        val etPin = EditText(this).apply {
            hint = "输入4位PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        AlertDialog.Builder(this)
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
            .show()
    }

    private fun performCsvExport(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bills = AppDatabase.getDatabase(this@BackupActivity).billDao().getAllBillsList()
                contentResolver.openOutputStream(uri)?.use { CsvManager.export(bills, it) }
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "已导出 ${bills.size} 条账单") }
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
        AlertDialog.Builder(this)
            .setTitle("选择导入账本")
            .setSingleChoiceItems(books.toTypedArray(), selectedIndex) { _, which -> selectedIndex = which }
            .setMessage("当 CSV 中缺少 bookName 时，将导入到你选择的账本。")
            .setPositiveButton("继续") { _, _ ->
                val targetBook = books.getOrNull(selectedIndex) ?: BookAccountManager.DEFAULT_BOOK
                performCsvImportInternal(uri, targetBook)
            }
            .setNegativeButton("取消", null)
            .show()
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
                    AlertDialog.Builder(this@BackupActivity)
                        .setTitle("确认导入")
                        .setMessage("共解析到 ${bills.size} 条账单，导入后将追加到现有数据（不会清空原有账单）。\n\n缺失账本字段将落到：$targetBook\n是否继续？")
                        .setPositiveButton("导入") { _, _ ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val db = AppDatabase.getDatabase(this@BackupActivity)
                                    importCsvBills(db, bills)
                                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "成功导入 ${bills.size} 条账单") }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "导入失败: ${e.message}") }
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Utils.toast(this@BackupActivity, "解析 CSV 失败: ${e.message}") }
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
    val backupSettingsGeneral: Boolean,
    val backupSettingsDisplay: Boolean,
    val backupSettingsAiCore: Boolean,
    val backupSettingsAiPrompts: Boolean,
    val backupSettingsAiChat: Boolean,
    val backupSettingsBooks: Boolean,
    val backupSettingsAdvanced: Boolean,
    val backupBanners: Boolean
)

data class RestoreOptions(
    val restoreAssets: Boolean,
    val restoreCategories: Boolean,
    val restoreBills: Boolean,
    val restoreRules: Boolean,
    val restoreChatMessages: Boolean,
    val restoreChatMedia: Boolean,
    val restoreSettingsGeneral: Boolean,
    val restoreSettingsDisplay: Boolean,
    val restoreSettingsAiCore: Boolean,
    val restoreSettingsAiPrompts: Boolean,
    val restoreSettingsAiChat: Boolean,
    val restoreSettingsBooks: Boolean,
    val restoreSettingsAdvanced: Boolean,
    val restoreBanners: Boolean
)

