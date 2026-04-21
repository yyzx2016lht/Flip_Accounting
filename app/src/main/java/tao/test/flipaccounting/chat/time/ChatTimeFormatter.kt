package tao.test.flipaccounting.chat.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object ChatTimeFormatter {

    fun formatTime(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

    fun formatChatMessageTime(ms: Long): String {
        val nowMs = System.currentTimeMillis()
        if (ms > nowMs) return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

        val diffMs = nowMs - ms
        if (diffMs < 60_000L) return "刚刚"
        if (diffMs < 60L * 60L * 1000L) return "${diffMs / 60_000L} 分钟前"

        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply { timeInMillis = ms }
        val dayDiff = dayDiffFromToday(target)
        return when {
            dayDiff == 0L -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
            dayDiff == 1L -> "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            dayDiff in 2L..6L -> "${weekdayLabel(target)} ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))}"
            now.get(Calendar.YEAR) == target.get(Calendar.YEAR) ->
                SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
            else -> SimpleDateFormat("yyyy年M月d日 HH:mm", Locale.getDefault()).format(Date(ms))
        }
    }

    private fun dayDiffFromToday(target: Calendar): Long {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val other = target.clone() as Calendar
        other.set(Calendar.HOUR_OF_DAY, 0)
        other.set(Calendar.MINUTE, 0)
        other.set(Calendar.SECOND, 0)
        other.set(Calendar.MILLISECOND, 0)
        return (today.timeInMillis - other.timeInMillis) / (24L * 60L * 60L * 1000L)
    }

    private fun weekdayLabel(calendar: Calendar): String =
        when (calendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "周一"
            Calendar.TUESDAY -> "周二"
            Calendar.WEDNESDAY -> "周三"
            Calendar.THURSDAY -> "周四"
            Calendar.FRIDAY -> "周五"
            Calendar.SATURDAY -> "周六"
            else -> "周日"
        }
}
