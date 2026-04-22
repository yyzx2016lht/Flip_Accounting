package tao.test.flipaccounting

import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tao.test.flipaccounting.data.local.AppDatabase
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import tao.test.flipaccounting.data.repository.CategoryRepository
import tao.test.flipaccounting.logic.BillAssetImpactService
import tao.test.flipaccounting.logic.BillMutationService
import java.util.Locale

class ChatBillCorrectionService(
    private val context: ChatActivity,
    private val db: AppDatabase,
    private val displayMessages: MutableList<ChatDisplayItem>,
    private val adapterProvider: () -> RecyclerView.Adapter<*>,
    private val appendAiTextMessage: (String, Boolean) -> Unit,
    private val scrollToBottom: () -> Unit,
    private val refreshSessionRows: suspend () -> Unit,
    private val getCurrentBookName: () -> String,
    private val setCurrentBookName: (String) -> Unit,
    private val getCurrentConversationId: () -> String,
    private val parseTimeToMillis: (String) -> Long,
    private val buildBillMessageContent: (List<Bill>, Set<Long>) -> String
) {
    fun decideSingleOrMultiForChat(text: String): Boolean {
        val normalized = text
            .removePrefix("[图片OCR文本]: ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.getDefault())
        if (normalized.isBlank()) return false

        val explicitMulti = Regex("分别|各[记来]?一笔|再来一笔|还有一笔|一共\\d+笔|两笔|三笔|多笔").containsMatchIn(normalized)
        if (explicitMulti) return true
        val explicitSingle = Regex("就这一笔|只记一笔|单笔|一笔就行|这笔就行").containsMatchIn(normalized)
        if (explicitSingle) return false

        var multiScore = 0
        val moneyUnitRegex = Regex("\\d+(?:\\.\\d{1,2})?\\s*(元|块钱|块|rmb|cny|pln|usd|eur|€|\\$)")
        val actionAmountRegex = Regex("(花了|花费|支付|付款|收了|收到|转账|还款|充值|提现|赚了|收入)\\s*\\d+(?:\\.\\d{1,2})?")
        val amountCount = maxOf(
            moneyUnitRegex.findAll(normalized).count(),
            actionAmountRegex.findAll(normalized).count()
        )
        if (amountCount >= 2) multiScore += 3

        val actionWords = listOf("买", "花", "支付", "付款", "收", "到账", "退款", "转账", "还款", "充值", "提现", "借出", "收回")
        val actionHitCount = actionWords.count { normalized.contains(it) }
        if (actionHitCount >= 2) multiScore += 2

        val connectorRegex = Regex("然后|再|又|另外|同时|并且|以及|分别|之后")
        val connectorCount = connectorRegex.findAll(normalized).count().coerceAtMost(3)
        multiScore += connectorCount

        val sentenceLikeCount = normalized
            .split(Regex("[,，。；;、\\n]+"))
            .map { it.trim() }
            .count { seg ->
                seg.isNotBlank() &&
                    (moneyUnitRegex.containsMatchIn(seg) ||
                        actionAmountRegex.containsMatchIn(seg) ||
                        actionWords.any { seg.contains(it) })
            }
        if (sentenceLikeCount >= 2) multiScore += 2

        val hasIncome = listOf("收入", "收到", "到账", "退款到账", "报销到账", "工资").any { normalized.contains(it) }
        val hasExpense = listOf("买", "花", "支付", "付款", "消费").any { normalized.contains(it) }
        val hasTransferOrRepay = listOf("转账", "还款", "还卡").any { normalized.contains(it) }
        if ((hasIncome && hasExpense) || (hasExpense && hasTransferOrRepay)) multiScore += 2

        val assetNames = Prefs.getAssets(context).mapNotNull { it.name?.trim() }.filter { it.isNotBlank() }.distinct()
        val mentionedAssetCount = assetNames.count { normalized.contains(it.lowercase(Locale.getDefault())) }
        if (mentionedAssetCount >= 2) multiScore += 1

        if (amountCount <= 1 && actionHitCount <= 1 && connectorCount == 0) multiScore -= 2
        if (amountCount == 0 && actionHitCount == 0) multiScore -= 2
        return multiScore >= 4
    }

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

    suspend fun processBillResult(result: JSONObject, _userText: String): List<Bill> {
        val rawBills = mutableListOf<JSONObject>()
        when {
            result.has("bills") -> {
                val arr = result.getJSONArray("bills")
                for (i in 0 until arr.length()) rawBills.add(arr.getJSONObject(i))
            }
            result.has("amount") -> rawBills.add(result)
            else -> {
                appendAiTextMessage("AI 返回了无法识别的格式。", false)
                return emptyList()
            }
        }
        if (rawBills.isEmpty()) {
            appendAiTextMessage("未解析到账单信息。", false)
            return emptyList()
        }

        val savedBills = mutableListOf<Bill>()
        val savedBillIds = mutableListOf<Long>()
        val activeBookName = BookAccountManager.normalizeBookName(
            getCurrentBookName().ifBlank { BookAccountManager.getSelectedBook(context) }
        )
        setCurrentBookName(activeBookName)

        withContext(Dispatchers.IO) {
            for (billJson in rawBills) {
                val timeLong = parseTimeToMillis(billJson.optString("time", ""))
                val rawType = billJson.optInt("type", 0)
                val type = when (rawType) { 0, 1, 2, 3 -> rawType; else -> 0 }
                val finalType = if (type == 3) 2 else type
                val subType = if (type == 3) Bill.SUBTYPE_REPAYMENT else Bill.SUBTYPE_NORMAL

                val categoryName = billJson.optString("category_name", "其它").replace("/::/", " > ")
                val assetName = billJson.optString("asset_name", "")
                val toAssetName = billJson.optString("to_asset_name", "")
                val amount = billJson.optDouble("amount", 0.0)
                val remark = billJson.optString("remarks", billJson.optString("remark", ""))
                val currency = billJson.optString("currency", "CNY")
                val fee = billJson.optDouble("fee", 0.0).coerceAtLeast(0.0)

                val categoryEntity = CategoryRepository(db.categoryDao()).findCategoryByDisplayName(
                    if (finalType == Bill.TYPE_INCOME) 1 else 0,
                    categoryName
                )
                val assetEntity = if (assetName.isNotEmpty()) db.assetDao().getAssetByName(assetName) else null
                val toAssetEntity = if (toAssetName.isNotEmpty()) db.assetDao().getAssetByName(toAssetName) else null
                val exchangeRate = when {
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
                val savedBill = BillMutationService.insertBillAndApplyImpact(db, bill)
                savedBillIds.add(savedBill.id)
                savedBills.add(savedBill)
            }
        }

        val billIdsJson = JSONArray(savedBillIds.map { it.toString() }).toString()
        val billsJsonArr = buildBillMessageContent(savedBills, emptySet())
        val msgId = withContext(Dispatchers.IO) {
            db.chatMessageDao().insert(
                ChatMessage(
                    msgType = ChatActivity.MSG_TYPE_AI_BILL,
                    content = billsJsonArr,
                    billIds = billIdsJson,
                    modelName = Prefs.getAiChatModel(context),
                    bookName = activeBookName,
                    conversationId = getCurrentConversationId()
                )
            )
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
}
