package tao.test.flipaccounting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tao.test.flipaccounting.chat.ai.AiBookkeepingMode
import tao.test.flipaccounting.chat.ai.AiIntentRouter
import tao.test.flipaccounting.chat.ai.AiIntentType
import tao.test.flipaccounting.data.local.entity.Bill
import tao.test.flipaccounting.data.local.entity.ChatMessage
import java.io.File
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.coroutines.coroutineContext

data class ChatRequestContext(
    val requestId: String,
    val bookName: String,
    val conversationId: String,
    val startedAt: Long,
    val loadingUiKey: String? = null
)

class ChatMessagePipeline(
    private val context: ChatActivity,
    private val aiWorkScope: CoroutineScope,
    private val getInputText: () -> String,
    private val clearInput: () -> Unit,
    private val updateInputActionUi: () -> Unit,
    private val appendUserMessage: (String, Int) -> Unit,
    private val consumePendingHabitSuggestionReply: (String) -> Boolean,
    private val appendAiTextMessage: (String, Boolean, String?, String?) -> String,
    private val removeLoadingMessage: (String) -> Unit,
    private val updateLoadingMessage: (String, String) -> Unit,
    private val finalizeLoadingMessage: (String, String, String, String) -> Unit,
    private val buildAnalysisInput: suspend (String) -> String,
    private val processBillResult: suspend (JSONObject, String, String, String) -> List<Bill>,
    private val processBillModifyResult: suspend (JSONObject, String, tao.test.flipaccounting.data.local.entity.Bill) -> Unit,
    private val buildBillSummary: (List<Bill>) -> String,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val chooseModifyTargetBill: suspend (String, List<Bill>) -> Bill?,
    private val persistAiTextMessage: suspend (String, String, String) -> Unit,
    private val db: tao.test.flipaccounting.data.local.AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String
) {
    private var isUserTextDispatching: Boolean = false
    private var activeRequestJob: Job? = null
    private var activeRequestContext: ChatRequestContext? = null

    private fun newRequestContext(loadingUiKey: String? = null): ChatRequestContext {
        return ChatRequestContext(
            requestId = UUID.randomUUID().toString(),
            bookName = getCurrentBookName(),
            conversationId = getCurrentConversationId(),
            startedAt = System.currentTimeMillis(),
            loadingUiKey = loadingUiKey
        )
    }

    private fun registerActiveJob(job: Job, ctx: ChatRequestContext) {
        activeRequestJob?.cancel()
        activeRequestJob = job
        activeRequestContext = ctx
    }

    private fun isRequestStillActive(ctx: ChatRequestContext): Boolean {
        return activeRequestContext?.requestId == ctx.requestId &&
            activeRequestJob?.isActive == true &&
            isUiAlive()
    }

    private fun isRequestStillInCurrentConversation(ctx: ChatRequestContext): Boolean {
        return ctx.bookName == getCurrentBookName() && ctx.conversationId == getCurrentConversationId()
    }

    private fun canWriteForRequest(ctx: ChatRequestContext): Boolean {
        return isRequestStillActive(ctx) && isRequestStillInCurrentConversation(ctx)
    }

    private fun clearActiveRequestIfMatch(ctx: ChatRequestContext) {
        if (activeRequestContext?.requestId == ctx.requestId) {
            activeRequestJob = null
            activeRequestContext = null
        }
    }

    fun cancelCurrentRequest(showInterruptedMessage: Boolean = true) {
        val ctx = activeRequestContext
        activeRequestJob?.cancel()
        activeRequestJob = null
        activeRequestContext = null
        isUserTextDispatching = false
        ctx?.loadingUiKey?.let { key ->
            runOnUiIfAlive { removeLoadingMessage(key) }
        }
        if (showInterruptedMessage && ctx != null && isRequestStillInCurrentConversation(ctx)) {
            appendAiTextMessage("已中断本次请求。", false, ctx.bookName, ctx.conversationId)
        }
    }

    private fun isUiAlive(): Boolean = !(context.isDestroyed || context.isFinishing)

    private fun runOnUiIfAlive(block: () -> Unit) {
        if (!isUiAlive()) return
        context.runOnUiThread {
            if (!isUiAlive()) return@runOnUiThread
            block()
        }
    }

    fun sendText() {
        val text = getInputText().trim()
        if (text.isEmpty()) {
            Utils.toast(context, "不能发送空内容")
            return
        }
        if (isUserTextDispatching) {
            Utils.toast(context, "上一条消息还在处理中，请稍等")
            return
        }
        isUserTextDispatching = true
        clearInput()
        updateInputActionUi()
        appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
        if (consumePendingHabitSuggestionReply(text)) {
            isUserTextDispatching = false
            return
        }
        routeAndHandleText(text)
    }

    private fun routeAndHandleText(userText: String) {
        val loadingKey = appendAiTextMessage("正在理解你的消息...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val historyCtx = buildChatHistoryContext(userText, requestContext)
                val rawRoute = try {
                    withContext(Dispatchers.IO) {
                        AIService.routeIntentWithModel(context, userText, historyCtx)
                    } ?: AiIntentRouter.route(userText)
                } catch (e: Exception) {
                    Logger.d(context, "AiIntentRouter", "model route failed, fallback=local, err=${e.javaClass.simpleName}")
                    AiIntentRouter.route(userText)
                }
                val routedType = when (rawRoute.intentType) {
                    AiIntentType.BOOKKEEPING -> AiIntentType.BOOKKEEPING
                    AiIntentType.MODIFY_BILL -> AiIntentType.MODIFY_BILL
                    AiIntentType.UNKNOWN -> AiIntentType.UNKNOWN
                    AiIntentType.QUERY,
                    AiIntentType.GENERAL_CHAT -> AiIntentType.GENERAL_CHAT
                }
                Logger.d(
                    context,
                    "AiIntentRouter",
                    "raw=\"$userText\", intent=$routedType, rawIntent=${rawRoute.intentType}, confidence=${rawRoute.confidence}, slots=${rawRoute.slots}"
                )
                if (!isActive || !canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                when (routedType) {
                    AiIntentType.BOOKKEEPING -> callAiAccounting(
                        userText,
                        appendUserBubble = false,
                        bookkeepingMode = rawRoute.bookkeepingMode
                    )
                    AiIntentType.MODIFY_BILL -> callAiAccountingModify(userText)
                    AiIntentType.QUERY -> handleLocalQuery(rawRoute, userText, requestContext)
                    AiIntentType.UNKNOWN -> {
                        if (AiIntentRouter.isHighRiskWrite(userText)) {
                            appendUnknownIntentReply(userText, requestContext)
                        } else {
                            callGeneralChat(userText, historyCtx)
                        }
                    }
                    AiIntentType.GENERAL_CHAT -> callGeneralChat(userText, historyCtx)
                }
            } finally {
                isUserTextDispatching = false
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private fun appendUnknownIntentReply(userText: String, requestContext: ChatRequestContext? = null) {
        val msg = "这个请求我不太确定该怎么安全处理。涉及删除、批量修改或覆盖之类的操作时，我需要你先明确说明并二次确认。"
        if (requestContext == null || canWriteForRequest(requestContext)) {
            appendAiTextMessage(msg, false, requestContext?.bookName, requestContext?.conversationId)
        }
        Logger.d(context, "AiIntentRouter", "unknown intent, raw=\"$userText\"")
    }

    private suspend fun handleLocalQuery(
        route: tao.test.flipaccounting.chat.ai.AiRouteResult,
        userText: String,
        requestContext: ChatRequestContext
    ) {
        if (!canWriteForRequest(requestContext)) return
        val slots = route.slots
        val normalized = userText.replace("\\s+".toRegex(), "")
        val asksLatest = listOf("上一笔", "前一笔", "刚刚那笔", "刚才那笔", "最近一笔").any { normalized.contains(it) }
        val reply = withContext(Dispatchers.IO) {
            val writableBook = BookAccountManager.resolveWritableBook(context, requestContext.bookName)
            val bills = if (asksLatest) {
                db.billDao().getRecentBillsByBookName(writableBook, 1)
            } else {
                val range = slots.timeRange
                if (range == null) {
                    return@withContext "我不确定你要查询的时间范围。可以说得更明确一点，比如“本月花了多少”或“上周餐饮支出”。"
                }
                db.billDao().getBillsByBookNamesBetweenTimesList(
                    listOf(writableBook),
                    range.startMillis,
                    range.endMillis
                )
            }.filter { bill ->
                val accountMatched = slots.account?.let { account ->
                    bill.accountName.contains(account, ignoreCase = true) ||
                        bill.toAccountName.contains(account, ignoreCase = true)
                } ?: true
                val categoryMatched = slots.category?.let { category ->
                    bill.categoryName.contains(category, ignoreCase = true) ||
                        bill.remark.contains(category, ignoreCase = true)
                } ?: true
                accountMatched && categoryMatched
            }

            if (bills.isEmpty()) {
                val rangeText = if (asksLatest) "最近" else slots.timeRange?.phrase.orEmpty().ifBlank { "这个范围内" }
                return@withContext "${rangeText}没有查到匹配账单。"
            }

            val expense = bills
                .filter { it.type == Bill.TYPE_EXPENSE && it.subType != Bill.SUBTYPE_REFUND }
                .sumOf { it.amount }
            val income = bills
                .filter { it.type == Bill.TYPE_INCOME }
                .sumOf { it.amount }
            val transfer = bills
                .filter { it.type == Bill.TYPE_TRANSFER }
                .sumOf { it.amount }
            val latest = bills.maxByOrNull { it.time }
            val rangeText = if (asksLatest) {
                "最近一笔"
            } else {
                slots.timeRange?.phrase.orEmpty().ifBlank { "查询范围内" }
            }
            buildString {
                append("${rangeText}共命中 ${bills.size} 笔账单。")
                append("\n支出合计：${String.format(Locale.getDefault(), "%.2f", expense)}")
                append("\n收入合计：${String.format(Locale.getDefault(), "%.2f", income)}")
                if (transfer > 0.0) append("\n转账合计：${String.format(Locale.getDefault(), "%.2f", transfer)}")
                latest?.let { bill ->
                    val typeText = when (bill.type) {
                        Bill.TYPE_INCOME -> "收入"
                        Bill.TYPE_TRANSFER -> "转账"
                        else -> "支出"
                    }
                    append("\n最近：$typeText ${String.format(Locale.getDefault(), "%.2f", bill.amount)}，${bill.remark.ifBlank { bill.categoryName.ifBlank { "未分类" } }}")
                }
            }
        }
        if (!canWriteForRequest(requestContext)) return
        appendAiTextMessage(reply, false, requestContext.bookName, requestContext.conversationId)
    }

    private suspend fun buildChatHistoryContext(userText: String, requestContext: ChatRequestContext? = null): String {
        return withContext(Dispatchers.IO) {
            val book = requestContext?.bookName ?: getCurrentBookName()
            val convId = requestContext?.conversationId ?: getCurrentConversationId()
            val recent = db.chatMessageDao().getRecentMessages(book, convId, 12).toMutableList()
            if (recent.isEmpty()) return@withContext ""

            val currentUserText = userText.trim()
            if (recent.isNotEmpty()) {
                val last = recent.last()
                val lastText = last.content.trim()
                if (last.msgType in 0..2 && lastText.isNotEmpty() && lastText == currentUserText) {
                    recent.removeAt(recent.lastIndex)
                }
            }

            val lines = recent
                .takeLast(8)
                .mapNotNull { msg ->
                    val summary = summarizeHistoryMessage(msg)
                    if (summary.isBlank()) return@mapNotNull null
                    val role = if (msg.msgType in 0..2) "用户" else "AI"
                    "[$role] $summary"
                }
            if (lines.isEmpty()) return@withContext ""
            lines.joinToString("\n")
        }
    }

    private fun summarizeHistoryMessage(msg: ChatMessage): String {
        val raw = msg.content.trim()
        if (raw.isBlank()) return ""
        return when (msg.msgType) {
            ChatActivity.MSG_TYPE_USER_TEXT -> compactHistoryText(raw)
            ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片消息]"
            ChatActivity.MSG_TYPE_USER_VOICE -> {
                val transcript = runCatching {
                    JSONObject(raw).optString("transcript").trim()
                }.getOrDefault("")
                if (transcript.isNotBlank()) {
                    "语音：${compactHistoryText(transcript, 160)}"
                } else {
                    "[语音消息]"
                }
            }
            ChatActivity.MSG_TYPE_AI_BILL -> "[账单结果]"
            ChatActivity.MSG_TYPE_AI_TEXT -> compactHistoryText(raw)
            else -> compactHistoryText(raw)
        }
    }

    private fun compactHistoryText(text: String, maxLen: Int = 180): String {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) return normalized
        return normalized.take(maxLen).trimEnd() + "…"
    }

    private fun callGeneralChat(userText: String, historyCtx: String = "") {
        val loadingKey = appendAiTextMessage("正在生成回复...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        val streamed = StringBuilder()
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val ctx = if (historyCtx.isNotBlank()) historyCtx else buildChatHistoryContext(userText, requestContext)
                val result = withContext(Dispatchers.IO) {
                    AIService.generateGeneralChatReply(context, userText, ctx) { delta ->
                        if (delta.isNotBlank()) {
                            streamed.append(delta)
                            runOnUiIfAlive {
                                if (canWriteForRequest(requestContext)) {
                                    updateLoadingMessage(loadingKey, streamed.toString())
                                }
                            }
                        }
                    }
                }
                if (!canWriteForRequest(requestContext)) return@launch
                val finalText = streamed.toString().trim().ifBlank { result.content.trim() }
                if (result.completed && finalText.isNotBlank()) {
                    finalizeLoadingMessage(loadingKey, finalText, requestContext.bookName, requestContext.conversationId)
                } else {
                    removeLoadingMessage(loadingKey)
                    val message = if (finalText.isNotBlank()) {
                        "回复中断，请重试。"
                    } else {
                        "我在呢。你可以直接跟我说要记什么账，或者问我某段时间的支出。"
                    }
                    appendAiTextMessage(message, false, requestContext.bookName, requestContext.conversationId)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                if (isRequestStillInCurrentConversation(requestContext)) removeLoadingMessage(loadingKey)
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, false, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private fun callAiAccountingModify(userText: String) {
        val loadingKey = appendAiTextMessage("正在修改账单...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        var loadingRemoved = false
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val book = requestContext.bookName
                val convId = requestContext.conversationId
                val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())

                val candidateBills = withContext(Dispatchers.IO) {
                    resolveModifyCandidates(userText, book, convId)
                }
                if (candidateBills.isEmpty()) {
                    if (!loadingRemoved) {
                        removeLoadingMessage(loadingKey)
                        loadingRemoved = true
                    }
                    val msg = "没有找到可以修改的账单，请先记一笔账。"
                    if (canWriteForRequest(requestContext)) appendAiTextMessage(msg, false, book, convId)
                    return@launch
                }

                if (!loadingRemoved) {
                    removeLoadingMessage(loadingKey)
                    loadingRemoved = true
                }
                if (!canWriteForRequest(requestContext)) return@launch
                val targetBill = chooseModifyTargetBill(userText, candidateBills)
                if (targetBill == null) {
                    // 取消状态已在聊天内交互卡片中展示，这里不再追加重复文本消息。
                    return@launch
                }

                val applyLoadingKey = appendAiTextMessage("正在应用修改...", true, book, convId)
                val billsJsonArray = org.json.JSONArray()
                billsJsonArray.put(org.json.JSONObject().apply {
                    put("bill_db_id", targetBill.id)
                    put("amount", targetBill.amount)
                    put("type", targetBill.type)
                    put("asset_name", targetBill.accountName)
                    put("to_asset_name", targetBill.toAccountName)
                    put("category_name", targetBill.categoryName)
                    put("time", fmt.format(java.util.Date(targetBill.time)))
                    put("remarks", targetBill.remark)
                    put("currency", targetBill.currency)
                    put("fee", targetBill.fee)
                })

                val modifiedJson = withContext(Dispatchers.IO) {
                    AIService.generateAccountingModifyReply(context, userText, billsJsonArray.toString())
                }
                if (!canWriteForRequest(requestContext)) {
                    removeLoadingMessage(applyLoadingKey)
                    return@launch
                }
                removeLoadingMessage(applyLoadingKey)
                Logger.d(
                    context,
                    "ModifyBill",
                    "modify reply raw=$modifiedJson"
                )

                val jsonObjectText = extractFirstJsonObjectText(cleanJsonString(modifiedJson))
                val parsedJson = runCatching { jsonObjectText?.let { org.json.JSONObject(it) } }.getOrNull()
                if (parsedJson == null || jsonObjectText.isNullOrBlank()) {
                    Logger.d(
                        context,
                        "ModifyBill",
                        "modify reply parse failed, raw=${modifiedJson.take(500)}"
                    )
                    val msg = "AI 返回的修改结果无法解析，请再试一次。"
                    appendAiTextMessage(msg, false, book, convId)
                    return@launch
                }

                if (parsedJson.optBoolean("no_match", false)) {
                    val msg = "我没能理解这次修改，可以换个说法再试试。"
                    appendAiTextMessage(msg, false, book, convId)
                    return@launch
                }

                // 目标账单由用户显式选择，强制覆盖为该 id，避免模型改错单。
                parsedJson.put("bill_db_id", targetBill.id)
                processBillModifyResult(parsedJson, userText, targetBill)
            } catch (_: kotlinx.coroutines.CancellationException) {
                if (!loadingRemoved) {
                    removeLoadingMessage(loadingKey)
                }
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                if (!loadingRemoved) {
                    removeLoadingMessage(loadingKey)
                    loadingRemoved = true
                }
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, false, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private suspend fun resolveModifyCandidates(userText: String, book: String, convId: String): List<Bill> {
        val candidates = LinkedHashMap<Long, Bill>()
        loadLatestAiBatchBills(book, convId).forEach { bill ->
            candidates[bill.id] = bill
        }
        val writableBook = BookAccountManager.resolveWritableBook(context, book)
        db.billDao().getRecentBillsByBookName(writableBook, 60).forEach { bill ->
            candidates.putIfAbsent(bill.id, bill)
        }
        val merged = candidates.values.toList()
        if (merged.isEmpty()) return emptyList()
        return rankModifyCandidates(userText, merged).take(3)
    }

    private suspend fun loadLatestAiBatchBills(book: String, convId: String): List<Bill> {
        val lastBillMsg = db.chatMessageDao().getLatestMessageByType(book, convId, ChatActivity.MSG_TYPE_AI_BILL)
            ?: return emptyList()
        val ids = runCatching {
            val arr = org.json.JSONArray(lastBillMsg.billIds)
            (0 until arr.length()).mapNotNull { arr.optString(it).toLongOrNull() }
        }.getOrElse { emptyList() }
        return ids.mapNotNull { id -> db.billDao().getBillById(id) }
    }

    private fun rankModifyCandidates(userText: String, bills: List<Bill>): List<Bill> {
        val normalizedInput = userText.lowercase(Locale.getDefault())
        val inputNoSpace = normalizedInput.replace(Regex("\\s+"), "")
        val amountHint = extractAmountHint(normalizedInput)
        val tokens = extractSemanticTokens(normalizedInput)
        val relativeWords = listOf("上次", "上一笔", "前一笔", "刚才那笔", "刚刚那笔", "刚刚", "刚才")
        val hasRelativeHint = relativeWords.any { normalizedInput.contains(it) }

        val scored = bills.mapIndexed { index, bill ->
            val remarkText = bill.remark.lowercase(Locale.getDefault())
            val remarkNoSpace = remarkText.replace(Regex("\\s+"), "")
            val categoryText = bill.categoryName.lowercase(Locale.getDefault())
            val accountText = listOf(bill.accountName, bill.toAccountName)
                .joinToString(" ")
                .lowercase(Locale.getDefault())

            var score = 0
            var remarkHitCount = 0
            val exactRemarkMention = remarkNoSpace.length >= 2 && inputNoSpace.contains(remarkNoSpace)
            if (exactRemarkMention) {
                // 用户直接说出备注词（如“可乐”）时，优先级应绝对领先。
                score += 40
                remarkHitCount += 3
            }
            tokens.forEach { token ->
                if (token.length < 2) return@forEach
                if (remarkText.contains(token)) {
                    // 备注是最常见的“商品名/场景”来源，权重最高。
                    score += 10
                    remarkHitCount += 1
                }
                if (categoryText.contains(token)) score += 3
                if (accountText.contains(token)) score += 2
            }
            if (remarkHitCount > 0) score += 2 * remarkHitCount

            amountHint?.let { targetAmount ->
                val diff = abs(bill.amount - targetAmount)
                when {
                    diff <= 0.009 -> score += 6
                    diff <= 0.5 -> score += 2
                }
            }

            if (hasRelativeHint) {
                // 有“上次/刚才”但缺少明确关键词时，才更依赖时间先后。
                val recencyBoost = (6 - index).coerceAtLeast(0)
                score += if (tokens.isEmpty()) recencyBoost else recencyBoost / 2
            }
            CandidateScore(
                score = score,
                hasRemarkHit = remarkHitCount > 0,
                hasExactRemarkMention = exactRemarkMention,
                bill = bill
            )
        }.sortedWith(compareByDescending<CandidateScore> { it.score }.thenByDescending { it.bill.time })

        val hasStrongHint = amountHint != null || tokens.isNotEmpty()
        val hasKeywordRemarkHit = tokens.isNotEmpty() && scored.any { it.hasRemarkHit }
        val hasAnyExactRemarkMention = scored.any { it.hasExactRemarkMention }
        val filtered = if (hasStrongHint) {
            scored.filter { candidate ->
                val scorePositive = candidate.score > 0
                val preferExactRemark = !hasAnyExactRemarkMention || candidate.hasExactRemarkMention
                val preferRemark = !hasKeywordRemarkHit || candidate.hasRemarkHit || amountHint != null
                scorePositive && preferExactRemark && preferRemark
            }
        } else {
            scored
        }
        val ranked = filtered.map { it.bill }
        return if (ranked.isNotEmpty()) ranked else bills
    }

    private data class CandidateScore(
        val score: Int,
        val hasRemarkHit: Boolean,
        val hasExactRemarkMention: Boolean,
        val bill: Bill
    )

    private fun extractAmountHint(text: String): Double? {
        val regex = Regex("""(?<!\d)(\d+(?:\.\d+)?)(?:元|块|块钱|rmb|cny|pln|usd|eur)?""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
    }

    private fun extractSemanticTokens(text: String): List<String> {
        val stripped = text.replace(Regex("[\\p{Punct}\\s]+"), " ")
        val rawTokens = Regex("[\\p{IsHan}]{2,}|[a-z0-9]{2,}").findAll(stripped)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()

        val stopwords = setOf(
            "上次", "上一笔", "前一笔", "刚才", "刚刚", "那笔", "这个", "那个",
            "改成", "改为", "改下", "修改", "补充", "一下", "多少", "多少钱", "其实",
            "把", "的", "是", "我", "了", "请", "帮我"
        )
        return rawTokens
            .filterNot { token -> stopwords.contains(token) }
            .distinct()
    }

    fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        bookkeepingMode: AiBookkeepingMode = AiBookkeepingMode.UNSPECIFIED,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, ChatActivity.MSG_TYPE_USER_TEXT)
        val loadingKey = loadingIdxOverride ?: appendAiTextMessage(
            "正在理解你的消息...",
            true,
            getCurrentBookName(),
            getCurrentConversationId()
        )
        val requestContext = newRequestContext(loadingKey)
        var loadingStage = 1
        val streamedRaw = StringBuilder()
        fun pushLoadingStatus(raw: String) {
            if (!canWriteForRequest(requestContext)) return
            if (raw.startsWith("AI_STREAM_TEXT::")) {
                val delta = raw.removePrefix("AI_STREAM_TEXT::")
                if (delta.isNotBlank()) {
                    streamedRaw.append(delta)
                    updateLoadingMessage(loadingKey, formatStreamingBillPreview(streamedRaw.toString()))
                }
                return
            }
            val (stage, text) = mapProgressToNaturalStatus(raw)
            val nextStage = maxOf(loadingStage, stage)
            loadingStage = nextStage
            val stableText = when (nextStage) {
                1 -> "正在理解你的消息..."
                2 -> "正在生成回复..."
                else -> text
            }
            updateLoadingMessage(loadingKey, stableText)
        }
        if (loadingIdxOverride != null && loadingBootstrapText.isNotBlank()) {
            updateLoadingMessage(loadingKey, loadingBootstrapText)
        }
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val analysisInput = buildAnalysisInput(userText)
                val autoMultiMode = resolveMultiMode(bookkeepingMode, userText)
                val result = try {
                    withContext(Dispatchers.IO) {
                        if (userText.startsWith("[MULTIMODAL_IMAGE]")) {
                            val payload = userText.removePrefix("[MULTIMODAL_IMAGE]")
                            val parts = payload.split("|", limit = 2)
                            val base64 = parts.getOrElse(0) { "" }
                            val mime = parts.getOrElse(1) { "image/jpeg" }
                            val visionResult = AIService.analyzeReceiptByImage(context, base64, mime)
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = visionResult,
                                isMultiModeOverride = autoMultiMode
                            ) { status ->
                                runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                            }
                        } else {
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = analysisInput,
                                isMultiModeOverride = autoMultiMode
                            ) { status ->
                                runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                if (result == null) {
                    val replied = appendAssistantCompanionReply(userText, billSummary = "", extractorReplyHint = "", requestContext)
                    if (forceTextReply && !replied) {
                        appendAiTextMessage(
                            "我这次没能正确解析，但已经收到你的语音转写文本。你可以再说得更具体一点，我继续帮你记账。",
                            false,
                            requestContext.bookName,
                            requestContext.conversationId
                        )
                    }
                    return@launch
                }
                if (result.optBoolean("no_bill", false)) {
                    val hint = result.optString("reply", "").trim()
                    val replied = appendAssistantCompanionReply(
                        userText = userText,
                        billSummary = "",
                        extractorReplyHint = hint,
                        requestContext = requestContext
                    )
                    if (forceTextReply && !replied) {
                        appendAiTextMessage(
                            hint.ifBlank { "我暂时没识别到明确账单，你可以补充金额、分类或账户，我继续帮你完成。"},
                            false,
                            requestContext.bookName,
                            requestContext.conversationId
                        )
                    }
                    return@launch
                }
                val savedBills = processBillResult(
                    result,
                    userText,
                    requestContext.bookName,
                    requestContext.conversationId
                )
                if (!canWriteForRequest(requestContext)) return@launch
                if (savedBills.isNotEmpty()) {
                    appendAssistantCompanionReply(
                        userText = userText,
                        billSummary = buildBillSummary(savedBills),
                        extractorReplyHint = "",
                        requestContext = requestContext
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                removeLoadingMessage(loadingKey)
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, false, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    fun callAiAccountingWithVoice(audioFile: File) {
        val loadingKey = appendAiTextMessage("正在理解你的消息...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        var loadingStage = 1
        val streamedRaw = StringBuilder()
        fun pushLoadingStatus(raw: String) {
            if (!canWriteForRequest(requestContext)) return
            if (raw.startsWith("AI_STREAM_TEXT::")) {
                val delta = raw.removePrefix("AI_STREAM_TEXT::")
                if (delta.isNotBlank()) {
                    streamedRaw.append(delta)
                    updateLoadingMessage(loadingKey, formatStreamingBillPreview(streamedRaw.toString()))
                }
                return
            }
            val (stage, text) = mapProgressToNaturalStatus(raw)
            loadingStage = maxOf(loadingStage, stage)
            val stableText = when (loadingStage) {
                1 -> "正在理解你的消息..."
                2 -> "正在生成回复..."
                else -> text
            }
            updateLoadingMessage(loadingKey, stableText)
        }
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val voiceUserText = "[语音输入]"
                val autoMultiMode = withContext(Dispatchers.IO) {
                    val transcript = transcribeVoiceToTextWithFallback(audioFile)
                    resolveMultiMode(AiIntentRouter.route(transcript).bookkeepingMode, transcript)
                }
                val result = try {
                    withContext(Dispatchers.IO) {
                        AIService.analyzeAccountingByAudio(
                            ctx = context,
                            audioFile = audioFile,
                            isMultiModeOverride = autoMultiMode
                        ) { status ->
                            runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                if (result == null) {
                    val replied = appendAssistantCompanionReply(voiceUserText, billSummary = "", extractorReplyHint = "", requestContext)
                    if (!replied) {
                        appendAiTextMessage(
                            "我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。",
                            false,
                            requestContext.bookName,
                            requestContext.conversationId
                        )
                    }
                    return@launch
                }
                if (result.optBoolean("no_bill", false)) {
                    val hint = result.optString("reply", "").trim()
                    val replied = appendAssistantCompanionReply(
                        userText = voiceUserText,
                        billSummary = "",
                        extractorReplyHint = hint,
                        requestContext = requestContext
                    )
                    if (!replied) {
                        appendAiTextMessage(
                            hint.ifBlank { "我暂时没识别到明确账单，你可以补充金额、分类或账户。"},
                            false,
                            requestContext.bookName,
                            requestContext.conversationId
                        )
                    }
                    return@launch
                }
                val savedBills = processBillResult(
                    result,
                    voiceUserText,
                    requestContext.bookName,
                    requestContext.conversationId
                )
                if (!canWriteForRequest(requestContext)) return@launch
                if (savedBills.isNotEmpty()) {
                    appendAssistantCompanionReply(
                        userText = voiceUserText,
                        billSummary = buildBillSummary(savedBills),
                        extractorReplyHint = "",
                        requestContext = requestContext
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                removeLoadingMessage(loadingKey)
            } catch (e: Exception) {
                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                val msg = mapAiErrorToUserMessage(e)
                appendAiTextMessage(msg, false, requestContext.bookName, requestContext.conversationId)
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private fun resolveMultiMode(bookkeepingMode: AiBookkeepingMode, userText: String): Boolean {
        return when (bookkeepingMode) {
            AiBookkeepingMode.MULTI -> true
            AiBookkeepingMode.SINGLE -> false
            AiBookkeepingMode.UNSPECIFIED -> AiIntentRouter.detectBookkeepingMode(userText) == AiBookkeepingMode.MULTI
        }
    }

    private suspend fun appendAssistantCompanionReply(
        userText: String,
        billSummary: String,
        extractorReplyHint: String,
        requestContext: ChatRequestContext
    ): Boolean {
        if (Prefs.getAiChatReplyStyle(context) == "off") return false
        if (!canWriteForRequest(requestContext)) return false
        val editingKey = appendAiTextMessage("", true, requestContext.bookName, requestContext.conversationId)
        val streamed = StringBuilder()
        val historyCtx = buildChatHistoryContext(userText, requestContext)
        val streamOk = try {
            withContext(Dispatchers.IO) {
                AIService.streamAccountingAssistantReply(
                    ctx = context,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint,
                    chatHistoryContext = historyCtx
                ) { delta ->
                    if (delta.isNotBlank()) {
                        streamed.append(delta)
                        runOnUiIfAlive {
                            if (canWriteForRequest(requestContext)) {
                                updateLoadingMessage(editingKey, streamed.toString())
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            false
        }

        if (!canWriteForRequest(requestContext)) {
            removeLoadingMessage(editingKey)
            return false
        }

        if (streamOk && streamed.isNotBlank()) {
            finalizeLoadingMessage(
                editingKey,
                sanitizeAssistantReply(streamed.toString().trim()),
                requestContext.bookName,
                requestContext.conversationId
            )
            return true
        }

        val reply = try {
            withContext(Dispatchers.IO) {
                AIService.generateAccountingAssistantReply(
                    ctx = context,
                    userInput = userText,
                    billSummary = billSummary,
                    extractorReplyHint = extractorReplyHint,
                    chatHistoryContext = historyCtx
                )
            }.trim()
        } catch (_: Exception) {
            ""
        }
        if (!canWriteForRequest(requestContext)) {
            removeLoadingMessage(editingKey)
            return false
        }
        removeLoadingMessage(editingKey)
        val sanitized = sanitizeAssistantReply(reply)
        if (sanitized.isNotBlank()) {
            appendAiTextMessage(sanitized, false, requestContext.bookName, requestContext.conversationId)
            return true
        }
        return false
    }

    private fun sanitizeAssistantReply(reply: String): String {
        var text = reply.trim()
        if (text.equals("BILL_SAVED", ignoreCase = true) || text.equals("NO_BILL", ignoreCase = true)) {
            return ""
        }
        text = text.replace(Regex("^\\s*(BILL_SAVED|NO_BILL|SCENE)\\s*[:：-]?\\s*", RegexOption.IGNORE_CASE), "")
        return text.trim()
    }

    private fun mapAiErrorToUserMessage(error: Exception): String {
        val raw = error.message.orEmpty()
        val normalized = raw.lowercase(Locale.getDefault())
        return when {
            normalized.contains("http 500") || normalized.contains("500 internal") -> "网络不佳，请重试"
            normalized.contains("timeout") || normalized.contains("timed out") -> "网络不佳，请重试"
            normalized.contains("unable to resolve host") || normalized.contains("failed to connect") -> "网络不佳，请重试"
            raw.isBlank() -> "分析失败，请稍后重试"
            else -> "分析失败: $raw"
        }
    }

    private fun shouldFallbackToAssistant(error: Exception): Boolean {
        val msg = error.message.orEmpty()
        if (msg.contains("API Key")) return false
        if (msg.contains("配置")) return false
        return error is IllegalArgumentException || msg.contains("JSON", ignoreCase = true)
    }

    private fun mapProgressToNaturalStatus(raw: String): Pair<Int, String> {
        val text = raw.trim()
        if (text.isBlank()) return 1 to "正在理解你的消息..."
        val lower = text.lowercase(Locale.getDefault())

        if (text.contains("智能分类中") || text.contains("智能分析中") || text.contains("匹配中")) {
            return 3 to text
        }

        return when {
            lower.contains("reply") ||
                lower.contains("respond") ||
                lower.contains("output") ||
                lower.contains("generate") ||
                lower.contains("生成") ||
                lower.contains("回复") -> 2 to "正在生成回复..."

            lower.contains("upload") ||
                lower.contains("audio") ||
                lower.contains("image") ||
                lower.contains("ocr") ||
                lower.contains("parse") ||
                lower.contains("extract") ||
                lower.contains("analy") ||
                lower.contains("thinking") ||
                lower.contains("理解") ||
                lower.contains("分析") -> 1 to "正在理解你的消息..."

            else -> 1 to "正在理解你的消息..."
        }
    }

    private fun formatStreamingBillPreview(raw: String): String {
        val compact = raw.replace("\n", "")
        val objectRegex = Regex("\\{[^{}]*\\}")
        val objects = objectRegex.findAll(compact).map { it.value }.toList()
        if (objects.isEmpty()) return "正在识别账单..."

        val lines = mutableListOf<String>()
        var index = 1
        objects.forEach { obj ->
            val amount = extractJsonNumber(obj, "amount")
            val remark = extractJsonString(obj, "remarks")
                ?: extractJsonString(obj, "remark")
                ?: "未命名账单"
            val category = extractJsonString(obj, "category_name").orEmpty()
            if (amount == null && category.isBlank() && remark == "未命名账单") return@forEach
            val amountText = amount?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "--"
            lines += "$index. $remark  ¥$amountText"
            if (category.isNotBlank()) {
                lines += "   分类: $category"
            }
            index += 1
            if (index > 8) return@forEach
        }
        return if (lines.isEmpty()) {
            "正在识别账单..."
        } else {
            "正在识别账单...\n" + lines.joinToString("\n")
        }
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
