package tao.test.flipaccounting

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import tao.test.flipaccounting.AiRule
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.Switch
import java.lang.StringBuilder
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface

class AiRuleManageActivity : AppCompatActivity() {

    private lateinit var rvRules: RecyclerView
    private lateinit var btnAddRule: MaterialButton
    private lateinit var adapter: RuleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_rule_manage)

        rvRules = findViewById(R.id.rv_rules)
        btnAddRule = findViewById(R.id.btn_add_rule)

        adapter = RuleAdapter { rule ->
            showEditDeleteDialog(rule)
        }
        rvRules.layoutManager = LinearLayoutManager(this)
        rvRules.adapter = adapter

        loadRules()

        btnAddRule.setOnClickListener {
            showEditDeleteDialog(null)
        }
    }

    private fun loadRules() {
        val rules = Prefs.getAiRules(this)
        adapter.submitList(rules)
    }

    private fun showEditDeleteDialog(rule: AiRule?) {
        tao.test.flipaccounting.logic.RuleDialogHelper.showDialog(
            ctx = this,
            rule = rule,
            referenceText = null,
            onSave = { newRule ->
                if (rule == null) {
                    Prefs.addAiRule(this, newRule)
                } else {
                    Prefs.updateAiRule(this, newRule)
                }
                loadRules()
            },
            onDelete = {
                Prefs.deleteAiRule(this, it.id)
                loadRules()
            }
        )
    }

    inner class RuleAdapter(private val onClick: (AiRule) -> Unit) :
        RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

        private var items = emptyList<AiRule>()

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
                holder.tvStatus.text = "生效中"
                holder.tvStatus.setTextColor(Color.parseColor("#4CAF50"))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_active)
            } else {
                holder.tvStatus.text = "已禁用"
                holder.tvStatus.setTextColor(Color.parseColor("#9E9E9E"))
                holder.tvStatus.setBackgroundResource(R.drawable.bg_status_inactive)
            }
            
            val sb = SpannableStringBuilder()
            
            fun appendAction(label: String, value: String?) {
                if (value.isNullOrEmpty()) return
                val start = sb.length
                sb.append(label).append(": ")
                val valStart = sb.length
                sb.append(value).append("   ")
                sb.setSpan(StyleSpan(Typeface.BOLD), valStart, sb.length - 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#333333")), valStart, sb.length - 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            
            rule.targetType?.let { 
                val typeStr = when(it) {
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
            
            if (sb.isEmpty()) {
                holder.tvActions.text = "无操作"
            } else {
                holder.tvActions.text = sb
            }
            
            holder.itemView.setOnClickListener { onClick(rule) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: TextView = view.findViewById(R.id.tv_keyword)
            val tvStatus: TextView = view.findViewById(R.id.tv_status)
            val tvActions: TextView = view.findViewById(R.id.tv_actions)
        }
    }
}
