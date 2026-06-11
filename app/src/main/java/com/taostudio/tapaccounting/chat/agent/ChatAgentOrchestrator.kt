package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taostudio.tapaccounting.AiModelSlots
import com.taostudio.tapaccounting.ChatTurn
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.SiliconFlowApi
import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRegistry
import com.taostudio.tapaccounting.chat.agent.skill.AgentSkillRouter
import com.taostudio.tapaccounting.chat.query.QueryContextBuilder
import com.taostudio.tapaccounting.data.local.AppDatabase
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class ChatAgentOrchestrator(
    private val context: Context,
    private val db: AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val onToolResult: (AgentToolResult) -> Unit,
    private val onDelta: ((String) -> Unit)? = null,
    private val getHistoryTurns: (suspend () -> List<ChatTurn>)? = null
) {
    companion object {
        private const val LOG_TAG = "ChatAgentOrchestrator"
        const val MAX_CHAIN_STEPS = 5
        private const val API_CONNECT_TIMEOUT_SECONDS = 60L
        private const val API_READ_TIMEOUT_SECONDS = 90L
        private const val API_WRITE_TIMEOUT_SECONDS = 90L

        /** Meta tools that are always available regardless of skill */
        private val META_TOOL_IDS = setOf(
            "chat.reply",
            "agent.clarify",
            "agent.list_capabilities",
            "agent.cancel",
            "agent.unsupported"
        )

        const val UNSUPPORTED_MESSAGE = "该功能尚未实现"
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

    suspend fun handle(userText: String): AgentToolResult {
        Logger.d(context, LOG_TAG, "handle: ${userText.take(200)}")
        val conversationId = getCurrentConversationId()

        // 0. Check for pending confirmation action
        val pending = PendingActionManager.get(conversationId)
        if (pending != null) {
            return handlePendingConfirmation(pending, userText)
        }

        val sessionContext = buildSessionContext()

        // 1. Route to skills
        val selectedSkillIds = AgentSkillRouter.routeWithFallback(userText, context)
        Logger.d(context, LOG_TAG, "Selected skills: $selectedSkillIds")

        // 2. Build allowed tool set: meta tools + skill tools
        val skillToolIds = AgentSkillRegistry.getToolsForSkills(selectedSkillIds.toSet())
        val allowedToolIds = (META_TOOL_IDS + skillToolIds).toSet()
        val allowedTools = allowedToolIds.mapNotNull { AgentToolRegistry.findById(it) }
        Logger.d(context, LOG_TAG, "Allowed tools (${allowedTools.size}): ${allowedTools.map { it.id }}")

        // 3. Build dynamic system prompt with allowed tools
        val systemPrompt = AgentPromptBuilder.buildSystemPrompt(sessionContext, selectedSkillIds, allowedTools)

        // 4. Build conversation history
        val historyTurns = getHistoryTurns?.invoke() ?: emptyList()

        // 5. Call LLM for tool selection (with history)
        val toolCallJson = callLlmForToolSelection(systemPrompt, userText, historyTurns)
            ?: return AgentToolResult.failure("无法理解您的请求，请重试")

        // 6. Parse response — may be single tool or multi-step
        val parsed = parseResponse(toolCallJson)
        if (parsed == null) {
            return AgentToolResult.failure("无法解析工具调用")
        }

        // 7. Execute
        return when (parsed) {
            is ParsedResponse.Single -> {
                if (parsed.toolCall.toolId !in allowedToolIds) {
                    return unsupportedResult()
                }
                executeSingleToolCall(parsed.toolCall, sessionContext, conversationId)
            }
            is ParsedResponse.MultiStep -> {
                if (parsed.calls.any { it.toolId !in allowedToolIds }) {
                    return unsupportedResult()
                }
                executeMultiStepCalls(parsed.calls, sessionContext, conversationId, parsed.responseGoal)
            }
        }
    }

    // region Pending confirmation

    private suspend fun handlePendingConfirmation(pending: PendingAgentAction, userText: String): AgentToolResult {
        val conversationId = pending.conversationId

        if (isConfirmIntent(userText)) {
            // Re-validate before executing
            val tool = AgentToolRegistry.findById(pending.toolId)
                ?: run {
                    PendingActionManager.clear(conversationId)
                    return AgentToolResult.failure("工具已不可用: ${pending.toolId}")
                }

            val validation = tool.validate(pending.params, buildSessionContext())
            if (!validation.valid) {
                PendingActionManager.clear(conversationId)
                return AgentToolResult.failure(validation.errorMessage ?: "参数校验失败")
            }

            // Execute the pending tool
            val result = try {
                tool.execute(pending.params, buildSessionContext())
            } catch (e: Exception) {
                Logger.d(context, LOG_TAG, "pending execute error: ${e.message}")
                AgentToolResult.failure("执行失败: ${e.message}")
            }

            // Clear pending action
            PendingActionManager.clear(conversationId)

            if (!result.success) return result

            if (result.uiAction is UiAction.StartAccounting) {
                return result
            }

            // If there are remaining multi-step calls, continue
            if (pending.hasRemainingCalls()) {
                val remaining = pending.remainingCalls
                val goal = pending.responseGoal
                // Execute remaining as a new chain (starting from step 2)
                val chainResult = executeMultiStepCalls(
                    remaining, buildSessionContext(), conversationId, goal, startStep = 2
                )
                // Merge facts
                return AgentToolResult.success(
                    facts = mergeFacts(result.facts, chainResult.facts),
                    userMessage = chainResult.userMessage ?: result.userMessage,
                    uiAction = chainResult.uiAction ?: result.uiAction
                )
            }

            // Generate natural reply for single confirmation
            val naturalReply = generateNaturalReplySafe(result)
            return AgentToolResult.success(
                facts = result.facts,
                userMessage = naturalReply,
                uiAction = result.uiAction
            )
        }

        if (isCancelIntent(userText)) {
            PendingActionManager.clear(conversationId)
            return AgentToolResult.success(userMessage = "已取消操作")
        }

        // Other input: cancel pending and process as new request
        PendingActionManager.clear(conversationId)
        return handle(userText)
    }

    // endregion

    // region Single tool execution

    private suspend fun executeSingleToolCall(
        toolCall: ToolCall,
        sessionContext: AgentSessionContext,
        conversationId: String
    ): AgentToolResult {
        // Handle meta tools
        when (toolCall.toolId) {
            "chat.reply" -> {
                val message = toolCall.params.optString("message", "")
                return AgentToolResult.success(userMessage = message)
            }
            "agent.clarify" -> {
                val question = toolCall.params.optString("question", "")
                return AgentToolResult.success(userMessage = question)
            }
            "agent.cancel" -> {
                PendingActionManager.clear(conversationId)
                return AgentToolResult.success(userMessage = "已取消当前操作")
            }
            "agent.unsupported" -> {
                val feature = toolCall.params.optString("feature", "").trim()
                return AgentToolResult.success(
                    userMessage = if (feature.isBlank()) {
                        UNSUPPORTED_MESSAGE
                    } else {
                        "“$feature”功能尚未实现"
                    }
                )
            }
        }

        val tool = AgentToolRegistry.findById(toolCall.toolId)
            ?: return unsupportedResult()

        // Validate
        val validation = tool.validate(toolCall.params, sessionContext)
        if (!validation.valid) {
            return when (validation.errorType) {
                AgentErrorType.AMBIGUOUS -> {
                    AgentToolResult.success(userMessage = validation.errorMessage ?: "找到多个匹配项，请明确指定")
                }
                AgentErrorType.NOT_FOUND -> {
                    AgentToolResult.failure(validation.errorMessage ?: "未找到目标")
                }
                else -> {
                    AgentToolResult.failure(validation.errorMessage ?: "参数校验失败")
                }
            }
        }

        // Check if confirmation needed
        if (AgentConfirmationController.shouldConfirm(tool, toolCall.params)) {
            val previewMsg = AgentConfirmationController.buildPreviewMessage(tool, toolCall.params)
            val pendingAction = PendingAgentAction.create(
                conversationId = conversationId,
                toolId = toolCall.toolId,
                params = toolCall.params,
                preview = previewMsg
            )
            PendingActionManager.save(pendingAction)
            return AgentToolResult.success(
                userMessage = "确认执行？\n$previewMsg\n\n回复「确认」执行，或回复「取消」放弃"
            )
        }

        // Execute
        return try {
            val result = tool.execute(toolCall.params, sessionContext)
            if (result.success) {
                if (result.uiAction is UiAction.StartAccounting) {
                    return result
                }
                val naturalReply = generateNaturalReplySafe(result)
                AgentToolResult.success(
                    facts = result.facts,
                    userMessage = naturalReply,
                    uiAction = result.uiAction
                )
            } else {
                result
            }
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "executeToolCall error: ${e.message}")
            AgentToolResult.failure("执行失败: ${e.message}")
        }
    }

    // endregion

    // region Multi-step execution

    private suspend fun executeMultiStepCalls(
        calls: List<ToolCall>,
        sessionContext: AgentSessionContext,
        conversationId: String,
        responseGoal: String,
        startStep: Int = 1
    ): AgentToolResult {
        var step = startStep
        val allFacts = JSONObject()
        var lastResult: AgentToolResult? = null

        for (call in calls) {
            if (step > MAX_CHAIN_STEPS) {
                Logger.d(context, LOG_TAG, "Max chain steps ($MAX_CHAIN_STEPS) reached, stopping")
                break
            }

            val tool = AgentToolRegistry.findById(call.toolId)
                ?: return unsupportedResult()

            // Validate each step
            val validation = tool.validate(call.params, sessionContext)
            if (!validation.valid) {
                return AgentToolResult.failure("步骤 $step 参数错误: ${validation.errorMessage}")
            }

            // If step needs confirmation, save remaining and pause
            if (AgentConfirmationController.shouldConfirm(tool, call.params)) {
                val previewMsg = AgentConfirmationController.buildPreviewMessage(tool, call.params)
                val remainingCalls = calls.drop(step - startStep + 1)
                val pendingAction = PendingAgentAction.create(
                    conversationId = conversationId,
                    toolId = call.toolId,
                    params = call.params,
                    preview = "步骤 $step: $previewMsg",
                    remainingCalls = remainingCalls,
                    responseGoal = responseGoal
                )
                PendingActionManager.save(pendingAction)
                return AgentToolResult.success(
                    userMessage = "步骤 $step 需要确认：\n$previewMsg\n\n回复「确认」执行，或回复「取消」放弃"
                )
            }

            // Execute
            val result = try {
                tool.execute(call.params, sessionContext)
            } catch (e: Exception) {
                Logger.d(context, LOG_TAG, "Step $step error: ${e.message}")
                // Stop chain on write failure
                if (tool.risk != RiskLevel.READ) {
                    return AgentToolResult.failure("步骤 $step 执行失败: ${e.message}")
                }
                AgentToolResult.failure("步骤 $step 执行失败: ${e.message}")
            }

            // Merge facts
            result.facts?.let { facts ->
                for (key in facts.keys()) {
                    allFacts.put("step${step}_$key", facts.get(key))
                }
            }
            lastResult = result

            step++
        }

        // Generate natural reply from combined facts
        val combinedResult = AgentToolResult.success(
            facts = allFacts,
            userMessage = lastResult?.userMessage,
            uiAction = lastResult?.uiAction
        )
        val naturalReply = generateNaturalReplySafe(combinedResult)
        return AgentToolResult.success(
            facts = allFacts,
            userMessage = naturalReply,
            uiAction = lastResult?.uiAction
        )
    }

    // endregion

    // region LLM calls

    private suspend fun generateNaturalReplySafe(toolResult: AgentToolResult): String {
        // For WRITE/NAV tools, use userMessage directly without LLM call
        if (toolResult.userMessage != null && toolResult.facts == null) {
            return toolResult.userMessage
        }

        val factsStr = toolResult.facts?.toString(2) ?: ""
        if (factsStr.isBlank()) {
            return toolResult.userMessage ?: "操作完成"
        }

        val prompt = """工具返回的原始数据如下，你必须直接引用这些数字，不得修改：

$factsStr

请用简洁自然的口语回复用户。要求：
1. 金额、数量、日期必须与上面数据完全一致
2. 不要说"根据数据"等官方用语
3. 简洁明了"""

        val reply = callLlmForChat(prompt)

        // Fact-check: verify key numbers from facts appear in reply
        if (reply != null && toolResult.facts != null) {
            val verified = verifyFactsInReply(reply, toolResult.facts)
            if (!verified) {
                Logger.d(context, LOG_TAG, "Reply failed fact check, falling back to userMessage")
                return toolResult.userMessage ?: "查询完成"
            }
        }

        return reply ?: toolResult.userMessage ?: "查询完成"
    }

    private fun verifyFactsInReply(reply: String, facts: JSONObject): Boolean {
        // Check that key numeric values from facts appear in reply
        val keysToCheck = listOf("totalAmount", "amount", "balance", "expense", "income", "count", "billCount")
        for (key in keysToCheck) {
            val value = facts.optString(key, "")
            if (value.isNotBlank() && value != "0" && value != "0.00") {
                // If the fact has a meaningful value, verify it appears in reply
                if (value.length >= 3 && !reply.contains(value)) {
                    Logger.d(context, LOG_TAG, "Fact check failed: '$key=$value' not found in reply")
                    return false
                }
            }
        }
        return true
    }

    private suspend fun callLlmForChat(userText: String): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) return null

        val model = AiModelSlots.resolveChatModel(context)
        val messages = JsonArray().apply {
            add(buildGsonMessage("system", "你是一个记账助手，用简洁自然的口语回复用户。金额和数字必须与提供的数据完全一致。"))
            add(buildGsonMessage("user", userText))
        }

        val requestJson = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.3)
            add("messages", messages)
        }

        return try {
            val response = getApi().chatRaw("Bearer $apiKey", requestJson)
            response.choices?.firstOrNull()?.message?.content
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "callLlmForChat error: ${e.message}")
            null
        }
    }

    private suspend fun callLlmForToolSelection(
        systemPrompt: String,
        userText: String,
        historyTurns: List<ChatTurn>
    ): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveChatModel(context)

        val messages = JsonArray().apply {
            add(buildGsonMessage("system", systemPrompt))
            // Add conversation history
            for (turn in historyTurns.takeLast(20)) {
                add(buildGsonMessage(turn.role, turn.content))
            }
            add(buildGsonMessage("user", userText))
        }

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
            null
        }
    }

    private fun buildGsonMessage(role: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
        }

    // endregion

    // region Parsing

    private sealed class ParsedResponse {
        class Single(val toolCall: ToolCall) : ParsedResponse()
        class MultiStep(val calls: List<ToolCall>, val responseGoal: String) : ParsedResponse()
    }

    private fun parseResponse(jsonStr: String): ParsedResponse? {
        return try {
            val cleaned = jsonStr.trim()
            val jsonStr2 = extractJsonObject(cleaned) ?: return null
            val json = JSONObject(jsonStr2)

            // Check for multi-step response
            val callsArray = json.optJSONArray("calls")
            if (callsArray != null && callsArray.length() > 0) {
                val calls = mutableListOf<ToolCall>()
                for (i in 0 until callsArray.length().coerceAtMost(MAX_CHAIN_STEPS)) {
                    val callJson = callsArray.getJSONObject(i)
                    val toolId = callJson.optString("tool", "").trim()
                    if (toolId.isEmpty()) continue
                    val params = callJson.optJSONObject("params") ?: JSONObject()
                    calls.add(ToolCall(toolId, params, ""))
                }
                if (calls.isEmpty()) return null
                val goal = json.optString("response_goal", "")
                return ParsedResponse.MultiStep(calls, goal)
            }

            // Single tool call
            val toolId = json.optString("tool", "").trim()
            if (toolId.isEmpty()) {
                Logger.d(context, LOG_TAG, "parseResponse: tool is empty")
                return null
            }
            val params = json.optJSONObject("params") ?: JSONObject()
            val hint = json.optString("assistant_hint", "").trim()
            Logger.d(context, LOG_TAG, "parseResponse: tool=$toolId, params=$params")
            ParsedResponse.Single(ToolCall(toolId, params, hint))
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "parseResponse error: ${e.message}")
            null
        }
    }

    private fun extractJsonObject(text: String): String? {
        if (text.startsWith("{")) return text
        val start = text.indexOf("{")
        val end = text.lastIndexOf("}")
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    private fun unsupportedResult(): AgentToolResult =
        AgentToolResult.success(userMessage = UNSUPPORTED_MESSAGE)

    // endregion

    // region Intent detection

    fun isConfirmIntent(text: String): Boolean {
        val normalized = text.trim()
        val confirmWords = listOf("确认", "执行", "好的", "好", "可以", "是的", "对", "嗯", "ok", "yes", "y", "确定")
        return confirmWords.any { normalized.equals(it, ignoreCase = true) }
    }

    fun isCancelIntent(text: String): Boolean {
        val normalized = text.trim()
        val cancelWords = listOf("取消", "算了", "不要了", "不用了", "不要", "不用", "取消吧", "no", "n")
        return cancelWords.any { normalized.equals(it, ignoreCase = true) }
    }

    // endregion

    // region Helpers

    private suspend fun buildSessionContext(): AgentSessionContext {
        val queryContext = QueryContextBuilder(db).build(getCurrentBookName())
        return AgentSessionContext(
            bookName = getCurrentBookName(),
            conversationId = getCurrentConversationId(),
            queryContext = queryContext
        )
    }

    private fun mergeFacts(f1: JSONObject?, f2: JSONObject?): JSONObject? {
        if (f1 == null) return f2
        if (f2 == null) return f1
        val merged = JSONObject()
        for (key in f1.keys()) merged.put(key, f1.get(key))
        for (key in f2.keys()) merged.put(key, f2.get(key))
        return merged
    }

    // endregion

    data class ToolCall(
        val toolId: String,
        val params: JSONObject,
        val assistantHint: String
    )
}
