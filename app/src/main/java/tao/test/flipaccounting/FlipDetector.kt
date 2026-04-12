package tao.test.flipaccounting

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock

/**
 * Flip detector tuned to reduce false triggers from hand jitter.
 */
class FlipDetector(
    private val ctx: android.content.Context,
    private val manager: SensorManager,
    private val debounceMs: Long = 500L,
    private val onFlipChange: () -> Unit
) : SensorEventListener {

    enum class SamplingMode { ECO, ACTIVE, BOOST }

    // 优先使用 TYPE_GRAVITY（虚拟传感器，已做低通滤波，抗抖动更好）；
    // 无 gravity 传感器的设备回退到 TYPE_ACCELEROMETER。
    // 只注册单个传感器，避免 onSensorChanged 被两路数据重复触发导致状态机误判。
    private val sensor: Sensor? =
        manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private enum class Face { UP, DOWN, UNKNOWN }

    private var lastFace = Face.UNKNOWN
    private var pendingFace = Face.UNKNOWN
    private var pendingFaceSince = 0L

    private var faceDownTime = 0L
    private var lastTriggerTime = 0L

    private val faceStableMs = 60L

    @Volatile
    var lastSensorEventTimeMillis: Long = System.currentTimeMillis()
    @Volatile
    private var lastMotionIntentTimeMillis: Long = System.currentTimeMillis()
    @Volatile
    private var samplingMode: SamplingMode = SamplingMode.ACTIVE

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    fun start(): Boolean {
        if (sensorThread == null) {
            sensorThread = HandlerThread("FlipSensorThread", Process.THREAD_PRIORITY_DEFAULT)
            sensorThread?.start()
            sensorHandler = Handler(sensorThread!!.looper)
        }
        return registerSensors()
    }

    fun stop() {
        manager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    fun refreshRegistration(): Boolean {
        if (sensorThread == null || sensorHandler == null) return false
        manager.unregisterListener(this)
        return registerSensors()
    }

    fun getSamplingMode(): SamplingMode = samplingMode

    fun setSamplingMode(mode: SamplingMode): Boolean {
        if (samplingMode == mode) return true
        samplingMode = mode
        return refreshRegistration()
    }

    fun hasRecentMotionIntent(windowMs: Long): Boolean {
        return System.currentTimeMillis() - lastMotionIntentTimeMillis <= windowMs
    }

    private fun registerSensors(): Boolean {
        val sensorDelay = when (samplingMode) {
            SamplingMode.ECO -> SensorManager.SENSOR_DELAY_NORMAL
            SamplingMode.ACTIVE -> SensorManager.SENSOR_DELAY_UI
            SamplingMode.BOOST -> SensorManager.SENSOR_DELAY_GAME
        }
        return sensor?.let {
            manager.registerListener(this, it, sensorDelay, sensorHandler)
        } ?: false
    }

    override fun onSensorChanged(e: SensorEvent) {
        lastSensorEventTimeMillis = System.currentTimeMillis()

        val z = e.values[2]
        val now = SystemClock.uptimeMillis()

        val isCustom = Prefs.isUseCustomSensitivity(ctx)
        val baseThreshold = if (isCustom) {
            Prefs.getCustomGThreshold(ctx)
        } else {
            val progress = Prefs.getFlipSensitivity(ctx)
            5.5f + (progress / 100f) * 3.5f
        }

        val currentFace = when {
            z > baseThreshold -> Face.UP
            z < -baseThreshold -> Face.DOWN
            else -> Face.UNKNOWN
        }

        if (kotlin.math.abs(z) > baseThreshold * 0.6f || currentFace != Face.UNKNOWN) {
            lastMotionIntentTimeMillis = lastSensorEventTimeMillis
        }

        if (currentFace == Face.UNKNOWN) {
            pendingFace = Face.UNKNOWN
            return
        }

        if (currentFace != pendingFace) {
            pendingFace = currentFace
            pendingFaceSince = now
            return
        }

        if (now - pendingFaceSince < faceStableMs) return
        if (currentFace == lastFace) return

        if (currentFace == Face.DOWN) {
            faceDownTime = now
        } else if (currentFace == Face.UP && faceDownTime > 0L) {
            val maxDuration = if (isCustom) {
                Prefs.getCustomMaxDuration(ctx)
            } else {
                val progress = Prefs.getFlipSensitivity(ctx)
                800L - (progress * 5L)
            }

            val minDuration = if (isCustom) {
                maxOf(20L, minOf(80L, maxDuration / 3))
            } else {
                70L
            }

            val flipDuration = now - faceDownTime
            if (flipDuration in minDuration until maxDuration && (now - lastTriggerTime > debounceMs)) {
                Logger.d(
                    ctx,
                    "FlipDetector",
                    "Flip action triggered! Duration: ${flipDuration}ms, min=${minDuration}ms, max=${maxDuration}ms, threshold=${"%.2f".format(baseThreshold)}"
                )
                onFlipChange()
                lastTriggerTime = now
                faceDownTime = 0L
            }
        }

        lastFace = currentFace
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
