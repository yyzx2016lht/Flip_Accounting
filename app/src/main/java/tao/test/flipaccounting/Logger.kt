package tao.test.flipaccounting

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

object Logger {
    private const val LOG_FILE_NAME = "app_logs.txt"
    private const val CRASH_FILE_NAME = "crash_logs.txt"
    /** 单个日志文件最大 2 MB，超过则轮转 */
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L

    private fun isDebuggable(ctx: Context): Boolean {
        return (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    fun d(ctx: Context, tag: String, message: String) {
        val shouldWriteLogcat = isDebuggable(ctx)
        val shouldWriteFile = Prefs.isLoggingEnabled(ctx)

        if (!shouldWriteLogcat && !shouldWriteFile) return
        if (shouldWriteLogcat) Log.d(tag, message)
        if (shouldWriteFile) writeLog(ctx, getLogFile(ctx), "[$tag] $message")
    }

    /** 写崩溃日志——不依赖 Prefs，崩溃必须记录 */
    fun crash(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val entry = "[CRASH] Thread=${thread.name}\n$sw"
        Log.e("Crash", entry)
        writeLog(ctx, getCrashFile(ctx), entry)
    }

    private fun writeLog(ctx: Context, file: File, content: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logLine = "[$time] $content\n"
        synchronized(this) {
            try {
                // 轮转：超过大小上限时将旧日志重命名为 .bak
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val bak = File(file.parent, file.name + ".bak")
                    bak.delete()
                    file.renameTo(bak)
                }
                file.appendText(logLine)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLogFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, LOG_FILE_NAME)
    }

    fun getCrashFile(ctx: Context): File {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        return File(dir, CRASH_FILE_NAME)
    }

    fun clearLogs(ctx: Context) {
        getLogFile(ctx).delete()
        getCrashFile(ctx).delete()
    }
}
