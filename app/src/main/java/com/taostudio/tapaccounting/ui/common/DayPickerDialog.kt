package com.taostudio.tapaccounting.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.widget.GridLayout
import android.widget.LinearLayout
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
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val container = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(16))
            background = ContextCompat.getDrawable(themed, R.drawable.bg_dialog_rounded)
        }

        container.addView(TextView(themed).apply {
            text = title
            setTextColor(ContextCompat.getColor(themed, R.color.text_primary))
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })

        var dialog: AlertDialog? = null
        fun select(day: Int) {
            onSelected(day)
            dialog?.dismiss()
        }

        container.addView(TextView(themed).apply {
            text = "不设置"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(if (selectedDay == 0) Color.WHITE else ContextCompat.getColor(themed, R.color.text_secondary))
            background = ContextCompat.getDrawable(
                themed,
                if (selectedDay == 0) R.drawable.bg_day_picker_cell_selected else R.drawable.bg_day_picker_clear
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(10)
            }
            setOnClickListener { select(0) }
        })

        val grid = GridLayout(themed).apply {
            columnCount = 7
        }
        val cell = dp(38)

        fun addCell(label: String, day: Int) {
            val tv = TextView(themed).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(if (day == selectedDay) Color.WHITE else Color.parseColor("#374151"))
                background = ContextCompat.getDrawable(
                    themed,
                    if (day == selectedDay) R.drawable.bg_day_picker_cell_selected else R.drawable.bg_day_picker_cell
                )
                layoutParams = GridLayout.LayoutParams().apply {
                    width = cell
                    height = cell
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setOnClickListener { select(day) }
            }
            grid.addView(tv)
        }

        for (day in 1..31) {
            addCell(day.toString(), day)
        }

        container.addView(grid)

        dialog = AlertDialog.Builder(themed)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    fun formatDay(day: Int): String = if (day <= 0) "未设置" else "${day}号"
}
