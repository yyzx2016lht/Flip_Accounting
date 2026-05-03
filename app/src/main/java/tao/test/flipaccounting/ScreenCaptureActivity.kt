package tao.test.flipaccounting

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class ScreenCaptureActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IS_MULTI_MODE = "extra_is_multi_mode"

        var onRecognitionResult: ((JSONObject) -> Unit)? = null
        var onRecognitionError: ((String) -> Unit)? = null
        var onRecognitionCancelled: (() -> Unit)? = null

        private var cachedProjectionResultCode: Int? = null
        private var cachedProjectionData: Intent? = null
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var tvStatus: TextView
    private lateinit var captureCard: View
    private lateinit var scanLine: View

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var analyzeJob: Job? = null
    private var scanAnimator: ValueAnimator? = null
    private var cardEnterAnimator: AnimatorSet? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMultiMode: Boolean = true
    private var finished = false

    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                Logger.d(this, "ScreenCaptureActivity", "Screen capture permission granted")
                cachedProjectionResultCode = result.resultCode
                cachedProjectionData = Intent(result.data)
                startMediaProjectionCapture()
            } else {
                Logger.d(this, "ScreenCaptureActivity", "Screen capture permission denied or cancelled")
                onRecognitionCancelled?.invoke()
                finishSafely()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen_capture)
        tvStatus = findViewById(R.id.tv_capture_status)
        captureCard = findViewById(R.id.layout_capture_card)
        scanLine = findViewById(R.id.view_scan_line)
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        isMultiMode = intent.getBooleanExtra(EXTRA_IS_MULTI_MODE, true)
        startWaitingAnimation()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (finished) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    return
                }
                Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "Back pressed, cancelling screen capture flow")
                onRecognitionCancelled?.invoke()
                finishSafely()
            }
        })

        Logger.d(
            this,
            "ScreenCaptureActivity",
            "onCreate. isMultiMode=$isMultiMode, shizukuEnabled=${Prefs.isShizukuModeEnabled(this)}, shizukuReady=${ShizukuSafe.isReady(this)}, hasCachedPermission=${cachedProjectionResultCode != null && cachedProjectionData != null}"
        )

        when {
            Prefs.isShizukuModeEnabled(this) && ShizukuSafe.isReady(this) -> {
                tvStatus.text = "正在通过 Shizuku 静默截屏..."
                Logger.d(this, "ScreenCaptureActivity", "Using Shizuku silent screencap path")
                startShizukuCapture()
            }
            cachedProjectionResultCode != null && cachedProjectionData != null -> {
                tvStatus.text = "正在识别屏幕中..."
                Logger.d(this, "ScreenCaptureActivity", "Reusing cached screen capture permission")
                startMediaProjectionCapture()
            }
            else -> {
                tvStatus.text = "正在请求截屏权限..."
                Logger.d(this, "ScreenCaptureActivity", "Requesting screen capture permission")
                capturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun startShizukuCapture() {
        analyzeJob?.cancel()
        analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = ShizukuShell.execBytes("screencap -p")
                if (bytes == null || bytes.isEmpty()) {
                    Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "Shizuku screencap returned empty bytes, fallback to MediaProjection")
                    withContext(Dispatchers.Main) { fallbackToMediaProjection() }
                    return@launch
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "Shizuku screencap decode returned null")
                    withContext(Dispatchers.Main) { fail("Shizuku 截屏失败，请稍后重试") }
                    return@launch
                }
                Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "Shizuku screencap succeeded. bytes=${bytes.size}, size=${bitmap.width}x${bitmap.height}")
                submitBitmapForRecognition(bitmap)
            } catch (e: Exception) {
                Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "Shizuku screencap failed: ${e.message}")
                withContext(Dispatchers.Main) { fail("Shizuku 截屏失败，请稍后重试") }
            }
        }
    }

    private fun fallbackToMediaProjection() {
        if (cachedProjectionResultCode != null && cachedProjectionData != null) {
            tvStatus.text = "正在识别屏幕中..."
            startMediaProjectionCapture()
        } else {
            tvStatus.text = "正在请求截屏权限..."
            capturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    private fun startMediaProjectionCapture() {
        val resultCode = cachedProjectionResultCode
        val resultData = cachedProjectionData
        if (resultCode == null || resultData == null) {
            Logger.d(this, "ScreenCaptureActivity", "startMediaProjectionCapture aborted: missing permission payload")
            fail("未获取到截屏权限")
            return
        }

        runCatching {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
        }.onFailure {
            Logger.d(this, "ScreenCaptureActivity", "getMediaProjection failed: ${it.message}")
            cachedProjectionResultCode = null
            cachedProjectionData = null
            fail("截屏权限已失效，请重新授权")
            return
        }

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2
        )
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "flipaccounting-screen-capture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
        Logger.d(
            this,
            "ScreenCaptureActivity",
            "Virtual display created: ${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}"
        )
        tvStatus.text = "正在识别屏幕中..."
        mainHandler.postDelayed({ tryAcquireImage(0) }, 260L)
    }

    private fun tryAcquireImage(retryCount: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            Logger.d(this, "ScreenCaptureActivity", "No screenshot image yet. retry=$retryCount")
            if (retryCount >= 8) {
                fail("截屏失败，请稍后重试")
            } else {
                mainHandler.postDelayed({ tryAcquireImage(retryCount + 1) }, 120L)
            }
            return
        }
        Logger.d(this, "ScreenCaptureActivity", "Screenshot image acquired. retry=$retryCount")

        analyzeJob?.cancel()
        analyzeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bitmap = imageToBitmap(image)
                image.close()
                releaseCaptureResources()
                submitBitmapForRecognition(bitmap)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Logger.d(this@ScreenCaptureActivity, "ScreenCaptureActivity", "AI screen recognition failed: ${e.message}")
                    fail(e.message ?: "截图识别失败，请稍后重试")
                }
            }
        }
    }

    private suspend fun submitBitmapForRecognition(bitmap: Bitmap) {
        val base64 = bitmapToBase64(bitmap)
        Logger.d(
            this@ScreenCaptureActivity,
            "ScreenCaptureActivity",
            "Screenshot prepared for AI. size=${bitmap.width}x${bitmap.height}, base64Len=${base64.length}"
        )
        val result = AIService.analyzeScreenAccountingByImage(
            ctx = this@ScreenCaptureActivity,
            imageBase64 = base64,
            mimeType = "image/jpeg",
            isMultiModeOverride = isMultiMode
        ) ?: JSONObject().apply {
            put("no_bill", true)
            put("reply", "未识别到可记账内容")
        }
        withContext(Dispatchers.Main) {
            Logger.d(
                this@ScreenCaptureActivity,
                "ScreenCaptureActivity",
                "AI screen recognition completed. no_bill=${result.optBoolean("no_bill", false)}, hasBills=${result.has("bills")}, hasAmount=${result.has("amount")}"
            )
            onRecognitionResult?.invoke(result)
            finishSafely()
        }
    }

    private fun fail(message: String) {
        if (finished) return
        Logger.d(this, "ScreenCaptureActivity", "Fail: $message")
        releaseCaptureResources()
        onRecognitionError?.invoke(message)
        finishSafely()
    }

    private fun finishSafely() {
        if (finished) return
        finished = true
        Logger.d(this, "ScreenCaptureActivity", "finishSafely")
        stopWaitingAnimation()
        mainHandler.removeCallbacksAndMessages(null)
        analyzeJob?.cancel()
        releaseCaptureResources()
        onRecognitionResult = null
        onRecognitionError = null
        onRecognitionCancelled = null
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun releaseCaptureResources() {
        Logger.d(this, "ScreenCaptureActivity", "Releasing capture resources")
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { mediaProjection?.stop() }
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val maxDim = 1440
        val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
            val ratio = minOf(
                maxDim.toFloat() / bitmap.width,
                maxDim.toFloat() / bitmap.height
            )
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).toInt(),
                (bitmap.height * ratio).toInt(),
                true
            )
        } else {
            bitmap
        }
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.d(this, "ScreenCaptureActivity", "onDestroy. finished=$finished")
        stopWaitingAnimation()
        if (!finished) {
            releaseCaptureResources()
        }
    }

    private fun startWaitingAnimation() {
        // 卡片入场：scale 0.85→1.0 + alpha 0→1，220ms，DecelerateInterpolator
        if (cardEnterAnimator == null) {
            val scaleX = ObjectAnimator.ofFloat(captureCard, "scaleX", 0.85f, 1f)
            val scaleY = ObjectAnimator.ofFloat(captureCard, "scaleY", 0.85f, 1f)
            val alpha  = ObjectAnimator.ofFloat(captureCard, "alpha", 0f, 1f)
            cardEnterAnimator = AnimatorSet().apply {
                playTogether(scaleX, scaleY, alpha)
                duration = 220L
                interpolator = DecelerateInterpolator()
                start()
            }
        }

        // 扫描线：translationY 从 -屏幕高 到 +屏幕高 循环，营造扫描感
        if (scanAnimator == null) {
            val screenH = resources.displayMetrics.heightPixels.toFloat()
            scanAnimator = ValueAnimator.ofFloat(-screenH, screenH).apply {
                duration = 2200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { scanLine.translationY = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun stopWaitingAnimation() {
        cardEnterAnimator?.cancel()
        cardEnterAnimator = null
        scanAnimator?.cancel()
        scanAnimator = null
    }

}
