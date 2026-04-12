package tao.test.flipaccounting.ui.dialog

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import tao.test.flipaccounting.CategoryNode
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import java.util.*

object OverlayDialogs {

    fun showAnchoredMenu(ctx: Context, anchor: View, items: List<String>, onSelected: (String) -> Unit) {
        val popup = ListPopupWindow(ctx).apply {
            setAdapter(ArrayAdapter(ctx, android.R.layout.simple_list_item_1, items))
            anchorView = anchor
            width = anchor.width
            isModal = true
            setOnItemClickListener { _, _, pos, _ ->
                onSelected(items[pos])
                dismiss()
            }
        }
        popup.show()
    }

    fun showGridCategoryPicker(ctx: Context, currentSelectionText: String, type: Int, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        // Get categories based on type
        val categories = Prefs.getCategories(ctx, type)
        var currentSelection = currentSelectionText.replace(" > ", "/::/")

        fun render() {
            container.removeAllViews()
            val parts = currentSelection.split("/::/")
            val parent = categories.find { it.name == parts.getOrNull(0) }

            categories.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { cat ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
                    val flBg = itemView.findViewById<android.widget.FrameLayout>(R.id.fl_icon_bg)
                    val isSelected = cat.name == parent?.name
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = cat.name
                        setTextColor(Color.parseColor(if (isSelected) "#1976D2" else "#555555"))
                    }
                    flBg.setBackgroundResource(if (isSelected) R.drawable.bg_category_icon_selected else R.drawable.bg_category_icon_normal)
                    ivIcon.setColorFilter(Color.parseColor(if (isSelected) "#1976D2" else "#9E9E9E"))
                    Glide.with(ctx).load(cat.icon).into(ivIcon)
                    itemView.setOnClickListener {
                        currentSelection = if (currentSelection.split("/::/").getOrNull(0) == cat.name) "" else cat.name
                        render()
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                // Fill empty spaces for left alignment
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) {
                        val emptyView = View(ctx)
                        rowLayout.addView(emptyView, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
                container.addView(rowLayout)
                if (parent != null && row.any { it.name == parent.name } && parent.subs.isNotEmpty()) {
                    container.addView(createSubPanel(ctx, parent, parts.getOrNull(1)) {
                        currentSelection = "${parent.name}/::/${it.name}"
                        render()
                    })
                }
            }
        }
        render()

        view.findViewById<Button>(R.id.btn_confirm_category).setOnClickListener {
            onConfirm(currentSelection.replace("/::/", " > "))
            dialog.dismiss()
        }

        dialog.window?.let {
            it.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 使宽度和位置与主弹窗一致
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        dialog.show()
    }

    private fun createSubPanel(ctx: Context, parent: CategoryNode, selected: String?, onClick: (CategoryNode) -> Unit): View {
        return GridLayout(ctx).apply {
            columnCount = 5
            val dp12 = (12 * ctx.resources.displayMetrics.density).toInt()
            val dp8 = (8 * ctx.resources.displayMetrics.density).toInt()
            setPadding(dp8, dp12, dp8, dp8)
            setBackgroundResource(R.drawable.bg_category_sub_panel)
            parent.subs.forEach { sub ->
                val item = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, this, false)
                item.findViewById<TextView>(R.id.tv_category_name).text = sub.name
                val ivIcon = item.findViewById<ImageView>(R.id.iv_category_icon)
                val flBg = item.findViewById<android.widget.FrameLayout>(R.id.fl_icon_bg)
                val isSubSelected = sub.name == selected
                item.findViewById<TextView>(R.id.tv_category_name).apply {
                    text = sub.name
                    setTextColor(Color.parseColor(if (isSubSelected) "#1976D2" else "#555555"))
                }
                flBg.setBackgroundResource(if (isSubSelected) R.drawable.bg_category_icon_selected else R.drawable.bg_category_icon_normal)
                ivIcon.setColorFilter(Color.parseColor(if (isSubSelected) "#1976D2" else "#9E9E9E"))
                Glide.with(ctx).load(sub.icon).into(ivIcon)
                item.setOnClickListener { onClick(sub) }
                addView(item, GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply { width = 0 })
            }
            
            // Fill empty spaces for sub panel left alignment
            val remainder = parent.subs.size % 5
            if (remainder != 0) {
                for (i in 0 until (5 - remainder)) {
                    addView(View(ctx), GridLayout.LayoutParams(
                        GridLayout.spec(GridLayout.UNDEFINED, 1f),
                        GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    ).apply { width = 0 })
                }
            }
        }
    }

    fun showCustomTimePicker(ctx: Context, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.layout_custom_time_picker, null)
        val dialog = AlertDialog.Builder(themeContext).setView(view).create()

        val cal = Calendar.getInstance()
        val npYear = view.findViewById<NumberPicker>(R.id.np_year).apply { minValue = 2024; maxValue = 2030; value = cal.get(Calendar.YEAR) }
        val npMonth = view.findViewById<NumberPicker>(R.id.np_month).apply { minValue = 1; maxValue = 12; value = cal.get(Calendar.MONTH) + 1 }
        val npDay = view.findViewById<NumberPicker>(R.id.np_day).apply { minValue = 1; maxValue = 31; value = cal.get(Calendar.DAY_OF_MONTH) }
        val npHour = view.findViewById<NumberPicker>(R.id.np_hour).apply { minValue = 0; maxValue = 23; value = cal.get(Calendar.HOUR_OF_DAY) }
        val npMin = view.findViewById<NumberPicker>(R.id.np_minute).apply { minValue = 0; maxValue = 59; value = cal.get(Calendar.MINUTE) }

        view.findViewById<View>(R.id.btn_confirm_time).setOnClickListener {
            val timeStr = String.format(Locale.getDefault(), "%d-%02d-%02d %02d:%02d:00", npYear.value, npMonth.value, npDay.value, npHour.value, npMin.value)
            onConfirm(timeStr)
            dialog.dismiss()
        }
        view.findViewById<View>(R.id.btn_cancel_time)?.setOnClickListener {
            dialog.dismiss() // 直接关闭对话框，不触发 onConfirm 回调
        }
        dialog.window?.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
        dialog.show()
    }

    fun showGridAssetPicker(ctx: Context, currentSelectionText: String, title: String, onConfirm: (String) -> Unit) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_category_picker, null)

        // 修改标题
        val tvTitle = view.findViewWithTag<TextView>("dialog_title")
        if (tvTitle != null) {
            tvTitle.text = title
        } else {
            // 如果没有tag，尝试找第一个TextView（根据布局结构）
            if (view is LinearLayout) {
                val firstChild = view.getChildAt(0)
                if (firstChild is TextView) {
                    firstChild.text = title
                }
            }
        }

        val dialog = AlertDialog.Builder(themeContext).setView(view).create()
        val container = view.findViewById<LinearLayout>(R.id.container_categories)
        val assets = Prefs.getAssets(ctx)
        var currentSelection = currentSelectionText

        fun render() {
            container.removeAllViews()

            assets.chunked(5).forEach { row ->
                val rowLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                row.forEach { asset ->
                    val itemView = LayoutInflater.from(ctx).inflate(R.layout.item_category_grid, rowLayout, false)
                    itemView.findViewById<TextView>(R.id.tv_category_name).apply {
                        text = asset.name
                        if (asset.name == currentSelection) setTextColor(Color.parseColor("#2196F3"))
                        else setTextColor(Color.parseColor("#333333"))
                    }
                    val ivIcon = itemView.findViewById<ImageView>(R.id.iv_category_icon)
                    // 清除因为复用布局而在 XML 中携带的 tint 滤镜，让资产图标保持原色和彩色
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        ivIcon.imageTintList = null
                    }
                    ivIcon.clearColorFilter()
                    
                    if (asset.icon.isNotEmpty()) {
                        Glide.with(ctx).load(asset.icon).transform(CircleCrop()).into(ivIcon)
                    } else {
                        ivIcon.setImageResource(R.mipmap.ic_launcher_round) // Fallback
                    }
                    
                    itemView.setOnClickListener {
                        currentSelection = asset.name
                        render()
                    }
                    rowLayout.addView(itemView, LinearLayout.LayoutParams(0, -2, 1f))
                }
                // Fill empty spaces
                if (row.size < 5) {
                    for (i in 0 until (5 - row.size)) {
                        val emptyView = View(ctx)
                        rowLayout.addView(emptyView, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                }
                container.addView(rowLayout)
            }
        }
        render()

        view.findViewById<Button>(R.id.btn_confirm_category).setOnClickListener {
            if (currentSelection.isNotEmpty() && !currentSelection.contains("选择")) {
                onConfirm(currentSelection)
                dialog.dismiss()
            } else {
                Toast.makeText(ctx, "请选择资产", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.window?.let {
            it.setType(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE)
            it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            it.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            // 使宽度和位置与主弹窗一致
            it.decorView.setPadding(0, 0, 0, 0)
            val lp = it.attributes
            lp.width = (ctx.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.y = 150
            it.attributes = lp
        }
        dialog.show()
    }

    fun showShizukuPrompt(ctx: Context) {
        val themeContext = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val dialog = AlertDialog.Builder(themeContext)
            .setTitle("需要 Shizuku 权限")
            .setMessage("您想使用白名单功能，但尚未启动 Shizuku 或授予本应用 Shizuku 权限。请进入 Shizuku 应用进行授权。")
            .setPositiveButton("去授权") { d, _ ->
                d.dismiss()
                try {
                    val intent = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                    } else {
                        Toast.makeText(ctx, "未找到 Shizuku 应用", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(ctx, "无法打开 Shizuku", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .create()
            
        dialog.window?.setType(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            else WindowManager.LayoutParams.TYPE_PHONE
        )
        dialog.show()
    }
}
