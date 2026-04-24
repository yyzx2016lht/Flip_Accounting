package tao.test.flipaccounting.ui.dialog

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import tao.test.flipaccounting.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ElegantDatePickerSheet {

    fun show(
        context: Context,
        initialTimeMillis: Long? = null,
        minTimeMillis: Long? = null,
        maxTimeMillis: Long? = null,
        onDateSelected: (Long) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_elegant_date_picker, null)
        val calendarView = view.findViewById<com.kizitonwose.calendar.view.CalendarView>(R.id.calendar_picker_view)
        val tvMonthTitle = view.findViewById<TextView>(R.id.tv_calendar_month_title)
        val btnPrev = view.findViewById<ImageView>(R.id.btn_calendar_prev_month)
        val btnNext = view.findViewById<ImageView>(R.id.btn_calendar_next_month)
        val btnCancel = view.findViewById<View>(R.id.btn_calendar_cancel)
        val btnConfirm = view.findViewById<View>(R.id.btn_calendar_confirm)

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        val minDate = minTimeMillis?.toLocalDate(zoneId)
        val maxDate = maxTimeMillis?.toLocalDate(zoneId)
        var selectedDate = (initialTimeMillis?.toLocalDate(zoneId) ?: today)
            .coerceAtLeast(minDate ?: LocalDate.MIN)
            .coerceAtMost(maxDate ?: LocalDate.MAX)

        val startMonth = YearMonth.from(minDate ?: selectedDate.minusYears(30))
        val endMonth = YearMonth.from(maxDate ?: selectedDate.plusYears(10))
        var visibleMonth = YearMonth.from(selectedDate)
        val firstDayOfWeek = DayOfWeek.MONDAY
        val monthFormatter = DateTimeFormatter.ofPattern("yyyy年MM月", Locale.CHINA)

        listOf(
            R.id.tv_calendar_week_1,
            R.id.tv_calendar_week_2,
            R.id.tv_calendar_week_3,
            R.id.tv_calendar_week_4,
            R.id.tv_calendar_week_5,
            R.id.tv_calendar_week_6,
            R.id.tv_calendar_week_7
        ).forEachIndexed { index, id ->
            val day = daysOfWeek(firstDayOfWeek)[index]
            view.findViewById<TextView>(id).text = day.toCnWeekLabel()
        }

        class DayViewContainer(dayView: View) : ViewContainer(dayView) {
            val dayText = dayView.findViewById<TextView>(R.id.tv_calendar_day)
        }

        calendarView.dayBinder = object : MonthDayBinder<DayViewContainer> {
            override fun create(view: View): DayViewContainer = DayViewContainer(view)

            override fun bind(container: DayViewContainer, data: CalendarDay) {
                val date = data.date
                val isInMonth = data.position == DayPosition.MonthDate
                if (!isInMonth) {
                    container.dayText.text = ""
                    container.dayText.background = null
                    container.dayText.isEnabled = false
                    container.dayText.alpha = 0f
                    container.dayText.setOnClickListener(null)
                    return
                }

                val inRange = (minDate == null || !date.isBefore(minDate)) &&
                    (maxDate == null || !date.isAfter(maxDate))
                container.dayText.alpha = if (inRange) 1f else 0.35f
                container.dayText.isEnabled = inRange
                container.dayText.text = date.dayOfMonth.toString()

                when {
                    date == selectedDate -> {
                        container.dayText.setTextColor(Color.WHITE)
                        container.dayText.setBackgroundResource(R.drawable.bg_elegant_calendar_day_selected)
                    }
                    date == today -> {
                        container.dayText.setTextColor(Color.parseColor("#2C74FF"))
                        container.dayText.setBackgroundResource(R.drawable.bg_elegant_calendar_day_today)
                    }
                    else -> {
                        container.dayText.setTextColor(Color.parseColor("#24374F"))
                        container.dayText.background = null
                    }
                }

                container.dayText.setOnClickListener {
                    if (!inRange) return@setOnClickListener
                    val oldDate = selectedDate
                    selectedDate = date
                    calendarView.notifyDateChanged(oldDate)
                    calendarView.notifyDateChanged(selectedDate)
                }
            }
        }

        calendarView.setup(startMonth, endMonth, firstDayOfWeek)
        calendarView.scrollToMonth(visibleMonth)
        tvMonthTitle.text = visibleMonth.format(monthFormatter)
        calendarView.monthScrollListener = { month ->
            visibleMonth = month.yearMonth
            tvMonthTitle.text = visibleMonth.format(monthFormatter)
        }

        btnPrev.setOnClickListener {
            val target = visibleMonth.minusMonths(1)
            if (target >= startMonth) {
                calendarView.smoothScrollToMonth(target)
            }
        }
        btnNext.setOnClickListener {
            val target = visibleMonth.plusMonths(1)
            if (target <= endMonth) {
                calendarView.smoothScrollToMonth(target)
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            onDateSelected(selectedDate.toEpochMillis(zoneId))
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheetId = context.resources.getIdentifier(
                "design_bottom_sheet",
                "id",
                "com.google.android.material"
            )
            if (bottomSheetId == 0) return@setOnShowListener
            val bottomSheet = dialog.findViewById<View>(bottomSheetId) ?: return@setOnShowListener
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                isFitToContents = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun Long.toLocalDate(zoneId: ZoneId): LocalDate {
        return Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
    }

    private fun LocalDate.toEpochMillis(zoneId: ZoneId): Long {
        return atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun DayOfWeek.toCnWeekLabel(): String {
        return when (this) {
            DayOfWeek.MONDAY -> "一"
            DayOfWeek.TUESDAY -> "二"
            DayOfWeek.WEDNESDAY -> "三"
            DayOfWeek.THURSDAY -> "四"
            DayOfWeek.FRIDAY -> "五"
            DayOfWeek.SATURDAY -> "六"
            DayOfWeek.SUNDAY -> "日"
        }
    }
}
