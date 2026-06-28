package com.taostudio.tapaccounting.ui.common

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.GridLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.taostudio.tapaccounting.R

object DayPickerDialog {

    fun show(
        context: Context,
        title: String,
        selectedDay: Int,
        onSelected: (Int) -> Unit
    ) {
        val themed = ContextThemeWrapper(context, R.style.Theme_TapAccounting)
        val grid = GridLayout(themed).apply {
            columnCount = 7
            setPadding(24, 16, 24, 8)
        }
        val density = context.resources.displayMetrics.density
        val cell = (40 * density).toInt()

        var dialog: AlertDialog? = null
        fun addCell(label: String, day: Int) {
            val tv = TextView(themed).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(if (day == selectedDay) Color.WHITE else Color.parseColor("#374151"))
                background = ContextCompat.getDrawable(
                    themed,
                    if (day == selectedDay) R.drawable.bg_dialog_button_primary else R.drawable.bg_dialog_button_cancel
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(4, 4, 4, 4)
                }
                setOnClickListener {
                    onSelected(day)
                    dialog?.dismiss()
                }
            }
            grid.addView(tv)
        }

        addCell("未设置", 0)
        for (day in 1..31) {
            addCell(day.toString(), day)
        }

        dialog = AlertDialog.Builder(themed)
            .setTitle(title)
            .setView(grid)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    fun formatDay(day: Int): String = if (day <= 0) "未设置" else "${day}号"
}
