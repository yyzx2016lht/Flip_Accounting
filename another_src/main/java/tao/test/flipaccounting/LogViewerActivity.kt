package tao.test.flipaccounting

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val tvContent = findViewById<TextView>(R.id.tv_log_content)
        val btnShare = findViewById<TextView>(R.id.btn_share_in_viewer)
        val btnClear = findViewById<TextView>(R.id.btn_clear_in_viewer)

        loadLogs(tvContent)

        btnShare.setOnClickListener {
            shareLogs()
        }
        
        btnClear.setOnClickListener {
            Logger.clearLogs(this)
            loadLogs(tvContent)
        }
    }

    private fun loadLogs(view: TextView) {
        CoroutineScope(Dispatchers.IO).launch {
            val logFile = Logger.getLogFile(this@LogViewerActivity)
            val content = if (logFile.exists()) {
                try {
                    val lines = logFile.readLines()
                    if (lines.isEmpty()) {
                        "尚无日志记录"
                    } else {
                        lines.reversed().joinToString("\n")
                    }
                } catch (e: Exception) {
                    "读取日志失败: ${e.message}"
                }
            } else {
                "尚无日志记录"
            }
            withContext(Dispatchers.Main) {
                view.text = if (content.isBlank()) "日志文件为空" else content
            }
        }
    }

    private fun shareLogs() {
        val logFile = Logger.getLogFile(this)
        if (!logFile.exists() || logFile.length() == 0L) {
            Utils.toast(this, "当前没有日志内容")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "tao.test.flipaccounting.fileprovider", logFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享运行日志"))
        } catch (e: Exception) {
            e.printStackTrace()
            Utils.toast(this, "分享失败: ${e.message}")
        }
    }
}
