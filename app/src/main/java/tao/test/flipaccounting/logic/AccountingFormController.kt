package tao.test.flipaccounting.logic

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.room.withTransaction
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.*
import org.json.JSONObject
import tao.test.flipaccounting.*
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.AiRule
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.ui.ExchangeRateActivity
import tao.test.flipaccounting.ui.common.UiMotion.applyFormRowPressFeedback
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.resume

class AccountingFormController(
    private val ctx: Context,
    private val rootView: View,
    private val onCloseRequest: (isSaved: Boolean) -> Unit,
    /** 当容器高度锁定后回调（悬浮窗需要同步更新 WindowManager params 高度，防止跳动） */
    val onHeightLocked: ((lockedHeight: Int) -> Unit)? = null
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var editingBillId: Long? = null
    private val etMoney: EditText = rootView.findViewById(R.id.et_amount)
    private val spType: Spinner = rootView.findViewById(R.id.spinner_type)
    private val layoutAccount: View = rootView.findViewById(R.id.layout_account)
    private val tvAccount: TextView = rootView.findViewById(R.id.tv_account)
    private val ivAccountIcon: ImageView? = rootView.findViewById(R.id.iv_account_icon)
    private val tvAccountIconEmoji: TextView? = rootView.findViewById(R.id.tv_account_icon_emoji)
    private val layoutAccount2: View = rootView.findViewById(R.id.layout_account_2)
    private val tvAccount2: TextView = rootView.findViewById(R.id.tv_account_2)
    private val ivAccount2Icon: ImageView? = rootView.findViewById(R.id.iv_account_2_icon)
    private val tvAccount2IconEmoji: TextView? = rootView.findViewById(R.id.tv_account_2_icon_emoji)
    private val tvAccountAmount: TextView? = rootView.findViewById(R.id.tv_account_amount)
    private val tvAccount2Amount: TextView? = rootView.findViewById(R.id.tv_account_2_amount)
    private val btnSwapAccounts: View? = rootView.findViewById(R.id.btn_swap_accounts)
    private val layoutCategory: View = rootView.findViewById(R.id.layout_category)
    private val tvCategory: TextView = rootView.findViewById(R.id.tv_category)
    private val ivCategoryIcon: ImageView? = rootView.findViewById(R.id.iv_category_icon)
    private val tvCategoryIconEmoji: TextView? = rootView.findViewById(R.id.tv_category_icon_emoji)
    private val layoutFee: View = rootView.findViewById(R.id.layout_fee)
    private val etFee: EditText = rootView.findViewById(R.id.et_fee)
    private val tvTime: TextView = rootView.findViewById(R.id.tv_time)
    private val etRemark: EditText = rootView.findViewById(R.id.et_remark)
    private val btnSave: Button = rootView.findViewById(R.id.btn_save)
    private val btnCancel: Button = rootView.findViewById(R.id.btn_cancel)
    private val tvTitle: TextView? = rootView.findViewById(R.id.tv_title)
    val btnVoice: ImageView = rootView.findViewById(R.id.btn_ai_voice)
    val layoutAiEntry: LinearLayout = rootView.findViewById(R.id.layout_ai_entry)
    val layoutAiTextEntry: LinearLayout = rootView.findViewById(R.id.layout_ai_text_entry)
    val btnAiIcon: ImageView = rootView.findViewById(R.id.btn_ai_magic)
    private val spCurrency: Spinner = rootView.findViewById(R.id.spinner_currency)
    private val layoutBook: View = rootView.findViewById(R.id.layout_book)
    private val tvBook: TextView = rootView.findViewById(R.id.tv_book)
    private var amountKeypadView: View? = null
    private var confirmKeyView: View? = null
    private var isAmountKeypadVisible: Boolean = false
    /** layout_form_body 首次布局后记录的高度，用于键盘显示时保持面板总高度不变 */
    private var formBodyMeasuredHeight: Int = 0

    // 退款来源账单相关（收入模式下可选退款来源）
    private val layoutRefundSource: View? = rootView.findViewById(R.id.layout_refund_source)
    private val tvRefundSourceBill: TextView? = rootView.findViewById(R.id.tv_refund_source_bill)
    /** 分类行右侧的“退款”切换标签（收入模式时显示，点击进入退款模式） */
    private val tvRefundToggle: TextView? = rootView.findViewById(R.id.tv_refund_toggle)
    /** 退款行右侧的“取消退款”标签 */
    private val tvRefundBadge: View? = rootView.findViewById(R.id.tv_refund_badge)
    /** 用户选中的退款来源支出账单，null 表示未选择（普通收入） */
    private var selectedRefundSourceBill: tao.test.flipaccounting.data.local.entity.Bill? = null
    /** 当前是否处于退款模式（分类行被退款来源行替换） */
    private var isRefundMode: Boolean = false

    /** 当前表单选定的账本（独立于全局选中账本，初始值同步全局） */
    private var selectedFormBook: String =
        BookAccountManager.resolveWritableBook(ctx, BookAccountManager.getSelectedBook(ctx))

    private val aiAssistant by lazy { AiAssistant(ctx) }
    private val isAssetFeatureEnabled: Boolean
        get() = Prefs.isAssetFeatureEnabled(ctx)

    private var customTransferRate: Double? = null
    private var customTargetAmount: Double? = null
    private var isSpinnersInitialized = false
    /** 转账模式下用户是否已打开过汇率窗口并确认 */
    private var hasConfirmedExchangeRate: Boolean = false

    // 支出/收入跨币种汇率确认
    /** 当选择的币种与账户1币种不同时，用户是否已确认过汇率 */
    private var hasConfirmedCurrencyRate: Boolean = false
    /** 用户在汇率窗口中确认的实际扣减金额（账户币种） */
    private var customCurrencyTargetAmount: Double? = null
    /** 支出/收入场景中用户手动确认的汇率 */
    private var customCurrencyRate: Double? = null
    /** 程序自动调用 setCurrency() 时置 true，避免触发用户切换逻辑 */
    private var isProgrammaticCurrencyChange: Boolean = false

    init {
        CurrencyManager.init(ctx)
        setupVisibility()
        setupSpinner()
        setupCurrencySpinner()
        setupListeners()
        setupAmountKeypad()
        setupDefaults()
        btnCancel.text = "取消"
        btnSave.text = "保存并记账"
        setupModeToggle()
        applyAssetFeatureMode()
        refreshSelectionIcons()
    }

    private fun refreshSelectionIcons() {
        refreshAccountIconForName(tvAccount.text?.toString().orEmpty())
        refreshAccount2IconForName(tvAccount2.text?.toString().orEmpty())
        refreshCategoryIconForSelection(tvCategory.text?.toString().orEmpty())
    }

    private fun resetAccountIconToEmoji() {
        ivAccountIcon?.visibility = View.GONE
        tvAccountIconEmoji?.visibility = View.VISIBLE
        tvAccountAmount?.text = "--"
    }

    private fun resetAccount2IconToEmoji() {
        ivAccount2Icon?.visibility = View.GONE
        tvAccount2IconEmoji?.visibility = View.VISIBLE
        tvAccount2Amount?.text = "--"
    }

    private fun formatAssetBalance(asset: Asset): String {
        val symbol = CurrencyManager.getSymbol(asset.currency)
        val amount = String.format(Locale.getDefault(), "%.2f", asset.balance)
        return "$symbol$amount"
    }

    private fun resetCategoryIconToEmoji() {
        ivCategoryIcon?.visibility = View.GONE
        tvCategoryIconEmoji?.visibility = View.VISIBLE
        ivCategoryIcon?.clearColorFilter()
    }

    private fun refreshAccountIconForName(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.contains("选择") || normalized == "转出账户" || normalized == "付款账户") {
            resetAccountIconToEmoji()
            return
        }
        scope.launch {
            val asset = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).assetDao().getAssetByName(normalized)
            }
            if (asset == null) {
                resetAccountIconToEmoji()
                return@launch
            }
            tvAccountAmount?.text = formatAssetBalance(asset)
            if (asset.icon.isBlank()) {
                ivAccountIcon?.visibility = View.GONE
                tvAccountIconEmoji?.visibility = View.VISIBLE
                return@launch
            }
            tvAccountIconEmoji?.visibility = View.GONE
            ivAccountIcon?.visibility = View.VISIBLE
            ivAccountIcon?.let {
                Glide.with(ctx)
                    .load(asset.icon)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(it)
            }
        }
    }

    private fun refreshCategoryIconForSelection(selection: String) {
        val normalized = selection.trim()
        if (normalized.isBlank() || normalized.contains("选择")) {
            resetCategoryIconToEmoji()
            return
        }
        val pathParts = normalized
            .replace(" > ", "/::/")
            .split("/::/")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val leafName = pathParts.lastOrNull().orEmpty()
        if (leafName.isBlank()) {
            resetCategoryIconToEmoji()
            return
        }
        scope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val dbType = if (spType.selectedItemPosition == 1) 1 else 0
                val categories = AppDatabase.getDatabase(ctx).categoryDao().getCategoriesListByType(dbType)
                val parentName = pathParts.getOrNull(pathParts.size - 2)
                val parent = if (parentName.isNullOrBlank()) {
                    null
                } else {
                    categories.firstOrNull { it.parentId == null && it.name == parentName }
                }
                val category = when {
                    parent != null -> categories.firstOrNull { it.parentId == parent.id && it.name == leafName }
                    else -> categories.firstOrNull { it.parentId == null && it.name == leafName }
                        ?: categories.firstOrNull { it.name == leafName }
                }
                val icon = when {
                    category == null -> ""
                    category.iconId.isNotBlank() -> category.iconId
                    parent?.iconId?.isNotBlank() == true -> parent.iconId
                    else -> ""
                }
                category to icon
            }
            val category = resolved.first
            val icon = resolved.second
            if (category == null || icon.isBlank()) {
                resetCategoryIconToEmoji()
                return@launch
            }
            tvCategoryIconEmoji?.visibility = View.GONE
            ivCategoryIcon?.visibility = View.VISIBLE
            val tintColor = if (category.type == 1) Color.parseColor("#43A047") else Color.parseColor("#E53935")
            ivCategoryIcon?.let {
                Glide.with(ctx)
                    .load(icon)
                    .into(it)
                it.setColorFilter(tintColor)
            }
        }
    }

    private fun setupVisibility() {
        rootView.findViewById<LinearLayout>(R.id.layout_ai_entry)?.visibility = if (Prefs.isShowAiText(ctx)) View.VISIBLE else View.GONE
        btnVoice.visibility = if (Prefs.isShowAiVoice(ctx)) View.VISIBLE else View.GONE
        spCurrency.visibility = if (Prefs.isShowMultiCurrency(ctx)) View.VISIBLE else View.GONE
        layoutBook.visibility = if (Prefs.isShowBookEntry(ctx)) View.VISIBLE else View.GONE

        // 记账页面 AI 对话入口按钮
        val btnOpenAiChat = rootView.findViewById<android.view.View>(R.id.btn_open_ai_chat)
        btnOpenAiChat?.visibility = View.GONE
    }

    private fun setupModeToggle() {
        rootView.findViewById<View>(R.id.layout_bill_mode_switch)?.visibility = View.GONE
    }

    private fun isCurrentUiMultiMode(): Boolean = true

    private fun setupSpinner() {
        val types = if (isAssetFeatureEnabled) {
            ctx.resources.getStringArray(R.array.bill_types).toList()
        } else {
            ctx.resources.getStringArray(R.array.bill_types).take(2)
        }
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, types) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.textSize = 15f
                v.setTextColor(Color.parseColor("#333333"))
                v.gravity = Gravity.CENTER
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.textSize = 16f
                v.setPadding(32, 32, 32, 32)
                return v
            }
        }
        spType.adapter = adapter
    }

    private fun applyAssetFeatureMode() {
        if (!isAssetFeatureEnabled) {
            layoutAccount.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = View.GONE
            refreshAccount2IconForName("")
            layoutFee.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_fee)?.visibility = View.GONE
            tvAccount.text = ""
            tvAccount2.text = ""
            refreshSelectionIcons()
        }
        onTypeChanged(spType.selectedItemPosition.coerceAtMost(if (isAssetFeatureEnabled) 3 else 1))
    }

    /** 统一处理 spinner position 变化：0=支出,1=收入,2=转账,3=还款 */
    private fun onTypeChanged(position: Int) {
        if (!isAssetFeatureEnabled && position > 1) {
            spType.setSelection(0)
            return
        }
        val isTransfer   = position == 2
        val isRepayment  = position == 3

        when (position) {
            0 -> etMoney.setTextColor(Color.parseColor("#FF5252"))
            1 -> etMoney.setTextColor(Color.parseColor("#4CAF50"))
            2 -> etMoney.setTextColor(Color.parseColor("#8A8A8E"))
            3 -> etMoney.setTextColor(Color.parseColor("#FF9800"))
        }

        // 转账/还款：显示转入账户
        if (isAssetFeatureEnabled) {
            layoutAccount.visibility = View.VISIBLE
            layoutAccount2.visibility = if (isTransfer || isRepayment) View.VISIBLE else View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = layoutAccount2.visibility
            btnSwapAccounts?.visibility = if (isTransfer || isRepayment) View.VISIBLE else View.GONE
        } else {
            layoutAccount.visibility = View.GONE
            layoutAccount2.visibility = View.GONE
            rootView.findViewById<View?>(R.id.line_account_2)?.visibility = View.GONE
            btnSwapAccounts?.visibility = View.GONE
        }

        // 分类：仅支出/收入时显示
        layoutCategory.visibility = if (!isTransfer && !isRepayment) View.VISIBLE else View.GONE

        // 手续费：仅转账时显示
        layoutFee.visibility = if (isAssetFeatureEnabled && isTransfer) View.VISIBLE else View.GONE
        rootView.findViewById<View?>(R.id.line_fee)?.visibility = layoutFee.visibility

        // 退款模式控制：收入模式下分类行右侧显示“退款”切换标签，非收入模式强制退出退款模式
        if (position == 1) {
            // 收入模式：显示退款切换标签（保持当前退款模式状态不变）
            tvRefundToggle?.visibility = View.VISIBLE
        } else {
            // 非收入模式：退出退款模式，隐藏所有退款相关元素
            tvRefundToggle?.visibility = View.GONE
            if (isRefundMode) exitRefundMode()
        }

        // 账户标签文字
        val tvLabel = rootView.findViewById<TextView?>(R.id.tv_account_label)
        val tvLabel2 = rootView.findViewById<TextView?>(R.id.tv_account_2_label)
        if (!isAssetFeatureEnabled) {
            updateCurrencySpinnerMode(isTransferMode = false)
            return
        }
        when (position) {
            2 -> {
                tvLabel?.text  = "转出账户"
                tvLabel2?.text = "转入账户"
                if (tvAccount.text == "选择资产") tvAccount.text = "转出账户"
                if (tvAccount2.text.isEmpty() || tvAccount2.text == "选择资产" || tvAccount2.text == "选择信用卡" || tvAccount2.text == "选择转入账户") tvAccount2.text = "转入账户"
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                customTransferRate = null
                customTargetAmount = null
                hasConfirmedExchangeRate = false
                // 转账模式下，spCurrency 作为汇率入口，禁用下拉改为点击弹窗
                updateCurrencySpinnerMode(isTransferMode = true)
            }
            3 -> {
                tvLabel?.text  = "转出账户"
                tvLabel2?.text = "转入账户"
                if (tvAccount.text == "选择资产" || tvAccount.text == "付款账户") tvAccount.text = "转出账户"
                if (tvAccount2.text.isEmpty() || tvAccount2.text == "选择转入账户" || tvAccount2.text == "选择信用卡") tvAccount2.text = "转入账户"
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                updateCurrencySpinnerMode(isTransferMode = false)
            }
            else -> {
                tvLabel?.text  = "选择账户"
                if (tvAccount.text == "转出账户" || tvAccount.text == "付款账户") tvAccount.text = "选择资产"
                refreshAccountIconForName(tvAccount.text.toString())
                refreshAccount2IconForName(tvAccount2.text.toString())
                updateCurrencySpinnerMode(isTransferMode = false)
            }
        }
    }

    private fun setupCurrencySpinner() {
        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx).toMutableList().apply {
            if (!contains("CNY")) add(0, "CNY")
        }
        
        val adapter = object : ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_item, enabledCodes) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                val code = enabledCodes[position]
                val info = CurrencyData.getInfo(code)
                v.text = info?.let { "${it.flagEmoji} ${it.code}" } ?: code
                v.textSize = 14f
                v.setTextColor(Color.parseColor("#333333"))
                v.gravity = Gravity.CENTER
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                val code = enabledCodes[position]
                val info = CurrencyData.getInfo(code)
                v.text = info?.let { "${it.flagEmoji} ${it.code} - ${it.nameZh}" } ?: code
                v.textSize = 16f
                v.setPadding(32, 32, 32, 32)
                return v
            }
        }
        spCurrency.adapter = adapter
        
        val defaultIdx = enabledCodes.indexOf("CNY")
        if (defaultIdx >= 0) spCurrency.setSelection(defaultIdx)
        // 初始状态不设置 onItemSelectedListener，由 updateCurrencySpinnerMode 控制行为
    }

    /**
     * 切换 spCurrency 的交互模式：
     * - isTransferMode=true：禁用下拉，点击直接弹汇率窗口（作为汇率设置入口）
     * - isTransferMode=false：恢复正常 Spinner 下拉选择
     */
    private fun updateCurrencySpinnerMode(isTransferMode: Boolean) {
        if (isTransferMode) {
            // 移除下拉监听，改为整体点击弹汇率（仅 ACTION_UP 触发一次，避免多次弹窗）
            spCurrency.onItemSelectedListener = null
            spCurrency.setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    showExchangeDialog()
                }
                true  // 消费事件，阻止 Spinner 下拉
            }
        } else {
            spCurrency.setOnTouchListener(null)
            // 非转账模式：监听用户手动切换币种，重置跨币种汇率确认状态
            spCurrency.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isProgrammaticCurrencyChange) {
                        // 用户主动切换了币种，重置汇率确认状态，下次保存时重新弹汇率窗口
                        hasConfirmedCurrencyRate = false
                        customCurrencyTargetAmount = null
                        customCurrencyRate = null
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
    }

    private fun setupDefaults() {
        tvTime.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        tvBook.text = selectedFormBook
    }

    private fun parseUiTimeToMillis(text: String): Long? {
        val value = text.trim()
        if (value.isEmpty()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(value)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun refreshAccount2IconForName(name: String) {
        val normalized = name.trim()
        if (normalized.isBlank() || normalized.contains("选择") || normalized == "转入账户") {
            resetAccount2IconToEmoji()
            return
        }
        scope.launch {
            val asset = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(ctx).assetDao().getAssetByName(normalized)
            }
            if (asset == null) {
                resetAccount2IconToEmoji()
                return@launch
            }
            tvAccount2Amount?.text = formatAssetBalance(asset)
            if (asset.icon.isBlank()) {
                ivAccount2Icon?.visibility = View.GONE
                tvAccount2IconEmoji?.visibility = View.VISIBLE
                return@launch
            }
            tvAccount2IconEmoji?.visibility = View.GONE
            ivAccount2Icon?.visibility = View.VISIBLE
            ivAccount2Icon?.let {
                Glide.with(ctx)
                    .load(asset.icon)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .into(it)
            }
        }
    }

    private fun isAccountPlaceholder(name: String): Boolean {
        val text = name.trim()
        if (text.isEmpty()) return true
        return text.contains("选择") || text == "转出账户" || text == "转入账户" || text == "付款账户"
    }

    private fun adjustTypeByToAccount(toAccountName: String) {
        if (!isAssetFeatureEnabled || toAccountName.isBlank()) return
        scope.launch(Dispatchers.IO) {
            val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(toAccountName)
            withContext(Dispatchers.Main) {
                val currentPos = spType.selectedItemPosition
                if (selected?.assetCategory == Asset.CATEGORY_CREDIT_CARD && currentPos == 2) {
                    spType.setSelection(3)
                } else if (selected?.assetCategory != Asset.CATEGORY_CREDIT_CARD && currentPos == 3) {
                    spType.setSelection(2)
                }
            }
        }
    }

    private fun swapAccounts() {
        val currentType = spType.selectedItemPosition
        if (currentType != 2 && currentType != 3) return

        val fromRaw = tvAccount.text?.toString().orEmpty()
        val toRaw = tvAccount2.text?.toString().orEmpty()
        val fromName = fromRaw.takeUnless { isAccountPlaceholder(it) }.orEmpty()
        val toName = toRaw.takeUnless { isAccountPlaceholder(it) }.orEmpty()

        tvAccount.text = if (toName.isNotEmpty()) toName else "转出账户"
        tvAccount2.text = if (fromName.isNotEmpty()) fromName else "转入账户"
        refreshAccountIconForName(tvAccount.text?.toString().orEmpty())
        refreshAccount2IconForName(tvAccount2.text?.toString().orEmpty())

        customTransferRate = null
        customTargetAmount = null
        hasConfirmedExchangeRate = false

        val newFromName = tvAccount.text?.toString().orEmpty().takeUnless { isAccountPlaceholder(it) }.orEmpty()
        if (Prefs.isShowMultiCurrency(ctx) && newFromName.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(newFromName)
                withContext(Dispatchers.Main) {
                    val accountCurrency = selected?.currency ?: "CNY"
                    setCurrency(accountCurrency)
                }
            }
        }

    }

    private fun setupListeners() {
        etMoney.setOnClickListener { showAmountKeypad() }
        etMoney.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                showAmountKeypad()
            }
            true
        }
        etMoney.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showAmountKeypad()
        }

        spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onTypeChanged(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val clickListener = View.OnClickListener { v ->
            hideAmountKeypad()
            when (v.id) {
                R.id.layout_account, R.id.tv_account -> {
                    scope.launch(Dispatchers.IO) {
                        val assets = AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
                        withContext(Dispatchers.Main) {
                            if (!isActivityAlive()) return@withContext
                            if (assets.isNotEmpty()) {
                                val title = if (spType.selectedItemPosition == 2 || spType.selectedItemPosition == 3) "选择转出账户" else "选择资产"
                                OverlayDialogs.showGridAssetPicker(ctx, tvAccount.text.toString(), title) { selectedName ->
                                    tvAccount.text = selectedName
                                    refreshAccountIconForName(selectedName)
                                    // 自动将 spCurrency 同步为转出账户（账户1）的货币
                                    if (Prefs.isShowMultiCurrency(ctx)) {
                                        scope.launch(Dispatchers.IO) {
                                            val selected = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(selectedName)
                                            withContext(Dispatchers.Main) {
                                                val accountCurrency = selected?.currency ?: "CNY"
                                                setCurrency(accountCurrency)
                                            }
                                        }
                                    }
                                }
                            } else Utils.toast(ctx, "请先添加资产")
                        }
                    }
                }
                R.id.layout_category, R.id.tv_category -> {
                    if (!isActivityAlive()) return@OnClickListener
                    val currentType = if (spType.selectedItemPosition == 1) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
                    OverlayDialogs.showGridCategoryPicker(ctx, tvCategory.text.toString(), currentType) {
                        tvCategory.text = it
                        refreshCategoryIconForSelection(it)
                    }
                }
                R.id.layout_account_2, R.id.tv_account_2 -> {
                    scope.launch(Dispatchers.IO) {
                        val assets = AppDatabase.getDatabase(ctx).assetDao().getAllAssetsList()
                        withContext(Dispatchers.Main) {
                            if (!isActivityAlive()) return@withContext
                            if (assets.isNotEmpty()) {
                                val title = "选择转入账户"
                                OverlayDialogs.showGridAssetPicker(ctx, tvAccount2.text.toString(), title) { selectedName ->
                                    tvAccount2.text = selectedName
                                    refreshAccount2IconForName(selectedName)
                                    adjustTypeByToAccount(selectedName)
                                }
                            } else Utils.toast(ctx, "请先添加资产")
                        }
                    }
                }
            }
        }

        layoutAccount.setOnClickListener(clickListener)
        tvAccount.setOnClickListener(clickListener)
        layoutCategory.setOnClickListener(clickListener)
        tvCategory.setOnClickListener(clickListener)
        layoutAccount2.setOnClickListener(clickListener)
        tvAccount2.setOnClickListener(clickListener)

        // Apply consistent press feedback to all form rows
        layoutAccount.applyFormRowPressFeedback()
        layoutCategory.applyFormRowPressFeedback()
        layoutAccount2.applyFormRowPressFeedback()
        layoutBook.applyFormRowPressFeedback()

        btnSwapAccounts?.setOnClickListener {
            hideAmountKeypad()
            swapAccounts()
        }
        btnSwapAccounts?.applyFormRowPressFeedback()

        // 分类行右侧“退款”标签：点击进入退款模式
        tvRefundToggle?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            enterRefundMode()
        }
        tvRefundToggle?.applyFormRowPressFeedback()

        // 退款来源账单行：整行点击弹出账单选择
        layoutRefundSource?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            OverlayDialogs.showRefundBillPickerDialog(ctx) { chosenBill ->
                selectedRefundSourceBill = chosenBill
                val df = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                val symbol = tao.test.flipaccounting.logic.CurrencyManager.getSymbol(chosenBill.currency)
                val label = "${chosenBill.categoryName.ifEmpty { "未分类" }}  ${df.format(java.util.Date(chosenBill.time))}  ${symbol}${String.format(java.util.Locale.getDefault(), "%.2f", chosenBill.amount)}"
                tvRefundSourceBill?.text = label
                tvRefundSourceBill?.setTextColor(android.graphics.Color.parseColor("#E53935"))
                // 自动填充：账户、分类（金额仅在未填写时才填入）
                val currentAmount = parseAmountInput()
                if (currentAmount == null || currentAmount == 0.0) {
                    etMoney.setText(String.format(java.util.Locale.getDefault(), "%.2f", chosenBill.amount))
                }
                if (tvAccount.text.toString().contains("选择") || tvAccount.text.isBlank()) {
                    tvAccount.text = chosenBill.accountName
                    refreshAccountIconForName(chosenBill.accountName)
                }
                if (tvCategory.text.toString().contains("选择") || tvCategory.text.isBlank()) {
                    val baseCategory = if (chosenBill.categoryName.startsWith("退款：")) {
                        chosenBill.categoryName.removePrefix("退款：").trim()
                    } else if (chosenBill.categoryName.startsWith("退款·")) {
                        // 兼容旧数据格式
                        chosenBill.categoryName.removePrefix("退款·").trim()
                    } else chosenBill.categoryName
                    tvCategory.text = baseCategory
                    refreshCategoryIconForSelection(baseCategory)
                }
            }
        }

        // 退款行右侧“取消退款”标签：点击退出退款模式
        tvRefundBadge?.setOnClickListener {
            if (!isActivityAlive()) return@setOnClickListener
            exitRefundMode()
        }

        rootView.findViewById<View>(R.id.layout_time)?.setOnClickListener {
            hideAmountKeypad()
            val initialTimeMillis = parseUiTimeToMillis(tvTime.text?.toString().orEmpty())
            if (isActivityAlive()) {
                OverlayDialogs.showCustomTimePicker(ctx, initialTimeMillis = initialTimeMillis) { tvTime.text = it }
            }
        }
        rootView.findViewById<View>(R.id.layout_time)?.applyFormRowPressFeedback()
        layoutBook.setOnClickListener {
            hideAmountKeypad()
            showBookPickerDialog()
        }
        btnSave.setOnClickListener { v ->
            hideAmountKeypad()
            // Press feedback: quick scale-down then restore
            v.animate().cancel()
            v.animate()
                .scaleX(0.93f)
                .scaleY(0.93f)
                .setDuration(80L)
                .setInterpolator(tao.test.flipaccounting.ui.common.UiMotion.EXIT_EASING)
                .withEndAction {
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120L)
                        .setInterpolator(tao.test.flipaccounting.ui.common.UiMotion.STANDARD_EASING)
                        .withEndAction { handleSave() }
                        .start()
                }
                .start()
        }
        btnCancel.setOnClickListener {
            hideAmountKeypad()
            if (isProcessingPendingBillQueue) {
                if (pendingBills.isNotEmpty()) {
                    Utils.toast(ctx, "已跳过当前账单，继续下一条")
                    processNextPendingBill()
                } else {
                    isProcessingPendingBillQueue = false
                    updateQueueActionUi()
                    Utils.toast(ctx, "已跳过当前账单")
                    onCloseRequest(true)
                }
            } else {
                if (pendingBills.isNotEmpty()) {
                    pendingBills.clear()
                    Utils.toast(ctx, "多账单已取消，当前填写内容会保留")
                }
                onCloseRequest(false)
            }
        }
        layoutAiTextEntry.setOnClickListener {
            hideAmountKeypad()
            aiAssistant.showInputPanel(isMultiMode = isCurrentUiMultiMode()) { fillDataToUi(it) }
        }
        // btnVoice 的触摸逻辑由 VoiceInputHandler.setupVoiceButton 接管，此处不设置 OnClickListener

        // 备注输入框：回车键收起输入法，不换行
        etRemark.dismissKeyboardOnEnter()
    }

    private fun setupAmountKeypad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            etMoney.showSoftInputOnFocus = false
        }
        etMoney.isLongClickable = false
        etMoney.setTextIsSelectable(false)
        etMoney.isCursorVisible = false

        val keypadView = rootView.findViewById<View>(R.id.layout_amount_keypad) ?: return
        amountKeypadView = keypadView

        // 等 layout_form_body 首次测量完成后记录其高度，键盘将使用完全相同的高度显示
        val formBody = rootView.findViewById<View>(R.id.layout_form_body)
        formBody?.post {
            if (formBodyMeasuredHeight == 0 && formBody.height > 0) {
                formBodyMeasuredHeight = formBody.height
            }
        }

        val digitIds = listOf(
            R.id.btn_key_0 to "0",
            R.id.btn_key_1 to "1",
            R.id.btn_key_2 to "2",
            R.id.btn_key_3 to "3",
            R.id.btn_key_4 to "4",
            R.id.btn_key_5 to "5",
            R.id.btn_key_6 to "6",
            R.id.btn_key_7 to "7",
            R.id.btn_key_8 to "8",
            R.id.btn_key_9 to "9",
            R.id.btn_key_dot to "."
        )
        digitIds.forEach { (id, value) ->
            keypadView.findViewById<View>(id)?.setOnClickListener { appendAmountInput(value) }
        }
        val operatorIds = listOf(
            R.id.btn_key_add to "+",
            R.id.btn_key_subtract to "-",
            R.id.btn_key_multiply to "×",
            R.id.btn_key_divide to "÷"
        )
        operatorIds.forEach { (id, value) ->
            keypadView.findViewById<View>(id)?.setOnClickListener { appendOperatorInput(value) }
        }
        keypadView.findViewById<View>(R.id.btn_key_delete)?.apply {
            setOnClickListener { deleteAmountInput() }
            setOnLongClickListener {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                clearAmountInput()
                true
            }
        }
        keypadView.findViewById<View>(R.id.btn_key_clear)?.setOnClickListener { clearAmountInput() }
        confirmKeyView = keypadView.findViewById<View>(R.id.btn_key_confirm)
        confirmKeyView?.setOnClickListener { confirmAmountInput() }

        etMoney.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                updateConfirmKeyState()
            }
        })
        updateConfirmKeyState()
    }

    private fun showAmountKeypad() {
        val keypad = amountKeypadView ?: return
        if (isAmountKeypadVisible) return
        val formBody = rootView.findViewById<View>(R.id.layout_form_body)
        // 若还未记录高度（极少情况），此刻补测
        if (formBodyMeasuredHeight == 0 && formBody != null && formBody.height > 0) {
            formBodyMeasuredHeight = formBody.height
        }
        // 将键盘高度精确设为表单体高度，保证面板总高度不变
        if (formBodyMeasuredHeight > 0) {
            val lp = keypad.layoutParams
            lp.height = formBodyMeasuredHeight
            keypad.layoutParams = lp
        }
        formBody?.visibility = View.GONE
        keypad.visibility = View.VISIBLE
        isAmountKeypadVisible = true
    }

    private fun hideAmountKeypad() {
        val keypad = amountKeypadView ?: return
        if (!isAmountKeypadVisible) return
        applyAmountInputResult()
        keypad.visibility = View.GONE
        rootView.findViewById<View>(R.id.layout_form_body)?.visibility = View.VISIBLE
        isAmountKeypadVisible = false
        etMoney.clearFocus()
    }

    fun handleBackPressed(): Boolean {
        return if (isAmountKeypadVisible) {
            confirmAmountInput()
            true
        } else {
            false
        }
    }

    private fun appendAmountInput(token: String) {
        val current = etMoney.text?.toString().orEmpty()
        val next = when (token) {
            "." -> {
                val lastOperatorIndex = current.indexOfLast { it in charArrayOf('+', '-', '×', '÷') }
                val currentSegment = if (lastOperatorIndex >= 0) current.substring(lastOperatorIndex + 1) else current
                when {
                    current.isEmpty() -> "0."
                    currentSegment.contains(".") -> current
                    current.lastOrNull()?.let { it in charArrayOf('+', '-', '×', '÷') } == true -> "${current}0."
                    else -> "$current."
                }
            }
            else -> {
                if (current == "0") token else current + token
            }
        }
        etMoney.setText(next)
        etMoney.setSelection(etMoney.text?.length ?: 0)
    }

    private fun deleteAmountInput() {
        val current = etMoney.text?.toString().orEmpty()
        if (current.isEmpty()) return
        val next = current.dropLast(1)
        etMoney.setText(next)
        etMoney.setSelection(etMoney.text?.length ?: 0)
    }

    private fun clearAmountInput() {
        etMoney.setText("")
        etMoney.setSelection(0)
        updateConfirmKeyState()
    }

    private fun appendOperatorInput(operator: String) {
        val current = etMoney.text?.toString().orEmpty()
        // 没有任何数字时，运算符一律不响应（避免出现负数或以运算符开头的表达式）
        if (current.isBlank() || current.all { it in charArrayOf('+', '-', '×', '÷') }) return
        val next = if (current.lastOrNull()?.let { it in charArrayOf('+', '-', '×', '÷') } == true) {
            current.dropLast(1) + operator
        } else {
            current + operator
        }
        etMoney.setText(next)
        etMoney.setSelection(etMoney.text?.length ?: 0)
    }

    private fun confirmAmountInput() {
        // 输入为空时也应关闭键盘（不修改金额），不阻拦
        if (isConfirmInputValid()) {
            applyAmountInputResult()
        }
        val keypad = amountKeypadView ?: return
        keypad.visibility = View.GONE
        rootView.findViewById<View>(R.id.layout_form_body)?.visibility = View.VISIBLE
        isAmountKeypadVisible = false
        etMoney.clearFocus()
    }

    private fun isConfirmInputValid(): Boolean {
        val raw = etMoney.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return false
        return parseAmountInput() != null
    }

    private fun updateConfirmKeyState() {
        val enabled = isConfirmInputValid()
        confirmKeyView?.isEnabled = enabled
        confirmKeyView?.alpha = if (enabled) 1f else 0.45f
    }

    private fun applyAmountInputResult() {
        val evaluated = parseAmountInput()
        if (evaluated != null) {
            etMoney.setText(String.format(Locale.getDefault(), "%.2f", evaluated))
            etMoney.setSelection(etMoney.text?.length ?: 0)
        }
    }

    private fun parseAmountInput(): Double? {
        val raw = etMoney.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return evaluateAmountExpression(raw)
    }

    private fun evaluateAmountExpression(raw: String): Double? {
        val expr = raw.replace("×", "*").replace("÷", "/").replace("\\s+".toRegex(), "")
        if (expr.isEmpty()) return null
        val values = java.util.Stack<Double>()
        val ops = java.util.Stack<Char>()
        var i = 0
        while (i < expr.length) {
            val ch = expr[i]
            if (ch.isDigit() || ch == '.' || (ch == '-' && (i == 0 || expr[i - 1] in charArrayOf('+', '-', '*', '/')))) {
                val start = i
                i++
                while (i < expr.length && (expr[i].isDigit() || expr[i] == '.')) i++
                val token = expr.substring(start, i)
                values.push(token.toDoubleOrNull() ?: return null)
                continue
            }
            if (ch !in charArrayOf('+', '-', '*', '/')) return null
            while (ops.isNotEmpty() && precedence(ops.peek()) >= precedence(ch)) {
                applyTopOperator(values, ops) ?: return null
            }
            ops.push(ch)
            i++
        }
        while (ops.isNotEmpty()) {
            applyTopOperator(values, ops) ?: return null
        }
        return values.takeIf { it.size == 1 }?.peek()
    }

    private fun precedence(op: Char): Int = when (op) {
        '*', '/' -> 2
        '+', '-' -> 1
        else -> 0
    }

    private fun applyTopOperator(values: java.util.Stack<Double>, ops: java.util.Stack<Char>): Double? {
        if (values.size < 2 || ops.isEmpty()) return null
        val right = values.pop()
        val left = values.pop()
        val result = when (ops.pop()) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right == 0.0) return null else left / right
            else -> return null
        }
        values.push(result)
        return result
    }

    /** 进入退款模式：隐藏分类行，显示退款来源账单行 */
    private fun enterRefundMode() {
        isRefundMode = true
        layoutCategory.visibility = View.GONE
        layoutRefundSource?.visibility = View.VISIBLE
        rootView.findViewById<View?>(R.id.line_refund_source)?.visibility = View.VISIBLE
    }

    /** 退出退款模式：显示分类行，隐藏退款来源账单行，清空退款选择 */
    private fun exitRefundMode() {
        isRefundMode = false
        layoutCategory.visibility = View.VISIBLE
        layoutRefundSource?.visibility = View.GONE
        rootView.findViewById<View?>(R.id.line_refund_source)?.visibility = View.GONE
        selectedRefundSourceBill = null
        tvRefundSourceBill?.text = "选择退款账单"
        tvRefundSourceBill?.setTextColor(Color.parseColor("#888888"))
    }

    private fun showBookPickerDialog() {
        val books = BookAccountManager.getBookAccounts(ctx)
        if (books.isEmpty()) return
        OverlayDialogs.showBookPickerDialog(ctx, books, selectedFormBook) { chosen ->
            selectedFormBook = BookAccountManager.resolveWritableBook(ctx, chosen)
            tvBook.text = selectedFormBook
        }
    }

    /** 检查 ctx 对应的 Activity 是否仍然存活（防止 BadTokenException） */
    private fun isActivityAlive(): Boolean {
        if (ctx !is android.app.Activity) return true  // 非 Activity ctx（如悬浮窗）跳过检查
        return !ctx.isFinishing && !ctx.isDestroyed
    }

    /** 根据用户输入文本关键字自动匹配并切换账本（不走 AI） */
    private fun tryAutoSwitchBookByKeyword(text: String) {
        val books = BookAccountManager.getBookAccounts(ctx)
        val matched = books.firstOrNull { name -> name.isNotEmpty() && text.contains(name) }
        if (matched != null && matched != selectedFormBook) {
            selectedFormBook = matched
            tvBook.text = matched
            Utils.toast(ctx, "已自动切换到账本：$matched")
        }
    }

    private fun showExchangeDialog() {
        val money = parseAmountInput() ?: 0.0
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val accountName1 = tvAccount.text.toString()
            val accountName2 = tvAccount2.text.toString()
            val asset1 = db.assetDao().getAssetByName(accountName1)
            val asset2 = if (accountName2 != "选择转入账户" && accountName2 != "转入账户" && accountName2.isNotEmpty())
                db.assetDao().getAssetByName(accountName2) else null
            
            val sourceCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            val targetCurrency = asset2?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            
            withContext(Dispatchers.Main) {
                val currentSpinnerCurrency = spCurrency.selectedItem as? String ?: "CNY"
                if (currentSpinnerCurrency != sourceCurrency) {
                    setCurrency(sourceCurrency)
                }
                // 如果转出账户和转入账户的币种相同，无需确认汇率，直接标记已确认
                if (sourceCurrency == targetCurrency) {
                    customTargetAmount = money
                    customTransferRate = 1.0
                    hasConfirmedExchangeRate = true
                } else {
                    // 币种不同，弹出汇率确认对话框
                    OverlayDialogs.showExchangeRateDialog(
                        ctx,
                        money,
                        sourceCurrency,
                        targetCurrency,
                        customTransferRate
                    ) { src, tgt, rate ->
                        etMoney.setText(String.format("%.2f", src))
                        customTargetAmount = tgt
                        customTransferRate = rate
                        hasConfirmedExchangeRate = true
                    }
                }
            }
        }
    }

    /**
     * 支出/收入跨币种汇率确认弹窗。
     * 上方：用户选择的记账币种（spCurrency）。
     * 下方：账户自身币种（asset1.currency）。
     * 用户确认后，实际从账户扣减 customCurrencyTargetAmount（账户币种金额）。
     * 若用户再次点击 spCurrency 切换，会重置状态并重新弹出此窗口。
     */
    private fun showCurrencyExchangeDialog(onConfirmed: () -> Unit) {
        val money = parseAmountInput() ?: 0.0
        val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val accountName1 = tvAccount.text.toString()
            val asset1 = db.assetDao().getAssetByName(accountName1)
            val assetCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
            withContext(Dispatchers.Main) {
                OverlayDialogs.showExchangeRateDialog(
                    ctx,
                    money,
                    selectedCurrency,
                    assetCurrency,
                    customCurrencyRate
                ) { src, tgt, rate ->
                    // tgt = 账户币种的实际扣减金额
                    customCurrencyTargetAmount = tgt
                    customCurrencyRate = if (src != 0.0) rate else customCurrencyRate
                    hasConfirmedCurrencyRate = true
                    onConfirmed()
                }
            }
        }
    }

    private fun handleSave() {
        val money = parseAmountInput() ?: 0.0
        if (money <= 0) {
            Utils.toast(ctx, "请输入有效金额")
            return
        }
        val spinnerPos = spType.selectedItemPosition
        // 0=支出,1=收入,2=转账,3=还款（本质 type=2 subType=REPAYMENT）
        val isRepayment = isAssetFeatureEnabled && spinnerPos == 3
        var type = if (spinnerPos > 2) 2 else spinnerPos
        if (!isAssetFeatureEnabled && type !in 0..1) {
            Utils.toast(ctx, "当前无资产模式仅支持支出和收入")
            return
        }

        // 开启多币种且为转账时，检查是否需要确认汇率
        if (isAssetFeatureEnabled && type == 2 && !isRepayment && Prefs.isShowMultiCurrency(ctx) && !hasConfirmedExchangeRate) {
            // 先检查转出账户和转入账户的币种是否相同
            val accountName1 = tvAccount.text.toString()
            val accountName2 = tvAccount2.text.toString()
            scope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(ctx)
                val asset1 = db.assetDao().getAssetByName(accountName1)
                val asset2 = if (accountName2 != "选择转入账户" && accountName2 != "转入账户" && accountName2.isNotEmpty())
                    db.assetDao().getAssetByName(accountName2) else null
                
                val sourceCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                val targetCurrency = asset2?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                
            withContext(Dispatchers.Main) {
                val currentSpinnerCurrency = spCurrency.selectedItem as? String ?: "CNY"
                if (currentSpinnerCurrency != sourceCurrency) {
                    setCurrency(sourceCurrency)
                }
                // 如果币种相同，无需弹窗，直接标记为已确认
                if (sourceCurrency == targetCurrency) {
                        customTargetAmount = money
                        customTransferRate = 1.0
                        hasConfirmedExchangeRate = true
                        // 继续保存流程，递归调用 handleSave，此时会跳过汇率检查
                        handleSave()
                    } else {
                        // 币种不同，弹出汇率对话框
                        showExchangeDialog()
                    }
                }
            }
            return
        }

        // 开启多币种且为支出/收入，且选择币种与账户币种不同时，先弹汇率确认
        if (isAssetFeatureEnabled && (type == 0 || type == 1) && Prefs.isShowMultiCurrency(ctx) && !hasConfirmedCurrencyRate) {
            val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
            val accountName1 = tvAccount.text.toString()
            scope.launch(Dispatchers.IO) {
                val asset1 = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(accountName1)
                val assetCurrency = asset1?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                withContext(Dispatchers.Main) {
                    if (selectedCurrency != assetCurrency) {
                        showCurrencyExchangeDialog { handleSave() }
                    } else {
                        // 币种一致，无需汇率确认，直接继续保存
                        customCurrencyRate = null
                        customCurrencyTargetAmount = null
                        hasConfirmedCurrencyRate = true
                        handleSave()
                    }
                }
            }
            return
        }

        val subType = if (isRepayment) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL
        
        val accountName1 = if (isAssetFeatureEnabled) tvAccount.text.toString() else ""
        val accountName2 = if (isAssetFeatureEnabled) tvAccount2.text.toString() else ""

        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val writableBook = BookAccountManager.resolveWritableBook(ctx, selectedFormBook)
            val timeStr = tvTime.text.toString()
            val parsedTimeLong = try {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time
            } catch (_: Exception) {
                null
            }
            val oldBill = editingBillId?.let { db.billDao().getBillById(it) }
            val timeLong = parsedTimeLong ?: oldBill?.time ?: System.currentTimeMillis()

            val asset1 = accountName1.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
            val asset2 = if (type == 2) accountName2.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) } else null

            if (type == Bill.TYPE_TRANSFER && !isRepayment && (asset1 == null || asset2 == null)) {
                withContext(Dispatchers.Main) {
                    Utils.toast(ctx, "转账要求转入和转出账户都为现有资产，请检查账户")
                }
                return@launch
            }

            val selectedCurrency = spCurrency.selectedItem as? String ?: "CNY"
            val effectiveCurrency = if (type == Bill.TYPE_TRANSFER) {
                asset1?.currency?.takeIf { it.isNotEmpty() } ?: selectedCurrency
            } else {
                selectedCurrency
            }
            val ratesReady = ensureRequiredRatesReady(
                type = type,
                isRepayment = isRepayment,
                selectedCurrency = effectiveCurrency,
                sourceAsset = asset1,
                targetAsset = asset2
            )
            if (!ratesReady) {
                return@launch
            }

            var finalCategory = tvCategory.text.toString()
            if (type == 2) {
                finalCategory = if (isRepayment) "还款" else "转账"
            }
            
            // 读取手续费（仅转账，非还款）
            val feeVal = if (type == 2 && !isRepayment) {
                etFee.text.toString().toDoubleOrNull() ?: 0.0
            } else 0.0

            // Calculate Rates and Target Amount
            var finalRate = 1.0
            var sourceDelta = money
            var targetDelta = money
            
            if (type == 2) {
                // Determine Source Delta (in Asset1 Currency)
                val rateSource = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                val rateTrans = CurrencyManager.getRate(selectedCurrency) ?: 1.0
                
                // Convert Transaction Amount -> Source Asset Currency
                // amount(Trans) * (Rate(CNY->Source) / Rate(CNY->Trans))? No.
                // Trans(USD). Source(CNY).
                // 100 USD = 100 * Rate(USD->CNY) CNY.
                // Rate(USD->CNY) = Rate(CNY->CNY) / Rate(CNY->USD) ?? No.
                // Rate in Manager is (1 Unit -> ? CNY) ??
                // Let's check CurrencyManager.kt read_file.
                // DEFAULT_RATES = mapOf("CNY" to 1.0, "USD" to 0.14). 
                // "USD" to 0.14 means 1 CNY = 0.14 USD? Or 1 USD = 0.14 CNY?
                // PREF_KEY_RATES... API_URL = .../latest/CNY.
                // Usually API returns: Base CNY. rates: {"USD": 0.14, ...}
                // Meaning 1 CNY = 0.14 USD.
                // So Rate(CNY->USD) = 0.14.
                // So to convert USD to CNY.
                // Amount(USD) / 0.14 = Amount(CNY).
                
                val sourceRateMapVal = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                if (sourceRateMapVal != 0.0) {
                     // Transaction(Selected) -> Source(Asset)
                     // Trans -> CNY -> Source
                     // Amt(Trans) / Rate(CNY->Trans) * Rate(CNY->Source) ?
                     // Amt(Trans) / Rate(CNY->Trans) = Amt(CNY).
                     // Amt(CNY) * Rate(CNY->Source) = Amt(Source).
                     val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                     if (rateTransMapVal != 0.0) {
                         sourceDelta = (money / rateTransMapVal) * sourceRateMapVal
                     }
                }
                
                // Determine Target Delta
                // If customTargetAmount set, use it.
                // Else calc: Trans -> CNY -> Target
                if (customTransferRate != null) {
                    finalRate = customTransferRate!!
                    targetDelta = money * finalRate
                } else if (customTargetAmount != null) {
                    targetDelta = customTargetAmount!!
                    if (money != 0.0) finalRate = targetDelta / money
                } else {
                     val rateTargetMapVal = CurrencyManager.getRate(asset2?.currency ?: "CNY") ?: 1.0
                     val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                     if (rateTransMapVal != 0.0) {
                         targetDelta = (money / rateTransMapVal) * rateTargetMapVal
                         if (money != 0.0) finalRate = targetDelta / money
                     }
                }
            } else {
                // 支出/收入：若用户已在汇率窗口确认实际扣减金额，直接使用；
                // 否则按汇率表自动换算（currency 与账户一致时 sourceDelta == money）。
                if (customCurrencyRate != null) {
                    sourceDelta = money * customCurrencyRate!!
                } else if (customCurrencyTargetAmount != null) {
                    sourceDelta = customCurrencyTargetAmount!!
                } else {
                    val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                    val sourceRateMapVal = CurrencyManager.getRate(asset1?.currency ?: "CNY") ?: 1.0
                    if (rateTransMapVal != 0.0) {
                        sourceDelta = (money / rateTransMapVal) * sourceRateMapVal
                    }
                }
                // 将记账时的换算比率存入 exchangeRate（统一折算为 CNY 等值）；
                // 统计时用 amount * exchangeRate 得到 CNY 等值，避免因汇率波动导致历史数据失真。
                // 例：记了 10 PLN（账户也是 PLN）：rate=0.56，exchangeRate = 1/0.56 ≈ 1.785
                //     统计：10 * 1.785 ≈ 17.85 CNY ✅
                if (effectiveCurrency == "CNY") {
                    finalRate = 1.0
                } else {
                    // exchangeRate 始终保存“该币种 -> CNY”的换算率，
                    // 这样默认人民币统计和详情页“≈人民币”都能稳定成立。
                    finalRate = BillAssetImpactService.estimateExchangeRateToCny(effectiveCurrency)
                }
            }

            // 手续费换算到目标账户货币并从 targetDelta 扣除
            // 示例：微信转支付宝 100，手续费 1（均为 CNY），targetDelta = 100 - 1 = 99
            var feeInTargetCurrency = 0.0
            if (type == 2 && feeVal > 0.0) {
                val rateTransMapVal = CurrencyManager.getRate(effectiveCurrency) ?: 1.0
                val rateTargetMapVal = CurrencyManager.getRate(asset2?.currency ?: "CNY") ?: 1.0
                feeInTargetCurrency = if (rateTransMapVal != 0.0)
                    (feeVal / rateTransMapVal) * rateTargetMapVal else feeVal
                targetDelta -= feeInTargetCurrency
            }

            var rBill = Bill(
                id = editingBillId ?: 0,
                amount = money,
                type = type,
                subType = subType,
                accountName = accountName1,
                accountId = asset1?.id,
                toAccountId = asset2?.id,
                toAccountName = if (type == 2) accountName2 else "",
                categoryName = finalCategory,
                time = timeLong,
                remark = etRemark.text.toString(),
                currency = effectiveCurrency,
                exchangeRate = finalRate,
                fee = feeVal,
                bookName = writableBook
            )

            if (editingBillId != null && oldBill != null) {
                try {
                    rBill = BillMutationService.replaceBill(
                        db = db,
                        oldBill = oldBill,
                        newBill = rBill,
                        applyAssetImpact = false
                    )
                } catch (e: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, e.message ?: "保存失败")
                    }
                    return@launch
                }
            }

            // === 退款来源处理：若选择了退款来源账单，修改 rBill 为退款账单类型 ===
            val refundSourceBill = selectedRefundSourceBill
            var latestRefundSource: tao.test.flipaccounting.data.local.entity.Bill? = null
            if (refundSourceBill != null && type == 1 && editingBillId == null) {
                latestRefundSource = db.billDao().getBillById(refundSourceBill.id)
                if (latestRefundSource != null
                    && latestRefundSource.type == tao.test.flipaccounting.data.local.entity.Bill.TYPE_EXPENSE
                    && latestRefundSource.subType != tao.test.flipaccounting.data.local.entity.Bill.SUBTYPE_REFUND) {
                    val sourceCategory = if (latestRefundSource.categoryName.startsWith("退款："))
                        latestRefundSource.categoryName.removePrefix("退款：").trim()
                    else if (latestRefundSource.categoryName.startsWith("退款·"))
                        latestRefundSource.categoryName.removePrefix("退款·").trim()  // 兼容旧数据
                    else latestRefundSource.categoryName.trim()
                    rBill = rBill.copy(
                        subType = tao.test.flipaccounting.data.local.entity.Bill.SUBTYPE_REFUND,
                        relatedBillId = latestRefundSource.id,
                        categoryName = "退款：$sourceCategory",
                        originalAmount = money
                    )
                } else {
                    latestRefundSource = null // 来源账单无效，按普通收入处理
                }
            }

            if (editingBillId == null) {
                if (latestRefundSource != null) {
                    // 退款路径：insert + 更新原账单 + 余额影响在同一事务内
                    require(money <= latestRefundSource.amount + 1e-9) {
                        "退款金额超过可退余额"
                    }
                    rBill = db.withTransaction {
                        val savedBill = BillMutationService.insertBillWithinActiveTransaction(
                            db = db,
                            bill = rBill,
                            applyAssetImpact = true
                        )
                        val sourceBaseOriginal = if (latestRefundSource.originalAmount > 0.0)
                            kotlin.math.max(latestRefundSource.originalAmount, latestRefundSource.amount)
                        else latestRefundSource.amount
                        val newActualExpense = (latestRefundSource.amount - money).coerceIn(0.0, sourceBaseOriginal)
                        db.billDao().updateBill(latestRefundSource.copy(
                            amount = newActualExpense,
                            originalAmount = sourceBaseOriginal
                        ))
                        savedBill
                    }
                } else {
                    rBill = BillMutationService.upsertBillAndApplyImpact(
                        db = db,
                        bill = rBill,
                        applyAssetImpact = false
                    )
                }
            }

            // Apply new balances（退款路径已在 withTransaction 内完成，此处仅处理非退款路径和编辑路径）
            if (latestRefundSource == null) {
                BillAssetImpactService.applyBillBalanceImpact(db, rBill)
            }

            withContext(Dispatchers.Main) {
                Utils.toast(ctx, "记账成功")
                if (Prefs.isSaveVibrateEnabled(ctx)) {
                    Utils.vibrate(ctx, 50)
                }
                // Reset custom transfer data
                customTransferRate = null
                customTargetAmount = null
                hasConfirmedExchangeRate = false
                // Reset custom currency rate data
                customCurrencyTargetAmount = null
                customCurrencyRate = null
                hasConfirmedCurrencyRate = false
                // 重置退款来源选择（如果保存后表单不关闭继续使用，退出退款模式）
                if (isRefundMode) exitRefundMode()
                tvRefundToggle?.visibility = if (spType.selectedItemPosition == 1) View.VISIBLE else View.GONE
                
                // ----------- AI 纠错拦截逻辑 -----------
                val originalText = lastAiSuggestOriginalText
                if (originalText != null && Prefs.isAiPromptCorrectionEnabled(ctx)) {
                    val changedType = if (lastAiSuggestType != null && lastAiSuggestType != type) type else null
                    
                    val normalizedSuggestCat = lastAiSuggestCategory?.replace(" ", "")?.replace("/::/", ">")?.replace("/:::/", ">") ?: ""
                    val normalizedFinalCat = finalCategory.replace(" ", "")
                    val changedCat = if (normalizedSuggestCat.isNotEmpty() && normalizedSuggestCat != normalizedFinalCat) finalCategory else null
                    
                    val normalizedSuggestAcc1 = lastAiSuggestAccount1?.replace(" ", "") ?: ""
                    val normalizedFinalAcc1 = accountName1.replace(" ", "")
                    val changedAcc1 = if (isAssetFeatureEnabled && normalizedSuggestAcc1.isNotEmpty() && normalizedSuggestAcc1 != normalizedFinalAcc1) accountName1 else null
                    
                    val normalizedSuggestAcc2 = lastAiSuggestAccount2?.replace(" ", "") ?: ""
                    val normalizedFinalAcc2 = accountName2.replace(" ", "")
                    val changedAcc2 = if (isAssetFeatureEnabled && normalizedSuggestAcc2.isNotEmpty() && normalizedSuggestAcc2 != normalizedFinalAcc2) accountName2 else null

                    var actualChangedCat = changedCat
                    if (type == 2) {
                        actualChangedCat = null
                    }
                    
                    var actualChangedAcc2 = changedAcc2
                    if (type == 0 || type == 1) {
                        actualChangedAcc2 = null
                    }

                    if (changedType != null || actualChangedCat != null) {
                        lastAiSuggestOriginalText = null
                        val acc1ToSuggest = if (changedType != null) (changedAcc1 ?: accountName1) else null

                        val suggestion = RuleCreateSuggestion(
                            originalText = originalText,
                            finalType = changedType ?: type,
                            finalCategory = actualChangedCat ?: finalCategory,
                            finalAcc1 = acc1ToSuggest,
                            finalAcc2 = actualChangedAcc2
                        )
                        handleRulePromptAfterBookkeeping(suggestion)
                        return@withContext
                    }
                }
                lastAiSuggestOriginalText = null

                if (pendingBills.isNotEmpty()) {
                    processNextPendingBill()
                } else {
                    onCloseRequest(true)
                }
            }
        }
    }

    private suspend fun ensureRequiredRatesReady(
        type: Int,
        isRepayment: Boolean,
        selectedCurrency: String,
        sourceAsset: Asset?,
        targetAsset: Asset?
    ): Boolean {
        if (!Prefs.isShowMultiCurrency(ctx)) return true

        val requiredCurrencies = linkedSetOf<String>()
        requiredCurrencies.add(selectedCurrency.trim().uppercase(Locale.ROOT))
        sourceAsset?.currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }

        if (type == Bill.TYPE_TRANSFER && !isRepayment) {
            targetAsset?.currency?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotBlank() }?.let { requiredCurrencies.add(it) }
        }

        fun missingRates(currencies: Set<String>): Set<String> {
            return currencies
                .filter { code -> code != "CNY" && CurrencyManager.getRate(code) == null }
                .toSet()
        }

        val missingBefore = missingRates(requiredCurrencies)
        if (missingBefore.isEmpty()) return true

        withContext(Dispatchers.Main) {
            Utils.toast(ctx, "检测到缺少汇率，正在自动刷新…")
        }

        val refreshSuccess = suspendCancellableCoroutine<Boolean> { cont ->
            CurrencyManager.updateRates(ctx) { success ->
                if (cont.isActive) cont.resume(success)
            }
        }

        val missingAfter = missingRates(requiredCurrencies)
        if (missingAfter.isNotEmpty()) {
            withContext(Dispatchers.Main) {
                showMissingRatesBlockingDialog(missingAfter, refreshSuccess)
            }
            return false
        }

        withContext(Dispatchers.Main) {
            Utils.toast(ctx, "汇率已更新，继续记账")
        }
        return true
    }

    private var isMissingRateDialogShowing = false

    private fun showMissingRatesBlockingDialog(missingCurrencies: Set<String>, refreshSuccess: Boolean) {
        val missingText = missingCurrencies.joinToString("、")
        val suffix = if (refreshSuccess) "" else "（自动刷新失败）"
        val message = "以下币种仍缺少汇率：$missingText$suffix\n\n请先更新汇率后再保存。"

        val safeContext = ctx
        if (safeContext !is Activity || safeContext.isFinishing) {
            Utils.toast(safeContext, message)
            return
        }

        if (isMissingRateDialogShowing) return
        isMissingRateDialogShowing = true

        val dialog = AlertDialog.Builder(ContextThemeWrapper(safeContext, R.style.Theme_FlipAccounting))
            .setTitle("缺少汇率，暂无法保存")
            .setMessage(message)
            .setCancelable(true)
            .setNegativeButton("稍后") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("复制缺失币种") { _, _ ->
                val clipboard = safeContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("missing_currencies", missingText))
                    Utils.toast(safeContext, "已复制：$missingText")
                } else {
                    Utils.toast(safeContext, "复制失败：系统剪贴板不可用")
                }
            }
            .setPositiveButton("去更新汇率") { dialog, _ ->
                dialog.dismiss()
                runCatching {
                    safeContext.startActivity(Intent(safeContext, ExchangeRateActivity::class.java))
                }.onFailure {
                    Utils.toast(safeContext, "无法打开汇率设置页")
                }
            }
            .setOnDismissListener {
                isMissingRateDialogShowing = false
            }
            .create()
        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private var hasFinishedSaveFlow = false

    private data class RuleCreateSuggestion(
        val originalText: String,
        val finalType: Int,
        val finalCategory: String?,
        val finalAcc1: String?,
        val finalAcc2: String?
    )

    private fun handleRulePromptAfterBookkeeping(suggestion: RuleCreateSuggestion) {
        val safeContext = ctx
        val isOverlayContext = safeContext !is Activity
        if (safeContext is Activity && safeContext.isFinishing) {
            finishSaveFlow()
            return
        }

        val dialog = AlertDialog.Builder(ContextThemeWrapper(safeContext, R.style.Theme_FlipAccounting))
            .setTitle("检测到识别偏差")
            .setMessage("你修改了 AI 识别结果，是否将本次修正保存为规则，下次自动纠正？")
            .setNegativeButton("不需要") { dialog, _ ->
                dialog.dismiss()
                finishSaveFlow()
            }
            .setPositiveButton("添加规则") { dialog, _ ->
                dialog.dismiss()
                showCreateRuleDialog(
                    suggestion.originalText,
                    suggestion.finalType,
                    suggestion.finalCategory,
                    suggestion.finalAcc1,
                    suggestion.finalAcc2,
                    finishOnDone = true
                )
            }
            .setCancelable(false)
            .create()

        if (isOverlayContext) {
            OverlayDialogs.showOverlayCenterDialog(
                dialog = dialog,
                ctx = safeContext,
                widthRatio = 0.88f,
                cancelOnTouchOutside = false,
                useSolidPanelBackground = true
            )
        } else {
            OverlayDialogs.showPageCenterDialog(
                dialog = dialog,
                ctx = safeContext,
                widthRatio = 0.88f,
                cancelOnTouchOutside = false,
                useSolidPanelBackground = true
            )
        }
    }

    private fun showCreateRuleDialog(
        originalText: String,
        finalType: Int,
        finalCat: String?,
        finalAcc1: String?,
        finalAcc2: String?,
        finishOnDone: Boolean = true
    ) {
        val categoryToSave = if (finalType == 2) null else finalCat
        hasFinishedSaveFlow = false
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
                scope.launch(Dispatchers.IO) {
                    val saveResult = saveRuleWithDedupDecision(newRule)
                    withContext(Dispatchers.Main) {
                        when (saveResult) {
                            RuleSaveResult.SAVED -> Utils.toast(ctx, "规则创建成功")
                            RuleSaveResult.SKIPPED -> Utils.toast(ctx, "已取消本次规则保存")
                        }

                        if (finishOnDone && !hasFinishedSaveFlow) {
                            hasFinishedSaveFlow = true
                            finishSaveFlow()
                        }
                    }
                }
            },
            onDelete = null,
            onCancel = { 
                if (finishOnDone && !hasFinishedSaveFlow) {
                    hasFinishedSaveFlow = true
                    finishSaveFlow()
                } else {
                    Utils.toast(ctx, "已取消添加规则")
                }
            }
        )
    }

    private enum class RuleSaveResult { SAVED, SKIPPED }

    private suspend fun saveRuleWithDedupDecision(newRule: AiRule): RuleSaveResult {
        val db = AppDatabase.getDatabase(ctx)
        val dao = db.aiRuleDao()
        val existingRules = dao.getRulesByKeyword(newRule.keyword.trim())

        if (existingRules.isEmpty()) {
            dao.insertRule(newRule)
            return RuleSaveResult.SAVED
        }

        val normalizedNewCategory = normalizeRuleCategory(newRule.targetCategory)
        val sameCategoryRule = existingRules.firstOrNull {
            normalizeRuleCategory(it.targetCategory) == normalizedNewCategory
        }
        if (sameCategoryRule != null) {
            dao.insertRule(newRule.copy(id = sameCategoryRule.id))
            return RuleSaveResult.SAVED
        }

        val chosen = withContext(Dispatchers.Main) {
            promptRuleConflictDecision(
                keyword = newRule.keyword,
                existingCategory = existingRules.firstOrNull()?.targetCategory,
                newCategory = newRule.targetCategory
            )
        }

        return when (chosen) {
            RuleConflictDecision.OVERWRITE -> {
                val target = existingRules.firstOrNull()
                if (target != null) {
                    dao.insertRule(newRule.copy(id = target.id))
                } else {
                    dao.insertRule(newRule)
                }
                RuleSaveResult.SAVED
            }
            RuleConflictDecision.CANCEL_RECORD,
            RuleConflictDecision.DISMISS -> RuleSaveResult.SKIPPED
        }
    }

    private enum class RuleConflictDecision { OVERWRITE, CANCEL_RECORD, DISMISS }

    private suspend fun promptRuleConflictDecision(
        keyword: String,
        existingCategory: String?,
        newCategory: String?
    ): RuleConflictDecision = suspendCancellableCoroutine { cont ->
        val safeContext = ctx
        if (safeContext !is Activity || safeContext.isFinishing) {
            cont.resume(RuleConflictDecision.DISMISS)
            return@suspendCancellableCoroutine
        }

        val existingLabel = existingCategory?.takeIf { it.isNotBlank() } ?: "未设置分类"
        val newLabel = newCategory?.takeIf { it.isNotBlank() } ?: "未设置分类"

        val dialog = AlertDialog.Builder(ContextThemeWrapper(safeContext, R.style.Theme_FlipAccounting))
            .setTitle("检测到重复关键词")
            .setMessage("关键词“$keyword”已有规则（分类：$existingLabel），本次分类为“$newLabel”。\n\n请选择：覆盖旧规则，或取消本次规则保存。")
            .setPositiveButton("覆盖") { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(RuleConflictDecision.OVERWRITE)
            }
            .setNegativeButton("取消本次规则保存") { d, _ ->
                d.dismiss()
                if (cont.isActive) cont.resume(RuleConflictDecision.CANCEL_RECORD)
            }
            .setOnCancelListener {
                if (cont.isActive) cont.resume(RuleConflictDecision.DISMISS)
            }
            .create()

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = safeContext,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = true
        )
    }

    private fun normalizeRuleCategory(category: String?): String {
        return category
            ?.replace(" ", "")
            ?.replace("/::/", ">")
            ?.replace("/:::/", ">")
            ?.trim()
            .orEmpty()
    }

    private fun finishSaveFlow() {
        if (pendingBills.isNotEmpty()) {
            processNextPendingBill()
        } else {
            onCloseRequest(true)
        }
    }

    private val pendingBills = mutableListOf<JSONObject>()
    private var isProcessingPendingBillQueue: Boolean = false
    private var totalPendingBillCount: Int = 0

    private fun updateQueueActionUi() {
        if (isProcessingPendingBillQueue) {
            val currentIndex = (totalPendingBillCount - pendingBills.size).coerceAtLeast(1)
            tvTitle?.text = "记账 $currentIndex/$totalPendingBillCount"
            btnCancel.text = "跳过这笔"
            btnSave.text = "保存并下一笔"
        } else {
            tvTitle?.text = if (editingBillId != null) "编辑账单" else "记一笔"
            btnCancel.text = "取消"
            btnSave.text = "保存并记账"
        }
    }

    private fun processNextPendingBill() {
        if (pendingBills.isEmpty()) {
            isProcessingPendingBillQueue = false
            totalPendingBillCount = 0
            updateQueueActionUi()
            onCloseRequest(true)
            return
        }
        isProcessingPendingBillQueue = true
        updateQueueActionUi()
        val next = pendingBills.removeAt(0)
        fillDataToUi(next, showToast = true)
        setCurrency(next.optString("currency", "CNY"))
        
        if (pendingBills.isNotEmpty()) {
            Utils.toast(ctx, "剩余 ${pendingBills.size} 条待记录")
        } else {
            Utils.toast(ctx, "这是最后一条记录")
        }
    }

    fun setCurrency(currencyCode: String) {
        val normalized = currencyCode.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) return

        val enabledCodes = CurrencyManager.getEnabledCurrencies(ctx).toMutableList().apply {
            if (!contains("CNY")) add(0, "CNY")
        }
        if (!enabledCodes.contains(normalized)) {
            enabledCodes.add(normalized)
            CurrencyManager.setEnabledCurrencies(ctx, enabledCodes)
            setupCurrencySpinner()
        }

        val index = enabledCodes.indexOf(normalized)
        if (index >= 0) {
            isProgrammaticCurrencyChange = true
            spCurrency.setSelection(index)
            isProgrammaticCurrencyChange = false
        }
    }

    private fun convertAmountBetweenCurrencies(amount: Double, fromCurrency: String, toCurrency: String): Double {
        if (fromCurrency.equals(toCurrency, ignoreCase = true)) return amount
        val fromRate = CurrencyManager.getRate(fromCurrency) ?: 1.0
        val toRate = CurrencyManager.getRate(toCurrency) ?: 1.0
        if (fromRate == 0.0) return amount
        return (amount / fromRate) * toRate
    }

    private var lastAiSuggestType: Int? = null
    private var lastAiSuggestCategory: String? = null
    private var lastAiSuggestAccount1: String? = null
    private var lastAiSuggestAccount2: String? = null
    private var lastAiSuggestOriginalText: String? = null

    fun fillDataToUi(json: JSONObject, showToast: Boolean = true, forceMultiMode: Boolean = false) {
        val localRuleCorrected = json.optBoolean("_local_rule_corrected", false)
        val original = json.takeIf { it.has("original_text_from_user") }?.optString("original_text_from_user")
        val remarks = json.takeIf { it.has("remarks") }?.optString("remarks")
        val remark = json.takeIf { it.has("remark") }?.optString("remark")
        lastAiSuggestOriginalText = remarks ?: remark ?: original

        // 根据用户原始输入文本自动切换账本（不走 AI）
        val rawText = original ?: remarks ?: remark
        if (!rawText.isNullOrEmpty()) tryAutoSwitchBookByKeyword(rawText)

        val isFromAi = json.has("original_text_from_user")
        if (isFromAi) {
            lastAiSuggestType = json.optInt("type", -1).takeIf { it != -1 }
            lastAiSuggestCategory = json.optString("category_name", json.optString("category", ""))
            lastAiSuggestAccount1 = json.optString("asset_name", json.optString("account", ""))
            lastAiSuggestAccount2 = json.optString("to_asset_name", json.optString("to_account", ""))
        } else {
            lastAiSuggestOriginalText = null
            lastAiSuggestType = null
            lastAiSuggestCategory = null
            lastAiSuggestAccount1 = null
            lastAiSuggestAccount2 = null
        }

        val isMulti = forceMultiMode || isCurrentUiMultiMode()
        
        if (isMulti && json.has("bills")) {
            val billsArray = json.getJSONArray("bills")
            if (billsArray.length() == 0) return
            
            val isNotSync = Prefs.isMultiBillNotSync(ctx)
            if (isNotSync) {
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(ctx)
                    val writableBook = BookAccountManager.resolveWritableBook(ctx, selectedFormBook)
                    for (i in 0 until billsArray.length()) {
                        val obj = billsArray.getJSONObject(i)
                        
                        var typeIndex = obj.optInt("type", 0)
                        if (!isAssetFeatureEnabled) {
                            typeIndex = if (typeIndex == 1) 1 else 0
                        } else if (typeIndex > 2) {
                            typeIndex = 2
                        }
                        val amt = obj.optDouble("amount", 0.0)
                        val asset1 = if (isAssetFeatureEnabled) obj.optString("asset_name", "") else ""
                        val asset2Name = if (isAssetFeatureEnabled) obj.optString("to_asset_name", "") else ""
                        val a1 = asset1.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
                        val a2ByName = asset2Name.takeIf { it.isNotBlank() }?.let { db.assetDao().getAssetByName(it) }
                        var cat = obj.optString("category_name", "").replace("/::/", " > ").replace("/:::/", " > ")
                        var toAssetId: Long? = null
                        var toAssetNm = ""
                        if (isAssetFeatureEnabled && typeIndex == 2) {
                            // 仅当转入/转出都为现有资产时，才允许落为转账
                            if (a1 != null && a2ByName != null) {
                                cat = "转账"
                                toAssetNm = asset2Name
                                toAssetId = a2ByName.id
                            } else {
                                typeIndex = Bill.TYPE_EXPENSE
                                if (cat.isBlank() || cat == "转账") cat = "其他"
                            }
                        }
                        
                        val timeStr = obj.optString("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                        val parsedTime = try { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).parse(timeStr)?.time ?: System.currentTimeMillis() } catch(e:Exception){ System.currentTimeMillis() }
                        val remark = obj.optString("remarks", "")
                        val currency = obj.optString("currency", "CNY").ifBlank { "CNY" }
                        val fee = obj.optDouble("fee", 0.0).coerceAtLeast(0.0)
                        
                        val a2 = if (toAssetId != null) db.assetDao().getAssetById(toAssetId) else null
                        val exchangeRate = when {
                            typeIndex == Bill.TYPE_TRANSFER && a2 != null && amt > 0.0 ->
                                BillAssetImpactService.estimateExchangeRateToTarget(amt, currency, a2.currency)
                            currency == "CNY" -> 1.0
                            else -> {
                                val rateToCurrency = CurrencyManager.getRate(currency) ?: 1.0
                                if (rateToCurrency != 0.0) 1.0 / rateToCurrency else 1.0
                            }
                        }
                        
                        val bill = Bill(
                            amount = amt,
                            type = typeIndex,
                            currency = currency,
                            exchangeRate = exchangeRate,
                            fee = fee,
                            accountName = asset1,
                            accountId = a1?.id,
                            toAccountId = toAssetId,
                            toAccountName = toAssetNm,
                            categoryName = cat,
                            time = parsedTime,
                            remark = remark,
                            bookName = writableBook
                        )
                        BillMutationService.insertBillAndApplyImpact(db, bill)
                    }
                    withContext(Dispatchers.Main) {
                        Utils.toast(ctx, "已自动存入 ${billsArray.length()} 条账单")
                        onCloseRequest(true)
                    }
                }
            } else {
                pendingBills.clear()
                totalPendingBillCount = 0
                for (i in 0 until billsArray.length()) {
                    pendingBills.add(billsArray.getJSONObject(i))
                }
                totalPendingBillCount = pendingBills.size
                processNextPendingBill()
            }
            return
        }

        val amount = json.optDouble("amount", 0.0)
        if (amount > 0) etMoney.setText(amount.toString())
        
        if (json.has("type")) {
            var t = json.optInt("type")
            val st = json.optInt("subType", 0)
            if (!isAssetFeatureEnabled) {
                t = if (t == 1) 1 else 0
            } else if (t > 2) {
                t = 2
            }
            // 还款：type=2 + subType=1 -> spinner position=3
            val spinnerPos = if (t == 2 && st == Bill.SUBTYPE_REPAYMENT) 3 else t
            val maxSpinnerPos = if (isAssetFeatureEnabled) 3 else 1
            if (spinnerPos in 0..maxSpinnerPos) spType.setSelection(spinnerPos)
        }
        
        val assetNameFromJson = json.optString("asset_name", "")
        if (isAssetFeatureEnabled && assetNameFromJson.isNotEmpty()) {
            tvAccount.text = assetNameFromJson
            refreshAccountIconForName(assetNameFromJson)
        }

        // 自动匹配资产对应的币种：
        // 优先级：① json 中 AI 已明确返回 currency -> 直接使用
        //         ② json 未返回 currency 或为 CNY（默认值）-> 从资产库查出该资产的 currency
        if (isAssetFeatureEnabled && Prefs.isShowMultiCurrency(ctx) && assetNameFromJson.isNotEmpty()) {
            val aiCurrency = json.optString("currency", "").trim()
            if (aiCurrency.isNotEmpty() && aiCurrency != "CNY") {
                // AI 已明确识别出非 CNY 币种，直接用
                setCurrency(aiCurrency)
            } else {
                // 从资产库自动查询币种
                scope.launch(Dispatchers.IO) {
                    val asset = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(assetNameFromJson)
                    val assetCurrency = asset?.currency?.takeIf { it.isNotEmpty() } ?: "CNY"
                    withContext(Dispatchers.Main) {
                        // 仅当资产币种不是 CNY，或 AI 未返回 currency 时才覆盖
                        if (assetCurrency != "CNY" || aiCurrency.isEmpty()) {
                            setCurrency(assetCurrency)
                        }
                    }
                }
            }
        } else if (json.has("currency")) {
            val aiCurrency = json.optString("currency", "").trim()
            if (aiCurrency.isNotEmpty()) setCurrency(aiCurrency)
        }

        if (isAssetFeatureEnabled && json.has("to_asset_name")) {
            val toA = json.optString("to_asset_name")
            if (toA.isNotEmpty()) {
                tvAccount2.text = toA
                refreshAccount2IconForName(toA)
                // 如果当前是转账且转入是信用卡，自动切换为还款
                if (spType.selectedItemPosition == 2) {
                    scope.launch(Dispatchers.IO) {
                        val asset = AppDatabase.getDatabase(ctx).assetDao().getAssetByName(toA)
                        withContext(Dispatchers.Main) {
                            if (asset?.assetCategory == tao.test.flipaccounting.data.local.entity.Asset.CATEGORY_CREDIT_CARD) {
                                spType.setSelection(3)
                            }
                        }
                    }
                }
            }
        }
        
        val categoryText = json.optString("category_name", tvCategory.text.toString()).replace("/::/", " > ")
        tvCategory.text = categoryText
        refreshCategoryIconForSelection(categoryText)
        etRemark.setText(json.optString("remarks", json.optString("remark", "")))
        val time = json.optString("time", "")
        if (time.isNotEmpty()) tvTime.text = time

        // 编辑已有账单时恢复账本
        val bookFromJson = json.optString("bookName", "")
        if (bookFromJson.isNotEmpty()) {
            selectedFormBook = BookAccountManager.resolveWritableBook(ctx, bookFromJson)
            tvBook.text = selectedFormBook
        }

        if (json.has("recordTime")) {
            val idStr = json.optString("recordTime", "")
            if (idStr.isNotEmpty()) editingBillId = idStr.toLongOrNull()
        }

        enforceTransferAssetConstraintIfNeeded(json, showToast)
        if (showToast && localRuleCorrected) {
            Utils.toast(ctx, "该笔账单已应用本地规则")
        }

        if (editingBillId != null) {
            val savedExchangeRate = json.optDouble("exchange_rate", Double.NaN)
            if (!savedExchangeRate.isNaN()) {
                val currentType = spType.selectedItemPosition
                val isTransferEdit = isAssetFeatureEnabled && currentType == 2
                val isRepaymentEdit = isAssetFeatureEnabled && currentType == 3
                if (isTransferEdit && !isRepaymentEdit) {
                    customTransferRate = savedExchangeRate
                    customTargetAmount = null
                    hasConfirmedExchangeRate = true
                } else if (currentType == 0 || currentType == 1) {
                    customCurrencyRate = savedExchangeRate
                    customCurrencyTargetAmount = null
                    hasConfirmedCurrencyRate = true
                }
            }
        }
    }

    private fun enforceTransferAssetConstraintIfNeeded(json: JSONObject, showToast: Boolean) {
        if (!isAssetFeatureEnabled) return
        if (json.optInt("type", -1) != Bill.TYPE_TRANSFER) return
        if (json.optInt("subType", 0) == Bill.SUBTYPE_REPAYMENT) return

        val fromName = json.optString("asset_name", json.optString("account", "")).trim()
        val toName = json.optString("to_asset_name", json.optString("to_account", "")).trim()

        scope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(ctx)
            val fromAsset = db.assetDao().getAssetByName(fromName)
            val toAsset = db.assetDao().getAssetByName(toName)
            val validTransfer = fromName.isNotEmpty() && toName.isNotEmpty() && fromAsset != null && toAsset != null
            if (validTransfer) return@launch

            withContext(Dispatchers.Main) {
                if (spType.selectedItemPosition == 2) {
                    spType.setSelection(0)
                }
                if (tvAccount2.text.toString() == toName) {
                    tvAccount2.text = ""
                    refreshAccount2IconForName("")
                }
                if (tvCategory.text.toString().trim() == "转账") {
                    tvCategory.text = "其他"
                    refreshCategoryIconForSelection("其他")
                }
                if (showToast) {
                    Utils.toast(ctx, "转账要求转入和转出账户都为现有资产，已改为支出，请确认")
                }
            }
        }
    }

}
