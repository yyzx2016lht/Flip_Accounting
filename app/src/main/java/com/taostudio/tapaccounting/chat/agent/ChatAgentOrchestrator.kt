package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.taostudio.tapaccounting.AIService
import com.taostudio.tapaccounting.ChatTurn
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.chat.query.QueryContextBuilder
import com.taostudio.tapaccounting.data.local.AppDatabase
import org.json.JSONObject

class ChatAgentOrchestrator(
    private val context: Context,
    private val db: AppDatabase,
    private val getCurrentBookName: () -> String,
    private val getCurrentConversationId: () -> String,
    private val onToolResult: (AgentToolResult) -> Unit,
    private val onDelta: ((String) -> Unit)? = null
) {
    companion object {
        private const val LOG_TAG = "ChatAgentOrchestrator"
        private const val MAX_CHAIN_STEPS = 5
    }

    suspend fun handle(userText: String, chatTurns: List<ChatTurn> = emptyList()): AgentToolResult {
        Logger.d(context, LOG_TAG, "handle: ${userText.take(200)}")

        val sessionContext = buildSessionContext()
        val systemPrompt = AgentPromptBuilder.buildSystemPrompt(sessionContext)
        val userPrompt = AgentPromptBuilder.buildUserPrompt(userText, sessionContext)

        val toolCallJson = callLlmForToolSelection(systemPrompt, userPrompt, chatTurns)
            ?: return AgentToolResult.failure("无法理解您的请求，请重试")

        val toolCall = parseToolCall(toolCallJson)
            ?: return AgentToolResult.failure("无法解析工具调用")

        return executeToolCall(toolCall, sessionContext)
    }

    suspend fun handleWithChain(userText: String, chatTurns: List<ChatTurn> = emptyList()): AgentToolResult {
        Logger.d(context, LOG_TAG, "handleWithChain: ${userText.take(200)}")

        val sessionContext = buildSessionContext()
        val systemPrompt = AgentPromptBuilder.buildSystemPrompt(sessionContext)
        val userPrompt = AgentPromptBuilder.buildUserPrompt(userText, sessionContext)

        var currentPrompt = userPrompt
        var step = 0
        var lastResult: AgentToolResult = AgentToolResult.failure("未执行任何操作")

        while (step < MAX_CHAIN_STEPS) {
            val toolCallJson = callLlmForToolSelection(systemPrompt, currentPrompt, chatTurns)
                ?: break

            val toolCall = parseToolCall(toolCallJson) ?: break

            if (toolCall.toolId == "chat.reply") {
                val message = toolCall.params.optString("message", "")
                return AgentToolResult.success(userMessage = message)
            }

            if (toolCall.toolId == "agent.clarify") {
                val question = toolCall.params.optString("question", "")
                return AgentToolResult.success(userMessage = question)
            }

            lastResult = executeToolCall(toolCall, sessionContext)
            onToolResult(lastResult)

            if (!lastResult.success) break
            if (toolCall.toolId.startsWith("nav.")) break

            currentPrompt = buildChainContinuePrompt(userText, lastResult)
            step++
        }

        return lastResult
    }

    private suspend fun callLlmForToolSelection(
        systemPrompt: String,
        userPrompt: String,
        chatTurns: List<ChatTurn>
    ): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val result = AIService.generateGeneralChatReply(
            ctx = context,
            userInput = userPrompt,
            chatTurns = chatTurns,
            replyGuideHint = "请根据用户消息选择合适的工具并返回JSON格式的工具调用"
        )

        return if (result.completed) result.content else null
    }

    private fun parseToolCall(jsonStr: String): ToolCall? {
        return try {
            val cleaned = jsonStr.trim()
            val jsonStr2 = if (cleaned.startsWith("{")) cleaned else {
                val start = cleaned.indexOf("{")
                val end = cleaned.lastIndexOf("}")
                if (start >= 0 && end > start) cleaned.substring(start, end + 1) else return null
            }
            val json = JSONObject(jsonStr2)
            val toolId = json.optString("tool", "").trim()
            if (toolId.isEmpty()) return null
            val params = json.optJSONObject("params") ?: JSONObject()
            val hint = json.optString("assistant_hint", "").trim()
            ToolCall(toolId, params, hint)
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "parseToolCall error: ${e.message}")
            null
        }
    }

    private suspend fun executeToolCall(toolCall: ToolCall, sessionContext: AgentSessionContext): AgentToolResult {
        val tool = AgentToolRegistry.findById(toolCall.toolId)
            ?: return AgentToolResult.failure("未知工具: ${toolCall.toolId}")

        if (AgentConfirmationController.shouldConfirm(tool, toolCall.params)) {
            val previewMsg = AgentConfirmationController.buildPreviewMessage(tool, toolCall.params)
            return AgentToolResult.success(
                userMessage = "确认执行？\n$previewMsg\n\n回复「确认」执行，或回复其他取消",
                facts = JSONObject().apply {
                    put("pendingTool", toolCall.toolId)
                    put("pendingParams", toolCall.params)
                }
            )
        }

        return try {
            tool.execute(toolCall.params, sessionContext)
        } catch (e: Exception) {
            Logger.d(context, LOG_TAG, "executeToolCall error: ${e.message}")
            AgentToolResult.failure("执行失败: ${e.message}")
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

    private fun buildChainContinuePrompt(originalUserText: String, lastResult: AgentToolResult): String {
        val sb = StringBuilder()
        sb.appendLine("原始用户请求: $originalUserText")
        sb.appendLine("上一步结果: ${lastResult.userMessage ?: "成功"}")
        if (lastResult.facts != null) {
            sb.appendLine("结果数据: ${lastResult.facts.toString().take(500)}")
        }
        sb.appendLine("请根据结果决定下一步操作，或用chat.reply回复用户。")
        return sb.toString()
    }

    data class ToolCall(
        val toolId: String,
        val params: JSONObject,
        val assistantHint: String
    )
}
