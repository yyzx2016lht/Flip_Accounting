package tao.test.flipaccounting

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class LogViewerActivity : AppCompatActivity() {

    private lateinit var tvContent: TextView
    private lateinit var tabRuntime: TextView
    private lateinit var tabCrash: TextView

    /** true = 显示崩溃日志，false = 显示运行日志 */
    private var showingCrash = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        tvContent  = findViewById(R.id.tv_log_content)
        tabRuntime = findViewById(R.id.tab_runtime_log)
        tabCrash   = findViewById(R.id.tab_crash_log)
        tvContent.setTextIsSelectable(true)

        val btnShare = findViewById<TextView>(R.id.btn_share_in_viewer)
        val btnClear = findViewById<TextView>(R.id.btn_clear_in_viewer)

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
            else Logger.clearLogs(this)
            loadCurrentLog()
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
            emptyHint = if (showingCrash) "尚无崩溃记录 🎉" else "尚无运行日志")
    }

    private fun loadFileContent(view: TextView, file: File, emptyHint: String) {
        view.text = "正在加载…"
        CoroutineScope(Dispatchers.IO).launch {
            val content = if (file.exists() && file.length() > 0) {
                try {
                    // 最新的在最上面；超大文件只取最后 500 行
                    val lines = file.readLines()
                    val tail = if (lines.size > 500) lines.takeLast(500) else lines
                    tail.reversed().joinToString("\n")
                } catch (e: Exception) {
                    "读取失败: ${e.message}"
                }
            } else {
                emptyHint
            }
            withContext(Dispatchers.Main) { view.text = content }
        }
    }

    private fun shareLogs() {
        val file = if (showingCrash) Logger.getCrashFile(this) else Logger.getLogFile(this)
        if (!file.exists() || file.length() == 0L) {
            Utils.toast(this, "当前没有日志内容")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享${if (showingCrash) "崩溃" else "运行"}日志"))
        } catch (e: Exception) {
            Utils.toast(this, "分享失败: ${e.message}")
        }
    }
}
