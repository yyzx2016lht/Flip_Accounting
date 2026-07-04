package com.taostudio.tapaccounting

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.ui.common.UiMotion

class ChatVoiceInputController(
    private val context: ChatActivity,
    private val etInputProvider: () -> android.widget.EditText,
    private val btnSendProvider: () -> ImageView,
    private val btnStopProvider: () -> ImageView,
    private val btnAttachProvider: () -> ImageView,
    private val isAiGenerating: () -> Boolean,
    private val btnVoiceToggleProvider: () -> ImageView,
    private val btnVoiceHoldProvider: () -> MaterialButton,
    private val layoutVoiceRecordOverlayProvider: () -> View,
    private val viewVoiceRecordDotProvider: () -> View,
    private val ivVoiceRecordStateProvider: () -> ImageView,
    private val tvVoiceRecordTitleProvider: () -> TextView,
    private val tvVoiceRecordSubtitleProvider: () -> TextView,
    private val tvVoiceRecordTimerProvider: () -> TextView,
    private val isVoiceMode: () -> Boolean,
    private val setVoiceMode: (Boolean) -> Unit,
    private val isRecording: () -> Boolean,
    private val setIsRecording: (Boolean) -> Unit,
    private val isWannaCancel: () -> Boolean,
    private val setIsWannaCancel: (Boolean) -> Unit,
    private val setIsFingerDown: (Boolean) -> Unit,
    private val setLongPressTriggered: (Boolean) -> Unit,
    private val getRecordingStartAt: () -> Long,
    private val ensureAiVoiceFeatureEnabled: () -> Boolean,
    private val ensureRecordPermission: () -> Boolean,
    private val clearPendingLongPress: () -> Unit,
    private val startVoiceRecording: () -> Boolean,
    private val stopVoiceRecording: ((java.io.File?, Int) -> Unit) -> Unit,
    private val onVoiceRecorded: (java.io.File, Int) -> Unit,
    private val isInlineAmountEditing: () -> Boolean,
    private val ensureLastMessageVisible: () -> Unit,
    private val refreshVoiceSupportHint: () -> Unit,
    private val hasPendingImages: () -> Boolean = { false }
) {
    private val overlayUiHandler = Handler(Looper.getMainLooper())
    private var overlayTicker: Runnable? = null

    fun toggleVoiceMode() {
        if (!isVoiceMode() && !ensureAiVoiceFeatureEnabled()) return
        setVoiceMode(!isVoiceMode())
        updateVoiceModeUi()
        if (isVoiceMode()) {
            etInputProvider().clearFocus()
        }
        refreshVoiceSupportHint()
    }

    fun updateVoiceModeUi() {
        etInputProvider().visibility = if (isVoiceMode()) android.view.View.GONE else android.view.View.VISIBLE
        btnVoiceHoldProvider().visibility = if (isVoiceMode()) android.view.View.VISIBLE else android.view.View.GONE
        btnVoiceToggleProvider().setImageResource(
            if (isVoiceMode()) R.drawable.ic_chat_edit else R.drawable.ic_chat_mic
        )
        btnVoiceToggleProvider().isSelected = isVoiceMode()
        btnVoiceHoldProvider().text = context.getString(R.string.hold_to_talk)
        resetVoiceHoldButtonAppearance()
        refreshVoiceSupportHint()
        updateInputActionUi()
    }

    fun resetVoiceHoldButtonAppearance() {
        val btn = btnVoiceHoldProvider()
        btn.backgroundTintList = null
        btn.setBackgroundResource(
            if (isRecording()) R.drawable.bg_chat_voice_hold_btn_recording
            else R.drawable.bg_chat_voice_hold_btn
        )
        btn.alpha = 1f
    }

    fun setVoiceHoldRecordingAppearance(isRecording: Boolean) {
        val btn = btnVoiceHoldProvider()
        btn.backgroundTintList = null
        btn.setBackgroundResource(
            if (isRecording) R.drawable.bg_chat_voice_hold_btn_recording
            else R.drawable.bg_chat_voice_hold_btn
        )
        btn.alpha = 1f
    }

    fun updateInputActionUi() {
        if (isAiGenerating()) {
            btnSendProvider().visibility = android.view.View.GONE
            btnStopProvider().visibility = android.view.View.VISIBLE
            btnAttachProvider().visibility = android.view.View.GONE
            btnVoiceToggleProvider().visibility = android.view.View.GONE
            return
        }
        btnStopProvider().visibility = android.view.View.GONE
        val hasText = etInputProvider().text?.toString()?.trim()?.isNotEmpty() == true
        val hasImages = hasPendingImages()
        val canSend = hasText || hasImages
        btnAttachProvider().visibility = android.view.View.VISIBLE
        if (isVoiceMode()) {
            btnSendProvider().visibility = android.view.View.GONE
            btnVoiceToggleProvider().visibility = android.view.View.VISIBLE
            btnSendProvider().alpha = 0.4f
            return
        }
        btnSendProvider().visibility = if (canSend) android.view.View.VISIBLE else android.view.View.GONE
        btnVoiceToggleProvider().visibility = if (canSend) android.view.View.GONE else android.view.View.VISIBLE
        btnSendProvider().alpha = if (canSend) 1f else 0.4f
    }

    fun startRecordingButtonPulse() {
        // Keep hold button capsule stable; pulse is handled on the overlay icon.
    }

    fun stopRecordingButtonPulse() {
        btnVoiceHoldProvider().animate().cancel()
        btnVoiceHoldProvider().alpha = 1f
    }

    fun showVoiceRecordOverlay(isCancelState: Boolean) {
        val overlay = layoutVoiceRecordOverlayProvider()
        val recordDot = viewVoiceRecordDotProvider()
        val tvTitle = tvVoiceRecordTitleProvider()
        val tvSubtitle = tvVoiceRecordSubtitleProvider()
        val tvTimer = tvVoiceRecordTimerProvider()

        if (isCancelState) {
            overlay.setBackgroundResource(R.drawable.bg_chat_voice_record_overlay_cancel)
            recordDot.setBackgroundResource(R.drawable.bg_voice_record_dot_cancel)
            tvTitle.text = context.getString(R.string.release_to_cancel)
            tvSubtitle.text = context.getString(R.string.slide_down_continue)
        } else {
            overlay.setBackgroundResource(R.drawable.bg_chat_voice_record_overlay)
            recordDot.setBackgroundResource(R.drawable.bg_voice_record_dot)
            tvTitle.text = context.getString(R.string.recording)
            tvSubtitle.text = context.getString(R.string.release_send_slide_cancel)
        }
        tvTimer.text = formatRecordingDuration()
        ensureOverlayTicker()
        recordDot.animate().cancel()
        recordDot.startAnimation(android.view.animation.AnimationUtils.loadAnimation(context, R.anim.voice_record_pulse))
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.translationY = 8f
            overlay.visibility = View.VISIBLE
        }
        overlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(UiMotion.FAST)
            .setInterpolator(UiMotion.STANDARD_EASING)
            .start()
    }

    fun hideVoiceRecordOverlay() {
        val overlay = layoutVoiceRecordOverlayProvider()
        viewVoiceRecordDotProvider().clearAnimation()
        stopOverlayTicker()
        if (overlay.visibility != View.VISIBLE) return
        overlay.animate().cancel()
        overlay.animate()
            .alpha(0f)
            .translationY(12f)
            .setDuration(UiMotion.FAST)
            .setInterpolator(UiMotion.EXIT_EASING)
            .withEndAction {
                overlay.visibility = View.GONE
            }
            .start()
    }

    fun handleVoiceButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!ensureAiVoiceFeatureEnabled()) return true
                if (!ensureRecordPermission()) return true
                clearPendingLongPress()
                LocalAsrService.resetStreamingBuffer()
                setIsFingerDown(true)
                setIsWannaCancel(false)
                btnVoiceHoldProvider().animate().alpha(0.94f).setDuration(80).start()
                setLongPressTriggered(true)
                Utils.vibrate(context, 10)
                val started = startVoiceRecording()
                if (!started) {
                    setIsRecording(false)
                    hideVoiceRecordOverlay()
                    Utils.toast(context, context.getString(R.string.toast_record_start_failed))
                } else {
                    btnVoiceHoldProvider().text = context.getString(R.string.release_send_slide_cancel)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRecording()) {
                    val shouldCancel = event.y < -150f
                    if (shouldCancel != isWannaCancel()) {
                        setIsWannaCancel(shouldCancel)
                        Utils.vibrate(context, if (shouldCancel) 30 else 10)
                        btnVoiceHoldProvider().text = if (shouldCancel) context.getString(R.string.release_to_cancel) else context.getString(R.string.release_send_slide_cancel)
                        showVoiceRecordOverlay(shouldCancel)
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                clearPendingLongPress()
                setIsFingerDown(false)
                btnVoiceHoldProvider().animate().alpha(1f).setDuration(80).start()
                if (!isRecording()) return true
                if (isWannaCancel()) {
                    stopVoiceRecording { file, _ ->
                        file?.delete()
                        LocalAsrService.resetStreamingBuffer()
                        context.runOnUiThread {
                            btnVoiceHoldProvider().text = context.getString(R.string.hold_to_talk)
                            resetVoiceHoldButtonAppearance()
                        }
                    }
                    Utils.toast(context, context.getString(R.string.toast_canceled))
                } else {
                    val holdDurationMs = (System.currentTimeMillis() - getRecordingStartAt()).coerceAtLeast(0L)
                    stopVoiceRecording { file, durationSec ->
                        context.runOnUiThread {
                            btnVoiceHoldProvider().text = context.getString(R.string.hold_to_talk)
                            resetVoiceHoldButtonAppearance()
                        }
                        if (holdDurationMs < 450L) {
                            file?.delete()
                            Utils.toast(context, context.getString(R.string.toast_hold_longer))
                            return@stopVoiceRecording
                        }
                        if (file == null) {
                            context.runOnUiThread { Utils.toast(context, context.getString(R.string.no_clear_voice)) }
                            return@stopVoiceRecording
                        }
                        onVoiceRecorded(file, durationSec)
                    }
                }
                hideVoiceRecordOverlay()
                setLongPressTriggered(false)
                return true
            }
        }
        return false
    }

    private fun ensureOverlayTicker() {
        stopOverlayTicker()
        val ticker = object : Runnable {
            override fun run() {
                if (!isRecording()) return
                tvVoiceRecordTimerProvider().text = formatRecordingDuration()
                overlayUiHandler.postDelayed(this, 120L)
            }
        }
        overlayTicker = ticker
        overlayUiHandler.post(ticker)
    }

    private fun stopOverlayTicker() {
        overlayTicker?.let { overlayUiHandler.removeCallbacks(it) }
        overlayTicker = null
    }

    private fun formatRecordingDuration(): String {
        val elapsedMs = (System.currentTimeMillis() - getRecordingStartAt()).coerceAtLeast(0L)
        val totalSeconds = (elapsedMs / 1000L).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }
}

