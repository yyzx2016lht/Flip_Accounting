package tao.test.flipaccounting.ui.activity

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.flipaccounting.FlipApplication
import tao.test.flipaccounting.R
import tao.test.flipaccounting.logic.AccountingFormController
import java.text.SimpleDateFormat
import java.util.*

class EditBillActivity : AppCompatActivity() {

    private var billId: Long = -1
    private var isCopy: Boolean = false
    private var formController: AccountingFormController? = null
    private var bottomSheet: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this)
        setContentView(container)

        billId = intent.getLongExtra("BILL_ID", -1)
        isCopy = intent.getBooleanExtra("IS_COPY", false)

        showBottomSheet()
    }

    private fun showBottomSheet() {
        bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_floating_window, null)
        
        formController = AccountingFormController(
            ctx = this,
            rootView = view,
            onCloseRequest = { isSaved ->
                if (isSaved) {
                    setResult(RESULT_OK)
                }
                bottomSheet?.dismiss()
            }
        )

        if (billId != -1L) {
            loadBillData()
        }

        bottomSheet?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                formController?.handleBackPressed() == true
            } else {
                false
            }
        }

        bottomSheet?.setOnDismissListener {
            finish()
        }

        bottomSheet?.setContentView(view)
        bottomSheet?.show()
    }

    private fun loadBillData() {
        val app = application as FlipApplication
        lifecycleScope.launch(Dispatchers.IO) {
            val bill = app.billRepository.getBillById(billId)
            if (bill != null) {
                val json = JSONObject()
                json.put("amount", bill.amount)
                json.put("type", bill.type) // 0-支出, 1-收入...
                json.put("category_name", bill.categoryName)
                json.put("asset_name", bill.accountName)
                json.put("remark", bill.remark)
                json.put("currency", bill.currency)
                json.put("exchange_rate", bill.exchangeRate)
                json.put("subType", bill.subType)
                json.put("bookName", bill.bookName)
                
                // 如果有 toAccountId (转账目标)，需要查出对应名字
                if (bill.type == 2) {
                    val toAssetName = if (bill.toAccountId != null) {
                        app.assetRepository.getAssetById(bill.toAccountId)?.name
                    } else {
                        null
                    } ?: bill.toAccountName.takeIf { it.isNotBlank() }
                    if (!toAssetName.isNullOrBlank()) {
                        json.put("to_asset_name", toAssetName)
                    }
                }

                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                
                if (isCopy) {
                    json.put("time", dateFormat.format(Date()))
                } else {
                    json.put("time", dateFormat.format(Date(bill.time)))
                    json.put("recordTime", bill.id.toString()) 
                }

                withContext(Dispatchers.Main) {
                    formController?.fillDataToUi(json, showToast = false)
                }
            }
        }
    }
}
