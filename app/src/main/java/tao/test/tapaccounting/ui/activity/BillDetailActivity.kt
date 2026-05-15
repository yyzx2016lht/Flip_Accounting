package tao.test.tapaccounting.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.BookAccountManager
import tao.test.tapaccounting.CategoryIconHelper
import tao.test.tapaccounting.TapApplication
import tao.test.tapaccounting.Prefs
import tao.test.tapaccounting.R
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.logic.BillDeleteHelper
import tao.test.tapaccounting.ui.dialog.AmountKeypadDialog
import tao.test.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class BillDetailActivity : AppCompatActivity() {

    private lateinit var tvCategory: TextView
    private lateinit var tvCategoryInitial: TextView
    private lateinit var ivCategoryIcon: ImageView
    private lateinit var layoutCategoryIcon: View
    private lateinit var tvAmount: TextView
    private lateinit var tvBillDate: TextView
    private lateinit var tvAsset: TextView
    private lateinit var tvBook: TextView
    private lateinit var tvRecordTime: TextView
    private lateinit var etRemark: EditText
    private lateinit var switchExcludeStats: SwitchCompat
    private lateinit var btnSave: TextView
    private lateinit var btnTypePrimary: TextView
    private lateinit var btnTypeSecondary: TextView
    private lateinit var btnRefund: TextView
    private lateinit var layoutAsset: LinearLayout
    private lateinit var dividerAssetTop: View
    private lateinit var dividerBookTop: View

    private var billId: Long = -1L
    private var bill: Bill? = null

    private var currentUiType: Int = Bill.TYPE_EXPENSE
    private var currentCategoryName: String = ""
    private var currentCategoryIcon: String = ""
    private var currentAmountText: String = "0.00"
    private var currentAssetName: String = ""
    private var currentBookName: String = ""
    private var currentExcludeFromStats: Boolean = false
    private var currentBillTimeMillis: Long = System.currentTimeMillis()
    private var originalRecordedTimeMillis: Long = System.currentTimeMillis()

    private var iconLoadJob: Job? = null

    private val detailPickerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val billDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val recordTimeFormat = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bill_detail)

        billId = intent.getLongExtra(BILL_ID, -1L)
        if (billId == -1L) {
            Toast.makeText(this, "账单不存在", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initViews()
        setupListeners()
        loadBillData()
    }

    private fun initViews() {
        tvCategory = findViewById(R.id.tv_category)
        tvCategoryInitial = findViewById(R.id.tv_category_initial)
        ivCategoryIcon = findViewById(R.id.iv_category_icon)
        layoutCategoryIcon = findViewById(R.id.layout_category_icon)
        tvAmount = findViewById(R.id.tv_amount)
        tvBillDate = findViewById(R.id.tv_bill_date)
        tvAsset = findViewById(R.id.tv_asset)
        tvBook = findViewById(R.id.tv_book)
        tvRecordTime = findViewById(R.id.tv_record_time)
        etRemark = findViewById(R.id.et_remark)
        switchExcludeStats = findViewById(R.id.switch_exclude_stats)
        btnSave = findViewById(R.id.btn_save)
        btnTypePrimary = findViewById(R.id.btn_type_primary)
        btnTypeSecondary = findViewById(R.id.btn_type_secondary)
        btnRefund = findViewById(R.id.btn_refund)
        layoutAsset = findViewById(R.id.layout_asset)
        dividerAssetTop = findViewById(R.id.divider_asset_top)
        dividerBookTop = findViewById(R.id.divider_book_top)

        val assetEnabled = Prefs.isAssetFeatureEnabled(this)
        layoutAsset.visibility = if (assetEnabled) View.VISIBLE else View.GONE
        dividerAssetTop.visibility = if (assetEnabled) View.VISIBLE else View.GONE
        dividerBookTop.visibility = View.VISIBLE
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        btnSave.setOnClickListener { saveBill() }

        findViewById<View>(R.id.layout_category).setOnClickListener { showCategoryPicker() }
        findViewById<View>(R.id.layout_amount).setOnClickListener { showAmountKeypad() }
        findViewById<View>(R.id.layout_bill_date).setOnClickListener { showBillDatePicker() }
        findViewById<View>(R.id.layout_book).setOnClickListener { showBookPicker() }
        layoutAsset.setOnClickListener { showAssetPicker() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { showDeleteConfirmation() }
        btnRefund.setOnClickListener { openRefundPage() }

        switchExcludeStats.setOnCheckedChangeListener { _, isChecked ->
            currentExcludeFromStats = isChecked
        }

        btnTypePrimary.setOnClickListener { onTypeChipClicked(isPrimary = true) }
        btnTypeSecondary.setOnClickListener { onTypeChipClicked(isPrimary = false) }
    }

    private fun loadBillData() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val loadedBill = app.billRepository.getBillById(billId)
            withContext(Dispatchers.Main) {
                if (loadedBill == null) {
                    Toast.makeText(this@BillDetailActivity, "账单不存在", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                bindBill(loadedBill)
            }
        }
    }

    private fun bindBill(loadedBill: Bill) {
        bill = loadedBill
        currentUiType = resolveUiType(loadedBill)
        currentAmountText = formatEditableAmount(loadedBill.amount)
        currentBillTimeMillis = loadedBill.time
        originalRecordedTimeMillis = loadedBill.time
        currentAssetName = loadedBill.accountName
        currentBookName = loadedBill.bookName.ifBlank { "默认账本" }
        currentExcludeFromStats = loadedBill.excludeFromStats
        currentCategoryName = resolveDisplayedCategory(loadedBill)
        currentCategoryIcon = ""

        etRemark.setText(loadedBill.remark)
        switchExcludeStats.isChecked = loadedBill.excludeFromStats

        renderBillState()
        loadCategoryIcon()
    }

    private fun renderBillState() {
        tvCategory.text = currentCategoryName.ifBlank { if (isTransferFamily(currentUiType)) getTypeDisplayName(currentUiType) else "请选择分类" }
        tvCategoryInitial.text = buildCategoryInitial(tvCategory.text.toString())
        tvAmount.text = formatAmountDisplay(currentAmountText.toDoubleOrNull() ?: 0.0, currentUiType)
        tvAmount.setTextColor(resolveAmountColor(currentUiType))
        tvBillDate.text = billDateFormat.format(Date(currentBillTimeMillis))
        tvRecordTime.text = recordTimeFormat.format(Date(originalRecordedTimeMillis))
        tvAsset.text = currentAssetName.ifBlank { "未选择" }
        tvBook.text = currentBookName.ifBlank { "默认账本" }
        btnRefund.visibility = if (shouldShowRefundButton()) View.VISIBLE else View.GONE
        renderTypeSelector()
        renderCategoryIcon()
    }

    private fun renderTypeSelector() {
        val primaryType = if (isTransferFamily(currentUiType)) Bill.TYPE_TRANSFER else Bill.TYPE_EXPENSE
        val secondaryType = if (isTransferFamily(currentUiType)) Bill.TYPE_REPAYMENT else Bill.TYPE_INCOME

        btnTypePrimary.text = getTypeDisplayName(primaryType)
        btnTypeSecondary.text = getTypeDisplayName(secondaryType)

        val primarySelected = currentUiType == primaryType
        applyTypeChipStyle(btnTypePrimary, primarySelected)
        applyTypeChipStyle(btnTypeSecondary, !primarySelected)
    }

    private fun applyTypeChipStyle(view: TextView, selected: Boolean) {
        view.setBackgroundResource(if (selected) R.drawable.bg_bill_detail_type_selected else android.R.color.transparent)
        view.setTextColor(if (selected) Color.WHITE else Color.parseColor("#6E737D"))
    }

    private fun onTypeChipClicked(isPrimary: Boolean) {
        val targetType = if (isTransferFamily(currentUiType)) {
            if (isPrimary) Bill.TYPE_TRANSFER else Bill.TYPE_REPAYMENT
        } else {
            if (isPrimary) Bill.TYPE_EXPENSE else Bill.TYPE_INCOME
        }
        if (currentUiType == targetType) return

        currentUiType = targetType
        if (isTransferFamily(currentUiType)) {
            currentCategoryName = getTypeDisplayName(currentUiType)
        }
        renderBillState()
        loadCategoryIcon()
    }

    private fun showCategoryPicker() {
        if (isTransferFamily(currentUiType)) {
            Toast.makeText(this, "转账和还款的分类跟随账单属性", Toast.LENGTH_SHORT).show()
            return
        }
        val pickerType = if (currentUiType == Bill.TYPE_INCOME) Prefs.TYPE_INCOME else Prefs.TYPE_EXPENSE
        OverlayDialogs.showGridCategoryPicker(this, currentCategoryName, pickerType) { selectedCategory ->
            currentCategoryName = selectedCategory
            tvCategory.text = selectedCategory.ifBlank { "请选择分类" }
            tvCategoryInitial.text = buildCategoryInitial(tvCategory.text.toString())
            loadCategoryIcon()
        }
    }

    private fun showAmountKeypad() {
        AmountKeypadDialog.show(this, currentAmountText) { result ->
            currentAmountText = result
            tvAmount.text = formatAmountDisplay(result.toDoubleOrNull() ?: 0.0, currentUiType)
            tvAmount.setTextColor(resolveAmountColor(currentUiType))
        }
    }

    private fun showBillDatePicker() {
        OverlayDialogs.showCustomTimePicker(this, currentBillTimeMillis) { timeString ->
            val parsed = runCatching { detailPickerFormat.parse(timeString)?.time }.getOrNull()
            if (parsed != null) {
                currentBillTimeMillis = parsed
                tvBillDate.text = billDateFormat.format(Date(currentBillTimeMillis))
            }
        }
    }

    private fun showAssetPicker() {
        if (!Prefs.isAssetFeatureEnabled(this)) return
        OverlayDialogs.showGridAssetPicker(this, currentAssetName, "选择资产") { selectedAsset ->
            currentAssetName = selectedAsset
            tvAsset.text = selectedAsset.ifBlank { "未选择" }
        }
    }

    private fun showBookPicker() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val dbBooks = app.database.billDao().getAllBookNames()
            val books = BookAccountManager.getBookAccounts(this@BillDetailActivity, dbBooks)
            withContext(Dispatchers.Main) {
                OverlayDialogs.showBookPickerDialog(
                    this@BillDetailActivity,
                    books = books,
                    currentBook = currentBookName
                ) { selectedBook ->
                    currentBookName = selectedBook
                    tvBook.text = selectedBook
                }
            }
        }
    }

    private fun showDeleteConfirmation() {
        showCustomConfirmDialog(
            title = "确认删除",
            message = "删除后可在回收站恢复，是否继续？",
            confirmText = "确认删除",
            isDanger = true,
            onConfirm = { deleteBill() }
        )
    }

    private fun deleteBill() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val currentBill = bill ?: return@launch
            runCatching { BillDeleteHelper.deleteBillAndRevertBalance(app.database, currentBill) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillDetailActivity, "账单已移入回收站", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@BillDetailActivity, "删除失败，请稍后重试", Toast.LENGTH_SHORT).show()
                    }
                }
        }
    }

    private fun saveBill() {
        val originalBill = bill ?: return
        val amount = currentAmountText.toDoubleOrNull() ?: 0.0
        if (amount <= 0.0) {
            Toast.makeText(this, "请输入有效金额", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isTransferFamily(currentUiType) && currentCategoryName.isBlank()) {
            Toast.makeText(this, "请选择分类", Toast.LENGTH_SHORT).show()
            return
        }

        val remark = etRemark.text.toString().trim()
        val app = application as TapApplication

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assetEnabled = Prefs.isAssetFeatureEnabled(this@BillDetailActivity)
                val persistedAssetName = if (assetEnabled) currentAssetName else originalBill.accountName
                val persistedAccountId = if (assetEnabled && persistedAssetName.isNotBlank()) {
                    app.database.assetDao().getAssetByName(persistedAssetName)?.id
                } else {
                    originalBill.accountId
                }

                val updatedBill = originalBill.copy(
                    type = persistedType(currentUiType),
                    subType = persistedSubType(originalBill, currentUiType),
                    amount = amount,
                    categoryName = persistedCategoryName(currentUiType, currentCategoryName, originalBill.categoryName),
                    accountName = persistedAssetName,
                    accountId = persistedAccountId,
                    bookName = currentBookName.ifBlank { originalBill.bookName },
                    remark = remark,
                    excludeFromStats = currentExcludeFromStats,
                    time = currentBillTimeMillis
                )

                app.billRepository.updateBill(updatedBill)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillDetailActivity, "保存成功", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (error: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@BillDetailActivity, "保存失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCategoryIcon() {
        iconLoadJob?.cancel()
        currentCategoryIcon = ""
        renderCategoryIcon()
        iconLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val lookupName = currentCategoryName.ifBlank { getTypeDisplayName(currentUiType) }
            val lookupType = if (currentUiType == Bill.TYPE_INCOME) Bill.TYPE_INCOME else Bill.TYPE_EXPENSE
            val icon = when {
                currentUiType == Bill.TYPE_TRANSFER || currentUiType == Bill.TYPE_REPAYMENT ->
                    "android.resource://$packageName/${R.drawable.ic_transfer}"
                lookupName.isBlank() -> ""
                else -> CategoryIconHelper.findCategoryIcon(this@BillDetailActivity, lookupName, lookupType)
            }
            withContext(Dispatchers.Main) {
                currentCategoryIcon = icon
                renderCategoryIcon()
            }
        }
    }

    private fun renderCategoryIcon() {
        layoutCategoryIcon.setBackgroundResource(
            when (currentUiType) {
                Bill.TYPE_EXPENSE -> R.drawable.bg_circle_expense_soft
                Bill.TYPE_INCOME -> R.drawable.bg_circle_income_soft
                else -> R.drawable.bg_circle_soft
            }
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ivCategoryIcon.imageTintList = null
        }
        ivCategoryIcon.setColorFilter(resolveIconTintColor(currentUiType))
        tvCategoryInitial.setTextColor(
            when (currentUiType) {
                Bill.TYPE_EXPENSE -> Color.parseColor("#D32F2F")
                Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
                else -> Color.parseColor("#6F7A8A")
            }
        )
        if (currentCategoryIcon.isBlank()) {
            ivCategoryIcon.setImageDrawable(null)
            tvCategoryInitial.visibility = View.VISIBLE
            return
        }
        tvCategoryInitial.visibility = View.INVISIBLE
        Glide.with(this)
            .load(currentCategoryIcon)
            .into(ivCategoryIcon)
    }

    private fun resolveUiType(bill: Bill): Int {
        return when {
            bill.type == Bill.TYPE_TRANSFER && bill.subType == Bill.SUBTYPE_REPAYMENT -> Bill.TYPE_REPAYMENT
            bill.type == Bill.TYPE_REPAYMENT -> Bill.TYPE_REPAYMENT
            else -> bill.type
        }
    }

    private fun resolveDisplayedCategory(bill: Bill): String {
        return if (isTransferFamily(resolveUiType(bill))) {
            getTypeDisplayName(resolveUiType(bill))
        } else {
            bill.categoryName
        }
    }

    private fun persistedType(uiType: Int): Int {
        return if (uiType == Bill.TYPE_REPAYMENT) Bill.TYPE_TRANSFER else uiType
    }

    private fun persistedSubType(originalBill: Bill, uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_REPAYMENT -> Bill.SUBTYPE_REPAYMENT
            Bill.TYPE_TRANSFER -> if (originalBill.subType == Bill.SUBTYPE_REPAYMENT) Bill.SUBTYPE_NORMAL else originalBill.subType
            else -> if (originalBill.subType == Bill.SUBTYPE_REPAYMENT) Bill.SUBTYPE_NORMAL else originalBill.subType
        }
    }

    private fun persistedCategoryName(uiType: Int, currentCategory: String, fallbackCategory: String): String {
        return when (uiType) {
            Bill.TYPE_TRANSFER -> "转账"
            Bill.TYPE_REPAYMENT -> "还款"
            else -> currentCategory.ifBlank { fallbackCategory }
        }
    }

    private fun isTransferFamily(type: Int): Boolean {
        return type == Bill.TYPE_TRANSFER || type == Bill.TYPE_REPAYMENT
    }

    private fun getTypeDisplayName(type: Int): String {
        return when (type) {
            Bill.TYPE_EXPENSE -> "支出"
            Bill.TYPE_INCOME -> "收入"
            Bill.TYPE_TRANSFER -> "转账"
            Bill.TYPE_REPAYMENT -> "还款"
            else -> "支出"
        }
    }

    private fun formatAmountDisplay(amount: Double, uiType: Int): String {
        val symbol = "¥"
        val prefix = when (uiType) {
            Bill.TYPE_EXPENSE -> "-$symbol"
            Bill.TYPE_INCOME -> "+$symbol"
            else -> symbol
        }
        return prefix + String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun formatEditableAmount(amount: Double): String {
        return String.format(Locale.getDefault(), "%.2f", amount)
    }

    private fun resolveAmountColor(uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_EXPENSE -> Color.parseColor("#D32F2F")
            Bill.TYPE_INCOME -> Color.parseColor("#1A9B5F")
            else -> Color.parseColor("#6F7A8A")
        }
    }

    private fun resolveIconTintColor(uiType: Int): Int {
        return when (uiType) {
            Bill.TYPE_EXPENSE -> Color.parseColor("#C62828")
            Bill.TYPE_INCOME -> Color.parseColor("#2E7D32")
            else -> Color.parseColor("#7A8598")
        }
    }

    private fun buildCategoryInitial(text: String): String {
        return text.trim().firstOrNull()?.toString() ?: "分"
    }

    private fun shouldShowRefundButton(): Boolean {
        val currentBill = bill ?: return false
        return currentUiType == Bill.TYPE_EXPENSE && currentBill.subType != Bill.SUBTYPE_REFUND
    }

    private fun openRefundPage() {
        val currentBill = bill ?: return
        if (!shouldShowRefundButton()) return
        val intent = Intent(this, RefundActivity::class.java)
        intent.putExtra(RefundActivity.BILL_ID, currentBill.id)
        startActivity(intent)
    }

    private fun showCustomConfirmDialog(
        title: String,
        message: String,
        confirmText: String = "确定",
        isDanger: Boolean = false,
        onConfirm: () -> Unit
    ) {
        val panel = LayoutInflater.from(this).inflate(R.layout.dialog_delete_followup_confirm, null, false)
        panel.findViewById<TextView>(R.id.tv_followup_confirm_title).text = title
        panel.findViewById<TextView>(R.id.tv_followup_confirm_message).text = message

        val dialog = AlertDialog.Builder(ContextThemeWrapper(this, R.style.Theme_TapAccounting))
            .setView(panel)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        panel.findViewById<TextView>(R.id.btn_followup_confirm_cancel).setOnClickListener {
            dialog.dismiss()
        }
        panel.findViewById<TextView>(R.id.btn_followup_confirm_ok).apply {
            text = confirmText
            setBackgroundResource(
                if (isDanger) R.drawable.bg_delete_followup_danger_btn
                else R.drawable.bg_delete_followup_primary_btn
            )
            setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
        }

        OverlayDialogs.showPageCenterDialog(
            dialog = dialog,
            ctx = this,
            widthRatio = 0.86f,
            cancelOnTouchOutside = true,
            useSolidPanelBackground = false
        )
    }

    companion object {
        const val BILL_ID = "BILL_ID"

        fun createIntent(context: Context, billId: Long): Intent {
            return Intent(context, BillDetailActivity::class.java).apply {
                putExtra(BILL_ID, billId)
            }
        }

        fun start(context: Context, billId: Long) {
            context.startActivity(createIntent(context, billId))
        }
    }
}
