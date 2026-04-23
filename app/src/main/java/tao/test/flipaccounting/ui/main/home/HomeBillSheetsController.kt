package tao.test.flipaccounting.ui.main.home

import android.content.Intent
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tao.test.flipaccounting.BookAccountManager
import tao.test.flipaccounting.CategoryIconHelper
import tao.test.flipaccounting.R
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.logic.BillDisplayFormatter
import tao.test.flipaccounting.logic.CurrencyManager
import tao.test.flipaccounting.ui.activity.EditBillActivity
import tao.test.flipaccounting.ui.dialog.OverlayDialogs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class HomeBillSheetsController(
    private val fragment: Fragment,
    private val dfDetailTime: SimpleDateFormat,
    private val dfDetailTimeShort: SimpleDateFormat
) {
    private val layoutInflater: LayoutInflater
        get() = fragment.layoutInflater

    private fun isRefundBill(bill: Bill): Boolean = bill.subType == Bill.SUBTYPE_REFUND

    private fun fillLinkedBillRow(row: View, bill: Bill, forceGrayStyle: Boolean) {
        val tvCategory = row.findViewById<TextView>(R.id.tv_bill_category)
        val tvDetail = row.findViewById<TextView>(R.id.tv_bill_detail)
        val tvAmount = row.findViewById<TextView>(R.id.tv_bill_amount)
        val tvTime = row.findViewById<TextView>(R.id.tv_bill_time)
        val ivIcon = row.findViewById<ImageView>(R.id.iv_bill_category_icon)
        val iconContainer = row.findViewById<View?>(R.id.layout_icon_container)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        val symbol = CurrencyManager.getSymbol(bill.currency)
        val baseCategory = HomeBillFormatHelper.stripRefundPrefix(bill.categoryName)

        row.setBackgroundResource(R.drawable.bg_bill_group_single)
        iconContainer?.setBackgroundResource(R.drawable.bg_circle_soft)

        tvCategory.text = when {
            isRepayment -> "还款"
            isTransfer -> "转账"
            isRefund -> BillDisplayFormatter.buildRefundCategoryLabel(bill.categoryName)
            else -> bill.categoryName.ifEmpty { "未分类" }
        }

        val refundAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
        tvAmount.text = if (!forceGrayStyle && !isRefund && bill.type == Bill.TYPE_EXPENSE && refundAmount > 0.0) {
            BillDisplayFormatter.buildRefundedExpenseAmountText(
                netAmount = bill.amount,
                originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                currency = bill.currency
            )
        } else {
            val sign = when {
                forceGrayStyle || isRefund -> ""
                bill.type == Bill.TYPE_EXPENSE -> "-"
                bill.type == Bill.TYPE_INCOME -> "+"
                else -> ""
            }
            "$sign$symbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
        }

        if (forceGrayStyle || isRefund) {
            tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
            tvCategory.setTextColor(Color.parseColor("#8E98A3"))
            tvDetail.setTextColor(Color.parseColor("#A1A8AF"))
            tvTime.setTextColor(Color.parseColor("#A1A8AF"))
        } else {
            tvCategory.setTextColor(Color.parseColor("#333333"))
            tvDetail.setTextColor(Color.parseColor("#999999"))
            tvTime.setTextColor(Color.parseColor("#999999"))
            when (bill.type) {
                Bill.TYPE_EXPENSE -> tvAmount.setTextColor(Color.parseColor("#C62828"))
                Bill.TYPE_INCOME -> tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                else -> tvAmount.setTextColor(Color.parseColor("#757575"))
            }
        }

        val detailStr = buildString {
            if (isTransfer) {
                append(bill.accountName)
                if (bill.toAccountName.isNotEmpty()) {
                    append(" -> ")
                    append(bill.toAccountName)
                }
            } else {
                if (bill.accountName.isNotEmpty()) append(bill.accountName)
                if (!forceGrayStyle) {
                    val linkedRefundAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
                    if (linkedRefundAmount > 0.0 && bill.type == Bill.TYPE_EXPENSE) {
                        append("(退款")
                        append(symbol)
                        append(String.format(Locale.getDefault(), "%.2f", linkedRefundAmount))
                        append(")")
                    }
                }
            }
            if (bill.remark.isNotEmpty()) {
                if (isNotEmpty()) append(" | ")
                append(bill.remark)
            }
        }

        if (forceGrayStyle) {
            tvDetail.text = dfDetailTimeShort.format(Date(bill.time))
            tvDetail.visibility = View.VISIBLE
            if (bill.accountName.isNotEmpty()) {
                tvTime.text = bill.accountName
                tvTime.visibility = View.VISIBLE
            } else {
                tvTime.visibility = View.GONE
            }
        } else {
            if (detailStr.isNotEmpty()) {
                tvDetail.text = detailStr
                tvDetail.visibility = View.VISIBLE
            } else {
                tvDetail.visibility = View.GONE
            }
            tvTime.text = dfDetailTimeShort.format(Date(bill.time))
            tvTime.visibility = View.VISIBLE
        }

        val iconLookupName = if (isRefund) baseCategory else bill.categoryName
        val iconLookupType = if (isRefund) Bill.TYPE_EXPENSE else bill.type
        val iconTint = when {
            forceGrayStyle || isRefund -> Color.parseColor("#8E98A3")
            bill.type == Bill.TYPE_EXPENSE -> Color.parseColor("#C62828")
            bill.type == Bill.TYPE_INCOME -> Color.parseColor("#4CAF50")
            else -> Color.parseColor("#9E9E9E")
        }
        ivIcon.setImageResource(R.mipmap.ic_launcher)
        ivIcon.setColorFilter(iconTint)
        fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val iconUrl = CategoryIconHelper.findCategoryIcon(fragment.requireContext(), iconLookupName, iconLookupType)
            withContext(Dispatchers.Main) {
                if (iconUrl.isNotEmpty()) {
                    Glide.with(row)
                        .load(iconUrl)
                        .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.DATA)
                        .into(ivIcon)
                }
            }
        }
    }

    private fun addLinkedBillRow(container: LinearLayout, bill: Bill, forceGrayStyle: Boolean, onClick: (() -> Unit)? = null) {
        val row = layoutInflater.inflate(R.layout.item_home_transaction, container, false)
        fillLinkedBillRow(row, bill, forceGrayStyle)
        row.findViewById<View>(R.id.cb_bill_select).visibility = View.GONE
        row.setOnClickListener { onClick?.invoke() }
        container.addView(row)
    }

    private fun renderRefundRecords(view: View, sourceBill: Bill, onItemClick: (Bill) -> Unit) {
        val section = view.findViewById<LinearLayout>(R.id.layout_refund_records_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_refund_records_container)
        section.visibility = View.GONE
        container.removeAllViews()

        fragment.lifecycleScope.launch(Dispatchers.IO) {
            val refunds = AppDatabase.getDatabase(fragment.requireContext()).billDao().getRefundBillsBySourceId(sourceBill.id)
            withContext(Dispatchers.Main) {
                if (refunds.isEmpty()) {
                    section.visibility = View.GONE
                    return@withContext
                }
                section.visibility = View.VISIBLE
                refunds.forEach { refundBill ->
                    addLinkedBillRow(container, refundBill, forceGrayStyle = true) {
                        onItemClick(refundBill)
                    }
                }
            }
        }
    }

    private fun renderOriginalBill(view: View, originalBill: Bill) {
        val section = view.findViewById<LinearLayout>(R.id.layout_original_bill_section)
        val container = view.findViewById<LinearLayout>(R.id.layout_original_bill_container)
        container.removeAllViews()
        section.visibility = View.VISIBLE
        addLinkedBillRow(container, originalBill, forceGrayStyle = false) {
            showBillDetailSheet(originalBill)
        }
    }

    fun showBillDetailSheet(bill: Bill) {
        val bottomSheet = BottomSheetDialog(fragment.requireContext())
        val view = layoutInflater.inflate(R.layout.layout_bill_detail_bottom_sheet, null)

        val tvAmount = view.findViewById<TextView>(R.id.tv_detail_amount)
        val tvAmountLabel = view.findViewById<TextView>(R.id.tv_detail_amount_label)
        val tvAmountFormula = view.findViewById<TextView>(R.id.tv_detail_amount_formula)
        val layoutIncoming = view.findViewById<View>(R.id.layout_detail_incoming)
        val lineIncoming = view.findViewById<View>(R.id.line_incoming)
        val tvIncomingAmount = view.findViewById<TextView>(R.id.tv_detail_incoming_amount)
        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val layoutCategory = view.findViewById<View>(R.id.layout_detail_category)
        val lineCategory = view.findViewById<View>(R.id.line_category)
        val tvCategory = view.findViewById<TextView>(R.id.tv_detail_category)
        val tvAccountLabel = view.findViewById<TextView>(R.id.tv_detail_account_label)
        val tvAccount = view.findViewById<TextView>(R.id.tv_detail_account)
        val tvTimeLabel = view.findViewById<TextView>(R.id.tv_detail_time_label)
        val layoutFeeDetail = view.findViewById<View>(R.id.layout_detail_fee)
        val lineFeeDetail = view.findViewById<View>(R.id.line_fee_detail)
        val tvFeeDetail = view.findViewById<TextView>(R.id.tv_detail_fee)

        val btnCopy = view.findViewById<View>(R.id.btn_copy)
        val btnRefund = view.findViewById<View>(R.id.btn_refund)
        val btnEdit = view.findViewById<View>(R.id.btn_edit)
        val btnDelete = view.findViewById<View>(R.id.btn_delete)

        val isTransfer = bill.type == Bill.TYPE_TRANSFER
        val isRepayment = isTransfer && bill.subType == Bill.SUBTYPE_REPAYMENT
        val isRefund = isRefundBill(bill)
        var linkedOriginalForRefund: Bill? = null

        tvAmountFormula.visibility = View.GONE
        layoutIncoming.visibility = View.GONE
        lineIncoming.visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_refund_records_section).visibility = View.GONE
        view.findViewById<LinearLayout>(R.id.layout_original_bill_section).visibility = View.GONE

        if (isTransfer) {
            tvTitle.text = if (isRepayment) "还款详情" else "转账详情"
            tvAmount.setTextColor(Color.parseColor("#1A1A1A"))
            layoutCategory.visibility = View.GONE
            lineCategory.visibility = View.GONE
            tvAccountLabel.text = "账户"
            tvTimeLabel.text = "时间"

            if (!isRepayment && bill.fee > 0.0) {
                layoutFeeDetail.visibility = View.VISIBLE
                lineFeeDetail.visibility = View.VISIBLE
                tvFeeDetail.text = "-${HomeBillFormatHelper.formatMoney(bill.fee, bill.currency)}"
            } else {
                layoutFeeDetail.visibility = View.GONE
                lineFeeDetail.visibility = View.GONE
            }

            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                val toAsset = db.assetDao().getAssetById(bill.toAccountId ?: -1)
                val toName = toAsset?.name ?: "未知账户"
                val toAssetCurrency = toAsset?.currency ?: "CNY"
                withContext(Dispatchers.Main) {
                    tvAccount.text = "${bill.accountName} -> $toName"
                    val sourceCurrency = bill.currency
                    val isCrossCurrency = !isRepayment && sourceCurrency != toAssetCurrency && bill.exchangeRate != 1.0
                    if (isCrossCurrency) {
                        tvAmountLabel.text = "转出金额"
                        val sourceSymbol = CurrencyManager.getSymbol(sourceCurrency)
                        tvAmount.text = "$sourceSymbol${String.format(Locale.getDefault(), "%.2f", bill.amount)}"
                        val targetAmount = bill.amount * bill.exchangeRate
                        val toSymbol = CurrencyManager.getSymbol(toAssetCurrency)
                        layoutIncoming.visibility = View.VISIBLE
                        lineIncoming.visibility = View.VISIBLE
                        tvIncomingAmount.text = "$toSymbol${String.format(Locale.getDefault(), "%.2f", targetAmount)}"
                    } else {
                        tvAmountLabel.text = if (isRepayment) "还款金额" else "转账金额"
                        tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                    }
                }
            }
        } else {
            layoutFeeDetail.visibility = View.GONE
            lineFeeDetail.visibility = View.GONE
            tvTitle.text = "详情"
            tvAmountLabel.text = "金额"
            layoutCategory.visibility = View.VISIBLE
            lineCategory.visibility = View.VISIBLE
            tvCategory.text = bill.categoryName

            if (isRefund) {
                tvAmount.text = HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)
                tvAmount.setTextColor(Color.parseColor("#9AA1AA"))
                tvAccountLabel.text = "入账账户"
                tvTimeLabel.text = "入账时间"
                tvAccount.text = bill.accountName

                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val original = bill.relatedBillId?.let { AppDatabase.getDatabase(fragment.requireContext()).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (original != null) {
                            linkedOriginalForRefund = original
                            renderOriginalBill(view, original)
                        }
                    }
                }
            } else {
                tvAccountLabel.text = "账户"
                tvTimeLabel.text = "时间"
                tvAccount.text = bill.accountName

                if (bill.type == Bill.TYPE_EXPENSE) {
                    val refundedAmount = HomeBillFormatHelper.refundAmountOfExpenseBill(bill)
                    if (refundedAmount > 0.0) {
                        tvAmount.text = BillDisplayFormatter.buildRefundedExpenseAmountText(
                            netAmount = bill.amount,
                            originalAmount = BillDisplayFormatter.originalAmountOfExpenseBill(bill),
                            currency = bill.currency
                        )
                        tvAmountFormula.visibility = View.VISIBLE
                        tvAmountFormula.text =
                            "退款${HomeBillFormatHelper.formatMoney(refundedAmount, bill.currency)}，实际支出${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                        renderRefundRecords(view, bill) { refundBill -> showBillDetailSheet(refundBill) }
                    } else {
                        tvAmount.text = "-${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                        fragment.lifecycleScope.launch(Dispatchers.IO) {
                            val crossCurrencyText = HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
                            withContext(Dispatchers.Main) {
                                if (!crossCurrencyText.isNullOrBlank()) {
                                    tvAmountFormula.visibility = View.VISIBLE
                                    tvAmountFormula.text = crossCurrencyText
                                }
                            }
                        }
                    }
                    tvAmount.setTextColor(Color.parseColor("#FF3B30"))
                } else {
                    tvAmount.text = "+${HomeBillFormatHelper.formatMoney(bill.amount, bill.currency)}"
                    tvAmount.setTextColor(Color.parseColor("#4CAF50"))
                    fragment.lifecycleScope.launch(Dispatchers.IO) {
                        val crossCurrencyText = HomeBillFormatHelper.buildCrossCurrencyDetailFormula(bill, "CNY")
                        withContext(Dispatchers.Main) {
                            if (!crossCurrencyText.isNullOrBlank()) {
                                tvAmountFormula.visibility = View.VISIBLE
                                tvAmountFormula.text = crossCurrencyText
                            }
                        }
                    }
                }
            }
        }

        val timeStr = dfDetailTimeShort.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_time).text = timeStr

        val recordTimeStr = dfDetailTime.format(Date(bill.time))
        view.findViewById<TextView>(R.id.tv_detail_record_time).text = "记录于 $recordTimeStr"
        val tvRemark = view.findViewById<TextView>(R.id.tv_detail_remark)
        tvRemark.text = bill.remark.ifEmpty { "无备注" }
        view.findViewById<TextView>(R.id.tv_detail_book_name).text =
            bill.bookName.ifEmpty { BookAccountManager.getDefaultBook(fragment.requireContext()) }

        if (!isRefund && bill.type == Bill.TYPE_EXPENSE && HomeBillFormatHelper.refundAmountOfExpenseBill(bill) > 0.0) {
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val refunds = AppDatabase.getDatabase(fragment.requireContext()).billDao().getRefundBillsBySourceId(bill.id)
                withContext(Dispatchers.Main) {
                    tvRemark.text = BillDisplayFormatter.buildRefundFlowRemark(bill.remark, refunds)
                }
            }
        }

        if (isRefund) {
            btnCopy.visibility = View.GONE
            btnRefund.visibility = View.GONE
        } else if (bill.type == Bill.TYPE_INCOME || bill.type == Bill.TYPE_TRANSFER || bill.amount <= 0.0) {
            btnRefund.visibility = View.GONE
        } else {
            btnRefund.visibility = View.VISIBLE
        }

        btnCopy.setOnClickListener {
            bottomSheet.dismiss()
            val intent = Intent(fragment.requireContext(), EditBillActivity::class.java)
            intent.putExtra("BILL_ID", bill.id)
            intent.putExtra("IS_COPY", true)
            fragment.startActivity(intent)
        }

        btnRefund.setOnClickListener {
            bottomSheet.dismiss()
            showRefundSheet(bill)
        }

        btnEdit.setOnClickListener {
            bottomSheet.dismiss()
            if (isRefund) {
                val cachedOriginal = linkedOriginalForRefund
                if (cachedOriginal != null) {
                    showRefundSheet(cachedOriginal, bill)
                    return@setOnClickListener
                }
                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val source = bill.relatedBillId?.let { AppDatabase.getDatabase(fragment.requireContext()).billDao().getBillById(it) }
                    withContext(Dispatchers.Main) {
                        if (source != null) {
                            showRefundSheet(source, bill)
                        } else {
                            val intent = Intent(fragment.requireContext(), EditBillActivity::class.java)
                            intent.putExtra("BILL_ID", bill.id)
                            fragment.startActivity(intent)
                        }
                    }
                }
            } else {
                val intent = Intent(fragment.requireContext(), EditBillActivity::class.java)
                intent.putExtra("BILL_ID", bill.id)
                fragment.startActivity(intent)
            }
        }

        btnDelete.setOnClickListener {
            bottomSheet.dismiss()
            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                tao.test.flipaccounting.logic.BillDeleteHelper.deleteBillAndRevertBalance(db, bill)
                withContext(Dispatchers.Main) {
                    Toast.makeText(fragment.context, "已删除", Toast.LENGTH_SHORT).show()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureDetailBottomSheet(bottomSheet)
        bottomSheet.show()
    }

    private fun configureDetailBottomSheet(bottomSheet: BottomSheetDialog) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun configureRefundBottomSheet(bottomSheet: BottomSheetDialog, contentView: View) {
        bottomSheet.dismissWithAnimation = true
        bottomSheet.setOnShowListener { dialog ->
            val bsDialog = dialog as? BottomSheetDialog ?: return@setOnShowListener
            val bottomSheetView =
                bsDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return@setOnShowListener
            val behavior = BottomSheetBehavior.from(bottomSheetView)
            val screenHeight = fragment.resources.displayMetrics.heightPixels
            contentView.post {
                val desiredHeight = minOf(
                    contentView.height + fragment.resources.displayMetrics.density.times(24).toInt(),
                    (screenHeight * 0.88f).toInt()
                )
                bottomSheetView.layoutParams = bottomSheetView.layoutParams.apply {
                    height = desiredHeight
                }
                bottomSheetView.requestLayout()
                behavior.peekHeight = desiredHeight
            }
            behavior.isFitToContents = true
            behavior.skipCollapsed = true
            behavior.isHideable = true
            behavior.isDraggable = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    fun showRefundSheet(originalBill: Bill, editingRefund: Bill? = null) {
        val bottomSheet = BottomSheetDialog(fragment.requireContext())
        val view = layoutInflater.inflate(R.layout.layout_refund_bottom_sheet, null)

        val tvTitle = view.findViewById<TextView>(R.id.tv_title)
        val tvOrigAmount = view.findViewById<TextView>(R.id.tv_orig_amount)
        val tvOrigCategory = view.findViewById<TextView>(R.id.tv_orig_category)

        val etRefundAmount = view.findViewById<EditText>(R.id.et_refund_amount)
        val layoutRefundAccount = view.findViewById<View>(R.id.layout_refund_account)
        val tvRefundAccount = view.findViewById<TextView>(R.id.tv_refund_account)
        val layoutRefundTime = view.findViewById<View>(R.id.layout_refund_time)
        val tvRefundTime = view.findViewById<TextView>(R.id.tv_refund_time)
        val etRefundRemark = view.findViewById<EditText>(R.id.et_refund_remark)
        val btnSaveRefund = view.findViewById<View>(R.id.btn_save_refund)
        val btnBack = view.findViewById<View>(R.id.btn_back)

        tvTitle.text = if (editingRefund == null) "退款" else "编辑退款"

        val sourceOriginalAmount = HomeBillFormatHelper.originalAmountOfExpenseBill(originalBill)
        tvOrigAmount.text = HomeBillFormatHelper.formatMoney(sourceOriginalAmount, originalBill.currency)
        tvOrigCategory.text = HomeBillFormatHelper.stripRefundPrefix(originalBill.categoryName)

        val defaultRefundAmount = editingRefund?.amount ?: originalBill.amount
        etRefundAmount.setText(String.format(Locale.getDefault(), "%.2f", defaultRefundAmount))

        var selectedAccount = editingRefund?.accountName ?: originalBill.accountName
        tvRefundAccount.text = selectedAccount

        var selectedTimeStr = if (editingRefund == null) {
            dfDetailTime.format(Date())
        } else {
            dfDetailTime.format(Date(editingRefund.time))
        }
        tvRefundTime.text = selectedTimeStr

        if (editingRefund != null) {
            etRefundRemark.setText(editingRefund.remark)
        }

        btnBack?.setOnClickListener {
            bottomSheet.cancel()
        }
        bottomSheet.setOnCancelListener {
            showBillDetailSheet(editingRefund ?: originalBill)
        }

        layoutRefundAccount.setOnClickListener {
            OverlayDialogs.showGridAssetPicker(fragment.requireContext(), tvRefundAccount.text.toString(), "选择退款入账账户") { account ->
                selectedAccount = account
                tvRefundAccount.text = account
            }
        }

        layoutRefundTime.setOnClickListener {
            OverlayDialogs.showCustomTimePicker(fragment.requireContext()) { timeStr ->
                selectedTimeStr = timeStr
                tvRefundTime.text = timeStr
            }
        }

        btnSaveRefund.setOnClickListener {
            val amountStr = etRefundAmount.text.toString()
            val refundAmount = amountStr.toDoubleOrNull() ?: 0.0

            if (refundAmount <= 0) {
                Toast.makeText(fragment.context, "请输入有效的退款金额", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (selectedAccount.isEmpty() || selectedAccount == "选择账户") {
                Toast.makeText(fragment.context, "请选择入账账户", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val remark = etRefundRemark.text.toString().trim()
            val finalRemark = when {
                remark.isNotEmpty() -> remark
                editingRefund != null -> editingRefund.remark
                else -> "退款：${HomeBillFormatHelper.stripRefundPrefix(originalBill.categoryName)}"
            }

            val refundTimeLong = try {
                dfDetailTime.parse(selectedTimeStr)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }

            fragment.lifecycleScope.launch(Dispatchers.IO) {
                val db = AppDatabase.getDatabase(fragment.requireContext())
                val account = db.assetDao().getAssetByName(selectedAccount)
                val refundBill = Bill(
                    id = editingRefund?.id ?: 0,
                    amount = refundAmount,
                    originalAmount = refundAmount,
                    type = Bill.TYPE_INCOME,
                    subType = Bill.SUBTYPE_REFUND,
                    accountId = account?.id ?: editingRefund?.accountId,
                    accountName = selectedAccount,
                    categoryName = originalBill.categoryName,
                    time = refundTimeLong,
                    remark = finalRemark,
                    currency = originalBill.currency
                )

                try {
                    tao.test.flipaccounting.logic.BillMutationService.saveRefundBill(
                        db = db,
                        originalBill = originalBill,
                        refundBill = refundBill,
                        previousRefundBill = editingRefund
                    )
                } catch (_: IllegalArgumentException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(fragment.context, "退款金额不能大于剩余支出", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                } catch (_: IllegalStateException) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(fragment.context, "原账单不存在或不可退款", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(fragment.context, if (editingRefund == null) "退款已保存" else "退款已更新", Toast.LENGTH_SHORT).show()
                    bottomSheet.dismiss()
                }
            }
        }

        bottomSheet.setContentView(view)
        configureRefundBottomSheet(bottomSheet, view)
        bottomSheet.show()
    }
}
