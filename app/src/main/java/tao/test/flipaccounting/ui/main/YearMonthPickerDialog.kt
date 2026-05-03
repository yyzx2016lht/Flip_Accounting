package tao.test.flipaccounting.ui.main

import android.content.Context
import android.graphics.Rect
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tao.test.flipaccounting.R
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

object YearMonthPickerDialog {

    enum class DisplayMode(val label: String) {
        MONTH("按月"),
        YEAR("按年"),
        ALL("全部")
    }

    private val supportedYears = (2000..2100).toList()
    private val supportedMonths = (1..12).toList()

    /**
     * 兼容旧调用：
     * - yearOnly=false: 月份选择（即时生效）
     * - yearOnly=true: 年份选择（即时生效）
     */
    fun show(
        context: Context,
        title: String,
        initialYear: Int,
        initialMonth: Int,
        yearOnly: Boolean = false,
        onConfirm: (year: Int, month: Int) -> Unit
    ) {
        val initialMode = if (yearOnly) DisplayMode.YEAR else DisplayMode.MONTH
        val enabled = if (yearOnly) listOf(DisplayMode.YEAR) else listOf(DisplayMode.MONTH)
        showInternal(
            context = context,
            initialYear = initialYear,
            initialMonth = initialMonth,
            initialMode = initialMode,
            enabledModes = enabled,
            onPickMonth = { year, month -> onConfirm(year, month) },
            onPickYear = { year -> onConfirm(year, initialMonth.coerceIn(1, 12)) },
            onPickAll = null
        )
    }

    /**
     * 新入口：支持按月/按年/全部切换。
     */
    fun showModePicker(
        context: Context,
        initialYear: Int,
        initialMonth: Int,
        initialMode: DisplayMode,
        enabledModes: List<DisplayMode>,
        onPickMonth: (year: Int, month: Int) -> Unit,
        onPickYear: (year: Int) -> Unit,
        onPickAll: (() -> Unit)? = null
    ) {
        showInternal(
            context = context,
            initialYear = initialYear,
            initialMonth = initialMonth,
            initialMode = initialMode,
            enabledModes = enabledModes,
            onPickMonth = onPickMonth,
            onPickYear = onPickYear,
            onPickAll = onPickAll
        )
    }

    private fun showInternal(
        context: Context,
        initialYear: Int,
        initialMonth: Int,
        initialMode: DisplayMode,
        enabledModes: List<DisplayMode>,
        onPickMonth: (year: Int, month: Int) -> Unit,
        onPickYear: (year: Int) -> Unit,
        onPickAll: (() -> Unit)?
    ) {
        val modes = enabledModes.distinct().filter {
            it != DisplayMode.ALL || onPickAll != null
        }.ifEmpty { listOf(DisplayMode.MONTH) }

        var selectedYear = initialYear.coerceIn(supportedYears.first(), supportedYears.last())
        var selectedMonth = initialMonth.coerceIn(1, 12)
        var currentMode = if (initialMode in modes) initialMode else modes.first()

        val root = LayoutInflater.from(context).inflate(R.layout.layout_year_month_picker, null, false)
        val dialog = AlertDialog.Builder(context).setView(root).create()

        val modeSwitch = root.findViewById<LinearLayout>(R.id.layout_mode_switch)
        val tvModeValue = root.findViewById<TextView>(R.id.tv_mode_value)
        val panelMonth = root.findViewById<LinearLayout>(R.id.panel_month)
        val panelAll = root.findViewById<LinearLayout>(R.id.panel_all)
        val rvYearList = root.findViewById<RecyclerView>(R.id.rv_year_list)
        val rvMonthGrid = root.findViewById<RecyclerView>(R.id.rv_month_grid)
        val rvYearGrid = root.findViewById<RecyclerView>(R.id.rv_year_grid)
        val btnAllConfirm = root.findViewById<View>(R.id.btn_all_confirm)

        // Keep year list rows complete: compute panel height from item height.
        val density = context.resources.displayMetrics.density
        val itemHeightPx = (42f * density).toInt()
        val visibleRows = 6
        val contentHeightPx = itemHeightPx * visibleRows
        val panelHeightPx = contentHeightPx + panelMonth.paddingTop + panelMonth.paddingBottom
        panelMonth.layoutParams = panelMonth.layoutParams.apply { height = panelHeightPx }
        rvYearGrid.layoutParams = rvYearGrid.layoutParams.apply { height = panelHeightPx }

        val yearListAdapter = YearAdapter(
            years = supportedYears,
            selectedYear = selectedYear
        ) { year ->
            selectedYear = year
            if (currentMode == DisplayMode.YEAR) {
                onPickYear(year)
                dialog.dismiss()
            }
        }

        val monthGridAdapter = MonthAdapter(
            months = supportedMonths,
            selectedMonth = selectedMonth
        ) { month ->
            selectedMonth = month
            if (currentMode == DisplayMode.MONTH) {
                onPickMonth(selectedYear, month)
                dialog.dismiss()
            }
        }

        val yearGridAdapter = YearAdapter(
            years = supportedYears.reversed(),
            selectedYear = selectedYear
        ) { year ->
            selectedYear = year
            if (currentMode == DisplayMode.YEAR) {
                onPickYear(year)
                dialog.dismiss()
            }
        }

        rvYearList.layoutManager = LinearLayoutManager(context)
        rvYearList.adapter = yearListAdapter
        rvMonthGrid.layoutManager = object : GridLayoutManager(context, 2) {
            override fun canScrollVertically(): Boolean = false
        }
        rvMonthGrid.adapter = monthGridAdapter
        val yearGridLayoutManager = GridLayoutManager(context, 3)
        yearGridLayoutManager.stackFromEnd = false
        rvYearGrid.layoutManager = yearGridLayoutManager
        rvYearGrid.adapter = yearGridAdapter
        rvYearGrid.addItemDecoration(GridSpaceDecoration((4f * density).toInt()))

        rvYearList.post {
            val index = supportedYears.indexOf(selectedYear).coerceAtLeast(0)
            (rvYearList.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(index, 0)
        }
        fun alignSelectedYearToTopRight() {
            val reversed = supportedYears.reversed()
            val index = reversed.indexOf(selectedYear).coerceAtLeast(0)
            yearGridLayoutManager.scrollToPositionWithOffset(index, 0)
        }

        rvYearGrid.post {
            alignSelectedYearToTopRight()
        }

        val applyMode = {
            tvModeValue.text = currentMode.label
            panelMonth.visibility = if (currentMode == DisplayMode.MONTH) View.VISIBLE else View.GONE
            rvYearGrid.visibility = if (currentMode == DisplayMode.YEAR) View.VISIBLE else View.GONE
            panelAll.visibility = if (currentMode == DisplayMode.ALL) View.VISIBLE else View.GONE
            if (currentMode == DisplayMode.YEAR) {
                rvYearGrid.post { alignSelectedYearToTopRight() }
            }
        }
        applyMode()

        if (modes.size > 1) {
            modeSwitch.setOnClickListener {
                val currentIndex = modes.indexOf(currentMode)
                val nextIndex = if (currentIndex < 0) 0 else (currentIndex + 1) % modes.size
                currentMode = modes[nextIndex]
                applyMode()
            }
        } else {
            modeSwitch.isClickable = false
        }

        btnAllConfirm.setOnClickListener {
            if (currentMode == DisplayMode.ALL) {
                onPickAll?.invoke()
                dialog.dismiss()
            }
        }

        val isLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val widthRatio = if (isLandscape) 0.54f else 0.80f

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = context,
            widthRatio = widthRatio
        )

        val targetWidthDp = when {
            currentMode == DisplayMode.YEAR -> 304f
            currentMode == DisplayMode.ALL -> 320f
            else -> 324f
        }
        val densityForWidth = context.resources.displayMetrics.density
        val targetWidthPx = (targetWidthDp * densityForWidth).toInt()
        val maxWidthPx = (context.resources.displayMetrics.widthPixels * widthRatio).toInt()
        dialog.window?.setLayout(
            minOf(targetWidthPx, maxWidthPx),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
    }

    private class GridSpaceDecoration(private val spacePx: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.left = spacePx
            outRect.right = spacePx
            outRect.top = spacePx
            outRect.bottom = spacePx
        }
    }

    private class YearAdapter(
        private val years: List<Int>,
        selectedYear: Int,
        private val onYearSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<YearAdapter.YearViewHolder>() {

        private var selectedValue: Int = selectedYear

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): YearViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_year_picker_entry, parent, false)
            return YearViewHolder(view)
        }

        override fun onBindViewHolder(holder: YearViewHolder, position: Int) {
            holder.bind(years[position], years[position] == selectedValue)
        }

        override fun getItemCount(): Int = years.size

        inner class YearViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvYear = view.findViewById<TextView>(R.id.tv_year_entry)

            fun bind(year: Int, selected: Boolean) {
                tvYear.text = "${year}年"
                tvYear.setBackgroundResource(
                    if (selected) R.drawable.bg_year_month_selected_pill else android.R.color.transparent
                )
                tvYear.setTextColor(
                    if (selected) 0xFFFFFFFF.toInt() else 0xFF1F2937.toInt()
                )
                itemView.setOnClickListener {
                    if (selectedValue != year) {
                        val oldIndex = years.indexOf(selectedValue)
                        selectedValue = year
                        if (oldIndex >= 0) notifyItemChanged(oldIndex)
                        notifyItemChanged(adapterPosition)
                    }
                    onYearSelected(year)
                }
            }
        }
    }

    private class MonthAdapter(
        private val months: List<Int>,
        selectedMonth: Int,
        private val onMonthSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<MonthAdapter.MonthViewHolder>() {

        private var selectedValue: Int = selectedMonth

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_month_picker_entry, parent, false)
            return MonthViewHolder(view)
        }

        override fun onBindViewHolder(holder: MonthViewHolder, position: Int) {
            holder.bind(months[position], months[position] == selectedValue)
        }

        override fun getItemCount(): Int = months.size

        inner class MonthViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvMonth = view.findViewById<TextView>(R.id.tv_month_entry)

            fun bind(month: Int, selected: Boolean) {
                tvMonth.text = "${month}月"
                tvMonth.setTextColor(
                    if (selected) 0xFF5C6BC0.toInt() else 0xFF1F2937.toInt()
                )
                itemView.setOnClickListener {
                    if (selectedValue != month) {
                        val oldIndex = months.indexOf(selectedValue)
                        selectedValue = month
                        if (oldIndex >= 0) notifyItemChanged(oldIndex)
                        notifyItemChanged(adapterPosition)
                    }
                    onMonthSelected(month)
                }
            }
        }
    }
}
