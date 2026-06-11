package com.taostudio.tapaccounting

import android.content.Context
import android.util.Base64
import java.io.File

object ReceiptImageInputHelper {

    const val MULTIMODAL_PREFIX = "[MULTIMODAL_IMAGE]"
    const val MULTIMODAL_DIRECT_PREFIX = "[MULTIMODAL_IMAGE_DIRECT]"

    data class ImagePayload(
        val base64: String,
        val mime: String,
        val supplement: String
    )

    fun encodePayload(prefix: String, base64: String, mime: String, supplement: String = ""): String {
        val safeSupplement = supplement.replace("|", " ").trim()
        return "$prefix$base64|$mime|$safeSupplement"
    }

    fun decodePayload(raw: String): ImagePayload? {
        val prefix = when {
            raw.startsWith(MULTIMODAL_DIRECT_PREFIX) -> MULTIMODAL_DIRECT_PREFIX
            raw.startsWith(MULTIMODAL_PREFIX) -> MULTIMODAL_PREFIX
            else -> return null
        }
        val payload = raw.removePrefix(prefix)
        val parts = payload.split("|", limit = 3)
        val base64 = parts.getOrElse(0) { "" }
        if (base64.isBlank()) return null
        return ImagePayload(
            base64 = base64,
            mime = parts.getOrElse(1) { "image/jpeg" },
            supplement = parts.getOrElse(2) { "" }.trim()
        )
    }

    fun isDirectPayload(raw: String): Boolean = raw.startsWith(MULTIMODAL_DIRECT_PREFIX)

    fun mergeSupplementWithSummary(summary: String, supplement: String): String {
        val cleanedSupplement = supplement.trim()
        if (cleanedSupplement.isBlank()) return normalizeVisionSummary(summary)
        val cleanedSummary = normalizeVisionSummary(summary)
        if (cleanedSummary.isBlank()) return cleanedSupplement
        if (cleanedSummary.contains(cleanedSupplement)) return cleanedSummary
        if (isPaymentSupplement(cleanedSupplement)) {
            return applyPaymentSupplementToLines(cleanedSummary, cleanedSupplement)
        }
        return "$cleanedSupplement\n$cleanedSummary"
    }

    /**
     * 图片草稿确认后交给记账模型的输入，确保补充说明（尤其支付方式）不会被漏掉。
     */
    fun buildAccountingInputFromImageDraft(draft: String, supplement: String): String {
        val cleanedDraft = draft.trim()
        val cleanedSupplement = supplement.trim()
        if (cleanedSupplement.isBlank()) return cleanedDraft
        if (cleanedDraft.contains(cleanedSupplement)) return cleanedDraft
        return buildString {
            append("【用户补充（必须写入每条账单的支付方式 asset_name，不可遗漏）】\n")
            append(cleanedSupplement)
            append("\n\n【待记账清单】\n")
            append(cleanedDraft)
        }
    }

    private fun isPaymentSupplement(text: String): Boolean {
        val keywords = listOf(
            "微信", "支付宝", "花呗", "银行", "信用卡", "储蓄卡", "借记卡",
            "现金", "visa", "mastercard", "银联", "零钱", "钱包", "apple pay",
            "paypal", "云闪付", "京东支付", "美团支付"
        )
        return keywords.any { text.contains(it, ignoreCase = true) } ||
            text.endsWith("支付") ||
            text.endsWith("卡")
    }

    private fun applyPaymentSupplementToLines(summary: String, payment: String): String {
        val paymentPhrase = when {
            payment.contains("用了") -> payment
            payment.contains("支付") && !payment.startsWith("用") -> "用了$payment"
            else -> "用了${payment}支付"
        }
        val paymentMarkers = listOf(
            "用了", "支付", "微信", "支付宝", "花呗", "银行", "现金",
            "visa", "mastercard", "pay", "银联", "零钱", "wallet"
        )
        return summary.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n") { line ->
                if (paymentMarkers.any { marker -> line.contains(marker, ignoreCase = true) }) {
                    line
                } else {
                    "$line $paymentPhrase"
                }
            }
    }

    /**
     * 将视觉模型返回的清单整理为「一行一条」便于展示和后续记账。
     */
    fun normalizeVisionSummary(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return trimmed
        if (trimmed.contains("未识别到可记账内容")) return trimmed

        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .flatMap { line -> splitDenseReceiptLine(line) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

        return if (lines.isNotEmpty()) lines.joinToString("\n") else trimmed
    }

    private fun splitDenseReceiptLine(line: String): List<String> {
        if (!line.contains("花了") && !line.contains("支付") && !line.contains("消费")) {
            return listOf(line)
        }
        val segments = Regex("(?=(?:购买|支付|消费|收到|到账|退款|转账))")
            .split(line)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return if (segments.size >= 2) segments else listOf(line)
    }

    @Deprecated("No longer used. Supplement dialogs are removed from all flows.")
    fun showSupplementDialog(
        ctx: android.content.Context,
        showDialog: (androidx.appcompat.app.AlertDialog, Float) -> Unit,
        onConfirm: (supplement: String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        onConfirm("")
    }

    suspend fun readImagePayload(ctx: Context, uri: android.net.Uri): ImagePayload? {
        val sourceMime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when {
            sourceMime.contains("png", ignoreCase = true) -> "png"
            else -> "jpg"
        }
        val imageDir = File(ctx.filesDir, "receipt_inputs").also { it.mkdirs() }
        val outFile = File(imageDir, "receipt_${System.currentTimeMillis()}.$ext")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return null

        val bytes = outFile.readBytes()
        if (bytes.isEmpty()) return null
        return ImagePayload(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mime = sourceMime,
            supplement = ""
        )
    }
}
