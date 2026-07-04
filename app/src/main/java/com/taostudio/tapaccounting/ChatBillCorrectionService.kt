package com.taostudio.tapaccounting

import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.Category
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.data.repository.CategoryRepository
import com.taostudio.tapaccounting.logic.BillAssetImpactService
import com.taostudio.tapaccounting.logic.BillMutationService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatBillCorrectionService(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val appendAiTextMessage: (String, Boolean, String?, String?) -> Unit,
    private val scrollToBottom: () -> Unit,
    private val refreshSessionRows: suspend () -> Unit,
    private val getCurrentBookName: () -> String,
    private val setCurrentBookName: (String) -> Unit,
    private val getCurrentConversationId: () -> String,
    private val parseTimeToMillis: (String) -> Long,
    private val buildBillMessageContent: (List<Bill>, Set<Long>, Set<Long>, Boolean) -> String,
    private val onBillMessagesUpdated: () -> Unit = {}
) {
    private data class TransferSettlementHint(
        val amount: Double,
        val currency: String?
    )

    fun buildBillSummary(bills: List<Bill>): String {
        return bills.joinToString("；") { bill ->
            val typeLabel = when (bill.type) {
                1 -> "收入"
                2 -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) "还款" else "转账"
                else -> "支出"
            }
            "$typeLabel ${String.format(Locale.getDefault(), "%.2f", bill.amount)}元，分类${bill.categoryName}，账户${bill.accountName.ifBlank { "未指定" }}${bill.remark.takeIf { it.isNotBlank() }?.let { "，备注$it" } ?: ""}"
        }
    }

    private fun parsePositiveDouble(raw: Any?): Double? {
        return when (raw) {
            is Number -> raw.toDouble().takeIf { it > 0.0 }
            is String -> raw.trim().takeIf { it.isNotBlank() }?.toDoubleOrNull()?.takeIf { it > 0.0 }
            else -> null
        }
    }

    private data class BillValidationResult(
        val bills: List<Bill> = emptyList(),
        val errors: List<String> = emptyList()
    )

    private fun userTextHasImplicitNow(userText: String): Boolean {
        val normalized = userText.replace("\\s+".toRegex(), "")
        return listOf("今天", "刚刚", "刚才", "现在", "此刻", "今晚", "今早", "早上", "中午", "下午", "晚上").any {
            normalized.contains(it)
        }
    }

    private fun parseExplicitTimeOrNull(rawTime: String, userText: String): Long? {
        val trimmed = rawTime.trim()
        if (trimmed.isBlank()) {
            return if (userTextHasImplicitNow(userText)) System.currentTimeMillis() else null
        }
        val patterns = listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.getDefault()).parse(trimmed)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }

    private fun requireSupportedType(raw: Any?): Int? {
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().toIntOrNull()
            else -> null
        } ?: return null
        return value.takeIf { it in 0..3 }
    }

    private fun normalizeCurrencyCode(raw: String?): String? {
        val cleaned = raw?.trim()?.uppercase(Locale.ROOT).orEmpty()
        if (cleaned.isBlank()) return null
        return when (cleaned) {
            "人民币", "元", "块", "块钱", "RMB" -> "CNY"
            "欧元" -> "EUR"
            "美元" -> "USD"
            "兹罗提", "兹羅提" -> "PLN"
            else -> cleaned
        }
    }

    private fun readTransferTargetAmountFromJson(billJson: JSONObject): Double? {
        val keys = listOf("target_amount", "received_amount", "to_amount", "arrival_amount", "in_amount")
        keys.forEach { key ->
            if (!billJson.has(key)) return@forEach
            parsePositiveDouble(billJson.opt(key))?.let { return it }
        }
        return null
    }

    private fun readTransferTargetCurrencyFromJson(billJson: JSONObject): String? {
        val keys = listOf("target_currency", "received_currency", "to_currency", "arrival_currency", "in_currency")
        keys.forEach { key ->
            if (!billJson.has(key)) return@forEach
            normalizeCurrencyCode(billJson.optString(key, ""))?.let { return it }
        }
        return null
    }

    private suspend fun resolveFallbackCategoryName(type: Int): String? {
        val categories = db.categoryDao().getCategoriesListByType(type)
        if (categories.isEmpty()) return null
        val rootsById = categories.filter { it.parentId == null }.associateBy { it.id }

        fun displayName(category: Category): String {
            val parent = category.parentId?.let { rootsById[it] }
            return if (parent != null) "${parent.name} - ${category.name}" else category.name
        }

        return categories.firstOrNull { it.parentId == null && it.name in setOf("其他", "其它") }?.let(::displayName)
            ?: categories.firstOrNull { it.name in setOf("其他", "其它") }?.let(::displayName)
    }

    private fun extractSingleTransferSettlementHint(userText: String): TransferSettlementHint? {
        if (userText.isBlank()) return null
        val pattern = Regex(
            "(到账|入账|到手|实际到账|实到|收到)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]{3}|人民币|元|块钱|块|RMB|CNY|PLN|USD|EUR|欧元|美元|兹罗提|兹羅提)?",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(userText) ?: return null
        val amount = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        if (amount <= 0.0) return null
        val currency = normalizeCurrencyCode(match.groupValues.getOrNull(3))
        return TransferSettlementHint(amount = amount, currency = currency)
    }

    suspend fun processBillResult(
        result: JSONObject,
        userText: String,
        bookName: String,
        conversationId: String
    ): List<Bill> {
        val rawBills = mutableListOf<JSONObject>()
        when {
            result.has("bills") -> {
                val arr = result.getJSONArray("bills")
                for (i in 0 until arr.length()) rawBills.add(arr.getJSONObject(i))
            }
            result.has("amount") -> rawBills.add(result)
            else -> {
                appendAiTextMessage("AI 返回了无法识别的格式。", false, bookName, conversationId)
                return emptyList()
            }
        }
        if (rawBills.isEmpty()) {
            appendAiTextMessage("未解析到账单信息。", false, bookName, conversationId)
            return emptyList()
        }

        val activeBookName = BookAccountManager.normalizeBookName(
            bookName.ifBlank { BookAccountManager.getSelectedBook(context) }
        )
        val singleTransferSettlementHint = if (rawBills.size == 1) {
            extractSingleTransferSettlementHint(userText)
        } else {
            null
        }

        val validation = withContext(Dispatchers.IO) {
            val billsToSave = mutableListOf<Bill>()
            val errors = mutableListOf<String>()
            for (billJson in rawBills) {
                val rowNo = billsToSave.size + errors.size + 1
                val amount = parsePositiveDouble(billJson.opt("amount"))
                if (amount == null) {
                    errors += "第 ${rowNo} 笔缺少有效金额，金额必须大于 0。"
                    continue
                }
                val type = requireSupportedType(billJson.opt("type"))
                if (type == null) {
                    errors += "第 ${rowNo} 笔缺少明确类型，无法安全落账。"
                    continue
                }
                val timeLong = parseExplicitTimeOrNull(billJson.optString("time", ""), userText)
                if (timeLong == null) {
                    errors += "第 ${rowNo} 笔缺少可确认时间，请补充时间后再记账。"
                    continue
                }
                val finalType = if (type == 3) 2 else type

                var categoryName = com.taostudio.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(billJson.optString("category_name", "")).trim()
                // 优先从 result 读取 normalizeAccountingResult 已设置的 subType；
                // 兜底：原始 type=3 或 category_name="还款" 也识别为还款
                val subType = billJson.optInt("subType", if (type == 3) Bill.SUBTYPE_REPAYMENT else {
                    if (finalType == Bill.TYPE_TRANSFER && categoryName == "还款") Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL
                })
                if (finalType != Bill.TYPE_TRANSFER && categoryName.isBlank()) {
                    categoryName = resolveFallbackCategoryName(if (finalType == Bill.TYPE_INCOME) 1 else 0).orEmpty()
                }
                if (finalType != Bill.TYPE_TRANSFER && categoryName.isBlank()) {
                    errors += "第 ${rowNo} 笔缺少分类，请补充分类后再记账。"
                    continue
                }
                val assetName = billJson.optString("asset_name", "")
                val toAssetName = billJson.optString("to_asset_name", "")
                val remark = billJson.optString("remarks", billJson.optString("remark", ""))
                val currency = billJson.optString("currency", "CNY").ifBlank { "CNY" }
                val fee = billJson.optDouble("fee", 0.0).coerceAtLeast(0.0)

                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                    if (finalType == Bill.TYPE_INCOME) 1 else 0,
                    categoryName
                )
                val assetEntity = if (assetName.isNotEmpty()) db.assetDao().getAssetByName(assetName) else null
                val toAssetEntity = if (toAssetName.isNotEmpty()) db.assetDao().getAssetByName(toAssetName) else null
                val explicitTargetAmount = readTransferTargetAmountFromJson(billJson)
                    ?: if (finalType == Bill.TYPE_TRANSFER) singleTransferSettlementHint?.amount else null
                val explicitTargetCurrency = readTransferTargetCurrencyFromJson(billJson)
                    ?: if (finalType == Bill.TYPE_TRANSFER) singleTransferSettlementHint?.currency else null
                val explicitTransferRate = if (
                    finalType == Bill.TYPE_TRANSFER &&
                    toAssetEntity != null &&
                    amount > 0.0 &&
                    explicitTargetAmount != null &&
                    explicitTargetAmount > 0.0
                ) {
                    val targetCurrency = explicitTargetCurrency ?: toAssetEntity.currency
                    if (targetCurrency.equals(toAssetEntity.currency, ignoreCase = true)) {
                        BillAssetImpactService.roundRate(explicitTargetAmount / amount)
                    } else {
                        null
                    }
                } else {
                    null
                }
                val exchangeRate = when {
                    explicitTransferRate != null -> explicitTransferRate
                    finalType == Bill.TYPE_TRANSFER && toAssetEntity != null && amount > 0.0 ->
                        BillAssetImpactService.estimateExchangeRateToTarget(amount, currency, toAssetEntity.currency)
                    currency.equals("CNY", ignoreCase = true) -> 1.0
                    else -> BillAssetImpactService.estimateExchangeRateToCny(currency)
                }

                val bill = Bill(
                    type = finalType,
                    subType = subType,
                    amount = amount,
                    currency = currency,
                    exchangeRate = exchangeRate,
                    fee = fee,
                    categoryId = categoryEntity?.id,
                    accountId = assetEntity?.id,
                    toAccountId = toAssetEntity?.id,
                    categoryName = categoryName,
                    accountName = assetName,
                    toAccountName = toAssetName,
                    time = timeLong,
                    remark = remark,
                    bookName = activeBookName
                )
                billsToSave.add(bill)
            }
            BillValidationResult(billsToSave, errors)
        }

        if (validation.bills.isEmpty()) {
            appendAiTextMessage(
                "这次识别结果还不够完整，我没有保存账单：\n${validation.errors.joinToString("\n")}",
                false,
                activeBookName,
                conversationId
            )
            return emptyList()
        }

        if (validation.errors.isNotEmpty()) {
            appendAiTextMessage(
                "部分账单已保存，以下未能保存：\n${validation.errors.joinToString("\n")}",
                false,
                activeBookName,
                conversationId
            )
        }

        val (savedBills, msgId, billsJsonArr) = withContext(Dispatchers.IO) {
            db.withTransaction {
                val saved = validation.bills.map { bill ->
                    BillMutationService.insertBillWithinActiveTransaction(db, bill)
                }
                val billIdsJson = JSONArray(saved.map { it.id.toString() }).toString()
                val content = buildBillMessageContent(saved, emptySet(), emptySet(), false)
                val messageId = db.chatMessageDao().insert(
                    ChatMessage(
                        msgType = ChatActivity.MSG_TYPE_AI_BILL,
                        content = content,
                        billIds = billIdsJson,
                        modelName = Prefs.getAiChatModel(context),
                        bookName = activeBookName,
                        conversationId = conversationId
                    )
                )
                Triple(saved, messageId, content)
            }
        }

        if (getCurrentBookName() != activeBookName || getCurrentConversationId() != conversationId) {
            return savedBills
        }

        displayMessages.add(
            ChatDisplayItem(
                dbId = msgId,
                msgType = ChatActivity.MSG_TYPE_AI_BILL,
                content = billsJsonArr,
                bills = savedBills.toMutableList(),
                timestamp = System.currentTimeMillis(),
                isLoading = false
            )
        )
        onBillMessagesUpdated()
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()
        refreshSessionRows()
        return savedBills
    }
}

