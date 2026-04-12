package tao.test.flipaccounting

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.SensorManager
import android.os.*
import android.view.WindowManager

class AccountingForegroundService : Service() {

    private lateinit var detector: FlipDetector
    private lateinit var overlayManager: OverlayManager

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayManager(this)

        // 初始化翻转检测
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        detector = FlipDetector(this, sensorManager) {
            // 当翻转发生时执行
            triggerVibration()
            showFloatingWindow()
        }
        detector.start()
    }

    private fun showFloatingWindow() {
        // 在主线程显示悬浮窗
        Handler(Looper.getMainLooper()).post {
            overlayManager.showOverlay()
        }
    }

    private fun triggerVibration() {
        if (!Prefs.isVibrateFeedbackEnabled(this)) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        detector.stop()
        super.onDestroy()
    }
}