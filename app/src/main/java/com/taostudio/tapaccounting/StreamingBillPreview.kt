package com.taostudio.tapaccounting

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object StreamingBillPreview {
    private const val UI_UPDATE_INTERVAL_MS = 150L

    fun shouldApplyNonStreamProgress(streamStarted: Boolean): Boolean = !streamStarted

    fun shouldUpdateUi(previous: String, candidate: String, lastUpdateMs: Long): Boolean {
        if (candidate == previous) return false
        if (previous.isBlank()) return true
        if (candidate.lines().size > previous.lines().size) return true
        if (candidate.length >= previous.length + 12) return true
        return SystemClock.elapsedRealtime() - lastUpdateMs >= UI_UPDATE_INTERVAL_MS
    }

    fun formatChatPreview(raw: String, previous: String = ""): String {
        val parsed = parseBillPreviewLines(raw)
        if (parsed.isEmpty()) {
            return previous.ifBlank {
                if (raw.isNotBlank()) "正在整理账单..." else "正在读懂这笔账..."
            }
        }
        return "正在整理账单...\n" + parsed.joinToString("\n")
    }

    fun formatOverlayPreview(raw: String, previous: String = "", lineFormatter: (remark: String, category: String, amount: Double?, currency: String) -> String): String? {
        val parsed = parseBillPreviewLines(raw, lineFormatter)
        if (parsed.isEmpty()) {
            return previous.takeIf { it.isNotBlank() }
        }
        return parsed.joinToString("\n")
    }

    private fun parseBillPreviewLines(
        raw: String,
        lineFormatter: ((remark: String, category: String, amount: Double?, currency: String) -> String)? = null
    ): List<String> {
        val jsonText = extractFirstJsonObjectText(raw.replace("\n", ""))
        if (!jsonText.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(jsonText)
                val bills = root.optJSONArray("bills")
                if (bills != null && bills.length() > 0) {
                    return linesFromBillsArray(bills, lineFormatter)
                }
            }
        }

        val compact = raw.replace("\n", "")
        val objectRegex = Regex("\\{[^{}]*\\}")
        val objects = objectRegex.findAll(compact).map { it.value }.toList()
        if (objects.isEmpty()) return emptyList()

        val lines = mutableListOf<String>()
        for (obj in objects) {
            val amount = extractJsonNumber(obj, "amount")
            val remark = extractJsonString(obj, "remarks")
                ?: extractJsonString(obj, "remark")
                ?: "未命名账单"
            val category = extractJsonString(obj, "category_name").orEmpty()
            val currency = extractJsonString(obj, "currency").orEmpty()
            if (amount == null && category.isBlank() && remark == "未命名账单") continue
            lines += if (lineFormatter != null) {
                lineFormatter(remark, category, amount, currency)
            } else {
                formatChatBillPrimaryLine(index = lines.size + 1, remark, amount, currency)
            }
            if (lineFormatter == null && category.isNotBlank()) {
                lines += "   分类: $category"
            }
            if (lines.size >= 8) break
        }
        return lines
    }

    private fun linesFromBillsArray(
        bills: JSONArray,
        lineFormatter: ((remark: String, category: String, amount: Double?, currency: String) -> String)?
    ): List<String> {
        val lines = mutableListOf<String>()
        for (i in 0 until minOf(bills.length(), 8)) {
            val bill = bills.optJSONObject(i) ?: continue
            val amount = bill.optDouble("amount", Double.NaN).takeUnless { it.isNaN() }
            val remark = bill.optString("remarks", bill.optString("remark", "")).ifBlank { "未命名账单" }
            val category = bill.optString("category_name", "")
            val currency = bill.optString("currency", "")
            lines += if (lineFormatter != null) {
                lineFormatter(remark, category, amount, currency)
            } else {
                formatChatBillPrimaryLine(index = i + 1, remark, amount, currency)
            }
            if (lineFormatter == null && category.isNotBlank()) {
                lines += "   分类: $category"
            }
        }
        return lines
    }

    private fun formatChatBillPrimaryLine(
        index: Int,
        remark: String,
        amount: Double?,
        currency: String
    ): String {
        val amountText = amount?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"
        val amountWithCurrency = if (currency.isNotBlank()) "$amountText $currency" else amountText
        return "$index. $remark  $amountWithCurrency"
    }

    private fun extractJsonString(obj: String, key: String): String? {
        val escapedKey = Regex.escape(key)
        val regex = Regex("\"$escapedKey\"\\s*:\\s*\"([^\"]*)\"")
        return regex.find(obj)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractJsonNumber(obj: String, key: String): Double? {
        val escapedKey = Regex.escape(key)
        val regex = Regex("\"$escapedKey\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        return regex.find(obj)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }
}
