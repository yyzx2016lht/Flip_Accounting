package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taostudio.tapaccounting.AiModelSlots
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.SiliconFlowApi
import com.taostudio.tapaccounting.chat.query.QueryContextBuilder
import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRegistry
import com.taostudio.tapaccounting.data.local.AppDatabase
import okhttp3.OkHttpClient
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ChatAgentOrchestrator(
    private val context: Context,
    private val db: AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val onDelta: ((String) -> Unit)? = null
) {
    companion object {
        private const val LOG_TAG = "ChatAgentOrchestrator"
        private const val MAX_CHAIN_STEPS = 5
        private const val API_CONNECT_TIMEOUT_SECONDS = 60L
        private const val API_READ_TIMEOUT_SECONDS = 90L
        private const val API_WRITE_TIMEOUT_SECONDS = 90L
        private const val HISTORY_FETCH_LIMIT = 40
        private const val HISTORY_MAX_TURN_CHARS = 2000
        private const val HISTORY_MAX_TOTAL_CHARS = 24_000
    }

    private fun getApi(): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(context))
        val client = OkHttpClient.Builder()
            .connectTimeout(API_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(API_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
    }

    private fun normalizeBaseUrl(url: String): String {
        var base = url.trim()
        if (!base.endsWith("/")) base += "/"
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            base = "https://$base"
        }
        return base
    }

    /**
     * Handle a user message in Agent mode.
     *
     * @param userText The current user input text.
     * @param historySnapshot Pre-built history messages (oldest first), EXCLUDING the current user message.
     *        This avoids race conditions with async DB writes and text-based dedup.
     *        If null, history will be fetched from DB (legacy path, less reliable).
     */
    suspend fun handle(
        userText: String,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        images: List<AgentImageInput> = emptyList(),
        onDelta: ((String) -> Unit)? = null
    ): AgentToolResult {
        Logger.d(context, LOG_TAG, "handle: ${userText.take(200)}")

        val conversationId = getCurrentConversationId()
        val sessionContext = buildSessionContext()
        val hasPending = PendingActionManager.hasPending(conversationId)

        if (hasPending) {
            if (isConfirmIntent(userText)) {
                val result = handleConfirmation(conversationId, sessionContext)
                // If pending expired, fall through to normal processing
                if (result.facts?.optBoolean("pendingExpired") == true) {
                    // Continue to normal message processing below
                } else {
                    return result
                }
            } else if (isCancelIntent(userText)) {
                return handleCancellation(conversationId)
            } else {
                // Non-confirm/cancel message with pending: clear pending and process normally
                PendingActionManager.clear(conversationId)
                ConversationStateManager.updateState(conversationId) { it.withPendingAction(null) }
            }
        }

        val selectedSkills = AgentSkillRegistry.getAll()
        val routedSkillIds = selectedSkills.map { it.id }
        val selectedTools = AgentToolRegistry.getAll()

        val conversationState = ConversationStateManager.getState(conversationId)
        ConversationStateManager.updateState(conversationId) { state ->
            state.withActiveSkills(routedSkillIds.toSet())
        }

        val systemPrompt = AgentPromptBuilder.buildSystemPrompt(sessionContext, selectedSkills, selectedTools, conversationState)

        val toolCallJson = callLlmForToolSelection(systemPrompt, userText, historySnapshot, images)
            ?: return AgentToolResult.failure("网络不佳或服务异常，请稍后重试")

        val multiStepCalls = parseMultiStepCalls(toolCallJson)
        if (multiStepCalls != null) {
            return executeMultiStepCalls(multiStepCalls, sessionContext, userText, conversationId, historySnapshot)
        }

        val toolCall = parseToolCall(toolCallJson)
            ?: return AgentToolResult.failure("未能理解你的请求，请换个说法试试")

        if (toolCall.toolId == "chat.reply") {
            val streamed = callLlmForChat(userText, historySnapshot, onDelta)
            val message = streamed ?: toolCall.params.optString("message", "")
            return AgentToolResult.success(userMessage = message)
        }
        if (toolCall.toolId == "agent.clarify") {
            val question = toolCall.params.optString("question", "")
            return AgentToolResult.success(userMessage = question)
        }

        val tool = AgentToolRegistry.findById(toolCall.toolId)
            ?: return AgentToolResult.failure("未知工具: ${toolCall.toolId}")

        val validation = tool.validate(toolCall.params, sessionContext)
        if (!validation.valid) {
            return if (validation.errorType == AgentErrorType.AMBIGUOUS) {
                AgentToolResult.success(userMessage = validation.errorMessage ?: "请提供更多信息")
            } else {
                AgentToolResult.failure(validation.errorMessage ?: "参数校验失败")
            }
        }

        if (AgentConfirmationController.shouldConfirm(tool, toolCall.params)) {
            val previewMsg = AgentConfirmationController.buildPreviewMessage(tool, toolCall.params, db)
            val pendingAction = PendingAgentAction.create(
                conversationId = conversationId,
                toolId = toolCall.toolId,
                params = toolCall.params,
                preview = previewMsg
            )
            PendingActionManager.save(pendingAction)
            ConversationStateManager.updateState(conversationId) { state ->
                state.withPendingAction(pendingAction)
            }
            return AgentToolResult.success(
                userMessage = "确认执行？\n$previewMsg\n\n回复「确认」执行，或回复「算了」取消",
                facts = JSONObject().apply {
                    put("pendingTool", toolCall.toolId)
                    put("pendingParams", toolCall.params)
                }
            )
        }

        return executeTool(tool, toolCall.params, sessionContext, userText, conversationId, historySnapshot, onDelta)
    }

    private suspend fun executeMultiStepCalls(
        multiStepCalls: MultiStepCalls,
        sessionContext: AgentSessionContext,
        userText: String,
        conversationId: String,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        onDelta: ((String) -> Unit)? = null
    ): AgentToolResult {
        val calls = multiStepCalls.calls
        if (calls.isEmpty()) {
            return AgentToolResult.failure("没有要执行的操作")
        }
        if (calls.size > MAX_CHAIN_STEPS) {
            return AgentToolResult.failure("最多支持 $MAX_CHAIN_STEPS 步操作")
        }

        return executeCallsFromIndex(calls, 0, sessionContext, conversationId, multiStepCalls.responseGoal, userText, historySnapshot)
    }

    private suspend fun executeCallsFromIndex(
        calls: List<ToolCall>,
        startIndex: Int,
        sessionContext: AgentSessionContext,
        conversationId: String,
        responseGoal: String,
        userText: String = "",
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null
    ): AgentToolResult {
        val allFacts = JSONObject()
        val results = mutableListOf<JSONObject>()
        val effects = mutableListOf<AgentEffect>()

        for (index in startIndex until calls.size) {
            val call = calls[index]
            Logger.d(context, LOG_TAG, "multi-step ${index + 1}/${calls.size}: ${call.toolId}")

            val tool = AgentToolRegistry.findById(call.toolId)
                ?: return AgentToolResult.failure("未知工具: ${call.toolId}")

            val validation = tool.validate(call.params, sessionContext)
            if (!validation.valid) {
                return AgentToolResult.failure("步骤 ${index + 1} 参数错误: ${validation.errorMessage}")
            }

            if (AgentConfirmationController.shouldConfirm(tool, call.params)) {
                val previewMsg = AgentConfirmationController.buildPreviewMessage(tool, call.params, db)
                val remainingCalls = if (index + 1 < calls.size) calls.subList(index + 1, calls.size) else emptyList()
                val pendingAction = PendingAgentAction.create(
                    conversationId = conversationId,
                    toolId = call.toolId,
                    params = call.params,
                    preview = "步骤 ${index + 1}: $previewMsg",
                    remainingCalls = remainingCalls,
                    responseGoal = responseGoal
                )
                PendingActionManager.save(pendingAction)
                ConversationStateManager.updateState(conversationId) { state ->
                    state.withPendingAction(pendingAction)
                }
                // Return confirmation message directly - do NOT pass through LLM
                return AgentToolResult.success(
                    userMessage = "需要确认步骤 ${index + 1}:\n$previewMsg\n\n回复「确认」执行，或回复「算了」取消",
                    facts = JSONObject().apply {
                        put("pendingTool", call.toolId)
                        put("pendingParams", call.params)
                        put("remainingSteps", calls.size - index - 1)
                    }
                )
            }

            val toolResult = executeToolDirect(tool, call.params, sessionContext, conversationId)

            if (!toolResult.success) {
                return toolResult
            }

            if (toolResult.facts != null) {
                results.add(toolResult.facts)
            }
            effects.addAll(toolResult.effects)
        }

        allFacts.put("steps", JSONArray(results))
        allFacts.put("stepCount", results.size)
        allFacts.put("responseGoal", responseGoal)

        val naturalReply = generateNaturalReply(userText, AgentToolResult.success(facts = allFacts), historySnapshot)
        return AgentToolResult.success(
            facts = allFacts,
            userMessage = naturalReply,
            effects = effects
        )
    }

    private fun parseMultiStepCalls(jsonStr: String): MultiStepCalls? {
        return try {
            val cleaned = jsonStr.trim()
            val jsonStr2 = if (cleaned.startsWith("{")) cleaned else {
                val start = cleaned.indexOf("{")
                val end = cleaned.lastIndexOf("}")
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else return null
            }
            val json = JSONObject(jsonStr2)
            val callsArray = json.optJSONArray("calls") ?: return null
            if (callsArray.length() == 0) return null

            val calls = mutableListOf<ToolCall>()
            for (i in 0 until callsArray.length()) {
                val callJson = callsArray.getJSONObject(i)
                val toolId = callJson.optString("tool", "").trim()
                if (toolId.isEmpty()) continue
                val params = callJson.optJSONObject("params") ?: JSONObject()
                calls.add(ToolCall(toolId, params, ""))
            }

            if (calls.isEmpty()) return null

            val responseGoal = json.optString("response_goal", "").trim()
            MultiStepCalls(calls, responseGoal)
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "parseMultiStepCalls error: ${e.message}")
            null
        }
    }

    private suspend fun handleConfirmation(conversationId: String, sessionContext: AgentSessionContext): AgentToolResult {
        val pendingAction = PendingActionManager.get(conversationId)
        if (pendingAction == null) {
            // Pending expired or missing - treat as normal message (caller should re-route)
            return AgentToolResult.success(userMessage = null, facts = JSONObject().apply { put("pendingExpired", true) })
        }

        val tool = AgentToolRegistry.findById(pendingAction.toolId)
        if (tool == null) {
            PendingActionManager.clear(conversationId)
            ConversationStateManager.updateState(conversationId) { it.withPendingAction(null) }
            return AgentToolResult.failure("工具已不可用: ${pendingAction.toolId}")
        }

        // Re-validate before execution
        val revalidation = tool.validate(pendingAction.params, sessionContext)
        if (!revalidation.valid) {
            PendingActionManager.clear(conversationId)
            ConversationStateManager.updateState(conversationId) { it.withPendingAction(null) }
            return AgentToolResult.failure("校验失败: ${revalidation.errorMessage}")
        }

        PendingActionManager.clear(conversationId)
        ConversationStateManager.updateState(conversationId) { it.withPendingAction(null) }

        val toolResult = executeToolDirect(tool, pendingAction.params, sessionContext, conversationId)
        if (!toolResult.success) return toolResult

        if (pendingAction.hasRemainingCalls()) {
            val remainingResult = executeCallsFromIndex(
                calls = pendingAction.remainingCalls,
                startIndex = 0,
                sessionContext = sessionContext,
                conversationId = conversationId,
                responseGoal = pendingAction.responseGoal
            )
            // If remaining chain needs confirmation, it returns directly (not LLM-processed)
            if (!remainingResult.success) return remainingResult

            val mergedFacts = JSONObject()
            if (toolResult.facts != null) mergedFacts.put("confirmedStep", toolResult.facts)
            if (remainingResult.facts != null) mergedFacts.put("remainingSteps", remainingResult.facts)
            mergedFacts.put("responseGoal", pendingAction.responseGoal)

            return AgentToolResult.success(
                facts = mergedFacts,
                userMessage = remainingResult.userMessage ?: toolResult.userMessage,
                effects = toolResult.effects + remainingResult.effects
            )
        }

        return toolResult
    }

    private fun handleCancellation(conversationId: String): AgentToolResult {
        PendingActionManager.clear(conversationId)
        ConversationStateManager.updateState(conversationId) { it.withPendingAction(null) }
        return AgentToolResult.success(userMessage = "已取消操作")
    }

    private fun isConfirmIntent(text: String): Boolean {
        val normalized = text.trim()
        val confirmWords = listOf("确认", "执行", "好的", "好", "可以", "是的", "对", "嗯", "ok", "yes", "y", "确定")
        return confirmWords.any { normalized.equals(it, ignoreCase = true) }
    }

    private fun isCancelIntent(text: String): Boolean {
        val normalized = text.trim()
        val cancelWords = listOf("取消", "算了", "不要了", "不用了", "不要", "不用", "取消吧", "no", "n")
        return cancelWords.any { normalized.equals(it, ignoreCase = true) }
    }

    private suspend fun executeTool(
        tool: AgentTool,
        params: JSONObject,
        sessionContext: AgentSessionContext,
        userText: String?,
        conversationId: String,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        onDelta: ((String) -> Unit)? = null
    ): AgentToolResult {
        val toolResult = executeToolDirect(tool, params, sessionContext, conversationId)
        if (!toolResult.success) return toolResult

        if (tool.risk == RiskLevel.READ || userText == null) {
            val naturalReply = generateNaturalReply(userText ?: "", toolResult, historySnapshot, onDelta)
            return AgentToolResult.success(
                facts = toolResult.facts,
                userMessage = naturalReply,
                uiAction = toolResult.uiAction,
                effects = toolResult.effects
            )
        }

        return toolResult
    }

    private suspend fun executeToolDirect(
        tool: AgentTool,
        params: JSONObject,
        sessionContext: AgentSessionContext,
        conversationId: String
    ): AgentToolResult {
        val toolResult = try {
            tool.execute(params, sessionContext)
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "executeTool error: ${e.message}")
            return AgentToolResult.failure("执行失败: ${e.message}")
        }

        if (!toolResult.success) return toolResult

        ConversationStateManager.updateState(conversationId) { state ->
            var updated = state.withLastTool(tool.id)
            if (toolResult.facts != null) {
                val billId = toolResult.facts.optLong("billId", 0)
                if (billId > 0) updated = updated.withRecentBill(billId)
                val assetId = toolResult.facts.optLong("assetId", 0)
                if (assetId > 0) updated = updated.withRecentAsset(assetId)
                // Also extract from "bills" array for list tools
                // Iterate in reverse so the first bill (newest) ends up first in recentBillIds
                val billsArray = toolResult.facts.optJSONArray("bills")
                if (billsArray != null) {
                    for (i in (billsArray.length() - 1) downTo 0) {
                        val bId = billsArray.optJSONObject(i)?.optLong("id", 0) ?: 0
                        if (bId > 0) updated = updated.withRecentBill(bId)
                    }
                }
            }
            updated
        }

        return toolResult
    }

    private suspend fun generateNaturalReply(
        userText: String,
        toolResult: AgentToolResult,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        onDelta: ((String) -> Unit)? = null
    ): String {
        val factsStr = toolResult.facts?.toString(2) ?: "无数据"
        val prompt = """
用户问: $userText

工具返回的数据:
$factsStr

请根据以上数据，用简洁自然的口语回复用户。要求:
1. 直接回答用户的问题，不要说"根据数据"、"查询结果"等官方用语
2. 金额保留2位小数
3. 简洁明了，像朋友聊天一样
4. 如果是余额查询，直接说"xx有xxx元"即可
5. 如果是花销查询，说"xx花了xxx元"即可，不需要列出笔数
""".trimIndent()

        val reply = callLlmForChat(prompt, historySnapshot, onDelta)
        return reply ?: toolResult.userMessage ?: "查询完成"
    }

    private suspend fun callLlmForChat(
        userText: String,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        onDelta: ((String) -> Unit)? = null
    ): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) return null

        val model = AiModelSlots.resolveChatModel(context)
        val messages = AgentLlmMessageBuilder.buildNaturalReplyMessages(userText, historySnapshot)

        val requestJson = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.5)
            add("messages", messages)
        }

        return try {
            if (onDelta != null) {
                callLlmForChatStream(apiKey, requestJson, onDelta)
            } else {
                val response = getApi().chatRaw("Bearer $apiKey", requestJson)
                response.choices?.firstOrNull()?.message?.content
            }
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "callLlmForChat error: ${e.message}")
            null
        }
    }

    private suspend fun callLlmForChatStream(
        apiKey: String,
        requestJson: JsonObject,
        onDelta: (String) -> Unit
    ): String? {
        val streamReq = requestJson.deepCopy().apply { addProperty("stream", true) }
        val content = StringBuilder()
        var sawDone = false
        getApi().chatStreamRaw("Bearer $apiKey", streamReq).use { responseBody ->
            val source = responseBody.source()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") {
                    sawDone = true
                    break
                }
                try {
                    val deltaObj = JSONObject(payload)
                        .optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                    val delta = jsonOptStringOrEmpty(deltaObj, "content")
                    if (delta.isNotEmpty()) {
                        content.append(delta)
                        onDelta(delta)
                    }
                } catch (_: JSONException) {
                    return null
                }
            }
        }
        return if (sawDone) stripAccidentalNullPrefix(content.toString()).trim() else null
    }

    private fun jsonOptStringOrEmpty(obj: JSONObject?, key: String): String {
        if (obj == null || obj.isNull(key)) return ""
        val value = obj.optString(key, "")
        return if (value.equals("null", ignoreCase = true)) "" else value
    }

    private fun stripAccidentalNullPrefix(raw: String): String {
        var text = raw
        while (text.startsWith("null", ignoreCase = true)) {
            text = text.substring(4)
        }
        return text
    }

    private suspend fun callLlmForToolSelection(
        systemPrompt: String,
        userText: String,
        historySnapshot: List<com.taostudio.tapaccounting.data.local.entity.ChatMessage>? = null,
        images: List<AgentImageInput> = emptyList()
    ): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = if (images.isNotEmpty()) {
            AiModelSlots.resolveVisionModel(context)
        } else {
            AiModelSlots.resolveChatModel(context)
        }
        val messages = AgentLlmMessageBuilder.buildToolSelectionMessages(
            systemPrompt,
            userText,
            historySnapshot,
            images
        )

        val requestJson = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.3)
            add("messages", messages)
            add("response_format", JsonObject().apply {
                addProperty("type", "json_object")
            })
        }

        return try {
            val response = getApi().chatRaw("Bearer $apiKey", requestJson)
            val content = response.choices?.firstOrNull()?.message?.content
            Logger.d(context, LOG_TAG, "LLM response: ${content?.take(500)}")
            content
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "callLlm error: ${e.message}")
            throw e
        }
    }

    private fun parseToolCall(jsonStr: String): ToolCall? {
        return try {
            val cleaned = jsonStr.trim()
            Logger.d(context, LOG_TAG, "parseToolCall input: ${cleaned.take(300)}")

            val jsonStr2 = if (cleaned.startsWith("{")) cleaned else {
                val start = cleaned.indexOf("{")
                val end = cleaned.lastIndexOf("}")
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else return null
            }
            val json = JSONObject(jsonStr2)
            val toolId = json.optString("tool", "").trim()
            if (toolId.isEmpty()) {
                Logger.d(context, LOG_TAG, "parseToolCall: tool is empty")
                return null
            }
            val params = json.optJSONObject("params") ?: JSONObject()
            val hint = json.optString("assistant_hint", "").trim()
            Logger.d(context, LOG_TAG, "parseToolCall result: tool=$toolId, params=$params")
            ToolCall(toolId, params, hint)
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "parseToolCall error: ${e.message}")
            null
        }
    }

    private suspend fun buildSessionContext(): AgentSessionContext {
        val queryContext = QueryContextBuilder(db).build(getCurrentBookName())
        return AgentSessionContext(
            bookName = getCurrentBookName(),
            conversationId = getCurrentConversationId(),
            queryContext = queryContext
        )
    }

    // History formatting is delegated to AgentLlmMessageBuilder.
    // The orchestrator always receives a pre-built snapshot from the caller.

    data class ToolCall(
        val toolId: String,
        val params: JSONObject,
        val assistantHint: String
    )

    data class MultiStepCalls(
        val calls: List<ToolCall>,
        val responseGoal: String
    )
}
