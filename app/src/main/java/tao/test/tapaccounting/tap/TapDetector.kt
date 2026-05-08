package tao.test.tapaccounting.tap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import tao.test.tapaccounting.Prefs

class TapDetector(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onTapAction: (tapCount: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "TapDetector"
        private const val SAMPLING_INTERVAL_NS = 2500000L
        val TAP_SENSITIVITY_VALUES = floatArrayOf(
            0.75f, 0.53f, 0.40f, 0.25f, 0.1f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
        )
    }

    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var tap: TapRT? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null

    @Volatile
    private var isRunning = false

    @Volatile
    var lastSensorEventTimeMillis: Long = 0L
        private set

    fun start(): Boolean {
        if (isRunning) return true

        if (accelerometer == null || gyroscope == null) {
            Log.e(TAG, "Missing sensors: acc=${accelerometer != null}, gyro=${gyroscope != null}")
            return false
        }

        try {
            val tapModel = TapModel.resolve(context)
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            val nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            val tripleEnabled = Prefs.isTapTripleEnabled(context)

            val classifier = TapTfClassifier(context.assets, tapModel.path, nnapiLowPower)

            tap = if (tripleEnabled) {
                TapTapTapRT(160000000L, true, sensitivity, classifier).apply {
                    getLowpassKey().setPara(0.2f)
                    getHighpassKey().setPara(0.2f)
                    getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
                    getPositivePeakDetector().setWindowSize(64)
                    reset(false)
                }
            } else {
                TapRT(160000000L).apply {
                    setClassifier(classifier)
                    getLowpassKey().setPara(0.2f)
                    getHighpassKey().setPara(0.2f)
                    getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
                    getPositivePeakDetector().setWindowSize(64)
                    reset(false)
                }
            }

            sensorThread = HandlerThread("TapSensorThread", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
                sensorHandler = Handler(looper)
            }

            sensorManager.registerListener(this, accelerometer, 0, sensorHandler)
            sensorManager.registerListener(this, gyroscope, 0, sensorHandler)

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "TapDetector started: model=${tapModel.displayName}, sensitivity=$sensitivity, nnapi=$nnapiLowPower, triple=$tripleEnabled")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TapDetector", e)
            stop()
            return false
        }
    }

    fun stop() {
        isRunning = false
        sensorManager.unregisterListener(this)
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        tap?.reset(false)
        (tap as? TapTapTapRT)?.closeClassifier()
        (tap as? TapRT)?.closeClassifier()
        tap = null
        Log.d(TAG, "TapDetector stopped")
    }

    fun restart() {
        stop()
        start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return

        val currentTap = tap ?: return
        lastSensorEventTimeMillis = System.currentTimeMillis()

        val isTripleEnabled = Prefs.isTapTripleEnabled(context)
        currentTap.updateData(
            event.sensor.type,
            event.values[0],
            event.values[1],
            event.values[2],
            event.timestamp,
            SAMPLING_INTERVAL_NS,
            false
        )

        val result = currentTap.checkDoubleTapTiming(event.timestamp)
        when {
            result == 2 -> onTapAction(2)
            result == 3 && isTripleEnabled -> onTapAction(3)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
