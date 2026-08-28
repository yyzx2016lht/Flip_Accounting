package com.taostudio.tapaccounting.ui.dialog

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.logic.BillMoveTargetResolver
import kotlin.math.min

object BillMoveDialog {
    fun show(
        activity: Activity,
        bills: List<Bill>,
        targets: List<BillMoveTargetResolver.Target>,
        onConfirmed: (targetBook: String) -> Unit
    ) {
        if (targets.none { !it.isNoOp }) {
            Toast.makeText(activity, "没有可移动到的其他账本", Toast.LENGTH_SHORT).show()
            return
        }

        val themeCtx = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_book_delete_options, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "移动到账本"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "选择目标账本"

        val optionsScroll = panel.findViewById<ScrollView>(R.id.scroll_delete_book_options)
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val targetByBookName = targets.associateBy { it.bookName }
        val sourceBooks = targets.map { it.bookName }
        val defaultBook = BookAccountManager.getDefaultBook(activity, sourceBooks)
        var collapsedGroupExpanded = false

        fun displayBooks(): List<String> = BookAccountManager.getDisplayBookAccounts(
            context = activity,
            books = sourceBooks,
            includeAllBook = false,
            collapsedGroupExpanded = collapsedGroupExpanded,
            defaultBookName = defaultBook
        )

        fun updateOptionsHeight(visibleCount: Int) {
            val maxHeight = (activity.resources.displayMetrics.heightPixels * 0.42f).toInt()
            val estimatedItemHeight = ((66 + 10) * activity.resources.displayMetrics.density).toInt()
            val estimatedContentHeight = (visibleCount * estimatedItemHeight).coerceAtLeast(1)
            optionsScroll.layoutParams = optionsScroll.layoutParams.apply {
                height = min(maxHeight, estimatedContentHeight)
            }
        }

        fun renderOptions() {
            optionsContainer.removeAllViews()
            val visibleBooks = displayBooks()
            val collapsedBooks = BookAccountManager.getCollapsedBookAccounts(activity, sourceBooks).toSet()

            visibleBooks.forEach { targetBook ->
                val item = LayoutInflater.from(activity)
                    .inflate(R.layout.item_book_delete_option, optionsContainer, false)
                val titleView = item.findViewById<TextView>(R.id.tv_delete_option_title)
                val descView = item.findViewById<TextView>(R.id.tv_delete_option_desc)
                item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE

                if (targetBook == BookAccountManager.COLLAPSED_BOOK_GROUP) {
                    titleView.text =
                        "${if (collapsedGroupExpanded) "▾" else "▸"} ${BookAccountManager.COLLAPSED_BOOK_GROUP}  ${collapsedBooks.size}"
                    descView.text = if (collapsedGroupExpanded) "收起已收纳账本" else "展开选择已收纳账本"
                    item.setOnClickListener {
                        collapsedGroupExpanded = !collapsedGroupExpanded
                        renderOptions()
                    }
                } else {
                    val target = targetByBookName.getValue(targetBook)
                    titleView.text = targetBook
                    descView.text = when {
                        target.isNoOp -> "当前账本，无需移动"
                        targetBook in collapsedBooks -> "已收纳账本，仍可作为迁移目标"
                        else -> "将所选账单迁移到该账本"
                    }
                    item.isEnabled = !target.isNoOp
                    item.alpha = if (target.isNoOp) 0.52f else 1f
                    if (!target.isNoOp) {
                        item.setOnClickListener {
                            dialog.dismiss()
                            showConfirmDialog(
                                activity = activity,
                                billCount = bills.size,
                                targetBook = targetBook,
                                onConfirmed = onConfirmed
                            )
                        }
                    }
                }
                optionsContainer.addView(item)
            }
            updateOptionsHeight(visibleBooks.size)
        }
        renderOptions()

        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = activity,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun showConfirmDialog(
        activity: Activity,
        billCount: Int,
        targetBook: String,
        onConfirmed: (targetBook: String) -> Unit
    ) {
        val themeCtx = ContextThemeWrapper(activity, R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = "确认移动"
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text =
            "确定要将 $billCount 条账单移动到「$targetBook」吗？"

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = "确认移动"
            setBackgroundResource(R.drawable.bg_delete_followup_primary_btn)
            setOnClickListener {
                dialog.dismiss()
                onConfirmed(targetBook)
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = activity,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }
}
