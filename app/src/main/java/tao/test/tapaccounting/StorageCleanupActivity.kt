package tao.test.tapaccounting

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.util.Locale

class StorageCleanupActivity : AppCompatActivity() {

    private data class GroupStat(val files: List<File>, val count: Int, val bytes: Long)

    private lateinit var cbVoice: MaterialCheckBox
    private lateinit var cbImages: MaterialCheckBox
    private lateinit var cbChatAvatar: MaterialCheckBox
    private lateinit var cbBookCoverBg: MaterialCheckBox
    private lateinit var cbTempCache: MaterialCheckBox
    private lateinit var cbOlder30: MaterialCheckBox

    private lateinit var tvVoice: TextView
    private lateinit var tvImages: TextView
    private lateinit var tvChatAvatar: TextView
    private lateinit var tvBookCoverBg: TextView
    private lateinit var tvTempCache: TextView
    private lateinit var tvTotal: TextView

    private var latestStats: Map<String, GroupStat> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_storage_cleanup)

        findViewById<ImageView>(R.id.btn_back).setOnClickListener { finish() }
        cbVoice = findViewById(R.id.cb_cleanup_voice)
        cbImages = findViewById(R.id.cb_cleanup_images)
        cbChatAvatar = findViewById(R.id.cb_cleanup_chat_avatar)
        cbBookCoverBg = findViewById(R.id.cb_cleanup_book_cover_bg)
        cbTempCache = findViewById(R.id.cb_cleanup_temp_cache)
        cbOlder30 = findViewById(R.id.cb_cleanup_older_30)

        tvVoice = findViewById(R.id.tv_cleanup_voice_stats)
        tvImages = findViewById(R.id.tv_cleanup_images_stats)
        tvChatAvatar = findViewById(R.id.tv_cleanup_chat_avatar_stats)
        tvBookCoverBg = findViewById(R.id.tv_cleanup_book_cover_bg_stats)
        tvTempCache = findViewById(R.id.tv_cleanup_temp_cache_stats)
        tvTotal = findViewById(R.id.tv_cleanup_total_stats)

        findViewById<MaterialButton>(R.id.btn_cleanup_refresh).setOnClickListener { refreshStats() }
        findViewById<MaterialButton>(R.id.btn_cleanup_preview).setOnClickListener { openPreviewPage() }
        findViewById<MaterialButton>(R.id.btn_cleanup_do).setOnClickListener { confirmAndCleanup() }
        cbOlder30.setOnCheckedChangeListener { _, _ -> refreshStats() }

        findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_history_bills).setOnClickListener {
            startActivity(Intent(this, tao.test.tapaccounting.ui.activity.HistoryBillActivity::class.java))
        }

        refreshStats()
        loadHistoryBillsCount()
    }

    private fun loadHistoryBillsCount() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(this@StorageCleanupActivity)
            val count = db.deletedBillDao().getCount()
            withContext(Dispatchers.Main) {
                val tvCount = findViewById<TextView>(R.id.tv_history_bills_count)
                tvCount.text = if (count > 0) "已删除 $count 条账单" else "查看已删除的账单"
            }
        }
    }

    private fun refreshStats() {
        val olderOnly = cbOlder30.isChecked
        lifecycleScope.launch(Dispatchers.IO) {
            val voiceFiles = listFilesInDir(File(filesDir, "chat_voice"))
                .filter { !olderOnly || isOlderThanDays(it, 30) }
            val imageFiles = buildList {
                addAll(listFilesInDir(File(filesDir, "chat_images")).filter { !olderOnly || isOlderThanDays(it, 30) })
                addAll(listFilesInDir(File(filesDir, "chat_pics")).filter { !olderOnly || isOlderThanDays(it, 30) })
            }
            val chatAvatarFiles = buildList {
                addIfFileExists(File(filesDir, "chat_ai_avatar.jpg"), olderOnly)
                addIfFileExists(File(filesDir, "chat_user_avatar.jpg"), olderOnly)
                addIfFileExists(File(filesDir, "chat_ai_avatar.png"), olderOnly)
                addIfFileExists(File(filesDir, "chat_user_avatar.png"), olderOnly)
            }
            val bookCoverBgFiles = buildList {
                addAll(listFilesInDir(File(filesDir, "chat_bg")).filter { !olderOnly || isOlderThanDays(it, 30) })
                addIfFileExists(File(filesDir, "chat_bg.jpg"), olderOnly)
                addIfFileExists(File(filesDir, "chat_bg.png"), olderOnly)
                addIfFileExists(File(filesDir, "chat_bg.webp"), olderOnly)
                addIfFileExists(File(filesDir, "chat_bg.jpeg"), olderOnly)
                addIfFileExists(File(filesDir, "chat_bg.bmp"), olderOnly)
                addIfFileExists(File(filesDir, "chat_bg.gif"), olderOnly)
                addAll(listFilesInDir(File(filesDir, "banners")).filter { !olderOnly || isOlderThanDays(it, 30) })
            }
            val tempCacheFiles = buildList {
                addAll(listFilesInDir(File(cacheDir, "picked_images")))
                addAll(listFilesInDir(File(cacheDir, "avatar_crop")))
                addAll(listFilesInDir(File(cacheDir, "banner_crop")))
                cacheDir.listFiles()
                    ?.filter { it.isFile && shouldCleanupCacheFile(it.name) }
                    ?.forEach { add(it) }
            }

            val stats = mapOf(
                "voice" to toStat(voiceFiles),
                "images" to toStat(imageFiles),
                "chat_avatar" to toStat(chatAvatarFiles),
                "book_cover_bg" to toStat(bookCoverBgFiles),
                "temp_cache" to toStat(tempCacheFiles)
            )

            withContext(Dispatchers.Main) {
                latestStats = stats
                bindStats(stats)
            }
        }
    }

    private fun bindStats(stats: Map<String, GroupStat>) {
        val voice = stats.getValue("voice")
        val images = stats.getValue("images")
        val chatAvatar = stats.getValue("chat_avatar")
        val bookCoverBg = stats.getValue("book_cover_bg")
        val tempCache = stats.getValue("temp_cache")
        val totalCount = voice.count + images.count + chatAvatar.count + bookCoverBg.count + tempCache.count
        val totalBytes = voice.bytes + images.bytes + chatAvatar.bytes + bookCoverBg.bytes + tempCache.bytes

        tvVoice.text = "聊天语音：${voice.count} 个 · ${formatBytes(voice.bytes)}"
        tvImages.text = "聊天图片：${images.count} 个 · ${formatBytes(images.bytes)}"
        tvChatAvatar.text = "聊天头像：${chatAvatar.count} 个 · ${formatBytes(chatAvatar.bytes)}"
        tvBookCoverBg.text = "账本封面与背景：${bookCoverBg.count} 个 · ${formatBytes(bookCoverBg.bytes)}"
        tvTempCache.text = "临时与缓存：${tempCache.count} 个 · ${formatBytes(tempCache.bytes)}"
        tvTotal.text = "可清理总计：$totalCount 个文件 · ${formatBytes(totalBytes)}"
    }

    private fun openPreviewPage() {
        if (!cbVoice.isChecked && !cbImages.isChecked && !cbChatAvatar.isChecked && !cbBookCoverBg.isChecked && !cbTempCache.isChecked) {
            Utils.toast(this, "请先勾选要预览的内容")
            return
        }
        val intent = Intent(this, StoragePreviewActivity::class.java).apply {
            putExtra(StoragePreviewActivity.EXTRA_INCLUDE_VOICE, cbVoice.isChecked)
            putExtra(StoragePreviewActivity.EXTRA_INCLUDE_IMAGES, cbImages.isChecked)
            putExtra(StoragePreviewActivity.EXTRA_INCLUDE_CHAT_AVATAR, cbChatAvatar.isChecked)
            putExtra(StoragePreviewActivity.EXTRA_INCLUDE_BOOK_COVER_BG, cbBookCoverBg.isChecked)
            putExtra(StoragePreviewActivity.EXTRA_INCLUDE_TEMP_CACHE, cbTempCache.isChecked)
            putExtra(StoragePreviewActivity.EXTRA_OLDER_ONLY, cbOlder30.isChecked)
        }
        startActivity(intent)
    }

    private fun confirmAndCleanup() {
        val selected = mutableListOf<GroupStat>()
        if (cbVoice.isChecked) selected += latestStats["voice"] ?: toStat(emptyList())
        if (cbImages.isChecked) selected += latestStats["images"] ?: toStat(emptyList())
        if (cbChatAvatar.isChecked) selected += latestStats["chat_avatar"] ?: toStat(emptyList())
        if (cbBookCoverBg.isChecked) selected += latestStats["book_cover_bg"] ?: toStat(emptyList())
        if (cbTempCache.isChecked) selected += latestStats["temp_cache"] ?: toStat(emptyList())

        val selectedCount = selected.sumOf { it.count }
        val selectedBytes = selected.sumOf { it.bytes }
        if (selectedCount == 0) {
            Utils.toast(this, "请先勾选要清理的内容")
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("确认清理")
            .setMessage("将删除 $selectedCount 个文件，预计释放 ${formatBytes(selectedBytes)}。\n\n不会删除账单、分类、资产和设置等核心数据。")
            .setPositiveButton("开始清理") { _, _ -> runCleanup(selected) }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun runCleanup(selected: List<GroupStat>) {
        lifecycleScope.launch(Dispatchers.IO) {
            var deleted = 0
            selected.flatMap { it.files }
                .distinctBy { it.absolutePath }
                .forEach { file ->
                    if (file.exists() && file.isFile && runCatching { file.delete() }.getOrDefault(false)) {
                        deleted++
                    }
                }
            withContext(Dispatchers.Main) {
                Utils.toast(this@StorageCleanupActivity, "清理完成：已删除 $deleted 个文件")
                refreshStats()
            }
        }
    }

    private fun shouldCleanupCacheFile(name: String): Boolean {
        val n = name.lowercase(Locale.getDefault())
        return n.startsWith("temp_") ||
            n == "voice_input.wav" ||
            n == "chat_voice_input.wav" ||
            n.startsWith("sense_voice") ||
            n.endsWith(".bak")
    }

    private fun listFilesInDir(dir: File): List<File> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.walkTopDown().filter { it.isFile }.toList()
    }

    private fun MutableList<File>.addIfFileExists(file: File, olderOnly: Boolean) {
        if (file.exists() && file.isFile && (!olderOnly || isOlderThanDays(file, 30))) add(file)
    }

    private fun isOlderThanDays(file: File, days: Int): Boolean {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        return file.lastModified() in 1 until cutoff
    }

    private fun toStat(files: List<File>): GroupStat {
        val dedup = files.distinctBy { it.absolutePath }
        val bytes = dedup.sumOf { it.length() }
        return GroupStat(dedup, dedup.size, bytes)
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }
}
