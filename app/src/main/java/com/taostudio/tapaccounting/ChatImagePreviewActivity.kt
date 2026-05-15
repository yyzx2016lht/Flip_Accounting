package com.taostudio.tapaccounting

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlin.math.abs

class ChatImagePreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URIS = "extra_chat_image_uris"
        const val EXTRA_INDEX = "extra_chat_image_index"
    }

    private lateinit var imageView: ZoomableImageView
    private lateinit var counterView: TextView
    private var imageUris: List<String> = emptyList()
    private var index: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, false)

        imageUris = intent.getStringArrayListExtra(EXTRA_IMAGE_URIS).orEmpty()
        index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, (imageUris.size - 1).coerceAtLeast(0))

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        imageView = ZoomableImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            onSingleTap = { finishWithFade() }
            onSwipePrevious = { showPrevious() }
            onSwipeNext = { showNext() }
        }
        counterView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            alpha = 0.72f
            gravity = Gravity.CENTER
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#52000000"))
                cornerRadius = 15.dp.toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = 28.dp
            }
        }
        val closeButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_left)
            setColorFilter(Color.WHITE)
            alpha = 0.78f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#33000000"))
                shape = android.graphics.drawable.GradientDrawable.OVAL
            }
            contentDescription = "关闭"
            setPadding(9.dp, 9.dp, 9.dp, 9.dp)
            setOnClickListener { finishWithFade() }
            layoutParams = FrameLayout.LayoutParams(40.dp, 40.dp, Gravity.TOP or Gravity.START).apply {
                topMargin = 22.dp
                marginStart = 12.dp
            }
        }

        root.addView(imageView)
        root.addView(counterView)
        root.addView(closeButton)
        setContentView(root)
        root.post { hideSystemBars() }
        bindImage()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun bindImage() {
        if (imageUris.isEmpty()) {
            counterView.text = "暂无图片"
            counterView.visibility = View.VISIBLE
            imageView.setImageDrawable(null)
            return
        }
        counterView.text = "${index + 1} / ${imageUris.size}"
        counterView.visibility = if (imageUris.size > 1) View.VISIBLE else View.GONE
        imageView.setImageDrawable(null)
        Glide.with(this)
            .load(Uri.parse(imageUris[index]))
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .skipMemoryCache(true)
            .into(imageView)
    }

    private fun showPrevious() {
        if (imageUris.size <= 1) return
        index = if (index == 0) imageUris.lastIndex else index - 1
        bindImage()
    }

    private fun showNext() {
        if (imageUris.size <= 1) return
        index = if (index == imageUris.lastIndex) 0 else index + 1
        bindImage()
    }

    private fun hideSystemBars() {
        val decor = window.decorView ?: return
        try {
            WindowCompat.getInsetsController(window, decor).apply {
                hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } catch (_: Exception) {
        }
        @Suppress("DEPRECATION")
        decor.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun finishWithFade() {
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

private class ZoomableImageView(context: android.content.Context) : AppCompatImageView(context) {
    var onSingleTap: (() -> Unit)? = null
    var onSwipePrevious: (() -> Unit)? = null
    var onSwipeNext: (() -> Unit)? = null

    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val imageRect = RectF()
    private var minScale = 1f
    private var currentScale = 1f
    private var maxScale = 4f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val drawable = drawable ?: return false
            if (drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return false
            val targetScale = (currentScale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = targetScale / currentScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = targetScale
            fixBounds()
            imageMatrix = drawMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (currentScale > minScale * 1.05f) {
                resetToFit()
            } else {
                zoomTo((minScale * 2.5f).coerceAtMost(maxScale), e.x, e.y)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (currentScale <= minScale * 1.02f) return false
            drawMatrix.postTranslate(-distanceX, -distanceY)
            fixBounds()
            imageMatrix = drawMatrix
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1 == null || currentScale > minScale * 1.02f) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) < 80.dp || abs(dx) < abs(dy) * 1.25f) return false
            if (dx > 0) onSwipePrevious?.invoke() else onSwipeNext?.invoke()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetToFit() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetToFit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(currentScale > minScale * 1.02f || scaleDetector.isInProgress)
        return true
    }

    private fun resetToFit() {
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        if (width <= 0 || height <= 0 || drawableWidth <= 0f || drawableHeight <= 0f) return

        drawMatrix.reset()
        minScale = minOf(width / drawableWidth, height / drawableHeight)
        maxScale = minScale * 4f
        currentScale = minScale
        val dx = (width - drawableWidth * minScale) / 2f
        val dy = (height - drawableHeight * minScale) / 2f
        drawMatrix.postScale(minScale, minScale)
        drawMatrix.postTranslate(dx, dy)
        imageMatrix = drawMatrix
    }

    private fun zoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        val factor = targetScale / currentScale
        drawMatrix.postScale(factor, factor, focusX, focusY)
        currentScale = targetScale
        fixBounds()
        imageMatrix = drawMatrix
    }

    private fun fixBounds() {
        val drawable = drawable ?: return
        imageRect.set(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        drawMatrix.mapRect(imageRect)

        val dx = when {
            imageRect.width() <= width -> width / 2f - imageRect.centerX()
            imageRect.left > 0f -> -imageRect.left
            imageRect.right < width -> width - imageRect.right
            else -> 0f
        }
        val dy = when {
            imageRect.height() <= height -> height / 2f - imageRect.centerY()
            imageRect.top > 0f -> -imageRect.top
            imageRect.bottom < height -> height - imageRect.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}

