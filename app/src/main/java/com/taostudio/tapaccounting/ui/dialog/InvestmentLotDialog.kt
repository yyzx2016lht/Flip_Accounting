package com.taostudio.tapaccounting.ui.dialog

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.InvestmentLot
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.InvestmentInterestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

object InvestmentLotDialog {

    data class InvestmentLotDraft(
        val amount: Double,
        val schedule: InvestmentInterestService.InvestmentSchedule
    )

    fun show(
        context: Context,
        assetId: Long,
        totalAmount: Double,
        annualInterestRate: Double,
        scope: CoroutineScope,
        onDone: (() -> Unit)? = null
    ) {
        val themeContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_TapAccounting)
        val dialog = Dialog(themeContext)
        val content = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 18))
            background = roundedDrawable(Color.WHITE, dp(context, 18))
        }

        content.addView(TextView(themeContext).apply {
            text = "补录本金批次"
            setTextColor(Color.parseColor("#1F2A38"))
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        content.addView(TextView(themeContext).apply {
            text = "如果由多笔不同利率组成，可以拆成多笔本金。"
            setTextColor(Color.parseColor("#667085"))
            textSize = 14f
            setPadding(0, dp(context, 8), 0, dp(context, 10))
        })
        content.addView(TextView(themeContext).apply {
            text = "当前余额 ${formatAmount(totalAmount)}"
            setTextColor(Color.parseColor("#8A9099"))
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 14))
        })

        val countRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(context, 10))
        }
        countRow.addView(TextView(themeContext).apply {
            text = "共有几笔不同利率的金额"
            setTextColor(Color.parseColor("#1F2A38"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val etLotCount = EditText(themeContext).apply {
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            setText("1")
            textSize = 16f
            setTextColor(Color.parseColor("#1F2A38"))
            background = roundedStrokeDrawable(context, Color.parseColor("#F7F9FC"), Color.parseColor("#E5EAF2"), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(dp(context, 70), dp(context, 40))
        }
        countRow.addView(etLotCount)
        content.addView(countRow)

        val remainingText = TextView(themeContext).apply {
            setTextColor(Color.parseColor("#667085"))
            textSize = 13f
            setPadding(0, 0, 0, dp(context, 10))
        }
        content.addView(remainingText)

        val lotsContainer = LinearLayout(themeContext).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(themeContext).apply {
            addView(lotsContainer)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 330))
        }
        content.addView(scroll)

        data class LotRow(
            val amountInput: EditText,
            val rateInput: EditText,
            val startInput: TextView,
            var startEarningAt: Long
        )

        val rows = mutableListOf<LotRow>()
        val todayStart = InvestmentInterestService.startOfDay(System.currentTimeMillis())
        val initialDrafts = loadDrafts(context, assetId)

        fun addField(parent: LinearLayout, label: String, hint: String, text: String = ""): EditText {
            parent.addView(TextView(themeContext).apply {
                this.text = label
                setTextColor(Color.parseColor("#667085"))
                textSize = 12f
                setPadding(0, dp(context, 10), 0, dp(context, 4))
            })
            return EditText(themeContext).apply {
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                this.hint = hint
                setText(text)
                textSize = 15f
                setTextColor(Color.parseColor("#1F2A38"))
                setHintTextColor(Color.parseColor("#A0A7B2"))
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
                background = roundedStrokeDrawable(context, Color.parseColor("#F7F9FC"), Color.parseColor("#E5EAF2"), dp(context, 10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 42))
            }.also(parent::addView)
        }

        fun updateRemainingHint() {
            val filled = BillAssetImpactService.roundMoney(
                rows.sumOf { parseLocalizedAmount(it.amountInput.text?.toString().orEmpty()) }
            )
            val remaining = BillAssetImpactService.roundMoney(totalAmount - filled)
            remainingText.text = when {
                abs(remaining) <= 0.01 -> "金额已分配完成"
                remaining > 0 -> "还剩 ${formatAmount(remaining)} 未分配"
                else -> "已超出 ${formatAmount(abs(remaining))}"
            }
            remainingText.setTextColor(
                if (remaining < -0.01) Color.parseColor("#D92D20") else Color.parseColor("#667085")
            )
        }

        fun rebuildRows() {
            lotsContainer.removeAllViews()
            rows.clear()
            val initialCount = initialDrafts.size.takeIf { it > 0 }
            val count = (etLotCount.text?.toString()?.toIntOrNull() ?: initialCount ?: 1).coerceIn(1, 20)
            repeat(count) { index ->
                val card = LinearLayout(themeContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 14))
                    background = roundedStrokeDrawable(context, Color.WHITE, Color.parseColor("#E8EDF5"), dp(context, 14))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(context, 10)
                    }
                }
                card.addView(TextView(themeContext).apply {
                    text = "第 ${index + 1} 笔本金"
                    setTextColor(Color.parseColor("#1F2A38"))
                    textSize = 15f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                val draft = initialDrafts.getOrNull(index)
                val amountText = when {
                    draft != null && draft.amount > 0.0 -> formatAmount(draft.amount)
                    count == 1 -> formatAmount(totalAmount)
                    else -> ""
                }
                val rateText = when {
                    draft != null && draft.schedule.annualInterestRate != 0.0 -> formatAmount(draft.schedule.annualInterestRate)
                    annualInterestRate != 0.0 -> formatAmount(annualInterestRate)
                    else -> ""
                }
                val amountInput = addField(card, "金额", "必填", amountText)
                amountInput.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = updateRemainingHint()
                })
                val rateInput = addField(card, "年利率（%）", "可留空", rateText)
                val row = LotRow(
                    amountInput = amountInput,
                    rateInput = rateInput,
                    startInput = TextView(themeContext),
                    startEarningAt = draft?.schedule?.startEarningAt ?: InvestmentInterestService.plusDays(todayStart, 1)
                )
                card.addView(TextView(themeContext).apply {
                    text = "开始计算收益"
                    setTextColor(Color.parseColor("#667085"))
                    textSize = 12f
                    setPadding(0, dp(context, 10), 0, dp(context, 4))
                })
                row.startInput.apply {
                    text = formatDateForSchedule(row.startEarningAt)
                    setTextColor(Color.parseColor("#1F2A38"))
                    textSize = 14f
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                    background = roundedStrokeDrawable(context, Color.parseColor("#F7F9FC"), Color.parseColor("#E5EAF2"), dp(context, 10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    setOnClickListener {
                        val cal = java.util.Calendar.getInstance().apply { timeInMillis = row.startEarningAt }
                        android.app.DatePickerDialog(
                            themeContext,
                            { _, year, month, day ->
                                cal.set(year, month, day)
                                row.startEarningAt = InvestmentInterestService.startOfDay(cal.timeInMillis)
                                row.startInput.text = formatDateForSchedule(row.startEarningAt)
                            },
                            cal.get(java.util.Calendar.YEAR),
                            cal.get(java.util.Calendar.MONTH),
                            cal.get(java.util.Calendar.DAY_OF_MONTH)
                        ).show()
                    }
                }
                card.addView(row.startInput)
                lotsContainer.addView(card)
                rows += row
            }
            updateRemainingHint()
        }

        etLotCount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = rebuildRows()
        })
        if (initialDrafts.isNotEmpty()) {
            etLotCount.setText(initialDrafts.size.toString())
        }
        rebuildRows()

        fun collectDrafts(): List<InvestmentLotDraft> {
            return rows.map { row ->
                InvestmentLotDraft(
                    amount = BillAssetImpactService.roundMoney(parseLocalizedAmount(row.amountInput.text?.toString().orEmpty())),
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = row.startEarningAt,
                        firstPayoutAt = InvestmentInterestService.plusDays(row.startEarningAt, 1),
                        annualInterestRate = parseLocalizedAmount(row.rateInput.text?.toString().orEmpty())
                    )
                )
            }
        }

        val actionRow = LinearLayout(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 16), 0, 0)
        }
        val laterButton = Button(themeContext).apply {
            text = "稍后再记"
            setTextColor(Color.parseColor("#2F80ED"))
            background = ColorDrawable(Color.TRANSPARENT)
            setOnClickListener {
                dialog.dismiss()
                saveDrafts(context, assetId, collectDrafts())
                onDone?.invoke()
            }
        }
        val confirmButton = Button(themeContext).apply {
            text = "确认"
            setTextColor(Color.WHITE)
            background = roundedDrawable(Color.parseColor("#2F80ED"), dp(context, 10))
            setOnClickListener {
                val drafts = collectDrafts()
                val sum = BillAssetImpactService.roundMoney(drafts.sumOf { it.amount })
                if (abs(sum - totalAmount) > 0.01) {
                    Toast.makeText(context, "各笔金额合计需等于当前余额", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                scope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(context)
                    val asset = db.assetDao().getAssetById(assetId)
                    if (asset != null) {
                        drafts.forEach { draft ->
                            val normalizedStart = InvestmentInterestService.startOfDay(draft.schedule.startEarningAt)
                            val normalizedPayout = InvestmentInterestService.plusDays(normalizedStart, 1)
                            val lot = InvestmentLot(
                                assetId = assetId,
                                sourceBillId = null,
                                principalAmount = draft.amount,
                                remainingPrincipal = draft.amount,
                                currency = asset.currency,
                                annualInterestRate = draft.schedule.annualInterestRate,
                                startEarningAt = normalizedStart,
                                firstPayoutAt = normalizedPayout,
                                lastSettledAt = normalizedStart
                            )
                            db.investmentLotDao().insertLot(lot)
                        }
                        clearDrafts(context, assetId)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                            onDone?.invoke()
                        }
                    }
                }
            }
        }
        actionRow.addView(laterButton)
        actionRow.addView(confirmButton, LinearLayout.LayoutParams(dp(context, 108), dp(context, 44)).apply {
            leftMargin = dp(context, 8)
        })
        content.addView(actionRow)

        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout((context.resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun roundedDrawable(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun roundedStrokeDrawable(context: Context, color: Int, strokeColor: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            setStroke(dp(context, 1), strokeColor)
        }

    private fun formatAmount(value: Double): String =
        String.format(Locale.getDefault(), "%.2f", value)

    private fun parseLocalizedAmount(text: String): Double =
        text.replace(",", "").replace("，", "").toDoubleOrNull() ?: 0.0

    private fun formatDateForSchedule(timeMillis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd E", Locale.getDefault()).format(java.util.Date(timeMillis))

    private fun draftKey(assetId: Long): String = "asset_$assetId"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences("investment_lot_drafts", Context.MODE_PRIVATE)

    fun loadDrafts(context: Context, assetId: Long): List<InvestmentLotDraft> {
        val raw = getPrefs(context).getString(draftKey(assetId), null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                InvestmentLotDraft(
                    amount = obj.optDouble("amount", 0.0),
                    schedule = InvestmentInterestService.InvestmentSchedule(
                        startEarningAt = obj.optLong("startEarningAt", 0L),
                        firstPayoutAt = obj.optLong("firstPayoutAt", 0L),
                        annualInterestRate = obj.optDouble("annualInterestRate", 0.0)
                    )
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveDrafts(context: Context, assetId: Long, drafts: List<InvestmentLotDraft>) {
        val arr = org.json.JSONArray()
        drafts.forEach { d ->
            arr.put(org.json.JSONObject().apply {
                put("amount", d.amount)
                put("startEarningAt", d.schedule.startEarningAt)
                put("firstPayoutAt", d.schedule.firstPayoutAt)
                put("annualInterestRate", d.schedule.annualInterestRate)
            })
        }
        getPrefs(context).edit().putString(draftKey(assetId), arr.toString()).apply()
    }

    private fun clearDrafts(context: Context, assetId: Long) {
        getPrefs(context).edit().remove(draftKey(assetId)).apply()
    }
}
