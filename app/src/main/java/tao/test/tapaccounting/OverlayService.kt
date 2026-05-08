package tao.test.tapaccounting

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import tao.test.tapaccounting.tap.TapDetector

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_foreground_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SHOW_OVERLAY = "tao.test.tapaccounting.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "tao.test.tapaccounting.HIDE_OVERLAY"
        const val ACTION_SHOW_AI_INPUT = "tao.test.tapaccounting.SHOW_AI_INPUT"
        const val ACTION_START_DOUBLE_TAP = "ACTION_START_DOUBLE_TAP"
        const val ACTION_STOP_DOUBLE_TAP = "ACTION_STOP_DOUBLE_TAP"
        const val ACTION_RESTART_DOUBLE_TAP = "ACTION_RESTART_DOUBLE_TAP"

        // 服务被杀后，通过 AlarmManager setExact 快速重拉，无需精准穿透 Doze
        private const val RESTART_DELAY_MS = 3_000L
        private const val TAP_DEAD_EVENT_TIMEOUT_MS = 120_000L
        private const val TAP_DEAD_CONSECUTIVE_LIMIT = 3
        private const val TAP_FEEDBACK_THROTTLE_MS = 650L
    }

    private lateinit var overlayManager: OverlayManager
    private var tapDetector: TapDetector? = null

    private var isDoubleTapEnabled = false
    private val keepAliveManager = KeepAliveManager()
    private var lastTapFeedbackAtMs: Long = 0L

    // ════════════════════════════════════════════════════════
    //  保活与健康检测
    // ════════════════════════════════════════════════════════
    private inner class KeepAliveManager {
        private var serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        private var watchdogJob: Job? = null
        private var restartDetectorJob: Job? = null
        private var wakeLock: PowerManager.WakeLock? = null

        private var lastWatchdogRestartAtMs: Long = 0L
        private var consecutiveDeadChecks: Int = 0
        private var boostUntilMs: Long = 0L

        // ── 亮/灭屏监听 ──────────────────────────────────────
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen OFF: stopping detectors to save power")
                        restartDetectorJob?.cancel()
                        if (isDoubleTapEnabled) stopTapDetection()
                        if (!Prefs.isAggressiveKeepAliveEnabled(this@OverlayService)) {
                            stopWatchdog()
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen ON: restarting detectors (${intent.action})")
                        if (!isDoubleTapEnabled) return
                        acquireWakeLockBriefly(5_000L)
                        restartDetector("screen-on")
                        if (!Prefs.isAggressiveKeepAliveEnabled(this@OverlayService)) {
                            startWatchdog()
                        }
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
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapAccount::BriefWL")
                    wakeLock?.setReferenceCounted(false)
                }
                if (wakeLock?.isHeld == true) {
                    if (Prefs.isAggressiveKeepAliveEnabled(this@OverlayService)) {
                        // 强制保活模式：先释放再重新计时，避免时间叠加
                        wakeLock?.release()
                    } else {
                        // 省电模式：已持有则跳过，让当前持有自然到期
                        return
                    }
                }
                wakeLock?.acquire(durationMs)
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "acquireWakeLockBriefly failed: ${e.message}")
            }
        }

        private fun releaseAllWakeLocks() {
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        }

        // ── Watchdog ──────────────────────────────────────────
        fun startWatchdog() {
            if (watchdogJob?.isActive == true) return
            watchdogJob = serviceScope.launch {
                val interval = if (Prefs.isAggressiveKeepAliveEnabled(this@OverlayService)) 15_000L else 60_000L
                Logger.d(this@OverlayService, "OverlayService", "Watchdog started (interval=${interval}ms)")
                while (isActive) {
                    delay(interval)
                    if (!isDoubleTapEnabled) break
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
            if (isDoubleTapEnabled) startWatchdog() else stopWatchdog()
        }

        // ── 传感器健康检查 ────────────────────────────────────
        private fun checkSensorHealth() {
            if (!isDoubleTapEnabled) return

            // 息屏时传感器已被完全停止，无需检查
            if (!isScreenInteractive()) return

            val now = System.currentTimeMillis()
            if (isDoubleTapEnabled) {
                val det = tapDetector
                if (det == null) {
                    Logger.d(this@OverlayService, "OverlayService", "Watchdog: tap detector is null (screen on), rebuilding...")
                    acquireWakeLockBriefly()
                    restartDetector("watchdog-null-tap")
                    return
                }
                val timeSinceLastEvent = now - det.lastSensorEventTimeMillis
                if (timeSinceLastEvent > TAP_DEAD_EVENT_TIMEOUT_MS) {
                    if (now - lastWatchdogRestartAtMs < 15_000L) return
                    lastWatchdogRestartAtMs = now
                    consecutiveDeadChecks++
                    if (consecutiveDeadChecks < TAP_DEAD_CONSECUTIVE_LIMIT) {
                        Logger.d(
                            this@OverlayService,
                            "OverlayService",
                            "Watchdog: tap no sensor event for ${timeSinceLastEvent}ms, waiting (${consecutiveDeadChecks}/$TAP_DEAD_CONSECUTIVE_LIMIT)"
                        )
                        return
                    }
                    acquireWakeLockBriefly()
                    Logger.d(
                        this@OverlayService,
                        "OverlayService",
                        "Watchdog: tap sensor dead for ${timeSinceLastEvent}ms (consecutive=$consecutiveDeadChecks), restarting..."
                    )
                    restartDetector("watchdog-dead-tap")
                    consecutiveDeadChecks = 0
                    return
                }
            }
            consecutiveDeadChecks = 0
        }

        // ── Detector 重建 ─────────────────────────────────────
        fun restartDetector(reason: String) {
            restartDetectorJob?.cancel()
            restartDetectorJob = serviceScope.launch(Dispatchers.Main) {
                Logger.d(this@OverlayService, "OverlayService", "Restarting detectors: reason=$reason")
                stopTapDetection()
                delay(300L)
                if (isDoubleTapEnabled) startTapDetection()
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

            isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
            keepAliveManager.attach()

            if (isDoubleTapEnabled) startTapDetection()

            keepAliveManager.syncWatchdogState()
            Logger.d(this, "OverlayService", "Service onCreate completed.")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 Fatal Error in onCreate: ${e.message}")
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("记账助手正在后台运行")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+：显式声明为 specialUse，避免系统回退到 manifest 全量类型导致权限校验异常。
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (se: SecurityException) {
            Logger.d(this, "OverlayService", "startForeground security fallback: ${se.message}")
            try {
                startForeground(NOTIF_ID, notification)
            } catch (fallbackError: Exception) {
                Logger.d(this, "OverlayService", "🚨 startForeground fallback Error: ${fallbackError.message}")
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
                isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
                if (isDoubleTapEnabled) {
                    cancelRestart()
                    startTapDetection()
                }
            }
            ACTION_START_DOUBLE_TAP -> {
                isDoubleTapEnabled = true
                cancelRestart()
                startTapDetection()
            }
            ACTION_STOP_DOUBLE_TAP -> {
                val userDisabledTap = !Prefs.isDoubleTapEnabled(this)
                isDoubleTapEnabled = false
                stopTapDetection()
                if (userDisabledTap) {
                    stopSelfIfIdle("double-tap-disabled")
                } else {
                    Logger.d(this, "OverlayService", "Tap detection paused temporarily; service kept alive")
                }
            }
            ACTION_RESTART_DOUBLE_TAP -> {
                stopTapDetection()
                isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
                if (isDoubleTapEnabled) {
                    cancelRestart()
                    startTapDetection()
                }
            }
            ACTION_SHOW_OVERLAY -> overlayManager.showOverlay()
            ACTION_SHOW_AI_INPUT -> overlayManager.showAiInputPanel()
            ACTION_HIDE_OVERLAY -> {
                overlayManager.removeOverlay()
                stopSelfIfIdle("overlay-hidden")
            }
            // RESTART_SERVICE 广播触发的重拉（BootReceiver 转发过来，已含相应 action，走上面分支）
        }

        keepAliveManager.syncWatchdogState()
        return START_STICKY
    }

    override fun onDestroy() {
        Logger.d(this, "OverlayService", "Service onDestroy")
        keepAliveManager.detach()
        stopTapDetection()
        overlayManager.removeOverlay()
        // START_STICKY 会自动重建，setExact 作为额外保险（部分 ROM 不遵循 START_STICKY）
        if (Prefs.isDoubleTapEnabled(this)) scheduleRestart(RESTART_DELAY_MS)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.d(this, "OverlayService", "onTaskRemoved")
        if (Prefs.isDoubleTapEnabled(this)) scheduleRestart(RESTART_DELAY_MS)
        super.onTaskRemoved(rootIntent)
    }

    private fun startTapDetection() {
        if (tapDetector != null) return
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        tapDetector = TapDetector(this, sensorManager) { tapCount ->
            Handler(Looper.getMainLooper()).post {
                handleTapAction(tapCount)
            }
        }
        if (tapDetector?.start() != true) {
            tapDetector = null
            Logger.d(this, "OverlayService", "TapDetector start failed")
        } else {
            Logger.d(this, "OverlayService", "TapDetector started")
        }
    }

    private fun handleTapAction(tapCount: Int) {
        if (Prefs.isDisableLandscape(this)) {
            val orientation = resources.configuration.orientation
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                Logger.d(this, "OverlayService", "Tap trigger ignored in landscape")
                return
            }
        }

        val actionId = when (tapCount) {
            2 -> Prefs.getTapActionDouble(this)
            3 -> Prefs.getTapActionTriple(this)
            else -> ""
        }
        triggerTapFeedback("tap-$tapCount-detected")
        if (actionId.isEmpty()) {
            Logger.d(this, "OverlayService", "Tap $tapCount detected but no action configured")
            Utils.toast(this, "已识别敲击，但还没有配置触发动作")
            return
        }
        val action = tao.test.tapaccounting.tap.TapActionRegistry.findById(actionId)
        if (action != null) {
            Logger.d(this, "OverlayService", "Tap $tapCount detected, executing: ${action.displayName}")
            keepAliveManager.acquireWakeLockBriefly(3_000L)
            action.execute(this)
        } else {
            Logger.d(this, "OverlayService", "Tap $tapCount detected but action id is unknown: $actionId")
            Utils.toast(this, "已识别敲击，但动作配置已失效")
        }
    }

    private fun stopTapDetection() {
        tapDetector?.stop()
        tapDetector = null
    }

    // ════════════════════════════════════════════════════════
    //  重拉保险：服务死亡时用 setExact 快速重建
    // ════════════════════════════════════════════════════════

    /**
     * 通过 AlarmManager.setExact 在 [delayMs] 后触发 BootReceiver，
     * 由 BootReceiver 调用 startForegroundService 重建服务。
     * 仅作为 START_STICKY 自动重建失败时的保险，不做周期性续约。
     */
    private fun scheduleRestart(delayMs: Long) {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = buildRestartIntent()
            val triggerAt = System.currentTimeMillis() + delayMs
            val clockType = if (Prefs.isAggressiveKeepAliveEnabled(this))
                AlarmManager.RTC_WAKEUP else AlarmManager.RTC
            if (canScheduleExactRestart(am)) {
                am.setExact(clockType, triggerAt, pi)
                Logger.d(this, "OverlayService", "Exact restart alarm set in ${delayMs / 1000}s (clockType=$clockType)")
            } else {
                am.set(clockType, triggerAt, pi)
                Logger.d(this, "OverlayService", "Inexact restart alarm set in ${delayMs / 1000}s (clockType=$clockType)")
            }
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "scheduleRestart failed: ${e.message}")
        }
    }

    private fun canScheduleExactRestart(alarmManager: AlarmManager): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
    }

    private fun cancelRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildRestartIntent())
        } catch (_: Exception) {}
    }

    private fun buildRestartIntent(): PendingIntent {
        val intent = Intent("tao.test.tapaccounting.RESTART_SERVICE").apply {
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

    fun enterMicrophoneMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        return try {
            startForeground(
                NOTIF_ID,
                buildNotification("录音中..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            Logger.d(this, "OverlayService", "enterMicrophoneMode: switched to MICROPHONE|SPECIAL_USE")
            true
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "enterMicrophoneMode failed: ${e.message}")
            false
        }
    }

    fun exitMicrophoneMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                startForeground(
                    NOTIF_ID,
                    buildNotification("记账助手正在后台运行"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Logger.d(this, "OverlayService", "exitMicrophoneMode: reverted to SPECIAL_USE")
            } catch (e: Exception) {
                Logger.d(this, "OverlayService", "exitMicrophoneMode failed: ${e.message}")
            }
        }
    }

    private fun stopSelfIfIdle(reason: String) {
        if (isDoubleTapEnabled || overlayManager.isShowing()) return
        Logger.d(this, "OverlayService", "Service idle ($reason), stopping")
        cancelRestart()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        stopSelf()
    }

    private fun triggerTapFeedback(reason: String) {
        if (!Prefs.isVibrateFeedbackEnabled(this)) {
            Logger.d(this, "OverlayService", "Tap feedback skipped: disabled by user. reason=$reason")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapFeedbackAtMs < TAP_FEEDBACK_THROTTLE_MS) {
            Logger.d(this, "OverlayService", "Tap feedback throttled. reason=$reason")
            return
        }
        lastTapFeedbackAtMs = now
        val vibrated = Utils.vibrate(this, duration = 45L, reason = reason, amplitude = 210)
        if (!vibrated) {
            Logger.d(this, "OverlayService", "Tap feedback requested but vibrator did not run. reason=$reason")
        }
    }

    private fun buildNotification(content: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("敲敲记账助手").setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "记账助手服务", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(p0: Intent?): IBinder? = null
}
