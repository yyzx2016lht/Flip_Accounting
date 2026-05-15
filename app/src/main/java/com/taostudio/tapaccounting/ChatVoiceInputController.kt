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
    private val btnMoreInputProvider: () -> ImageView,
    private val btnVoiceToggleProvider: () -> ImageView,
    private val btnVoiceHoldProvider: () -> MaterialButton,
    private val layoutVoiceRecordOverlayProvider: () -> View,
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
    private val refreshVoiceSupportHint: () -> Unit
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
        btnVoiceToggleProvider().setColorFilter(Color.parseColor("#456387"))
        btnVoiceHoldProvider().backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F2F3F5"))
        btnVoiceHoldProvider().text = "按住说话，松开发送"
        btnVoiceHoldProvider().alpha = 1f
        updateInputActionUi()
    }

    fun updateInputActionUi() {
        val hasText = etInputProvider().text?.toString()?.trim()?.isNotEmpty() == true
        if (isVoiceMode()) {
            btnSendProvider().visibility = android.view.View.GONE
            btnMoreInputProvider().visibility = android.view.View.VISIBLE
            btnSendProvider().alpha = 0.4f
            return
        }
        btnSendProvider().visibility = if (hasText) android.view.View.VISIBLE else android.view.View.GONE
        btnMoreInputProvider().visibility = if (hasText) android.view.View.GONE else android.view.View.VISIBLE
        btnSendProvider().alpha = if (hasText) 1f else 0.4f
    }

    fun startRecordingButtonPulse() {
        btnVoiceHoldProvider().animate().cancel()
        btnVoiceHoldProvider().animate().alpha(0.88f).setDuration(180).withEndAction {
            if (isRecording() && !isWannaCancel()) {
                btnVoiceHoldProvider().animate().alpha(1f).setDuration(180).withEndAction {
                    if (isRecording() && !isWannaCancel()) startRecordingButtonPulse()
                }.start()
            }
        }.start()
    }

    fun stopRecordingButtonPulse() {
        btnVoiceHoldProvider().animate().cancel()
        btnVoiceHoldProvider().animate().alpha(1f).setDuration(120).start()
    }

    fun showVoiceRecordOverlay(isCancelState: Boolean) {
        val overlay = layoutVoiceRecordOverlayProvider()
        val ivState = ivVoiceRecordStateProvider()
        val tvTitle = tvVoiceRecordTitleProvider()
        val tvSubtitle = tvVoiceRecordSubtitleProvider()
        val tvTimer = tvVoiceRecordTimerProvider()

        if (isCancelState) {
            overlay.setBackgroundResource(R.drawable.bg_chat_voice_record_overlay_cancel)
            ivState.setImageResource(R.drawable.ic_delete)
            ivState.setColorFilter(Color.parseColor("#FFC4C4"))
            tvTitle.text = "松开取消发送"
            tvSubtitle.text = "手指下移可继续发送"
        } else {
            overlay.setBackgroundResource(R.drawable.bg_chat_voice_record_overlay)
            ivState.setImageResource(R.drawable.ic_chat_mic)
            ivState.setColorFilter(Color.WHITE)
            tvTitle.text = "正在录音..."
            tvSubtitle.text = "松开发送，上滑取消"
        }
        tvTimer.text = formatRecordingDuration()
        ensureOverlayTicker()
        overlay.animate().cancel()
        if (overlay.visibility != View.VISIBLE) {
            overlay.alpha = 0f
            overlay.translationY = 12f
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
                    Utils.toast(context, "录音启动失败")
                } else {
                    btnVoiceHoldProvider().text = "松开发送，上滑取消"
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (isRecording()) {
                    val shouldCancel = event.y < -150f
                    if (shouldCancel != isWannaCancel()) {
                        setIsWannaCancel(shouldCancel)
                        Utils.vibrate(context, if (shouldCancel) 30 else 10)
                        btnVoiceHoldProvider().text = if (shouldCancel) "松开取消发送" else "松开发送，上滑取消"
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
                        context.runOnUiThread { btnVoiceHoldProvider().text = "按住说话，松开发送" }
                    }
                    Utils.toast(context, "已取消")
                } else {
                    val holdDurationMs = (System.currentTimeMillis() - getRecordingStartAt()).coerceAtLeast(0L)
                    stopVoiceRecording { file, durationSec ->
                        context.runOnUiThread { btnVoiceHoldProvider().text = "按住说话，松开发送" }
                        if (holdDurationMs < 450L) {
                            file?.delete()
                            Utils.toast(context, "按住稍久一点再说话")
                            return@stopVoiceRecording
                        }
                        if (file == null) {
                            context.runOnUiThread { Utils.toast(context, "未检测到清晰语音") }
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

