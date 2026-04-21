package tao.test.flipaccounting

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.BaseAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.room.withTransaction
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Asset
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillAssetImpactService
import tao.test.flipaccounting.logic.BillMutationService
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.text.ParsePosition
import java.util.Locale
import kotlin.math.abs

class AddAssetActivity : AppCompatActivity() {

    private lateinit var etName: EditText
    private lateinit var etBalance: EditText
    private lateinit var etRemark: EditText
    private lateinit var tvTypeName: TextView
    private lateinit var ivTypeIcon: ImageView
    private lateinit var tvCurrency: TextView
    private lateinit var swIncludeNet: androidx.appcompat.widget.SwitchCompat
    private lateinit var layoutTypePicker: View
    private lateinit var etSearchType: EditText
    private lateinit var tvAssetCategory: TextView

    private var selectedType: String = ""
    private var selectedIcon: String = ""
    private var selectedCurrency: String = "CNY"
    private var selectedAssetCategory: String = Asset.CATEGORY_FUND
    private var assetId: Long = -1L
    private var originalSortOrder: Int = 0
    private var originalPickerSortOrder: Int = 0
    private var allIcons: List<BuiltInCategory> = emptyList()
    private var suppressBalanceWatcher = false
    private var balanceEditedByUser = false
    private var pendingAssetSave: PendingAssetSave? = null
    private val assetUiPrefs by lazy { getSharedPreferences(PREFS_ASSET_UI, MODE_PRIVATE) }

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val balanceAdjustmentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pending = pendingAssetSave ?: return@registerForActivityResult
        pendingAssetSave = null
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val data = result.data
        val mode = data?.getStringExtra(BalanceAdjustmentActivity.RESULT_MODE)
            ?: BalanceAdjustmentActivity.MODE_SAVE_ONLY

        lifecycleScope.launch {
            persistAssetChange(
                pending = pending,
                adjustment = if (mode == BalanceAdjustmentActivity.MODE_SAVE_WITH_RECORD && data != null) {
                    AdjustmentDraft(
                        categoryName = data.getStringExtra(BalanceAdjustmentActivity.RESULT_CATEGORY_NAME).orEmpty(),
                        includeInStats = data.getBooleanExtra(BalanceAdjustmentActivity.RESULT_INCLUDE_IN_STATS, true),
                        remark = data.getStringExtra(BalanceAdjustmentActivity.RESULT_REMARK).orEmpty()
                    )
                } else {
                    null
                }
            )
            finish()
        }
    }

    private data class PendingAssetSave(
        val asset: Asset,
        val oldName: String,
        val oldBalance: Double,
        val oldCurrency: String,
        val isNew: Boolean
    )

    private data class AdjustmentDraft(
        val categoryName: String,
        val includeInStats: Boolean,
        val remark: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_asset)

        assetId = intent.getLongExtra("ASSET_ID", -1L)

        initViews()
        setupTypePicker()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!handleBackAction()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        if (assetId != -1L) {
            loadAssetData()
        }
    }

    private fun initViews() {
        etName = findViewById(R.id.et_name)
        etBalance = findViewById(R.id.et_balance)
        etRemark = findViewById(R.id.et_remark)
        tvTypeName = findViewById(R.id.tv_type_name)
        ivTypeIcon = findViewById(R.id.iv_type_icon)
        tvCurrency = findViewById(R.id.tv_currency)
        swIncludeNet = findViewById(R.id.sw_include_net)
        layoutTypePicker = findViewById(R.id.layout_type_picker)
        etSearchType = findViewById(R.id.et_search_type)
        tvAssetCategory = findViewById(R.id.tv_asset_category)
        refreshCurrencyDisplay()

        findViewById<View>(R.id.btn_back).setOnClickListener {
            if (!handleBackAction()) finish()
        }
        findViewById<View>(R.id.btn_save).setOnClickListener { saveAsset() }

        etRemark.dismissKeyboardOnEnter()

        findViewById<View>(R.id.layout_select_type).setOnClickListener {
            layoutTypePicker.visibility =
                if (layoutTypePicker.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        findViewById<View>(R.id.layout_select_currency).setOnClickListener {
            showCurrencyDialog()
        }

        findViewById<View>(R.id.layout_select_asset_category).setOnClickListener {
            showAssetCategoryDialog()
        }

        etSearchType.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterIcons(s.toString())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        etBalance.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!suppressBalanceWatcher) {
                    balanceEditedByUser = true
                }
                etBalance.setTextColor(
                    if (s.isNullOrEmpty()) Color.parseColor("#999999")
                    else Color.parseColor("#2196F3")
                )
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        swIncludeNet.setOnCheckedChangeListener { buttonView, isChecked ->
            // Programmatic state updates (e.g. loading existing asset) should not trigger this one-time tip.
            if (!buttonView.isPressed) return@setOnCheckedChangeListener
            if (isChecked) return@setOnCheckedChangeListener
            if (assetUiPrefs.getBoolean(KEY_SKIP_NET_ASSET_TIP_SHOWN, false)) return@setOnCheckedChangeListener

            Toast.makeText(
                this,
                "该账户将不计入总资产，余额变化不影响净资产；相关流水仍会正常记录。",
                Toast.LENGTH_LONG
            ).show()
            assetUiPrefs.edit().putBoolean(KEY_SKIP_NET_ASSET_TIP_SHOWN, true).apply()
        }
    }

    private fun handleBackAction(): Boolean {
        if (layoutTypePicker.visibility == View.VISIBLE) {
            layoutTypePicker.visibility = View.GONE
            etSearchType.setText("")
            return true
        }
        return false
    }

    private fun setupTypePicker() {
        allIcons = loadAssetsJson()
        val rvIcons = findViewById<RecyclerView>(R.id.rv_icon_list)
        rvIcons.layoutManager = GridLayoutManager(this, 4)
        rvIcons.adapter = IconPickerAdapter(allIcons) { item ->
            val oldTypeName = selectedType
            selectedType = item.name
            selectedIcon = item.icon
            selectedAssetCategory = when (item.type.uppercase(Locale.ROOT)) {
                "CREDIT" -> Asset.CATEGORY_CREDIT_CARD
                else -> selectedAssetCategory
            }
            tvTypeName.text = item.name
            tvTypeName.setTextColor(Color.parseColor("#2196F3"))
            Glide.with(this)
                .load(AssetIconDefaults.withDefault(item.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(ivTypeIcon)

            val currentName = etName.text.toString()
            if (currentName.isEmpty() || currentName == oldTypeName) {
                etName.setText(item.name)
            }
            updateAssetCategoryUI()
            layoutTypePicker.visibility = View.GONE
        }
    }

    private fun filterIcons(query: String) {
        val filtered = if (query.isEmpty()) {
            allIcons
        } else {
            allIcons.filter { it.name.contains(query, ignoreCase = true) }
        }
        (findViewById<RecyclerView>(R.id.rv_icon_list).adapter as? IconPickerAdapter)?.updateData(filtered)
    }

    private fun loadAssetData() {
        findViewById<TextView>(R.id.tv_title).text = "修改账户"
        lifecycleScope.launch {
            val asset = db.assetDao().getAssetById(assetId)
            asset?.let {
                etName.setText(it.name)

                val balanceFormatted = String.format(Locale.getDefault(), "%.2f", it.balance)
                    .trimEnd('0')
                    .trimEnd('.')
                    .ifEmpty { "0" }

                suppressBalanceWatcher = true
                etBalance.setText(balanceFormatted)
                suppressBalanceWatcher = false
                balanceEditedByUser = false
                etBalance.setTextColor(Color.parseColor("#333333"))

                etBalance.post {
                    etBalance.selectAll()
                    etBalance.requestFocus()
                }

                etRemark.setText(it.remark)
                tvTypeName.text = it.type
                tvTypeName.setTextColor(Color.parseColor("#2196F3"))
                selectedType = it.type
                selectedIcon = AssetIconDefaults.withDefault(it.icon)
                selectedCurrency = it.currency
                refreshCurrencyDisplay()
                swIncludeNet.isChecked = it.includeInNetAsset
                selectedAssetCategory = it.assetCategory
                originalSortOrder = it.sortOrder
                originalPickerSortOrder = it.pickerSortOrder
                updateAssetCategoryUI()

                Glide.with(this@AddAssetActivity)
                    .load(AssetIconDefaults.withDefault(it.icon))
                    .transform(CircleCrop())
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.ic_placeholder)
                    .error(R.drawable.ic_placeholder)
                    .into(ivTypeIcon)
            }
        }
    }

    private fun saveAsset(skipCurrencyConfirm: Boolean = false) {
        val name = etName.text.toString().trim()
        val balance = BillAssetImpactService.roundMoney(parseLocalizedAmount(etBalance.text.toString()))

        if (name.isEmpty()) {
            Toast.makeText(this, "请输入账户名称", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val existing = db.assetDao().getAssetByName(name)
            val isDuplicate = when {
                assetId == -1L -> existing != null
                else -> existing != null && existing.id != assetId
            }
            if (isDuplicate) {
                withContext(Dispatchers.Main) {
                    etName.error = "该名称已被其他账户使用"
                    etName.requestFocus()
                }
                return@launch
            }

            val existingAsset = if (assetId != -1L) db.assetDao().getAssetById(assetId) else null
            val oldBalance = existingAsset?.balance ?: 0.0
            val oldName = existingAsset?.name.orEmpty()
            val oldCurrency = existingAsset?.currency ?: selectedCurrency
            val currencyChanged = assetId != -1L && !oldCurrency.equals(selectedCurrency, ignoreCase = true)

            if (currencyChanged && !balanceEditedByUser) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AddAssetActivity,
                        "修改币种后，请确认新币种下的当前余额",
                        Toast.LENGTH_SHORT
                    ).show()
                    etBalance.requestFocus()
                    etBalance.selectAll()
                }
                return@launch
            }

            if (currencyChanged && !skipCurrencyConfirm) {
                withContext(Dispatchers.Main) {
                    showCurrencyChangeConfirmDialog(oldCurrency, selectedCurrency) {
                        saveAsset(skipCurrencyConfirm = true)
                    }
                }
                return@launch
            }

            val currentBalance = BillAssetImpactService.roundMoney(balance)
            val previousBalance = BillAssetImpactService.roundMoney(oldBalance)

            val asset = Asset(
                id = if (assetId == -1L) 0 else assetId,
                name = name,
                type = if (selectedType.isEmpty()) "其它" else selectedType,
                balance = currentBalance,
                initialBalance = if (assetId == -1L) currentBalance else (existingAsset?.initialBalance ?: 0.0),
                currency = selectedCurrency,
                icon = AssetIconDefaults.withDefault(selectedIcon),
                remark = etRemark.text.toString(),
                includeInNetAsset = swIncludeNet.isChecked,
                assetCategory = selectedAssetCategory,
                sortOrder = if (assetId == -1L) 0 else originalSortOrder,
                pickerSortOrder = if (assetId == -1L) 0 else originalPickerSortOrder
            )

            if (assetId == -1L) {
                persistAssetChange(
                    pending = PendingAssetSave(
                        asset = asset,
                        oldName = "",
                        oldBalance = 0.0,
                        oldCurrency = selectedCurrency,
                        isNew = true
                    ),
                    adjustment = null
                )
                finish()
            } else {
                if (currencyChanged || abs(currentBalance - previousBalance) > 0.000001) {
                    pendingAssetSave = PendingAssetSave(
                        asset = asset,
                        oldName = oldName,
                        oldBalance = previousBalance,
                        oldCurrency = oldCurrency,
                        isNew = false
                    )
                    val intent = Intent(this@AddAssetActivity, BalanceAdjustmentActivity::class.java)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_ASSET_ID, assetId)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_OLD_BALANCE, previousBalance)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_NEW_BALANCE, currentBalance)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_ASSET_NAME, name)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_OLD_CURRENCY, oldCurrency)
                    intent.putExtra(BalanceAdjustmentActivity.EXTRA_CURRENCY, selectedCurrency)
                    balanceAdjustmentLauncher.launch(intent)
                } else {
                    persistAssetChange(
                        pending = PendingAssetSave(
                            asset = asset,
                            oldName = oldName,
                            oldBalance = previousBalance,
                            oldCurrency = oldCurrency,
                            isNew = false
                        ),
                        adjustment = null
                    )
                    finish()
                }
            }
        }
    }

    private suspend fun persistAssetChange(
        pending: PendingAssetSave,
        adjustment: AdjustmentDraft?
    ) {
        db.withTransaction {
            val savedAssetId = if (pending.isNew) {
                val maxOrder = db.assetDao().getMaxSortOrderInCategory(pending.asset.assetCategory) ?: 0
                val maxPickerOrder = db.assetDao().getMaxPickerSortOrder() ?: 0
                db.assetDao().insertAsset(
                    pending.asset.copy(
                        sortOrder = maxOrder + 10,
                        pickerSortOrder = maxPickerOrder + 10
                    )
                )
            } else {
                db.assetDao().updateAsset(pending.asset)
                if (pending.oldName.isNotEmpty() && pending.oldName != pending.asset.name) {
                    db.billDao().bindAccountIdByLegacyName(pending.asset.id, pending.oldName)
                    db.billDao().bindToAccountIdByLegacyName(pending.asset.id, pending.oldName)
                    db.billDao().syncAccountNameByAssetId(pending.asset.id, pending.asset.name)
                    db.billDao().syncToAccountNameByAssetId(pending.asset.id, pending.asset.name)
                }
                pending.asset.id
            }

            if (adjustment != null) {
                val currencyChanged = !pending.oldCurrency.equals(pending.asset.currency, ignoreCase = true)
                val diff = BillAssetImpactService.roundMoney(pending.asset.balance - pending.oldBalance)
                val bill = Bill(
                    type = if (currencyChanged) Bill.TYPE_EXPENSE else if (diff >= 0) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE,
                    subType = if (currencyChanged || !adjustment.includeInStats) {
                        Bill.SUBTYPE_BALANCE_ADJUSTMENT_EXCLUDED
                    } else {
                        Bill.SUBTYPE_BALANCE_ADJUSTMENT
                    },
                    amount = BillAssetImpactService.roundMoney(
                        if (currencyChanged) abs(pending.asset.balance) else abs(diff)
                    ),
                    currency = pending.asset.currency,
                    accountId = savedAssetId,
                    accountName = pending.asset.name,
                    time = System.currentTimeMillis(),
                    remark = adjustment.remark,
                    categoryName = adjustment.categoryName.ifBlank { "其它" },
                    bookName = BookAccountManager.getSelectedBook(this@AddAssetActivity),
                )
                BillMutationService.insertBillAndApplyImpact(
                    db = db,
                    bill = bill,
                    applyAssetImpact = false
                )
            }
        }
    }

    private fun showCurrencyDialog() {
        val currencies = CurrencyManager.getEnabledCurrencies(this)
        if (currencies.isEmpty()) return

        val selected = selectedCurrency.trim().uppercase(Locale.ROOT)
        val selectedIndex = currencies.indexOfFirst { it.trim().uppercase(Locale.ROOT) == selected }
            .takeIf { it >= 0 } ?: 0

        val options = currencies.map { code ->
            val normalized = code.trim().uppercase(Locale.ROOT)
            val info = CurrencyData.getInfo(normalized)
            if (info != null) {
                DialogOption(
                    title = "${info.symbol} ${info.code}",
                    subtitle = "${info.nameZh} (${info.countryZh})"
                )
            } else {
                DialogOption(title = normalized)
            }
        }

        showOptionPickerDialog(
            title = "选择币种",
            options = options,
            selectedIndex = selectedIndex,
            widthRatio = 0.9f
        ) { which ->
            selectedCurrency = currencies[which]
            refreshCurrencyDisplay()
        }
    }

    private fun refreshCurrencyDisplay() {
        tvCurrency.text = formatCurrencyDisplay(selectedCurrency)
    }

    private fun formatCurrencyDisplay(code: String): String {
        val normalized = code.trim().uppercase(Locale.ROOT)
        if (normalized.isBlank()) return code

        val info = CurrencyData.getInfo(normalized)
        val symbol = info?.symbol?.takeIf { it.isNotBlank() } ?: normalized
        return "$symbol $normalized"
    }

    private fun showAssetCategoryDialog() {
        val categories = arrayOf(
            Asset.CATEGORY_FUND,
            Asset.CATEGORY_CREDIT_CARD,
            Asset.CATEGORY_RECHARGE,
            Asset.CATEGORY_INVESTMENT
        )
        val options = listOf(
            DialogOption(title = "资金（普通账户）", subtitle = "用于现金、储蓄、借记卡等日常账户"),
            DialogOption(title = "信用卡", subtitle = "用于记录信用消费与还款"),
            DialogOption(title = "充值账户", subtitle = "用于余额钱包、礼品卡、平台储值"),
            DialogOption(title = "投资理财", subtitle = "用于基金、股票等投资类账户")
        )
        val currentIndex = categories.indexOf(selectedAssetCategory).takeIf { it >= 0 } ?: 0
        showOptionPickerDialog(
            title = "选择资产类别",
            options = options,
            selectedIndex = currentIndex,
            widthRatio = 0.9f
        ) { which ->
            selectedAssetCategory = categories[which]
            updateAssetCategoryUI()
        }
    }

    private fun updateAssetCategoryUI() {
        tvAssetCategory.text = Asset.categoryLabel(selectedAssetCategory)
        tvAssetCategory.setTextColor(
            when (selectedAssetCategory) {
                Asset.CATEGORY_CREDIT_CARD -> Color.parseColor("#F44336")
                Asset.CATEGORY_INVESTMENT -> Color.parseColor("#FF9800")
                Asset.CATEGORY_RECHARGE -> Color.parseColor("#9C27B0")
                else -> Color.parseColor("#2196F3")
            }
        )
    }

    private fun loadAssetsJson(): List<BuiltInCategory> {
        val list = mutableListOf<BuiltInCategory>()
        try {
            val inputStream = resources.openRawResource(R.raw.assets)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonStr = reader.use { it.readText() }
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BuiltInCategory(
                        name = obj.getString("name"),
                        icon = obj.getString("icon"),
                        type = obj.optString("type")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun parseLocalizedAmount(raw: String): Double {
        val value = raw.trim()
        if (value.isEmpty() || value == "-") return 0.0

        val localized = NumberFormat.getNumberInstance(Locale.getDefault())
        val parsePosition = ParsePosition(0)
        localized.parse(value, parsePosition)?.toDouble()
            ?.takeIf { parsePosition.index == value.length }
            ?.let { return it }

        val normalized = value
            .replace("\\s".toRegex(), "")
            .replace(',', '.')

        return normalized.toDoubleOrNull() ?: 0.0
    }

    private fun showCurrencyChangeConfirmDialog(
        oldCurrency: String,
        newCurrency: String,
        onConfirm: () -> Unit
    ) {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_FlipAccounting)
        val dialog = AlertDialog.Builder(themeContext)
            .setTitle("确认修改币种")
            .setMessage(
                "确定要把这个资产的币种从 $oldCurrency 改成 $newCurrency 吗？\n\n" +
                    "这会按当前汇率同步换算当前余额，并更新该资产关联历史账单的币种与统计结果。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("确定") { _, _ -> onConfirm() }
            .create()
        OverlayDialogs.showStyledCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.88f,
            cancelOnTouchOutside = true,
            applyOverlayType = false,
            useSolidPanelBackground = true
        )
    }

    private data class DialogOption(
        val title: String,
        val subtitle: String = ""
    )

    private fun showOptionPickerDialog(
        title: String,
        options: List<DialogOption>,
        selectedIndex: Int,
        widthRatio: Float,
        onSelected: (Int) -> Unit
    ) {
        val themeContext = ContextThemeWrapper(this, R.style.Theme_FlipAccounting)
        val contentView = LayoutInflater.from(themeContext).inflate(R.layout.dialog_option_picker, null)
        contentView.findViewById<TextView>(R.id.tv_option_picker_title).text = title
        contentView.findViewById<TextView>(R.id.tv_option_picker_desc).visibility = View.GONE
        contentView.findViewById<TextView>(R.id.btn_option_picker_cancel).visibility = View.GONE

        val listView = contentView.findViewById<ListView>(R.id.lv_option_picker)
        val adapter = OptionPickerAdapter(options, selectedIndex)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.parseColor("#12000000"))
        listView.dividerHeight = 1
        adjustOptionListHeight(listView, options.size)

        val dialog = AlertDialog.Builder(themeContext)
            .setView(contentView)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (position in options.indices) {
                adapter.selectedIndex = position
                adapter.notifyDataSetChanged()
                onSelected(position)
                dialog.dismiss()
            }
        }

        OverlayDialogs.showStyledCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = widthRatio,
            cancelOnTouchOutside = true,
            applyOverlayType = false
        )
    }

    private fun adjustOptionListHeight(listView: ListView, itemCount: Int) {
        val density = resources.displayMetrics.density
        val itemHeightPx = (64f * density).toInt()
        val maxHeightPx = (320f * density).toInt()
        val minHeightPx = (136f * density).toInt()
        val targetHeight = (itemHeightPx * itemCount).coerceAtMost(maxHeightPx).coerceAtLeast(minHeightPx)
        listView.layoutParams = listView.layoutParams.apply { height = targetHeight }
    }

    private inner class OptionPickerAdapter(
        private val options: List<DialogOption>,
        var selectedIndex: Int
    ) : BaseAdapter() {
        override fun getCount(): Int = options.size

        override fun getItem(position: Int): DialogOption = options[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_dialog_option_picker, parent, false)
            val titleView = view.findViewById<TextView>(R.id.tv_option_title)
            val subtitleView = view.findViewById<TextView>(R.id.tv_option_subtitle)
            val checkView = view.findViewById<TextView>(R.id.tv_option_check)
            val riskView = view.findViewById<TextView>(R.id.tv_option_risk)
            val arrowView = view.findViewById<ImageView>(R.id.iv_option_arrow)

            val option = getItem(position)
            val isSelected = position == selectedIndex

            titleView.text = option.title
            titleView.setTextColor(if (isSelected) Color.parseColor("#1762C5") else Color.parseColor("#1F2A38"))
            subtitleView.text = option.subtitle
            subtitleView.visibility = if (option.subtitle.isBlank()) View.GONE else View.VISIBLE
            checkView.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
            riskView.visibility = View.GONE
            arrowView.visibility = View.GONE
            view.setBackgroundColor(if (isSelected) Color.parseColor("#0F2F80ED") else Color.TRANSPARENT)
            return view
        }
    }

    inner class IconPickerAdapter(
        private var icons: List<BuiltInCategory>,
        private val onSelect: (BuiltInCategory) -> Unit
    ) : RecyclerView.Adapter<IconPickerAdapter.VH>() {

        fun updateData(newData: List<BuiltInCategory>) {
            icons = newData
            notifyDataSetChanged()
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val iv: ImageView = v.findViewById(R.id.iv_asset_icon)
            val tv: TextView = v.findViewById(R.id.tv_asset_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_asset_grid, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = icons[position]
            holder.tv.text = item.name
            Glide.with(holder.itemView)
                .load(AssetIconDefaults.withDefault(item.icon))
                .transform(CircleCrop())
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .into(holder.iv)
            holder.itemView.setOnClickListener { onSelect(item) }
        }

        override fun getItemCount(): Int = icons.size
    }

    companion object {
        private const val PREFS_ASSET_UI = "asset_ui_prefs"
        private const val KEY_SKIP_NET_ASSET_TIP_SHOWN = "skip_net_asset_tip_shown"
    }
}
