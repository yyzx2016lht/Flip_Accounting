package tao.test.flipaccounting.ui.main.home

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.R
import kotlin.math.abs

class BookAccountAdapter(
    private val onItemClick: (String) -> Unit,
    private val onRenameClick: (oldName: String, newName: String) -> Unit,
    private val onSetDefaultClick: (name: String) -> Unit,
    private val onDeleteClick: (name: String) -> Unit,
    private val onOrderChanged: (newOrder: List<String>) -> Unit,
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<BookAccountAdapter.BookViewHolder>() {

    private val items = mutableListOf<String>()
    private var selectedBook: String = ""
    private var defaultBook: String = BookAccountManager.DEFAULT_BOOK
    private var openedPosition: Int = RecyclerView.NO_POSITION
    private var editingPosition: Int = RecyclerView.NO_POSITION

    fun submitList(books: List<String>, selected: String, defaultBookName: String) {
        val openedName = items.getOrNull(openedPosition)
        val editingName = items.getOrNull(editingPosition)

        defaultBook = BookAccountManager.normalizeBookName(defaultBookName)
        items.clear()
        books.map { BookAccountManager.normalizeBookName(it) }
            .filter { it.isNotBlank() }
            .forEach {
                if (!items.contains(it)) items.add(it)
            }
        if (!items.contains(defaultBook)) {
            items.add(0, defaultBook)
        }
        selectedBook = selected

        openedPosition = items.indexOf(openedName).takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        editingPosition = items.indexOf(editingName).takeIf { it >= 0 } ?: RecyclerView.NO_POSITION
        notifyDataSetChanged()
    }

    fun onItemMove(fromPos: Int, toPos: Int): Boolean {
        if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
        if (fromPos !in items.indices || toPos !in items.indices) return false
        if (!isDraggablePosition(fromPos) || !isDraggablePosition(toPos)) return false
        val item = items.removeAt(fromPos)
        items.add(toPos, item)
        notifyItemMoved(fromPos, toPos)
        return true
    }

    fun isDraggablePosition(position: Int): Boolean {
        if (position !in items.indices) return false
        val normalized = BookAccountManager.normalizeBookName(items[position])
        return normalized != BookAccountManager.ALL_BOOK && normalized != defaultBook
    }

    fun onDragEnd() {
        onOrderChanged(items.toList())
    }

    fun closeSwipeActions() {
        if (openedPosition != RecyclerView.NO_POSITION) {
            val old = openedPosition
            openedPosition = RecyclerView.NO_POSITION
            notifyItemChanged(old)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book_account, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(
            name = items[position],
            selected = items[position] == selectedBook,
            opened = position == openedPosition,
            editing = position == editingPosition
        )
    }

    override fun getItemCount(): Int = items.size

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val foreground: View = itemView.findViewById(R.id.layout_foreground)
        private val tvName: TextView = itemView.findViewById(R.id.tv_book_name)
        private val etName: EditText = itemView.findViewById(R.id.et_book_name)
        private val ivSelected: TextView = itemView.findViewById(R.id.iv_book_selected)
        private val btnSetDefaultInline: TextView = itemView.findViewById(R.id.btn_book_set_default_inline)
        private val btnEdit: ImageView = itemView.findViewById(R.id.btn_book_edit)
        private val btnDelete: ImageView = itemView.findViewById(R.id.btn_book_delete)

        private val slop = ViewConfiguration.get(itemView.context).scaledTouchSlop
        private val actionsWidthPx = 120f * itemView.resources.displayMetrics.density

        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var dragging = false
        private var movedBeyondTapSlop = false
        private var longPressRunnable: Runnable? = null
        private var dragStartedByLongPress = false

        init {
            foreground.setOnTouchListener { _, ev -> onForegroundTouch(ev) }
            foreground.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (editingPosition == pos) return@setOnClickListener

                if (openedPosition == pos) {
                    closeSwipeActions()
                    return@setOnClickListener
                }

                closeSwipeActions()
                onItemClick(items[pos])
            }

            btnEdit.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                closeSwipeActions()
                startInlineEdit(pos)
            }

            btnDelete.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                closeSwipeActions()
                onDeleteClick(items[pos])
            }
            btnSetDefaultInline.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val target = items[pos]
                if (BookAccountManager.normalizeBookName(target) == defaultBook) return@setOnClickListener
                onSetDefaultClick(target)
            }

            etName.setOnEditorActionListener { _, actionId, event ->
                val imeDone = actionId == EditorInfo.IME_ACTION_DONE
                val keyDone = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (imeDone || keyDone) {
                    commitInlineRename()
                    true
                } else {
                    false
                }
            }

            etName.setOnFocusChangeListener { _, hasFocus ->
                val pos = adapterPosition
                if (!hasFocus && pos != RecyclerView.NO_POSITION && editingPosition == pos) {
                    commitInlineRename()
                }
            }
        }

        private fun onForegroundTouch(ev: MotionEvent): Boolean {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION || editingPosition == pos) return false

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startTx = foreground.translationX
                    dragging = false
                    movedBeyondTapSlop = false
                    dragStartedByLongPress = false
                    if (openedPosition != RecyclerView.NO_POSITION && openedPosition != pos) {
                        val old = openedPosition
                        openedPosition = RecyclerView.NO_POSITION
                        notifyItemChanged(old)
                    }
                    longPressRunnable?.let { foreground.removeCallbacks(it) }
                    if (isDraggablePosition(pos)) {
                        val runnable = Runnable {
                            val currentPos = adapterPosition
                            if (currentPos == RecyclerView.NO_POSITION || !isDraggablePosition(currentPos)) return@Runnable
                            if (editingPosition == currentPos || movedBeyondTapSlop || dragging) return@Runnable
                            dragStartedByLongPress = true
                            closeSwipeActions()
                            foreground.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            itemView.parent?.requestDisallowInterceptTouchEvent(false)
                            onStartDrag(this)
                        }
                        longPressRunnable = runnable
                        foreground.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    // 提前告知父容器不要拦截，确保 MOVE 能持续送达
                    itemView.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) {
                        movedBeyondTapSlop = true
                        longPressRunnable?.let {
                            foreground.removeCallbacks(it)
                            longPressRunnable = null
                        }
                    }
                    if (dragStartedByLongPress) {
                        // 长按进入排序后，事件交还给 RecyclerView + ItemTouchHelper 处理拖拽
                        itemView.parent?.requestDisallowInterceptTouchEvent(false)
                        return false
                    }
                    if (!dragging) {
                        when {
                            abs(dx) > slop && abs(dx) > abs(dy) -> {
                                // 确认横向拖动
                                dragging = true
                            }
                            abs(dy) > slop -> {
                                // 确认纵向滚动，释放拦截权，让 RecyclerView/NestedScrollView 接管
                                dragging = false
                                itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                return false
                            }
                        }
                    }
                    if (dragging) {
                        // 向左滑（dx < 0）露出右侧编辑/删除按钮，translationX 范围 [-actionsWidthPx, 0]
                        val tx = (startTx + dx).coerceIn(-actionsWidthPx, 0f)
                        foreground.translationX = tx
                        return true
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressRunnable?.let {
                        foreground.removeCallbacks(it)
                        longPressRunnable = null
                    }
                    itemView.parent?.requestDisallowInterceptTouchEvent(false)
                    if (dragStartedByLongPress) {
                        dragStartedByLongPress = false
                        return false
                    }
                    if (dragging) {
                        settleSwipe(pos)
                        dragging = false
                        return true
                    }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        val isTap = !movedBeyondTapSlop && abs(dx) <= slop && abs(dy) <= slop
                        if (isTap) {
                            foreground.performClick()
                        }
                        return true
                    }
                }
            }
            return false
        }

        private fun settleSwipe(pos: Int) {
            // 向左滑超过40%则弹出，否则回弹；向右滑时关闭
            val shouldOpen = foreground.translationX < -actionsWidthPx * 0.4f
            val target = if (shouldOpen) -actionsWidthPx else 0f
            if (shouldOpen) {
                val old = openedPosition
                openedPosition = pos
                if (old != RecyclerView.NO_POSITION && old != pos) {
                    notifyItemChanged(old)
                }
            } else if (openedPosition == pos) {
                openedPosition = RecyclerView.NO_POSITION
            }
            foreground.animate().translationX(target).setDuration(180L).start()
        }

        private fun startInlineEdit(pos: Int) {
            val old = editingPosition
            editingPosition = pos
            if (old != RecyclerView.NO_POSITION && old != pos) {
                notifyItemChanged(old)
            }
            notifyItemChanged(pos)
        }

        private fun commitInlineRename() {
            val pos = adapterPosition
            if (pos == RecyclerView.NO_POSITION) return
            val oldName = items.getOrNull(pos) ?: return
            val newName = etName.text?.toString()?.trim().orEmpty()
            editingPosition = RecyclerView.NO_POSITION
            hideKeyboard(etName)
            // Focus changes may happen while RecyclerView is in layout/scroll.
            // Post notify to next frame to avoid "Cannot call this method while RecyclerView is computing a layout".
            itemView.post {
                if (pos in 0 until itemCount) {
                    notifyItemChanged(pos)
                }
            }

            if (newName.isBlank() || newName == oldName) return
            onRenameClick(oldName, newName)
        }

        private fun hideKeyboard(view: View) {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }

        fun bind(name: String, selected: Boolean, opened: Boolean, editing: Boolean) {
            val normalized = BookAccountManager.normalizeBookName(name)
            val isAllBook = normalized == BookAccountManager.ALL_BOOK
            val isDefaultBook = normalized == defaultBook

            foreground.animate().cancel()
            tvName.text = when {
                isAllBook -> BookAccountManager.ALL_BOOK
                isDefaultBook -> defaultBook
                else -> name
            }
            foreground.translationX = if (opened) -actionsWidthPx else 0f
            foreground.setBackgroundResource(if (selected) R.drawable.bg_book_item_selected else R.drawable.bg_book_item_normal)
            ivSelected.visibility = if (selected) View.VISIBLE else View.GONE

            if (isAllBook) {
                tvName.setTextColor(android.graphics.Color.parseColor("#8E9CAF"))
                btnEdit.isEnabled = false
                btnDelete.isEnabled = false
                btnEdit.alpha = 0.35f
                btnDelete.alpha = 0.35f
            } else if (isDefaultBook) {
                tvName.setTextColor(android.graphics.Color.parseColor("#1A73E8"))
                btnEdit.isEnabled = true
                btnDelete.isEnabled = false
                btnEdit.alpha = 1f
                btnDelete.alpha = 0.35f
            } else {
                tvName.setTextColor(android.graphics.Color.parseColor("#333333"))
                btnEdit.isEnabled = true
                btnDelete.isEnabled = true
                btnEdit.alpha = 1f
                btnDelete.alpha = 1f
            }

            if (editing) {
                tvName.visibility = View.GONE
                etName.visibility = View.VISIBLE
                if (!isAllBook) {
                    btnSetDefaultInline.visibility = View.VISIBLE
                    btnSetDefaultInline.isEnabled = !isDefaultBook
                    btnSetDefaultInline.alpha = if (isDefaultBook) 0.6f else 1f
                    btnSetDefaultInline.text = if (isDefaultBook) "已是默认" else "设为默认"
                } else {
                    btnSetDefaultInline.visibility = View.GONE
                }
                if (etName.text?.toString() != name) {
                    etName.setText(name)
                }
                etName.post {
                    if (adapterPosition != RecyclerView.NO_POSITION && adapterPosition == editingPosition) {
                        etName.requestFocus()
                        etName.setSelection(etName.text?.length ?: 0)
                        val imm = etName.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.showSoftInput(etName, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            } else {
                tvName.visibility = View.VISIBLE
                etName.visibility = View.GONE
                btnSetDefaultInline.visibility = View.GONE
            }
        }
    }
}
