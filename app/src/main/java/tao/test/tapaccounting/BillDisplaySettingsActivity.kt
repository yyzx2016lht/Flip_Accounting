package tao.test.tapaccounting

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import tao.test.tapaccounting.logic.BillDisplayFormatter

class BillDisplaySettingsActivity : AppCompatActivity() {

    private lateinit var switchShowIcon: CompoundButton
    private lateinit var switchShowFullCategory: CompoundButton
    private lateinit var switchRemarkPriority: CompoundButton
    private lateinit var switchIndependentDetail: CompoundButton

    private lateinit var iconContainer: View
    private lateinit var iconView: ImageView
    private lateinit var textContainer: View
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView

    private val sampleIconUrl = "http://res3.qianjiapp.com/ic_cate2_wancan.png"
    private val sampleCategory = "三餐-晚餐"
    private val sampleRemark = "陪爸妈一起聚餐"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_display_settings)

        findViewById<View>(R.id.btn_back_bill_display).setOnClickListener { finish() }

        iconContainer = findViewById(R.id.layout_preview_icon_container)
        iconView = findViewById(R.id.iv_preview_icon)
        textContainer = findViewById(R.id.layout_preview_text_container)
        titleView = findViewById(R.id.tv_preview_title)
        subtitleView = findViewById(R.id.tv_preview_subtitle)

        switchShowIcon = findViewById(R.id.switch_bill_show_icon)
        switchShowFullCategory = findViewById(R.id.switch_bill_show_full_category)
        switchRemarkPriority = findViewById(R.id.switch_bill_remark_priority)
        switchIndependentDetail = findViewById(R.id.switch_independent_detail)

        switchShowIcon.isChecked = Prefs.isShowBillCategoryIcon(this)
        switchShowFullCategory.isChecked = Prefs.isShowBillFullCategory(this)
        switchRemarkPriority.isChecked = Prefs.isBillRemarkPriority(this)
        switchIndependentDetail.isChecked = Prefs.isIndependentDetailEnabled(this)

        switchShowIcon.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowBillCategoryIcon(this, checked)
            renderPreview()
        }
        switchShowFullCategory.setOnCheckedChangeListener { _, checked ->
            Prefs.setShowBillFullCategory(this, checked)
            renderPreview()
        }
        switchRemarkPriority.setOnCheckedChangeListener { _, checked ->
            Prefs.setBillRemarkPriority(this, checked)
            renderPreview()
        }
        switchIndependentDetail.setOnCheckedChangeListener { _, checked ->
            Prefs.setIndependentDetailEnabled(this, checked)
        }

        listOf(
            R.id.row_switch_bill_icon to switchShowIcon,
            R.id.row_switch_bill_full_category to switchShowFullCategory,
            R.id.row_switch_bill_remark_priority to switchRemarkPriority,
            R.id.row_switch_independent_detail to switchIndependentDetail
        ).forEach { (rowId, switchView) ->
            findViewById<View>(rowId).setOnClickListener { switchView.performClick() }
        }

        renderPreview()
    }

    private fun renderPreview() {
        val showIcon = switchShowIcon.isChecked
        val showFullCategory = switchShowFullCategory.isChecked
        val remarkPriority = switchRemarkPriority.isChecked

        val categoryText = BillDisplayFormatter.formatCategoryByPreference(
            categoryName = sampleCategory,
            showFullCategory = showFullCategory
        )
        val (primaryText, secondaryText) = BillDisplayFormatter.resolvePrimarySecondaryText(
            categoryText = categoryText,
            remarkText = sampleRemark,
            suffixText = "",
            remarkPriority = remarkPriority
        )
        titleView.text = primaryText
        subtitleView.text = secondaryText
        subtitleView.visibility = if (secondaryText.isBlank()) View.GONE else View.VISIBLE

        if (showIcon) {
            iconContainer.setBackgroundResource(R.drawable.bg_circle_expense_soft)
            iconView.clearColorFilter()
            Glide.with(this)
                .load(sampleIconUrl)
                .placeholder(R.drawable.ic_profile_category)
                .error(R.drawable.ic_profile_category)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .into(iconView)
            iconContainer.layoutParams = iconContainer.layoutParams.apply {
                width = dp(44)
                height = dp(44)
            }
            (textContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = dp(13)
                textContainer.layoutParams = it
            }
            iconView.layoutParams = iconView.layoutParams.apply {
                width = dp(22)
                height = dp(22)
            }
        } else {
            iconContainer.setBackgroundColor(Color.TRANSPARENT)
            iconView.clearColorFilter()
            iconContainer.layoutParams = iconContainer.layoutParams.apply {
                width = dp(10)
                height = dp(44)
            }
            (textContainer.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.marginStart = dp(8)
                textContainer.layoutParams = it
            }
            iconView.setImageResource(R.drawable.bg_bill_dot_expense)
            iconView.layoutParams = iconView.layoutParams.apply {
                width = dp(6)
                height = dp(6)
            }
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
}
