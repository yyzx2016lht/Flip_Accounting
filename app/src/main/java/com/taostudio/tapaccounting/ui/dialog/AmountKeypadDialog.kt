package com.taostudio.tapaccounting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.taostudio.tapaccounting.R
import java.text.DecimalFormat

class AmountKeypadDialog(
    context: Context,
    private val initialAmount: String = "",
    private val onConfirm: (String) -> Unit
) : Dialog(context, R.style.Theme_TapAccounting_Transparent) {

    private lateinit var etAmount: EditText
    private lateinit var btnConfirm: View
    private var currentAmount: String = initialAmount

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_amount_keypad)

        window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(R.style.Animation_TapAccounting_BottomDialogSoft)
        }

        initViews()
        setupKeypad()
        updateAmountDisplay()
    }

    private fun initViews() {
        etAmount = findViewById(R.id.et_amount)
        btnConfirm = findViewById(R.id.btn_key_confirm)
        findViewById<View>(R.id.layout_amount_keypad)?.visibility = View.VISIBLE

        etAmount.setText(initialAmount)
        etAmount.setSelection(initialAmount.length)

        btnConfirm.setOnClickListener {
            onConfirm(currentAmount)
            dismiss()
        }
    }

    private fun setupKeypad() {
        val digitIds = listOf(
            R.id.btn_key_0 to "0",
            R.id.btn_key_1 to "1",
            R.id.btn_key_2 to "2",
            R.id.btn_key_3 to "3",
            R.id.btn_key_4 to "4",
            R.id.btn_key_5 to "5",
            R.id.btn_key_6 to "6",
            R.id.btn_key_7 to "7",
            R.id.btn_key_8 to "8",
            R.id.btn_key_9 to "9",
            R.id.btn_key_dot to "."
        )
        digitIds.forEach { (id, value) ->
            findViewById<View>(id)?.setOnClickListener { appendAmountInput(value) }
        }

        val operatorIds = listOf(
            R.id.btn_key_add to "+",
            R.id.btn_key_subtract to "-",
            R.id.btn_key_multiply to "×",
            R.id.btn_key_divide to "÷"
        )
        operatorIds.forEach { (id, value) ->
            findViewById<View>(id)?.setOnClickListener { appendOperatorInput(value) }
        }

        findViewById<View>(R.id.btn_key_delete)?.apply {
            setOnClickListener { deleteAmountInput() }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                clearAmountInput()
                true
            }
        }
        findViewById<View>(R.id.btn_key_clear)?.setOnClickListener { clearAmountInput() }

        etAmount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                currentAmount = s?.toString().orEmpty()
                updateConfirmKeyState()
            }
        })
        updateConfirmKeyState()
    }

    private fun appendAmountInput(token: String) {
        val current = etAmount.text?.toString().orEmpty()
        val next = when (token) {
            "." -> {
                val lastOperatorIndex = current.indexOfLast { it in charArrayOf('+', '-', '×', '÷') }
                val currentSegment = if (lastOperatorIndex >= 0) current.substring(lastOperatorIndex + 1) else current
                when {
                    current.isEmpty() -> "0."
                    currentSegment.contains(".") -> current
                    current.lastOrNull()?.let { it in charArrayOf('+', '-', '×', '÷') } == true -> "${current}0."
                    else -> "$current."
                }
            }
            else -> {
                if (current == "0") token else current + token
            }
        }
        etAmount.setText(next)
        etAmount.setSelection(etAmount.text?.length ?: 0)
    }

    private fun deleteAmountInput() {
        val current = etAmount.text?.toString().orEmpty()
        if (current.isEmpty()) return
        val next = current.dropLast(1)
        etAmount.setText(next)
        etAmount.setSelection(etAmount.text?.length ?: 0)
    }

    private fun clearAmountInput() {
        etAmount.setText("")
        etAmount.setSelection(0)
        updateConfirmKeyState()
    }

    private fun appendOperatorInput(operator: String) {
        val current = etAmount.text?.toString().orEmpty()
        if (current.isBlank() || current.all { it in charArrayOf('+', '-', '×', '÷') }) return
        val next = if (current.lastOrNull()?.let { it in charArrayOf('+', '-', '×', '÷') } == true) {
            current.dropLast(1) + operator
        } else {
            current + operator
        }
        etAmount.setText(next)
        etAmount.setSelection(etAmount.text?.length ?: 0)
    }

    private fun updateConfirmKeyState() {
        val enabled = currentAmount.isNotBlank() && !currentAmount.all { it in charArrayOf('+', '-', '×', '÷') }
        btnConfirm.isEnabled = enabled
        btnConfirm.alpha = if (enabled) 1f else 0.45f
    }

    private fun updateAmountDisplay() {
        etAmount.setText(currentAmount)
        etAmount.setSelection(currentAmount.length)
    }

    companion object {
        fun show(
            context: Context,
            initialAmount: String = "",
            onConfirm: (String) -> Unit
        ): AmountKeypadDialog {
            val dialog = AmountKeypadDialog(context, initialAmount, onConfirm)
            dialog.show()
            return dialog
        }
    }
}

