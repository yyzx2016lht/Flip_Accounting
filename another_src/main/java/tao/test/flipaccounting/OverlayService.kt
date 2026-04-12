package tao.test.flipaccounting

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

        const val ACTION_START_BACK_TAP = "action_start_back_tap"
        const val ACTION_STOP_BACK_TAP = "action_stop_back_tap"
    }

    private lateinit var overlayManager: OverlayManager
    private var flipDetector: FlipDetector? = null

    private var isFlipEnabled = false
    private var isBackTapEnabled = false

    private val keepAliveManager = KeepAliveManager()

    // --- 保活与健康检测机制 (KeepAlive & Watchdog) ---
    private inner class KeepAliveManager {
        private var watchdogJob: Job? = null
        private var restartDetectorJob: Job? = null
        private var wakeLock: PowerManager.WakeLock? = null
        private var permanentWakeLock: PowerManager.WakeLock? = null

        private val screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen OFF: Stopping detector")
                        restartDetectorJob?.cancel()
                        stopFlipDetection()
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Logger.d(this@OverlayService, "OverlayService", "Screen ON/Unlock: Restarting detector (${intent.action})")
                        if (isFlipEnabled) {
                            acquireWakeLockAwhile()
                            restartFlipDetector()
                        }
                    }
                }
            }
        }

        fun attach() {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            // 适配 Android 14+ 要求的明确导向，否则会报 SecurityException 导致服务死循环重启失败
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(screenReceiver, filter)
                }
            } catch (e: Exception) {
                Logger.d(this@OverlayService, "OverlayService", "🚨 registerReceiver Error: ${e.message}")
                try { registerReceiver(screenReceiver, filter) } catch (ignore: Exception) {}
            }

            // 毒瘤模式接管：只要系统给了Shizuku权限，直接把本应用从一切省电和后台限制中强行解除
            if (Prefs.isShizukuPersistenceEnabled(this@OverlayService)) {
                Logger.d(this@OverlayService, "OverlayService", "Applying Shizuku Aggressive Persistence")
                ShizukuShell.applyAggressivePersistence(packageName)
            } else {
                Logger.d(this@OverlayService, "OverlayService", "Shizuku Persistence is disabled by user.")
            }
        }

        fun detach() {
            restartDetectorJob?.cancel()
            stopWatchdog()
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun acquireWakeLockAwhile() {
            try {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipAccounting::SensorWatchdogWL")
                }
                wakeLock?.acquire(3000L) // 借用3秒CPU时间，确保不会刚开始注册就又休眠
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun acquirePermanentWakeLock() {
            if (!Prefs.isPermanentWakeLockEnabled(this@OverlayService)) return
            
            try {
                if (permanentWakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    permanentWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FlipAccounting::AggressivePermanentWL")
                }
                if (permanentWakeLock?.isHeld != true) {
                    permanentWakeLock?.acquire()
                    Logger.d(this@OverlayService, "OverlayService", "☢️ Aggressive: Permanent WakeLock Acquired! CPU forced awake.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun releasePermanentWakeLock() {
            try {
                if (permanentWakeLock?.isHeld == true) {
                    permanentWakeLock?.release()
                    Logger.d(this@OverlayService, "OverlayService", "☢️ Aggressive: Permanent WakeLock Released!")
                }
            } catch (e: Exception) {}
            permanentWakeLock = null
        }

        fun startWatchdog() {
            if (watchdogJob == null) {
                watchdogJob = CoroutineScope(Dispatchers.Default).launch {
                    while (isActive) {
                        delay(15_000)
                        checkSensorHealth()
                    }
                }
                Logger.d(this@OverlayService, "OverlayService", "Watchdog (Coroutine) started: will monitor sensor health every 15s")
            }
        }

        private fun stopWatchdog() {
            watchdogJob?.cancel()
            watchdogJob = null
            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (e: Exception) {}
            Logger.d(this@OverlayService, "OverlayService", "Watchdog (Coroutine) stopped")
        }

        private fun checkSensorHealth() {
            if (!isFlipEnabled) return
            
            // 如果目前是息屏状态，不强制唤醒传感器，防止耗电
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val isScreenOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) pm.isInteractive else pm.isScreenOn
            if (!isScreenOn) return 
            
            val detector = flipDetector
            if (detector == null) {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog Alert: Detector is null, but switch is ON! Force restarting...")
                acquireWakeLockAwhile()
                restartFlipDetector()
                return
            }
            
            val now = System.currentTimeMillis()
            val timeSinceLastEvent = now - detector.lastSensorEventTimeMillis
            
            // 如果超过 20 秒没有收到传感器数据，判定假死（针对各大国产OS激进杀后台问题）
            if (timeSinceLastEvent > 20_000) {
                Logger.d(this@OverlayService, "OverlayService", "Watchdog Alert: Sensor seems dead! (No events for ${timeSinceLastEvent}ms). Force restarting...")
                acquireWakeLockAwhile()
                restartFlipDetector()
            }
        }

        private fun restartFlipDetector() {
            restartDetectorJob?.cancel()
            restartDetectorJob = CoroutineScope(Dispatchers.Main).launch {
                delay(500)
                stopFlipDetection()
                delay(200) // Give SensorService a tiny bit of time to clear resources
                if (isFlipEnabled) {
                    startFlipDetection()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Logger.d(this, "OverlayService", "Service Created")

        try {
            // 使用 this (Service Context) 而不是 applicationContext，
            // 这样系统能正确将麦克风访问关联到这个前台服务。
            overlayManager = OverlayManager(this)
            createNotificationChannel()

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    var type = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    // API 34 (Android 14) 引入了 SPECIAL_USE
                    if (Build.VERSION.SDK_INT >= 34) {
                         type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    }
                    startForeground(NOTIF_ID, buildNotification("记账助手正在后台运行"), type)
                } else {
                    startForeground(NOTIF_ID, buildNotification("记账助手正在后台运行"))
                }
            } catch (e: Exception) {
                Logger.d(this, "OverlayService", "🚨 startForeground Error: ${e.message}")
            }

            // 初始化状态
            isFlipEnabled = Prefs.isFlipEnabled(this)

            if (isFlipEnabled) startFlipDetection()

            // 挂载保活与健康检测模块
            keepAliveManager.attach()
            keepAliveManager.startWatchdog()
            
            Logger.d(this, "OverlayService", "Service onCreate sequence completed.")
        } catch (e: Exception) {
            Logger.d(this, "OverlayService", "🚨 Fatal Error in onCreate: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logger.d(this, "OverlayService", "onStartCommand: ${intent?.action}")
        if (intent == null) {
            // 服务被系统重启，恢复状态
            isFlipEnabled = Prefs.isFlipEnabled(this)

            if (isFlipEnabled) startFlipDetection()

        } else {
            when (intent.action) {
                ACTION_SHOW_OVERLAY -> overlayManager.showOverlay() // 按钮点击强制显示，无需检查白名单
                ACTION_HIDE_OVERLAY -> overlayManager.removeOverlay()

                ACTION_START_FLIP -> {
                    isFlipEnabled = true
                    startFlipDetection()
                }
                ACTION_STOP_FLIP -> {
                    isFlipEnabled = false
                    stopFlipDetection()
                }


            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        keepAliveManager.detach()
        stopFlipDetection()
        overlayManager.removeOverlay()
        
        super.onDestroy()
    }

    // --- 核心逻辑：统一检查白名单 ---
    private fun checkAndShowOverlay() {
        keepAliveManager.acquireWakeLockAwhile() // 弹出UI前确保CPU处于唤醒状态，特别是强制回到Main Looper时防止卡死

        // 全局感应逻辑处理
        if (Prefs.isFlipAlways(this)) {
            Logger.d(this, "OverlayService", "Global Trigger Mode is ON. Vibrating and showing UI directly.")
            triggerVibration()
            Handler(Looper.getMainLooper()).post {
                overlayManager.showOverlay()
            }
            return
        }

        // 检查 Shizuku 权限状态（如果开启了白名单功能）
        val whiteList = Prefs.getAppWhiteList(this)
        if (whiteList.isNotEmpty()) {
            if (!rikka.shizuku.Shizuku.pingBinder() || 
                rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Logger.d(this, "OverlayService", "Shizuku not authorized or running, prompting user.")
                Handler(Looper.getMainLooper()).post {
                    tao.test.flipaccounting.ui.dialog.OverlayDialogs.showShizukuPrompt(this)
                }
                return
            }
        }

        // 1. 获取白名单和当前前台应用
        val currentApp = ShizukuShell.getForegroundApp()

        Logger.d(this, "OverlayService", "Trigger Logic -> Current Foreground: $currentApp, Whitelist: $whiteList")

        val isAllowed = when {
            currentApp == packageName -> true
            whiteList.contains(currentApp) -> true
            whiteList.isEmpty() -> {
                currentApp == null
            }
            else -> false
        }

        if (isAllowed) {
            Logger.d(this, "OverlayService", "Trigger Logic -> Allowed! Trigerring UI.")
            triggerVibration()
            Handler(Looper.getMainLooper()).post {
                overlayManager.showOverlay()
            }
        } else {
            Logger.d(this, "OverlayService", "Trigger Logic -> Blocked! The app '$currentApp' is not in whitelist.")
        }
    }

    // --- Flip Detector ---
    private fun startFlipDetection() {
        if (flipDetector != null) {
            Logger.d(this, "OverlayService", "FlipDetector already running, skipping start")
            return
        }
        Logger.d(this, "OverlayService", "Starting FlipDetector")
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        flipDetector = FlipDetector(this, sensorManager) {
            Logger.d(this, "OverlayService", "Flip Triggered!")
            // 翻转成功，弹出悬浮窗
            checkAndShowOverlay()
        }
        val success = flipDetector?.start() ?: false
        if (!success) {
            Logger.d(this, "OverlayService", "Failed to start FlipDetector (no sensor?)")
            flipDetector = null
        } else {
            // 超级毒瘤：如果传感器启动成功，我们强行霸占永久唤醒锁
            keepAliveManager.acquirePermanentWakeLock()
        }
    }

    private fun stopFlipDetection() {
        Logger.d(this, "OverlayService", "Stopping FlipDetector")
        flipDetector?.stop()
        flipDetector = null
        keepAliveManager.releasePermanentWakeLock()
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
            vibrator.vibrate(50)
        }
    }

    private fun buildNotification(content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("翻转记账助手").setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentIntent(pendingIntent)
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