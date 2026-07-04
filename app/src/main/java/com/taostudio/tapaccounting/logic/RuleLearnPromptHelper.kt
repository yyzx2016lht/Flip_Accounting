package com.taostudio.tapaccounting.logic

import android.app.Activity
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs

object RuleLearnPromptHelper {

    data class PromptModel(
        val referenceText: String,
        val beforeType: Int?,
        val afterType: Int,
        val beforeCategory: String?,
        val afterCategory: String?
    )

    fun show(
        ctx: Context,
        model: PromptModel,
        isOverlay: Boolean = ctx !is Activity,
        onContinue: () -> Unit,
        onDismiss: () -> Unit
    ) {
        if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) {
            onDismiss()
            return
        }

        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.dialog_rule_learn_prompt, null)

        view.findViewById<TextView>(R.id.tv_rule_learn_remark).text = model.referenceText

        val typeChanged = model.beforeType != null && model.beforeType != model.afterType
        val categoryChanged = !model.beforeCategory.isNullOrBlank()
                && !model.afterCategory.isNullOrBlank()
                && model.beforeCategory != model.afterCategory

        val layoutTypeChange = view.findViewById<View>(R.id.layout_rule_learn_type_change)
        if (typeChanged) {
            layoutTypeChange.visibility = View.VISIBLE
            val types = ctx.resources.getStringArray(R.array.bill_types)
            view.findViewById<TextView>(R.id.tv_rule_learn_before_type).text =
                types.getOrNull(model.beforeType!!) ?: model.beforeType.toString()
            view.findViewById<TextView>(R.id.tv_rule_learn_after_type).text =
                types.getOrNull(model.afterType) ?: model.afterType.toString()
        } else {
            layoutTypeChange.visibility = View.GONE
        }

        val layoutCategoryChange = view.findViewById<View>(R.id.layout_rule_learn_category_change)
        if (categoryChanged) {
            layoutCategoryChange.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tv_rule_learn_before_category).text = model.beforeCategory
            view.findViewById<TextView>(R.id.tv_rule_learn_after_category).text = model.afterCategory
        } else {
            layoutCategoryChange.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(view)
            .setCancelable(false)
            .create()

        view.findViewById<View>(R.id.btn_rule_learn_dismiss).setOnClickListener {
            dialog.dismiss()
            onDismiss()
        }
        view.findViewById<View>(R.id.btn_rule_learn_continue).setOnClickListener {
            dialog.dismiss()
            onContinue()
        }
        dialog.setOnCancelListener { onDismiss() }

        if (isOverlay) {
            OverlayDialogs.showOverlayCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.88f,
                cancelOnTouchOutside = false,
                useSolidPanelBackground = true
            )
        } else {
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = ctx,
                widthRatio = 0.88f,
                cancelOnTouchOutside = false,
                useSolidPanelBackground = true
            )
        }
    }
}
