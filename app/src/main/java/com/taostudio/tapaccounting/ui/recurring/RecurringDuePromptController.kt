package com.taostudio.tapaccounting.ui.recurring

import android.content.Context
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.taostudio.tapaccounting.R
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.RecurringFrequency
import com.taostudio.tapaccounting.data.local.entity.RecurringPattern
import com.taostudio.tapaccounting.logic.RecurringBillingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecurringDuePromptController {
    private const val PREFS_NAME = "recurring_due_prompt_prefs"
    private const val KEY_PREFIX_SNOOZE = "snooze_today_"

    private var showing = false
    private var mutedForSession = false
    private val dayFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    private val displayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun maybeShow(activity: AppCompatActivity) {
        if (mutedForSession || showing || activity.isFinishing || activity.isDestroyed) return

        activity.lifecycleScope.launch {
            val db = AppDatabase.getDatabase(activity)
            val service = RecurringBillingService(db)
            val duePattern = withContext(Dispatchers.IO) {
                service.getDuePatternsForPrompt()
                    .firstOrNull { !isSnoozedToday(activity, it.id) }
            } ?: return@launch

            if (activity.isFinishing || activity.isDestroyed || showing) return@launch
            showPrompt(activity, service, duePattern)
        }
    }

    fun showIfDue(activity: AppCompatActivity, pattern: RecurringPattern) {
        if (mutedForSession || showing || activity.isFinishing || activity.isDestroyed) return

        activity.lifecycleScope.launch {
            val service = RecurringBillingService(AppDatabase.getDatabase(activity))
            val shouldShow = withContext(Dispatchers.IO) {
                !isSnoozedToday(activity, pattern.id) && service.isDueForPrompt(pattern)
            }
            if (!shouldShow || activity.isFinishing || activity.isDestroyed || showing) return@launch
            showPrompt(activity, service, pattern)
        }
    }

    private fun showPrompt(
        activity: AppCompatActivity,
        service: RecurringBillingService,
        pattern: RecurringPattern
    ) {
        showing = true
        val view = activity.layoutInflater.inflate(R.layout.dialog_recurring_due_prompt, null)
        val etAmount = view.findViewById<EditText>(R.id.et_recurring_due_amount)
        val title = view.findViewById<TextView>(R.id.tv_recurring_due_title)
        val body = view.findViewById<TextView>(R.id.tv_recurring_due_body)
        val meta = view.findViewById<TextView>(R.id.tv_recurring_due_meta)
        val btnClose = view.findViewById<TextView>(R.id.btn_recurring_due_close)
        val btnRecord = view.findViewById<View>(R.id.btn_recurring_due_record)
        val btnSkipToday = view.findViewById<TextView>(R.id.btn_recurring_due_skip_today)
        val btnCancelPattern = view.findViewById<TextView>(R.id.btn_recurring_due_cancel_pattern)

        title.text = activity.getString(R.string.recurring_due_prompt_title)
        body.text = activity.getString(R.string.recurring_due_prompt_body, pattern.merchantKey)
        meta.text = activity.getString(
            R.string.recurring_due_prompt_meta,
            frequencyLabel(activity, pattern.frequency),
            listOfNotNull(
                pattern.categoryName,
                if (pattern.toAccountName.isNotBlank()) {
                    "${pattern.accountName.orEmpty()} -> ${pattern.toAccountName}"
                } else {
                    pattern.accountName
                }
            ).ifEmpty {
                listOf(pattern.bookName)
            }.joinToString(" · "),
            pattern.nextExpectedAt?.let { displayDateFormat.format(Date(it)) }
                ?: activity.getString(R.string.recurring_next_unknown)
        )
        etAmount.setText(String.format(Locale.getDefault(), "%.2f", pattern.amountApprox))
        etAmount.selectAll()

        val dialog = BottomSheetDialog(activity)
        var handled = false
        dialog.setContentView(view)
        dialog.setOnDismissListener {
            if (!handled) mutedForSession = true
            showing = false
        }
        btnClose.setOnClickListener {
            handled = true
            mutedForSession = true
            dialog.dismiss()
        }
        btnRecord.setOnClickListener {
            val amount = etAmount.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(activity, R.string.recurring_input_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    service.recordDuePattern(pattern, amount)
                }
                Toast.makeText(activity, R.string.recurring_due_recorded, Toast.LENGTH_SHORT).show()
                handled = true
                dialog.dismiss()
                maybeShow(activity)
            }
        }
        btnSkipToday.setOnClickListener {
            snoozeToday(activity, pattern.id)
            mutedForSession = true
            Toast.makeText(activity, R.string.recurring_due_snoozed, Toast.LENGTH_SHORT).show()
            handled = true
            dialog.dismiss()
        }
        btnCancelPattern.setOnClickListener {
            activity.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    service.dismissPattern(pattern)
                }
                Toast.makeText(activity, R.string.recurring_due_cancelled, Toast.LENGTH_SHORT).show()
                handled = true
                dialog.dismiss()
                maybeShow(activity)
            }
        }
        dialog.show()
    }

    private fun frequencyLabel(context: Context, frequency: RecurringFrequency): String =
        when (frequency) {
            RecurringFrequency.WEEKLY -> context.getString(R.string.recurring_frequency_weekly)
            RecurringFrequency.MONTHLY -> context.getString(R.string.recurring_frequency_monthly)
            RecurringFrequency.YEARLY -> context.getString(R.string.recurring_frequency_yearly)
        }

    private fun isSnoozedToday(context: Context, patternId: Long): Boolean {
        val today = dayFormat.format(Date())
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFIX_SNOOZE + patternId, null) == today
    }

    private fun snoozeToday(context: Context, patternId: Long) {
        val today = dayFormat.format(Date())
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX_SNOOZE + patternId, today)
            .apply()
    }
}
