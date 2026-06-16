package com.taostudio.tapaccounting

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatUiHelperController(
    private val context: ChatActivity,
    private val displayMessagesProvider: () -> List<ChatDisplayItem>
) {
    fun showPageCenterDialog(dialog: AlertDialog, widthRatio: Float = 0.86f) {
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = context,
            widthRatio = widthRatio,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    fun showCustomConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_delete_followup_confirm, null)
        view.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        view.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        val btnOk = view.findViewById<TextView>(R.id.btn_followup_confirm_ok)
        btnOk.text = confirmText
        btnOk.setBackgroundResource(
            if (isDanger) R.drawable.bg_delete_followup_danger_btn
            else R.drawable.bg_delete_followup_primary_btn
        )

        val dialog = AlertDialog.Builder(ContextThemeWrapper(context, R.style.Theme_TapAccounting))
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val btnCancel = view.findViewById<TextView>(R.id.btn_followup_confirm_cancel)
        btnCancel.text = context.getString(R.string.cancel)
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        btnOk.setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }

        showPageCenterDialog(dialog)
    }

    fun showPageBottomDialog(dialog: AlertDialog) {
        val margin = (12 * context.resources.displayMetrics.density).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val targetWidth = (screenWidth - margin * 2).coerceAtLeast(1)
        val widthRatio = targetWidth.toFloat() / screenWidth.toFloat()
        OverlayDialogs.showPageBottomDialog(
            dialog = dialog,
            ctx = context,
            widthRatio = widthRatio,
            y = 0,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    fun copyToClipboard(label: String, text: String, toast: String = "已复制") {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Utils.toast(context, toast)
    }

    fun loadUserAvatar(iv: ImageView) {
        val path = Prefs.getUserChatAvatarPath(context)
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = iv,
                file = file,
                placeholderRes = R.drawable.ic_user_avatar_default,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            iv.setImageResource(R.drawable.ic_user_avatar_default)
        }
    }

    fun loadAiAvatar(iv: ImageView) {
        val path = Prefs.getAiChatAvatarPath(context)
        val file = if (path.isNotBlank()) File(path) else null
        if (file != null && file.exists()) {
            GlideLocalFiles.load(
                target = iv,
                file = file,
                placeholderRes = R.drawable.ic_ai_default_avatar,
                circleCrop = true,
                overrideSize = 128
            )
        } else {
            iv.setImageResource(R.drawable.ic_ai_default_avatar)
        }
    }

    fun showSoftKeyboard(view: View) {
        view.post {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideSoftKeyboard(view: View) {
        val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
            as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun formatChatMessageTime(ms: Long): String {
        val nowMs = System.currentTimeMillis()
        if (ms > nowMs) return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

        val diffMs = nowMs - ms
        if (diffMs < 60_000L) return "刚刚"
        if (diffMs < 60L * 60L * 1000L) return "${diffMs / 60_000L} 分钟前"

        val now = java.util.Calendar.getInstance()
        val target = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val dayDiff = dayDiffFromToday(target)
        return when {
            dayDiff == 0L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            dayDiff == 1L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            dayDiff in 2L..6L -> "${weekdayLabel(target)} ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) ->
                SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    fun shouldShowTimestamp(position: Int, timestamp: Long): Boolean {
        if (position <= 0) return true
        val prev = displayMessagesProvider().getOrNull(position - 1)?.timestamp ?: return true
        return (timestamp - prev) >= 10 * 60 * 1000L
    }

    private fun dayDiffFromToday(target: java.util.Calendar): Long {
        val today = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val other = target.clone() as java.util.Calendar
        other.set(java.util.Calendar.HOUR_OF_DAY, 0)
        other.set(java.util.Calendar.MINUTE, 0)
        other.set(java.util.Calendar.SECOND, 0)
        other.set(java.util.Calendar.MILLISECOND, 0)
        return (today.timeInMillis - other.timeInMillis) / (24L * 60L * 60L * 1000L)
    }

    private fun weekdayLabel(calendar: java.util.Calendar): String =
        when (calendar.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> "周一"
            java.util.Calendar.TUESDAY -> "周二"
            java.util.Calendar.WEDNESDAY -> "周三"
            java.util.Calendar.THURSDAY -> "周四"
            java.util.Calendar.FRIDAY -> "周五"
            java.util.Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
}

