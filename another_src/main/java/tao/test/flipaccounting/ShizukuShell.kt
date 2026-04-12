package tao.test.flipaccounting

import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

object ShizukuShell {

    fun exec(cmd: String): String {
        if (!Shizuku.pingBinder()) return ""

        return try {
            val commandArray = arrayOf("sh", "-c", cmd)

            // --- 核心修改：通过 Java 助手类来创建进程 ---
            val process = ShizukuHelper.createProcess(commandArray, null, null)

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.use { it.readText() }
            process.waitFor()
            result
        } catch (e: Exception) {
            android.util.Log.e("ShizukuShell", "Shell执行异常: ${e.message}")
            ""
        }
    }

    /**
     * 获取当前最顶层（Resumed）的应用包名
     */
    fun getForegroundApp(): String? {
        val output = exec("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'")
        val regex = Regex("""([a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+)/""", RegexOption.IGNORE_CASE)
        return output.lineSequence()
            .mapNotNull { line -> regex.find(line)?.groupValues?.get(1) }
            .firstOrNull { it.contains('.') && it != "android" }
    }

    /**
     * 赋予极其暴力的毒瘤保活策略
     */
    fun applyAggressivePersistence(pkg: String) {
        if (!Shizuku.pingBinder()) return
        Thread {
            try {
                exec("dumpsys deviceidle whitelist +$pkg")
                exec("am set-standby-bucket $pkg active")
                exec("cmd appops set $pkg RUN_IN_BACKGROUND allow")
                exec("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")  
                exec("cmd appops set $pkg WAKE_LOCK allow")
                exec("cmd appops set $pkg SYSTEM_ALERT_WINDOW allow")
                // 关闭 Android 12+ 幽灵进程杀手（如果系统支持）
                exec("device_config put activity_manager max_phantom_processes 2147483647")
            } catch (e: Exception) {
                android.util.Log.e("ShizukuShell", "保活执行异常: ${e.message}")
            }
        }.start()
    }
}