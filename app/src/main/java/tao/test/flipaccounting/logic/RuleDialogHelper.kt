package tao.test.flipaccounting.logic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.AIService
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.ui.dialog.OverlayDialogs

object RuleDialogHelper {

    const val DEFAULT_RULE_PROMPT = """你是一个记账规则提取助手。
用户原来的一段记账文本是：{{REMARK}}
用户将其归成了 类型:{{TYPE}}(0:支出,1:收入,2:转账,3:还款), 分类:{{CATEGORY}}
请提取出代表【交易物品或事由】的核心名词作为规则匹配关键字。
【严重警告】：绝对不能把支付方式、账户名称、资产名称（如微信、支付宝、哪怕是别人名字等）作为单独关键字，因为它们不能代表商品或分类。
【组合建议】：如果你觉得必须同时满足两个条件（比如某个特定商品且发生在特定账户下），请用空格分隔。例如：小笼包 微信
【多独立规则】：如果是多个并列且绝对独立的事物，可用英文逗号分隔。
不要做任何多余解释，只返回关键字文本本身。例如：豆浆,拉面 微信"""

    fun showDialog(
        ctx: Context,
        rule: AiRule?,
        referenceText: String?,
        defaultType: Int? = null,
        defaultCat: String? = null,
        defaultAcc1: String? = null,
        defaultAcc2: String? = null,
        isOverlay: Boolean = false,
        onSave: (AiRule) -> Unit,
        onDelete: ((AiRule) -> Unit)? = null,
        onCancel: (() -> Unit)? = null
    ) {
        val themeCtx = ContextThemeWrapper(ctx, R.style.Theme_FlipAccounting)
        val view = LayoutInflater.from(themeCtx).inflate(R.layout.dialog_edit_ai_rule, null)
        
        val tvReferenceText = view.findViewById<TextView>(R.id.tv_reference_text)
        val btnAiExtract = view.findViewById<View>(R.id.btn_ai_extract_rule)
        val btnDeleteRule = view.findViewById<View>(R.id.btn_delete_rule)
        val btnCancelRule = view.findViewById<View>(R.id.btn_cancel_rule)
        val btnSaveRule = view.findViewById<View>(R.id.btn_save_rule)
        val etKeyword = view.findViewById<TextInputEditText>(R.id.et_keyword)
        val spType = view.findViewById<Spinner>(R.id.sp_type)
        val tvCategory = view.findViewById<TextView>(R.id.tv_category)
        val spAccount1 = view.findViewById<Spinner>(R.id.sp_account1)
        val spAccount2 = view.findViewById<Spinner>(R.id.sp_account2)
        val layoutAccount2 = view.findViewById<View>(R.id.layout_account2)
        val switchEnabled = view.findViewById<Switch>(R.id.switch_enabled)

        if (!referenceText.isNullOrEmpty()) {
            view.findViewById<View>(R.id.layout_reference_card).visibility = View.VISIBLE
            tvReferenceText.text = referenceText
        } else {
            view.findViewById<View>(R.id.layout_reference_card).visibility = View.GONE
        }

        btnAiExtract.setOnClickListener {
            Toast.makeText(ctx, "AI正在为你提取规则...", Toast.LENGTH_SHORT).show()
            CoroutineScope(Dispatchers.IO).launch {
                val remark = referenceText ?: ""
                var customPrompt = Prefs.getRulePrompt(ctx)
                if (customPrompt.isEmpty() || customPrompt.contains("核心的【名词】或【宾语】")) {
                    customPrompt = DEFAULT_RULE_PROMPT
                    Prefs.setRulePrompt(ctx, customPrompt)
                }
                val prompt = customPrompt
                    .replace("{{REMARK}}", remark)
                    .replace("{{TYPE}}", spType.selectedItemPosition.toString())
                    .replace("{{CATEGORY}}", tvCategory.text.toString())
                try {
                    val result = AIService.simpleChat(ctx, prompt)
                    withContext(Dispatchers.Main) {
                        etKeyword.setText(result.trim())
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "AI提取失败：${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        // type
        val types = ctx.resources.getStringArray(R.array.bill_types)
        spType.adapter = ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, types)
        (spType.adapter as ArrayAdapter<*>).setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        
        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                if (position == 2 || position == 3) {
                    layoutAccount2.visibility = View.VISIBLE
                } else {
                    layoutAccount2.visibility = View.GONE
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        // Accounts
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(ctx)
            val accounts = db.assetDao().getAllAssetsList().map { it.name }
            withContext(Dispatchers.Main) {
                val accAdapter = ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, (listOf("无") + accounts).toTypedArray()).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                spAccount1.adapter = accAdapter
                spAccount2.adapter = accAdapter

                var initType = rule?.targetType ?: defaultType ?: 0
                if (initType !in 0..5) initType = 0
                spType.setSelection(initType)

                val initCat = rule?.targetCategory ?: defaultCat ?: ""
                if (initCat.isNotEmpty()) tvCategory.text = initCat

                val initAcc1 = rule?.targetAccount1 ?: defaultAcc1 ?: ""
                var pos1 = accounts.indexOf(initAcc1) + 1
                if (pos1 > 0) spAccount1.setSelection(pos1)

                val initAcc2 = rule?.targetAccount2 ?: defaultAcc2 ?: ""
                var pos2 = accounts.indexOf(initAcc2) + 1
                if (pos2 > 0) spAccount2.setSelection(pos2)
            }
        }

        tvCategory.setOnClickListener {
            val currentType = if (spType.selectedItemPosition == 1) 1 else 0
            OverlayDialogs.showGridCategoryPicker(ctx, tvCategory.text.toString(), currentType) { selected ->
                tvCategory.text = selected
            }
        }

        switchEnabled.isChecked = rule?.isEnabled ?: true
        if (rule != null) {
            etKeyword.setText(rule.keyword)
        }

        view.findViewById<TextView>(R.id.tv_dialog_title).text = if (rule == null) "添加记账习惯" else "编辑记账习惯"
        view.findViewById<TextView>(R.id.tv_dialog_subtitle).text =
            if (rule == null) "当文本命中关键词时，自动帮你补上分类、类型和账户。"
            else "你可以微调关键词、分类和账户，让这条习惯更贴近你的记账方式。"

        val dialog = AlertDialog.Builder(themeCtx)
            .setView(view)
            .create()

        btnCancelRule.setOnClickListener {
            dialog.dismiss()
            onCancel?.invoke()
        }

        btnDeleteRule.visibility = if (rule != null && onDelete != null) View.VISIBLE else View.GONE
        btnDeleteRule.setOnClickListener {
            if (rule != null && onDelete != null) {
                onDelete(rule)
                dialog.dismiss()
            }
        }

        btnSaveRule.setOnClickListener {
            val keyword = etKeyword.text.toString().trim()
            if (keyword.isEmpty()) {
                Toast.makeText(ctx, "关键词不能为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val finalType = spType.selectedItemPosition
            val finalCat = tvCategory.text.toString().takeIf { it != "点击选择分类" && it.isNotBlank() }
            val finalAcc1Str = spAccount1.selectedItem?.toString()
            val finalAcc1 = if (finalAcc1Str == "无" || finalAcc1Str == null) null else finalAcc1Str
            val finalAcc2Str = spAccount2.selectedItem?.toString()
            val finalAcc2 = if (finalAcc2Str == "无" || finalAcc2Str == null || (finalType != 2 && finalType != 3)) null else finalAcc2Str

            val keywords = keyword.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() }
            for (kw in keywords) {
                val newRule = AiRule(
                    id = if (keywords.size == 1) (rule?.id ?: 0) else 0,
                    keyword = kw,
                    targetType = finalType,
                    targetCategory = finalCat,
                    targetAccount1 = finalAcc1,
                    targetAccount2 = finalAcc2,
                    isEnabled = switchEnabled.isChecked
                )
                onSave(newRule)
            }
            dialog.dismiss()
        }

        dialog.setOnCancelListener { onCancel?.invoke() }
        OverlayDialogs.showStyledCenterDialog(
            dialog = dialog,
            ctx = ctx,
            widthRatio = 0.92f,
            cancelOnTouchOutside = true,
            applyOverlayType = isOverlay,
            useSolidPanelBackground = true
        )
    }
}
