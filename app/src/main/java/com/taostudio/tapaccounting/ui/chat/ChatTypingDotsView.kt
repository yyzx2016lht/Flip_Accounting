package com.taostudio.tapaccounting.ui.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.R
import kotlin.math.sin

/** Three-dot typing indicator (ChatGPT-style). */
class ChatTypingDotsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.chat_loading_progress)
        style = Paint.Style.FILL
    }
    private val dotRadius = 3.5f * resources.displayMetrics.density
    private val dotGap = 5f * resources.displayMetrics.density
    private var phase = 0f
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 900L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = ((dotRadius * 2 * 3) + (dotGap * 2) + paddingStart + paddingEnd).toInt()
        val height = ((dotRadius * 2) + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerY = height / 2f
        var x = paddingStart + dotRadius
        repeat(3) { index ->
            val wave = sin((phase * Math.PI * 2 + index * 0.9).toDouble()).toFloat()
            val alpha = (0.35f + (wave + 1f) * 0.325f).coerceIn(0.25f, 1f)
            paint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(x, centerY, dotRadius, paint)
            x += dotRadius * 2 + dotGap
        }
    }
}
