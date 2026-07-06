package com.taostudio.tapaccounting.ui.dialog

import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.R

object InvestmentLotPromptDialog {

    fun show(
        activity: AppCompatActivity,
        hasDraft: Boolean,
        onGo: () -> Unit
    ) {
        val themeContext = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(activity).inflate(R.layout.dialog_investment_lot_prompt, null, false)
        panel.findViewById<TextView>(R.id.tv_investment_lot_prompt_message).text =
            activity.getString(
                if (hasDraft) {
                    R.string.investment_lot_prompt_message_resume
                } else {
                    R.string.investment_lot_prompt_message_new
                }
            )

        val dialog = AlertDialog.Builder(themeContext)
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_investment_lot_prompt_later).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_investment_lot_prompt_go).setOnClickListener {
            dialog.dismiss()
            onGo()
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = activity,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }
}
