package com.taostudio.tapaccounting.chat.agent

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.taostudio.tapaccounting.AiModelSlots
import com.taostudio.tapaccounting.Logger
import com.taostudio.tapaccounting.Prefs
import com.taostudio.tapaccounting.SiliconFlowApi
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
    private val onDelta: ((String) -> Unit)? = null
) {
    companion object {
        private const val LOG_TAG = "ChatAgentOrchestrator"
        private const val MAX_CHAIN_STEPS = 5
        private const val API_CONNECT_TIMEOUT_SECONDS = 60L
        private const val API_READ_TIMEOUT_SECONDS = 90L
        private const val API_WRITE_TIMEOUT_SECONDS = 90L
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

        val sessionContext = buildSessionContext()
        val systemPrompt = AgentPromptBuilder.buildSystemPrompt(sessionContext)

        val toolCallJson = callLlmForToolSelection(systemPrompt, userText)
            ?: return AgentToolResult.failure("无法理解您的请求，请重试")

        val toolCall = parseToolCall(toolCallJson)
            ?: return AgentToolResult.failure("无法解析工具调用")

        return executeToolCall(toolCall, sessionContext)
    }

    private suspend fun callLlmForToolSelection(
        systemPrompt: String,
        userText: String
    ): String? {
        val apiKey = Prefs.getAiKey(context)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveChatModel(context)

        val messages = JsonArray().apply {
            add(buildTextMessage("system", systemPrompt))
            add(buildTextMessage("user", userText))
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

    private fun buildTextMessage(role: String, content: String): JsonObject =
        JsonObject().apply {
            addProperty("role", role)
            addProperty("content", content)
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

    data class ToolCall(
        val toolId: String,
        val params: JSONObject,
        val assistantHint: String
    )
}
