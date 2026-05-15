package com.taostudio.tapaccounting

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.SensorManager
import android.os.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import com.taostudio.tapaccounting.tap.TapDetector

class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "overlay_foreground_channel"
        const val NOTIF_ID = 2001
        const val ACTION_SHOW_OVERLAY = "com.taostudio.tapaccounting.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.taostudio.tapaccounting.HIDE_OVERLAY"
        const val ACTION_SHOW_AI_INPUT = "com.taostudio.tapaccounting.SHOW_AI_INPUT"
        const val ACTION_SCREEN_CAPTURE = "com.taostudio.tapaccounting.SCREEN_CAPTURE"
        const val ACTION_START_DOUBLE_TAP = "ACTION_START_DOUBLE_TAP"
        const val ACTION_STOP_DOUBLE_TAP = "ACTION_STOP_DOUBLE_TAP"
        const val ACTION_RESTART_DOUBLE_TAP = "ACTION_RESTART_DOUBLE_TAP"

        @Volatile
        var isServiceRunning = false
            private set

        fun startCompat(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        private const val WATCHDOG_INTERVAL_MS = 180_000L
        private const val TAP_DEAD_EVENT_TIMEOUT_MS = 180_000L
        private const val TAP_DEAD_CONSECUTIVE_LIMIT = 2
        private const val MAX_CONSECUTIVE_WATCHDOG_RESTARTS = 3
        private const val WATCHDOG_COOLDOWN_MS = 5 * 60_000L
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
        private var startDetectorJob: Job? = null
        private var wakeLock: PowerManager.WakeLock? = null

        private var consecutiveDeadChecks: Int = 0
        private var consecutiveWatchdogRestarts = 0
        private var watchdogCooldownUntilMs = 0L

        // ── 亮/灭屏监听 ──────────────────────────────────────
        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen OFF: stopping detectors to save power")
                        startDetectorJob?.cancel()
                        restartDetectorJob?.cancel()
                        if (isDoubleTapEnabled) stopTapDetection()
                        stopWatchdog()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        if (canRunTapDetectorNow()) {
                            Logger.d(this@OverlayService, "OverlayService", "Screen ON: detector allowed, scheduling detector start")
                            scheduleStartAfterUnlock("screen-on-unlocked")
                        } else {
                            Logger.d(this@OverlayService, "OverlayService", "Screen ON: detector not allowed, waiting")
                        }
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Logger.d(this@OverlayService, "OverlayService", "User present: scheduling detector start")
                        if (!isDoubleTapEnabled) return
                        watchdogCooldownUntilMs = 0L
                        consecutiveDeadChecks = 0
                        consecutiveWatchdogRestarts = 0
                        scheduleStartAfterUnlock("user-present")
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
            startDetectorJob?.cancel()
            restartDetectorJob?.cancel()
            stopWatchdog()
            releaseAllWakeLocks()
            serviceScope.cancel()
            try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        }

        // ── WakeLock ──────────────────────────────────────────
        fun acquireWakeLockBriefly(durationMs: Long = 4_000L) {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapAccount::BriefWL")
                    wakeLock?.setReferenceCounted(false)
                }
                if (wakeLock?.isHeld == true) wakeLock?.release()
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
            if (isWatchdogCoolingDown(System.currentTimeMillis())) return
            if (watchdogJob?.isActive == true) return
            watchdogJob = serviceScope.launch {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog started (interval=${WATCHDOG_INTERVAL_MS}ms)")
                while (isActive) {
                    delay(WATCHDOG_INTERVAL_MS)
                    if (!isDoubleTapEnabled) break
                    ProcessExitLogger.recordHeartbeat(applicationContext as Application)
                    checkSensorHealth()
                }
            }
        }

        fun stopWatchdog() {
            if (watchdogJob == null) return
            watchdogJob?.cancel()
            watchdogJob = null
            Logger.d(this@OverlayService, "OverlayService", "Watchdog stopped")
        }

        fun syncWatchdogState() {
            if (isDoubleTapEnabled && canRunTapDetectorNow()) startWatchdog() else stopWatchdog()
        }

        // ── 传感器健康检查 ────────────────────────────────────
        private fun checkSensorHealth() {
            if (!isDoubleTapEnabled) return

            if (!canRunTapDetectorNow()) {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog skipped: detector not allowed")
                consecutiveDeadChecks = 0
                stopWatchdog()
                return
            }

            val now = System.currentTimeMillis()
            if (isWatchdogCoolingDown(now)) {
                stopWatchdog()
                return
            }

            val det = tapDetector
            if (det == null) {
                consecutiveWatchdogRestarts++
                if (consecutiveWatchdogRestarts > MAX_CONSECUTIVE_WATCHDOG_RESTARTS) {
                    enterWatchdogCooldown(now)
                    return
                }
                Logger.d(this@OverlayService, "OverlayService", "Watchdog: tap detector is null, rebuilding... (restart=${consecutiveWatchdogRestarts})")
                acquireWakeLockBriefly()
                restartDetector("watchdog-null-tap")
                return
            }
            val timeSinceLastEvent = now - det.lastSensorEventTimeMillis
            if (timeSinceLastEvent <= TAP_DEAD_EVENT_TIMEOUT_MS) {
                consecutiveDeadChecks = 0
                consecutiveWatchdogRestarts = 0
                return
            }

            consecutiveDeadChecks++
            if (consecutiveDeadChecks < TAP_DEAD_CONSECUTIVE_LIMIT) {
                Logger.d(
                    this@OverlayService,
                    "OverlayService",
                    "Watchdog: tap no sensor event for ${timeSinceLastEvent}ms, waiting (${consecutiveDeadChecks}/$TAP_DEAD_CONSECUTIVE_LIMIT)"
                )
                return
            }

            consecutiveDeadChecks = 0
            consecutiveWatchdogRestarts++

            if (consecutiveWatchdogRestarts > MAX_CONSECUTIVE_WATCHDOG_RESTARTS) {
                enterWatchdogCooldown(now)
                return
            }

            acquireWakeLockBriefly()
            Logger.d(
                this@OverlayService,
                "OverlayService",
                "Watchdog: tap sensor dead for ${timeSinceLastEvent}ms (restart=${consecutiveWatchdogRestarts}), restarting..."
            )
            restartDetector("watchdog-dead-tap")
        }

        // ── Detector 重建 ─────────────────────────────────────
        fun restartDetector(reason: String) {
            restartDetectorJob?.cancel()
            restartDetectorJob = serviceScope.launch(Dispatchers.Main) {
                if (!canRunTapDetectorNow()) {
                    Logger.d(this@OverlayService, "OverlayService", "Restart skipped: detector not allowed. reason=$reason")
                    stopTapDetection()
                    stopWatchdog()
                    return@launch
                }

                Logger.d(this@OverlayService, "OverlayService", "Restarting detector: reason=$reason")
                stopTapDetection()
                delay(500L)
                if (isDoubleTapEnabled && canRunTapDetectorNow()) {
                    startTapDetection()
                }
            }
        }

        // ── 延迟启动 ──────────────────────────────────────────
        fun scheduleStartAfterUnlock(reason: String) {
            startDetectorJob?.cancel()
            startDetectorJob = serviceScope.launch(Dispatchers.Main) {
                delay(1000L)
                if (isDoubleTapEnabled && canRunTapDetectorNow()) {
                    if (tapDetector == null) {
                        Logger.d(this@OverlayService, "OverlayService", "Starting detector after unlock: reason=$reason")
                        startTapDetection()
                    } else {
                        Logger.d(this@OverlayService, "OverlayService", "Detector already running after unlock: reason=$reason")
                    }
                    startWatchdog()
                } else {
                    Logger.d(this@OverlayService, "OverlayService", "Detector start skipped after unlock: reason=$reason")
                }
            }
        }

        fun onOrientationMaybeChanged(reason: String) {
            if (!isDoubleTapEnabled) return
            if (!canRunTapDetectorNow()) {
                Logger.d(this@OverlayService, "OverlayService", "Detector paused: not allowed after $reason")
                startDetectorJob?.cancel()
                restartDetectorJob?.cancel()
                stopTapDetection()
                stopWatchdog()
            } else {
                Logger.d(this@OverlayService, "OverlayService", "Detector allowed after $reason, scheduling start")
                scheduleStartAfterUnlock(reason)
            }
        }

        // ── Watchdog 冷却 ─────────────────────────────────────
        private fun isWatchdogCoolingDown(now: Long): Boolean {
            return watchdogCooldownUntilMs > now
        }

        private fun enterWatchdogCooldown(now: Long) {
            watchdogCooldownUntilMs = now + WATCHDOG_COOLDOWN_MS
            consecutiveDeadChecks = 0
            consecutiveWatchdogRestarts = 0
            Logger.d(this@OverlayService, "OverlayService", "Watchdog cooldown entered")
            stopTapDetection()
            stopWatchdog()
        }
    }

    // ════════════════════════════════════════════════════════
    //  Service 生命周期
    // ════════════════════════════════════════════════════════
    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        Logger.d(this, "OverlayService", "Service Created")
        KeepAliveDiagnostics.logSnapshot(this, "overlay-onCreate")
        try {
            overlayManager = OverlayManager(this)
            isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
            promoteToForeground("双击检测运行中")
            keepAliveManager.attach()

            if (isDoubleTapEnabled) {
                startTapDetectionIfAllowed("service-create")
                scheduleKeepAliveWork()
            }

            keepAliveManager.syncWatchdogState()
            Logger.d(this, "OverlayService", "Service onCreate completed.")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 Fatal Error in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Logger.d(this, "OverlayService", "onStartCommand: $action")

        when (action) {
            null -> {
                // START_STICKY 重建，从 Prefs 恢复状态
                isDoubleTapEnabled = Prefs.isDoubleTapEnabled(this)
                if (isDoubleTapEnabled) {
                    cancelRestart()
                    startTapDetectionIfAllowed("sticky-restore")
                    scheduleKeepAliveWork()
                }
            }
            ACTION_START_DOUBLE_TAP -> {
                isDoubleTapEnabled = true
                cancelRestart()
                promoteToForeground("双击检测运行中")
                startTapDetectionIfAllowed("user-start")
                scheduleKeepAliveWork()
            }
            ACTION_STOP_DOUBLE_TAP -> {
                val userDisabledTap = !Prefs.isDoubleTapEnabled(this)
                isDoubleTapEnabled = false
                stopTapDetection()
                if (userDisabledTap) {
                    KeepAliveWorker.cancelHourlyRestart(this)
                    KeepAliveWorker.cancelPeriodic(this)
                    KeepAliveWorker.cancelOneTime(this)
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
                    promoteToForeground("双击检测运行中")
                    startTapDetectionIfAllowed("settings-restart")
                    scheduleKeepAliveWork()
                }
            }
            ACTION_SHOW_OVERLAY -> overlayManager.showOverlay()
            ACTION_SHOW_AI_INPUT -> overlayManager.showAiInputPanel()
            ACTION_SCREEN_CAPTURE -> overlayManager.startScreenCaptureFromTap()
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
        isServiceRunning = false
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Logger.d(this, "OverlayService", "onTrimMemory: level=$level")
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Logger.d(this, "OverlayService", "onLowMemory")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val orientation = when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unknown"
        }
        Logger.d(this, "OverlayService", "onConfigurationChanged: orientation=$orientation")
        keepAliveManager.onOrientationMaybeChanged("configuration-$orientation")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.d(this, "OverlayService", "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
    }

    private fun startTapDetection() {
        if (tapDetector != null) return
        ProcessExitLogger.recordHeartbeat(applicationContext as Application)
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

    private fun startTapDetectionIfAllowed(reason: String) {
        if (!canRunTapDetectorNow()) {
            Logger.d(this, "OverlayService", "TapDetector not started: detector not allowed ($reason)")
            stopTapDetection()
            return
        }
        startTapDetection()
    }

    private fun isUserUnlockedAndInteractive(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) pm.isInteractive else pm.isScreenOn
        return interactive && !km.isKeyguardLocked
    }

    private fun canRunTapDetectorNow(): Boolean {
        return isUserUnlockedAndInteractive() && !isLandscapeDetectionBlocked()
    }

    private fun isLandscapeDetectionBlocked(): Boolean {
        return Prefs.isDisableLandscape(this) &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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
        if (actionId.isEmpty()) {
            triggerTapFeedback("tap-$tapCount-detected-no-action")
            Logger.d(this, "OverlayService", "Tap $tapCount detected but no action configured")
            Utils.toast(this, "已识别敲击，但还没有配置触发动作")
            return
        }
        val action = com.taostudio.tapaccounting.tap.TapActionRegistry.findById(actionId)
        if (action != null) {
            triggerTapFeedback("tap-$tapCount-detected")
            Logger.d(this, "OverlayService", "Tap $tapCount detected, executing: ${action.displayName}")
            keepAliveManager.acquireWakeLockBriefly(3_000L)
            action.execute(this)
        } else {
            triggerTapFeedback("tap-$tapCount-detected-unknown-action")
            Logger.d(this, "OverlayService", "Tap $tapCount detected but action id is unknown: $actionId")
            Utils.toast(this, "已识别敲击，但动作配置已失效")
        }
    }

    private fun stopTapDetection() {
        tapDetector?.stop()
        tapDetector = null
    }

    private fun scheduleKeepAliveWork() {
        KeepAliveWorker.cancelPeriodic(this)
        KeepAliveWorker.cancelHourlyRestart(this)
        KeepAliveWorker.cancelOneTime(this)
    }

    private fun cancelRestart() {
        KeepAliveWorker.cancelOneTime(this)
    }

    // ════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════
    private fun promoteToForeground(content: String) {
        try {
            val notification = OverlayServiceNotifications.build(this, CHANNEL_ID, content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            Logger.d(this, "OverlayService", "Foreground service active: $content")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "startForeground failed: ${e.message}")
        }
    }

    fun enterMicrophoneMode(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true

        return try {
            startForeground(
                NOTIF_ID,
                OverlayServiceNotifications.build(this, CHANNEL_ID, "录音中..."),
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
        try {
            if (isDoubleTapEnabled) {
                promoteToForeground("双击检测运行中")
                Logger.d(this, "OverlayService", "exitMicrophoneMode: restored SPECIAL_USE foreground")
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
                Logger.d(this, "OverlayService", "exitMicrophoneMode: foreground notification removed")
            }
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "exitMicrophoneMode failed: ${e.message}")
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

    override fun onBind(p0: Intent?): IBinder? = null
}

