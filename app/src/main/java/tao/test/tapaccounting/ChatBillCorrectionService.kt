package tao.test.tapaccounting

import androidx.recyclerview.widget.RecyclerView
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tao.test.tapaccounting.data.local.AppDatabase
import tao.test.tapaccounting.data.local.entity.Bill
import tao.test.tapaccounting.data.local.entity.ChatMessage
import tao.test.tapaccounting.data.repository.CategoryRepository
import tao.test.tapaccounting.logic.BillAssetImpactService
import tao.test.tapaccounting.logic.BillMutationService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
    private val confirmBillModifyPreview: suspend (Bill, Bill, List<String>) -> Boolean
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

    private fun typeLabel(bill: Bill): String {
        return when (bill.type) {
            Bill.TYPE_INCOME -> "收入"
            Bill.TYPE_TRANSFER -> if (bill.subType == Bill.SUBTYPE_REPAYMENT) "还款" else "转账"
            else -> "支出"
        }
    }

    private fun accountLabel(bill: Bill): String {
        return when (bill.type) {
            Bill.TYPE_INCOME -> bill.toAccountName.ifBlank { bill.accountName.ifBlank { "未指定" } }
            Bill.TYPE_TRANSFER -> {
                val from = bill.accountName.ifBlank { "未指定" }
                val to = bill.toAccountName.ifBlank { "未指定" }
                "$from -> $to"
            }
            else -> bill.accountName.ifBlank { "未指定" }
        }
    }

    private fun formatBillTime(time: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }

    private fun moneyText(amount: Double, currency: String): String {
        return "${String.format(Locale.getDefault(), "%.2f", amount)} $currency"
    }

    private fun isDifferent(a: Double, b: Double): Boolean = abs(a - b) > 1e-9

    private fun buildChangePreviewLines(oldBill: Bill, newBill: Bill): List<String> {
        val changes = mutableListOf<String>()
        if (oldBill.type != newBill.type || oldBill.subType != newBill.subType) {
            changes += "类型：${typeLabel(oldBill)} -> ${typeLabel(newBill)}"
        }
        if (isDifferent(oldBill.amount, newBill.amount) || !oldBill.currency.equals(newBill.currency, ignoreCase = true)) {
            changes += "金额：${moneyText(oldBill.amount, oldBill.currency)} -> ${moneyText(newBill.amount, newBill.currency)}"
        }
        if (oldBill.categoryName != newBill.categoryName) {
            changes += "分类：${oldBill.categoryName.ifBlank { "未分类" }} -> ${newBill.categoryName.ifBlank { "未分类" }}"
        }
        if (accountLabel(oldBill) != accountLabel(newBill)) {
            changes += "账户：${accountLabel(oldBill)} -> ${accountLabel(newBill)}"
        }
        if (oldBill.time != newBill.time) {
            changes += "时间：${formatBillTime(oldBill.time)} -> ${formatBillTime(newBill.time)}"
        }
        if (oldBill.remark != newBill.remark) {
            changes += "备注：${oldBill.remark.ifBlank { "无" }} -> ${newBill.remark.ifBlank { "无" }}"
        }
        if (isDifferent(oldBill.fee, newBill.fee)) {
            changes += "手续费：${String.format(Locale.getDefault(), "%.2f", oldBill.fee)} -> ${String.format(Locale.getDefault(), "%.2f", newBill.fee)}"
        }
        return changes
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
                val subType = if (type == 3) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL

                val categoryName = tao.test.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(billJson.optString("category_name", "")).trim()
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

        if (validation.errors.isNotEmpty() || validation.bills.size != rawBills.size) {
            appendAiTextMessage(
                "这次识别结果还不够完整，我没有保存账单：\n${validation.errors.joinToString("\n")}",
                false,
                activeBookName,
                conversationId
            )
            return emptyList()
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
        adapterProvider().notifyItemInserted(displayMessages.lastIndex)
        scrollToBottom()
        refreshSessionRows()
        return savedBills
    }

    suspend fun processBillModifyResult(result: JSONObject, userText: String, oldBill: Bill) {
        val rawTime = result.optString("time", "").trim()
        val timeLong = if (rawTime.isBlank()) oldBill.time else {
            parseExplicitTimeOrNull(rawTime, userText) ?: run {
                appendAiTextMessage("修改失败：AI 返回了无法确认的时间，我没有保存修改。", false, oldBill.bookName, getCurrentConversationId())
                return
            }
        }
        val type = if (result.has("type")) {
            requireSupportedType(result.opt("type")) ?: run {
                appendAiTextMessage("修改失败：AI 返回了不支持的账单类型，我没有保存修改。", false, oldBill.bookName, getCurrentConversationId())
                return
            }
        } else {
            oldBill.type
        }
        val finalType = if (type == 3) 2 else type
        val subType = if (type == 3) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL

        val categoryName = tao.test.tapaccounting.logic.CategoryNameNormalizer.normalizeForStorage(result.optString("category_name", oldBill.categoryName)).trim().ifBlank { oldBill.categoryName }
        val assetName = result.optString("asset_name", oldBill.accountName).ifBlank { oldBill.accountName }
        val toAssetName = result.optString("to_asset_name", oldBill.toAccountName).ifBlank { oldBill.toAccountName }
        val amount = if (result.has("amount")) {
            parsePositiveDouble(result.opt("amount")) ?: run {
                appendAiTextMessage("修改失败：AI 返回了无效金额，我没有保存修改。", false, oldBill.bookName, getCurrentConversationId())
                return
            }
        } else {
            oldBill.amount
        }
        val remark = result.optString("remarks", result.optString("remark", oldBill.remark))
        val currency = result.optString("currency", oldBill.currency).ifBlank { oldBill.currency }
        val fee = result.optDouble("fee", oldBill.fee).coerceAtLeast(0.0)

        val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
            if (finalType == Bill.TYPE_INCOME) 1 else 0, categoryName
        )
        val assetEntity = if (assetName.isNotEmpty()) db.assetDao().getAssetByName(assetName) else null
        val toAssetEntity = if (toAssetName.isNotEmpty()) db.assetDao().getAssetByName(toAssetName) else null

        val newBill = oldBill.copy(
            type = finalType,
            subType = subType,
            amount = amount,
            currency = currency,
            fee = fee,
            categoryId = categoryEntity?.id ?: oldBill.categoryId,
            accountId = assetEntity?.id ?: oldBill.accountId,
            toAccountId = toAssetEntity?.id ?: oldBill.toAccountId,
            categoryName = categoryName,
            accountName = assetName,
            toAccountName = toAssetName,
            time = timeLong,
            remark = remark,
            // 修改现有账单时，保持原账本不变，避免跨账本误迁移。
            bookName = oldBill.bookName
        )

        val changes = buildChangePreviewLines(oldBill, newBill)
        if (changes.isEmpty()) {
            appendAiTextMessage("这次没有检测到实际变化，我先不修改。", false, oldBill.bookName, getCurrentConversationId())
            return
        }
        val existingRenderedItemIndex = displayMessages.indexOfFirst { item ->
            item.msgType == ChatActivity.MSG_TYPE_AI_BILL &&
                item.billInteractionMode == ChatActivity.BILL_INTERACTION_NONE &&
                item.bills.none { it.id < 0L } &&
                item.bills.any { it.id == oldBill.id }
        }
        val confirmed = confirmBillModifyPreview(oldBill, newBill, changes)
        if (!confirmed) {
            // 取消状态由聊天内确认卡片承载，不再重复追加文本消息。
            return
        }

        val savedBill = withContext(Dispatchers.IO) {
            tao.test.tapaccounting.logic.BillMutationService.replaceBill(db, oldBill, newBill)
        }
        val persistedBill = withContext(Dispatchers.IO) {
            db.billDao().getBillById(savedBill.id)
        } ?: run {
            appendAiTextMessage("修改失败：账单保存后未找到记录。", false, oldBill.bookName, getCurrentConversationId())
            return
        }
        if (buildChangePreviewLines(oldBill, persistedBill).isEmpty()) {
            appendAiTextMessage("修改失败：数据库内容未发生变化。", false, oldBill.bookName, getCurrentConversationId())
            return
        }

        if (existingRenderedItemIndex >= 0) {
            val oldItem = displayMessages[existingRenderedItemIndex]
            val updatedEditedIds = oldItem.editedBillIds.toMutableSet().apply { add(oldBill.id) }
            val snapshotOnly = ChatBillMessageParser.parseSnapshotOnlyFromContent(oldItem.content)
            val updatedContent = buildBillMessageContent(
                oldItem.bills,
                oldItem.deprecatedBillIds,
                updatedEditedIds,
                snapshotOnly
            )
            displayMessages[existingRenderedItemIndex] = oldItem.copy(
                content = updatedContent,
                editedBillIds = updatedEditedIds
            )
            adapterProvider().notifyItemChanged(existingRenderedItemIndex)
            if (oldItem.dbId > 0L) {
                withContext(Dispatchers.IO) {
                    db.chatMessageDao().getById(oldItem.dbId)?.let { msg ->
                        db.chatMessageDao().update(msg.copy(content = updatedContent))
                    }
                }
            }
        }

        val compareItemIndex = displayMessages.indexOfLast { item ->
            item.msgType == ChatActivity.MSG_TYPE_AI_BILL &&
                item.bills.size >= 2 &&
                item.bills.any { it.id < 0L } &&
                item.bills.any { it.id == oldBill.id }
        }
        if (compareItemIndex >= 0) {
            val compareItem = displayMessages[compareItemIndex]
            val updatedBills = compareItem.bills.toMutableList()
            val newBillIndex = updatedBills.indexOfLast { it.id == oldBill.id }
            if (newBillIndex >= 0) {
                updatedBills[newBillIndex] = persistedBill
            }
            val beforeBillId = updatedBills.firstOrNull { it.id < 0L }?.id ?: updatedBills.firstOrNull()?.id ?: 0L
            val updatedDeprecatedIds = compareItem.deprecatedBillIds.toMutableSet().apply {
                clear()
                if (beforeBillId != 0L) add(beforeBillId)
            }
            val updatedContent = buildBillMessageContent(
                updatedBills,
                updatedDeprecatedIds,
                compareItem.editedBillIds,
                true
            )
            displayMessages[compareItemIndex] = compareItem.copy(
                content = updatedContent,
                bills = updatedBills,
                deprecatedBillIds = updatedDeprecatedIds,
                billHint = "",
                billInteractionMode = ChatActivity.BILL_INTERACTION_NONE,
                billInteractionToken = ""
            )
            adapterProvider().notifyItemChanged(compareItemIndex)
            if (compareItem.dbId > 0L) {
                withContext(Dispatchers.IO) {
                    db.chatMessageDao().getById(compareItem.dbId)?.let { msg ->
                        db.chatMessageDao().update(msg.copy(content = updatedContent))
                    }
                }
            }
        }

        appendAiTextMessage("已确认修改，已保存。", false, oldBill.bookName, getCurrentConversationId())
        scrollToBottom()
        refreshSessionRows()
    }
}
