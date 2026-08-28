package com.taostudio.tapaccounting.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import com.taostudio.tapaccounting.viewscope.ViewBookOption

object LedgerViewScopeDialog {
    fun show(
        context: Context,
        current: ResolvedLedgerViewScope,
        onConfirm: (LedgerViewScope) -> Unit
    ) {
        val themedContext = ContextThemeWrapper(context, R.style.Theme_TapAccounting)
        val view = LayoutInflater.from(themedContext)
            .inflate(R.layout.dialog_ledger_view_scope, null, false)
        val dialog = AlertDialog.Builder(themedContext).setView(view).create()
        val booksContainer = view.findViewById<LinearLayout>(R.id.layout_scope_books)
        val everyoneButton = view.findViewById<RadioButton>(R.id.rb_scope_everyone)
        val mineButton = view.findViewById<RadioButton>(R.id.rb_scope_mine)
        val selectedIds = current.selectedBooks.mapTo(linkedSetOf()) { it.id }
        val activeBooks = current.availableBooks.filterNot { it.isCollapsed }
        val collapsedBooks = current.availableBooks.filter { it.isCollapsed }
        val checks = linkedMapOf<ViewBookOption, CheckBox>()
        var collapsedExpanded = false

        fun addBookCheck(book: ViewBookOption): CheckBox {
            return CheckBox(themedContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(themedContext, 46)
                )
                text = if (book.isShared) "${book.name}  · 共享" else book.name
                textSize = 14f
                setTextColor(themedContext.getColor(R.color.dialog_title))
                isChecked = book.id in selectedIds
                setPadding(0, 0, 0, 0)
                booksContainer.addView(this)
            }
        }
        activeBooks.forEach { book -> checks[book] = addBookCheck(book) }

        val collapsedHeader = if (collapsedBooks.isNotEmpty()) {
            TextView(themedContext).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(themedContext, 46)
                )
                gravity = android.view.Gravity.CENTER_VERTICAL
                text = "已收纳账本（${collapsedBooks.size}）  展开"
                textSize = 14f
                setTextColor(themedContext.getColor(R.color.brand_primary))
                setPadding(0, 0, 0, 0)
                booksContainer.addView(this)
            }
        } else null
        collapsedBooks.forEach { book ->
            checks[book] = addBookCheck(book).apply {
                visibility = android.view.View.GONE
                setPadding(dp(themedContext, 12), 0, 0, 0)
            }
        }
        collapsedHeader?.setOnClickListener {
            collapsedExpanded = !collapsedExpanded
            collapsedHeader.text = if (collapsedExpanded) {
                "已收纳账本（${collapsedBooks.size}）  收起"
            } else {
                "已收纳账本（${collapsedBooks.size}）  展开"
            }
            collapsedBooks.forEach { book ->
                checks[book]?.visibility = if (collapsedExpanded) android.view.View.VISIBLE else android.view.View.GONE
            }
        }

        if (current.scope.members == LedgerMemberScope.MINE) mineButton.isChecked = true
        else everyoneButton.isChecked = true

        fun select(bookIds: Set<Long>, memberScope: LedgerMemberScope) {
            checks.forEach { (book, check) -> check.isChecked = book.id in bookIds }
            if (memberScope == LedgerMemberScope.MINE) mineButton.isChecked = true
            else everyoneButton.isChecked = true
        }

        fun consideredBooks() = if (collapsedExpanded) current.availableBooks else activeBooks

        view.findViewById<android.view.View>(R.id.btn_scope_mine).setOnClickListener {
            select(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.MINE)
        }
        view.findViewById<android.view.View>(R.id.btn_scope_personal).setOnClickListener {
            val personal = consideredBooks().filterNot { it.isShared }.mapTo(linkedSetOf()) { it.id }
            if (personal.isEmpty()) {
                Toast.makeText(context, "当前没有个人账本", Toast.LENGTH_SHORT).show()
            } else {
                select(personal, LedgerMemberScope.MINE)
            }
        }
        view.findViewById<android.view.View>(R.id.btn_scope_all).setOnClickListener {
            select(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.EVERYONE)
        }
        view.findViewById<android.view.View>(R.id.btn_scope_cancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<android.view.View>(R.id.btn_scope_confirm).setOnClickListener {
            val consideredIds = consideredBooks().mapTo(hashSetOf()) { it.id }
            val chosen = checks
                .filter { (book, check) -> book.id in consideredIds && check.isChecked }
                .keys
                .mapTo(linkedSetOf()) { it.id }
            if (chosen.isEmpty()) {
                Toast.makeText(context, "请至少选择一个账本", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val defaultIds = activeBooks.mapTo(hashSetOf()) { it.id }
            val books = if (chosen == defaultIds) {
                LedgerBookSelection.All
            } else {
                LedgerBookSelection.Selected(chosen)
            }
            val members = if (mineButton.isChecked) LedgerMemberScope.MINE else LedgerMemberScope.EVERYONE
            onConfirm(LedgerViewScope(books, members))
            dialog.dismiss()
        }
        OverlayDialogs.showPageCenterDialog(dialog, context, widthRatio = 0.9f)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
