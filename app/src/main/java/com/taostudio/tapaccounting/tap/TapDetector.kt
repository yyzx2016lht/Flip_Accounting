package com.taostudio.tapaccounting.tap

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
import com.taostudio.tapaccounting.Prefs
import kotlin.math.abs
import kotlin.math.sqrt

class TapDetector(
    private val context: Context,
    private val sensorManager: SensorManager,
    private val onTapAction: (tapCount: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "TapDetector"
        private const val SAMPLING_INTERVAL_NS = 2500000L
        private const val FULL_POWER_SENSOR_SAMPLING_PERIOD_US = 0
        private const val SENSOR_BATCHING_PERIOD_US = 0
        private const val FULL_POWER_AFTER_START_MS = 3 * 60_000L
        private const val STILLNESS_TO_LOW_POWER_MS = 3 * 60_000L
        private const val POWER_CHECK_INTERVAL_MS = 30_000L
        private const val SIGNIFICANT_ACCEL_DELTA = 1.15f
        private const val SIGNIFICANT_GYRO_ABS = 0.65f
        private const val TAP_THROTTLE_MS = 500L
        val TAP_SENSITIVITY_VALUES = floatArrayOf(
            0.75f, 0.53f, 0.40f, 0.25f, 0.1f, 0.05f, 0.04f, 0.03f, 0.02f, 0.01f, 0.0f
        )

        private enum class PowerProfile(val samplingPeriodUs: Int) {
            Full(FULL_POWER_SENSOR_SAMPLING_PERIOD_US),
            HeuristicStandby(FULL_POWER_SENSOR_SAMPLING_PERIOD_US)
        }
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

    private var forceFullMlMode = false

    @Volatile
    private var tripleEnabled = false

    private var powerProfile = PowerProfile.Full
    private var fullPowerUntilUptimeMs = 0L
    private var lastSignificantMotionUptimeMs = 0L
    private var lastAccelMagnitude: Float? = null

    private val powerProfileCheck = object : Runnable {
        override fun run() {
            if (!isRunning || forceFullMlMode) return
            maybeEnterDynamicLowPower()
            sensorHandler?.postDelayed(this, POWER_CHECK_INTERVAL_MS)
        }
    }

    fun start(): Boolean {
        if (isRunning) return true

        val fullMlMode = Prefs.isTapForceFullMl(context)
        forceFullMlMode = fullMlMode

        if (accelerometer == null) {
            Log.e(TAG, "Missing accelerometer")
            return false
        }
        if (gyroscope == null) {
            Log.e(TAG, "Missing gyroscope (required for ML tap detection)")
            return false
        }

        try {
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            val nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            tripleEnabled = Prefs.isTapTripleEnabled(context)

            tap = createTapRuntime(
                useHeuristic = false,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                nnapiLowPower = nnapiLowPower
            )

            sensorThread = HandlerThread("TapSensorThread", Process.THREAD_PRIORITY_DEFAULT).apply {
                start()
                sensorHandler = Handler(looper)
            }

            powerProfile = PowerProfile.Full
            fullPowerUntilUptimeMs = SystemClock.uptimeMillis() + FULL_POWER_AFTER_START_MS
            lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
            lastAccelMagnitude = null
            registerSensors(powerProfile)

            isRunning = true
            lastSensorEventTimeMillis = System.currentTimeMillis()
            if (!fullMlMode) {
                sensorHandler?.postDelayed(powerProfileCheck, POWER_CHECK_INTERVAL_MS)
            }

            val tapModelName = TapModel.resolve(context).displayName
            Log.d(TAG, "TapDetector started: model=$tapModelName, " +
                    "sensitivity=$sensitivity, nnapi=$nnapiLowPower, " +
                    "forceFullMlMode=$fullMlMode, heuristic=false, " +
                    "gyroRegistered=true, classifierLoaded=true, " +
                    "tripleEnabled=$tripleEnabled, " +
                    "samplingPeriodUs=${powerProfile.samplingPeriodUs}, " +
                    "samplingIntervalNs=$SAMPLING_INTERVAL_NS, " +
                    "batchingUs=$SENSOR_BATCHING_PERIOD_US, " +
                    "dynamicPower=${!fullMlMode}, standby=heuristic")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start TapDetector", e)
            stop()
            return false
        }
    }

    fun stop() {
        isRunning = false
        sensorHandler?.removeCallbacks(powerProfileCheck)
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
            trackMotionForDynamicPower(event)
            val isTripleEnabled = tripleEnabled
            currentTap.updateData(
                event.sensor.type,
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp,
                SAMPLING_INTERVAL_NS,
                shouldUseHeuristicRuntime()
            )

            val result = currentTap.checkDoubleTapTiming(event.timestamp)
            if (powerProfile == PowerProfile.HeuristicStandby && result >= 1) {
                extendFullPower("heuristic-candidate-$result")
            }
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

    private fun registerSensors(profile: PowerProfile) {
        val handler = sensorHandler ?: return
        sensorManager.registerListener(
            this,
            accelerometer,
            profile.samplingPeriodUs,
            SENSOR_BATCHING_PERIOD_US,
            handler
        )
        if (!shouldUseHeuristicRuntime()) {
            sensorManager.registerListener(
                this,
                gyroscope,
                profile.samplingPeriodUs,
                SENSOR_BATCHING_PERIOD_US,
                handler
            )
        }
    }

    private fun switchPowerProfile(profile: PowerProfile, reason: String) {
        if (forceFullMlMode || powerProfile == profile) return
        val handler = sensorHandler ?: return
        handler.post {
            if (!isRunning || forceFullMlMode || powerProfile == profile) return@post
            try {
                sensorManager.unregisterListener(this, accelerometer)
                sensorManager.unregisterListener(this, gyroscope)
            } catch (_: Exception) {
            }
            try {
                tap?.closeClassifier()
            } catch (e: Exception) {
                Log.w(TAG, "close classifier during profile switch failed", e)
            }
            powerProfile = profile
            val sensitivityLevel = Prefs.getTapSensitivityLevel(context)
            val sensitivity = TAP_SENSITIVITY_VALUES.getOrElse(sensitivityLevel) { 0.05f }
            tripleEnabled = Prefs.isTapTripleEnabled(context)
            tap = createTapRuntime(
                useHeuristic = profile == PowerProfile.HeuristicStandby,
                tripleEnabled = tripleEnabled,
                sensitivity = sensitivity,
                nnapiLowPower = Prefs.isTapNnapiLowPower(context)
            )
            lastAccelMagnitude = null
            registerSensors(profile)
            Log.d(
                TAG,
                "Dynamic power profile changed: profile=$profile, reason=$reason, " +
                    "samplingPeriodUs=${profile.samplingPeriodUs}, batchingUs=$SENSOR_BATCHING_PERIOD_US, " +
                    "gyroRegistered=${profile != PowerProfile.HeuristicStandby}, heuristic=${profile == PowerProfile.HeuristicStandby}"
            )
        }
    }

    private fun extendFullPower(reason: String) {
        if (forceFullMlMode) return
        fullPowerUntilUptimeMs = SystemClock.uptimeMillis() + FULL_POWER_AFTER_START_MS
        if (powerProfile != PowerProfile.Full) {
            switchPowerProfile(PowerProfile.Full, reason)
        }
    }

    private fun maybeEnterDynamicLowPower() {
        if (powerProfile == PowerProfile.HeuristicStandby) return
        val now = SystemClock.uptimeMillis()
        if (now < fullPowerUntilUptimeMs) return
        if (now - lastSignificantMotionUptimeMs < STILLNESS_TO_LOW_POWER_MS) return
        switchPowerProfile(PowerProfile.HeuristicStandby, "still-${now - lastSignificantMotionUptimeMs}ms")
    }

    private fun trackMotionForDynamicPower(event: SensorEvent) {
        if (forceFullMlMode) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                val previous = lastAccelMagnitude
                lastAccelMagnitude = magnitude
                if (previous != null && abs(magnitude - previous) >= SIGNIFICANT_ACCEL_DELTA) {
                    lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                    extendFullPower("accel-motion")
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroAbs = maxOf(abs(event.values[0]), abs(event.values[1]), abs(event.values[2]))
                if (gyroAbs >= SIGNIFICANT_GYRO_ABS) {
                    lastSignificantMotionUptimeMs = SystemClock.uptimeMillis()
                    extendFullPower("gyro-motion")
                }
            }
        }
    }

    private fun shouldUseHeuristicRuntime(): Boolean =
        !forceFullMlMode && powerProfile == PowerProfile.HeuristicStandby

    private fun createTapRuntime(
        useHeuristic: Boolean,
        tripleEnabled: Boolean,
        sensitivity: Float,
        nnapiLowPower: Boolean
    ): TapRT {
        return when {
            useHeuristic && tripleEnabled -> HeuristicTapTapTapRT(
                160000000L,
                true,
                TapRT.HEURISTIC_MIN_TIME_GAP_NS
            ).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                getNegativePeakDetection().setWindowSize(64)
                reset(false)
            }
            useHeuristic -> TapRT(160000000L, TapRT.HEURISTIC_MIN_TIME_GAP_NS).apply {
                configureCommonFilters(sensitivity)
                getNegativePeakDetection().setMinNoiseTolerate(sensitivity)
                getNegativePeakDetection().setWindowSize(64)
                reset(false)
            }
            tripleEnabled -> {
                val tapModel = TapModel.resolve(context)
                TapTapTapRT(160000000L, true, sensitivity, TapTfClassifier(context.assets, tapModel.path, nnapiLowPower)).apply {
                    configureCommonFilters(sensitivity)
                    reset(false)
                }
            }
            else -> {
                val tapModel = TapModel.resolve(context)
                TapRT(160000000L).apply {
                    setClassifier(TapTfClassifier(context.assets, tapModel.path, nnapiLowPower))
                    configureCommonFilters(sensitivity)
                    reset(false)
                }
            }
        }
    }

    private fun TapRT.configureCommonFilters(sensitivity: Float) {
        getLowpassKey().setPara(0.2f)
        getHighpassKey().setPara(0.2f)
        getPositivePeakDetector().setMinNoiseTolerate(sensitivity)
        getPositivePeakDetector().setWindowSize(64)
    }
}

