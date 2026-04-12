package tao.test.flipaccounting.logic

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.*
import org.json.JSONObject
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.Bill
import tao.test.flipaccounting.R
import tao.test.flipaccounting.Utils
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.*

import tao.test.flipaccounting.CurrencyData
import tao.test.flipaccounting.Logger
import kotlinx.coroutines.*
import tao.test.flipaccounting.AIService

class AccountingFormController(
    private val ctx: Context,
    private val rootView: View,
    private val onCloseRequest: (isSaved: Boolean) -> Unit // 修改回调，带上状态
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val etMoney: EditText = rootView.findViewById(R.id.et_amount)
    private val spType: Spinner = rootView.findViewById(R.id.spinner_type)
    private val layoutAccount: View = rootView.findViewById(R.id.layout_account)
    private val tvAccount: TextView = rootView.findViewById(R.id.tv_account)
    private val layoutAccount2: View = rootView.findViewById(R.id.layout_account_2)
    private val tvAccount2: TextView = rootView.findViewById(R.id.tv_account_2)
    private val layoutCategory: View = rootView.findViewById(R.id.layout_category)
    private val tvCategory: TextView = rootView.findViewById(R.id.tv_category)
    private val layoutFee: View = rootView.findViewById(R.id.layout_fee)
    private val etFee: EditText = rootView.findViewById(R.id.et_fee)
    private val tvTime: TextView = rootView.findViewById(R.id.tv_time)
    private val etRemark: EditText = rootView.findViewById(R.id.et_remark)
    private val btnSave: Button = rootView.findViewById(R.id.btn_save)
    private val btnSaveOnly: Button = rootView.findViewById(R.id.btn_save_only)
    private val btnCancel: Button = rootView.findViewById(R.id.btn_cancel)
    val btnVoice: ImageView = rootView.findViewById(R.id.btn_ai_voice)
    val layoutAiEntry: LinearLayout = rootView.findViewById(R.id.layout_ai_entry)
    val btnAiIcon: ImageView = rootView.findViewById(R.id.btn_ai_magic)
    private val spCurrency: Spinner = rootView.findViewById(R.id.spinner_currency)

    private var editingRecordTime: String = "" // [新增] 保存正在编辑的历史账单 ID

    init {
        CurrencyManager.init(ctx)
        setupVisibility()
        setupSpinner()
        setupCurrencySpinner()
        setupListeners()
        setupDefaults()
        setupAnimations()
        
        // 关键修复：解决服务覆盖层中 EditText 点击导致的 ActionMode 崩溃 (Token 验证失败)
        disableActionModeForEditTexts()
    }

    private fun disableActionModeForEditTexts() {
        val callback = object : android.view.ActionMode.Callback {
            override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
            override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean = false
            override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean = false
            override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
        }
        etMoney.customSelectionActionModeCallback = callback
        etFee.customSelectionActionModeCallback = callback
        etRemark.customSelectionActionModeCallback = callback
        
        // 同时也禁用拼写检查，因为它也会触发类似的浮动框
        etMoney.inputType = etMoney.inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        etFee.inputType = etFee.inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        etRemark.inputType = etRemark.inputType or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun setupVisibility() {
        // [新增] 根据偏好设置显示/隐藏组件
        val showAiText = Prefs.isShowAiText(ctx)
        val showAiVoice = Prefs.isShowAiVoice(ctx)
        val showMultiCur = Prefs.isShowMultiCurrency(ctx)

        layoutAiEntry.visibility = if (showAiText) View.VISIBLE else View.GONE
        btnVoice.visibility = if (showAiVoice) View.VISIBLE else View.GONE
        spCurrency.visibility = if (showMultiCur) View.VISIBLE else View.GONE
        
        // 如果 AI 文本和语音都隐藏了，通常意味着用户不想用 AI，
        // 我们可以在布局上做进一步调整（如隐藏分隔线等，这里简单处理）
    }

    private fun setupSpinner() {
        val types = ctx.resources.getStringArray(R.array.bill_types)
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, types) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.gravity = android.view.Gravity.CENTER
                v.setPadding(0, 0, 0, 0)
                v.textSize = 13f
                v.setTextColor(Color.parseColor("#666666"))
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.gravity = android.view.Gravity.CENTER
                v.setPadding(0, 30, 0, 30) // 增加下拉列表的垂直间距
                return v
            }
        }
        spType.adapter = adapter
    }

    private fun setupCurrencySpinner() {
        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx)
        
        // Map codes to display names with emoji
        val displayList = enabledCodes.map { code ->
            val info = CurrencyData.getInfo(code)
            info?.getDisplayName() ?: code
        }

        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, displayList) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                
                // MAIN CHANGE: For the collapsed view, show only flag and code
                val code = enabledCodes[position]
                val info = CurrencyData.getInfo(code)
                v.text = info?.getShortName() ?: code
                
                v.gravity = android.view.Gravity.CENTER
                v.setTextColor(Color.parseColor("#333333"))
                v.textSize = 14f 
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                // Keep full display name in dropdown for clarity
                v.setPadding(30, 20, 30, 20)
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spCurrency.adapter = adapter
        
        // Default to CNY
        val defaultIndex = enabledCodes.indexOf("CNY")
        if (defaultIndex >= 0) {
            spCurrency.setSelection(defaultIndex)
        }
    }

    private fun setupAnimations() {
        ObjectAnimator.ofFloat(btnAiIcon, "alpha", 1.0f, 0.4f, 1.0f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun setupDefaults() {
        tvTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        updateAmountTextColor(spType.selectedItemPosition)
    }

    private fun updateAmountTextColor(typeIndex: Int) {
        val color = when (typeIndex) {
            1 -> "#388E3C"
            2, 3 -> "#808080"
            else -> "#D32F2F"
        }
        etMoney.setTextColor(Color.parseColor(color))
    }

    private fun setupListeners() {
        val assets = Prefs.getAssets(ctx)
        val assetNames = assets.map { it.name }

        layoutAccount.setOnClickListener { anchor ->
            if (assetNames.isNotEmpty()) {
                val title = if (spType.selectedItemPosition == 2) "选择转出账户" else if (spType.selectedItemPosition == 3) "选择付款账户" else "选择资产"
                OverlayDialogs.showGridAssetPicker(ctx, tvAccount.text.toString(), title) { tvAccount.text = it }
            } else Utils.toast(ctx, "请先在App内添加资产")
        }
        layoutAccount2.setOnClickListener { anchor ->
            if (assetNames.isNotEmpty()) {
                val title = if (spType.selectedItemPosition == 2) "转入账户" else "还款账户(信用卡)"
                OverlayDialogs.showGridAssetPicker(ctx, tvAccount2.text.toString(), title) { tvAccount2.text = it }
            } else Utils.toast(ctx, "请先在App内添加资产")
        }
        layoutCategory.setOnClickListener {
            // 根据钱迹规范：1 是收入，0 是支出
            val currentType = if (spType.selectedItemPosition == 1) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
            OverlayDialogs.showGridCategoryPicker(ctx, tvCategory.text.toString(), currentType) {
                tvCategory.text = it
            }
        }

        rootView.findViewById<View>(R.id.layout_time).setOnClickListener {
            OverlayDialogs.showCustomTimePicker(ctx) { tvTime.text = it }
        }

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                updateAmountTextColor(pos)
                when (pos) {
                    2, 3 -> { // 转账(2)或还款(3)
                        layoutCategory.visibility = View.GONE
                        layoutAccount2.visibility = View.VISIBLE
                        layoutFee.visibility = if (pos == 2) View.VISIBLE else View.GONE
                        
                        if (tvAccount.text.toString().contains("选择") || tvAccount.text.toString().contains("资产")) {
                            tvAccount.text = if (pos == 2) "选择转出账户" else "选择付款账户"
                        }
                        if (tvAccount2.text.toString().contains("选择") || tvAccount2.text.toString().contains("账户")) {
                            tvAccount2.text = if (pos == 2) "转入账户" else "还款账户(信用卡)"
                        }
                        etMoney.hint = if (pos == 2) "转账金额" else "还款金额"
                    }
                    else -> { // 支出(0)或收入(1)
                        layoutCategory.visibility = View.VISIBLE
                        layoutAccount2.visibility = View.GONE
                        layoutFee.visibility = View.GONE
                        if (tvAccount.text.toString().contains("选择") || tvAccount.text.toString().contains("转出")) {
                            tvAccount.text = "选择资产"
                        }
                        etMoney.hint = if (pos == 1) "收入金额" else "支出金额"
                    }
                }
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnSave.setOnClickListener { handleSave() }
        btnSaveOnly.setOnClickListener { handleSave(isSaveOnly = true) }
        btnCancel.setOnClickListener { onCloseRequest(false) } // 取消
    }

    private fun handleSave(isSaveOnly: Boolean = false) {
        val rawMoneyStr = etMoney.text.toString().replace("-", "")
        if (rawMoneyStr.isEmpty() || (rawMoneyStr.toDoubleOrNull() ?: 0.0) <= 0.0) {
            Utils.toast(ctx, "金额无效")
            return
        }

        val typeIndex = spType.selectedItemPosition
        val account1 = if (tvAccount.text.contains("选择")) "" else tvAccount.text.toString()
        var account2 = ""
        var feeStr = "0"
        var finalMoney = rawMoneyStr.toDouble()

        // Handle Currency Conversion
        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx)
        val selectedIndex = spCurrency.selectedItemPosition
        val selectedCurrency = if (selectedIndex >= 0 && selectedIndex < enabledCodes.size) {
            enabledCodes[selectedIndex]
        } else {
            "CNY"
        }

        var remarkStr = etRemark.text.toString()
        
        if (selectedCurrency != "CNY") {
            val cnyAmountRaw = CurrencyManager.convertToCny(finalMoney, selectedCurrency)
            val formattedCnyStr = String.format(Locale.US, "%.2f", cnyAmountRaw)
            
            // Append info to remarks: e.g. (10.00 EUR ≈ 78.50 CNY)
            val curInfo = "(${String.format(Locale.US, "%.2f", finalMoney)} $selectedCurrency ≈ $formattedCnyStr CNY)"
            remarkStr = if (remarkStr.isEmpty()) curInfo else "$remarkStr $curInfo"
            
            finalMoney = formattedCnyStr.toDouble()
        }

        var finalCategory = if (tvCategory.text.contains("选择")) "" else tvCategory.text.toString().replace(" > ", "/::/").replace(">", "/::/")

        if (typeIndex == 2 || typeIndex == 3) { // 转账或还款模式
            account2 = if (tvAccount2.text.contains("选择")) "" else tvAccount2.text.toString()
            if (account1.isEmpty() || account2.isEmpty()) {
                val msg = if (typeIndex == 2) "转账需选择两个账户" else "还款需选择付款和还款账户"
                Utils.toast(ctx, msg)
                return
            }
            val feeVal = etFee.text.toString().toDoubleOrNull() ?: 0.0
            feeStr = feeVal.toString()
            finalCategory = ""
        }

        val url = Utils.buildQianjiUrl(
            type = typeIndex.toString(),
            money = finalMoney.toString(),
            time = tvTime.text.toString(),
            remark = remarkStr,
            catename = finalCategory,
            accountname = account1,
            accountname2 = account2,
            fee = feeStr,
            currency = "CNY",
            showresult = "0"
        )

        // 如果分类中包含 /::/，说明是 AI 识别的层级结构，需要保留 /::/ 用于传给钱迹，但在显示时替换为 >
        val finalCategoryString = when (typeIndex) {
            2 -> "转账到 $account2"
            3 -> "还款到 $account2"
            else -> finalCategory.replace("/::/", " > ")
        }

        // 核心修改：在此处计算并保存图标，确保 AI 记账也能有图标
        val resolvedIcon = tao.test.flipaccounting.CategoryIconHelper.findCategoryIcon(ctx, if (typeIndex == 2 || typeIndex == 3) "转账" else finalCategoryString, typeIndex)

        // 保存到本地数据库 (SharedPreferences)
        val bill = Bill(
            finalMoney,
            typeIndex, // 0-支出, 1-收入, 2-转账, 3-还款
            account1,
            finalCategoryString,
            tvTime.text.toString(),
            remarkStr,
            resolvedIcon, // 保存图标链接
            if (editingRecordTime.isNotEmpty()) editingRecordTime else 
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()) // 记录当前记账时间
        )
        Prefs.addBill(ctx, bill)

        val originalText = lastAiSuggestOriginalText
        if (originalText != null && Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val changedType = if (lastAiSuggestType != null && lastAiSuggestType != typeIndex) typeIndex else null
            
            val normalizedSuggestCat = lastAiSuggestCategory?.replace(" ", "")?.replace("/:::/", ">")?.replace("/::/", ">") ?: ""
            // 同样格式化 finalCategory 再比较，防止 /::/ 和 > 格式不同误判为修改
            val normalizedFinalCat = finalCategory.replace(" ", "").replace("/:::/", ">").replace("/::/", ">")
            val changedCat = if (normalizedSuggestCat.isNotEmpty() && normalizedSuggestCat != normalizedFinalCat) finalCategory else null
            
            val normalizedSuggestAcc1 = lastAiSuggestAccount1?.replace(" ", "") ?: ""
            val normalizedFinalAcc1 = account1.replace(" ", "")
            val changedAcc1 = if (normalizedSuggestAcc1.isNotEmpty() && normalizedSuggestAcc1 != normalizedFinalAcc1) account1 else null
            
            val normalizedSuggestAcc2 = lastAiSuggestAccount2?.replace(" ", "") ?: ""
            val normalizedFinalAcc2 = account2.replace(" ", "")
            val changedAcc2 = if (normalizedSuggestAcc2.isNotEmpty() && normalizedSuggestAcc2 != normalizedFinalAcc2) account2 else null

            var actualChangedCat = changedCat
            if (typeIndex == 2 || typeIndex == 3) {
                actualChangedCat = null
            }
            
            var actualChangedAcc2 = changedAcc2
            if (typeIndex == 0 || typeIndex == 1) {
                actualChangedAcc2 = null
            }

            if (changedType != null || actualChangedCat != null || changedAcc1 != null || actualChangedAcc2 != null) {
                // 用 remarks（originalText）检查是否已有规则命中，避免重复弹窗
                val existingRules = Prefs.getAiRules(ctx)
                val alreadyCovered = existingRules.any { rule ->
                    rule.isEnabled && rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                        .all { originalText.contains(it, ignoreCase = true) }
                }
                if (alreadyCovered) {
                    lastAiSuggestOriginalText = null
                    // 已有规则命中，直接发送，不弹窗
                } else {
                    lastAiSuggestOriginalText = null
                    val acc1ToSuggest = if (changedType != null) (changedAcc1 ?: account1) else null

                    // 传 remarks 作为 referenceText，让 AI 只从商品名提取关键字
                    showCreateRuleDialog(
                        originalText = originalText,
                        finalType = changedType ?: typeIndex,
                        finalCat = actualChangedCat ?: finalCategory,
                        finalAcc1 = acc1ToSuggest,
                        finalAcc2 = actualChangedAcc2,
                        url = url,
                        isSaveOnly = isSaveOnly
                    )
                    return
                }
            }
        }
        lastAiSuggestOriginalText = null

        if (isSaveOnly) {
            Utils.toast(ctx, "已保存至账单列表")
            onCloseRequest(true)
            return
        }

        dispatchUrl(url)
    }

    /** 执行跳转钱迹 */
    private fun dispatchUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            ctx.startActivity(intent)
            onCloseRequest(true)
        } catch (e: Exception) {
            Utils.toast(ctx, "未安装钱迹")
        }
    }

    fun showSaveOnlyButton(show: Boolean) {
        btnSaveOnly.visibility = if (show) View.VISIBLE else View.GONE
    }

    private var lastAiSuggestType: Int? = null
    private var lastAiSuggestCategory: String? = null
    private var lastAiSuggestAccount1: String? = null
    private var lastAiSuggestAccount2: String? = null
    private var lastAiSuggestOriginalText: String? = null

    private fun showCreateRuleDialog(
        originalText: String,
        finalType: Int,
        finalCat: String?,
        finalAcc1: String?,
        finalAcc2: String?,
        url: String,
        isSaveOnly: Boolean
    ) {
        val categoryToSave = if (finalType == 2 || finalType == 3) null else finalCat
        RuleDialogHelper.showDialog(
            ctx = ctx,
            rule = null,
            referenceText = originalText,
            defaultType = finalType,
            defaultCat = categoryToSave,
            defaultAcc1 = finalAcc1,
            defaultAcc2 = finalAcc2,
            isOverlay = ctx !is android.app.Activity,
            onSave = { newRule ->
                Prefs.addAiRule(ctx, newRule)
                Utils.toast(ctx, "规则已保存")
                // 保存规则后继续发送账单
                if (isSaveOnly) {
                    Utils.toast(ctx, "已保存至账单列表")
                    onCloseRequest(true)
                } else {
                    dispatchUrl(url)
                }
            },
            onCancel = {
                // 取消规则：直接发送账单，不保存规则
                if (isSaveOnly) {
                    Utils.toast(ctx, "已保存至账单列表")
                    onCloseRequest(true)
                } else {
                    dispatchUrl(url)
                }
            }
        )
    }

    fun fillDataToUi(result: JSONObject, showToast: Boolean = true) {
        val original = if (result.has("original_text_from_user")) result.getString("original_text_from_user") else null
        val remarks = if (result.has("remarks")) result.getString("remarks") else null
        val remark = if (result.has("remark")) result.getString("remark") else null

        // 多账单模式下，用 remarks（商品名）作为规则提取依据；单账单用原始文本
        val isFromAi = result.has("original_text_from_user") || result.has("from_ai")
        lastAiSuggestOriginalText = if (isFromAi) {
            // 优先用 remarks 作为关键字来源（如"购买卷心菜"），而不是整段原文
            remarks ?: remark ?: original
        } else {
            original ?: remarks ?: remark
        }

        if (isFromAi) {
            lastAiSuggestType = result.optInt("type", -1).takeIf { it != -1 }
            lastAiSuggestCategory = result.optString("category_name", result.optString("category", null))
            lastAiSuggestAccount1 = result.optString("asset_name", result.optString("account", null))
            lastAiSuggestAccount2 = result.optString("to_asset_name", null)
        }

        // [新增] 如果是编辑模式，记录下 recordTime，否则重置
        editingRecordTime = if (result.has("recordTime")) {
            result.getString("recordTime")
        } else {
            ""
        }

        val targetType = result.optInt("type", 0)
        val currentType = spType.selectedItemPosition

        val executeFillData = {
            updateAmountTextColor(targetType)
            // A. 填充金额
            val amount = result.optDouble("amount", 0.0)
            if (amount > 0) {
                etMoney.setText(amount.toString())
                if (showToast) playHighlightAnimation(etMoney)
            }

            // B. 填充主账户
            val asset = result.optString("asset_name", "")
            if (asset.isNotEmpty()) {
                tvAccount.text = asset
                if (showToast) playHighlightAnimation(tvAccount.parent as View)
            }

            // C. 根据类型填充其他数据
            if (targetType == 2 || targetType == 3) { // 转账 或 还款
                val toAsset = result.optString("to_asset_name", "")
                if (toAsset.isNotEmpty()) {
                    tvAccount2.text = toAsset
                    if (showToast) playHighlightAnimation(tvAccount2.parent as View)
                }
                val fee = result.optDouble("fee", 0.0)
                if (fee > 0) {
                    etFee.setText(fee.toString())
                    if (showToast) playHighlightAnimation(etFee)
                }
            } else { // 支出/收入
                val category = result.optString("category_name", "")
                if (category.isNotEmpty()) {
                    tvCategory.text = category.replace("/::/", " > ")
                    if (showToast) playHighlightAnimation(tvCategory.parent as View)
                }
            }

            // D. 填充备注
            val remark = result.optString("remarks", "")
            if (remark.isNotEmpty()) {
                etRemark.setText(remark)
                if (showToast) playHighlightAnimation(etRemark)
            }
            
            // E. 填充时间：必须符合 yyyy-MM-dd HH:mm:ss 格式
            val timeStr = result.optString("time", "")
            if (timeStr.isNotEmpty()) {
                // 如果 AI 返回的是标准的 yyyy-MM-dd... 格式，直接填充
                if (timeStr.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*"))) {
                    tvTime.text = timeStr
                } else {
                    // 如果仍然是“昨天”等文字（提示词失效），我们不建议填充日期字段
                    Logger.d(ctx, "AccountingForm", "AI返回了非标准时间格式: $timeStr")
                }
                if (showToast) playHighlightAnimation(tvTime)
            }
        }

        if (currentType != targetType && targetType < spType.adapter.count) {
            spType.setSelection(targetType)
            spType.post { executeFillData() }
        } else {
            executeFillData()
        }
    }

    fun setCurrency(currencyCode: String) {
        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx)
        val code = currencyCode.uppercase().ifBlank { "CNY" }
        val index = enabledCodes.indexOf(code)
        if (index >= 0) {
            spCurrency.setSelection(index)
        } else {
            // 目标货币不在启用列表中，回退到 CNY（index 0）
            val cnyIndex = enabledCodes.indexOf("CNY")
            if (cnyIndex >= 0) spCurrency.setSelection(cnyIndex)
        }
    }

    /**
     * 为视图播放一个简单的高亮动画，向用户展示该项是由 AI 填充的。
     */
    private fun playHighlightAnimation(view: View) {
        ObjectAnimator.ofFloat(view, "alpha", 0.3f, 1.0f).apply {
            duration = 500
            start()
        }
    }
}
