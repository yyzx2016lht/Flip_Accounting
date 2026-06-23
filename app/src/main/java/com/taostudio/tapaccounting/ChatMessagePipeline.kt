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
    private val buildBillSummary: (List<Bill>) -> String,
    private val transcribeVoiceToTextWithFallback: suspend (File) -> String,
    private val persistAiTextMessage: suspend (String, String, String) -> Unit,
    private val db: com.taostudio.tapaccounting.data.local.AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val onMessagesChanged: () -> Unit = {},
    private val appendQueryDraftMessage: (com.taostudio.tapaccounting.chat.query.QueryDraft) -> String = { "" },
    private val appendQueryResultMessage: (com.taostudio.tapaccounting.chat.query.QueryResult) -> String = { "" }
) {
    companion object {
        private const val CHAT_ROUTE_LOG_TAG = "AiChatRoute"
        private const val REPEAT_REPLY_STATS_LOG_TAG = "RepeatReplyStats"
        private const val REPEAT_REPLY_WINDOW_SIZE = 3
        private const val REPEAT_REPLY_MAX_KEYS = 24
        private const val HIGH_SIMILARITY_THRESHOLD = 0.82
        const val CHAT_HISTORY_FETCH_LIMIT = 80
        const val CHAT_HISTORY_MAX_TURNS = 40
        const val CHAT_HISTORY_MAX_TOTAL_CHARS = 48_000
        const val MAX_CHAT_HISTORY_TURN_CHARS = 6000
        const val MAX_CHAT_HISTORY_VOICE_CHARS = 400

        /**
         * Truncate history messages from newest to oldest, then reverse to chronological order.
         * Ensures the first message is a user message (not an orphaned assistant reply).
         * A single oversized message is truncated rather than dropped.
         * This is a pure function — testable without Android dependencies.
         */
        fun truncateHistory(
            messages: List<Pair<Int, String>>,
            maxTotalChars: Int = CHAT_HISTORY_MAX_TOTAL_CHARS
        ): List<Pair<Int, String>> {
            if (messages.isEmpty()) return emptyList()
            // Step 1: Truncate from newest to oldest, preserving recent context.
            val selected = mutableListOf<Pair<Int, String>>()
            var totalChars = 0
            for (msg in messages.reversed()) {
                val (msgType, content) = msg
                if (content.isBlank()) continue
                if (totalChars + content.length > maxTotalChars) {
                    // If this is the newest message and nothing selected yet:
                    // - User messages: truncate text to fit (user intent is always important)
                    // - Assistant messages: drop entirely (truncated reply is misleading)
                    if (selected.isEmpty() && msgType in 0..2 && content.length > maxTotalChars) {
                        val truncated = content.take(maxTotalChars - 1).trimEnd() + "…"
                        selected.add(msgType to truncated)
                    }
                    break
                }
                totalChars += content.length
                selected.add(msg)
            }
            // Step 2: Reverse to chronological order
            selected.reverse()
            // Step 3: Ensure first message is a user message (msgType 0-2)
            val firstUserIndex = selected.indexOfFirst { it.first in 0..2 }
            return if (firstUserIndex > 0) selected.subList(firstUserIndex, selected.size) else selected
        }
    }

    private var isUserTextDispatching: Boolean = false
    private var activeRequestJob: Job? = null
    private var activeRequestContext: ChatRequestContext? = null
    private val repeatedInputReplyWindow = LinkedHashMap<String, ArrayDeque<String>>()
    private var repeatInputTurns: Int = 0
    private var repeatInputExactMatches: Int = 0
    private var repeatInputHighSimilarityMatches: Int = 0

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
            Utils.toast(context, context.getString(R.string.toast_empty_content))
            return
        }
        isUserTextDispatching = true
        clearInput()
        updateInputActionUi()

        if (consumePendingHabitSuggestionReply(text)) {
            // Still save the user message for habit suggestion replies
            appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
            isUserTextDispatching = false
            return
        }

        appendUserMessage(text, ChatActivity.MSG_TYPE_USER_TEXT)
        callAiAccounting(text, appendUserBubble = false)
        isUserTextDispatching = false
        onMessagesChanged()
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

    fun callAiAccounting(
        userText: String,
        appendUserBubble: Boolean = true,
        forceTextReply: Boolean = false,
        loadingIdxOverride: String? = null,
        loadingBootstrapText: String = ""
    ) {
        if (appendUserBubble) appendUserMessage(userText, ChatActivity.MSG_TYPE_USER_TEXT)
        val loadingKey = loadingIdxOverride ?: appendAiTextMessage(
            "正在分析...",
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
                val isSingleImagePayload = userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_PREFIX) ||
                    userText.startsWith(ReceiptImageInputHelper.MULTIMODAL_DIRECT_PREFIX)
                val isMultiImagePayload = ChatImageComposer.isMultiImagePayload(userText)
                val isImagePayload = isSingleImagePayload || isMultiImagePayload
                val historyInputText = if (isImagePayload) "图片记账" else userText
                val chatHistoryTurns = buildChatHistoryTurns(historyInputText, requestContext)
                val analysisInput = buildAnalysisInput(userText)
                val autoMultiMode = true
                var accountingSourceText = userText
                val result = try {
                    // 意图路由：图片有附带文字时用文字分类，无文字默认记账
                    if (isImagePayload) {
                        val supplementText = if (isMultiImagePayload) {
                            ChatImageComposer.decodeMultiImagePayload(userText)?.supplement ?: ""
                        } else {
                            ReceiptImageInputHelper.decodePayload(userText)?.supplement ?: ""
                        }
                        if (supplementText.isNotBlank()) {
                            val intent = withContext(Dispatchers.IO) {
                                AIService.classifyIntent(context, supplementText)
                            }
                            if (!canWriteForRequest(requestContext)) return@launch
                            if (intent == "GENERAL_CHAT") {
                                updateLoadingMessage(loadingKey, "正在思考...")
                                val streamedText = StringBuffer()
                                val chatReply = withContext(Dispatchers.IO) {
                                    AIService.generateGeneralChatReply(
                                        ctx = context,
                                        userInput = supplementText,
                                        chatTurns = chatHistoryTurns,
                                        onDelta = { delta ->
                                            streamedText.append(delta)
                                            runOnUiIfAlive {
                                                if (canWriteForRequest(requestContext)) {
                                                    updateLoadingMessage(loadingKey, streamedText.toString())
                                                }
                                            }
                                        }
                                    )
                                }
                                if (!canWriteForRequest(requestContext)) return@launch
                                removeLoadingMessage(loadingKey)
                                if (chatReply.completed && chatReply.content.isNotBlank()) {
                                    appendAiTextMessage(chatReply.content, false, requestContext.bookName, requestContext.conversationId)
                                } else {
                                    appendAiTextMessage(context.getString(R.string.chat_reply_failed), false, requestContext.bookName, requestContext.conversationId)
                                }
                                return@launch
                            }
                        }
                    } else {
                        // 纯文字：所有输入先走 AI Router
                        val queryDraftMgr = context.queryDraftManager

                        // 窄文本命令：仅当有 active draft 时，完全匹配才执行
                        if (queryDraftMgr.hasActiveDraft()) {
                            val trimmed = userText.trim()
                            val isExactStats = trimmed == "统计" || trimmed == "统计金额"
                            val isExactSearch = trimmed == "搜索" || trimmed == "搜索账单"
                            val isExactCancel = trimmed == "取消"

                            if (isExactStats || isExactSearch || isExactCancel) {
                                if (isExactCancel) {
                                    removeLoadingMessage(loadingKey)
                                    queryDraftMgr.clearDraft()
                                    appendAiTextMessage("已取消查询。", false, requestContext.bookName, requestContext.conversationId)
                                    return@launch
                                }
                                val queryContext = withContext(Dispatchers.IO) {
                                    context.buildQueryContext()
                                }
                                val draft = queryDraftMgr.currentDraft!!
                                if (isExactStats) {
                                    removeLoadingMessage(loadingKey)
                                    val result = withContext(Dispatchers.IO) {
                                        queryDraftMgr.executeStats(draft, queryContext)
                                    }
                                    appendQueryResultMessage(result)
                                    return@launch
                                }
                                if (isExactSearch) {
                                    removeLoadingMessage(loadingKey)
                                    val bills = withContext(Dispatchers.IO) {
                                        queryDraftMgr.executeSearch(draft, queryContext)
                                    }
                                    val result = com.taostudio.tapaccounting.chat.query.QueryResult(
                                        draft = draft,
                                        billCount = bills.size,
                                        totalAmount = bills.filterNot { it.excludeFromStats }
                                            .filter { it.type == com.taostudio.tapaccounting.data.local.entity.Bill.TYPE_EXPENSE &&
                                                it.subType != com.taostudio.tapaccounting.data.local.entity.Bill.SUBTYPE_REFUND }
                                            .sumOf { it.amount },
                                        billsPreview = bills.take(3).map {
                                            com.taostudio.tapaccounting.chat.query.BillPreview(
                                                id = it.id, time = it.time, type = it.type,
                                                amount = it.amount, remark = it.remark,
                                                categoryName = it.categoryName,
                                                accountName = it.accountName, currency = it.currency
                                            )
                                        }
                                    )
                                    appendQueryResultMessage(result)
                                    return@launch
                                }
                            }
                        }

                        // AI Router 四分类
                        val routerResult = withContext(Dispatchers.IO) {
                            AIService.classifyRouterIntent(context, userText)
                        }
                        if (!canWriteForRequest(requestContext)) return@launch

                        when (routerResult.intent) {
                            "GENERAL_CHAT" -> {
                                updateLoadingMessage(loadingKey, "正在思考...")
                                val streamedText = StringBuffer()
                                val chatReply = withContext(Dispatchers.IO) {
                                    AIService.generateGeneralChatReply(
                                        ctx = context,
                                        userInput = userText,
                                        chatTurns = chatHistoryTurns,
                                        onDelta = { delta ->
                                            streamedText.append(delta)
                                            runOnUiIfAlive {
                                                if (canWriteForRequest(requestContext)) {
                                                    updateLoadingMessage(loadingKey, streamedText.toString())
                                                }
                                            }
                                        }
                                    )
                                }
                                if (!canWriteForRequest(requestContext)) return@launch
                                removeLoadingMessage(loadingKey)
                                if (chatReply.completed && chatReply.content.isNotBlank()) {
                                    appendAiTextMessage(chatReply.content, false, requestContext.bookName, requestContext.conversationId)
                                } else {
                                    appendAiTextMessage("回复生成失败，请重试。", false, requestContext.bookName, requestContext.conversationId)
                                }
                                return@launch
                            }
                            "UNSUPPORTED_WRITE" -> {
                                removeLoadingMessage(loadingKey)
                                appendAiTextMessage(
                                    context.getString(com.taostudio.tapaccounting.R.string.query_write_rejected),
                                    false,
                                    requestContext.bookName,
                                    requestContext.conversationId
                                )
                                return@launch
                            }
                            "ACCOUNTING_QUERY" -> {
                                // AI Query Extractor 提取查询草稿
                                updateLoadingMessage(loadingKey, "正在分析查询...")
                                val existingDraft = queryDraftMgr.currentDraft
                                val aiDraftJson = withContext(Dispatchers.IO) {
                                    AIService.extractQueryDraft(context, userText, existingDraft)
                                }
                                if (!canWriteForRequest(requestContext)) return@launch
                                removeLoadingMessage(loadingKey)

                                if (aiDraftJson == null) {
                                    appendAiTextMessage(
                                        "查询解析失败，请重试或换一种说法。",
                                        false,
                                        requestContext.bookName,
                                        requestContext.conversationId
                                    )
                                    return@launch
                                }

                                val aiIntent = aiDraftJson.optString("intent", "UNSUPPORTED")
                                if (aiIntent == "UNSUPPORTED") {
                                    val reason = aiDraftJson.optString("reason", "")
                                    val msg = if (reason == "SHOULD_USE_ACCOUNTING_CREATE_FLOW") {
                                        "这看起来像是记账，请直接说金额和消费内容。"
                                    } else {
                                        context.getString(com.taostudio.tapaccounting.R.string.query_write_rejected)
                                    }
                                    appendAiTextMessage(msg, false, requestContext.bookName, requestContext.conversationId)
                                    return@launch
                                }
                                if (aiIntent == "CLARIFY") {
                                    val question = aiDraftJson.optString("clarifyQuestion", "你想查什么？能再具体一点吗？")
                                    appendAiTextMessage(question, false, requestContext.bookName, requestContext.conversationId)
                                    return@launch
                                }

                                val queryContext = withContext(Dispatchers.IO) {
                                    context.buildQueryContext()
                                }

                                if (aiIntent == "UPDATE_DRAFT" && existingDraft != null) {
                                    // AI 判断为更新现有草稿
                                    val updated = queryDraftMgr.updateFromAiExtract(aiDraftJson, queryContext)
                                    if (updated != null) {
                                        appendQueryDraftMessage(updated)
                                    } else {
                                        appendAiTextMessage("更新查询条件失败，请重试。", false, requestContext.bookName, requestContext.conversationId)
                                    }
                                    return@launch
                                }

                                // QUERY_DRAFT → 新建草稿
                                val draft = queryDraftMgr.createFromAiExtract(aiDraftJson, userText, queryContext)
                                if (draft != null) {
                                    appendQueryDraftMessage(draft)
                                } else {
                                    appendAiTextMessage(
                                        "查询条件解析失败，请重试。",
                                        false,
                                        requestContext.bookName,
                                        requestContext.conversationId
                                    )
                                }
                                return@launch
                            }
                            // ACCOUNTING_CREATE → 继续走记账流程
                        }
                    }

                    // 记账流程
                    if (isImagePayload) {
                        if (isMultiImagePayload) {
                            // Multi-image path: send all images to multimodal at once
                            val multiPayload = ChatImageComposer.decodeMultiImagePayload(userText)
                                ?: throw IllegalArgumentException("多图数据无效")
                            accountingSourceText = multiPayload.supplement.ifBlank { "图片记账" }
                            val imagePairs = multiPayload.images.map { it.base64 to it.mime }
                            val isDirectImageAccounting = !Prefs.isReceiptImageDraftConfirmEnabled(context)

                            if (isDirectImageAccounting) {
                                // Direct path: multimodal returns JSON directly
                                updateLoadingMessage(loadingKey, "正在从${multiPayload.images.size}张图片生成账单...")
                                withContext(Dispatchers.IO) {
                                    AIService.analyzeScreenAccountingByImages(
                                        ctx = context,
                                        images = imagePairs,
                                        sourceKind = "receipt_image",
                                        supplementText = multiPayload.supplement,
                                        isFromChat = true,
                                        chatTurns = chatHistoryTurns,
                                        onProgress = { status ->
                                            runOnUiIfAlive { if (canWriteForRequest(requestContext)) pushLoadingStatus(status) }
                                        }
                                    )
                                }
                            } else {
                                // Draft-confirm path: multimodal returns text summary, user confirms, then accounting
                                updateLoadingMessage(loadingKey, "正在识别${multiPayload.images.size}张图片...")
                                val visionResult = withContext(Dispatchers.IO) {
                                    AIService.analyzeReceiptByImages(
                                        ctx = context,
                                        images = imagePairs,
                                        supplementText = multiPayload.supplement
                                    )
                                }
                                if (!canWriteForRequest(requestContext)) return@launch
                                val draftForConfirm = ReceiptImageInputHelper.mergeSupplementWithSummary(
                                    visionResult, multiPayload.supplement
                                )
                                updateLoadingMessage(loadingKey, "识别好了，等你核对草稿...")
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
                                    confirmedDraft, multiPayload.supplement
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
                            // Single-image legacy path (unchanged)
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
                        }
                    } else {
                        updateLoadingMessage(loadingKey, "正在读懂这笔账...")
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
                val transcript = withContext(Dispatchers.IO) { transcribeVoiceToTextWithFallback(audioFile) }
                if (!canWriteForRequest(requestContext)) return@launch

                if (transcript.isBlank()) {
                    removeLoadingMessage(loadingKey)
                    appendAiTextMessage(
                        "我没听清语音内容，你可以再说一次或直接打字。",
                        false, requestContext.bookName, requestContext.conversationId
                    )
                    return@launch
                }

                updateLoadingMessage(loadingKey, "正在整理账单...")
                val chatHistoryTurns = buildChatHistoryTurns(transcript, requestContext)
                val autoMultiMode = decideSingleOrMultiForChat(transcript)
                val result = withContext(Dispatchers.IO) {
                    AIService.analyzeAccounting(
                        ctx = context,
                        userInput = transcript,
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

                if (!canWriteForRequest(requestContext)) return@launch
                removeLoadingMessage(loadingKey)
                finalizeChatAccountingResult(
                    result = result,
                    sourceText = transcript,
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
