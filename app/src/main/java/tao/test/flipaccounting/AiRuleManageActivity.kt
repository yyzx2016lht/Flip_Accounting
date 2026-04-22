package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

class AiRuleManageActivity : AppCompatActivity() {

    private lateinit var rvRules: RecyclerView
    private lateinit var btnAddRule: MaterialButton
    private lateinit var etRuleSearch: android.widget.EditText
    private lateinit var layoutRuleMultiActions: View
    private lateinit var tvRuleSelectedCount: TextView
    private lateinit var btnRuleSelectAll: TextView
    private lateinit var btnRuleCancelMulti: TextView
    private lateinit var btnRuleDeleteMulti: TextView
    private lateinit var adapter: RuleAdapter
    private lateinit var db: AppDatabase

    private var allRules: List<AiRule> = emptyList()
    private var keyword: String = ""
    private var isMultiSelectMode = false
    private val selectedRuleIds = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_rule_manage)

        db = AppDatabase.getDatabase(this)
        rvRules = findViewById(R.id.rv_rules)
        btnAddRule = findViewById(R.id.btn_add_rule)
        etRuleSearch = findViewById(R.id.et_rule_search)
        layoutRuleMultiActions = findViewById(R.id.layout_rule_multi_actions)
        tvRuleSelectedCount = findViewById(R.id.tv_rule_selected_count)
        btnRuleSelectAll = findViewById(R.id.btn_rule_select_all)
        btnRuleCancelMulti = findViewById(R.id.btn_rule_cancel_multi)
        btnRuleDeleteMulti = findViewById(R.id.btn_rule_delete_multi)

        adapter = RuleAdapter(
            onClick = { rule ->
                if (isMultiSelectMode) {
                    toggleRuleSelection(rule)
                } else {
                    showEditDeleteDialog(rule)
                }
            },
            onLongClick = { rule ->
                if (!isMultiSelectMode) {
                    enterMultiSelectMode(rule)
                }
            },
            onToggleEnabled = { rule, enabled ->
                lifecycleScope.launch {
                    db.aiRuleDao().updateRule(rule.copy(isEnabled = enabled))
                }
            },
            isMultiSelectMode = { isMultiSelectMode },
            isSelected = { rule -> selectedRuleIds.contains(rule.id) }
        )

        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = adapter

        lifecycleScope.launch {
            db.aiRuleDao().getAllRules().collectLatest { rules ->
                allRules = rules
                if (isMultiSelectMode) {
                    selectedRuleIds.retainAll(rules.map { it.id }.toSet())
                    if (selectedRuleIds.isEmpty()) {
                        exitMultiSelectMode()
                    } else {
                        updateMultiSelectUi()
                    }
                }
                submitFilteredRules()
            }
        }

        etRuleSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString().orEmpty().trim()
                submitFilteredRules()
            }
        })

        btnAddRule.setOnClickListener { showEditDeleteDialog(null) }
        btnRuleCancelMulti.setOnClickListener { exitMultiSelectMode() }
        btnRuleSelectAll.setOnClickListener { toggleSelectAllVisibleRules() }
        btnRuleDeleteMulti.setOnClickListener { deleteSelectedRules() }
    }

    private fun submitFilteredRules() {
        val filtered = if (keyword.isBlank()) {
            allRules
        } else {
            val key = keyword.lowercase()
            allRules.filter { rule -> buildSearchSource(rule).contains(key) }
        }
        adapter.submitList(filtered)
        updateMultiSelectUi()
    }

    private fun buildSearchSource(rule: AiRule): String {
        return listOf(
            rule.keyword,
            rule.targetCategory.orEmpty(),
            rule.targetAccount1.orEmpty(),
            rule.targetAccount2.orEmpty(),
            when (rule.targetType) {
                0 -> "支出"
                1 -> "收入"
                2 -> "转账"
                3 -> "还款"
                else -> ""
            }
        ).joinToString(" ").lowercase()
    }

    private fun enterMultiSelectMode(initialRule: AiRule) {
        val offset = captureRuleListOffset()
        isMultiSelectMode = true
        selectedRuleIds.clear()
        selectedRuleIds.add(initialRule.id)
        adapter.notifyDataSetChanged()
        updateMultiSelectUi()
        restoreRuleListOffset(offset, extraOffset = multiActionOffsetPx())
    }

    private fun exitMultiSelectMode() {
        val offset = captureRuleListOffset()
        isMultiSelectMode = false
        selectedRuleIds.clear()
        adapter.notifyDataSetChanged()
        restoreRuleListOffset(offset, extraOffset = -multiActionOffsetPx())
        updateMultiSelectUi()
    }

    private fun toggleRuleSelection(rule: AiRule) {
        if (!selectedRuleIds.add(rule.id)) {
            selectedRuleIds.remove(rule.id)
        }
        if (selectedRuleIds.isEmpty()) {
            exitMultiSelectMode()
        } else {
            adapter.notifyDataSetChanged()
            updateMultiSelectUi()
        }
    }

    private fun toggleSelectAllVisibleRules() {
        val visibleIds = adapter.currentItems.map { it.id }.toSet()
        if (visibleIds.isEmpty()) return
        if (selectedRuleIds.containsAll(visibleIds) && selectedRuleIds.size == visibleIds.size) {
            selectedRuleIds.clear()
        } else {
            selectedRuleIds.clear()
            selectedRuleIds.addAll(visibleIds)
        }
        if (selectedRuleIds.isEmpty()) {
            exitMultiSelectMode()
        } else {
            adapter.notifyDataSetChanged()
            updateMultiSelectUi()
        }
    }

    private fun updateMultiSelectUi() {
        layoutRuleMultiActions.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
        tvRuleSelectedCount.text = "已选择 ${selectedRuleIds.size} 条"

        val visibleIds = adapter.currentItems.map { it.id }.toSet()
        btnRuleSelectAll.text =
            if (visibleIds.isNotEmpty() && selectedRuleIds.containsAll(visibleIds) && selectedRuleIds.size == visibleIds.size) {
                "取消全选"
            } else {
                "全选"
            }
    }

    private fun captureRuleListOffset(): Pair<Int, Int>? {
        val layoutManager = rvRules.layoutManager as? LinearLayoutManager ?: return null
        val firstPosition = layoutManager.findFirstVisibleItemPosition()
        if (firstPosition == RecyclerView.NO_POSITION) return null
        val firstView = layoutManager.findViewByPosition(firstPosition) ?: return null
        return firstPosition to firstView.top
    }

    private fun restoreRuleListOffset(offset: Pair<Int, Int>?, extraOffset: Int) {
        val layoutManager = rvRules.layoutManager as? LinearLayoutManager ?: return
        val (position, top) = offset ?: return
        rvRules.post {
            layoutManager.scrollToPositionWithOffset(position, top + extraOffset)
        }
    }

    private fun multiActionOffsetPx(): Int {
        val actionHeight = if (layoutRuleMultiActions.height > 0) {
            layoutRuleMultiActions.height
        } else {
            layoutRuleMultiActions.measuredHeight
        }
        val extra = (12f * resources.displayMetrics.density).toInt()
        return actionHeight + extra
    }

    private fun deleteSelectedRules() {
        if (selectedRuleIds.isEmpty()) return
        val toDelete = allRules.filter { selectedRuleIds.contains(it.id) }
        if (toDelete.isEmpty()) {
            exitMultiSelectMode()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("删除记账习惯")
            .setMessage("确定删除选中的 ${toDelete.size} 条记账习惯吗？")
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    toDelete.forEach { db.aiRuleDao().deleteRule(it) }
                    exitMultiSelectMode()
                }
            }
            .setNegativeButton("取消", null)
            .create()
        OverlayDialogs.showStyledCenterDialog(
            dialog = dialog,
            ctx = this,
            cancelOnTouchOutside = true,
            applyOverlayType = false,
            useSolidPanelBackground = true
        )
    }

    private fun showEditDeleteDialog(rule: AiRule?) {
        tao.test.flipaccounting.logic.RuleDialogHelper.showDialog(
            ctx = this,
            rule = rule,
            referenceText = null,
            onSave = { newRule ->
                lifecycleScope.launch {
                    if (rule == null) {
                        db.aiRuleDao().insertRule(newRule)
                    } else {
                        db.aiRuleDao().updateRule(newRule)
                    }
                }
            },
            onDelete = {
                lifecycleScope.launch {
                    db.aiRuleDao().deleteRule(it)
                }
            }
        )
    }

    override fun onBackPressed() {
        if (isMultiSelectMode) {
            exitMultiSelectMode()
            return
        }
        super.onBackPressed()
    }
    inner class RuleAdapter(
        private val onClick: (AiRule) -> Unit,
        private val onLongClick: (AiRule) -> Unit,
        private val onToggleEnabled: (AiRule, Boolean) -> Unit,
        private val isMultiSelectMode: () -> Boolean,
        private val isSelected: (AiRule) -> Boolean
    ) : RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

        private var items = emptyList<AiRule>()
        val currentItems: List<AiRule> get() = items

        fun submitList(list: List<AiRule>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ai_rule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rule = items[position]

            holder.tvKeyword.text = rule.keyword

            if (rule.isEnabled) {
                holder.tvStatus.text = "已启用"
                holder.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_active)
            } else {
                holder.tvStatus.text = "已停用"
                holder.tvStatus.setTextColor(Color.parseColor("#9E9E9E"))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_inactive)
            }

            val sb = SpannableStringBuilder()

            fun appendAction(label: String, value: String?) {
                if (value.isNullOrEmpty()) return
                val labelStart = sb.length
                sb.append(label).append(": ")
                val valueStart = sb.length
                sb.append(value).append("   ")
                sb.setSpan(StyleSpan(Typeface.BOLD), valueStart, sb.length - 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#333333")), valueStart, sb.length - 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#7A8598")), labelStart, valueStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            rule.targetType?.let {
                val typeStr = when (it) {
                    0 -> "支出"
                    1 -> "收入"
                    2 -> "转账"
                    3 -> "还款"
                    else -> "未知"
                }
                appendAction("类型", typeStr)
            }
            appendAction("分类", rule.targetCategory)
            appendAction("账户", rule.targetAccount1)
            appendAction("目标账户", rule.targetAccount2)

            holder.tvActions.text = if (sb.isEmpty()) "未设置自动填充动作" else sb
            holder.cbSelect.visibility = if (isMultiSelectMode()) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = isSelected(rule)
            holder.itemView.alpha = if (isMultiSelectMode() && isSelected(rule)) 0.82f else 1f

            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.isChecked = rule.isEnabled
            holder.switchEnabled.isEnabled = !isMultiSelectMode()
            holder.switchEnabled.alpha = if (isMultiSelectMode()) 0.45f else 1f
            holder.switchEnabled.setOnCheckedChangeListener { _, checked ->
                if (!isMultiSelectMode()) onToggleEnabled(rule, checked)
            }

            holder.itemView.setOnClickListener { onClick(rule) }
            holder.itemView.setOnLongClickListener {
                onLongClick(rule)
                true
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: TextView = view.findViewById(R.id.tv_keyword)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvActions: TextView = view.findViewById(R.id.tv_actions)
            val cbSelect: CheckBox = view.findViewById(R.id.cb_rule_select)
            val switchEnabled: Switch = view.findViewById(R.id.switch_rule_enabled)
        }
    }
}
