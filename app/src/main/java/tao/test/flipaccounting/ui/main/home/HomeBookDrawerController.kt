package tao.test.flipaccounting.ui.main.home

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.ui.activity.BookOverviewActivity
import kotlin.math.max

internal class HomeBookDrawerController(
    private val fragment: Fragment,
    private val homeViewModel: HomeViewModel,
    private val drawerBooks: DrawerLayout,
    private val layoutBookDrawer: View,
    private val rvBookAccounts: RecyclerView,
    private val btnAddBookAccount: View,
    private val layoutAddBookInput: View,
    private val etAddBookAccountName: EditText,
    private val btnConfirmAddBook: View,
    private val btnCancelAddBook: View,
    private val bookDrawerBasePaddingBottom: Int,
    private val rvBookAccountsBasePaddingTop: Int,
    private val rvBookAccountsBasePaddingBottom: Int,
    private val getSelectedBookName: () -> String,
    private val setSelectedBookName: (String) -> Unit,
    private val getAvailableBookNames: () -> List<String>,
    private val setAvailableBookNames: (List<String>) -> Unit,
    private val getPendingBookSwitchName: () -> String?,
    private val setPendingBookSwitchName: (String?) -> Unit,
    private val setAnimateNextBookDataReveal: (Boolean) -> Unit,
    private val getSelectedYear: () -> Int,
    private val getSelectedMonth: () -> Int,
    private val getCurrentTimeRange: () -> Int,
    private val getCurrentType: () -> Int,
    private val updateHeaderBanner: () -> Unit,
    private val updateHomeFabVisibilityByDrawerState: () -> Unit,
    private val applyHomeFabDrawerProgress: (Float) -> Unit,
    private val dismissKeyboardForDialog: () -> Unit,
    private val configureDialogWindow: (Dialog, Int, Float) -> Unit,
) {
    private lateinit var bookAccountAdapter: BookAccountAdapter

    private enum class BookDeleteMode {
        MOVE_TO_OTHER_BOOK,
        REMOVE_FROM_BOOK_KEEP_IN_ALL,
        DELETE_BILLS_KEEP_ASSETS,
        DELETE_BILLS_AND_REVERT_ASSETS
    }

    fun setupBookDrawer() {
        rvBookAccounts.layoutManager = LinearLayoutManager(fragment.requireContext())
        bookAccountAdapter = BookAccountAdapter(
            onItemClick = { onBookSelected(it) },
            onRenameClick = { oldName, newName ->
                if (BookAccountManager.normalizeBookName(oldName) == BookAccountManager.ALL_BOOK) {
                    Toast.makeText(fragment.requireContext(), "「全部账本」是系统入口，不能重命名", Toast.LENGTH_SHORT).show()
                } else {
                    renameBook(oldName, newName)
                }
            },
            onDeleteClick = { name ->
                if (BookAccountManager.normalizeBookName(name) == BookAccountManager.ALL_BOOK) {
                    Toast.makeText(fragment.requireContext(), "「全部账本」是系统入口，不能删除", Toast.LENGTH_SHORT).show()
                } else {
                    deleteBook(name)
                }
            }
        )
        rvBookAccounts.adapter = bookAccountAdapter
        rvBookAccounts.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            adjustBookListBottomPaddingForWholeRows()
        }
        rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }

        btnAddBookAccount.setOnClickListener { showInlineAddBookInput() }
        btnConfirmAddBook.setOnClickListener { commitInlineAddBook() }
        btnCancelAddBook.setOnClickListener { hideInlineAddBookInput(clearText = true) }
        etAddBookAccountName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitInlineAddBook()
                true
            } else {
                false
            }
        }

        fragment.view?.findViewById<View>(R.id.btnBookOverview)?.setOnClickListener {
            drawerBooks.closeDrawer(GravityCompat.START)
            val intent = Intent(fragment.requireContext(), BookOverviewActivity::class.java).apply {
                putExtra(BookOverviewActivity.EXTRA_CURRENT_BOOK, getSelectedBookName())
            }
            fragment.startActivity(intent)
        }

        drawerBooks.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (slideOffset > 0f) {
                    drawerBooks.getChildAt(0)?.let { content ->
                        val cancel = android.view.MotionEvent.obtain(
                            0, 0, android.view.MotionEvent.ACTION_CANCEL, 0f, 0f, 0
                        )
                        content.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                applyHomeFabDrawerProgress(slideOffset)
            }

            override fun onDrawerOpened(drawerView: View) {
                bookAccountAdapter.closeSwipeActions()
                updateHomeFabVisibilityByDrawerState()
                adjustBookListBottomPaddingForWholeRows()
                scrollBookListToSelected(animate = true)
            }

            override fun onDrawerClosed(drawerView: View) {
                hideInlineAddBookInput(clearText = true)
                bookAccountAdapter.closeSwipeActions()
                updateHomeFabVisibilityByDrawerState()
                getPendingBookSwitchName()?.let { target ->
                    setPendingBookSwitchName(null)
                    homeViewModel.syncAndLoad(
                        bookName = target,
                        year = getSelectedYear(),
                        month = getSelectedMonth(),
                        timeRange = getCurrentTimeRange(),
                        type = getCurrentType(),
                        isChartHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())
                    )
                }
            }
        })
    }

    fun setupBookDrawerImeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(layoutBookDrawer) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val imeExtra = max(0, imeBottom - navBottom)
            v.updatePadding(bottom = bookDrawerBasePaddingBottom + imeExtra)
            rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }
            insets
        }
        ViewCompat.requestApplyInsets(layoutBookDrawer)
    }

    fun refreshBookAccounts(reloadTransactions: Boolean) {
        if (!fragment.isAdded) return
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val context = fragment.requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)

            BookAccountManager.rawAliases(BookAccountManager.DEFAULT_BOOK)
                .filter { it.isNotBlank() && it != BookAccountManager.DEFAULT_BOOK }
                .forEach { alias ->
                    db.billDao().renameBookName(alias, BookAccountManager.DEFAULT_BOOK)
                }

            val dbBooks = db.billDao().getAllBookNames()
            val mergedBooks = BookAccountManager.getBookAccounts(context, dbBooks)
            val selectedFromPrefs = BookAccountManager.getSelectedBook(context, mergedBooks)

            withContext(Dispatchers.Main) {
                if (!fragment.isAdded) return@withContext
                val currentSelected = getSelectedBookName()
                val resolvedSelected = when {
                    mergedBooks.contains(currentSelected) -> currentSelected
                    else -> selectedFromPrefs
                }
                val bookChanged = resolvedSelected != currentSelected
                setSelectedBookName(resolvedSelected)
                BookAccountManager.setSelectedBook(fragment.requireContext(), resolvedSelected)
                setAvailableBookNames(mergedBooks)
                bookAccountAdapter.submitList(getAvailableBookNames(), getSelectedBookName())
                if (drawerBooks.isDrawerOpen(GravityCompat.START)) {
                    scrollBookListToSelected(animate = false)
                }
                updateHeaderBanner()
                val shouldReload = reloadTransactions || bookChanged
                if (shouldReload) {
                    homeViewModel.syncAndLoad(
                        bookName = getSelectedBookName(),
                        year = getSelectedYear(),
                        month = getSelectedMonth(),
                        timeRange = getCurrentTimeRange(),
                        type = getCurrentType(),
                        isChartHidden = !Prefs.isShowHomeTrendCard(fragment.requireContext())
                    )
                }
            }
        }
    }

    private fun scrollBookListToSelected(animate: Boolean = false) {
        val layoutManager = rvBookAccounts.layoutManager as? LinearLayoutManager ?: return
        val selectedIndex = getAvailableBookNames().indexOfFirst {
            BookAccountManager.normalizeBookName(it) == getSelectedBookName()
        }
        if (selectedIndex < 0) return

        rvBookAccounts.post {
            if (!fragment.isAdded) return@post
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            val lastVisible = layoutManager.findLastVisibleItemPosition()
            if (firstVisible != RecyclerView.NO_POSITION &&
                lastVisible != RecyclerView.NO_POSITION &&
                selectedIndex in firstVisible..lastVisible
            ) {
                return@post
            }

            val density = fragment.resources.displayMetrics.density
            val estimatedRowHeight = ((60f + 8f) * density).toInt()
            val itemHeight = layoutManager.findViewByPosition(selectedIndex)?.height
                ?: estimatedRowHeight.coerceAtLeast(1)
            val offset = ((rvBookAccounts.height - itemHeight) / 2).coerceAtLeast(0)
            if (!animate) {
                layoutManager.scrollToPositionWithOffset(selectedIndex, offset)
                return@post
            }

            val smoothScroller = object : LinearSmoothScroller(rvBookAccounts.context) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_ANY

                override fun calculateDtToFit(
                    viewStart: Int,
                    viewEnd: Int,
                    boxStart: Int,
                    boxEnd: Int,
                    snapPreference: Int
                ): Int {
                    val viewCenter = (viewStart + viewEnd) / 2
                    val boxCenter = (boxStart + boxEnd) / 2
                    return boxCenter - viewCenter
                }

                override fun calculateSpeedPerPixel(displayMetrics: DisplayMetrics): Float {
                    return 110f / displayMetrics.densityDpi
                }

                override fun calculateTimeForDeceleration(dx: Int): Int {
                    return (super.calculateTimeForDeceleration(dx) * 1.15f).toInt()
                }
            }
            smoothScroller.targetPosition = selectedIndex
            layoutManager.startSmoothScroll(smoothScroller)
        }
    }

    private fun adjustBookListBottomPaddingForWholeRows() {
        if (!fragment.isAdded || fragment.context == null || fragment.view == null) return
        val available = rvBookAccounts.height
        if (available <= 0) return

        val density = rvBookAccounts.resources.displayMetrics.density
        val rowHeightPx = (60f * density).toInt()
        val rowGapPx = (8f * density).toInt()
        val rowUnitPx = rowHeightPx + rowGapPx
        if (rowUnitPx <= 0) return

        val remainder = available % rowUnitPx
        val topExtra = remainder / 2
        val bottomExtra = remainder - topExtra
        rvBookAccounts.updatePadding(
            top = rvBookAccountsBasePaddingTop + topExtra,
            bottom = rvBookAccountsBasePaddingBottom + bottomExtra
        )
    }

    private fun showInlineAddBookInput() {
        btnAddBookAccount.visibility = View.GONE
        layoutAddBookInput.visibility = View.VISIBLE
        etAddBookAccountName.setText("")
        etAddBookAccountName.requestFocus()
        val imm = fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(etAddBookAccountName, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideInlineAddBookInput(clearText: Boolean) {
        val imm = fragment.requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etAddBookAccountName.windowToken, 0)
        etAddBookAccountName.clearFocus()
        if (clearText) etAddBookAccountName.setText("")
        layoutAddBookInput.visibility = View.GONE
        btnAddBookAccount.visibility = View.VISIBLE
    }

    private fun commitInlineAddBook() {
        val inputName = etAddBookAccountName.text?.toString()?.trim().orEmpty()
        val newName = BookAccountManager.normalizeBookName(inputName)
        if (newName.isBlank()) {
            etAddBookAccountName.error = "名称不能为空"
            return
        }
        if (getAvailableBookNames().any { it == newName }) {
            etAddBookAccountName.error = "账户名已存在"
            return
        }

        if (BookAccountManager.addBookAccount(fragment.requireContext(), newName)) {
            setSelectedBookName(newName)
            hideInlineAddBookInput(clearText = true)
            refreshBookAccounts(reloadTransactions = true)
        } else {
            Toast.makeText(fragment.requireContext(), "新增失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBookSelected(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        if (target == getSelectedBookName()) {
            drawerBooks.closeDrawer(GravityCompat.START)
            return
        }
        setSelectedBookName(target)
        setAnimateNextBookDataReveal(true)
        BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
        bookAccountAdapter.submitList(getAvailableBookNames(), getSelectedBookName())
        updateHeaderBanner()
        setPendingBookSwitchName(getSelectedBookName())
        drawerBooks.closeDrawer(GravityCompat.START)
    }

    private fun renameBook(oldName: String, inputName: String) {
        val oldNorm = BookAccountManager.normalizeBookName(oldName)
        val newNorm = BookAccountManager.normalizeBookName(inputName)
        if (newNorm == oldNorm) return
        if (getAvailableBookNames().any { it == newNorm }) {
            Toast.makeText(fragment.requireContext(), "账户名已存在", Toast.LENGTH_SHORT).show()
            return
        }

        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val context = fragment.requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)
            BookAccountManager.rawAliases(oldNorm).forEach { alias ->
                db.billDao().renameBookName(alias, newNorm)
                db.chatMessageDao().renameBookName(alias, newNorm)
            }
            val success = BookAccountManager.renameBookAccount(context, oldNorm, newNorm)
            withContext(Dispatchers.Main) {
                if (!fragment.isAdded) return@withContext
                if (!success) {
                    Toast.makeText(fragment.requireContext(), "重命名失败", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (getSelectedBookName() == oldNorm) {
                    setSelectedBookName(newNorm)
                }
                BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
                refreshBookAccounts(reloadTransactions = true)
            }
        }
    }

    private fun deleteBook(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        if (target == BookAccountManager.ALL_BOOK) {
            Toast.makeText(fragment.requireContext(), "「全部账本」是系统入口，不能删除", Toast.LENGTH_SHORT).show()
            return
        }

        val transferCandidates = getAvailableBookNames()
            .map { BookAccountManager.normalizeBookName(it) }
            .filter { it != BookAccountManager.ALL_BOOK && it != target }
            .distinct()

        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = fragment.requireContext().applicationContext
            val db = AppDatabase.getDatabase(ctx)
            val billCount = BookAccountManager.rawAliases(target).sumOf { alias ->
                db.billDao().countBillsByBookName(alias)
            }
            withContext(Dispatchers.Main) {
                if (billCount == 0) {
                    performDeleteBook(
                        target = target,
                        mode = BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL,
                        hadBillsBeforeDelete = false
                    )
                } else {
                    showDeleteBookOptions(target, transferCandidates)
                }
            }
        }
    }

    private fun showDeleteBookOptions(target: String, transferCandidates: List<String>) {
        dismissKeyboardForDialog()
        data class DeleteOption(
            val title: String,
            val desc: String,
            val highRisk: Boolean = false,
            val onClick: () -> Unit
        )

        val options = listOf(
            DeleteOption(
                title = "迁移到账本后删除",
                desc = "先把账单迁移到其他账本，再删除当前账本",
                onClick = {
                    if (transferCandidates.isEmpty()) {
                        Toast.makeText(fragment.requireContext(), "没有可迁移的目标账本", Toast.LENGTH_SHORT).show()
                    } else {
                        showTransferTargetPickerAndDelete(target, transferCandidates)
                    }
                }
            ),
            DeleteOption(
                title = "仅删除账本",
                desc = "账单归档到“全部账本”，不会丢失记录",
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "确认删除账本",
                        message = "删除后，「$target」内账单会归档到「全部账本」。",
                        confirmText = "确认删除",
                        isDanger = false
                    ) {
                        performDeleteBook(target, BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL)
                    }
                }
            ),
            DeleteOption(
                title = "删除账本和账单",
                desc = "删除账单，但不回退资产余额",
                highRisk = true,
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "高风险操作确认",
                        message = "将永久删除「$target」内所有账单，且不会回退资产余额。",
                        confirmText = "仍要删除",
                        isDanger = true
                    ) {
                        performDeleteBook(target, BookDeleteMode.DELETE_BILLS_KEEP_ASSETS)
                    }
                }
            ),
            DeleteOption(
                title = "删除账本并回退资产",
                desc = "删除账单并回退相关资产余额",
                highRisk = true,
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "高风险操作确认",
                        message = "将删除「$target」内所有账单并回退资产余额，此操作不可撤销。",
                        confirmText = "仍要删除",
                        isDanger = true
                    ) {
                        performDeleteBook(target, BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS)
                    }
                }
            )
        )

        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_book_delete_options, null, false)
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "删除账本「$target」"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text = "请选择删除方式"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val popupDialog = Dialog(fragment.requireContext(), R.style.Theme_FlipAccounting)
        val targetWidth = (fragment.resources.displayMetrics.widthPixels * 0.92f).toInt()
        options.forEach { opt ->
            val item = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = opt.title
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = opt.desc
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility =
                if (opt.highRisk) View.VISIBLE else View.GONE
            item.setOnClickListener {
                popupDialog.dismiss()
                opt.onClick()
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { popupDialog.dismiss() }

        popupDialog.setContentView(panel)
        popupDialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(popupDialog, targetWidth, 0.34f)
        popupDialog.show()
    }

    private fun showTransferTargetPickerAndDelete(target: String, transferCandidates: List<String>) {
        dismissKeyboardForDialog()
        val dialog = Dialog(fragment.requireContext(), R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_delete_followup_picker, null, false)
        val width = (fragment.resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.findViewById<TextView>(R.id.tv_followup_picker_title).text = "选择迁移目标"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_followup_picker_options)
        transferCandidates.forEach { candidate ->
            val item = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.item_delete_followup_picker_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_followup_picker_option).text = "迁移到「$candidate」"
            item.setOnClickListener {
                dialog.dismiss()
                showDeleteFollowupConfirmDialog(
                    title = "确认迁移并删除",
                    message = "删除后，「$target」账本内的所有账单将迁移到「$candidate」。",
                    confirmText = "迁移并删除",
                    isDanger = false
                ) {
                    performDeleteBook(
                        target = target,
                        mode = BookDeleteMode.MOVE_TO_OTHER_BOOK,
                        transferToBook = candidate
                    )
                }
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_followup_picker_cancel).setOnClickListener { dialog.dismiss() }
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width, 0.34f)
        dialog.show()
    }

    private fun showDeleteFollowupConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        isDanger: Boolean,
        onConfirm: () -> Unit
    ) {
        dismissKeyboardForDialog()
        val dialog = Dialog(fragment.requireContext(), R.style.Theme_FlipAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_delete_followup_confirm, null, false)
        val width = (fragment.resources.displayMetrics.widthPixels * 0.86f).toInt()
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message
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
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        configureDialogWindow(dialog, width, 0.34f)
        dialog.show()
    }

    private fun performDeleteBook(
        target: String,
        mode: BookDeleteMode,
        transferToBook: String? = null,
        hadBillsBeforeDelete: Boolean = true
    ) {
        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = fragment.requireContext().applicationContext
            val db = AppDatabase.getDatabase(ctx)
            val aliases = BookAccountManager.rawAliases(target).toSet()

            when (mode) {
                BookDeleteMode.MOVE_TO_OTHER_BOOK -> {
                    val destination = transferToBook?.let { BookAccountManager.normalizeBookName(it) }
                    if (destination.isNullOrBlank() || destination == target || destination == BookAccountManager.ALL_BOOK) {
                        withContext(Dispatchers.Main) {
                            if (fragment.isAdded) Toast.makeText(fragment.requireContext(), "迁移目标无效", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    aliases.forEach { alias ->
                        db.billDao().renameBookName(alias, destination)
                        db.chatMessageDao().renameBookName(alias, destination)
                    }
                }
                BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL -> {
                    aliases.forEach { alias ->
                        db.billDao().renameBookName(alias, BookAccountManager.ALL_BOOK)
                        db.chatMessageDao().renameBookName(alias, BookAccountManager.ALL_BOOK)
                    }
                }
                BookDeleteMode.DELETE_BILLS_KEEP_ASSETS -> {
                    aliases.forEach { alias ->
                        db.billDao().deleteAllByBookName(alias)
                        db.chatMessageDao().deleteAllByBookName(alias)
                    }
                }
                BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS -> {
                    db.billDao().backfillAssetLinksByName()
                    db.billDao().getBillsByBookNamesList(aliases.toList())
                        .forEach { tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, it) }
                    aliases.forEach { alias ->
                        db.chatMessageDao().deleteAllByBookName(alias)
                    }
                }
            }

            val fallback = transferToBook
                ?.let { BookAccountManager.normalizeBookName(it) }
                ?.takeIf { it != BookAccountManager.ALL_BOOK }
                ?: getAvailableBookNames()
                    .map { BookAccountManager.normalizeBookName(it) }
                    .firstOrNull { it != BookAccountManager.ALL_BOOK && it != target }

            val removed = BookAccountManager.removeBookAccount(ctx, target, fallback)

            withContext(Dispatchers.Main) {
                if (!fragment.isAdded) return@withContext
                if (!removed) {
                    Toast.makeText(fragment.requireContext(), "删除失败", Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                if (getSelectedBookName() == target) {
                    setSelectedBookName(fallback ?: BookAccountManager.ALL_BOOK)
                }
                BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
                refreshBookAccounts(reloadTransactions = true)
                val tip = when (mode) {
                    BookDeleteMode.MOVE_TO_OTHER_BOOK -> "已删除账本，账单已迁移到「$transferToBook」"
                    BookDeleteMode.REMOVE_FROM_BOOK_KEEP_IN_ALL ->
                        if (hadBillsBeforeDelete) "已删除账本，账单已归档到「全部账本」"
                        else "已删除空账本"
                    BookDeleteMode.DELETE_BILLS_KEEP_ASSETS -> "已删除账本与所有账单（未回退资产）"
                    BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS -> "已删除账本与所有账单，并回退资产"
                }
                Toast.makeText(fragment.requireContext(), tip, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
