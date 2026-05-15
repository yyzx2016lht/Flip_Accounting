package tao.test.tapaccounting.tap

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
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
        private const val SENSOR_SAMPLING_PERIOD_US = 0
        private const val TAP_THROTTLE_MS = 500L
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

    private var lastTapActionUptimeMs = 0L

    private var isLowPowerMode = false

    fun start(): Boolean {
        if (isRunning) return true

        val lowPowerMode = Prefs.isTapLowPower(context)
        isLowPowerMode = lowPowerMode

        if (accelerometer == null) {
            Log.e(TAG, "Missing accelerometer")
            return false
        }
        if (!lowPowerMode && gyroscope == null) {
            Log.e(TAG, "Missing gyroscope (required for non-low-power mode)")
            return false
        }

        try {
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            val nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            val tripleEnabled = Prefs.isTapTripleEnabled(context)

            val classifier = if (!lowPowerMode) {
                val tapModel = TapModel.resolve(context)
                TapTfClassifier(context.assets, tapModel.path, nnapiLowPower)
            } else null

            tap = when {
                lowPowerMode && tripleEnabled -> HeuristicTapTapTapRT(160000000L, true).apply {
                    getLowpassKey().setPara(0.2f)
                    getHighpassKey().setPara(0.2f)
                    getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
                    getPositivePeakDetector().setWindowSize(64)
                    getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                    getNegativePeakDetection().setWindowSize(64)
                    reset(false)
                }
                lowPowerMode && !tripleEnabled -> TapRT(160000000L).apply {
                    getLowpassKey().setPara(0.2f)
                    getHighpassKey().setPara(0.2f)
                    getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
                    getPositivePeakDetector().setWindowSize(64)
                    getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                    getNegativePeakDetection().setWindowSize(64)
                    reset(false)
                }
                !lowPowerMode && tripleEnabled -> TapTapTapRT(160000000L, true, sensitivity, classifier!!).apply {
                    getLowpassKey().setPara(0.2f)
                    getHighpassKey().setPara(0.2f)
                    getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
                    getPositivePeakDetector().setWindowSize(64)
                    reset(false)
                }
                else -> TapRT(160000000L).apply {
                    setClassifier(classifier!!)
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

            sensorManager.registerListener(this, accelerometer, SENSOR_SAMPLING_PERIOD_US, sensorHandler)
            if (!lowPowerMode) {
                sensorManager.registerListener(this, gyroscope, SENSOR_SAMPLING_PERIOD_US, sensorHandler)
            }

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()

            val tapModelName = if (!lowPowerMode) TapModel.resolve(context).displayName else "none"
            Log.d(TAG, "TapDetector started: model=$tapModelName, " +
                    "sensitivity=$sensitivity, nnapi=$nnapiLowPower, " +
                    "lowPowerMode=$lowPowerMode, heuristic=$lowPowerMode, " +
                    "gyroRegistered=${!lowPowerMode}, classifierLoaded=${!lowPowerMode}, " +
                    "tripleEnabled=$tripleEnabled, " +
                    "samplingPeriodUs=$SENSOR_SAMPLING_PERIOD_US, " +
                    "samplingIntervalNs=$SAMPLING_INTERVAL_NS")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TapDetector", e)
            stop()
            return false
        }
    }

    fun stop() {
        isRunning = false
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {
        }
        sensorThread?.quitSafely()
        sensorThread = null
        sensorHandler = null
        try {
            tap?.closeClassifier()
        } catch (e: Exception) {
            Log.w(TAG, "close classifier failed", e)
        }
        tap = null
        Log.d(TAG, "TapDetector stopped")
    }

    fun restart() {
        stop()
        start()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning) return
        if (event.values.size < 3) return

        val currentTap = tap ?: return
        lastSensorEventTimeMillis = System.currentTimeMillis()

        try {
            val isTripleEnabled = Prefs.isTapTripleEnabled(context)
            currentTap.updateData(
                event.sensor.type,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                SAMPLING_INTERVAL_NS,
                isLowPowerMode
            )

            val result = currentTap.checkDoubleTapTiming(event.timestamp)
            if (result >= 2) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTapActionUptimeMs < TAP_THROTTLE_MS) return
                lastTapActionUptimeMs = now
            }
            when {
                result == 2 -> onTapAction(2)
                result == 3 && isTripleEnabled -> onTapAction(3)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tap sensor event failed; resetting detector state", e)
            currentTap.reset(false)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }
}
