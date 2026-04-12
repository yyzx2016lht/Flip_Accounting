package tao.test.flipaccounting

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_foreground_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SHOW_OVERLAY = "tao.test.flipaccounting.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "tao.test.flipaccounting.HIDE_OVERLAY"
        const val ACTION_START_FLIP = "ACTION_START_FLIP"
        const val ACTION_STOP_FLIP = "ACTION_STOP_FLIP"

        // 服务被杀后，通过 AlarmManager setExact 快速重拉，无需精准穿透 Doze
        // （翻转只在亮屏时有意义，Doze 期间屏幕必然是关的，服务死了也无所谓）
        private const val RESTART_DELAY_MS = 3_000L
    }

    private lateinit var overlayManager: OverlayManager
    private var flipDetector: FlipDetector? = null

    // 防止乱序 intent 问题：记录最后一次合法的 START 时间戳
    private var lastStartFlipAtMs: Long = 0L

    private var isFlipEnabled = false
    private val keepAliveManager = KeepAliveManager()

    // ════════════════════════════════════════════════════════
    //  保活与健康检测
    // ════════════════════════════════════════════════════════
    private inner class KeepAliveManager {
        private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var watchdogJob: Job? = null
        private var restartDetectorJob: Job? = null
        private var wakeLock: PowerManager.WakeLock? = null
        private var permanentWakeLock: PowerManager.WakeLock? = null

        private var lastWatchdogRestartAtMs: Long = 0L
        private var consecutiveDeadChecks: Int = 0
        private var boostUntilMs: Long = 0L
        private var lastLoggedSamplingMode: FlipDetector.SamplingMode? = null

        // ── 亮/灭屏监听 ──────────────────────────────────────
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        // 息屏：完全停止传感器，释放资源（翻转只在亮屏时有意义）
                        Logger.d(this@OverlayService, "OverlayService", "Screen OFF: stopping detector to save power")
                        restartDetectorJob?.cancel()
                        if (isFlipEnabled) stopFlipDetection()
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen ON: restarting detector (${intent.action})")
                        if (!isFlipEnabled) return
                        acquireWakeLockBriefly(5_000L)
                        restartDetector("screen-on")
                    }
                }
            }
        }

        // ── attach / detach ───────────────────────────────────
        fun attach() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(screenReceiver, filter)
                }
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "registerReceiver failed: ${e.message}")
                try { registerReceiver(screenReceiver, filter) } catch (_: Exception) {}
            }

            if (Prefs.isShizukuPersistenceEnabled(this@OverlayService)) {
                Logger.d(this@OverlayService, "OverlayService", "Applying Shizuku deep persistence")
                ShizukuShell.applyAggressivePersistence(packageName)
            } else {
                Logger.d(this@OverlayService, "OverlayService", "Shizuku Persistence is disabled by user.")
            }
        }

        fun detach() {
            restartDetectorJob?.cancel()
            stopWatchdog()
            releaseAllWakeLocks()
            serviceScope.cancel()
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }

        // ── WakeLock ──────────────────────────────────────────
        /**
         * 借用短时 WakeLock 确保传感器注册/重启期间 CPU 不睡眠。
         */
        fun acquireWakeLockBriefly(durationMs: Long = 4_000L) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipAccounting::BriefWL")
                    wakeLock?.setReferenceCounted(false)
                }
                // 已持有则先释放再重新计时，避免时间叠加
                if (wakeLock?.isHeld == true) wakeLock?.release()
                wakeLock?.acquire(durationMs)
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "acquireWakeLockBriefly failed: ${e.message}")
            }
        }

        fun acquirePermanentWakeLock() {
            if (!Prefs.isPermanentWakeLockEnabled(this@OverlayService)) return
            try {
                if (permanentWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    permanentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipAccounting::PermanentWL")
                    permanentWakeLock?.setReferenceCounted(false)
                }
                if (permanentWakeLock?.isHeld != true) {
                    permanentWakeLock?.acquire()
                    Logger.d(this@OverlayService, "OverlayService", "🔒 Permanent WakeLock Acquired")
                }
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "acquirePermanentWakeLock failed: ${e.message}")
            }
        }

        fun releasePermanentWakeLock() {
            try {
                if (permanentWakeLock?.isHeld == true) {
                    permanentWakeLock?.release()
                    Logger.d(this@OverlayService, "OverlayService", "🔓 Permanent WakeLock Released")
                }
            } catch (_: Exception) {}
            permanentWakeLock = null
        }

        private fun releaseAllWakeLocks() {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            releasePermanentWakeLock()
        }

        // ── Watchdog ──────────────────────────────────────────
        fun startWatchdog() {
            if (watchdogJob?.isActive == true) return
            watchdogJob = serviceScope.launch {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog started")
                while (isActive) {
                    delay(15_000L)
                    if (!isFlipEnabled) break
                    checkSensorHealth()
                }
            }
        }

        fun stopWatchdog() {
            watchdogJob?.cancel()
            watchdogJob = null
            Logger.d(this@OverlayService, "OverlayService", "Watchdog stopped")
        }

        fun syncWatchdogState() {
            if (isFlipEnabled) startWatchdog() else stopWatchdog()
        }

        fun onDetectorStarted() {
            consecutiveDeadChecks = 0
            requestBoost("detector-startup", 25_000L)
            // 亮屏时才会启动 detector，直接 BOOST
            applySamplingMode(FlipDetector.SamplingMode.BOOST)
        }

        fun onDetectorStopped() {
            consecutiveDeadChecks = 0
            boostUntilMs = 0L
            lastLoggedSamplingMode = null
        }

        // ── Sensor 采样模式 ───────────────────────────────────
        private fun requestBoost(reason: String, durationMs: Long = 45_000L) {
            val until = System.currentTimeMillis() + durationMs
            if (until > boostUntilMs) {
                boostUntilMs = until
                Logger.d(this@OverlayService, "OverlayService", "Boost requested: reason=$reason durationMs=$durationMs")
            }
        }

        private fun applySamplingMode(mode: FlipDetector.SamplingMode) {
            val det = flipDetector ?: return
            val switched = det.setSamplingMode(mode)
            if (switched && lastLoggedSamplingMode != mode) {
                lastLoggedSamplingMode = mode
                Logger.d(this@OverlayService, "OverlayService", "Sampling mode => $mode")
            }
        }

        // ── 传感器健康检查 ────────────────────────────────────
        private fun checkSensorHealth() {
            if (!isFlipEnabled) return

            // 息屏时传感器已被完全停止，无需检查
            if (!isScreenInteractive()) return

            val det = flipDetector
            if (det == null) {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog: detector is null (screen on), rebuilding...")
                acquireWakeLockBriefly()
                restartDetector("watchdog-null")
                return
            }

            val timeSinceLastEvent = System.currentTimeMillis() - det.lastSensorEventTimeMillis
            // 亮屏下 20s 无传感器事件，判定假死，强制重建
            if (timeSinceLastEvent <= 20_000L) {
                consecutiveDeadChecks = 0
                return
            }

            // 防止频繁重启：距上次 Watchdog 重启不足 15s 则跳过
            val now = System.currentTimeMillis()
            if (now - lastWatchdogRestartAtMs < 15_000L) return
            lastWatchdogRestartAtMs = now

            consecutiveDeadChecks++
            acquireWakeLockBriefly()

            Logger.d(this@OverlayService, "OverlayService",
                "Watchdog: sensor dead for ${timeSinceLastEvent}ms (consecutive=$consecutiveDeadChecks), restarting...")
            restartDetector("watchdog-dead")
            consecutiveDeadChecks = 0
        }

        // ── Detector 重建 ─────────────────────────────────────
        fun restartDetector(reason: String) {
            restartDetectorJob?.cancel()
            restartDetectorJob = serviceScope.launch(Dispatchers.Main) {
                Logger.d(this@OverlayService, "OverlayService", "Restarting detector: reason=$reason")
                stopFlipDetection()
                delay(300L)
                if (isFlipEnabled) startFlipDetection()
            }
        }

        // ── 工具 ──────────────────────────────────────────────
        private fun isScreenInteractive(): Boolean = this@OverlayService.isScreenInteractive()
    }

    // ════════════════════════════════════════════════════════
    //  Service 生命周期
    // ════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        Logger.d(this, "OverlayService", "Service Created")
        try {
            overlayManager = OverlayManager(this)
            createNotificationChannel()
            startForegroundCompat()

            isFlipEnabled = Prefs.isFlipEnabled(this)
            keepAliveManager.attach()

            if (isFlipEnabled) startFlipDetection()

            keepAliveManager.syncWatchdogState()
            Logger.d(this, "OverlayService", "Service onCreate completed.")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 Fatal Error in onCreate: ${e.message}")
        }
    }

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIF_ID, buildNotification("记账助手正在后台运行"), type)
            } else {
                startForeground(NOTIF_ID, buildNotification("记账助手正在后台运行"))
            }
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 startForeground Error: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.d(this, "OverlayService", "onStartCommand: $action")

        startForegroundCompat()

        when (action) {
            null -> {
                // START_STICKY 重建，从 Prefs 恢复状态
                isFlipEnabled = Prefs.isFlipEnabled(this)
                if (isFlipEnabled) startFlipDetection()
            }
            ACTION_START_FLIP -> {
                lastStartFlipAtMs = System.currentTimeMillis()
                isFlipEnabled = true
                startFlipDetection()
            }
            ACTION_STOP_FLIP -> {
                // 防止乱序：若 START 刚刚发出（1s 内），忽略这个滞后的 STOP
                if (System.currentTimeMillis() - lastStartFlipAtMs < 1_000L) {
                    Logger.d(this, "OverlayService", "STOP_FLIP ignored: arrived too soon after last START (possible out-of-order)")
                } else {
                    isFlipEnabled = false
                    stopFlipDetection()
                    stopSelfIfIdle("flip-disabled")
                }
            }
            ACTION_SHOW_OVERLAY -> overlayManager.showOverlay()
            ACTION_HIDE_OVERLAY -> {
                overlayManager.removeOverlay()
                stopSelfIfIdle("overlay-hidden")
            }
            // RESTART_SERVICE 广播触发的重拉（BootReceiver 转发过来，已含 ACTION_START_FLIP，走上面分支）
        }

        keepAliveManager.syncWatchdogState()
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.d(this, "OverlayService", "Service onDestroy")
        keepAliveManager.detach()
        stopFlipDetection()
        overlayManager.removeOverlay()
        // START_STICKY 会自动重建，setExact 作为额外保险（部分 ROM 不遵循 START_STICKY）
        if (Prefs.isFlipEnabled(this)) scheduleRestart(RESTART_DELAY_MS)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.d(this, "OverlayService", "onTaskRemoved")
        if (Prefs.isFlipEnabled(this)) scheduleRestart(RESTART_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    // ════════════════════════════════════════════════════════
    //  翻转检测
    // ════════════════════════════════════════════════════════
    private fun checkAndShowOverlay() {
        if (Prefs.isFlipDisableLandscape(this)) {
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Logger.d(this, "OverlayService", "Flip trigger ignored in landscape")
                return
            }
        }
        keepAliveManager.acquireWakeLockBriefly(3_000L)
        val shizukuModeEnabled = Prefs.isShizukuModeEnabled(this)
        if (!shizukuModeEnabled || Prefs.isFlipAlways(this)) {
            triggerVibration()
            Handler(Looper.getMainLooper()).post { overlayManager.showOverlay() }
            return
        }
        val whiteList = Prefs.getAppWhiteList(this)
        if (whiteList.isNotEmpty() && !ShizukuSafe.isReady(this)) {
            Handler(Looper.getMainLooper()).post {
                tao.test.flipaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(this)
            }
            return
        }
        val currentApp = ShizukuShell.getForegroundApp()
        val isAllowed = when {
            currentApp == packageName -> true
            whiteList.contains(currentApp) -> true
            whiteList.isEmpty() -> currentApp == null
            else -> false
        }
        if (isAllowed) {
            triggerVibration()
            Handler(Looper.getMainLooper()).post { overlayManager.showOverlay() }
        }
    }

    private fun startFlipDetection() {
        if (flipDetector != null) return
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        flipDetector = FlipDetector(this, sensorManager) { checkAndShowOverlay() }
        if (flipDetector?.start() != true) {
            flipDetector = null
            Logger.d(this, "OverlayService", "FlipDetector start failed (no sensor?)")
        } else {
            Logger.d(this, "OverlayService", "FlipDetector started")
            keepAliveManager.onDetectorStarted()
            keepAliveManager.acquirePermanentWakeLock()
        }
    }

    private fun stopFlipDetection() {
        flipDetector?.stop()
        flipDetector = null
        keepAliveManager.onDetectorStopped()
        keepAliveManager.releasePermanentWakeLock()
    }

    // ════════════════════════════════════════════════════════
    //  重拉保险：服务死亡时用 setExact 快速重建
    // ════════════════════════════════════════════════════════

    /**
     * 通过 AlarmManager.setExact 在 [delayMs] 后触发 BootReceiver，
     * 由 BootReceiver 调用 startForegroundService 重建服务。
     * 仅作为 START_STICKY 自动重建失败时的保险，不做周期性续约。
     * Doze 期间屏幕必然关闭，翻转功能本就不工作，无需穿透 Doze。
     */
    private fun scheduleRestart(delayMs: Long) {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildRestartIntent()
            val triggerAt = System.currentTimeMillis() + delayMs
            // setExact 在 Doze 下可能延迟，但这正是我们想要的：
            // 屏幕亮起系统退出 Doze 后自然触发，不打扰深度休眠
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Logger.d(this, "OverlayService", "Restart alarm set in ${delayMs / 1000}s")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "scheduleRestart failed: ${e.message}")
        }
    }

    private fun cancelRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildRestartIntent())
        } catch (_: Exception) {}
    }

    private fun buildRestartIntent(): PendingIntent {
        val intent = Intent("tao.test.flipaccounting.RESTART_SERVICE").apply {
            setClass(this@OverlayService, BootReceiver::class.java)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getBroadcast(this, 2002, intent, flags)
    }

    // ════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════
    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) pm.isInteractive else pm.isScreenOn
    }

    private fun stopSelfIfIdle(reason: String) {
        if (isFlipEnabled || overlayManager.isShowing()) return
        Logger.d(this, "OverlayService", "Service idle ($reason), stopping")
        cancelRestart()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun triggerVibration() {
        if (!Prefs.isVibrateFeedbackEnabled(this)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(50)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("翻转记账助手").setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "记账助手服务", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null
}

