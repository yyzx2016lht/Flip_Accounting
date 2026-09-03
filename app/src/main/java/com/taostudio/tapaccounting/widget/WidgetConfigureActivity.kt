package com.taostudio.tapaccounting.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.taostudio.tapaccounting.BookAccountManager
import com.taostudio.tapaccounting.R

/**
 * 小组件的配置页。
 *
 * 两种入口都会打开这个页面：
 * 1. 系统在用户"添加小组件到桌面"时会带着 ACTION_APPWIDGET_CONFIGURE + appWidgetId 启动它；
 * 2. App 内"桌面小组件"设置页点击"编辑"时，也会带着已放置的 appWidgetId 打开它（此时不需要走配置握手）。
 */
class WidgetConfigureActivity : AppCompatActivity() {

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID
    private var isConfigureFlow: Boolean = false

    private lateinit var rgBook: RadioGroup
    private lateinit var rgPeriod: RadioGroup
    private lateinit var cbExpense: CheckBox
    private lateinit var cbBudget: CheckBox
    private lateinit var cbRemaining: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 标准 App Widget 配置握手：先设为取消，保存成功后再改成 OK。
        // 这样如果用户在配置页直接按返回键，系统不会把这个小组件加到桌面上。
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        isConfigureFlow = intent?.action == AppWidgetManager.ACTION_APPWIDGET_CONFIGURE

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.activity_widget_configure)

        rgBook = findViewById(R.id.rg_widget_book)
        rgPeriod = findViewById(R.id.rg_widget_period)
        cbExpense = findViewById(R.id.cb_metric_expense)
        cbBudget = findViewById(R.id.cb_metric_budget)
        cbRemaining = findViewById(R.id.cb_metric_remaining)

        findViewById<View>(R.id.btn_widget_config_back).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save_widget_config).setOnClickListener { save() }

        val existing = WidgetConfigStore.load(this, appWidgetId) ?: WidgetConfig.default(this)
        populateBooks(existing.bookName)
        populatePeriod(existing.period)
        populateMetrics(existing.metrics)
    }

    private fun populateBooks(selectedBook: String) {
        rgBook.removeAllViews()
        val books = BookAccountManager.getBookAccounts(this) + BookAccountManager.ALL_BOOK
        val normalizedSelected = BookAccountManager.normalizeBookName(selectedBook)
        books.distinct().forEach { book ->
            val radio = RadioButton(this).apply {
                text = book
                id = View.generateViewId()
                tag = book
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
            }
            rgBook.addView(radio)
            if (book == normalizedSelected) rgBook.check(radio.id)
        }
        if (rgBook.checkedRadioButtonId == View.NO_ID && rgBook.childCount > 0) {
            rgBook.check(rgBook.getChildAt(0).id)
        }
    }

    private fun populatePeriod(period: WidgetPeriod) {
        val id = when (period) {
            WidgetPeriod.THIS_MONTH -> R.id.rb_period_month
            WidgetPeriod.THIS_WEEK -> R.id.rb_period_week
            WidgetPeriod.LAST_7_DAYS -> R.id.rb_period_7days
        }
        rgPeriod.check(id)
    }

    private fun populateMetrics(metrics: Set<WidgetMetric>) {
        cbExpense.isChecked = WidgetMetric.EXPENSE in metrics
        cbBudget.isChecked = WidgetMetric.BUDGET in metrics
        cbRemaining.isChecked = WidgetMetric.REMAINING in metrics
    }

    private fun save() {
        val selectedBook = rgBook.findViewById<RadioButton>(rgBook.checkedRadioButtonId)?.tag as? String
            ?: BookAccountManager.getSelectedBook(this)

        val period = when (rgPeriod.checkedRadioButtonId) {
            R.id.rb_period_week -> WidgetPeriod.THIS_WEEK
            R.id.rb_period_7days -> WidgetPeriod.LAST_7_DAYS
            else -> WidgetPeriod.THIS_MONTH
        }

        val metrics = buildSet {
            if (cbExpense.isChecked) add(WidgetMetric.EXPENSE)
            if (cbBudget.isChecked) add(WidgetMetric.BUDGET)
            if (cbRemaining.isChecked) add(WidgetMetric.REMAINING)
        }
        if (metrics.isEmpty()) {
            Toast.makeText(this, "请至少选择一项显示内容", Toast.LENGTH_SHORT).show()
            return
        }

        WidgetConfigStore.save(this, appWidgetId, WidgetConfig(selectedBook, period, metrics))
        ExpenseWidgetUpdater.refreshOne(this, appWidgetId)

        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        setResult(RESULT_OK, resultValue)
        finish()
    }
}
