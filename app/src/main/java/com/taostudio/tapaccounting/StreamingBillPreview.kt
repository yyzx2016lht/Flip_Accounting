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
            // 解析不出来 → 保持上次预览（如果有的话），否则返回 null 让调用方不更新
            return previous.takeIf { it.isNotBlank() }
        }
        return parsed.joinToString("\n")
    }

    private fun parseBillPreviewLines(
        raw: String,
        lineFormatter: ((remark: String, category: String, amount: Double?, currency: String) -> String)? = null
    ): List<String> {
        val compact = raw.replace("\n", "")
        // 优先增量提取：每闭合一个 { } 就解析一笔，逐条渲染
        val incremental = extractCompleteBillsFromStream(compact, lineFormatter)
        if (incremental.isNotEmpty()) return incremental

        // 增量没结果（可能 JSON 结构不同），兜底完整解析
        val jsonText = extractFirstJsonObjectText(compact)
        if (!jsonText.isNullOrBlank()) {
            runCatching {
                val root = JSONObject(jsonText)
                val bills = root.optJSONArray("bills")
                if (bills != null && bills.length() > 0) {
                    return linesFromBillsArray(bills, lineFormatter)
                }
            }
        }
        return emptyList()
    }

    /**
     * 从流式文本中增量提取已完整的账单对象。
     * 只用标准 JSONObject 解析，不用正则，100% 准确。
     */
    private fun extractCompleteBillsFromStream(
        compact: String,
        lineFormatter: ((remark: String, category: String, amount: Double?, currency: String) -> String)? = null
    ): List<String> {
        // 找到 "bills":[ 的位置
        val billsKeyIdx = compact.indexOf("\"bills\"")
        if (billsKeyIdx < 0) return emptyList()
        val arrayStart = compact.indexOf('[', billsKeyIdx + 7)
        if (arrayStart < 0) return emptyList()

        val lines = mutableListOf<String>()
        var i = arrayStart + 1
        var depth = 0           // { } 深度
        var objStart = -1       // 当前账单对象起始位置
        var inStr = false       // 是否在字符串内
        var escaped = false     // 上一个字符是否是 \
        var objCount = 0        // 调试：发现的对象数

        while (i < compact.length && lines.size < 8) {
            val c = compact[i]
            // 字符串内跳过所有特殊字符
            if (escaped) { escaped = false; i++; continue }
            if (c == '\\' && inStr) { escaped = true; i++; continue }
            if (c == '"') { inStr = !inStr; i++; continue }
            if (inStr) { i++; continue }

            when {
                c == '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                c == '}' && depth > 0 -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objCount++
                        // 一个完整的账单对象，用 JSONObject 解析
                        runCatching {
                            val bill = JSONObject(compact.substring(objStart, i + 1))
                            val amount = bill.optDouble("amount", Double.NaN).takeUnless { it.isNaN() }
                            if (amount != null) {
                                val remark = bill.optString("remarks", bill.optString("remark", "")).ifBlank { "未命名账单" }
                                val category = bill.optString("category_name", "")
                                val currency = bill.optString("currency", "")
                                lines += if (lineFormatter != null) {
                                    lineFormatter(remark, category, amount, currency)
                                } else {
                                    formatChatBillPrimaryLine(lines.size + 1, remark, amount, currency)
                                }
                                if (lineFormatter == null && category.isNotBlank()) {
                                    lines += "   分类: $category"
                                }
                            }
                        }
                        objStart = -1
                    }
                }
                c == ']' && depth == 0 -> break  // bills 数组结束
            }
            i++
        }
        android.util.Log.d("StreamParser", "extractComplete: compactLen=${compact.length}, objCount=$objCount, lines=${lines.size}, arrayStart=$arrayStart")
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
