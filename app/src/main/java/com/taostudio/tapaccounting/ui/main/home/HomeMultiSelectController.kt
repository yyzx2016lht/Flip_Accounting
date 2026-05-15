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
        val hostActivity = fragment.activity ?: return
        val bottomNav = hostActivity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_navigation)
        val fallbackNavHeight = (56f * fragment.resources.displayMetrics.density).toInt()

        fun applyOffset() {
            val lp = layoutMultiSelectActions.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val navHeight = bottomNav?.height ?: 0
            val navExtra = (navHeight - fallbackNavHeight).coerceAtLeast(0)
            val targetBottom = multiSelectActionsBaseBottomMargin + navExtra
            if (lp.bottomMargin != targetBottom) {
                lp.bottomMargin = targetBottom
                layoutMultiSelectActions.layoutParams = lp
            }
        }

        bottomNav?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyOffset()
        }
        layoutMultiSelectActions.post { applyOffset() }
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
        val availableBookNames = getAvailableBookNames()

        availableBookNames.forEach { targetBook ->
            val item = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = targetBook
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = "将所选账单迁移到该账本"
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility = View.GONE
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
                    val ids = bills.map { it.id }
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(fragment.requireContext())
                        db.billDao().moveBillsToBook(ids, targetBook)
                        withContext(Dispatchers.Main) {
                            getHomeAdapter().clearSelection()
                            Toast.makeText(
                                fragment.requireContext(),
                                "已将 ${bills.size} 条账单移动到「$targetBook」",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            optionsContainer.addView(item)
        }

        val maxHeight = (fragment.resources.displayMetrics.heightPixels * 0.42f).toInt()
        val estimatedItemHeight = ((66 + 10) * fragment.resources.displayMetrics.density).toInt()
        val estimatedContentHeight = (availableBookNames.size * estimatedItemHeight).coerceAtLeast(1)
        val targetHeight = min(maxHeight, estimatedContentHeight)
        optionsScroll.layoutParams = optionsScroll.layoutParams.apply { height = targetHeight }
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

