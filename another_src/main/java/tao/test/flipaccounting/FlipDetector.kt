package tao.test.flipaccounting

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * 翻转检测器：通过加速度/重力传感器检测快速翻转动作
 */
class FlipDetector(
    private val ctx: android.content.Context,
    private val manager: SensorManager,
    private val debounceMs: Long = 500L, // 两次触发之间的防抖时间
    private val onFlipChange: () -> Unit
) : SensorEventListener {

    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private enum class Face { UP, DOWN, UNKNOWN }
    private var lastFace = Face.UNKNOWN

    private var faceDownTime = 0L // 记录进入“面朝下”状态的时间点
    private var lastTriggerTime = 0L // 上次成功激发的时间点
    
    // 跟踪最后一次收到传感器数据的真实时刻
    @Volatile
    var lastSensorEventTimeMillis: Long = System.currentTimeMillis()

    // --- 灵敏度调节参数 ---
    // G_THRESHOLD: 重力阈值。范围 5.0 (容易) - 9.0 (极难)。 用户调节 0-100。
    // MAX_FLIP_DURATION: 上下翻转的最大耗时。范围 300ms (极快) - 800ms (慢悠悠)。 用户调节 0-100。

    private fun getGThreshold(): Float {
        // 进度 0 -> 5.0f (灵敏), 100 -> 9.0 (严格)
        // 默认 50 -> 7.0f
        val progress = Prefs.getFlipSensitivity(ctx)
        return 5.0f + (progress / 100f) * 4.0f
    }

    private fun getMaxFlipDuration(): Long {
         // 进度暂不开放给用户，或者也可以复用Sensitivity
         // 这里我们可以简单起见，灵敏度越高(进度越小)，检测越容易（阈值低，时间长）
         // 灵敏度越低(进度大)，检测越难（阈值高，时间短）
         
         // 假设 Sensitivity 定义为 "触发难度"：
         // 0 (最容易触发): G=5.0, Time=800ms
         // 100 (最难触发): G=9.0, Time=300ms
         val progress = Prefs.getFlipSensitivity(ctx)
         return 800L - (progress / 100L) * 500L
    }

    // private val G_THRESHOLD = 7.5f    // OLD
    // private val MAX_FLIP_DURATION = 400L // OLD

    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    fun start(): Boolean = sensor?.let {
        if (sensorThread == null) {
            sensorThread = HandlerThread("FlipSensorThread", Process.THREAD_PRIORITY_BACKGROUND)
            sensorThread?.start()
            sensorHandler = Handler(sensorThread!!.looper)
        }
        manager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, sensorHandler)
        true
    } ?: false

    fun stop() {
        manager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        lastSensorEventTimeMillis = System.currentTimeMillis()
        val z = e.values[2]
        val now = SystemClock.uptimeMillis()

        // 1. 判定当前朝向
        // 动态获取当前灵敏度
        val isCustom = Prefs.isUseCustomSensitivity(ctx)
        val gThreshold = if (isCustom) {
            Prefs.getCustomGThreshold(ctx)
        } else {
            val progress = Prefs.getFlipSensitivity(ctx) // 0-100, 0最灵敏(阈值低)
            // 映射：0 -> 5.5f, 100 -> 9.0f
            5.5f + (progress / 100f) * 3.5f
        }
        
        val currentFace = when {
            z > gThreshold -> Face.UP
            z < -gThreshold -> Face.DOWN
            else -> Face.UNKNOWN
        }

        // 状态未改变或无效状态直接返回
        if (currentFace == Face.UNKNOWN || currentFace == lastFace) return

        // 2. 状态切换逻辑
        if (currentFace == Face.DOWN) {
            // 记录手机开始“面朝下”的时刻
            faceDownTime = now
            // Logger.d(ctx, "FlipDetector", "Face DOWN detected. Threshold=$gThreshold")
        } else if (currentFace == Face.UP) {
            // 当检测到“面朝上”时，检查之前是否是“面朝下”
            if (lastFace == Face.DOWN) {
                // 动态获取时间阈值
                val maxDuration = if (isCustom) {
                    Prefs.getCustomMaxDuration(ctx)
                } else {
                    val progress = Prefs.getFlipSensitivity(ctx)
                    // 映射：0 -> 800ms, 100 -> 300ms
                    800L - (progress * 5L)
                }
                
                val flipDuration = now - faceDownTime
                // Logger.d(ctx, "FlipDetector", "Flip distance: ${flipDuration}ms. Max allowed: $maxDuration")

                // 判断动作快慢：只有翻转过程耗时在设定范围内，且满足防抖时间
                if (flipDuration < maxDuration && (now - lastTriggerTime > debounceMs)) {
                    Logger.d(ctx, "FlipDetector", "Flip action triggered! Duration: ${flipDuration}ms")
                    onFlipChange()
                    lastTriggerTime = now
                }
            }
        }

        lastFace = currentFace
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}