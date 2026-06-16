package com.taostudio.tapaccounting

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import com.google.android.material.switchmaterial.SwitchMaterial
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class LogViewerActivity : AppCompatActivity() {

    companion object {
        private const val MAX_DISPLAY_ENTRIES = 200
        private const val MAX_READ_BYTES = 512 * 1024
        private const val MAX_DISPLAY_CHARS = 200_000
    }

    private lateinit var tvContent: TextView
    private lateinit var tabRuntime: TextView
    private lateinit var tabCrash: TextView
    private lateinit var switchDevFullLogging: SwitchMaterial

    /** true = 显示崩溃日志，false = 显示运行日志 */
    private var showingCrash = false
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        tvContent  = findViewById(R.id.tv_log_content)
        tabRuntime = findViewById(R.id.tab_runtime_log)
        tabCrash   = findViewById(R.id.tab_crash_log)
        switchDevFullLogging = findViewById(R.id.switch_dev_full_logging)
        tvContent.setTextIsSelectable(true)

        val btnShare = findViewById<View>(R.id.btn_header_action)
        val btnClear = findViewById<View>(R.id.btn_header_action_secondary)
        findViewById<ImageView>(R.id.btn_header_action).setColorFilter(0xFF5C6BC0.toInt())
        findViewById<ImageView>(R.id.btn_header_action_secondary).setColorFilter(0xFFE53935.toInt())

        // 若从外部（如崩溃后重启）携带 extra 要求直接跳到崩溃日志
        if (intent.getBooleanExtra("show_crash", false)) {
            showingCrash = true
        }

        updateTabs()
        loadCurrentLog()

        tabRuntime.setOnClickListener {
            showingCrash = false
            updateTabs()
            loadCurrentLog()
        }
        tabCrash.setOnClickListener {
            showingCrash = true
            updateTabs()
            loadCurrentLog()
        }

        btnShare.setOnClickListener { shareLogs() }
        btnClear.setOnClickListener {
            if (showingCrash) Logger.getCrashFile(this).delete()
            else Logger.getLogFile(this).delete()
            loadCurrentLog()
        }

        switchDevFullLogging.isChecked = Prefs.isDeveloperFullLoggingEnabled(this)
        switchDevFullLogging.setOnCheckedChangeListener { _, isChecked ->
            Prefs.setDeveloperFullLoggingEnabled(this, isChecked)
            if (isChecked) {
                Utils.toast(this, getString(R.string.dev_log_on))
            } else {
                Utils.toast(this, getString(R.string.dev_log_off))
            }
        }
    }

    private fun updateTabs() {
        tabRuntime.setTextColor(if (!showingCrash) 0xFF4080FF.toInt() else 0xFF888888.toInt())
        tabRuntime.setTypeface(null, if (!showingCrash) Typeface.BOLD else Typeface.NORMAL)
        tabCrash.setTextColor(if (showingCrash) 0xFFE53935.toInt() else 0xFF888888.toInt())
        tabCrash.setTypeface(null, if (showingCrash) Typeface.BOLD else Typeface.NORMAL)

        // 指示器颜色跟随 Tab
        findViewById<View>(R.id.tab_indicator)
            .setBackgroundColor(if (showingCrash) 0xFFE53935.toInt() else 0xFF4080FF.toInt())
    }

    private fun loadCurrentLog() {
        val file = if (showingCrash) Logger.getCrashFile(this) else Logger.getLogFile(this)
        loadFileContent(tvContent, file,
            emptyHint = if (showingCrash) getString(R.string.no_crash_log_hint) else getString(R.string.no_runtime_log_hint))
    }

    private fun loadFileContent(view: TextView, file: File, emptyHint: String) {
        val generation = ++loadGeneration
        view.text = getString(R.string.loading)
        lifecycleScope.launch {
            val content = withContext(Dispatchers.IO) {
                readDisplayContent(file, emptyHint)
            }
            if (generation == loadGeneration) {
                view.text = content
            }
        }
    }

    private fun readDisplayContent(file: File, emptyHint: String): String {
        if (!file.exists() || file.length() <= 0L) {
            return emptyHint
        }

        return try {
            val fileLength = file.length()
            val omittedHead = fileLength > MAX_READ_BYTES
            val text = readTailText(file, MAX_READ_BYTES)
            val entries = splitLogEntries(text)
            val content = if (entries.isNotEmpty()) {
                entries
                    .takeLast(MAX_DISPLAY_ENTRIES)
                    .asReversed()
                    .joinToString("\n\n")
                    .take(MAX_DISPLAY_CHARS)
            } else {
                text.trim().takeLast(MAX_DISPLAY_CHARS)
            }
            val prefix = if (omittedHead) {
                getString(R.string.log_omitted_hint) + "\n\n"
            } else {
                ""
            }
            prefix + content.ifBlank { emptyHint }
        } catch (e: Exception) {
            getString(R.string.read_failed_fmt, e.message ?: "")
        }
    }

    private fun readTailText(file: File, maxBytes: Int): String {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val bytesToRead = minOf(length, maxBytes.toLong()).toInt()
            val start = length - bytesToRead
            val buffer = ByteArray(bytesToRead)
            raf.seek(start)
            raf.readFully(buffer)
            return buffer.toString(Charsets.UTF_8)
        }
    }

    private fun splitLogEntries(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        val regex = Regex("(?m)^\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}]")
        val matches = regex.findAll(normalized).toList()
        if (matches.isEmpty()) {
            return listOf(normalized.trim()).filter { it.isNotEmpty() }
        }

        val entries = mutableListOf<String>()
        for (index in matches.indices) {
            val start = matches[index].range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else normalized.length
            val entry = normalized.substring(start, end).trim()
            if (entry.isNotEmpty()) {
                entries.add(entry)
            }
        }
        return entries
    }

    private fun shareLogs() {
        val file = if (showingCrash) Logger.getCrashFile(this) else Logger.getLogFile(this)
        if (!file.exists() || file.length() == 0L) {
            Utils.toast(this, getString(R.string.no_log_content))
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, if (showingCrash) getString(R.string.share_crash_log) else getString(R.string.share_runtime_log)))
        } catch (e: Exception) {
            Utils.toast(this, getString(R.string.share_failed))
        }
    }
}

