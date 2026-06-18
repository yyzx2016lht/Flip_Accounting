package com.taostudio.tapaccounting

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.Gson
import com.taostudio.tapaccounting.data.local.AppDatabase
import com.taostudio.tapaccounting.data.local.entity.Asset
import com.taostudio.tapaccounting.data.local.entity.AiRule as DbAiRule
import com.taostudio.tapaccounting.data.local.entity.Bill
import com.google.gson.JsonObject
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

// OCR 模式常量
const val OCR_MODE_LOCAL      = 0   // 本地 ML Kit OCR + 文本 AI
const val OCR_MODE_MULTIMODAL = 1   // 直接多模态 AI（发送图片）

object AIService {
    private const val MAX_AUDIO_INLINE_BYTES = 8L * 1024L * 1024L
    private const val ACCOUNTING_MULTI_LOG_TAG = "AccountingMulti"
    private const val ACCOUNTING_AUDIO_MULTI_LOG_TAG = "AccountingAudioMulti"
    private const val RECEIPT_VISION_LOG_TAG = "ReceiptVision"
    private const val SCREEN_ACCOUNTING_LOG_TAG = "ScreenAccounting"
    private const val ACCOUNTING_ASSISTANT_LOG_TAG = "AccountingAssistant"
    private const val GENERAL_CHAT_LOG_TAG = "GeneralChat"
    private const val SIMPLE_CHAT_LOG_TAG = "SimpleChat"
    private const val AI_IO_LOG_TAG = "AI_IO"
    private const val AI_CACHE_LOG_TAG = "AICache"

    const val MULTI_BILL_PROMPT_DEFAULT = AIPrompts.MULTI_BILL_PROMPT_DEFAULT
    val RULE_EXTRACT_PROMPT_DEFAULT              get() = com.taostudio.tapaccounting.logic.RuleDialogHelper.DEFAULT_RULE_PROMPT
    const val RECEIPT_VISION_RETRY_PROMPT_DEFAULT= AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT
    const val IMAGE_ACCOUNTING_PROMPT   = AIPrompts.IMAGE_ACCOUNTING_PROMPT
    const val CHAT_ASSISTANT_PROMPT_DEFAULT      = AIPrompts.CHAT_ASSISTANT_PROMPT_DEFAULT
    const val INTENT_ROUTER_PROMPT_DEFAULT       = AIPrompts.INTENT_ROUTER_PROMPT_DEFAULT
    private const val MAX_ACCOUNTING_INPUT_CHARS = 12000

    private const val MAX_ASSISTANT_INPUT_CHARS = 4000
    private const val MAX_ASSISTANT_SUMMARY_CHARS = 2500
    private const val API_CONNECT_TIMEOUT_SECONDS = 60L
    private const val API_READ_TIMEOUT_SECONDS = 90L
    private const val API_WRITE_TIMEOUT_SECONDS = 90L
    private const val SPEECH_CONNECT_TIMEOUT_SECONDS = 60L
    private const val SPEECH_READ_TIMEOUT_SECONDS = 180L
    private const val SPEECH_WRITE_TIMEOUT_SECONDS = 180L
    private const val DEFAULT_CUSTOM_REPLY_STYLE_GUIDE =
        "回复风格：按用户自定义要求回复。请直接对用户说自然的人话，不要输出场景标签、英文状态词、JSON 或内部指令。"
    private fun enableThinkingForAccounting(ctx: Context): Boolean = Prefs.isAiThinkingMultiBillEnabled(ctx)
    private fun enableThinkingForVision(ctx: Context): Boolean = Prefs.isAiThinkingVisionEnabled(ctx)

    fun getDefaultSingleBillPrompt(ctx: Context): String =
        getDefaultMultiBillPrompt(ctx)

    fun getDefaultMultiBillPrompt(ctx: Context): String =
        if (Prefs.isAssetFeatureEnabled(ctx)) {
            AIPrompts.MULTI_BILL_PROMPT_DEFAULT
        } else {
            AIPromptsWithoutAccount.MULTI_BILL_PROMPT_DEFAULT
        }

    private val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(API_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(API_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(API_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private val speechClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SPEECH_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SPEECH_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(SPEECH_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
            .build()
    }

    @Volatile private var cachedApi: Pair<String, SiliconFlowApi>? = null
    @Volatile private var cachedSpeechApi: Pair<String, SiliconFlowApi>? = null

    private fun getApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        cachedApi?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(sharedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
        cachedApi = baseUrl to api
        return api
    }

    private fun getSpeechApi(ctx: Context): SiliconFlowApi {
        val baseUrl = normalizeBaseUrl(Prefs.getAiUrl(ctx))
        cachedSpeechApi?.let { (url, api) -> if (url == baseUrl) return api }
        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(speechClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SiliconFlowApi::class.java)
        cachedSpeechApi = baseUrl to api
        return api
    }

    suspend fun speechToText(ctx: Context, audioFile: File): String? {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) return null
        if (!audioFile.exists() || audioFile.length() <= 44L) return null

        val mimeType = detectSpeechAudioMimeType(audioFile)
        val modelName = AiModelSlots.resolveSpeechModel(ctx)
        if (modelName.isEmpty()) return null

        return try {
            val providerId = Prefs.getAiProvider(ctx)
            val parsed = when (providerId) {
                AiProviderRegistry.PROVIDER_QWEN,
                AiProviderRegistry.PROVIDER_MIMO -> speechToTextViaChatInputAudio(
                    ctx = ctx,
                    apiKey = apiKey,
                    audioFile = audioFile,
                    mimeType = mimeType,
                    modelName = modelName
                )

                else -> {
                    val requestFile = audioFile.asRequestBody(mimeType.toMediaTypeOrNull())
                    val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
                    val modelPart = MultipartBody.Part.createFormData("model", modelName)
                    parseSpeechText(
                        getSpeechApi(ctx).transcribe("Bearer $apiKey", modelPart, filePart)
                    )
                }
            }
            if (!parsed.isNullOrBlank()) {
                Logger.d(ctx, "AIService", "Cloud ASR success. model=$modelName, mime=$mimeType, textLen=${parsed.length}")
            } else {
                Logger.d(ctx, "AIService", "Cloud ASR empty response. model=$modelName")
            }
            parsed
        } catch (e: Exception) {
            Logger.dPriv(
                ctx,
                "AIService",
                "Cloud ASR failed. model=$modelName, errType=${e.javaClass.simpleName}",
                "Cloud ASR failure detail=${detailedHttpError(e)}"
            )
            null
        }
    }

    private suspend fun speechToTextViaChatInputAudio(
        ctx: Context,
        apiKey: String,
        audioFile: File,
        mimeType: String,
        modelName: String
    ): String? {
        if (audioFile.length() > MAX_AUDIO_INLINE_BYTES) return null
        val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        val requestJson = JsonObject().apply {
            addProperty("model", modelName)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("type", "input_audio")
                            add("input_audio", JsonObject().apply {
                                addProperty("data", "data:$mimeType;base64,$audioBase64")
                            })
                        })
                    })
                })
            })
        }
        if (Prefs.getAiProvider(ctx) == AiProviderRegistry.PROVIDER_MIMO) {
            requestJson.add("asr_options", JsonObject().apply {
                addProperty("language", "auto")
            })
        }
        val response = getApi(ctx).chatRaw(
            "Bearer $apiKey",
            adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
        )
        return response.choices.firstOrNull()?.message?.content?.trim()
    }

    private fun parseSpeechText(response: AudioResponse): String? =
        listOf(response.text, response.result, response.transcript)
            .firstOrNull { !it.isNullOrBlank() }?.trim()

    suspend fun analyzeAccounting(
        ctx: Context,
        userInput: String,
        isMultiModeOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList()
    ): JSONObject? {
        val safeUserInput = shortenForModel(userInput, MAX_ACCOUNTING_INPUT_CHARS)
        Logger.d(ctx, AI_IO_LOG_TAG, "[记账] USER: ${safeUserInput.take(2000)}")
        Logger.d(ctx, "AIService", "Accounting analyze request: inputLen=${safeUserInput.length}, multiOverride=$isMultiModeOverride, fromChat=$isFromChat")
        val apiKey = Prefs.getAiKey(ctx)
        val enableThinking = enableThinkingForAccounting(ctx)
        val model = if (isFromChat) {
            AiModelSlots.resolveChatModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        } else {
            AiModelSlots.resolveTextModel(ctx)
        }
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val promptContext = buildAccountingPromptContext(ctx)

        val promptRules = loadActivePromptRules(ctx)
        val matchedPromptRules = if (Prefs.isAiPromptCorrectionEnabled(ctx)) {
            findMatchedPromptRules(safeUserInput, promptRules)
        } else {
            emptyList()
        }

        val systemPrompt = buildAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat
        )
        val aiName = if (isFromChat) Prefs.getAiChatName(ctx).trim().ifBlank { "小记" } else ""
        val userPrompt = buildAccountingUserPrompt(
            userInput = safeUserInput,
            promptContext = promptContext,
            matchedPromptRules = matchedPromptRules,
            assetFeatureEnabled = promptContext.assetFeatureEnabled,
            isFromChat = isFromChat,
            aiName = aiName
        )

        return try {
            val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
                buildMultiTurnChatRequest(
                    model = model,
                    temperature = 0.3,
                    systemPrompt = systemPrompt,
                    historyTurns = chatTurns,
                    userText = userPrompt,
                    enableThinking = enableThinking
                )
            } else {
                buildTextChatRequest(
                    model = model,
                    temperature = 0.3,
                    systemPrompt = systemPrompt,
                    userText = userPrompt,
                    enableThinking = enableThinking
                )
            }
            onProgress?.invoke("正在拆分金额和备注...")
            val content = requestAccountingContentStreamed(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                onProgress = onProgress,
                emitTextDelta = true,
                logReasoning = enableThinking,
                reasoningLogTag = ACCOUNTING_MULTI_LOG_TAG
            )
            Logger.d(ctx, "AIService", "Accounting response received: len=${content.length}")
            Logger.d(ctx, AI_IO_LOG_TAG, "[记账] AI: ${content.take(3000)}")

            val result = parseAnalyzeResult(content, isMultiMode = true)

            result?.let { root ->
                if (shouldTreatAsNoBillChatter(safeUserInput, root)) {
                    return JSONObject().apply {
                        put("no_bill", true)
                        put("reply", "这句更像是在聊天，不像记账内容，我就先不帮你生成账单啦。")
                    }
                }
                enforceExpenseForReceiptSummaries(root, safeUserInput)

                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled,
                    referenceText = safeUserInput,
                    assetCurrencyMap = promptContext.assetCurrencyMap
                )
                if (Prefs.isLocalRuleOverrideEnabled(ctx)) {
                    applyLocalRuleOverrideOnResult(root, safeUserInput, promptRules)
                    normalizeAccountingResult(
                        root = root,
                        expenseCats = promptContext.expenseCats,
                        incomeCats = promptContext.incomeCats,
                        assetNames = promptContext.assetNames,
                        assetFeatureEnabled = promptContext.assetFeatureEnabled,
                        referenceText = safeUserInput,
                        assetCurrencyMap = promptContext.assetCurrencyMap
                    )
                }
                Logger.d(ctx, AI_IO_LOG_TAG, "[记账] FINAL: ${root.toString().take(3000)}")
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "Accounting request failed: errType=${e.javaClass.simpleName}",
                "Accounting request failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    /**
     * 已废弃：不再支持直接音频记账。请先转写为文本，再调用 [analyzeAccounting]。
     */
    @Deprecated("Use transcription → analyzeAccounting instead", replaceWith = ReplaceWith("analyzeAccounting(ctx, userInput)"))
    suspend fun analyzeAccountingByAudio(
        ctx: Context,
        audioFile: File,
        isMultiModeOverride: Boolean? = null,
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false
    ): JSONObject? {
        require(audioFile.exists() && audioFile.length() > 44L) { "语音文件无效" }
        require(audioFile.length() <= MAX_AUDIO_INLINE_BYTES) { "语音文件过大，请缩短后再试" }
        Logger.d(ctx, AI_IO_LOG_TAG, "[语音记账] USER: [语音输入]")

        val apiKey = Prefs.getAiKey(ctx)
        val enableThinking = enableThinkingForAccounting(ctx)
        val model = if (isFromChat) {
            AiModelSlots.resolveChatModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        } else {
            AiModelSlots.resolveTextModel(ctx)
        }
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val promptContext = buildAccountingPromptContext(ctx)
        val promptRules = loadActivePromptRules(ctx)

        val systemPrompt = buildAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat
        )

        val audioBase64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
        }
        val audioFormat = audioFile.extension.lowercase(Locale.ROOT).ifBlank { "wav" }

        return try {
            val aiName = if (isFromChat) Prefs.getAiChatName(ctx).trim().ifBlank { "小记" } else ""
            val leadText = buildAccountingUserPrompt(
                userInput = "这是一段用户口述记账语音。请严格按系统提示词要求提取账单 JSON。",
                promptContext = promptContext,
                matchedPromptRules = emptyList(),
                assetFeatureEnabled = promptContext.assetFeatureEnabled,
                isFromChat = isFromChat,
                aiName = aiName
            )
            val requestJson = buildAudioChatRequest(
                model = model,
                temperature = 0.3,
                systemPrompt = systemPrompt,
                leadText = leadText,
                audioBase64 = audioBase64,
                audioFormat = audioFormat,
                enableThinking = enableThinking
            )
            onProgress?.invoke("正在听写并识别账单...")
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinking,
                reasoningLogTag = ACCOUNTING_AUDIO_MULTI_LOG_TAG,
                onProgressChars = { currentLen -> onProgress?.invoke("正在整理语音结果...（已接收 $currentLen 字）") }
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("语音账单流式回复未完整结束")
            }
            val content = streamed.content
            Logger.d(ctx, "AIService", "AI audio response received, len=${content.length}")
            Logger.d(ctx, AI_IO_LOG_TAG, "[语音记账] AI: ${content.take(3000)}")
            val result = parseAnalyzeResult(content, isMultiMode = true)

            result?.let { root ->
                if (shouldTreatAsNoBillChatter("[语音输入]", root)) {
                    return JSONObject().apply {
                        put("no_bill", true)
                        put("reply", "这段语音更像是在聊天，不像需要落账的内容，我先按聊天回复你。")
                    }
                }
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled,
                    assetCurrencyMap = promptContext.assetCurrencyMap
                )
                if (Prefs.isLocalRuleOverrideEnabled(ctx)) {
                    applyLocalRuleOverrideOnResult(root, "[语音输入]", promptRules)
                    normalizeAccountingResult(
                        root = root,
                        expenseCats = promptContext.expenseCats,
                        incomeCats = promptContext.incomeCats,
                        assetNames = promptContext.assetNames,
                        assetFeatureEnabled = promptContext.assetFeatureEnabled,
                        assetCurrencyMap = promptContext.assetCurrencyMap
                    )
                }
                Logger.d(ctx, AI_IO_LOG_TAG, "[语音记账] FINAL: ${root.toString().take(3000)}")
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "Accounting audio request failed: errType=${e.javaClass.simpleName}",
                "Accounting audio request failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun analyzeReceiptByImage(
        ctx: Context,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        supplementText: String = ""
    ): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImage: multimodal mode")
        Logger.d(ctx, AI_IO_LOG_TAG, "[票据图片] USER: [图片输入]")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT +
            AIPrompts.buildReceiptVisionPaymentMethodRule(
                promptContext.assetFeatureEnabled,
                promptContext.assetNames
            )
        val dataUrl = "data:$mimeType;base64,$imageBase64"
        val userText = buildString {
            append("请分析这张用于记账的图片，提取时间、对象/商品、支付方式（仅在资产功能开启时）、金额，转为自然语言清单。每行一条交易，不要输出其他内容。")
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }

        val requestJson = buildVisionChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            dataUrl = dataUrl,
            userText = userText,
            enableThinking = enableThinkingForVision(ctx)
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = RECEIPT_VISION_LOG_TAG
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("图片识别流式回复未完整结束")
            }
            val content = ReceiptImageInputHelper.normalizeVisionSummary(streamed.content)
            Logger.d(ctx, "AIService", "Receipt multimodal response received: contentLen=${content.length}")
            Logger.d(ctx, AI_IO_LOG_TAG, "[票据图片] AI: ${content.take(3000)}")
            content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeReceiptByImage failed: errType=${e.javaClass.simpleName}",
                "Receipt image failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun probeVisionInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() } ?: AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) return false
        return runCatching {
            Logger.d(ctx, "AIService", "Probing vision input support. model=$model")
            val requestJson = buildVisionChatRequest(
                model = model,
                temperature = 0.1,
                dataUrl = "data:image/png;base64,${buildProbeImageBase64(ctx)}",
                userText = "Reply with OK only.",
                enableThinking = enableThinkingForVision(ctx)
            )
            val response = getApi(ctx).chatRaw(
                "Bearer $apiKey",
                adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
            )
            Logger.d(ctx, "AIService", "Vision input support probe succeeded. model=$model")
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse {
            Logger.d(ctx, "AIService", "Vision input support probe failed. model=$model, errType=${it.javaClass.simpleName}")
            false
        }
    }

    suspend fun analyzeScreenAccountingByImage(
        ctx: Context,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        isMultiModeOverride: Boolean? = null,
        sourceKind: String = "screen_capture",
        supplementText: String = "",
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList()
    ): JSONObject? {
        Logger.d(ctx, "AIService", "analyzeScreenAccountingByImage: multimodal accounting mode source=$sourceKind fromChat=$isFromChat")
        Logger.d(ctx, AI_IO_LOG_TAG, "[截图记账] USER: [屏幕截图]")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) throw IllegalArgumentException("请先在智能配置中选择视觉重试模型")

        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildScreenAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat
        )

        val dataUrl = "data:$mimeType;base64,$imageBase64"
        val taskInstruction = buildString {
            if (isFromChat) {
                append("这是一张用于记账识别的图片。请先判断画面类型，只提取真实交易信息，返回记账账单 JSON。")
                append("\n成功提取交易：{\"bills\":[...], \"assistant_reply\":\"一句自然的中文回复\"}；无交易/纯闲聊：{\"no_bill\":true, \"reply\":\"...\"}。")
                append("\n不要输出 requires_review、natural_summary、risk_flags、source_kind 等字段。不要输出 Markdown、代码块或额外文字。")
            } else {
                append("这是一张用于记账识别的图片。请先判断画面类型，只提取真实交易信息，返回待核对的账单草稿 JSON。")
                append("\n统一使用多账单格式，即使只有一条账单，也返回：{\"source_kind\":\"image\",\"requires_review\":true,\"confidence\":0.0,\"natural_summary\":\"...\",\"risk_flags\":[],\"bills\":[...]}。")
                append("\nnatural_summary 用中文概括用户需要核对的内容；risk_flags 标记风险项（如 missing_asset、unclear_item）。没有风险时返回空数组。")
            }
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }
        // 加载本地纠错规则（用 supplementText 匹配，无补充说明时跳过）
        val matchedPromptRules = if (supplementText.isNotBlank() && Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val promptRules = loadActivePromptRules(ctx)
            findMatchedPromptRules(supplementText, promptRules)
        } else {
            emptyList()
        }
        val userText = buildScreenAccountingUserText(
            promptContext = promptContext,
            taskInstruction = taskInstruction,
            matchedPromptRules = matchedPromptRules
        )
        val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
            buildMultiTurnVisionChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                historyTurns = chatTurns,
                dataUrl = dataUrl,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        } else {
            buildVisionChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                dataUrl = dataUrl,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        }

        return try {
            onProgress?.invoke("正在识别图片中的交易...")
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = SCREEN_ACCOUNTING_LOG_TAG,
                onContentDelta = if (onProgress == null) null else { delta ->
                    if (delta.isNotBlank()) onProgress("AI_STREAM_TEXT::$delta")
                },
                onProgressChars = null
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("截图记账流式回复未完整结束")
            }
            val content = streamed.content
            Logger.d(ctx, "AIService", "Screen accounting multimodal response: $content")
            Logger.d(ctx, AI_IO_LOG_TAG, "[截图记账] AI: ${content.take(3000)}")
            val result = parseAnalyzeResult(content, isMultiMode = true)

            result?.let { root ->
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled,
                    referenceText = supplementText,
                    assetCurrencyMap = promptContext.assetCurrencyMap
                )
                if (!isFromChat) {
                    markVisualAccountingReviewDraft(
                        root = root,
                        sourceKind = sourceKind,
                        naturalSummary = supplementText.trim(),
                        includePaymentMethod = promptContext.assetFeatureEnabled
                    )
                }
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeScreenAccountingByImage failed: errType=${e.javaClass.simpleName}",
                "Screen accounting image failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    /**
     * 多图直出记账：一次性把所有图片发给多模态，直接返回 JSON 结果。
     */
    suspend fun analyzeScreenAccountingByImages(
        ctx: Context,
        images: List<Pair<String, String>>,
        isMultiModeOverride: Boolean? = null,
        sourceKind: String = "receipt_image",
        supplementText: String = "",
        onProgress: ((String) -> Unit)? = null,
        isFromChat: Boolean = false,
        chatTurns: List<ChatTurn> = emptyList()
    ): JSONObject? {
        Logger.d(ctx, "AIService", "analyzeScreenAccountingByImages: multi-image multimodal accounting, count=${images.size} fromChat=$isFromChat")
        if (images.isEmpty()) throw IllegalArgumentException("图片列表不能为空")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx)
        if (model.isBlank()) throw IllegalArgumentException("请先在智能配置中选择视觉重试模型")

        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = buildScreenAccountingSystemPrompt(
            ctx = ctx,
            promptContext = promptContext,
            isFromChat = isFromChat
        )

        val dataUrls = images.map { (base64, mime) -> "data:$mime;base64,$base64" }
        val taskInstruction = buildString {
            if (isFromChat) {
                append("这是${images.size}张用于记账识别的图片，请逐一分析每张图片，只提取真实交易信息，返回记账账单 JSON。")
                append("\n成功提取交易：{\"bills\":[...], \"assistant_reply\":\"一句自然的中文回复\"}；无交易/纯闲聊：{\"no_bill\":true, \"reply\":\"...\"}。")
                append("\n不要输出 requires_review、natural_summary、risk_flags、source_kind 等字段。不要输出 Markdown、代码块或额外文字。")
            } else {
                append("这是${images.size}张用于记账识别的图片，请逐一分析每张图片，只提取真实交易信息，返回待核对的账单草稿 JSON。")
                append("\n统一使用多账单格式，返回：{\"source_kind\":\"image\",\"requires_review\":true,\"confidence\":0.0,\"natural_summary\":\"...\",\"risk_flags\":[],\"bills\":[...]}。")
                append("\nnatural_summary 用中文概括用户需要核对的内容；risk_flags 标记风险项。没有风险时返回空数组。")
            }
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }
        val matchedPromptRules = if (supplementText.isNotBlank() && Prefs.isAiPromptCorrectionEnabled(ctx)) {
            val promptRules = loadActivePromptRules(ctx)
            findMatchedPromptRules(supplementText, promptRules)
        } else {
            emptyList()
        }
        val userText = buildScreenAccountingUserText(
            promptContext = promptContext,
            taskInstruction = taskInstruction,
            matchedPromptRules = matchedPromptRules
        )
        val requestJson = if (isFromChat && chatTurns.isNotEmpty()) {
            buildMultiTurnMultiImageVisionChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                historyTurns = chatTurns,
                dataUrls = dataUrls,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        } else {
            buildMultiImageVisionChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = systemPrompt,
                dataUrls = dataUrls,
                userText = userText,
                enableThinking = enableThinkingForVision(ctx)
            )
        }

        return try {
            onProgress?.invoke("正在识别图片中的交易...")
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = SCREEN_ACCOUNTING_LOG_TAG,
                onContentDelta = if (onProgress == null) null else { delta ->
                    if (delta.isNotBlank()) onProgress("AI_STREAM_TEXT::$delta")
                },
                onProgressChars = null
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("多图记账流式回复未完整结束")
            }
            val content = streamed.content
            Logger.d(ctx, "AIService", "Multi-image accounting response: $content")
            val result = parseAnalyzeResult(content, isMultiMode = true)

            result?.let { root ->
                normalizeAccountingResult(
                    root = root,
                    expenseCats = promptContext.expenseCats,
                    incomeCats = promptContext.incomeCats,
                    assetNames = promptContext.assetNames,
                    assetFeatureEnabled = promptContext.assetFeatureEnabled,
                    referenceText = supplementText,
                    assetCurrencyMap = promptContext.assetCurrencyMap
                )
                if (!isFromChat) {
                    markVisualAccountingReviewDraft(
                        root = root,
                        sourceKind = sourceKind,
                        naturalSummary = supplementText.trim(),
                        includePaymentMethod = promptContext.assetFeatureEnabled
                    )
                }
            }
            result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeScreenAccountingByImages failed: errType=${e.javaClass.simpleName}",
                "Multi-image accounting failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    /**
     * 多图自然语言摘要：一次性把所有图片发给多模态，返回文字摘要。
     */
    suspend fun analyzeReceiptByImages(
        ctx: Context,
        images: List<Pair<String, String>>,
        supplementText: String = ""
    ): String {
        Logger.d(ctx, "AIService", "analyzeReceiptByImages: multi-image multimodal OCR, count=${images.size}")
        if (images.isEmpty()) throw IllegalArgumentException("图片列表不能为空")
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")

        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        val promptContext = buildAccountingPromptContext(ctx)
        val systemPrompt = AIPrompts.RECEIPT_VISION_RETRY_PROMPT_DEFAULT +
            AIPrompts.buildReceiptVisionPaymentMethodRule(
                promptContext.assetFeatureEnabled,
                promptContext.assetNames
            )
        val dataUrls = images.map { (base64, mime) -> "data:$mime;base64,$base64" }
        val userText = buildString {
            append("请分析这${images.size}张用于记账的图片，提取时间、对象/商品、支付方式（仅在资产功能开启时）、金额，转为自然语言清单。每行一条交易，不要输出其他内容。")
            val supplement = supplementText.trim()
            if (supplement.isNotBlank()) {
                append("\n\n用户补充说明（优先参考）：\n")
                append(supplement)
            }
        }

        val requestJson = buildMultiImageVisionChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            dataUrls = dataUrls,
            userText = userText,
            enableThinking = enableThinkingForVision(ctx)
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = enableThinkingForVision(ctx),
                reasoningLogTag = RECEIPT_VISION_LOG_TAG
            )
            if (!streamed.completed) {
                throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("多图识别流式回复未完整结束")
            }
            val content = ReceiptImageInputHelper.normalizeVisionSummary(streamed.content)
            Logger.d(ctx, "AIService", "Multi-image receipt response received: contentLen=${content.length}")
            content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.dPriv(
                ctx,
                "AIService",
                "analyzeReceiptByImages failed: errType=${e.javaClass.simpleName}",
                "Multi-image receipt failure detail=${detailedHttpError(e)}"
            )
            throw e
        }
    }

    suspend fun fetchModels(ctx: Context, apiKey: String): List<String> =
        fetchModelsWithDetails(Prefs.getAiUrl(ctx), apiKey)

    suspend fun fetchModelsForProvider(
        preset: AiProviderPreset,
        apiKey: String
    ): List<String> {
        return runCatching {
            fetchModelsWithDetails(preset.baseUrl, apiKey)
                .takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("模型列表为空")
        }.getOrElse { modelListError ->
            val detail = modelListError.message.orEmpty()
            if (detail.contains("401") || detail.contains("403")) throw modelListError
            verifyProviderConnection(preset, apiKey)
            listOf(
                preset.defaultTextModel,
                preset.defaultVisionModel,
                preset.defaultSpeechModel
            ).filter { it.isNotBlank() }.distinct()
        }
    }

    private fun verifyProviderConnection(
        preset: AiProviderPreset,
        apiKey: String
    ) {
        val requestJson = buildTextChatRequest(
            model = preset.defaultTextModel,
            temperature = 0.1,
            userText = "Reply with OK only.",
            enableThinking = false
        ).let {
            adaptChatRequestForProvider(preset.id, it)
        }
        val request = Request.Builder()
            .url(normalizeBaseUrl(preset.baseUrl) + "v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaTypeOrNull()))
            .build()
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${response.message}")
                }
            }
    }

    suspend fun fetchModelsWithDetails(url: String, apiKey: String): List<String> {
        var baseUrl = url
        if (baseUrl.isEmpty()) baseUrl = "https://api.siliconflow.cn/"
        if (!baseUrl.endsWith("/")) baseUrl += "/"
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        val rawRequest = Request.Builder()
            .url(baseUrl + "v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        val rawResponse = client.newCall(rawRequest).execute()
        if (!rawResponse.isSuccessful) {
            throw IllegalStateException("HTTP ${rawResponse.code}: ${rawResponse.message}")
        }

        val bodyText = rawResponse.body?.string().orEmpty()
        val parsed = runCatching {
            val root = JSONObject(bodyText)
            val data = root.optJSONArray("data") ?: JSONArray()
            buildList {
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id").trim()
                    if (id.isNotEmpty()) add(id)
                }
            }
        }.getOrElse { throw IllegalStateException("模型列表解析失败", it) }

        return parsed.sorted()
    }

    /**
     * 轻量意图分类：判断用户输入是记账、闲聊还是查询。
     * 仅用于聊天入口，悬浮窗记账不走此函数。
     * 模型选择：优先用视觉模型（多模态平台），没有则 fallback 到文本模型。
     * @return "BOOKKEEPING" / "GENERAL_CHAT" / "QUERY" / "UNKNOWN"
     */
    suspend fun classifyIntent(ctx: Context, userText: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return "BOOKKEEPING"
        val model = AiModelSlots.resolveVisionModel(ctx).ifBlank { AiModelSlots.resolveTextModel(ctx) }
        if (model.isBlank()) return "BOOKKEEPING"

        val systemPrompt = AIPrompts.buildIntentRouterPrompt(enableQuery = false)
        val requestJson = buildTextChatRequest(
            model = model,
            temperature = 0.1,
            systemPrompt = systemPrompt,
            userText = userText,
            enableThinking = false
        )

        return try {
            val content = requestAccountingContentStreamed(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                onProgress = null,
                emitTextDelta = false,
                logReasoning = false,
                reasoningLogTag = "IntentRouter"
            )
            val cleaned = cleanJsonString(content)
            val jsonText = extractFirstJsonObjectText(cleaned)
            val json = runCatching { jsonText?.let { org.json.JSONObject(it) } }.getOrNull()
            val intent = json?.optString("intent_type", "BOOKKEEPING") ?: "BOOKKEEPING"
            Logger.d(ctx, "AIService", "classifyIntent: input=${userText.take(50)}, result=$intent")
            when (intent) {
                "BOOKKEEPING", "GENERAL_CHAT" -> intent
                // QUERY 功能暂未实现，MODIFY_BILL 已移除，均 fallback 到记账
                "QUERY", "UNKNOWN", "MODIFY_BILL" -> "BOOKKEEPING"
                else -> "BOOKKEEPING"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "classifyIntent failed: ${e.message}, fallback to BOOKKEEPING")
            "BOOKKEEPING"
        }
    }

    suspend fun simpleChat(ctx: Context, prompt: String): String {
        val apiKey = Prefs.getAiKey(ctx)
        val model = AiModelSlots.resolveTextModel(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        val request = ChatRequest(
            model = model,
            messages = listOf(MessageUnion.Text(Message("user", prompt))),
            response_format = null
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = buildRawRequest(request),
            logReasoning = true,
            reasoningLogTag = SIMPLE_CHAT_LOG_TAG
        )
        if (!streamed.completed) {
            throw streamed.parseError ?: streamed.transportError ?: IllegalStateException("简单聊天流式回复未完整结束")
        }
        return streamed.content
    }

    suspend fun generateAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatTurns: List<ChatTurn> = emptyList()
    ): String {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[助手] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val systemPrompt = buildAssistantSystemPrompt(
            ctx = ctx,
            defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
        )
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint
        )

        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            logReasoning = false,
            reasoningLogTag = ACCOUNTING_ASSISTANT_LOG_TAG
        )
        val reply = if (streamed.completed) streamed.content.trim() else ""
        if (reply.isNotBlank()) Logger.d(ctx, AI_IO_LOG_TAG, "[助手] AI: ${reply.take(3000)}")
        return reply
    }

    suspend fun generateGeneralChatReply(
        ctx: Context,
        userInput: String,
        chatTurns: List<ChatTurn> = emptyList(),
        replyGuideHint: String = "",
        onDelta: ((String) -> Unit)? = null
    ): StreamResult {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[聊天] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val systemPrompt = buildAssistantSystemPrompt(
            ctx = ctx,
            defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
        )
        val safeReplyGuideHint = shortenForModel(replyGuideHint, 400, preserveTail = false)
        val userPrompt = buildString {
            append(safeUserInput)
            if (safeReplyGuideHint.isNotBlank()) {
                append("\n\n补充参考（仅供你组织回复语气，不要逐字复述）：")
                append(safeReplyGuideHint)
            }
        }
        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )
        val streamed = requestChatContentStreamedWithReasoning(
            ctx = ctx,
            apiKey = apiKey,
            requestJson = requestJson,
            logReasoning = false,
            reasoningLogTag = GENERAL_CHAT_LOG_TAG,
            onContentDelta = onDelta
        )
        val result = streamed.copy(content = streamed.content.trim())
        if (result.completed && result.content.isNotBlank()) {
            Logger.d(ctx, AI_IO_LOG_TAG, "[聊天] AI: ${result.content.take(3000)}")
        }
        return result
    }

    suspend fun streamAccountingAssistantReply(
        ctx: Context,
        userInput: String,
        billSummary: String = "",
        extractorReplyHint: String = "",
        chatTurns: List<ChatTurn> = emptyList(),
        onDelta: (String) -> Unit
    ): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isEmpty()) throw IllegalArgumentException("请先在设置中配置 API Key")
        Logger.d(ctx, AI_IO_LOG_TAG, "[助手流] USER: ${userInput.take(2000)}")

        val model = AiModelSlots.resolveChatModel(ctx)
        val safeUserInput = shortenForModel(userInput, MAX_ASSISTANT_INPUT_CHARS)
        val safeBillSummary = shortenForModel(billSummary, MAX_ASSISTANT_SUMMARY_CHARS)
        val safeReplyHint = shortenForModel(extractorReplyHint, MAX_ASSISTANT_INPUT_CHARS, preserveTail = false)
        val systemPrompt = buildAssistantSystemPrompt(
            ctx = ctx,
            defaultCustomReplyStyleGuide = DEFAULT_CUSTOM_REPLY_STYLE_GUIDE
        )
        val userPrompt = buildAccountingAssistantUserPrompt(
            userInput = safeUserInput,
            billSummary = safeBillSummary,
            extractorReplyHint = safeReplyHint
        )

        val requestJson = buildMultiTurnChatRequest(
            model = model,
            temperature = 0.7,
            systemPrompt = systemPrompt,
            historyTurns = chatTurns,
            userText = userPrompt,
            stream = true,
            enableThinking = false
        )

        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = false,
                reasoningLogTag = ACCOUNTING_ASSISTANT_LOG_TAG,
                onContentDelta = onDelta
            )
            streamed.completed
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    suspend fun probeDirectAudioInputSupport(ctx: Context, modelName: String? = null): Boolean {
        val apiKey = Prefs.getAiKey(ctx)
        if (apiKey.isBlank()) return false
        val model = modelName?.takeIf { it.isNotBlank() }
            ?: AiModelSlots.resolveChatModel(ctx)
        return runCatching {
            val requestJson = buildAudioChatRequest(
                model = model,
                temperature = 0.1,
                systemPrompt = null,
                leadText = "Reply with OK only.",
                audioBase64 = buildProbeAudioBase64(),
                audioFormat = "wav",
                enableThinking = false
            )
            val response = getApi(ctx).chatRaw(
                "Bearer $apiKey",
                adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
            )
            response.choices.firstOrNull()?.message?.content != null
        }.getOrElse { false }
    }

    fun extractAccountingAssistantReply(root: JSONObject): String {
        return root.optString("assistant_reply", "").trim()
            .ifBlank { root.optString("reply", "").trim() }
    }

    fun markVisualAccountingReviewDraft(
        root: JSONObject,
        sourceKind: String,
        naturalSummary: String = "",
        includePaymentMethod: Boolean = true
    ): JSONObject {
        if (root.optBoolean("no_bill", false)) return root
        root.put("source_kind", sourceKind)
        root.put("requires_review", true)
        val summary = naturalSummary.trim().ifBlank {
            root.optString("natural_summary", "").trim().ifBlank {
                buildVisualAccountingNaturalSummary(root, includePaymentMethod)
            }
        }
        if (summary.isNotBlank()) root.put("natural_summary", summary)

        val riskFlags = root.optJSONArray("risk_flags") ?: JSONArray()
        collectVisualAccountingRiskFlags(root, includePaymentMethod).forEach { flag ->
            if (!jsonArrayContains(riskFlags, flag)) riskFlags.put(flag)
        }
        root.put("risk_flags", riskFlags)

        if (root.has("bills")) {
            val bills = root.getJSONArray("bills")
            for (i in 0 until bills.length()) {
                val bill = bills.optJSONObject(i) ?: continue
                bill.put("source_kind", sourceKind)
                bill.put("requires_review", true)
                if (summary.isNotBlank()) bill.put("natural_summary", summary)
            }
        }
        return root
    }

    private fun buildVisualAccountingNaturalSummary(root: JSONObject, includePaymentMethod: Boolean): String {
        val bills = root.optJSONArray("bills") ?: JSONArray().put(root)
        if (bills.length() == 0) return ""
        val lines = mutableListOf<String>()
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            val type = bill.optInt("type", 0)
            val subType = bill.optInt("subType", 0)
            val amount = bill.optDouble("amount", 0.0)
            val currency = bill.optString("currency", "CNY").ifBlank { "CNY" }
            val remark = bill.optString("remarks", bill.optString("remark", "")).ifBlank { "待确认" }
            val time = bill.optString("time", "").ifBlank { "待确认" }
            val asset = bill.optString("asset_name", "").ifBlank { "待确认" }
            val toAsset = bill.optString("to_asset_name", "")
            val category = bill.optString("category_name", "").ifBlank { "待确认" }
            val accountText = if (toAsset.isNotBlank()) "$asset -> $toAsset" else asset
            val paymentText = if (includePaymentMethod) "，账户 $accountText" else ""
            lines += "${i + 1}. ${visualTypeLabel(type, subType)} ${formatVisualAmount(amount, currency)}，$remark，时间 $time$paymentText，分类 $category"
        }
        return lines.joinToString("\n")
    }

    private fun collectVisualAccountingRiskFlags(root: JSONObject, includePaymentMethod: Boolean): Set<String> {
        val flags = linkedSetOf<String>()
        val bills = root.optJSONArray("bills") ?: JSONArray().put(root)
        for (i in 0 until bills.length()) {
            val bill = bills.optJSONObject(i) ?: continue
            if (bill.optDouble("amount", 0.0) <= 0.0) flags += "unclear_amount"
            if (includePaymentMethod && bill.optString("asset_name", "").isBlank()) flags += "missing_asset"
            if (bill.optString("remarks", bill.optString("remark", "")).isBlank()) flags += "unclear_item"
            if (bill.optString("category_name", "").isBlank()) flags += "unclear_category"
            if (bill.optString("time", "").isBlank()) flags += "missing_time"
        }
        return flags
    }

    private fun visualTypeLabel(type: Int, subType: Int): String = when {
        type == Bill.TYPE_INCOME -> "收入"
        type == Bill.TYPE_TRANSFER && subType == Bill.SUBTYPE_REPAYMENT -> "还款"
        type == Bill.TYPE_TRANSFER -> "转账"
        else -> "支出"
    }

    private fun formatVisualAmount(amount: Double, currency: String): String {
        val formatted = String.format(Locale.getDefault(), "%.2f", amount)
        val prefix = if (currency.equals("CNY", ignoreCase = true)) "¥" else "$currency "
        return "$prefix$formatted"
    }

    private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
        for (i in 0 until array.length()) {
            if (array.optString(i) == value) return true
        }
        return false
    }

    /**
     * 本地规则强覆盖（最终裁决）。
     * 直接在已解析的 JSONObject 上原地修改，保证规则的 type / category_name /
     * asset_name / to_asset_name 不会再被后续步骤覆盖。
     */
    private fun applyLocalRuleOverrideOnResult(root: JSONObject, userInput: String, allRules: List<com.taostudio.tapaccounting.data.local.entity.AiRule>) {
        if (allRules.isEmpty()) return

        fun applyRulesToBill(bill: JSONObject) {
            // 优先取 remarks 作为该条账单的语义文本
            val remarks = bill.optString("remarks", "").trim()
                .ifEmpty { bill.optString("remark", "").trim() }
            val categoryName = bill.optString("category_name", "")

            // 多账单场景下：仅用 remarks+categoryName 匹配，避免 A 账单规则命中 B/C。
            // 单账单或 AI 未返回 remarks 时才兜底 userInput。
            val matchText = if (remarks.isNotBlank()) "$remarks $categoryName" else userInput

            allRules.filter { rule ->
                rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                    .all { matchText.contains(it, ignoreCase = true) }
            }.forEach { rule ->
                rule.targetType?.takeIf { it in 0..3 }?.let { t ->
                    bill.put("type", if (t == 3) 2 else t)
                    if (t == 3) {
                        bill.put("subType", 1)
                        bill.put("category_name", "还款")
                    }
                }
                if (!rule.targetCategory.isNullOrEmpty()) bill.put("category_name", rule.targetCategory)
                if (!rule.targetAccount1.isNullOrEmpty()) bill.put("asset_name", rule.targetAccount1)
                if (!rule.targetAccount2.isNullOrEmpty()) bill.put("to_asset_name", rule.targetAccount2)
            }
        }

        try {
            if (root.has("bills")) {
                val billsArr = root.getJSONArray("bills")
                for (i in 0 until billsArr.length()) {
                    applyRulesToBill(billsArr.getJSONObject(i))
                }
            } else if (root.has("amount")) {
                applyRulesToBill(root)
            }
            android.util.Log.d("AIService", "Local rule override applied")
        } catch (e: Exception) {
            android.util.Log.d("AIService", "applyLocalRuleOverrideOnResult error: ${e.javaClass.simpleName}")
        }
    }

    private fun parseAnalyzeResult(finalContent: String, isMultiMode: Boolean): JSONObject? {
        val cleaned = cleanJsonString(finalContent)
        val json = try {
            if (cleaned.startsWith("[")) {
                val array = org.json.JSONArray(cleaned)
                JSONObject().apply { put("bills", array) }
            } else {
                JSONObject(cleaned)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("AI响应非JSON: ${cleaned.take(50)}...")
        }
        if (json.optBoolean("no_bill", false)) {
            return json
        }
        return when {
            json.has("bills")  -> json
            json.has("amount") -> JSONObject().apply { put("bills", JSONArray().put(json)) }
            else -> throw IllegalArgumentException("AI 返回的数据缺少关键字段 'bills' 或 'amount'")
        }
    }

    private fun enforceExpenseForReceiptSummaries(root: JSONObject, userInput: String) {
        if (!looksLikeReceiptExpenseSummary(userInput)) return
        if (root.has("bills")) {
            val billsArr = root.getJSONArray("bills")
            for (i in 0 until billsArr.length()) {
                val bill = billsArr.getJSONObject(i)
                if (bill.optInt("type", 0) in 0..1) bill.put("type", 0)
            }
        } else if (root.has("amount")) {
            if (root.optInt("type", 0) in 0..1) root.put("type", 0)
        }
    }

    private fun looksLikeReceiptExpenseSummary(text: String): Boolean {
        val normalized = text.lowercase()
        val expenseSignals = listOf("购买", "花了", "总计花费", "小票", "receipt", "discount", "visa",
            "mastercard", "刷卡", "支付", "超市", "商店", "biedronka", "pln", "eur")
        val incomeSignals = listOf("工资", "收入", "收款", "收到", "到账", "退款到账", "报销到账", "转入", "打款给我")
        return expenseSignals.count { normalized.contains(it) } >= 2 && incomeSignals.none { normalized.contains(it) }
    }

    private fun shouldTreatAsNoBillChatter(userInput: String, root: JSONObject): Boolean {
        val normalizedInput = userInput.trim().lowercase(Locale.ROOT)
        if (normalizedInput.isBlank()) return true
        if (looksLikeReceiptExpenseSummary(normalizedInput)) return false

        val strongFinancialSignals = listOf(
            "花了", "消费", "支出", "购买", "付款", "支付", "转账", "还款", "报销", "退款",
            "收入", "收款", "到账", "记账", "记一笔", "入账", "提现", "充值", "扣款", "账单",
            "工资", "报销到账"
        )
        val weakFinancialSignals = listOf("花", "买", "赚")
        val currencySignals = listOf("€", "$", "¥", "￥", "元", "块", "毛", "角", "pln", "cny", "usd", "eur", "rmb")
        val hasStrongFinancialSignal = strongFinancialSignals.any { normalizedInput.contains(it) }
        val weakFinancialSignalHits = weakFinancialSignals.count { normalizedInput.contains(it) }
        val hasNumber = Regex("\\d").containsMatchIn(normalizedInput)
        val hasCurrencySignal = currencySignals.any { normalizedInput.contains(it) }
        val hasAmountPattern = Regex("""\d+(?:[.,]\d{1,2})?\s*(元|块|人民币|rmb|cny|usd|eur|pln|¥|￥|\$|€)""")
            .containsMatchIn(normalizedInput)
        val hasFinancialSignal =
            hasStrongFinancialSignal ||
                hasAmountPattern ||
                (hasNumber && hasCurrencySignal) ||
                weakFinancialSignalHits >= 2 ||
                (hasNumber && weakFinancialSignalHits >= 1)
        if (hasFinancialSignal) return false

        val chatterSignals = listOf(
            "你好", "您好", "哈喽", "嗨", "hello", "hi", "早上好", "晚上好", "午安",
            "在吗", "聊天", "聊聊天", "陪我", "打个招呼", "介绍", "介绍下", "自我介绍",
            "说说话", "陪我说话", "你是谁", "你叫什么", "今天过得怎么样", "开心吗"
        )
        val inputLooksLikeChatter = chatterSignals.any { normalizedInput.contains(it) } ||
            normalizedInput.endsWith("吗") || normalizedInput.endsWith("呢") || normalizedInput.endsWith("?") || normalizedInput.endsWith("？")

        val billCandidates = when {
            root.has("bills") -> {
                val bills = root.optJSONArray("bills") ?: JSONArray()
                List(bills.length()) { index -> bills.optJSONObject(index) }.filterNotNull()
            }
            root.has("amount") -> listOf(root)
            else -> emptyList()
        }
        if (billCandidates.isEmpty()) {
            // 当模型返回 {"bills":[]} 这种空壳结果时，若输入本身不像记账，则按闲聊处理。
            val emptyBillArray = root.has("bills") && (root.optJSONArray("bills")?.length() ?: 0) == 0
            if (emptyBillArray) {
                return inputLooksLikeChatter || !hasFinancialSignal
            }
            return false
        }

        val allCandidatesLookEmpty = billCandidates.all { bill ->
            val amount = bill.optDouble("amount", 0.0)
            val category = bill.optString("category_name", "").trim()
            val asset = bill.optString("asset_name", "").trim()
            val toAsset = bill.optString("to_asset_name", "").trim()
            amount <= 0.0 &&
                asset.isBlank() &&
                toAsset.isBlank() &&
                category in setOf("", "其他", "其它", "其他/::/其他")
        }

        if (!allCandidatesLookEmpty) return false

        // 兜底：输入本身完全不像记账，同时 AI 只产出了 0 元/空资产/其他分类 的空壳账单，直接视为 no_bill。
        return inputLooksLikeChatter || !hasFinancialSignal
    }

    private suspend fun loadActivePromptRules(ctx: Context): List<DbAiRule> = withContext(Dispatchers.IO) {
        val dbRules = AppDatabase.getDatabase(ctx).aiRuleDao().getEnabledRulesList()
        val legacyRules = Prefs.getAiRules(ctx).filter { it.isEnabled }.mapIndexed { index, rule ->
            DbAiRule(
                id = -(index + 1),
                keyword = rule.keyword,
                targetType = rule.targetType,
                targetCategory = rule.targetCategory,
                targetAccount1 = rule.targetAccount1,
                targetAccount2 = rule.targetAccount2,
                isEnabled = rule.isEnabled
            )
        }
        (dbRules + legacyRules)
            .distinctBy {
                listOf(
                    it.keyword.trim(),
                    it.targetType?.toString().orEmpty(),
                    it.targetCategory.orEmpty(),
                    it.targetAccount1.orEmpty(),
                    it.targetAccount2.orEmpty(),
                    it.isEnabled.toString()
                ).joinToString("|")
            }
    }

    private fun findMatchedPromptRules(text: String, allRules: List<DbAiRule>): List<DbAiRule> =
        allRules.filter { rule ->
            rule.isEnabled && rule.keyword.split("\\s+".toRegex()).filter { it.isNotEmpty() }
                .all { text.contains(it, ignoreCase = true) }
        }

    data class StreamResult(
        val content: String,
        val reasoning: String,
        val completed: Boolean,
        val sawDone: Boolean,
        val parseError: Exception? = null,
        val transportError: Exception? = null
    )

    private suspend fun requestChatContentStreamedWithReasoning(
        ctx: Context,
        apiKey: String,
        requestJson: com.google.gson.JsonObject,
        logReasoning: Boolean = false,
        reasoningLogTag: String = "AIService",
        onContentDelta: ((String) -> Unit)? = null,
        onProgressChars: ((Int) -> Unit)? = null
    ): StreamResult {
        val streamReq = requestJson.deepCopy()
            .apply { addProperty("stream", true) }
            .let { adaptChatRequestForProvider(Prefs.getAiProvider(ctx), it) }
        val contentBuilder = StringBuilder()
        val reasoningBuilder = StringBuilder()
        var lastProgressLen = 0
        var sawDone = false
        var parseError: Exception? = null
        var transportError: Exception? = null
        try {
            val body = getApi(ctx).chatStreamRaw("Bearer $apiKey", streamReq)
            body.use { responseBody ->
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
                        val root = JSONObject(payload)
                        root.optJSONObject("usage")?.let { usage ->
                            logPromptCacheUsage(ctx, reasoningLogTag, usage)
                        }
                        val deltaObj = root.optJSONArray("choices")
                            ?.optJSONObject(0)
                            ?.optJSONObject("delta")
                        val contentDelta = jsonOptStringOrEmpty(deltaObj, "content")
                        val reasoningDelta = extractReasoningDelta(deltaObj)
                        if (reasoningDelta.isNotEmpty()) {
                            reasoningBuilder.append(reasoningDelta)
                            if (logReasoning) {
                                Logger.d(ctx, reasoningLogTag, "reasoning delta received: len=${reasoningDelta.length}")
                            }
                        }
                        if (contentDelta.isNotEmpty()) {
                            contentBuilder.append(contentDelta)
                            onContentDelta?.invoke(contentDelta)
                            val currentLen = contentBuilder.length
                            if (onProgressChars != null && currentLen - lastProgressLen >= 120) {
                                lastProgressLen = currentLen
                                onProgressChars.invoke(currentLen)
                            }
                        }
                    } catch (e: Exception) {
                        parseError = e
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            transportError = e
        }
        if (logReasoning && reasoningBuilder.isNotBlank()) {
            Logger.d(ctx, reasoningLogTag, "reasoning full received: len=${reasoningBuilder.length}")
        }
        return StreamResult(
            content = stripAccidentalNullPrefix(contentBuilder.toString()),
            reasoning = reasoningBuilder.toString(),
            completed = sawDone && parseError == null && transportError == null,
            sawDone = sawDone,
            parseError = parseError,
            transportError = transportError
        )
    }

    /**
     * Android JSONObject.optString returns the literal "null" when the JSON value is null.
     * DeepSeek reasoning streams often emit many content=null chunks before real text arrives.
     */
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

    private fun logPromptCacheUsage(ctx: Context, requestTag: String, usage: JSONObject) {
        val hit = usage.optLong("prompt_cache_hit_tokens", -1)
        val miss = usage.optLong("prompt_cache_miss_tokens", -1)
        val promptTokens = usage.optLong("prompt_tokens", -1)
        val cachedTokens = usage.optLong("cached_tokens", -1).takeIf { it >= 0 }
            ?: usage.optJSONObject("prompt_tokens_details")
                ?.optLong("cached_tokens", -1)
            ?: -1
        if (hit >= 0 || miss >= 0 || cachedTokens >= 0) {
            Logger.d(
                ctx,
                AI_CACHE_LOG_TAG,
                "tag=$requestTag, prompt_cache_hit=$hit, prompt_cache_miss=$miss, prompt_tokens=$promptTokens, cached_tokens=$cachedTokens"
            )
        }
    }

    private fun extractReasoningDelta(deltaObj: JSONObject?): String {
        if (deltaObj == null) return ""
        val candidates = listOf(
            jsonOptStringOrEmpty(deltaObj, "reasoning_content"),
            jsonOptStringOrEmpty(deltaObj, "reasoning"),
            jsonOptStringOrEmpty(deltaObj, "thinking")
        )
        return candidates.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private suspend fun requestAccountingContentStreamed(
        ctx: Context,
        apiKey: String,
        requestJson: com.google.gson.JsonObject,
        onProgress: ((String) -> Unit)?,
        emitTextDelta: Boolean = false,
        logReasoning: Boolean = false,
        reasoningLogTag: String = "AIService"
    ): String {
        return try {
            val streamed = requestChatContentStreamedWithReasoning(
                ctx = ctx,
                apiKey = apiKey,
                requestJson = requestJson,
                logReasoning = logReasoning,
                reasoningLogTag = reasoningLogTag,
                onContentDelta = if (emitTextDelta) { delta -> onProgress?.invoke("AI_STREAM_TEXT::$delta") } else null,
                onProgressChars = if (emitTextDelta) null else { currentLen -> onProgress?.invoke("正在整理账单...（已接收 $currentLen 字）") }
            )
            if (!streamed.completed) {
                val reason = streamed.parseError ?: streamed.transportError ?: IllegalStateException("SSE stream ended before [DONE]")
                throw reason
            }
            streamed.content
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Logger.d(ctx, "AIService", "Accounting stream failed, fallback raw request, err=${e.javaClass.simpleName}")
            val response = getApi(ctx).chatRaw(
                "Bearer $apiKey",
                adaptChatRequestForProvider(Prefs.getAiProvider(ctx), requestJson)
            )
            response.choices.firstOrNull()?.message?.content
                ?: throw IllegalStateException("API returned empty choices in fallback path")
        }
    }

    private fun buildRawRequest(request: ChatRequest): com.google.gson.JsonObject {
        val gson = com.google.gson.GsonBuilder()
            .registerTypeAdapter(MessageUnion::class.java, MessageUnionSerializer())
            .create()
        return gson.fromJson(gson.toJson(request), com.google.gson.JsonObject::class.java)
    }
}
