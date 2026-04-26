package tao.test.flipaccounting.ui.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.res.use
import tao.test.flipaccounting.R

class SecondaryPageHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val tvTitle: TextView
    private val tvSubtitle: TextView
    private val btnActionSecondary: ImageView
    private val btnAction: ImageView
    private val btnActionText: TextView
    private val layoutViewModeSwitch: LinearLayout
    private val btnViewModeMonth: TextView
    private val btnViewModeYear: TextView

    init {
        LayoutInflater.from(context).inflate(R.layout.view_secondary_page_header, this, true)
        tvTitle = findViewById(R.id.tv_header_title)
        tvSubtitle = findViewById(R.id.tv_header_subtitle)
        btnActionSecondary = findViewById(R.id.btn_header_action_secondary)
        btnAction = findViewById(R.id.btn_header_action)
        btnActionText = findViewById(R.id.btn_header_action_text)
        layoutViewModeSwitch = findViewById(R.id.layout_view_mode_switch)
        btnViewModeMonth = findViewById(R.id.btn_view_mode_month)
        btnViewModeYear = findViewById(R.id.btn_view_mode_year)

        context.obtainStyledAttributes(attrs, R.styleable.SecondaryPageHeaderView).use { ta ->
            setTitle(ta.getString(R.styleable.SecondaryPageHeaderView_headerTitle).orEmpty())
            setSubtitle(ta.getString(R.styleable.SecondaryPageHeaderView_headerSubtitle))

            val actionIcon = ta.getResourceId(
                R.styleable.SecondaryPageHeaderView_headerActionIcon,
                0
            )
            if (actionIcon != 0) {
                btnAction.setImageResource(actionIcon)
            }

            val secondaryActionIcon = ta.getResourceId(
                R.styleable.SecondaryPageHeaderView_headerSecondaryActionIcon,
                0
            )
            if (secondaryActionIcon != 0) {
                btnActionSecondary.setImageResource(secondaryActionIcon)
            }

            val actionVisible = ta.getBoolean(
                R.styleable.SecondaryPageHeaderView_headerActionVisible,
                actionIcon != 0
            )
            btnAction.visibility = if (actionVisible && actionIcon != 0) View.VISIBLE else View.GONE

            val secondaryActionVisible = ta.getBoolean(
                R.styleable.SecondaryPageHeaderView_headerSecondaryActionVisible,
                secondaryActionIcon != 0
            )
            btnActionSecondary.visibility =
                if (secondaryActionVisible && secondaryActionIcon != 0) View.VISIBLE else View.GONE

            setActionText(ta.getString(R.styleable.SecondaryPageHeaderView_headerActionText))
        }
    }

    fun setTitle(title: String) {
        tvTitle.text = title
    }

    fun setSubtitle(subtitle: String?) {
        val text = subtitle?.trim().orEmpty()
        tvSubtitle.text = text
        tvSubtitle.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setActionIcon(@DrawableRes iconRes: Int?) {
        if (iconRes == null || iconRes == 0) {
            btnAction.visibility = View.GONE
            return
        }
        btnAction.setImageResource(iconRes)
        btnAction.visibility = View.VISIBLE
    }

    fun setSecondaryActionIcon(@DrawableRes iconRes: Int?) {
        if (iconRes == null || iconRes == 0) {
            btnActionSecondary.visibility = View.GONE
            return
        }
        btnActionSecondary.setImageResource(iconRes)
        btnActionSecondary.visibility = View.VISIBLE
    }

    fun setActionText(text: String?) {
        val content = text?.trim().orEmpty()
        btnActionText.text = content
        btnActionText.visibility = if (content.isEmpty()) View.GONE else View.VISIBLE
    }

    val viewModeSwitch: LinearLayout get() = layoutViewModeSwitch
    val viewModeMonthBtn: TextView get() = btnViewModeMonth
    val viewModeYearBtn: TextView get() = btnViewModeYear
}
