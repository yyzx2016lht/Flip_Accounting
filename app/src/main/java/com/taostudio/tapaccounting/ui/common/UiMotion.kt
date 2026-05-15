package com.taostudio.tapaccounting.ui.common

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * Centralised animation duration & interpolator constants for the app.
 *
 * Usage example:
 * ```
 * view.animate()
 *     .alpha(0f)
 *     .setDuration(UiMotion.FAST)
 *     .setInterpolator(UiMotion.EXIT_EASING)
 *     .withEndAction { view.visibility = View.GONE }
 *     .start()
 * ```
 */
object UiMotion {

    /** Micro-interaction – ripple, icon morph, button press feedback. */
    const val FAST = 150L

    /** Standard transition – page element enter/exit, snackbar. */
    const val NORMAL = 220L

    /** Emphasised transition – page change, shared element. */
    const val SLOW = 300L

    // ── Easing curves ──────────────────────────────────────────────

    /** Standard easing (decelerate-in). Use for most enter animations. */
    val STANDARD_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Exit easing (accelerate-out). Use for dismiss / exit animations. */
    val EXIT_EASING = PathInterpolator(0.4f, 0f, 1f, 1f)

    /** Emphasised easing (same curve as standard, kept separate for clarity). */
    val EMPHASISED_EASING = PathInterpolator(0.2f, 0f, 0f, 1f)

    // ── View extension helpers ──────────────────────────────────────

    /**
     * Cancel any running animation on [this] view safely (no crash if none).
     */
    fun View.safeCancelAnimation() {
        animate().cancel()
    }

    /**
     * Apply a quick press-feedback scale effect and restore.
     * Useful for `onTouch` / `OnClickListener` callbacks.
     */
    fun View.pressFeedback() {
        animate().cancel()
        animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(FAST)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(FAST)
                    .setInterpolator(STANDARD_EASING)
                    .start()
            }
            .start()
    }

    /**
     * Fade in [this] view over [duration] ms using [STANDARD_EASING].
     */
    fun View.fadeIn(duration: Long = NORMAL, onEnd: (() -> Unit)? = null) {
        alpha = 0f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(STANDARD_EASING)
            .withEndAction { onEnd?.invoke() }
            .start()
    }

    /**
     * Fade out [this] view over [duration] ms, then set [View.GONE].
     */
    fun View.fadeOut(duration: Long = NORMAL, onEnd: (() -> Unit)? = null) {
        animate()
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                visibility = View.GONE
                onEnd?.invoke()
            }
            .start()
    }

    // ── FAB helpers ──────────────────────────────────────────────────

    /**
     * Animate FAB to visible state with scale + alpha transition.
     * Uses [FloatingActionButton.show] for proper internal state management,
     * but wraps it with a crossfade for smoother appearance.
     */
    fun FloatingActionButton.showAnimated() {
        if (isOrWillBeShown) return
        safeCancelAnimation()
        alpha = 0f
        scaleX = 0.5f
        scaleY = 0.5f
        visibility = View.VISIBLE
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(NORMAL)
            .setInterpolator(STANDARD_EASING)
            .withLayer()
            .withEndAction { show() }
            .start()
    }

    /**
     * Animate FAB to hidden state with scale + alpha transition.
     * Uses [FloatingActionButton.hide] for proper internal state management.
     */
    fun FloatingActionButton.hideAnimated() {
        if (isOrWillBeHidden) return
        safeCancelAnimation()
        animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(NORMAL)
            .setInterpolator(EXIT_EASING)
            .withLayer()
            .withEndAction {
                hide()
                // Reset visual state so next show starts clean
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
            }
            .start()
    }

    // ── Form / overlay helpers ──────────────────────────────────────

    /**
     * Gentle press feedback for accounting form rows: scale to 0.98 + alpha to 0.92 on press,
     * restore on release/cancel. Does not interfere with click/long-click listeners.
     */
    fun View.applyFormRowPressFeedback() {
        var isPressed = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .alpha(0.88f)
                        .setDuration(FAST)
                        .setInterpolator(EXIT_EASING)
                        .withLayer()
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressed) {
                        isPressed = false
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(FAST)
                            .setInterpolator(STANDARD_EASING)
                            .withLayer()
                            .start()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Crossfade from [from] view to [to] view over [duration] ms.
     * Fades out [from], then fades in [to]. Calls [onMidpoint] between the two phases.
     */
    fun crossfadeViews(
        from: View,
        to: View,
        duration: Long = NORMAL,
        onMidpoint: (() -> Unit)? = null
    ) {
        from.animate().cancel()
        from.animate()
            .alpha(0f)
            .setDuration(duration / 2)
            .setInterpolator(EXIT_EASING)
            .withLayer()
            .withEndAction {
                from.visibility = View.GONE
                onMidpoint?.invoke()
                to.alpha = 0f
                to.visibility = View.VISIBLE
                to.animate()
                    .alpha(1f)
                    .setDuration(duration / 2)
                    .setInterpolator(STANDARD_EASING)
                    .withLayer()
                    .start()
            }
            .start()
    }

    // ── Home page helpers ─────────────────────────────────────────

    /**
     * Crossfade text on a [TextView]: fade out → set new text → fade in.
     * Uses [FAST] duration to feel instant but smooth.
     * Cancels any running animation on the same view before starting.
     */
    fun TextView.crossfadeText(newText: String) {
        if (text == newText) return
        animate().cancel()
        animate()
            .alpha(0f)
            .setDuration(60L)
            .setInterpolator(EXIT_EASING)
            .withEndAction {
                text = newText
                animate()
                    .alpha(1f)
                    .setDuration(80L)
                    .setInterpolator(STANDARD_EASING)
                    .start()
            }
            .start()
    }

    /**
     * Gentle press feedback for list items: scale to 0.98 + alpha to 0.94 on press,
     * restore on release/cancel. Uses [OnTouchListener] so it does not interfere
     * with click/long-click listeners.
     *
     * Call this from [RecyclerView.Adapter.onViewAttachedToWindow] or ViewHolder init,
     * NOT from bind, to avoid re-setting on every rebind.
     */
    fun View.applyItemPressFeedback() {
        var isPressed = false
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isPressed = true
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .alpha(0.92f)
                        .setDuration(FAST)
                        .setInterpolator(EXIT_EASING)
                        .withLayer()
                        .start()
                    false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isPressed) {
                        isPressed = false
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(FAST)
                            .setInterpolator(STANDARD_EASING)
                            .withLayer()
                            .start()
                    }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    false
                }
                else -> false
            }
        }
    }

    /**
     * Stagger fade-in animation for RecyclerView children.
     * Animates only the first [maxItems] visible children with a stagger delay.
     * Safe for performance: only targets currently attached views.
     */
    fun staggerFirstLoadAnimation(
        recyclerView: RecyclerView,
        maxItems: Int = 6,
        itemDelayMs: Long = 40L,
        startDelayMs: Long = 80L
    ) {
        recyclerView.post {
            val lm = recyclerView.layoutManager ?: return@post
            val count = minOf(maxItems, lm.childCount)
            for (i in 0 until count) {
                val child = lm.getChildAt(i) ?: continue
                child.alpha = 0f
                child.translationY = 24f
                child.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(NORMAL)
                    .setInterpolator(STANDARD_EASING)
                    .setStartDelay(startDelayMs + i * itemDelayMs)
                    .withLayer()
                    .start()
            }
        }
    }
}

