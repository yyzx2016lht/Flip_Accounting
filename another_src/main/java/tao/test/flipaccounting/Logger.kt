package tao.test.flipaccounting

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val LOG_FILE_NAME = "app_logs.txt"

    fun d(ctx: Context, tag: String, message: String) {
        if (!Prefs.isLoggingEnabled(ctx)) return
        
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$time] [$tag] $message\n"
        
        synchronized(this) {
            try {
                val logFile = getLogFile(ctx)
                logFile.appendText(logLine)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLogFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, LOG_FILE_NAME)
    }

    fun clearLogs(ctx: Context) {
        val logFile = getLogFile(ctx)
        if (logFile.exists()) {
            logFile.delete()
        }
    }
}
