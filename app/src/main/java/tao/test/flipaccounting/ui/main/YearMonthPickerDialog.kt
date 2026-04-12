package tao.test.flipaccounting.ui.main

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import tao.test.flipaccounting.R
import java.util.Locale

object YearMonthPickerDialog {

    fun show(
        context: Context,
        title: String,
        initialYear: Int,
        initialMonth: Int,
        yearOnly: Boolean = false,
        onConfirm: (year: Int, month: Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.layout_year_month_picker, null, false)

        val tvTitle = view.findViewById<TextView>(R.id.tv_picker_title)
        val tvPreview = view.findViewById<TextView>(R.id.tv_picker_preview)
        val monthContainer = view.findViewById<LinearLayout>(R.id.layout_month_container)
        val npYear = view.findViewById<NumberPicker>(R.id.np_picker_year)
        val npMonth = view.findViewById<NumberPicker>(R.id.np_picker_month)
        val btnCancel = view.findViewById<View>(R.id.btn_picker_cancel)
        val btnConfirm = view.findViewById<View>(R.id.btn_picker_confirm)

        tvTitle.text = title

        npYear.minValue = 2000
        npYear.maxValue = 2100
        npYear.value = initialYear
        npYear.wrapSelectorWheel = false

        npMonth.minValue = 1
        npMonth.maxValue = 12
        npMonth.value = initialMonth.coerceIn(1, 12)
        npMonth.wrapSelectorWheel = true
        npMonth.setFormatter { String.format(Locale.getDefault(), "%02d", it) }

        monthContainer.visibility = if (yearOnly) View.GONE else View.VISIBLE

        var suppressLinkedUpdate = false

        val refreshPreview = {
            tvPreview.text = if (yearOnly) {
                String.format(Locale.getDefault(), "%04d", npYear.value)
            } else {
                String.format(Locale.getDefault(), "%04d-%02d", npYear.value, npMonth.value)
            }
        }
        refreshPreview()

        npYear.setOnValueChangedListener { _, _, _ -> refreshPreview() }
        npMonth.setOnValueChangedListener { _, oldVal, newVal ->
            if (yearOnly || suppressLinkedUpdate) {
                refreshPreview()
                return@setOnValueChangedListener
            }

            suppressLinkedUpdate = true
            when {
                oldVal == 12 && newVal == 1 && npYear.value < npYear.maxValue -> {
                    npYear.value = npYear.value + 1
                }
                oldVal == 1 && newVal == 12 && npYear.value > npYear.minValue -> {
                    npYear.value = npYear.value - 1
                }
            }
            suppressLinkedUpdate = false
            refreshPreview()
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            onConfirm(npYear.value, if (yearOnly) initialMonth.coerceIn(1, 12) else npMonth.value)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
