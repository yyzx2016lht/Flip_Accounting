package com.taostudio.tapaccounting.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager.BadTokenException
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.viewscope.LedgerBookSelection
import com.taostudio.tapaccounting.viewscope.LedgerMemberScope
import com.taostudio.tapaccounting.viewscope.LedgerViewScope
import com.taostudio.tapaccounting.viewscope.ResolvedLedgerViewScope
import com.taostudio.tapaccounting.viewscope.ViewBookOption

object LedgerViewScopeDialog {
    private data class BookRow(val book: ViewBookOption?)

    fun show(
        context: Context,
        current: ResolvedLedgerViewScope,
        onConfirm: (LedgerViewScope) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_ledger_view_scope, null, false)
        val rvBooks = view.findViewById<RecyclerView>(R.id.rv_scope_books)
        val summary = view.findViewById<TextView>(R.id.tv_scope_selection_summary)
        val btnMine = view.findViewById<MaterialButton>(R.id.btn_scope_mine)
        val btnPersonal = view.findViewById<MaterialButton>(R.id.btn_scope_personal)
        val btnAll = view.findViewById<MaterialButton>(R.id.btn_scope_all)
        val btnEveryone = view.findViewById<TextView>(R.id.btn_scope_everyone)
        val btnOnlyMine = view.findViewById<TextView>(R.id.btn_scope_only_mine)

        val activeBooks = current.availableBooks.filterNot { it.isCollapsed }
        val collapsedBooks = current.availableBooks.filter { it.isCollapsed }
        val selectedIds = current.selectedBooks.mapTo(linkedSetOf()) { it.id }
        var memberScope = current.scope.members
        var collapsedExpanded = false
        lateinit var bookAdapter: RecyclerView.Adapter<RecyclerView.ViewHolder>

        fun consideredBooks(): List<ViewBookOption> =
            if (collapsedExpanded) current.availableBooks else activeBooks

        fun effectiveSelectedIds(): Set<Long> {
            val consideredIds = consideredBooks().mapTo(hashSetOf()) { it.id }
            return selectedIds.filterTo(linkedSetOf()) { it in consideredIds }
        }

        fun visibleRows(): List<BookRow> = buildList {
            activeBooks.forEach { add(BookRow(it)) }
            if (collapsedBooks.isNotEmpty()) {
                add(BookRow(null))
                if (collapsedExpanded) collapsedBooks.forEach { add(BookRow(it)) }
            }
        }

        fun updateMemberSegmentedControl() {
            val everyoneSelected = memberScope == LedgerMemberScope.EVERYONE
            btnEveryone.setBackgroundResource(
                if (everyoneSelected) R.drawable.bg_stats_segmented_selected
                else R.drawable.bg_stats_segmented_unselected
            )
            btnOnlyMine.setBackgroundResource(
                if (everyoneSelected) R.drawable.bg_stats_segmented_unselected
                else R.drawable.bg_stats_segmented_selected
            )
            btnEveryone.setTextColor(context.getColor(
                if (everyoneSelected) R.color.stats_segmented_text_active
                else R.color.stats_segmented_text_inactive
            ))
            btnOnlyMine.setTextColor(context.getColor(
                if (everyoneSelected) R.color.stats_segmented_text_inactive
                else R.color.stats_segmented_text_active
            ))
        }

        fun updateSelectionUi() {
            val considered = consideredBooks()
            val effectiveIds = effectiveSelectedIds()
            val allIds = considered.mapTo(linkedSetOf()) { it.id }
            val personalIds = considered.filterNot { it.isShared }.mapTo(linkedSetOf()) { it.id }
            val allSelected = allIds.isNotEmpty() && effectiveIds == allIds
            val minePreset = allSelected && memberScope == LedgerMemberScope.MINE
            val allPreset = allSelected && memberScope == LedgerMemberScope.EVERYONE
            val personalPreset = !minePreset && personalIds.isNotEmpty() &&
                effectiveIds == personalIds && memberScope == LedgerMemberScope.MINE
            btnMine.isChecked = minePreset
            btnPersonal.isChecked = personalPreset
            btnAll.isChecked = allPreset
            summary.text = "已选 ${effectiveIds.size} 个 · ${if (memberScope == LedgerMemberScope.MINE) "仅我" else "全部成员"}"
            updateMemberSegmentedControl()
        }

        fun selectPreset(bookIds: Set<Long>, members: LedgerMemberScope) {
            selectedIds.clear()
            selectedIds.addAll(bookIds)
            memberScope = members
            updateSelectionUi()
            bookAdapter.notifyDataSetChanged()
        }

        bookAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val item = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_book_picker, parent, false)
                return object : RecyclerView.ViewHolder(item) {}
            }

            override fun getItemCount(): Int = visibleRows().size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val row = visibleRows()[position]
                val itemView = holder.itemView
                val tvName = itemView.findViewById<TextView>(R.id.tv_book_name)
                val ivIcon = itemView.findViewById<ImageView>(R.id.iv_book_icon)
                val ivSelected = itemView.findViewById<ImageView>(R.id.iv_book_selected)
                val book = row.book

                if (book == null) {
                    tvName.text = "已收纳账本（${collapsedBooks.size}）"
                    tvName.setTextColor(context.getColor(R.color.text_secondary))
                    ivIcon.setImageResource(R.drawable.ic_backup_folder_action)
                    ivIcon.alpha = 0.72f
                    ivSelected.setImageResource(R.drawable.ic_chevron_down_small)
                    ivSelected.visibility = View.VISIBLE
                    ivSelected.rotation = if (collapsedExpanded) 180f else 0f
                    itemView.setBackgroundResource(R.drawable.bg_book_item_normal)
                    itemView.contentDescription = if (collapsedExpanded) "收起已收纳账本" else "展开已收纳账本"
                    itemView.setOnClickListener {
                        collapsedExpanded = !collapsedExpanded
                        updateSelectionUi()
                        notifyDataSetChanged()
                    }
                    return
                }

                val isSelected = book.id in selectedIds
                tvName.text = if (book.isShared) "${book.name}  · 共享" else book.name
                tvName.setTextColor(context.getColor(
                    if (isSelected) R.color.brand_primary else R.color.text_primary
                ))
                ivIcon.setImageResource(R.drawable.ic_switch_book)
                ivIcon.alpha = if (isSelected) 1f else 0.75f
                ivSelected.setImageResource(R.drawable.ic_book_selected_check)
                ivSelected.rotation = 0f
                ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
                itemView.setBackgroundResource(
                    if (isSelected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal
                )
                itemView.contentDescription = "${book.name}，${if (isSelected) "已选择" else "未选择"}"
                itemView.setOnClickListener {
                    if (!selectedIds.add(book.id)) selectedIds.remove(book.id)
                    updateSelectionUi()
                    notifyItemChanged(position)
                }
            }
        }
        rvBooks.layoutManager = LinearLayoutManager(context)
        rvBooks.adapter = bookAdapter
        rvBooks.itemAnimator = null

        btnMine.setOnClickListener {
            selectPreset(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.MINE)
        }
        btnPersonal.setOnClickListener {
            val personalIds = consideredBooks().filterNot { it.isShared }.mapTo(linkedSetOf()) { it.id }
            if (personalIds.isEmpty()) {
                Toast.makeText(context, "当前没有个人账本", Toast.LENGTH_SHORT).show()
            } else {
                selectPreset(personalIds, LedgerMemberScope.MINE)
            }
        }
        btnAll.setOnClickListener {
            selectPreset(consideredBooks().mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.EVERYONE)
        }
        btnEveryone.setOnClickListener {
            memberScope = LedgerMemberScope.EVERYONE
            updateSelectionUi()
        }
        btnOnlyMine.setOnClickListener {
            memberScope = LedgerMemberScope.MINE
            updateSelectionUi()
        }
        view.findViewById<View>(R.id.btn_scope_close).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_scope_reset).setOnClickListener {
            collapsedExpanded = false
            selectPreset(activeBooks.mapTo(linkedSetOf()) { it.id }, LedgerMemberScope.EVERYONE)
        }
        view.findViewById<View>(R.id.btn_scope_confirm).setOnClickListener {
            val chosen = effectiveSelectedIds()
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
            onConfirm(LedgerViewScope(books, memberScope))
            dialog.dismiss()
        }

        updateSelectionUi()
        dialog.setContentView(view)
        dialog.setOnShowListener {
            val bottomSheetId = context.resources.getIdentifier(
                "design_bottom_sheet",
                "id",
                "com.google.android.material"
            )
            if (bottomSheetId == 0) return@setOnShowListener
            val bottomSheet = dialog.findViewById<View>(bottomSheetId) ?: return@setOnShowListener
            bottomSheet.layoutParams = bottomSheet.layoutParams.apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                isFitToContents = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        try {
            dialog.show()
        } catch (_: BadTokenException) {
        } catch (_: IllegalStateException) {
        }
    }
}
