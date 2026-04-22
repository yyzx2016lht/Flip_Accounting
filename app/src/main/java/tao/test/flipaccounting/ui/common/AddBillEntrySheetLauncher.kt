package tao.test.flipaccounting.ui.common

import android.content.Intent
import android.view.KeyEvent
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import tao.test.flipaccounting.AiAssistant
import tao.test.flipaccounting.ImagePickerActivity
import tao.test.flipaccounting.Prefs
import tao.test.flipaccounting.R
import tao.test.flipaccounting.logic.AccountingFormController
import tao.test.flipaccounting.logic.VoiceInputHandler

object AddBillEntrySheetLauncher {

    fun show(
        activity: AppCompatActivity,
        prefillData: JSONObject? = null,
        onShow: (() -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val bottomSheet = BottomSheetDialog(activity)
        val view = activity.layoutInflater.inflate(R.layout.layout_floating_window, null)

        val aiAssistant = AiAssistant(activity)
        var sheetMultiBillMode = false

        val formController = AccountingFormController(
            ctx = activity,
            rootView = view,
            onCloseRequest = { _ -> bottomSheet.dismiss() }
        )
        prefillData?.let { formController.fillDataToUi(it, showToast = false) }

        val handleAiResult: (JSONObject) -> Unit = { resultJson ->
            val isMulti = sheetMultiBillMode
            if (isMulti && resultJson.has("bills")) {
                formController.fillDataToUi(resultJson, showToast = true, forceMultiMode = true)
                val firstBill = resultJson.getJSONArray("bills").optJSONObject(0)
                if (firstBill != null) formController.setCurrency(firstBill.optString("currency", "CNY"))
            } else {
                formController.fillDataToUi(resultJson, showToast = true)
                formController.setCurrency(resultJson.optString("currency", "CNY"))
            }
        }

        val rgBillMode = view.findViewById<RadioGroup?>(R.id.rg_bill_mode)
        rgBillMode?.setOnCheckedChangeListener { _, checkedId ->
            sheetMultiBillMode = checkedId == R.id.rb_multi
        }

        val voiceHandler = VoiceInputHandler(activity, aiAssistant, { sheetMultiBillMode }, handleAiResult)
        aiAssistant.voiceInputBtnSetup = { btn ->
            voiceHandler.setupVoiceButton(btn)
        }
        voiceHandler.setupVoiceButton(formController.btnVoice)

        val btnAiImage = view.findViewById<ImageView?>(R.id.btn_ai_image)
        if (Prefs.isShowAiImage(activity)) {
            btnAiImage?.visibility = android.view.View.VISIBLE
            btnAiImage?.setOnClickListener {
                sheetMultiBillMode = true
                view.findViewById<RadioButton?>(R.id.rb_multi)?.isChecked = true

                ImagePickerActivity.onImagePicked = { uri ->
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                    aiAssistant.analyzeImage(uri, handleAiResult)
                }
                ImagePickerActivity.onPickCancelled = {
                    ImagePickerActivity.onImagePicked = null
                    ImagePickerActivity.onPickCancelled = null
                }
                activity.startActivity(Intent(activity, ImagePickerActivity::class.java))
            }
        } else {
            btnAiImage?.visibility = android.view.View.GONE
        }

        formController.layoutAiTextEntry.setOnClickListener {
            aiAssistant.showInputPanel(
                isMultiMode = sheetMultiBillMode,
                onResult = handleAiResult
            )
        }

        bottomSheet.setOnShowListener { onShow?.invoke() }
        bottomSheet.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                formController.handleBackPressed()
            } else {
                false
            }
        }
        bottomSheet.setOnDismissListener {
            voiceHandler.release()
            onDismiss?.invoke()
        }
        bottomSheet.setContentView(view)
        bottomSheet.show()
    }
}

