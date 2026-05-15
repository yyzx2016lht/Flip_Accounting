package tao.test.tapaccounting.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.tapaccounting.CategoryIconHelper
import tao.test.tapaccounting.TapApplication
import tao.test.tapaccounting.R
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.logic.BillMutationService
import tao.test.tapaccounting.ui.dialog.AmountKeypadDialog
import tao.test.tapaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RefundActivity : AppCompatActivity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvOrigAmount: TextView
    private lateinit var tvOrigCategory: TextView
    private lateinit var tvRefundAmount: TextView
    private lateinit var tvRefundAccount: TextView
    private lateinit var tvRefundTime: TextView
    private lateinit var tvRefundCategoryInitial: TextView
    private lateinit var ivRefundCategoryIcon: ImageView
    private lateinit var layoutRefundCategoryIcon: View
    private lateinit var etRemark: EditText

    private var billId: Long = -1L
    private var editingRefundId: Long = -1L
    private var originalBill: Bill? = null
    private var editingRefund: Bill? = null
    private var selectedAccount: String = ""
    private var selectedTimeStr: String = ""
    private var refundAmount: String = "0.00"
    private var currentCategoryIcon: String = ""
    private var iconLoadJob: Job? = null

    private val pickerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val displayTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_refund)

        billId = intent.getLongExtra(BILL_ID, -1L)
        editingRefundId = intent.getLongExtra(EDITING_REFUND_ID, -1L)
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
        tvTitle = findViewById(R.id.tv_title)
        tvOrigAmount = findViewById(R.id.tv_orig_amount)
        tvOrigCategory = findViewById(R.id.tv_orig_category)
        tvRefundAmount = findViewById(R.id.tv_refund_amount)
        tvRefundAccount = findViewById(R.id.tv_refund_account)
        tvRefundTime = findViewById(R.id.tv_refund_time)
        tvRefundCategoryInitial = findViewById(R.id.tv_refund_category_initial)
        ivRefundCategoryIcon = findViewById(R.id.iv_refund_category_icon)
        layoutRefundCategoryIcon = findViewById(R.id.layout_refund_category_icon)
        etRemark = findViewById(R.id.et_remark)

        selectedTimeStr = displayTimeFormat.format(Date())
        tvRefundTime.text = selectedTimeStr
    }

    private fun setupListeners() {
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btn_save).setOnClickListener { saveRefund() }
        findViewById<View>(R.id.layout_refund_amount).setOnClickListener { showAmountKeypad() }
        findViewById<View>(R.id.layout_refund_account).setOnClickListener { showAssetPicker() }
        findViewById<View>(R.id.layout_refund_time).setOnClickListener { showTimePicker() }
    }

    private fun loadBillData() {
        val app = application as TapApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val sourceBill = app.billRepository.getBillById(billId)
            val existingRefund = if (editingRefundId > 0L) app.billRepository.getBillById(editingRefundId) else null
            withContext(Dispatchers.Main) {
                if (sourceBill == null) {
                    Toast.makeText(this@RefundActivity, "账单不存在", Toast.LENGTH_SHORT).show()
                    finish()
                    return@withContext
                }
                originalBill = sourceBill
                editingRefund = existingRefund
                displayBillData(sourceBill, existingRefund)
            }
        }
    }

    private fun displayBillData(sourceBill: Bill, existingRefund: Bill?) {
        tvTitle.text = if (existingRefund == null) "退款" else "编辑退款"
        tvOrigCategory.text = sourceBill.categoryName.ifBlank { "未分类" }
        tvOrigAmount.text = "原支出 ${formatMoney(sourceBill.amount)}"
        tvRefundCategoryInitial.text = buildInitial(tvOrigCategory.text.toString())

        refundAmount = String.format(Locale.getDefault(), "%.2f", existingRefund?.amount ?: sourceBill.amount)
        tvRefundAmount.text = "+${formatMoney(refundAmount.toDoubleOrNull() ?: 0.0)}"

        selectedAccount = existingRefund?.accountName ?: sourceBill.accountName
        tvRefundAccount.text = selectedAccount.ifBlank { "选择账户" }

        val existingTime = existingRefund?.time
        selectedTimeStr = if (existingTime != null) {
            displayTimeFormat.format(Date(existingTime))
        } else {
            displayTimeFormat.format(Date())
        }
        tvRefundTime.text = selectedTimeStr

        etRemark.setText(
            existingRefund?.remark ?: "退款：${sourceBill.categoryName}"
        )

        loadCategoryIcon(sourceBill)
    }

    private fun showAmountKeypad() {
        AmountKeypadDialog.show(this, refundAmount) { result ->
            refundAmount = result
            val amount = result.toDoubleOrNull() ?: 0.0
            tvRefundAmount.text = "+${formatMoney(amount)}"
        }
    }

    private fun showAssetPicker() {
        OverlayDialogs.showGridAssetPicker(this, selectedAccount, "选择退款入账账户") { selected ->
            selectedAccount = selected
            tvRefundAccount.text = selected.ifBlank { "选择账户" }
        }
    }

    private fun showTimePicker() {
        val initialTimeMillis = runCatching {
            pickerFormat.parse("$selectedTimeStr:00")?.time
        }.getOrNull()
        OverlayDialogs.showCustomTimePicker(this, initialTimeMillis = initialTimeMillis) { timeStr ->
            val parsed = runCatching { pickerFormat.parse(timeStr) }.getOrNull()
            if (parsed != null) {
                selectedTimeStr = displayTimeFormat.format(parsed)
                tvRefundTime.text = selectedTimeStr
            }
        }
    }

    private fun saveRefund() {
        val app = application as TapApplication
        val sourceBill = originalBill ?: return

        val amount = refundAmount.toDoubleOrNull() ?: 0.0
        if (amount <= 0) {
            Toast.makeText(this, "退款金额必须大于0", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedAccount.isBlank() || selectedAccount == "选择账户") {
            Toast.makeText(this, "请选择退款入账账户", Toast.LENGTH_SHORT).show()
            return
        }

        val remark = etRemark.text.toString().trim().ifBlank { "退款：${sourceBill.categoryName}" }
        val refundTimeLong = runCatching {
            pickerFormat.parse("$selectedTimeStr:00")?.time ?: System.currentTimeMillis()
        }.getOrDefault(System.currentTimeMillis())

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val db = app.database
                val account = db.assetDao().getAssetByName(selectedAccount)
                val refundBill = Bill(
                    id = editingRefund?.id ?: 0L,
                    amount = amount,
                    originalAmount = amount,
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_REFUND,
                    accountId = account?.id ?: editingRefund?.accountId,
                    accountName = selectedAccount,
                    categoryName = sourceBill.categoryName,
                    time = refundTimeLong,
                    remark = remark,
                    currency = sourceBill.currency
                )

                BillMutationService.saveRefundBill(
                    db = db,
                    originalBill = sourceBill,
                    refundBill = refundBill,
                    previousRefundBill = editingRefund
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RefundActivity, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }
            } catch (e: IllegalArgumentException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RefundActivity, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                }
            } catch (e: IllegalStateException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RefundActivity, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RefundActivity, "退款失败，请稍后重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadCategoryIcon(sourceBill: Bill) {
        iconLoadJob?.cancel()
        currentCategoryIcon = ""
        renderCategoryIcon()
        iconLoadJob = lifecycleScope.launch(Dispatchers.IO) {
            val icon = CategoryIconHelper.findCategoryIcon(this@RefundActivity, sourceBill.categoryName, Bill.TYPE_EXPENSE)
            withContext(Dispatchers.Main) {
                currentCategoryIcon = icon
                renderCategoryIcon()
            }
        }
    }

    private fun renderCategoryIcon() {
        layoutRefundCategoryIcon.setBackgroundResource(R.drawable.bg_circle_income_soft)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ivRefundCategoryIcon.imageTintList = null
        }
        ivRefundCategoryIcon.setColorFilter(Color.parseColor("#2E7D32"))
        tvRefundCategoryInitial.setTextColor(Color.parseColor("#2E7D32"))
        if (currentCategoryIcon.isBlank()) {
            ivRefundCategoryIcon.setImageDrawable(null)
            tvRefundCategoryInitial.visibility = View.VISIBLE
            return
        }
        tvRefundCategoryInitial.visibility = View.INVISIBLE
        Glide.with(this)
            .load(currentCategoryIcon)
            .into(ivRefundCategoryIcon)
    }

    private fun formatMoney(amount: Double): String {
        return "¥${String.format(Locale.getDefault(), "%.2f", amount)}"
    }

    private fun buildInitial(text: String): String {
        return text.trim().firstOrNull()?.toString() ?: "退"
    }

    companion object {
        const val BILL_ID = "BILL_ID"
        const val EDITING_REFUND_ID = "EDITING_REFUND_ID"

        fun start(context: Context, billId: Long, editingRefundId: Long = -1L) {
            val intent = Intent(context, RefundActivity::class.java)
            intent.putExtra(BILL_ID, billId)
            intent.putExtra(EDITING_REFUND_ID, editingRefundId)
            context.startActivity(intent)
        }
    }
}
