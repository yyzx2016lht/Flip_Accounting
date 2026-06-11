package com.taostudio.tapaccounting

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.taostudio.tapaccounting.data.local.entity.ChatMessage
import com.taostudio.tapaccounting.chat.agent.AgentToolRegistrar
import com.taostudio.tapaccounting.chat.agent.ChatAgentOrchestrator
import com.taostudio.tapaccounting.chat.agent.ChatConversationMode
import com.taostudio.tapaccounting.chat.agent.UiAction
import java.util.ArrayDeque
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
    private val decideSingleOrMultiForChat: (String) -> Boolean,
    private val processBillResult: suspend (JSONObject, String, String, String) -> List<Bill>,
    private val confirmVisualAccountingDraft: suspend (String, String, String) -> String?,
    private val processBillModifyResult: suspend (JSONObject, String, com.taostudio.tapaccounting.data.local.entity.Bill) -> Unit,
    private val buildBillSummary: (List<Bill>) -> String,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val chooseModifyTargetBill: suspend (String, List<Bill>) -> Bill?,
    private val persistAiTextMessage: suspend (String, String, String) -> Unit,
    private val db: com.taostudio.tapaccounting.data.local.AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String
) {
    companion object {
        private const val CHAT_ROUTE_LOG_TAG = "AiChatRoute"
        private const val REPEAT_REPLY_STATS_LOG_TAG = "RepeatReplyStats"
        private const val REPEAT_REPLY_WINDOW_SIZE = 3
        private const val REPEAT_REPLY_MAX_KEYS = 24
        private const val HIGH_SIMILARITY_THRESHOLD = 0.82
        private const val CHAT_HISTORY_FETCH_LIMIT = 80
        private const val CHAT_HISTORY_MAX_TURNS = 40
        private const val CHAT_HISTORY_MAX_TOTAL_CHARS = 48_000
        private const val MAX_CHAT_HISTORY_TURN_CHARS = 6000
        private const val MAX_CHAT_HISTORY_VOICE_CHARS = 400

        fun mapAgentErrorToUserMessage(errorMessage: String?): String {
            val msg = errorMessage ?: ""
            return when {
                msg.contains("401") || msg.contains("unauthorized", ignoreCase = true) ->
                    "API 认证失败，请检查 API Key 是否正确"
                msg.contains("429") || msg.contains("rate limit", ignoreCase = true) ->
                    "请求过于频繁，请稍后重试"
                msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("Connection timed out", ignoreCase = true) ->
                    "请求超时，请稍后重试"
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                    msg.contains("Failed to connect", ignoreCase = true) ->
                    "网络连接失败，请检查网络设置"
                msg.contains("500") || msg.contains("502") || msg.contains("503") ->
                    "服务暂时不可用，请稍后重试"
                msg.isBlank() -> "服务异常，请稍后重试"
                else -> "操作失败，请稍后重试"
            }
        }

        fun truncateHistory(messages: List<Pair<Int, String>>, maxTotalChars: Int): List<Pair<Int, String>> {
            if (messages.isEmpty()) return emptyList()

            // Calculate total chars
            val totalChars = messages.sumOf { it.second.length }
            if (totalChars <= maxTotalChars) return messages

            // Drop oldest messages first, but keep at least one user message
            var remaining = maxTotalChars
            val result = mutableListOf<Pair<Int, String>>()
            for (msg in messages.reversed()) {
                val len = msg.second.length
                if (remaining - len < 0 && result.isNotEmpty()) {
                    // If this is a user message and result is empty, truncate instead of drop
                    if (result.isEmpty() && msg.first in 0..2) {
                        val truncated = msg.second.take((remaining - 1).coerceAtLeast(1)) + "…"
                        result.add(0, msg.first to truncated)
                        remaining = 0
                    }
                    break
                }
                if (remaining - len < 0 && msg.first in 0..2) {
                    // Oversized user message: truncate
                    val truncated = msg.second.take((remaining - 1).coerceAtLeast(1)) + "…"
                    result.add(0, msg.first to truncated)
                    remaining = 0
                    break
                }
                result.add(0, msg)
                remaining -= len
            }

            // Ensure first message is user type
            if (result.isNotEmpty() && result.first().first !in 0..2) {
                result.removeAt(0)
            }

            return result
        }
    }

    private var isUserTextDispatching: Boolean = false
    private var activeRequestJob: Job? = null
    private var activeRequestContext: ChatRequestContext? = null
    private val repeatedInputReplyWindow = LinkedHashMap<String, ArrayDeque<String>>()
    private var repeatInputTurns: Int = 0
    private var repeatInputExactMatches: Int = 0
    private var repeatInputHighSimilarityMatches: Int = 0
    private var agentOrchestrator: ChatAgentOrchestrator? = null
    private var agentInitialized: Boolean = false

    private fun isAgentMode(): Boolean {
        if (!agentInitialized || agentOrchestrator == null) return false
        val convId = getCurrentConversationId()
        return ChatConversationMode.belongsTo(convId, ChatConversationMode.AGENT)
    }

    init {
        initAgentOrchestrator()
    }

    private fun initAgentOrchestrator() {
        try {
            AgentToolRegistrar.registerAll(context, db)
            agentOrchestrator = ChatAgentOrchestrator(
                context = context,
                db = db,
                getCurrentBookName = getCurrentBookName,
                getCurrentConversationId = getCurrentConversationId,
                onToolResult = { result ->
                    handleAgentResult(result)
                },
                getHistoryTurns = {
                    buildChatHistoryTurns("")
                }
            )
            agentInitialized = true
        } catch (e: Exception) {
            Logger.d(context, "ChatMessagePipeline", "initAgentOrchestrator error: ${e.message}")
            agentInitialized = false
        }
    }

    private fun handleAgentResult(result: com.taostudio.tapaccounting.chat.agent.AgentToolResult) {
        if (result.userMessage != null) {
            appendAiTextMessage(result.userMessage, false, getCurrentBookName(), getCurrentConversationId())
        }
        when (val action = result.uiAction) {
            is UiAction.Navigate -> {
                try {
                    context.startActivity(action.intent)
                } catch (e: Exception) {
                    Logger.d(context, "ChatMessagePipeline", "navigate error: ${e.message}")
                }
            }
            is UiAction.StartAccounting ->
                callAiAccounting(action.text, appendUserBubble = false)
            else -> Unit
        }
    }

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
        isUserTextDispatching = true
        clearInput()
        updateInputActionUi()
        appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
        if (consumePendingHabitSuggestionReply(text)) {
            isUserTextDispatching = false
            return
        }

        if (isAgentMode()) {
            handleWithAgent(text)
        } else {
            callAiAccounting(text, appendUserBubble = false)
        }
        isUserTextDispatching = false
    }

    private fun handleWithAgent(userText: String) {
        val loadingKey = appendAiTextMessage(
            "正在思考...",
            true,
            getCurrentBookName(),
            getCurrentConversationId()
        )
        val requestContext = newRequestContext(loadingKey)
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val result = agentOrchestrator?.handle(userText)
                if (!canWriteForRequest(requestContext)) return@launch
                runOnUiIfAlive { removeLoadingMessage(loadingKey) }
                if (result != null) {
                    handleAgentResult(result)
                }
            } catch (e: Exception) {
                Logger.d(context, "ChatMessagePipeline", "handleWithAgent error: ${e.message}")
                if (canWriteForRequest(requestContext)) {
                    runOnUiIfAlive { removeLoadingMessage(loadingKey) }
                    appendAiTextMessage("处理失败：${e.message}", false, getCurrentBookName(), getCurrentConversationId())
                }
            } finally {
                clearActiveRequestIfMatch(requestContext)
            }
        }
        registerActiveJob(job, requestContext)
        job.start()
    }

    private suspend fun buildChatHistoryTurns(
        userText: String,
        requestContext: ChatRequestContext? = null
    ): List<ChatTurn> {
        return withContext(Dispatchers.IO) {
            val book = requestContext?.bookName ?: getCurrentBookName()
            val convId = requestContext?.conversationId ?: getCurrentConversationId()
            val recent = db.chatMessageDao().getRecentMessages(book, convId, CHAT_HISTORY_FETCH_LIMIT).toMutableList()
            if (recent.isEmpty()) return@withContext emptyList()

            val filteredRecent = filterRepeatedInputTurnsFromHistory(recent, userText)

            val turns = filteredRecent
                .takeLast(CHAT_HISTORY_MAX_TURNS)
                .mapNotNull { msg ->
                    val summary = summarizeHistoryMessage(msg, book)
                    if (summary.isBlank()) return@mapNotNull null
                    val role = if (msg.msgType in 0..2) "user" else "assistant"
                    ChatTurn(role, summary)
                }
            trimHistoryToCharBudget(turns, CHAT_HISTORY_MAX_TOTAL_CHARS)
        }
    }

    private fun trimHistoryToCharBudget(turns: List<ChatTurn>, maxTotalChars: Int): List<ChatTurn> {
        if (turns.isEmpty()) return turns
        var total = turns.sumOf { it.content.length }
        if (total <= maxTotalChars) return turns
        val trimmed = turns.toMutableList()
        while (trimmed.isNotEmpty() && total > maxTotalChars) {
            total -= trimmed.removeAt(0).content.length
        }
        return trimmed
    }

    private fun filterRepeatedInputTurnsFromHistory(
        recent: List<ChatMessage>,
        userText: String
    ): List<ChatMessage> {
        val currentUserNormalized = normalizeForRepeatComparison(userText)
        if (currentUserNormalized.isBlank()) return recent

        val currentMessageId = recent.asReversed()
            .firstOrNull { msg ->
                msg.msgType == ChatActivity.MSG_TYPE_USER_TEXT &&
                    normalizeForRepeatComparison(msg.content) == currentUserNormalized
            }
            ?.id
            ?: return recent

        return recent.filterNot { it.id == currentMessageId }
    }

    private suspend fun summarizeHistoryMessage(msg: ChatMessage, bookName: String): String {
        val raw = msg.content.trim()
        if (raw.isBlank() && msg.msgType != ChatActivity.MSG_TYPE_AI_BILL) return ""
        return when (msg.msgType) {
            ChatActivity.MSG_TYPE_USER_TEXT -> truncateHistoryText(raw, MAX_CHAT_HISTORY_TURN_CHARS)
            ChatActivity.MSG_TYPE_USER_IMAGE -> "[图片消息]"
            ChatActivity.MSG_TYPE_USER_VOICE -> {
                val transcript = runCatching {
                    JSONObject(raw).optString("transcript").trim()
                }.getOrDefault("")
                if (transcript.isNotBlank()) {
                    "语音：${compactHistoryText(transcript, MAX_CHAT_HISTORY_VOICE_CHARS)}"
                } else {
                    "[语音消息]"
                }
            }
            ChatActivity.MSG_TYPE_AI_BILL -> summarizeBillHistoryMessage(msg, bookName)
            ChatActivity.MSG_TYPE_AI_TEXT -> truncateHistoryText(raw, MAX_CHAT_HISTORY_TURN_CHARS)
            else -> compactHistoryText(raw)
        }
    }

    private suspend fun summarizeBillHistoryMessage(msg: ChatMessage, bookName: String): String {
        val billIds = ChatBillMessageParser.parseBillIds(msg.billIds)
        val bills = billIds.mapNotNull { id -> db.billDao().getBillById(id) }
        if (bills.isNotEmpty()) {
            return truncateHistoryText("已记账：${buildBillSummary(bills)}", MAX_CHAT_HISTORY_TURN_CHARS)
        }
        if (msg.content.isNotBlank()) {
            return truncateHistoryText("已记账：${compactHistoryText(msg.content, 800)}", MAX_CHAT_HISTORY_TURN_CHARS)
        }
        return "[账单结果]"
    }

    private fun compactHistoryText(text: String, maxLen: Int = 180): String {
        val normalized = text
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.length <= maxLen) return normalized
        return normalized.take(maxLen).trimEnd() + "…"
    }

    private fun truncateHistoryText(text: String, maxLen: Int): String {
        if (text.length <= maxLen) return text
        return text.take(maxLen).trimEnd() + "…"
    }

    private fun normalizeForRepeatComparison(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\p{Punct}]+"), "")
            .trim()
    }

    private fun logRepeatReplyStats(routeTag: String, userText: String, finalReply: String) {
        val normalizedInput = normalizeForRepeatComparison(userText)
        val normalizedReply = normalizeForRepeatComparison(finalReply)
        if (normalizedInput.isBlank() || normalizedReply.isBlank()) return

        val previousReplies = repeatedInputReplyWindow[normalizedInput]
        if (previousReplies != null && previousReplies.isNotEmpty()) {
            repeatInputTurns += 1
            val exactMatched = previousReplies.any { it == normalizedReply }
            val highSimilarityMatched = previousReplies.any {
                similarityScore(it, normalizedReply) >= HIGH_SIMILARITY_THRESHOLD
            }
            if (exactMatched) repeatInputExactMatches += 1
            if (highSimilarityMatched) repeatInputHighSimilarityMatches += 1
            val exactRate = if (repeatInputTurns <= 0) 0.0 else repeatInputExactMatches.toDouble() / repeatInputTurns.toDouble()
            val highRate = if (repeatInputTurns <= 0) 0.0 else repeatInputHighSimilarityMatches.toDouble() / repeatInputTurns.toDouble()
            Logger.d(
                context,
                REPEAT_REPLY_STATS_LOG_TAG,
                "route=$routeTag, repeatedTurns=$repeatInputTurns, exactRate=${String.format(Locale.US, "%.3f", exactRate)}, highSimRate=${String.format(Locale.US, "%.3f", highRate)}, inputHash=${abs(normalizedInput.hashCode())}, priorCount=${previousReplies.size}"
            )
        }

        val window = repeatedInputReplyWindow.getOrPut(normalizedInput) { ArrayDeque<String>(REPEAT_REPLY_WINDOW_SIZE) }
        if (window.size >= REPEAT_REPLY_WINDOW_SIZE) {
            window.removeFirst()
        }
        window.addLast(normalizedReply)
        while (repeatedInputReplyWindow.size > REPEAT_REPLY_MAX_KEYS) {
            val firstKey = repeatedInputReplyWindow.keys.firstOrNull() ?: break
            repeatedInputReplyWindow.remove(firstKey)
        }
    }

    private fun similarityScore(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val setA = a.chunked(2).filter { it.isNotBlank() }.toSet()
        val setB = b.chunked(2).filter { it.isNotBlank() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union <= 0.0) return 0.0
        return (intersection / union).coerceIn(0.0, 1.0)
    }

    private fun callGeneralChat(userText: String) {
        val loadingKey = appendAiTextMessage("正在生成回复...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val historyTurns = buildChatHistoryTurns(userText, requestContext)
                Logger.d(
                    this@ChatMessagePipeline.context,
                    CHAT_ROUTE_LOG_TAG,
                    "requestId=${requestContext.requestId}, route=general_chat, historyTurns=${historyTurns.size}"
                )
                appendGeneralChatStreamingReply(
                    userText = userText,
                    requestContext = requestContext,
                    historyTurnsOverride = historyTurns,
                    loadingKeyOverride = loadingKey,
                    routeTag = "general_chat"
                )
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
                    put("category_name", targetBill.categoryName.replace(" - ", "/::/"))
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
                    "modify reply received, requestId=${requestContext.requestId}, len=${modifiedJson.length}"
                )

                val jsonObjectText = extractFirstJsonObjectText(cleanJsonString(modifiedJson))
                val parsedJson = runCatching { jsonObjectText?.let { org.json.JSONObject(it) } }.getOrNull()
                if (parsedJson == null || jsonObjectText.isNullOrBlank()) {
                    Logger.d(
                        context,
                        "ModifyBill",
                        "modify reply parse failed, requestId=${requestContext.requestId}, len=${modifiedJson.length}"
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

    private fun isLikelyGeneralChat(userText: String): Boolean {
        val normalized = userText
            .trim()
            .lowercase(Locale.getDefault())
        if (normalized.isBlank()) return false
        if (normalized.startsWith("[语音输入]")) return false

        if (extractAmountHint(normalized) != null) return false

        val accountingKeywords = listOf(
            "记一笔", "记账", "记一下", "记个账", "花了", "买了", "支付", "付款", "收款", "收入",
            "转账", "还款", "报销", "账单", "改一下", "改成", "刚才那笔", "上一笔", "删掉",
            "删除", "小票", "报销", "到账", "退款", "充值", "提现", "工资"
        )
        if (accountingKeywords.any { normalized.contains(it) }) return false

        val moneyPattern = Regex("""\d+(?:\.\d+)?\s*(?:元|块|块钱|rmb|cny|usd|eur|\$|€)""")
        if (moneyPattern.containsMatchIn(normalized)) return false

        return true
    }

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
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, ChatActivity.MSG_TYPE_USER_TEXT)
        val loadingKey = loadingIdxOverride ?: appendAiTextMessage(
            "正在读懂这笔账...",
            true,
            getCurrentBookName(),
            getCurrentConversationId()
        )
        val requestContext = newRequestContext(loadingKey)
        var loadingStage = 1
        val streamedRaw = StringBuilder()
        var lastDisplayedPreview = ""
        var streamStarted = false
        var lastPreviewUpdateMs = 0L
        fun pushLoadingStatus(raw: String) {
            if (!canWriteForRequest(requestContext)) return
            if (raw.startsWith("AI_STREAM_TEXT::")) {
                val delta = raw.removePrefix("AI_STREAM_TEXT::")
                if (delta.isBlank()) return
                streamStarted = true
                streamedRaw.append(delta)
                val candidate = StreamingBillPreview.formatChatPreview(streamedRaw.toString(), lastDisplayedPreview)
                if (!StreamingBillPreview.shouldUpdateUi(lastDisplayedPreview, candidate, lastPreviewUpdateMs)) return
                lastDisplayedPreview = candidate
                lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
                updateLoadingMessage(loadingKey, candidate)
                return
            }
            if (!StreamingBillPreview.shouldApplyNonStreamProgress(streamStarted)) return
            val (stage, text) = mapProgressToNaturalStatus(raw)
            val nextStage = maxOf(loadingStage, stage)
            loadingStage = nextStage
            val stableText = when (nextStage) {
                1 -> "正在读懂这笔账..."
                2 -> "正在整理账单..."
                else -> text
            }
            if (stableText == lastDisplayedPreview) return
            lastDisplayedPreview = stableText
            lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
            updateLoadingMessage(loadingKey, stableText)
        }
        if (loadingIdxOverride != null && loadingBootstrapText.isNotBlank()) {
            updateLoadingMessage(loadingKey, loadingBootstrapText)
        }
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val isImagePayload = userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_PREFIX) ||
                    userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX)
                val historyInputText = if (isImagePayload) "图片记账" else userText
                val chatHistoryTurns = buildChatHistoryTurns(historyInputText, requestContext)
                if (!isImagePayload && isLikelyGeneralChat(userText)) {
                    removeLoadingMessage(loadingKey)
                    appendGeneralChatStreamingReply(
                        userText = userText,
                        requestContext = requestContext,
                        historyTurnsOverride = chatHistoryTurns,
                        routeTag = "direct_general_chat"
                    )
                    return@launch
                }
                val analysisInput = buildAnalysisInput(userText)
                val autoMultiMode = true
                var accountingSourceText = userText
                val result = try {
                    if (isImagePayload) {
                        val imagePayload = ReceiptImageInputHelper.decodePayload(userText)
                            ?: throw IllegalArgumentException("图片数据无效")
                        val isDirectImageAccounting = ReceiptImageInputHelper.isDirectPayload(userText) ||
                            !Prefs.isReceiptImageDraftConfirmEnabled(context)
                        accountingSourceText = imagePayload.supplement.ifBlank { "图片记账" }

                        if (isDirectImageAccounting) {
                            updateLoadingMessage(loadingKey, "正在直接从图片生成账单...")
                            withContext(Dispatchers.IO) {
                                AIService.analyzeScreenAccountingByImage(
                                    ctx = context,
                                    imageBase64 = imagePayload.base64,
                                    mimeType = imagePayload.mime,
                                    sourceKind = "receipt_image",
                                    supplementText = imagePayload.supplement,
                                    isFromChat = true,
                                    chatTurns = chatHistoryTurns,
                                    onProgress = { status ->
                                        runOnUiIfAlive {
                                            if (canWriteForRequest(requestContext)) pushLoadingStatus(status)
                                        }
                                    }
                                )
                            }
                        } else {
                            updateLoadingMessage(loadingKey, "正在看图识别交易...")
                            val visionResult = withContext(Dispatchers.IO) {
                                AIService.analyzeReceiptByImage(
                                    ctx = context,
                                    imageBase64 = imagePayload.base64,
                                    mimeType = imagePayload.mime,
                                    supplementText = imagePayload.supplement
                                )
                            }
                            if (!canWriteForRequest(requestContext)) return@launch

                            updateLoadingMessage(loadingKey, "识别好了，等你核对草稿...")
                            val draftForConfirm = ReceiptImageInputHelper.mergeSupplementWithSummary(
                                visionResult,
                                imagePayload.supplement
                            )
                            val confirmedDraft = confirmVisualAccountingDraft(
                                draftForConfirm,
                                requestContext.bookName,
                                requestContext.conversationId
                            )?.trim()
                            if (!canWriteForRequest(requestContext)) return@launch
                            if (confirmedDraft.isNullOrBlank()) {
                                removeLoadingMessage(loadingKey)
                                appendAiTextMessage(
                                    "已取消本次图片记账。",
                                    false,
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )
                                return@launch
                            }
                            val accountingInput = ReceiptImageInputHelper.buildAccountingInputFromImageDraft(
                                confirmedDraft,
                                imagePayload.supplement
                            )
                            accountingSourceText = accountingInput

                            updateLoadingMessage(loadingKey, "正在按确认内容整理账单...")
                            withContext(Dispatchers.IO) {
                                AIService.analyzeAccounting(
                                    ctx = context,
                                    userInput = accountingInput,
                                    isMultiModeOverride = autoMultiMode,
                                    isFromChat = true,
                                    chatTurns = chatHistoryTurns,
                                    onProgress = { status ->
                                        runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                    }
                                )?.also { root ->
                                    AIService.markVisualAccountingReviewDraft(
                                        root = root,
                                        sourceKind = "chat_image",
                                        naturalSummary = accountingInput,
                                        includePaymentMethod = Prefs.isAssetFeatureEnabled(context)
                                    )
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.IO) {
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = analysisInput,
                                isMultiModeOverride = autoMultiMode,
                                isFromChat = true,
                                chatTurns = chatHistoryTurns,
                                onProgress = { status ->
                                    runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }

                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                finalizeChatAccountingResult(
                    result = result,
                    sourceText = accountingSourceText,
                    requestContext = requestContext,
                    forceTextReply = forceTextReply,
                    parseFailureHint = if (forceTextReply) {
                        "我这次没能正确解析，但已经收到你的语音转写文本。你可以再说得更具体一点，我继续帮你记账。"
                    } else {
                        "我这次没能正确解析，你可以说得更具体一点，我继续帮你记账。"
                    }
                )
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

    private suspend fun appendGeneralChatStreamingReply(
        userText: String,
        requestContext: ChatRequestContext,
        historyTurnsOverride: List<ChatTurn>? = null,
        replyGuideHint: String = "",
        loadingKeyOverride: String? = null,
        routeTag: String = "general_chat"
    ) {
        if (!canWriteForRequest(requestContext)) return
        val historyTurns = historyTurnsOverride ?: buildChatHistoryTurns(userText, requestContext)
        val loadingKey = loadingKeyOverride ?: appendAiTextMessage("", true, requestContext.bookName, requestContext.conversationId)
        val streamed = StringBuilder()
        Logger.d(
            context,
            CHAT_ROUTE_LOG_TAG,
            "requestId=${requestContext.requestId}, route=$routeTag, historyTurns=${historyTurns.size}, hintLen=${replyGuideHint.length}"
        )
        val result = runCatching {
            withContext(Dispatchers.IO) {
                AIService.generateGeneralChatReply(
                    ctx = context,
                    userInput = userText,
                    chatTurns = historyTurns,
                    replyGuideHint = replyGuideHint
                ) { delta ->
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
        }.getOrNull()
        if (!canWriteForRequest(requestContext)) {
            removeLoadingMessage(loadingKey)
            return
        }
        val finalText = streamed.toString().trim().ifBlank { result?.content.orEmpty().trim() }
        if (result?.completed == true && finalText.isNotBlank()) {
            finalizeLoadingMessage(loadingKey, finalText, requestContext.bookName, requestContext.conversationId)
            logRepeatReplyStats(routeTag, userText, finalText)
        } else {
            removeLoadingMessage(loadingKey)
            appendAiTextMessage(
                finalText.ifBlank { "我在呢。你想听什么类型的故事？我可以现场编一个。" },
                false,
                requestContext.bookName,
                requestContext.conversationId
            )
        }
    }

    fun callAiAccountingWithVoice(audioFile: File) {
        val loadingKey = appendAiTextMessage("正在听写语音...", true, getCurrentBookName(), getCurrentConversationId())
        val requestContext = newRequestContext(loadingKey)
        var loadingStage = 1
        val streamedRaw = StringBuilder()
        var lastDisplayedPreview = ""
        var streamStarted = false
        var lastPreviewUpdateMs = 0L
        fun pushLoadingStatus(raw: String) {
            if (!canWriteForRequest(requestContext)) return
            if (raw.startsWith("AI_STREAM_TEXT::")) {
                val delta = raw.removePrefix("AI_STREAM_TEXT::")
                if (delta.isBlank()) return
                streamStarted = true
                streamedRaw.append(delta)
                val candidate = StreamingBillPreview.formatChatPreview(streamedRaw.toString(), lastDisplayedPreview)
                if (!StreamingBillPreview.shouldUpdateUi(lastDisplayedPreview, candidate, lastPreviewUpdateMs)) return
                lastDisplayedPreview = candidate
                lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
                updateLoadingMessage(loadingKey, candidate)
                return
            }
            if (!StreamingBillPreview.shouldApplyNonStreamProgress(streamStarted)) return
            val (stage, text) = mapProgressToNaturalStatus(raw)
            loadingStage = maxOf(loadingStage, stage)
            val stableText = when (loadingStage) {
                1 -> "正在听写语音..."
                2 -> "正在整理账单..."
                else -> text
            }
            if (stableText == lastDisplayedPreview) return
            lastDisplayedPreview = stableText
            lastPreviewUpdateMs = android.os.SystemClock.elapsedRealtime()
            updateLoadingMessage(loadingKey, stableText)
        }
        val job = aiWorkScope.launch(start = CoroutineStart.LAZY) {
            try {
                if (!canWriteForRequest(requestContext)) return@launch
                val voiceUserText = "[语音输入]"
                val transcript = withContext(Dispatchers.IO) { transcribeVoiceToTextWithFallback(audioFile) }
                val chatHistoryTurns = buildChatHistoryTurns(
                    transcript.takeIf { it.isNotBlank() } ?: voiceUserText,
                    requestContext
                )
                val autoMultiMode = decideSingleOrMultiForChat(transcript)
                val directAudioResult = try {
                    withContext(Dispatchers.IO) {
                        AIService.analyzeAccountingByAudio(
                            ctx = context,
                            audioFile = audioFile,
                            isMultiModeOverride = autoMultiMode,
                            isFromChat = true,
                            onProgress = { status ->
                                runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                            }
                        )
                    }
                } catch (e: Exception) {
                    if (!shouldFallbackToAssistant(e)) throw e
                    null
                }
                val result = directAudioResult ?: transcript
                    .takeIf { it.isNotBlank() }
                    ?.let { transcribedText ->
                        withContext(Dispatchers.IO) {
                            AIService.analyzeAccounting(
                                ctx = context,
                                userInput = transcribedText,
                                isMultiModeOverride = autoMultiMode,
                                onProgress = { status ->
                                    runOnUiIfAlive {
                                        if (canWriteForRequest(requestContext)) pushLoadingStatus(status)
                                    }
                                },
                                isFromChat = true,
                                chatTurns = chatHistoryTurns
                            )
                        }
                    }

                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                finalizeChatAccountingResult(
                    result = result,
                    sourceText = voiceUserText,
                    requestContext = requestContext,
                    parseFailureHint = "我收到这段语音了，但这次没能正确解析。你可以再说得更具体一点。"
                )
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

    private suspend fun finalizeChatAccountingResult(
        result: JSONObject?,
        sourceText: String,
        requestContext: ChatRequestContext,
        forceTextReply: Boolean = true,
        parseFailureHint: String = "我这次没能正确解析，你可以说得更具体一点，我继续帮你记账。"
    ) {
        if (result == null) {
            if (forceTextReply) {
                appendAiTextMessage(
                    parseFailureHint,
                    false,
                    requestContext.bookName,
                    requestContext.conversationId
                )
            }
            return
        }
        if (result.optBoolean("no_bill", false)) {
            appendAccountingInlineReply(result, requestContext)
            if (forceTextReply && AIService.extractAccountingAssistantReply(result).isBlank()) {
                appendAiTextMessage(
                    "我暂时没识别到明确账单，你可以补充金额、分类或账户，我继续帮你完成。",
                    false,
                    requestContext.bookName,
                    requestContext.conversationId
                )
            }
            return
        }
        val savedBills = processBillResult(
            result,
            sourceText,
            requestContext.bookName,
            requestContext.conversationId
        )
        if (!canWriteForRequest(requestContext)) return
        if (savedBills.isNotEmpty()) {
            appendAccountingInlineReply(result, requestContext)
        }
    }

    private suspend fun appendAccountingInlineReply(
        result: JSONObject,
        requestContext: ChatRequestContext
    ) {
        if (Prefs.getAiChatReplyStyle(context) == "off") return
        if (!canWriteForRequest(requestContext)) return
        val reply = sanitizeAssistantReply(AIService.extractAccountingAssistantReply(result))
        if (reply.isBlank()) return
        appendAiTextMessage(reply, false, requestContext.bookName, requestContext.conversationId)
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
            else -> "分析失败，请稍后重试"
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
        if (text.isBlank()) return 1 to "正在读懂这笔账..."
        val lower = text.lowercase(Locale.getDefault())

        if (text.contains("智能分类中") ||
            text.contains("智能分析中") ||
            text.contains("匹配中") ||
            text.contains("核对分类") ||
            text.contains("确认分类")
        ) {
            return 3 to text
        }

        return when {
            lower.contains("reply") ||
                lower.contains("respond") ||
                lower.contains("output") ||
                lower.contains("generate") ||
                lower.contains("生成") ||
                lower.contains("回复") ||
                lower.contains("整理") -> 2 to "正在整理账单..."

            lower.contains("upload") ||
                lower.contains("audio") ||
                lower.contains("image") ||
                lower.contains("ocr") ||
                lower.contains("parse") ||
                lower.contains("extract") ||
                lower.contains("analy") ||
                lower.contains("thinking") ||
                lower.contains("理解") ||
                lower.contains("分析") -> 1 to "正在读懂这笔账..."

            else -> 1 to "正在读懂这笔账..."
        }
    }
}
