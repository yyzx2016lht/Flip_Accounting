package com.taostudio.tapaccounting.ui.dialog

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.BadTokenException
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.kizitonwose.calendar.core.CalendarDay
import com.kizitonwose.calendar.core.DayPosition
import com.kizitonwose.calendar.core.daysOfWeek
import com.kizitonwose.calendar.view.MonthDayBinder
import com.kizitonwose.calendar.view.ViewContainer
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.main.YearMonthPickerDialog
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object ElegantDatePickerSheet {

    /**
     * 日期范围选择模式。
     * 先选开始日期，再选结束日期，确认后回调 (startMillis, endMillis)。
     */
    fun showRange(
        context: Context,
        initialStartMillis: Long? = null,
        initialEndMillis: Long? = null,
        onRangeSelected: (startMillis: Long, endMillis: Long) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_elegant_date_picker, null)
        val baseBottomPadding = view.paddingBottom
        view.setPadding(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            baseBottomPadding + navigationBarHeight(context)
        )
        val calendarView = view.findViewById<com.kizitonwose.calendar.view.CalendarView>(R.id.calendar_picker_view)
        val tvMonthTitle = view.findViewById<TextView>(R.id.tv_calendar_month_title)
        val btnPrevYear = view.findViewById<ImageView>(R.id.btn_calendar_prev_year)
        val btnPrev = view.findViewById<ImageView>(R.id.btn_calendar_prev_month)
        val btnNext = view.findViewById<ImageView>(R.id.btn_calendar_next_month)
        val btnNextYear = view.findViewById<ImageView>(R.id.btn_calendar_next_year)
        val btnCancel = view.findViewById<View>(R.id.btn_calendar_cancel)
        val btnConfirm = view.findViewById<View>(R.id.btn_calendar_confirm)

        val zoneId = ZoneId.systemDefault()
        val today = LocalDate.now(zoneId)
        // 状态：null = 未选择，正在选开始日期；non-null = 已选开始日期，正在选结束日期
        var startDate: LocalDate? = initialStartMillis?.toLocalDate(zoneId)
        var endDate: LocalDate? = initialEndMillis?.toLocalDate(zoneId)
        // 如果已有初始范围，直接进入"选结束日期"模式
        var selectingEnd = startDate != null && endDate != null

        val startMonth = YearMonth.from(startDate ?: today).minusYears(10)
        val endMonth = YearMonth.from(endDate ?: today).plusYears(10)
        var visibleMonth = YearMonth.from(startDate ?: today)
        val firstDayOfWeek = DayOfWeek.MONDAY
        val monthFormatter = DateTimeFormatter.ofPattern("yyyy年MM月", Locale.CHINA)

        // 更新标题提示
        fun updateTitleHint() {
            tvMonthTitle.text = when {
                startDate == null -> "选择开始日期"
                selectingEnd && endDate == null -> "选择结束日期"
                else -> visibleMonth.format(monthFormatter)
            }
        }

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

                container.dayText.alpha = 1f
                container.dayText.isEnabled = true
                container.dayText.text = date.dayOfMonth.toString()

                val isStart = date == startDate
                val isEnd = date == endDate
                val inRange = startDate != null && endDate != null &&
                    !date.isBefore(startDate) && !date.isAfter(endDate)

                when {
                    isStart || isEnd -> {
                        container.dayText.setTextColor(Color.WHITE)
                        container.dayText.setBackgroundResource(R.drawable.bg_elegant_calendar_day_selected)
                    }
                    inRange -> {
                        container.dayText.setTextColor(Color.parseColor("#5C6BC0"))
                        container.dayText.setBackgroundResource(R.drawable.bg_elegant_calendar_day_today)
                    }
                    date == today -> {
                        container.dayText.setTextColor(Color.parseColor("#5C6BC0"))
                        container.dayText.setBackgroundResource(R.drawable.bg_elegant_calendar_day_today)
                    }
                    else -> {
                        container.dayText.setTextColor(Color.parseColor("#24374F"))
                        container.dayText.background = null
                    }
                }

                container.dayText.setOnClickListener {
                    if (startDate == null || (startDate != null && endDate != null)) {
                        // 开始新的选择
                        startDate = date
                        endDate = null
                        selectingEnd = true
                        calendarView.notifyCalendarChanged()
                        updateTitleHint()
                    } else if (selectingEnd) {
                        // 选择结束日期
                        if (date.isBefore(startDate)) {
                            // 结束日期早于开始日期，交换
                            endDate = startDate
                            startDate = date
                        } else {
                            endDate = date
                        }
                        calendarView.notifyCalendarChanged()
                        updateTitleHint()
                    }
                }
            }
        }

        calendarView.setup(startMonth, endMonth, firstDayOfWeek)
        calendarView.scrollToMonth(visibleMonth)
        updateTitleHint()
        calendarView.monthScrollListener = { month ->
            visibleMonth = month.yearMonth
            if (startDate == null || (selectingEnd && endDate == null)) {
                // 保持提示文字
            } else {
                tvMonthTitle.text = visibleMonth.format(monthFormatter)
            }
        }

        btnPrevYear.setOnClickListener {
            val target = visibleMonth.minusYears(1)
            if (target >= startMonth) calendarView.smoothScrollToMonth(target)
        }
        btnPrev.setOnClickListener {
            val target = visibleMonth.minusMonths(1)
            if (target >= startMonth) calendarView.smoothScrollToMonth(target)
        }
        btnNext.setOnClickListener {
            val target = visibleMonth.plusMonths(1)
            if (target <= endMonth) calendarView.smoothScrollToMonth(target)
        }
        btnNextYear.setOnClickListener {
            val target = visibleMonth.plusYears(1)
            if (target <= endMonth) calendarView.smoothScrollToMonth(target)
        }
        tvMonthTitle.setOnClickListener {
            YearMonthPickerDialog.show(
                context = context,
                title = "选择月份",
                initialYear = visibleMonth.year,
                initialMonth = visibleMonth.monthValue
            ) { year, month ->
                val target = YearMonth.of(year, month)
                if (target in startMonth..endMonth) {
                    calendarView.smoothScrollToMonth(target)
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val s = startDate
            val e = endDate
            if (s != null && e != null) {
                onRangeSelected(s.toEpochMillis(zoneId), e.toEpochMillis(zoneId))
                dialog.dismiss()
            } else if (s != null && e == null) {
                // 只选了开始日期，自动设结束日期为当天
                onRangeSelected(s.toEpochMillis(zoneId), today.toEpochMillis(zoneId))
                dialog.dismiss()
            }
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
            val behavior = BottomSheetBehavior.from(bottomSheet)
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            behavior.apply {
                skipCollapsed = true
                isFitToContents = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            bottomSheet.post {
                val contentHeight = (bottomSheet as? ViewGroup)?.getChildAt(0)?.measuredHeight
                    ?: bottomSheet.measuredHeight
                val desiredHeight = minOf(contentHeight, maxHeight).coerceAtLeast(1)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheet.requestLayout()
                behavior.peekHeight = desiredHeight
            }
        }
        try {
            dialog.show()
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }

    fun show(
        context: Context,
        initialTimeMillis: Long? = null,
        minTimeMillis: Long? = null,
        maxTimeMillis: Long? = null,
        onDateSelected: (Long) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_elegant_date_picker, null)
        val baseBottomPadding = view.paddingBottom
        view.setPadding(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            baseBottomPadding + navigationBarHeight(context)
        )
        val calendarView = view.findViewById<com.kizitonwose.calendar.view.CalendarView>(R.id.calendar_picker_view)
        val tvMonthTitle = view.findViewById<TextView>(R.id.tv_calendar_month_title)
        val btnPrevYear = view.findViewById<ImageView>(R.id.btn_calendar_prev_year)
        val btnPrev = view.findViewById<ImageView>(R.id.btn_calendar_prev_month)
        val btnNext = view.findViewById<ImageView>(R.id.btn_calendar_next_month)
        val btnNextYear = view.findViewById<ImageView>(R.id.btn_calendar_next_year)
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
                        container.dayText.setTextColor(Color.parseColor("#5C6BC0"))
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

        btnPrevYear.setOnClickListener {
            val target = visibleMonth.minusYears(1)
            if (target >= startMonth) {
                calendarView.smoothScrollToMonth(target)
            }
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
        btnNextYear.setOnClickListener {
            val target = visibleMonth.plusYears(1)
            if (target <= endMonth) {
                calendarView.smoothScrollToMonth(target)
            }
        }
        tvMonthTitle.setOnClickListener {
            YearMonthPickerDialog.show(
                context = context,
                title = "选择月份",
                initialYear = visibleMonth.year,
                initialMonth = visibleMonth.monthValue
            ) { year, month ->
                val target = YearMonth.of(year, month)
                if (target in startMonth..endMonth) {
                    calendarView.smoothScrollToMonth(target)
                }
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
            val behavior = BottomSheetBehavior.from(bottomSheet)
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.88f).toInt()
            behavior.apply {
                skipCollapsed = true
                isFitToContents = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
            bottomSheet.post {
                val contentHeight = (bottomSheet as? ViewGroup)?.getChildAt(0)?.measuredHeight
                    ?: bottomSheet.measuredHeight
                val desiredHeight = minOf(contentHeight, maxHeight).coerceAtLeast(1)
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheet.requestLayout()
                behavior.peekHeight = desiredHeight
            }
        }
        try {
            dialog.show()
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }

    private fun Long.toLocalDate(zoneId: ZoneId): LocalDate {
        return Instant.ofEpochMilli(this).atZone(zoneId).toLocalDate()
    }

    private fun LocalDate.toEpochMillis(zoneId: ZoneId): Long {
        return atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun navigationBarHeight(context: Context): Int {
        val res = context.resources
        val id = res.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) res.getDimensionPixelSize(id) else 0
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

