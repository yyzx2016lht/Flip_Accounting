package com.taostudio.tapaccounting.ui.main.home

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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.ui.activity.BookOverviewActivity
import com.taostudio.tapaccounting.ui.dialog.OverlayDialogs
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
    private val btnAddBookSetDefaultToggle: TextView,
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
    private var bookOrderTouchHelper: ItemTouchHelper? = null
    private var addBookSetDefaultEnabled: Boolean = false
    private var isBookNameEditing: Boolean = false
    private var collapsedBooksExpanded: Boolean = false
    private var drawerDisplayBooks: List<String> = emptyList()

    private enum class BookDeleteMode {
        MOVE_TO_OTHER_BOOK,
        REMOVE_FROM_BOOK_MOVE_TO_DEFAULT,
        DELETE_BILLS_KEEP_ASSETS,
        DELETE_BILLS_AND_REVERT_ASSETS
    }

    fun setupBookDrawer() {
        layoutBookDrawer.isClickable = true
        layoutBookDrawer.isFocusable = true

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
            onManageClick = { name ->
                showBookManageDialog(name)
            },
            onOrderChanged = { newOrder ->
                BookAccountManager.reorderBookAccounts(fragment.requireContext(), newOrder)
                val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext(), newOrder)
                setAvailableBookNames(BookAccountManager.withAllBookOption(newOrder, defaultBook))
                drawerDisplayBooks = buildDrawerDisplayBooks(newOrder, defaultBook)
            },
            onStartDrag = { viewHolder ->
                bookOrderTouchHelper?.startDrag(viewHolder)
            },
            onEditingChanged = { editing ->
                updateBookRenameEditingState(editing)
            }
        )
        rvBookAccounts.adapter = bookAccountAdapter
        setupBookDrawerReorder()
        rvBookAccounts.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            adjustBookListBottomPaddingForWholeRows()
        }
        rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }

        btnAddBookAccount.setOnClickListener {
            if (!bookAccountAdapter.commitActiveRename()) {
                showInlineAddBookInput()
            }
        }
        btnConfirmAddBook.setOnClickListener { commitInlineAddBook() }
        btnCancelAddBook.setOnClickListener { hideInlineAddBookInput(clearText = true) }
        btnAddBookSetDefaultToggle.setOnClickListener {
            addBookSetDefaultEnabled = !addBookSetDefaultEnabled
            updateAddBookSetDefaultToggleUi()
        }
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
                putExtra(BookOverviewActivity.EXTRA_SELECTED_YEAR, getSelectedYear())
                putExtra(BookOverviewActivity.EXTRA_SELECTED_MONTH, getSelectedMonth())
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

    private fun setupBookDrawerReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            private var dragFrom = RecyclerView.NO_POSITION
            private var dragTo = RecyclerView.NO_POSITION

            override fun isLongPressDragEnabled(): Boolean = false

            override fun getMovementFlags(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder
            ): Int {
                if (!bookAccountAdapter.isDraggablePosition(viewHolder.adapterPosition)) {
                    return makeMovementFlags(0, 0)
                }
                return makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (dragFrom == RecyclerView.NO_POSITION) dragFrom = from
                dragTo = to
                return bookAccountAdapter.onItemMove(from, to)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    bookAccountAdapter.closeSwipeActions()
                    viewHolder?.itemView?.animate()
                        ?.scaleX(1.02f)
                        ?.scaleY(1.02f)
                        ?.alpha(0.93f)
                        ?.setDuration(100L)
                        ?.start()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragFrom != RecyclerView.NO_POSITION &&
                    dragTo != RecyclerView.NO_POSITION &&
                    dragFrom != dragTo
                ) {
                    bookAccountAdapter.onDragEnd()
                }
                dragFrom = RecyclerView.NO_POSITION
                dragTo = RecyclerView.NO_POSITION
                viewHolder.itemView.scaleX = 1f
                viewHolder.itemView.scaleY = 1f
                viewHolder.itemView.alpha = 1f
            }
        }
        bookOrderTouchHelper = ItemTouchHelper(callback).also { it.attachToRecyclerView(rvBookAccounts) }
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
            val canonicalDefaultBook = BookAccountManager.DEFAULT_BOOK

            BookAccountManager.rawAliases(BookAccountManager.DEFAULT_BOOK)
                .filter { it.isNotBlank() && it != canonicalDefaultBook }
                .forEach { alias ->
                    db.billDao().renameBookName(alias, canonicalDefaultBook)
                }
            db.billDao().renameBookName(BookAccountManager.ALL_BOOK, canonicalDefaultBook)
            db.chatMessageDao().renameBookName(BookAccountManager.ALL_BOOK, canonicalDefaultBook)

            val dbBooks = db.billDao().getAllBookNames()
            val mergedBooks = BookAccountManager.getBookAccounts(context, dbBooks)
            val defaultBook = BookAccountManager.getDefaultBook(context, mergedBooks)
            val booksWithAll = BookAccountManager.withAllBookOption(mergedBooks, defaultBook)
            val selectedFromPrefs = BookAccountManager.getSelectedBook(context, booksWithAll)
            val collapsedBooks = BookAccountManager.getCollapsedBookAccounts(context, mergedBooks)

            withContext(Dispatchers.Main) {
                if (!fragment.isAdded) return@withContext
                val currentSelected = getSelectedBookName()
                val resolvedSelected = when {
                    currentSelected != BookAccountManager.ALL_BOOK && collapsedBooks.contains(currentSelected) -> defaultBook
                    booksWithAll.contains(currentSelected) -> currentSelected
                    selectedFromPrefs != BookAccountManager.ALL_BOOK && collapsedBooks.contains(selectedFromPrefs) -> defaultBook
                    else -> selectedFromPrefs
                }
                val bookChanged = resolvedSelected != currentSelected
                setSelectedBookName(resolvedSelected)
                BookAccountManager.setSelectedBook(fragment.requireContext(), resolvedSelected)
                setAvailableBookNames(booksWithAll)
                drawerDisplayBooks = buildDrawerDisplayBooks(booksWithAll, defaultBook)
                bookAccountAdapter.submitList(
                    books = drawerDisplayBooks,
                    selected = getSelectedBookName(),
                    defaultBookName = defaultBook,
                    collapsedBookNames = collapsedBooks
                )
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
        val selectedIndex = drawerDisplayBooks.indexOfFirst {
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
        rvBookAccounts.updatePadding(
            top = rvBookAccountsBasePaddingTop,
            bottom = rvBookAccountsBasePaddingBottom
        )
    }

    private fun showInlineAddBookInput() {
        if (isBookNameEditing) return
        btnAddBookAccount.visibility = View.GONE
        layoutAddBookInput.visibility = View.VISIBLE
        etAddBookAccountName.setText("")
        addBookSetDefaultEnabled = false
        btnAddBookSetDefaultToggle.visibility = View.VISIBLE
        updateAddBookSetDefaultToggleUi()
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
        addBookSetDefaultEnabled = false
        btnAddBookSetDefaultToggle.visibility = View.GONE
        btnAddBookAccount.visibility = View.VISIBLE
        updateBookRenameActionButton()
    }

    private fun updateBookRenameEditingState(editing: Boolean) {
        isBookNameEditing = editing
        drawerBooks.setDrawerLockMode(
            if (editing) DrawerLayout.LOCK_MODE_LOCKED_OPEN else DrawerLayout.LOCK_MODE_UNLOCKED,
            GravityCompat.START
        )
        if (editing) {
            layoutAddBookInput.visibility = View.GONE
            addBookSetDefaultEnabled = false
            btnAddBookSetDefaultToggle.visibility = View.GONE
            btnAddBookAccount.visibility = View.VISIBLE
        }
        updateBookRenameActionButton()
    }

    private fun updateBookRenameActionButton() {
        (btnAddBookAccount as? TextView)?.apply {
            if (isBookNameEditing) {
                text = "保存名字"
                setTextColor(android.graphics.Color.parseColor("#2FA36B"))
            } else {
                text = "+ 新增账本"
                setTextColor(android.graphics.Color.parseColor("#3D67DA"))
            }
        }
    }

    private fun updateAddBookSetDefaultToggleUi() {
        btnAddBookSetDefaultToggle.isSelected = addBookSetDefaultEnabled
        if (addBookSetDefaultEnabled) {
            btnAddBookSetDefaultToggle.text = "创建后设为默认账本 · 已开启"
            btnAddBookSetDefaultToggle.setTextColor(android.graphics.Color.parseColor("#3D67DA"))
        } else {
            btnAddBookSetDefaultToggle.text = "创建后设为默认账本 · 未开启"
            btnAddBookSetDefaultToggle.setTextColor(android.graphics.Color.parseColor("#6E7D94"))
        }
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

        val setAsDefault = addBookSetDefaultEnabled
        if (BookAccountManager.addBookAccount(fragment.requireContext(), newName)) {
            if (setAsDefault) {
                BookAccountManager.setDefaultBook(fragment.requireContext(), newName)
            }
            setSelectedBookName(newName)
            hideInlineAddBookInput(clearText = true)
            refreshBookAccounts(reloadTransactions = true)
        } else {
            Toast.makeText(fragment.requireContext(), "新增失败，请重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBookSelected(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        if (target == BookAccountManager.COLLAPSED_BOOK_GROUP) {
            collapsedBooksExpanded = !collapsedBooksExpanded
            val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext())
            drawerDisplayBooks = buildDrawerDisplayBooks(getAvailableBookNames(), defaultBook)
            bookAccountAdapter.submitList(
                books = drawerDisplayBooks,
                selected = getSelectedBookName(),
                defaultBookName = defaultBook,
                collapsedBookNames = BookAccountManager.getCollapsedBookAccounts(fragment.requireContext())
            )
            rvBookAccounts.post { adjustBookListBottomPaddingForWholeRows() }
            return
        }
        if (target == getSelectedBookName()) {
            drawerBooks.closeDrawer(GravityCompat.START)
            return
        }
        setSelectedBookName(target)
        setAnimateNextBookDataReveal(true)
        BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
        bookAccountAdapter.submitList(
            books = drawerDisplayBooks,
            selected = getSelectedBookName(),
            defaultBookName = BookAccountManager.getDefaultBook(fragment.requireContext()),
            collapsedBookNames = BookAccountManager.getCollapsedBookAccounts(fragment.requireContext())
        )
        updateHeaderBanner()
        setPendingBookSwitchName(getSelectedBookName())
        drawerBooks.closeDrawer(GravityCompat.START)
    }

    private fun toggleBookCollapsed(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext())
        if (target == BookAccountManager.ALL_BOOK || target == BookAccountManager.COLLAPSED_BOOK_GROUP) {
            Toast.makeText(fragment.requireContext(), "该入口不能收纳", Toast.LENGTH_SHORT).show()
            return
        }
        if (target == defaultBook) {
            Toast.makeText(fragment.requireContext(), "默认账本不能收纳", Toast.LENGTH_SHORT).show()
            return
        }

        val nextCollapsed = !BookAccountManager.isBookCollapsed(fragment.requireContext(), target)
        val wasSelected = getSelectedBookName() == target
        BookAccountManager.setBookCollapsed(fragment.requireContext(), target, nextCollapsed)
        if (nextCollapsed && wasSelected) {
            setSelectedBookName(defaultBook)
            BookAccountManager.setSelectedBook(fragment.requireContext(), defaultBook)
        }
        val tip = if (nextCollapsed) "已将「$target」移入收纳账本" else "已将「$target」移出收纳账本"
        Toast.makeText(fragment.requireContext(), tip, Toast.LENGTH_SHORT).show()
        refreshBookAccounts(reloadTransactions = nextCollapsed && wasSelected)
    }

    private fun setDefaultBook(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        if (target == BookAccountManager.ALL_BOOK || target == BookAccountManager.COLLAPSED_BOOK_GROUP) return
        BookAccountManager.setDefaultBook(fragment.requireContext(), target)
        BookAccountManager.setBookCollapsed(fragment.requireContext(), target, collapsed = false)
        Toast.makeText(fragment.requireContext(), "已将「$target」设为默认账本", Toast.LENGTH_SHORT).show()
        refreshBookAccounts(reloadTransactions = false)
    }

    private fun showBookManageDialog(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext())
        if (target == BookAccountManager.ALL_BOOK || target == BookAccountManager.COLLAPSED_BOOK_GROUP) return

        dismissKeyboardForDialog()
        data class ManageOption(
            val title: String,
            val desc: String,
            val highRisk: Boolean = false,
            val enabled: Boolean = true,
            val onClick: () -> Unit
        )

        val isDefault = target == defaultBook
        val isCollapsed = BookAccountManager.isBookCollapsed(fragment.requireContext(), target)
        val options = buildList {
            add(
                ManageOption(
                    title = if (isDefault) "已是默认账本" else "设为默认账本",
                    desc = if (isDefault) "默认账本会固定保留在日常列表" else "新账单没有指定账本时会进入这里",
                    enabled = !isDefault,
                    onClick = { setDefaultBook(target) }
                )
            )
            add(
                ManageOption(
                    title = "重命名",
                    desc = "回到列表中直接编辑名称，历史账单会同步更新",
                    onClick = { startInlineRenameBook(target) }
                )
            )
            add(
                ManageOption(
                    title = if (isCollapsed) "移出收纳账本" else "移入收纳账本",
                    desc = if (isCollapsed) "重新显示在日常账本列表" else "从日常记账入口收起，仍可展开访问",
                    enabled = !isDefault,
                    onClick = { toggleBookCollapsed(target) }
                )
            )
            add(
                ManageOption(
                    title = "删除账本",
                    desc = if (isDefault) "默认账本不能删除" else "删除前可选择迁移或处理账单",
                    highRisk = true,
                    enabled = !isDefault,
                    onClick = { deleteBook(target) }
                )
            )
        }

        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_book_delete_options, null, false)
        panel.findViewById<TextView>(R.id.tv_delete_book_title).text = "管理账本「$target」"
        panel.findViewById<TextView>(R.id.tv_delete_book_desc).text =
            if (isCollapsed) "这个账本当前位于收纳账本中" else "选择要执行的整理操作"
        val optionsContainer = panel.findViewById<LinearLayout>(R.id.layout_delete_book_options)
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
        options.forEach { opt ->
            val item = LayoutInflater.from(fragment.requireContext())
                .inflate(R.layout.item_book_delete_option, optionsContainer, false)
            item.findViewById<TextView>(R.id.tv_delete_option_title).text = opt.title
            item.findViewById<TextView>(R.id.tv_delete_option_desc).text = opt.desc
            item.findViewById<TextView>(R.id.tv_delete_option_risk).visibility =
                if (opt.highRisk && opt.enabled) View.VISIBLE else View.GONE
            item.isEnabled = opt.enabled
            item.alpha = if (opt.enabled) 1f else 0.45f
            item.setOnClickListener {
                if (!opt.enabled) return@setOnClickListener
                dialog.dismiss()
                opt.onClick()
            }
            optionsContainer.addView(item)
        }
        panel.findViewById<TextView>(R.id.btn_delete_book_cancel).setOnClickListener { dialog.dismiss() }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = fragment.requireContext(),
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun startInlineRenameBook(bookName: String) {
        hideInlineAddBookInput(clearText = true)
        bookAccountAdapter.startInlineRename(bookName)
    }

    private fun buildDrawerDisplayBooks(books: List<String>, defaultBook: String): List<String> {
        return BookAccountManager.getDisplayBookAccounts(
            context = fragment.requireContext(),
            books = books,
            includeAllBook = true,
            collapsedGroupExpanded = collapsedBooksExpanded,
            defaultBookName = defaultBook
        )
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
            val wasDefault = BookAccountManager.getDefaultBook(context) == oldNorm
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
                if (wasDefault) {
                    BookAccountManager.setDefaultBook(fragment.requireContext(), newNorm)
                }
                BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
                refreshBookAccounts(reloadTransactions = true)
            }
        }
    }

    private fun deleteBook(bookName: String) {
        val target = BookAccountManager.normalizeBookName(bookName)
        val defaultBook = BookAccountManager.getDefaultBook(fragment.requireContext())
        if (target == BookAccountManager.ALL_BOOK) {
            Toast.makeText(fragment.requireContext(), "「全部账本」是系统入口，不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        if (target == defaultBook) {
            Toast.makeText(fragment.requireContext(), "默认账本不能删除，请先切换默认账本", Toast.LENGTH_SHORT).show()
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
                        mode = BookDeleteMode.REMOVE_FROM_BOOK_MOVE_TO_DEFAULT,
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
                desc = "账单迁移到“${BookAccountManager.getDefaultBook(fragment.requireContext())}”，不会丢失记录",
                onClick = {
                    showDeleteFollowupConfirmDialog(
                        title = "确认删除账本",
                        message = "删除后，「$target」内账单会迁移到「${BookAccountManager.getDefaultBook(fragment.requireContext())}」。",
                        confirmText = "确认删除",
                        isDanger = false
                    ) {
                        performDeleteBook(target, BookDeleteMode.REMOVE_FROM_BOOK_MOVE_TO_DEFAULT)
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
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val popupDialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
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

        OverlayDialogs.showPageCenterDialog(
            dialog = popupDialog,
            ctx = fragment.requireContext(),
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun showTransferTargetPickerAndDelete(target: String, transferCandidates: List<String>) {
        dismissKeyboardForDialog()
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_delete_followup_picker, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
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
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = fragment.requireContext(),
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    private fun showDeleteFollowupConfirmDialog(
        title: String,
        message: String,
        confirmText: String,
        isDanger: Boolean,
        onConfirm: () -> Unit
    ) {
        dismissKeyboardForDialog()
        val themeCtx = ContextThemeWrapper(fragment.requireContext(), R.style.Theme_TapAccounting)
        val panel = LayoutInflater.from(fragment.requireContext())
            .inflate(R.layout.dialog_delete_followup_confirm, null, false)
        val dialog = AlertDialog.Builder(themeCtx)
            .setView(panel)
            .create()
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
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = fragment.requireContext(),
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
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
                BookDeleteMode.REMOVE_FROM_BOOK_MOVE_TO_DEFAULT -> {
                    val defaultBook = BookAccountManager.getDefaultBook(ctx)
                    aliases.forEach { alias ->
                        db.billDao().renameBookName(alias, defaultBook)
                        db.chatMessageDao().renameBookName(alias, defaultBook)
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
                        .forEach { com.taostudio.tapaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, it) }
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
                    setSelectedBookName(fallback ?: BookAccountManager.getDefaultBook(fragment.requireContext()))
                }
                BookAccountManager.setSelectedBook(fragment.requireContext(), getSelectedBookName())
                refreshBookAccounts(reloadTransactions = true)
                val tip = when (mode) {
                    BookDeleteMode.MOVE_TO_OTHER_BOOK -> "已删除账本，账单已迁移到「$transferToBook」"
                    BookDeleteMode.REMOVE_FROM_BOOK_MOVE_TO_DEFAULT ->
                        if (hadBillsBeforeDelete) "已删除账本，账单已迁移到「${BookAccountManager.getDefaultBook(fragment.requireContext())}」"
                        else "已删除空账本"
                    BookDeleteMode.DELETE_BILLS_KEEP_ASSETS -> "已删除账本与所有账单（未回退资产）"
                    BookDeleteMode.DELETE_BILLS_AND_REVERT_ASSETS -> "已删除账本与所有账单，并回退资产"
                }
                Toast.makeText(fragment.requireContext(), tip, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

