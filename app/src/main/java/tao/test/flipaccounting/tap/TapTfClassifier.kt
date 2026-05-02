package tao.test.flipaccounting.tap

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.channels.FileChannel

class TapTfClassifier(
    assetManager: AssetManager,
    modelPath: String,
    private val lowPowerEnabled: Boolean = false
) : TfClassifier() {

    companion object {
        private const val TAG = "TapDetector"
    }

    private val nnApiDelegate by lazy {
        if (lowPowerEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                NnApiDelegate(NnApiDelegate.Options().apply {
                    setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_LOW_POWER)
                    setUseNnapiCpu(true)
                })
            } catch (e: Exception) {
                Log.w(TAG, "NNAPI not available, falling back to CPU", e)
                null
            }
        } else null
    }

    private val options by lazy {
        Interpreter.Options().apply {
            nnApiDelegate?.let { addDelegate(it) }
        }
    }

    private val interpreter: Interpreter? by lazy {
        try {
            assetManager.openFd(modelPath).let {
                Triple(
                    FileInputStream(it.fileDescriptor).channel,
                    it.startOffset,
                    it.declaredLength
                )
            }.run {
                Interpreter(first.map(FileChannel.MapMode.READ_ONLY, second, third), options)
            }.apply {
                Log.d(TAG, "tflite file loaded: $modelPath (nnapi=$lowPowerEnabled)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "load tflite file error: $modelPath", e)
            null
        }
    }

    override fun predict(input: ArrayList<Float>, size: Int): ArrayList<ArrayList<Float>> {
        val interpreter = interpreter ?: return ArrayList()
        return predict12(interpreter, input, size)
    }

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
    }
}
