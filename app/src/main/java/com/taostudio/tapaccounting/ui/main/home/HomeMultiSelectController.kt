package com.taostudio.tapaccounting.ui.main.home

import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
import kotlin.math.min

internal class HomeMultiSelectController(
    private val fragment: Fragment,
    private val layoutMultiSelectActions: View,
    private val btnMsCancel: View,
    private val btnMsSelectAll: View,
    private val btnMsDelete: View,
    private val btnMsMoveBook: View,
    private val multiSelectActionsBaseBottomMargin: Int,
    private val getAvailableBookNames: () -> List<String>,
    private val getHomeAdapter: () -> HomeAdapter,
    private val dismissKeyboardForDialog: () -> Unit,
    private val configureDialogWindow: (Dialog, Int, Float) -> Unit,
    private val onDataChanged: () -> Unit = {},
) {
    fun setupMultiSelectActions() {
        btnMsCancel.setOnClickListener {
            getHomeAdapter().clearSelection()
        }
        btnMsSelectAll.setOnClickListener {
            val adapter = getHomeAdapter()
            val allCount = adapter.items.count { it is HomeAdapter.ListItem.Item }
            if (adapter.selectedBills.size >= allCount && allCount > 0) {
                adapter.deselectAll()
            } else {
                adapter.selectAll()
            }
        }
        btnMsMoveBook.setOnClickListener {
            val billsToMove = getHomeAdapter().selectedBills.toList()
            if (billsToMove.isEmpty()) return@setOnClickListener
            showMoveToBookDialog(billsToMove)
        }
        btnMsDelete.setOnClickListener {
            val billsToDelete = getHomeAdapter().selectedBills.toList()
            if (billsToDelete.isEmpty()) return@setOnClickListener

            showConfirmDialog(
                title = "确认删除",
                message = "确定要删除选中的 ${billsToDelete.size} 条账单吗？删除后可在回收站恢复。",
                confirmText = "确认删除",
                isDanger = true
            ) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                fragment.lifecycleScope.launch {
                    com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillsAndRevertBalance(db, billsToDelete)
                    getHomeAdapter().clearSelection()
                    onDataChanged()
                    Toast.makeText(
                        fragment.context,
                        "已删除 ${billsToDelete.size} 条账单",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun setupMultiSelectActionsBottomOffset() {
        // Home 页面容器已经通过 activity_main.xml 为底栏预留了固定高度，
        // 这里不应再叠加 BottomNavigation 的额外偏移，否则会出现“悬空”间隙。
        val lp = layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin != multiSelectActionsBaseBottomMargin) {
            lp.bottomMargin = multiSelectActionsBaseBottomMargin
            layoutMultiSelectActions.layoutParams = lp
        }
    }

    private fun showMoveToBookDialog(bills: List<Bill>) {
        dismissKeyboardForDialog()
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_book_delete_options, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "移动到账本"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "选择目标账本"
        val optionsScroll = panel.findViewById<ScrollView>(R.id.scroll_delete_book_options)
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val sourceBooks = getAvailableBookNames()
            .map { BookAccountManager.normalizeBookName(it) }
            .filter {
                it.isNotBlank() &&
                    it != BookAccountManager.ALL_BOOK &&
                    it != BookAccountManager.COLLAPSED_BOOK_GROUP
            }
            .distinct()
        val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext(), sourceBooks)
        var collapsedGroupExpanded = false

        fun displayBooks(): List<String> = BookAccountManager.getDisplayBookAccounts(
            context = fragment.requireContext(),
            books = sourceBooks,
            includeAllBook = false,
            collapsedGroupExpanded = collapsedGroupExpanded,
            defaultBookName = defaultBook
        )

        fun updateOptionsHeight(visibleCount: Int) {
            val maxHeight = (fragment.resources.displayMetrics.heightPixels * 0.42f).toInt()
            val estimatedItemHeight = ((66 + 10) * fragment.resources.displayMetrics.density).toInt()
            val estimatedContentHeight = (visibleCount * estimatedItemHeight).coerceAtLeast(1)
            val targetHeight = min(maxHeight, estimatedContentHeight)
            optionsScroll.layoutParams = optionsScroll.layoutParams.apply { height = targetHeight }
        }

        fun renderOptions() {
            optionsContainer.removeAllViews()
            val visibleBooks = displayBooks()
            val collapsedBooks = BookAccountManager.getCollapsedBookAccounts(
                fragment.requireContext(),
                sourceBooks
            ).toSet()

            visibleBooks.forEach { targetBook ->
                val item = LayoutInflater.from(fragment.requireContext())
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
                    titleView.text = targetBook
                    descView.text = if (targetBook in collapsedBooks) {
                        "已收纳账本，仍可作为迁移目标"
                    } else {
                        "将所选账单迁移到该账本"
                    }
                    item.setOnClickListener {
                        val normalized = BookAccountManager.normalizeBookName(targetBook)
                        val allSameBook = bills.all {
                            BookAccountManager.normalizeBookName(it.bookName) == normalized
                        }
                        if (allSameBook) {
                            Toast.makeText(
                                fragment.requireContext(),
                                "账单已在「$targetBook」中，无需转移",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@setOnClickListener
                        }
                        dialog.dismiss()
                        showConfirmDialog(
                            title = "确认移动",
                            message = "确定要将 ${bills.size} 条账单移动到「$targetBook」吗？",
                            confirmText = "确认移动",
                            isDanger = false
                        ) {
                            fragment.lifecycleScope.launch(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(fragment.requireContext())
                                val error = runCatching {
                                    com.taostudio.tapaccounting.data.sync.SharedMutationHooks.moveBills(db, bills, targetBook)
                                }.exceptionOrNull()
                                withContext(Dispatchers.Main) {
                                    if (error != null) {
                                        Toast.makeText(
                                            fragment.requireContext(), error.message ?: "移动失败", Toast.LENGTH_LONG
                                        ).show()
                                        return@withContext
                                    }
                                    getHomeAdapter().clearSelection()
                                    onDataChanged()
                                    Toast.makeText(
                                        fragment.requireContext(),
                                        "已将 ${bills.size} 条账单移动到「$targetBook」",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
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
            ctx = fragment.requireContext(),
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun showConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = fragment.requireContext(),
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }
}

