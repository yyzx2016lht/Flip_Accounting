package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView

class ChatMessageMenuController(
    private val context: ChatActivity,
    private val parseVoicePayload: (String) -> VoicePayload,
    private val hideVoiceTranscript: (ChatDisplayItem) -> Unit,
    private val transcribeVoiceMessage: (ChatDisplayItem, Boolean, Boolean) -> Unit,
    private val copyToClipboard: (String, String, String) -> Unit,
    private val enterVoiceSelectionMode: (ChatDisplayItem?) -> Unit,
    private val requestDeleteFromLongPressMenu: (ChatDisplayItem) -> Unit,
    private val isVoiceMode: () -> Boolean,
    private val setVoiceMode: (Boolean) -> Unit,
    private val updateVoiceModeUi: () -> Unit,
    private val etInputProvider: () -> EditText,
    private val showSoftKeyboard: (View) -> Unit,
    private val updateInputActionUi: () -> Unit
) {
    fun showVoiceMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_msg_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.VISIBLE
        val voice = item.voice ?: parseVoicePayload(item.content)
        val hasTranscript = voice.transcript.trim().isNotBlank()
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility =
            if (hasTranscript) View.VISIBLE else View.GONE
        val tvTranscribe = popupView.findViewById<TextView>(R.id.tv_menu_transcribe)
        val ivTranscribe = popupView.findViewById<ImageView>(R.id.iv_menu_transcribe)
        tvTranscribe.text = if (hasTranscript) "隐藏转写" else "转文字"
        ivTranscribe.setImageResource(if (hasTranscript) R.drawable.ic_delete else R.drawable.ic_mic)
        ivTranscribe.setColorFilter(if (hasTranscript) Color.parseColor("#FFB4B4") else Color.WHITE)

        popupView.findViewById<View>(R.id.menu_item_transcribe).setOnClickListener {
            popup.dismiss()
            if (hasTranscript) {
                hideVoiceTranscript(item)
            } else {
                transcribeVoiceMessage(item, true, false)
            }
        }
        popupView.findViewById<View>(R.id.menu_item_retranscribe).setOnClickListener {
            popup.dismiss()
            transcribeVoiceMessage(item, true, true)
        }
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).setOnClickListener {
            popup.dismiss()
            val transcript = voice.transcript.trim()
            if (transcript.isNotBlank()) {
                copyToClipboard("voice_transcript", transcript, "已复制转写文本")
            }
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            requestDeleteFromLongPressMenu(item)
        }
        popup.showAsDropDown(anchor, -40, -anchor.height - 16)
    }

    fun showTextMessageMenu(anchor: View, item: ChatDisplayItem) {
        val popupView = LayoutInflater.from(context).inflate(R.layout.popup_msg_menu, null)
        val popup = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        popupView.findViewById<View>(R.id.menu_item_copy).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_edit_resend).visibility = View.VISIBLE
        popupView.findViewById<View>(R.id.menu_item_transcribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_retranscribe).visibility = View.GONE
        popupView.findViewById<View>(R.id.menu_item_copy_transcript).visibility = View.GONE

        popupView.findViewById<View>(R.id.menu_item_copy).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            copyToClipboard("chat_message", text, "已复制")
        }
        popupView.findViewById<View>(R.id.menu_item_edit_resend).setOnClickListener {
            popup.dismiss()
            val text = item.content.trim()
            if (text.isEmpty()) return@setOnClickListener
            if (isVoiceMode()) {
                setVoiceMode(false)
                updateVoiceModeUi()
            }
            val etInput = etInputProvider()
            etInput.setText(text)
            etInput.setSelection(text.length)
            etInput.requestFocus()
            showSoftKeyboard(etInput)
            updateInputActionUi()
        }
        popupView.findViewById<View>(R.id.menu_item_multiselect).setOnClickListener {
            popup.dismiss()
            enterVoiceSelectionMode(item)
        }
        popupView.findViewById<View>(R.id.menu_item_delete).setOnClickListener {
            popup.dismiss()
            requestDeleteFromLongPressMenu(item)
        }
        popup.showAsDropDown(anchor, -40, -anchor.height - 16)
    }
}
